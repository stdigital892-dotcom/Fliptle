package com.fliptle.app.auth

import android.content.Context
import android.util.Log
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException

/**
 * The paywall gate. Calls the `getEntitlement` Cloud Function and mirrors the
 * result into [EntitlementStore]. Every callsite reads the store, never this
 * class directly — this only writes to the store.
 *
 * ## Safeguards (critical)
 *
 * A network error, timeout, or server-side failure MUST NOT count as "not
 * entitled". Only an explicit `{entitled: false}` response from the server
 * lands in the store as [EntitlementStore.State.DENIED]. Everything else —
 * offline, DNS failure, HTTP 5xx, timeout, function crash, missing auth —
 * leaves the last known state untouched. If the user was entitled the last
 * time we successfully checked, they stay entitled until the server says
 * otherwise.
 *
 * ## Auth
 *
 * The Cloud Function requires Firebase Auth and reads the email from the
 * caller's ID token; this class does not send an email. Signed-out users
 * simply get [Result.Unauthenticated] and the store is untouched.
 */
object EntitlementGate {

    private const val TAG = "EntitlementGate"
    private const val REGION = "asia-south2"

    /** The result of a single [check] call. Only [Entitled] and [Denied] are
     *  explicit server responses — anything else leaves the local store
     *  unchanged and the caller should keep the last known state. */
    sealed class Result {
        data class Entitled(val reason: String, val expiresAtMs: Long?) : Result()
        data class Denied(val reason: String) : Result()
        object Unauthenticated : Result()
        data class TransientError(val message: String) : Result()
    }

    /**
     * One-shot entitlement check. Fires and forgets, invoking [onResult] on the
     * main thread. Safe to call from onCreate/onResume; the callback is
     * lifecycle-agnostic — the caller is responsible for not touching a
     * destroyed view.
     */
    fun check(context: Context, onResult: (Result) -> Unit) {
        if (!FirebaseGate.isAvailable(context)) {
            // Firebase not configured (dev build without google-services.json).
            // Treat like any other transient failure — do NOT flip the store.
            onResult(Result.TransientError("Firebase not configured"))
            return
        }
        val functions = FirebaseFunctions.getInstance(REGION)
        val store = EntitlementStore(context.applicationContext)

        functions.getHttpsCallable("getEntitlement")
            .call() // no args — server reads email from auth token
            .addOnSuccessListener { httpsResult ->
                val data = httpsResult.data as? Map<*, *>
                if (data == null) {
                    Log.w(TAG, "getEntitlement: unexpected non-map response")
                    onResult(Result.TransientError("Malformed response"))
                    return@addOnSuccessListener
                }
                val entitled = data["entitled"] as? Boolean ?: false
                val reason = data["reason"] as? String ?: if (entitled) "paid" else "none"
                val expiresAtAny = data["expiresAt"]
                val expiresAtMs: Long? = when (expiresAtAny) {
                    is Number -> expiresAtAny.toLong()
                    else -> null
                }
                val nowMs = System.currentTimeMillis()
                if (entitled) {
                    store.recordEntitled(reason, expiresAtMs, nowMs)
                    onResult(Result.Entitled(reason, expiresAtMs))
                } else {
                    store.recordDenied(reason, nowMs)
                    onResult(Result.Denied(reason))
                }
            }
            .addOnFailureListener { e ->
                // Distinguish "not signed in" from every other failure. Only
                // UNAUTHENTICATED implies the user needs to sign in; everything
                // else is transient and must not change the cached state.
                val code = (e as? FirebaseFunctionsException)?.code
                if (code == FirebaseFunctionsException.Code.UNAUTHENTICATED) {
                    Log.i(TAG, "getEntitlement: unauthenticated — leaving cache untouched")
                    onResult(Result.Unauthenticated)
                    return@addOnFailureListener
                }
                Log.w(TAG, "getEntitlement failed (transient): ${e.message}")
                onResult(Result.TransientError(e.message ?: "unknown"))
            }
    }
}
