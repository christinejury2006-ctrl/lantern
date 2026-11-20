package com.lantern.library.data

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.lantern.library.data.LoreNoticeCategory
import com.lantern.library.data.NotificationCenter

/**
 * Receives data-only FCM events from the trusted notification sender. The server must authenticate
 * and authorize event creation; the Android client never accepts arbitrary user-supplied tokens.
 */
class LoreMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        NotificationCenter.initialize(applicationContext)
        NotificationCenter.saveToken(token)
        NotificationCenter.registerDeviceToken()
        // Token registration is intentionally left to the authenticated account-sync endpoint.
        // The token is persisted locally so registration can be retried after the user signs in.
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        NotificationCenter.initialize(applicationContext)
        val data = message.data
        NotificationCenter.post(
            context = applicationContext,
            category = LoreNoticeCategory.from(data["category"]),
            title = data["title"] ?: message.notification?.title ?: "Lore update",
            body = data["body"] ?: message.notification?.body.orEmpty(),
            eventId = data["event_id"] ?: data["eventId"].orEmpty(),
            target = data["target"].orEmpty(),
            showSystem = true
        )
    }
}
