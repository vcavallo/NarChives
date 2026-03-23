package com.narchives.reader.ui.screen.viewer

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.narchives.reader.data.local.entity.ArchiveEventEntity
import com.narchives.reader.data.local.entity.SavedArchiveEntity
import com.narchives.reader.data.local.dao.SavedArchiveDao
import com.narchives.reader.data.repository.ArchiveRepository
import com.narchives.reader.replay.ReplayServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class ViewerUiState(
    val archive: ArchiveEventEntity? = null,
    val serverUrl: String? = null,
    val waczSourceUrl: String? = null,
    val archivedPageUrl: String = "",
    val isLoading: Boolean = true,
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
                    _uiState.update { it.copy(isLoading = false, error = "Archive not found") }
                    return@launch
                }

                val isSaved = savedArchiveDao.isSaved(eventId)
                _uiState.update { it.copy(archive = archive, isSaved = isSaved) }

                // Check for local file first
                val savedArchive = savedArchiveDao.getByEventId(eventId)
                val localFile = savedArchive?.localWaczPath?.let { File(it) }
                    ?.takeIf { it.exists() }

                if (localFile != null) {
                    // Serve from local file
                    replayServer.setWaczSource(localFile = localFile)
                } else {
                    // Resolve remote URL
                    val waczUrl = archiveRepository.resolveWaczUrl(archive)
                    if (waczUrl == null) {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = "Archive not available — the file may have been removed from all known servers",
                            )
                        }
                        return@launch
                    }
                    replayServer.setWaczSource(remoteUrl = waczUrl)
                }

                // Start the replay server
                replayServer.start()

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        serverUrl = replayServer.serverUrl,
                        waczSourceUrl = "${replayServer.serverUrl}/archive.wacz",
                        archivedPageUrl = archive.archivedUrl,
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun retry() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        loadArchive()
    }

    override fun onCleared() {
        super.onCleared()
        try {
            replayServer.stop()
        } catch (_: Exception) { }
    }
}
