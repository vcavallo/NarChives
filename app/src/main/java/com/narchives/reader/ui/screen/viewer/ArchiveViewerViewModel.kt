package com.narchives.reader.ui.screen.viewer

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.narchives.reader.data.local.entity.ArchiveEventEntity
import com.narchives.reader.data.local.dao.SavedArchiveDao
import com.narchives.reader.data.remote.blossom.BlossomClient
import com.narchives.reader.data.repository.ArchiveRepository
import com.narchives.reader.replay.WaczReader
import com.narchives.reader.replay.WaczReplayWebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

private const val TAG = "NarchivesViewer"

enum class ViewerMode {
    LOADING,
    DOWNLOADING,
    WACZ_REPLAY,
    TEXT_CONTENT,
    ERROR,
}

data class ViewerUiState(
    val archive: ArchiveEventEntity? = null,
    val mode: ViewerMode = ViewerMode.LOADING,
    val entryUrl: String? = null,
    val webViewClient: WaczReplayWebViewClient? = null,
    val textContent: String? = null,
    val archivedPageUrl: String = "",
    val error: String? = null,
    val downloadProgress: Int = 0, // 0-100
)

class ArchiveViewerViewModel(
    private val eventId: String,
    private val archiveRepository: ArchiveRepository,
    private val savedArchiveDao: SavedArchiveDao,
    private val blossomClient: BlossomClient,
    private val cacheDir: File,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ViewerUiState())
    val uiState: StateFlow<ViewerUiState> = _uiState

    private var waczReader: WaczReader? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    init {
        loadArchive()
    }

    private fun loadArchive() {
        viewModelScope.launch {
            try {
                val archive = archiveRepository.getById(eventId)
                if (archive == null) {
                    _uiState.update { it.copy(mode = ViewerMode.ERROR, error = "Archive not found") }
                    return@launch
                }
                _uiState.update { it.copy(archive = archive) }

                val hasWacz = archive.waczHash != null || archive.waczUrl != null

                if (!hasWacz) {
                    // Text content event
                    val content = archive.description
                    if (content != null && content.isNotBlank()) {
                        _uiState.update {
                            it.copy(
                                mode = ViewerMode.TEXT_CONTENT,
                                textContent = content,
                                archivedPageUrl = archive.archivedUrl,
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(mode = ViewerMode.ERROR, error = "No viewable content")
                        }
                    }
                    return@launch
                }

                // Get a local WACZ file (from saved, cache, or download)
                val waczFile = resolveWaczFile(archive)
                if (waczFile == null) {
                    _uiState.update {
                        it.copy(
                            mode = ViewerMode.ERROR,
                            error = "Could not download the WACZ archive",
                        )
                    }
                    return@launch
                }

                // Open the WACZ and create the replay client
                val reader = withContext(Dispatchers.IO) {
                    WaczReader(waczFile)
                }
                waczReader = reader

                val entryUrl = reader.entryUrl ?: archive.archivedUrl
                val client = WaczReplayWebViewClient(reader)

                Log.i(TAG, "WACZ ready: ${reader.resourceCount} resources, entryUrl=$entryUrl")

                _uiState.update {
                    it.copy(
                        mode = ViewerMode.WACZ_REPLAY,
                        entryUrl = entryUrl,
                        webViewClient = client,
                        archivedPageUrl = archive.archivedUrl,
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load archive", e)
                _uiState.update { it.copy(mode = ViewerMode.ERROR, error = e.message) }
            }
        }
    }

    private suspend fun resolveWaczFile(archive: ArchiveEventEntity): File? {
        // 1. Check saved local file
        val saved = savedArchiveDao.getByEventId(archive.eventId)
        saved?.localWaczPath?.let { path ->
            val file = File(path)
            if (file.exists()) {
                Log.d(TAG, "Using saved WACZ: $path")
                return file
            }
        }

        // 2. Check cache
        val hash = archive.waczHash
        if (hash != null) {
            val cacheFile = File(cacheDir, "wacz/$hash.wacz")
            if (cacheFile.exists()) {
                Log.d(TAG, "Using cached WACZ: ${cacheFile.absolutePath}")
                return cacheFile
            }
        }

        // 3. Resolve URL and download
        val waczUrl = archiveRepository.resolveWaczUrl(archive) ?: return null
        Log.i(TAG, "Downloading WACZ from: $waczUrl")
        _uiState.update { it.copy(mode = ViewerMode.DOWNLOADING) }

        return downloadWacz(waczUrl, hash)
    }

    private suspend fun downloadWacz(url: String, hash: String?): File? = withContext(Dispatchers.IO) {
        val destFile = if (hash != null) {
            File(cacheDir, "wacz/$hash.wacz")
        } else {
            File(cacheDir, "wacz/${System.currentTimeMillis()}.wacz")
        }
        destFile.parentFile?.mkdirs()

        try {
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.e(TAG, "Download failed: HTTP ${response.code}")
                response.close()
                return@withContext null
            }

            val body = response.body ?: return@withContext null
            val totalBytes = body.contentLength()
            var downloaded = 0L

            destFile.outputStream().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        if (totalBytes > 0) {
                            val progress = (downloaded * 100 / totalBytes).toInt()
                            _uiState.update { it.copy(downloadProgress = progress) }
                        }
                    }
                }
            }

            Log.i(TAG, "Downloaded ${destFile.length()} bytes to ${destFile.absolutePath}")
            destFile
        } catch (e: Exception) {
            Log.e(TAG, "Download error: ${e.message}", e)
            destFile.delete()
            null
        }
    }

    fun retry() {
        _uiState.update { it.copy(mode = ViewerMode.LOADING, error = null, downloadProgress = 0) }
        loadArchive()
    }

    override fun onCleared() {
        super.onCleared()
        waczReader?.close()
    }
}
