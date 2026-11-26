package com.fliptle.app

/**
 * Day 5's rule: the exit gate's approved flag may be set ONLY after the
 * server write succeeds -- never before, never on failure. Pure, so the
 * rule itself is directly unit-tested regardless of how the write is made.
 */
object FinalDayGate {
    fun mayApprove(writeSucceeded: Boolean): Boolean = writeSucceeded
}
