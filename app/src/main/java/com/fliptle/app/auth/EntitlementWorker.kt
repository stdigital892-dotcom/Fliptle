package com.fliptle.app.auth

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Periodic background entitlement re-check, so an expired subscription or
 * trial flips [EntitlementStore] to DENIED even if the user never opens the
 * app. [UrlBlockAccessibilityService] and [com.fliptle.app.BlockingService]
 * both read that store live, so this is what actually turns protection off
 * on a schedule rather than only at the next app launch.
 *
 * Runs every 8 hours (inside the requested 6-12h window) with a CONNECTED
 * network constraint — WorkManager won't even start the job while offline.
 *
 * Safeguard: this worker does no denial logic of its own. It only invokes
 * [EntitlementGate.check], which already guarantees a failed or offline
 * check leaves the cached state untouched — only an explicit server
 * `entitled: false` response ever writes DENIED. This worker cannot make
 * that guarantee weaker; it can only trigger a check that obeys it.
 */
class EntitlementWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        // EntitlementGate.check is callback-based (a Firebase Functions Task
        // under the hood), but Worker.doWork() must block until finished —
        // WorkManager runs this on its own background thread, so blocking
        // here is safe and doesn't touch the main thread. The callback fires
        // exactly once either way; the timeout only guards against a hung
        // connection that somehow slips past the CONNECTED constraint.
        val latch = CountDownLatch(1)
        EntitlementGate.check(applicationContext) { latch.countDown() }
        latch.await(CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        // Always success: the job's role is "attempt a check", not "achieve
        // a particular entitlement outcome". A timeout or transient failure
        // already left the cache exactly as it was (EntitlementGate's own
        // safeguard) — the next scheduled run 8 hours later tries again.
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "entitlement-recheck"
        private const val CHECK_TIMEOUT_SECONDS = 30L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<EntitlementWorker>(8, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }
    }
}
