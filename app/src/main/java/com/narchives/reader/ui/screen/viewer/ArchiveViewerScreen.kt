package com.narchives.reader.ui.screen.viewer

import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MenuBook
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
import androidx.compose.ui.viewinterop.AndroidView
import com.narchives.reader.ui.components.ErrorState
import com.narchives.reader.ui.components.LoadingIndicator
import com.narchives.reader.ui.util.extractDomain
import java.net.URLEncoder

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
                        text = uiState.archive?.let { extractDomain(it.archivedUrl) } ?: "Viewer",
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { onReaderMode(uiState.archive?.eventId ?: "") },
                        enabled = uiState.archive != null,
                    ) {
                        Icon(Icons.Default.MenuBook, contentDescription = "Reader mode")
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
            when {
                uiState.isLoading -> LoadingIndicator()
                uiState.error != null -> ErrorState(
                    message = uiState.error!!,
                    onRetry = viewModel::retry,
                )
                uiState.serverUrl != null -> {
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
