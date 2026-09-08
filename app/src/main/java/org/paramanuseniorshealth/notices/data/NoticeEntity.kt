package org.paramanuseniorshealth.notices.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One received notice.
 *
 * [logId] is the sender-supplied identifier and carries a UNIQUE index: the same message can
 * legitimately reach us twice (FCM guarantees at-least-once delivery, and a message can arrive via
 * both [org.paramanuseniorshealth.notices.fcm.NoticeMessagingService] and the notification-tap
 * intent). Inserting with OnConflictStrategy.IGNORE turns that into a no-op instead of a duplicate.
 *
 * SQLite allows multiple NULLs in a unique index, so notices sent without a logId are still stored.
 */
@Entity(
    tableName = "notices",
    indices = [Index(value = ["logId"], unique = true)],
)
data class NoticeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val logId: String?,
    /** The plain-text headline. Mandatory: an attachment is never allowed to be the whole message. */
    val title: String,
    /** One-line summary. May be blank; the push contract only requires a title. */
    val body: String,
    /** Epoch millis. Parsed from [logId] when it is a timestamp, otherwise arrival time. */
    val receivedAt: Long,
    /**
     * A picture the sender attached directly: a photograph of a note taped to the shutter, a
     * poster, a screenshot. Shown as-is.
     */
    val imageUrl: String? = null,
    /**
     * A notice PDF on the website. Page one is rendered to an image on arrival.
     *
     * This and [imageUrl] are not alternatives and may both be present: a notice can carry a photo
     * *and* link the official circular. When both exist the photo wins the notification tray, since
     * it was chosen for a small frame, and the expanded row shows both.
     */
    val pdfUrl: String? = null,
)
