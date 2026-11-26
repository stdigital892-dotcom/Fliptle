package com.fliptle.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Day 5's approved flag may only be set after the server write succeeds. */
class FinalDayGateTest {

    @Test fun writeSucceeded_mayApprove() {
        assertTrue(FinalDayGate.mayApprove(writeSucceeded = true))
    }

    @Test fun writeFailed_mayNotApprove() {
        assertFalse(FinalDayGate.mayApprove(writeSucceeded = false))
    }
}
