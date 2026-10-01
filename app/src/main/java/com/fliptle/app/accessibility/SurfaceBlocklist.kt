package com.fliptle.app.accessibility

import android.content.Context

/**
 * The two in-app blocking toggles. "Reels" covers Instagram Reels AND Stories;
 * "Shorts" is YouTube Shorts. A toggled surface is simply blocked: there is no
 * session, allowance or cooldown. Both default to OFF — nothing is blocked until
 * the user chooses it and starts a commitment (see [com.fliptle.app.Commitment]).
 *
 * These are the LIVE values the accessibility service enforces. The wizard edits
 * a separate draft and only copies it here when the commitment starts.
 */
class SurfaceBlocklist(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var reels: Boolean
        get() = prefs.getBoolean(KEY_REELS, false)
        set(v) = prefs.edit().putBoolean(KEY_REELS, v).apply()

    var shorts: Boolean
        get() = prefs.getBoolean(KEY_SHORTS, false)
        set(v) = prefs.edit().putBoolean(KEY_SHORTS, v).apply()

    /** When on, the service logs what it matches and does NOT block, for diagnosis.
     *  Developer tool: reads false unless DevMode is unlocked, so a stale flag can
     *  never leave a shipped build in observe-only (non-blocking) mode. */
    var debug: Boolean
        get() = com.fliptle.app.DevMode.enabled(appContext) && prefs.getBoolean(KEY_DEBUG, false)
        set(v) = prefs.edit().putBoolean(KEY_DEBUG, v).apply()

    fun isBlocked(surface: SurfaceDetector.Surface): Boolean = when (surface) {
        SurfaceDetector.Surface.IG_REELS, SurfaceDetector.Surface.IG_STORIES -> reels
        SurfaceDetector.Surface.YT_SHORTS -> shorts
    }

    companion object {
        const val PREFS = "surface_blocks"
        private const val KEY_REELS = "reels"
        private const val KEY_SHORTS = "shorts"
        private const val KEY_DEBUG = "debug"
    }
}
