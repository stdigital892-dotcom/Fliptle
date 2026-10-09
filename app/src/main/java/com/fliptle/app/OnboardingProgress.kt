package com.fliptle.app

/**
 * Canonical "Step X of Y" positions for the onboarding progress indicator.
 * Spans two Activities — OnboardingActivity's own numbered steps, and the
 * name screen hosted in SignInActivity right after sign-in —
 * so both reference this single source of truth rather than each hardcoding
 * its own numbers that could drift apart.
 *
 * Intro is position 1 but never actually shows the indicator (see
 * OnboardingActivity.render()) — it's an atmospheric welcome screen, not a
 * numbered task — so in practice the indicator is only ever seen starting
 * from "Step 2 of 7".
 */
object OnboardingProgress {
    const val TOTAL = 7

    const val INTRO = 1
    const val SIGNIN = 2
    const val PROFILE = 3 // name — shown in SignInActivity
    const val USAGE = 4
    const val OVERLAY = 5
    const val ACCESSIBILITY = 6
    const val OEM_BATTERY = 7
}
