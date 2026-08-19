package com.fliptle.app

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

/**
 * State for the "Request to uninstall" gate: BOTH gates (10 math questions +
 * typing 1..50) each day for 5 days, one set per day. The per-day unlock uses the
 * SAME tamper resistance as the freeze — monotonic elapsedRealtime + a stored wall
 * anchor + the OS boot counter — so changing the system clock can't skip days, and
 * a reboot is handled by re-anchoring against trusted network time (VERIFYING
 * until then).
 *
 * Missing a day is a HARD RESET (no pause, no configuration): each day unlocks 24h
 * after the previous day's completion and must then be completed within the next
 * 24h. Let that window lapse — i.e. a full extra day passes with the set available
 * but not done — and progress resets all the way to day 1. The 24h window is timed
 * with the same reboot-proof monotonic anchor, so the reset can't be dodged by
 * changing the clock or rebooting.
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

    fun unitMs(): Long = if (debugMode) DEBUG_DAY_MS else DAY_MS

    /** True if the most recent [state] evaluation reset progress on a missed day. */
    val justReset: Boolean get() = prefs.getBoolean(KEY_JUST_RESET, false)

    fun clearJustReset() = prefs.edit().putBoolean(KEY_JUST_RESET, false).apply()

    fun start() {
        prefs.edit()
            .putBoolean(KEY_ACTIVE, true)
            .putInt(KEY_DAYS, 0)
            .putBoolean(KEY_APPROVED, false)
            .putBoolean(KEY_JUST_RESET, false)
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
        applyMissReset()
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

    /**
     * HARD RESET on a missed day (always on — no pause, no config). A day unlocks
     * one window (24h) after the last completion and must be done within the next
     * window; if TWO full windows pass with the set still not completed, the day
     * was missed and all progress resets to day 1.
     *
     * Timing is reboot-proof: within a boot the monotonic elapsed clock is
     * authoritative; a reboot (VERIFYING) suspends the miss check until trusted
     * network time re-anchors, so the reset can be neither dodged nor forced early
     * by changing the clock or rebooting.
     */
    private fun applyMissReset() {
        if (daysDone == 0 || daysDone >= DAYS_REQUIRED) return
        if (rebooted()) return // don't judge a miss on an untrusted post-reboot clock
        if (prefs.getLong(KEY_ELAPSED_ANCHOR, 0L) == 0L) return
        // Deadline = unlock + one more window. elapsedUnlock() is re-anchored from
        // trusted wall time after a reboot (see applyTrustedTime), so this deadline
        // is correct within and across boots.
        val deadline = elapsedUnlock() + unitMs()
        if (SystemClock.elapsedRealtime() > deadline) {
            prefs.edit().putInt(KEY_DAYS, 0)
                .putBoolean(KEY_JUST_RESET, true)
                .remove(KEY_ELAPSED_ANCHOR).remove(KEY_ELAPSED_UNLOCK)
                .remove(KEY_WALL_UNLOCK).remove(KEY_BOOT_COUNT).remove(KEY_LAST_COMPLETE)
                .apply()
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
        private const val KEY_JUST_RESET = "just_reset"
        private const val KEY_ELAPSED_ANCHOR = "elapsed_anchor"
        private const val KEY_ELAPSED_UNLOCK = "elapsed_unlock"
        private const val KEY_WALL_UNLOCK = "wall_unlock"
        private const val KEY_BOOT_COUNT = "boot_count"
        private const val KEY_LAST_COMPLETE = "last_complete"
    }
}
