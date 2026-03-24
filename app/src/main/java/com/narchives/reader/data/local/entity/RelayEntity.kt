package com.narchives.reader.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "relays")
data class RelayEntity(
    @PrimaryKey val url: String,
    val name: String? = null,
    val isEnabled: Boolean = true,
    val isDefault: Boolean = false,
    val lastConnected: Long? = null,
    val archiveEventCount: Int = 0,
)
