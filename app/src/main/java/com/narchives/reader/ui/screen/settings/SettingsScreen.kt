package com.narchives.reader.ui.screen.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateToRelays: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showFontDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // RELAYS
            SectionHeader("RELAYS")
            SettingsItem(
                title = "Manage relays",
                subtitle = "Add, remove, and configure relays",
                onClick = onNavigateToRelays,
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // READER MODE
            SectionHeader("READER MODE")
            SettingsItem(
                title = "Theme",
                subtitle = uiState.theme.replaceFirstChar { it.uppercase() },
                onClick = { showThemeDialog = true },
            )
            SettingsItem(
                title = "Font family",
                subtitle = uiState.readerFont.replaceFirstChar { it.uppercase() },
                onClick = { showFontDialog = true },
            )
            SettingsItem(
                title = "Font size",
                subtitle = "${uiState.readerFontSize}sp",
                onClick = { /* TODO: font size slider */ },
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // STORAGE
            SectionHeader("STORAGE")
            SettingsItem(
                title = "WACZ cache",
                subtitle = "${uiState.cacheUsageMb} MB / ${uiState.waczCacheLimitMb} MB",
                onClick = {},
            )
            SettingsItem(
                title = "Clear cache",
                subtitle = "Remove cached archive files",
                onClick = { showClearCacheDialog = true },
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // ABOUT
            SectionHeader("ABOUT")

            val context = LocalContext.current

            SettingsItem(
                title = "Version",
                subtitle = "1.0.0",
                onClick = {},
            )

            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row {
                    Text(
                        text = "Vibed by ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "vinney...axkl",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            textDecoration = TextDecoration.Underline,
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://vinneycavallo.com")))
                        },
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "github.com/vcavallo/NarChives",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        textDecoration = TextDecoration.Underline,
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/vcavallo/NarChives")))
                    },
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // Dialogs
    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = { Text("Clear cache?") },
            text = { Text("This will remove all cached WACZ files. Saved archives will not be affected.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearCache()
                    showClearCacheDialog = false
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showThemeDialog) {
        ChoiceDialog(
            title = "Theme",
            options = listOf("system", "light", "dark"),
            selected = uiState.theme,
            onSelect = { viewModel.setTheme(it); showThemeDialog = false },
            onDismiss = { showThemeDialog = false },
        )
    }

    if (showFontDialog) {
        ChoiceDialog(
            title = "Font family",
            options = listOf("serif", "sans-serif", "monospace"),
            selected = uiState.readerFont,
            onSelect = { viewModel.setReaderFont(it); showFontDialog = false },
            onDismiss = { showFontDialog = false },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun SettingsItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChoiceDialog(
    title: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(option) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = option.replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (option == selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmButton = {},
    )
}
