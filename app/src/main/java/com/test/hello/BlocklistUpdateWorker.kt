package com.test.hello

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Weekly background refresh of the adult-content blocklist. Runs only when the
 * device is online; if the fetch fails it retries later and the app keeps using
 * the last known list.
 */
class BlocklistUpdateWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        val newCount = AdultBlocklist.updateFromNetwork(applicationContext)
        return if (newCount != null) Result.success() else Result.retry()
    }

    companion object {
        private const val WORK_NAME = "adult-blocklist-weekly-update"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BlocklistUpdateWorker>(7, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
