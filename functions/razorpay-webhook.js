"use strict";

// razorpayWebhook: server-to-server safety net for Razorpay's
// `payment.captured` event (independent of verifyPayment — see index.js).
// Pulled out of index.js, with dependencies injected, so it can be
// unit-tested with fakes — the same pattern whatsapp.js already uses for the
// WhatsApp webhook.
//
// Logging rule for this file: a rejection may log the Razorpay payment id and
// a short, fixed reason string ONLY. Never the email, phone, name, notes
// content, request body, tokens or secrets — Razorpay's `notes` are
// merchant-supplied content (we set them ourselves in createOrder, but this
// webhook must not assume that on every event — see the plan-rejection case
// below), not something safe to put in logs verbatim.

const crypto = require("crypto");
const subscription = require("./subscription");

/**
 * X-Razorpay-Signature is hex(HMAC-SHA256(rawBody, webhookSecret)) — unlike
 * Meta's header, there is no "sha256=" prefix. Constant-time compare.
 */
function verifySignature(rawBody, header, secret) {
  if (!secret || !Buffer.isBuffer(rawBody) || typeof header !== "string" || !header) {
    return false;
  }
  const expected = crypto.createHmac("sha256", secret).update(rawBody).digest("hex");
  const expectedBuf = Buffer.from(expected, "utf8");
  const gotBuf = Buffer.from(header, "utf8");
  return expectedBuf.length === gotBuf.length && crypto.timingSafeEqual(expectedBuf, gotBuf);
}

/**
 * The whole endpoint.
 *
 * deps: { db, fv, logger, webhookSecret(), paidPlans, emailRe, nowMs }
 *   - paidPlans: the PAID_PLANS catalog, { monthly: {name, amountPaise,
 *     currency}, annual: {...} }.
 *   - emailRe: validates a resolved email address.
 *   - nowMs: the server clock, read once by the caller for this request.
 */
async function handleRequest(req, res, deps) {
  const { logger } = deps;

  if (req.method !== "POST") {
    res.status(405).send("Method not allowed");
    return;
  }

  const signature = req.get("x-razorpay-signature");
  if (!signature) {
    logger.warn("Razorpay webhook: missing X-Razorpay-Signature header");
    res.status(400).send("Missing signature");
    return;
  }

  // req.rawBody is the exact bytes Firebase received, before JSON parsing —
  // required because Razorpay signs the raw payload, and a parsed-then-
  // re-serialized body is not guaranteed to match it byte-for-byte.
  if (!verifySignature(req.rawBody, signature, deps.webhookSecret())) {
    logger.error("Razorpay webhook: signature verification failed — rejecting");
    res.status(400).send("Invalid signature");
    return;
  }

  let event;
  try {
    event = JSON.parse(req.rawBody.toString("utf8"));
  } catch (_) {
    res.status(400).send("Bad request");
    return;
  }

  // Acknowledge every other event type so Razorpay doesn't keep retrying it.
  if (event.event !== "payment.captured") {
    res.status(200).send("ignored");
    return;
  }

  const payment = event.payload && event.payload.payment && event.payload.payment.entity;
  if (!payment || !payment.id) {
    logger.error("Razorpay webhook: payment.captured with no payment entity", {
      event: event.event,
    });
    res.status(400).send("Malformed payload");
    return;
  }

  const notes = payment.notes || {};
  const email = String(notes.email || payment.email || "").trim().toLowerCase();
  const planId = String(notes.plan || "").trim().toLowerCase();

  if (!deps.emailRe.test(email)) {
    logger.error("Razorpay webhook: payment.captured with no resolvable email", {
      paymentId: payment.id,
    });
    res.status(400).send("No email on payment");
    return;
  }

  const plan = deps.paidPlans[planId];
  if (!plan) {
    // Can't compute a prepaid period without a known plan — writing a doc
    // with no expiry would be unsafe now that entitlement is expiry-based.
    // createOrder always sets notes.plan/notes.email together before an
    // order can exist (see index.js), so a legitimate payment from this
    // app always has a valid plan here; this branch is for anything else —
    // e.g. a payment made by some other means that doesn't share that
    // invariant. Log ONLY the payment id and a fixed reason: never planId
    // itself (that's notes content) or anything else from the payload.
    logger.error("Razorpay webhook: rejected: invalid plan", { paymentId: payment.id });
    res.status(400).send("Unknown or missing plan");
    return;
  }

  // Shared with verifyPayment so the two paths can never disagree on
  // expiresAt — see subscription.js. Idempotent: if verifyPayment already
  // applied this exact payment id, this call still refreshes the doc's
  // other fields but does not extend expiresAt a second time.
  try {
    const result = await subscription.applyPayment(
      { db: deps.db, fv: deps.fv },
      {
        email,
        planId,
        planName: plan.name,
        amountRupees: typeof payment.amount === "number" ? payment.amount / 100 : plan.amountPaise / 100,
        currency: payment.currency || plan.currency || "INR",
        paymentId: payment.id,
        orderId: payment.order_id || null,
        source: "webhook",
        nowMs: deps.nowMs,
      }
    );

    logger.info("Razorpay webhook: subscription confirmed", {
      email, paymentId: payment.id, applied: result.applied,
    });
    res.status(200).send("ok");
  } catch (e) {
    logger.error("Razorpay webhook: Firestore write failed", { email, message: e && e.message });
    res.status(500).send("internal error");
  }
}

module.exports = { verifySignature, handleRequest };
