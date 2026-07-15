package com.test.hello.accessibility

import android.net.Uri

/**
 * Safe-search enforcement for the major search engines.
 *
 * Given a URL, it either:
 *   - reports the query should be BLOCKED (it contains a banned keyword), or
 *   - reports a REDIRECT to the same search with the engine's safe-search
 *     parameter forced on, or
 *   - reports NONE (not a search / already safe).
 *
 * This module is self-contained: it has no Android dependencies beyond Uri.
 */
object SafeSearch {

    // Sample keyword list. Queries containing any of these are blocked outright.
    val KEYWORDS: Set<String> = setOf("porn", "xxx", "nsfw", "explicit")

    private enum class Engine(val safeParam: String, val safeValue: String) {
        GOOGLE("safe", "active"),
        BING("adlt", "strict"),
        DDG("kp", "1")
    }

    sealed class Result {
        object None : Result()
        object BlockKeyword : Result()
        data class Redirect(val url: String) : Result()
    }

    fun evaluate(rawUrl: String): Result {
        val uri = toUri(rawUrl) ?: return Result.None
        val host = uri.host?.lowercase() ?: return Result.None
        val engine = when {
            host.contains("google.") -> Engine.GOOGLE
            host.contains("bing.com") -> Engine.BING
            host.contains("duckduckgo.com") -> Engine.DDG
            else -> return Result.None
        }

        val query = (uri.getQueryParameter("q") ?: uri.getQueryParameter("p") ?: "").lowercase()
        val isSearch = query.isNotBlank() || (uri.path?.contains("search") == true)
        if (!isSearch) return Result.None

        if (query.isNotBlank() && KEYWORDS.any { query.contains(it) }) {
            return Result.BlockKeyword
        }

        val alreadySafe = uri.getQueryParameter(engine.safeParam)?.equals(engine.safeValue, true) == true
        if (alreadySafe) return Result.None

        val safeUrl = uri.buildUpon()
            .appendQueryParameter(engine.safeParam, engine.safeValue)
            .build()
            .toString()
        return Result.Redirect(safeUrl)
    }

    private fun toUri(rawUrl: String): Uri? = try {
        val withScheme = if (rawUrl.contains("://")) rawUrl else "https://$rawUrl"
        Uri.parse(withScheme)
    } catch (_: Exception) {
        null
    }
}
