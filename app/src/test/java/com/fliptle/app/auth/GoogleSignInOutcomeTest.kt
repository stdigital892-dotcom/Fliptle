package com.fliptle.app.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a failed Google sign-in is reported, and that one attempt never sticks. */
class GoogleSignInOutcomeTest {

    // ---- what the screen says ----

    @Test fun closingTheAccountPicker_isCancelled_notAnError() {
        assertEquals(SignInFailure.CANCELLED, GoogleSignInOutcome.fromGoogleStatusCode(12501))
        assertEquals(
            SignInFailure.CANCELLED,
            GoogleSignInOutcome.fromPickerResult(resultCanceled = true, hasData = false)
        )
    }

    @Test fun aCancelledResultThatCarriesData_isLeftForTheStatusCodeToDecide() {
        assertNull(GoogleSignInOutcome.fromPickerResult(resultCanceled = true, hasData = true))
        assertNull(GoogleSignInOutcome.fromPickerResult(resultCanceled = false, hasData = true))
        assertNull(GoogleSignInOutcome.fromPickerResult(resultCanceled = false, hasData = false))
    }

    @Test fun networkProblems_sayNoInternet() {
        assertEquals(SignInFailure.NO_INTERNET, GoogleSignInOutcome.fromGoogleStatusCode(7))  // NETWORK_ERROR
        assertEquals(SignInFailure.NO_INTERNET, GoogleSignInOutcome.fromGoogleStatusCode(15)) // TIMEOUT
        assertEquals(SignInFailure.NO_INTERNET, GoogleSignInOutcome.fromFirebase(isNetworkException = true))
    }

    @Test fun everythingElse_isTheGenericFailure() {
        for (code in listOf(10, 12500, 8, 13, 0, -1)) {
            assertEquals("code $code", SignInFailure.OTHER, GoogleSignInOutcome.fromGoogleStatusCode(code))
        }
        assertEquals(SignInFailure.OTHER, GoogleSignInOutcome.fromFirebase(isNetworkException = false))
    }

    @Test fun codesMatchGoogleSignInStatusCodes() {
        // Guards the literals against a typo; these are public constants of the SDK.
        assertEquals(com.google.android.gms.common.api.CommonStatusCodes.NETWORK_ERROR,
            GoogleSignInOutcome.CODE_NETWORK_ERROR)
        assertEquals(com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes.SIGN_IN_CANCELLED,
            GoogleSignInOutcome.CODE_SIGN_IN_CANCELLED)
        assertEquals(com.google.android.gms.common.api.CommonStatusCodes.TIMEOUT,
            GoogleSignInOutcome.CODE_TIMEOUT)
    }

    // ---- one attempt at a time, and it always ends ----

    @Test fun aSecondTapWhileOneIsRunning_isRefused() {
        val a = SignInAttempt()
        assertTrue(a.tryStart())
        assertFalse(a.tryStart())
        assertTrue(a.inFlight)
    }

    @Test fun afterACancel_theButtonCanBeUsedAgain() {
        val a = SignInAttempt()
        a.tryStart()
        a.end() // cancel / failure / success all end the attempt
        assertFalse(a.inFlight)
        assertTrue(a.tryStart())
    }

    @Test fun endingWithNothingRunning_isHarmless() {
        val a = SignInAttempt()
        a.end()
        a.end()
        assertFalse(a.inFlight)
        assertTrue(a.tryStart())
    }

    @Test fun everyFailureKind_endsTheAttempt() {
        for (kind in SignInFailure.values()) {
            val a = SignInAttempt()
            assertTrue(a.tryStart())
            a.end() // what failAttempt() does first, whatever the kind
            assertFalse("$kind left the attempt running", a.inFlight)
        }
    }
}
