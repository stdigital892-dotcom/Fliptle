package com.fliptle.app

import android.content.Context

/**
 * The set of domains the user chose to block, stored in SharedPreferences. A host
 * matches if it equals a blocked domain or is a subdomain of one (e.g.
 * "www.example.com" -> "example.com"). There are no built-in domains.
 *
 * The matching rules ([normalize], [hostMatches]) are pure (no Context), so they
 * are unit-tested directly — see DomainBlocklistTest.
 */
class DomainBlocklist(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("domain_blocklist", Context.MODE_PRIVATE)

    fun userDomains(): Set<String> =
        HashSet(prefs.getStringSet(KEY_DOMAINS, emptySet()) ?: emptySet())

    /** All active domains: the ones the user chose. */
    fun allDomains(): List<String> = userDomains().toList()

    fun add(input: String) {
        val domain = normalize(input)
        if (domain.isEmpty()) return
        val set = userDomains().toMutableSet()
        if (set.add(domain)) prefs.edit().putStringSet(KEY_DOMAINS, set).apply()
    }

    fun removeUser(domain: String) {
        val set = userDomains().toMutableSet()
        if (set.remove(domain)) prefs.edit().putStringSet(KEY_DOMAINS, set).apply()
    }

    /** Replace the user-added set (used when a commitment starts from the draft). */
    fun setUserDomains(domains: Set<String>) {
        prefs.edit().putStringSet(KEY_DOMAINS, HashSet(domains)).apply()
    }

    /** Merge in domains from a cloud backup (union — never drops existing blocks). */
    fun addAll(domains: Collection<String>) {
        val set = userDomains().toMutableSet()
        var changed = false
        for (d in domains) {
            val n = normalize(d)
            if (n.isNotEmpty() && set.add(n)) changed = true
        }
        if (changed) prefs.edit().putStringSet(KEY_DOMAINS, set).apply()
    }

    fun isBlocked(host: String): Boolean {
        val h = normalizeHost(host)
        return allDomains().any { d -> hostMatches(h, d) }
    }

    companion object {
        private const val KEY_DOMAINS = "domains"

        /** Lowercase, strip a trailing dot. For a host already extracted from a URL. */
        fun normalizeHost(host: String): String = host.lowercase().trimEnd('.')

        /** True if [host] equals [domain] or is a subdomain of it. Both must already be normalized. */
        fun hostMatches(host: String, domain: String): Boolean =
            host == domain || host.endsWith(".$domain")

        /**
         * Strip scheme/path/query/fragment/whitespace, lowercase, and drop a
         * leading "www.", leaving a bare registrable-ish host.
         *
         * The "www." strip matters: without it, typing "https://www.Instagram.com/"
         * would store "www.instagram.com", and [hostMatches] only matches a host
         * that equals the stored domain or is one of ITS subdomains —
         * "instagram.com" itself (and "m.instagram.com") would NOT be subdomains
         * of "www.instagram.com", so the bare domain would stay unblocked.
         * Storing the bare domain instead makes every variant (bare, www., m.,
         * any other subdomain) match via the subdomain check.
         */
        fun normalize(input: String): String {
            var d = input.trim().lowercase()
            if (d.contains("://")) d = d.substringAfter("://")
            d = d.substringBefore("/").substringBefore("?").substringBefore("#")
            d = d.trimEnd('.')
            if (d.startsWith("www.")) d = d.removePrefix("www.")
            return d
        }
    }
}
