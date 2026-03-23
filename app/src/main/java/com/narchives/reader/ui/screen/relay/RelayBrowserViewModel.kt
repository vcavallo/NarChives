package com.narchives.reader.ui.screen.relay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.narchives.reader.data.local.entity.RelayEntity
import com.narchives.reader.data.repository.RelayRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RelayBrowserUiState(
    val relays: List<RelayEntity> = emptyList(),
    val isLoading: Boolean = true,
    val showAddDialog: Boolean = false,
)

class RelayBrowserViewModel(
    private val relayRepository: RelayRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RelayBrowserUiState())
    val uiState: StateFlow<RelayBrowserUiState> = _uiState

    init {
        viewModelScope.launch {
            relayRepository.seedDefaultRelaysIfNeeded()
            relayRepository.observeAll().collect { relays ->
                _uiState.update { it.copy(relays = relays, isLoading = false) }
            }
        }
    }

    fun toggleRelay(url: String, enabled: Boolean) {
        viewModelScope.launch {
            relayRepository.setEnabled(url, enabled)
        }
    }

    fun addRelay(url: String) {
        val normalizedUrl = url.trim().let {
            if (!it.startsWith("wss://") && !it.startsWith("ws://")) "wss://$it" else it
        }
        viewModelScope.launch {
            relayRepository.addRelay(normalizedUrl)
            _uiState.update { it.copy(showAddDialog = false) }
        }
    }

    fun removeRelay(relay: RelayEntity) {
        viewModelScope.launch {
            relayRepository.removeRelay(relay)
        }
    }

    fun showAddDialog() {
        _uiState.update { it.copy(showAddDialog = true) }
    }

    fun hideAddDialog() {
        _uiState.update { it.copy(showAddDialog = false) }
    }
}
