package com.fliptle.app.accessibility

import android.content.Context

/**
 * Release-build stand-in for the developer surface log. The real implementation
 * (src/debug) records package names, view ids and content descriptions for
 * diagnosis; shipped builds contain none of that code. It has the same API as
 * the debug class so callers compile unchanged, and it never stores or logs
 * anything. (In release the callers can't reach it anyway: the developer tools
 * are switched off by BuildConfig.DEV_TOOLS.)
 */
@Suppress("UNUSED_PARAMETER")
class SurfaceDebug(context: Context) {

    fun record(
        pkg: String,
        eventType: Int,
        decision: SurfaceDetector.Surface?,
        candidates: List<String>
    ) {
        // intentionally empty
    }

    fun get(): String = ""

    fun clear() {
        // intentionally empty
    }
}
