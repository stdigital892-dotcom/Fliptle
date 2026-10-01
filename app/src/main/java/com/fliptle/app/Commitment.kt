package com.fliptle.app

import android.content.Context

/**
 * The single place a commitment starts. The wizard edits a draft
 * ([FreezeDraftStore]); [commit] is the only code that copies it into the live
 * blocking stores and starts (or restarts) the 3-day lock.
 *
 *   • While the lock is running, nothing can be changed: [commit] refuses.
 *   • Before the first cycle, committing starts the lock.
 *   • At a day-3+ review, committing restarts the lock — but only if the draft
 *     actually differs from what is running. Changing nothing leaves the current
 *     cycle going, exactly as the review has always worked.
 *   • An empty draft can never be committed.
 */
object Commitment {

    enum class Result { STARTED, RESTARTED, UNCHANGED, EMPTY, LOCKED }

    /** True if blocking choices may be edited right now. */
    fun canEdit(context: Context): Boolean = !FreezeStore(context).settingsLocked()

    /** Whether the final wizard button should be enabled for the current draft. */
    fun canCommit(context: Context): Boolean {
        val freeze = FreezeStore(context)
        if (freeze.settingsLocked()) return false
        val draft = FreezeDraftStore(context)
        draft.ensureInitialized()
        if (draft.itemCount() < 1) return false
        return !freeze.active || draft.differsFromLive()
    }

    fun commit(context: Context): Result {
        val ctx = context.applicationContext
        val freeze = FreezeStore(ctx)
        if (freeze.settingsLocked()) return Result.LOCKED

        val draft = FreezeDraftStore(ctx)
        draft.ensureInitialized()
        if (draft.itemCount() < 1) return Result.EMPTY

        val wasActive = freeze.active
        if (wasActive && !draft.differsFromLive()) return Result.UNCHANGED

        draft.applyToLive()
        BlockingService.start(ctx)
        if (wasActive) freeze.restartCycle() else freeze.startCycle()
        draft.reset()
        CloudState.backup(ctx)
        return if (wasActive) Result.RESTARTED else Result.STARTED
    }
}
