package com.fliptle.app.auth

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Records install/reinstall history per user in Firestore, keyed by the Firebase
 * Auth UID (NOT the phone number — the phone is contact info only). Mirrors
 * lifecycle events to Analytics for reinstall/churn analysis.
 *
 * Reinstall detection: each fresh install has a new local install ID. When a UID
 * that already has a record signs in with a DIFFERENT install ID, the previous
 * install must have been removed -> logged as an inferred uninstall + reinstall,
 * and (at/above the configurable threshold) the account is flagged for a price
 * increase. Android has no real-time self-uninstall callback, so the uninstall is
 * inferred at the next sign-in.
 */
object InstallTracker {

    private const val COLLECTION = "installs"
    private const val EVENTS = "events"

    fun recordSignIn(
        context: Context,
        uid: String,
        email: String?,
        method: String,
        installId: String,
        onResult: (String) -> Unit
    ) {
        if (!FirebaseGate.isAvailable(context)) {
            onResult("Firebase not configured — sign-in recorded locally only.")
            return
        }

        val db = FirebaseFirestore.getInstance()
        val analytics = FirebaseAnalytics.getInstance(context)
        val graceMs = Heartbeat.graceMs(context)
        val doc = db.collection(COLLECTION).document(uid)

        db.runTransaction { txn ->
            val snap = txn.get(doc)
            val now = System.currentTimeMillis()
            if (!snap.exists()) {
                txn.set(
                    doc, mapOf(
                        "uid" to uid,
                        "email" to email,
                        "signInMethod" to method,
                        "firstInstallAt" to now,
                        "installCount" to 1L,
                        "reinstallCount" to 0L,
                        "lastInstallId" to installId,
                        "lastSignInAt" to now
                    )
                )
                Outcome.Install
            } else {
                val storedId = snap.getString("lastInstallId")
                val common = mapOf(
                    "email" to email,
                    "signInMethod" to method,
                    "lastSignInAt" to now
                )
                if (storedId != null && storedId != installId) {
                    val reinstalls = (snap.getLong("reinstallCount") ?: 0L) + 1
                    val installs = (snap.getLong("installCount") ?: 0L) + 1
                    // Direct uninstall = previous install went dark past the grace
                    // window without ever being uninstall-approved.
                    val approved = snap.getBoolean("uninstall_approved") ?: false
                    val lastBeat = snap.getLong("lastHeartbeatMs") ?: 0L
                    val darkMs = if (lastBeat > 0L) now - lastBeat else Long.MAX_VALUE
                    val directUninstall = !approved && darkMs >= graceMs
                    txn.update(
                        doc, common + mapOf(
                            "installCount" to installs,
                            "reinstallCount" to reinstalls,
                            "lastInstallId" to installId,
                            "previousInstallId" to storedId,
                            "lastReinstallAt" to now
                        )
                    )
                    Outcome.Reinstall(
                        reinstalls,
                        prevId = storedId, directUninstall = directUninstall, darkMs = darkMs
                    )
                } else {
                    txn.update(doc, common)
                    Outcome.SignIn
                }
            }
        }.addOnSuccessListener { outcome ->
            when (outcome) {
                is Outcome.Install -> {
                    logEvent(doc, analytics, "install", mapOf("installId" to installId))
                    onResult("Signed in. First install recorded.")
                }
                is Outcome.Reinstall -> {
                    logEvent(doc, analytics, "uninstall_detected", mapOf("previousInstallId" to outcome.prevId))
                    logEvent(doc, analytics, "reinstall", mapOf("reinstallCount" to outcome.count))
                    if (outcome.directUninstall) {
                        // PARTNER-NOTIFICATION HOOK: when partner push is added,
                        // trigger the "direct uninstall detected" notification to the
                        // paired partner here (this is the direct-uninstall path,
                        // separate from the approved path). Keyed off this uid/email.
                        logEvent(
                            doc, analytics, "direct_uninstall",
                            mapOf("darkMs" to outcome.darkMs, "previousInstallId" to outcome.prevId)
                        )
                    }
                    onResult(
                        "Reinstall #${outcome.count} detected." +
                            (if (outcome.directUninstall) " (direct uninstall logged.)" else "")
                    )
                }
                is Outcome.SignIn -> {
                    logEvent(doc, analytics, "sign_in", emptyMap())
                    onResult("Signed in.")
                }
            }
        }.addOnFailureListener { e ->
            onResult("Install tracking failed: ${e.message}")
        }
    }

    /** Save the optional display name, shown in place of the email wherever the
     *  app greets the user. */
    fun saveDisplayName(context: Context, uid: String, name: String, onResult: (String) -> Unit) {
        if (!FirebaseGate.isAvailable(context)) {
            onResult("Firebase not configured — name saved on device only.")
            return
        }
        FirebaseFirestore.getInstance().collection(COLLECTION).document(uid)
            .set(mapOf("displayName" to name), com.google.firebase.firestore.SetOptions.merge())
            .addOnSuccessListener { onResult("Name saved.") }
            .addOnFailureListener { e -> onResult("Could not sync name (saved on device): ${e.message}") }
    }

    /** Best-effort read of any display name already stored for this user, so a
     *  returning/reinstalled user who already provided one isn't re-prompted. */
    fun fetchDisplayName(context: Context, uid: String, onResult: (String?) -> Unit) {
        if (!FirebaseGate.isAvailable(context)) {
            onResult(null)
            return
        }
        FirebaseFirestore.getInstance().collection(COLLECTION).document(uid)
            .get()
            .addOnSuccessListener { onResult(it.getString("displayName")) }
            .addOnFailureListener { onResult(null) }
    }

    private fun logEvent(
        doc: DocumentReference,
        analytics: FirebaseAnalytics,
        type: String,
        extra: Map<String, Any?>
    ) {
        val data = HashMap<String, Any?>()
        data["type"] = type
        data["timestamp"] = System.currentTimeMillis()
        data.putAll(extra)
        doc.collection(EVENTS).add(data)

        val bundle = Bundle().apply { putString("event_type", type) }
        analytics.logEvent("install_lifecycle", bundle)
    }

    private sealed class Outcome {
        object Install : Outcome()
        object SignIn : Outcome()
        data class Reinstall(
            val count: Long,
            val prevId: String,
            val directUninstall: Boolean,
            val darkMs: Long
        ) : Outcome()
    }
}
