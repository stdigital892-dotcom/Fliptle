package com.fliptle.app

import android.content.Context
import android.widget.Toast

/**
 * One place for the rule that governs every committed setting (blocked apps,
 * blocked domains, Reels allowance):
 *
 *   • While the 3-day lock is running, changes are REFUSED.
 *   • At a day-3+ review (or before the first cycle), changes are allowed — and
 *     making one immediately starts a fresh 3-day cycle.
 *
 * Screens that edit a committed setting call [canChange] before writing and
 * [onChanged] after, so the behaviour cannot drift between them.
 */
object Commitment {

    /** True if committed settings may be edited right now. */
    fun canChange(context: Context): Boolean = !FreezeStore(context).settingsLocked()

    /**
     * Guard for an edit action. Shows the reason and returns false when the
     * change must be refused.
     */
    fun guard(context: Context): Boolean {
        val store = FreezeStore(context)
        if (!store.settingsLocked()) return true
        val msg = if (store.state() == FreezeStore.State.VERIFYING) {
            context.getString(R.string.commit_locked_verifying)
        } else {
            context.getString(R.string.commit_locked, store.dayNumber(), store.cycleDays())
        }
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        return false
    }

    /**
     * Record that a committed setting actually changed. At review this starts the
     * next 3-day cycle; before the first cycle it does nothing (the user starts
     * their commitment explicitly from the freeze screen).
     */
    fun onChanged(context: Context) {
        val store = FreezeStore(context)
        if (!store.active) return
        if (store.restartCycle()) {
            Toast.makeText(
                context,
                context.getString(R.string.commit_restarted, store.cycleDays()),
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
