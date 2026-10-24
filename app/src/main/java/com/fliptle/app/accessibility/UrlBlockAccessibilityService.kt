package com.fliptle.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.fliptle.app.AdultBlocklist
import com.fliptle.app.BlockOverlay
import com.fliptle.app.BrowserDetector
import com.fliptle.app.DomainBlocklist
import com.fliptle.app.PornBlockStore
import com.fliptle.app.KeywordBlocklist
import com.fliptle.app.R
import com.fliptle.app.auth.EntitlementStore

/**
 * SELF-CONTAINED MODULE — URL-based blocking via Accessibility.
 *
 * This service reads the address-bar text of browsers, matches it against the
 * adult-content blocklist and the user's [DomainBlocklist], and enforces
 * safe-search. The adult list and safe-search apply once the user has switched on
 * porn blocking ([PornBlockStore]) — permanently, independent of any freeze. The
 * user's own domain list always applies. Nothing else in the app depends on this
 * class; disabling the accessibility service in system settings turns the whole
 * feature off with no effect on the rest of the app.
 *
 * Reads are used ONLY to compare the current URL against the local blocklist.
 * No browsing data is stored or transmitted (see the in-app disclosure screen).
 */
class UrlBlockAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    private var lastProcessMs = 0L
    private var lastBlockedHost: String? = null
    private var lastBlockedAtMs = 0L
    private var lastSafeSearchUrl: String? = null
    private var lastSafeSearchAtMs = 0L

    private var pollRunning = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        AdultBlocklist.ensureLoaded(this)
        KeywordBlocklist.ensureLoaded(this)
        com.fliptle.app.BlockedDomainApps.recompute(this)
        narrowScopeToMonitoredApps()
        startPollIfNeeded()
    }

    /**
     * Lightweight re-evaluation while a monitored browser is foreground, so an
     * already-open tab (no new AccessibilityEvent fires on it), a throttled
     * event, or a domain blocked after the tab was opened still gets caught.
     * Self-terminating: stops rescheduling the moment the foreground window
     * isn't a monitored browser, and never touches PackageManager -- just
     * [rootInActiveWindow] and the same [extractUrl]/[handleUrl] path events
     * already use.
     */
    private val reevaluateRunnable = object : Runnable {
        override fun run() {
            val root = rootInActiveWindow
            val pkg = root?.packageName?.toString()
            if (root != null && pkg != null && BrowserDetector.isBrowser(this@UrlBlockAccessibilityService, pkg)) {
                extractUrl(root, pkg)?.let { handleUrl(it, pkg) }
                handler.postDelayed(this, REEVALUATE_MS)
            } else {
                pollRunning = false
            }
        }
    }

    private fun startPollIfNeeded() {
        if (pollRunning) return
        pollRunning = true
        handler.postDelayed(reevaluateRunnable, REEVALUATE_MS)
    }

    /**
     * Narrow the monitored package set at runtime to the browsers actually
     * installed plus Instagram/YouTube. This keeps the service off every other
     * app (banking/UPI included), reinforcing the static packageNames in the XML.
     */
    private fun narrowScopeToMonitoredApps() {
        try {
            val info = serviceInfo ?: return
            val packages = HashSet<String>()
            packages.addAll(BrowserDetector.installedBrowsers(this))
            packages.add(SurfaceDetector.IG_PKG)
            packages.add(SurfaceDetector.YT_PKG)
            info.packageNames = packages.toTypedArray()
            serviceInfo = info
        } catch (_: Exception) {
            // Keep the static packageNames from the XML config on failure.
        }
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return

        // Only browsers (URL/porn blocking) and Instagram/YouTube (surface blocking).
        // Porn blocking is one-way once enabled.
        val isBrowser = BrowserDetector.isBrowser(this, pkg)
        val isSurfaceApp = SurfaceDetector.isSurfaceApp(pkg)
        if (!isBrowser && !isSurfaceApp) return

        // Paywall gate: an explicit DENIED from the server (subscription/trial
        // expired past grace, tester mode off) suspends URL/porn/domain and
        // Shorts/Reels enforcement — matching engine and blocklists are left
        // untouched, this only decides whether to act on a match. We never
        // disable the Accessibility service itself (that requires the user to
        // go into system Settings), so the moment EntitlementStore flips back
        // to ENTITLED, the very next event runs the normal path again with no
        // re-enable step needed. UNKNOWN (never checked yet) still enforces —
        // only an explicit DENIED suspends it.
        if (EntitlementStore(this).state == EntitlementStore.State.DENIED) {
            hideOverlay()
            BlockOverlay.hide()
            return
        }

        // Throttle content-change spam; always handle window/state changes.
        val now = SystemClock.elapsedRealtime()
        val isStateChange = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (!isStateChange && now - lastProcessMs < THROTTLE_MS) return
        lastProcessMs = now

        val root = rootInActiveWindow ?: return
        if (isBrowser) {
            extractUrl(root, pkg)?.let { handleUrl(it, pkg) }
            startPollIfNeeded() // restart the poll if it had stopped (browser back to front)
        } else {
            handleSurface(root, pkg, event.eventType)
        }
    }

    /** Block Instagram Reels/Stories or YouTube Shorts if that surface is toggled on.
     *  A toggled surface is simply blocked: no session, allowance or cooldown. */
    private fun handleSurface(root: AccessibilityNodeInfo, pkg: String, eventType: Int) {
        val store = SurfaceBlocklist(this)
        // Observe-only diagnosis is a developer tool: unreachable in release builds
        // and off until the hidden gesture is used, so normal users always block.
        val debugMode = com.fliptle.app.DevMode.enabled(this) && store.debug
        val debug = if (debugMode) ArrayList<String>() else null

        val surface = SurfaceDetector.detect(pkg, root, debug)

        if (debug != null) {
            SurfaceDebug(this).record(pkg, eventType, surface, debug)
        }

        // In debug mode we observe only — never block — so surfaces can be
        // diagnosed freely.
        if (debugMode || surface == null || !store.isBlocked(surface)) return

        // Back returns to the feed / normal app, so the rest stays usable.
        blockAndLeave(surface.name)
    }

    private fun handleUrl(read: UrlReadResult, pkg: String) {
        // Adult blocking is a one-way switch the user turns on once; it can never
        // be turned back off from anywhere in the app. The user's own domain list
        // is separate and always applies.
        val pornBlocking = PornBlockStore(this).enabled
        val result = SafeSearch.evaluate(read.text)

        // 1) Blocked search keyword -> one clean overlay, no navigation. Safe to
        // act on regardless of read confidence: showing more often is safe, only
        // HIDING incorrectly is the risk this function must guard against.
        if (pornBlocking && result is SafeSearch.Result.BlockKeyword) {
            BlockOverlay.show(this)
            return
        }

        // 2) Domain/porn blocklist match (subdomains included) -> one clean overlay.
        val host = hostOf(read.text)
        val blocked = host != null &&
            ((pornBlocking && AdultBlocklist.isBlocked(host)) || DomainBlocklist(this).isBlocked(host))

        // decideOverlay: blocked -> always show (even from a fallback guess);
        // not blocked -> only a CONFIDENT read (real url-bar node) may clear the
        // overlay; a fallback guess leaving the overlay untouched is what fixes
        // the myntra.com case (a collapsed Chrome toolbar makes looksLikeUrl's
        // fallback return junk text for an unrelated node, which must never be
        // read as proof the blocked page is gone).
        when (decideOverlay(read.confident, blocked)) {
            true -> {
                // Idempotent: repeated detections of the same block do NOT stack,
                // and we never hit back / reload / open a new tab (no cascade).
                BlockOverlay.show(this)
                return
            }
            false -> BlockOverlay.hide()
            null -> return
        }

        // 4) Safe-search enforcement — apply ONCE per search, rate-limited, and
        //    never re-fire the same target URL (prevents the redirect loop that
        //    trips Google's "unusual traffic" CAPTCHA). Only reached on a
        //    confident, non-blocked read.
        if (pornBlocking && result is SafeSearch.Result.Redirect) {
            maybeApplySafeSearch(result.url, pkg)
        }
    }

    private fun maybeApplySafeSearch(safeUrl: String, pkg: String) {
        val now = SystemClock.elapsedRealtime()
        if (safeUrl == lastSafeSearchUrl) return                    // already did this exact one
        if (now - lastSafeSearchAtMs < SAFE_SEARCH_COOLDOWN_MS) return // rate limit
        lastSafeSearchUrl = safeUrl
        lastSafeSearchAtMs = now
        redirectTo(safeUrl, pkg)
    }

    /** Navigate the browser away from the blocked page and show a brief overlay. */
    private fun blockAndLeave(host: String?) {
        showBriefOverlay()
        val now = SystemClock.elapsedRealtime()
        val repeat = host != null && host == lastBlockedHost && now - lastBlockedAtMs < ESCALATE_MS
        lastBlockedHost = host
        lastBlockedAtMs = now
        // Back usually closes the blocked page/tab; if it persists, escalate home.
        if (repeat) {
            performGlobalAction(GLOBAL_ACTION_HOME)
        } else {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    private fun redirectTo(safeUrl: String, pkg: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl))
                .setPackage(pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (_: Exception) {
            // Fall back to leaving the unsafe page if the redirect can't launch.
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    // ---- URL extraction ------------------------------------------------------

    /**
     * A read from [extractUrl]: [confident] is true only when the text came
     * from the real url-bar node (matched by its known view id). A
     * [confident] == false read came from [scanForUrl]'s generic fallback and
     * must never be trusted to clear the overlay or apply safe-search -- see
     * [handleUrl]/[decideOverlay].
     */
    private data class UrlReadResult(val text: String, val confident: Boolean)

    private fun extractUrl(root: AccessibilityNodeInfo, pkg: String): UrlReadResult? {
        for (id in urlBarIdsFor(pkg)) {
            val nodes = try {
                root.findAccessibilityNodeInfosByViewId(id)
            } catch (_: Exception) {
                null
            }
            if (!nodes.isNullOrEmpty()) {
                val text = nodes[0].text?.toString()?.trim()
                if (!text.isNullOrEmpty()) return UrlReadResult(text, confident = true)
            }
        }
        // Generic fallback: find a node whose text looks like a URL/host. No
        // url-bar node found at all (e.g. a collapsed toolbar) falls through
        // to this; if it finds nothing either, extractUrl returns null and the
        // caller leaves the current overlay state exactly as it was.
        val fallback = scanForUrl(root, 0) ?: return null
        return UrlReadResult(fallback, confident = false)
    }

    private fun scanForUrl(node: AccessibilityNodeInfo?, depth: Int): String? {
        if (node == null || depth > MAX_DEPTH) return null
        val text = node.text?.toString()?.trim()
        if (text != null && looksLikeUrl(text)) return text
        for (i in 0 until node.childCount) {
            val found = scanForUrl(node.getChild(i), depth + 1)
            if (found != null) return found
        }
        return null
    }

    private fun hostOf(text: String): String? {
        val t = text.trim()
        if (t.isEmpty() || t.contains(' ')) return null
        val uri = if (t.contains("://")) Uri.parse(t) else Uri.parse("https://$t")
        return uri.host?.lowercase()
    }

    private fun urlBarIdsFor(pkg: String): List<String> = when {
        pkg == "com.android.chrome" || pkg.startsWith("com.chrome") ->
            listOf("com.android.chrome:id/url_bar")
        pkg == "com.sec.android.app.sbrowser" ->
            listOf(
                "com.sec.android.app.sbrowser:id/location_bar_edit_text",
                "com.sec.android.app.sbrowser:id/sbrowser_url_bar"
            )
        pkg == "org.mozilla.firefox" ->
            listOf(
                "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
                "org.mozilla.firefox:id/url_bar_title"
            )
        else -> listOf(
            "$pkg:id/url_bar",
            "$pkg:id/location_bar_edit_text",
            "$pkg:id/mozac_browser_toolbar_url_view",
            "$pkg:id/url"
        )
    }

    // ---- brief overlay -------------------------------------------------------

    private fun showBriefOverlay() {
        if (overlayView != null) return
        if (!Settings.canDrawOverlays(this)) return
        val wm = windowManager ?: return

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_url_blocked, null)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        try {
            wm.addView(view, params)
            overlayView = view
            handler.postDelayed({ hideOverlay() }, OVERLAY_MS)
        } catch (_: Exception) {
            overlayView = null
        }
    }

    private fun hideOverlay() {
        val view = overlayView ?: return
        try {
            windowManager?.removeView(view)
        } catch (_: Exception) {
        }
        overlayView = null
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        hideOverlay()
        BlockOverlay.hide()
    }

    companion object {
        private const val THROTTLE_MS = 300L
        private const val ESCALATE_MS = 1_500L
        private const val OVERLAY_MS = 1_500L
        private const val MAX_DEPTH = 40
        private const val SAFE_SEARCH_COOLDOWN_MS = 20_000L
        private const val REEVALUATE_MS = 1_000L

        /**
         * Requires a real hostname shape: one or more "label." segments
         * followed by an alphabetic TLD of 2+ letters, optionally preceded by
         * a scheme and followed by a path. Rejects pure numbers and anything
         * whose last segment isn't alphabetic (e.g. "4.5", "Rs.499", "e.g.",
         * "v1.2.3") -- see DomainAppResolverTest's sibling, this file's own
         * test, for the exact accept/reject list this was tightened against.
         */
        private val URL_LIKE_REGEX = Regex(
            "^(https?://)?([a-z0-9-]+\\.)+[a-z]{2,}(/.*)?$",
            RegexOption.IGNORE_CASE
        )

        /** Pure, Context-free -- directly unit-tested (see UrlHeuristicsTest). */
        fun looksLikeUrl(text: String): Boolean {
            if (text.isEmpty() || text.length > 2048 || text.contains(' ')) return false
            return URL_LIKE_REGEX.matches(text)
        }

        /**
         * The overlay rule at the heart of this fix: a [blocked] match always
         * shows the overlay, confident or not (showing more is always safe).
         * Clearing it -- the only unsafe direction -- requires [confident] to
         * be true. Returns null when the overlay must be left exactly as it
         * is (a fallback guess found nothing blocked: not proof enough).
         * Pure -- directly unit-tested (see UrlHeuristicsTest).
         */
        fun decideOverlay(confident: Boolean, blocked: Boolean): Boolean? = when {
            blocked -> true
            confident -> false
            else -> null
        }
    }
}
