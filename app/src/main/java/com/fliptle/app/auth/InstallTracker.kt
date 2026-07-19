package com.fliptle.app.auth

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.remoteconfig.FirebaseRemoteConfig

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
    private const val KEY_THRESHOLD = "reinstall_price_increase_threshold"

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
        val threshold = FirebaseRemoteConfig.getInstance().getLong(KEY_THRESHOLD)
            .let { if (it <= 0L) 1L else it }
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
                        "lastSignInAt" to now,
                        "priceIncreaseFlagged" to false
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
                    val already = snap.getBoolean("priceIncreaseFlagged") ?: false
                    val flagged = already || reinstalls >= threshold
                    txn.update(
                        doc, common + mapOf(
                            "installCount" to installs,
                            "reinstallCount" to reinstalls,
                            "lastInstallId" to installId,
                            "previousInstallId" to storedId,
                            "lastReinstallAt" to now,
                            "priceIncreaseFlagged" to flagged
                        )
                    )
                    Outcome.Reinstall(reinstalls, threshold, flaggedNow = flagged && !already, prevId = storedId)
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
                    if (outcome.flaggedNow) {
                        logEvent(
                            doc, analytics, "price_increase_flagged",
                            mapOf("reinstallCount" to outcome.count, "threshold" to outcome.threshold)
                        )
                    }
                    onResult(
                        "Reinstall #${outcome.count} detected." +
                            if (outcome.flaggedNow) " Account flagged for a price increase." else ""
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

    /** Save the parent's phone number as contact-only info on the user's record. */
    fun saveParentPhone(context: Context, uid: String, phone: String, onResult: (String) -> Unit) {
        if (!FirebaseGate.isAvailable(context)) {
            onResult("Firebase not configured — phone not saved.")
            return
        }
        FirebaseFirestore.getInstance().collection(COLLECTION).document(uid)
            .update("parentPhone", phone)
            .addOnSuccessListener { onResult("Parent phone saved.") }
            .addOnFailureListener { e -> onResult("Could not save phone: ${e.message}") }
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
            val threshold: Long,
            val flaggedNow: Boolean,
            val prevId: String
        ) : Outcome()
    }
}
