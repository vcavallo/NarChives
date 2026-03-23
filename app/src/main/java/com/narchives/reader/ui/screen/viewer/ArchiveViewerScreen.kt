package com.narchives.reader.ui.screen.viewer

import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.viewinterop.AndroidView
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
                    if (uiState.mode == ViewerMode.WACZ_REPLAY) {
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
                ViewerMode.ERROR -> ErrorState(
                    message = uiState.error ?: "Unknown error",
                    onRetry = viewModel::retry,
                )
                ViewerMode.TEXT_CONTENT -> {
                    TextContentView(
                        title = uiState.archive?.title ?: "",
                        content = uiState.textContent ?: "",
                        sourceUrl = uiState.archivedPageUrl,
                    )
                }
                ViewerMode.WACZ_REPLAY -> {
                    ArchiveWebView(
                        serverUrl = uiState.serverUrl!!,
                        waczSourceUrl = uiState.waczSourceUrl!!,
                        archivedPageUrl = uiState.archivedPageUrl,
                    )
                }
            }
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
    androidx.compose.foundation.layout.Column(
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
            lineHeight = MaterialTheme.typography.bodyLarge.lineHeight,
        )
    }
}

@Composable
private fun ArchiveWebView(
    serverUrl: String,
    waczSourceUrl: String,
    archivedPageUrl: String,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = true
                    allowContentAccess = true
                    mediaPlaybackRequiresUserGesture = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                    userAgentString = "$userAgentString Narchives/1.0"
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        val encodedWaczUrl = waczSourceUrl.replace("'", "\\'")
                        val encodedPageUrl = archivedPageUrl.replace("'", "\\'")
                        view?.evaluateJavascript(
                            "loadArchive('$encodedWaczUrl', '$encodedPageUrl')",
                            null,
                        )
                    }
                }

                loadUrl("$serverUrl/index.html")
            }
        },
    )
}
