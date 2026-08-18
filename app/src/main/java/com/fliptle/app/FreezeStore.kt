package com.fliptle.app

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

/**
 * The commitment engine: a FIXED 3-DAY CYCLE that locks the things you committed
 * to — blocked apps, blocked domains, and the Reels/Shorts allowance.
 *
 * Lifecycle:
 *   NONE      -> nothing committed yet.
 *   LOCKED    -> day 1-2. Settings CANNOT be changed. Blocking is enforced.
 *   REVIEW    -> day 3 and onward. Blocking KEEPS RUNNING at the current settings;
 *                the user may now change something. Changing anything calls
 *                [restartCycle] and immediately begins a fresh 3-day lock.
 *   VERIFYING -> a reboot was detected and the elapsed time cannot be trusted yet.
 *                Fails CLOSED: treated as locked until a trusted time re-anchors.
 *
 * It NEVER auto-unlocks. Ignoring the day-3 review simply leaves the freeze
 * running (day 4, 5, 6 …) at the same settings until the user actively returns
 * and changes something. There is no "cancel" and no expiry — the only exits are
 * changing a setting at review (which restarts the cycle) or uninstalling.
 *
 * Anti-tamper (unchanged from the previous design, and still the reason the lock
 * holds): the lock deadline is anchored on the monotonic clock
 * (SystemClock.elapsedRealtime), which no clock change can move; a wall-clock
 * mirror plus Settings.Global.BOOT_COUNT detects reboots; and only a TRUSTED
 * network time ([rebootReanchor]) may re-anchor after a reboot. Offline after a
 * reboot means the lock stays on — a reboot makes the app stricter, never looser.
 */
class FreezeStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("freeze_prefs", Context.MODE_PRIVATE)

    enum class State { NONE, LOCKED, REVIEW, VERIFYING }

    /** True whenever a commitment is running — LOCKED, REVIEW or VERIFYING. */
    val active: Boolean
        get() = prefs.getBoolean(KEY_ACTIVE, false)

    /** True once the deadline has been confirmed against a trusted network time. */
    val wallTrusted: Boolean
        get() = prefs.getBoolean(KEY_WALL_TRUSTED, false)

    /** Length of one cycle in ms (short in developer mode for testing). */
    fun cycleMs(): Long =
        if (DevMode.enabled(appContext)) DEBUG_CYCLE_MS else CYCLE_DAYS * DAY_MS

    private fun unitMs(): Long =
        if (DevMode.enabled(appContext)) DEBUG_UNIT_MS else DAY_MS

    /**
     * Begin the first 3-day cycle. No-op if one is already running — use
     * [restartCycle] for the day-3 "I changed something" path.
     */
    fun startCycle() {
        if (active) return
        anchorNow()
    }

    /**
     * The user actively changed a committed setting at review: start a fresh
     * 3-day lock immediately. Allowed only when settings are changeable, so a
     * locked cycle can never be shortened by "changing" something.
     */
    fun restartCycle(): Boolean {
        if (active && settingsLocked()) return false
        anchorNow()
        return true
    }

    private fun anchorNow() {
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        val duration = cycleMs()
        prefs.edit()
            .putBoolean(KEY_ACTIVE, true)
            .putLong(KEY_ELAPSED_START, nowElapsed)
            .putLong(KEY_ELAPSED_UNLOCK, nowElapsed + duration)
            .putLong(KEY_WALL_START, nowWall)
            .putLong(KEY_WALL_UNLOCK, nowWall + duration)
            .putInt(KEY_BOOT_COUNT, currentBootCount())
            .putBoolean(KEY_WALL_TRUSTED, false)
            .putInt(KEY_MAX_DAY, 1)
            .apply()
    }

    fun state(): State {
        if (!active) return State.NONE
        if (rebooted()) return State.VERIFYING // fail closed: never trust the local clock here
        return if (remainingLockMs() > 0L) State.LOCKED else State.REVIEW
    }

    /**
     * Whether the committed settings (apps, domains, Reels allowance) are frozen.
     * True while LOCKED and while VERIFYING — the safe direction in both cases.
     */
    fun settingsLocked(): Boolean = when (state()) {
        State.LOCKED, State.VERIFYING -> true
        State.NONE, State.REVIEW -> false
    }

    /** Time left until the review opens; 0 once it is open. */
    fun remainingMs(): Long {
        if (!active || rebooted()) return 0L
        return remainingLockMs()
    }

    /**
     * Which day of the commitment this is, 1-based. Keeps counting past day 3
     * (day 4, 5, 6 …) when the review is ignored. Ratcheted so it never goes
     * backward if the clock is wound back.
     */
    fun dayNumber(): Int {
        if (!active) return 0
        val latched = prefs.getInt(KEY_MAX_DAY, 1)
        // After a reboot the monotonic anchor is gone; hold the last known day
        // rather than recomputing from an untrusted clock.
        if (rebooted()) return latched
        val since = SystemClock.elapsedRealtime() - prefs.getLong(KEY_ELAPSED_START, 0L)
        val computed = (since / unitMs()).toInt() + 1
        if (computed > latched) {
            prefs.edit().putInt(KEY_MAX_DAY, computed).apply()
            return computed
        }
        return latched
    }

    /** Total days in one cycle, for display ("day 2 of 3"). */
    fun cycleDays(): Int = CYCLE_DAYS

    /**
     * Correct the wall-clock mirror against a trusted time while still inside the
     * same boot (the monotonic anchor is intact and stays authoritative).
     */
    fun applyTrustedTimeSameBoot(trustedNow: Long) {
        if (!active || rebooted()) return
        val remaining = remainingLockMs()
        prefs.edit()
            .putLong(KEY_WALL_UNLOCK, trustedNow + remaining)
            .putBoolean(KEY_WALL_TRUSTED, true)
            .apply()
    }

    /**
     * Re-anchor after a reboot from a TRUSTED network time — the only path out of
     * VERIFYING. Rebuilds the monotonic anchor from the surviving wall deadline.
     */
    fun rebootReanchor(trustedNow: Long) {
        if (!active) return
        val nowElapsed = SystemClock.elapsedRealtime()
        val remaining = (prefs.getLong(KEY_WALL_UNLOCK, 0L) - trustedNow).coerceAtLeast(0L)
        val elapsedSoFar = (trustedNow - prefs.getLong(KEY_WALL_START, 0L)).coerceAtLeast(0L)
        prefs.edit()
            // Keep the original start on the timeline so the day counter continues.
            .putLong(KEY_ELAPSED_START, nowElapsed - elapsedSoFar)
            .putLong(KEY_ELAPSED_UNLOCK, nowElapsed + remaining)
            .putInt(KEY_BOOT_COUNT, currentBootCount()) // commit this boot
            .putBoolean(KEY_WALL_TRUSTED, true)
            .apply()
    }

    private fun remainingLockMs(): Long =
        (prefs.getLong(KEY_ELAPSED_UNLOCK, 0L) - SystemClock.elapsedRealtime()).coerceAtLeast(0L)

    /**
     * A reboot is detected when the OS boot counter changed, or the monotonic
     * clock reset below the recorded start. BOOT_COUNT is authoritative when
     * available; the elapsed check is a secondary guard.
     */
    private fun rebooted(): Boolean {
        val storedBoot = prefs.getInt(KEY_BOOT_COUNT, -1)
        val currentBoot = currentBootCount()
        val bootChanged = storedBoot >= 0 && currentBoot >= 0 && currentBoot != storedBoot
        val monotonicReset = SystemClock.elapsedRealtime() < prefs.getLong(KEY_ELAPSED_START, 0L)
        return bootChanged || monotonicReset
    }

    private fun currentBootCount(): Int =
        Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT, -1)

    companion object {
        /** Fixed commitment length. Not user-configurable by design. */
        const val CYCLE_DAYS = 3

        private const val DAY_MS = 86_400_000L
        // Developer-mode timings: a "day" is a minute, so a cycle runs in 3 minutes.
        private const val DEBUG_UNIT_MS = 60_000L
        private const val DEBUG_CYCLE_MS = CYCLE_DAYS * DEBUG_UNIT_MS

        private const val KEY_ACTIVE = "active"
        private const val KEY_ELAPSED_START = "elapsed_start"
        private const val KEY_ELAPSED_UNLOCK = "elapsed_unlock"
        private const val KEY_WALL_START = "wall_start"
        private const val KEY_WALL_UNLOCK = "wall_unlock"
        private const val KEY_BOOT_COUNT = "boot_count"
        private const val KEY_WALL_TRUSTED = "wall_trusted"
        private const val KEY_MAX_DAY = "max_day"
    }
}
