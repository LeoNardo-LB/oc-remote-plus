package dev.leonardo.ocremotev2.ui.screens.viewer

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import dev.leonardo.ocremotev2.R

private const val TAG = "CodeWebView"

private fun extToLanguage(filePath: String): String {
    val ext = filePath.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "kt", "kts" -> "kotlin"; "java" -> "java"; "xml" -> "xml"
        "json" -> "json"; "py" -> "python"; "js" -> "javascript"
        "ts" -> "typescript"; "go" -> "go"; "rs" -> "rust"
        "c", "h" -> "c"; "cpp", "cc", "cxx" -> "cpp"; "cs" -> "csharp"
        "rb" -> "ruby"; "swift" -> "swift"; "php" -> "php"
        "sh", "bash" -> "bash"; "sql" -> "sql"; "yaml", "yml" -> "yaml"
        "html", "htm" -> "xml"; "css" -> "css"; "md" -> "markdown"
        "gradle" -> "groovy"; "properties" -> "properties"
        "dockerfile" -> "dockerfile"; "toml" -> "ini"; else -> ""
    }
}

private class CodeViewerBridge {
    private val mainHandler = Handler(Looper.getMainLooper())
    var annotateCallback: ((String) -> Unit)? = null
    var copyCallback: ((String) -> Unit)? = null

    @JavascriptInterface
    fun onAnnotate(text: String) {
        Log.d(TAG, "onAnnotate: '${text.take(50)}...'")
        mainHandler.post { annotateCallback?.invoke(text) }
    }

    @JavascriptInterface
    fun onCopy(text: String) {
        Log.d(TAG, "onCopy: '${text.take(50)}...'")
        mainHandler.post { copyCallback?.invoke(text) }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CodeWebView(
    content: String,
    filePath: String,
    modifier: Modifier = Modifier,
    onAnnotate: ((String) -> Unit)? = null,
    onCopy: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    // Read APP theme from luminance of surface color
    val surfaceColor = MaterialTheme.colorScheme.surface
    val isDark = surfaceColor.red * 0.299f + surfaceColor.green * 0.587f + surfaceColor.blue * 0.114f < 0.5f
    val annotateLabel = androidx.compose.ui.res.stringResource(R.string.annotation_context_annotate)
    val copyLabel = androidx.compose.ui.res.stringResource(android.R.string.copy)
    val language = remember(filePath) { extToLanguage(filePath) }
    val escapedContent = remember(content) {
        content.replace("\\", "\\\\").replace("`", "\\`").replace("$", "\\$")
    }

    // App surface color for WebView background (prevents transparency/blur)
    val bgColorArgb = MaterialTheme.colorScheme.surface.toArgb()

    val bridge = remember { CodeViewerBridge() }
    bridge.annotateCallback = onAnnotate
    bridge.copyCallback = onCopy

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.userAgentString = "OCRemoteCodeViewer"
                settings.allowFileAccess = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = false
                // Fix blurry text: set opaque background matching app theme
                setBackgroundColor(bgColorArgb)
                setLayerType(android.graphics.Paint.ANTI_ALIAS_FLAG, null)
                addJavascriptInterface(bridge, "AndroidBridge")

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.evaluateJavascript(
                            "setCode(`$escapedContent`, '$language'); setTheme($isDark); setLabels('$annotateLabel', '$copyLabel');",
                            null
                        )
                    }
                }

                val html = ctx.assets.open("code_viewer.html").bufferedReader().use { it.readText() }
                    .replace("__ANNOTATE_LABEL__", annotateLabel)
                    .replace("__COPY_LABEL__", copyLabel)
                loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null)
            }
        },
        update = { webView ->
            webView.post {
                webView.evaluateJavascript(
                    "setCode(`$escapedContent`, '$language'); setTheme($isDark); setLabels('$annotateLabel', '$copyLabel');",
                    null
                )
            }
        }
    )
}
