package com.narchives.reader.ui.screen.feed

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.narchives.reader.ui.components.ArchiveCard
import com.narchives.reader.ui.components.EmptyState
import com.narchives.reader.ui.components.ErrorState
import com.narchives.reader.ui.components.LoadingIndicator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    viewModel: FeedViewModel,
    onArchiveClick: (String) -> Unit,
    onAuthorClick: (String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    var showSearch by remember { mutableStateOf(false) }

    val hasActiveFilters = uiState.filters.let {
        !it.waczOnly || it.selectedRelay != null || it.selectedDomain != null || it.searchQuery.isNotEmpty()
    }
    // waczOnly=true is the default, so "active" means something beyond default
    val hasNonDefaultFilters = uiState.filters.let {
        it.selectedRelay != null || it.selectedDomain != null || it.selectedAuthor != null || it.searchQuery.isNotEmpty()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NarChives") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                actions = {
                    IconButton(onClick = { showSearch = !showSearch }) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Search bar
            if (showSearch) {
                OutlinedTextField(
                    value = uiState.filters.searchQuery,
                    onValueChange = viewModel::onSearchQueryChanged,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("Search archives...") },
                    singleLine = true,
                    trailingIcon = {
                        if (uiState.filters.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                )
            }

            // Filter chips row
            FilterBar(
                filters = uiState.filters,
                availableRelays = uiState.availableRelays,
                availableDomains = uiState.availableDomains,
                availableAuthors = uiState.availableAuthors,
                totalCount = uiState.allArchives.size,
                filteredCount = uiState.filteredArchives.size,
                onWaczOnlyChanged = viewModel::setWaczOnly,
                onRelaySelected = viewModel::setRelayFilter,
                onDomainSelected = viewModel::setDomainFilter,
                onAuthorSelected = viewModel::setAuthorFilter,
                onClearAll = if (hasNonDefaultFilters) viewModel::clearAllFilters else null,
            )

            when {
                uiState.isLoading -> LoadingIndicator()
                uiState.error != null -> ErrorState(
                    message = uiState.error!!,
                    onRetry = viewModel::refresh,
                )
                uiState.filteredArchives.isEmpty() -> EmptyState(
                    title = if (uiState.allArchives.isEmpty()) "No archives yet"
                    else "No archives match filters",
                    subtitle = if (uiState.allArchives.isEmpty()) "Pull to refresh or check relay connections"
                    else "${uiState.allArchives.size} total archives available",
                )
                else -> {
                    val listState = rememberLazyListState()

                    // Detect when scrolled near the bottom
                    val shouldLoadMore by remember {
                        derivedStateOf {
                            val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()
                                ?: return@derivedStateOf false
                            val totalItems = listState.layoutInfo.totalItemsCount
                            lastVisibleItem.index >= totalItems - 3
                        }
                    }

                    LaunchedEffect(shouldLoadMore) {
                        if (shouldLoadMore && !uiState.isLoadingMore) {
                            viewModel.loadMore()
                        }
                    }

                    PullToRefreshBox(
                        isRefreshing = uiState.isLoading,
                        onRefresh = viewModel::refresh,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(
                                items = uiState.filteredArchives,
                                key = { it.eventId },
                            ) { archive ->
                                ArchiveCard(
                                    archive = archive,
                                    profile = uiState.profiles[archive.authorPubkey],
                                    onClick = { onArchiveClick(archive.eventId) },
                                    onAuthorClick = { onAuthorClick(archive.authorPubkey) },
                                    onSaveClick = { viewModel.toggleSaved(archive) },
                                    isSaved = archive.eventId in uiState.savedEventIds,
                                )
                            }

                            // Loading more indicator
                            if (uiState.isLoadingMore) {
                                item {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        horizontalArrangement = Arrangement.Center,
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.height(24.dp).width(24.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterBar(
    filters: FeedFilters,
    availableRelays: List<String>,
    availableDomains: List<String>,
    availableAuthors: List<AuthorOption>,
    totalCount: Int,
    filteredCount: Int,
    onWaczOnlyChanged: (Boolean) -> Unit,
    onRelaySelected: (String?) -> Unit,
    onDomainSelected: (String?) -> Unit,
    onAuthorSelected: (String?) -> Unit,
    onClearAll: (() -> Unit)?,
) {
    var showRelayDropdown by remember { mutableStateOf(false) }
    var showDomainDropdown by remember { mutableStateOf(false) }
    var showAuthorDropdown by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // WACZ-only toggle
            FilterChip(
                selected = filters.waczOnly,
                onClick = { onWaczOnlyChanged(!filters.waczOnly) },
                label = { Text("WACZ only") },
            )

            // Relay selector
            FilterChipWithDropdown(
                label = filters.selectedRelay?.removePrefix("wss://")?.removeSuffix("/") ?: "All relays",
                isSelected = filters.selectedRelay != null,
                expanded = showRelayDropdown,
                onExpandToggle = { showRelayDropdown = it },
                options = listOf(null to "All relays") + availableRelays.map { it to it.removePrefix("wss://").removeSuffix("/") },
                selectedValue = filters.selectedRelay,
                onOptionSelected = { relay ->
                    onRelaySelected(relay)
                    showRelayDropdown = false
                },
            )

            // Domain selector
            FilterChipWithDropdown(
                label = filters.selectedDomain ?: "All domains",
                isSelected = filters.selectedDomain != null,
                expanded = showDomainDropdown,
                onExpandToggle = { showDomainDropdown = it },
                options = listOf(null to "All domains") + availableDomains.map { it to it },
                selectedValue = filters.selectedDomain,
                onOptionSelected = { domain ->
                    onDomainSelected(domain)
                    showDomainDropdown = false
                },
            )

            // Author selector
            val selectedAuthorName = filters.selectedAuthor?.let { pk ->
                availableAuthors.firstOrNull { it.pubkey == pk }?.displayName ?: pk.take(12) + "..."
            }
            FilterChipWithDropdown(
                label = selectedAuthorName ?: "All authors",
                isSelected = filters.selectedAuthor != null,
                expanded = showAuthorDropdown,
                onExpandToggle = { showAuthorDropdown = it },
                options = listOf(null to "All authors") + availableAuthors.map { it.pubkey to it.displayName },
                selectedValue = filters.selectedAuthor,
                onOptionSelected = { pubkey ->
                    onAuthorSelected(pubkey)
                    showAuthorDropdown = false
                },
            )

            // Clear all button
            if (onClearAll != null) {
                TextButton(onClick = onClearAll) {
                    Text("Clear", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        // Count indicator
        if (totalCount > 0) {
            Text(
                text = if (filteredCount == totalCount) "$totalCount archives"
                else "$filteredCount / $totalCount archives",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun <T> FilterChipWithDropdown(
    label: String,
    isSelected: Boolean,
    expanded: Boolean,
    onExpandToggle: (Boolean) -> Unit,
    options: List<Pair<T?, String>>,
    selectedValue: T?,
    onOptionSelected: (T?) -> Unit,
) {
    Column {
        FilterChip(
            selected = isSelected,
            onClick = { onExpandToggle(!expanded) },
            label = {
                Text(
                    text = label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandToggle(false) },
        ) {
            options.forEach { (value, display) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = display,
                            color = if (value == selectedValue) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    onClick = { onOptionSelected(value) },
                )
            }
        }
    }
}
