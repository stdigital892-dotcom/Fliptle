"use strict";
// In-memory stand-ins for Firestore, Auth, req/res and logger. Not a test file.
const crypto = require("crypto");

function makeFirestore(initial = {}) {
  const store = new Map(Object.entries(initial));
  let tick = 0;
  const state = { failTransactions: 0 };

  const ref = (path) => ({
    path,
    id: path.split("/").pop(),
    async get() {
      return { exists: store.has(path), data: () => store.get(path), id: path.split("/").pop() };
    },
    async set(data, opts) {
      store.set(path, opts && opts.merge ? { ...(store.get(path) || {}), ...data } : { ...data });
    },
    async delete() { store.delete(path); },
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
    },
    collection(name) {
      const list = () => [...store.keys()]
        .filter((k) => k.startsWith(name + "/") && k.split("/").length === 2)
        .map((k) => ({ ref: ref(k), data: () => store.get(k), id: k.split("/")[1] }));
      return {
        async get() { return { docs: list() }; },
        where(field, op, val) {
          if (op !== "==") throw new Error("fake supports == only");
          return { async get() { return { docs: list().filter((x) => x.data()[field] === val) }; } };
        },
      };
    },
    async runTransaction(fn) {
      if (state.failTransactions > 0) {
        state.failTransactions--;
        // like a real Firestore error: the message carries a document path
        const e = new Error("14 UNAVAILABLE: could not reach projects/p/databases/(default)/documents/whatsappOptOuts/919876543210");
        e.code = 14;
        throw e;
      }
      const pending = [];
      const txn = {
        get: (r) => r.get(),
        set: (r, data, opts) => { pending.push([r, data, opts]); },
      };
      const result = await fn(txn);
      for (const [r, data, opts] of pending) await r.set(data, opts);
      return result;
    },
  };
  // serverTimestamp stand-in: a rising number, so "first write wins" is observable
  const serverTimestamp = () => ++tick;
  return { db, store, state, serverTimestamp };
}

function makeLogger() {
  const lines = [];
  const push = (level) => (...args) => lines.push(level + " " + JSON.stringify(args));
  return { lines, info: push("info"), warn: push("warn"), error: push("error") };
}

function makeRes() {
  return {
    statusCode: null, headers: {}, body: null,
    status(c) { this.statusCode = c; return this; },
    set(k, v) { this.headers[String(k).toLowerCase()] = v; return this; },
    send(b) { this.body = b; return this; },
  };
}

function makeReq({ method = "POST", query = {}, headers = {}, rawBody } = {}) {
  const h = {};
  for (const k of Object.keys(headers)) h[k.toLowerCase()] = headers[k];
  return { method, query, rawBody, get: (n) => h[String(n).toLowerCase()] };
}

const sign = (raw, secret) =>
  "sha256=" + crypto.createHmac("sha256", secret).update(raw).digest("hex");

// ---- WhatsApp payload builders (the shapes Meta sends) ----
const wrap = (value) => ({
  object: "whatsapp_business_account",
  entry: [{ id: "WABA_ID", changes: [{ field: "messages", value }] }],
});
const textMsg = (from, body, type = "text") => ({
  from, id: "wamid.IN" + Math.random().toString(36).slice(2), timestamp: "1700000000",
  type, text: { body },
});
const statusEvt = (id, status, extra = {}) => ({
  id, status, timestamp: "1700000100", recipient_id: "919876543210", ...extra,
});
const raw = (obj) => Buffer.from(JSON.stringify(obj), "utf8");

module.exports = { makeFirestore, makeLogger, makeRes, makeReq, sign, wrap, textMsg, statusEvt, raw };
