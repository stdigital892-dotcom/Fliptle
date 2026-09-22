package com.fliptle.app.auth

import android.content.Context
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

/**
 * Fire-and-forget: write the user's email to the Firestore `appSignups`
 * collection after sign-in. This is a SEPARATE group from the website's
 * `waitlist` collection — that one is only for pre-launch campaign visitors
 * who don't have the app yet. App users are already using the app and are
 * choosing a real plan, so they get a different Cloud Function
 * (`sendAppWelcomeEmail`) and a different email (plan-selection wording,
 * never "early access").
 *
 * Duplicate prevention: the email is used as the document ID. Firestore's
 * onDocumentCreated only fires when a document transitions from non-existent
 * to existing — so re-signing in just updates the same doc and never
 * re-triggers the Cloud Function or sends a second email. No read required,
 * which avoids silent failures from Firestore read rules on this collection.
 */
object AppSignupHelper {

    private const val COLLECTION = "appSignups"

    fun maybeRecordSignup(context: Context, email: String) {
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
