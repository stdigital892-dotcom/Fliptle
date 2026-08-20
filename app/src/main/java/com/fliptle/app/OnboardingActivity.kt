package com.fliptle.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.fliptle.app.auth.SignInActivity

/**
 * First-launch flow: intro -> optional sign-in -> permissions requested one at a
 * time in order (usage access, overlay, Accessibility), each with a plain-language
 * reason. The Accessibility step carries the full disclosure.
 */
class OnboardingActivity : AppCompatActivity() {

    private var step = STEP_INTRO

    private lateinit var titleText: TextView
    private lateinit var bodyText: TextView
    private lateinit var statusText: TextView
    private lateinit var actionButton: Button
    private lateinit var backButton: Button
    private lateinit var nextButton: Button
    private lateinit var tutorialButton: Button
    private lateinit var root: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)
        root = findViewById(R.id.onboardingRoot)
        titleText = findViewById(R.id.stepTitle)
        bodyText = findViewById(R.id.stepBody)
        statusText = findViewById(R.id.stepStatus)
        actionButton = findViewById(R.id.actionButton)
        backButton = findViewById(R.id.backButton)
        nextButton = findViewById(R.id.nextButton)
        tutorialButton = findViewById(R.id.tutorialButton)

        tutorialButton.setOnClickListener { openTutorial() }
        actionButton.setOnClickListener { onAction() }
        backButton.setOnClickListener { if (step > 0) { step--; render() } }
        nextButton.setOnClickListener { onNext() }

        // If onboarding was already completed but a permission is now missing,
        // resume directly at the first missing permission step.
        if (OnboardingState(this).complete) {
            val missing = firstMissingPermissionStep()
            if (missing < 0) {
                goHome()
                return
            }
            step = missing
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        render() // refresh permission status after returning from a settings screen
    }

    private fun onAction() {
        when (step) {
            STEP_SIGNIN -> startActivity(Intent(this, SignInActivity::class.java))
            STEP_USAGE -> startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            STEP_OVERLAY -> startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            STEP_ACCESSIBILITY -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun onNext() {
        if (step < STEP_LAST) {
            step++
            render()
        } else {
            // Finishing requires every enforcement permission to be granted.
            if (Permissions.allEnforcementGranted(this)) {
                OnboardingState(this).complete = true
                goHome()
            } else {
                step = firstMissingPermissionStep().let { if (it < 0) STEP_LAST else it }
                render()
            }
        }
    }

    private fun goHome() {
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }

    /** Index of the first ungranted permission step, or -1 if all are granted. */
    private fun firstMissingPermissionStep(): Int = when {
        !Permissions.hasUsageAccess(this) -> STEP_USAGE
        !Permissions.hasOverlay(this) -> STEP_OVERLAY
        !Permissions.isAccessibilityEnabled(this) -> STEP_ACCESSIBILITY
        else -> -1
    }

    /** Whether the current step's requirement is satisfied (gates the Next button). */
    private fun stepSatisfied(): Boolean = when (step) {
        STEP_INTRO -> true
        // Sign-in is MANDATORY — Next stays disabled until a user is signed in.
        // (If Firebase isn't configured, signing in is impossible, so the step
        // stands down rather than trapping the user on an unusable screen.)
        STEP_SIGNIN -> !AuthGate.required(this)
        STEP_USAGE -> Permissions.hasUsageAccess(this)
        STEP_OVERLAY -> Permissions.hasOverlay(this)
        STEP_ACCESSIBILITY -> Permissions.isAccessibilityEnabled(this)
        else -> true
    }

    /** Placeholder tutorial link — swap for the real video when it's published. */
    private fun openTutorial() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TUTORIAL_URL)))
        } catch (_: Exception) {
            // No browser/YouTube available; nothing to do.
        }
    }

    private fun render() {
        backButton.visibility = if (step > STEP_INTRO) View.VISIBLE else View.GONE
        actionButton.visibility = View.VISIBLE
        statusText.visibility = View.VISIBLE
        // The tutorial link — and the atmospheric Welcome illustration — belong on
        // the intro step only. Other steps fall back to the shared window glow.
        tutorialButton.visibility = if (step == STEP_INTRO) View.VISIBLE else View.GONE
        root.setBackgroundResource(if (step == STEP_INTRO) R.drawable.bg_welcome else 0)
        nextButton.text = getString(if (step == STEP_LAST) R.string.ob_finish else R.string.ob_next)
        // Compulsory: Next/Finish stays disabled until the step is actually satisfied.
        nextButton.isEnabled = stepSatisfied()

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
                nextButton.setText(R.string.ob_continue)
                val account = signedInAccount()
                statusText.text = if (account != null) {
                    getString(R.string.status_signed_in, account)
                } else {
                    getString(R.string.status_not_signed_in)
                }
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
            STEP_ACCESSIBILITY -> {
                titleText.setText(R.string.ob_a11y_title)
                bodyText.setText(R.string.ob_a11y_body)
                actionButton.setText(R.string.ob_a11y_action)
                showEnabled(Permissions.isAccessibilityEnabled(this))
            }
        }
    }

    /** Signed-in email/uid if Firebase is configured and a user is present. */
    private fun signedInAccount(): String? {
        if (!com.fliptle.app.auth.FirebaseGate.isAvailable(this)) return null
        val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser ?: return null
        return user.email ?: user.uid
    }

    private fun showGranted(granted: Boolean) {
        statusText.setText(if (granted) R.string.status_granted else R.string.status_not_granted)
    }

    private fun showEnabled(enabled: Boolean) {
        statusText.setText(if (enabled) R.string.status_enabled else R.string.status_not_enabled)
    }

    companion object {
        /** PLACEHOLDER — replace with the real Fliptle tutorial video URL. */
        private const val TUTORIAL_URL = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"

        private const val STEP_INTRO = 0
        private const val STEP_SIGNIN = 1
        private const val STEP_USAGE = 2
        private const val STEP_OVERLAY = 3
        private const val STEP_ACCESSIBILITY = 4
        private const val STEP_LAST = STEP_ACCESSIBILITY
    }
}
