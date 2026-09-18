"use strict";

const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const { defineSecret } = require("firebase-functions/params");
const logger = require("firebase-functions/logger");
const admin = require("firebase-admin");
const { Resend } = require("resend");

admin.initializeApp();

// Resend API key — stored in Google Secret Manager, never in code.
// Set it with:  firebase functions:secrets:set RESEND_API_KEY
const RESEND_API_KEY = defineSecret("RESEND_API_KEY");

// ----- Config -----
const FROM = "RESCUE <sales@fliptle.com>";
const REPLY_TO = "sales@fliptle.com";
// Early-access / offer page lives at the site's #access route.
const OFFER_BASE = "https://fliptle.com/";

function offerLink(email) {
  return `${OFFER_BASE}?email=${encodeURIComponent(email)}#access`;
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
    region: "us-central1",
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

    const link = offerLink(email);
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
