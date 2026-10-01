"use strict";

// Account deletion with a 72-hour delay.
//
// This module holds the logic with its dependencies injected (db, auth, logger,
// partner hooks) so it can be unit-tested with fakes: see test/deletion.test.js.
// index.js wires in the real Admin SDK.

const DELETION_DELAY_MS = 72 * 60 * 60 * 1000; // 72 hours
const SWEEP_WINDOW_MS = 2 * 60 * 60 * 1000;    // see sweepResurrected()
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/** When a deletion requested at nowMs becomes due. */
function computeScheduledForMs(nowMs) {
  return nowMs + DELETION_DELAY_MS;
}

/** A millis value, whether stored as a Firestore Timestamp or a number. */
function toMs(value) {
  if (value == null) return null;
  if (typeof value === "number") return value;
  if (typeof value.toMillis === "function") return value.toMillis();
  return null;
}

function isDue(scheduledForMs, nowMs) {
  return typeof scheduledForMs === "number" && scheduledForMs <= nowMs;
}

function normalizeEmail(value) {
  const e = String(value || "").trim().toLowerCase();
  return EMAIL_RE.test(e) ? e : null;
}

// ---- Partner hooks (placeholders) -----------------------------------------

/**
 * PARTNER HOOK. Does nothing today: the accountability-partner feature has not
 * shipped. Called at REQUEST time (requestAccountDeletion) and again at
 * EXECUTION time (executeDeletion).
 *
 * When the partner feature ships, this MUST send the linked partner a
 * "link ending" notice at request time (so they know the link will end in 72
 * hours), and may send a final "link ended" notice at execution time. Keep it
 * keyed by uid only, and make it safe to call twice.
 */
async function notifyPartnerOfDeletion(uid) { // eslint-disable-line no-unused-vars
  // intentionally empty
}

/**
 * PARTNER HOOK. Does nothing today: there is no partner-data collection yet
 * (the parent/partner phone lives inside installs/{uid} and is removed with
 * it). When partner data exists, delete it here, scoped by uid/email, and keep
 * it idempotent.
 */
async function deletePartnerData(uid, email) { // eslint-disable-line no-unused-vars
  // intentionally empty
}

// ---- Execution -------------------------------------------------------------

/** Delete every subcollection of a doc, then the doc itself. Idempotent. */
async function deleteDocTree(db, ref) {
  const subs = await ref.listCollections();
  for (const col of subs) {
    await db.recursiveDelete(col);
  }
  await ref.delete();
}

/**
 * Permanently delete one account whose deletion is due. Idempotent: every step
 * is a no-op when its data is already gone, so a retry after a partial failure
 * is safe, and a missing Auth user counts as already deleted.
 *
 * ORDER MATTERS. installs/{uid} is the marker the hourly job searches for, so
 * it is deleted LAST. If any earlier step throws, the marker survives and the
 * next hourly run finishes the job. Deleting it first would orphan whatever
 * came after it.
 *
 * Returns "deleted" | "no-marker" | "not-due".
 */
async function executeDeletion(deps, uid, nowMs) {
  const { db, auth, logger, partner } = deps;
  const installsRef = db.doc("installs/" + uid);

  // Re-read right before acting: a cancel may have landed since the query.
  const snap = await installsRef.get();
  if (!snap.exists) return "no-marker";
  const data = snap.data() || {};
  if (!isDue(toMs(data.deletionScheduledFor), nowMs)) return "not-due";

  // Email: the Auth record is authoritative; if the Auth user is already gone
  // (a previous attempt got that far), fall back to what installs recorded.
  let email = null;
  try {
    const user = await auth.getUser(uid);
    email = normalizeEmail(user.email);
  } catch (e) {
    if (!e || e.code !== "auth/user-not-found") throw e;
    email = normalizeEmail(data.email);
  }

  await partner.notifyPartnerOfDeletion(uid);
  await partner.deletePartnerData(uid, email);

  if (email) {
    await db.doc("appSignups/" + email).delete();
    await db.doc("planSelections/" + email).delete();
    await db.doc("subscriptions/" + email).delete();
    const waitlist = await db.collection("waitlist").where("email", "==", email).get();
    for (const d of waitlist.docs) {
      await d.ref.delete();
    }
  }

  await deleteDocTree(db, db.doc("typing_gate/" + uid));

  try {
    await auth.deleteUser(uid);
  } catch (e) {
    if (!e || e.code !== "auth/user-not-found") throw e;
  }

  // Marker last.
  await deleteDocTree(db, installsRef);

  // The phone may still hold a valid ID token for up to an hour and could
  // re-create installs/{uid} (heartbeat, backup). Leave a PII-free note so
  // the next runs sweep it again; see sweepResurrected().
  await db.doc("deletionSweeps/" + uid).set({ sweepUntilMs: nowMs + SWEEP_WINDOW_MS });

  logger.info("Account deleted", { uid });
  return "deleted";
}

/**
 * Re-delete installs/{uid} for recently deleted accounts, in case a signed-in
 * device re-created it with its still-valid token. Notes expire after
 * SWEEP_WINDOW_MS. Idempotent.
 */
async function sweepResurrected(deps, nowMs) {
  const { db } = deps;
  const notes = await db.collection("deletionSweeps").get();
  for (const note of notes.docs) {
    await deleteDocTree(db, db.doc("installs/" + note.id));
    const until = (note.data() || {}).sweepUntilMs;
    if (typeof until !== "number" || until <= nowMs) {
      await note.ref.delete();
    }
  }
}

module.exports = {
  DELETION_DELAY_MS,
  SWEEP_WINDOW_MS,
  computeScheduledForMs,
  toMs,
  isDue,
  normalizeEmail,
  notifyPartnerOfDeletion,
  deletePartnerData,
  deleteDocTree,
  executeDeletion,
  sweepResurrected,
};
