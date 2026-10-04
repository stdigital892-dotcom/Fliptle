package com.fliptle.app.auth

import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.fliptle.app.R
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException

/**
 * Account deletion with a 72-hour delay (client side).
 *
 * The server (requestAccountDeletion / cancelAccountDeletion) owns the truth:
 * it writes or clears installs/{uid}.deletionScheduledFor, using only the uid
 * and email from the caller's token. The client NEVER writes deletionScheduledFor
 * or deletionRequestedAt; this object only asks for those calls and keeps a small
 * local copy of the scheduled time, tagged with the uid it belongs to, so the app
 * knows when a deletion has come due:
 *   • EntitlementGate uses it so the "not entitled" answer that deletion itself
 *     causes can never switch blocking off ([DeletionDue]).
 *   • [AccountDeletionWatcher] uses it to clean up when the account disappears.
 *
 * How the pieces fit:
 *   • Requesting deletion (after the gate in AccountDeletionActivity): the server
 *     sets now + 72h afresh and revokes all refresh tokens; the app then shows the
 *     date and signs out through the ordinary sign-out.
 *   • Signing in again IS the cancellation: SignInActivity reads the server value
 *     and, if it is still in the future, calls [cancelOnSignIn].
 *
 * Nothing here deletes anything locally and nothing touches blocking.
 */
object PendingDeletion {

    private const val REGION = "asia-south2"
    private const val PREFS = "pending_deletion"
    private const val KEY_UID = "uid"
    private const val KEY_AT = "scheduled_for_ms"

    // A separate file: clearCache() wipes PREFS, and these must outlive it.
    private const val FLOW_PREFS = "deletion_flow"
    private const val KEY_NOTICE_MS = "notice_ms"
    private const val KEY_CHECK_UID = "check_uid"
    private const val KEY_CHECK_METHOD = "check_method"

    // ---- local copy ----

    /** The scheduled time cached for [uid], or null if none is cached for that account. */
    fun cachedFor(context: Context, uid: String): Long? {
        val p = prefs(context)
        if (p.getString(KEY_UID, null) != uid) return null
        return p.getLong(KEY_AT, -1L).takeIf { it > 0L }
    }

    /** The uid the cached scheduled time belongs to, if any. */
    fun cachedUid(context: Context): String? = prefs(context).getString(KEY_UID, null)

    /** The cached scheduled time, whichever account it belongs to. */
    fun cachedScheduledMs(context: Context): Long? =
        prefs(context).getLong(KEY_AT, -1L).takeIf { it > 0L }

    /** True when a deletion for [uid] is cached and its time has passed. */
    fun isDueFor(context: Context, uid: String?, nowMs: Long = System.currentTimeMillis()): Boolean {
        val p = prefs(context)
        return DeletionDue.isDue(
            p.getString(KEY_UID, null),
            p.getLong(KEY_AT, -1L).takeIf { it > 0L },
            uid,
            nowMs
        )
    }

    fun cache(context: Context, uid: String, scheduledForMs: Long) {
        prefs(context).edit().putString(KEY_UID, uid).putLong(KEY_AT, scheduledForMs).apply()
    }

    fun clearCache(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- the "scheduled for ..." notice shown on the sign-in screen after sign-out ----

    fun noticeMs(context: Context): Long? =
        flowPrefs(context).getLong(KEY_NOTICE_MS, -1L).takeIf { it > 0L }

    fun setNotice(context: Context, scheduledForMs: Long) {
        flowPrefs(context).edit().putLong(KEY_NOTICE_MS, scheduledForMs).apply()
    }

    fun clearNotice(context: Context) {
        flowPrefs(context).edit().remove(KEY_NOTICE_MS).apply()
    }

    // ---- "sign-in check not finished" marker ----
    //
    // Set when a FRESH sign-in on this phone starts its deletion check, cleared
    // when the check is over. While it is set for the signed-in account the app
    // keeps returning to the sign-in screen (see AuthGate), so a failed cancel
    // cannot be skipped by relaunching. It exists only because of a real sign-in
    // here: nothing cancels a deletion at app start or on a token refresh, so a
    // second phone that still holds a valid token can never cancel by accident.

    fun signInCheckPending(context: Context, uid: String): Boolean =
        flowPrefs(context).getString(KEY_CHECK_UID, null) == uid

    fun signInCheckMethod(context: Context): String =
        flowPrefs(context).getString(KEY_CHECK_METHOD, null) ?: "unknown"

    fun markSignInCheck(context: Context, uid: String, method: String) {
        flowPrefs(context).edit().putString(KEY_CHECK_UID, uid).putString(KEY_CHECK_METHOD, method).apply()
    }

    fun clearSignInCheck(context: Context) {
        flowPrefs(context).edit().remove(KEY_CHECK_UID).remove(KEY_CHECK_METHOD).apply()
    }

    private fun flowPrefs(context: Context) =
        context.applicationContext.getSharedPreferences(FLOW_PREFS, Context.MODE_PRIVATE)

    // ---- reading the server's value ----

    /**
     * Read installs/{uid}.deletionScheduledFor. [onResult] gets (scheduledForMs or
     * null, ok). The local copy changes ONLY when ok is true: a failed or offline
     * read leaves everything exactly as it was.
     */
    fun fetch(context: Context, uid: String, onResult: (Long?, Boolean) -> Unit) {
        if (!FirebaseGate.isAvailable(context)) {
            onResult(null, false)
            return
        }
        val app = context.applicationContext
        FirebaseFirestore.getInstance().collection("installs").document(uid).get()
            .addOnSuccessListener { snap ->
                val ms = snap.getTimestamp("deletionScheduledFor")?.toDate()?.time
                if (ms != null) cache(app, uid, ms) else if (cachedFor(app, uid) != null) clearCache(app)
                onResult(ms, true)
            }
            .addOnFailureListener { onResult(null, false) }
    }

    /**
     * One fresh read of installs/{uid} from the SERVER (never the local cache),
     * reporting what it found without changing any local state. [onResult] gets
     * (ok, documentExists, scheduledForMs). Used before the watcher cleans up.
     * Firestore rules only let a signed-in owner read this document, so a signed-out
     * phone gets ok = false.
     */
    fun readServer(
        context: Context,
        uid: String,
        onResult: (ok: Boolean, exists: Boolean, scheduledForMs: Long?) -> Unit
    ) {
        if (!FirebaseGate.isAvailable(context)) {
            onResult(false, false, null)
            return
        }
        FirebaseFirestore.getInstance().collection("installs").document(uid)
            .get(com.google.firebase.firestore.Source.SERVER)
            .addOnSuccessListener { snap ->
                onResult(true, snap.exists(), snap.getTimestamp("deletionScheduledFor")?.toDate()?.time)
            }
            .addOnFailureListener { onResult(false, false, null) }
    }

    /**
     * Like [fetch], but always asks the SERVER (never the offline cache, which could
     * still show the document from before the deletion was requested) and so is the
     * one a sign-in trusts. [onResult] gets (scheduledForMs or null, ok); the cached
     * copy follows the server only when ok is true.
     */
    fun fetchFresh(context: Context, uid: String, onResult: (Long?, Boolean) -> Unit) {
        val app = context.applicationContext
        readServer(app, uid) { ok, _, ms ->
            if (ok) {
                if (ms != null) cache(app, uid, ms) else if (cachedFor(app, uid) != null) clearCache(app)
            }
            onResult(if (ok) ms else null, ok)
        }
    }

    // ---- user actions ----

    /**
     * The final confirmation, shown once the deletion gate is complete. Confirming
     * asks the server to schedule deletion in 72 hours; [onScheduled] gets the
     * scheduled time. A refusal or failure is shown with the server's message.
     */
    fun confirm(activity: Activity, onScheduled: (Long) -> Unit) {
        if (!FirebaseGate.isAvailable(activity) || FirebaseAuth.getInstance().currentUser == null) return
        AlertDialog.Builder(activity)
            .setTitle(R.string.deletion_confirm_title)
            .setMessage(R.string.deletion_confirm_body)
            .setNegativeButton(R.string.deletion_confirm_no, null)
            .setPositiveButton(R.string.deletion_confirm_yes) { _, _ -> request(activity, onScheduled) }
            .show()
    }

    private fun request(activity: Activity, onScheduled: (Long) -> Unit) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        Toast.makeText(activity, R.string.deletion_requesting, Toast.LENGTH_SHORT).show()
        // No arguments: the server identifies the account from the auth token only
        // and decides the time itself (always a fresh 72 hours).
        FirebaseFunctions.getInstance(REGION).getHttpsCallable("requestAccountDeletion").call()
            .addOnSuccessListener { result ->
                val ms = ((result.data as? Map<*, *>)?.get("scheduledForMs") as? Number)?.toLong()
                if (ms == null) {
                    Toast.makeText(
                        activity, activity.getString(R.string.deletion_request_failed, ""), Toast.LENGTH_LONG
                    ).show()
                    return@addOnSuccessListener
                }
                cache(activity, uid, ms)
                onScheduled(ms)
            }
            .addOnFailureListener { e ->
                Toast.makeText(
                    activity, activity.getString(R.string.deletion_request_failed, e.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    /** Cancel a scheduled deletion from the Account screen (the server refuses once it is due). */
    fun cancel(activity: Activity, onChanged: () -> Unit) {
        FirebaseFunctions.getInstance(REGION).getHttpsCallable("cancelAccountDeletion").call()
            .addOnSuccessListener {
                clearCache(activity)
                Toast.makeText(activity, R.string.deletion_cancelled, Toast.LENGTH_LONG).show()
                onChanged()
            }
            .addOnFailureListener { e ->
                Toast.makeText(
                    activity, activity.getString(R.string.deletion_cancel_failed, e.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    enum class CancelResult { CANCELLED, DUE, FAILED }

    /**
     * Cancel as part of a fresh sign-in. The server refuses with FAILED_PRECONDITION
     * once the time has passed; that is reported as DUE (no retry will help). Any
     * other failure is FAILED (a retry may). The local copy changes only on success.
     */
    fun cancelOnSignIn(context: Context, onResult: (CancelResult) -> Unit) {
        val app = context.applicationContext
        FirebaseFunctions.getInstance(REGION).getHttpsCallable("cancelAccountDeletion").call()
            .addOnSuccessListener {
                clearCache(app)
                onResult(CancelResult.CANCELLED)
            }
            .addOnFailureListener { e ->
                val due = e is FirebaseFunctionsException &&
                    e.code == FirebaseFunctionsException.Code.FAILED_PRECONDITION
                onResult(if (due) CancelResult.DUE else CancelResult.FAILED)
            }
    }
}

/**
 * The one rule behind "a deletion is due for the signed-in account", as a pure
 * function so it can be unit-tested without Android.
 *
 * Due means: a scheduled time is cached, it belongs to THIS account (same uid),
 * and it has arrived. A cached time for a different account never counts, so
 * one account's deletion can't affect the next account that signs in.
 */
object DeletionDue {
    fun isDue(cachedUid: String?, cachedScheduledMs: Long?, currentUid: String?, nowMs: Long): Boolean =
        cachedUid != null &&
            currentUid != null &&
            cachedUid == currentUid &&
            cachedScheduledMs != null &&
            cachedScheduledMs <= nowMs
}

/**
 * The decisions of the deletion flow that must not depend on Android, so each one
 * has a unit test (DeletionFlowRulesTest).
 */
object DeletionFlowRules {

    /** What a fresh sign-in does, given the server's scheduled time for that account. */
    enum class SignInCheck {
        /** Nothing scheduled: continue straight away. */
        PROCEED,
        /** Scheduled in the future: cancel it (signing in IS the cancellation). */
        CANCEL,
        /** The time has already passed: it can no longer be cancelled. */
        DUE
    }

    fun signInCheck(scheduledMs: Long?, nowMs: Long): SignInCheck = when {
        scheduledMs == null -> SignInCheck.PROCEED
        scheduledMs > nowMs -> SignInCheck.CANCEL
        else -> SignInCheck.DUE
    }

    /** What the watcher does after re-reading installs/{uid} from the server. */
    enum class Cleanup {
        /** The deletion really happened (or is still due): do the cleanup. */
        CLEAN,
        /** Cancelled, or not due yet: do nothing and fix the cached copy. */
        SKIP,
        /** The server could not be read: change nothing, decide again later. */
        DEFER
    }

    fun cleanupAfterReread(ok: Boolean, exists: Boolean, scheduledMs: Long?, nowMs: Long): Cleanup = when {
        !ok -> Cleanup.DEFER
        !exists -> Cleanup.CLEAN                // the account's data is gone: it was deleted
        scheduledMs == null -> Cleanup.SKIP     // no longer scheduled (cancelled)
        scheduledMs > nowMs -> Cleanup.SKIP     // no longer due
        else -> Cleanup.CLEAN
    }

    /**
     * True when the deletion cached for ANOTHER account has come due while
     * [currentUid] signs in, so that account's leftovers must be cleared before
     * the new account uses them. The same account never qualifies (it is verified
     * against the server instead), and a deletion still in the future never does.
     */
    fun staleOtherAccountDue(cachedUid: String?, cachedMs: Long?, currentUid: String?, nowMs: Long): Boolean =
        cachedUid != null &&
            currentUid != null &&
            cachedUid != currentUid &&
            cachedMs != null &&
            cachedMs <= nowMs
}
