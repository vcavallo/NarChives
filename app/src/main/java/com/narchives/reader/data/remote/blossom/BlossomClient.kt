package com.narchives.reader.data.remote.blossom

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

class BlossomClient(private val httpClient: OkHttpClient) {

    suspend fun hasBlob(serverUrl: String, sha256: String): Boolean = withContext(Dispatchers.IO) {
        val url = "${serverUrl.trimEnd('/')}/$sha256"
        val request = Request.Builder().url(url).head().build()
        try {
            val response = httpClient.newCall(request).execute()
            response.close()
            response.code == 200
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getBlobInfo(serverUrl: String, sha256: String): BlobInfo? = withContext(Dispatchers.IO) {
        val url = "${serverUrl.trimEnd('/')}/$sha256"
        val request = Request.Builder().url(url).head().build()
        try {
            val response = httpClient.newCall(request).execute()
            response.close()
            if (response.code == 200) {
                BlobInfo(
                    size = response.header("content-length")?.toLongOrNull() ?: 0,
                    contentType = response.header("content-type") ?: "application/octet-stream",
                    supportsRangeRequests = response.header("accept-ranges") == "bytes",
                )
            } else null
        } catch (e: Exception) {
            null
        }
    }

    suspend fun downloadBlob(
        serverUrl: String,
        sha256: String,
        destinationFile: File,
        onProgress: ((bytesDownloaded: Long, totalBytes: Long) -> Unit)? = null,
    ): Result<File> = withContext(Dispatchers.IO) {
        val url = "${serverUrl.trimEnd('/')}/$sha256.wacz"
        val request = Request.Builder().url(url).build()
        try {
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                return@withContext Result.failure(Exception("HTTP ${response.code}"))
            }

            val body = response.body ?: return@withContext Result.failure(Exception("Empty body"))
            val totalBytes = body.contentLength()
            var bytesDownloaded = 0L

            destinationFile.parentFile?.mkdirs()
            destinationFile.outputStream().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        bytesDownloaded += bytesRead
                        onProgress?.invoke(bytesDownloaded, totalBytes)
                    }
                }
            }

            // Verify SHA-256
            val actualHash = sha256Hash(destinationFile)
            if (actualHash != sha256) {
                destinationFile.delete()
                return@withContext Result.failure(
                    Exception("SHA-256 mismatch: expected $sha256, got $actualHash")
                )
            }

            Result.success(destinationFile)
        } catch (e: Exception) {
            destinationFile.delete()
            Result.failure(e)
        }
    }

    suspend fun resolveWaczUrl(
        directUrl: String?,
        sha256: String?,
        authorBlossomServers: List<String>,
        fallbackServers: List<String>,
    ): String? {
        if (sha256 == null && directUrl == null) return null

        // Try direct URL first
        if (directUrl != null) {
            try {
                val request = Request.Builder().url(directUrl).head().build()
                val response = withContext(Dispatchers.IO) {
                    httpClient.newCall(request).execute()
                }
                response.close()
                if (response.code == 200) return directUrl
            } catch (_: Exception) { }
        }

        // Try author's Blossom servers, then fallbacks
        if (sha256 != null) {
            val allServers = (authorBlossomServers + fallbackServers).distinct()
            for (server in allServers) {
                if (hasBlob(server, sha256)) {
                    return "${server.trimEnd('/')}/$sha256.wacz"
                }
            }
        }

        return null
    }

    private fun sha256Hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

data class BlobInfo(
    val size: Long,
    val contentType: String,
    val supportsRangeRequests: Boolean,
)
