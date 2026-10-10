package com.fliptle.app

import android.content.Context
import com.fliptle.app.accessibility.SurfaceBlocklist
import com.fliptle.app.auth.AuthStore
import com.fliptle.app.auth.FirebaseGate
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

/**
 * Cloud backup/restore of the user's PROGRESS, keyed to the Firebase Auth UID, so
 * it survives sign-out and reinstall. Backed up:
 *   • porn-blocking day counter (enabled flag + anchor + high-water day),
 *   • freeze status (portable wall anchors + day),
 *   • blocked apps and blocked domains,
 *   • the Reels (incl. Stories) and Shorts blocking toggles.
 *
 * Signing out never touches local progress — [restore] only ever ADDS to it
 * (union for lists; fill-if-absent for the one-way porn switch and the freeze),
 * so re-signing in re-syncs from the cloud without erasing anything. No-ops
 * without Firebase or a signed-in user.
 */
object CloudState {

    private const val COLLECTION = "installs"
    private const val FIELD = "state"

    /** Push the current local progress to Firestore. Best-effort, fire-and-forget. */
    fun backup(context: Context) {
        val ctx = context.applicationContext
        if (!FirebaseGate.isAvailable(ctx)) return
        val user = FirebaseAuth.getInstance().currentUser ?: return

        val data = HashMap<String, Any?>()
        data.putAll(PornBlockStore(ctx).backupState())
        data.putAll(FreezeStore(ctx).backupState())
        data["blockedApps"] = ArrayList(BlockedAppsStore(ctx).get())
        data["blockedDomains"] = ArrayList(DomainBlocklist(ctx).userDomains())
        val surfaces = SurfaceBlocklist(ctx)
        data["blockReels"] = surfaces.reels
        data["blockShorts"] = surfaces.shorts
        // Once-per-account screens: only ever set to true (never reset).
        if (AuthStore(ctx).uninstallInfoSeen) data["uninstallInfoSeen"] = true
        if (AuthStore(ctx).inboxConfirmShown) data["inboxConfirmShown"] = true
        // E1: the exit process's own progress (day count + approved), so a device
        // wipe (Clear data) doesn't silently reset it back to day 0. Only ever
        // grows locally (see UninstallLatch), so backing up whatever is local now
        // is always safe to merge back in later.
        val gate = UninstallGateStore(ctx)
        if (gate.daysDone > 0) data["uninstallDays"] = gate.daysDone
        if (gate.approved) data["uninstallApproved"] = true

        FirebaseFirestore.getInstance().collection(COLLECTION).document(user.uid)
            .set(mapOf(FIELD to data), SetOptions.merge())
    }

    /**
     * Pull progress from Firestore and merge it into the local stores, then invoke
     * [onDone] (always, even on failure/no-op) so the caller can proceed.
     */
    fun restore(context: Context, onDone: () -> Unit) {
        val ctx = context.applicationContext
        if (!FirebaseGate.isAvailable(ctx)) { onDone(); return }
        val user = FirebaseAuth.getInstance().currentUser ?: run { onDone(); return }

        FirebaseFirestore.getInstance().collection(COLLECTION).document(user.uid)
            .get()
            .addOnSuccessListener { snap ->
                @Suppress("UNCHECKED_CAST")
                val state = snap.get(FIELD) as? Map<String, Any?>
                if (state != null) applyState(ctx, state)
                onDone()
            }
            .addOnFailureListener { onDone() }
    }

    private fun applyState(ctx: Context, s: Map<String, Any?>) {
        // Porn blocking: only ever fill in if not already on locally.
        if (s["pornEnabled"] == true) {
            PornBlockStore(ctx).restore(asLong(s["pornEnabledAt"]), asInt(s["pornMaxDays"], 1))
        }
        // Freeze: only fill in if no local cycle is running (restoreFromCloud guards
        // this too). Re-anchors from trusted time before it can count down.
        if (s["freezeActive"] == true) {
            FreezeStore(ctx).restoreFromCloud(
                asLong(s["freezeWallStart"]),
                asLong(s["freezeWallUnlock"]),
                asInt(s["freezeMaxDay"], 1)
            )
        }
        // Blocked apps / domains: union so a restore never drops a block.
        asStringList(s["blockedApps"])?.let { cloud ->
            val store = BlockedAppsStore(ctx)
            store.set(store.get() + cloud)
        }
        asStringList(s["blockedDomains"])?.let { DomainBlocklist(ctx).addAll(it) }
        // Reels (incl. Stories) / Shorts blocking: union, so a restore never drops
        // a block. A backup from before these were stored (a running freeze with
        // no flags) predates the wizard, when both surfaces were on by default.
        if (s["freezeActive"] == true) {
            val surfaces = SurfaceBlocklist(ctx)
            surfaces.reels = surfaces.reels || (s["blockReels"] as? Boolean ?: true)
            surfaces.shorts = surfaces.shorts || (s["blockShorts"] as? Boolean ?: true)
        }
        // Once-per-account screens: latch to true (never reset).
        if (s["uninstallInfoSeen"] == true) AuthStore(ctx).uninstallInfoSeen = true
        if (s["inboxConfirmShown"] == true) AuthStore(ctx).inboxConfirmShown = true
        // E1: the exit process's own progress, latched forward only — see
        // UninstallGateStore.applyCloudLatch. A cloud "approved" is what lets
        // SignOutGuard unlock again after a device wipe.
        val cloudDays = asInt(s["uninstallDays"], 0)
        val cloudApproved = s["uninstallApproved"] == true
        if (cloudDays > 0 || cloudApproved) {
            UninstallGateStore(ctx).applyCloudLatch(cloudDays, cloudApproved)
        }
    }

    private fun asLong(v: Any?): Long = when (v) {
        is Number -> v.toLong()
        else -> 0L
    }

    private fun asInt(v: Any?, def: Int): Int = when (v) {
        is Number -> v.toInt()
        else -> def
    }

    private fun asStringList(v: Any?): List<String>? =
        (v as? List<*>)?.mapNotNull { it as? String }
}
