package com.fliptle.app

import android.content.Context

/**
 * In-memory cache of [DomainAppResolver.resolveApps] for the user's current
 * domain list, so BlockingService's 1-second enforce() loop never touches
 * PackageManager itself. Recomputed -- not read live -- whenever the domain
 * list changes ([DomainBlocklist]'s mutators), on process/service start, and
 * whenever an app is installed, updated or removed ([PackageInstallReceiver]).
 */
object BlockedDomainApps {

    @Volatile private var cached: Set<String> = emptySet()

    fun current(): Set<String> = cached

    fun recompute(context: Context) {
        val domains = DomainBlocklist(context).userDomains()
        cached = if (domains.isEmpty()) emptySet() else DomainAppResolver.resolveApps(context, domains)
    }
}
