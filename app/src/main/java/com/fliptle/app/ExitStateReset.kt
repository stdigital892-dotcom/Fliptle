package com.fliptle.app

import android.content.Context
import com.fliptle.app.auth.FirebaseGate
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

/**
 * Resets the exit (uninstall) process when the user starts a NEW commitment
 * (a freeze, or adult-content blocking being turned on) while a PREVIOUS exit
 * was already approved -- staying in means the earlier approval to leave no
 * longer applies. Must NEVER fire on a cloud restore or a plain sign-in:
 * CloudState's latch (see UninstallGateStore.applyCloudLatch) only ever moves
 * the exit gate forward, never back, so those paths never call this.
 */
object ExitStateReset {

    enum class Trigger { NEW_COMMITMENT, RESTORE_OR_SIGN_IN }

    /** Pure rule, Context-free -- directly unit-tested. */
    fun shouldReset(trigger: Trigger, approved: Boolean): Boolean =
        trigger == Trigger.NEW_COMMITMENT && approved

    /** Call right when a freeze starts/restarts, or adult-content blocking is
     *  enabled. No-ops unless the exit process was already approved. */
    fun resetIfNeeded(context: Context, trigger: Trigger) {
        val ctx = context.applicationContext
        val gate = UninstallGateStore(ctx)
        if (!shouldReset(trigger, gate.approved)) return

        gate.cancel()
        if (!FirebaseGate.isAvailable(ctx)) return
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val doc = FirebaseFirestore.getInstance().collection("installs").document(user.uid)
        // Clear the backed-up exit progress first, so a later restore can't
        // re-latch the approval this reset just revoked.
        doc.update(
            "state.uninstallApproved", FieldValue.delete(),
            "state.uninstallDays", FieldValue.delete()
        )
        // Flip the live flag back; uninstall_approved_at (the prior approval's
        // own timestamp) is deliberately left untouched -- it's history, not state.
        doc.set(mapOf("uninstall_approved" to false), SetOptions.merge())
    }
}
