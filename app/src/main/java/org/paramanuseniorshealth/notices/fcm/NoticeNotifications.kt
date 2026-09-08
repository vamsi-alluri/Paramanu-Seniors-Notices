package org.paramanuseniorshealth.notices.fcm

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.paramanuseniorshealth.notices.MainActivity
import org.paramanuseniorshealth.notices.activation.Subscription
import org.paramanuseniorshealth.notices.R

/**
 * Builds and posts the tray notification for a message.
 *
 * One channel per [Subscription], not per severity. The app this was forked from split channels by
 * alert level, which is a server-monitoring idea; here the split that matters to a reader is what
 * the message is *for* -- an unexpected closure is worth interrupting for, the daily open/closed
 * confirmation is not.
 */
object NoticeNotifications {

    /**
     * One channel per subscription.
     *
     * Notices are high importance: a closure notice is worth interrupting for. The daily status is
     * low, so it appears silently in the shade rather than buzzing sixty times a month -- an app
     * that pesters gets ignored, and an ignored app fails to deliver the closure notice too.
     *
     * Channel importance is fixed once created and belongs to the user afterwards, so these cannot
     * be quietly retuned later; changing one means a new channel id and a fresh default.
     */
    fun create(context: Context) {
        val notices = NotificationChannel(
            Subscription.NOTICES.channelId,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = context.getString(R.string.notification_channel_description) }

        val status = NotificationChannel(
            Subscription.STATUS.channelId,
            context.getString(R.string.notification_channel_status_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = context.getString(R.string.notification_channel_status_description) }

        NotificationManagerCompat.from(context).createNotificationChannels(listOf(notices, status))
    }

    /**
     * Creates the testing channel, and only then.
     *
     * Android lists every channel an app has ever created in system settings, and a channel cannot
     * be un-created -- only deleted, which is untidy if it is ever wanted again. Creating this on
     * demand keeps a "Testing" entry out of the notification settings of everyone who will never
     * use it.
     */
    fun createTestingChannel(context: Context) {
        val testing = NotificationChannel(
            Subscription.TESTING.channelId,
            context.getString(R.string.notification_channel_testing_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = context.getString(R.string.notification_channel_testing_description) }
        NotificationManagerCompat.from(context).createNotificationChannel(testing)
    }

    /** Whether a notification posted now would actually appear. */
    fun canPost(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * Posts a notification for a data-only message. Data-only payloads are never rendered by the
     * FCM SDK, so the app owns the tray UI entirely.
     *
     * [image] renders as a BigPictureStyle expansion; null falls back to BigTextStyle, which is why
     * an unreachable or unrenderable PDF costs only the picture and never the notice.
     */
    fun post(
        context: Context,
        title: String,
        body: String,
        logId: String?,
        image: Bitmap? = null,
        subscription: Subscription = Subscription.NOTICES,
    ) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // Not granted. The notice is still in Room and appears in the in-app list, and the
            // list shows a banner explaining why nothing is arriving in the tray.
            return
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_LOG_ID, logId)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_BODY, body)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            logId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val style = if (image != null) {
            NotificationCompat.BigPictureStyle()
                .bigPicture(image)
                .setSummaryText(body)
                // Otherwise the thumbnail stays pinned in the corner once expanded.
                .bigLargeIcon(null as Bitmap?)
        } else {
            NotificationCompat.BigTextStyle().bigText(body)
        }

        val notification = NotificationCompat.Builder(context, subscription.channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(style)
            .setLargeIcon(image)
            .setPriority(
                if (subscription == Subscription.NOTICES) NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_LOW
            )
            // Notices are about hours and closures; they are worth reading now, not on a schedule.
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        // Distinct id per logId so successive notices stack instead of replacing each other.
        NotificationManagerCompat.from(context)
            .notify(logId?.hashCode() ?: System.currentTimeMillis().toInt(), notification)
    }

    const val EXTRA_LOG_ID = "logId"
    const val EXTRA_TITLE = "title"
    const val EXTRA_BODY = "body"
}
