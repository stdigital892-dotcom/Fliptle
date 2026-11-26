package com.fliptle.app.auth

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Logs uninstall-request progress and approval to Firestore, keyed to the signed-in
 * user's UID (with email for reference). No-ops when Firebase is unavailable or no
 * user is signed in — the gate itself still works locally.
 */
object UninstallLog {

    private const val COLLECTION = "installs"

    fun logDayComplete(context: Context, day: Int, totalDays: Int) {
        withUser(context) { db, uid, email ->
            db.collection(COLLECTION).document(uid)
                .collection("uninstall_progress")
                .add(
                    mapOf(
                        "type" to "uninstall_day_complete",
                        "day" to day,
                        "totalDays" to totalDays,
                        "email" to email,
                        "timestamp" to FieldValue.serverTimestamp()
                    )
                )
        }
    }

    fun logApproved(context: Context) {
        withUser(context) { db, uid, email ->
            // PARTNER-NOTIFICATION HOOK: when partner push is added, trigger the
            // "uninstall approved" notification to the paired partner here (this is
            // the approved-path event). Attach off the uid/email written below.
            val doc = db.collection(COLLECTION).document(uid)
            doc.set(
                mapOf(
                    "uninstall_approved" to true,
                    "uninstall_approved_at" to FieldValue.serverTimestamp(),
                    "email" to email
                ),
                com.google.firebase.firestore.SetOptions.merge()
            )
            doc.collection("uninstall_progress").add(
                mapOf(
                    "type" to "uninstall_approved",
                    "email" to email,
                    "timestamp" to FieldValue.serverTimestamp()
                )
            )
        }
    }

    /**
     * Day 5 only: the same write as [logApproved], but awaited -- [onResult]
     * fires with whether the write actually reached the server. The
     * "uninstall_approved" progress-log entry is added only on success, so a
     * failed attempt leaves no misleading log trail. Call on the main thread;
     * [onResult] is invoked on the main thread too.
     */
    fun logApprovedAwait(context: Context, onResult: (Boolean) -> Unit) {
        if (!FirebaseGate.isAvailable(context)) { onResult(false); return }
        val user = FirebaseAuth.getInstance().currentUser ?: run { onResult(false); return }
        val db = FirebaseFirestore.getInstance()
        val doc = db.collection(COLLECTION).document(user.uid)
        doc.set(
            mapOf(
                "uninstall_approved" to true,
                "uninstall_approved_at" to FieldValue.serverTimestamp(),
                "email" to user.email
            ),
            com.google.firebase.firestore.SetOptions.merge()
        ).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                doc.collection("uninstall_progress").add(
                    mapOf(
                        "type" to "uninstall_approved",
                        "email" to user.email,
                        "timestamp" to FieldValue.serverTimestamp()
                    )
                )
            }
            onResult(task.isSuccessful)
        }
    }

    private inline fun withUser(
        context: Context,
        block: (FirebaseFirestore, String, String?) -> Unit
    ) {
        if (!FirebaseGate.isAvailable(context)) return
        val user = FirebaseAuth.getInstance().currentUser ?: return
        block(FirebaseFirestore.getInstance(), user.uid, user.email)
    }
}
