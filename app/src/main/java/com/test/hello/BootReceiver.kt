package com.test.hello

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the blocking service after the device reboots. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            BlockingService.start(context)
        }
    }
}
