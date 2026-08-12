package com.fliptle.app

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

/**
 * Controlled-access valve for the DISTRACTION surfaces only (Instagram Reels/
 * Stories, YouTube Shorts). Porn is never routed through this — it has no valve.
 *
 * Flow: Request access -> COOLDOWN (the wait is the friction, default 15 min) ->
 * OPEN window (default 15 min) -> auto re-lock. A daily cap (default 1) is a hard
 * ceiling with no override.
 *
 * The COOLDOWN/WINDOW timing is reboot- and clock-tamper-proof (monotonic
 * elapsedRealtime + wall anchor + BOOT_COUNT + trusted-time re-anchor) so the
 * clock can't skip the wait. The daily cap resets on a wall-clock period; the
 * per-access cooldown remains tamper-proof either way.
 *
 * During an active freeze the valve respects the freeze's loosening rules: by
 * default it will NOT open (a committed freeze is not bypassable). Configurable
 * via [allowDuringFreeze].
 */
class ValveStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("reels_valve", Context.MODE_PRIVATE)

    enum class State { BLOCKED, COOLDOWN, OPEN, VERIFYING, CAP_REACHED }
    enum class RequestResult { STARTED, CAP_REACHED, DENIED_FREEZE }

    // ---- configurable settings ----
    var cooldownMin: Int
        get() = prefs.getInt(KEY_COOLDOWN_MIN, 15)
        set(v) = prefs.edit().putInt(KEY_COOLDOWN_MIN, v.coerceAtLeast(0)).apply()
    var windowMin: Int
        get() = prefs.getInt(KEY_WINDOW_MIN, 15)
        set(v) = prefs.edit().putInt(KEY_WINDOW_MIN, v.coerceAtLeast(1)).apply()
    var dailyCap: Int
        get() = prefs.getInt(KEY_DAILY_CAP, 1)
        set(v) = prefs.edit().putInt(KEY_DAILY_CAP, v.coerceAtLeast(1)).apply()
    var allowDuringFreeze: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_FREEZE, false)
        set(v) = prefs.edit().putBoolean(KEY_ALLOW_FREEZE, v).apply()
    var debug: Boolean
        get() = prefs.getBoolean(KEY_DEBUG, false)
        set(v) = prefs.edit().putBoolean(KEY_DEBUG, v).apply()

    private fun cooldownMs(): Long = if (debug) DEBUG_STEP_MS else cooldownMin * 60_000L
    private fun windowMs(): Long = if (debug) DEBUG_STEP_MS else windowMin * 60_000L
    private fun capPeriodMs(): Long = if (debug) DEBUG_CAP_MS else DAY_MS

    // ---- request lifecycle ----

    fun requestAccess(): RequestResult {
        refreshCap()
        if (capCount() >= dailyCap) return RequestResult.CAP_REACHED
        if (freezeActive() && !allowDuringFreeze) return RequestResult.DENIED_FREEZE

        val nowE = SystemClock.elapsedRealtime()
        val nowW = System.currentTimeMillis()
        val cooldownEndE = nowE + cooldownMs()
        val editor = prefs.edit()
            .putBoolean(KEY_REQ_ACTIVE, true)
            .putLong(KEY_COOLDOWN_E, cooldownEndE)
            .putLong(KEY_WINDOW_E, cooldownEndE + windowMs())
            .putLong(KEY_COOLDOWN_W, nowW + cooldownMs())
            .putLong(KEY_WINDOW_W, nowW + cooldownMs() + windowMs())
            .putLong(KEY_ANCHOR_E, nowE)
            .putInt(KEY_BOOT, currentBootCount())
        // Consume a daily-cap token; start a new wall-clock cap period on first use.
        if (capCount() == 0) editor.putLong(KEY_CAP_END_W, nowW + capPeriodMs())
        editor.putInt(KEY_CAP_COUNT, capCount() + 1)
        editor.apply()
        return RequestResult.STARTED
    }

    fun state(): State {
        refreshCap()
        if (prefs.getBoolean(KEY_REQ_ACTIVE, false)) {
            if (rebooted()) return State.VERIFYING
            val e = SystemClock.elapsedRealtime()
            when {
                e < prefs.getLong(KEY_COOLDOWN_E, 0L) -> return State.COOLDOWN
                e < prefs.getLong(KEY_WINDOW_E, 0L) -> return State.OPEN
                else -> prefs.edit().putBoolean(KEY_REQ_ACTIVE, false).apply() // window expired
            }
        }
        return if (capCount() >= dailyCap) State.CAP_REACHED else State.BLOCKED
    }

    /** The only method the enforcer needs: is a surface-access window open now? */
    fun isAccessOpen(): Boolean {
        if (state() != State.OPEN) return false
        if (freezeActive() && !allowDuringFreeze) return false // respect the freeze
        return true
    }

    /** Milliseconds left in the current phase (cooldown or open window). */
    fun remainingMs(): Long {
        val e = SystemClock.elapsedRealtime()
        return when (state()) {
            State.COOLDOWN -> (prefs.getLong(KEY_COOLDOWN_E, 0L) - e).coerceAtLeast(0L)
            State.OPEN -> (prefs.getLong(KEY_WINDOW_E, 0L) - e).coerceAtLeast(0L)
            else -> 0L
        }
    }

    fun usedToday(): Int = capCount()

    /** Re-anchor the active cooldown/window from trusted network time after a reboot. */
    fun applyTrustedTime(trustedNow: Long) {
        if (!prefs.getBoolean(KEY_REQ_ACTIVE, false)) return
        val nowE = SystemClock.elapsedRealtime()
        val cd = (prefs.getLong(KEY_COOLDOWN_W, 0L) - trustedNow).coerceAtLeast(0L)
        val wd = (prefs.getLong(KEY_WINDOW_W, 0L) - trustedNow).coerceAtLeast(0L)
        prefs.edit()
            .putLong(KEY_COOLDOWN_E, nowE + cd)
            .putLong(KEY_WINDOW_E, nowE + wd)
            .putLong(KEY_ANCHOR_E, nowE)
            .putInt(KEY_BOOT, currentBootCount())
            .apply()
    }

    // ---- internals ----

    private fun capCount(): Int = prefs.getInt(KEY_CAP_COUNT, 0)

    private fun refreshCap() {
        if (capCount() <= 0) return
        if (System.currentTimeMillis() >= prefs.getLong(KEY_CAP_END_W, 0L)) {
            prefs.edit().putInt(KEY_CAP_COUNT, 0).remove(KEY_CAP_END_W).apply()
        }
    }

    private fun freezeActive(): Boolean = FreezeStore(appContext).active

    private fun rebooted(): Boolean {
        val storedBoot = prefs.getInt(KEY_BOOT, -1)
        val curBoot = currentBootCount()
        if (storedBoot >= 0 && curBoot >= 0) return curBoot != storedBoot
        return SystemClock.elapsedRealtime() < prefs.getLong(KEY_ANCHOR_E, 0L)
    }

    private fun currentBootCount(): Int =
        Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT, -1)

    companion object {
        private const val DAY_MS = 86_400_000L
        private const val DEBUG_STEP_MS = 30_000L   // 30s cooldown / window in debug
        private const val DEBUG_CAP_MS = 180_000L   // 3-minute cap period in debug

        private const val KEY_COOLDOWN_MIN = "cooldown_min"
        private const val KEY_WINDOW_MIN = "window_min"
        private const val KEY_DAILY_CAP = "daily_cap"
        private const val KEY_ALLOW_FREEZE = "allow_during_freeze"
        private const val KEY_DEBUG = "debug"
        private const val KEY_REQ_ACTIVE = "req_active"
        private const val KEY_COOLDOWN_E = "cooldown_e"
        private const val KEY_WINDOW_E = "window_e"
        private const val KEY_COOLDOWN_W = "cooldown_w"
        private const val KEY_WINDOW_W = "window_w"
        private const val KEY_ANCHOR_E = "anchor_e"
        private const val KEY_BOOT = "boot"
        private const val KEY_CAP_COUNT = "cap_count"
        private const val KEY_CAP_END_W = "cap_end_w"
    }
}
