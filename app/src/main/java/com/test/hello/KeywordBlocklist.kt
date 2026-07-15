package com.test.hello

import android.content.Context

/**
 * Separate keyword layer for search queries. A search whose text contains any of
 * these terms is blocked outright. Loaded from assets/blocked_keywords.txt, with
 * a hardcoded fallback so it works even before the asset is read.
 */
object KeywordBlocklist {

    private const val ASSET_NAME = "blocked_keywords.txt"

    private val FALLBACK = setOf("porn", "xxx", "nsfw", "hentai", "explicit")

    @Volatile private var keywords: Set<String> = FALLBACK
    @Volatile var count: Int = FALLBACK.size
        private set
    @Volatile private var loaded = false
    private val lock = Any()

    fun ensureLoaded(context: Context) {
        if (loaded) return
        val app = context.applicationContext
        synchronized(lock) {
            if (loaded) return
            val set = try {
                app.assets.open(ASSET_NAME).bufferedReader().useLines { lines ->
                    lines.map { it.trim().lowercase() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                        .toHashSet()
                }
            } catch (_: Exception) {
                HashSet(FALLBACK)
            }
            if (set.isNotEmpty()) {
                keywords = set
                count = set.size
            }
            loaded = true
        }
    }

    /** True if the query contains any blocked keyword (case-insensitive). */
    fun matches(query: String): Boolean {
        val q = query.lowercase()
        return keywords.any { q.contains(it) }
    }
}
