package com.fliptle.app.auth

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.fliptle.app.MainActivity
import com.fliptle.app.R
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions

/**
 * Permanent, self-service account deletion from the Account screen.
 *
 * Only reachable when the user is already signed in (the button lives in
 * [com.fliptle.app.auth.SignInActivity]'s signed-in account view), and always
 * targets whichever account is currently signed in — there is no way to pass
 * a different account's identifier through this flow. The server-side
 * `deleteAccount` Cloud Function independently enforces the same rule (it
 * reads uid/email only from the caller's auth token, never from any request
 * payload), so this is defense in depth, not the only guard against deleting
 * someone else's account.
 */
object DeleteAccount {

    private const val REGION = "asia-south2"

    /** Show the required permanent-deletion warning, then proceed on confirmation. */
    fun confirm(activity: Activity) {
        if (!FirebaseGate.isAvailable(activity) || FirebaseAuth.getInstance().currentUser == null) {
            return // Nothing to delete — the button shouldn't be reachable in this state anyway.
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.delete_account_title)
            .setMessage(R.string.delete_account_warning)
            .setNegativeButton(R.string.delete_account_cancel, null)
            .setPositiveButton(R.string.delete_account_confirm) { _, _ -> perform(activity) }
            .show()
    }

    private fun perform(activity: Activity) {
        if (FirebaseAuth.getInstance().currentUser == null) {
            Toast.makeText(activity, R.string.delete_account_failed, Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(activity, R.string.delete_account_working, Toast.LENGTH_SHORT).show()

        // No arguments — the server identifies which account to delete solely
        // from this call's auth token, never from anything passed here.
        FirebaseFunctions.getInstance(REGION)
            .getHttpsCallable("deleteAccount")
            .call()
            .addOnSuccessListener { finishLocally(activity) }
            .addOnFailureListener { e ->
                Toast.makeText(
                    activity,
                    activity.getString(R.string.delete_account_failed_detail, e.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    /**
     * Clear every local trace of this account, then restart through the
     * launcher router. Firebase Auth is now signed out and the account no
     * longer exists server-side, so the router lands on the sign-in screen —
     * the closest this app has to a "welcome screen" for a device that has
     * already completed onboarding.
     */
    private fun finishLocally(activity: Activity) {
        AuthStore(activity).resetForAccountDeletion()
        EntitlementStore(activity).clear()
        try {
            FirebaseAuth.getInstance().signOut()
        } catch (_: Throwable) {
        }
        try {
            GoogleSignIn.getClient(activity, GoogleSignInOptions.DEFAULT_SIGN_IN).signOut()
        } catch (_: Exception) {
        }
        activity.startActivity(
            Intent(activity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        activity.finish()
    }
}
