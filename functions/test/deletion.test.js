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
  const db = {
    doc: ref,
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
  };
  const partnerCalls = [];
  const partner = {
    async notifyPartnerOfDeletion(uid) { partnerCalls.push("notify:" + uid); },
    async deletePartnerData(uid, email) { partnerCalls.push("data:" + uid + ":" + email); },
  };
  const logger = { info() {}, warn() {}, error() {} };
  return { deps: { db, auth, logger, partner }, store, log, authUsers, partnerCalls };
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
