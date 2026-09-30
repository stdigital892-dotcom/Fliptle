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

        // Steps that use a simple class reference (no extra data).
        val destination = when {
            !OnboardingState(this).complete -> OnboardingActivity::class.java
            // Skip the permissions nag for a DENIED user — re-enabling
            // accessibility/overlay accomplishes nothing while the paywall
            // gate has enforcement paused, so send them to the renewal
            // screen (below) instead of asking them to fix a permission for
            // a service that's deliberately not acting on anything right
            // now. DENIED can only be set for a currently-signed-in user
            // (EntitlementStore is cleared on sign-out), so this can't skip
            // a genuine sign-in requirement.
            !entitlementDenies && !Permissions.allEnforcementGranted(this) ->
                ProtectionGuardActivity::class.java
            AuthGate.required(this) -> com.fliptle.app.auth.SignInActivity::class.java
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
            routeToInboxConfirm()
            return
        }

        // ---- Entitlement gate ----
        // Only ENTITLED lets the user into Home. DENIED sends them back to
        // InboxConfirmActivity, which now doubles as the renewal screen (its
        // Continue button stays hidden until entitlement flips green from
        // getEntitlement's poll). UNKNOWN means we've never had a successful
        // server response on this device: safer to also route through the
        // confirm screen, which will poll and update the store, than to let
        // an unverified user reach Home and (potentially) enable protection
        // features. This is only reached AFTER inboxConfirmShown, so the
        // familiar "check your email → confirm" flow is the fallback.
        if (entitlement.state != EntitlementStore.State.ENTITLED) {
            routeToInboxConfirm()
            return
        }

        // Shown exactly once, right after onboarding AND the entitlement gate
        // both clear — the moment the user is about to reach Home for the
        // first time — rather than blocking the path to first use earlier in
        // onboarding. Still unskippable when it appears.
        if (!store.uninstallInfoSeen) {
            startActivity(Intent(this, UninstallInfoActivity::class.java))
            finish()
            return
        }

        startActivity(Intent(this, HomeActivity::class.java))
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
