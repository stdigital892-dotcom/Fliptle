package com.fliptle.app.auth

import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.fliptle.app.R
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions

/**
 * Account deletion with a 72-hour delay (client side).
 *
 * The server (requestAccountDeletion / cancelAccountDeletion) owns the truth:
 * it writes or clears installs/{uid}.deletionScheduledFor, using only the uid
 * and email from the caller's token. This object asks for those calls and keeps
 * a small local copy of the scheduled time, tagged with the uid it belongs to,
 * so the app knows when a deletion has come due:
 *   • EntitlementGate uses it so the "not entitled" answer that deletion itself
 *     causes can never switch blocking off ([DeletionDue]).
 *   • [AccountDeletionWatcher] uses it to clean up when the account disappears.
 *
 * Nothing here deletes anything locally and nothing touches blocking.
 */
object PendingDeletion {

    private const val REGION = "asia-south2"
    private const val PREFS = "pending_deletion"
    private const val KEY_UID = "uid"
    private const val KEY_AT = "scheduled_for_ms"

    // ---- local copy ----

    /** The scheduled time cached for [uid], or null if none is cached for that account. */
    fun cachedFor(context: Context, uid: String): Long? {
        val p = prefs(context)
        if (p.getString(KEY_UID, null) != uid) return null
        return p.getLong(KEY_AT, -1L).takeIf { it > 0L }
    }

    /** The uid the cached scheduled time belongs to, if any. */
    fun cachedUid(context: Context): String? = prefs(context).getString(KEY_UID, null)

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

    // ---- user actions ----

    /** The confirmation dialog. Confirming asks the server to schedule deletion in 72 hours. */
    fun confirm(activity: Activity, onChanged: () -> Unit) {
        if (!FirebaseGate.isAvailable(activity) || FirebaseAuth.getInstance().currentUser == null) return
        AlertDialog.Builder(activity)
            .setTitle(R.string.deletion_confirm_title)
            .setMessage(R.string.deletion_confirm_body)
            .setNegativeButton(R.string.deletion_confirm_no, null)
            .setPositiveButton(R.string.deletion_confirm_yes) { _, _ -> request(activity, onChanged) }
            .show()
    }

    private fun request(activity: Activity, onChanged: () -> Unit) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        Toast.makeText(activity, R.string.deletion_requesting, Toast.LENGTH_SHORT).show()
        // No arguments: the server identifies the account from the auth token only.
        FirebaseFunctions.getInstance(REGION).getHttpsCallable("requestAccountDeletion").call()
            .addOnSuccessListener { result ->
                val ms = ((result.data as? Map<*, *>)?.get("scheduledForMs") as? Number)?.toLong()
                if (ms != null) cache(activity, uid, ms)
                Toast.makeText(activity, R.string.deletion_scheduled_toast, Toast.LENGTH_LONG).show()
                onChanged()
            }
            .addOnFailureListener { e ->
                Toast.makeText(
                    activity, activity.getString(R.string.deletion_request_failed, e.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    /** Cancel a scheduled deletion (the server refuses once it is due). */
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
