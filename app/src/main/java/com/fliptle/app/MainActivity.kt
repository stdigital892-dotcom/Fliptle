package com.fliptle.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** Launcher/router: sends first-time users to onboarding, everyone else home. */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Onboarding must be complete AND all enforcement permissions granted,
        // otherwise route back into onboarding (it resumes at the first gap).
        val ready = OnboardingState(this).complete && Permissions.allEnforcementGranted(this)
        val destination = if (ready) HomeActivity::class.java else OnboardingActivity::class.java
        startActivity(Intent(this, destination))
        finish()
    }
}
