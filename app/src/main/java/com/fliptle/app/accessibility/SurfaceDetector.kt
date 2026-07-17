package com.fliptle.app.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Detects the FULL-SCREEN Instagram Reels/Stories and YouTube Shorts players by
 * inspecting the accessibility node tree. Deliberately conservative: it errs
 * strongly toward NOT blocking, because a false positive that breaks a whole app
 * is far worse than missing a reel.
 *
 * Two conditions must BOTH hold for a match:
 *   1. A node's view id matches a specific player-container substring (below).
 *   2. That node is (near) full-screen — it fills most of the app window. This is
 *      what separates the immersive player from an inline reel preview in the
 *      home feed, a Shorts shelf, or the "Shorts"/"Reels" nav-tab button.
 *
 * Content-descriptions are intentionally NOT used to classify: the string
 * "Shorts" appears on YouTube's nav tab and home shelf, which previously caused
 * the whole app to be blocked on launch.
 *
 * HONEST CAVEAT — these are Instagram's/YouTube's INTERNAL view ids, not a public
 * API, and can change with any app update, silently breaking detection. Use the
 * in-app debug mode (Setup → In-app surfaces → "Debug: log matches, don't block")
 * to capture the real ids on your device, then update the substrings here.
 *
 * View ids matched (must ALSO be full-screen):
 *   Instagram (com.instagram.android)
 *     • Reels   : view id contains "clips_viewer"
 *     • Stories : view id contains "reel_viewer"
 *   YouTube (com.google.android.youtube)
 *     • Shorts  : view id contains "reel_recycler", "reel_player" or "reel_watch"
 */
object SurfaceDetector {

    const val IG_PKG = "com.instagram.android"
    const val YT_PKG = "com.google.android.youtube"

    enum class Surface { IG_REELS, IG_STORIES, YT_SHORTS }

    fun isSurfaceApp(pkg: String): Boolean = pkg == IG_PKG || pkg == YT_PKG

    /**
     * Returns the blocked surface if a full-screen player is present. When
     * [debug] is non-null, diagnostic lines for candidate nodes are appended to
     * it (id / description / bounds / full-screen flag) for on-device inspection.
     */
    fun detect(pkg: String, root: AccessibilityNodeInfo?, debug: MutableList<String>?): Surface? {
        if (root == null) return null
        val rootBounds = Rect().also { root.getBoundsInScreen(it) }
        if (rootBounds.width() <= 0 || rootBounds.height() <= 0) return null
        return dfs(pkg, root, 0, intArrayOf(MAX_NODES), rootBounds, debug)
    }

    private fun dfs(
        pkg: String,
        node: AccessibilityNodeInfo?,
        depth: Int,
        budget: IntArray,
        rootBounds: Rect,
        debug: MutableList<String>?
    ): Surface? {
        if (node == null || depth > MAX_DEPTH || budget[0] <= 0) return null
        budget[0]--

        val id = node.viewIdResourceName
        val fullScreen = isFullScreen(node, rootBounds)

        if (debug != null && id != null) {
            val low = id.lowercase()
            if (low.contains("reel") || low.contains("clip") || low.contains("short") || fullScreen) {
                val desc = node.contentDescription?.toString().orEmpty()
                debug.add("id=$id desc=\"$desc\" fullScreen=$fullScreen")
            }
        }

        val candidate = classify(pkg, id)
        if (candidate != null && fullScreen) return candidate

        for (i in 0 until node.childCount) {
            dfs(pkg, node.getChild(i), depth + 1, budget, rootBounds, debug)?.let { return it }
        }
        return null
    }

    /** Classify by view id only (no content-description) — the strict signal. */
    private fun classify(pkg: String, id: String?): Surface? {
        if (id == null) return null
        return when (pkg) {
            IG_PKG -> when {
                id.contains("clips_viewer") -> Surface.IG_REELS
                id.contains("reel_viewer") -> Surface.IG_STORIES
                else -> null
            }
            YT_PKG -> if (
                id.contains("reel_recycler") ||
                id.contains("reel_player") ||
                id.contains("reel_watch")
            ) Surface.YT_SHORTS else null
            else -> null
        }
    }

    /** A node counts as full-screen only if it fills most of the app window. */
    private fun isFullScreen(node: AccessibilityNodeInfo, rootBounds: Rect): Boolean {
        val b = Rect().also { node.getBoundsInScreen(it) }
        if (b.width() <= 0 || b.height() <= 0) return false
        val wRatio = b.width().toFloat() / rootBounds.width()
        val hRatio = b.height().toFloat() / rootBounds.height()
        return wRatio >= MIN_WIDTH_RATIO && hRatio >= MIN_HEIGHT_RATIO
    }

    private const val MAX_DEPTH = 40
    private const val MAX_NODES = 1200
    private const val MIN_WIDTH_RATIO = 0.9f
    private const val MIN_HEIGHT_RATIO = 0.75f
}
