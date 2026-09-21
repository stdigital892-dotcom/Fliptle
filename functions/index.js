"use strict";

const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { defineSecret } = require("firebase-functions/params");
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
const RESEND_API_KEY = defineSecret("RESEND_API_KEY");
const RAZORPAY_KEY_ID = defineSecret("RAZORPAY_KEY_ID");
const RAZORPAY_KEY_SECRET = defineSecret("RAZORPAY_KEY_SECRET");

// Server-side plan catalog. Amounts are authoritative here; the client never
// gets to say what a plan costs — that's the whole point of server-side order
// creation + HMAC verification.
const PAID_PLANS = {
  monthly: { name: "Monthly", amountPaise: 9900,  currency: "INR" }, // ₹99
  annual:  { name: "Annual",  amountPaise: 79900, currency: "INR" }, // ₹799
};

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const RAZORPAY_REGION = "asia-south2"; // must match Firestore region

// ----- Config -----
const FROM = "RESCUE <sales@fliptle.com>";
const REPLY_TO = "sales@fliptle.com";
// Early-access / offer page.
const OFFER_BASE = "https://fliptle.com/offer";

function offerLink(email, source) {
  const src = source === "app" ? "app" : "web";
  return `${OFFER_BASE}?email=${encodeURIComponent(email)}&source=${src}`;
}

function buildHtml(link) {
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

function buildText(link) {
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

// Fires once for every new document created in the `waitlist` collection
// (the same collection the website writes signups to).
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

    const source = typeof data.source === "string" ? data.source : "";
    const link = offerLink(email, source);
    const resend = new Resend(RESEND_API_KEY.value());

    try {
      const { data: sent, error } = await resend.emails.send({
        from: FROM,
        to: [email],
        replyTo: REPLY_TO,
        subject: "You're in - your RESCUE early-access offer",
        html: buildHtml(link),
        text: buildText(link),
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
//   7. If it matches, the Admin SDK writes subscriptions/{email}
//      (this bypasses the Firestore rule that denies all client access to
//      subscriptions/*). If not, the whole request is refused — no write.
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
    // down (`allow read, write: if false`) to the client.
    await admin.firestore().doc("subscriptions/" + email).set({
      email,
      plan: planId,
      planName: plan.name,
      amount: plan.amountPaise / 100,  // rupees, matches WEBSITE_SETUP.md schema
      currency: plan.currency,
      status: "active",
      razorpayPaymentId: paymentId,
      razorpayOrderId: orderId,
      razorpaySignature: signature,
      startedAt: admin.firestore.FieldValue.serverTimestamp(),
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      source: "website",
    }, { merge: true });

    logger.info("Subscription activated", { email, plan: planId, paymentId });
    return { status: "active", plan: planId, planName: plan.name };
  }
);
