package org.paramanuseniorshealth.notices.fcm

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.paramanuseniorshealth.notices.MainActivity
import org.paramanuseniorshealth.notices.activation.Importance
import org.paramanuseniorshealth.notices.data.NoticeEntity
import org.paramanuseniorshealth.notices.data.NoticeTime
import org.paramanuseniorshealth.notices.R

/**
 * The notification channels, one per importance rather than one per topic.
 *
 * Topics now come from the dispensary's record in the database, and Android lists every channel an
 * app has ever created in system settings and never forgets one. A channel per topic would let data
 * add permanent entries to four hundred people's notification settings. Two importances cover what a
 * reader actually needs to control: what may interrupt them, and what may not.
 *
 * Channel importance is fixed once created and belongs to the user afterwards, so these cannot be
 * quietly retuned later; changing one means a new channel id and a fresh default.
 */
enum class NoticeChannel(val id: String) {
    /** Closures and circulars: worth interrupting for. Created at start-up. */
    HIGH("notices_default"),

    /** Routine messages that arrive without a sound. Created the first time one is posted. */
    LOW("notices_low"),

    /** Delivery checks. Created only once testing is switched on. */
    TESTING("notices_testing");

    companion object {
        fun forImportance(importance: Importance): NoticeChannel =
            if (importance == Importance.LOW) LOW else HIGH
    }
}

/** Builds and posts the tray notification for a message. */
object NoticeNotifications {

    /** The channel every phone needs. Must exist before the first notice, so start-up creates it. */
    fun create(context: Context) {
        ensureChannel(context, NoticeChannel.HIGH)
    }

    /**
     * Creates [channel] if it does not exist yet. Idempotent, so it is called before every post.
     *
     * The low and testing channels are created only when first needed. Android lists every channel
     * an app has ever created in system settings, and a channel cannot be un-created, so creating
     * them eagerly would put entries in the notification settings of everyone who will never receive
     * such a message.
     */
    fun ensureChannel(context: Context, channel: NoticeChannel) {
        val created = when (channel) {
            NoticeChannel.HIGH -> NotificationChannel(
                channel.id,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.notification_channel_description) }

            NoticeChannel.LOW -> NotificationChannel(
                channel.id,
                context.getString(R.string.notification_channel_low_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.notification_channel_low_description) }

            NoticeChannel.TESTING -> NotificationChannel(
                channel.id,
                context.getString(R.string.notification_channel_testing_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.notification_channel_testing_description) }
        }
        NotificationManagerCompat.from(context).createNotificationChannel(created)
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
     *
     * [hasPdf]/[hasImage] say what kind of attachment is coming, for the placeholder glyph used
     * when [image] is null -- either because it has not been fetched yet, or because [updatePicture]
     * is not what posted this notification. A real [image] always wins over the placeholder.
     */
    fun post(
        context: Context,
        title: String,
        body: String,
        logId: String?,
        image: Bitmap? = null,
        hasPdf: Boolean = false,
        hasImage: Boolean = false,
        channel: NoticeChannel = NoticeChannel.HIGH,
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

        // The attachment has not been fetched yet -- that happens some time in the next hour, and
        // may not happen at all on a metered connection. A type glyph says "there is a circular
        // here" without claiming it has arrived, and without a sentence explaining why it has not.
        val placeholder = when {
            hasImage -> R.drawable.ic_file_image
            hasPdf -> R.drawable.ic_file_pdf
            else -> null
        }

        // Sized here, and only here. Both bitmaps cross a Binder transaction capped near 1MB, and
        // going over throws TransactionTooLargeException -- which loses the whole notification, not
        // just the picture. Passing one full-size bitmap to both slots, as this did, put a portrait
        // poster over the line on its own. See TrayArtwork.
        val picture = image?.let { TrayArtwork.bigPicture(it) }
        val icon = image?.let { TrayArtwork.largeIcon(it) }

        val style = if (picture != null) {
            NotificationCompat.BigPictureStyle()
                .bigPicture(picture)
                .setSummaryText(body)
                // Otherwise the thumbnail stays pinned in the corner once expanded.
                .bigLargeIcon(null as Bitmap?)
        } else {
            NotificationCompat.BigTextStyle().bigText(body)
        }

        ensureChannel(context, channel)
        val notification = NotificationCompat.Builder(context, channel.id)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            // The time the notice was sent, not the time this phone posted it. Left to the default,
            // a phone that came online late showed a later time than everyone else's for the same
            // notice, and people comparing phones reasonably concluded something was wrong.
            .setWhen(NoticeTime.sentAt(logId, System.currentTimeMillis()))
            .setShowWhen(true)
            .setStyle(style)
            .setLargeIcon(icon ?: placeholder?.let { context.glyphBitmap(it) })
            .setPriority(
                if (channel == NoticeChannel.HIGH) NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_LOW
            )
            // Notices are about hours and closures; they are worth reading now, not on a schedule.
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            // The worker re-posts this same id when the picture lands, up to an hour later. Without
            // this the phone would buzz a second time for a notice the user already read.
            .setOnlyAlertOnce(true)
            .build()

        // Distinct id per logId so successive notices stack instead of replacing each other.
        NotificationManagerCompat.from(context)
            .notify(logId?.hashCode() ?: System.currentTimeMillis().toInt(), notification)
    }

    /**
     * Takes a posted notification back out of the tray.
     *
     * The id derivation has to match [post] exactly, which is why it lives here rather than at the
     * call site: a mismatch would leave the notification in place with nothing to say so.
     *
     * Needed because a locally-posted notice can stop being true. The revocation one says no more
     * alerts will arrive; once the code is restored that is wrong, and it sat there until the user
     * swiped it away -- and tapping it took them to a notice that no longer existed.
     */
    fun cancel(context: Context, logId: String) {
        NotificationManagerCompat.from(context).cancel(logId.hashCode())
    }

    /**
     * A vector turned into the bitmap `setLargeIcon` requires, on a filled disc rather than
     * transparency.
     *
     * The tray's large icon is not auto-tinted the way `setSmallIcon` is -- it is drawn as-is over
     * a panel background that follows the system theme. A theme attribute in the vector would
     * resolve against this app's theme at render time, not the tray's independent dark styling, so
     * it would not reliably track the background it needs contrast against. A disc carries its own
     * background instead, so the glyph reads the same in both themes with no detection at all.
     * Paper and ink match the standing-information card (see `NoticeListScreen.kt`), for the same
     * "fixed, identical in light and dark" reasoning recorded there.
     */
    private fun Context.glyphBitmap(resId: Int): Bitmap? {
        val drawable = ContextCompat.getDrawable(this, resId) ?: return null
        val size = 128
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawCircle(
            size / 2f,
            size / 2f,
            size / 2f,
            Paint().apply {
                isAntiAlias = true
                color = 0xFFFAF4E6.toInt() // paper
            },
        )
        // A quarter-size inset on each side leaves the glyph sitting inside the disc rather than
        // touching its edge.
        val inset = size / 4
        drawable.setBounds(inset, inset, size - inset, size - inset)
        drawable.setTint(0xFF10131A.toInt()) // ink
        drawable.draw(canvas)
        return bitmap
    }

    /**
     * Puts a fetched picture onto a notification already in the tray.
     *
     * Does nothing if the notification is gone. Dismissed means dismissed -- re-posting an hour
     * later would resurrect something the user has already dealt with, and `setOnlyAlertOnce`
     * would not save them from seeing it reappear. A notice whose notification has gone still gets
     * its picture in the app, which is where they would go looking.
     *
     * Safe to call after any fetch attempt, not only a wholly successful one: a notice can carry
     * several attachments and have some land while others defer, and the ones that landed should
     * be shown. Every path below returns without posting when there is nothing new to show.
     */
    fun updatePicture(context: Context, notice: NoticeEntity) {
        val logId = notice.logId ?: return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val id = logId.hashCode()
        val posted = manager.activeNotifications.firstOrNull { it.id == id } ?: return

        // Same precedence the message path used to apply: the sender's own picture, then the
        // circular's first page, then the link card. The card is last and conditional -- see below.
        val chosen = NoticeImageStore.cachedImage(context, logId)
            ?: NoticeImageStore.cachedPdfRender(context, logId)
        val card = if (chosen == null) NoticeImageStore.cachedLinkImage(context, logId) else null
        val bitmap = NoticeImageStore.decodeDownsampled(chosen ?: card ?: return) ?: return

        // A link image only earns the tray when it is a picture rather than a logo: a YouTube still
        // fills a notification, a 128px favicon stretched across one looks broken. The test has to
        // run after decoding, because the dimensions are not known before. The two other kinds are
        // exempt -- a sender's photo and a rendered A4 page were both chosen to be looked at.
        //
        // No placeholder glyph accompanies this. A link-only notice has always shown either the
        // card image or nothing, and giving it a glyph here would be new behaviour rather than the
        // restoration this is.
        if (card != null && !TrayArtwork.isPictureWorthy(bitmap)) {
            bitmap.recycle()
            return
        }

        post(
            context = context,
            title = notice.title,
            body = notice.body,
            logId = logId,
            image = bitmap,
            // Read back off the notification being replaced rather than assumed. NoticeEntity does
            // not store the topic, so the channel cannot be re-derived from the notice the way
            // NoticeMessagingService derives it -- but the live notification already carries the
            // answer, and it is the same one that post() chose minutes ago. Hardcoding HIGH here
            // silently promoted a TESTING or LOW notice the moment its picture landed, which would
            // have buzzed a phone for a delivery check.
            channel = NoticeChannel.entries.firstOrNull { it.id == posted.notification.channelId }
                ?: NoticeChannel.HIGH,
        )
    }

    const val EXTRA_LOG_ID = "logId"
    const val EXTRA_TITLE = "title"
    const val EXTRA_BODY = "body"
}
