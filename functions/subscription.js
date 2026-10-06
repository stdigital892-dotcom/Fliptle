"use strict";

// Shared subscription-extension logic for verifyPayment and razorpayWebhook,
// so a captured payment is applied the same way and exactly once no matter
// which of the two paths processes it first (verifyPayment is the fast path;
// razorpayWebhook is the server-to-server safety net — see index.js).
//
// subscriptions/{email} fields this module owns:
//   status, plan, planName, amount, currency, expiresAt, lastPaymentId,
//   razorpayPaymentId, razorpayOrderId, startedAt, updatedAt, source.
//
// Prepaid periods, not recurring billing: paying adds a fixed period to
// whichever is later — now, or the current expiresAt if it's still in the
// future. Paying early extends the period; it can never shorten or reset it.
//
// Idempotency: lastPaymentId is the Razorpay payment id that was last
// APPLIED (i.e. actually extended expiresAt). If the exact same payment id
// is seen again — verifyPayment runs, then the webhook fires for the same
// payment, or the webhook retries itself — the write still happens (so
// razorpayPaymentId/orderId/amount stay current) but expiresAt is left
// untouched. All of this runs inside one transaction so two callers racing
// on the same payment can't both win.

const PERIOD_DAYS = Object.freeze({ monthly: 30, annual: 365 });
const DAY_MS = 24 * 60 * 60 * 1000;

/** The period, in ms, a payment for this plan adds. null for an unknown plan. */
function periodMsFor(planId) {
  const days = PERIOD_DAYS[planId];
  return typeof days === "number" ? days * DAY_MS : null;
}

/** A millis value, whether stored as a Firestore Timestamp or a plain number. */
function toMs(value) {
  if (value == null) return null;
  if (typeof value === "number") return value;
  if (typeof value.toMillis === "function") return value.toMillis();
  return null;
}

/**
 * Apply one captured payment to subscriptions/{email}.
 *
 * params: { email, planId, planName, amountRupees, currency, paymentId,
 *           orderId, source, nowMs }
 *
 * Returns { applied, expiresAtMs }. `applied` is false when this exact
 * paymentId had already been applied earlier — expiresAt is unchanged, but
 * the non-expiry fields (amount/order id/etc.) are still refreshed to match
 * whichever call is processing it now.
 *
 * Throws if planId isn't a known paid plan — callers must validate the plan
 * before calling this (see PAID_PLANS in index.js).
 */
async function applyPayment(deps, params) {
  const { db, fv } = deps;
  const {
    email, planId, planName, amountRupees, currency,
    paymentId, orderId, source, nowMs,
  } = params;

  const periodMs = periodMsFor(planId);
  if (!periodMs) throw new Error("applyPayment: unknown plan " + planId);

  const ref = db.doc("subscriptions/" + email);

  return db.runTransaction(async (txn) => {
    const snap = await txn.get(ref);
    const existing = snap.exists ? (snap.data() || {}) : null;

    const alreadyApplied = !!(existing && existing.lastPaymentId === paymentId);

    let expiresAtMs;
    if (alreadyApplied) {
      // Same payment seen again (e.g. webhook after verifyPayment already
      // applied it). Keep the expiry exactly as this payment already set it.
      const cur = toMs(existing.expiresAt);
      expiresAtMs = cur != null ? cur : nowMs;
    } else {
      // A genuinely new payment: extend from whichever is later — now, or
      // the existing expiry if it hasn't lapsed yet. Never shortens it.
      const existingExpMs = existing ? toMs(existing.expiresAt) : null;
      const base = existingExpMs != null && existingExpMs > nowMs ? existingExpMs : nowMs;
      expiresAtMs = base + periodMs;
    }

    txn.set(
      ref,
      {
        email,
        plan: planId,
        planName,
        amount: amountRupees,
        currency,
        status: "active",
        expiresAt: fv.timestampFromMs(expiresAtMs),
        lastPaymentId: alreadyApplied ? existing.lastPaymentId : paymentId,
        razorpayPaymentId: paymentId,
        razorpayOrderId: orderId,
        startedAt: (existing && existing.startedAt) || fv.serverTimestamp(),
        updatedAt: fv.serverTimestamp(),
        source: (existing && existing.source) || source,
      },
      { merge: true }
    );

    return { applied: !alreadyApplied, expiresAtMs };
  });
}

/**
 * Whether an "active" subscriptions/{email} doc currently grants paid
 * entitlement, given the server clock and the grace window. Pulled out of
 * getEntitlement (index.js) so the expiry/grace boundary and the
 * no-expiresAt-at-all case can be unit-tested without a live Firestore.
 *
 * A doc with no expiresAt at all (a payment recorded before prepaid periods
 * existed) is NOT entitled here — it falls through to "expired" in
 * getEntitlement, never indefinite access.
 *
 * sub: the doc's data, already known to have status === "active".
 * Returns { entitled, expiresAtMs }. expiresAtMs is null only when the doc
 * has no expiresAt field at all.
 */
function isPaidActive(sub, nowMs, graceMs) {
  const expMs = toMs(sub && sub.expiresAt);
  if (expMs != null && nowMs <= expMs + graceMs) {
    return { entitled: true, expiresAtMs: expMs };
  }
  return { entitled: false, expiresAtMs: expMs };
}

module.exports = { PERIOD_DAYS, periodMsFor, toMs, applyPayment, isPaidActive };
