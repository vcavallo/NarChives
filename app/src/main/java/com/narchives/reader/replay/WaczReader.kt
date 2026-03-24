package com.narchives.reader.replay

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

private const val TAG = "NarchivesWaczReader"

/**
 * Reads and replays content from a WACZ (Web Archive Collection Zipped) file.
 *
 * A WACZ is a ZIP file containing:
 * - archive/data.warc.gz: Concatenated individually-gzipped WARC records (STORE mode in ZIP)
 * - indexes/index.cdx or indexes/index.cdx.gz: CDX index mapping URLs to offsets
 * - pages/pages.jsonl: List of archived pages with entry URL
 * - datapackage.json: Metadata
 *
 * The CDX index provides (url -> offset, length) within the WARC file.
 * Each WARC record at a given offset is individually gzipped.
 * Since the WARC file is stored uncompressed in the ZIP (STORE mode),
 * we can seek directly to any offset within it.
 */
class WaczReader(private val waczFile: File) : AutoCloseable {

    private val zipFile = ZipFile(waczFile)
    private val cdxIndex: Map<String, CdxEntry> = parseCdxIndex()
    private val warcEntryName: String = findWarcEntry()
    private val warcDataOffset: Long = calculateWarcDataOffset()

    /** The entry page URL from pages.jsonl */
    val entryUrl: String? = parseEntryUrl()

    /** Number of indexed resources */
    val resourceCount: Int get() = cdxIndex.size

    init {
        Log.i(TAG, "Opened WACZ: ${waczFile.name}, ${cdxIndex.size} resources, entryUrl=$entryUrl")
    }

    /**
     * Look up a URL in the archive and return its HTTP response.
     * Returns null if the URL is not in the archive.
     */
    fun getResource(url: String): WaczResponse? {
        // Try exact match first
        var entry = cdxIndex[url]

        // Try without fragment
        if (entry == null) {
            val noFragment = url.substringBefore("#")
            entry = cdxIndex[noFragment]
        }

        // Try http<->https swap
        if (entry == null) {
            val swapped = if (url.startsWith("https://")) {
                "http://" + url.removePrefix("https://")
            } else if (url.startsWith("http://")) {
                "https://" + url.removePrefix("http://")
            } else null
            if (swapped != null) entry = cdxIndex[swapped]
        }

        // Try without trailing slash
        if (entry == null && url.endsWith("/")) {
            entry = cdxIndex[url.removeSuffix("/")]
        }
        // Try with trailing slash
        if (entry == null && !url.endsWith("/")) {
            entry = cdxIndex["$url/"]
        }

        if (entry == null) {
            return null
        }

        return readWarcRecord(entry)
    }

    /**
     * Read the WARC record at the given CDX offset and parse out
     * the HTTP status, headers, and body.
     */
    private fun readWarcRecord(entry: CdxEntry): WaczResponse? {
        try {
            // Read the gzipped record from the WARC file within the ZIP
            val raf = RandomAccessFile(waczFile, "r")
            val absoluteOffset = warcDataOffset + entry.offset
            raf.seek(absoluteOffset)

            val compressedBytes = ByteArray(entry.length)
            raf.readFully(compressedBytes)
            raf.close()

            // Decompress the individual gzip record
            val decompressed = GZIPInputStream(ByteArrayInputStream(compressedBytes)).readBytes()

            // Parse: WARC headers \r\n\r\n HTTP response \r\n\r\n body
            val text = decompressed.toString(Charsets.ISO_8859_1)

            // Find end of WARC headers
            val warcHeaderEnd = text.indexOf("\r\n\r\n")
            if (warcHeaderEnd == -1) {
                Log.w(TAG, "No WARC header terminator for ${entry.url}")
                return null
            }

            val afterWarcHeaders = warcHeaderEnd + 4

            // Check WARC-Type — we only handle 'response' records
            val warcHeaders = text.substring(0, warcHeaderEnd)
            if (!warcHeaders.contains("WARC-Type: response", ignoreCase = true) &&
                !warcHeaders.contains("WARC-Type: revisit", ignoreCase = true)) {
                Log.d(TAG, "Skipping non-response WARC record for ${entry.url}")
                return null
            }

            // Parse HTTP status line
            val httpPart = text.substring(afterWarcHeaders)
            val statusLineEnd = httpPart.indexOf("\r\n")
            if (statusLineEnd == -1) {
                Log.w(TAG, "No HTTP status line for ${entry.url}")
                return null
            }
            val statusLine = httpPart.substring(0, statusLineEnd)
            val statusCode = parseStatusCode(statusLine)

            // Parse HTTP headers
            val httpHeaderEnd = httpPart.indexOf("\r\n\r\n")
            if (httpHeaderEnd == -1) {
                Log.w(TAG, "No HTTP header terminator for ${entry.url}")
                return null
            }

            val httpHeaderBlock = httpPart.substring(statusLineEnd + 2, httpHeaderEnd)
            val headers = parseHttpHeaders(httpHeaderBlock)

            // Extract body bytes (use the original byte array to preserve binary data)
            val bodyStartInText = afterWarcHeaders + httpHeaderEnd + 4
            val bodyBytes = decompressed.copyOfRange(bodyStartInText, decompressed.size)

            // Use mime from CDX if available, fall back to Content-Type header
            val mimeType = entry.mime.takeIf { it.isNotBlank() && it != "warc/revisit" }
                ?: headers["content-type"]?.split(";")?.firstOrNull()?.trim()
                ?: "application/octet-stream"

            return WaczResponse(
                statusCode = statusCode,
                mimeType = mimeType,
                headers = headers,
                body = bodyBytes,
                url = entry.url,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read WARC record for ${entry.url}: ${e.message}")
            return null
        }
    }

    private fun parseCdxIndex(): Map<String, CdxEntry> {
        val json = Json { ignoreUnknownKeys = true }
        val index = mutableMapOf<String, CdxEntry>()

        // Try both .cdx and .cdx.gz
        val cdxEntryName = zipFile.entries().asSequence()
            .map { it.name }
            .firstOrNull { it.startsWith("indexes/index.cdx") }

        if (cdxEntryName == null) {
            Log.e(TAG, "No CDX index found in WACZ")
            return emptyMap()
        }

        Log.d(TAG, "Reading CDX from: $cdxEntryName")

        val rawBytes = zipFile.getInputStream(zipFile.getEntry(cdxEntryName)).readBytes()
        val cdxText = if (cdxEntryName.endsWith(".gz")) {
            GZIPInputStream(ByteArrayInputStream(rawBytes)).readBytes().toString(Charsets.UTF_8)
        } else {
            rawBytes.toString(Charsets.UTF_8)
        }

        for (line in cdxText.lineSequence()) {
            if (line.isBlank() || line.startsWith("!")) continue
            try {
                // CDXJ format: SURT TIMESTAMP JSON
                val firstSpace = line.indexOf(' ')
                if (firstSpace == -1) continue
                val secondSpace = line.indexOf(' ', firstSpace + 1)
                if (secondSpace == -1) continue

                val jsonStr = line.substring(secondSpace + 1)
                val entry = json.decodeFromString<CdxEntry>(jsonStr)

                // Index by the original URL
                if (entry.url.isNotBlank() && !entry.url.startsWith("urn:")) {
                    index[entry.url] = entry
                }
            } catch (e: Exception) {
                // Skip malformed lines
            }
        }

        Log.i(TAG, "Parsed ${index.size} CDX entries")
        return index
    }

    private fun findWarcEntry(): String {
        return zipFile.entries().asSequence()
            .map { it.name }
            .firstOrNull { it.startsWith("archive/") && (it.endsWith(".warc.gz") || it.endsWith(".warc")) }
            ?: "archive/data.warc.gz"
    }

    /**
     * Calculate the absolute byte offset of the WARC data within the ZIP file.
     * Since the WARC is stored with STORE mode (no compression), we can compute:
     * absolute offset = local file header position + 30 + filename_len + extra_len
     *
     * ZIP local file header format (all little-endian):
     *   offset 0:  signature (4 bytes) = PK\x03\x04
     *   offset 26: filename length (2 bytes, little-endian)
     *   offset 28: extra field length (2 bytes, little-endian)
     *   offset 30: filename (variable)
     *   offset 30+fn_len: extra field (variable)
     *   offset 30+fn_len+extra_len: FILE DATA STARTS HERE
     */
    private fun calculateWarcDataOffset(): Long {
        // Scan the file for the local header of our WARC entry
        val raf = RandomAccessFile(waczFile, "r")
        val targetName = warcEntryName.toByteArray(Charsets.UTF_8)
        val sig = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        val buf = ByteArray(4)
        var pos = 0L

        while (pos < raf.length() - 30) {
            raf.seek(pos)
            raf.readFully(buf)
            if (buf.contentEquals(sig)) {
                // Read filename length and extra field length (little-endian uint16)
                raf.seek(pos + 26)
                val b1 = raf.read(); val b2 = raf.read()
                val fnLen = b1 or (b2 shl 8)
                val b3 = raf.read(); val b4 = raf.read()
                val extraLen = b3 or (b4 shl 8)

                if (fnLen == targetName.size) {
                    val fn = ByteArray(fnLen)
                    raf.readFully(fn)
                    if (fn.contentEquals(targetName)) {
                        raf.close()
                        val dataOffset = pos + 30 + fnLen + extraLen
                        Log.d(TAG, "WARC data offset in ZIP: $dataOffset (header=$pos, nameLen=$fnLen, extraLen=$extraLen)")
                        return dataOffset
                    }
                }
                // Skip past this entry's header + data
                pos += 30 + fnLen
            } else {
                pos++
            }
        }
        raf.close()
        throw IllegalStateException("Could not find local header for $warcEntryName")
    }

    private fun parseEntryUrl(): String? {
        try {
            val pagesEntry = zipFile.getEntry("pages/pages.jsonl") ?: return null
            val lines = zipFile.getInputStream(pagesEntry).bufferedReader().readLines()
            for (line in lines) {
                if (line.contains("\"url\"")) {
                    val json = Json { ignoreUnknownKeys = true }
                    val page = json.decodeFromString<PageEntry>(line)
                    if (page.url != null) return page.url
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse pages.jsonl: ${e.message}")
        }
        return null
    }

    private fun parseStatusCode(statusLine: String): Int {
        // "HTTP/1.1 200 OK" -> 200
        val parts = statusLine.split(" ", limit = 3)
        return parts.getOrNull(1)?.toIntOrNull() ?: 200
    }

    private fun parseHttpHeaders(headerBlock: String): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        for (line in headerBlock.split("\r\n")) {
            val colonIndex = line.indexOf(':')
            if (colonIndex > 0) {
                val key = line.substring(0, colonIndex).trim().lowercase()
                val value = line.substring(colonIndex + 1).trim()
                headers[key] = value
            }
        }
        return headers
    }

    override fun close() {
        zipFile.close()
    }
}

@Serializable
data class CdxEntry(
    val url: String,
    val mime: String = "",
    val offset: Long = 0,
    val length: Int = 0,
    val status: Int = 200,
    val filename: String = "",
    val digest: String = "",
    val recordDigest: String = "",
)

@Serializable
data class PageEntry(
    val url: String? = null,
    val title: String? = null,
)

data class WaczResponse(
    val statusCode: Int,
    val mimeType: String,
    val headers: Map<String, String>,
    val body: ByteArray,
    val url: String,
)
