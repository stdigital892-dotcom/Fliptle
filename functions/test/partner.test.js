"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const F = require("./fakes");
const partner = require("../partner");
const sender = require("../partner-sender");
const whatsapp = require("../whatsapp");

// Keep every number distinct so a crossed wire shows up in the test name.
const USER = { uid: "u1", displayName: "Priya Sharma", email: "priya@example.com" };
const USER_PHONE_RAW = "9876543210";
const PARTNER_RAW = "9123456789";
const PARTNER_CANONICAL = "919123456789";

const NOW = Date.UTC(2026, 9, 5, 12, 0, 0); // 2026-10-05 12:00 UTC = 17:30 IST (open)

function fv() {
  // Minimal admin stand-in. deleteField returns undefined so merging drops the field.
  let seq = 1;
  return {
    timestampFromMs: (ms) => ms,
    serverTimestamp: () => seq++,
    deleteField: () => undefined,
  };
}

function env(initial = {}) {
  const fs = F.makeFirestore(initial);
  const logger = F.makeLogger();
  return { ...fs, logger, deps: { db: fs.db, logger, fv: fv() } };
}

// ---------------------------------------------------------------- normalization
test("normalizePartnerPhone accepts common Indian spellings and rejects everything else", () => {
  for (const ok of ["9876543210", "+91 98765 43210", "919876543210", "09876543210", "+91-98765-43210"]) {
    assert.equal(partner.normalizePartnerPhone(ok), "919876543210", JSON.stringify(ok));
  }
  for (const bad of ["", "   ", null, undefined, "123", "5876543210", "+1 415 555 2671", "abcdefghij", "919876543"]) {
    assert.equal(partner.normalizePartnerPhone(bad), null, JSON.stringify(bad));
  }
});

// ---------------------------------------------------------------- savePartner
test("savePartner validates the phone, requires the confirmation and writes both docs", async () => {
  const e = env();
  const out = await partner.savePartner(e.deps, USER.uid, {
    partnerPhone: PARTNER_RAW, note: "please check on me", userConfirmed: true,
  }, NOW);
  assert.deepEqual(out, { status: "saved", partnerPhoneLast4: "6789" });

  const contact = e.store.get("partnerContacts/" + USER.uid);
  assert.equal(contact.partnerPhone, PARTNER_CANONICAL);
  assert.equal(contact.note, "please check on me");
  assert.equal(contact.status, "active");
  assert.equal(contact.userConfirmedAt, NOW);

  const link = e.store.get("partnerLinks/" + USER.uid);
  assert.equal(link.status, "saved");
  assert.equal(link.partnerPhoneLast4, "6789");
});

test("savePartner rejects a bad phone with the right code", async () => {
  const e = env();
  await assert.rejects(
    partner.savePartner(e.deps, USER.uid, { partnerPhone: "notaphone", userConfirmed: true }, NOW),
    (err) => err.code === "invalid-argument" && err.message === partner.MSG_BAD_PHONE
  );
  await assert.rejects(
    partner.savePartner(e.deps, USER.uid, { partnerPhone: "5876543210", userConfirmed: true }, NOW),
    (err) => err.code === "invalid-argument"
  );
  assert.equal(e.store.has("partnerContacts/" + USER.uid), false);
  assert.equal(e.store.has("partnerLinks/" + USER.uid), false);
});

test("savePartner rejects an unconfirmed save with the right code", async () => {
  const e = env();
  await assert.rejects(
    partner.savePartner(e.deps, USER.uid, { partnerPhone: PARTNER_RAW, userConfirmed: false }, NOW),
    (err) => err.code === "failed-precondition" && err.message === partner.MSG_NOT_CONFIRMED
  );
  assert.equal(e.store.has("partnerContacts/" + USER.uid), false);
});

test("savePartner caps the note at 200 characters and strips control characters", async () => {
  const e = env();
  const dirty = "line one\nline two\twith a tab" + " ".repeat(10) + "ok" + "x".repeat(500);
  await partner.savePartner(e.deps, USER.uid, { partnerPhone: PARTNER_RAW, note: dirty, userConfirmed: true }, NOW);
  const note = e.store.get("partnerContacts/" + USER.uid).note;
  assert.equal(note.length, partner.NOTE_MAX);
  assert.ok(!/[\u0000-\u001F]/.test(note), "control chars stripped");
});

test("removePartner deletes the contact and marks the link none", async () => {
  const e = env({
    "partnerContacts/u1": { partnerPhone: PARTNER_CANONICAL, status: "active" },
    "partnerLinks/u1": { status: "saved", partnerPhoneLast4: "6789" },
  });
  const out = await partner.removePartner(e.deps, USER.uid);
  assert.deepEqual(out, { status: "none" });
  assert.equal(e.store.has("partnerContacts/u1"), false);
  assert.equal(e.store.get("partnerLinks/u1").status, "none");
});

// ---------------------------------------------------------------- STOP -> partnerLinks
test("markLinkStoppedFor sets partnerLinks.status='stopped' for the matching user", async () => {
  const e = env({
    "partnerContacts/u1": { partnerPhone: PARTNER_CANONICAL, status: "active" },
    "partnerLinks/u1": { status: "saved", partnerPhoneLast4: "6789" },
    // another user with a different partner — must NOT be touched
    "partnerContacts/u2": { partnerPhone: "919000000000", status: "active" },
    "partnerLinks/u2": { status: "saved", partnerPhoneLast4: "0000" },
  });
  const updated = await partner.markLinkStoppedFor(e.deps, PARTNER_RAW);
  assert.equal(updated, 1);
  assert.equal(e.store.get("partnerLinks/u1").status, "stopped");
  assert.equal(e.store.get("partnerLinks/u2").status, "saved");
});

test("markLinkStoppedFor is a no-op when the number can't be normalized", async () => {
  const e = env({
    "partnerContacts/u1": { partnerPhone: PARTNER_CANONICAL, status: "active" },
  });
  assert.equal(await partner.markLinkStoppedFor(e.deps, "4155552671"), 0);
});

test("webhook STOP flow calls the hook and marks partnerLinks 'stopped'", async () => {
  // One run through the real whatsapp.processPayload, with the partner hook injected.
  const e = env({
    "partnerContacts/u1": { partnerPhone: PARTNER_CANONICAL, status: "active" },
    "partnerLinks/u1": { status: "saved", partnerPhoneLast4: "6789" },
  });
  const deps = {
    ...e.deps,
    serverTimestamp: e.serverTimestamp,
    onOptOut: (hookDeps, from) => partner.markLinkStoppedFor(hookDeps, from),
  };
  const body = { object: "whatsapp_business_account",
    entry: [{ changes: [{ value: { messages: [{ from: PARTNER_CANONICAL, type: "text",
      text: { body: "STOP" }, id: "wamid.TEST1", timestamp: "1700" }] } }] }] };
  const summary = await whatsapp.processPayload(deps, body);
  assert.equal(summary.optOuts, 1);
  // whatsappOptOuts recorded under the normalized key
  assert.ok(e.store.has("whatsappOptOuts/" + PARTNER_CANONICAL));
  // and the partner's visible link flipped
  assert.equal(e.store.get("partnerLinks/u1").status, "stopped");
});

// ---------------------------------------------------------------- sanitize
test("sanitizeParam strips newlines/tabs, collapses 4+ spaces, trims, and falls back when empty", () => {
  assert.equal(sender.sanitizeParam("a\nb\tc", "fb"), "a b c");
  assert.equal(sender.sanitizeParam("a" + " ".repeat(6) + "b", "fb"), "a  b");
  assert.equal(sender.sanitizeParam("   ", "fallback"), "fallback");
  assert.equal(sender.sanitizeParam("", "No note was left."), "No note was left.");
  assert.equal(sender.sanitizeParam(null, "fb"), "fb");
  assert.equal(sender.sanitizeParam("x".repeat(300), "fb", 50).length, 50);
  // if even the fallback is empty we never return empty
  assert.equal(sender.sanitizeParam("", ""), "-");
});

test("renderParameters produces exactly 7 text parameters in the right order", () => {
  const params = sender.renderParameters({
    firstName: "Priya", note: "please check", days: 42, longest: 42,
    exitStatus: "Not completed",
    lastHeartbeatMs: Date.UTC(2026, 9, 4, 1, 0, 0), // 06:30 IST on 4 Oct
  });
  assert.equal(params.length, 7);
  for (const p of params) assert.equal(p.type, "text");
  assert.equal(params[0].text, "Priya");       // {{1}} first name
  assert.equal(params[1].text, "Priya");       // {{2}} same name again
  assert.equal(params[2].text, "please check"); // {{3}} note
  assert.equal(params[3].text, "42");           // {{4}} days
  assert.equal(params[4].text, "42");           // {{5}} longest
  assert.equal(params[5].text, "Not completed"); // {{6}}
  assert.equal(params[6].text, "4 Oct 2026");    // {{7}} IST date
});

test("renderParameters uses safe fallbacks for missing fields", () => {
  const params = sender.renderParameters({
    firstName: "", note: "", days: NaN, longest: NaN,
    exitStatus: "", lastHeartbeatMs: NaN,
  });
  assert.equal(params[0].text, "there");
  assert.equal(params[2].text, "No note was left.");
  assert.equal(params[3].text, "0");
  assert.equal(params[4].text, "0");
  assert.equal(params[5].text, "Not completed");
  assert.equal(params[6].text, "-");
  for (const p of params) assert.ok(p.text && p.text.length > 0, "never empty");
});

// ---------------------------------------------------------------- quiet hours
test("isQuietHour is true between 22:00 and 08:00 IST and false otherwise", () => {
  // 22:00 IST exactly
  assert.equal(sender.isQuietHour(Date.UTC(2026, 9, 5, 16, 30, 0)), true);
  // 02:00 IST
  assert.equal(sender.isQuietHour(Date.UTC(2026, 9, 5, 20, 30, 0)), true);
  // 08:00 IST exactly (open)
  assert.equal(sender.isQuietHour(Date.UTC(2026, 9, 5, 2, 30, 0)), false);
  // 12:00 IST
  assert.equal(sender.isQuietHour(Date.UTC(2026, 9, 5, 6, 30, 0)), false);
  // 21:59 IST (open)
  assert.equal(sender.isQuietHour(Date.UTC(2026, 9, 5, 16, 29, 0)), false);
});

// ---------------------------------------------------------------- evaluate (gates)
const H = 60 * 60 * 1000;
const DEFAULT_CFG = {
  staleHours: 48,
  cooldownDays: 7,
  dryRun: false,
  allowedUids: new Set(),
};
const activeContact = (phone = PARTNER_CANONICAL, note = "please check") =>
  ({ partnerPhone: phone, status: "active", note });
const installBase = {
  email: USER.email, displayName: USER.displayName,
  protectionActive: true, pornDays: 10,
  lastHeartbeatMs: NOW - 60 * H, // 60h ago, past the 48h stale boundary
};

test("evaluate: boundary — exactly 48h stale is not yet eligible, 48h01m is", async () => {
  const e = env();
  const atExactly = { ...installBase, lastHeartbeatMs: NOW - 48 * H };
  const atOneMinutePast = { ...installBase, lastHeartbeatMs: NOW - (48 * H + 60 * 1000) };
  const resEq = await sender.evaluate(e.deps, "u1", atExactly, activeContact(), NOW, DEFAULT_CFG);
  const resPast = await sender.evaluate(e.deps, "u1", atOneMinutePast, activeContact(), NOW, DEFAULT_CFG);
  assert.equal(resEq.ok, false);
  assert.equal(resEq.reason, sender.SKIP.NOT_STALE);
  assert.equal(resPast.ok, true);
});

test("evaluate: skips when the exit process is marked completed (uninstall_approved)", async () => {
  const e = env();
  const r = await sender.evaluate(
    e.deps, "u1",
    { ...installBase, uninstall_approved: true },
    activeContact(), NOW, DEFAULT_CFG
  );
  assert.deepEqual(r, { ok: false, reason: sender.SKIP.EXIT_COMPLETED });
});

test("evaluate: skips when a deletion is scheduled, and when no partner is saved", async () => {
  const e = env();
  assert.equal((await sender.evaluate(e.deps, "u1",
    { ...installBase, deletionScheduledFor: NOW + H }, activeContact(), NOW, DEFAULT_CFG)).reason,
    sender.SKIP.DELETION_SCHEDULED);
  assert.equal((await sender.evaluate(e.deps, "u1", installBase, null, NOW, DEFAULT_CFG)).reason,
    sender.SKIP.NO_PARTNER);
});

test("evaluate: skips when protectionActive was never recorded", async () => {
  const e = env();
  const noProtection = { ...installBase };
  delete noProtection.protectionActive;
  const r = await sender.evaluate(e.deps, "u1", noProtection, activeContact(), NOW, DEFAULT_CFG);
  assert.equal(r.reason, sender.SKIP.PROTECTION_WAS_NEVER_ACTIVE);
});

test("evaluate: never sends the same event twice (dedup on lastHeartbeatMs)", async () => {
  const e = env();
  const lastHeartbeatMs = NOW - 60 * H;
  const r = await sender.evaluate(e.deps, "u1",
    { ...installBase, lastHeartbeatMs, lastPartnerAlertEventId: String(lastHeartbeatMs) },
    activeContact(), NOW, DEFAULT_CFG);
  assert.equal(r.reason, sender.SKIP.ALREADY_SENT);
});

test("evaluate: cooldown refuses a second alert within COOLDOWN_DAYS days", async () => {
  const e = env();
  const sixDaysAgo = NOW - 6 * 24 * H;
  const nineDaysAgo = NOW - 9 * 24 * H;
  const base = { ...installBase, lastHeartbeatMs: NOW - 60 * H };
  const inCooldown = await sender.evaluate(e.deps, "u1",
    { ...base, lastPartnerAlertAt: sixDaysAgo }, activeContact(), NOW, DEFAULT_CFG);
  const past = await sender.evaluate(e.deps, "u1",
    { ...base, lastPartnerAlertAt: nineDaysAgo }, activeContact(), NOW, DEFAULT_CFG);
  assert.equal(inCooldown.reason, sender.SKIP.COOLDOWN);
  assert.equal(past.ok, true);
});

test("evaluate: too-many-failed-attempts refuses the same event", async () => {
  const e = env();
  const lastHeartbeatMs = NOW - 60 * H;
  const r = await sender.evaluate(e.deps, "u1",
    { ...installBase, lastHeartbeatMs, partnerAlertFailuresByEvent: { [String(lastHeartbeatMs)]: 3 } },
    activeContact(), NOW, DEFAULT_CFG);
  assert.equal(r.reason, sender.SKIP.TOO_MANY_FAILS);
});

// ---------------------------------------------------------------- signedOutAt
test("evaluate: signedOutAt newer than the last heartbeat skips with 'signed_out'", async () => {
  const e = env();
  const lastHeartbeatMs = NOW - 60 * H;
  const r = await sender.evaluate(e.deps, "u1",
    { ...installBase, lastHeartbeatMs, signedOutAt: lastHeartbeatMs + 1000 },
    activeContact(), NOW, DEFAULT_CFG);
  assert.deepEqual(r, { ok: false, reason: sender.SKIP.SIGNED_OUT });
  assert.equal(sender.SKIP.SIGNED_OUT, "signed_out");
});

test("evaluate: signedOutAt older than the heartbeat does not skip (signed back in, then went dark)", async () => {
  const e = env();
  const lastHeartbeatMs = NOW - 60 * H;
  const r = await sender.evaluate(e.deps, "u1",
    { ...installBase, lastHeartbeatMs, signedOutAt: lastHeartbeatMs - 5 * H },
    activeContact(), NOW, DEFAULT_CFG);
  assert.equal(r.ok, true);
});

test("evaluate: signedOutAt equal to the heartbeat does not skip", async () => {
  const e = env();
  const lastHeartbeatMs = NOW - 60 * H;
  const r = await sender.evaluate(e.deps, "u1",
    { ...installBase, lastHeartbeatMs, signedOutAt: lastHeartbeatMs },
    activeContact(), NOW, DEFAULT_CFG);
  assert.equal(r.ok, true);
});

test("evaluate: a missing signedOutAt changes nothing", async () => {
  const e = env();
  const withField = await sender.evaluate(e.deps, "u1",
    { ...installBase, signedOutAt: null }, activeContact(), NOW, DEFAULT_CFG);
  const without = await sender.evaluate(e.deps, "u1", installBase, activeContact(), NOW, DEFAULT_CFG);
  assert.equal(without.ok, true);
  assert.deepEqual(withField, without);
});

test("evaluate: signedOutAt as a Firestore Timestamp is read correctly", async () => {
  const e = env();
  const lastHeartbeatMs = NOW - 60 * H;
  const ts = (ms) => ({ toMillis: () => ms });
  const newer = await sender.evaluate(e.deps, "u1",
    { ...installBase, lastHeartbeatMs, signedOutAt: ts(lastHeartbeatMs + 1) }, activeContact(), NOW, DEFAULT_CFG);
  const older = await sender.evaluate(e.deps, "u1",
    { ...installBase, lastHeartbeatMs, signedOutAt: ts(lastHeartbeatMs - 1) }, activeContact(), NOW, DEFAULT_CFG);
  assert.equal(newer.reason, sender.SKIP.SIGNED_OUT);
  assert.equal(older.ok, true);
});

test("runOnce: a signed-out user is skipped, counted under 'signed_out', and nothing is sent", async () => {
  const lastHeartbeatMs = NOW - 60 * H;
  const e = env({
    "installs/u1": { ...installBase, lastHeartbeatMs, signedOutAt: lastHeartbeatMs + 1000 },
    "partnerContacts/u1": activeContact(),
    "installs/u2": { ...installBase, lastHeartbeatMs, email: "b@x.com", displayName: "Bela" }, // removed: no signedOutAt
    "partnerContacts/u2": activeContact("919000000001"),
  });
  const calls = [];
  const deps = {
    ...e.deps,
    fetch: async (url, init) => { calls.push(JSON.parse(init.body).to); return { status: 200, async json() { return { messages: [{ id: "wamid.S1" }] }; } }; },
    isOptedOut: async () => false,
  };
  const counts = await sender.runOnce(deps, NOW, SEND_CFG);
  assert.equal(counts.bySkipReason[sender.SKIP.SIGNED_OUT], 1);
  assert.equal(counts.sent, 1);
  assert.deepEqual(calls, ["919000000001"], "only the removed user's partner is messaged");
});

// ---------------------------------------------------------------- processUser (send)
function makeFetch(responses) {
  const calls = [];
  let i = 0;
  return {
    calls,
    fetch: async (url, init) => {
      calls.push({ url, init });
      const r = responses[Math.min(i++, responses.length - 1)];
      return {
        status: r.status,
        async json() { return r.json; },
      };
    },
  };
}

const okResp = (wamid = "wamid.OK1") => ({ status: 200, json: { messages: [{ id: wamid }] } });
const badResp = { status: 400, json: {} };

const SEND_CFG = {
  ...DEFAULT_CFG,
  phoneNumberId: "PHONEID",
  templateName: "partner_alert_v2",
  templateLang: "en_US",
  accessToken: "SECRET",
};

const EVENT = {
  eventId: "evt-1",
  lastHeartbeatMs: Date.UTC(2026, 9, 4, 1, 0, 0),
  partnerPhone: PARTNER_CANONICAL,
  note: "please check",
  displayName: USER.displayName,
  email: USER.email,
  pornDays: 42,
  failedCount: 0,
};

test("processUser: opted-out partner is skipped and NOTHING is sent, even in dry run", async () => {
  const e = env();
  const fetcher = makeFetch([okResp()]);
  const deps = { ...e.deps, fetch: fetcher.fetch, isOptedOut: async () => true };
  for (const cfg of [SEND_CFG, { ...SEND_CFG, dryRun: true }]) {
    const r = await sender.processUser(deps, "u1", EVENT, NOW, cfg);
    assert.equal(r.result, "skipped_opt_out");
  }
  assert.equal(fetcher.calls.length, 0, "no HTTP call was made for an opted-out partner");
});

test("processUser: on success, writes whatsappAlerts/{wamid} and marks the install", async () => {
  const e = env({ "installs/u1": { email: USER.email } });
  const fetcher = makeFetch([okResp("wamid.ABC")]);
  const deps = { ...e.deps, fetch: fetcher.fetch, isOptedOut: async () => false };
  const r = await sender.processUser(deps, "u1", EVENT, NOW, SEND_CFG);
  assert.equal(r.result, "sent");
  const alert = e.store.get("whatsappAlerts/wamid.ABC");
  assert.deepEqual(
    { uid: alert.uid, eventId: alert.eventId, kind: alert.kind, status: alert.status },
    { uid: "u1", eventId: EVENT.eventId, kind: "initial", status: "sent" }
  );
  const install = e.store.get("installs/u1");
  assert.equal(install.lastPartnerAlertEventId, EVENT.eventId);
  assert.equal(install.lastPartnerAlertAt, NOW);
});

test("processUser: sends exactly one POST with the exact 7-parameter payload", async () => {
  const e = env({ "installs/u1": {} });
  const fetcher = makeFetch([okResp()]);
  const deps = { ...e.deps, fetch: fetcher.fetch, isOptedOut: async () => false };
  await sender.processUser(deps, "u1", EVENT, NOW, SEND_CFG);
  assert.equal(fetcher.calls.length, 1);
  const call = fetcher.calls[0];
  assert.equal(call.url, "https://graph.facebook.com/v21.0/PHONEID/messages");
  const headers = call.init.headers;
  assert.equal(headers.Authorization, "Bearer SECRET");
  const body = JSON.parse(call.init.body);
  assert.equal(body.to, PARTNER_CANONICAL);
  assert.equal(body.type, "template");
  assert.equal(body.template.name, "partner_alert_v2");
  assert.equal(body.template.language.code, "en_US");
  const params = body.template.components[0].parameters;
  assert.equal(params.length, 7);
  assert.equal(params[0].text, "Priya");
  assert.equal(params[1].text, "Priya");
  assert.equal(params[2].text, "please check");
  assert.equal(params[3].text, "42");
  assert.equal(params[4].text, "42");
  assert.equal(params[5].text, "Not completed");
  assert.equal(params[6].text, "4 Oct 2026");
});

test("processUser: retries once on failure, then records a failed attempt and keeps the install eligible", async () => {
  const e = env({ "installs/u1": {} });
  const fetcher = makeFetch([badResp, badResp]); // first + retry both fail
  const deps = { ...e.deps, fetch: fetcher.fetch, isOptedOut: async () => false };
  const r = await sender.processUser(deps, "u1", EVENT, NOW, SEND_CFG);
  assert.equal(r.result, "failed");
  assert.equal(fetcher.calls.length, 2);
  const install = e.store.get("installs/u1");
  assert.equal(install.partnerAlertFailuresByEvent[EVENT.eventId], 1);
  // lastPartnerAlertEventId NOT set, so the next run still sees this as new.
  assert.equal(install.lastPartnerAlertEventId, undefined);
});

test("processUser: dry-run writes a dry_run doc and sends NOTHING", async () => {
  const e = env({ "installs/u1": {} });
  const fetcher = makeFetch([okResp()]);
  const deps = { ...e.deps, fetch: fetcher.fetch, isOptedOut: async () => false };
  const r = await sender.processUser(deps, "u1", EVENT, NOW, { ...SEND_CFG, dryRun: true });
  assert.equal(r.result, "dry_run");
  assert.equal(fetcher.calls.length, 0);
  assert.equal(e.store.get("whatsappAlerts/dry_u1_evt-1").status, "dry_run");
});

// ---------------------------------------------------------------- runOnce (end-to-end)
test("runOnce: processes stale users, skips the rest with correct reasons, and ONE user failing doesn't stop the batch", async () => {
  const staleBase = { ...installBase, lastHeartbeatMs: NOW - 60 * H };
  const e = env({
    // u1 — fully eligible, send succeeds
    "installs/u1": { ...staleBase, email: "a@x.com", displayName: "Anita" },
    "partnerContacts/u1": activeContact(PARTNER_CANONICAL),
    "partnerLinks/u1": { status: "saved", partnerPhoneLast4: "6789" },
    // u2 — same stale, send fails and throws unexpectedly
    "installs/u2": { ...staleBase, email: "b@x.com", displayName: "Bela" },
    "partnerContacts/u2": activeContact("919000000001"),
    "partnerLinks/u2": { status: "saved", partnerPhoneLast4: "0001" },
    // u3 — eligible but partner opted out
    "installs/u3": { ...staleBase, email: "c@x.com", displayName: "Chaya" },
    "partnerContacts/u3": activeContact("919000000002"),
    "partnerLinks/u3": { status: "saved", partnerPhoneLast4: "0002" },
    // u4 — the exit process completed, must be skipped
    "installs/u4": { ...staleBase, uninstall_approved: true, email: "d@x.com", displayName: "Diya" },
    "partnerContacts/u4": activeContact("919000000003"),
    // u5 — no partner saved
    "installs/u5": { ...staleBase, email: "e@x.com", displayName: "Esha" },
    // u6 — fresh heartbeat, nowhere near stale -> should not even be queried
    "installs/u6": { ...installBase, lastHeartbeatMs: NOW - H, email: "f@x.com", displayName: "Fiona" },
    "partnerContacts/u6": activeContact("919000000004"),
  });

  // Make u2's send fail twice in a row (first try + retry both throw) so it
  // ends up as a hard failure rather than a recovered retry.
  let call = 0;
  const deps = {
    ...e.deps,
    fetch: async (url) => {
      call++;
      if (url.endsWith("/messages") && (call === 2 || call === 3)) {
        throw new Error("network blown up");
      }
      return { status: 200, async json() { return { messages: [{ id: "wamid.N" + call }] }; } };
    },
    isOptedOut: async (_db, phone) => phone === "919000000002", // only u3's partner is opted out
  };
  const counts = await sender.runOnce(deps, NOW, SEND_CFG);

  // u1 sent, u2 failed (fetch threw), u3 opted out skipped
  assert.equal(counts.sent, 1);
  assert.equal(counts.failed, 1);
  assert.equal(counts.skipped_opt_out, 1);
  // Skipped-by-reason counts: u4 (exit), u5 (no partner); u6 is never queried.
  assert.equal(counts.bySkipReason[sender.SKIP.EXIT_COMPLETED], 1);
  assert.equal(counts.bySkipReason[sender.SKIP.NO_PARTNER], 1);
  // Guarantee: the batch kept running after u2's crash
  assert.equal(e.store.has("whatsappAlerts/wamid.N1"), true);
});

test("runOnce: during quiet hours it skips the whole run and sends nothing", async () => {
  const e = env();
  let called = false;
  const deps = {
    ...e.deps,
    fetch: async () => { called = true; return { status: 200, async json() { return {}; } }; },
    isOptedOut: async () => false,
  };
  const quiet = Date.UTC(2026, 9, 5, 20, 0, 0); // 01:30 IST
  const counts = await sender.runOnce(deps, quiet, SEND_CFG);
  assert.equal(counts.sent, 0);
  assert.equal(called, false);
});

// ---------------------------------------------------------------- deletion safety
test("the sender module never names whatsappOptOuts and never writes to it", () => {
  const fs_ = require("fs");
  const path = require("path");
  const src = fs_.readFileSync(path.join(__dirname, "..", "partner-sender.js"), "utf8");
  // Opt-outs are consulted only through the SHARED helper (isOptedOut), never
  // written here. A test fails if someone copy-pastes the collection name.
  assert.ok(!/whatsappOptOuts/.test(src), "partner-sender.js must not name whatsappOptOuts directly");
});

test("the sender never sends without calling isOptedOut, even if a caller passes a lax config", async () => {
  const e = env({ "installs/u1": {} });
  let optOutCalls = 0;
  const fetcher = makeFetch([okResp("wamid.X")]);
  const deps = {
    ...e.deps,
    fetch: fetcher.fetch,
    isOptedOut: async () => { optOutCalls++; return false; },
  };
  await sender.processUser(deps, "u1", EVENT, NOW, SEND_CFG);
  assert.equal(optOutCalls, 1, "isOptedOut MUST be called exactly once per send");
  assert.equal(fetcher.calls.length, 1);
});
