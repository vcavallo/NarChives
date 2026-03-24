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
 * Supports two event kinds for web archives:
 *
 * Kind 4554 (nostr-web-archiver by fiatjaf):
 *   - "url" tag: Blossom URL where WACZ file is stored (e.g. https://blossom.primal.net/<sha256>)
 *   - "page" tag: original URL of the archived page
 *   - "r" tag: domain name(s)
 *   - content: empty
 *   - The sha256 hash is embedded in the Blossom URL itself
 *
 * Kind 30041 (other publishers, text articles):
 *   - Various tag schemes (r, source, i, url, title, x, etc.)
 *   - content: may contain article text
 */
object EventMapper {

    /** Both kinds we treat as archive events */
    val ARCHIVE_KINDS = listOf(4554, 30041)

    private val json = Json { ignoreUnknownKeys = true }

    fun toArchiveEntity(event: Event, sourceRelay: String): ArchiveEventEntity? {
        return when (event.kind) {
            4554 -> mapKind4554(event, sourceRelay)
            30041 -> mapKind30041(event, sourceRelay)
            else -> null
        }
    }

    /**
     * Kind 4554: nostr-web-archiver events (actual WACZ archives on Blossom)
     *
     * Tags: ["url", "<blossom-url>"], ["page", "<original-url>"], ["r", "<domain>"]
     * The blossom URL contains the sha256 hash as the path component.
     */
    private fun mapKind4554(event: Event, sourceRelay: String): ArchiveEventEntity? {
        val blossomUrl = findFirstTagValue(event, "url")
        val pageUrl = findFirstTagValue(event, "page")
        val domain = findAllTagValues(event, "r")
            .firstOrNull { it != "undefined" }

        val archivedUrl = pageUrl ?: domain ?: blossomUrl
        if (archivedUrl == null) {
            Log.w(TAG, "kind 4554 event ${event.id.take(8)} has no page/r/url tag")
            return null
        }

        // Extract sha256 from the Blossom URL (it's the last path segment)
        val waczHash = blossomUrl?.substringAfterLast("/")?.takeIf {
            it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' }
        }

        // Collect all page URLs
        val allPageUrls = findAllTagValues(event, "page")

        val entity = ArchiveEventEntity(
            eventId = event.id,
            authorPubkey = event.pubKey,
            createdAt = event.createdAt,
            dTag = event.id, // kind 4554 is not addressable
            archivedUrl = archivedUrl,
            allUrls = json.encodeToString(allPageUrls.ifEmpty { listOf(archivedUrl) }),
            title = domain ?: extractDomain(archivedUrl), // No title tag; use domain
            waczHash = waczHash,
            waczUrl = blossomUrl, // Direct Blossom URL
            waczSize = null,
            mimeType = "application/wacz",
            description = null, // content is empty for kind 4554
            archiveMode = null,
            hasOts = false,
            tags = serializeTags(event.tags),
            sourceRelay = sourceRelay,
            fetchedAt = System.currentTimeMillis() / 1000,
        )
        Log.d(TAG, "Mapped 4554 event ${event.id.take(8)}: page=${pageUrl?.take(60)}, blossom=${blossomUrl?.take(60)}")
        return entity
    }

    /**
     * Kind 30041: text articles and other archive formats
     */
    private fun mapKind30041(event: Event, sourceRelay: String): ArchiveEventEntity? {
        val archivedUrl = findFirstTagValue(event, "r")
            ?: findFirstTagValue(event, "source")
            ?: findFirstTagValue(event, "i")
            ?: findFirstTagValue(event, "url")

        if (archivedUrl == null) {
            Log.w(TAG, "kind 30041 event ${event.id.take(8)} has no URL tag")
            return null
        }

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
        Log.d(TAG, "Mapped 30041 event ${event.id.take(8)}: title=${entity.title?.take(40)}, url=${entity.archivedUrl.take(60)}")
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

    private fun findAllTagValues(event: Event, tagName: String): List<String> {
        return event.tags.filter { it.size >= 2 && it[0] == tagName }.map { it[1] }
    }

    private fun extractDomain(url: String): String {
        return try {
            java.net.URI(url).host?.removePrefix("www.") ?: url
        } catch (_: Exception) {
            url
        }
    }

    private fun serializeTags(tags: Array<Array<String>>): String {
        val tagsList = tags.map { it.toList() }
        return json.encodeToString(tagsList)
    }
}
