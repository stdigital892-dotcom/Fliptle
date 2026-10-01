package com.fliptle.app

import android.content.Context

/**
 * The set of domains the user chose to block, stored in SharedPreferences. A host
 * matches if it equals a blocked domain or is a subdomain of one (e.g.
 * "www.example.com" -> "example.com"). There are no built-in domains.
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
        val h = host.lowercase().trimEnd('.')
        return allDomains().any { d -> h == d || h.endsWith(".$d") }
    }

    /** Strip scheme/path/whitespace and lowercase, leaving a bare host. */
    fun normalize(input: String): String {
        var d = input.trim().lowercase()
        if (d.contains("://")) d = d.substringAfter("://")
        d = d.substringBefore("/").substringBefore("?")
        return d.trimEnd('.')
    }

    companion object {
        private const val KEY_DOMAINS = "domains"
    }
}
