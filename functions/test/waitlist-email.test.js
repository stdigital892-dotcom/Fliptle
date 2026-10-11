"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const email = require("../waitlist-email");

const FORBIDDEN = [
  "/offer",
  "early access",
  "early-access",
  "plan",
  "₹",
  "pricing",
  "trial",
  "choose your plan",
  "discount",
  "offer",
];

test("subject line is a plain confirmation, no pricing or offer language", () => {
  assert.equal(email.SUBJECT, "You're on the Rescue waitlist");
});

test("HTML body confirms the waitlist and promises exactly one future email, nothing else", () => {
  const html = email.buildWaitlistHtml();
  assert.match(html, /on the waitlist/i);
  assert.match(html, /we'll email you once, when rescue launches/i);
  const lower = html.toLowerCase();
  for (const forbidden of FORBIDDEN) {
    assert.equal(lower.includes(forbidden.toLowerCase()), false, `HTML must not contain "${forbidden}"`);
  }
});

test("plain-text body matches the HTML in substance, with the same forbidden language absent", () => {
  const text = email.buildWaitlistText();
  assert.match(text, /on the waitlist/i);
  assert.match(text, /we'll email you once, when rescue launches/i);
  const lower = text.toLowerCase();
  for (const forbidden of FORBIDDEN) {
    assert.equal(lower.includes(forbidden.toLowerCase()), false, `text must not contain "${forbidden}"`);
  }
});

test("templates take no arguments and never embed a link (static content only)", () => {
  assert.equal(email.buildWaitlistHtml.length, 0);
  assert.equal(email.buildWaitlistText.length, 0);
  assert.equal(email.buildWaitlistHtml().includes("http"), false);
  assert.equal(email.buildWaitlistText().includes("http"), false);
  assert.equal(email.buildWaitlistHtml().includes("undefined"), false);
  assert.equal(email.buildWaitlistText().includes("undefined"), false);
});
