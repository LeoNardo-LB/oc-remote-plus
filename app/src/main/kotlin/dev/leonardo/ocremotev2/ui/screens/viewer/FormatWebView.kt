package dev.leonardo.ocremotev2.ui.screens.viewer

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView

/**
 * WebView-based renderer for SVG, CSV, and JSON file previews.
 * Delegates HTML generation to [RenderHtmlBuilder].
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun FormatWebView(
    content: String,
    fileType: FileType,
    modifier: Modifier = Modifier
) {
    val surfaceColor = MaterialTheme.colorScheme.surface
    val isDark = surfaceColor.red * 0.299f + surfaceColor.green * 0.587f + surfaceColor.blue * 0.114f < 0.5f
    val bgColorArgb = surfaceColor.toArgb()

    val html = RenderHtmlBuilder.build(fileType, content, isDark)

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                setBackgroundColor(bgColorArgb)
                webViewClient = android.webkit.WebViewClient()
                loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
        }
    )
}
