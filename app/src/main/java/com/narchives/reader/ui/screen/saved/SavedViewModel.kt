package com.narchives.reader.ui.screen.saved

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.narchives.reader.data.local.entity.ArchiveEventEntity
import com.narchives.reader.data.local.entity.ProfileEntity
import com.narchives.reader.data.local.entity.SavedArchiveEntity
import com.narchives.reader.data.local.dao.SavedArchiveDao
import com.narchives.reader.data.repository.ArchiveRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SavedUiState(
    val savedArchives: List<Pair<SavedArchiveEntity, ArchiveEventEntity?>> = emptyList(),
    val profiles: Map<String, ProfileEntity> = emptyMap(),
    val isLoading: Boolean = true,
)

class SavedViewModel(
    private val savedArchiveDao: SavedArchiveDao,
    private val archiveRepository: ArchiveRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SavedUiState())
    val uiState: StateFlow<SavedUiState> = _uiState

    init {
        observeSaved()
    }

    private fun observeSaved() {
        viewModelScope.launch {
            savedArchiveDao.observeAll().collect { savedList ->
                val withArchives = savedList.map { saved ->
                    saved to archiveRepository.getById(saved.eventId)
                }

                val pubkeys = withArchives
                    .mapNotNull { it.second?.authorPubkey }
                    .distinct()
                val profiles = archiveRepository.getProfilesForPubkeys(pubkeys)

                _uiState.update {
                    it.copy(
                        savedArchives = withArchives,
                        profiles = profiles.associateBy { p -> p.pubkey },
                        isLoading = false,
                    )
                }
            }
        }
    }

    fun removeSaved(saved: SavedArchiveEntity) {
        viewModelScope.launch {
            savedArchiveDao.delete(saved)
        }
    }
}
