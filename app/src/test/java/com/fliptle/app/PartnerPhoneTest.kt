package com.fliptle.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** D1: the shared partner-number validation rules, app side. */
class PartnerPhoneTest {

    @Test fun validNumbersPass() {
        assertEquals("919812345670", PartnerPhone.normalizedIfValid("9812345670"))
        assertEquals("917001234567", PartnerPhone.normalizedIfValid("7001234567"))
    }

    @Test fun normalizationCases() {
        assertEquals("919812345670", PartnerPhone.normalizedIfValid("+91 98123 45670"))
        assertEquals("919812345670", PartnerPhone.normalizedIfValid("098123 45670"))
        assertEquals("919812345670", PartnerPhone.normalizedIfValid("919812345670"))
    }

    @Test fun rejectsWrongStartDigit() {
        assertNull(PartnerPhone.normalizedIfValid("5812345670"))
    }

    @Test fun rejectsAllSameDigit() {
        assertNull(PartnerPhone.normalizedIfValid("9999999999"))
    }

    @Test fun rejectsAscendingOrDescendingRun() {
        assertNull(PartnerPhone.normalizedIfValid("9876543210"))
    }

    @Test fun rejectsFewerThanFourDistinctDigits() {
        assertNull(PartnerPhone.normalizedIfValid("9898989898"))
    }

    @Test fun rejectsNonDigitsOrWrongLength() {
        assertNull(PartnerPhone.normalizedIfValid("98123abcde"))
        assertNull(PartnerPhone.normalizedIfValid("981234567"))
    }
}
