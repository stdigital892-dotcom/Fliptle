package com.fliptle.app.auth

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging

/**
 * Writes the current FCM token to installs/{uid}.fcmToken, so the server can
 * send the silent {type:"heartbeat_ping"} data push used to wake a dormant
 * app and trigger an immediate Heartbeat.beat(). Called on sign-in and from
 * [com.fliptle.app.HeartbeatPingService.onNewToken] whenever the token
 * rotates. Best-effort; no-ops without Firebase or a signed-in user.
 */
object FcmTokenRegistrar {

    private const val TAG = "FcmTokenRegistrar"

    fun registerForCurrentUser(context: Context) {
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (!FirebaseGate.isAvailable(context)) return
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token -> writeToken(uid, token) }
            .addOnFailureListener { e -> Log.w(TAG, "FCM token fetch failed: ${e.message}") }
    }

    fun writeToken(uid: String, token: String) {
        FirebaseFirestore.getInstance().collection("installs").document(uid)
            .set(mapOf("fcmToken" to token), SetOptions.merge())
    }
}
