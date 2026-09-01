package org.paramanuseniorshealth.notices.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One received push notification.
 *
 * [logId] is the backend-supplied identifier and carries a UNIQUE index: the same message can
 * legitimately reach us twice (FCM guarantees at-least-once delivery, and a message can arrive via
 * both [org.paramanuseniorshealth.notices.fcm.NoticeMessagingService] and the notification-tap intent). Inserting
 * with OnConflictStrategy.IGNORE turns that into a no-op instead of a duplicate row.
 *
 * SQLite allows multiple NULLs in a unique index, so messages sent without a logId are still stored.
 */
@Entity(
    tableName = "notifications",
    indices = [Index(value = ["logId"], unique = true)],
)
data class NotificationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val logId: String?,
    val title: String,
    val body: String,
    /** Epoch millis. Parsed from [logId] when it is a timestamp, otherwise arrival time. */
    val receivedAt: Long,
    /** Remote image URL, when the sender supplied one. */
    val imageUrl: String? = null,
    /** `info` | `success` | `warning` | `error`, when supplied. */
    val level: String? = null,
    /** `#RRGGBB` accent, when supplied. Takes precedence over [level]. */
    val color: String? = null,
)
