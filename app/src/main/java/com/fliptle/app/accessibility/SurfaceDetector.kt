package com.fliptle.app.accessibility

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Detects specific in-app "distraction surfaces" by inspecting the accessibility
 * node tree for view-id / content-description signatures.
 *
 * HONEST CAVEAT — these signatures are matched against the current builds of
 * Instagram and YouTube and are NOT a stable public API. Both apps use
 * obfuscated / internal resource IDs that can change with any update, which would
 * silently break detection until these substrings are updated. The matches used:
 *
 *   Instagram (com.instagram.android)
 *     • Reels   : a node whose view id contains "clips_viewer"
 *                 (the Reels vertical pager / player)
 *     • Stories : a node whose view id contains "reel_viewer"
 *                 (Instagram's internal name for the Stories viewer)
 *
 *   YouTube (com.google.android.youtube)
 *     • Shorts  : a node whose view id contains "reel_recycler",
 *                 "reel_player" or "reel_watch" (YouTube's internal name for
 *                 Shorts is "reel"), OR a content description containing "Shorts"
 *
 * If Instagram/YouTube change these IDs, update the substrings below.
 */
object SurfaceDetector {

    const val IG_PKG = "com.instagram.android"
    const val YT_PKG = "com.google.android.youtube"

    enum class Surface { IG_REELS, IG_STORIES, YT_SHORTS }

    fun isSurfaceApp(pkg: String): Boolean = pkg == IG_PKG || pkg == YT_PKG

    fun detect(pkg: String, root: AccessibilityNodeInfo?): Surface? = when (pkg) {
        IG_PKG -> firstMatch(root) { id, _ ->
            when {
                id == null -> null
                id.contains("clips_viewer") -> Surface.IG_REELS
                id.contains("reel_viewer") -> Surface.IG_STORIES
                else -> null
            }
        }
        YT_PKG -> firstMatch(root) { id, desc ->
            when {
                id != null && (id.contains("reel_recycler") ||
                    id.contains("reel_player") || id.contains("reel_watch")) -> Surface.YT_SHORTS
                desc != null && desc.contains("Shorts") -> Surface.YT_SHORTS
                else -> null
            }
        }
        else -> null
    }

    private fun firstMatch(
        root: AccessibilityNodeInfo?,
        classify: (id: String?, desc: String?) -> Surface?
    ): Surface? {
        val budget = intArrayOf(MAX_NODES)
        return dfs(root, 0, budget, classify)
    }

    private fun dfs(
        node: AccessibilityNodeInfo?,
        depth: Int,
        budget: IntArray,
        classify: (String?, String?) -> Surface?
    ): Surface? {
        if (node == null || depth > MAX_DEPTH || budget[0] <= 0) return null
        budget[0]--
        classify(node.viewIdResourceName, node.contentDescription?.toString())?.let { return it }
        for (i in 0 until node.childCount) {
            dfs(node.getChild(i), depth + 1, budget, classify)?.let { return it }
        }
        return null
    }

    private const val MAX_DEPTH = 40
    private const val MAX_NODES = 800
}
