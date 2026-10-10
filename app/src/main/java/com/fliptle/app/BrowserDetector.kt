package com.fliptle.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.fliptle.app.accessibility.SurfaceDetector

/**
 * Decides whether a package is a web browser and drives auto-blocking.
 *
 * A package counts as a browser if EITHER:
 *   - it is in the bundled [KNOWN_BROWSERS] list, OR
 *   - it declares an intent-filter for ACTION_VIEW / BROWSABLE with an http(s)
 *     scheme (the same query Android itself uses to find browsers).
 *
 * Chrome is deliberately EXEMPT from auto-blocking: it stays usable because it
 * is kept usable — porn URLs inside Chrome are handled by the Accessibility URL
 * blocker instead. Every other detected browser gets blocked as an app.
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

    /** Instagram and YouTube have their own surface toggle; they must never be
     *  treated as a browser, whatever an intent-resolution heuristic says — see
     *  the false-positive note on [declaresWebViewIntent]. */
    private fun isSurfaceApp(pkg: String): Boolean =
        pkg == SurfaceDetector.IG_PKG || pkg == SurfaceDetector.YT_PKG

    fun isBrowser(context: Context, pkg: String): Boolean =
        !isSurfaceApp(pkg) && (KNOWN_BROWSERS.contains(pkg) || declaresWebViewIntent(context, pkg))

    /** True if this package is a browser that should be auto-blocked. */
    fun shouldAutoBlock(context: Context, pkg: String): Boolean =
        pkg != context.packageName && !isExempt(pkg) && isBrowser(context, pkg)

    /** Package names of every installed browser (Chrome included, for the list). */
    fun installedBrowsers(context: Context): List<String> {
        val pm = context.packageManager
        val result = LinkedHashSet<String>()
        for (ri in queryBrowsers(pm)) {
            if (!isSurfaceApp(ri.activityInfo.packageName)) result.add(ri.activityInfo.packageName)
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
            if (isExempt(pkg) || pkg == context.packageName || isSurfaceApp(pkg)) continue
            if (current.add(pkg)) added.add(pkg)
        }
        if (added.isNotEmpty()) {
            store.set(current)
            BlockingService.start(context)
        }
        return added
    }

    /**
     * A real browser resolves ANY http(s) host. A deep-link handler (Instagram,
     * YouTube, banking apps, ...) is restricted to the specific hosts it
     * registers, but some apps declare a scheme-only filter (no host) that
     * matches every URI the system can throw at it — including one as generic
     * as "example.com" — which made a single-host probe here a false positive
     * for Instagram. Requiring a match on TWO unrelated, unlikely-to-be-
     * registered hosts is a much stronger signal of "this is a real browser"
     * and a near-zero chance for a deep-link handler to match both by accident.
     * [isSurfaceApp] above is still checked first as a hard, unconditional
     * backstop regardless of what this heuristic ever returns.
     */
    private fun declaresWebViewIntent(context: Context, pkg: String): Boolean =
        WEB_PROBE_INTENTS.all { intent ->
            queryBrowsers(context.packageManager, intent).any { it.activityInfo.packageName == pkg }
        }

    @Suppress("DEPRECATION", "QueryPermissionsNeeded")
    private fun queryBrowsers(pm: PackageManager, intent: Intent = WEB_PROBE_INTENTS[0]) =
        pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)

    private fun isInstalled(context: Context, pkg: String): Boolean =
        try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    // ACTION_VIEW + BROWSABLE over two unrelated, made-up hosts: a real browser
    // resolves both; a deep-link handler registered for its own hosts resolves
    // neither (see declaresWebViewIntent).
    private val WEB_PROBE_INTENTS: List<Intent> = listOf(
        Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com")).addCategory(Intent.CATEGORY_BROWSABLE),
        Intent(Intent.ACTION_VIEW, Uri.parse("http://fliptle-browser-probe.invalid"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
    )
}
