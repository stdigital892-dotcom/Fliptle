"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const crypto = require("crypto");
const F = require("./fakes");
const webhook = require("../razorpay-webhook");

const SECRET = "whsec_test_secret";
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const PAID_PLANS = {
  monthly: { name: "Monthly", amountPaise: 9900,  currency: "INR" },
  annual:  { name: "Annual",  amountPaise: 79900, currency: "INR" },
};
const DAY = 24 * 60 * 60 * 1000;
const NOW = Date.UTC(2026, 9, 6, 12, 0, 0);

/** Razorpay's header has no "sha256=" prefix, unlike Meta's. */
function sign(rawBody, secret) {
  return crypto.createHmac("sha256", secret).update(rawBody).digest("hex");
}

function fv() {
  let seq = 1;
  return {
    timestampFromMs: (ms) => ({ toMillis: () => ms }),
    serverTimestamp: () => "SERVER_TS_" + seq++,
  };
}

function env(initial = {}) {
  const fs = F.makeFirestore(initial);
  const logger = F.makeLogger();
  const deps = {
    db: fs.db,
    fv: fv(),
    logger,
    webhookSecret: () => SECRET,
    paidPlans: PAID_PLANS,
    emailRe: EMAIL_RE,
    nowMs: NOW,
  };
  return { ...fs, logger, deps };
}

function capturedEvent(entityOverrides = {}) {
  return {
    event: "payment.captured",
    payload: {
      payment: {
        entity: {
          id: "pay_1",
          amount: 9900,
          currency: "INR",
          email: "razorpay-fallback@example.com", // must never leak into a rejection log
          order_id: "order_1",
          notes: { email: "a@x.com", plan: "monthly" },
          ...entityOverrides,
        },
      },
    },
  };
}

function post(body, { secret = SECRET, signatureOverride } = {}) {
  const rawBody = F.raw(body);
  const signature = signatureOverride !== undefined ? signatureOverride : sign(rawBody, secret);
  const headers = {};
  if (signature !== null) headers["x-razorpay-signature"] = signature;
  return F.makeReq({ method: "POST", rawBody, headers });
}

// ---- happy path, for contrast -----------------------------------------------

test("a valid captured payment with a known plan is applied and returns 200", async () => {
  const e = env();
  const res = F.makeRes();
  await webhook.handleRequest(post(capturedEvent()), res, e.deps);
  assert.equal(res.statusCode, 200);
  const doc = e.store.get("subscriptions/a@x.com");
  assert.equal(doc.plan, "monthly");
  assert.equal(doc.expiresAt.toMillis(), NOW + 30 * DAY);
});

// ---- the rejected-plan case -------------------------------------------------

test("rejects a captured payment with an unknown plan, writing nothing", async () => {
  const e = env();
  const res = F.makeRes();
  const body = capturedEvent({ notes: { email: "a@x.com", plan: "weekly" } });
  await webhook.handleRequest(post(body), res, e.deps);
  assert.equal(res.statusCode, 400);
  assert.equal(e.store.has("subscriptions/a@x.com"), false);
});

test("rejects a captured payment with no plan in notes at all, writing nothing", async () => {
  const e = env();
  const res = F.makeRes();
  const body = capturedEvent({ notes: { email: "a@x.com" } }); // no plan field
  await webhook.handleRequest(post(body), res, e.deps);
  assert.equal(res.statusCode, 400);
  assert.equal(e.store.has("subscriptions/a@x.com"), false);
});

test("the rejected-plan log line contains ONLY the payment id and the fixed reason — no PII", async () => {
  const e = env();
  const res = F.makeRes();
  const body = capturedEvent({
    notes: { email: "a@x.com", plan: "weekly" },
    email: "razorpay-fallback@example.com",
  });
  await webhook.handleRequest(post(body), res, e.deps);

  const rejectLines = e.logger.lines.filter((l) => l.includes("rejected: invalid plan"));
  assert.equal(rejectLines.length, 1, "exactly one rejection log line");
  const line = rejectLines[0];

  // Must identify the payment...
  assert.match(line, /pay_1/);
  // ...and must not contain any of: the email (either source), the invalid
  // plan value itself (that's notes content), the order id, the webhook
  // secret, or a serialized request body/notes object.
  const forbidden = [
    "a@x.com",
    "razorpay-fallback@example.com",
    "weekly",
    "order_1",
    SECRET,
    "notes",
  ];
  for (const value of forbidden) {
    assert.equal(line.includes(value), false, `log line must not contain "${value}": ${line}`);
  }
});

test("an unresolvable plan from a non-Razorpay-created payment is still rejected the same way", async () => {
  // Simulates a payment that didn't originate from this app's createOrder
  // (e.g. a manual Razorpay payment link) and so never had notes.plan set.
  const e = env();
  const res = F.makeRes();
  const body = capturedEvent({ notes: {}, email: "someone@elsewhere.com" });
  await webhook.handleRequest(post(body), res, e.deps);
  assert.equal(res.statusCode, 400);
  assert.equal(e.store.has("subscriptions/someone@elsewhere.com"), false);
});

// ---- other guards, unchanged behaviour --------------------------------------

test("rejects a bad signature before even looking at the plan", async () => {
  const e = env();
  const res = F.makeRes();
  await webhook.handleRequest(post(capturedEvent(), { secret: "wrong" }), res, e.deps);
  assert.equal(res.statusCode, 400);
  assert.equal(e.store.has("subscriptions/a@x.com"), false);
});

test("rejects a missing signature header", async () => {
  const e = env();
  const res = F.makeRes();
  await webhook.handleRequest(post(capturedEvent(), { signatureOverride: null }), res, e.deps);
  assert.equal(res.statusCode, 400);
});

test("non-POST methods are rejected", async () => {
  const e = env();
  const res = F.makeRes();
  const req = F.makeReq({ method: "GET" });
  await webhook.handleRequest(req, res, e.deps);
  assert.equal(res.statusCode, 405);
});

test("event types other than payment.captured are acknowledged and ignored", async () => {
  const e = env();
  const res = F.makeRes();
  const body = { event: "payment.failed", payload: {} };
  await webhook.handleRequest(post(body), res, e.deps);
  assert.equal(res.statusCode, 200);
  assert.equal(res.body, "ignored");
});

test("a captured payment with no resolvable email is rejected without writing anything", async () => {
  const e = env();
  const res = F.makeRes();
  const body = capturedEvent({ notes: { plan: "monthly" }, email: "" });
  await webhook.handleRequest(post(body), res, e.deps);
  assert.equal(res.statusCode, 400);
});

// ---- verifySignature (pure helper) ------------------------------------------

test("verifySignature: correct HMAC matches, anything else does not", () => {
  const raw = Buffer.from('{"a":1}', "utf8");
  const good = sign(raw, SECRET);
  assert.equal(webhook.verifySignature(raw, good, SECRET), true);
  assert.equal(webhook.verifySignature(raw, good, "other-secret"), false);
  assert.equal(webhook.verifySignature(raw, "not-hex", SECRET), false);
  assert.equal(webhook.verifySignature(raw, "", SECRET), false);
  assert.equal(webhook.verifySignature(raw, good, ""), false);
});
