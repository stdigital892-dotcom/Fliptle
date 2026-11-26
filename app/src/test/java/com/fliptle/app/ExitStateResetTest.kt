package com.fliptle.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reset-vs-restore rule: only a NEW_COMMITMENT while already approved
 *  resets the exit gate; a restore or sign-in never does, even if approved. */
class ExitStateResetTest {

    @Test fun newCommitmentWhileApproved_resets() {
        assertTrue(ExitStateReset.shouldReset(ExitStateReset.Trigger.NEW_COMMITMENT, approved = true))
    }

    @Test fun newCommitmentWhileNotApproved_doesNothing() {
        assertFalse(ExitStateReset.shouldReset(ExitStateReset.Trigger.NEW_COMMITMENT, approved = false))
    }

    @Test fun restoreOrSignIn_neverResets_evenIfApproved() {
        assertFalse(ExitStateReset.shouldReset(ExitStateReset.Trigger.RESTORE_OR_SIGN_IN, approved = true))
        assertFalse(ExitStateReset.shouldReset(ExitStateReset.Trigger.RESTORE_OR_SIGN_IN, approved = false))
    }
}
