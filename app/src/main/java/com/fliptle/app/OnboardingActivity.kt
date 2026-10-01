package com.fliptle.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.fliptle.app.auth.SignInActivity

/**
 * First-launch flow: intro -> optional sign-in (+ the combined name/phone
 * screen, hosted in SignInActivity) -> permissions requested one at a time in
 * order (usage access, overlay, Accessibility, then a combined OEM-guidance +
 * battery-optimization step), each with a plain-language reason. The
 * Accessibility step carries the full disclosure. The final step shows
 * brand-specific guidance (Xiaomi/Oppo/Vivo/OnePlus/Samsung battery killers)
 * alongside the battery-exemption request on known-aggressive manufacturers,
 * and just the battery request on everyone else.
 *
 * A "Step X of Y" indicator (see [OnboardingProgress]) spans this Activity
 * and the name/phone screen in SignInActivity — hidden on Intro only.
 */
class OnboardingActivity : AppCompatActivity() {

    private var step = STEP_INTRO

    private lateinit var progressSection: View
    private lateinit var progressText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var titleText: TextView
    private lateinit var bodyText: TextView
    private lateinit var statusText: TextView
    private lateinit var actionButton: Button
    private lateinit var backButton: Button
    private lateinit var nextButton: Button
    private lateinit var tutorialButton: Button
    private lateinit var root: View
    private lateinit var stepsSection: View
    private lateinit var stepText1: TextView
    private lateinit var stepText2: TextView
    private lateinit var stepText3: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)
        root = findViewById(R.id.onboardingRoot)
        progressSection = findViewById(R.id.progressSection)
        progressText = findViewById(R.id.progressText)
        progressBar = findViewById(R.id.progressBar)
        titleText = findViewById(R.id.stepTitle)
        bodyText = findViewById(R.id.stepBody)
        statusText = findViewById(R.id.stepStatus)
        actionButton = findViewById(R.id.actionButton)
        backButton = findViewById(R.id.backButton)
        nextButton = findViewById(R.id.nextButton)
        tutorialButton = findViewById(R.id.tutorialButton)
        stepsSection = findViewById(R.id.stepsSection)
        stepText1 = findViewById(R.id.stepText1)
        stepText2 = findViewById(R.id.stepText2)
        stepText3 = findViewById(R.id.stepText3)

        tutorialButton.setOnClickListener { openTutorial() }
        actionButton.setOnClickListener { onAction() }
        backButton.setOnClickListener { if (step > firstStep()) { step--; render() } }
        nextButton.setOnClickListener { onNext() }

        // A signed-in user who has not cleared the paywall never sees a
        // permission step: MainActivity holds them on the inbox screen.
        if (routeIfUnpaid()) return

        // If onboarding was already completed but a permission is now missing,
        // resume directly at the first missing permission step.
        if (OnboardingState(this).complete) {
            val missing = firstMissingPermissionStep()
            if (missing < 0) {
                routeThroughMain()
                return
            }
            step = missing
        } else {
            step = firstStep()
        }
        render()
    }

    /** Signed in means Intro and Sign-in are behind us (and the paywall was
     *  cleared to get here), so the flow begins at the first permission step. */
    private fun firstStep(): Int = if (AuthGate.signedIn(this)) STEP_USAGE else STEP_INTRO

    /** True (and leaves) if the user is signed in but has not cleared the paywall. */
    private fun routeIfUnpaid(): Boolean {
        if (!AuthGate.signedIn(this) || PaywallGate.open(this)) return false
        routeThroughMain()
        return true
    }

    override fun onResume() {
        super.onResume()
        // Covers system-Back landing here from the profile screen before the
        // paywall is cleared.
        if (routeIfUnpaid()) return
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
            STEP_OEM_BATTERY -> try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {
                // Some OEM skins don't implement this standard dialog; fall back
                // to the app's own settings screen.
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            }
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
                routeThroughMain()
            } else {
                step = firstMissingPermissionStep().let { if (it < 0) STEP_LAST else it }
                render()
            }
        }
    }

    /** Hand off to [MainActivity]'s routing chain rather than jumping to Home
     *  directly. MainActivity enforces the full gate stack —
     *    uninstall-info -> inbox-confirm -> entitlement -> Home —
     *  which an onboarding-finishes-with-startActivity(HomeActivity) shortcut
     *  silently skipped, letting unpaid users straight into Home. */
    private fun routeThroughMain() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    /** Index of the first ungranted permission step, or -1 if all are granted. */
    private fun firstMissingPermissionStep(): Int = when {
        !Permissions.hasUsageAccess(this) -> STEP_USAGE
        !Permissions.hasOverlay(this) -> STEP_OVERLAY
        !Permissions.isAccessibilityEnabled(this) -> STEP_ACCESSIBILITY
        !Permissions.hasBatteryExemption(this) -> STEP_OEM_BATTERY
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
        STEP_OEM_BATTERY -> Permissions.hasBatteryExemption(this)
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
        backButton.visibility = if (step > firstStep()) View.VISIBLE else View.GONE
        actionButton.visibility = View.VISIBLE
        statusText.visibility = View.VISIBLE
        // The tutorial link — and the atmospheric Welcome illustration — belong on
        // the intro step only. Other steps fall back to the shared window glow.
        tutorialButton.visibility = if (step == STEP_INTRO) View.VISIBLE else View.GONE
        root.setBackgroundResource(if (step == STEP_INTRO) R.drawable.bg_welcome else 0)
        // Numbered step cards only exist on the Accessibility screen.
        stepsSection.visibility = if (step == STEP_ACCESSIBILITY) View.VISIBLE else View.GONE
        nextButton.text = getString(if (step == STEP_LAST) R.string.ob_finish else R.string.ob_next)
        // Compulsory: Next/Finish stays disabled until the step is actually satisfied.
        nextButton.isEnabled = stepSatisfied()

        // "Step X of Y" — hidden on Intro (an atmospheric welcome screen, not a
        // numbered task), visible everywhere else. DISPLAY_POSITION skips
        // position 3, reserved for the combined name/phone screen that lives
        // in SignInActivity, not this Activity's own step machine.
        if (step == STEP_INTRO) {
            progressSection.visibility = View.GONE
        } else {
            progressSection.visibility = View.VISIBLE
            val position = DISPLAY_POSITION[step]
            progressText.text = getString(R.string.ob_progress_format, position, OnboardingProgress.TOTAL)
            progressBar.progress = (position * 100) / OnboardingProgress.TOTAL
        }

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
                val name = getString(R.string.app_name)
                stepText1.text = getString(R.string.ob_a11y_step1, name)
                stepText2.setText(R.string.ob_a11y_step2)
                stepText3.setText(R.string.ob_a11y_step3)
                showEnabled(Permissions.isAccessibilityEnabled(this))
            }
            STEP_OEM_BATTERY -> {
                // Combined screen: brand-specific guidance (if this manufacturer
                // is a known battery killer) shown together with the battery-
                // exemption request, since both address the same underlying
                // problem (background survival). Unlisted brands see only the
                // generic battery explanation — same content as before, just no
                // longer a separate screen.
                val brand = OemGuidance.detect()
                if (brand != null) {
                    titleText.setText(brand.titleRes)
                    bodyText.text = getString(brand.bodyRes) + "\n\n" + getString(R.string.ob_battery_body)
                } else {
                    titleText.setText(R.string.ob_battery_title)
                    bodyText.setText(R.string.ob_battery_body)
                }
                actionButton.setText(R.string.ob_battery_action)
                showGranted(Permissions.hasBatteryExemption(this))
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
        private const val STEP_OEM_BATTERY = 5
        private const val STEP_LAST = STEP_OEM_BATTERY

        /** Maps this Activity's own step index to the shared "Step X of Y"
         *  display position — skips display position 3, reserved for the
         *  combined name/phone screen hosted in SignInActivity. */
        private val DISPLAY_POSITION = intArrayOf(
            OnboardingProgress.INTRO,          // STEP_INTRO
            OnboardingProgress.SIGNIN,         // STEP_SIGNIN
            OnboardingProgress.USAGE,          // STEP_USAGE
            OnboardingProgress.OVERLAY,        // STEP_OVERLAY
            OnboardingProgress.ACCESSIBILITY,  // STEP_ACCESSIBILITY
            OnboardingProgress.OEM_BATTERY,    // STEP_OEM_BATTERY
        )
    }
}
