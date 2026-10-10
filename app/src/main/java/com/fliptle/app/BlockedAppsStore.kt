package com.fliptle.app

import android.content.Context
import com.fliptle.app.accessibility.SurfaceDetector

/**
 * Persists the set of package names the user has chosen to block as whole apps.
 *
 * [EXCLUDED] (Instagram, YouTube) can never enter this store, filtered on BOTH
 * [get] and [set]. Those two apps have their own, narrower control — the
 * Reels/Stories/Shorts surface toggle ([SurfaceBlocklist]) — which blocks only
 * the full-screen player inside the app, never the whole app. Filtering on read
 * as well as write means a value that got in before this filter existed (or via
 * a cloud-restore union) stops being read back as blocked the next time anyone
 * asks, with no extra migration step.
 */
class BlockedAppsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("blocked_apps", Context.MODE_PRIVATE)

    fun get(): Set<String> {
        val stored = prefs.getStringSet(KEY_BLOCKED, emptySet()) ?: emptySet()
        // Return a defensive copy; the set from prefs must not be mutated.
        return HashSet(stored).apply { removeAll(EXCLUDED) }
    }

    fun set(packages: Set<String>) {
        prefs.edit().putStringSet(KEY_BLOCKED, HashSet(packages).apply { removeAll(EXCLUDED) }).apply()
    }

    companion object {
        private const val KEY_BLOCKED = "blocked_packages"

        /** Apps that have their own surface toggle and must never be whole-app blocked. */
        val EXCLUDED: Set<String> = setOf(SurfaceDetector.IG_PKG, SurfaceDetector.YT_PKG)
    }
}
