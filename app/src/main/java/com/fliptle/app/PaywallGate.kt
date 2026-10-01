package com.fliptle.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.fliptle.app.auth.AuthStore
import com.fliptle.app.auth.EntitlementStore

/**
 * The paywall, as one shared check. A user has cleared it only when they have
 * seen the inbox-confirm screen AND the server has said entitled=true (paid,
 * trial or tester). Anything else, including a state we have never verified,
 * is not cleared. A failed network check never writes to [EntitlementStore], so
 * it can't flip a verified user out of ENTITLED (see EntitlementGate).
 *
 * Every screen that must not be reachable without a plan (the permission steps,
 * Setup, Freeze, Home, the protection guard) calls [gate]. A user who has not
 * cleared it is sent through [MainActivity], which holds them on
 * InboxConfirmActivity until they are entitled.
 */
object PaywallGate {

    fun open(context: Context): Boolean =
        AuthStore(context).inboxConfirmShown &&
            EntitlementStore(context).state == EntitlementStore.State.ENTITLED

    /** True when the caller may proceed; otherwise routes via MainActivity and finishes it. */
    fun gate(activity: Activity): Boolean {
        if (open(activity)) return true
        activity.startActivity(
            Intent(activity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        activity.finish()
        return false
    }
}
