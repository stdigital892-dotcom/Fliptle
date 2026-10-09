package com.fliptle.app

import com.fliptle.app.auth.EntitlementStore.State
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When manual sign-out is locked: protection enforcing (adult-content blocking
 * or a freeze, with the plan not explicitly DENIED) AND the exit process not done.
 */
class SignOutGuardTest {

    private fun locked(
        adult: Boolean = false,
        freeze: Boolean = false,
        entitlement: State = State.ENTITLED,
        exitDone: Boolean = false
    ) = SignOutGuard.isLocked(adult, freeze, entitlement, exitDone)

    @Test fun adultContentBlockingOnly_isLocked() {
        assertTrue(locked(adult = true))
    }

    @Test fun freezeOnly_isLocked() {
        assertTrue(locked(freeze = true))
    }

    @Test fun both_isLocked() {
        assertTrue(locked(adult = true, freeze = true))
    }

    @Test fun neither_isNotLocked() {
        assertFalse(locked())
    }

    @Test fun deniedEntitlementWhileEnabled_isNotLocked_soALapsedUserIsNeverTrapped() {
        assertFalse(locked(adult = true, entitlement = State.DENIED))
        assertFalse(locked(freeze = true, entitlement = State.DENIED))
        assertFalse(locked(adult = true, freeze = true, entitlement = State.DENIED))
    }

    @Test fun unknownEntitlementWhileEnabled_isLocked_becauseEnforcementOnlySuspendsOnDenied() {
        assertTrue(locked(adult = true, entitlement = State.UNKNOWN))
        assertTrue(locked(freeze = true, entitlement = State.UNKNOWN))
    }

    @Test fun exitProcessCompletedWhileEnabled_isNotLocked() {
        assertFalse(locked(adult = true, exitDone = true))
        assertFalse(locked(freeze = true, exitDone = true))
        assertFalse(locked(adult = true, freeze = true, exitDone = true))
    }

    @Test fun nothingEnabled_isNotLocked_whatEverTheOtherInputsAre() {
        for (e in State.values()) for (done in listOf(false, true)) {
            assertFalse(locked(entitlement = e, exitDone = done))
        }
    }
}
