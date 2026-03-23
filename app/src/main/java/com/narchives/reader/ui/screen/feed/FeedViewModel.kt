package com.narchives.reader.ui.screen.feed

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

data class FeedUiState(
    val archives: List<ArchiveEventEntity> = emptyList(),
    val profiles: Map<String, ProfileEntity> = emptyMap(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val searchQuery: String = "",
)

class FeedViewModel(
    private val archiveRepository: ArchiveRepository,
    private val profileRepository: ProfileRepository,
    private val relayRepository: RelayRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FeedUiState())
    val uiState: StateFlow<FeedUiState> = _uiState

    init {
        loadFeed()
        observeArchives()
    }

    private fun loadFeed() {
        viewModelScope.launch {
            try {
                // Seed defaults if first run
                relayRepository.seedDefaultRelaysIfNeeded()

                val relayUrls = relayRepository.getEnabledRelayUrls()
                if (relayUrls.isEmpty()) {
                    _uiState.update { it.copy(isLoading = false, error = "No relays configured") }
                    return@launch
                }

                // Connect and subscribe
                archiveRepository.subscribeGlobalFeed(relayUrls)
                _uiState.update { it.copy(isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    private fun observeArchives() {
        viewModelScope.launch {
            archiveRepository.observeAll().collect { archives ->
                _uiState.update { state ->
                    state.copy(
                        archives = if (state.searchQuery.isEmpty()) archives
                        else archives.filter { archive ->
                            archive.archivedUrl.contains(state.searchQuery, ignoreCase = true) ||
                                archive.title?.contains(state.searchQuery, ignoreCase = true) == true
                        },
                    )
                }
                // Fetch profiles for new authors
                val authorPubkeys = archives.map { it.authorPubkey }.distinct()
                val knownPubkeys = _uiState.value.profiles.keys
                val newPubkeys = authorPubkeys.filter { it !in knownPubkeys }

                if (newPubkeys.isNotEmpty()) {
                    val relayUrls = relayRepository.getEnabledRelayUrls()
                    profileRepository.fetchProfilesBatch(newPubkeys, relayUrls)
                }

                // Load cached profiles
                val profiles = archiveRepository.getProfilesForPubkeys(authorPubkeys)
                _uiState.update { state ->
                    state.copy(profiles = profiles.associateBy { it.pubkey })
                }
            }
        }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        viewModelScope.launch {
            if (query.isEmpty()) {
                // Reset to full feed
                archiveRepository.observeAll()
            } else {
                val results = archiveRepository.search(query)
                _uiState.update { it.copy(archives = results) }
            }
        }
    }

    fun refresh() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        loadFeed()
    }

    override fun onCleared() {
        super.onCleared()
        archiveRepository.unsubscribe()
    }
}
