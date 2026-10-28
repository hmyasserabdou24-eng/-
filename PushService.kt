package org.darelihsan.parents

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class PushService : FirebaseMessagingService() {

    /** FCM rotates tokens (reinstall, restore, clear data). Store it; the page registers it once a session exists. */
    override fun onNewToken(token: String) {
        Prefs(this).fcmToken = token
        MainActivity.onTokenChanged?.invoke()
    }

    /** Called when the app is in the foreground (or for data-only messages). Background notifications are shown by the system. */
    override fun onMessageReceived(message: RemoteMessage) {
        val d = message.data
        val eventId = d["event_id"] ?: message.messageId ?: return
        Notify.show(this, eventId, d["kind"], d["route"])
    }
}
