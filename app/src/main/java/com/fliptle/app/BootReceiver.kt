package com.fliptle.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the blocking service after the device reboots. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            BlockingService.start(context)
            // Restart the DNS filter too. establish() succeeds only if the user
            // previously granted VPN consent; otherwise the service stops itself.
            DnsVpnService.start(context)
        }
    }
}
