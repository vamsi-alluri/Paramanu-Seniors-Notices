package org.paramanuseniorshealth.notices.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationDao {

    /** Newest first. `id` breaks ties when two messages share a timestamp. */
    @Query("SELECT * FROM notifications ORDER BY receivedAt DESC, id DESC")
    fun observeAll(): Flow<List<NotificationEntity>>

    /** Returns -1 when the row was ignored because [NotificationEntity.logId] already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(notification: NotificationEntity): Long

    @Query("DELETE FROM notifications")
    suspend fun deleteAll()

    /**
     * Deletes by primary key rather than by level, so the caller decides what a "level" means. The
     * UI already buckets rows through [org.paramanuseniorshealth.notices.ui.LevelFilter]; re-expressing that in SQL
     * would duplicate the casing and unknown-value rules and let the two drift apart.
     */
    @Query("DELETE FROM notifications WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}
