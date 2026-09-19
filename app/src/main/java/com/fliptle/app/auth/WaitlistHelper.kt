package com.fliptle.app.auth

import android.content.Context
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

/**
 * Fire-and-forget: write the user's email to the shared Firestore `waitlist`
 * collection after sign-in. The Cloud Function `sendWaitlistWelcome` fires on
 * every NEW document in that collection and sends the offer email.
 *
 * Duplicate prevention: the email is used as the document ID. Firestore's
 * onDocumentCreated only fires when a document transitions from non-existent
 * to existing — so re-signing in just updates the same doc and never
 * re-triggers the Cloud Function or sends a second email. No read required,
 * which avoids silent failures from Firestore read rules on this collection.
 */
object WaitlistHelper {

    private const val COLLECTION = "waitlist"

    fun maybeAddToWaitlist(context: Context, email: String) {
        if (!FirebaseGate.isAvailable(context)) return
        val normalized = email.trim().lowercase()
        if (normalized.isEmpty()) return
        // Use email as doc ID — idempotent by design; merge preserves any
        // existing fields (e.g. welcomeEmailSentAt set by the Cloud Function).
        FirebaseFirestore.getInstance()
            .collection(COLLECTION)
            .document(normalized)
            .set(
                mapOf(
                    "email" to normalized,
                    "source" to "app",
                    "signedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            )
    }
}
