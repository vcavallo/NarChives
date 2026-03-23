package com.narchives.reader.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "archive_events")
data class ArchiveEventEntity(
    @PrimaryKey val eventId: String,
    val authorPubkey: String,
    val createdAt: Long,
    val dTag: String,
    val archivedUrl: String,
    val allUrls: String,           // JSON array of all r-tag URLs
    val title: String?,
    val waczHash: String?,         // SHA-256 from x-tag
    val waczUrl: String?,          // Direct URL from url-tag
    val waczSize: Long?,
    val mimeType: String?,
    val description: String?,      // From event content field
    val archiveMode: String?,      // forensic/verified/personal
    val hasOts: Boolean,
    val tags: String,              // Full tags JSON for anything we didn't parse
    val sourceRelay: String,
    val fetchedAt: Long,
)
