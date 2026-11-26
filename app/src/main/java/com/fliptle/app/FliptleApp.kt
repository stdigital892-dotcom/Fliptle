package com.fliptle.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Bundle
import com.fliptle.app.auth.EntitlementWorker
import com.fliptle.app.auth.FirebaseGate
import com.fliptle.app.auth.Heartbeat
import com.fliptle.app.auth.HeartbeatWorker

/**
 * Process-wide startup: warm the shared blocklists, schedule the weekly update,
 * and initialise Remote Config when Firebase is available. This runs on every
 * process start (including after a boot), independent of any screen. Foreground
 * services are NOT started here (that must happen from a foreground context or
 * the boot receiver) — see HomeActivity / BootReceiver.
 */
class FliptleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FreezeMigration.run(this)
        AdultBlocklist.ensureLoaded(this)
        KeywordBlocklist.ensureLoaded(this)
        BlockedDomainApps.recompute(this)
        BlocklistUpdateWorker.schedule(this)
        HeartbeatWorker.schedule(this)
        EntitlementWorker.schedule(this)
        Heartbeat.beat(this) // check in on process start
        com.fliptle.app.auth.AccountDeletionWatcher.start(this)
        registerConnectivityHeartbeat()
        registerForegroundHeartbeat()

        if (FirebaseGate.isAvailable(this)) {
            try {
                val rc = com.google.firebase.remoteconfig.FirebaseRemoteConfig.getInstance()
                rc.setDefaultsAsync(R.xml.remote_config_defaults)
                rc.fetchAndActivate()
            } catch (_: Throwable) {
                // Remote Config unavailable; in-app default threshold applies.
            }
        }
    }

    /**
     * "When connectivity returns" is implemented as a ConnectivityManager
     * callback registered here, NOT a manifest <receiver> for
     * CONNECTIVITY_ACTION -- that broadcast stopped being delivered to
     * manifest-declared receivers for apps targeting API 24+, so a static
     * receiver for it would silently never fire. onAvailable() covers both
     * "came back online" and "first network found at process start".
     */
    private fun registerConnectivityHeartbeat() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            cm.registerNetworkCallback(
                NetworkRequest.Builder().build(),
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        Heartbeat.beat(this@FliptleApp)
                    }
                }
            )
        } catch (_: Throwable) {
            // Best-effort; the periodic worker and other triggers still run.
        }
    }

    /** Heartbeat whenever the app comes to the foreground (0 -> 1 started
     *  activities), not just on cold process start. */
    private fun registerForegroundHeartbeat() {
        var startedCount = 0
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (startedCount == 0) Heartbeat.beat(this@FliptleApp)
                startedCount++
            }
            override fun onActivityStopped(activity: Activity) {
                startedCount = (startedCount - 1).coerceAtLeast(0)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
