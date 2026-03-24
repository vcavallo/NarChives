package com.narchives.reader.replay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.dankito.readability4j.Readability4J
import java.io.File
import java.net.URI
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

/**
 * Extracts readable article content from a WACZ file.
 * Opens the ZIP, finds the WARC data, locates the HTML response
 * for the target URL, and runs Readability4J on it.
 */
class ReaderModeExtractor {

    suspend fun extractArticle(waczFile: File, targetUrl: String): ReaderArticle? =
        withContext(Dispatchers.IO) {
            try {
                ZipFile(waczFile).use { zip ->
                    val html = extractHtmlFromWacz(zip, targetUrl)
                    if (html != null) {
                        parseArticle(html, targetUrl)
                    } else null
                }
            } catch (e: Exception) {
                null
            }
        }

    private fun extractHtmlFromWacz(zip: ZipFile, targetUrl: String): String? {
        // Find the WARC entry inside the WACZ
        val warcEntry = zip.entries().asSequence()
            .firstOrNull { it.name.endsWith(".warc") || it.name.endsWith(".warc.gz") }
            ?: return null

        val inputStream = if (warcEntry.name.endsWith(".gz")) {
            GZIPInputStream(zip.getInputStream(warcEntry))
        } else {
            zip.getInputStream(warcEntry)
        }

        // Parse WARC records to find the HTML response for our target URL.
        // WARC format: records separated by double CRLF, each starting with "WARC/1.0"
        val content = inputStream.bufferedReader().readText()
        return findHtmlInWarcContent(content, targetUrl)
    }

    private fun findHtmlInWarcContent(content: String, targetUrl: String): String? {
        // Split into WARC records
        val records = content.split(Regex("(?=WARC/1\\.(?:0|1)\\r?\\n)")).drop(1)

        for (record in records) {
            // Only look at response records
            if (!record.contains("WARC-Type: response")) continue

            // Check if this record is for our target URL (try both exact and normalized)
            val hasTargetUrl = record.contains("WARC-Target-URI: $targetUrl") ||
                record.contains("WARC-Target-URI: ${targetUrl.trimEnd('/')}")

            if (!hasTargetUrl) continue

            // Find the HTTP response body (after HTTP headers)
            // Structure: WARC headers \r\n\r\n HTTP status line + HTTP headers \r\n\r\n body
            val httpStart = record.indexOf("HTTP/")
            if (httpStart == -1) continue

            // Find end of HTTP headers (double newline after HTTP/ line)
            val bodyStart = record.indexOf("\r\n\r\n", httpStart)
            if (bodyStart == -1) continue

            val body = record.substring(bodyStart + 4)

            // Only return if it looks like HTML
            if (body.contains("<html", ignoreCase = true) ||
                body.contains("<!DOCTYPE", ignoreCase = true) ||
                body.contains("<head", ignoreCase = true)
            ) {
                return body
            }
        }

        // Fallback: try to find any HTML response record
        for (record in records) {
            if (!record.contains("WARC-Type: response")) continue
            if (!record.contains("content-type: text/html", ignoreCase = true) &&
                !record.contains("Content-Type: text/html", ignoreCase = true)
            ) continue

            val httpStart = record.indexOf("HTTP/")
            if (httpStart == -1) continue
            val bodyStart = record.indexOf("\r\n\r\n", httpStart)
            if (bodyStart == -1) continue

            val body = record.substring(bodyStart + 4)
            if (body.contains("<html", ignoreCase = true) || body.contains("<!DOCTYPE", ignoreCase = true)) {
                return body
            }
        }

        return null
    }

    private fun parseArticle(html: String, url: String): ReaderArticle {
        val article = Readability4J(url, html).parse()
        return ReaderArticle(
            title = article.title ?: "",
            content = article.articleContent?.toString() ?: article.textContent ?: "",
            textContent = article.textContent ?: "",
            excerpt = article.excerpt ?: "",
            byline = article.byline ?: "",
            siteName = extractDomain(url),
        )
    }

    private fun extractDomain(url: String): String {
        return try {
            URI(url).host?.removePrefix("www.") ?: url
        } catch (_: Exception) {
            url
        }
    }
}

data class ReaderArticle(
    val title: String,
    val content: String,      // HTML content (cleaned by Readability)
    val textContent: String,  // Plain text
    val excerpt: String,
    val byline: String,
    val siteName: String,
)
