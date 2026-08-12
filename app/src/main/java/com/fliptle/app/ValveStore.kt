package com.fliptle.app

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

/**
 * Controlled-access valve for the DISTRACTION surfaces only (Instagram Reels/
 * Stories, YouTube Shorts). Porn is never routed through this — it has no valve.
 *
 * Flow: Request access -> COOLDOWN -> OPEN window -> auto re-lock, subject to a
 * hard daily cap. Cooldown/window timing is reboot/clock-tamper-proof.
 *
 * Anti-abuse on the SETTINGS themselves:
 *  - Hard bounds ([COOLDOWN_MIN_MINUTES], [WINDOW_MAX_MINUTES], [CAP_MAX]) are
 *    code constants the user cannot exceed; values are clamped on write AND read.
 *  - The loosening rule applies to settings: TIGHTENING (higher cooldown, lower
 *    window, lower cap, disabling allow-during-freeze) applies instantly.
 *    LOOSENING (lower cooldown, higher window, higher cap, enabling
 *    allow-during-freeze) is BLOCKED during an active freeze, and otherwise only
 *    takes effect after a delay ([LOOSEN_DELAY_MINUTES]) — staged as a pending
 *    change with the same tamper-proof timing.
 */
class ValveStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("reels_valve", Context.MODE_PRIVATE)

    enum class State { BLOCKED, COOLDOWN, OPEN, VERIFYING, CAP_REACHED }
    enum class RequestResult { STARTED, CAP_REACHED, DENIED_FREEZE }

    data class SettingsChangeResult(
        val clamped: Boolean,        // one or more values were clamped to the bounds
        val appliedInstantly: Boolean, // one or more tightening changes applied now
        val staged: Boolean,         // one or more loosening changes staged (delayed)
        val blockedByFreeze: Boolean // one or more loosening changes blocked (freeze)
    )

    // ---- effective settings (clamped to hard bounds on read; writes go through
    //      requestSettingsChange / promotePendingIfDue only) ----
    val cooldownMin: Int
        get() = prefs.getInt(KEY_COOLDOWN_MIN, DEF_COOLDOWN).coerceIn(COOLDOWN_MIN_MINUTES, COOLDOWN_MAX_MINUTES)
    val windowMin: Int
        get() = prefs.getInt(KEY_WINDOW_MIN, DEF_WINDOW).coerceIn(WINDOW_MIN_MINUTES, WINDOW_MAX_MINUTES)
    val dailyCap: Int
        get() = prefs.getInt(KEY_DAILY_CAP, DEF_CAP).coerceIn(CAP_MIN, CAP_MAX)
    val allowDuringFreeze: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_FREEZE, false)

    /** Developer-only fast timings for testing. */
    var debug: Boolean
        get() = prefs.getBoolean(KEY_DEBUG, false)
        set(v) = prefs.edit().putBoolean(KEY_DEBUG, v).apply()

    private fun cooldownMs(): Long = if (debug) DEBUG_STEP_MS else cooldownMin * 60_000L
    private fun windowMs(): Long = if (debug) DEBUG_STEP_MS else windowMin * 60_000L
    private fun capPeriodMs(): Long = if (debug) DEBUG_CAP_MS else DAY_MS
    private fun loosenDelayMs(): Long = if (debug) DEBUG_STEP_MS else LOOSEN_DELAY_MINUTES * 60_000L

    // ---- access request lifecycle ----

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
        if (capCount() == 0) editor.putLong(KEY_CAP_END_W, nowW + capPeriodMs())
        editor.putInt(KEY_CAP_COUNT, capCount() + 1)
        editor.apply()
        return RequestResult.STARTED
    }

    fun state(): State {
        promotePendingIfDue()
        refreshCap()
        if (prefs.getBoolean(KEY_REQ_ACTIVE, false)) {
            if (rebooted(KEY_BOOT, KEY_ANCHOR_E)) return State.VERIFYING
            val e = SystemClock.elapsedRealtime()
            when {
                e < prefs.getLong(KEY_COOLDOWN_E, 0L) -> return State.COOLDOWN
                e < prefs.getLong(KEY_WINDOW_E, 0L) -> return State.OPEN
                else -> prefs.edit().putBoolean(KEY_REQ_ACTIVE, false).apply()
            }
        }
        return if (capCount() >= dailyCap) State.CAP_REACHED else State.BLOCKED
    }

    fun isAccessOpen(): Boolean {
        if (state() != State.OPEN) return false
        if (freezeActive() && !allowDuringFreeze) return false
        return true
    }

    fun remainingMs(): Long {
        val e = SystemClock.elapsedRealtime()
        return when (state()) {
            State.COOLDOWN -> (prefs.getLong(KEY_COOLDOWN_E, 0L) - e).coerceAtLeast(0L)
            State.OPEN -> (prefs.getLong(KEY_WINDOW_E, 0L) - e).coerceAtLeast(0L)
            else -> 0L
        }
    }

    fun usedToday(): Int = capCount()

    // ---- settings changes with the loosening rule ----

    fun requestSettingsChange(reqCooldown: Int, reqWindow: Int, reqCap: Int, reqAllowFreeze: Boolean): SettingsChangeResult {
        promotePendingIfDue()
        val cd = reqCooldown.coerceIn(COOLDOWN_MIN_MINUTES, COOLDOWN_MAX_MINUTES)
        val win = reqWindow.coerceIn(WINDOW_MIN_MINUTES, WINDOW_MAX_MINUTES)
        val cap = reqCap.coerceIn(CAP_MIN, CAP_MAX)
        val clamped = cd != reqCooldown || win != reqWindow || cap != reqCap

        val freeze = freezeActive()
        var applied = false
        var staged = false
        var blocked = false
        val e = prefs.edit()

        // cooldown: loosening = LOWER than current
        when {
            cd >= cooldownMin -> { e.putInt(KEY_COOLDOWN_MIN, cd); e.remove(KEY_PEND_CD); if (cd != cooldownMin) applied = true }
            freeze -> blocked = true
            else -> { e.putInt(KEY_PEND_CD, cd); staged = true }
        }
        // window: loosening = HIGHER than current
        when {
            win <= windowMin -> { e.putInt(KEY_WINDOW_MIN, win); e.remove(KEY_PEND_WIN); if (win != windowMin) applied = true }
            freeze -> blocked = true
            else -> { e.putInt(KEY_PEND_WIN, win); staged = true }
        }
        // cap: loosening = HIGHER than current
        when {
            cap <= dailyCap -> { e.putInt(KEY_DAILY_CAP, cap); e.remove(KEY_PEND_CAP); if (cap != dailyCap) applied = true }
            freeze -> blocked = true
            else -> { e.putInt(KEY_PEND_CAP, cap); staged = true }
        }
        // allow-during-freeze: loosening = ENABLING
        when {
            !reqAllowFreeze -> { e.putBoolean(KEY_ALLOW_FREEZE, false); e.remove(KEY_PEND_ALLOW); if (allowDuringFreeze) applied = true }
            reqAllowFreeze == allowDuringFreeze -> { /* already on, no-op */ }
            freeze -> blocked = true
            else -> { e.putBoolean(KEY_PEND_ALLOW, true); staged = true }
        }

        if (staged) {
            val nowE = SystemClock.elapsedRealtime()
            val nowW = System.currentTimeMillis()
            e.putLong(KEY_PEND_UNLOCK_E, nowE + loosenDelayMs())
                .putLong(KEY_PEND_UNLOCK_W, nowW + loosenDelayMs())
                .putLong(KEY_PEND_ANCHOR_E, nowE)
                .putInt(KEY_PEND_BOOT, currentBootCount())
        }
        e.apply()
        return SettingsChangeResult(clamped, applied, staged, blocked)
    }

    fun pendingActive(): Boolean =
        prefs.getInt(KEY_PEND_CD, -1) >= 0 || prefs.getInt(KEY_PEND_WIN, -1) >= 0 ||
            prefs.getInt(KEY_PEND_CAP, -1) >= 0 || prefs.getBoolean(KEY_PEND_ALLOW, false)

    /** Remaining ms until a staged loosening applies; -1 if verifying after reboot. */
    fun pendingRemainingMs(): Long {
        if (!pendingActive()) return 0L
        if (rebooted(KEY_PEND_BOOT, KEY_PEND_ANCHOR_E)) return -1L
        return (prefs.getLong(KEY_PEND_UNLOCK_E, 0L) - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
    }

    fun promotePendingIfDue() {
        if (!pendingActive()) return
        if (rebooted(KEY_PEND_BOOT, KEY_PEND_ANCHOR_E)) return
        if (SystemClock.elapsedRealtime() < prefs.getLong(KEY_PEND_UNLOCK_E, 0L)) return
        val e = prefs.edit()
        prefs.getInt(KEY_PEND_CD, -1).let { if (it >= 0) { e.putInt(KEY_COOLDOWN_MIN, it); e.remove(KEY_PEND_CD) } }
        prefs.getInt(KEY_PEND_WIN, -1).let { if (it >= 0) { e.putInt(KEY_WINDOW_MIN, it); e.remove(KEY_PEND_WIN) } }
        prefs.getInt(KEY_PEND_CAP, -1).let { if (it >= 0) { e.putInt(KEY_DAILY_CAP, it); e.remove(KEY_PEND_CAP) } }
        if (prefs.getBoolean(KEY_PEND_ALLOW, false)) { e.putBoolean(KEY_ALLOW_FREEZE, true); e.remove(KEY_PEND_ALLOW) }
        e.remove(KEY_PEND_UNLOCK_E).remove(KEY_PEND_UNLOCK_W).remove(KEY_PEND_ANCHOR_E).remove(KEY_PEND_BOOT)
        e.apply()
    }

    /** Re-anchor active request AND any pending settings change from trusted time. */
    fun applyTrustedTime(trustedNow: Long) {
        val nowE = SystemClock.elapsedRealtime()
        val e = prefs.edit()
        if (prefs.getBoolean(KEY_REQ_ACTIVE, false)) {
            val cd = (prefs.getLong(KEY_COOLDOWN_W, 0L) - trustedNow).coerceAtLeast(0L)
            val wd = (prefs.getLong(KEY_WINDOW_W, 0L) - trustedNow).coerceAtLeast(0L)
            e.putLong(KEY_COOLDOWN_E, nowE + cd).putLong(KEY_WINDOW_E, nowE + wd)
                .putLong(KEY_ANCHOR_E, nowE).putInt(KEY_BOOT, currentBootCount())
        }
        if (pendingActive()) {
            val pd = (prefs.getLong(KEY_PEND_UNLOCK_W, 0L) - trustedNow).coerceAtLeast(0L)
            e.putLong(KEY_PEND_UNLOCK_E, nowE + pd)
                .putLong(KEY_PEND_ANCHOR_E, nowE).putInt(KEY_PEND_BOOT, currentBootCount())
        }
        e.apply()
    }

    fun needsTimeVerification(): Boolean =
        (prefs.getBoolean(KEY_REQ_ACTIVE, false) && rebooted(KEY_BOOT, KEY_ANCHOR_E)) ||
            (pendingActive() && rebooted(KEY_PEND_BOOT, KEY_PEND_ANCHOR_E))

    // ---- internals ----

    private fun capCount(): Int = prefs.getInt(KEY_CAP_COUNT, 0)

    private fun refreshCap() {
        if (capCount() <= 0) return
        if (System.currentTimeMillis() >= prefs.getLong(KEY_CAP_END_W, 0L)) {
            prefs.edit().putInt(KEY_CAP_COUNT, 0).remove(KEY_CAP_END_W).apply()
        }
    }

    private fun freezeActive(): Boolean = FreezeStore(appContext).active

    private fun rebooted(bootKey: String, anchorKey: String): Boolean {
        val storedBoot = prefs.getInt(bootKey, -1)
        val curBoot = currentBootCount()
        if (storedBoot >= 0 && curBoot >= 0) return curBoot != storedBoot
        return SystemClock.elapsedRealtime() < prefs.getLong(anchorKey, 0L)
    }

    private fun currentBootCount(): Int =
        Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT, -1)

    companion object {
        // ---- HARD BOUNDS (tune here in code; users cannot exceed these) ----
        const val COOLDOWN_MIN_MINUTES = 10   // cooldown can't be set below this
        const val COOLDOWN_MAX_MINUTES = 1440
        const val WINDOW_MIN_MINUTES = 1
        const val WINDOW_MAX_MINUTES = 20     // window can't exceed this
        const val CAP_MIN = 1
        const val CAP_MAX = 3                 // at most this many windows/day
        const val LOOSEN_DELAY_MINUTES = 60   // delay before a loosening setting applies

        private const val DEF_COOLDOWN = 15
        private const val DEF_WINDOW = 15
        private const val DEF_CAP = 1

        private const val DAY_MS = 86_400_000L
        private const val DEBUG_STEP_MS = 30_000L
        private const val DEBUG_CAP_MS = 180_000L

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
        private const val KEY_PEND_CD = "pend_cd"
        private const val KEY_PEND_WIN = "pend_win"
        private const val KEY_PEND_CAP = "pend_cap"
        private const val KEY_PEND_ALLOW = "pend_allow"
        private const val KEY_PEND_UNLOCK_E = "pend_unlock_e"
        private const val KEY_PEND_UNLOCK_W = "pend_unlock_w"
        private const val KEY_PEND_ANCHOR_E = "pend_anchor_e"
        private const val KEY_PEND_BOOT = "pend_boot"
    }
}
