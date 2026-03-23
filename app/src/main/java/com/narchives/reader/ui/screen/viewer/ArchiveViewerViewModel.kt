package com.narchives.reader.ui.screen.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.narchives.reader.data.local.entity.ArchiveEventEntity
import com.narchives.reader.data.local.dao.SavedArchiveDao
import com.narchives.reader.data.repository.ArchiveRepository
import com.narchives.reader.replay.ReplayServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

enum class ViewerMode {
    LOADING,
    WACZ_REPLAY,   // Full WACZ replay via ReplayServer + WebView
    TEXT_CONTENT,   // Direct text/article content from the event
    ERROR,
}

data class ViewerUiState(
    val archive: ArchiveEventEntity? = null,
    val mode: ViewerMode = ViewerMode.LOADING,
    val serverUrl: String? = null,
    val waczSourceUrl: String? = null,
    val archivedPageUrl: String = "",
    val textContent: String? = null,
    val error: String? = null,
    val isSaved: Boolean = false,
)

class ArchiveViewerViewModel(
    private val eventId: String,
    private val archiveRepository: ArchiveRepository,
    private val replayServer: ReplayServer,
    private val savedArchiveDao: SavedArchiveDao,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ViewerUiState())
    val uiState: StateFlow<ViewerUiState> = _uiState

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

                val isSaved = savedArchiveDao.isSaved(eventId)
                _uiState.update { it.copy(archive = archive, isSaved = isSaved) }

                val hasWacz = archive.waczHash != null || archive.waczUrl != null

                if (!hasWacz) {
                    // This is a text-content event, not a WACZ archive
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
                            it.copy(
                                mode = ViewerMode.ERROR,
                                error = "This archive has no viewable content — it may be a reference without an attached WACZ file",
                            )
                        }
                    }
                    return@launch
                }

                // Has WACZ — try to resolve and serve it
                val savedArchive = savedArchiveDao.getByEventId(eventId)
                val localFile = savedArchive?.localWaczPath?.let { File(it) }
                    ?.takeIf { it.exists() }

                if (localFile != null) {
                    replayServer.setWaczSource(localFile = localFile)
                } else {
                    val waczUrl = archiveRepository.resolveWaczUrl(archive)
                    if (waczUrl == null) {
                        _uiState.update {
                            it.copy(
                                mode = ViewerMode.ERROR,
                                error = "WACZ file not available — could not find it on any known Blossom server",
                            )
                        }
                        return@launch
                    }
                    replayServer.setWaczSource(remoteUrl = waczUrl)
                }

                replayServer.start()

                _uiState.update {
                    it.copy(
                        mode = ViewerMode.WACZ_REPLAY,
                        serverUrl = replayServer.serverUrl,
                        waczSourceUrl = "${replayServer.serverUrl}/archive.wacz",
                        archivedPageUrl = archive.archivedUrl,
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(mode = ViewerMode.ERROR, error = e.message) }
            }
        }
    }

    fun retry() {
        _uiState.update { it.copy(mode = ViewerMode.LOADING, error = null) }
        loadArchive()
    }

    override fun onCleared() {
        super.onCleared()
        try { replayServer.stop() } catch (_: Exception) { }
    }
}
