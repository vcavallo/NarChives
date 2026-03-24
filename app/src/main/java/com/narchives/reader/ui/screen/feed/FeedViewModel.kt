package com.narchives.reader.ui.screen.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.narchives.reader.data.local.entity.ArchiveEventEntity
import com.narchives.reader.data.local.entity.ProfileEntity
import com.narchives.reader.data.repository.ArchiveRepository
import com.narchives.reader.data.repository.ProfileRepository
import com.narchives.reader.data.repository.RelayRepository
import com.narchives.reader.data.repository.SavedArchiveRepository
import com.narchives.reader.ui.util.extractDomain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FeedFilters(
    val waczOnly: Boolean = true,
    val selectedRelay: String? = null,
    val selectedDomain: String? = null,
    val selectedAuthor: String? = null,  // pubkey
    val searchQuery: String = "",
)

data class AuthorOption(
    val pubkey: String,
    val displayName: String,
)

data class FeedUiState(
    val allArchives: List<ArchiveEventEntity> = emptyList(),
    val filteredArchives: List<ArchiveEventEntity> = emptyList(),
    val profiles: Map<String, ProfileEntity> = emptyMap(),
    val savedEventIds: Set<String> = emptySet(),
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
    val filters: FeedFilters = FeedFilters(),
    val availableRelays: List<String> = emptyList(),
    val availableDomains: List<String> = emptyList(),
    val availableAuthors: List<AuthorOption> = emptyList(),
)

class FeedViewModel(
    private val archiveRepository: ArchiveRepository,
    private val profileRepository: ProfileRepository,
    private val relayRepository: RelayRepository,
    private val savedArchiveRepository: SavedArchiveRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FeedUiState())
    val uiState: StateFlow<FeedUiState> = _uiState

    init {
        loadFeed()
        observeArchives()
        observeSaved()
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

                // Fetch profiles
                val authorPubkeys = archives.map { it.authorPubkey }.distinct()
                val knownPubkeys = _uiState.value.profiles.keys
                val newPubkeys = authorPubkeys.filter { it !in knownPubkeys }
                if (newPubkeys.isNotEmpty()) {
                    val relayUrls = relayRepository.getEnabledRelayUrls()
                    profileRepository.fetchProfilesBatch(newPubkeys, relayUrls)
                }
                val profiles = archiveRepository.getProfilesForPubkeys(authorPubkeys)
                val profileMap = profiles.associateBy { it.pubkey }

                // Derive available authors with display names
                val authors = authorPubkeys.map { pk ->
                    val p = profileMap[pk]
                    AuthorOption(
                        pubkey = pk,
                        displayName = p?.displayName ?: p?.name ?: pk.take(12) + "...",
                    )
                }.sortedBy { it.displayName.lowercase() }

                _uiState.update { state ->
                    state.copy(
                        allArchives = archives,
                        profiles = profileMap,
                        availableRelays = relays,
                        availableDomains = domains,
                        availableAuthors = authors,
                    )
                }

                applyFilters()
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

                // Author filter
                val passesAuthorFilter = if (filters.selectedAuthor != null) {
                    archive.authorPubkey == filters.selectedAuthor
                } else true

                // Search query
                val passesSearch = if (filters.searchQuery.isNotEmpty()) {
                    archive.archivedUrl.contains(filters.searchQuery, ignoreCase = true) ||
                        archive.title?.contains(filters.searchQuery, ignoreCase = true) == true
                } else true

                passesWaczFilter && passesRelayFilter && passesDomainFilter && passesAuthorFilter && passesSearch
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

    fun setAuthorFilter(pubkey: String?) {
        _uiState.update { it.copy(filters = it.filters.copy(selectedAuthor = pubkey)) }
        applyFilters()
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(filters = it.filters.copy(searchQuery = query)) }
        applyFilters()
    }

    private fun observeSaved() {
        viewModelScope.launch {
            savedArchiveRepository.observeAll().collect { savedList ->
                _uiState.update { it.copy(savedEventIds = savedList.map { s -> s.eventId }.toSet()) }
            }
        }
    }

    fun toggleSaved(archive: ArchiveEventEntity) {
        viewModelScope.launch {
            if (_uiState.value.savedEventIds.contains(archive.eventId)) {
                savedArchiveRepository.remove(archive.eventId)
            } else {
                savedArchiveRepository.save(archive)
            }
        }
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
