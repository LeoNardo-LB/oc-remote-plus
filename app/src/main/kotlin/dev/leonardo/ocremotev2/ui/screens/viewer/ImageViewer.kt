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
 * Image viewer using WebView with base64 data URI.
 * Supports pinch-to-zoom via WebView built-in zoom controls.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ImageViewer(
    base64Data: String,
    mimeType: String,
    modifier: Modifier = Modifier
) {
    val bgColor = MaterialTheme.colorScheme.surface.toArgb()

    val html = """
    <!DOCTYPE html>
    <html>
    <head>
    <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=5">
    <style>
        body { margin:0; padding:0; background:${argbToHex(bgColor)}; display:flex; justify-content:center; align-items:center; min-height:100vh; }
        img { max-width:100%; height:auto; object-fit:contain; }
    </style>
    </head>
    <body>
    <img src="data:$mimeType;base64,$base64Data" alt="preview" />
    </body>
    </html>
    """.trimIndent()

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                setBackgroundColor(bgColor)
                webViewClient = android.webkit.WebViewClient()
                loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
        }
    )
}

private fun argbToHex(argb: Int): String {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return String.format("#%02X%02X%02X", r, g, b)
}
