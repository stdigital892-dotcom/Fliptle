package com.fliptle.app

import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.fliptle.app.auth.AuthStore
import com.fliptle.app.auth.PhoneAuthActivity

/**
 * First-launch flow: intro -> optional phone sign-in -> permissions requested
 * one at a time in order (usage access, overlay, VPN, Accessibility), each with
 * a plain-language reason. The Accessibility step carries the full disclosure.
 */
class OnboardingActivity : AppCompatActivity() {

    private var step = STEP_INTRO

    private lateinit var titleText: TextView
    private lateinit var bodyText: TextView
    private lateinit var statusText: TextView
    private lateinit var actionButton: Button
    private lateinit var backButton: Button
    private lateinit var nextButton: Button

    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) DnsVpnService.start(this)
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)
        titleText = findViewById(R.id.stepTitle)
        bodyText = findViewById(R.id.stepBody)
        statusText = findViewById(R.id.stepStatus)
        actionButton = findViewById(R.id.actionButton)
        backButton = findViewById(R.id.backButton)
        nextButton = findViewById(R.id.nextButton)

        actionButton.setOnClickListener { onAction() }
        backButton.setOnClickListener { if (step > 0) { step--; render() } }
        nextButton.setOnClickListener { onNext() }
        render()
    }

    override fun onResume() {
        super.onResume()
        render() // refresh permission status after returning from a settings screen
    }

    private fun onAction() {
        when (step) {
            STEP_SIGNIN -> startActivity(Intent(this, PhoneAuthActivity::class.java))
            STEP_USAGE -> startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            STEP_OVERLAY -> startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            STEP_VPN -> {
                val consent = VpnService.prepare(this)
                if (consent != null) vpnLauncher.launch(consent) else DnsVpnService.start(this)
                render()
            }
            STEP_ACCESSIBILITY -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun onNext() {
        if (step < STEP_LAST) {
            step++
            render()
        } else {
            OnboardingState(this).complete = true
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
        }
    }

    private fun render() {
        backButton.visibility = if (step > STEP_INTRO) View.VISIBLE else View.GONE
        actionButton.visibility = View.VISIBLE
        statusText.visibility = View.VISIBLE
        nextButton.text = getString(if (step == STEP_LAST) R.string.ob_finish else R.string.ob_next)

        when (step) {
            STEP_INTRO -> {
                titleText.setText(R.string.ob_intro_title)
                bodyText.setText(R.string.ob_intro_body)
                actionButton.visibility = View.GONE
                statusText.visibility = View.GONE
                nextButton.setText(R.string.ob_get_started)
            }
            STEP_SIGNIN -> {
                titleText.setText(R.string.ob_signin_title)
                bodyText.setText(R.string.ob_signin_body)
                actionButton.setText(R.string.ob_signin_action)
                val phone = AuthStore(this).signedInPhone
                statusText.text = if (phone != null) {
                    getString(R.string.status_signed_in, phone)
                } else {
                    getString(R.string.status_not_signed_in)
                }
                nextButton.setText(R.string.ob_continue)
            }
            STEP_USAGE -> {
                titleText.setText(R.string.ob_usage_title)
                bodyText.setText(R.string.ob_usage_body)
                actionButton.setText(R.string.ob_usage_action)
                showGranted(Permissions.hasUsageAccess(this))
            }
            STEP_OVERLAY -> {
                titleText.setText(R.string.ob_overlay_title)
                bodyText.setText(R.string.ob_overlay_body)
                actionButton.setText(R.string.ob_overlay_action)
                showGranted(Permissions.hasOverlay(this))
            }
            STEP_VPN -> {
                titleText.setText(R.string.ob_vpn_title)
                bodyText.setText(R.string.ob_vpn_body)
                actionButton.setText(R.string.ob_vpn_action)
                showGranted(Permissions.hasVpnConsent(this))
            }
            STEP_ACCESSIBILITY -> {
                titleText.setText(R.string.ob_a11y_title)
                bodyText.setText(R.string.ob_a11y_body)
                actionButton.setText(R.string.ob_a11y_action)
                showEnabled(Permissions.isAccessibilityEnabled(this))
            }
        }
    }

    private fun showGranted(granted: Boolean) {
        statusText.setText(if (granted) R.string.status_granted else R.string.status_not_granted)
    }

    private fun showEnabled(enabled: Boolean) {
        statusText.setText(if (enabled) R.string.status_enabled else R.string.status_not_enabled)
    }

    companion object {
        private const val STEP_INTRO = 0
        private const val STEP_SIGNIN = 1
        private const val STEP_USAGE = 2
        private const val STEP_OVERLAY = 3
        private const val STEP_VPN = 4
        private const val STEP_ACCESSIBILITY = 5
        private const val STEP_LAST = STEP_ACCESSIBILITY
    }
}
