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

    // ---- the shared validation rule set (D1) ----
    //
    // Beyond "is this syntactically a valid Indian mobile", reject numbers that
    // are almost certainly typos or placeholders. This mirrors
    // functions/partner.js's validatePartnerPhone() exactly, EXCEPT the "equals
    // the WhatsApp business number" check: that number is a server secret the
    // app never has, so the server is the only place that rule can run (it
    // always re-validates on savePartner).

    private fun allSameDigit(ten: String): Boolean = ten.toSet().size == 1

    private fun isSequentialRun(ten: String): Boolean {
        var ascending = true
        var descending = true
        for (i in 1 until ten.length) {
            val diff = ten[i] - ten[i - 1]
            if (diff != 1) ascending = false
            if (diff != -1) descending = false
        }
        return ascending || descending
    }

    private fun distinctDigitCount(ten: String): Int = ten.toSet().size

    /** True if [input] passes every LOCALLY checkable rule (shape + the three fake-number rules). */
    fun isValidPartnerNumber(input: String?): Boolean {
        val normalized = normalizeIndianNumber(input) ?: return false
        val ten = normalized.substring(2)
        if (allSameDigit(ten)) return false
        if (isSequentialRun(ten)) return false
        if (distinctDigitCount(ten) < 4) return false
        return true
    }

    /** The normalized "91"+10 digit number if [input] passes every local rule, else null. */
    fun normalizedIfValid(input: String?): String? {
        val normalized = normalizeIndianNumber(input) ?: return null
        return if (isValidPartnerNumber(input)) normalized else null
    }
}
