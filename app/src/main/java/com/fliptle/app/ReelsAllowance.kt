package com.fliptle.app

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

/**
 * The Reels/Shorts/Stories allowance — part of the freeze commitment, not a
 * separate system. (This replaces the old standalone "distraction access valve".)
 *
 * Defaults, which are what the commitment means in practice:
 *   • a session lasts [DEF_SESSION_MIN] minutes,
 *   • at most [DEF_PER_DAY] sessions per day,
 *   • with a [DEF_COOLDOWN_MIN]-minute cooldown between sessions.
 *
 * Porn is NEVER routed through this. It has no session, no allowance and no
 * cooldown — see [PornBlockStore], which is one-way and permanent.
 *
 * Change control now comes from the freeze itself: these numbers can only be
 * edited when [FreezeStore.settingsLocked] is false (i.e. before the first cycle,
 * or at a day-3+ review). Editing them at review restarts the 3-day cycle. That
 * replaces the old per-setting "loosening delay / staged pending change"
 * machinery, which the 3-day lock now covers.
 *
 * Session timing is reboot- and clock-tamper-proof in the same way as the freeze:
 * monotonic anchor + wall mirror + BOOT_COUNT, failing closed into VERIFYING.
 */
class ReelsAllowance(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("reels_allowance", Context.MODE_PRIVATE)

    enum class State { LOCKED, COOLDOWN, OPEN, CAP_REACHED, VERIFYING }
    enum class RequestResult { STARTED, CAP_REACHED, COOLDOWN_ACTIVE }

    // ---- settings (clamped to hard bounds on read AND write) ----

    val sessionMin: Int
        get() = prefs.getInt(KEY_SESSION, DEF_SESSION_MIN).coerceIn(SESSION_MIN, SESSION_MAX)
    val perDay: Int
        get() = prefs.getInt(KEY_PER_DAY, DEF_PER_DAY).coerceIn(PER_DAY_MIN, PER_DAY_MAX)
    val cooldownMin: Int
        get() = prefs.getInt(KEY_COOLDOWN, DEF_COOLDOWN_MIN).coerceIn(COOLDOWN_MIN, COOLDOWN_MAX)

    /**
     * Write new allowance values. Refused outright while the freeze has settings
     * locked, so a running commitment cannot be loosened mid-cycle. Returns true
     * when applied (the caller then restarts the cycle).
     */
    fun applySettings(session: Int, perDayReq: Int, cooldown: Int): Boolean {
        if (FreezeStore(appContext).settingsLocked()) return false
        prefs.edit()
            .putInt(KEY_SESSION, session.coerceIn(SESSION_MIN, SESSION_MAX))
            .putInt(KEY_PER_DAY, perDayReq.coerceIn(PER_DAY_MIN, PER_DAY_MAX))
            .putInt(KEY_COOLDOWN, cooldown.coerceIn(COOLDOWN_MIN, COOLDOWN_MAX))
            .apply()
        return true
    }

    private fun minuteMs(): Long = if (DevMode.enabled(appContext)) DEBUG_MINUTE_MS else 60_000L
    private fun dayMs(): Long = if (DevMode.enabled(appContext)) DEBUG_DAY_MS else DAY_MS

    // ---- session lifecycle ----

    /** Start a session now, if the daily cap and cooldown allow it. */
    fun requestSession(): RequestResult {
        refreshDailyCap()
        if (usedToday() >= perDay) return RequestResult.CAP_REACHED
        if (state() == State.COOLDOWN) return RequestResult.COOLDOWN_ACTIVE

        val nowE = SystemClock.elapsedRealtime()
        val nowW = System.currentTimeMillis()
        val sessionEndE = nowE + sessionMin * minuteMs()
        val editor = prefs.edit()
            .putBoolean(KEY_ACTIVE, true)
            .putLong(KEY_SESSION_END_E, sessionEndE)
            .putLong(KEY_COOLDOWN_END_E, sessionEndE + cooldownMin * minuteMs())
            .putLong(KEY_SESSION_END_W, nowW + sessionMin * minuteMs())
            .putLong(KEY_COOLDOWN_END_W, nowW + (sessionMin + cooldownMin) * minuteMs())
            .putLong(KEY_ANCHOR_E, nowE)
            .putInt(KEY_BOOT, currentBootCount())
        if (usedToday() == 0) editor.putLong(KEY_DAY_END_W, nowW + dayMs())
        editor.putInt(KEY_USED, usedToday() + 1)
        editor.apply()
        return RequestResult.STARTED
    }

    fun state(): State {
        refreshDailyCap()
        if (prefs.getBoolean(KEY_ACTIVE, false)) {
            if (rebooted()) return State.VERIFYING // fail closed
            val e = SystemClock.elapsedRealtime()
            when {
                e < prefs.getLong(KEY_SESSION_END_E, 0L) -> return State.OPEN
                e < prefs.getLong(KEY_COOLDOWN_END_E, 0L) -> return State.COOLDOWN
                else -> prefs.edit().putBoolean(KEY_ACTIVE, false).apply()
            }
        }
        return if (usedToday() >= perDay) State.CAP_REACHED else State.LOCKED
    }

    /** The single question the accessibility service asks before allowing a reel. */
    fun isOpen(): Boolean = state() == State.OPEN

    fun remainingMs(): Long {
        val e = SystemClock.elapsedRealtime()
        return when (state()) {
            State.OPEN -> (prefs.getLong(KEY_SESSION_END_E, 0L) - e).coerceAtLeast(0L)
            State.COOLDOWN -> (prefs.getLong(KEY_COOLDOWN_END_E, 0L) - e).coerceAtLeast(0L)
            else -> 0L
        }
    }

    fun usedToday(): Int = prefs.getInt(KEY_USED, 0)

    fun needsTimeVerification(): Boolean =
        prefs.getBoolean(KEY_ACTIVE, false) && rebooted()

    /** Re-anchor an in-flight session after a reboot, from a trusted network time. */
    fun applyTrustedTime(trustedNow: Long) {
        if (!prefs.getBoolean(KEY_ACTIVE, false)) return
        val nowE = SystemClock.elapsedRealtime()
        val s = (prefs.getLong(KEY_SESSION_END_W, 0L) - trustedNow).coerceAtLeast(0L)
        val c = (prefs.getLong(KEY_COOLDOWN_END_W, 0L) - trustedNow).coerceAtLeast(0L)
        prefs.edit()
            .putLong(KEY_SESSION_END_E, nowE + s)
            .putLong(KEY_COOLDOWN_END_E, nowE + c)
            .putLong(KEY_ANCHOR_E, nowE)
            .putInt(KEY_BOOT, currentBootCount())
            .apply()
    }

    // ---- internals ----

    /** The per-day counter rolls over on the wall clock; winding it forward only
     *  costs the user their remaining sessions, so it is not an exploit. */
    private fun refreshDailyCap() {
        if (usedToday() <= 0) return
        if (System.currentTimeMillis() >= prefs.getLong(KEY_DAY_END_W, 0L)) {
            prefs.edit().putInt(KEY_USED, 0).remove(KEY_DAY_END_W).apply()
        }
    }

    private fun rebooted(): Boolean {
        val storedBoot = prefs.getInt(KEY_BOOT, -1)
        val curBoot = currentBootCount()
        if (storedBoot >= 0 && curBoot >= 0) return curBoot != storedBoot
        return SystemClock.elapsedRealtime() < prefs.getLong(KEY_ANCHOR_E, 0L)
    }

    private fun currentBootCount(): Int =
        Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT, -1)

    companion object {
        // ---- committed defaults ----
        const val DEF_SESSION_MIN = 10
        const val DEF_PER_DAY = 3
        const val DEF_COOLDOWN_MIN = 15

        // ---- hard bounds the user cannot exceed ----
        const val SESSION_MIN = 1
        const val SESSION_MAX = 10
        const val PER_DAY_MIN = 0
        const val PER_DAY_MAX = 3
        const val COOLDOWN_MIN = 15
        const val COOLDOWN_MAX = 1440

        private const val DAY_MS = 86_400_000L
        // Developer-mode timings: a "minute" is 2s and a "day" is 3 minutes.
        private const val DEBUG_MINUTE_MS = 2_000L
        private const val DEBUG_DAY_MS = 180_000L

        private const val KEY_SESSION = "session_min"
        private const val KEY_PER_DAY = "per_day"
        private const val KEY_COOLDOWN = "cooldown_min"
        private const val KEY_ACTIVE = "active"
        private const val KEY_SESSION_END_E = "session_end_e"
        private const val KEY_COOLDOWN_END_E = "cooldown_end_e"
        private const val KEY_SESSION_END_W = "session_end_w"
        private const val KEY_COOLDOWN_END_W = "cooldown_end_w"
        private const val KEY_ANCHOR_E = "anchor_e"
        private const val KEY_BOOT = "boot"
        private const val KEY_USED = "used"
        private const val KEY_DAY_END_W = "day_end_w"
    }
}
