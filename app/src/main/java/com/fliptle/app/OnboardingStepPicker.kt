package com.fliptle.app

/**
 * Which onboarding step to open on, given the LIVE state of every requirement —
 * never a saved step number. Pure (no Context), so it is unit tested directly;
 * [OnboardingActivity] supplies the live Permissions/AuthStore reads.
 *
 * Order: sign-in, the (optional) name step, Usage access, Overlay,
 * Accessibility, battery exemption. The first one not satisfied wins; if
 * everything is satisfied, [OnboardingStep.DONE].
 */
enum class OnboardingStep { SIGN_IN, USAGE, OVERLAY, ACCESSIBILITY, BATTERY, DONE }

object OnboardingStepPicker {
    fun firstUnsatisfiedStep(
        signedIn: Boolean,
        nameHandled: Boolean,
        usage: Boolean,
        overlay: Boolean,
        accessibility: Boolean,
        battery: Boolean
    ): OnboardingStep = when {
        !signedIn -> OnboardingStep.SIGN_IN
        !nameHandled -> OnboardingStep.SIGN_IN
        !usage -> OnboardingStep.USAGE
        !overlay -> OnboardingStep.OVERLAY
        !accessibility -> OnboardingStep.ACCESSIBILITY
        !battery -> OnboardingStep.BATTERY
        else -> OnboardingStep.DONE
    }
}
