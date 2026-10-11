package com.fliptle.app

/**
 * Pure comparison behind the protection guard's post-tap cooldown (C1 follow-up):
 * after the user taps one of [ProtectionGuardActivity]'s "enable" buttons, the
 * guard must not relaunch for 60 seconds, so Settings stays usable instead of
 * being yanked back over by the next poll. Kept Context-free so it is directly
 * unit-testable — see GuardCooldownTest.
 */
object GuardCooldown {
    const val SUPPRESS_MS = 60_000L

    /** True while [nowMs] is still before [suppressUntilMs]. */
    fun isSuppressed(nowMs: Long, suppressUntilMs: Long): Boolean = nowMs < suppressUntilMs
}
