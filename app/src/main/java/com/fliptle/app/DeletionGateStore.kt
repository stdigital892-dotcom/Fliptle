package com.fliptle.app

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

/**
 * State for the account-deletion gate: BOTH gates each day (arithmetic questions,
 * then typing the number sequence) for [DeletionConfig.DAYS_REQUIRED] days, one
 * set per day. The per-day unlock uses the same tamper resistance as the freeze
 * and the uninstall gate: monotonic elapsedRealtime plus a stored wall anchor plus
 * the OS boot counter, so changing the clock can't skip days, and a reboot is
 * handled by re-anchoring against trusted network time (VERIFYING until then).
 *
 * Missing a day is a hard reset to day 1, exactly like the uninstall gate.
 *
 * This is its own store (its own file, its own keys): it never reads or writes
 * the uninstall gate. It is also tied to ONE account: the uid is saved with the
 * progress, and progress saved for a different uid is discarded on open, so an
 * account never sees another account's days.
 *
 * Nothing here can shorten the 72 hours. This only decides when the user may ASK;
 * the server sets the deletion time itself at the moment of the request.
 */
class DeletionGateStore(context: Context, private val uid: String) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("deletion_gate", Context.MODE_PRIVATE)

    enum class State { INACTIVE, AVAILABLE, LOCKED, VERIFYING, APPROVED }

    init {
        if (prefs.getString(KEY_UID, null) != uid) {
            prefs.edit().clear().putString(KEY_UID, uid).apply()
        }
    }

    val active: Boolean get() = prefs.getBoolean(KEY_ACTIVE, false)
    val approved: Boolean get() = prefs.getBoolean(KEY_APPROVED, false)
    val daysDone: Int get() = prefs.getInt(KEY_DAYS, 0)

    /** Short "days" for testing. Reads false unless DevMode is unlocked. */
    var debugMode: Boolean
        get() = DevMode.enabled(appContext) && prefs.getBoolean(KEY_DEBUG, false)
        set(v) = prefs.edit().putBoolean(KEY_DEBUG, v).apply()

    fun unitMs(): Long = if (debugMode) DEBUG_DAY_MS else DAY_MS

    val justReset: Boolean get() = prefs.getBoolean(KEY_JUST_RESET, false)

    fun clearJustReset() = prefs.edit().putBoolean(KEY_JUST_RESET, false).apply()

    /** Begin from day 1. */
    fun start() {
        prefs.edit()
            .putBoolean(KEY_ACTIVE, true)
            .putInt(KEY_DAYS, 0)
            .putBoolean(KEY_APPROVED, false)
            .putBoolean(KEY_JUST_RESET, false)
            .remove(KEY_ELAPSED_ANCHOR).remove(KEY_ELAPSED_UNLOCK)
            .remove(KEY_WALL_UNLOCK).remove(KEY_BOOT_COUNT)
            .apply()
    }

    /** Forget all progress (after a request is made, or when the user cancels). */
    fun cancel() {
        prefs.edit().clear().putString(KEY_UID, uid).apply()
    }

    fun state(): State {
        if (!active) return State.INACTIVE
        if (daysDone >= DeletionConfig.DAYS_REQUIRED || approved) return State.APPROVED
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
        val done = (daysDone + 1).coerceAtMost(DeletionConfig.DAYS_REQUIRED)
        val editor = prefs.edit().putInt(KEY_DAYS, done)
        if (done >= DeletionConfig.DAYS_REQUIRED) {
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
        if (!active || daysDone == 0 || daysDone >= DeletionConfig.DAYS_REQUIRED) return
        val remaining = (prefs.getLong(KEY_WALL_UNLOCK, 0L) - trustedNow).coerceAtLeast(0L)
        val nowElapsed = SystemClock.elapsedRealtime()
        prefs.edit()
            .putLong(KEY_ELAPSED_ANCHOR, nowElapsed)
            .putLong(KEY_ELAPSED_UNLOCK, nowElapsed + remaining)
            .putInt(KEY_BOOT_COUNT, currentBootCount())
            .apply()
    }

    /**
     * Hard reset on a missed day: a day unlocks one window (24h) after the last
     * completion and must be done within the next window; if two full windows pass
     * with the set still not completed, all progress resets to day 1. A reboot
     * suspends the check until trusted network time re-anchors it.
     */
    private fun applyMissReset() {
        if (daysDone == 0 || daysDone >= DeletionConfig.DAYS_REQUIRED) return
        if (rebooted()) return
        if (prefs.getLong(KEY_ELAPSED_ANCHOR, 0L) == 0L) return
        val deadline = elapsedUnlock() + unitMs()
        if (SystemClock.elapsedRealtime() > deadline) {
            prefs.edit().putInt(KEY_DAYS, 0)
                .putBoolean(KEY_JUST_RESET, true)
                .remove(KEY_ELAPSED_ANCHOR).remove(KEY_ELAPSED_UNLOCK)
                .remove(KEY_WALL_UNLOCK).remove(KEY_BOOT_COUNT)
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
        private const val DAY_MS = 86_400_000L
        private const val DEBUG_DAY_MS = 120_000L // 2 minutes per "day" for testing

        private const val KEY_UID = "uid"
        private const val KEY_ACTIVE = "active"
        private const val KEY_DAYS = "days_done"
        private const val KEY_APPROVED = "approved"
        private const val KEY_DEBUG = "debug"
        private const val KEY_JUST_RESET = "just_reset"
        private const val KEY_ELAPSED_ANCHOR = "elapsed_anchor"
        private const val KEY_ELAPSED_UNLOCK = "elapsed_unlock"
        private const val KEY_WALL_UNLOCK = "wall_unlock"
        private const val KEY_BOOT_COUNT = "boot_count"
    }
}
