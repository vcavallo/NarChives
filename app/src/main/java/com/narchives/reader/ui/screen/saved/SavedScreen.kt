package com.narchives.reader.ui.screen.saved

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.narchives.reader.ui.components.ArchiveCard
import com.narchives.reader.ui.components.EmptyState
import com.narchives.reader.ui.components.LoadingIndicator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedScreen(
    viewModel: SavedViewModel,
    onArchiveClick: (String) -> Unit,
    onAuthorClick: (String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Saved") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        when {
            uiState.isLoading -> LoadingIndicator(modifier = Modifier.padding(padding))
            uiState.savedArchives.isEmpty() -> EmptyState(
                title = "No saved archives",
                subtitle = "Archives you save for offline reading will appear here",
                modifier = Modifier.padding(padding),
            )
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(
                        items = uiState.savedArchives,
                        key = { it.first.eventId },
                    ) { (saved, archive) ->
                        if (archive != null) {
                            ArchiveCard(
                                archive = archive,
                                profile = uiState.profiles[archive.authorPubkey],
                                onClick = { onArchiveClick(archive.eventId) },
                                onAuthorClick = { onAuthorClick(archive.authorPubkey) },
                            )
                        }
                    }
                }
            }
        }
    }
}
