package com.fliptle.app

import android.app.Application
import com.fliptle.app.auth.FirebaseGate

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
        AdultBlocklist.ensureLoaded(this)
        KeywordBlocklist.ensureLoaded(this)
        BlocklistUpdateWorker.schedule(this)

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
}
