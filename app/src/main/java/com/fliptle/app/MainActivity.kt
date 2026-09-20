package com.fliptle.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth

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

        val store = com.fliptle.app.auth.AuthStore(this)

        // Steps that use a simple class reference (no extra data).
        val destination = when {
            !OnboardingState(this).complete -> OnboardingActivity::class.java
            !Permissions.allEnforcementGranted(this) -> ProtectionGuardActivity::class.java
            AuthGate.required(this) -> com.fliptle.app.auth.SignInActivity::class.java
            !store.uninstallInfoSeen -> UninstallInfoActivity::class.java
            else -> null
        }
        if (destination != null) {
            startActivity(Intent(this, destination))
            finish()
            return
        }

        // Inbox-confirm screen: shown once per account and needs the email extra.
        // Catches already-signed-in users who haven't seen it yet (e.g. testers
        // who installed before this feature shipped, or reinstalls where the local
        // flag was cleared but the Firestore flag hasn't been restored yet).
        if (!store.inboxConfirmShown) {
            val email = FirebaseAuth.getInstance().currentUser?.email ?: ""
            startActivity(
                Intent(this, com.fliptle.app.auth.InboxConfirmActivity::class.java)
                    .putExtra(com.fliptle.app.auth.InboxConfirmActivity.EXTRA_EMAIL, email)
            )
            finish()
            return
        }

        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }
}
