package com.fliptle.app.auth

import android.content.Context
import com.fliptle.app.Permissions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

/**
 * Periodic check-in ("heartbeat"). While the app is installed and a user is
 * signed in, it writes a fresh timestamp — and whether protection is actually
 * enforcing right now — to installs/{uid}. If the app is uninstalled directly
 * (no approved uninstall flow), it stops checking in.
 *
 * Android can't have an uninstalled app report its own removal, and we don't use
 * paid Cloud Functions, so a DIRECT uninstall is detected at the next sign-in
 * after a reinstall: if the previous heartbeat is older than the grace window and
 * the account was never uninstall-approved, a "direct_uninstall" event is logged
 * (see InstallTracker). This mirrors the reinstall-detection design.
 *
 * The `protectionActive` field is a SEPARATE, more precise signal than raw
 * liveness: an OEM battery killer can leave the app process able to wake
 * briefly (e.g. for an unrelated WorkManager tick) while the Accessibility
 * service or BlockingService it killed stays dead. A fresh heartbeat with
 * protectionActive=false means "the app is alive but not protecting" — a
 * different, more actionable server-side signal than "gone dark entirely".
 * See the scheduled Cloud Function `flagStaleProtection` in functions/index.js.
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
        val protectionActive = Permissions.allEnforcementGranted(context)
        // Passive read for the partner-alert template: adult-content blocking has
        // no reset path, so dayCount() is both "days with protection on" and
        // "longest stretch" at once. The sender uses it as both {{4}} and {{5}}.
        // Reading this doesn't change any blocking state.
        val pornDays = com.fliptle.app.PornBlockStore(context).dayCount()
        val data = mutableMapOf<String, Any?>(
            "lastHeartbeatAt" to FieldValue.serverTimestamp(),
            "lastHeartbeatMs" to System.currentTimeMillis(),
            "email" to user.email,
            "protectionActive" to protectionActive,
            "pornDays" to pornDays
        )
        // Only clear a previously-set staleness flag once protection is
        // confirmed back on — if protection is still off, leave whatever flag
        // the scheduled function set alone; it'll re-evaluate on its own
        // schedule rather than being cleared just because the process woke up.
        if (protectionActive) {
            data["protectionDownFlaggedAt"] = FieldValue.delete()
            data["protectionDownReason"] = FieldValue.delete()
        }
        FirebaseFirestore.getInstance().collection(COLLECTION).document(user.uid)
            .set(data, SetOptions.merge())
    }
}
