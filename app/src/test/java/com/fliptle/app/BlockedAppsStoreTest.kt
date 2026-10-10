package com.fliptle.app

import com.fliptle.app.accessibility.SurfaceDetector
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Instagram and YouTube must never be whole-app-blockable (B2): they have their
 * own surface toggle instead. [BlockedAppsStore.get]/[set] filter on this set;
 * the filtering itself needs a real Context (SharedPreferences) so it isn't
 * exercised here, but this pins down exactly which packages are excluded.
 */
class BlockedAppsStoreTest {

    @Test fun excludedSetIsExactlyTheSurfaceApps() {
        assertEquals(setOf(SurfaceDetector.IG_PKG, SurfaceDetector.YT_PKG), BlockedAppsStore.EXCLUDED)
    }

    @Test fun surfaceAppPackageNamesAreWhatWeExpect() {
        assertEquals("com.instagram.android", SurfaceDetector.IG_PKG)
    }
}
