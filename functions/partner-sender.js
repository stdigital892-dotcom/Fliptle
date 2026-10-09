"use strict";

// Partner alert sender. Runs hourly from index.js. Logic lives here so it can be
// unit-tested with injected fakes (clock, fetch, isOptedOut, logger).
//
// What this is: ONE "the app went dark before the user finished the exit
// process" alert per unexpected-disappearance EVENT. The event id is the
// installs/{uid}.lastHeartbeatMs value (the last time the phone checked in),
// so the "same event" is any run that sees the same lastHeartbeatMs. If the
// user comes back and sends a fresh heartbeat, that is a different event.
//
// Follow-ups (24h / 72h) are intentionally NOT built here. See
// FOLLOW-UPS at the bottom.

const partner = require("./partner");

// ---- constants -----------------------------------------------------------

const STALE_HOURS_DEFAULT = 48;
const COOLDOWN_DAYS_DEFAULT = 7;
const MAX_USERS_PER_RUN = 200;
const MAX_FAILED_ATTEMPTS = 3;
const RETRY_COUNT = 1; // one retry on top of the first try
const QUIET_START_HOUR_IST = 22; // 22:00 IST
const QUIET_END_HOUR_IST = 8;    //  8:00 IST
const WHATSAPP_API_BASE = "https://graph.facebook.com/v21.0";

const INSTALLS_COLLECTION = "installs";
const ALERT_COLLECTION = "whatsappAlerts"; // shared with whatsapp.js

// Skip reasons the sender can emit (counts only, no PII). Keep stable so a
// log dashboard can match on them.
const SKIP = Object.freeze({
  EXIT_COMPLETED: "exit_completed",
  DELETION_SCHEDULED: "deletion_scheduled",
  NO_PARTNER: "no_partner",
  PARTNER_INACTIVE: "partner_inactive",
  OPTED_OUT: "opted_out",
  ALREADY_SENT: "already_sent",
  COOLDOWN: "cooldown",
  QUIET_HOURS: "quiet_hours",
  TOO_MANY_FAILS: "too_many_failed_attempts",
  NO_HEARTBEAT: "no_heartbeat",
  NOT_STALE: "not_stale",
  PROTECTION_WAS_NEVER_ACTIVE: "protection_never_active",
  SIGNED_OUT: "signed_out",
  ALLOWLIST: "allowlist",
});

// ---- sanitisation --------------------------------------------------------

/**
 * The one rule for template parameters: no newlines or tabs, no run of 4+
 * spaces, trim, never empty (fall back to [fallback]), length capped. Returns a
 * string. The user already can't type newlines in the Android screen, but the
 * database may hold legacy junk, so we clean every value on the way out.
 */
function sanitizeParam(value, fallback, maxLen = 200) {
  let s = value == null ? "" : String(value);
  // Replace every control character (newline, tab, carriage return, etc.) with a single space.
  s = s.replace(/[\u0000-\u001F\u007F]/g, " ");
  // Collapse four-or-more spaces to two (Meta rejects long runs).
  s = s.replace(/ {4,}/g, "  ");
  s = s.trim();
  if (!s) s = String(fallback || "").trim();
  if (!s) s = "-"; // ultimate backstop so no value is ever empty
  if (s.length > maxLen) s = s.slice(0, maxLen).trim() || "-";
  return s;
}

/**
 * Pull the first word of a display name as the "first name". Falls back to the
 * part before the '@' of the email, then to "there". Never returns empty.
 */
function firstNameFrom(displayName, email) {
  const trimmed = String(displayName || "").trim();
  if (trimmed) return trimmed.split(/\s+/)[0];
  const e = String(email || "").trim();
  if (e.includes("@")) return e.split("@")[0];
  return "there";
}

// ---- time ----------------------------------------------------------------

/** A millis value from a Firestore Timestamp, a Date, or a number; null if absent/unreadable. */
function toMillis(value) {
  if (value == null) return null;
  if (typeof value === "number") return Number.isFinite(value) ? value : null;
  if (typeof value.toMillis === "function") return value.toMillis();
  if (value instanceof Date) return value.getTime();
  return null;
}

/** 2026-10-05T05:00:00Z -> "5 Oct 2026" in IST (Asia/Kolkata, +05:30). */
function formatIstDate(ms) {
  if (!Number.isFinite(ms)) return "";
  // IST is a fixed offset (no DST), so a plain shift works without a tz library.
  const d = new Date(ms + 5.5 * 60 * 60 * 1000);
  const months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun",
                  "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
  return d.getUTCDate() + " " + months[d.getUTCMonth()] + " " + d.getUTCFullYear();
}

function istHour(ms) {
  const d = new Date(ms + 5.5 * 60 * 60 * 1000);
  return d.getUTCHours();
}

function isQuietHour(ms) {
  const h = istHour(ms);
  return h >= QUIET_START_HOUR_IST || h < QUIET_END_HOUR_IST;
}

// ---- template parameters -------------------------------------------------

/**
 * Build the seven body parameters in the exact order the template expects.
 * Every value is sanitised and never empty.
 */
function renderParameters({ firstName, note, days, longest, exitStatus, lastHeartbeatMs }) {
  const name = sanitizeParam(firstName, "there", 60);
  const noteText = sanitizeParam(note, "No note was left.", 200);
  const daysText = sanitizeParam(Number.isFinite(days) ? String(days) : "", "0", 10);
  const longestText = sanitizeParam(Number.isFinite(longest) ? String(longest) : "", "0", 10);
  const status = sanitizeParam(exitStatus, "Not completed", 60);
  const dateText = sanitizeParam(formatIstDate(lastHeartbeatMs), "-", 40);
  return [
    { type: "text", text: name },
    { type: "text", text: name },
    { type: "text", text: noteText },
    { type: "text", text: daysText },
    { type: "text", text: longestText },
    { type: "text", text: status },
    { type: "text", text: dateText },
  ];
}

// ---- eligibility ---------------------------------------------------------

/**
 * The one place that decides whether a user is eligible, as a pure function of
 * (install, partnerContact, nowMs, isOptedOutFn, config). Returns either
 * { ok: true, event } or { ok: false, reason }.
 *
 * It fails CLOSED on anything missing: unknown fields never pass, and a user
 * without a cooldown field is treated as not-in-cooldown only when no earlier
 * send is recorded.
 *
 * It does NOT consult the opt-out collection itself — that is an async read
 * done separately, because the sender does it last (right before send) and only
 * for users that would otherwise pass. See runOnce.
 */
async function evaluate(deps, uid, install, partnerContact, nowMs, config) {
  // Allowlist, if the operator set one.
  if (config.allowedUids && config.allowedUids.size > 0 && !config.allowedUids.has(uid)) {
    return { ok: false, reason: SKIP.ALLOWLIST };
  }

  if (!install) return { ok: false, reason: SKIP.NO_HEARTBEAT };

  // Account-deletion flow: never alert on top of a scheduled deletion.
  if (install.deletionScheduledFor) return { ok: false, reason: SKIP.DELETION_SCHEDULED };

  // The exit/uninstall process already finished -> no alert. Field used:
  // installs/{uid}.uninstall_approved, which is set by UninstallLog.logApproved
  // when the 5-day uninstall gate completes.
  if (install.uninstall_approved === true) return { ok: false, reason: SKIP.EXIT_COMPLETED };

  // Protection must actually have been enabled at some point. A user who never
  // turned adult-content blocking on hasn't lost anything by the app going dark.
  if (install.protectionActive !== true && install.protectionActive !== false) {
    // field absent entirely: the install never checked in with a protection
    // state, so we have nothing to go on.
    return { ok: false, reason: SKIP.PROTECTION_WAS_NEVER_ACTIVE };
  }

  const lastHeartbeatMs = Number(install.lastHeartbeatMs);
  if (!Number.isFinite(lastHeartbeatMs) || lastHeartbeatMs <= 0) {
    return { ok: false, reason: SKIP.NO_HEARTBEAT };
  }
  // A clean manual sign-out (SignOut.perform in the app) stamps signedOutAt. If it
  // is newer than the last heartbeat the phone went quiet because the user signed
  // out, not because Rescue was removed. A later heartbeat (signing back in) makes
  // signedOutAt older again, so alerts resume on their own for a real removal.
  //
  // Both stamps should be SERVER time, so the heartbeat's lastHeartbeatAt is the
  // reference; the phone's clock (lastHeartbeatMs) could be behind the server and
  // make a later heartbeat look older than the sign-out. A doc without
  // lastHeartbeatAt falls back to lastHeartbeatMs. The event id and the stale
  // check below keep using lastHeartbeatMs unchanged.
  const signedOutMs = toMillis(install.signedOutAt);
  const heartbeatServerMs = toMillis(install.lastHeartbeatAt);
  const heartbeatRefMs = heartbeatServerMs != null ? heartbeatServerMs : lastHeartbeatMs;
  if (signedOutMs != null && signedOutMs > heartbeatRefMs) {
    return { ok: false, reason: SKIP.SIGNED_OUT };
  }

  const staleMs = config.staleHours * 60 * 60 * 1000;
  // "older than STALE_HOURS": strictly older. At exactly the boundary we wait.
  if (nowMs - lastHeartbeatMs <= staleMs) return { ok: false, reason: SKIP.NOT_STALE };

  if (!partnerContact) return { ok: false, reason: SKIP.NO_PARTNER };
  if (partnerContact.status !== "active") return { ok: false, reason: SKIP.PARTNER_INACTIVE };
  const partnerPhone = partner.normalizePartnerPhone(partnerContact.partnerPhone);
  if (!partnerPhone) return { ok: false, reason: SKIP.NO_PARTNER };

  const eventId = String(lastHeartbeatMs);
  if (install.lastPartnerAlertEventId === eventId) {
    return { ok: false, reason: SKIP.ALREADY_SENT };
  }

  // Cooldown: time since the last alert to this user.
  const lastAlertMs = Number(install.lastPartnerAlertAt);
  if (Number.isFinite(lastAlertMs) && lastAlertMs > 0) {
    const cooldownMs = config.cooldownDays * 24 * 60 * 60 * 1000;
    if (nowMs - lastAlertMs < cooldownMs) return { ok: false, reason: SKIP.COOLDOWN };
  }

  // Too many failed attempts for this exact event -> give up.
  const failed = Number((install.partnerAlertFailuresByEvent || {})[eventId]) || 0;
  if (failed >= MAX_FAILED_ATTEMPTS) return { ok: false, reason: SKIP.TOO_MANY_FAILS };

  return {
    ok: true,
    event: {
      eventId,
      lastHeartbeatMs,
      partnerPhone,
      note: partnerContact.note || "",
      displayName: install.displayName || "",
      email: install.email || "",
      pornDays: Number(install.pornDays) || 0,
      failedCount: failed,
    },
  };
}

// ---- sending -------------------------------------------------------------

/**
 * One POST to Meta. Returns { ok, wamid?, errorCode?, errorName? } and never
 * throws. The response body is NEVER logged or returned — it can contain
 * recipient numbers.
 */
async function postToWhatsApp(deps, { phone, parameters }, config) {
  const url = `${WHATSAPP_API_BASE}/${encodeURIComponent(config.phoneNumberId)}/messages`;
  const body = {
    messaging_product: "whatsapp",
    to: phone,
    type: "template",
    template: {
      name: config.templateName,
      language: { code: config.templateLang },
      components: [{ type: "body", parameters }],
    },
  };
  try {
    const resp = await deps.fetch(url, {
      method: "POST",
      headers: {
        Authorization: "Bearer " + config.accessToken,
        "Content-Type": "application/json",
      },
      body: JSON.stringify(body),
    });
    if (!resp || typeof resp.status !== "number") {
      return { ok: false, errorCode: 0, errorName: "no_response" };
    }
    if (resp.status < 200 || resp.status >= 300) {
      return { ok: false, errorCode: resp.status, errorName: "http_" + resp.status };
    }
    let json = null;
    try { json = await resp.json(); } catch (_) { json = null; }
    const wamid = json && json.messages && json.messages[0] && json.messages[0].id;
    if (typeof wamid !== "string" || !wamid) {
      return { ok: false, errorCode: resp.status, errorName: "no_wamid" };
    }
    return { ok: true, wamid };
  } catch (e) {
    return { ok: false, errorCode: 0, errorName: "fetch_error" };
  }
}

async function sendWithOneRetry(deps, args, config) {
  const first = await postToWhatsApp(deps, args, config);
  if (first.ok) return first;
  for (let i = 0; i < RETRY_COUNT; i++) {
    const next = await postToWhatsApp(deps, args, config);
    if (next.ok) return next;
  }
  return first;
}

// ---- the per-user step ---------------------------------------------------

/**
 * Everything that happens for ONE eligible user, after the eligibility rules
 * above have passed. Returns one of: "sent", "dry_run", "skipped_opt_out",
 * "failed".
 */
async function processUser(deps, uid, event, nowMs, config) {
  // The OPT-OUT CHECK IS MANDATORY. It is done LAST, right before send, so a
  // STOP recorded between eligibility and send is still honoured.
  const optedOut = await deps.isOptedOut(deps.db, event.partnerPhone);
  if (optedOut) return { result: "skipped_opt_out" };

  const parameters = renderParameters({
    firstName: firstNameFrom(event.displayName, event.email),
    note: event.note,
    days: event.pornDays,
    longest: event.pornDays, // see note in Heartbeat.kt: dayCount is monotonic (no reset)
    exitStatus: "Not completed",
    lastHeartbeatMs: event.lastHeartbeatMs,
  });

  const installsRef = deps.db.doc(INSTALLS_COLLECTION + "/" + uid);

  if (config.dryRun) {
    // Record a dry-run entry so a count exists; send NOTHING.
    const dryId = "dry_" + uid + "_" + event.eventId;
    await deps.db.doc(ALERT_COLLECTION + "/" + dryId).set(
      {
        uid,
        eventId: event.eventId,
        kind: "initial",
        status: "dry_run",
        createdAt: deps.fv.serverTimestamp(),
      },
      { merge: true }
    );
    return { result: "dry_run" };
  }

  const outcome = await sendWithOneRetry(
    deps,
    { phone: event.partnerPhone, parameters },
    config
  );

  if (outcome.ok) {
    // Record the alert under its wamid. Merge, so a status update that lands
    // before this write is kept (same shape the webhook writes).
    await deps.db.doc(ALERT_COLLECTION + "/" + outcome.wamid).set(
      {
        uid,
        eventId: event.eventId,
        kind: "initial",
        sentAt: deps.fv.serverTimestamp(),
        status: "sent",
      },
      { merge: true }
    );
    await installsRef.set(
      {
        lastPartnerAlertAt: nowMs,
        lastPartnerAlertEventId: event.eventId,
      },
      { merge: true }
    );
    return { result: "sent" };
  }

  // Record a failed attempt (no number, no token, no body text).
  await installsRef.set(
    {
      partnerAlertFailuresByEvent: {
        [event.eventId]: event.failedCount + 1,
      },
      lastPartnerAlertFailureAt: nowMs,
    },
    { merge: true }
  );
  deps.logger.warn("partner-sender: send failed", {
    uid,
    attempt: event.failedCount + 1,
    errorCode: outcome.errorCode || 0,
    errorName: outcome.errorName || "unknown",
  });
  return { result: "failed" };
}

// ---- the run -------------------------------------------------------------

/**
 * One scheduled run. Iterates stale installs, applies every gate, sends.
 *
 * Returns per-run counts only (never PII).
 */
async function runOnce(deps, nowMs, config) {
  const counts = {
    checked: 0,
    eligible: 0,
    sent: 0,
    dry_run: 0,
    skipped_opt_out: 0,
    skipped: 0,
    failed: 0,
    bySkipReason: {},
  };

  if (isQuietHour(nowMs)) {
    counts.bySkipReason[SKIP.QUIET_HOURS] = -1; // sentinel; the whole run is a skip
    deps.logger.info("partner-sender: quiet hours; skipping run", { istHour: istHour(nowMs) });
    return counts;
  }

  const staleBoundary = nowMs - config.staleHours * 60 * 60 * 1000;
  const snap = await deps.db
    .collection(INSTALLS_COLLECTION)
    .where("lastHeartbeatMs", "<", staleBoundary)
    .orderBy("lastHeartbeatMs", "asc")
    .limit(MAX_USERS_PER_RUN)
    .get();

  for (const doc of snap.docs) {
    counts.checked++;
    const uid = doc.id;
    let contact = null;
    try {
      const contactSnap = await deps.db.doc(partner.CONTACTS_COLLECTION + "/" + uid).get();
      if (contactSnap.exists) contact = contactSnap.data() || null;
    } catch (e) {
      deps.logger.error("partner-sender: contact read failed", {
        uid, code: e && e.code, name: e && e.name,
      });
    }

    const verdict = await evaluate(deps, uid, doc.data() || {}, contact, nowMs, config);
    if (!verdict.ok) {
      counts.skipped++;
      counts.bySkipReason[verdict.reason] = (counts.bySkipReason[verdict.reason] || 0) + 1;
      continue;
    }
    counts.eligible++;

    try {
      const outcome = await processUser(deps, uid, verdict.event, nowMs, config);
      if (outcome.result === "sent") counts.sent++;
      else if (outcome.result === "dry_run") counts.dry_run++;
      else if (outcome.result === "skipped_opt_out") {
        counts.skipped_opt_out++;
        counts.bySkipReason[SKIP.OPTED_OUT] = (counts.bySkipReason[SKIP.OPTED_OUT] || 0) + 1;
      }
      else counts.failed++;
    } catch (e) {
      // One user's crash must not stop the run.
      counts.failed++;
      deps.logger.error("partner-sender: user step crashed", {
        uid, code: e && e.code, name: e && e.name,
      });
    }
  }

  deps.logger.info("partner-sender: run complete", counts);
  return counts;
}

// ---- FOLLOW-UPS (NOT BUILT) ---------------------------------------------
//
// TODO(partner-followups): add 24h and 72h follow-up kinds (kind:'followup_24h',
// kind:'followup_72h'). The `kind` field already lives on every whatsappAlerts
// doc for this reason. Follow-ups should obey the SAME opt-out check, the SAME
// quiet-hours rule, and should refuse to send if the initial alert is older
// than N days or if lastPartnerAlertEventId has changed.
//

module.exports = {
  // constants and helpers
  STALE_HOURS_DEFAULT,
  COOLDOWN_DAYS_DEFAULT,
  MAX_USERS_PER_RUN,
  MAX_FAILED_ATTEMPTS,
  QUIET_START_HOUR_IST,
  QUIET_END_HOUR_IST,
  SKIP,
  WHATSAPP_API_BASE,
  // pure helpers (tested)
  sanitizeParam,
  firstNameFrom,
  formatIstDate,
  istHour,
  isQuietHour,
  renderParameters,
  // the moving parts (tested with fakes)
  evaluate,
  postToWhatsApp,
  sendWithOneRetry,
  processUser,
  runOnce,
};
