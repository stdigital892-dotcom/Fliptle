package com.fliptle.app.auth

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
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
 * Post-sign-in screen and renewal screen in one. Shown when [MainActivity]
 * cannot route the user to Home — either because they've never seen this
 * screen (fresh sign-in) or because their entitlement is not ENTITLED
 * (never was, or expired).
 *
 * "Take me to my email" is always visible. "I've confirmed — continue" is
 * hidden until [EntitlementGate] returns entitled=true. This screen polls
 * getEntitlement every 5s while resumed, so a user who pays in a browser
 * and comes back is let in automatically without tapping anything.
 */
class InboxConfirmActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_EMAIL = "extra_email"
        private const val POLL_INTERVAL_MS = 5_000L
    }

    private var email = ""

    private lateinit var goToEmailButton: Button
    private lateinit var continueButton: Button
    private lateinit var inboxSubtext: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            checkEntitlement()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_inbox_confirm)

        email = intent.getStringExtra(EXTRA_EMAIL) ?: ""

        // Mark shown immediately so a back-stack edge-case never re-shows it.
        val store = AuthStore(this)
        store.inboxConfirmShown = true
        CloudState.backup(this)

        // Personalize the headline with the display name if one was given —
        // never the subtext below, which names the email the link was sent to
        // (necessary information a name can't stand in for).
        val name = store.signedInName
        findViewById<TextView>(R.id.inboxHeadline).text = if (!name.isNullOrBlank()) {
            getString(R.string.inbox_confirm_headline_named, name)
        } else {
            getString(R.string.inbox_confirm_headline)
        }

        inboxSubtext = findViewById(R.id.inboxSubtext)
        inboxSubtext.text = getString(R.string.inbox_confirm_subtext, email.ifEmpty { "your email" })

        goToEmailButton = findViewById(R.id.goToEmailButton)
        continueButton = findViewById(R.id.continueButton)

        goToEmailButton.setOnClickListener {
            openEmailApp()
            // Deliberately do NOT navigate away — user comes back and taps the
            // "I've confirmed" button after they've opened the link in their email.
        }

        continueButton.setOnClickListener { goToMain() }

        findViewById<TextView>(R.id.resendText).setOnClickListener {
            resendSignupEmail()
        }

        // Continue starts hidden. Only an ENTITLED result from getEntitlement
        // (or, if we already have that cached from a previous check) reveals
        // it. Never surface it purely on local state absent a positive
        // server signal.
        applyEntitlementUi()
    }

    override fun onResume() {
        super.onResume()
        // Poll while the screen is visible so a user who pays in the browser
        // and comes back is let in without tapping anything. Every 5 seconds
        // matches the user's request; the callable is cheap and each response
        // caches into EntitlementStore.
        handler.removeCallbacks(poll)
        handler.post(poll) // fires immediately, then every POLL_INTERVAL_MS
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poll)
    }

    private fun checkEntitlement() {
        EntitlementGate.check(this) { result ->
            when (result) {
                is EntitlementGate.Result.Entitled -> applyEntitlementUi()
                is EntitlementGate.Result.Denied -> applyEntitlementUi()
                else -> {
                    // Transient error or unauthenticated — do NOT change the
                    // UI. The store isn't touched by these results either,
                    // so re-reading it below preserves the last known state.
                    applyEntitlementUi()
                }
            }
        }
    }

    private fun applyEntitlementUi() {
        val state = EntitlementStore(this).state
        if (state == EntitlementStore.State.ENTITLED) {
            continueButton.visibility = View.VISIBLE
        } else {
            continueButton.visibility = View.GONE
        }
    }

    private fun openEmailApp() {
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_APP_EMAIL)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("mailto:")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(fallback)
        }
    }

    private fun goToMain() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    /** Delete + re-create the appSignups doc so onDocumentCreated fires again. */
    private fun resendSignupEmail() {
        if (!FirebaseGate.isAvailable(this) || email.isEmpty()) {
            Toast.makeText(this, R.string.inbox_resend_ok, Toast.LENGTH_SHORT).show()
            return
        }
        val normalized = email.trim().lowercase()
        val db = FirebaseFirestore.getInstance()
        db.collection("appSignups").document(normalized)
            .delete()
            .addOnCompleteListener {
                db.collection("appSignups").document(normalized)
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
