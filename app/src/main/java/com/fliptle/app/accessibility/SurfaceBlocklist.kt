package com.fliptle.app.accessibility

import android.content.Context

/**
 * Per-surface toggles for in-app blocking (Instagram Reels/Stories, YouTube
 * Shorts). Each surface is independently on/off, so the rest of those apps
 * (feed, normal videos) stays usable. Defaults to on — the point of the feature
 * is to block these distraction surfaces.
 */
class SurfaceBlocklist(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("surface_blocks", Context.MODE_PRIVATE)

    var reels: Boolean
        get() = prefs.getBoolean(KEY_REELS, true)
        set(v) = prefs.edit().putBoolean(KEY_REELS, v).apply()

    var stories: Boolean
        get() = prefs.getBoolean(KEY_STORIES, true)
        set(v) = prefs.edit().putBoolean(KEY_STORIES, v).apply()

    var shorts: Boolean
        get() = prefs.getBoolean(KEY_SHORTS, true)
        set(v) = prefs.edit().putBoolean(KEY_SHORTS, v).apply()

    /** When on, the service logs what it matches and does NOT block, for diagnosis. */
    var debug: Boolean
        get() = prefs.getBoolean(KEY_DEBUG, false)
        set(v) = prefs.edit().putBoolean(KEY_DEBUG, v).apply()

    fun isBlocked(surface: SurfaceDetector.Surface): Boolean = when (surface) {
        SurfaceDetector.Surface.IG_REELS -> reels
        SurfaceDetector.Surface.IG_STORIES -> stories
        SurfaceDetector.Surface.YT_SHORTS -> shorts
    }

    companion object {
        private const val KEY_REELS = "reels"
        private const val KEY_STORIES = "stories"
        private const val KEY_SHORTS = "shorts"
        private const val KEY_DEBUG = "debug"
    }
}
