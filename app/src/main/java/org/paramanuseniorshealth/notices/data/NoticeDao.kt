package org.paramanuseniorshealth.notices.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NoticeDao {

    /** Newest first. `id` breaks ties when two notices share a timestamp. */
    @Query("SELECT * FROM notices ORDER BY receivedAt DESC, id DESC")
    fun observeAll(): Flow<List<NoticeEntity>>

    @Query("SELECT * FROM notices WHERE id = :id")
    suspend fun byId(id: Long): NoticeEntity?

    @Query("SELECT * FROM notices WHERE logId = :logId LIMIT 1")
    suspend fun byLogId(logId: String): NoticeEntity?

    /** Returns -1 when the row was ignored because [NoticeEntity.logId] already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(notice: NoticeEntity): Long

    @Query("DELETE FROM notices")
    suspend fun deleteAll()

    @Query("DELETE FROM notices WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    /**
     * Rows carrying an attachment, newest first, for the catch-up sweep.
     *
     * Filtering by state happens in Kotlin rather than SQL: the rule lives in
     * [org.paramanuseniorshealth.notices.fcm.FetchPolicy.shouldRetry], where it is unit-tested, and
     * duplicating it as a WHERE clause would give it two homes that could drift apart.
     *
     * `logId IS NOT NULL` because [updateAttachment] matches by logId and a NULL-logId row could
     * never be found again -- it would be re-selected and re-fetched by every sweep forever, stuck
     * at `attachmentAttempts = 0` since the update that would advance it can never match. This is
     * not a lost case: every attachment cache path keys on logId
     * ([org.paramanuseniorshealth.notices.fcm.NoticeImageStore.cachedImage] returns null for a null
     * id), so a NULL-logId notice can never hold a cached attachment in the first place.
     */
    @Query(
        "SELECT * FROM notices WHERE logId IS NOT NULL AND (imageUrl IS NOT NULL OR " +
            "pdfUrl IS NOT NULL OR pdfThumbUrl IS NOT NULL OR linkImage IS NOT NULL) " +
            "ORDER BY receivedAt DESC"
    )
    suspend fun withAttachments(): List<NoticeEntity>

    /**
     * Unconditional overwrite of [NoticeEntity.attachmentState] and
     * [NoticeEntity.attachmentAttempts] -- SQL here has no way to express "never decrease", so the
     * caller is responsible for carrying the current attempt count forward on a deferral. Passing
     * `attempts = 0` for a [org.paramanuseniorshealth.notices.fcm.AttachmentState.DEFERRED] would
     * silently reset a failure count the state's own KDoc says deferrals must never touch.
     */
    @Query(
        "UPDATE notices SET attachmentState = :state, attachmentAttempts = :attempts, " +
            "pdfPages = COALESCE(:pages, pdfPages), pdfBytes = COALESCE(:bytes, pdfBytes) " +
            "WHERE logId = :logId"
    )
    suspend fun updateAttachment(
        logId: String,
        state: String,
        attempts: Int,
        pages: Int?,
        bytes: Long?,
    )
}
