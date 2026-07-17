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
import com.fliptle.app.BrowserDetector
import com.fliptle.app.DomainBlocklist
import com.fliptle.app.FreezeStore
import com.fliptle.app.KeywordBlocklist
import com.fliptle.app.R

/**
 * SELF-CONTAINED MODULE — URL-based blocking via Accessibility.
 *
 * This service reads the address-bar text of browsers, matches it against the
 * SHARED [DomainBlocklist] (the same list used by the VPN feature), and enforces
 * safe-search. It only acts while a freeze is running. Nothing else in the app
 * depends on this class; disabling the accessibility service in system settings
 * turns the whole feature off with no effect on the rest of the app.
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        AdultBlocklist.ensureLoaded(this)
        KeywordBlocklist.ensureLoaded(this)
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return

        // Only browsers (URL blocking) and Instagram/YouTube (surface blocking),
        // and only while a freeze is running.
        val isBrowser = BrowserDetector.isBrowser(this, pkg)
        val isSurfaceApp = SurfaceDetector.isSurfaceApp(pkg)
        if (!isBrowser && !isSurfaceApp) return
        if (!FreezeStore(this).active) return

        // Throttle content-change spam; always handle window/state changes.
        val now = SystemClock.elapsedRealtime()
        val isStateChange = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (!isStateChange && now - lastProcessMs < THROTTLE_MS) return
        lastProcessMs = now

        val root = rootInActiveWindow ?: return
        if (isBrowser) {
            val url = extractUrl(root, pkg) ?: return
            handleUrl(url, pkg)
        } else {
            handleSurface(root, pkg, event.eventType)
        }
    }

    /** Block Instagram Reels/Stories or YouTube Shorts if that surface is toggled on. */
    private fun handleSurface(root: AccessibilityNodeInfo, pkg: String, eventType: Int) {
        val store = SurfaceBlocklist(this)
        val debugMode = store.debug
        val debug = if (debugMode) ArrayList<String>() else null

        val surface = SurfaceDetector.detect(pkg, root, debug)

        if (debug != null) {
            SurfaceDebug(this).record(pkg, eventType, surface, debug)
        }

        // In debug mode we observe only — never block — so surfaces can be
        // diagnosed freely. Otherwise block the full-screen player if toggled on.
        if (!debugMode && surface != null && store.isBlocked(surface)) {
            // Back returns to the feed / normal app, so the rest stays usable.
            blockAndLeave(surface.name)
        }
    }

    private fun handleUrl(url: String, pkg: String) {
        // 1) Safe-search enforcement.
        when (val result = SafeSearch.evaluate(url)) {
            is SafeSearch.Result.BlockKeyword -> {
                blockAndLeave(hostOf(url))
                return
            }
            is SafeSearch.Result.Redirect -> {
                redirectTo(result.url, pkg)
                return
            }
            SafeSearch.Result.None -> { /* fall through to domain check */ }
        }

        // 2) Domain blocklist match (subdomains included). Checks the shared
        //    adult-content list AND the user's own blocked domains.
        val host = hostOf(url) ?: return
        if (AdultBlocklist.isBlocked(host) || DomainBlocklist(this).isBlocked(host)) {
            blockAndLeave(host)
        }
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

    private fun extractUrl(root: AccessibilityNodeInfo, pkg: String): String? {
        for (id in urlBarIdsFor(pkg)) {
            val nodes = try {
                root.findAccessibilityNodeInfosByViewId(id)
            } catch (_: Exception) {
                null
            }
            if (!nodes.isNullOrEmpty()) {
                val text = nodes[0].text?.toString()?.trim()
                if (!text.isNullOrEmpty()) return text
            }
        }
        // Generic fallback: find a node whose text looks like a URL/host.
        return scanForUrl(root, 0)
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

    private fun looksLikeUrl(text: String): Boolean {
        if (text.length > 2048 || text.contains(' ')) return false
        if (text.startsWith("http://") || text.startsWith("https://")) return true
        return text.contains('.')
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
    }

    companion object {
        private const val THROTTLE_MS = 300L
        private const val ESCALATE_MS = 1_500L
        private const val OVERLAY_MS = 1_500L
        private const val MAX_DEPTH = 40
    }
}
