package com.fliptle.app.auth

import com.fliptle.app.DeletionConfig
import com.fliptle.app.auth.DeletionFlowRules.Cleanup
import com.fliptle.app.auth.DeletionFlowRules.SignInCheck
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The deletion flow's decisions, without Android: what a fresh sign-in does, when
 * the watcher may clean up, and when another account's leftovers are cleared.
 */
class DeletionFlowRulesTest {

    private val now = 1_000_000L

    // ---- signing in IS the cancellation ----

    @Test fun signIn_nothingScheduled_proceeds() {
        assertEquals(SignInCheck.PROCEED, DeletionFlowRules.signInCheck(null, now))
    }

    @Test fun signIn_scheduledInTheFuture_cancels() {
        assertEquals(SignInCheck.CANCEL, DeletionFlowRules.signInCheck(now + 1, now))
        assertEquals(SignInCheck.CANCEL, DeletionFlowRules.signInCheck(now + 72 * 3_600_000L, now))
    }

    @Test fun signIn_alreadyDue_isNeverCancelled_exactlyNowCountsAsDue() {
        assertEquals(SignInCheck.DUE, DeletionFlowRules.signInCheck(now, now))
        assertEquals(SignInCheck.DUE, DeletionFlowRules.signInCheck(now - 1, now))
    }

    // ---- the watcher re-reads the server before it cleans up ----

    @Test fun cleanup_skippedWhenNoLongerScheduled() {
        // cancelled on another phone: the document is there, the field is gone
        assertEquals(Cleanup.SKIP, DeletionFlowRules.cleanupAfterReread(true, true, null, now))
    }

    @Test fun cleanup_skippedWhenNoLongerDue() {
        // pushed out by a newer request
        assertEquals(Cleanup.SKIP, DeletionFlowRules.cleanupAfterReread(true, true, now + 1, now))
    }

    @Test fun cleanup_happensWhenStillDue() {
        assertEquals(Cleanup.CLEAN, DeletionFlowRules.cleanupAfterReread(true, true, now - 1, now))
        assertEquals(Cleanup.CLEAN, DeletionFlowRules.cleanupAfterReread(true, true, now, now))
    }

    @Test fun cleanup_happensWhenTheAccountDataIsGone() {
        // the server removed installs/{uid}: the deletion really happened
        assertEquals(Cleanup.CLEAN, DeletionFlowRules.cleanupAfterReread(true, false, null, now))
    }

    @Test fun cleanup_deferredWhenTheServerCannotBeRead_whateverTheCacheSays() {
        // signed out (rules refuse), offline or failed: nothing is decided from the cache alone
        assertEquals(Cleanup.DEFER, DeletionFlowRules.cleanupAfterReread(false, false, null, now))
        assertEquals(Cleanup.DEFER, DeletionFlowRules.cleanupAfterReread(false, true, now - 1, now))
        assertEquals(Cleanup.DEFER, DeletionFlowRules.cleanupAfterReread(false, true, now + 1, now))
    }

    // ---- a different account on the same phone ----

    @Test fun otherAccountLeftovers_clearedOnlyWhenThatDeletionIsDue() {
        assertTrue(DeletionFlowRules.staleOtherAccountDue("old", now - 1, "new", now))
        assertTrue(DeletionFlowRules.staleOtherAccountDue("old", now, "new", now))
    }

    @Test fun otherAccountLeftovers_notClearedWhileThatDeletionIsStillInTheFuture() {
        assertFalse(DeletionFlowRules.staleOtherAccountDue("old", now + 1, "new", now))
    }

    @Test fun sameAccountIsNeverCleaned_itIsCheckedAgainstTheServerInstead() {
        assertFalse(DeletionFlowRules.staleOtherAccountDue("u1", now - 1, "u1", now))
    }

    @Test fun nothingCachedOrNobodySignedIn_cleansNothing() {
        assertFalse(DeletionFlowRules.staleOtherAccountDue(null, null, "new", now))
        assertFalse(DeletionFlowRules.staleOtherAccountDue("old", null, "new", now))
        assertFalse(DeletionFlowRules.staleOtherAccountDue("old", now - 1, null, now))
    }

    // ---- the gate's knobs are plain numbers ----

    @Test fun typingSequence_isTheNumbersFromOneToTheConfiguredMaximum() {
        val seq = DeletionConfig.typingSequence()
        assertEquals((1..DeletionConfig.TYPING_MAX).joinToString(""), seq)
        assertTrue(seq.startsWith("123456789101112"))
        assertTrue(seq.endsWith(DeletionConfig.TYPING_MAX.toString()))
    }

    @Test fun config_isUsable() {
        assertTrue(DeletionConfig.DAYS_REQUIRED >= 1)
        assertTrue(DeletionConfig.QUESTIONS_PER_DAY >= 1)
        assertTrue(DeletionConfig.TYPING_MAX >= 1)
    }

    // ---- the client never writes the deletion fields ----

    /**
     * Only the server may set or clear deletionScheduledFor / deletionRequestedAt.
     * In the app's sources these names may appear in a comment or in a read
     * (getTimestamp); on any other line this test fails, so a client write cannot
     * be added by accident.
     */
    @Test fun clientSourcesNeverWriteTheDeletionFields() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).firstOrNull { it.isDirectory }
            ?: throw AssertionError("source directory not found from ${File(".").absolutePath}")
        val offenders = mutableListOf<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            file.readLines().forEachIndexed { i, line ->
                val code = line.trim()
                val mentions = "deletionScheduledFor" in code || "deletionRequestedAt" in code
                val isComment = code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")
                if (mentions && !isComment && "getTimestamp(" !in code) {
                    offenders += "${file.name}:${i + 1}: $code"
                }
            }
        }
        assertTrue("client code touches the deletion fields: $offenders", offenders.isEmpty())
    }
}
