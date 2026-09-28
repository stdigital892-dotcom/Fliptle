package com.fliptle.app.auth

import android.content.Context

/**
 * Local cache of the last known [EntitlementGate] result. Three possible states:
 *
 *  • UNKNOWN  — no server response has ever succeeded on this device. First-run
 *               state; also the state after a wipe. Callers should treat this
 *               as NOT entitled for enforcement but must NOT show an "expired"
 *               UI (nothing has expired — we simply don't know yet).
 *  • ENTITLED — server explicitly said entitled=true. Cached until a later
 *               server call explicitly says otherwise.
 *  • DENIED   — server explicitly said entitled=false. This is the only path
 *               into DENIED; network failures never write here.
 *
 * The last-known state is preserved across app opens; a network failure or
 * server timeout deliberately does NOT overwrite it. See [EntitlementGate].
 */
class EntitlementStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("entitlement", Context.MODE_PRIVATE)

    enum class State { UNKNOWN, ENTITLED, DENIED }

    val state: State
        get() = when (prefs.getString(KEY_STATE, null)) {
            "ENTITLED" -> State.ENTITLED
            "DENIED" -> State.DENIED
            else -> State.UNKNOWN
        }

    /** "paid" | "trial" | "tester" | "expired" | "none" | null (unknown). */
    val reason: String?
        get() = prefs.getString(KEY_REASON, null)

    /** Server-computed expiry in epoch millis, if the server returned one. */
    val expiresAt: Long?
        get() = prefs.getLong(KEY_EXPIRES_AT, -1L).takeIf { it > 0L }

    /** epoch millis of the last successful server response. */
    val lastCheckedAt: Long
        get() = prefs.getLong(KEY_CHECKED_AT, 0L)

    fun recordEntitled(reason: String, expiresAtMs: Long?, nowMs: Long) {
        prefs.edit()
            .putString(KEY_STATE, "ENTITLED")
            .putString(KEY_REASON, reason)
            .putLong(KEY_EXPIRES_AT, expiresAtMs ?: -1L)
            .putLong(KEY_CHECKED_AT, nowMs)
            .apply()
    }

    fun recordDenied(reason: String, nowMs: Long) {
        prefs.edit()
            .putString(KEY_STATE, "DENIED")
            .putString(KEY_REASON, reason)
            .putLong(KEY_EXPIRES_AT, -1L)
            .putLong(KEY_CHECKED_AT, nowMs)
            .apply()
    }

    /** Clear on sign-out so the next signed-in user starts fresh. */
    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_STATE = "state"
        private const val KEY_REASON = "reason"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_CHECKED_AT = "checked_at"
    }
}
