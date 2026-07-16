package com.fliptle.app

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

/**
 * Shared "usage sensor": reports the package currently in the foreground using
 * UsageStatsManager. Used by both the main screen and the blocking service.
 */
object ForegroundApp {

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Package name of the most recent foreground app, or null if unknown. */
    fun current(context: Context): String? {
        if (!hasUsageAccess(context)) return null
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - QUERY_WINDOW_MS, now)
        val event = UsageEvents.Event()
        var lastPackage: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastPackage = event.packageName
            }
        }
        return lastPackage
    }

    private const val QUERY_WINDOW_MS = 60_000L
}
