package org.paramanuseniorshealth.notices.data

import android.content.Context
import org.paramanuseniorshealth.notices.fcm.NotificationImageStore
import kotlinx.coroutines.flow.Flow

class NotificationRepository(
    private val dao: NotificationDao,
    private val context: Context,
) {

    val notifications: Flow<List<NotificationEntity>> = dao.observeAll()

    /**
     * Persists an incoming message. Returns true when a new row was written, false when [logId]
     * was already stored — callers use that to avoid re-posting a system notification for a
     * message the user has already seen.
     */
    suspend fun save(
        title: String,
        body: String,
        logId: String?,
        imageUrl: String? = null,
        level: String? = null,
        color: String? = null,
    ): Boolean {
        val entity = NotificationEntity(
            logId = logId?.takeIf { it.isNotBlank() },
            title = title,
            body = body,
            receivedAt = parseTimestamp(logId),
            imageUrl = imageUrl?.takeIf { it.isNotBlank() },
            level = level?.takeIf { it.isNotBlank() },
            color = color?.takeIf { it.isNotBlank() },
        )
        return dao.insert(entity) != -1L
    }

    /** Clears history and the cached images with it, so "Clear" leaves nothing on disk. */
    suspend fun clearAll() {
        dao.deleteAll()
        NotificationImageStore.clear(context)
    }

    /**
     * Clears exactly the rows given, and their cached images with them. Takes entities rather than
     * a level so the caller's notion of which rows are selected is the one that gets deleted.
     */
    suspend fun clear(notifications: List<NotificationEntity>) {
        if (notifications.isEmpty()) return
        dao.deleteByIds(notifications.map { it.id })
        NotificationImageStore.delete(context, notifications.mapNotNull { it.logId })
    }

    /**
     * The backend sends `logId` as a timestamp string. Apps Script's `Date.now()` yields epoch
     * millis, but `getTime()/1000` style values show up as seconds, so both are accepted. Anything
     * unparseable (e.g. an ISO string or a UUID) falls back to arrival time.
     */
    private fun parseTimestamp(logId: String?): Long {
        val now = System.currentTimeMillis()
        val numeric = logId?.trim()?.toLongOrNull() ?: return now
        return when {
            numeric > 100_000_000_000L -> numeric          // already millis
            numeric > 100_000_000L -> numeric * 1000L      // seconds
            else -> now
        }
    }
}
