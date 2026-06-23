package dev.leonardo.ocremotev2.ui.screens.viewer

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import dev.leonardo.ocremotev2.R

private const val TAG = "CodeWebView"

/** Maps file extension to Highlight.js language name. */
private fun extToLanguage(filePath: String): String {
    val ext = filePath.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "kt", "kts" -> "kotlin"
        "java" -> "java"
        "xml" -> "xml"
        "json" -> "json"
        "py" -> "python"
        "js" -> "javascript"
        "ts" -> "typescript"
        "go" -> "go"
        "rs" -> "rust"
        "c", "h" -> "c"
        "cpp", "cc", "cxx" -> "cpp"
        "cs" -> "csharp"
        "rb" -> "ruby"
        "swift" -> "swift"
        "php" -> "php"
        "sh", "bash" -> "bash"
        "sql" -> "sql"
        "yaml", "yml" -> "yaml"
        "html", "htm" -> "xml"
        "css" -> "css"
        "md" -> "markdown"
        "gradle" -> "groovy"
        "properties" -> "properties"
        "dockerfile" -> "dockerfile"
        "toml" -> "ini"
        else -> ""
    }
}

/** Bridge for JavaScript → Kotlin communication. */
private class CodeViewerBridge(
    val onAnnotate: (String) -> Unit,
    val onCopy: (String) -> Unit,
) {
    @JavascriptInterface
    fun onAnnotate(text: String) { onAnnotate.invoke(text) }

    @JavascriptInterface
    fun onCopy(text: String) { onCopy.invoke(text) }
}

/**
 * WebView-based code viewer using Highlight.js.
 *
 * Replaces the hand-rolled Compose Text-based [CodeSourceView] to fix:
 * - Syntax highlighting quality (Highlight.js is industrial-grade)
 * - Text selection (WebView native selection works reliably)
 * - Custom context menu ("Annotate" injected via JavaScript)
 * - Gutter/code split scroll (CSS flexbox, gutter stays fixed)
 */
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
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val annotateLabel = androidx.compose.ui.res.stringResource(R.string.annotation_context_annotate)
    val copyLabel = androidx.compose.ui.res.stringResource(android.R.string.copy)
    val language = remember(filePath) { extToLanguage(filePath) }
    val escapedContent = remember(content) {
        content.replace("\\", "\\\\").replace("`", "\\`").replace("$", "\\$")
    }

    val bridge = remember(onAnnotate, onCopy) {
        CodeViewerBridge(
            onAnnotate = { text -> onAnnotate?.invoke(text) },
            onCopy = { text -> onCopy?.invoke(text) },
        )
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.userAgentString = "OCRemoteCodeViewer"
                settings.allowFileAccess = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = false
                addJavascriptInterface(bridge, "AndroidBridge")

                // Load HTML template from assets
                val html = ctx.assets.open("code_viewer.html").bufferedReader().use { it.readText() }
                    .replace("__ANNOTATE_LABEL__", annotateLabel)
                    .replace("__COPY_LABEL__", copyLabel)
                loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null)
            }
        },
        update = { webView ->
            // Set code content + language + theme whenever content changes
            webView.post {
                webView.evaluateJavascript(
                    "setCode(`$escapedContent`, '$language'); setTheme($isDark);",
                    null
                )
            }
        }
    )
}
