package com.fliptle.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fliptle.app.auth.EntitlementStore

/** Restarts the blocking service after the device reboots. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Never restart enforcement for a user we know isn't entitled
            // (paywall gate has explicitly denied them). UNKNOWN still starts
            // so a first-boot user isn't left unprotected while the app
            // hasn't been opened yet.
            if (EntitlementStore(context).state == EntitlementStore.State.DENIED) return
            BlockingService.start(context)
        }
    }
}
