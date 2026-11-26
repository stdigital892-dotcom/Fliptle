"use strict";

// Plan-expiry renewal email: sent once automatically when getEntitlement
// first sees an account flip to "expired" for a given expiry, and available
// on demand via the sendExpiryRenewalEmail callable (rate-limited).
//
// Copy rule: this email may say "Continue on fliptle.com" or "Choose a
// plan" — never "Buy" or "Purchase". It only ever links to offer.html with
// source=expiry; it never claims the plan was charged or refunded.

const OFFER_BASE = "https://fliptle.com/offer";
const MAX_PER_DAY = 3;
const DAY_MS = 24 * 60 * 60 * 1000;
const SUBJECT = "Your Rescue plan has ended";

function expiryLink(email) {
  return `${OFFER_BASE}?email=${encodeURIComponent(email)}&source=expiry`;
}

/** 2026-10-05T05:00:00Z -> "5 Oct 2026" in IST (Asia/Kolkata, +05:30). */
function formatIstDate(ms) {
  if (!Number.isFinite(ms)) return "";
  const d = new Date(ms + 5.5 * 60 * 60 * 1000);
  const months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun",
                  "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
  return d.getUTCDate() + " " + months[d.getUTCMonth()] + " " + d.getUTCFullYear();
}

function buildHtml({ email, planName, expiresAtMs }) {
  const link = expiryLink(email);
  const plan = planName || "Rescue";
  const dateText = formatIstDate(expiresAtMs);
  return `<!DOCTYPE html>
<html>
  <body style="margin:0;background:#060608;font-family:Arial,Helvetica,sans-serif;color:#f5f5f7;">
    <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#060608;padding:32px 0;">
      <tr><td align="center">
        <table role="presentation" width="100%" style="max-width:520px;background:#0c0c11;border:1px solid #1c1c22;border-radius:16px;padding:36px;">
          <tr><td>
            <div style="font-size:26px;font-weight:800;letter-spacing:2px;color:#ffffff;">RESCUE<span style="color:#FF2233;">.</span></div>
            <h1 style="font-size:24px;line-height:1.25;margin:22px 0 10px;color:#ffffff;">Your ${plan} plan has ended.</h1>
            <p style="font-size:15px;line-height:1.6;color:#c9c9d2;margin:0 0 24px;">
              ${dateText ? `It ended on ${dateText}. ` : ""}Your protection stays as it was, but it won't keep checking in the same way until you continue.
            </p>
            <a href="${link}" style="display:inline-block;background:#FF2233;color:#ffffff;text-decoration:none;font-weight:700;font-size:16px;padding:15px 34px;border-radius:12px;">
              Choose a plan &rarr;
            </a>
            <p style="font-size:13px;line-height:1.6;color:#8a8a97;margin:26px 0 0;">
              Or continue on fliptle.com:<br />
              <a href="${link}" style="color:#ff6a6a;word-break:break-all;">${link}</a>
            </p>
            <hr style="border:none;border-top:1px solid #1c1c22;margin:28px 0;" />
            <p style="font-size:12px;color:#6c6c78;margin:0;">
              You're receiving this because your Rescue plan ended. Questions? Just reply to this email.
            </p>
          </td></tr>
        </table>
      </td></tr>
    </table>
  </body>
</html>`;
}

function buildText({ email, planName, expiresAtMs }) {
  const link = expiryLink(email);
  const plan = planName || "Rescue";
  const dateText = formatIstDate(expiresAtMs);
  return [
    `Your ${plan} plan has ended.`,
    "",
    (dateText ? `It ended on ${dateText}. ` : "") +
      "Your protection stays as it was, but it won't keep checking in the same way until you continue.",
    "",
    "Choose a plan:",
    link,
    "",
    "You're receiving this because your Rescue plan ended.",
    "Questions? Just reply to this email.",
  ].join("\n");
}

/**
 * Whether the automatic once-per-expiry email should fire. `doc` is whichever
 * Firestore doc the caller already has in hand (subscriptions/{email} for a
 * paid expiry, planSelections/{email} for a trial expiry) — compared against
 * its own `expiryRenewalEmailSentFor` marker so a new expiry (a later
 * subscription, a later trial) always gets its own email.
 */
function shouldAutoSend(doc, expiresAtMs) {
  if (!Number.isFinite(expiresAtMs)) return false;
  return Number(doc && doc.expiryRenewalEmailSentFor) !== expiresAtMs;
}

/**
 * Manual-send rate limit: at most MAX_PER_DAY per rolling UTC day, tracked on
 * installs/{uid}.expiryEmailDayKey / .expiryEmailCountToday.
 * Returns { allowed, dayKey, nextCount } — the caller writes dayKey/nextCount
 * back only when it actually sends.
 */
function checkRateLimit(install, nowMs) {
  const dayKey = Math.floor(nowMs / DAY_MS);
  const sameDay = install && Number(install.expiryEmailDayKey) === dayKey;
  const count = sameDay ? Number(install.expiryEmailCountToday) || 0 : 0;
  return { allowed: count < MAX_PER_DAY, dayKey, nextCount: count + 1 };
}

module.exports = {
  SUBJECT,
  MAX_PER_DAY,
  expiryLink,
  formatIstDate,
  buildHtml,
  buildText,
  shouldAutoSend,
  checkRateLimit,
};
