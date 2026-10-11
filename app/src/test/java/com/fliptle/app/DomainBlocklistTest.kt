package com.fliptle.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The matcher and normaliser behind custom domain blocking (B1). */
class DomainBlocklistTest {

    private fun blocked(host: String, domain: String): Boolean =
        DomainBlocklist.hostMatches(DomainBlocklist.normalizeHost(host), DomainBlocklist.normalize(domain))

    @Test fun bareDomainSaved_blocksEveryRequiredVariant() {
        val saved = "instagram.com"
        assertTrue(blocked("instagram.com", saved))
        assertTrue(blocked("www.instagram.com", saved))
        assertTrue(blocked("m.instagram.com", saved))
        assertTrue(blocked("INSTAGRAM.com", saved))
    }

    @Test fun typedWithSchemeAndTrailingSlash_normalizesToTheBareDomain() {
        assertEquals("instagram.com", DomainBlocklist.normalize("https://instagram.com/path?x=1"))
        assertEquals("instagram.com", DomainBlocklist.normalize("INSTAGRAM.com"))
        assertEquals("instagram.com", DomainBlocklist.normalize("https://www.Instagram.com/"))
    }

    @Test fun savingTheWwwSpellingStillBlocksTheBareDomainAndOtherSubdomains() {
        // The root-cause case: a user pastes "https://www.Instagram.com/".
        val saved = DomainBlocklist.normalize("https://www.Instagram.com/")
        assertEquals("instagram.com", saved)
        assertTrue(blocked("instagram.com", saved))
        assertTrue(blocked("www.instagram.com", saved))
        assertTrue(blocked("m.instagram.com", saved))
    }

    @Test fun subdomainsOfABlockedDomainBlock() {
        assertTrue(blocked("checkout.instagram.com", "instagram.com"))
        assertTrue(blocked("a.b.instagram.com", "instagram.com"))
    }

    @Test fun aSimilarButDifferentDomainIsNotBlocked() {
        assertFalse(blocked("notinstagram.com", "instagram.com"))
        assertFalse(blocked("instagram.com.evil.com", "instagram.com"))
        assertFalse(blocked("instagram.co", "instagram.com"))
    }

    @Test fun queryAndFragmentAndWhitespaceAreStripped() {
        assertEquals("instagram.com", DomainBlocklist.normalize("  instagram.com?x=1  "))
        assertEquals("instagram.com", DomainBlocklist.normalize("instagram.com#frag"))
        assertEquals("instagram.com", DomainBlocklist.normalize("instagram.com."))
    }

    /**
     * userDomains() re-normalizes every stored entry on read, so a domain a
     * pre-fix build stored raw (e.g. "www.instagram.com", before the "www."
     * strip existed) ends up matching exactly like a freshly-saved one. This
     * models that read-time transform directly, since userDomains() itself
     * needs a real Context/SharedPreferences.
     */
    private fun reread(storedRaw: Set<String>): Set<String> =
        storedRaw.mapNotNull { DomainBlocklist.normalize(it).ifEmpty { null } }.toSet()

    @Test fun legacyStoredEntries_areNormalizedOnRead() {
        val stored = setOf("www.instagram.com", "INSTAGRAM.CO.UK", "https://m.example.com/")
        val reread = reread(stored)
        assertTrue(reread.contains("instagram.com"))
        assertTrue(reread.contains("instagram.co.uk"))
        assertTrue(reread.contains("m.example.com"))

        // And the re-normalized value now blocks the bare domain, which the
        // raw "www.instagram.com" string on its own never would have.
        assertTrue(blocked("instagram.com", reread.first { it == "instagram.com" }))
    }
}
