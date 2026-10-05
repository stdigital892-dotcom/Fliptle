package com.fliptle.app

/**
 * The Kotlin twin of normalizeIndianNumber() in functions/phone.js — the ONE
 * normaliser used anywhere a WhatsApp number is handled. Changes here MUST
 * stay in step with that file; functions/test/phone-vectors.json holds the
 * agreed inputs/outputs for a future shared-source check.
 *
 * Accepts "+91 98765 43210", "+91-98765-43210", "9876543210", "919876543210",
 * "09876543210" (leading 0), "0091 98765 43210" (00 prefix). Spaces, dashes,
 * dots and brackets are ignored. The 10-digit part must be a MOBILE (starts
 * with 6-9); anything else returns null.
 *
 * Returns "91" + 10 digits, or null.
 */
object PartnerPhone {

    fun normalizeIndianNumber(input: String?): String? {
        if (input == null) return null
        var d = input.filter { it.isDigit() }
        if (d.startsWith("00")) d = d.drop(2)
        if (d.length == 13 && d.startsWith("910")) d = "91" + d.drop(3)
        if (d.length == 12 && d.startsWith("91")) d = d.drop(2)
        else if (d.length == 11 && d.startsWith("0")) d = d.drop(1)
        if (!Regex("^[6-9][0-9]{9}$").matches(d)) return null
        return "91$d"
    }
}
