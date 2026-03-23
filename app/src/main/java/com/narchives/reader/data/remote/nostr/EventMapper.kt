package com.narchives.reader.data.remote.nostr

import android.util.Log
import com.narchives.reader.data.local.entity.ArchiveEventEntity
import com.narchives.reader.data.local.entity.ProfileEntity
import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

private const val TAG = "NarchivesEventMapper"

/**
 * Maps Quartz Event objects to our Room entities.
 *
 * Kind 30041 events in the wild use various tag schemes:
 * - "r" tag for archived URL (spec)
 * - "source" tag for source URL (some publishers)
 * - "i" tag for input URL (some publishers)
 * - "url" tag for direct WACZ file URL
 * - "x" tag for SHA-256 hash
 * - "title" tag for page title
 * - "d" tag for addressable event identifier
 */
object EventMapper {

    private val json = Json { ignoreUnknownKeys = true }

    fun toArchiveEntity(event: Event, sourceRelay: String): ArchiveEventEntity? {
        if (event.kind != 30041) return null

        // Try multiple tag names for the archived URL
        val archivedUrl = findFirstTagValue(event, "r")
            ?: findFirstTagValue(event, "source")
            ?: findFirstTagValue(event, "i")
            ?: findFirstTagValue(event, "url")

        if (archivedUrl == null) {
            Log.w(TAG, "kind 30041 event ${event.id.take(8)} has no URL tag (tried r, source, i, url). Tags: ${event.tags.map { it.toList() }}")
            return null
        }

        // Collect all URL-like tags
        val allUrls = listOfNotNull(
            findFirstTagValue(event, "r"),
            findFirstTagValue(event, "source"),
            findFirstTagValue(event, "i"),
        ).distinct()

        val title = findFirstTagValue(event, "title")
            ?: findFirstTagValue(event, "subject")

        val entity = ArchiveEventEntity(
            eventId = event.id,
            authorPubkey = event.pubKey,
            createdAt = event.createdAt,
            dTag = findFirstTagValue(event, "d") ?: event.id,
            archivedUrl = archivedUrl,
            allUrls = json.encodeToString(allUrls.ifEmpty { listOf(archivedUrl) }),
            title = title,
            waczHash = findFirstTagValue(event, "x"),
            waczUrl = findFirstTagValue(event, "url"),
            waczSize = findFirstTagValue(event, "size")?.toLongOrNull(),
            mimeType = findFirstTagValue(event, "m"),
            description = event.content.takeIf { it.isNotBlank() },
            archiveMode = findFirstTagValue(event, "mode"),
            hasOts = event.tags.any { it.isNotEmpty() && it[0] == "ots" },
            tags = serializeTags(event.tags),
            sourceRelay = sourceRelay,
            fetchedAt = System.currentTimeMillis() / 1000,
        )
        Log.d(TAG, "Mapped event ${event.id.take(8)}: title=${entity.title?.take(40)}, url=${entity.archivedUrl.take(60)}")
        return entity
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
                blossomServers = null,
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

    private fun findFirstTagValue(event: Event, tagName: String): String? {
        return event.tags.firstOrNull { it.size >= 2 && it[0] == tagName }?.get(1)
    }

    private fun serializeTags(tags: Array<Array<String>>): String {
        val tagsList = tags.map { it.toList() }
        return json.encodeToString(tagsList)
    }
}
