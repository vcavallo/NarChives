package com.narchives.reader.ui.screen.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.narchives.reader.data.local.entity.ArchiveEventEntity
import com.narchives.reader.data.local.entity.ProfileEntity
import com.narchives.reader.data.repository.ArchiveRepository
import com.narchives.reader.data.repository.ProfileRepository
import com.narchives.reader.data.repository.RelayRepository
import com.narchives.reader.ui.util.extractDomain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FeedFilters(
    val waczOnly: Boolean = true,        // Show only WACZ archives (not text articles)
    val selectedRelay: String? = null,    // null = all relays
    val selectedDomain: String? = null,   // null = all domains
    val searchQuery: String = "",
)

data class FeedUiState(
    val allArchives: List<ArchiveEventEntity> = emptyList(),
    val filteredArchives: List<ArchiveEventEntity> = emptyList(),
    val profiles: Map<String, ProfileEntity> = emptyMap(),
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
    val filters: FeedFilters = FeedFilters(),
    // Available filter options (derived from data)
    val availableRelays: List<String> = emptyList(),
    val availableDomains: List<String> = emptyList(),
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
                relayRepository.seedDefaultRelaysIfNeeded()
                val relayUrls = relayRepository.getEnabledRelayUrls()
                if (relayUrls.isEmpty()) {
                    _uiState.update { it.copy(isLoading = false, error = "No relays configured") }
                    return@launch
                }
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
                // Derive available filter options from full dataset
                val relays = archives.map { it.sourceRelay }.distinct().sorted()
                val domains = archives.map { extractDomain(it.archivedUrl) }
                    .filter { it.isNotBlank() && it != "undefined" }
                    .distinct().sorted()

                _uiState.update { state ->
                    state.copy(
                        allArchives = archives,
                        availableRelays = relays,
                        availableDomains = domains,
                    )
                }

                applyFilters()

                // Fetch profiles
                val authorPubkeys = archives.map { it.authorPubkey }.distinct()
                val knownPubkeys = _uiState.value.profiles.keys
                val newPubkeys = authorPubkeys.filter { it !in knownPubkeys }
                if (newPubkeys.isNotEmpty()) {
                    val relayUrls = relayRepository.getEnabledRelayUrls()
                    profileRepository.fetchProfilesBatch(newPubkeys, relayUrls)
                }
                val profiles = archiveRepository.getProfilesForPubkeys(authorPubkeys)
                _uiState.update { state ->
                    state.copy(profiles = profiles.associateBy { it.pubkey })
                }
            }
        }
    }

    private fun applyFilters() {
        _uiState.update { state ->
            val filtered = state.allArchives.filter { archive ->
                val filters = state.filters

                // WACZ-only filter: archive has waczUrl or waczHash
                val passesWaczFilter = if (filters.waczOnly) {
                    archive.waczUrl != null || archive.waczHash != null
                } else true

                // Relay filter
                val passesRelayFilter = if (filters.selectedRelay != null) {
                    archive.sourceRelay == filters.selectedRelay
                } else true

                // Domain filter
                val passesDomainFilter = if (filters.selectedDomain != null) {
                    extractDomain(archive.archivedUrl) == filters.selectedDomain
                } else true

                // Search query
                val passesSearch = if (filters.searchQuery.isNotEmpty()) {
                    archive.archivedUrl.contains(filters.searchQuery, ignoreCase = true) ||
                        archive.title?.contains(filters.searchQuery, ignoreCase = true) == true
                } else true

                passesWaczFilter && passesRelayFilter && passesDomainFilter && passesSearch
            }

            state.copy(filteredArchives = filtered)
        }
    }

    fun setWaczOnly(enabled: Boolean) {
        _uiState.update { it.copy(filters = it.filters.copy(waczOnly = enabled)) }
        applyFilters()
    }

    fun setRelayFilter(relay: String?) {
        _uiState.update { it.copy(filters = it.filters.copy(selectedRelay = relay)) }
        applyFilters()
    }

    fun setDomainFilter(domain: String?) {
        _uiState.update { it.copy(filters = it.filters.copy(selectedDomain = domain)) }
        applyFilters()
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(filters = it.filters.copy(searchQuery = query)) }
        applyFilters()
    }

    fun clearAllFilters() {
        _uiState.update { it.copy(filters = FeedFilters()) }
        applyFilters()
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.isLoadingMore || state.allArchives.isEmpty()) return

        val oldestTimestamp = state.allArchives.minOf { it.createdAt }
        _uiState.update { it.copy(isLoadingMore = true) }

        viewModelScope.launch {
            archiveRepository.loadMore(oldestTimestamp)
            // The new events will arrive via the observeAll() flow.
            // Reset isLoadingMore after a delay (events arrive asynchronously).
            kotlinx.coroutines.delay(3000)
            _uiState.update { it.copy(isLoadingMore = false) }
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
