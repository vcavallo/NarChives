package com.narchives.reader.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val pubkey: String,
    val name: String? = null,
    val displayName: String? = null,
    val about: String? = null,
    val pictureUrl: String? = null,
    val nip05: String? = null,
    val blossomServers: String? = null, // JSON array from kind 10063
    val lastUpdated: Long,
)
