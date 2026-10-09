package com.fliptle.app.auth

/** Why a Google sign-in attempt did not finish, reduced to what the screen says. */
enum class SignInFailure {
    /** The user closed the account picker. Calm message, no error. */
    CANCELLED,
    /** No usable network. */
    NO_INTERNET,
    /** Anything else. The user sees one generic line; the details go to the log. */
    OTHER
}

/**
 * Maps the ways a Google / Firebase sign-in can fail onto [SignInFailure].
 * Free of Android classes so it can be unit-tested; the screen passes in plain
 * values. The raw exception text is never shown to the user.
 */
object GoogleSignInOutcome {

    // GoogleSignInStatusCodes values (com.google.android.gms.auth.api.signin).
    const val CODE_NETWORK_ERROR = 7
    const val CODE_TIMEOUT = 15
    const val CODE_SIGN_IN_CANCELLED = 12501

    /** From an ApiException thrown by the Google account picker result. */
    fun fromGoogleStatusCode(statusCode: Int): SignInFailure = when (statusCode) {
        CODE_SIGN_IN_CANCELLED -> SignInFailure.CANCELLED
        CODE_NETWORK_ERROR, CODE_TIMEOUT -> SignInFailure.NO_INTERNET
        else -> SignInFailure.OTHER
    }

    /** From a failed Firebase signInWithCredential; [isNetworkException] = it was a FirebaseNetworkException. */
    fun fromFirebase(isNetworkException: Boolean): SignInFailure =
        if (isNetworkException) SignInFailure.NO_INTERNET else SignInFailure.OTHER

    /** The picker returned RESULT_CANCELED with no data: the user backed out. */
    fun fromPickerResult(resultCanceled: Boolean, hasData: Boolean): SignInFailure? =
        if (resultCanceled && !hasData) SignInFailure.CANCELLED else null
}

/**
 * One sign-in attempt at a time. [tryStart] refuses a second tap while one is in
 * flight; every way an attempt can end (success, cancel, failure) calls [end], so
 * the button can never stay disabled or the spinner stay up.
 */
class SignInAttempt {
    var inFlight: Boolean = false
        private set

    /** True if a new attempt began; false if one is already running. */
    fun tryStart(): Boolean {
        if (inFlight) return false
        inFlight = true
        return true
    }

    fun end() {
        inFlight = false
    }
}
