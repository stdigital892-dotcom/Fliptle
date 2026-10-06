"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const F = require("./fakes");
const subscription = require("../subscription");

const DAY = 24 * 60 * 60 * 1000;
const NOW = Date.UTC(2026, 9, 6, 12, 0, 0); // 2026-10-06 12:00 UTC

function fv() {
  let seq = 1;
  return {
    // Mirrors the real admin.firestore.Timestamp shape (has .toMillis()) so
    // subscription.toMs() is exercised the same way it is in production.
    timestampFromMs: (ms) => ({ toMillis: () => ms }),
    serverTimestamp: () => "SERVER_TS_" + seq++,
  };
}

function env(initial = {}) {
  const fs = F.makeFirestore(initial);
  return { ...fs, deps: { db: fs.db, fv: fv() } };
}

function basePayment(overrides = {}) {
  return {
    email: "a@x.com",
    planId: "monthly",
    planName: "Monthly",
    amountRupees: 99,
    currency: "INR",
    paymentId: "pay_1",
    orderId: "order_1",
    source: "website",
    nowMs: NOW,
    ...overrides,
  };
}

// ---- periodMsFor / toMs ----------------------------------------------------

test("periodMsFor: monthly is 30 days, annual is 365 days, unknown is null", () => {
  assert.equal(subscription.periodMsFor("monthly"), 30 * DAY);
  assert.equal(subscription.periodMsFor("annual"), 365 * DAY);
  assert.equal(subscription.periodMsFor("weekly"), null);
  assert.equal(subscription.periodMsFor(undefined), null);
});

test("toMs reads numbers, Timestamp-like values, and null/undefined", () => {
  assert.equal(subscription.toMs(5), 5);
  assert.equal(subscription.toMs({ toMillis: () => 7 }), 7);
  assert.equal(subscription.toMs(null), null);
  assert.equal(subscription.toMs(undefined), null);
});

// ---- applyPayment: fresh plans ---------------------------------------------

test("monthly adds exactly 30 days from now for a brand new subscription", async () => {
  const e = env();
  const r = await subscription.applyPayment(e.deps, basePayment());
  assert.equal(r.applied, true);
  assert.equal(r.expiresAtMs, NOW + 30 * DAY);
  const doc = e.store.get("subscriptions/a@x.com");
  assert.equal(doc.expiresAt.toMillis(), NOW + 30 * DAY);
  assert.equal(doc.plan, "monthly");
  assert.equal(doc.status, "active");
  assert.equal(doc.lastPaymentId, "pay_1");
  assert.equal(doc.razorpayPaymentId, "pay_1");
  assert.equal(doc.source, "website");
});

test("annual adds exactly 365 days from now for a brand new subscription", async () => {
  const e = env();
  const r = await subscription.applyPayment(
    e.deps,
    basePayment({ planId: "annual", planName: "Annual", amountRupees: 799, paymentId: "pay_a" })
  );
  assert.equal(r.applied, true);
  assert.equal(r.expiresAtMs, NOW + 365 * DAY);
});

test("rejects an unknown plan rather than writing a doc with no period", async () => {
  const e = env();
  await assert.rejects(
    subscription.applyPayment(e.deps, basePayment({ planId: "weekly" })),
    /unknown plan/
  );
  assert.equal(e.store.has("subscriptions/a@x.com"), false);
});

// ---- applyPayment: early renewal extends, never shortens -------------------

test("early renewal with a NEW payment id extends from the old expiry, not from now", async () => {
  const e = env({
    "subscriptions/a@x.com": {
      email: "a@x.com", status: "active", lastPaymentId: "pay_1",
      expiresAt: { toMillis: () => NOW + 10 * DAY }, // 10 days still left
    },
  });
  const r = await subscription.applyPayment(e.deps, basePayment({ paymentId: "pay_2" }));
  assert.equal(r.applied, true);
  // 10 days left + a fresh 30-day month = 40 days from now, not 30.
  assert.equal(r.expiresAtMs, NOW + 40 * DAY);
  assert.equal(e.store.get("subscriptions/a@x.com").lastPaymentId, "pay_2");
});

test("renewing AFTER expiry starts the new period from now, not from the lapsed date", async () => {
  const e = env({
    "subscriptions/a@x.com": {
      email: "a@x.com", status: "active", lastPaymentId: "pay_1",
      expiresAt: { toMillis: () => NOW - 5 * DAY }, // lapsed 5 days ago
    },
  });
  const r = await subscription.applyPayment(e.deps, basePayment({ paymentId: "pay_2" }));
  assert.equal(r.expiresAtMs, NOW + 30 * DAY);
});

test("a doc with no expiresAt at all (pre-period payment) is treated like a fresh start", async () => {
  const e = env({
    "subscriptions/a@x.com": { email: "a@x.com", status: "active", lastPaymentId: "pay_1" },
  });
  const r = await subscription.applyPayment(e.deps, basePayment({ paymentId: "pay_2" }));
  assert.equal(r.expiresAtMs, NOW + 30 * DAY);
});

// ---- applyPayment: idempotency ---------------------------------------------

test("the exact same payment id applied twice extends only once", async () => {
  const e = env();
  const first = await subscription.applyPayment(e.deps, basePayment());
  assert.equal(first.applied, true);
  assert.equal(first.expiresAtMs, NOW + 30 * DAY);

  // Same payment id, called again later (e.g. a retry) — must not stack.
  const second = await subscription.applyPayment(
    e.deps,
    basePayment({ nowMs: NOW + 5 * DAY })
  );
  assert.equal(second.applied, false);
  assert.equal(second.expiresAtMs, NOW + 30 * DAY, "unchanged — not extended a second time");
});

test("verifyPayment applying a payment, then the webhook retrying the SAME payment id, does not double-extend", async () => {
  const e = env();
  // verifyPayment path.
  const viaVerify = await subscription.applyPayment(e.deps, basePayment({ source: "website" }));
  assert.equal(viaVerify.expiresAtMs, NOW + 30 * DAY);

  // webhook path, same payment id, arrives slightly later.
  const viaWebhook = await subscription.applyPayment(
    e.deps,
    basePayment({ source: "webhook", nowMs: NOW + DAY })
  );
  assert.equal(viaWebhook.applied, false);
  assert.equal(viaWebhook.expiresAtMs, NOW + 30 * DAY);
  // First writer's source wins; the doc isn't re-attributed to the webhook.
  assert.equal(e.store.get("subscriptions/a@x.com").source, "website");
});

test("webhook applying a NEW payment after verifyPayment applied an earlier one still extends once per id", async () => {
  const e = env();
  await subscription.applyPayment(e.deps, basePayment({ source: "website", paymentId: "pay_1" }));
  // A second, later payment (different id) — e.g. next month's renewal —
  // must extend again exactly once, not be mistaken for a duplicate.
  const r = await subscription.applyPayment(
    e.deps,
    basePayment({ source: "webhook", paymentId: "pay_2", nowMs: NOW + 20 * DAY })
  );
  assert.equal(r.applied, true);
  assert.equal(r.expiresAtMs, NOW + 30 * DAY + 30 * DAY, "10 days left + a fresh period");
  // Duplicate of pay_2 must not extend a third time.
  const dup = await subscription.applyPayment(
    e.deps,
    basePayment({ source: "webhook", paymentId: "pay_2", nowMs: NOW + 25 * DAY })
  );
  assert.equal(dup.applied, false);
  assert.equal(dup.expiresAtMs, NOW + 60 * DAY);
});

// ---- applyPayment: field refresh on duplicate ------------------------------

test("a duplicate payment id still refreshes non-expiry fields (amount, order id)", async () => {
  const e = env();
  await subscription.applyPayment(e.deps, basePayment());
  await subscription.applyPayment(
    e.deps,
    basePayment({ orderId: "order_corrected", amountRupees: 99 })
  );
  const doc = e.store.get("subscriptions/a@x.com");
  assert.equal(doc.razorpayOrderId, "order_corrected");
});

// ---- isPaidActive: the getEntitlement "paid" boundary ----------------------

const GRACE = 3 * DAY;

test("entitled while now is before expiresAt", () => {
  const sub = { status: "active", expiresAt: { toMillis: () => NOW + 10 * DAY } };
  const r = subscription.isPaidActive(sub, NOW, GRACE);
  assert.equal(r.entitled, true);
  assert.equal(r.expiresAtMs, NOW + 10 * DAY);
});

test("entitled exactly at the expiry + grace boundary, not after it", () => {
  const sub = { status: "active", expiresAt: { toMillis: () => NOW } };
  assert.equal(subscription.isPaidActive(sub, NOW + GRACE, GRACE).entitled, true, "exactly at the boundary");
  assert.equal(subscription.isPaidActive(sub, NOW + GRACE + 1, GRACE).entitled, false, "one ms past it");
});

test("entitled exactly at expiresAt itself (grace has not started yet)", () => {
  const sub = { status: "active", expiresAt: { toMillis: () => NOW } };
  assert.equal(subscription.isPaidActive(sub, NOW, GRACE).entitled, true);
});

test("a doc with no expiresAt at all is never entitled, regardless of grace", () => {
  const sub = { status: "active" }; // predates prepaid periods
  const r = subscription.isPaidActive(sub, NOW, GRACE);
  assert.equal(r.entitled, false);
  assert.equal(r.expiresAtMs, null);
});
