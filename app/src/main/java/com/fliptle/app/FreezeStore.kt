package com.fliptle.app

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

/**
 * Persists freeze state in SharedPreferences (local storage) and computes the
 * remaining time so that the freeze can NEVER be shortened by tampering — not by
 * changing the system clock, and not by rebooting (with or without a clock change).
 *
 * Anchors stored on start:
 *   - elapsedStart / elapsedEnd : monotonic clock (SystemClock.elapsedRealtime).
 *     Authoritative within a single boot; immune to system-clock changes.
 *   - wallEnd : absolute end on the wall clock, corrected to a TRUSTED network
 *     time when one is available. Used only to re-anchor after a reboot.
 *   - bootCount : Settings.Global.BOOT_COUNT at the time of anchoring. This value
 *     is maintained by the OS and cannot be set by the user, so it is a reliable
 *     reboot detector (unlike the wall clock).
 *
 * Reboot handling (the core of the fix):
 *   When a reboot is detected the monotonic anchor is gone. The store returns
 *   VERIFYING and REFUSES to use the local system clock to expire the freeze.
 *   Only [rebootReanchor], fed by a trusted network time, can move the freeze
 *   out of VERIFYING. If no trusted time is available (offline), the freeze stays
 *   LOCKED indefinitely. A reboot makes the app more cautious, never less.
 */
class FreezeStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("freeze_prefs", Context.MODE_PRIVATE)

    enum class State { NONE, FROZEN, REVIEW, VERIFYING }

    val active: Boolean
        get() = prefs.getBoolean(KEY_ACTIVE, false)

    /** True once [wallEnd] has been confirmed against a trusted network time. */
    val wallTrusted: Boolean
        get() = prefs.getBoolean(KEY_WALL_TRUSTED, false)

    /** Begin a freeze for [durationMs]. Overwrites any previous freeze. */
    fun start(durationMs: Long) {
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        prefs.edit()
            .putBoolean(KEY_ACTIVE, true)
            .putLong(KEY_ELAPSED_START, nowElapsed)
            .putLong(KEY_ELAPSED_END, nowElapsed + durationMs)
            .putLong(KEY_WALL_END, nowWall + durationMs)
            .putInt(KEY_BOOT_COUNT, currentBootCount())
            .putBoolean(KEY_WALL_TRUSTED, false)
            .apply()
    }

    /**
     * Extend (tighten) the freeze by [addMs]. Always allowed, including while
     * VERIFYING. Never shortens. While VERIFYING it only tightens the wall end so
     * the state stays LOCKED until a trusted re-anchor happens.
     */
    fun extend(addMs: Long) {
        if (!active || addMs <= 0) return
        val editor = prefs.edit()
        if (!rebooted()) {
            editor.putLong(KEY_ELAPSED_END, prefs.getLong(KEY_ELAPSED_END, 0L) + addMs)
        }
        editor.putLong(KEY_WALL_END, prefs.getLong(KEY_WALL_END, 0L) + addMs)
        editor.apply()
    }

    /** Only meaningful in REVIEW state: clear the freeze after the user confirms. */
    fun confirmClear() {
        prefs.edit().clear().apply()
    }

    fun state(): State {
        if (!active) return State.NONE
        if (rebooted()) return State.VERIFYING // fail closed: never trust local clock here
        return if (remainingWithinBoot() > 0L) State.FROZEN else State.REVIEW
    }

    /** Remaining time for display while in FROZEN (monotonic, tamper-proof). */
    fun remainingMs(): Long {
        if (!active || rebooted()) return 0L
        return remainingWithinBoot()
    }

    /**
     * Correct [wallEnd] to a trusted basis while still in the same boot (monotonic
     * anchor intact). Fixes any wall-clock offset that existed when the freeze was
     * started, without disturbing the authoritative monotonic anchor.
     */
    fun applyTrustedTimeSameBoot(trustedNow: Long) {
        if (!active || rebooted()) return
        val remaining = remainingWithinBoot()
        prefs.edit()
            .putLong(KEY_WALL_END, trustedNow + remaining)
            .putBoolean(KEY_WALL_TRUSTED, true)
            .apply()
    }

    /**
     * Re-anchor after a reboot using a TRUSTED network time. Rebuilds the
     * monotonic anchor from the surviving absolute wall end. Only this path can
     * take the freeze out of VERIFYING; it is the sole place a reboot is allowed
     * to be "committed".
     */
    fun rebootReanchor(trustedNow: Long) {
        if (!active) return
        val nowElapsed = SystemClock.elapsedRealtime()
        val remaining = (prefs.getLong(KEY_WALL_END, 0L) - trustedNow).coerceAtLeast(0L)
        prefs.edit()
            .putLong(KEY_ELAPSED_START, nowElapsed)
            .putLong(KEY_ELAPSED_END, nowElapsed + remaining)
            .putInt(KEY_BOOT_COUNT, currentBootCount()) // commit this boot
            .putBoolean(KEY_WALL_TRUSTED, true)
            .apply()
    }

    private fun remainingWithinBoot(): Long =
        (prefs.getLong(KEY_ELAPSED_END, 0L) - SystemClock.elapsedRealtime()).coerceAtLeast(0L)

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
        private const val KEY_ACTIVE = "active"
        private const val KEY_ELAPSED_START = "elapsed_start"
        private const val KEY_ELAPSED_END = "elapsed_end"
        private const val KEY_WALL_END = "wall_end"
        private const val KEY_BOOT_COUNT = "boot_count"
        private const val KEY_WALL_TRUSTED = "wall_trusted"
    }
}
