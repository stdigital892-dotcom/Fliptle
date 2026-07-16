package com.fliptle.app

/**
 * Parses hosts-file format ("0.0.0.0 example.com" / "127.0.0.1 example.com")
 * into clean domain names. Comments, blank lines, and non-domain placeholders
 * are skipped. Used by the network updater; the bundled asset is already clean.
 */
object HostsParser {

    private val PLACEHOLDERS = setOf(
        "localhost", "localhost.localdomain", "local",
        "broadcasthost", "ip6-localhost", "ip6-loopback", "0.0.0.0"
    )

    /** Parse one line; returns a bare domain or null if the line has none. */
    fun parse(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return null

        // Strip inline comments.
        val noComment = trimmed.substringBefore('#').trim()
        if (noComment.isEmpty()) return null

        val tokens = noComment.split(Regex("\\s+"))
        // "IP domain" -> take the domain; a bare "domain" line -> take it.
        val host = when {
            tokens.size >= 2 -> tokens[1]
            tokens.size == 1 -> tokens[0]
            else -> return null
        }.lowercase().trimEnd('.')

        if (host.isEmpty() || host in PLACEHOLDERS) return null
        if (host.startsWith("ip6-")) return null
        // A real domain must contain a dot and no illegal chars.
        if (!host.contains('.') || host.contains('/')) return null
        return host
    }

    fun parseAll(lines: Sequence<String>): HashSet<String> {
        val out = HashSet<String>()
        for (line in lines) parse(line)?.let { out.add(it) }
        return out
    }
}
