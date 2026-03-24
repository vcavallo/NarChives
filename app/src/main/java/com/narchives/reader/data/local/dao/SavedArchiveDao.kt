package com.narchives.reader.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.narchives.reader.data.local.entity.SavedArchiveEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedArchiveDao {

    @Query("SELECT * FROM saved_archives ORDER BY savedAt DESC")
    suspend fun getAll(): List<SavedArchiveEntity>

    @Query("SELECT * FROM saved_archives ORDER BY savedAt DESC")
    fun observeAll(): Flow<List<SavedArchiveEntity>>

    @Query("SELECT * FROM saved_archives WHERE eventId = :eventId")
    suspend fun getByEventId(eventId: String): SavedArchiveEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(saved: SavedArchiveEntity)

    @Delete
    suspend fun delete(saved: SavedArchiveEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM saved_archives WHERE eventId = :eventId)")
    suspend fun isSaved(eventId: String): Boolean

    @Query("UPDATE saved_archives SET downloadStatus = :status, localWaczPath = :path, downloadedAt = :downloadedAt WHERE eventId = :eventId")
    suspend fun updateDownloadStatus(eventId: String, status: String, path: String? = null, downloadedAt: Long? = null)
}
