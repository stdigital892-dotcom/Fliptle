package com.fliptle.app

/**
 * The knobs of the account-deletion gate, in one place.
 *
 * Change a number here and everything follows: the screen text is built from
 * these values, so nothing else needs editing. The wording of the typing gate
 * (and of every other line) lives in strings.xml under the account_delete_*
 * names, so it can be changed there without touching code.
 *
 * None of this can shorten the 72 hours: the server alone decides when a
 * deletion is due, from its own clock, at the moment a request arrives.
 */
object DeletionConfig {
    /** Days in a row the user must complete before deletion can be requested. */
    const val DAYS_REQUIRED = 5

    /** Arithmetic questions per day (first gate). */
    const val QUESTIONS_PER_DAY = 10

    /** The typing gate asks for the numbers 1..TYPING_MAX with no separators (second gate). */
    const val TYPING_MAX = 50

    /** The sequence the typing gate expects: "1234567891011…". */
    fun typingSequence(): String = buildString { for (i in 1..TYPING_MAX) append(i) }
}
