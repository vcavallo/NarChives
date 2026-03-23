package com.narchives.reader.ui.screen.reader

import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.narchives.reader.replay.ReaderArticle
import com.narchives.reader.ui.components.ErrorState
import com.narchives.reader.ui.components.LoadingIndicator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderModeScreen(
    viewModel: ReaderModeViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reader") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                uiState.error != null -> ErrorState(message = uiState.error!!)
                uiState.article != null -> {
                    ReaderWebView(
                        article = uiState.article!!,
                        fontSize = uiState.fontSize,
                        fontFamily = uiState.fontFamily,
                        isDarkMode = uiState.isDarkMode,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderWebView(
    article: ReaderArticle,
    fontSize: Int,
    fontFamily: String,
    isDarkMode: Boolean,
    modifier: Modifier = Modifier,
) {
    val html = buildReaderHtml(article, fontSize, fontFamily, isDarkMode)

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = false
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        },
    )
}

private fun buildReaderHtml(
    article: ReaderArticle,
    fontSize: Int,
    fontFamily: String,
    isDarkMode: Boolean,
): String {
    val bgColor = if (isDarkMode) "#1a1a1a" else "#fafafa"
    val textColor = if (isDarkMode) "#e0e0e0" else "#1a1a1a"
    val metaColor = if (isDarkMode) "#888" else "#666"
    val linkColor = if (isDarkMode) "#6ba3d6" else "#1a5fa0"
    val borderColor = if (isDarkMode) "#333" else "#ddd"
    val quoteColor = if (isDarkMode) "#444" else "#ccc"
    val codeBg = if (isDarkMode) "#2a2a2a" else "#f0f0f0"

    return """
    <!DOCTYPE html>
    <html>
    <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=yes">
    <style>
        body {
            font-family: $fontFamily, Georgia, serif;
            font-size: ${fontSize}px;
            line-height: 1.7;
            color: $textColor;
            background: $bgColor;
            padding: 24px 20px 80px;
            max-width: 680px;
            margin: 0 auto;
            -webkit-font-smoothing: antialiased;
        }
        h1 { font-size: 1.6em; line-height: 1.3; margin: 0 0 12px; font-weight: 700; }
        .meta {
            color: $metaColor; font-size: 0.85em; margin-bottom: 32px;
            border-bottom: 1px solid $borderColor; padding-bottom: 16px;
        }
        img { max-width: 100%; height: auto; border-radius: 4px; margin: 16px 0; }
        a { color: $linkColor; }
        blockquote {
            border-left: 3px solid $quoteColor; margin: 16px 0;
            padding: 8px 16px; color: $metaColor;
        }
        pre, code { background: $codeBg; border-radius: 4px; padding: 2px 6px; font-size: 0.9em; }
        pre { padding: 12px; overflow-x: auto; }
        p { margin: 0 0 16px; }
    </style>
    </head>
    <body>
        <h1>${escapeHtml(article.title)}</h1>
        <div class="meta">
            ${if (article.byline.isNotBlank()) "<span>${escapeHtml(article.byline)}</span><br>" else ""}
            <span>${escapeHtml(article.siteName)}</span>
        </div>
        <div class="article-content">
            ${article.content}
        </div>
    </body>
    </html>
    """.trimIndent()
}

private fun escapeHtml(text: String): String {
    return text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}
