package com.fliptle.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fires when a new app is installed. If the new package is a browser (and not the
 * exempt Chrome), it is added to the blocked-apps list so the existing overlay
 * blocking applies. Normal apps are left untouched.
 */
class PackageInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_ADDED) return
        // Ignore updates/re-installs of an already-present package.
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return

        val pkg = intent.data?.schemeSpecificPart ?: return
        if (!BrowserDetector.shouldAutoBlock(context, pkg)) return

        val store = BlockedAppsStore(context)
        val set = store.get().toMutableSet()
        if (set.add(pkg)) {
            store.set(set)
            BlockingService.start(context)
        }
    }
}
