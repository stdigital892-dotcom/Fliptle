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
const PENDING_CHANGE_DELAY_MS = 72 * 60 * 60 * 1000; // 72 hours
const HEARTBEAT_FRESH_MS = 24 * 60 * 60 * 1000; // 24 hours
const MAX_PENDING_CHANGES_PER_RUN = 200;

/**
 * Validate and canonicalise an incoming phone number from the app. Returns
 * "91"+10 digits, or null if it isn't an Indian mobile the shared normaliser
 * accepts. The app enforces 10 digits starting 6-9; this is the server backstop.
 */
function normalizePartnerPhone(raw) {
  return normalizeIndianNumber(raw);
}

// ---- the shared validation rule set (D1) -----------------------------------
//
// Beyond "is this syntactically a valid Indian mobile" (normalizeIndianNumber:
// 10 digits, starts 6-9), reject numbers that are almost certainly typos or
// placeholders rather than a real contact. The app enforces the same rules
// (PartnerPhone.kt) except the business-number check, which needs a server
// secret the app never has.

function allSameDigit(tenDigits) {
  return /^(\d)\1{9}$/.test(tenDigits);
}

function isSequentialRun(tenDigits) {
  let ascending = true;
  let descending = true;
  for (let i = 1; i < tenDigits.length; i++) {
    const diff = tenDigits.charCodeAt(i) - tenDigits.charCodeAt(i - 1);
    if (diff !== 1) ascending = false;
    if (diff !== -1) descending = false;
  }
  return ascending || descending;
}

function distinctDigitCount(tenDigits) {
  return new Set(tenDigits.split("")).size;
}

/**
 * The full rule set. Returns the normalized "91"+10-digit number, or null if
 * any rule fails (bad shape, all one digit, an ascending/descending run, fewer
 * than 4 distinct digits, or it equals the WhatsApp business number).
 * [businessNumberRaw] is optional; the check is skipped when it's not configured.
 */
function validatePartnerPhone(raw, businessNumberRaw) {
  const normalized = normalizePartnerPhone(raw);
  if (!normalized) return null;
  const ten = normalized.slice(2);
  if (allSameDigit(ten)) return null;
  if (isSequentialRun(ten)) return null;
  if (distinctDigitCount(ten) < 4) return null;
  const business = businessNumberRaw ? normalizePartnerPhone(businessNumberRaw) : null;
  if (business && business === normalized) return null;
  return normalized;
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

const MSG_BAD_PHONE = "Enter a valid 10-digit Indian mobile number.";
const MSG_NOT_CONFIRMED = "Please confirm your partner has agreed before saving.";
const MSG_NO_PARTNER = "There is no partner to remove.";

/**
 * Save a partner contact.
 *
 * input: { partnerPhone, note, userConfirmed }
 *
 * The FIRST partner saves immediately, exactly as before. If a contact already
 * exists (any status), this does NOT replace it: the new number/note are
 * queued as pendingChange (type "replace"), applied by
 * [applyPendingPartnerChanges] after PENDING_CHANGE_DELAY_MS, and the current
 * partner stays active in the meantime. A failing number writes nothing
 * either way.
 */
async function savePartner(deps, uid, input, nowMs) {
  const normalized = validatePartnerPhone(input && input.partnerPhone, deps.businessNumberRaw);
  if (!normalized) throw new PartnerError("invalid-argument", MSG_BAD_PHONE);
  if (!input || input.userConfirmed !== true) {
    throw new PartnerError("failed-precondition", MSG_NOT_CONFIRMED);
  }
  const note = cleanNote(input.note);

  const contactsRef = deps.db.doc(CONTACTS_COLLECTION + "/" + uid);
  const linksRef = deps.db.doc(LINKS_COLLECTION + "/" + uid);

  return deps.db.runTransaction(async (txn) => {
    const existing = await txn.get(contactsRef);

    if (!existing.exists) {
      // First partner: saves immediately.
      txn.set(
        contactsRef,
        {
          partnerPhone: normalized,
          note,
          userConfirmedAt: deps.fv.timestampFromMs(nowMs),
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
          pendingType: deps.fv.deleteField(),
          pendingEffectiveAt: deps.fv.deleteField(),
          updatedAt: deps.fv.serverTimestamp(),
        },
        { merge: true }
      );
      return { status: LINK_STATUS.SAVED, partnerPhoneLast4: last4(normalized) };
    }

    // A partner already exists: queue a delayed replace. Overwrites any earlier
    // pending change and restarts the 72 hours, and never touches the active
    // contact's own fields (it stays exactly as it is until applied).
    const effectiveAtMs = nowMs + PENDING_CHANGE_DELAY_MS;
    txn.set(
      contactsRef,
      {
        pendingChange: {
          type: "replace",
          requestedAt: deps.fv.timestampFromMs(nowMs),
          effectiveAt: deps.fv.timestampFromMs(effectiveAtMs),
          newPhone: normalized,
          newNote: note,
        },
        pendingChangeEffectiveAt: effectiveAtMs, // flat copy, for the hourly query only
        updatedAt: deps.fv.serverTimestamp(),
      },
      { merge: true }
    );
    txn.set(
      linksRef,
      {
        pendingType: "replace",
        pendingEffectiveAt: effectiveAtMs,
        pendingPhoneLast4: last4(normalized), // new number's last 4 only — safe to show
        updatedAt: deps.fv.serverTimestamp(),
      },
      { merge: true }
    );
    const currentStatus = (existing.data() || {}).status === "active" ? LINK_STATUS.SAVED : LINK_STATUS.STOPPED;
    return {
      status: currentStatus,
      partnerPhoneLast4: last4((existing.data() || {}).partnerPhone),
      pending: { type: "replace", effectiveAtMs },
    };
  });
}

/**
 * Queue removal of the current partner, 72 hours out. The partner stays
 * active until [applyPendingPartnerChanges] applies it. Overwrites any earlier
 * pending change and restarts the 72 hours.
 */
async function requestPartnerRemoval(deps, uid, nowMs) {
  const contactsRef = deps.db.doc(CONTACTS_COLLECTION + "/" + uid);
  const linksRef = deps.db.doc(LINKS_COLLECTION + "/" + uid);
  const effectiveAtMs = nowMs + PENDING_CHANGE_DELAY_MS;

  await deps.db.runTransaction(async (txn) => {
    const existing = await txn.get(contactsRef);
    if (!existing.exists) throw new PartnerError("failed-precondition", MSG_NO_PARTNER);
    txn.set(
      contactsRef,
      {
        pendingChange: {
          type: "remove",
          requestedAt: deps.fv.timestampFromMs(nowMs),
          effectiveAt: deps.fv.timestampFromMs(effectiveAtMs),
        },
        pendingChangeEffectiveAt: effectiveAtMs,
        updatedAt: deps.fv.serverTimestamp(),
      },
      { merge: true }
    );
    txn.set(
      linksRef,
      {
        pendingType: "remove",
        pendingEffectiveAt: effectiveAtMs,
        updatedAt: deps.fv.serverTimestamp(),
      },
      { merge: true }
    );
  });
  return { pending: { type: "remove", effectiveAtMs } };
}

/**
 * Cancel whatever pending change exists (remove or replace). Idempotent:
 * nothing pending is not an error.
 */
async function cancelPartnerChange(deps, uid) {
  const contactsRef = deps.db.doc(CONTACTS_COLLECTION + "/" + uid);
  const linksRef = deps.db.doc(LINKS_COLLECTION + "/" + uid);
  await deps.db.runTransaction(async (txn) => {
    if (!(await txn.get(contactsRef)).exists) return;
    txn.set(
      contactsRef,
      {
        pendingChange: deps.fv.deleteField(),
        pendingChangeEffectiveAt: deps.fv.deleteField(),
        updatedAt: deps.fv.serverTimestamp(),
      },
      { merge: true }
    );
    txn.set(
      linksRef,
      {
        pendingType: deps.fv.deleteField(),
        pendingEffectiveAt: deps.fv.deleteField(),
        pendingPhoneLast4: deps.fv.deleteField(),
        updatedAt: deps.fv.serverTimestamp(),
      },
      { merge: true }
    );
  });
  return { cancelled: true };
}

// ---- scheduled: applyPendingPartnerChanges ---------------------------------
//
// Hourly. Applies a pending change ONLY IF the account's heartbeat is fresh
// (within HEARTBEAT_FRESH_MS) at apply time — a stale heartbeat means the
// phone may be the one going dark, and applying a change in that window could
// be an attacker (or the in-the-moment self) racing the exit process. Dropping
// instead of applying keeps the CURRENT partner active and simply forgets the
// request; the user can ask again once they're back.
//
// Never touches whatsappOptOuts. A replacement number that is already opted
// out is still applied, but the link status becomes "stopped" instead of
// "saved" so the screen reflects it immediately.

async function freshHeartbeat(deps, uid, nowMs) {
  const snap = await deps.db.doc("installs/" + uid).get();
  if (!snap.exists) return false;
  const at = (snap.data() || {}).lastHeartbeatAt;
  if (at == null) return false;
  const atMs = typeof at.toMillis === "function" ? at.toMillis() : Number(at);
  return Number.isFinite(atMs) && nowMs - atMs <= HEARTBEAT_FRESH_MS;
}

async function applyOnePendingChange(deps, doc, nowMs) {
  const uid = doc.id;
  const data = doc.data() || {};
  const pending = data.pendingChange;
  if (!pending) return "no-pending"; // query raced a cancel; nothing to do

  const contactsRef = deps.db.doc(CONTACTS_COLLECTION + "/" + uid);
  const linksRef = deps.db.doc(LINKS_COLLECTION + "/" + uid);

  if (!(await freshHeartbeat(deps, uid, nowMs))) {
    // Stale (or no) heartbeat: drop the change, keep the partner as is.
    await deps.db.runTransaction(async (txn) => {
      txn.set(
        contactsRef,
        { pendingChange: deps.fv.deleteField(), pendingChangeEffectiveAt: deps.fv.deleteField() },
        { merge: true }
      );
      txn.set(
        linksRef,
        { pendingType: deps.fv.deleteField(), pendingEffectiveAt: deps.fv.deleteField(), pendingPhoneLast4: deps.fv.deleteField() },
        { merge: true }
      );
    });
    return "dropped";
  }

  if (pending.type === "remove") {
    await deps.db.runTransaction(async (txn) => {
      txn.delete(contactsRef);
      txn.set(
        linksRef,
        {
          status: LINK_STATUS.NONE,
          partnerPhoneLast4: deps.fv.deleteField(),
          pendingType: deps.fv.deleteField(),
          pendingEffectiveAt: deps.fv.deleteField(),
          updatedAt: deps.fv.serverTimestamp(),
        },
        { merge: true }
      );
    });
    return "removed";
  }

  if (pending.type === "replace") {
    const newPhone = normalizePartnerPhone(pending.newPhone);
    if (!newPhone) {
      // Shouldn't happen (validated at request time) — drop rather than apply
      // something unusable.
      await deps.db.runTransaction(async (txn) => {
        txn.set(
          contactsRef,
          { pendingChange: deps.fv.deleteField(), pendingChangeEffectiveAt: deps.fv.deleteField() },
          { merge: true }
        );
        txn.set(
          linksRef,
          { pendingType: deps.fv.deleteField(), pendingEffectiveAt: deps.fv.deleteField(), pendingPhoneLast4: deps.fv.deleteField() },
          { merge: true }
        );
      });
      return "dropped";
    }
    const optedOut = typeof deps.isOptedOut === "function" ? await deps.isOptedOut(deps.db, newPhone) : false;
    await deps.db.runTransaction(async (txn) => {
      txn.set(
        contactsRef,
        {
          partnerPhone: newPhone,
          note: pending.newNote || "",
          status: "active",
          pendingChange: deps.fv.deleteField(),
          pendingChangeEffectiveAt: deps.fv.deleteField(),
          updatedAt: deps.fv.serverTimestamp(),
        },
        { merge: true }
      );
      txn.set(
        linksRef,
        {
          status: optedOut ? LINK_STATUS.STOPPED : LINK_STATUS.SAVED,
          partnerPhoneLast4: last4(newPhone),
          pendingType: deps.fv.deleteField(),
          pendingEffectiveAt: deps.fv.deleteField(),
          updatedAt: deps.fv.serverTimestamp(),
        },
        { merge: true }
      );
    });
    return optedOut ? "replaced-stopped" : "replaced";
  }

  return "no-pending"; // unknown type: ignore rather than guess
}

/**
 * One hourly run. Returns counts only (never numbers, notes or names).
 */
async function applyPendingPartnerChanges(deps, nowMs) {
  const counts = { checked: 0, removed: 0, replaced: 0, dropped: 0, failed: 0 };
  const due = await deps.db
    .collection(CONTACTS_COLLECTION)
    .where("pendingChangeEffectiveAt", "<=", nowMs)
    .limit(MAX_PENDING_CHANGES_PER_RUN)
    .get();

  for (const doc of due.docs) {
    counts.checked++;
    try {
      const result = await applyOnePendingChange(deps, doc, nowMs);
      if (result === "removed") counts.removed++;
      else if (result === "replaced" || result === "replaced-stopped") counts.replaced++;
      else if (result === "dropped") counts.dropped++;
    } catch (e) {
      counts.failed++;
      deps.logger.error("applyPendingPartnerChanges: one account failed", {
        code: e && e.code,
        name: e && e.name,
      });
    }
  }

  deps.logger.info("applyPendingPartnerChanges: run complete", counts);
  return counts;
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
  PENDING_CHANGE_DELAY_MS,
  HEARTBEAT_FRESH_MS,
  PartnerError,
  MSG_BAD_PHONE,
  MSG_NOT_CONFIRMED,
  MSG_NO_PARTNER,
  normalizePartnerPhone,
  validatePartnerPhone,
  cleanNote,
  last4,
  savePartner,
  removePartner,
  requestPartnerRemoval,
  cancelPartnerChange,
  applyPendingPartnerChanges,
  markLinkStoppedFor,
};
