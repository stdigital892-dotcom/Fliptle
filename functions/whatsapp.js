"use strict";

// WhatsApp Cloud API webhook: Meta's verification handshake, signed POST
// handling, STOP opt-outs and delivery-status tracking.
//
// Dependencies (Firestore, logger, secrets) are injected so this can be tested
// with fakes; index.js wires in the real ones. Two Firestore collections are
// used, both written ONLY by Cloud Functions (deny them to clients in rules):
//
//   whatsappOptOuts/{key}   key = optOutKey(number), see phone.js
//       optedOut, optedOutAt, source. Written once; a repeat STOP changes
//       nothing. Never deleted by the account-deletion job.
//
//   whatsappAlerts/{wamid}  one document per WhatsApp message id
//       waStatus, waStatusAt, waStatusUpdatedAt, waErrorCode. Status updates are
//       MERGED in, so a status that arrives before the sender has written its
//       own document is kept. The sender must also write with merge and must not
//       use the wa* field names.
//
// What is deliberately NOT stored: message text, the recipient number on
// status updates, Meta's error text. Nothing here logs secrets, signatures,
// request bodies or phone numbers.

const crypto = require("crypto");
const { optOutKey } = require("./phone");

const OPT_OUT_COLLECTION = "whatsappOptOuts";
const ALERT_COLLECTION = "whatsappAlerts";

// Replies that mean "stop messaging me". Compared after normalizeBody().
const OPT_OUT_KEYWORDS = new Set(["stop", "stop all", "unsubscribe"]);

// A status only ever moves forward, so a late or repeated update can't undo a
// later one. "failed" is final.
const STATUS_RANK = { sent: 1, delivered: 2, read: 3, failed: 4 };

// WhatsApp message ids look like "wamid.HBgM...". Anything that couldn't be a
// safe Firestore document id is ignored.
const WAMID_RE = /^[A-Za-z0-9._=+-]{1,256}$/;

/** Lower-case, trim, collapse inner spaces, drop trailing spaces/punctuation. */
function normalizeBody(body) {
  if (typeof body !== "string") return "";
  return body.trim().toLowerCase().replace(/\s+/g, " ").replace(/[\s\p{P}]+$/u, "");
}

function isOptOutMessage(body) {
  return OPT_OUT_KEYWORDS.has(normalizeBody(body));
}

/** Constant-time string comparison (hashing first makes the lengths equal). */
function secretEquals(a, b) {
  const ha = crypto.createHash("sha256").update(String(a)).digest();
  const hb = crypto.createHash("sha256").update(String(b)).digest();
  return crypto.timingSafeEqual(ha, hb);
}

/**
 * X-Hub-Signature-256 is "sha256=" + hex(HMAC-SHA256(rawBody, appSecret)).
 * rawBody must be the exact bytes received, not re-serialized JSON.
 */
function verifySignature(rawBody, header, secret) {
  if (!secret || !Buffer.isBuffer(rawBody) || typeof header !== "string") return false;
  const m = /^sha256=([0-9a-fA-F]{64})$/.exec(header.trim());
  if (!m) return false;
  const expected = crypto.createHmac("sha256", secret).update(rawBody).digest();
  const got = Buffer.from(m[1], "hex");
  return got.length === expected.length && crypto.timingSafeEqual(got, expected);
}

// ---- opt-outs --------------------------------------------------------------

/** First STOP wins; later ones change nothing. */
async function recordOptOut(deps, key) {
  const ref = deps.db.doc(OPT_OUT_COLLECTION + "/" + key);
  await deps.db.runTransaction(async (txn) => {
    const snap = await txn.get(ref);
    if (snap.exists) return;
    txn.set(ref, {
      optedOut: true,
      optedOutAt: deps.serverTimestamp(),
      source: "whatsapp_reply",
    });
  });
}

/**
 * Whether alerts to this number are blocked. THE SENDER MUST CALL THIS before
 * every send. It fails closed: a value that can't be turned into a number
 * returns true, so nothing is sent to it.
 */
async function isOptedOut(db, number) {
  const key = optOutKey(number);
  if (!key) return true;
  const snap = await db.doc(OPT_OUT_COLLECTION + "/" + key).get();
  return snap.exists && (snap.data() || {}).optedOut === true;
}

// ---- delivery statuses -----------------------------------------------------

/** Merge a status into whatsappAlerts/{wamid}. Returns true if it changed anything. */
async function recordStatus(deps, s) {
  if (!s || typeof s.id !== "string" || !WAMID_RE.test(s.id)) return false;
  const status = String(s.status || "").toLowerCase();
  const rank = STATUS_RANK[status];
  if (!rank) return false;

  const ref = deps.db.doc(ALERT_COLLECTION + "/" + s.id);
  let changed = false;
  await deps.db.runTransaction(async (txn) => {
    changed = false; // a retried transaction starts clean
    const snap = await txn.get(ref);
    const current = snap.exists ? STATUS_RANK[(snap.data() || {}).waStatus] || 0 : 0;
    if (rank <= current) return;

    const update = { waStatus: status, waStatusUpdatedAt: deps.serverTimestamp() };
    const at = Number(s.timestamp) * 1000;
    if (Number.isFinite(at)) update.waStatusAt = at;
    if (status === "failed") {
      const code = Array.isArray(s.errors) && s.errors[0] ? s.errors[0].code : undefined;
      if (Number.isInteger(code)) update.waErrorCode = code;
    }
    txn.set(ref, update, { merge: true });
    changed = true;
  });
  return changed;
}

// ---- payload ---------------------------------------------------------------

const asArray = (v) => (Array.isArray(v) ? v : []);

/** Apply every opt-out and status in a verified payload. Returns counts only. */
async function processPayload(deps, body) {
  const summary = { optOuts: 0, statuses: 0 };
  if (!body || body.object !== "whatsapp_business_account") return summary;

  for (const entry of asArray(body.entry)) {
    for (const change of asArray(entry && entry.changes)) {
      const value = change && change.value;
      if (!value) continue;

      for (const m of asArray(value.messages)) {
        if (m && m.type === "text" && m.text && isOptOutMessage(m.text.body)) {
          const key = optOutKey(m.from);
          if (key) {
            await recordOptOut(deps, key);
            summary.optOuts++;
            // Mirror the stop onto any user who had this number as their partner.
            // The whatsappOptOuts write above is the source of truth; this is
            // only the client-visible side (partnerLinks). Called after the
            // opt-out is recorded so the sender's isOptedOut check already
            // honours it, even if the hook below fails.
            if (deps.onOptOut) {
              try {
                await deps.onOptOut(deps, m.from);
              } catch (e) {
                deps.logger.error("whatsappWebhook: onOptOut hook failed", {
                  code: e && e.code, name: e && e.name,
                });
              }
            }
          }
        }
      }
      for (const s of asArray(value.statuses)) {
        if (await recordStatus(deps, s)) summary.statuses++;
      }
    }
  }
  return summary;
}

// ---- HTTP ------------------------------------------------------------------

function handleVerification(req, res, deps) {
  const q = req.query || {};
  const mode = q["hub.mode"];
  const token = q["hub.verify_token"];
  const challenge = q["hub.challenge"];
  const expected = deps.verifyToken();

  if (
    mode === "subscribe" &&
    typeof token === "string" &&
    typeof challenge === "string" &&
    expected &&
    secretEquals(token, expected)
  ) {
    res
      .status(200)
      .set("Content-Type", "text/plain; charset=utf-8")
      .set("X-Content-Type-Options", "nosniff")
      .send(challenge);
    return;
  }
  res.status(403).send("Forbidden");
}

/**
 * The whole endpoint. GET answers Meta's verification. POST checks the
 * signature on the raw body, applies the payload, then answers 200. If applying
 * it fails the answer is 500 so Meta retries; that is safe because every write
 * is idempotent.
 */
async function handleRequest(req, res, deps) {
  const { logger } = deps;
  try {
    if (req.method === "GET") {
      handleVerification(req, res, deps);
      return;
    }
    if (req.method !== "POST") {
      res.set("Allow", "GET, POST").status(405).send("Method not allowed");
      return;
    }

    if (!verifySignature(req.rawBody, req.get("x-hub-signature-256"), deps.appSecret())) {
      logger.warn("whatsappWebhook: signature check failed");
      res.status(403).send("Forbidden");
      return;
    }

    let body;
    try {
      body = JSON.parse(req.rawBody.toString("utf8"));
    } catch (_) {
      res.status(400).send("Bad request");
      return;
    }

    const summary = await processPayload(deps, body);
    logger.info("whatsappWebhook: processed", summary);
    res.status(200).send("ok");
  } catch (e) {
    // Only the error's type and code: Firestore messages can contain document
    // paths, and document ids here are phone numbers.
    logger.error("whatsappWebhook: processing failed; Meta will retry", {
      code: e && e.code,
      name: e && e.name,
    });
    res.status(500).send("error");
  }
}

module.exports = {
  OPT_OUT_COLLECTION,
  ALERT_COLLECTION,
  OPT_OUT_KEYWORDS,
  normalizeBody,
  isOptOutMessage,
  verifySignature,
  recordOptOut,
  isOptedOut,
  recordStatus,
  processPayload,
  handleRequest,
};
