package com.fliptle.app

import com.fliptle.app.auth.FcmTokenRegistrar
import com.fliptle.app.auth.Heartbeat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Data-only silent push handling. The server-contracted message is exactly
 * {"type": "heartbeat_ping"} with no notification payload -- this never
 * shows UI or a notification, it only wakes the app to call Heartbeat.beat()
 * so a reliable check-in isn't solely dependent on the 6h WorkManager tick or
 * the app happening to be opened.
 */
class HeartbeatPingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] == "heartbeat_ping") {
            Heartbeat.beat(this)
        }
    }

    override fun onNewToken(token: String) {
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: return
        FcmTokenRegistrar.writeToken(uid, token)
    }
}
