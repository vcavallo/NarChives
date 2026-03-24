package com.narchives.reader.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.narchives.reader.data.local.dao.ArchiveEventDao
import com.narchives.reader.data.local.dao.ProfileDao
import com.narchives.reader.data.local.dao.RelayDao
import com.narchives.reader.data.local.dao.SavedArchiveDao
import com.narchives.reader.data.local.entity.ArchiveEventEntity
import com.narchives.reader.data.local.entity.ProfileEntity
import com.narchives.reader.data.local.entity.RelayEntity
import com.narchives.reader.data.local.entity.SavedArchiveEntity

@Database(
    entities = [
        ArchiveEventEntity::class,
        RelayEntity::class,
        ProfileEntity::class,
        SavedArchiveEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class NarchivesDatabase : RoomDatabase() {
    abstract fun archiveEventDao(): ArchiveEventDao
    abstract fun relayDao(): RelayDao
    abstract fun profileDao(): ProfileDao
    abstract fun savedArchiveDao(): SavedArchiveDao
}
