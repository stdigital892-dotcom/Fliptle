package com.fliptle.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fliptle.app.auth.EntitlementStore

/**
 * Fires when an app is installed, updated, or removed.
 *
 * On install (not an update) of a browser (and not the exempt Chrome), it is
 * added to the blocked-apps list so the existing overlay blocking applies.
 * Normal apps are left untouched by that part.
 *
 * On ANY install, update, or removal, [BlockedDomainApps] is recomputed: a
 * newly-installed app that handles a domain the user already blocked (e.g.
 * installing Myntra after blocking myntra.com) must be blocked immediately,
 * not only the next time the domain list itself changes.
 */
class PackageInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED -> {
                BlockedDomainApps.recompute(context)
                // Ignore updates/re-installs of an already-present package for
                // the browser auto-block step below.
                if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
                autoBlockIfBrowser(context, intent)
            }
            Intent.ACTION_PACKAGE_REMOVED, Intent.ACTION_PACKAGE_REPLACED -> {
                BlockedDomainApps.recompute(context)
            }
            else -> return
        }
    }

    private fun autoBlockIfBrowser(context: Context, intent: Intent) {
        val pkg = intent.data?.schemeSpecificPart ?: return
        if (!BrowserDetector.shouldAutoBlock(context, pkg)) return

        val store = BlockedAppsStore(context)
        val set = store.get().toMutableSet()
        if (set.add(pkg)) {
            store.set(set)
            // Don't (re)start enforcement for a user the paywall gate has
            // explicitly denied. The blocked-apps list is still updated so
            // it's ready to run the moment they renew.
            if (EntitlementStore(context).state != EntitlementStore.State.DENIED) {
                BlockingService.start(context)
            }
            CloudState.backup(context) // keep the cloud copy of blocked apps current
        }
    }
}
