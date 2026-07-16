package com.fliptle.app.auth

import android.content.Context
import com.google.firebase.FirebaseApp

/**
 * Central check for whether Firebase is configured. Without google-services.json
 * the FirebaseInitProvider does not initialize a default app, so this returns
 * false and all Firebase-dependent screens degrade gracefully — the app's
 * protection features never depend on it.
 */
object FirebaseGate {
    fun isAvailable(context: Context): Boolean = try {
        FirebaseApp.getApps(context).isNotEmpty()
    } catch (_: Throwable) {
        false
    }
}
