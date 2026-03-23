package com.narchives.reader.replay

import android.content.Context
import com.narchives.reader.data.remote.blossom.BlossomClient
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream

/**
 * Local HTTP server that serves:
 * 1. ReplayWeb.page static assets (ui.js, sw.js, index.html) from bundled app assets
 * 2. WACZ files — either proxied from a remote Blossom server or served from local cache
 *
 * This is the critical piece that makes WACZ replay work in Android WebView,
 * since service workers are unreliable there.
 */
class ReplayServer(
    private val context: Context,
    private val blossomClient: BlossomClient,
    port: Int = 0, // 0 = auto-assign
) : NanoHTTPD("127.0.0.1", port) {

    private var currentWaczUrl: String? = null
    private var currentLocalWaczFile: File? = null

    private val proxyHttpClient = OkHttpClient.Builder().build()

    val serverUrl: String get() = "http://127.0.0.1:$listeningPort"

    fun setWaczSource(remoteUrl: String? = null, localFile: File? = null) {
        currentWaczUrl = remoteUrl
        currentLocalWaczFile = localFile
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri

        // Add CORS headers to all responses
        return when {
            session.method == Method.OPTIONS -> {
                corsPreflightResponse()
            }
            uri == "/" || uri == "/index.html" -> {
                serveAsset("replay/index.html")
            }
            uri.startsWith("/replay/") -> {
                serveAsset(uri.removePrefix("/"))
            }
            uri == "/archive.wacz" || uri.endsWith(".wacz") -> {
                serveWacz(session)
            }
            else -> {
                newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found: $uri")
            }
        }.also { addCorsHeaders(it) }
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
            // Set Service-Worker-Allowed header for sw.js
            val response = newChunkedResponse(Response.Status.OK, mimeType, inputStream)
            if (assetPath.endsWith("sw.js")) {
                response.addHeader("Service-Worker-Allowed", "/")
            }
            response
        } catch (e: Exception) {
            newFixedLengthResponse(
                Response.Status.NOT_FOUND, "text/plain", "Asset not found: $assetPath"
            )
        }
    }

    private fun serveWacz(session: IHTTPSession): Response {
        // Serve from local file if available
        currentLocalWaczFile?.let { file ->
            if (file.exists()) {
                return serveLocalFile(file, session)
            }
        }

        // Otherwise proxy from remote Blossom URL
        currentWaczUrl?.let { url ->
            return proxyRemoteWacz(url, session)
        }

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

        // Full file response
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
        }

        return try {
            val remoteResponse = proxyHttpClient.newCall(requestBuilder.build()).execute()
            val body = remoteResponse.body ?: return newFixedLengthResponse(
                Response.Status.BAD_GATEWAY, "text/plain", "Empty response from Blossom server"
            )

            val status = if (remoteResponse.code == 206) {
                Response.Status.PARTIAL_CONTENT
            } else {
                Response.Status.OK
            }

            val response = newFixedLengthResponse(
                status,
                remoteResponse.header("content-type") ?: "application/wacz",
                body.byteStream(),
                body.contentLength(),
            )

            // Forward relevant headers
            remoteResponse.header("content-range")?.let {
                response.addHeader("Content-Range", it)
            }
            remoteResponse.header("accept-ranges")?.let {
                response.addHeader("Accept-Ranges", it)
            }
            response.addHeader("Content-Length", body.contentLength().toString())

            response
        } catch (e: Exception) {
            newFixedLengthResponse(
                Response.Status.BAD_GATEWAY,
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
