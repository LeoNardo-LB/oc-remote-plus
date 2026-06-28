package dev.leonardo.ocremotev2.ui.screens.viewer

import android.annotation.SuppressLint
import android.view.View
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Unified WebView-based renderer that **reuses a single WebView instance** across
 * render-mode toggles. When [content] or [fileType] changes (e.g., user taps the
 * toggle button), only [WebView.loadDataWithBaseURL] is called — no WebView
 * destruction/recreation, eliminating the blank-flash flicker.
 *
 * Supports: IMAGE (base64 data-URI), SVG, CSV.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RenderWebView(
    content: String,
    fileType: FileType,
    mimeType: String = "image/*",
    visible: Boolean = true,
    modifier: Modifier = Modifier
) {
    val surfaceColor = MaterialTheme.colorScheme.surface
    val isDark = surfaceColor.red * 0.299f + surfaceColor.green * 0.587f + surfaceColor.blue * 0.114f < 0.5f
    val bgColorArgb = surfaceColor.toArgb()
    val bgHex = argbToHex(bgColorArgb)
    val fgHex = argbToHex(MaterialTheme.colorScheme.onSurface.toArgb())

    // Build HTML from current fileType + content
    val html = remember(content, fileType, mimeType, bgColorArgb) {
        when (fileType) {
            FileType.IMAGE -> buildImageHtml(content, mimeType, bgHex)
            FileType.SVG, FileType.CSV -> RenderHtmlBuilder.build(fileType, content, isDark, bgHex, fgHex)
            else -> "" // fallback — should never reach here
        }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.apply {
                    when (fileType) {
                        FileType.IMAGE -> {
                            builtInZoomControls = true
                            displayZoomControls = false
                            loadWithOverviewMode = true
                            useWideViewPort = true
                        }
                        else -> {
                            javaScriptEnabled = false
                            loadWithOverviewMode = true
                            useWideViewPort = true
                        }
                    }
                }
                setBackgroundColor(bgColorArgb)
                webViewClient = android.webkit.WebViewClient()
                loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
        },
        update = { webView ->
            webView.visibility = if (visible) View.VISIBLE else View.GONE
            // Content changed (toggle or new file) → reload in-place, no recreation
            webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        }
    )
}

private fun buildImageHtml(base64Data: String, mimeType: String, bgHex: String): String {
    return """
    <!DOCTYPE html>
    <html>
    <head>
    <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=5">
    <style>
        body { margin:0; padding:12px 16px; background:$bgHex; display:flex; justify-content:center; align-items:center; min-height:100vh; }
        img { max-width:100%; height:auto; object-fit:contain; }
    </style>
    </head>
    <body>
    <img src="data:$mimeType;base64,$base64Data" alt="preview" />
    </body>
    </html>
    """.trimIndent()
}

private fun argbToHex(argb: Int): String {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return String.format("#%02X%02X%02X", r, g, b)
}
