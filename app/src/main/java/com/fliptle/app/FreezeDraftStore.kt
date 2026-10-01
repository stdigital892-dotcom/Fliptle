package com.fliptle.app

import android.content.Context
import com.fliptle.app.accessibility.SurfaceBlocklist

/**
 * The wizard's draft: what the user has chosen to block but NOT yet committed.
 * Nothing here is enforced. Each wizard step's Save writes its part; leaving
 * the wizard early keeps whatever was saved. Only [Commitment.commit] copies the
 * draft into the live stores ([BlockedAppsStore], [DomainBlocklist],
 * [SurfaceBlocklist]) and starts the lock.
 *
 * The draft is seeded from the live selections the first time it is needed, so a
 * returning user starts from their current choices. After a commit it is reset,
 * so the next wizard run seeds from the (now-live) values again.
 *
 * Only user-chosen domains are held here. The built-in test domains are always
 * enforced but are not a user choice, so they never count toward the "at least
 * one item" rule.
 */
class FreezeDraftStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("freeze_draft", Context.MODE_PRIVATE)

    val apps: Set<String>
        get() = HashSet(prefs.getStringSet(KEY_APPS, emptySet()) ?: emptySet())

    val domains: Set<String>
        get() = HashSet(prefs.getStringSet(KEY_DOMAINS, emptySet()) ?: emptySet())

    val reels: Boolean
        get() = prefs.getBoolean(KEY_REELS, false)

    val shorts: Boolean
        get() = prefs.getBoolean(KEY_SHORTS, false)

    /** Seed from the live selections if no draft exists yet. Idempotent. */
    fun ensureInitialized() {
        if (prefs.getBoolean(KEY_INIT, false)) return
        val surfaces = SurfaceBlocklist(appContext)
        prefs.edit()
            .putStringSet(KEY_APPS, HashSet(BlockedAppsStore(appContext).get()))
            .putStringSet(KEY_DOMAINS, HashSet(DomainBlocklist(appContext).userDomains()))
            .putBoolean(KEY_REELS, surfaces.reels)
            .putBoolean(KEY_SHORTS, surfaces.shorts)
            .putBoolean(KEY_INIT, true)
            .apply()
    }

    fun saveApps(packages: Set<String>) {
        ensureInitialized()
        prefs.edit().putStringSet(KEY_APPS, HashSet(packages)).apply()
    }

    fun saveDomains(userDomains: Set<String>) {
        ensureInitialized()
        prefs.edit().putStringSet(KEY_DOMAINS, HashSet(userDomains)).apply()
    }

    fun saveSurfaces(reels: Boolean, shorts: Boolean) {
        ensureInitialized()
        prefs.edit().putBoolean(KEY_REELS, reels).putBoolean(KEY_SHORTS, shorts).apply()
    }

    /** Number of user-chosen items: apps + user domains + each toggle that is on. */
    fun itemCount(): Int =
        apps.size + domains.size + (if (reels) 1 else 0) + (if (shorts) 1 else 0)

    /** True if the draft differs from what is currently live. */
    fun differsFromLive(): Boolean {
        val surfaces = SurfaceBlocklist(appContext)
        return apps != BlockedAppsStore(appContext).get() ||
            domains != DomainBlocklist(appContext).userDomains() ||
            reels != surfaces.reels ||
            shorts != surfaces.shorts
    }

    /** Copy the draft into the live stores. */
    fun applyToLive() {
        BlockedAppsStore(appContext).set(apps)
        DomainBlocklist(appContext).setUserDomains(domains)
        val surfaces = SurfaceBlocklist(appContext)
        surfaces.reels = reels
        surfaces.shorts = shorts
    }

    /** Forget the draft so the next wizard run re-seeds from the live values. */
    fun reset() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_INIT = "initialized"
        private const val KEY_APPS = "apps"
        private const val KEY_DOMAINS = "domains"
        private const val KEY_REELS = "reels"
        private const val KEY_SHORTS = "shorts"
    }
}
