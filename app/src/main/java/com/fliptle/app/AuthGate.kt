package com.fliptle.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.fliptle.app.auth.FirebaseGate
import com.fliptle.app.auth.SignInActivity
import com.google.firebase.auth.FirebaseAuth

/**
 * Sign-in is MANDATORY: the main app is not reachable until the user has signed
 * in with Google or email/password. There is no skip and no phone-number sign-in.
 *
 * One deliberate exception: if Firebase is not configured in this build
 * (no google-services.json), authentication is impossible, so requiring it would
 * brick the app entirely. In that case the gate stands down — the same
 * fail-open rule [FirebaseGate] applies everywhere else. In a properly configured
 * release build Firebase is always present, so sign-in is always required.
 *
 * The parent's phone number is a SEPARATE, optional step handled inside
 * [SignInActivity] (skippable for now — see PhoneGate).
 */
object AuthGate {

    /** True when the user must sign in before continuing. */
    fun required(context: Context): Boolean {
        if (!FirebaseGate.isAvailable(context)) return false // cannot sign in at all
        return FirebaseAuth.getInstance().currentUser == null
    }

    /**
     * If sign-in is required, open [SignInActivity] and finish [activity].
     * Returns true when the caller may proceed.
     */
    fun gate(activity: Activity): Boolean {
        if (!required(activity)) return true
        activity.startActivity(Intent(activity, SignInActivity::class.java))
        activity.finish()
        return false
    }
}
