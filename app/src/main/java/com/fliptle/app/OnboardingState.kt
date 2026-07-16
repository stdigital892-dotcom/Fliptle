package com.fliptle.app

import android.content.Context

/** Tracks whether first-launch onboarding has been completed. */
class OnboardingState(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("onboarding", Context.MODE_PRIVATE)

    var complete: Boolean
        get() = prefs.getBoolean("complete", false)
        set(value) = prefs.edit().putBoolean("complete", value).apply()
}
