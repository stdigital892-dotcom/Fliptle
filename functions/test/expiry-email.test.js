"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const expiryEmail = require("../expiry-email");

const DAY = 24 * 60 * 60 * 1000;

test("copy rule: never says Buy or Purchase, only Choose a plan / Continue on fliptle.com", () => {
  const html = expiryEmail.buildHtml({ email: "a@x.com", planName: "Monthly", expiresAtMs: Date.now() });
  const text = expiryEmail.buildText({ email: "a@x.com", planName: "Monthly", expiresAtMs: Date.now() });
  for (const forbidden of ["Buy", "buy now", "Purchase", "purchase"]) {
    assert.equal(html.includes(forbidden), false, `html must not contain "${forbidden}"`);
    assert.equal(text.includes(forbidden), false, `text must not contain "${forbidden}"`);
  }
  assert.match(html, /Choose a plan/);
  assert.match(text, /Choose a plan/i);
});

test("link always points at offer.html with source=expiry and the right email", () => {
  const link = expiryEmail.expiryLink("a@x.com");
  assert.equal(link, "https://fliptle.com/offer?email=a%40x.com&source=expiry");
});

test("shouldAutoSend: fires once per distinct expiresAtMs, not twice for the same one", () => {
  const expiresAtMs = 1_700_000_000_000;
  assert.equal(expiryEmail.shouldAutoSend(null, expiresAtMs), true);
  assert.equal(expiryEmail.shouldAutoSend({}, expiresAtMs), true);
  assert.equal(expiryEmail.shouldAutoSend({ expiryRenewalEmailSentFor: expiresAtMs }, expiresAtMs), false);
  assert.equal(expiryEmail.shouldAutoSend({ expiryRenewalEmailSentFor: expiresAtMs + 1 }, expiresAtMs), true);
});

test("shouldAutoSend: never fires without a finite expiry", () => {
  assert.equal(expiryEmail.shouldAutoSend({}, NaN), false);
  assert.equal(expiryEmail.shouldAutoSend({}, undefined), false);
});

test("checkRateLimit: allows up to MAX_PER_DAY, then blocks for the rest of the day", () => {
  const now = Date.UTC(2026, 9, 10, 12, 0, 0);
  let install = null;
  for (let i = 0; i < expiryEmail.MAX_PER_DAY; i++) {
    const r = expiryEmail.checkRateLimit(install, now);
    assert.equal(r.allowed, true, `send #${i + 1} should be allowed`);
    install = { expiryEmailDayKey: r.dayKey, expiryEmailCountToday: r.nextCount };
  }
  const blocked = expiryEmail.checkRateLimit(install, now);
  assert.equal(blocked.allowed, false);
});

test("checkRateLimit: resets on a new day", () => {
  const day1 = Date.UTC(2026, 9, 10, 12, 0, 0);
  const day2 = day1 + DAY;
  const maxedOut = { expiryEmailDayKey: Math.floor(day1 / DAY), expiryEmailCountToday: expiryEmail.MAX_PER_DAY };
  assert.equal(expiryEmail.checkRateLimit(maxedOut, day1).allowed, false);
  assert.equal(expiryEmail.checkRateLimit(maxedOut, day2).allowed, true);
});

test("formatIstDate renders a short human date", () => {
  assert.equal(expiryEmail.formatIstDate(Date.UTC(2026, 9, 5, 5, 0, 0)), "5 Oct 2026");
});
