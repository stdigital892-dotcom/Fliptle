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
}
