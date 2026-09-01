package org.paramanuseniorshealth.notices.fcm

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import org.paramanuseniorshealth.notices.MainActivity
import org.paramanuseniorshealth.notices.NotificationAccent
import org.paramanuseniorshealth.notices.R
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object NotificationChannels {

    const val DEFAULT_ID = "notices_default"

    /**
     * `info` messages land here instead. A channel's importance is fixed once created and is the
     * user's to change afterwards, so silencing has to be a second channel rather than a per-message
     * flag; it also means the user can re-enable sound for these without affecting real alerts.
     */
    const val SILENT_ID = "notices_silent"

    fun create(context: Context) {
        val alerts = NotificationChannel(
            DEFAULT_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notification_channel_description)
        }
        val updates = NotificationChannel(
            SILENT_ID,
            context.getString(R.string.notification_channel_silent_name),
            // LOW keeps it out of the shade's heads-up and silences it, while still showing in the
            // list and the status bar.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_silent_description)
        }
        NotificationManagerCompat.from(context).createNotificationChannels(listOf(alerts, updates))
    }

    /**
     * Posts a notification for a data-only message. Data-only payloads are never rendered by the
     * FCM SDK, so the app is responsible for the tray UI.
     *
     * [image] renders as a BigPictureStyle expansion; null falls back to BigTextStyle, which is why
     * a failed download costs only the picture and never the notification.
     */
    fun post(
        context: Context,
        title: String,
        body: String,
        logId: String?,
        image: Bitmap? = null,
        level: String? = null,
        color: String? = null,
    ) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // The user has not granted POST_NOTIFICATIONS. The message is still saved to Room and
            // will show up in the in-app history.
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

        // One accent for both the (advisory) tray colour and the large icon that actually shows it.
        val accent = NotificationAccent.trayColor(color, level)
        val parsedLevel = NotificationAccent.levelOf(level)

        // An image and the level chip compete for the same large-icon slot. The image wins -- it
        // carries far more than a colour -- so the level falls back to a symbol on the title, which
        // is the only other place in a stock notification the app controls.
        //
        // Applied to the displayed title only: the raw title is what reaches Room and the tap-intent
        // extras, so history stays free of decoration.
        val displayTitle = if (image != null && parsedLevel != null) {
            "${symbolFor(parsedLevel)} $title"
        } else {
            title
        }

        val channelId = if (parsedLevel == NotificationAccent.Level.INFO) {
            SILENT_ID
        } else {
            DEFAULT_ID
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(displayTitle)
            .setContentText(body)
            .setStyle(style)
            // A remote image is more informative than a chip, so it keeps the slot when present;
            // the in-app accent stripe still carries the level for those messages.
            .setLargeIcon(image ?: LevelIcon.bitmap(context, color, level))
            .setColor(accent)
            // Mirrors the channel, for the pre-O priority path; PRIORITY_HIGH on a silent message
            // would contradict it.
            .setPriority(
                if (channelId == SILENT_ID) NotificationCompat.PRIORITY_LOW
                else NotificationCompat.PRIORITY_HIGH
            )
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        // Distinct id per logId so successive messages stack instead of replacing each other.
        NotificationManagerCompat.from(context)
            .notify(logId?.hashCode() ?: System.currentTimeMillis().toInt(), notification)
    }

    /**
     * Mirrors the punched symbols on the level chip, so a message reads the same whether its level
     * arrives as artwork or as text. Error is the triangle and warning the bare exclamation, which
     * is the pairing the chips use.
     *
     * Written as escapes rather than literal emoji so the meaning survives any source-encoding
     * mishap between here and the compiler.
     */
    private fun symbolFor(level: NotificationAccent.Level): String = when (level) {
        NotificationAccent.Level.INFO -> "ℹ️"     // information source
        NotificationAccent.Level.SUCCESS -> "✅"        // white heavy check mark
        NotificationAccent.Level.WARNING -> "❗"        // heavy exclamation mark
        NotificationAccent.Level.ERROR -> "⚠️"    // warning sign (triangle)
    }

    const val EXTRA_LOG_ID = "logId"
    const val EXTRA_TITLE = "title"
    const val EXTRA_BODY = "body"
}
