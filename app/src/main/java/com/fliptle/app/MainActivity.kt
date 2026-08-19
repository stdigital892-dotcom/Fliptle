package com.fliptle.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** Launcher/router: sends first-time users to onboarding, everyone else home. */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Enforcement runs independently of auth: (re)start it here in the launcher
        // so blocking is active no matter where routing sends the user — including
        // the mandatory sign-in screen after a sign-out. Auth never gates blocking.
        if (Permissions.allEnforcementGranted(this)) {
            BrowserDetector.autoBlockInstalledBrowsers(this)
            BlockingService.start(this)
        }

        // Not onboarded yet -> onboarding. Onboarded but protection off -> the
        // full-screen guard. Otherwise -> Home.
        val destination = when {
            !OnboardingState(this).complete -> OnboardingActivity::class.java
            !Permissions.allEnforcementGranted(this) -> ProtectionGuardActivity::class.java
            // Sign-in is mandatory (Google or email/password); no skip.
            AuthGate.required(this) -> com.fliptle.app.auth.SignInActivity::class.java
            else -> HomeActivity::class.java
        }
        startActivity(Intent(this, destination))
        finish()
    }
}
