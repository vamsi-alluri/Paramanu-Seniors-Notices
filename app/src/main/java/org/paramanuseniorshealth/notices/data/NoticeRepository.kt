package org.paramanuseniorshealth.notices.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import org.paramanuseniorshealth.notices.fcm.NoticeImageStore

class NoticeRepository(
    private val dao: NoticeDao,
    private val context: Context,
) {

    val notices: Flow<List<NoticeEntity>> = dao.observeAll()

    suspend fun byId(id: Long): NoticeEntity? = dao.byId(id)

    suspend fun byLogId(logId: String): NoticeEntity? = dao.byLogId(logId)

    /**
     * Persists an incoming notice. Returns true when a new row was written, false when [logId] was
     * already stored -- callers use that to avoid re-posting a notification for something the user
     * has already seen.
     */
    suspend fun save(
        title: String,
        body: String,
        logId: String?,
        imageUrl: String? = null,
        pdfUrl: String? = null,
    ): Boolean {
        val entity = NoticeEntity(
            logId = logId?.takeIf { it.isNotBlank() },
            title = title,
            body = body,
            receivedAt = parseTimestamp(logId),
            imageUrl = imageUrl?.takeIf { it.isNotBlank() },
            pdfUrl = pdfUrl?.takeIf { it.isNotBlank() },
        )
        return dao.insert(entity) != -1L
    }

    /**
     * Writes the first entry a new install sees, immediately after a code is redeemed.
     *
     * Without it someone types a code and is rewarded with an empty screen until the NGO happens to
     * send something, which reads as "it did not work" rather than "there is no news". It is also
     * what a Play reviewer sees, and an app showing nothing but an empty state invites a minimum
     * functionality flag.
     *
     * Written locally, never pushed. The `local-` prefix keeps its id out of the sender's numeric
     * namespace so it can never collide with a real notice.
     */
    suspend fun saveWelcome(title: String, body: String): Boolean = save(
        title = title,
        body = body,
        logId = "local-welcome",
    )

    /**
     * Writes the entry that says access has ended, mirroring [saveWelcome] at the other end.
     *
     * Returns false when one is already stored, which is how a repeated revoke broadcast is stopped
     * from posting a second notification: the insert is IGNORE on a duplicate `logId`.
     */
    suspend fun saveRevoked(title: String, body: String): Boolean = save(
        title = title,
        body = body,
        logId = REVOKED_LOG_ID,
    )

    /**
     * Removes the revocation entry when access is restored.
     *
     * Without this, the row would sit permanently in a working phone's history saying delivery had
     * stopped, and -- because the insert ignores a duplicate id -- a later revocation would never
     * announce itself.
     */
    suspend fun clearRevokedNotice() {
        dao.byLogId(REVOKED_LOG_ID)?.let { clear(listOf(it)) }
    }

    /** Clears history and the cached renders with it, so "clear" leaves nothing on disk. */
    suspend fun clearAll() {
        dao.deleteAll()
        NoticeImageStore.clear(context)
    }

    suspend fun clear(notices: List<NoticeEntity>) {
        if (notices.isEmpty()) return
        dao.deleteByIds(notices.map { it.id })
        NoticeImageStore.delete(context, notices.mapNotNull { it.logId })
    }

    /**
     * The sender supplies `logId`. When it is a timestamp we use it, so ordering reflects when the
     * NGO published rather than when this particular phone happened to receive it -- which matters
     * for a device that was switched off overnight. Anything unparseable (a UUID, an ISO string)
     * falls back to arrival time.
     */
    companion object {
        /** `local-` keeps it out of the sender's numeric namespace, as with `local-welcome`. */
        const val REVOKED_LOG_ID = "local-revoked"
    }

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
