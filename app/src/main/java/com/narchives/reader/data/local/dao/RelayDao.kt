package com.narchives.reader.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.narchives.reader.data.local.entity.RelayEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RelayDao {

    @Query("SELECT * FROM relays WHERE isEnabled = 1 ORDER BY isDefault DESC, url ASC")
    suspend fun getEnabled(): List<RelayEntity>

    @Query("SELECT * FROM relays ORDER BY isDefault DESC, url ASC")
    suspend fun getAll(): List<RelayEntity>

    @Query("SELECT * FROM relays ORDER BY isDefault DESC, url ASC")
    fun observeAll(): Flow<List<RelayEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(relay: RelayEntity)

    @Delete
    suspend fun delete(relay: RelayEntity)

    @Query("UPDATE relays SET archiveEventCount = :count WHERE url = :url")
    suspend fun updateCount(url: String, count: Int)

    @Query("UPDATE relays SET isEnabled = :enabled WHERE url = :url")
    suspend fun setEnabled(url: String, enabled: Boolean)

    @Query("SELECT COUNT(*) FROM relays")
    suspend fun count(): Int
}
