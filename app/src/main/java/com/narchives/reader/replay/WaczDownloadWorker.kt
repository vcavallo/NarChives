package com.narchives.reader.replay

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.narchives.reader.NarchivesApp
import com.narchives.reader.data.preferences.UserPreferences
import java.io.File

/**
 * Background worker that downloads a WACZ file for offline reading.
 * Survives process death via WorkManager.
 */
class WaczDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val eventId = inputData.getString("eventId") ?: return Result.failure()
        val sha256 = inputData.getString("sha256") ?: return Result.failure()
        val directUrl = inputData.getString("directUrl")
        val blossomServers = inputData.getStringArray("blossomServers")?.toList() ?: emptyList()

        val container = (applicationContext as NarchivesApp).container
        val savedArchiveDao = container.database.savedArchiveDao()

        // Update status to downloading
        savedArchiveDao.updateDownloadStatus(eventId, "downloading")

        val destFile = File(applicationContext.filesDir, "saved_wacz/$sha256.wacz")
        destFile.parentFile?.mkdirs()

        // Resolve URL
        val url = container.blossomClient.resolveWaczUrl(
            directUrl = directUrl,
            sha256 = sha256,
            authorBlossomServers = blossomServers,
            fallbackServers = UserPreferences.DEFAULT_BLOSSOM_SERVERS,
        )

        if (url == null) {
            savedArchiveDao.updateDownloadStatus(eventId, "failed")
            return Result.failure()
        }

        // Extract server base URL from the resolved URL
        val serverUrl = url.substringBeforeLast("/")

        val result = container.blossomClient.downloadBlob(
            serverUrl = serverUrl,
            sha256 = sha256,
            destinationFile = destFile,
        ) { downloaded, total ->
            if (total > 0) {
                setProgressAsync(
                    workDataOf("progress" to (downloaded.toFloat() / total * 100).toInt())
                )
            }
        }

        return if (result.isSuccess) {
            savedArchiveDao.updateDownloadStatus(
                eventId = eventId,
                status = "complete",
                path = destFile.absolutePath,
                downloadedAt = System.currentTimeMillis() / 1000,
            )
            Result.success()
        } else {
            savedArchiveDao.updateDownloadStatus(eventId, "failed")
            Result.failure()
        }
    }
}
