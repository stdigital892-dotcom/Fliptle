package com.test.hello

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Shared adult-content domain blocklist used by BOTH the VPN DNS filter and the
 * Accessibility URL blocker. Held in a single in-memory HashSet (process-wide
 * singleton) for O(number-of-labels) lookups — never a linear scan.
 *
 * Data lifecycle:
 *   - First run: seeded from the bundled asset (assets/adult_domains.txt), a
 *     large porn-only list (StevenBlack porn-only), so it works offline instantly.
 *   - Updates: [updateFromNetwork] fetches the latest hosts file, parses it,
 *     merges (union) with the current set, and persists to internal storage.
 *   - Later runs: loaded from the persisted internal copy if present.
 *
 * If the network is unavailable the app keeps using the last known list.
 */
object AdultBlocklist {

    const val SOURCE_URL =
        "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn-only/hosts"

    private const val ASSET_NAME = "adult_domains.txt"
    private const val STORAGE_NAME = "adult_domains.txt"
    private const val PREFS = "adult_blocklist"
    private const val KEY_LAST_UPDATED = "last_updated"

    @Volatile private var domains: Set<String> = emptySet()
    @Volatile var count: Int = 0
        private set
    @Volatile private var loaded = false
    private val lock = Any()

    /** Kick off a one-time async load (safe to call from a service's onCreate). */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        val app = context.applicationContext
        Thread { loadBlocking(app) }.start()
    }

    /** Load synchronously if needed; returns the domain count. */
    fun loadBlocking(context: Context): Int {
        synchronized(lock) {
            if (!loaded) {
                val set = readFromStorage(context.applicationContext)
                domains = set
                count = set.size
                loaded = true
            }
            return count
        }
    }

    /**
     * True if [host] or any of its parent domains is blocked. Checks the full
     * host then strips one leftmost label at a time, so "cdn.example.com" and
     * "www.example.com" both match a blocked "example.com". O(labels) lookups.
     */
    fun isBlocked(host: String): Boolean {
        val set = domains
        if (set.isEmpty()) return false
        var h = host.lowercase().trimEnd('.')
        while (h.isNotEmpty()) {
            if (set.contains(h)) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
        }
        return false
    }

    fun lastUpdated(context: Context): Long =
        prefs(context).getLong(KEY_LAST_UPDATED, 0L)

    /**
     * Fetch the latest list, merge it in, and persist. Returns the new domain
     * count, or null on failure (in which case the existing list is untouched).
     */
    fun updateFromNetwork(context: Context): Int? {
        val app = context.applicationContext
        return try {
            val connection = (URL(SOURCE_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                requestMethod = "GET"
            }
            val fetched = connection.inputStream.bufferedReader().use { reader ->
                HostsParser.parseAll(reader.lineSequence())
            }
            if (fetched.isEmpty()) return null

            synchronized(lock) {
                if (!loaded) {
                    domains = readFromStorage(app)
                    loaded = true
                }
                val merged = HashSet(domains).apply { addAll(fetched) }
                writeToStorage(app, merged)
                domains = merged
                count = merged.size
            }
            prefs(app).edit().putLong(KEY_LAST_UPDATED, System.currentTimeMillis()).apply()
            count
        } catch (_: Exception) {
            null
        }
    }

    private fun readFromStorage(context: Context): HashSet<String> {
        val internal = File(context.filesDir, STORAGE_NAME)
        val set = HashSet<String>(80_000)
        try {
            if (internal.exists()) {
                internal.bufferedReader().useLines { lines -> addDomains(lines, set) }
            } else {
                context.assets.open(ASSET_NAME).bufferedReader().useLines { lines ->
                    addDomains(lines, set)
                }
            }
        } catch (_: Exception) {
            // Leave whatever was read so far; empty set just means "nothing blocked".
        }
        return set
    }

    private fun addDomains(lines: Sequence<String>, set: HashSet<String>) {
        for (line in lines) {
            val d = line.trim()
            if (d.isNotEmpty() && !d.startsWith("#")) set.add(d.lowercase())
        }
    }

    private fun writeToStorage(context: Context, set: Set<String>) {
        val internal = File(context.filesDir, STORAGE_NAME)
        internal.bufferedWriter().use { writer ->
            for (d in set) {
                writer.write(d)
                writer.newLine()
            }
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
