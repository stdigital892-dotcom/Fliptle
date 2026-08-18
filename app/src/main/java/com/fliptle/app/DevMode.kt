package com.fliptle.app

import android.content.Context
import android.view.View
import android.widget.Toast

/**
 * Hidden developer toggle for the short-timer test modes (fast freeze cycles,
 * fast Reels cooldowns, surface-id logging).
 *
 * Two locks, both must be open:
 *  1. [BuildConfig.DEV_TOOLS] — false in release builds, so the whole thing is
 *     compiled out of reach for shipped users. No gesture can turn it on.
 *  2. A hidden gesture ([attachUnlockGesture]) — even in a debug build the tools
 *     stay off until someone taps a normally-inert view [UNLOCK_TAPS] times.
 *
 * Nothing in the normal user flow shows or reaches this: there is no menu entry,
 * no visible switch, and every debug timing path checks [enabled] first.
 */
object DevMode {

    private const val PREFS = "dev_mode"
    private const val KEY_ENABLED = "enabled"
    private const val UNLOCK_TAPS = 7
    private const val TAP_WINDOW_MS = 3_000L

    /** True only in a dev-tools build where the hidden gesture has been used. */
    fun enabled(context: Context): Boolean {
        if (!BuildConfig.DEV_TOOLS) return false
        return prefs(context).getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, value: Boolean) {
        if (!BuildConfig.DEV_TOOLS) return
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    /**
     * Wire the hidden unlock gesture to an ordinary-looking view (e.g. a title).
     * [UNLOCK_TAPS] taps within [TAP_WINDOW_MS] of each other toggles dev tools.
     * In a release build this is a no-op, so the view behaves normally.
     */
    fun attachUnlockGesture(view: View) {
        if (!BuildConfig.DEV_TOOLS) return
        var taps = 0
        var lastTapMs = 0L
        view.setOnClickListener {
            val now = android.os.SystemClock.elapsedRealtime()
            taps = if (now - lastTapMs > TAP_WINDOW_MS) 1 else taps + 1
            lastTapMs = now
            if (taps >= UNLOCK_TAPS) {
                taps = 0
                val ctx = view.context
                val on = !enabled(ctx)
                setEnabled(ctx, on)
                Toast.makeText(
                    ctx,
                    if (on) "Developer mode ON" else "Developer mode OFF",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
