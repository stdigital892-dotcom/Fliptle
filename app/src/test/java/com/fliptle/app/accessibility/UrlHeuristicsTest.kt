package com.fliptle.app.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure URL heuristics behind the myntra.com false-"not blocked" fix:
 * looksLikeUrl()'s tightened hostname shape, and decideOverlay()'s
 * confident-vs-fallback rule for clearing the block overlay.
 */
class UrlHeuristicsTest {

    @Test fun looksLikeUrl_acceptsRealHostnames() {
        assertTrue(UrlBlockAccessibilityService.looksLikeUrl("myntra.com"))
        assertTrue(UrlBlockAccessibilityService.looksLikeUrl("https://www.myntra.com/men"))
        assertTrue(UrlBlockAccessibilityService.looksLikeUrl("m.reddit.com"))
    }

    @Test fun looksLikeUrl_rejectsNonHostnameText() {
        assertFalse(UrlBlockAccessibilityService.looksLikeUrl("4.5"))
        assertFalse(UrlBlockAccessibilityService.looksLikeUrl("Rs.499"))
        assertFalse(UrlBlockAccessibilityService.looksLikeUrl("50%.off"))
        assertFalse(UrlBlockAccessibilityService.looksLikeUrl("e.g."))
        assertFalse(UrlBlockAccessibilityService.looksLikeUrl("v1.2.3"))
    }

    @Test fun looksLikeUrl_rejectsTextWithSpacesOrTooLong() {
        assertFalse(UrlBlockAccessibilityService.looksLikeUrl("myntra.com has a sale"))
        assertFalse(UrlBlockAccessibilityService.looksLikeUrl("a.co" + "x".repeat(2048)))
        assertFalse(UrlBlockAccessibilityService.looksLikeUrl(""))
    }

    @Test fun decideOverlay_blockedAlwaysShows_confidentOrNot() {
        assertEquals(true, UrlBlockAccessibilityService.decideOverlay(confident = true, blocked = true))
        assertEquals(true, UrlBlockAccessibilityService.decideOverlay(confident = false, blocked = true))
    }

    @Test fun decideOverlay_confidentNotBlocked_clears() {
        assertEquals(false, UrlBlockAccessibilityService.decideOverlay(confident = true, blocked = false))
    }

    @Test fun decideOverlay_fallbackNotBlocked_leavesStateAlone() {
        // The myntra.com case: a fallback guess found nothing blocked -- this
        // must NOT be read as proof the blocked page is gone.
        assertNull(UrlBlockAccessibilityService.decideOverlay(confident = false, blocked = false))
    }
}
