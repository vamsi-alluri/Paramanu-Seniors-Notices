package org.paramanuseniorshealth.notices.ui

import org.paramanuseniorshealth.notices.data.NotificationEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Renders selected notifications as the plain text handed to the share sheet.
 *
 * Images are deliberately not shared. Sending them would mean attaching files through a
 * FileProvider, and the cached copies are pruned to the newest ten, so a share could silently
 * carry nothing. Text is always available and always complete.
 *
 * Timestamps carry their zone because a shared alert usually ends up somewhere the reader's clock
 * differs from the phone's -- a chat with a colleague, a ticket, a note read weeks later.
 */
object ShareText {

    private val Stamp = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a z")

    /** Two blank lines between entries, so titles stay visually separate in a chat or an editor. */
    private const val SEPARATOR = "\n\n\n"

    fun build(
        notifications: List<NotificationEntity>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = notifications.joinToString(SEPARATOR) { entry(it, zone) }

    private fun entry(notification: NotificationEntity, zone: ZoneId): String = buildString {
        appendLine(notification.title)
        // A blank body is possible: the push contract only requires a title.
        if (notification.body.isNotBlank()) appendLine(notification.body)
        append(Instant.ofEpochMilli(notification.receivedAt).atZone(zone).format(Stamp))
    }
}
