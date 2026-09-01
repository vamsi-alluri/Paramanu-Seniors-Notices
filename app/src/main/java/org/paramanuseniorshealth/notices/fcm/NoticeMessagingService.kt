package org.paramanuseniorshealth.notices.fcm

import org.paramanuseniorshealth.notices.NoticesApplication
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking

class NoticeMessagingService : FirebaseMessagingService() {

    /**
     * Called for every message while the app is in the foreground, and for **data-only** messages
     * in the background too. A payload containing a `notification` block is handled by the SDK and
     * drawn straight into the tray while backgrounded -- this method does not run, which is why the
     * backend must send data-only messages. See MainActivity for the tap-intent fallback.
     */
    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        // Prefer the data payload; fall back to the notification block for hybrid payloads.
        val title = data["title"] ?: message.notification?.title ?: return
        val body = data["body"] ?: message.notification?.body.orEmpty()
        val logId = data["logId"]
        val imageUrl = data["imageUrl"]?.takeIf { it.isNotBlank() }
        val level = data["level"]
        val color = data["color"]

        // onMessageReceived already runs off the main thread and the service is held alive for the
        // duration of this call, so blocking here guarantees the work lands before teardown.
        runBlocking {
            val app = applicationContext as NoticesApplication

            // Saved before the image is fetched: a slow or dead URL must never cost a history entry.
            val isNew = app.repository.save(
                title = title,
                body = body,
                logId = logId,
                imageUrl = imageUrl,
                level = level,
                color = color,
            )

            // Fetching serves double duty -- the bitmap goes straight into the tray notification and
            // the same file backs the in-app list, so the UI never performs network I/O.
            val image = if (imageUrl != null && logId != null) {
                NotificationImageStore.fetch(applicationContext, imageUrl, logId)
            } else {
                null
            }

            // Only draw the tray notification ourselves for data-only payloads. If the server also
            // sent a `notification` block while we were in the foreground, posting again would
            // duplicate it. `isNew` additionally suppresses re-delivered messages.
            if (isNew && message.notification == null) {
                NotificationChannels.post(
                    context = this@NoticeMessagingService,
                    title = title,
                    body = body,
                    logId = logId,
                    image = image,
                    level = level,
                    color = color,
                )
            }
        }
    }

    // onNewToken is intentionally not overridden: this app is addressed only by topic, and the SDK
    // re-establishes topic subscriptions itself after a token rotation.
}
