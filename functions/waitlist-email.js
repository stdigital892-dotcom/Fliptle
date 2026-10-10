"use strict";

// The waitlist welcome email: a short, plain confirmation. The waitlist is
// pre-launch — there is no plan to choose and nothing to pay yet, so this
// email makes no offer, states no price, and links nowhere (in particular,
// never to /offer). It only confirms the signup and says we'll email once,
// when Rescue launches. Used by sendWaitlistWelcome in index.js, which keeps
// the sender, trigger and idempotency logic.

const SUBJECT = "You're on the Rescue waitlist";

function buildWaitlistHtml() {
  return `<!DOCTYPE html>
<html>
  <body style="margin:0;background:#060608;font-family:Arial,Helvetica,sans-serif;color:#f5f5f7;">
    <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#060608;padding:32px 0;">
      <tr><td align="center">
        <table role="presentation" width="100%" style="max-width:520px;background:#0c0c11;border:1px solid #1c1c22;border-radius:16px;padding:36px;">
          <tr><td>
            <div style="font-size:26px;font-weight:800;letter-spacing:2px;color:#ffffff;">RESCUE<span style="color:#FF2233;">.</span></div>
            <h1 style="font-size:24px;line-height:1.25;margin:22px 0 10px;color:#ffffff;">You're on the waitlist.</h1>
            <p style="font-size:15px;line-height:1.6;color:#c9c9d2;margin:0 0 4px;">
              That's it — you're confirmed. We'll email you once, when Rescue launches.
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

function buildWaitlistText() {
  return [
    "You're on the waitlist.",
    "",
    "That's it — you're confirmed. We'll email you once, when Rescue launches.",
    "",
    "You're receiving this because you joined the RESCUE waitlist at fliptle.com.",
    "Questions? Just reply to this email.",
  ].join("\n");
}

module.exports = { SUBJECT, buildWaitlistHtml, buildWaitlistText };
