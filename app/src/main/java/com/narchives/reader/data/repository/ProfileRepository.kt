package com.narchives.reader.data.repository

import com.narchives.reader.data.local.dao.ProfileDao
import com.narchives.reader.data.local.entity.ProfileEntity
import com.narchives.reader.data.remote.nostr.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import java.util.UUID

class ProfileRepository(
    private val nostrClient: NostrClient,
    private val profileDao: ProfileDao,
) {

    companion object {
        private const val PROFILE_CACHE_TTL_SECONDS = 86400L // 24 hours
    }

    suspend fun getProfile(pubkey: String): ProfileEntity? =
        profileDao.getByPubkey(pubkey)

    suspend fun getProfilesForPubkeys(pubkeys: List<String>): List<ProfileEntity> =
        profileDao.getByPubkeys(pubkeys)

    /**
     * Fetch profile from relays if not cached or stale.
     */
    suspend fun fetchProfileIfNeeded(pubkey: String, relayUrls: List<String>) {
        val existing = profileDao.getByPubkey(pubkey)
        val now = System.currentTimeMillis() / 1000

        if (existing != null && (now - existing.lastUpdated) < PROFILE_CACHE_TTL_SECONDS) {
            return // Cache is fresh
        }

        val subId = "prof-${UUID.randomUUID().toString().take(8)}"
        nostrClient.subscribe(
            subscriptionId = subId,
            relayUrls = relayUrls,
            filters = listOf(
                Filter(kinds = listOf(0, 10063), authors = listOf(pubkey)),
            ),
        )
        // Events will be processed by the ArchiveRepository's event collector
    }

    suspend fun fetchProfilesBatch(pubkeys: List<String>, relayUrls: List<String>) {
        if (pubkeys.isEmpty()) return
        val subId = "prof-batch-${UUID.randomUUID().toString().take(8)}"
        nostrClient.subscribe(
            subscriptionId = subId,
            relayUrls = relayUrls,
            filters = listOf(
                Filter(kinds = listOf(0), authors = pubkeys),
            ),
        )
    }
}
