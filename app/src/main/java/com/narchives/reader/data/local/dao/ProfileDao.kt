package com.narchives.reader.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.narchives.reader.data.local.entity.ProfileEntity

@Dao
interface ProfileDao {

    @Query("SELECT * FROM profiles WHERE pubkey = :pubkey")
    suspend fun getByPubkey(pubkey: String): ProfileEntity?

    @Query("SELECT * FROM profiles WHERE pubkey IN (:pubkeys)")
    suspend fun getByPubkeys(pubkeys: List<String>): List<ProfileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: ProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(profiles: List<ProfileEntity>)
}
