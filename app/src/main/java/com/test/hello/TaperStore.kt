package com.test.hello

import android.content.Context

/**
 * Progressive taper for the freeze system.
 *
 * A "completed cycle" = a freeze runs to completion AND the user actively
 * confirms it (in the REVIEW state). Each completed cycle lengthens the freeze
 * delay by one tier, starting at 1 day and capped at 7 days.
 *
 * Rules:
 *  - The tier only ever RISES or HOLDS. A slip, a missed cycle, or forgetting to
 *    confirm never resets it, never shortens it, never punishes.
 *  - Advancement requires an ACTIVE confirm of a completed taper freeze; simply
 *    forgetting to confirm holds the tier (and blocks stay on — fail closed).
 *
 * State is a small set of counters/flags in SharedPreferences, so it survives
 * reboot trivially. The freeze DURATION derived from the tier is enforced by
 * [FreezeStore], which already provides the monotonic + trusted-time + boot-count
 * anti-tamper — so taper freezes inherit that same protection.
 */
class TaperStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("taper", Context.MODE_PRIVATE)

    var debugMode: Boolean
        get() = prefs.getBoolean(KEY_DEBUG, false)
        set(value) = prefs.edit().putBoolean(KEY_DEBUG, value).apply()

    private var taperFreezeActive: Boolean
        get() = prefs.getBoolean(KEY_TAPER_ACTIVE, false)
        set(value) = prefs.edit().putBoolean(KEY_TAPER_ACTIVE, value).apply()

    fun completedCycles(): Int = prefs.getInt(KEY_CYCLES, 0)

    /** Current tier in "days" (1..7). */
    fun currentTier(): Int = (START_TIER + completedCycles()).coerceAtMost(MAX_TIER)

    fun nextTier(): Int = (currentTier() + 1).coerceAtMost(MAX_TIER)

    fun isAtMax(): Boolean = currentTier() >= MAX_TIER

    /** Milliseconds per tier unit — a day normally, a short unit in debug mode. */
    fun unitMillis(): Long = if (debugMode) DEBUG_UNIT_MS else DAY_MS

    /** Duration of a freeze started at the current tier. */
    fun currentDurationMs(): Long = currentTier() * unitMillis()

    /** Call when a taper freeze is started (marks the active freeze as a taper one). */
    fun onTaperFreezeStarted() {
        taperFreezeActive = true
    }

    /** Call when a plain manual freeze is started (so it is NOT counted as a cycle). */
    fun onManualFreezeStarted() {
        taperFreezeActive = false
    }

    /**
     * Call when the user actively confirms a completed freeze. If it was a taper
     * freeze, advance the tier and return the NEW tier; otherwise return null
     * (holds — no advancement).
     */
    fun onCycleConfirmed(): Int? {
        if (!taperFreezeActive) return null
        prefs.edit()
            .putInt(KEY_CYCLES, completedCycles() + 1)
            .putBoolean(KEY_TAPER_ACTIVE, false)
            .apply()
        return currentTier()
    }

    companion object {
        private const val KEY_CYCLES = "completed_cycles"
        private const val KEY_DEBUG = "debug_mode"
        private const val KEY_TAPER_ACTIVE = "taper_freeze_active"

        const val START_TIER = 1
        const val MAX_TIER = 7
        private const val DAY_MS = 86_400_000L
        // In debug mode each tier is a short unit so cycles complete quickly.
        const val DEBUG_UNIT_MS = 15_000L
    }
}
