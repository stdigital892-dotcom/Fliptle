package com.fliptle.app.auth

import android.app.Application
import com.google.firebase.auth.FirebaseAuth

/**
 * Keeps the phone in step with a scheduled account deletion.
 *
 *  • While an account is signed in, it refreshes the locally cached scheduled
 *    time from the server (one small read per sign-in / app start).
 *  • When the account goes away (after the server deletes it, the Firebase SDK
 *    signs the device out on its next token refresh) and a deletion for that
 *    account was due, it does what a sign-out does: clears the entitlement cache
 *    and the per-account local data, so any other account can sign in normally.
 *
 * It never touches blocking: the porn block, a running freeze, blocked apps and
 * domains, and the enforcement services all keep running exactly as they do
 * after an ordinary sign-out.
 */
object AccountDeletionWatcher {

    fun start(app: Application) {
        if (!FirebaseGate.isAvailable(app)) return
        FirebaseAuth.getInstance().addAuthStateListener { auth ->
            val user = auth.currentUser
            if (user != null) {
                PendingDeletion.fetch(app, user.uid) { _, _ -> }
                return@addAuthStateListener
            }
            // Signed out. Only act if a deletion cached for the account we just
            // lost was due (the cache carries its uid, so this survives restarts).
            val uid = PendingDeletion.cachedUid(app) ?: return@addAuthStateListener
            if (PendingDeletion.isDueFor(app, uid)) {
                EntitlementStore(app).clear()
                AuthStore(app).resetForAccountDeletion()
                PendingDeletion.clearCache(app)
            }
        }
    }
}
