package com.fliptle.app

/**
 * The pure latch rule behind [UninstallGateStore.applyCloudLatch] (E1): local
 * progress on the exit process only ever moves forward when a cloud value is
 * merged in, never backward. Pure (no Context) so it is unit tested directly.
 */
object UninstallLatch {
    /** Never lower the local day count. */
    fun latchedDays(localDays: Int, cloudDays: Int): Int = maxOf(localDays, cloudDays)

    /** Never turn approved back to false once it's true, on either side. */
    fun latchedApproved(localApproved: Boolean, cloudApproved: Boolean): Boolean =
        localApproved || cloudApproved
}
