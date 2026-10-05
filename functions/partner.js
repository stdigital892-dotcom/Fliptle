"use strict";

// Accountability partner: contact + opt-out plumbing (sending is in
// partner-sender.js).
//
// Two Firestore collections, both written ONLY by Cloud Functions:
//
//   partnerContacts/{uid}  { partnerPhone, note, userConfirmedAt, status,
//                            updatedAt }
//       Server-only, never read by clients. Holds the number and the user's
//       own note. Deleted on account deletion (see deletion.js).
//
//   partnerLinks/{uid}     { status, partnerPhoneLast4, updatedAt }
//       The one row the client is allowed to READ (write still server-only), so
//       the Partner screen can show 'saved' / 'none' / 'stopped' without ever
//       seeing the full number again. Deleted on account deletion.
//
// Numbers are canonicalised by phone.js (the ONLY normaliser; see
// WHATSAPP_SETUP.md). The sender's opt-out check uses the SAME helper, so an
// opt-out recorded under one spelling is honoured for every spelling of the
// same number.
//
// Nothing here logs numbers, names, notes or template bodies.

const { normalizeIndianNumber } = require("./phone");

const CONTACTS_COLLECTION = "partnerContacts";
const LINKS_COLLECTION = "partnerLinks";

const LINK_STATUS = Object.freeze({
  NONE: "none",
  SAVED: "saved",
  STOPPED: "stopped",
});

const NOTE_MAX = 200;

/**
 * Validate and canonicalise an incoming phone number from the app. Returns
 * "91"+10 digits, or null if it isn't an Indian mobile the shared normaliser
 * accepts. The app enforces 10 digits starting 6-9; this is the server backstop.
 */
function normalizePartnerPhone(raw) {
  return normalizeIndianNumber(raw);
}

function last4(phone) {
  const d = String(phone || "").replace(/\D/g, "");
  return d.length >= 4 ? d.slice(-4) : d;
}

/**
 * Trim, drop control characters, and cap at NOTE_MAX. Empty is fine (the sender
 * fills a safe fallback). Does NOT reject anything — this is a free-text field.
 */
function cleanNote(raw) {
  if (raw == null) return "";
  const s = String(raw).replace(/[\u0000-\u001F\u007F]/g, " ").trim();
  return s.length > NOTE_MAX ? s.slice(0, NOTE_MAX) : s;
}

// ---- callable: savePartner -----------------------------------------------

class PartnerError extends Error {
  constructor(code, message) {
    super(message);
    this.name = "PartnerError";
    this.code = code;
  }
}

const MSG_BAD_PHONE = "That isn't a valid 10-digit Indian mobile.";
const MSG_NOT_CONFIRMED = "Please confirm your partner has agreed before saving.";

/**
 * Store a partner contact and the matching client-readable link row.
 *
 * input: { partnerPhone, note, userConfirmed }
 *
 * Also enforces the ONE-partner invariant: a save REPLACES an earlier contact.
 * The user says in the UI that only one partner is messaged, so this makes it
 * so on the server too, regardless of what the client sends.
 */
async function savePartner(deps, uid, input, nowMs) {
  const normalized = normalizePartnerPhone(input && input.partnerPhone);
  if (!normalized) throw new PartnerError("invalid-argument", MSG_BAD_PHONE);
  if (!input || input.userConfirmed !== true) {
    throw new PartnerError("failed-precondition", MSG_NOT_CONFIRMED);
  }
  const note = cleanNote(input.note);
  const confirmedAt = deps.fv.timestampFromMs(nowMs);

  const contactsRef = deps.db.doc(CONTACTS_COLLECTION + "/" + uid);
  const linksRef = deps.db.doc(LINKS_COLLECTION + "/" + uid);
  await deps.db.runTransaction(async (txn) => {
    txn.set(
      contactsRef,
      {
        partnerPhone: normalized,
        note,
        userConfirmedAt: confirmedAt,
        status: "active",
        updatedAt: deps.fv.serverTimestamp(),
      },
      { merge: true }
    );
    txn.set(
      linksRef,
      {
        status: LINK_STATUS.SAVED,
        partnerPhoneLast4: last4(normalized),
        updatedAt: deps.fv.serverTimestamp(),
      },
      { merge: true }
    );
  });
  return { status: LINK_STATUS.SAVED, partnerPhoneLast4: last4(normalized) };
}

// ---- callable: removePartner ---------------------------------------------

/**
 * Clear the contact entirely and mark the link 'none' so the Partner screen
 * reflects it immediately.
 */
async function removePartner(deps, uid) {
  const contactsRef = deps.db.doc(CONTACTS_COLLECTION + "/" + uid);
  const linksRef = deps.db.doc(LINKS_COLLECTION + "/" + uid);
  await deps.db.runTransaction(async (txn) => {
    txn.delete(contactsRef);
    txn.set(
      linksRef,
      {
        status: LINK_STATUS.NONE,
        partnerPhoneLast4: deps.fv.deleteField(),
        updatedAt: deps.fv.serverTimestamp(),
      },
      { merge: true }
    );
  });
  return { status: LINK_STATUS.NONE };
}

// ---- webhook hook: STOP -> partnerLinks 'stopped' ------------------------

/**
 * Called by whatsapp.js right after a STOP has been recorded. Any user whose
 * partnerContacts.partnerPhone matches gets their partnerLinks.status set to
 * 'stopped'. The STOP record itself is in whatsappOptOuts (owned by the webhook
 * and never deleted by account deletion); this is just the client-visible side.
 *
 * Returns the number of links updated (0 if the number was recorded but no
 * contact row matches — all tests). Never throws out to the webhook; a Firestore
 * failure is logged with a code only.
 */
async function markLinkStoppedFor(deps, partnerPhone) {
  const key = normalizePartnerPhone(partnerPhone);
  if (!key) return 0; // non-Indian STOP: nothing in partnerContacts can match
  let updated = 0;
  try {
    const snap = await deps.db
      .collection(CONTACTS_COLLECTION)
      .where("partnerPhone", "==", key)
      .get();
    for (const doc of snap.docs) {
      const linksRef = deps.db.doc(LINKS_COLLECTION + "/" + doc.id);
      await linksRef.set(
        {
          status: LINK_STATUS.STOPPED,
          updatedAt: deps.fv.serverTimestamp(),
        },
        { merge: true }
      );
      updated++;
    }
  } catch (e) {
    deps.logger.error("partner.markLinkStoppedFor failed", { code: e && e.code, name: e && e.name });
  }
  return updated;
}

module.exports = {
  CONTACTS_COLLECTION,
  LINKS_COLLECTION,
  LINK_STATUS,
  NOTE_MAX,
  PartnerError,
  MSG_BAD_PHONE,
  MSG_NOT_CONFIRMED,
  normalizePartnerPhone,
  cleanNote,
  last4,
  savePartner,
  removePartner,
  markLinkStoppedFor,
};
