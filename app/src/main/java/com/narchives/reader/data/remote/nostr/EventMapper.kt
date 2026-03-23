package com.narchives.reader.data.remote.nostr

import com.narchives.reader.data.local.entity.ArchiveEventEntity
import com.narchives.reader.data.local.entity.ProfileEntity
import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

/**
 * Maps Quartz Event objects to our Room entities.
 */
object EventMapper {

    private val json = Json { ignoreUnknownKeys = true }

    fun toArchiveEntity(event: Event, sourceRelay: String): ArchiveEventEntity? {
        if (event.kind != 30041) return null

        val archivedUrl = event.tags.firstOrNull { it.size >= 2 && it[0] == "r" }?.get(1)
            ?: return null

        val allUrls = event.tags
            .filter { it.size >= 2 && it[0] == "r" }
            .map { it[1] }

        return ArchiveEventEntity(
            eventId = event.id,
            authorPubkey = event.pubKey,
            createdAt = event.createdAt,
            dTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: event.id,
            archivedUrl = archivedUrl,
            allUrls = json.encodeToString(allUrls),
            title = event.tags.firstOrNull { it.size >= 2 && it[0] == "title" }?.get(1),
            waczHash = event.tags.firstOrNull { it.size >= 2 && it[0] == "x" }?.get(1),
            waczUrl = event.tags.firstOrNull { it.size >= 2 && it[0] == "url" }?.get(1),
            waczSize = event.tags.firstOrNull { it.size >= 2 && it[0] == "size" }?.get(1)?.toLongOrNull(),
            mimeType = event.tags.firstOrNull { it.size >= 2 && it[0] == "m" }?.get(1),
            description = event.content.takeIf { it.isNotBlank() },
            archiveMode = event.tags.firstOrNull { it.size >= 2 && it[0] == "mode" }?.get(1),
            hasOts = event.tags.any { it.isNotEmpty() && it[0] == "ots" },
            tags = serializeTags(event.tags),
            sourceRelay = sourceRelay,
            fetchedAt = System.currentTimeMillis() / 1000,
        )
    }

    fun toProfileEntity(event: Event): ProfileEntity? {
        if (event.kind != 0) return null

        return try {
            val content = json.parseToJsonElement(event.content)
            val obj = content as? kotlinx.serialization.json.JsonObject ?: return null

            ProfileEntity(
                pubkey = event.pubKey,
                name = obj["name"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content },
                displayName = obj["display_name"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content },
                about = obj["about"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content },
                pictureUrl = obj["picture"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content },
                nip05 = obj["nip05"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content },
                blossomServers = null, // Populated separately from kind 10063
                lastUpdated = System.currentTimeMillis() / 1000,
            )
        } catch (e: Exception) {
            null
        }
    }

    fun extractBlossomServers(event: Event): List<String>? {
        if (event.kind != 10063) return null
        return event.tags
            .filter { it.size >= 2 && it[0] == "server" }
            .map { it[1] }
    }

    private fun serializeTags(tags: Array<Array<String>>): String {
        val tagsList = tags.map { it.toList() }
        return json.encodeToString(tagsList)
    }
}
