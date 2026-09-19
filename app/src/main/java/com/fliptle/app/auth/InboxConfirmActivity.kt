package com.fliptle.app.auth

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.fliptle.app.CloudState
import com.fliptle.app.MainActivity
import com.fliptle.app.R
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

/**
 * Shown exactly once per account (first sign-in only) after the phone step.
 * Tells the user their welcome/offer email is on its way and gives them a
 * one-tap shortcut to their email app. Back navigation is disabled — the
 * only exit is "Take me to my email."
 */
class InboxConfirmActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_EMAIL = "extra_email"
    }

    private var email = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_inbox_confirm)

        email = intent.getStringExtra(EXTRA_EMAIL) ?: ""

        // Mark shown immediately so a back-stack edge-case never re-shows it.
        val store = AuthStore(this)
        store.inboxConfirmShown = true
        CloudState.backup(this)

        findViewById<TextView>(R.id.inboxSubtext).text =
            getString(R.string.inbox_confirm_subtext, email.ifEmpty { "your email" })

        findViewById<Button>(R.id.goToEmailButton).setOnClickListener {
            openEmailApp()
            goToMain()
        }

        findViewById<TextView>(R.id.resendText).setOnClickListener {
            resendWaitlistEmail()
        }
    }

    private fun openEmailApp() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_APP_EMAIL)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // No email app installed — proceed anyway.
        }
    }

    private fun goToMain() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    /** Delete + re-create the waitlist doc so onDocumentCreated fires again. */
    private fun resendWaitlistEmail() {
        if (!FirebaseGate.isAvailable(this) || email.isEmpty()) {
            Toast.makeText(this, R.string.inbox_resend_ok, Toast.LENGTH_SHORT).show()
            return
        }
        val normalized = email.trim().lowercase()
        val db = FirebaseFirestore.getInstance()
        db.collection("waitlist").document(normalized)
            .delete()
            .addOnCompleteListener {
                db.collection("waitlist").document(normalized)
                    .set(
                        mapOf(
                            "email" to normalized,
                            "source" to "app",
                            "signedAt" to FieldValue.serverTimestamp()
                        ),
                        SetOptions.merge()
                    )
                Toast.makeText(this, R.string.inbox_resend_ok, Toast.LENGTH_SHORT).show()
            }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Intentionally blocked — user must tap "Take me to my email."
    }
}
