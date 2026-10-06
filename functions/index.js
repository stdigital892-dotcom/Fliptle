"use strict";

const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const { onCall, onRequest, HttpsError } = require("firebase-functions/v2/https");
const { onSchedule } = require("firebase-functions/v2/scheduler");
const { defineSecret, defineString, defineBoolean, defineInt } = require("firebase-functions/params");
const logger = require("firebase-functions/logger");
const admin = require("firebase-admin");
const { Resend } = require("resend");
const crypto = require("node:crypto");

admin.initializeApp();

// ---- Secrets (Google Secret Manager, never in code) -----------------------
// Resend welcome email:
//   firebase functions:secrets:set RESEND_API_KEY
// Razorpay Standard Checkout:
//   firebase functions:secrets:set RAZORPAY_KEY_ID
//   firebase functions:secrets:set RAZORPAY_KEY_SECRET
// Only RAZORPAY_KEY_ID is ever returned to the browser; RAZORPAY_KEY_SECRET
// is used exclusively by createOrder + verifyPayment on the server.
// Razorpay webhook (separate from RAZORPAY_KEY_SECRET — this is the signing
// secret Razorpay generates when you register the webhook in its dashboard):
//   firebase functions:secrets:set RAZORPAY_WEBHOOK_SECRET
const RESEND_API_KEY = defineSecret("RESEND_API_KEY");
const RAZORPAY_KEY_ID = defineSecret("RAZORPAY_KEY_ID");
const RAZORPAY_KEY_SECRET = defineSecret("RAZORPAY_KEY_SECRET");
const RAZORPAY_WEBHOOK_SECRET = defineSecret("RAZORPAY_WEBHOOK_SECRET");

// Server-side plan catalog. Amounts are authoritative here; the client never
// gets to say what a plan costs — that's the whole point of server-side order
// creation + HMAC verification.
const PAID_PLANS = {
  monthly: { name: "Monthly", amountPaise: 9900,  currency: "INR" }, // ₹99 / 30 days
  annual:  { name: "Annual",  amountPaise: 79900, currency: "INR" }, // ₹799 / 365 days
};

// Prepaid-period subscription logic shared by verifyPayment and
// razorpayWebhook, so a payment is applied identically and exactly once no
// matter which path processes it first. See subscription.js.
const subscription = require("./subscription");
const paymentFieldValues = {
  timestampFromMs: (ms) => admin.firestore.Timestamp.fromMillis(ms),
  serverTimestamp: () => admin.firestore.FieldValue.serverTimestamp(),
};

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const RAZORPAY_REGION = "asia-south2"; // must match Firestore region

// ----- Config -----
const FROM = "RESCUE <sales@fliptle.com>";
const REPLY_TO = "sales@fliptle.com";
// Early-access / offer page.
const OFFER_BASE = "https://fliptle.com/offer";

function offerLink(email, source, name) {
  const src = source === "app" ? "app" : "web";
  let url = `${OFFER_BASE}?email=${encodeURIComponent(email)}&source=${src}`;
  // Only appended when a display name is available (currently: app sign-ins
  // via Google, which populate FirebaseUser.displayName). The offer page
  // falls back to greeting by email when this is absent.
  if (name) url += `&name=${encodeURIComponent(name)}`;
  return url;
}

// ---- Template 1: WEBSITE waitlist signups (pre-launch, no app yet) --------
function buildWaitlistHtml(link) {
  return `<!DOCTYPE html>
<html>
  <body style="margin:0;background:#060608;font-family:Arial,Helvetica,sans-serif;color:#f5f5f7;">
    <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#060608;padding:32px 0;">
      <tr><td align="center">
        <table role="presentation" width="100%" style="max-width:520px;background:#0c0c11;border:1px solid #1c1c22;border-radius:16px;padding:36px;">
          <tr><td>
            <div style="font-size:26px;font-weight:800;letter-spacing:2px;color:#ffffff;">RESCUE<span style="color:#FF2233;">.</span></div>
            <h1 style="font-size:24px;line-height:1.25;margin:22px 0 10px;color:#ffffff;">You're in. Welcome to the rescue.</h1>
            <p style="font-size:15px;line-height:1.6;color:#c9c9d2;margin:0 0 24px;">
              You've decided to take your life back &mdash; that's the hardest step, and you just took it.
              As an early member, here's your first-access offer for the RESCUE app.
            </p>
            <a href="${link}" style="display:inline-block;background:#FF2233;color:#ffffff;text-decoration:none;font-weight:700;font-size:16px;padding:15px 34px;border-radius:12px;">
              See your early-access offer &rarr;
            </a>
            <p style="font-size:13px;line-height:1.6;color:#8a8a97;margin:26px 0 0;">
              Or paste this link into your browser:<br />
              <a href="${link}" style="color:#ff6a6a;word-break:break-all;">${link}</a>
            </p>
            <hr style="border:none;border-top:1px solid #1c1c22;margin:28px 0;" />
            <p style="font-size:12px;color:#6c6c78;margin:0;">
              You're receiving this because you joined the RESCUE waitlist at fliptle.com.
              Questions? Just reply to this email.
            </p>
          </td></tr>
        </table>
      </td></tr>
    </table>
  </body>
</html>`;
}

function buildWaitlistText(link) {
  return [
    "You're in. Welcome to the rescue.",
    "",
    "You've decided to take your life back - that's the hardest step, and you just took it.",
    "As an early member, here's your first-access offer for the RESCUE app:",
    "",
    link,
    "",
    "You're receiving this because you joined the RESCUE waitlist at fliptle.com.",
    "Questions? Just reply to this email.",
  ].join("\n");
}

// ---- Template 2: APP users (already have the app, choosing a plan) -------
// Never says "early access" — that phrase is reserved for website waitlist
// visitors who don't have the app yet. App users are past that stage.
function buildAppHtml(link) {
  return `<!DOCTYPE html>
<html>
  <body style="margin:0;background:#060608;font-family:Arial,Helvetica,sans-serif;color:#f5f5f7;">
    <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#060608;padding:32px 0;">
      <tr><td align="center">
        <table role="presentation" width="100%" style="max-width:520px;background:#0c0c11;border:1px solid #1c1c22;border-radius:16px;padding:36px;">
          <tr><td>
            <div style="font-size:26px;font-weight:800;letter-spacing:2px;color:#ffffff;">RESCUE<span style="color:#FF2233;">.</span></div>
            <h1 style="font-size:24px;line-height:1.25;margin:22px 0 10px;color:#ffffff;">Complete your setup.</h1>
            <p style="font-size:15px;line-height:1.6;color:#c9c9d2;margin:0 0 24px;">
              You've signed in to RESCUE — one step left. Choose your plan below and
              your protection activates the moment you do.
            </p>
            <a href="${link}" style="display:inline-block;background:#FF2233;color:#ffffff;text-decoration:none;font-weight:700;font-size:16px;padding:15px 34px;border-radius:12px;">
              Choose your plan &rarr;
            </a>
            <p style="font-size:13px;line-height:1.6;color:#8a8a97;margin:26px 0 0;">
              Or paste this link into your browser:<br />
              <a href="${link}" style="color:#ff6a6a;word-break:break-all;">${link}</a>
            </p>
            <hr style="border:none;border-top:1px solid #1c1c22;margin:28px 0;" />
            <p style="font-size:12px;color:#6c6c78;margin:0;">
              You're receiving this because you signed in to the RESCUE app.
              Questions? Just reply to this email.
            </p>
          </td></tr>
        </table>
      </td></tr>
    </table>
  </body>
</html>`;
}

function buildAppText(link) {
  return [
    "Complete your setup.",
    "",
    "You've signed in to RESCUE — one step left. Choose your plan below and",
    "your protection activates the moment you do:",
    "",
    link,
    "",
    "You're receiving this because you signed in to the RESCUE app.",
    "Questions? Just reply to this email.",
  ].join("\n");
}

// Fires once for every new document created in the `waitlist` collection —
// WEBSITE campaign signups only (pre-launch visitors with no app yet).
exports.sendWaitlistWelcome = onDocumentCreated(
  {
    document: "waitlist/{docId}",
    secrets: [RESEND_API_KEY],
    // Must exactly match the Firestore database's region (Firebase Console ->
    // Project settings -> General -> "Default GCP resource location", or
    // `gcloud firestore databases list`). This project's Firestore is in
    // asia-south2 (Delhi) — a Firestore trigger deployed to any other region
    // silently never fires, even though `firebase deploy` reports success.
    region: "asia-south2",
  },
  async (event) => {
    const snap = event.data;
    if (!snap) return;
    const data = snap.data() || {};

    // Idempotency: never send twice for the same signup.
    if (data.welcomeEmailSentAt) {
      logger.info("Welcome email already sent, skipping", { docId: event.params.docId });
      return;
    }

    const email = String(data.email || "").trim().toLowerCase();
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
      logger.warn("Invalid or missing email on waitlist doc", { docId: event.params.docId });
      return;
    }

    const link = offerLink(email, "web");
    const resend = new Resend(RESEND_API_KEY.value());

    try {
      const { data: sent, error } = await resend.emails.send({
        from: FROM,
        to: [email],
        replyTo: REPLY_TO,
        subject: "You're in - your RESCUE early-access offer",
        html: buildWaitlistHtml(link),
        text: buildWaitlistText(link),
      });

      if (error) {
        logger.error("Resend returned an error", { email, error });
        return;
      }

      await snap.ref.update({
        welcomeEmailSentAt: admin.firestore.FieldValue.serverTimestamp(),
        welcomeEmailId: sent && sent.id ? sent.id : null,
      });
      logger.info("Welcome email sent", { email, id: sent && sent.id });
    } catch (e) {
      logger.error("Failed to send welcome email", { email, message: e && e.message });
    }
  }
);

// Fires once for every new document created in the `appSignups` collection —
// APP users only (already signed in through the app, choosing a real plan).
// Completely separate from sendWaitlistWelcome above: different collection,
// different Cloud Function, different email copy. Nothing here ever says
// "early access".
exports.sendAppWelcomeEmail = onDocumentCreated(
  {
    document: "appSignups/{docId}",
    secrets: [RESEND_API_KEY],
    region: "asia-south2", // must match Firestore region — see note above
  },
  async (event) => {
    const snap = event.data;
    if (!snap) return;
    const data = snap.data() || {};

    // Idempotency: never send twice for the same sign-in.
    if (data.welcomeEmailSentAt) {
      logger.info("App welcome email already sent, skipping", { docId: event.params.docId });
      return;
    }

    const email = String(data.email || "").trim().toLowerCase();
    if (!EMAIL_RE.test(email)) {
      logger.warn("Invalid or missing email on appSignups doc", { docId: event.params.docId });
      return;
    }

    const displayName = typeof data.displayName === "string" ? data.displayName.trim() : "";
    const link = offerLink(email, "app", displayName);
    const resend = new Resend(RESEND_API_KEY.value());

    try {
      const { data: sent, error } = await resend.emails.send({
        from: FROM,
        to: [email],
        replyTo: REPLY_TO,
        subject: "Complete your setup — choose your RESCUE plan",
        html: buildAppHtml(link),
        text: buildAppText(link),
      });

      if (error) {
        logger.error("Resend returned an error (app welcome email)", { email, error });
        return;
      }

      await snap.ref.update({
        welcomeEmailSentAt: admin.firestore.FieldValue.serverTimestamp(),
        welcomeEmailId: sent && sent.id ? sent.id : null,
      });
      logger.info("App welcome email sent", { email, id: sent && sent.id });
    } catch (e) {
      logger.error("Failed to send app welcome email", { email, message: e && e.message });
    }
  }
);

// ============================================================================
// Razorpay Standard Checkout — createOrder + verifyPayment (both callable)
//
// Flow:
//   1. Client picks Monthly or Annual → calls createOrder({email, plan})
//   2. Server calls Razorpay Orders API using Basic Auth (Key ID + Key Secret)
//      and returns {orderId, amount, currency, keyId, planName} to the client.
//   3. Client opens Razorpay Checkout with those values.
//   4. On payment success, Razorpay calls the client handler with
//      {razorpay_payment_id, razorpay_order_id, razorpay_signature}.
//   5. Client forwards those + {email, plan} to verifyPayment.
//   6. Server recomputes HMAC-SHA256(orderId + "|" + paymentId, KEY_SECRET)
//      as hex and compares it with the signature in constant time.
//   7. If it matches, subscription.applyPayment() (Admin SDK, bypassing the
//      Firestore rule that denies all client access to subscriptions/*)
//      extends expiresAt by the plan's prepaid period — 30 days for Monthly,
//      365 for Annual — from whichever is later: now, or the existing
//      expiresAt if it hasn't lapsed. razorpayWebhook calls the same helper,
//      so the two paths can never disagree. If the signature doesn't match,
//      the whole request is refused — no write.
// ============================================================================

exports.createOrder = onCall(
  {
    secrets: [RAZORPAY_KEY_ID, RAZORPAY_KEY_SECRET],
    region: RAZORPAY_REGION,
    cors: true,
  },
  async (request) => {
    const data = request.data || {};
    const email = String(data.email || "").trim().toLowerCase();
    const planId = String(data.plan || "").toLowerCase();

    if (!EMAIL_RE.test(email)) {
      throw new HttpsError("invalid-argument", "A valid email is required.");
    }
    const plan = PAID_PLANS[planId];
    if (!plan) {
      throw new HttpsError("invalid-argument", "Unknown plan.");
    }

    const keyId = RAZORPAY_KEY_ID.value();
    const keySecret = RAZORPAY_KEY_SECRET.value();
    if (!keyId || !keySecret) {
      logger.error("Razorpay secrets not configured");
      throw new HttpsError("failed-precondition", "Payments not configured.");
    }

    // Razorpay receipt is capped at 40 chars.
    const receipt =
      `sub_${planId}_${Date.now().toString(36)}_${crypto.randomBytes(3).toString("hex")}`
        .slice(0, 40);

    let res;
    try {
      res = await fetch("https://api.razorpay.com/v1/orders", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization:
            "Basic " + Buffer.from(keyId + ":" + keySecret).toString("base64"),
        },
        body: JSON.stringify({
          amount: plan.amountPaise,
          currency: plan.currency,
          receipt,
          notes: { email, plan: planId },
        }),
      });
    } catch (e) {
      logger.error("Razorpay orders network error", { message: e && e.message });
      throw new HttpsError("unavailable", "Could not reach payment provider.");
    }

    const body = await res.json().catch(() => ({}));
    if (!res.ok) {
      logger.error("Razorpay orders create failed", { status: res.status, body });
      throw new HttpsError("internal", "Could not create payment order.");
    }

    logger.info("Razorpay order created", { orderId: body.id, email, plan: planId });
    return {
      orderId: body.id,
      amount: body.amount,
      currency: body.currency,
      keyId,        // safe to expose — this is the public Razorpay Key ID
      planName: plan.name,
      planId,
    };
  }
);

exports.verifyPayment = onCall(
  {
    secrets: [RAZORPAY_KEY_ID, RAZORPAY_KEY_SECRET],
    region: RAZORPAY_REGION,
    cors: true,
  },
  async (request) => {
    const data = request.data || {};
    const orderId = String(data.razorpay_order_id || "");
    const paymentId = String(data.razorpay_payment_id || "");
    const signature = String(data.razorpay_signature || "");
    const email = String(data.email || "").trim().toLowerCase();
    const planId = String(data.plan || "").toLowerCase();

    if (!orderId || !paymentId || !signature) {
      throw new HttpsError("invalid-argument", "Missing payment fields.");
    }
    if (!EMAIL_RE.test(email)) {
      throw new HttpsError("invalid-argument", "A valid email is required.");
    }
    const plan = PAID_PLANS[planId];
    if (!plan) {
      throw new HttpsError("invalid-argument", "Unknown plan.");
    }

    const keySecret = RAZORPAY_KEY_SECRET.value();
    if (!keySecret) {
      logger.error("Razorpay secret not configured");
      throw new HttpsError("failed-precondition", "Payments not configured.");
    }

    const expected = crypto
      .createHmac("sha256", keySecret)
      .update(orderId + "|" + paymentId)
      .digest("hex");

    // Constant-time compare so an attacker can't leak the signature via timing.
    const a = Buffer.from(expected, "utf8");
    const b = Buffer.from(signature, "utf8");
    const valid = a.length === b.length && crypto.timingSafeEqual(a, b);
    if (!valid) {
      logger.warn("Razorpay signature mismatch", { orderId, paymentId, email });
      throw new HttpsError("permission-denied", "Payment verification failed.");
    }

    // Admin SDK bypasses Firestore rules — `subscriptions` stays fully locked
    // down (`allow read, write: if false`) to the client. Shared with
    // razorpayWebhook so the two paths can never disagree on expiresAt.
    const result = await subscription.applyPayment(
      { db: admin.firestore(), fv: paymentFieldValues },
      {
        email,
        planId,
        planName: plan.name,
        amountRupees: plan.amountPaise / 100,  // rupees, matches WEBSITE_SETUP.md schema
        currency: plan.currency,
        paymentId,
        orderId,
        source: "website",
        nowMs: admin.firestore.Timestamp.now().toMillis(),
      }
    );

    logger.info("Subscription activated", {
      email, plan: planId, paymentId, applied: result.applied,
    });
    return {
      status: "active",
      plan: planId,
      planName: plan.name,
      expiresAt: result.expiresAtMs,
    };
  }
);

// ============================================================================
// getSubscription — lets the client check subscriptions/{email} without
// exposing the collection publicly. Purpose: never tell a user their payment
// failed when it actually succeeded. If verifyPayment errors or times out
// after Razorpay's success handler fires, the client calls this to see
// whether razorpayWebhook (or verifyPayment itself, transactionally) has
// already recorded the subscription.
//
// Enumeration guard: the caller must send at least one Razorpay id
// (payment or order) they just got from Razorpay's checkout handler. If it
// doesn't match what's stored on the doc, we return { subscribed: false }
// exactly as if there were no doc — so this callable can only confirm what
// the caller already helped pay for; it can't be used to scan emails.
// ============================================================================
exports.getSubscription = onCall(
  {
    region: RAZORPAY_REGION,
    cors: true,
  },
  async (request) => {
    const data = request.data || {};
    const email = String(data.email || "").trim().toLowerCase();
    const paymentId = String(data.razorpay_payment_id || "").trim();
    const orderId = String(data.razorpay_order_id || "").trim();

    if (!EMAIL_RE.test(email)) {
      throw new HttpsError("invalid-argument", "A valid email is required.");
    }
    if (!paymentId && !orderId) {
      throw new HttpsError(
        "invalid-argument",
        "razorpay_payment_id or razorpay_order_id is required."
      );
    }

    const snap = await admin.firestore().doc("subscriptions/" + email).get();
    if (!snap.exists) return { subscribed: false };

    const doc = snap.data() || {};
    // Only reveal the record to a caller who can prove they know a Razorpay
    // id that's actually on it. Anything else — silent "not subscribed".
    const matches =
      (paymentId && doc.razorpayPaymentId === paymentId) ||
      (orderId && doc.razorpayOrderId === orderId);
    if (!matches) return { subscribed: false };

    return {
      subscribed: doc.status === "active",
      status: doc.status || null,
      plan: doc.plan || null,
      planName: doc.planName || null,
    };
  }
);

// ============================================================================
// Account deletion, with a 72-hour delay.
//
//   requestAccountDeletion  — schedules it (always a fresh 72 hours; writes
//                             deletionScheduledFor on installs/{uid}) and revokes
//                             the user's refresh tokens; deletes nothing.
//   cancelAccountDeletion   — clears it, any time before it is due. The app calls
//                             it on every fresh sign-in. Both refuse once due.
//   executeScheduledDeletions (hourly) — deletes accounts whose time has come.
//
// The two callables read uid and email ONLY from the caller's ID token
// (request.auth); there is no request field that names another account.
// The logic lives in deletion.js (dependencies injected, unit-tested).
// ============================================================================
const deletion = require("./deletion");

const deletionPartnerHooks = {
  notifyPartnerOfDeletion: deletion.notifyPartnerOfDeletion,
  deletePartnerData: deletion.deletePartnerData,
};

function requireSignedInWithEmail(request) {
  if (!request.auth || !request.auth.token || !request.auth.token.email) {
    throw new HttpsError("unauthenticated", "Sign in required.");
  }
  const email = String(request.auth.token.email).trim().toLowerCase();
  if (!EMAIL_RE.test(email)) {
    throw new HttpsError("failed-precondition", "Auth token has no valid email.");
  }
  return { uid: request.auth.uid, email };
}

// Real Admin SDK values for the injected field helpers (see deletion.js).
const deletionFieldValues = {
  timestampFromMs: (ms) => admin.firestore.Timestamp.fromMillis(ms),
  serverTimestamp: () => admin.firestore.FieldValue.serverTimestamp(),
  deleteField: () => admin.firestore.FieldValue.delete(),
};

function deletionCallDeps() {
  return {
    db: admin.firestore(),
    auth: admin.auth(),
    logger,
    partner: deletionPartnerHooks,
    fv: deletionFieldValues,
  };
}

// A DeletionError carries a code and a message meant for the user.
function deletionHttpsError(e) {
  if (e instanceof deletion.DeletionError) return new HttpsError(e.code, e.message);
  return e;
}

exports.requestAccountDeletion = onCall(
  { region: RAZORPAY_REGION },
  async (request) => {
    const { uid } = requireSignedInWithEmail(request);
    const nowMs = admin.firestore.Timestamp.now().toMillis();
    try {
      // Always a fresh 72 hours, then every refresh token is revoked.
      const { scheduledForMs } = await deletion.requestDeletion(deletionCallDeps(), uid, nowMs);
      logger.info("Account deletion scheduled", { uid, scheduledForMs });
      return { scheduledForMs };
    } catch (e) {
      throw deletionHttpsError(e);
    }
  }
);

exports.cancelAccountDeletion = onCall(
  { region: RAZORPAY_REGION },
  async (request) => {
    const { uid } = requireSignedInWithEmail(request);
    const nowMs = admin.firestore.Timestamp.now().toMillis();
    try {
      const result = await deletion.cancelDeletion(deletionCallDeps(), uid, nowMs);
      logger.info("Account deletion cancelled", { uid });
      return result;
    } catch (e) {
      throw deletionHttpsError(e);
    }
  }
);

// Hourly: ~720 invocations a month (free tier: 2M). The query returns only
// accounts that are due, so reads stay near one per run. A failure on one
// account is logged and retried next hour; it never blocks the others.
exports.executeScheduledDeletions = onSchedule(
  { schedule: "every 1 hours", region: RAZORPAY_REGION, timeoutSeconds: 300 },
  async () => {
    const db = admin.firestore();
    const deps = {
      db,
      auth: admin.auth(),
      logger,
      partner: deletionPartnerHooks,
    };
    const nowMs = admin.firestore.Timestamp.now().toMillis();

    const due = await db
      .collection("installs")
      .where("deletionScheduledFor", "<=", admin.firestore.Timestamp.fromMillis(nowMs))
      .limit(50)
      .get();

    for (const doc of due.docs) {
      try {
        await deletion.executeDeletion(deps, doc.id, nowMs);
      } catch (e) {
        logger.error("executeScheduledDeletions: failed, will retry next run", {
          uid: doc.id,
          message: e && e.message,
        });
      }
    }

    try {
      await deletion.sweepResurrected(deps, nowMs);
    } catch (e) {
      logger.error("executeScheduledDeletions: sweep failed", { message: e && e.message });
    }
  }
);

// ============================================================================
// getEntitlement — the paywall. Every protection feature in the app is gated
// on this returning entitled: true. Auth is REQUIRED; the email is pulled
// from the auth token and the client's email field (if any) is ignored,
// so a signed-in attacker can't check someone else's entitlement.
//
// Precedence (first match wins):
//   1. subscriptions/{email} status="active" and now <= expiresAt + 3-day
//      grace → "paid". A doc with no expiresAt at all (a payment recorded
//      before prepaid periods existed) is treated as expired, not granted
//      indefinite access — see subscription.js.
//   2. planSelections/{email} selectedPlan="trial" → "trial" (14 days from
//      trialStartedAt, set on the server the FIRST time we see this doc and
//      NEVER overwritten thereafter; 3-day grace after the 14-day mark)
//   3. Remote Config `testerMode` ON + any planSelections/{email} doc →
//      "tester". Flip testerMode OFF at public launch to turn this off in
//      one go without redeploying anything.
//   4. Otherwise not entitled. If the account had ever been paid or trialled
//      (record exists but is past grace), reason is "expired"; otherwise
//      "none".
//
// Never trusts the phone's clock — expiry math uses admin.firestore.
// Timestamp.now() on the server.
// ============================================================================
const GRACE_MS = 3 * 24 * 60 * 60 * 1000; // 3 days
const TRIAL_MS = 14 * 24 * 60 * 60 * 1000; // 14 days

async function readTesterMode() {
  // Remote Config: read the server template and evaluate defaults. If the
  // parameter is missing, absent, or the API call fails, treat as OFF
  // (safer default — a broken flag never widens access).
  try {
    const template = await admin.remoteConfig().getServerTemplate();
    const config = template.evaluate();
    return config.getBoolean("testerMode");
  } catch (e) {
    logger.warn("Remote Config read failed; treating testerMode as OFF", {
      message: e && e.message,
    });
    return false;
  }
}

exports.getEntitlement = onCall(
  { region: RAZORPAY_REGION },
  async (request) => {
    // Auth REQUIRED. The email comes from the ID token, never from the
    // client payload — a signed-in attacker can't probe another account.
    if (!request.auth || !request.auth.token || !request.auth.token.email) {
      throw new HttpsError("unauthenticated", "Sign in required.");
    }
    const email = String(request.auth.token.email).trim().toLowerCase();
    if (!EMAIL_RE.test(email)) {
      throw new HttpsError("failed-precondition", "Auth token has no valid email.");
    }

    const db = admin.firestore();
    const nowMs = admin.firestore.Timestamp.now().toMillis();

    // ---- 1. Paid subscription ----
    const subSnap = await db.doc("subscriptions/" + email).get();
    const sub = subSnap.exists ? (subSnap.data() || {}) : null;
    if (sub && sub.status === "active") {
      const paid = subscription.isPaidActive(sub, nowMs, GRACE_MS);
      if (paid.entitled) {
        return { entitled: true, reason: "paid", expiresAt: paid.expiresAtMs };
      }
      // Either past expiry + grace, or no expiresAt at all — a payment
      // recorded before this field existed. Both fall through and are
      // treated as "expired" below; neither grants indefinite access.
    }

    // ---- 2. Trial (14 days from server-stamped trialStartedAt) ----
    const planRef = db.doc("planSelections/" + email);
    const planSnap = await planRef.get();
    const plan = planSnap.exists ? (planSnap.data() || {}) : null;

    if (plan && plan.selectedPlan === "trial") {
      let trialStartedAt = plan.trialStartedAt;
      if (!trialStartedAt) {
        // First time we've seen this trial — stamp it now with the SERVER
        // clock. Immutable thereafter: we only ever set on merge if it's
        // absent (this branch), so a client can't reset it by re-writing
        // planSelections.
        await planRef.set(
          { trialStartedAt: admin.firestore.FieldValue.serverTimestamp() },
          { merge: true }
        );
        // Re-read so we return the actual stored value, not a client-side guess.
        const reread = await planRef.get();
        trialStartedAt = (reread.data() || {}).trialStartedAt;
      }
      if (trialStartedAt && trialStartedAt.toMillis) {
        const startMs = trialStartedAt.toMillis();
        const trialEndMs = startMs + TRIAL_MS;
        if (nowMs <= trialEndMs + GRACE_MS) {
          return { entitled: true, reason: "trial", expiresAt: trialEndMs };
        }
        // Trial past grace — fall through
      }
    }

    // ---- 3. Tester mode: ON + any planSelections doc → allow ----
    if (plan) {
      const testerMode = await readTesterMode();
      if (testerMode) {
        return { entitled: true, reason: "tester", expiresAt: null };
      }
    }

    // ---- 4. Not entitled ----
    // "expired" means they had an entitlement that ran out (subscription doc
    // or trial); "none" means they've never had one at all.
    const wasEntitledBefore =
      (sub && (sub.status === "active" || sub.status === "expired")) ||
      (plan && plan.selectedPlan === "trial");
    return {
      entitled: false,
      reason: wasEntitledBefore ? "expired" : "none",
      expiresAt: null,
    };
  }
);

// ============================================================================
// razorpayWebhook — server-to-server safety net for `payment.captured`.
//
// Independent of verifyPayment above: if the app crashes or loses connection
// right after a successful payment, before it can call verifyPayment itself,
// Razorpay still calls this webhook directly from its own servers, so
// subscriptions/{email} gets written regardless of what the client does next.
//
// Email/plan correlation: createOrder (above) already passes
// notes: { email, plan: planId } to the Razorpay Orders API, and Razorpay
// echoes those notes back onto the payment entity — that's what this reads.
// ============================================================================
exports.razorpayWebhook = onRequest(
  { secrets: [RAZORPAY_WEBHOOK_SECRET], region: RAZORPAY_REGION },
  async (req, res) => {
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
    const expected = crypto
      .createHmac("sha256", RAZORPAY_WEBHOOK_SECRET.value())
      .update(req.rawBody)
      .digest("hex");

    const expectedBuf = Buffer.from(expected, "utf8");
    const gotBuf = Buffer.from(signature, "utf8");
    const validSignature =
      expectedBuf.length === gotBuf.length && crypto.timingSafeEqual(expectedBuf, gotBuf);

    if (!validSignature) {
      logger.error("Razorpay webhook: signature verification failed — rejecting");
      res.status(400).send("Invalid signature");
      return;
    }

    const event = req.body || {};

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

    if (!EMAIL_RE.test(email)) {
      logger.error("Razorpay webhook: payment.captured with no resolvable email", {
        paymentId: payment.id,
      });
      res.status(400).send("No email on payment");
      return;
    }

    const plan = PAID_PLANS[planId];
    if (!plan) {
      // Can't compute a prepaid period without a known plan. Previously this
      // wrote a subscription doc with a null plan; now that entitlement is
      // expiry-based, that would be unsafe (no period to extend). Reject
      // instead, same as createOrder/verifyPayment do for an unknown plan.
      logger.error("Razorpay webhook: payment.captured with unknown/missing plan", {
        paymentId: payment.id,
        planId,
      });
      res.status(400).send("Unknown or missing plan");
      return;
    }

    // Shared with verifyPayment so the two paths can never disagree on
    // expiresAt — see subscription.js. Idempotent: if verifyPayment already
    // applied this exact payment id, this call still refreshes the doc's
    // other fields but does not extend expiresAt a second time.
    try {
      const result = await subscription.applyPayment(
        { db: admin.firestore(), fv: paymentFieldValues },
        {
          email,
          planId,
          planName: plan.name,
          amountRupees: typeof payment.amount === "number" ? payment.amount / 100 : plan.amountPaise / 100,
          currency: payment.currency || plan.currency || "INR",
          paymentId: payment.id,
          orderId: payment.order_id || null,
          source: "webhook",
          nowMs: admin.firestore.Timestamp.now().toMillis(),
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
);

// ============================================================================
// flagStaleProtection — daily server-side backstop for "protection has been
// down for an unusually long time", independent of any reinstall or sign-in
// event. Two independent, single-field queries (each auto-indexed by
// Firestore already — no composite index needed):
//
//   1. lastHeartbeatMs < now - STALE_MS -> the app has gone dark entirely
//      (OEM killed everything, including the periodic heartbeat itself, or
//      a genuine uninstall nobody ever came back from).
//   2. protectionActive == false, filtered in-memory to only those with a
//      RECENT heartbeat -> the app is still alive and checking in, but the
//      thing it's supposed to protect with (Accessibility/BlockingService)
//      is off. This is the more precise signal for an OEM battery killer
//      that leaves the process able to wake occasionally.
//
// Idempotent: only writes protectionDownFlaggedAt the first time an account
// is found in either state; Heartbeat.beat() (Android) clears it client-side
// the moment protection is confirmed back on. Never re-writes a flag that's
// already set, so an account stuck down for weeks costs one write, not one
// per day.
// ============================================================================
const STALE_MS = 48 * 60 * 60 * 1000; // 48h — longer than Heartbeat's 24h reinstall grace
const RECENT_MS = 24 * 60 * 60 * 1000; // freshness window for the protectionActive=false check

exports.flagStaleProtection = onSchedule(
  { schedule: "every 24 hours", region: RAZORPAY_REGION },
  async () => {
    const db = admin.firestore();
    const now = Date.now();

    // ---- 1. Gone dark entirely ----
    const darkSnap = await db
      .collection("installs")
      .where("lastHeartbeatMs", "<", now - STALE_MS)
      .get();

    let darkFlagged = 0;
    for (const doc of darkSnap.docs) {
      const data = doc.data() || {};
      if (data.protectionDownFlaggedAt) continue; // already flagged, skip
      await doc.ref.set(
        {
          protectionDownFlaggedAt: admin.firestore.FieldValue.serverTimestamp(),
          protectionDownReason: "no_heartbeat",
        },
        { merge: true }
      );
      darkFlagged++;
    }

    // ---- 2. Alive, but not protecting ----
    const offSnap = await db
      .collection("installs")
      .where("protectionActive", "==", false)
      .get();

    let offFlagged = 0;
    for (const doc of offSnap.docs) {
      const data = doc.data() || {};
      if (data.protectionDownFlaggedAt) continue; // already flagged, skip
      const lastBeat = typeof data.lastHeartbeatMs === "number" ? data.lastHeartbeatMs : 0;
      if (now - lastBeat > RECENT_MS) continue; // stale data — query 1 already covers it
      await doc.ref.set(
        {
          protectionDownFlaggedAt: admin.firestore.FieldValue.serverTimestamp(),
          protectionDownReason: "protection_off",
        },
        { merge: true }
      );
      offFlagged++;
    }

    logger.info("flagStaleProtection run complete", {
      darkChecked: darkSnap.size,
      darkFlagged,
      offChecked: offSnap.size,
      offFlagged,
    });
  }
);

// ============================================================================
// whatsappWebhook: WhatsApp Cloud API webhook (Meta calls this).
//   GET  Meta's verification handshake (WHATSAPP_VERIFY_TOKEN)
//   POST signed events (X-Hub-Signature-256, WHATSAPP_APP_SECRET): STOP /
//        STOP ALL / UNSUBSCRIBE opt-outs and sent/delivered/read/failed status
// Logic is in whatsapp.js (dependencies injected, unit-tested); numbers are
// canonicalised by phone.js. Setup: WHATSAPP_SETUP.md.
//
//   firebase functions:secrets:set WHATSAPP_VERIFY_TOKEN
//   firebase functions:secrets:set WHATSAPP_APP_SECRET
//   firebase deploy --only functions:whatsappWebhook
// ============================================================================
const whatsapp = require("./whatsapp");
const WHATSAPP_VERIFY_TOKEN = defineSecret("WHATSAPP_VERIFY_TOKEN");
const WHATSAPP_APP_SECRET = defineSecret("WHATSAPP_APP_SECRET");

const partner = require("./partner");
const partnerSender = require("./partner-sender");

exports.whatsappWebhook = onRequest(
  {
    region: RAZORPAY_REGION, // asia-south2, same as the other functions
    secrets: [WHATSAPP_VERIFY_TOKEN, WHATSAPP_APP_SECRET],
    invoker: "public", // Meta must be able to reach it; the signature is the gate
    cors: false,
    timeoutSeconds: 30,
  },
  (req, res) =>
    whatsapp.handleRequest(req, res, {
      db: admin.firestore(),
      verifyToken: () => WHATSAPP_VERIFY_TOKEN.value(),
      appSecret: () => WHATSAPP_APP_SECRET.value(),
      serverTimestamp: () => admin.firestore.FieldValue.serverTimestamp(),
      logger,
      fv: partnerFieldValues,
      // After a STOP is recorded, mirror it onto any user whose partnerContacts
      // number matches. The sender's isOptedOut check still consults the
      // authoritative whatsappOptOuts record; this only updates the client-visible
      // partnerLinks row so the Partner screen can show "Your partner asked not
      // to receive messages".
      onOptOut: (hookDeps, fromNumber) =>
        partner.markLinkStoppedFor(
          { db: hookDeps.db, logger: hookDeps.logger, fv: partnerFieldValues },
          fromNumber
        ),
    })
);

// ============================================================================
// Partner (accountability partner): callables + hourly sender.
//
// Collections:
//   partnerContacts/{uid}  server-only. Never read/written by clients.
//   partnerLinks/{uid}     client may READ their own row; writes server-only.
//
// Deploy:
//   firebase functions:config:set \
//     partner.whatsapp_phone_number_id=... \
//     partner.whatsapp_template_name=partner_alert_v2 \
//     partner.whatsapp_template_lang=en_US \
//     partner.alerts_dry_run=true \
//     partner.alerts_allowed_uids=
//   firebase functions:secrets:set WHATSAPP_ACCESS_TOKEN
//   firebase deploy --only functions:savePartner,functions:removePartner,functions:sendPartnerAlerts
//
// Firestore rules the operator must add (not in this repo):
//   match /partnerContacts/{uid} { allow read, write: if false; }
//   match /partnerLinks/{uid}    { allow read: if request.auth.uid == uid;
//                                   allow write: if false; }
// ============================================================================

const WHATSAPP_ACCESS_TOKEN = defineSecret("WHATSAPP_ACCESS_TOKEN");

// Shared helpers for partner.js / partner-sender.js.
const partnerFieldValues = {
  timestampFromMs: (ms) => admin.firestore.Timestamp.fromMillis(ms),
  serverTimestamp: () => admin.firestore.FieldValue.serverTimestamp(),
  deleteField: () => admin.firestore.FieldValue.delete(),
};

function partnerCallDeps() {
  return { db: admin.firestore(), logger, fv: partnerFieldValues };
}

function partnerHttpsError(e) {
  if (e instanceof partner.PartnerError) return new HttpsError(e.code, e.message);
  return e;
}

exports.savePartner = onCall(
  { region: RAZORPAY_REGION },
  async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Sign in required.");
    const uid = request.auth.uid;
    const nowMs = Date.now();
    try {
      const result = await partner.savePartner(partnerCallDeps(), uid, {
        partnerPhone: request.data && request.data.partnerPhone,
        note: request.data && request.data.note,
        userConfirmed: !!(request.data && request.data.userConfirmed),
      }, nowMs);
      logger.info("savePartner: ok", { uid });
      return result;
    } catch (e) {
      throw partnerHttpsError(e);
    }
  }
);

exports.removePartner = onCall(
  { region: RAZORPAY_REGION },
  async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Sign in required.");
    const uid = request.auth.uid;
    try {
      const result = await partner.removePartner(partnerCallDeps(), uid);
      logger.info("removePartner: ok", { uid });
      return result;
    } catch (e) {
      throw partnerHttpsError(e);
    }
  }
);

// Operator-tunable params. Firebase prompts for these on deploy; a .env.<project>
// file in functions/ supplies them non-interactively. Defaults keep this
// dormant: DRY RUN is on by default so a bad deploy sends nothing.
const PARTNER_WHATSAPP_PHONE_NUMBER_ID =
  defineString("WHATSAPP_PHONE_NUMBER_ID", { default: "" });
const PARTNER_WHATSAPP_TEMPLATE_NAME =
  defineString("WHATSAPP_TEMPLATE_NAME", { default: "partner_alert_v2" });
const PARTNER_WHATSAPP_TEMPLATE_LANG =
  defineString("WHATSAPP_TEMPLATE_LANG", { default: "en_US" });
const PARTNER_ALERTS_DRY_RUN = defineBoolean("PARTNER_ALERTS_DRY_RUN", { default: true });
const PARTNER_ALERTS_ALLOWED_UIDS =
  defineString("PARTNER_ALERTS_ALLOWED_UIDS", { default: "" });
const PARTNER_ALERTS_STALE_HOURS =
  defineInt("PARTNER_ALERTS_STALE_HOURS", { default: partnerSender.STALE_HOURS_DEFAULT });
const PARTNER_ALERTS_COOLDOWN_DAYS =
  defineInt("PARTNER_ALERTS_COOLDOWN_DAYS", { default: partnerSender.COOLDOWN_DAYS_DEFAULT });

function partnerConfig() {
  const list = String(PARTNER_ALERTS_ALLOWED_UIDS.value() || "")
    .split(",").map((s) => s.trim()).filter(Boolean);
  return {
    phoneNumberId: String(PARTNER_WHATSAPP_PHONE_NUMBER_ID.value() || ""),
    templateName: String(PARTNER_WHATSAPP_TEMPLATE_NAME.value() || "partner_alert_v2"),
    templateLang: String(PARTNER_WHATSAPP_TEMPLATE_LANG.value() || "en_US"),
    accessToken: WHATSAPP_ACCESS_TOKEN.value(),
    staleHours: Number(PARTNER_ALERTS_STALE_HOURS.value()) || partnerSender.STALE_HOURS_DEFAULT,
    cooldownDays: Number(PARTNER_ALERTS_COOLDOWN_DAYS.value()) || partnerSender.COOLDOWN_DAYS_DEFAULT,
    dryRun: PARTNER_ALERTS_DRY_RUN.value() !== false,
    allowedUids: new Set(list),
  };
}

exports.sendPartnerAlerts = onSchedule(
  {
    schedule: "every 1 hours",
    region: RAZORPAY_REGION,
    secrets: [WHATSAPP_ACCESS_TOKEN],
    timeoutSeconds: 540,
  },
  async () => {
    const cfg = partnerConfig();
    if (!cfg.phoneNumberId) {
      logger.warn("sendPartnerAlerts: no whatsapp_phone_number_id configured; skipping run");
      return;
    }
    if (!cfg.accessToken) {
      logger.warn("sendPartnerAlerts: no WHATSAPP_ACCESS_TOKEN secret; skipping run");
      return;
    }
    const deps = {
      db: admin.firestore(),
      logger,
      fv: partnerFieldValues,
      fetch: globalThis.fetch, // Node 20
      // Shared opt-out check from whatsapp.js. NEVER swap for a local copy:
      // bypassing this would send alerts to numbers that have replied STOP.
      isOptedOut: whatsapp.isOptedOut,
    };
    await partnerSender.runOnce(deps, Date.now(), cfg);
  }
);
