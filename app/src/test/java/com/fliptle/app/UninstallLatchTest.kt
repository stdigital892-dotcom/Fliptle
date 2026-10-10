package com.fliptle.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** E1: the cloud latch for the exit process's day count and approved flag. */
class UninstallLatchTest {

    @Test fun lowerCloudDays_doNotReduceLocalDays() {
        assertEquals(4, UninstallLatch.latchedDays(localDays = 4, cloudDays = 1))
    }

    @Test fun higherCloudDays_raiseLocalDays() {
        assertEquals(4, UninstallLatch.latchedDays(localDays = 1, cloudDays = 4))
    }

    @Test fun missingCloudFields_changeNothing() {
        assertEquals(3, UninstallLatch.latchedDays(localDays = 3, cloudDays = 0))
        assertFalse(UninstallLatch.latchedApproved(localApproved = false, cloudApproved = false))
    }

    @Test fun approvedNeverReverts() {
        assertTrue(UninstallLatch.latchedApproved(localApproved = true, cloudApproved = false))
    }

    @Test fun cloudApprovedOpensTheLock_evenIfLocalWasNot() {
        assertTrue(UninstallLatch.latchedApproved(localApproved = false, cloudApproved = true))
    }

    @Test fun bothApproved_staysApproved() {
        assertTrue(UninstallLatch.latchedApproved(localApproved = true, cloudApproved = true))
    }
}
