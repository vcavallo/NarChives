package com.narchives.reader.data.repository

import android.content.Context
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.narchives.reader.data.local.dao.SavedArchiveDao
import com.narchives.reader.data.local.entity.ArchiveEventEntity
import com.narchives.reader.data.local.entity.SavedArchiveEntity
import com.narchives.reader.replay.WaczDownloadWorker
import kotlinx.coroutines.flow.Flow

class SavedArchiveRepository(
    private val savedArchiveDao: SavedArchiveDao,
    private val context: Context,
) {

    fun observeAll(): Flow<List<SavedArchiveEntity>> = savedArchiveDao.observeAll()

    suspend fun isSaved(eventId: String): Boolean = savedArchiveDao.isSaved(eventId)

    suspend fun save(archive: ArchiveEventEntity) {
        // Create the saved entry
        savedArchiveDao.insert(
            SavedArchiveEntity(
                eventId = archive.eventId,
                savedAt = System.currentTimeMillis() / 1000,
                downloadStatus = if (archive.waczHash != null || archive.waczUrl != null) "pending" else "complete",
            )
        )

        // Schedule download if it's a WACZ archive
        if (archive.waczHash != null || archive.waczUrl != null) {
            val inputData = Data.Builder()
                .putString("eventId", archive.eventId)
                .putString("sha256", archive.waczHash ?: "")
                .putString("directUrl", archive.waczUrl)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<WaczDownloadWorker>()
                .setInputData(inputData)
                .build()

            WorkManager.getInstance(context).enqueue(workRequest)
        }
    }

    suspend fun remove(eventId: String) {
        val saved = savedArchiveDao.getByEventId(eventId) ?: return
        savedArchiveDao.delete(saved)
    }
}
