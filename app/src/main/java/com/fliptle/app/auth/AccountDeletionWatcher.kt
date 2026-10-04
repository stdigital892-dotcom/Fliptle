package com.fliptle.app.auth

import android.app.Application
import android.content.Context
import com.google.firebase.auth.FirebaseAuth

/**
 * Keeps the phone in step with a scheduled account deletion.
 *
 *  • While an account is signed in, it refreshes the locally cached scheduled
 *    time from the server (one small read per sign-in / app start).
 *  • When the phone is signed out and the cached deletion for that account looks
 *    due, it does what a sign-out does (clears the entitlement cache and the
 *    per-account local data) so any other account can sign in normally. The cache
 *    alone is never trusted for this: the server is re-read first, and the cleanup
 *    is skipped if the deletion is no longer scheduled or no longer due (for
 *    example because it was cancelled by signing in on another phone).
 *
 * That re-read needs a signed-in owner (Firestore rules: only auth.uid == uid may
 * read installs/{uid}). A signed-out phone is therefore usually refused. A refused
 * or failed read changes NOTHING: the cached copy stays, and the cleanup happens
 * at the next sign-in as a DIFFERENT account, see [cleanupForOtherAccount]. The
 * same account signing in again is verified against the server and is never
 * cleaned.
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
            // Signed out. Only look further if a deletion cached for the account we
            // just lost looks due (the cache carries its uid, so this survives restarts).
            val uid = PendingDeletion.cachedUid(app) ?: return@addAuthStateListener
            if (!PendingDeletion.isDueFor(app, uid)) return@addAuthStateListener

            // Re-read from the server before acting on the cache.
            PendingDeletion.readServer(app, uid) { ok, exists, ms ->
                // Someone may have signed in while the read was in flight.
                if (FirebaseAuth.getInstance().currentUser != null) return@readServer
                when (DeletionFlowRules.cleanupAfterReread(ok, exists, ms, System.currentTimeMillis())) {
                    DeletionFlowRules.Cleanup.CLEAN -> cleanUp(app, clearEntitlement = true)
                    DeletionFlowRules.Cleanup.SKIP ->
                        // Cancelled or pushed out: correct the cached copy, wipe nothing.
                        if (ms == null) PendingDeletion.clearCache(app) else PendingDeletion.cache(app, uid, ms)
                    DeletionFlowRules.Cleanup.DEFER -> Unit // unverified: leave everything as it is
                }
            }
        }
    }

    /**
     * Called by a fresh sign-in, before the new account uses any local data: if the
     * deletion cached for a DIFFERENT account has come due, clear that account's
     * leftovers. (Entitlement is already cleared by the sign-in itself.)
     */
    fun cleanupForOtherAccount(context: Context, currentUid: String) {
        if (DeletionFlowRules.staleOtherAccountDue(
                PendingDeletion.cachedUid(context),
                PendingDeletion.cachedScheduledMs(context),
                currentUid,
                System.currentTimeMillis()
            )
        ) {
            cleanUp(context, clearEntitlement = false)
        }
    }

    private fun cleanUp(context: Context, clearEntitlement: Boolean) {
        if (clearEntitlement) EntitlementStore(context).clear()
        AuthStore(context).resetForAccountDeletion()
        PendingDeletion.clearCache(context)
    }
}
