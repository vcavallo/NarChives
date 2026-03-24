package com.narchives.reader.ui.screen.viewer

import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.narchives.reader.replay.WaczReplayWebViewClient
import com.narchives.reader.ui.components.ErrorState
import com.narchives.reader.ui.components.LoadingIndicator
import com.narchives.reader.ui.util.extractDomain

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveViewerScreen(
    viewModel: ArchiveViewerViewModel,
    onBack: () -> Unit,
    onReaderMode: (String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    if (uiState.mode == ViewerMode.WACZ_REPLAY) {
        // Full-screen replay with floating controls
        Box(modifier = Modifier.fillMaxSize()) {
            ArchiveWebView(
                entryUrl = uiState.entryUrl!!,
                webViewClient = uiState.webViewClient!!,
            )

            // Floating controls at top-left
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(36.dp)
                        .shadow(4.dp, CircleShape),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    ),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = { onReaderMode(uiState.archive?.eventId ?: "") },
                    enabled = uiState.archive != null,
                    modifier = Modifier
                        .size(36.dp)
                        .shadow(4.dp, CircleShape),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    ),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.MenuBook,
                        contentDescription = "Reader mode",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    } else {
        // Standard scaffold for non-replay modes
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = uiState.archive?.title
                                ?: uiState.archive?.let { extractDomain(it.archivedUrl) }
                                ?: "Viewer",
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (uiState.mode == ViewerMode.TEXT_CONTENT) {
                            IconButton(
                                onClick = { onReaderMode(uiState.archive?.eventId ?: "") },
                                enabled = uiState.archive != null,
                            ) {
                                Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = "Reader mode")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                when (uiState.mode) {
                    ViewerMode.LOADING -> LoadingIndicator()
                    ViewerMode.DOWNLOADING -> DownloadingIndicator(progress = uiState.downloadProgress)
                    ViewerMode.ERROR -> ErrorState(
                        message = uiState.error ?: "Unknown error",
                        onRetry = viewModel::retry,
                    )
                    ViewerMode.TEXT_CONTENT -> TextContentView(
                        title = uiState.archive?.title ?: "",
                        content = uiState.textContent ?: "",
                        sourceUrl = uiState.archivedPageUrl,
                    )
                    else -> {} // WACZ_REPLAY handled above
                }
            }
        }
    }
}

@Composable
private fun DownloadingIndicator(progress: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Downloading archive...",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(modifier = Modifier.height(16.dp))
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth(0.6f),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "$progress%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TextContentView(
    title: String,
    content: String,
    sourceUrl: String,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
    ) {
        if (title.isNotBlank()) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        if (sourceUrl.isNotBlank()) {
            Text(
                text = sourceUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
        Text(
            text = content,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun ArchiveWebView(
    entryUrl: String,
    webViewClient: WaczReplayWebViewClient,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                    blockNetworkLoads = false
                    userAgentString = "$userAgentString Narchives/1.0"
                }
                this.webViewClient = webViewClient
                loadUrl(entryUrl)
            }
        },
    )
}
