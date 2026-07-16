package com.fliptle.app

import android.content.Context

/**
 * The set of domains to block via DNS. Combines a small hardcoded test list with
 * user-added domains stored in SharedPreferences. A host matches if it equals a
 * blocked domain or is a subdomain of one (e.g. "www.example.com" -> "example.com").
 */
class DomainBlocklist(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("domain_blocklist", Context.MODE_PRIVATE)

    fun userDomains(): Set<String> =
        HashSet(prefs.getStringSet(KEY_DOMAINS, emptySet()) ?: emptySet())

    /** All active domains: built-in test list plus user-added. */
    fun allDomains(): List<String> = (DEFAULT + userDomains()).distinct()

    fun isBuiltIn(domain: String): Boolean = DEFAULT.contains(domain)

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

        // Small hardcoded test list. example.com is ideal for testing because it
        // is a real, stable site that is easy to confirm as "blocked".
        val DEFAULT: List<String> = listOf("example.com", "neverssl.com")
    }
}
