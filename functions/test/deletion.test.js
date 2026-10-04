"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const d = require("../deletion");

const H = 3600 * 1000;

// ---- in-memory fakes -------------------------------------------------------
function makeEnv({ docs = {}, users = {}, failOnce = {} } = {}) {
  const store = new Map(Object.entries(docs));
  const log = [];
  const failures = { ...failOnce }; // path -> remaining failures

  const maybeFail = (op, path) => {
    const key = op + ":" + path;
    if (failures[key] > 0) {
      failures[key]--;
      throw new Error("injected failure " + key);
    }
  };
  const ref = (path) => ({
    path,
    async get() {
      return { exists: store.has(path), data: () => store.get(path), ref: ref(path) };
    },
    async set(data) { maybeFail("set", path); store.set(path, data); },
    async delete() { maybeFail("delete", path); log.push("delete:" + path); store.delete(path); },
    async listCollections() {
      const ids = new Set();
      for (const k of store.keys()) {
        if (k.startsWith(path + "/")) ids.add(k.slice(path.length + 1).split("/")[0]);
      }
      return [...ids].map((id) => ({ path: path + "/" + id }));
    },
  });
  const DELETE = Symbol("delete-field");
  const applyFields = (path, data, merge) => {
    const next = merge ? { ...(store.get(path) || {}) } : {};
    for (const [k, v] of Object.entries(data)) {
      if (v === DELETE) delete next[k]; else next[k] = v;
    }
    store.set(path, next);
  };
  const db = {
    doc: ref,
    async runTransaction(fn) {
      // Writes are buffered and applied only if the callback returns, like Firestore.
      const writes = [];
      const txn = {
        async get(r) { return r.get(); },
        set(r, data, opts) { writes.push(() => applyFields(r.path, data, !!(opts && opts.merge))); },
        update(r, data) {
          if (!store.has(r.path)) throw new Error("update of a missing doc");
          writes.push(() => applyFields(r.path, data, true));
        },
      };
      const result = await fn(txn);
      writes.forEach((w) => w());
      return result;
    },
    async recursiveDelete(col) {
      for (const k of [...store.keys()]) if (k.startsWith(col.path + "/")) store.delete(k);
      log.push("recursiveDelete:" + col.path);
    },
    collection(name) {
      const list = () => [...store.keys()]
        .filter((k) => k.startsWith(name + "/") && k.split("/").length === 2)
        .map((k) => ({ ref: ref(k), data: () => store.get(k), id: k.split("/")[1] }));
      return {
        async get() { return { docs: list() }; },
        where(field, op, val) {
          assert.equal(op, "==");
          return { async get() { return { docs: list().filter((x) => x.data()[field] === val) }; } };
        },
      };
    },
  };
  const authUsers = new Map(Object.entries(users));
  const auth = {
    async getUser(uid) {
      if (!authUsers.has(uid)) { const e = new Error("nf"); e.code = "auth/user-not-found"; throw e; }
      return authUsers.get(uid);
    },
    async deleteUser(uid) {
      if (!authUsers.has(uid)) { const e = new Error("nf"); e.code = "auth/user-not-found"; throw e; }
      log.push("deleteUser:" + uid);
      authUsers.delete(uid);
    },
    async revokeRefreshTokens(uid) {
      maybeFail("revoke", uid);
      log.push("revoke:" + uid);
    },
  };
  const fv = {
    timestampFromMs: (ms) => ms, // toMs() reads plain numbers
    serverTimestamp: () => "SERVER_TS",
    deleteField: () => DELETE,
  };
  const partnerCalls = [];
  const partner = {
    async notifyPartnerOfDeletion(uid) { partnerCalls.push("notify:" + uid); },
    async deletePartnerData(uid, email) { partnerCalls.push("data:" + uid + ":" + email); },
  };
  const logger = { info() {}, warn() {}, error() {} };
  return { deps: { db, auth, logger, partner, fv }, store, log, authUsers, partnerCalls };
}

const NOW = 1_000_000 * H;
const due = { deletionScheduledFor: NOW - 1000, email: "a@x.com" };
function seeded(extra = {}) {
  return makeEnv({
    docs: {
      "installs/u1": due,
      "installs/u1/events/e1": { type: "install" },
      "installs/u1/uninstall_progress/p1": { day: 1 },
      "appSignups/a@x.com": { email: "a@x.com" },
      "planSelections/a@x.com": { selectedPlan: "trial" },
      "subscriptions/a@x.com": { status: "active" },
      "waitlist/w1": { email: "a@x.com" },
      "waitlist/w2": { email: "a@x.com" },
      "waitlist/w3": { email: "other@x.com" },
      "typing_gate/u1/attempts/t1": { ok: true },
      "installs/u2": { email: "b@x.com" },
      "appSignups/b@x.com": { email: "b@x.com" },
      ...extra.docs,
    },
    users: { u1: { email: "A@X.com" }, u2: { email: "b@x.com" } },
    failOnce: extra.failOnce,
  });
}

// ---- pure helpers ----------------------------------------------------------
test("scheduled time is exactly 72 hours after the request", () => {
  assert.equal(d.computeScheduledForMs(NOW), NOW + 72 * H);
});

test("isDue: past and exactly-now are due, future and missing are not", () => {
  assert.equal(d.isDue(NOW - 1, NOW), true);
  assert.equal(d.isDue(NOW, NOW), true);
  assert.equal(d.isDue(NOW + 1, NOW), false);
  assert.equal(d.isDue(null, NOW), false);
  assert.equal(d.isDue(undefined, NOW), false);
});

test("toMs reads numbers and Timestamp-like values", () => {
  assert.equal(d.toMs(5), 5);
  assert.equal(d.toMs({ toMillis: () => 7 }), 7);
  assert.equal(d.toMs(null), null);
});

// ---- request and cancel ----------------------------------------------------
const reqEnv = (docs = {}, extra = {}) =>
  makeEnv({ docs: { "installs/u1": { email: "a@x.com", ...docs }, ...(extra.docs || {}) },
    users: { u1: { email: "a@x.com" } }, failOnce: extra.failOnce });

test("request schedules exactly now + 72h and records the request time", async () => {
  const env = reqEnv();
  const r = await d.requestDeletion(env.deps, "u1", NOW);
  assert.deepEqual(r, { scheduledForMs: NOW + 72 * H });
  const doc = env.store.get("installs/u1");
  assert.equal(doc.deletionScheduledFor, NOW + 72 * H);
  assert.equal(doc.deletionRequestedAt, "SERVER_TS");
  assert.equal(doc.email, "a@x.com", "merge: other fields are kept");
});

test("every request starts a full new 72h: an earlier schedule never shortens it", async () => {
  // earlier request, 70h ago: only 2h would be left if it were kept
  const env = reqEnv({ deletionScheduledFor: NOW + 2 * H });
  const r = await d.requestDeletion(env.deps, "u1", NOW);
  assert.equal(r.scheduledForMs, NOW + 72 * H);
  assert.equal(env.store.get("installs/u1").deletionScheduledFor, NOW + 72 * H);
  // and a second request later restarts the clock again
  const later = await d.requestDeletion(env.deps, "u1", NOW + 10 * H);
  assert.equal(later.scheduledForMs, NOW + 82 * H);
});

test("request refuses once the scheduled time has passed, with a clear message", async () => {
  for (const at of [NOW - 1000, NOW]) { // past and exactly now are both due
    const env = reqEnv({ deletionScheduledFor: at });
    await assert.rejects(d.requestDeletion(env.deps, "u1", NOW), (e) => {
      assert.ok(e instanceof d.DeletionError);
      assert.equal(e.code, "failed-precondition");
      assert.match(e.message, /can no longer be changed/);
      return true;
    });
    assert.equal(env.store.get("installs/u1").deletionScheduledFor, at, "unchanged");
    assert.equal(env.log.includes("revoke:u1"), false, "no sign-out for a refused request");
  }
});

test("request revokes the refresh tokens of that user only, after the write", async () => {
  const env = reqEnv();
  await d.requestDeletion(env.deps, "u1", NOW);
  assert.deepEqual(env.log.filter((x) => x.startsWith("revoke:")), ["revoke:u1"]);
  assert.ok(env.store.get("installs/u1").deletionScheduledFor, "schedule written before revoke");
});

test("request: a failed revoke is reported, the schedule stays, and a retry works", async () => {
  const env = reqEnv({}, { failOnce: { "revoke:u1": 1 } });
  await assert.rejects(d.requestDeletion(env.deps, "u1", NOW), (e) => {
    assert.ok(e instanceof d.DeletionError);
    assert.equal(e.code, "internal");
    assert.match(e.message, /sign out your other devices/);
    return true;
  });
  assert.equal(env.store.get("installs/u1").deletionScheduledFor, NOW + 72 * H);
  const r = await d.requestDeletion(env.deps, "u1", NOW + H);
  assert.equal(r.scheduledForMs, NOW + 73 * H, "retry restarts the 72h");
  assert.deepEqual(env.log.filter((x) => x.startsWith("revoke:")), ["revoke:u1"]);
});

test("request never touches another account's document", async () => {
  const env = reqEnv({}, { docs: { "installs/u2": { email: "b@x.com", deletionScheduledFor: NOW + H } } });
  await d.requestDeletion(env.deps, "u1", NOW);
  assert.equal(env.store.get("installs/u2").deletionScheduledFor, NOW + H);
  assert.equal(env.log.includes("revoke:u2"), false);
});

test("request still calls the partner placeholder, which does nothing", async () => {
  const env = reqEnv();
  await d.requestDeletion(env.deps, "u1", NOW);
  assert.deepEqual(env.partnerCalls, ["notify:u1"]);
  assert.equal(await d.notifyPartnerOfDeletion("u1"), undefined);
});

test("cancel clears both fields before the time, and a repeat is harmless", async () => {
  const env = reqEnv({ deletionScheduledFor: NOW + 5 * H, deletionRequestedAt: "x" });
  assert.deepEqual(await d.cancelDeletion(env.deps, "u1", NOW), { cancelled: true });
  const doc = env.store.get("installs/u1");
  assert.equal("deletionScheduledFor" in doc, false);
  assert.equal("deletionRequestedAt" in doc, false);
  assert.equal(doc.email, "a@x.com");
  assert.deepEqual(await d.cancelDeletion(env.deps, "u1", NOW), { cancelled: true });
});

test("cancel refuses once the scheduled time has passed, with a clear message", async () => {
  for (const at of [NOW - 1000, NOW]) {
    const env = reqEnv({ deletionScheduledFor: at });
    await assert.rejects(d.cancelDeletion(env.deps, "u1", NOW), (e) => {
      assert.ok(e instanceof d.DeletionError);
      assert.equal(e.code, "failed-precondition");
      assert.match(e.message, /can no longer be cancelled/);
      return true;
    });
    assert.equal(env.store.get("installs/u1").deletionScheduledFor, at, "unchanged");
  }
});

test("cancel with nothing scheduled, or no document, does not fail or create anything", async () => {
  assert.deepEqual(await d.cancelDeletion(reqEnv().deps, "u1", NOW), { cancelled: true });
  const none = makeEnv({});
  assert.deepEqual(await d.cancelDeletion(none.deps, "u1", NOW), { cancelled: true });
  assert.equal(none.store.has("installs/u1"), false);
});

test("cancel after a request restores the account, and a new request starts fresh again", async () => {
  const env = reqEnv();
  await d.requestDeletion(env.deps, "u1", NOW);
  await d.cancelDeletion(env.deps, "u1", NOW + H);
  assert.equal("deletionScheduledFor" in env.store.get("installs/u1"), false);
  const again = await d.requestDeletion(env.deps, "u1", NOW + 2 * H);
  assert.equal(again.scheduledForMs, NOW + 74 * H);
});

// ---- execution -------------------------------------------------------------
test("deletes every listed item for this user and nothing else", async () => {
  const env = seeded();
  assert.equal(await d.executeDeletion(env.deps, "u1", NOW), "deleted");
  const gone = ["installs/u1", "installs/u1/events/e1", "installs/u1/uninstall_progress/p1",
    "appSignups/a@x.com", "planSelections/a@x.com", "subscriptions/a@x.com",
    "waitlist/w1", "waitlist/w2", "typing_gate/u1/attempts/t1"];
  for (const p of gone) assert.equal(env.store.has(p), false, p + " should be deleted");
  assert.equal(env.authUsers.has("u1"), false);
  // untouched
  for (const p of ["waitlist/w3", "installs/u2", "appSignups/b@x.com"]) {
    assert.equal(env.store.has(p), true, p + " must remain");
  }
  assert.equal(env.authUsers.has("u2"), true);
});

test("installs/{uid} is deleted LAST, after the Auth user and the email data", async () => {
  const env = seeded();
  await d.executeDeletion(env.deps, "u1", NOW);
  const i = (x) => env.log.indexOf(x);
  assert.ok(i("deleteUser:u1") > -1 && i("delete:installs/u1") > i("deleteUser:u1"));
  assert.ok(i("delete:installs/u1") > i("delete:subscriptions/a@x.com"));
  assert.ok(i("delete:installs/u1") > i("delete:appSignups/a@x.com"));
  assert.equal(env.log[env.log.length - 1], "delete:installs/u1");
});

test("partner hooks run once at execution time", async () => {
  const env = seeded();
  await d.executeDeletion(env.deps, "u1", NOW);
  assert.deepEqual(env.partnerCalls, ["notify:u1", "data:u1:a@x.com"]);
});

test("not due, cancelled (no field) and missing marker do nothing", async () => {
  const future = makeEnv({ docs: { "installs/u1": { deletionScheduledFor: NOW + H }, "appSignups/a@x.com": {} },
    users: { u1: { email: "a@x.com" } } });
  assert.equal(await d.executeDeletion(future.deps, "u1", NOW), "not-due");
  assert.equal(future.store.has("appSignups/a@x.com"), true);
  assert.equal(future.authUsers.has("u1"), true);

  const cancelled = makeEnv({ docs: { "installs/u1": { email: "a@x.com" } }, users: { u1: { email: "a@x.com" } } });
  assert.equal(await d.executeDeletion(cancelled.deps, "u1", NOW), "not-due");
  assert.equal(cancelled.authUsers.has("u1"), true);

  const none = makeEnv({ users: { u1: { email: "a@x.com" } } });
  assert.equal(await d.executeDeletion(none.deps, "u1", NOW), "no-marker");
  assert.equal(none.authUsers.has("u1"), true);
});

test("retry after a mid-way failure finishes the job (marker survives)", async () => {
  const env = seeded({ failOnce: { "delete:subscriptions/a@x.com": 1 } });
  await assert.rejects(d.executeDeletion(env.deps, "u1", NOW));
  assert.equal(env.store.has("installs/u1"), true, "marker must survive so the next run retries");
  assert.equal(env.authUsers.has("u1"), true, "Auth user must not be deleted before data");
  assert.equal(await d.executeDeletion(env.deps, "u1", NOW), "deleted");
  assert.equal(env.store.has("subscriptions/a@x.com"), false);
  assert.equal(env.store.has("installs/u1"), false);
  assert.equal(env.authUsers.has("u1"), false);
});

test("retry after the Auth user is already gone still completes", async () => {
  const env = seeded({ failOnce: { "delete:installs/u1": 1 } });
  await assert.rejects(d.executeDeletion(env.deps, "u1", NOW));
  assert.equal(env.authUsers.has("u1"), false);
  assert.equal(env.store.has("installs/u1"), true);
  assert.equal(await d.executeDeletion(env.deps, "u1", NOW), "deleted");
  assert.equal(env.store.has("installs/u1"), false);
});

test("running again after success is a no-op", async () => {
  const env = seeded();
  await d.executeDeletion(env.deps, "u1", NOW);
  assert.equal(await d.executeDeletion(env.deps, "u1", NOW), "no-marker");
});

// ---- resurrection sweep ----------------------------------------------------
test("sweep removes an installs doc re-created by a still-signed-in phone, then expires the note", async () => {
  const env = seeded();
  await d.executeDeletion(env.deps, "u1", NOW);
  assert.ok(env.store.has("deletionSweeps/u1"));
  env.store.set("installs/u1", { email: "a@x.com", lastHeartbeatMs: NOW + 1 }); // phone beat again
  await d.sweepResurrected(env.deps, NOW + H);
  assert.equal(env.store.has("installs/u1"), false);
  assert.equal(env.store.has("deletionSweeps/u1"), true, "kept until the window ends");
  await d.sweepResurrected(env.deps, NOW + d.SWEEP_WINDOW_MS + 1);
  assert.equal(env.store.has("deletionSweeps/u1"), false);
});

test("notifyPartnerOfDeletion and deletePartnerData are harmless no-ops today", async () => {
  assert.equal(await d.notifyPartnerOfDeletion("u1"), undefined);
  assert.equal(await d.deletePartnerData("u1", "a@x.com"), undefined);
});
