package com.fliptle.app

import android.content.Context

/**
 * ONE-WAY switch for adult-content blocking, plus the "Day X" streak.
 *
 * Deliberately has NO disable path. There is no setter, no clear(), no settings
 * screen entry, and nothing anywhere in the app calls anything that could turn it
 * back off — [enable] is the only mutator and it only ever writes `true`. Once on,
 * the sole way to remove the blocking is uninstalling the app (which wipes these
 * SharedPreferences along with everything else, and is itself gated behind the
 * 5-day uninstall-request flow).
 *
 * Day counter: count-UP only.
 *  - Day 1 is the day it was enabled; it increments every 24h thereafter.
 *  - [KEY_MAX_DAYS] latches the highest day ever seen, so winding the phone clock
 *    BACKWARD cannot shrink the streak — the displayed value never decreases.
 *  - Winding the clock FORWARD can only inflate it, which is harmless (it grants
 *    no access), so no trusted-time round trip is needed here.
 *  - There is no reset: no slip logic, no zeroing, no "start over" call exists.
 */
class PornBlockStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("porn_block", Context.MODE_PRIVATE)

    /** Once true, permanently true for the life of the install. */
    val enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)

    /**
     * Turn adult-content blocking on. Irreversible: calling it again after it is
     * already on is a no-op and never re-anchors the day counter.
     */
    fun enable() {
        if (enabled) return
        prefs.edit()
            .putBoolean(KEY_ENABLED, true)
            .putLong(KEY_ENABLED_AT, System.currentTimeMillis())
            .putInt(KEY_MAX_DAYS, 1)
            .apply()
    }

    /**
     * Days since blocking was enabled, starting at day 1, monotonically
     * non-decreasing. Returns 0 when blocking has never been enabled.
     */
    fun dayCount(): Int {
        if (!enabled) return 0
        val since = System.currentTimeMillis() - prefs.getLong(KEY_ENABLED_AT, 0L)
        val computed = (since / DAY_MS).toInt() + 1
        val latched = prefs.getInt(KEY_MAX_DAYS, 1)
        // Ratchet: only ever move the stored high-water mark upward.
        val day = if (computed > latched) {
            prefs.edit().putInt(KEY_MAX_DAYS, computed).apply()
            computed
        } else {
            latched
        }
        return day.coerceAtLeast(1)
    }

    /** Portable state for cloud backup (empty when never enabled). */
    fun backupState(): Map<String, Any?> =
        if (!enabled) emptyMap()
        else mapOf(
            "pornEnabled" to true,
            "pornEnabledAt" to prefs.getLong(KEY_ENABLED_AT, 0L),
            "pornMaxDays" to prefs.getInt(KEY_MAX_DAYS, 1)
        )

    /**
     * Restore from a cloud backup after signing in on a fresh install. Only ever
     * turns blocking ON (never off) and never re-anchors an already-enabled local
     * copy, preserving the one-way guarantee and the existing streak.
     */
    fun restore(enabledAt: Long, maxDays: Int) {
        if (enabled) return
        prefs.edit()
            .putBoolean(KEY_ENABLED, true)
            .putLong(KEY_ENABLED_AT, if (enabledAt > 0L) enabledAt else System.currentTimeMillis())
            .putInt(KEY_MAX_DAYS, maxDays.coerceAtLeast(1))
            .apply()
    }

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_ENABLED_AT = "enabled_at"
        private const val KEY_MAX_DAYS = "max_days"
        private const val DAY_MS = 86_400_000L
    }
}
