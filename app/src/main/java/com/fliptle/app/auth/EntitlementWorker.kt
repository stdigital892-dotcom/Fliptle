package com.fliptle.app.auth

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.fliptle.app.BlockingService
import com.fliptle.app.Permissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Periodic background entitlement re-check, so an expired subscription or
 * trial flips [EntitlementStore] to DENIED even if the user never opens the
 * app. [UrlBlockAccessibilityService] reads that store live on every event
 * and needs no help from this worker — but [BlockingService] (the app-
 * blocking foreground service) has no such check of its own; it only reacts
 * to explicit start()/stop() calls. So this worker mirrors MainActivity's
 * own gating and drives BlockingService directly on an explicit result, the
 * same way MainActivity does at every app open.
 *
 * Runs every 8 hours (inside the requested 6-12h window) with a CONNECTED
 * network constraint — WorkManager won't even start the job while offline.
 *
 * Safeguard: this worker does no denial logic of its own. It only invokes
 * [EntitlementGate.check], which already guarantees a failed or offline
 * check leaves the cached state untouched — only an explicit server
 * `entitled: false` response ever writes DENIED. The BlockingService
 * side-effects below only run for an explicit Entitled/Denied result, never
 * for Unauthenticated, TransientError, or a timeout.
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
        var callbackResult: EntitlementGate.Result? = null
        EntitlementGate.check(applicationContext) { r ->
            callbackResult = r
            latch.countDown()
        }
        // latch.await() establishes happens-before with countDown() above, so
        // reading callbackResult afterwards is safe even though it was
        // written from a different thread (Firebase's callback thread).
        latch.await(CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val finalResult = callbackResult

        try {
            when (finalResult) {
                is EntitlementGate.Result.Denied -> {
                    // Explicit "no": stop enforcement even if the app is
                    // never reopened. BlockingService has no internal
                    // EntitlementStore check of its own — without this call
                    // it would keep blocking apps indefinitely until the
                    // next app open or reboot.
                    BlockingService.stop(applicationContext)
                }
                is EntitlementGate.Result.Entitled -> {
                    // Renewed/entitled again: restart enforcement, but only
                    // if permissions are actually granted — mirrors
                    // MainActivity's own gate exactly. A background worker
                    // must never prompt for a permission.
                    if (Permissions.allEnforcementGranted(applicationContext)) {
                        BlockingService.start(applicationContext)
                    }
                }
                else -> {
                    // Unauthenticated, TransientError, or a timeout (null):
                    // EntitlementGate.check already left EntitlementStore
                    // untouched for every one of these — leave BlockingService
                    // alone too.
                }
            }
        } catch (e: Exception) {
            // Defense in depth: Android 12+ can refuse startForegroundService()
            // from a background execution context
            // (ForegroundServiceStartNotAllowedException), and this worker IS
            // a background context. Not fatal — the accessibility gate already
            // reacts to EntitlementStore live on its own, and the next app
            // open restarts BlockingService normally from MainActivity's
            // foreground context. A background worker must never crash the
            // app over this.
            Log.w(TAG, "Post-check BlockingService update failed: ${e.message}")
        }

        // Always success: the job's role is "attempt a check", not "achieve
        // a particular entitlement outcome". A timeout or transient failure
        // already left the cache exactly as it was (EntitlementGate's own
        // safeguard) — the next scheduled run 8 hours later tries again.
        return Result.success()
    }

    companion object {
        private const val TAG = "EntitlementWorker"
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
