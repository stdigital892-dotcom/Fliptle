"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const { normalizeIndianNumber, optOutKey } = require("../phone");
const v = require("./phone-vectors.json");

test("Indian numbers in every common spelling normalise to 91 + 10 digits", () => {
  for (const { input, expected } of v.indian) {
    assert.equal(normalizeIndianNumber(input), expected, JSON.stringify(input));
  }
});

test("things that are not Indian mobile numbers return null", () => {
  for (const input of v.notIndian) {
    assert.equal(normalizeIndianNumber(input), null, JSON.stringify(input));
  }
});

test("optOutKey: Indian canonical form, other countries keep their digits, junk is null", () => {
  for (const { input, key } of v.optOutKey) {
    assert.equal(optOutKey(input), key, JSON.stringify(input));
  }
});

test("different spellings of one number give one key", () => {
  const spellings = ["+91 98765 43210", "09876543210", "9876543210", "919876543210", "0091-98765-43210"];
  assert.equal(new Set(spellings.map(optOutKey)).size, 1);
});
