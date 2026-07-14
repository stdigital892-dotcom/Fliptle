package com.test.hello

import android.content.Context
import android.os.SystemClock

/**
 * Persists freeze state in SharedPreferences (local storage) and computes the
 * remaining time in a way that resists both wall-clock tampering and reboots.
 *
 * Two anchors are stored when a freeze starts:
 *   - wallEnd:    absolute end on the wall clock (System.currentTimeMillis).
 *                 Survives reboot, but the user can change the system clock.
 *   - elapsedEnd: absolute end on the monotonic clock (SystemClock.elapsedRealtime).
 *                 Immune to wall-clock changes, but resets to 0 on reboot.
 *
 * Within a single boot, the monotonic clock is authoritative, so moving the
 * system clock forward or backward cannot skip the freeze.
 *
 * A reboot is detected when elapsedRealtime() is smaller than the elapsed value
 * recorded at start (the monotonic clock can only move backward across a boot).
 * On the first check after a reboot we re-anchor the monotonic clock from the
 * surviving wall-clock end, so the freeze persists across the reboot and is once
 * again protected against clock changes for the rest of the new boot.
 */
class FreezeStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("freeze_prefs", Context.MODE_PRIVATE)

    enum class State { NONE, FROZEN, REVIEW }

    var active: Boolean
        get() = prefs.getBoolean(KEY_ACTIVE, false)
        private set(value) = prefs.edit().putBoolean(KEY_ACTIVE, value).apply()

    /** Begin a freeze for [durationMs]. Overwrites any previous freeze. */
    fun start(durationMs: Long) {
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        prefs.edit()
            .putBoolean(KEY_ACTIVE, true)
            .putLong(KEY_ELAPSED_START, nowElapsed)
            .putLong(KEY_ELAPSED_END, nowElapsed + durationMs)
            .putLong(KEY_WALL_END, nowWall + durationMs)
            .apply()
    }

    /**
     * Extend (tighten) the freeze by [addMs]. Always allowed. If the freeze is
     * currently in REVIEW (already expired), this re-arms it starting from now.
     */
    fun extend(addMs: Long) {
        if (!active) return
        val remaining = remainingMs().coerceAtLeast(0L)
        val newRemaining = remaining + addMs
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        prefs.edit()
            .putLong(KEY_ELAPSED_START, nowElapsed)
            .putLong(KEY_ELAPSED_END, nowElapsed + newRemaining)
            .putLong(KEY_WALL_END, nowWall + newRemaining)
            .apply()
    }

    /** Only meaningful in REVIEW state: clear the freeze after the user confirms. */
    fun confirmClear() {
        prefs.edit().clear().apply()
    }

    /**
     * Remaining freeze time in milliseconds, clamped to >= 0. Performs reboot
     * detection and re-anchoring as a side effect.
     */
    fun remainingMs(): Long {
        if (!active) return 0L
        val nowElapsed = SystemClock.elapsedRealtime()
        val elapsedStart = prefs.getLong(KEY_ELAPSED_START, 0L)

        if (nowElapsed < elapsedStart) {
            // Monotonic clock went backwards -> the device rebooted.
            // Fall back to the surviving wall-clock end, then re-anchor the
            // monotonic clock so later clock changes in this boot are ignored.
            val remainingWall = (prefs.getLong(KEY_WALL_END, 0L) - System.currentTimeMillis())
                .coerceAtLeast(0L)
            prefs.edit()
                .putLong(KEY_ELAPSED_START, nowElapsed)
                .putLong(KEY_ELAPSED_END, nowElapsed + remainingWall)
                .apply()
            return remainingWall
        }

        return (prefs.getLong(KEY_ELAPSED_END, 0L) - nowElapsed).coerceAtLeast(0L)
    }

    fun state(): State = when {
        !active -> State.NONE
        remainingMs() > 0L -> State.FROZEN
        else -> State.REVIEW
    }

    companion object {
        private const val KEY_ACTIVE = "active"
        private const val KEY_ELAPSED_START = "elapsed_start"
        private const val KEY_ELAPSED_END = "elapsed_end"
        private const val KEY_WALL_END = "wall_end"
    }
}
