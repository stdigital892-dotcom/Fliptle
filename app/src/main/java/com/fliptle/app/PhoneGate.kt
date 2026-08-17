package com.fliptle.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.fliptle.app.auth.AuthStore
import com.fliptle.app.auth.FirebaseGate
import com.fliptle.app.auth.SignInActivity
import com.google.firebase.auth.FirebaseAuth

/**
 * Enforces the mandatory parent phone number after sign-in.
 *
 * Auth itself is optional — a user who never signs in is never asked for a phone.
 * But once a user HAS signed in (by any method: Google or email/password), a valid
 * parent phone number is required before they can reach the main app. This gate
 * bounces such a user to [SignInActivity], where the phone step blocks progress
 * until a plausible number is entered.
 *
 * The device is never trapped: SignInActivity remains exitable (back/home work),
 * the gate simply refuses to let the main app open until the number is provided.
 */
object PhoneGate {

    /** True when a user is signed in but has not yet provided a parent phone. */
    fun required(context: Context): Boolean {
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
