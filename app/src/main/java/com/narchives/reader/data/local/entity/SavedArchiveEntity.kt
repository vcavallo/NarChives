package com.narchives.reader.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "saved_archives")
data class SavedArchiveEntity(
    @PrimaryKey val eventId: String,
    val localWaczPath: String? = null,
    val downloadedAt: Long? = null,
    val downloadStatus: String = "pending", // pending / downloading / complete / failed
    val savedAt: Long,
)
