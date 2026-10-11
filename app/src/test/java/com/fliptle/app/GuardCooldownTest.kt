package com.fliptle.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 60s post-tap suppression that keeps the guard off Settings (C1 follow-up). */
class GuardCooldownTest {

    @Test fun suppressedRightAfterATap() {
        val tapMs = 10_000L
        val suppressUntil = tapMs + GuardCooldown.SUPPRESS_MS
        assertTrue(GuardCooldown.isSuppressed(nowMs = tapMs + 1_000L, suppressUntilMs = suppressUntil))
    }

    @Test fun noLongerSuppressedOnceSixtySecondsPass() {
        val tapMs = 10_000L
        val suppressUntil = tapMs + GuardCooldown.SUPPRESS_MS
        assertFalse(GuardCooldown.isSuppressed(nowMs = suppressUntil, suppressUntilMs = suppressUntil))
        assertFalse(GuardCooldown.isSuppressed(nowMs = suppressUntil + 1, suppressUntilMs = suppressUntil))
    }

    @Test fun neverTapped_defaultDeadlineOfZero_isNotSuppressed() {
        assertFalse(GuardCooldown.isSuppressed(nowMs = 5_000L, suppressUntilMs = 0L))
    }

    @Test fun suppressWindowIsSixtySeconds() {
        org.junit.Assert.assertEquals(60_000L, GuardCooldown.SUPPRESS_MS)
    }
}
