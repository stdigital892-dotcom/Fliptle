package com.fliptle.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** Launcher/router: sends first-time users to onboarding, everyone else home. */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Not onboarded yet -> onboarding. Onboarded but protection off -> the
        // full-screen guard. Otherwise -> Home.
        val destination = when {
            !OnboardingState(this).complete -> OnboardingActivity::class.java
            !Permissions.allEnforcementGranted(this) -> ProtectionGuardActivity::class.java
            else -> HomeActivity::class.java
        }
        startActivity(Intent(this, destination))
        finish()
    }
}
