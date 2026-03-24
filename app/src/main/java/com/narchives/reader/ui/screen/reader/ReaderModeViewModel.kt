package com.narchives.reader.ui.screen.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.narchives.reader.data.local.entity.ArchiveEventEntity
import com.narchives.reader.data.local.dao.SavedArchiveDao
import com.narchives.reader.data.repository.ArchiveRepository
import com.narchives.reader.data.remote.blossom.BlossomClient
import com.narchives.reader.data.preferences.UserPreferences
import com.narchives.reader.replay.ReaderArticle
import com.narchives.reader.replay.ReaderModeExtractor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class ReaderUiState(
    val article: ReaderArticle? = null,
    val archive: ArchiveEventEntity? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val fontSize: Int = 18,
    val fontFamily: String = "serif",
    val isDarkMode: Boolean = false,
)

class ReaderModeViewModel(
    private val eventId: String,
    private val archiveRepository: ArchiveRepository,
    private val savedArchiveDao: SavedArchiveDao,
    private val blossomClient: BlossomClient,
    private val userPreferences: UserPreferences,
    private val cacheDir: File,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState

    private val extractor = ReaderModeExtractor()

    init {
        loadPreferences()
        loadArticle()
    }

    private fun loadPreferences() {
        viewModelScope.launch {
            val fontSize = userPreferences.readerFontSize.first()
            val font = userPreferences.readerFont.first()
            _uiState.update { it.copy(fontSize = fontSize, fontFamily = font) }
        }
    }

    private fun loadArticle() {
        viewModelScope.launch {
            try {
                val archive = archiveRepository.getById(eventId)
                if (archive == null) {
                    _uiState.update { it.copy(isLoading = false, error = "Archive not found") }
                    return@launch
                }
                _uiState.update { it.copy(archive = archive) }

                // Try to get a local WACZ file
                val waczFile = getWaczFile(archive)
                if (waczFile == null) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Reader mode not available — could not access the archive file",
                        )
                    }
                    return@launch
                }

                val article = extractor.extractArticle(waczFile, archive.archivedUrl)
                if (article == null) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Reader mode not available for this archive",
                        )
                    }
                    return@launch
                }

                _uiState.update { it.copy(article = article, isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    private suspend fun getWaczFile(archive: ArchiveEventEntity): File? {
        // Check saved local file first
        val saved = savedArchiveDao.getByEventId(archive.eventId)
        saved?.localWaczPath?.let { path ->
            val file = File(path)
            if (file.exists()) return file
        }

        // Otherwise download to cache
        val sha256 = archive.waczHash ?: return null
        val cacheFile = File(cacheDir, "wacz/$sha256.wacz")
        if (cacheFile.exists()) return cacheFile

        val waczUrl = archiveRepository.resolveWaczUrl(archive) ?: return null
        val serverUrl = waczUrl.substringBeforeLast("/")

        val result = blossomClient.downloadBlob(serverUrl, sha256, cacheFile)
        return result.getOrNull()
    }

    fun setFontSize(size: Int) {
        _uiState.update { it.copy(fontSize = size) }
        viewModelScope.launch { userPreferences.setReaderFontSize(size) }
    }

    fun setFontFamily(font: String) {
        _uiState.update { it.copy(fontFamily = font) }
        viewModelScope.launch { userPreferences.setReaderFont(font) }
    }

    fun toggleDarkMode() {
        _uiState.update { it.copy(isDarkMode = !it.isDarkMode) }
    }
}
