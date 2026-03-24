package com.narchives.reader.replay

import android.content.Context
import android.util.Log
import com.narchives.reader.data.remote.blossom.BlossomClient
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.TimeUnit

private const val TAG = "NarchivesReplay"

/**
 * Local HTTP server that serves:
 * 1. ReplayWeb.page static assets (ui.js, sw.js) from bundled app assets
 * 2. A dynamically generated index.html with the source attribute pre-set
 * 3. WACZ files — either proxied from a remote Blossom server or served from local cache
 */
class ReplayServer(
    private val context: Context,
    private val blossomClient: BlossomClient,
    port: Int = 0,
) : NanoHTTPD("127.0.0.1", port) {

    private var currentWaczUrl: String? = null
    private var currentLocalWaczFile: File? = null
    private var currentPageUrl: String? = null

    private val proxyHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val serverUrl: String get() = "http://127.0.0.1:$listeningPort"

    fun setWaczSource(remoteUrl: String? = null, localFile: File? = null, pageUrl: String? = null) {
        currentWaczUrl = remoteUrl
        currentLocalWaczFile = localFile
        currentPageUrl = pageUrl
        Log.d(TAG, "setWaczSource: remote=$remoteUrl, local=$localFile, page=$pageUrl")
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        Log.d(TAG, "$method $uri (range=${session.headers["range"]})")

        return when {
            method == Method.OPTIONS -> {
                corsPreflightResponse()
            }
            uri == "/" || uri == "/index.html" -> {
                serveDynamicIndex()
            }
            // Service worker scope and replay page navigations — return minimal HTML
            // The service worker will intercept these and serve archived content
            uri == "/replay/" || uri.startsWith("/replay/w/") -> {
                Log.d(TAG, "Serving SW scope/navigation page: $uri")
                val html = "<!DOCTYPE html><html><head></head><body></body></html>"
                val bytes = html.toByteArray(Charsets.UTF_8)
                newFixedLengthResponse(
                    Response.Status.OK,
                    "text/html",
                    ByteArrayInputStream(bytes),
                    bytes.size.toLong(),
                )
            }
            uri.startsWith("/replay/") -> {
                serveAsset(uri.removePrefix("/"))
            }
            uri == "/archive.wacz" || uri.endsWith(".wacz") -> {
                serveWacz(session)
            }
            else -> {
                Log.w(TAG, "404: $uri")
                newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found: $uri")
            }
        }.also { addCorsHeaders(it) }
    }

    /**
     * Generate the index.html dynamically with source and url attributes pre-set.
     * This avoids JS bridge timing issues with the service worker.
     */
    private fun serveDynamicIndex(): Response {
        val waczSource = "$serverUrl/archive.wacz"
        val pageUrl = currentPageUrl ?: ""
        val urlAttr = if (pageUrl.isNotEmpty()) "url=\"$pageUrl\"" else ""

        val html = """
        <!DOCTYPE html>
        <html>
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>Narchives Viewer</title>
          <style>
            * { margin: 0; padding: 0; box-sizing: border-box; }
            html, body { width: 100%; height: 100%; overflow: hidden; }
            replay-web-page {
              display: block;
              width: 100vw;
              height: 100vh;
            }
          </style>
          <script src="/replay/ui.js"></script>
        </head>
        <body>
          <replay-web-page
            source="$waczSource"
            $urlAttr
            replayBase="/replay/"
            embed="replayonly"
          ></replay-web-page>
        </body>
        </html>
        """.trimIndent()

        val bytes = html.toByteArray(Charsets.UTF_8)
        return newFixedLengthResponse(
            Response.Status.OK,
            "text/html",
            ByteArrayInputStream(bytes),
            bytes.size.toLong(),
        )
    }

    private fun corsPreflightResponse(): Response {
        return newFixedLengthResponse(Response.Status.OK, "text/plain", "").also {
            addCorsHeaders(it)
        }
    }

    private fun addCorsHeaders(response: Response) {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Range, Content-Type")
        response.addHeader("Access-Control-Expose-Headers", "Content-Range, Content-Length, Accept-Ranges")
    }

    private fun serveAsset(assetPath: String): Response {
        return try {
            val inputStream = context.assets.open(assetPath)
            val mimeType = when {
                assetPath.endsWith(".html") -> "text/html"
                assetPath.endsWith(".js") -> "application/javascript"
                assetPath.endsWith(".css") -> "text/css"
                assetPath.endsWith(".json") -> "application/json"
                assetPath.endsWith(".wasm") -> "application/wasm"
                else -> "application/octet-stream"
            }
            val response = newChunkedResponse(Response.Status.OK, mimeType, inputStream)
            if (assetPath.endsWith("sw.js")) {
                response.addHeader("Service-Worker-Allowed", "/")
            }
            response
        } catch (e: Exception) {
            Log.e(TAG, "Asset not found: $assetPath", e)
            newFixedLengthResponse(
                Response.Status.NOT_FOUND, "text/plain", "Asset not found: $assetPath"
            )
        }
    }

    private fun serveWacz(session: IHTTPSession): Response {
        // Serve from local file if available
        currentLocalWaczFile?.let { file ->
            if (file.exists()) {
                Log.d(TAG, "Serving WACZ from local file: ${file.absolutePath}")
                return serveLocalFile(file, session)
            }
        }

        // Otherwise proxy from remote Blossom URL
        currentWaczUrl?.let { url ->
            Log.d(TAG, "Proxying WACZ from: $url")
            return proxyRemoteWacz(url, session)
        }

        Log.e(TAG, "No WACZ source configured")
        return newFixedLengthResponse(
            Response.Status.NOT_FOUND, "text/plain", "No WACZ source configured"
        )
    }

    private fun serveLocalFile(file: File, session: IHTTPSession): Response {
        val fileLength = file.length()
        val rangeHeader = session.headers["range"]

        if (rangeHeader != null) {
            val range = parseRange(rangeHeader, fileLength)
            if (range != null) {
                val (start, end) = range
                val contentLength = end - start + 1
                Log.d(TAG, "Local range: $start-$end/$fileLength ($contentLength bytes)")
                val inputStream = FileInputStream(file).apply {
                    if (start > 0) {
                        var skipped = 0L
                        while (skipped < start) {
                            skipped += skip(start - skipped)
                        }
                    }
                }
                val response = newFixedLengthResponse(
                    Response.Status.PARTIAL_CONTENT,
                    "application/wacz",
                    inputStream,
                    contentLength,
                )
                response.addHeader("Content-Range", "bytes $start-$end/$fileLength")
                response.addHeader("Accept-Ranges", "bytes")
                response.addHeader("Content-Length", contentLength.toString())
                return response
            }
        }

        val inputStream = FileInputStream(file)
        val response = newFixedLengthResponse(
            Response.Status.OK,
            "application/wacz",
            inputStream,
            fileLength,
        )
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Content-Length", fileLength.toString())
        return response
    }

    private fun proxyRemoteWacz(url: String, session: IHTTPSession): Response {
        val requestBuilder = Request.Builder().url(url)

        // Forward range header if present
        session.headers["range"]?.let { range ->
            requestBuilder.header("Range", range)
            Log.d(TAG, "Proxying with Range: $range")
        }

        return try {
            val remoteResponse = proxyHttpClient.newCall(requestBuilder.build()).execute()
            Log.d(TAG, "Blossom response: ${remoteResponse.code} content-length=${remoteResponse.header("content-length")} content-range=${remoteResponse.header("content-range")}")

            val body = remoteResponse.body ?: return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "text/plain", "Empty response from Blossom server"
            )

            val status = if (remoteResponse.code == 206) {
                Response.Status.PARTIAL_CONTENT
            } else {
                Response.Status.OK
            }

            val contentLength = body.contentLength()
            val response = newFixedLengthResponse(
                status,
                remoteResponse.header("content-type") ?: "application/wacz",
                body.byteStream(),
                contentLength,
            )

            // Forward relevant headers
            remoteResponse.header("content-range")?.let {
                response.addHeader("Content-Range", it)
            }
            response.addHeader("Accept-Ranges", "bytes")
            if (contentLength >= 0) {
                response.addHeader("Content-Length", contentLength.toString())
            }

            response
        } catch (e: Exception) {
            Log.e(TAG, "Failed to proxy from Blossom: ${e.message}", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain",
                "Failed to fetch from Blossom: ${e.message}",
            )
        }
    }

    private fun parseRange(rangeHeader: String, fileLength: Long): Pair<Long, Long>? {
        val match = Regex("bytes=(\\d*)-(\\d*)").find(rangeHeader) ?: return null
        val startStr = match.groupValues[1]
        val endStr = match.groupValues[2]

        return when {
            startStr.isNotEmpty() && endStr.isNotEmpty() -> {
                Pair(startStr.toLong(), minOf(endStr.toLong(), fileLength - 1))
            }
            startStr.isNotEmpty() -> {
                Pair(startStr.toLong(), fileLength - 1)
            }
            endStr.isNotEmpty() -> {
                val suffix = endStr.toLong()
                Pair(maxOf(fileLength - suffix, 0), fileLength - 1)
            }
            else -> null
        }
    }
}
