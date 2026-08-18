package com.fliptle.app

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

/**
 * State for the "Request to uninstall" math gate: 10 questions/day for 5 days,
 * one set per day. The per-day unlock uses the SAME tamper resistance as the
 * freeze — monotonic elapsedRealtime + a stored wall anchor + the OS boot counter
 * — so changing the system clock can't skip days, and a reboot is handled by
 * re-anchoring against trusted network time (VERIFYING until then).
 *
 * Missing a day defaults to PAUSE (resume where left off); a configurable strict
 * mode instead resets progress if a whole extra day window is missed.
 */
class UninstallGateStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("uninstall_gate", Context.MODE_PRIVATE)

    enum class State { INACTIVE, AVAILABLE, LOCKED, VERIFYING, APPROVED }

    val active: Boolean get() = prefs.getBoolean(KEY_ACTIVE, false)
    val approved: Boolean get() = prefs.getBoolean(KEY_APPROVED, false)
    val daysDone: Int get() = prefs.getInt(KEY_DAYS, 0)

    /** Short "days" for testing. Reads false unless DevMode is unlocked, so a flag
     *  left set can never shorten the real 5-day gate in a shipped build. */
    var debugMode: Boolean
        get() = DevMode.enabled(appContext) && prefs.getBoolean(KEY_DEBUG, false)
        set(v) = prefs.edit().putBoolean(KEY_DEBUG, v).apply()

    /** Strict mode: reset to day 0 if a whole extra day window is missed. */
    var resetOnMiss: Boolean
        get() = prefs.getBoolean(KEY_RESET_ON_MISS, false)
        set(v) = prefs.edit().putBoolean(KEY_RESET_ON_MISS, v).apply()

    fun unitMs(): Long = if (debugMode) DEBUG_DAY_MS else DAY_MS

    fun start() {
        prefs.edit()
            .putBoolean(KEY_ACTIVE, true)
            .putInt(KEY_DAYS, 0)
            .putBoolean(KEY_APPROVED, false)
            .remove(KEY_ELAPSED_ANCHOR).remove(KEY_ELAPSED_UNLOCK)
            .remove(KEY_WALL_UNLOCK).remove(KEY_BOOT_COUNT).remove(KEY_LAST_COMPLETE)
            .apply()
    }

    fun cancel() {
        prefs.edit().clear().apply()
    }

    fun state(): State {
        if (!active) return State.INACTIVE
        if (daysDone >= DAYS_REQUIRED || approved) return State.APPROVED
        applyMissIfConfigured()
        if (daysDone == 0) return State.AVAILABLE // first set is immediate
        if (rebooted()) return State.VERIFYING
        return if (SystemClock.elapsedRealtime() >= elapsedUnlock()) State.AVAILABLE else State.LOCKED
    }

    /** Milliseconds until the next set unlocks (0 if available; only valid in LOCKED). */
    fun remainingMs(): Long {
        if (rebooted()) return 0L
        return (elapsedUnlock() - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
    }

    /** Record a completed day; returns the new completed-day count. Sets the next lock. */
    fun completeDay(): Int {
        val done = (daysDone + 1).coerceAtMost(DAYS_REQUIRED)
        val editor = prefs.edit().putInt(KEY_DAYS, done)
            .putLong(KEY_LAST_COMPLETE, System.currentTimeMillis())
        if (done >= DAYS_REQUIRED) {
            editor.putBoolean(KEY_APPROVED, true)
        } else {
            val nowElapsed = SystemClock.elapsedRealtime()
            editor.putLong(KEY_ELAPSED_ANCHOR, nowElapsed)
                .putLong(KEY_ELAPSED_UNLOCK, nowElapsed + unitMs())
                .putLong(KEY_WALL_UNLOCK, System.currentTimeMillis() + unitMs())
                .putInt(KEY_BOOT_COUNT, currentBootCount())
        }
        editor.apply()
        return done
    }

    /** Re-anchor the monotonic unlock from trusted time after a reboot. */
    fun applyTrustedTime(trustedNow: Long) {
        if (!active || daysDone == 0 || daysDone >= DAYS_REQUIRED) return
        val remaining = (prefs.getLong(KEY_WALL_UNLOCK, 0L) - trustedNow).coerceAtLeast(0L)
        val nowElapsed = SystemClock.elapsedRealtime()
        prefs.edit()
            .putLong(KEY_ELAPSED_ANCHOR, nowElapsed)
            .putLong(KEY_ELAPSED_UNLOCK, nowElapsed + remaining)
            .putInt(KEY_BOOT_COUNT, currentBootCount())
            .apply()
    }

    private fun applyMissIfConfigured() {
        if (!resetOnMiss || daysDone == 0 || daysDone >= DAYS_REQUIRED) return
        val last = prefs.getLong(KEY_LAST_COMPLETE, 0L)
        if (last == 0L) return
        // Missed if more than TWO full day windows elapsed since the last completion.
        if (System.currentTimeMillis() - last > unitMs() * 2) {
            prefs.edit().putInt(KEY_DAYS, 0)
                .remove(KEY_ELAPSED_ANCHOR).remove(KEY_ELAPSED_UNLOCK)
                .remove(KEY_WALL_UNLOCK).remove(KEY_BOOT_COUNT).apply()
        }
    }

    private fun elapsedUnlock(): Long = prefs.getLong(KEY_ELAPSED_UNLOCK, 0L)

    private fun rebooted(): Boolean {
        val storedBoot = prefs.getInt(KEY_BOOT_COUNT, -1)
        val curBoot = currentBootCount()
        val bootChanged = storedBoot >= 0 && curBoot >= 0 && curBoot != storedBoot
        val monotonicReset = SystemClock.elapsedRealtime() < prefs.getLong(KEY_ELAPSED_ANCHOR, 0L)
        return bootChanged || monotonicReset
    }

    private fun currentBootCount(): Int =
        Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT, -1)

    companion object {
        const val DAYS_REQUIRED = 5
        private const val DAY_MS = 86_400_000L
        private const val DEBUG_DAY_MS = 120_000L // 2 minutes per "day" for testing

        private const val KEY_ACTIVE = "active"
        private const val KEY_DAYS = "days_done"
        private const val KEY_APPROVED = "approved"
        private const val KEY_DEBUG = "debug"
        private const val KEY_RESET_ON_MISS = "reset_on_miss"
        private const val KEY_ELAPSED_ANCHOR = "elapsed_anchor"
        private const val KEY_ELAPSED_UNLOCK = "elapsed_unlock"
        private const val KEY_WALL_UNLOCK = "wall_unlock"
        private const val KEY_BOOT_COUNT = "boot_count"
        private const val KEY_LAST_COMPLETE = "last_complete"
    }
}
