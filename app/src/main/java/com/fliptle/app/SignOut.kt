package com.fliptle.app

import android.app.Activity
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import com.fliptle.app.auth.AuthStore
import com.fliptle.app.auth.FirebaseGate
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth

/**
 * Sign-out flow. Auth is ONLY account tracking — it never gates or pauses any
 * blocking. Signing out clears the Firebase/Google session and restarts the app
 * to the mandatory sign-in screen, but touches nothing else: porn blocking, the
 * freeze, blocked apps/domains and every enforcement service keep running
 * uninterrupted (they are device-level and auth-independent). Signing back in
 * with the same account re-syncs progress from the cloud (see [CloudState]).
 */
object SignOut {

    /** Show the required warning, then sign out on confirmation. */
    fun confirm(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.sign_out_title)
            .setMessage(R.string.sign_out_warning)
            .setNegativeButton(R.string.sign_out_cancel, null)
            .setPositiveButton(R.string.sign_out_confirm) { _, _ -> perform(activity) }
            .show()
    }

    private fun perform(activity: Activity) {
        // Clear auth only. Deliberately does NOT stop BlockingService, the
        // accessibility service, the browser receiver, or the heartbeat, and does
        // NOT clear any feature prefs — blocking is unaffected by auth state.
        if (FirebaseGate.isAvailable(activity)) {
            try {
                FirebaseAuth.getInstance().signOut()
            } catch (_: Throwable) {
            }
        }
        try {
            GoogleSignIn.getClient(activity, GoogleSignInOptions.DEFAULT_SIGN_IN).signOut()
        } catch (_: Exception) {
        }
        // The parent-phone step is per sign-in session; require it again next time.
        AuthStore(activity).parentPhoneProvided = false
        // Restart from the launcher -> the router sends the user to mandatory sign-in.
        activity.startActivity(
            Intent(activity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        activity.finish()
    }
}
