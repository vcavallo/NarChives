package com.narchives.reader.ui.screen.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.narchives.reader.data.local.entity.ArchiveEventEntity
import com.narchives.reader.data.local.entity.ProfileEntity
import com.narchives.reader.data.repository.ArchiveRepository
import com.narchives.reader.data.repository.ProfileRepository
import com.narchives.reader.data.repository.RelayRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileUiState(
    val profile: ProfileEntity? = null,
    val archives: List<ArchiveEventEntity> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

class ProfileViewModel(
    private val pubkey: String,
    private val archiveRepository: ArchiveRepository,
    private val profileRepository: ProfileRepository,
    private val relayRepository: RelayRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState

    init {
        loadProfile()
        observeArchives()
    }

    private fun loadProfile() {
        viewModelScope.launch {
            try {
                val relayUrls = relayRepository.getEnabledRelayUrls()

                // Subscribe to this author's archives
                archiveRepository.subscribeByAuthor(relayUrls, pubkey)

                // Fetch profile
                profileRepository.fetchProfileIfNeeded(pubkey, relayUrls)

                // Load cached profile
                val profile = profileRepository.getProfile(pubkey)
                _uiState.update { it.copy(profile = profile, isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    private fun observeArchives() {
        viewModelScope.launch {
            archiveRepository.observeByAuthor(pubkey).collect { archives ->
                _uiState.update { it.copy(archives = archives) }
            }
        }

        // Also keep watching for profile updates
        viewModelScope.launch {
            // Simple polling for profile updates since we can't observe a single row easily
            kotlinx.coroutines.delay(2000)
            val profile = profileRepository.getProfile(pubkey)
            _uiState.update { it.copy(profile = profile) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        archiveRepository.unsubscribe()
    }
}
