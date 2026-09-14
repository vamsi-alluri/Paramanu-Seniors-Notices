package org.paramanuseniorshealth.notices.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import org.paramanuseniorshealth.notices.fcm.NoticeImageStore
import org.paramanuseniorshealth.notices.fcm.NoticeNotifications
import java.io.File

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
        linkUrl: String? = null,
        linkTitle: String? = null,
        linkImage: String? = null,
        linkSite: String? = null,
    ): Boolean {
        val entity = NoticeEntity(
            logId = logId?.takeIf { it.isNotBlank() },
            title = title,
            body = body,
            receivedAt = NoticeTime.sentAt(logId, System.currentTimeMillis()),
            imageUrl = imageUrl?.takeIf { it.isNotBlank() },
            pdfUrl = pdfUrl?.takeIf { it.isNotBlank() },
            linkUrl = linkUrl?.takeIf { it.isNotBlank() },
            linkTitle = linkTitle?.takeIf { it.isNotBlank() },
            linkImage = linkImage?.takeIf { it.isNotBlank() },
            linkSite = linkSite?.takeIf { it.isNotBlank() },
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
        logId = WELCOME_LOG_ID,
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
        // The tray copy has to go too. Deleting only the row left the notification sitting there
        // still saying no more alerts would arrive, after they had started arriving again.
        NoticeNotifications.cancel(context, REVOKED_LOG_ID)
    }

    /**
     * The circular itself, downloading it if this is the first time it has been asked for.
     *
     * Lives here rather than in the view model because it needs a Context and the view model
     * deliberately holds none. Returns null when there is no PDF, no logId to file it under, or the
     * download failed -- callers fall back to opening the URL in a browser.
     */
    suspend fun pdfFile(notice: NoticeEntity): File? {
        val url = notice.pdfUrl?.takeIf { it.isNotBlank() } ?: return null
        val logId = notice.logId ?: return null
        return NoticeImageStore.fetchPdf(context, url, logId)
    }

    suspend fun clear(notices: List<NoticeEntity>) {
        if (notices.isEmpty()) return
        dao.deleteByIds(notices.map { it.id })
        NoticeImageStore.delete(context, notices.mapNotNull { it.logId })
    }

    companion object {
        /**
         * Marks a notice this app wrote itself rather than one the sender broadcast.
         *
         * The sender's ids are epoch millis, so this can never collide with a real notice. It also
         * tells the notification-tap fallback in MainActivity what it must not try to recover: a
         * locally-written notice has no payload behind it to restore from.
         */
        const val LOCAL_LOG_ID_PREFIX = "local-"

        const val WELCOME_LOG_ID = LOCAL_LOG_ID_PREFIX + "welcome"
        const val REVOKED_LOG_ID = LOCAL_LOG_ID_PREFIX + "revoked"
    }
}
