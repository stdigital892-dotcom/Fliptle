package com.fliptle.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Manual sign-out (or an account switch, which goes through the same flow)
 * is locked unless the exit process is done OR a deletion is already
 * scheduled. Blocking state and entitlement no longer matter.
 */
class SignOutGuardTest {

    @Test fun neitherDone_isLocked() {
        assertTrue(SignOutGuard.isLocked(exitCompleted = false, accountDeletionScheduled = false))
    }

    @Test fun exitCompleted_isNotLocked() {
        assertFalse(SignOutGuard.isLocked(exitCompleted = true, accountDeletionScheduled = false))
    }

    @Test fun deletionScheduled_isNotLocked() {
        assertFalse(SignOutGuard.isLocked(exitCompleted = false, accountDeletionScheduled = true))
    }

    @Test fun bothDone_isNotLocked() {
        assertFalse(SignOutGuard.isLocked(exitCompleted = true, accountDeletionScheduled = true))
    }
}
