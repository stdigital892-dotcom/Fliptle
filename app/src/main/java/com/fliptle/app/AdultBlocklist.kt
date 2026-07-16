package com.fliptle.app

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Shared adult-content domain blocklist merged from MULTIPLE public sources
 * (adult category only — no ads/tracking/malware/gambling/social). Used by BOTH
 * the VPN DNS filter and the Accessibility URL blocker.
 *
 * All sources are merged into ONE deduplicated in-memory HashSet for
 * O(number-of-labels) lookups. Each source is persisted separately so a weekly
 * update can refresh sources independently:
 *   - First run: each source seeded from its bundled asset (blocklist/<id>.txt).
 *   - Update: fetch each source; on success replace that source's stored copy,
 *     on failure keep the previous copy. The merged set is then rebuilt. A failed
 *     source never wipes the list — the last known good data is always retained.
 */
object AdultBlocklist {

    data class Source(val id: String, val name: String, val url: String)

    val SOURCES: List<Source> = listOf(
        Source(
            "stevenblack", "StevenBlack porn-only",
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn-only/hosts"
        ),
        Source(
            "sinfonietta", "Sinfonietta pornography",
            "https://raw.githubusercontent.com/Sinfonietta/hostfiles/master/pornography-hosts"
        ),
        Source(
            "mhhakim", "mhhakim porn",
            "https://raw.githubusercontent.com/mhhakim/pihole-blocklist/master/porn.txt"
        ),
        Source(
            "tiuxo", "tiuxo porn",
            "https://raw.githubusercontent.com/tiuxo/hosts/master/porn"
        )
    )

    data class UpdateResult(
        val mergedCount: Int,
        val succeeded: List<String>,
        val failed: List<String>
    )

    private const val PREFS = "adult_blocklist"
    private const val KEY_LAST_UPDATED = "last_updated"
    private const val STORAGE_DIR = "blocklists"
    private const val ASSET_DIR = "blocklist"

    @Volatile private var domains: Set<String> = emptySet()
    @Volatile var count: Int = 0
        private set
    @Volatile var sourceCounts: Map<String, Int> = emptyMap()
        private set
    @Volatile private var loaded = false
    private val lock = Any()

    fun ensureLoaded(context: Context) {
        if (loaded) return
        val app = context.applicationContext
        Thread { loadBlocking(app) }.start()
    }

    fun loadBlocking(context: Context): Int {
        val app = context.applicationContext
        synchronized(lock) {
            if (!loaded) {
                rebuildMaster(app)
                loaded = true
            }
            return count
        }
    }

    /**
     * True if [host] or any parent domain is blocked. Checks the full host, then
     * strips one leftmost label at a time, so subdomains match. O(labels).
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
     * Fetch every source, refresh the ones that succeed, keep the rest, then
     * rebuild the merged set. Never wipes: a source that fails to fetch retains
     * its previous stored (or bundled) copy.
     */
    fun updateFromNetwork(context: Context): UpdateResult {
        val app = context.applicationContext
        synchronized(lock) {
            if (!loaded) {
                rebuildMaster(app)
                loaded = true
            }
            val succeeded = ArrayList<String>()
            val failed = ArrayList<String>()
            for (src in SOURCES) {
                val fetched = fetchAndParse(src.url)
                if (fetched != null && fetched.isNotEmpty()) {
                    writeSourceFile(app, src.id, fetched)
                    succeeded.add(src.id)
                } else {
                    failed.add(src.id)
                }
            }
            rebuildMaster(app)
            prefs(app).edit().putLong(KEY_LAST_UPDATED, System.currentTimeMillis()).apply()
            return UpdateResult(count, succeeded, failed)
        }
    }

    // ---- internals -----------------------------------------------------------

    private fun rebuildMaster(context: Context) {
        val master = HashSet<String>(300_000)
        val counts = LinkedHashMap<String, Int>()
        for (src in SOURCES) {
            val set = readSource(context, src.id)
            counts[src.id] = set.size
            master.addAll(set)
        }
        domains = master
        count = master.size
        sourceCounts = counts
    }

    /** Read a source from its persisted internal copy, else the bundled asset. */
    private fun readSource(context: Context, id: String): HashSet<String> {
        val set = HashSet<String>()
        val internal = File(File(context.filesDir, STORAGE_DIR), "$id.txt")
        try {
            val reader = if (internal.exists()) {
                internal.bufferedReader()
            } else {
                context.assets.open("$ASSET_DIR/$id.txt").bufferedReader()
            }
            reader.useLines { lines ->
                for (line in lines) {
                    val d = line.trim()
                    if (d.isNotEmpty() && !d.startsWith("#")) set.add(d.lowercase())
                }
            }
        } catch (_: Exception) {
            // Return whatever was read; a missing source just contributes nothing.
        }
        return set
    }

    private fun fetchAndParse(url: String): HashSet<String>? = try {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "GET"
        }
        connection.inputStream.bufferedReader().use { reader ->
            HostsParser.parseAll(reader.lineSequence())
        }
    } catch (_: Exception) {
        null
    }

    private fun writeSourceFile(context: Context, id: String, set: Set<String>) {
        val dir = File(context.filesDir, STORAGE_DIR).apply { mkdirs() }
        File(dir, "$id.txt").bufferedWriter().use { writer ->
            for (d in set) {
                writer.write(d)
                writer.newLine()
            }
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
