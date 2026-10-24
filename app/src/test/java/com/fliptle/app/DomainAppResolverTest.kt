package com.fliptle.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure parts of the domain -> app resolver: the static fallback table and
 * the exclusion predicate ([DomainAppResolver.isBlockable]). The PackageManager
 * query itself ([DomainAppResolver.resolveApps]) needs a real Context and is
 * not covered here -- see the manual checklist in the PR/commit notes.
 */
class DomainAppResolverTest {

    @Test fun ownPackage_isNeverBlockable() {
        assertFalse(DomainAppResolver.isBlockable("com.fliptle.app", "com.fliptle.app", isBrowserPkg = false))
    }

    @Test fun aBrowser_isNeverBlockableThroughThisPath() {
        assertFalse(DomainAppResolver.isBlockable("com.android.chrome", "com.fliptle.app", isBrowserPkg = true))
    }

    @Test fun aProtectedSystemPackage_isNeverBlockable() {
        assertFalse(DomainAppResolver.isBlockable("com.android.settings", "com.fliptle.app", isBrowserPkg = false))
        assertFalse(DomainAppResolver.isBlockable("com.android.dialer", "com.fliptle.app", isBrowserPkg = false))
        assertFalse(DomainAppResolver.isBlockable("com.android.vending", "com.fliptle.app", isBrowserPkg = false))
    }

    @Test fun anOrdinaryThirdPartyApp_isBlockable() {
        assertTrue(DomainAppResolver.isBlockable("com.myntra.android", "com.fliptle.app", isBrowserPkg = false))
    }

    @Test fun fallbackTable_coversTheRequestedDomains() {
        val table = DomainAppResolver.STATIC_FALLBACK
        assertTrue(table["instagram.com"]!!.contains("com.instagram.android"))
        assertTrue(table["youtube.com"]!!.contains("com.google.android.youtube"))
        assertTrue(table["youtu.be"]!!.contains("com.google.android.youtube"))
        assertTrue(table["reddit.com"]!!.contains("com.reddit.frontpage"))
        assertTrue(table["myntra.com"]!!.contains("com.myntra.android"))
        assertTrue(table["flipkart.com"]!!.contains("com.flipkart.android"))
        assertTrue(table["amazon.in"]!!.contains("in.amazon.mShop.android.shopping"))
        assertTrue(table["swiggy.com"]!!.contains("in.swiggy.android"))
    }

    /** D3/D4: an EXCLUDED app (Instagram/YouTube) must still show up for its
     *  own domain, since BlockedAppsStore.EXCLUDED is never consulted here --
     *  BlockingService's domain-app check is a separate path that bypasses it. */
    @Test fun instagramAndYouTube_areInTheirOwnDomainsFallback_despiteBeingExcludedFromWholeAppBlocking() {
        assertTrue(BlockedAppsStore.EXCLUDED.contains("com.instagram.android"))
        assertTrue(DomainAppResolver.STATIC_FALLBACK["instagram.com"]!!.contains("com.instagram.android"))
        assertTrue(BlockedAppsStore.EXCLUDED.contains("com.google.android.youtube"))
        assertTrue(DomainAppResolver.STATIC_FALLBACK["youtube.com"]!!.contains("com.google.android.youtube"))
    }

    @Test fun protectedPackages_coverTheRequestedCategories() {
        val p = DomainAppResolver.PROTECTED_PACKAGES
        assertTrue(p.contains("com.android.settings"))       // Settings
        assertTrue(p.contains("com.android.dialer"))          // Phone/Dialer
        assertTrue(p.contains("com.android.mms"))             // Messages
        assertTrue(p.contains("com.android.vending"))         // Play Store
        assertTrue(p.contains("com.google.android.gms"))      // Play Services
        assertTrue(p.contains("com.android.systemui"))        // System UI
        assertTrue(p.contains("com.android.launcher3"))       // launcher
        assertTrue(p.contains("com.google.android.gm"))       // Gmail
        assertTrue(p.contains("com.google.android.apps.maps")) // Maps
        assertTrue(p.contains("com.android.camera2"))         // Camera
        assertTrue(p.contains("com.android.contacts"))        // Contacts
        assertTrue(p.contains("com.android.deskclock"))       // Clock
    }
}
