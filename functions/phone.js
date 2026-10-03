"use strict";

// The ONE place phone numbers are canonicalised for WhatsApp opt-outs.
//
// Everything that stores, looks up or sends to a number must go through
// optOutKey(): the webhook (when it records a STOP), isOptedOut(), and the
// future alert sender. If any of them used its own formatting, an opted-out
// number could slip through under a different spelling.
//
// The expected results live in test/phone-vectors.json so a Kotlin port (should
// the app ever need one) can be checked against the same list.

/**
 * Indian mobile number in any common spelling -> "91" + 10 digits, or null.
 *
 * Accepts: "+91 98765 43210", "+91-98765-43210", "919876543210", "9876543210",
 * "09876543210" (leading 0), "0091 98765 43210" (00 prefix), "+91 09876543210".
 * Spaces, dashes, dots and brackets are ignored. The 10-digit part must be a
 * mobile number (starts with 6-9); anything else returns null.
 */
function normalizeIndianNumber(input) {
  if (input == null) return null;
  let d = String(input).replace(/\D/g, "");
  if (d.startsWith("00")) d = d.slice(2);                        // international prefix
  if (d.length === 13 && d.startsWith("910")) d = "91" + d.slice(3); // +91 0XXXXXXXXXX
  if (d.length === 12 && d.startsWith("91")) d = d.slice(2);     // country code
  else if (d.length === 11 && d.startsWith("0")) d = d.slice(1); // trunk 0
  if (!/^[6-9]\d{9}$/.test(d)) return null;
  return "91" + d;
}

/**
 * The key used for the opt-out collection and for isOptedOut().
 *
 * An Indian number becomes "91XXXXXXXXXX". A number that is not Indian (WhatsApp
 * reports senders with their country code, digits only) falls back to its digits
 * so that a STOP from anyone is still honoured and never silently dropped.
 * Returns null for input that can't be a phone number.
 */
function optOutKey(input) {
  const indian = normalizeIndianNumber(input);
  if (indian) return indian;
  const d = String(input == null ? "" : input).replace(/\D/g, "");
  return d.length >= 8 && d.length <= 15 && !d.startsWith("0") ? d : null;
}

module.exports = { normalizeIndianNumber, optOutKey };
