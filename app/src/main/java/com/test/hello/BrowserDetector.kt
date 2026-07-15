package com.test.hello

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Decides whether a package is a web browser and drives auto-blocking.
 *
 * A package counts as a browser if EITHER:
 *   - it is in the bundled [KNOWN_BROWSERS] list, OR
 *   - it declares an intent-filter for ACTION_VIEW / BROWSABLE with an http(s)
 *     scheme (the same query Android itself uses to find browsers).
 *
 * Chrome is deliberately EXEMPT from auto-blocking: it stays usable because it
 * will be filtered by the VPN later. Every other detected browser gets blocked.
 */
object BrowserDetector {

    const val CHROME = "com.android.chrome"

    val KNOWN_BROWSERS: Set<String> = setOf(
        CHROME,                            // Chrome (known browser, but exempt)
        "org.mozilla.firefox",             // Firefox
        "com.brave.browser",               // Brave
        "com.opera.browser",               // Opera
        "com.opera.mini.native",           // Opera Mini
        "com.microsoft.emmx",              // Edge
        "com.UCMobile.intl",               // UC Browser
        "com.sec.android.app.sbrowser",    // Samsung Internet
        "com.duckduckgo.mobile.android",   // DuckDuckGo
        "com.vivaldi.browser",             // Vivaldi
        "com.kiwibrowser.browser"          // Kiwi
    )

    fun isExempt(pkg: String): Boolean = pkg == CHROME

    fun isBrowser(context: Context, pkg: String): Boolean =
        KNOWN_BROWSERS.contains(pkg) || declaresWebViewIntent(context, pkg)

    /** True if this package is a browser that should be auto-blocked. */
    fun shouldAutoBlock(context: Context, pkg: String): Boolean =
        pkg != context.packageName && !isExempt(pkg) && isBrowser(context, pkg)

    /** Package names of every installed browser (Chrome included, for the list). */
    fun installedBrowsers(context: Context): List<String> {
        val pm = context.packageManager
        val result = LinkedHashSet<String>()
        for (ri in queryBrowsers(pm)) {
            result.add(ri.activityInfo.packageName)
        }
        for (pkg in KNOWN_BROWSERS) {
            if (isInstalled(context, pkg)) result.add(pkg)
        }
        result.remove(context.packageName)
        return result.toList()
    }

    /**
     * Auto-block every currently-installed browser except Chrome. Returns the
     * packages newly added to the blocked list.
     */
    fun autoBlockInstalledBrowsers(context: Context): List<String> {
        val store = BlockedAppsStore(context)
        val current = store.get().toMutableSet()
        val added = ArrayList<String>()
        for (pkg in installedBrowsers(context)) {
            if (isExempt(pkg) || pkg == context.packageName) continue
            if (current.add(pkg)) added.add(pkg)
        }
        if (added.isNotEmpty()) {
            store.set(current)
            BlockingService.start(context)
        }
        return added
    }

    private fun declaresWebViewIntent(context: Context, pkg: String): Boolean =
        queryBrowsers(context.packageManager).any { it.activityInfo.packageName == pkg }

    @Suppress("DEPRECATION", "QueryPermissionsNeeded")
    private fun queryBrowsers(pm: PackageManager) =
        pm.queryIntentActivities(WEB_INTENT, PackageManager.MATCH_ALL)

    private fun isInstalled(context: Context, pkg: String): Boolean =
        try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    // ACTION_VIEW + BROWSABLE over a generic http URL -> matches real browsers,
    // not deep-link handlers that restrict themselves to a specific host.
    private val WEB_INTENT: Intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com"))
        .addCategory(Intent.CATEGORY_BROWSABLE)
}
