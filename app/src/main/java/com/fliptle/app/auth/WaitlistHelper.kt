package com.fliptle.app.auth

import android.content.Context
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Fire-and-forget: write the user's email to the shared Firestore `waitlist`
 * collection after sign-in. The Cloud Function `sendWaitlistWelcome` fires on
 * every NEW document in that collection and sends the offer email.
 *
 * Duplicate prevention: queries first; skips the write if any document with
 * the same email already exists (covers both website and app sign-ups).
 */
object WaitlistHelper {

    private const val COLLECTION = "waitlist"

    fun maybeAddToWaitlist(context: Context, email: String) {
        if (!FirebaseGate.isAvailable(context)) return
        val normalized = email.trim().lowercase()
        if (normalized.isEmpty()) return
        val db = FirebaseFirestore.getInstance()
        db.collection(COLLECTION)
            .whereEqualTo("email", normalized)
            .limit(1)
            .get()
            .addOnSuccessListener { snap ->
                if (snap.isEmpty) {
                    db.collection(COLLECTION).add(
                        mapOf(
                            "email" to normalized,
                            "source" to "app",
                            "signedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
            }
    }
}
