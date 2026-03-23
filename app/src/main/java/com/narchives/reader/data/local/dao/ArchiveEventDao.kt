package com.narchives.reader.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.narchives.reader.data.local.entity.ArchiveEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ArchiveEventDao {

    @Query("SELECT * FROM archive_events ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    suspend fun getAll(limit: Int = 50, offset: Int = 0): List<ArchiveEventEntity>

    @Query("SELECT * FROM archive_events ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ArchiveEventEntity>>

    @Query("SELECT * FROM archive_events WHERE sourceRelay = :relayUrl ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    suspend fun getByRelay(relayUrl: String, limit: Int = 50, offset: Int = 0): List<ArchiveEventEntity>

    @Query("SELECT * FROM archive_events WHERE sourceRelay = :relayUrl ORDER BY createdAt DESC")
    fun observeByRelay(relayUrl: String): Flow<List<ArchiveEventEntity>>

    @Query("SELECT * FROM archive_events WHERE authorPubkey = :pubkey ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    suspend fun getByAuthor(pubkey: String, limit: Int = 50, offset: Int = 0): List<ArchiveEventEntity>

    @Query("SELECT * FROM archive_events WHERE authorPubkey = :pubkey ORDER BY createdAt DESC")
    fun observeByAuthor(pubkey: String): Flow<List<ArchiveEventEntity>>

    @Query("SELECT * FROM archive_events WHERE archivedUrl LIKE '%' || :domain || '%' ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    suspend fun getByUrlDomain(domain: String, limit: Int = 50, offset: Int = 0): List<ArchiveEventEntity>

    @Query("SELECT * FROM archive_events WHERE archivedUrl LIKE '%' || :query || '%' OR title LIKE '%' || :query || '%' ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    suspend fun search(query: String, limit: Int = 50, offset: Int = 0): List<ArchiveEventEntity>

    @Query("SELECT * FROM archive_events WHERE eventId = :eventId")
    suspend fun getById(eventId: String): ArchiveEventEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<ArchiveEventEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: ArchiveEventEntity)

    @Query("DELETE FROM archive_events WHERE fetchedAt < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("SELECT COUNT(*) FROM archive_events")
    suspend fun count(): Int

    @Query("SELECT DISTINCT authorPubkey FROM archive_events")
    suspend fun getDistinctAuthors(): List<String>

    @Query("SELECT DISTINCT sourceRelay FROM archive_events")
    suspend fun getDistinctRelays(): List<String>
}
