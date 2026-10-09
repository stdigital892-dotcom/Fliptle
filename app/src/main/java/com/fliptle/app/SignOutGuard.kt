package com.fliptle.app

import android.content.Context
import com.fliptle.app.auth.EntitlementStore

/**
 * Decides whether MANUAL sign-out is locked.
 *
 * Locked (true) only when BOTH hold:
 *   a) protection is enforcing: adult-content blocking is on OR a freeze is
 *      active, AND the entitlement is not an explicit DENIED. An UNKNOWN
 *      entitlement still counts as enforcing, because enforcement only ever
 *      suspends on an explicit DENIED (UrlBlockAccessibilityService, BlockingService).
 *   b) the exit (uninstall) process has not been completed. The flag is
 *      [UninstallGateStore.approved], set in UninstallGateStore.completeDay() on the
 *      same day-5 completion where UninstallRequestActivity calls
 *      UninstallLog.logApproved(), which writes installs/{uid}.uninstall_approved.
 *
 * Unlocked (false) with no protection on, with a DENIED entitlement (a lapsed
 * plan never traps the user), or once the exit process is completed.
 *
 * Only manual sign-out entry points ask this. Account deletion's own sign-out
 * ([SignOut.perform]) and a server-forced sign-out never go through it.
 */
object SignOutGuard {

    fun isLocked(context: Context): Boolean = isLocked(
        adultContentBlocking = PornBlockStore(context).enabled,
        freezeActive = FreezeStore(context).active,
        entitlement = EntitlementStore(context).state,
        exitCompleted = UninstallGateStore(context).approved
    )

    /** The rule itself, free of Android so it can be unit-tested. */
    fun isLocked(
        adultContentBlocking: Boolean,
        freezeActive: Boolean,
        entitlement: EntitlementStore.State,
        exitCompleted: Boolean
    ): Boolean {
        val enforcing = (adultContentBlocking || freezeActive) &&
            entitlement != EntitlementStore.State.DENIED
        return enforcing && !exitCompleted
    }
}
