package com.fliptle.app.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule behind EntitlementGate's deletion guard: it may only apply to a
 * deletion that is already due for the signed-in account. Anything else must
 * leave normal, trial and expired users exactly as they were.
 */
class DeletionDueTest {

    private val now = 1_000_000L

    @Test fun due_whenSameAccountAndTimeHasPassed() {
        assertTrue(DeletionDue.isDue("u1", now - 1, "u1", now))
    }

    @Test fun due_whenTimeIsExactlyNow() {
        assertTrue(DeletionDue.isDue("u1", now, "u1", now))
    }

    @Test fun notDue_whenScheduledForTheFuture() {
        assertFalse(DeletionDue.isDue("u1", now + 1, "u1", now))
    }

    @Test fun notDue_whenNothingIsCached_normalTrialAndExpiredUsers() {
        assertFalse(DeletionDue.isDue(null, null, "u1", now))
        assertFalse(DeletionDue.isDue("u1", null, "u1", now))
        assertFalse(DeletionDue.isDue(null, now - 1, "u1", now))
    }

    @Test fun notDue_whenTheCachedDeletionBelongsToAnotherAccount() {
        assertFalse(DeletionDue.isDue("old-account", now - 1, "new-account", now))
    }

    @Test fun notDue_whenNobodyIsSignedIn() {
        assertFalse(DeletionDue.isDue("u1", now - 1, null, now))
    }
}
