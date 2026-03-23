package com.narchives.reader.data.repository

import android.util.Log
import com.narchives.reader.data.local.dao.ArchiveEventDao
import com.narchives.reader.data.local.dao.ProfileDao
import com.narchives.reader.data.local.dao.RelayDao
import com.narchives.reader.data.local.entity.ArchiveEventEntity
import com.narchives.reader.data.local.entity.ProfileEntity
import com.narchives.reader.data.preferences.UserPreferences
import com.narchives.reader.data.remote.blossom.BlossomClient
import com.narchives.reader.data.remote.nostr.EventMapper
import com.narchives.reader.data.remote.nostr.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.UUID

private const val TAG = "NarchivesArchiveRepo"

class ArchiveRepository(
    private val nostrClient: NostrClient,
    private val blossomClient: BlossomClient,
    private val archiveEventDao: ArchiveEventDao,
    private val profileDao: ProfileDao,
    private val relayDao: RelayDao,
    private val userPreferences: UserPreferences,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var activeSubscriptionId: String? = null

    init {
        // Listen for incoming events from relays and store them
        scope.launch {
            Log.d(TAG, "Started collecting events from NostrClient")
            nostrClient.events.collect { (relayUrl, event) ->
                Log.d(TAG, "Received event kind=${event.kind} from $relayUrl")
                when (event.kind) {
                    30041 -> {
                        val entity = EventMapper.toArchiveEntity(event, relayUrl)
                        Log.d(TAG, "Mapped kind 30041 event: ${entity?.title?.take(40) ?: "null"}")
                        if (entity != null) {
                            archiveEventDao.insert(entity)
                            // Update relay archive count
                            try {
                                val count = archiveEventDao.count()
                                relayDao.updateCount(relayUrl, count)
                            } catch (_: Exception) { }
                        }
                    }
                    0 -> {
                        val profile = EventMapper.toProfileEntity(event)
                        if (profile != null) {
                            profileDao.insert(profile)
                        }
                    }
                    10063 -> {
                        val servers = EventMapper.extractBlossomServers(event)
                        if (servers != null) {
                            val existing = profileDao.getByPubkey(event.pubKey)
                            if (existing != null) {
                                profileDao.insert(
                                    existing.copy(
                                        blossomServers = kotlinx.serialization.json.Json.encodeToString(servers),
                                        lastUpdated = System.currentTimeMillis() / 1000,
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    fun subscribeGlobalFeed(relayUrls: List<String>) {
        Log.i(TAG, "subscribeGlobalFeed with ${relayUrls.size} relays: $relayUrls")
        activeSubscriptionId?.let { nostrClient.unsubscribe(it) }

        val subId = "global-${UUID.randomUUID().toString().take(8)}"
        activeSubscriptionId = subId

        nostrClient.subscribe(
            subscriptionId = subId,
            relayUrls = relayUrls,
            filters = listOf(
                Filter(kinds = listOf(30041), limit = 50),
            ),
        )
    }

    fun subscribeByAuthor(relayUrls: List<String>, authorPubkey: String) {
        activeSubscriptionId?.let { nostrClient.unsubscribe(it) }

        val subId = "author-${UUID.randomUUID().toString().take(8)}"
        activeSubscriptionId = subId

        nostrClient.subscribe(
            subscriptionId = subId,
            relayUrls = relayUrls,
            filters = listOf(
                Filter(kinds = listOf(30041), authors = listOf(authorPubkey), limit = 50),
            ),
        )

        // Also fetch their profile and blossom servers
        val profileSubId = "profile-${UUID.randomUUID().toString().take(8)}"
        nostrClient.subscribe(
            subscriptionId = profileSubId,
            relayUrls = relayUrls,
            filters = listOf(
                Filter(kinds = listOf(0, 10063), authors = listOf(authorPubkey)),
            ),
        )
    }

    fun subscribeByRelay(relayUrl: String) {
        activeSubscriptionId?.let { nostrClient.unsubscribe(it) }

        val subId = "relay-${UUID.randomUUID().toString().take(8)}"
        activeSubscriptionId = subId

        nostrClient.subscribe(
            subscriptionId = subId,
            relayUrls = listOf(relayUrl),
            filters = listOf(
                Filter(kinds = listOf(30041), limit = 50),
            ),
        )
    }

    fun fetchProfilesForAuthors(relayUrls: List<String>, authorPubkeys: List<String>) {
        if (authorPubkeys.isEmpty()) return
        val subId = "profiles-${UUID.randomUUID().toString().take(8)}"
        nostrClient.subscribe(
            subscriptionId = subId,
            relayUrls = relayUrls,
            filters = listOf(
                Filter(kinds = listOf(0), authors = authorPubkeys),
            ),
        )
    }

    fun unsubscribe() {
        activeSubscriptionId?.let { nostrClient.unsubscribe(it) }
        activeSubscriptionId = null
    }

    // Room query wrappers
    fun observeAll(): Flow<List<ArchiveEventEntity>> = archiveEventDao.observeAll()

    fun observeByRelay(relayUrl: String): Flow<List<ArchiveEventEntity>> =
        archiveEventDao.observeByRelay(relayUrl)

    fun observeByAuthor(pubkey: String): Flow<List<ArchiveEventEntity>> =
        archiveEventDao.observeByAuthor(pubkey)

    suspend fun getById(eventId: String): ArchiveEventEntity? =
        archiveEventDao.getById(eventId)

    suspend fun search(query: String): List<ArchiveEventEntity> =
        archiveEventDao.search(query)

    suspend fun getByUrlDomain(domain: String): List<ArchiveEventEntity> =
        archiveEventDao.getByUrlDomain(domain)

    suspend fun getDistinctAuthors(): List<String> =
        archiveEventDao.getDistinctAuthors()

    suspend fun getProfilesForPubkeys(pubkeys: List<String>): List<ProfileEntity> =
        profileDao.getByPubkeys(pubkeys)

    suspend fun getProfile(pubkey: String): ProfileEntity? =
        profileDao.getByPubkey(pubkey)

    suspend fun resolveWaczUrl(archive: ArchiveEventEntity): String? {
        val profile = profileDao.getByPubkey(archive.authorPubkey)
        val authorServers = profile?.blossomServers?.let { json ->
            try {
                kotlinx.serialization.json.Json.decodeFromString<List<String>>(json)
            } catch (_: Exception) {
                emptyList()
            }
        } ?: emptyList()

        return blossomClient.resolveWaczUrl(
            directUrl = archive.waczUrl,
            sha256 = archive.waczHash,
            authorBlossomServers = authorServers,
            fallbackServers = UserPreferences.DEFAULT_BLOSSOM_SERVERS,
        )
    }
}
