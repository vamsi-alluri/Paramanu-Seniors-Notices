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
     */
    @Query(
        "SELECT * FROM notices WHERE imageUrl IS NOT NULL OR pdfUrl IS NOT NULL " +
            "OR pdfThumbUrl IS NOT NULL OR linkImage IS NOT NULL ORDER BY receivedAt DESC"
    )
    suspend fun withAttachments(): List<NoticeEntity>

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
