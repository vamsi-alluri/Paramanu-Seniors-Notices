package org.paramanuseniorshealth.notices.ui

import org.paramanuseniorshealth.notices.data.NoticeEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Renders selected notifications as the plain text handed to the share sheet.
 *
 * This used to be the whole of sharing, on the reasoning that attaching a file through a
 * FileProvider could silently carry nothing once the cached copy had been pruned. The reasoning was
 * right and the conclusion was too broad: AttachmentActions now offers a file alongside this text,
 * but only where the file is still on the phone, so the failure that argument was about cannot
 * happen. This remains what a multi-select share produces, and the text that accompanies a shared
 * attachment -- a picture arriving in a chat with no title or date is a poor way to pass on a
 * notice.
 *
 * Timestamps carry their zone because a shared alert usually ends up somewhere the reader's clock
 * differs from the phone's -- a chat with a colleague, a ticket, a note read weeks later.
 */
object ShareText {

    private val Stamp = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a z")

    /** Two blank lines between entries, so titles stay visually separate in a chat or an editor. */
    private const val SEPARATOR = "\n\n\n"

    fun build(
        notifications: List<NoticeEntity>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = notifications.joinToString(SEPARATOR) { entry(it, zone) }

    private fun entry(notification: NoticeEntity, zone: ZoneId): String = buildString {
        appendLine(notification.title)
        // A blank body is possible: the push contract only requires a title.
        if (notification.body.isNotBlank()) appendLine(notification.body)
        append(Instant.ofEpochMilli(notification.receivedAt).atZone(zone).format(Stamp))
    }
}
