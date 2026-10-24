package com.fliptle.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Resolves which installed apps can open a blocked domain, so a website
 * block can't be bypassed by opening the same site's native app (e.g. an
 * Android App Link handing instagram.com or myntra.com straight to its app).
 *
 * Two sources, unioned:
 *  - [STATIC_FALLBACK]: a curated domain -> package(s) table for well-known
 *    apps, used whether or not the app is installed yet (so it's ready the
 *    moment it is).
 *  - Dynamic resolution via [PackageManager.queryIntentActivities] against
 *    ACTION_VIEW/BROWSABLE for the domain (and its "www." form), the same
 *    mechanism Android itself uses to pick an app for a link -- this is what
 *    actually catches a verified Android App Link.
 *
 * Both are filtered through [isBlockable] so a browser, this app itself, or a
 * protected system package is never swept in.
 */
object DomainAppResolver {

    /** Curated domain -> package(s), only for apps whose real package name is
     *  well known. Applied even if the app isn't installed (harmless: nothing
     *  to block until it is, and BlockingService only acts on it then). */
    val STATIC_FALLBACK: Map<String, Set<String>> = mapOf(
        "instagram.com" to setOf("com.instagram.android", "com.instagram.lite"),
        "youtube.com" to setOf("com.google.android.youtube"),
        "youtu.be" to setOf("com.google.android.youtube"),
        "reddit.com" to setOf("com.reddit.frontpage"),
        "myntra.com" to setOf("com.myntra.android"),
        "flipkart.com" to setOf("com.flipkart.android"),
        "amazon.in" to setOf("in.amazon.mShop.android.shopping", "com.amazon.mShop.android.shopping"),
        "amazon.com" to setOf("in.amazon.mShop.android.shopping", "com.amazon.mShop.android.shopping"),
        "facebook.com" to setOf("com.facebook.katana", "com.facebook.lite"),
        "x.com" to setOf("com.twitter.android"),
        "twitter.com" to setOf("com.twitter.android"),
        "snapchat.com" to setOf("com.snapchat.android"),
        "pinterest.com" to setOf("com.pinterest"),
        "telegram.org" to setOf("org.telegram.messenger"),
        "t.me" to setOf("org.telegram.messenger"),
        "netflix.com" to setOf("com.netflix.mediaclient"),
        "spotify.com" to setOf("com.spotify.music"),
        "linkedin.com" to setOf("com.linkedin.android"),
        "discord.com" to setOf("com.discord"),
        "quora.com" to setOf("com.quora.android"),
        "meesho.com" to setOf("com.meesho.supply"),
        "zomato.com" to setOf("com.application.zomato"),
        "swiggy.com" to setOf("in.swiggy.android")
    )

    /** Settings, Phone/Dialer, Messages, Play Store, Play Services, System UI,
     *  launcher, Gmail, Maps, Camera, Contacts, Clock. Not exhaustive across
     *  every OEM (especially launchers) -- extend as specific gaps surface. */
    val PROTECTED_PACKAGES: Set<String> = setOf(
        "com.android.settings", "com.samsung.android.settings",
        "com.android.dialer", "com.google.android.dialer", "com.samsung.android.dialer",
        "com.android.server.telecom", "com.android.phone", "com.android.incallui",
        "com.google.android.apps.messaging", "com.android.mms", "com.samsung.android.messaging",
        "com.android.vending", "com.google.android.gms", "com.google.android.gsf",
        "com.android.systemui",
        "com.google.android.apps.nexuslauncher", "com.android.launcher3",
        "com.sec.android.app.launcher", "com.miui.home", "com.oppo.launcher",
        "com.google.android.gm",
        "com.google.android.apps.maps",
        "com.android.camera2", "com.google.android.GoogleCamera", "com.sec.android.app.camera",
        "com.google.android.contacts", "com.android.contacts", "com.samsung.android.app.contacts",
        "com.google.android.deskclock", "com.android.deskclock", "com.sec.android.app.clockpackage"
    )

    /** True if [pkg] is allowed to be swept into a domain's derived app-block
     *  set: not this app, not a browser, not a protected system package.
     *  Pure (Context-free) so it's directly unit-tested. */
    fun isBlockable(pkg: String, ownPackage: String, isBrowserPkg: Boolean): Boolean =
        pkg != ownPackage && !isBrowserPkg && pkg !in PROTECTED_PACKAGES

    /** Apps (installed, via PackageManager) that can open [host] or "www."+[host]. */
    private fun resolveForHost(context: Context, host: String): Set<String> {
        val pm = context.packageManager
        val candidates = HashSet<String>()
        candidates.addAll(queryHandlers(pm, "https://$host"))
        val withWww = if (host.startsWith("www.")) host else "www.$host"
        if (withWww != host) candidates.addAll(queryHandlers(pm, "https://$withWww"))

        val own = context.packageName
        return candidates.filterTo(HashSet()) { pkg ->
            isBlockable(pkg, own, BrowserDetector.isBrowser(context, pkg))
        }
    }

    @Suppress("DEPRECATION", "QueryPermissionsNeeded")
    private fun queryHandlers(pm: PackageManager, url: String): List<String> =
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL).map { it.activityInfo.packageName }
        } catch (_: Exception) {
            emptyList()
        }

    /** The full derived app set for every domain in [domains] (already
     *  normalized bare hosts, as [DomainBlocklist.userDomains] returns). */
    fun resolveApps(context: Context, domains: Collection<String>): Set<String> {
        val result = HashSet<String>()
        for (domain in domains) {
            STATIC_FALLBACK[domain]?.let { result.addAll(it) }
            try {
                result.addAll(resolveForHost(context, domain))
            } catch (_: Exception) {
                // A single bad domain/PM failure must not drop the rest.
            }
        }
        return result
    }
}
