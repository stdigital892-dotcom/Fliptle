package com.fliptle.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.fliptle.app.auth.AuthStore
import com.fliptle.app.auth.FirebaseGate
import com.fliptle.app.auth.SignInActivity
import com.google.firebase.auth.FirebaseAuth

/**
 * Optional (for now) parent phone number after sign-in.
 *
 * The phone step still appears after every sign-in, but it is currently SKIPPABLE:
 * the user can proceed to the main app without entering a number. Enforcement is
 * off ([ENFORCED] = false) until the partner system is built. Flip [ENFORCED] to
 * true to make the number mandatory again — the gate then bounces a signed-in user
 * with no phone to [SignInActivity] (which stays exitable, so the device is never
 * trapped) until a plausible number is entered.
 */
object PhoneGate {

    /** Master switch. false = phone is optional/skippable; true = mandatory. */
    private const val ENFORCED = false

    /** True when a user is signed in but has not yet provided a parent phone. */
    fun required(context: Context): Boolean {
        if (!ENFORCED) return false
        if (!FirebaseGate.isAvailable(context)) return false
        FirebaseAuth.getInstance().currentUser ?: return false
        return !AuthStore(context).parentPhoneProvided
    }

    /**
     * If a phone number is required, open [SignInActivity] to collect it and finish
     * [activity]. Returns true when the caller may proceed (no phone required).
     */
    fun gate(activity: Activity): Boolean {
        if (!required(activity)) return true
        activity.startActivity(Intent(activity, SignInActivity::class.java))
        activity.finish()
        return false
    }
}
