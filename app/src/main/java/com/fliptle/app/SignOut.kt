package com.fliptle.app

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import com.fliptle.app.auth.FirebaseGate
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sign-out flow. Auth is ONLY account tracking — it never gates or pauses any
 * blocking. Signing out clears the Firebase/Google session and restarts the app
 * to the mandatory sign-in screen, but touches nothing else: porn blocking, the
 * freeze, blocked apps/domains and every enforcement service keep running
 * uninterrupted (they are device-level and auth-independent). Signing back in
 * with the same account re-syncs progress from the cloud (see [CloudState]).
 */
object SignOut {

    /**
     * Show the required warning, then sign out on confirmation. While sign-out is
     * locked ([SignOutGuard]) it refuses instead, with the locked message.
     * [enforceLock] = false is for the deletion-check retry screen only, which must
     * keep working exactly as before.
     */
    fun confirm(activity: Activity, enforceLock: Boolean = true) {
        if (enforceLock && SignOutGuard.isLocked(activity)) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.sign_out_locked_title)
                .setMessage(R.string.sign_out_locked)
                .setPositiveButton(R.string.sign_out_locked_ok, null)
                .show()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.sign_out_title)
            .setMessage(R.string.sign_out_warning)
            .setNegativeButton(R.string.sign_out_cancel, null)
            .setPositiveButton(R.string.sign_out_confirm) { _, _ -> perform(activity, markSignedOut = true) }
            .show()
    }

    /**
     * The sign-out itself, without the confirmation dialog.
     *
     * [markSignedOut] is true ONLY for a manual sign-out (the confirm() dialog). It
     * first records installs/{uid}.signedOutAt (server time) and protectionActive =
     * false, best effort, so the partner-alert sender can tell a clean sign-out from
     * a removal. Account deletion calls this with the default (false), and a
     * server-forced sign-out never comes through here, so neither writes it.
     */
    fun perform(activity: Activity, markSignedOut: Boolean = false) {
        if (markSignedOut) markSignedOutThen(activity) { finishSignOut(activity) }
        else finishSignOut(activity)
    }

    /**
     * Write the marker, then run [next] once. A queued Firestore write can be sent
     * with the credentials that exist when it goes out, so signing out first could
     * get it refused. Wait for the write to finish (success OR failure), but never
     * longer than [WRITE_WAIT_MS], so a slow or offline phone is never stuck.
     */
    private fun markSignedOutThen(activity: Activity, next: () -> Unit) {
        val uid = if (FirebaseGate.isAvailable(activity)) FirebaseAuth.getInstance().currentUser?.uid else null
        if (uid == null) { next(); return }
        val done = AtomicBoolean(false)
        val once = { if (done.compareAndSet(false, true)) next() }
        try {
            FirebaseFirestore.getInstance().collection("installs").document(uid)
                .set(
                    mapOf("signedOutAt" to FieldValue.serverTimestamp(), "protectionActive" to false),
                    SetOptions.merge()
                )
                .addOnCompleteListener { once() }
        } catch (_: Throwable) {
            once()
            return
        }
        Handler(Looper.getMainLooper()).postDelayed({ once() }, WRITE_WAIT_MS)
    }

    private const val WRITE_WAIT_MS = 3_000L

    private fun finishSignOut(activity: Activity) {
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
        // Entitlement is per-account, and the cache is what MainActivity's
        // routing gate reads at launch — a stale ENTITLED cache from the
        // previous user would let a fresh signed-in user briefly reach Home
        // before the recheck lands. Clear it so the next sign-in starts from
        // UNKNOWN and re-verifies through InboxConfirmActivity.
        com.fliptle.app.auth.EntitlementStore(activity).clear()
        // Restart from the launcher -> the router sends the user to mandatory sign-in.
        activity.startActivity(
            Intent(activity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        activity.finish()
    }
}
