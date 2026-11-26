package com.fliptle.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.fliptle.app.auth.EntitlementStore
import com.google.firebase.functions.FirebaseFunctions
import java.text.DateFormat
import java.util.Date

/**
 * Shown by MainActivity instead of InboxConfirmActivity when entitlement is
 * explicitly DENIED for a LAPSED user (reason "expired") -- a new user who
 * never had a plan (reason "none") still goes to InboxConfirmActivity.
 *
 * Primary action re-sends the renewal email via the sendExpiryRenewalEmail
 * callable (no arguments). "Continue on fliptle.com" opens the offer page in
 * Chrome -- see the class doc note on why this is a plain ACTION_VIEW rather
 * than a true Chrome Custom Tab (no new library was added for this task
 * beyond firebase-messaging, which androidx.browser is not part of).
 */
class PlanExpiredActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_EMAIL = "extra_email"
        private const val REGION = "asia-south2"
    }

    private var email = ""
    private lateinit var headlineText: TextView
    private lateinit var resendButton: Button
    private lateinit var sentText: TextView
    private lateinit var websiteLink: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_plan_expired)
        EdgeToEdge.applyInsets(this)

        email = intent.getStringExtra(EXTRA_EMAIL) ?: ""
        headlineText = findViewById(R.id.planExpiredHeadline)
        resendButton = findViewById(R.id.planExpiredResendButton)
        sentText = findViewById(R.id.planExpiredSentText)
        websiteLink = findViewById(R.id.planExpiredWebsiteLink)

        val store = EntitlementStore(this)
        val planName = store.planName ?: getString(R.string.plan_expired_plan_fallback)
        val dateText = store.expiredAt?.let {
            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it))
        } ?: getString(R.string.plan_expired_date_fallback)
        headlineText.text = getString(R.string.plan_expired_headline, planName, dateText)

        resendButton.setOnClickListener { resend() }
        websiteLink.setOnClickListener { openWebsite() }
        findViewById<View>(R.id.planExpiredExitLink).setOnClickListener {
            startActivity(Intent(this, UninstallRequestActivity::class.java))
        }
        findViewById<View>(R.id.planExpiredDeleteLink).setOnClickListener {
            startActivity(Intent(this, AccountDeletionActivity::class.java))
        }
    }

    private fun resend() {
        resendButton.isEnabled = false
        sentText.visibility = View.GONE
        FirebaseFunctions.getInstance(REGION).getHttpsCallable("sendExpiryRenewalEmail")
            .call() // no arguments, per contract
            .addOnCompleteListener { task ->
                resendButton.isEnabled = true
                if (task.isSuccessful) {
                    sentText.visibility = View.VISIBLE
                } else {
                    Toast.makeText(this, R.string.plan_expired_resend_failed, Toast.LENGTH_SHORT).show()
                }
            }
    }

    private fun openWebsite() {
        val encodedEmail = Uri.encode(email)
        val url = "https://fliptle.com/offer.html?email=$encodedEmail&source=expiry"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.android.chrome")
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Chrome isn't installed; fall back to whatever handles http(s).
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }
}
