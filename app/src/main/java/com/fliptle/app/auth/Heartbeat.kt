package com.fliptle.app.auth

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

/**
 * Periodic check-in ("heartbeat"). While the app is installed and a user is
 * signed in, it writes a fresh timestamp to installs/{uid}. If the app is
 * uninstalled directly (no approved uninstall flow), it stops checking in.
 *
 * Android can't have an uninstalled app report its own removal, and we don't use
 * paid Cloud Functions, so a DIRECT uninstall is detected at the next sign-in
 * after a reinstall: if the previous heartbeat is older than the grace window and
 * the account was never uninstall-approved, a "direct_uninstall" event is logged
 * (see InstallTracker). This mirrors the reinstall-detection design.
 */
object Heartbeat {

    private const val COLLECTION = "installs"
    private const val PREFS = "heartbeat"
    private const val KEY_DEBUG = "debug"

    const val GRACE_MS = 86_400_000L        // 24h default grace before "gone dark"
    const val DEBUG_GRACE_MS = 120_000L     // 2 min for testing

    fun graceMs(context: Context): Long =
        if (isDebug(context)) DEBUG_GRACE_MS else GRACE_MS

    /** Fast grace window for testing. Gated on DevMode, so a stale flag cannot
     *  shorten the real 24h grace in a shipped build. */
    fun isDebug(context: Context): Boolean =
        com.fliptle.app.DevMode.enabled(context) &&
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_DEBUG, false)

    fun setDebug(context: Context, value: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DEBUG, value).apply()
    }

    /** Record a check-in. Safe to call often; no-ops without Firebase/user. */
    fun beat(context: Context) {
        if (!FirebaseGate.isAvailable(context)) return
        val user = FirebaseAuth.getInstance().currentUser ?: return
        FirebaseFirestore.getInstance().collection(COLLECTION).document(user.uid)
            .set(
                mapOf(
                    "lastHeartbeatAt" to FieldValue.serverTimestamp(),
                    "lastHeartbeatMs" to System.currentTimeMillis(),
                    "email" to user.email
                ),
                SetOptions.merge()
            )
    }
}
