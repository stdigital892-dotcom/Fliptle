package com.fliptle.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** C2: onboarding resume must pick the first unsatisfied step from LIVE state. */
class OnboardingStepPickerTest {

    private fun pick(usage: Boolean, overlay: Boolean, accessibility: Boolean, battery: Boolean) =
        OnboardingStepPicker.firstUnsatisfiedStep(
            signedIn = true, nameHandled = true,
            usage = usage, overlay = overlay, accessibility = accessibility, battery = battery
        )

    @Test fun allGranted_isDone() {
        assertEquals(OnboardingStep.DONE, pick(usage = true, overlay = true, accessibility = true, battery = true))
    }

    @Test fun firstTwoGranted_opensOnTheThird() {
        assertEquals(
            OnboardingStep.ACCESSIBILITY,
            pick(usage = true, overlay = true, accessibility = false, battery = true)
        )
    }

    @Test fun noneGranted_opensOnTheFirst() {
        assertEquals(OnboardingStep.USAGE, pick(usage = false, overlay = false, accessibility = false, battery = false))
    }

    @Test fun aMiddleOneRevoked_goesBackToIt_evenWithLaterOnesGranted() {
        // Usage granted, Overlay revoked, Accessibility and battery still granted.
        assertEquals(
            OnboardingStep.OVERLAY,
            pick(usage = true, overlay = false, accessibility = true, battery = true)
        )
    }

    @Test fun notSignedIn_opensOnSignIn_regardlessOfPermissions() {
        assertEquals(
            OnboardingStep.SIGN_IN,
            OnboardingStepPicker.firstUnsatisfiedStep(
                signedIn = false, nameHandled = true,
                usage = true, overlay = true, accessibility = true, battery = true
            )
        )
    }

    @Test fun signedInButNameStepNotHandled_opensOnSignIn() {
        assertEquals(
            OnboardingStep.SIGN_IN,
            OnboardingStepPicker.firstUnsatisfiedStep(
                signedIn = true, nameHandled = false,
                usage = true, overlay = true, accessibility = true, battery = true
            )
        )
    }
}
