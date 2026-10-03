"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("fs");
const path = require("path");
const wa = require("../whatsapp");
const deletion = require("../deletion");
const F = require("./fakes");

const VERIFY = "verify-token-S3CRET-123";
const SECRET = "app-secret-S3CRET-abc";
const NUMBER = "919876543210";

function setup(initial) {
  const fs_ = F.makeFirestore(initial);
  const logger = F.makeLogger();
  const deps = {
    db: fs_.db, logger,
    verifyToken: () => VERIFY,
    appSecret: () => SECRET,
    serverTimestamp: fs_.serverTimestamp,
  };
  return { ...fs_, logger, deps };
}

async function post(env, bodyObj, { secret = SECRET, headers } = {}) {
  const rawBody = F.raw(bodyObj);
  const req = F.makeReq({ rawBody, headers: headers || { "X-Hub-Signature-256": F.sign(rawBody, secret) } });
  const res = F.makeRes();
  await wa.handleRequest(req, res, env.deps);
  return { res, rawBody };
}
const msgPayload = (...msgs) => F.wrap({ messages: msgs });
const statusPayload = (...sts) => F.wrap({ statuses: sts });

// ---------------------------------------------------------------- GET
test("GET: correct mode + token returns the challenge as plain text", async () => {
  const env = setup();
  const res = F.makeRes();
  await wa.handleRequest(F.makeReq({ method: "GET", query: { "hub.mode": "subscribe", "hub.verify_token": VERIFY, "hub.challenge": "1158201444" } }), res, env.deps);
  assert.equal(res.statusCode, 200);
  assert.equal(res.body, "1158201444");
  assert.match(res.headers["content-type"], /^text\/plain/);
});

test("GET: wrong token, wrong mode, missing parts and an empty secret all give 403", async () => {
  const cases = [
    { "hub.mode": "subscribe", "hub.verify_token": "nope", "hub.challenge": "1" },
    { "hub.mode": "unsubscribe", "hub.verify_token": VERIFY, "hub.challenge": "1" },
    { "hub.mode": "subscribe", "hub.challenge": "1" },
    { "hub.mode": "subscribe", "hub.verify_token": VERIFY },
    {},
  ];
  for (const query of cases) {
    const env = setup();
    const res = F.makeRes();
    await wa.handleRequest(F.makeReq({ method: "GET", query }), res, env.deps);
    assert.equal(res.statusCode, 403, JSON.stringify(query));
  }
  const env = setup();
  env.deps.verifyToken = () => "";
  const res = F.makeRes();
  await wa.handleRequest(F.makeReq({ method: "GET", query: { "hub.mode": "subscribe", "hub.verify_token": "", "hub.challenge": "1" } }), res, env.deps);
  assert.equal(res.statusCode, 403, "an unset secret must never verify");
});

// ---------------------------------------------------------------- signature
test("POST: missing, wrong, malformed and tampered signatures are rejected with 403 and write nothing", async () => {
  const body = msgPayload(F.textMsg(NUMBER, "STOP"));
  const env = setup();

  assert.equal((await post(env, body, { headers: {} })).res.statusCode, 403);
  assert.equal((await post(env, body, { secret: "wrong-secret" })).res.statusCode, 403);
  assert.equal((await post(env, body, { headers: { "X-Hub-Signature-256": "sha1=" + "a".repeat(40) } })).res.statusCode, 403);
  assert.equal((await post(env, body, { headers: { "X-Hub-Signature-256": "sha256=zz" } })).res.statusCode, 403);

  // signed for one body, delivered with another
  const rawA = F.raw(body);
  const rawB = F.raw(msgPayload(F.textMsg(NUMBER, "STOP"), F.textMsg("919000000001", "STOP")));
  const res = F.makeRes();
  await wa.handleRequest(F.makeReq({ rawBody: rawB, headers: { "X-Hub-Signature-256": F.sign(rawA, SECRET) } }), res, env.deps);
  assert.equal(res.statusCode, 403);
  assert.equal(env.store.size, 0, "nothing may be written for an unsigned request");
});

test("POST: the signature is over the RAW bytes; a re-serialized body does not verify", async () => {
  const env = setup();
  const original = Buffer.from('{ "object": "whatsapp_business_account",  "entry": [] }', "utf8"); // odd spacing
  const signed = F.sign(original, SECRET);
  const reserialized = Buffer.from(JSON.stringify(JSON.parse(original.toString())), "utf8");
  const res = F.makeRes();
  await wa.handleRequest(F.makeReq({ rawBody: reserialized, headers: { "X-Hub-Signature-256": signed } }), res, env.deps);
  assert.equal(res.statusCode, 403);
  const ok = F.makeRes();
  await wa.handleRequest(F.makeReq({ rawBody: original, headers: { "X-Hub-Signature-256": signed } }), ok, env.deps);
  assert.equal(ok.statusCode, 200);
});

test("POST: valid signature but invalid JSON gets 400; other methods get 405", async () => {
  const env = setup();
  const rawBody = Buffer.from("not json", "utf8");
  const res = F.makeRes();
  await wa.handleRequest(F.makeReq({ rawBody, headers: { "X-Hub-Signature-256": F.sign(rawBody, SECRET) } }), res, env.deps);
  assert.equal(res.statusCode, 400);
  const put = F.makeRes();
  await wa.handleRequest(F.makeReq({ method: "PUT" }), put, env.deps);
  assert.equal(put.statusCode, 405);
});

// ---------------------------------------------------------------- opt-outs
test("opt-out keywords: STOP, STOP ALL, UNSUBSCRIBE, ignoring case, spaces and trailing punctuation", async () => {
  const yes = ["STOP", "stop", " Stop ", "sToP", "STOP ALL", "stop  all", "Stop All", "UNSUBSCRIBE", "Unsubscribe.", "Stop!!", "stop all ...", "unsubscribe ?!"];
  for (const body of yes) {
    const env = setup();
    const { res } = await post(env, msgPayload(F.textMsg(NUMBER, body)));
    assert.equal(res.statusCode, 200);
    assert.ok(env.store.has("whatsappOptOuts/" + NUMBER), "should opt out: " + JSON.stringify(body));
  }
});

test("things that are not opt-outs are ignored: other words, other message types", async () => {
  const no = ["STOPPED", "please stop", "stop all alerts", "unsubscribe me", "stop-all", "hello", "", "  ", "s t o p"];
  for (const body of no) {
    const env = setup();
    await post(env, msgPayload(F.textMsg(NUMBER, body)));
    assert.equal(env.store.size, 0, "must NOT opt out: " + JSON.stringify(body));
  }
  const env = setup();
  await post(env, msgPayload({ from: NUMBER, id: "wamid.X", type: "image", image: { caption: "STOP" } }));
  await post(env, msgPayload({ from: NUMBER, id: "wamid.Y", type: "button", button: { text: "STOP" } }));
  assert.equal(env.store.size, 0);
});

test("the opt-out stores only the flag, a time and a source: no message text, no extra fields", async () => {
  const env = setup();
  await post(env, msgPayload(F.textMsg(NUMBER, "STOP ALL")));
  const doc = env.store.get("whatsappOptOuts/" + NUMBER);
  assert.deepEqual(Object.keys(doc).sort(), ["optedOut", "optedOutAt", "source"]);
  assert.equal(doc.optedOut, true);
  assert.ok(!JSON.stringify(doc).toLowerCase().includes("stop"));
});

test("a repeated STOP (Meta retries) leaves the first record untouched", async () => {
  const env = setup();
  const body = msgPayload(F.textMsg(NUMBER, "STOP"));
  await post(env, body);
  const first = { ...env.store.get("whatsappOptOuts/" + NUMBER) };
  await post(env, body);
  await post(env, msgPayload(F.textMsg(NUMBER, "unsubscribe")));
  assert.deepEqual(env.store.get("whatsappOptOuts/" + NUMBER), first);
});

test("a STOP from a number outside India is still recorded (keyed by its digits)", async () => {
  const env = setup();
  await post(env, msgPayload(F.textMsg("14155552671", "STOP")));
  assert.ok(env.store.has("whatsappOptOuts/14155552671"));
});

test("isOptedOut matches every spelling of an opted-out number and fails closed on junk", async () => {
  const env = setup();
  await post(env, msgPayload(F.textMsg(NUMBER, "STOP")));
  for (const spelling of ["+91 98765 43210", "09876543210", "9876543210", "919876543210"]) {
    assert.equal(await wa.isOptedOut(env.db, spelling), true, spelling);
  }
  assert.equal(await wa.isOptedOut(env.db, "+91 98765 43211"), false);
  assert.equal(await wa.isOptedOut(env.db, "not a number"), true, "unusable input must never be sendable");
  assert.equal(await wa.isOptedOut(env.db, null), true);
});

// ---------------------------------------------------------------- statuses
const wamid = "wamid.HBgMOTE5ODc2NTQzMjEwFQIAERgSQzg3";

test("a status becomes waStatus on whatsappAlerts/{wamid}; unknown statuses and unsafe ids are ignored", async () => {
  const env = setup();
  await post(env, statusPayload(F.statusEvt(wamid, "sent")));
  assert.equal(env.store.get("whatsappAlerts/" + wamid).waStatus, "sent");
  await post(env, statusPayload(F.statusEvt(wamid, "deleted"), F.statusEvt("a/b", "sent"), F.statusEvt("", "sent")));
  assert.equal(env.store.size, 1);
});

test("order-independent: a status that arrives BEFORE the sender's own write is kept", async () => {
  const env = setup();
  await post(env, statusPayload(F.statusEvt(wamid, "delivered")));
  assert.equal(env.store.get("whatsappAlerts/" + wamid).waStatus, "delivered");
  // the sender writes its own record later, with merge
  await env.db.doc("whatsappAlerts/" + wamid).set({ recipientKey: NUMBER, alertType: "uninstall" }, { merge: true });
  const doc = env.store.get("whatsappAlerts/" + wamid);
  assert.equal(doc.waStatus, "delivered", "the sender's merge must not erase the status");
  assert.equal(doc.recipientKey, NUMBER);
  // and the normal order works too
  await post(env, statusPayload(F.statusEvt(wamid, "read")));
  assert.equal(env.store.get("whatsappAlerts/" + wamid).waStatus, "read");
  assert.equal(env.store.get("whatsappAlerts/" + wamid).alertType, "uninstall");
});

test("statuses only move forward: out-of-order and duplicate updates change nothing", async () => {
  const env = setup();
  await post(env, statusPayload(F.statusEvt(wamid, "read")));
  const afterRead = { ...env.store.get("whatsappAlerts/" + wamid) };
  await post(env, statusPayload(F.statusEvt(wamid, "delivered")));
  await post(env, statusPayload(F.statusEvt(wamid, "sent")));
  await post(env, statusPayload(F.statusEvt(wamid, "read")));
  assert.deepEqual(env.store.get("whatsappAlerts/" + wamid), afterRead);
});

test("failed records the error code only, and is final", async () => {
  const env = setup();
  await post(env, statusPayload(F.statusEvt(wamid, "sent")));
  await post(env, statusPayload(F.statusEvt(wamid, "failed", { errors: [{ code: 131047, title: "Re-engagement message", message: "secret detail", error_data: { details: "x" } }] })));
  let doc = env.store.get("whatsappAlerts/" + wamid);
  assert.equal(doc.waStatus, "failed");
  assert.equal(doc.waErrorCode, 131047);
  assert.ok(!JSON.stringify(doc).includes("Re-engagement") && !JSON.stringify(doc).includes("secret detail"));
  await post(env, statusPayload(F.statusEvt(wamid, "delivered")));
  doc = env.store.get("whatsappAlerts/" + wamid);
  assert.equal(doc.waStatus, "failed", "a late 'delivered' can't undo a failure");
});

test("status updates do not store the recipient number", async () => {
  const env = setup();
  await post(env, statusPayload(F.statusEvt(wamid, "sent", { recipient_id: NUMBER })));
  assert.ok(!JSON.stringify(env.store.get("whatsappAlerts/" + wamid)).includes(NUMBER));
});

test("a payload with several entries, messages and statuses is processed completely", async () => {
  const env = setup();
  const body = {
    object: "whatsapp_business_account",
    entry: [
      { changes: [{ value: { messages: [F.textMsg("919876543210", "STOP")], statuses: [F.statusEvt("wamid.A", "sent")] } }] },
      { changes: [{ value: { messages: [F.textMsg("919123456789", "unsubscribe")] } }, { value: { statuses: [F.statusEvt("wamid.B", "read")] } }] },
    ],
  };
  await post(env, body);
  assert.ok(env.store.has("whatsappOptOuts/919876543210") && env.store.has("whatsappOptOuts/919123456789"));
  assert.equal(env.store.get("whatsappAlerts/wamid.A").waStatus, "sent");
  assert.equal(env.store.get("whatsappAlerts/wamid.B").waStatus, "read");
  const wrong = setup();
  const { res } = await post(wrong, { object: "page", entry: [{ changes: [{ value: { messages: [F.textMsg(NUMBER, "STOP")] } }] }] });
  assert.equal(res.statusCode, 200);
  assert.equal(wrong.store.size, 0);
});

// ---------------------------------------------------------------- failures + privacy
test("a storage failure returns 500 (so Meta retries) and the retry then succeeds", async () => {
  const env = setup();
  const body = msgPayload(F.textMsg(NUMBER, "STOP"));
  env.state.failTransactions = 1;
  assert.equal((await post(env, body)).res.statusCode, 500);
  assert.equal(env.store.size, 0);
  assert.equal((await post(env, body)).res.statusCode, 200);
  assert.ok(env.store.has("whatsappOptOuts/" + NUMBER));
});

test("logs never contain secrets, signatures, request bodies, message text or phone numbers", async () => {
  const env = setup();
  const body = msgPayload(F.textMsg(NUMBER, "STOP"));
  const rawBody = F.raw(body);
  const goodSig = F.sign(rawBody, SECRET);
  await post(env, body);                                                    // success
  await post(env, statusPayload(F.statusEvt(wamid, "failed", { errors: [{ code: 1, title: "T" }] })));
  await post(env, body, { secret: "wrong" });                               // bad signature
  await post(env, body, { headers: {} });                                   // missing signature
  env.state.failTransactions = 1;
  await post(env, msgPayload(F.textMsg("919000000009", "STOP all")));       // storage failure
  const res = F.makeRes();                                                  // failed verification
  await wa.handleRequest(F.makeReq({ method: "GET", query: { "hub.mode": "subscribe", "hub.verify_token": "guess", "hub.challenge": "1" } }), res, env.deps);

  assert.ok(env.logger.lines.length > 0);
  const all = env.logger.lines.join("\n");
  for (const forbidden of [SECRET, VERIFY, goodSig, goodSig.slice(7), rawBody.toString(), NUMBER, "919000000009", "STOP", "guess"]) {
    assert.ok(!all.includes(forbidden), "logged something forbidden: " + forbidden.slice(0, 20));
  }
});

// ---------------------------------------------------------------- account deletion
test("account deletion can never remove opt-outs or alert records", async () => {
  const env = setup({
    "installs/u1": { deletionScheduledFor: 1000, email: "p@x.com", parentPhone: "+91 98765 43210" },
    "installs/u1/events/e1": { type: "install" },
    "appSignups/p@x.com": { email: "p@x.com" },
    ["whatsappOptOuts/" + NUMBER]: { optedOut: true, optedOutAt: 5, source: "whatsapp_reply" },
    "whatsappOptOuts/919123456789": { optedOut: true, optedOutAt: 6, source: "whatsapp_reply" },
    ["whatsappAlerts/" + wamid]: { waStatus: "read" },
  });
  const auth = {
    async getUser() { return { email: "p@x.com" }; },
    async deleteUser() {},
  };
  const partner = { async notifyPartnerOfDeletion() {}, async deletePartnerData() {} };
  const out = await deletion.executeDeletion({ db: env.db, auth, logger: env.logger, partner }, "u1", 2000);
  assert.equal(out, "deleted");
  assert.equal(env.store.has("installs/u1"), false, "the account itself is deleted");
  assert.ok(env.store.has("whatsappOptOuts/" + NUMBER), "the parent's opt-out must survive");
  assert.ok(env.store.has("whatsappOptOuts/919123456789"));
  assert.ok(env.store.has("whatsappAlerts/" + wamid));
  await deletion.sweepResurrected({ db: env.db }, 3000);
  assert.ok(env.store.has("whatsappOptOuts/" + NUMBER));
});

test("the deletion code has no wildcard or root-level delete and never names the WhatsApp collections", () => {
  const src = fs.readFileSync(path.join(__dirname, "..", "deletion.js"), "utf8");
  assert.ok(!/whatsapp/i.test(src), "deletion.js must not reference the WhatsApp collections");
  assert.ok(!/db\.listCollections|recursiveDelete\(\s*db\b/.test(src), "no root-level deletion");
});
