package com.fliptle.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.fliptle.app.auth.EntitlementGate
import com.fliptle.app.auth.EntitlementStore
import com.google.firebase.auth.FirebaseAuth

/** Launcher/router: sends first-time users to onboarding, everyone else home. */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val store = com.fliptle.app.auth.AuthStore(this)
        val entitlement = EntitlementStore(this)

        // Enforcement is gated on TWO things:
        //   1. All permissions granted (so BlockingService can do its job).
        //   2. Entitlement not explicitly DENIED (paid, trial, tester, or
        //      simply unknown — we don't have a server "no" on record).
        // If either fails we don't start the service. Enforcement never runs
        // for a user we know isn't paying. UNKNOWN → we haven't asked yet,
        // so we leave the service alone (either already running from a prior
        // entitled session, or not started yet — the entitlement recheck
        // below will resolve it).
        val entitlementDenies = entitlement.state == EntitlementStore.State.DENIED
        if (Permissions.allEnforcementGranted(this) && !entitlementDenies) {
            BrowserDetector.autoBlockInstalledBrowsers(this)
            BlockingService.start(this)
        } else if (entitlementDenies) {
            // Explicit denial: kill enforcement. A network failure never
            // reaches this branch — EntitlementStore.DENIED only happens on
            // an explicit server response.
            BlockingService.stop(this)
        }

        // Kick off a background entitlement re-check on every launch. Its
        // result lands in the store; a network failure leaves the store
        // untouched (safeguard). We don't await it — this launch uses the
        // cached state; the next launch (or InboxConfirmActivity's poll) will
        // pick up any change.
        EntitlementGate.check(this) { /* result already persisted to store */ }

        // ---- Routing chain: the first gate that fails decides the screen ----
        //   1. Not signed in, onboarding not done -> Intro, then Sign-in
        //   2. Not signed in, onboarding done      -> Sign-in
        //   3. Signed in but the paywall is not cleared (inbox-confirm not seen,
        //      or not ENTITLED; also the renewal screen for an expired plan)
        //                                           -> InboxConfirmActivity
        //   4. Onboarding not done                 -> permission steps (Usage first)
        //   5. A permission was revoked later      -> ProtectionGuardActivity
        //   6. Uninstall-info not seen yet         -> UninstallInfoActivity
        //   7. Everything clear                    -> Home
        // The paywall (3) sits BEFORE every permission step (4, 5), so a user
        // without a plan is never asked for a permission. A failed network check
        // never writes to the entitlement store, so it can't flip a verified
        // user out of ENTITLED; only an explicit server answer does.
        val onboarded = OnboardingState(this).complete
        val destination = when {
            !AuthGate.signedIn(this) && !onboarded -> OnboardingActivity::class.java
            AuthGate.required(this) -> com.fliptle.app.auth.SignInActivity::class.java
            !PaywallGate.open(this) -> {
                routeToInboxConfirm()
                return
            }
            !onboarded -> OnboardingActivity::class.java
            !Permissions.allEnforcementGranted(this) -> ProtectionGuardActivity::class.java
            !store.uninstallInfoSeen -> UninstallInfoActivity::class.java
            else -> HomeActivity::class.java
        }
        startActivity(Intent(this, destination))
        finish()
    }

    private fun routeToInboxConfirm() {
        val email = FirebaseAuth.getInstance().currentUser?.email ?: ""
        startActivity(
            Intent(this, com.fliptle.app.auth.InboxConfirmActivity::class.java)
                .putExtra(com.fliptle.app.auth.InboxConfirmActivity.EXTRA_EMAIL, email)
        )
        finish()
    }
}
