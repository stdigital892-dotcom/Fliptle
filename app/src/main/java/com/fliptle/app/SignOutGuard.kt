package com.fliptle.app

import android.content.Context
import com.fliptle.app.auth.PendingDeletion
import com.google.firebase.auth.FirebaseAuth

/**
 * Decides whether MANUAL sign-out (or switching accounts, which goes through
 * the same sign-out flow) is locked.
 *
 * Locked (true) unless EITHER:
 *   a) the exit (uninstall) process has been completed
 *      ([UninstallGateStore.approved]), or
 *   b) an account deletion is already scheduled for this account -- that's
 *      its own committed exit path, so sign-out no longer needs to be the
 *      thing holding the user here.
 *
 * Blocking state and entitlement no longer matter: a locked sign-out always
 * offers the same two forward paths regardless of why the account is
 * "staying", so there is no case where the user is simply stuck with no way
 * forward.
 */
object SignOutGuard {

    fun isLocked(context: Context): Boolean {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        val deletionScheduled = uid != null && PendingDeletion.cachedFor(context, uid) != null
        return isLocked(
            exitCompleted = UninstallGateStore(context).approved,
            accountDeletionScheduled = deletionScheduled
        )
    }

    /** The rule itself, free of Android so it can be unit-tested. */
    fun isLocked(exitCompleted: Boolean, accountDeletionScheduled: Boolean): Boolean =
        !exitCompleted && !accountDeletionScheduled
}
