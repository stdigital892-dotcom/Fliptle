package com.fliptle.app.auth

/**
 * Which name to suggest in the (optional) name step.
 *
 * Priority, first non-blank wins: what is already typed in the field, the name
 * saved on the account (installs/{uid}.displayName), then the Google account's
 * display name. The result is trimmed and capped at [MAX_LENGTH] (the field's
 * own limit). Null when there is nothing to suggest, in which case the field
 * stays blank and leaving it blank still skips the step.
 */
object NamePrefill {

    const val MAX_LENGTH = 40

    fun choose(typed: String?, saved: String?, google: String?): String? {
        for (candidate in listOf(typed, saved, google)) {
            val name = candidate?.trim()
            if (!name.isNullOrEmpty()) return name.take(MAX_LENGTH).trim()
        }
        return null
    }
}
