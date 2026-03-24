package com.narchives.reader.data.repository

import com.narchives.reader.data.local.dao.RelayDao
import com.narchives.reader.data.local.entity.RelayEntity
import com.narchives.reader.data.preferences.UserPreferences
import kotlinx.coroutines.flow.Flow

class RelayRepository(
    private val relayDao: RelayDao,
    private val userPreferences: UserPreferences,
) {

    fun observeAll(): Flow<List<RelayEntity>> = relayDao.observeAll()

    suspend fun getEnabled(): List<RelayEntity> = relayDao.getEnabled()

    suspend fun getAll(): List<RelayEntity> = relayDao.getAll()

    suspend fun addRelay(url: String, name: String? = null) {
        relayDao.insert(
            RelayEntity(
                url = url,
                name = name,
                isEnabled = true,
                isDefault = false,
            )
        )
    }

    suspend fun removeRelay(relay: RelayEntity) {
        relayDao.delete(relay)
    }

    suspend fun setEnabled(url: String, enabled: Boolean) {
        relayDao.setEnabled(url, enabled)
    }

    /**
     * Seed the default relays into the database on first launch.
     */
    suspend fun seedDefaultRelaysIfNeeded() {
        if (relayDao.count() == 0) {
            UserPreferences.DEFAULT_RELAYS.forEach { url ->
                relayDao.insert(
                    RelayEntity(
                        url = url,
                        isEnabled = true,
                        isDefault = true,
                    )
                )
            }
        }
    }

    suspend fun getEnabledRelayUrls(): List<String> =
        relayDao.getEnabled().map { it.url }
}
