package dev.leonardo.ocremotev2.ui.screens.viewer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import dev.leonardo.ocremotev2.R
import kotlin.math.sqrt

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

/**
 * WebView subclass that intercepts the native text selection ActionMode
 * to inject an "Annotate" item alongside Copy/Select all.
 *
 * This replaces the JavaScript contextmenu approach which conflicted with
 * the native selection bar (two overlapping menus).
 */
private class CodeWebViewWithAnnotate(
    context: Context,
    private val annotateLabel: String,
    private val onAnnotate: (String) -> Unit,
) : WebView(context) {

    private val handler = Handler(Looper.getMainLooper())

    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode {
        // Wrap the system callback to inject our "Annotate" menu item
        val wrappedCallback = AnnotateActionCallback(
            original = callback,
            annotateLabel = annotateLabel,
            onAnnotateClicked = {
                // Get selected text from WebView
                evaluateJavascript("window.getSelection().toString()") { result ->
                    val text = result
                        ?.removeSurrounding("\"")
                        ?.replace("\\n", "\n")
                        ?.replace("\\r", "")
                        ?.trim()
                        ?: ""
                    if (text.isNotBlank()) {
                        Log.d(TAG, "Annotate selected: '${text.take(50)}...'")
                        handler.post { onAnnotate(text) }
                    }
                }
            }
        )
        return if (type == ActionMode.TYPE_FLOATING) {
            super.startActionMode(wrappedCallback, type)
        } else {
            super.startActionMode(wrappedCallback, type)
        }
    }

    // Legacy overload — delegate to the typed version
    override fun startActionMode(callback: ActionMode.Callback): ActionMode {
        return startActionMode(callback, ActionMode.TYPE_PRIMARY)
    }
}

/**
 * Wraps the system ActionMode callback to add "Annotate" to the selection menu.
 */
private class AnnotateActionCallback(
    private val original: ActionMode.Callback,
    private val annotateLabel: String,
    private val onAnnotateClicked: () -> Unit,
) : ActionMode.Callback {

    private val annotateItemId = 0x1001

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        val result = original.onCreateActionMode(mode, menu)
        // Add our custom item — click handled in onActionItemClicked below
        menu.add(0, annotateItemId, 100, annotateLabel)
        return result
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        return original.onPrepareActionMode(mode, menu)
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        // Intercept our Annotate item BEFORE the system callback
        if (item.itemId == annotateItemId) {
            onAnnotateClicked()
            mode.finish()
            return true
        }
        return original.onActionItemClicked(mode, item)
    }

    override fun onDestroyActionMode(mode: ActionMode) {
        original.onDestroyActionMode(mode)
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
    val annotateLabel = stringResource(R.string.annotation_context_annotate)

    // App theme detection
    val surfaceColor = MaterialTheme.colorScheme.surface
    val isDark = surfaceColor.red * 0.299f + surfaceColor.green * 0.587f + surfaceColor.blue * 0.114f < 0.5f
    val bgColorArgb = surfaceColor.toArgb()
    val language = remember(filePath) { extToLanguage(filePath) }
    val escapedContent = remember(content) {
        content.replace("\\", "\\\\").replace("`", "\\`").replace("$", "\\$")
    }

    // Stable annotate callback holder (survives recomposition)
    val annotateRef = remember { mutableStateOf<((String) -> Unit)?>(null) }
    annotateRef.value = onAnnotate

    // Track WebView reference for cleanup on dispose
    var webViewRef: WebView? = null

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.apply {
                stopLoading()
                removeJavascriptInterface("AndroidBridge")
                loadUrl("about:blank")
                clearHistory()
                (parent as? android.view.ViewGroup)?.removeView(this)
                destroy()
            }
            webViewRef = null
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            CodeWebViewWithAnnotate(
                context = ctx,
                annotateLabel = annotateLabel,
                onAnnotate = { text -> annotateRef.value?.invoke(text) }
            ).apply {
                settings.javaScriptEnabled = true
                settings.userAgentString = "OCRemoteCodeViewer"
                settings.allowFileAccess = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = false
                setBackgroundColor(bgColorArgb)

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.evaluateJavascript(
                            "setCode(`$escapedContent`, '$language'); setTheme($isDark);",
                            null
                        )
                    }
                }

                val html = ctx.assets.open("code_viewer.html").bufferedReader().use { it.readText() }
                    .replace("__ANNOTATE_LABEL__", annotateLabel)
                    .replace("__COPY_LABEL__", "Copy")
                loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null)
                webViewRef = this
            }
        },
        update = { webView ->
            webView.post {
                webView.evaluateJavascript(
                    "setCode(`$escapedContent`, '$language'); setTheme($isDark);",
                    null
                )
            }
        }
    )
}
