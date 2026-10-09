package com.fliptle.app.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which name the (optional) name step suggests: typed, then saved, then Google's. */
class NamePrefillTest {

    @Test fun googleName_isSuggested_whenNothingElseIsKnown() {
        assertEquals("Asha Rao", NamePrefill.choose(null, null, "Asha Rao"))
    }

    @Test fun savedName_beatsTheGoogleName() {
        assertEquals("Asha", NamePrefill.choose(null, "Asha", "Asha Rao"))
    }

    @Test fun whatIsAlreadyTyped_beatsEverything() {
        assertEquals("Ash", NamePrefill.choose("Ash", "Asha", "Asha Rao"))
    }

    @Test fun blankValues_areSkipped() {
        assertEquals("Asha Rao", NamePrefill.choose("   ", "", "Asha Rao"))
        assertEquals("Asha", NamePrefill.choose(null, "  Asha  ", null))
    }

    @Test fun nothingToSuggest_leavesTheFieldBlank_soBlankStillSkips() {
        assertNull(NamePrefill.choose(null, null, null))
        assertNull(NamePrefill.choose("", " ", "\t"))
    }

    @Test fun nameIsTrimmed() {
        assertEquals("Asha Rao", NamePrefill.choose(null, null, "  Asha Rao \n"))
    }

    @Test fun longNameIsCappedAtTheFieldLimit() {
        val long = "A".repeat(80)
        val picked = NamePrefill.choose(null, null, long)!!
        assertEquals(NamePrefill.MAX_LENGTH, picked.length)
        assertEquals("A".repeat(NamePrefill.MAX_LENGTH), picked)
    }

    @Test fun limitMatchesTheScreensValidation() {
        assertEquals(40, NamePrefill.MAX_LENGTH)
    }
}
