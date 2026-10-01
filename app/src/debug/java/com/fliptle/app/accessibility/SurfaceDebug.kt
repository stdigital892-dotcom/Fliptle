package com.fliptle.app.accessibility

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-device ring-buffer log of what the surface detector matched, so the exact
 * view ids / content descriptions that trigger (or nearly trigger) a block can be
 * inspected without adb. Also mirrored to Logcat under the tag "FliptleSurface".
 */
class SurfaceDebug(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("surface_debug", Context.MODE_PRIVATE)

    fun record(
        pkg: String,
        eventType: Int,
        decision: SurfaceDetector.Surface?,
        candidates: List<String>
    ) {
        if (candidates.isEmpty() && decision == null) return
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val header = "[$ts] $pkg evt=$eventType wouldBlock=${decision?.name ?: "none"}"
        val body = candidates.take(10).joinToString("\n") { "   $it" }
        val entry = if (body.isEmpty()) "$header\n" else "$header\n$body\n"

        Log.i(TAG, entry)
        val combined = (entry + (prefs.getString(KEY_LOG, "") ?: "")).take(MAX_CHARS)
        prefs.edit().putString(KEY_LOG, combined).apply()
    }

    fun get(): String = prefs.getString(KEY_LOG, "") ?: ""

    fun clear() = prefs.edit().remove(KEY_LOG).apply()

    companion object {
        private const val TAG = "FliptleSurface"
        private const val KEY_LOG = "log"
        private const val MAX_CHARS = 8000
    }
}
