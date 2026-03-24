package com.narchives.reader.replay

import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebViewClient
import android.webkit.WebView
import java.io.ByteArrayInputStream

private const val TAG = "NarchivesReplayWV"

/**
 * WebViewClient that intercepts all requests and serves content from a WACZ archive.
 *
 * This completely bypasses the need for service workers — every request the WebView
 * makes (navigations, JS, CSS, images, fonts, XHR) is intercepted at the Android level
 * and served directly from the WARC data inside the WACZ file.
 */
class WaczReplayWebViewClient(
    private val waczReader: WaczReader,
) : WebViewClient() {

    companion object {
        private const val MAX_REDIRECTS = 5
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val url = request.url.toString()

        // Don't intercept data: or blob: URLs
        if (url.startsWith("data:") || url.startsWith("blob:") || url.startsWith("about:")) {
            return null
        }

        // Resolve the URL, following redirects within the archive
        val response = resolveWithRedirects(url)

        if (response != null) {
            Log.d(TAG, "HIT  ${request.method} ${url.take(100)} -> ${response.statusCode} ${response.mimeType} (${response.body.size} bytes)")

            val headers = mutableMapOf<String, String>()
            response.headers["content-type"]?.let { headers["Content-Type"] = it }
            response.headers["cache-control"]?.let { headers["Cache-Control"] = it }
            // Disable CSP/CORS since we're serving everything locally
            headers["Access-Control-Allow-Origin"] = "*"
            // Don't forward content-encoding — WARC record is already decompressed
            // Don't forward content-security-policy — it may block inline scripts

            val encoding = response.headers["content-type"]
                ?.let { ct ->
                    val charsetMatch = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(ct)
                    charsetMatch?.groupValues?.get(1)
                }

            // WebResourceResponse does NOT allow 3xx status codes.
            // We've already followed redirects, so use 200.
            val safeStatusCode = if (response.statusCode in 200..299 || response.statusCode >= 400) {
                response.statusCode
            } else {
                200 // Treat anything else (1xx, 3xx we shouldn't hit) as 200
            }

            return WebResourceResponse(
                response.mimeType,
                encoding,
                safeStatusCode,
                "OK",
                headers,
                ByteArrayInputStream(response.body),
            )
        } else {
            Log.d(TAG, "MISS ${request.method} ${url.take(100)}")
            val notFoundBody = "<!-- Not in archive: $url -->".toByteArray()
            return WebResourceResponse(
                "text/html",
                "UTF-8",
                404,
                "Not Found",
                mapOf("Content-Type" to "text/html"),
                ByteArrayInputStream(notFoundBody),
            )
        }
    }

    /**
     * Look up a URL in the archive, following any redirect chains.
     * If the WARC record is a 3xx redirect with a Location header,
     * follow the redirect within the archive up to MAX_REDIRECTS times.
     */
    private fun resolveWithRedirects(url: String): WaczResponse? {
        var currentUrl = url
        var hops = 0

        while (hops < MAX_REDIRECTS) {
            val response = waczReader.getResource(currentUrl) ?: return null

            if (response.statusCode in 300..399) {
                val location = response.headers["location"]
                if (location == null) {
                    Log.w(TAG, "Redirect without Location header for $currentUrl")
                    // Return the body anyway (some 304s have no location)
                    return response
                }
                // Resolve relative redirect
                val resolvedLocation = resolveUrl(currentUrl, location)
                Log.d(TAG, "REDIRECT $currentUrl -> $resolvedLocation (${response.statusCode})")
                currentUrl = resolvedLocation
                hops++
            } else {
                return response
            }
        }

        Log.w(TAG, "Too many redirects for $url")
        return null
    }

    /**
     * Resolve a potentially relative URL against a base URL.
     */
    private fun resolveUrl(base: String, relative: String): String {
        if (relative.startsWith("http://") || relative.startsWith("https://")) {
            return relative
        }
        return try {
            java.net.URI(base).resolve(relative).toString()
        } catch (_: Exception) {
            relative
        }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        Log.d(TAG, "Page finished: $url")
    }
}
