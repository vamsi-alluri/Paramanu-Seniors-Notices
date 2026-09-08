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
}
