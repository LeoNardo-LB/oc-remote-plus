package dev.leonardo.ocremotev2.ui.screens.viewer

import android.annotation.SuppressLint
import android.content.Context
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import dev.leonardo.ocremotev2.R
import dev.leonardo.ocremotev2.util.DebugLogger

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

private class SelectionBridge {
    private val mainHandler = Handler(Looper.getMainLooper())
    var callback: ((text: String, start: Int, end: Int) -> Unit)? = null

    @JavascriptInterface
    fun onSelection(text: String, start: Int) {
        val end = start + text.length
        DebugLogger.log(TAG, "Bridge.onSelection: '${text.take(40)}' [$start-$end]")
        mainHandler.post { callback?.invoke(text, start, end) }
    }
}

/**
 * WebView subclass that injects "Annotate" into the native text selection
 * ActionMode toolbar (alongside Copy / Select all).
 *
 * Click handled by matching item TITLE (not itemId) because Android WebView's
 * internal ActionMode may reassign or not dispatch custom itemIds.
 */
private class AnnotateWebView(
    context: Context,
    private val annotateLabel: String,
    private val bridge: SelectionBridge,
) : WebView(context) {

    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode {
        DebugLogger.log(TAG, "▶ startActionMode type=$type  (0=FLOATING, 1=PRIMARY, 2=MENU)")
        if (callback == null) {
            DebugLogger.log(TAG, "  callback == null, delegating to super")
            return super.startActionMode(null, type)
        }

        val wrapped = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                val ok = callback.onCreateActionMode(mode, menu)
                // Add Annotate after system items
                menu.add(Menu.NONE, Menu.NONE, 200, annotateLabel)
                // Dump all menu items for debugging
                val items = (0 until menu.size()).map { i ->
                    val item = menu.getItem(i)
                    "'${item.title}'(id=${item.itemId})"
                }.joinToString(", ")
                DebugLogger.log(TAG, "  ✓ onCreateActionMode ok=$ok  items=[$items]")
                return ok
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                val result = callback.onPrepareActionMode(mode, menu)
                val items = (0 until menu.size()).map { i ->
                    "'${menu.getItem(i).title}'"
                }.joinToString(", ")
                DebugLogger.log(TAG, "  ~ onPrepareActionMode result=$result  items=[$items]")
                return result
            }

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                DebugLogger.log(TAG, "  ✋ onActionItemClicked title='${item.title}' id=${item.itemId}")
                // Match by TITLE — system may reassign itemIds
                if (item.title?.toString() == annotateLabel) {
                    DebugLogger.log(TAG, "  → Annotate matched! Getting selection via JS...")
                    // Synchronously evaluate JS to get selection
                    evaluateJavascript("getSelectionInfo()") { result ->
                        try {
                            val arr = org.json.JSONArray(result ?: "[\"\", -1]")
                            val text = arr.optString(0, "")
                            val start = arr.optInt(1, -1)
                            DebugLogger.log(TAG, "  ← JS selection: text='${text.take(40)}' start=$start len=${text.length}")
                            if (text.isNotBlank() && start >= 0) {
                                Handler(Looper.getMainLooper()).post {
                                    bridge.onSelection(text, start)
                                    mode.finish()
                                }
                            } else {
                                DebugLogger.log(TAG, "  ✗ Selection empty or invalid, finishing mode")
                                Handler(Looper.getMainLooper()).post { mode.finish() }
                            }
                        } catch (e: Exception) {
                            DebugLogger.log(TAG, "  ✗ Parse failed: ${result?.take(60)}")
                            Handler(Looper.getMainLooper()).post { mode.finish() }
                        }
                    }
                    return true
                }
                DebugLogger.log(TAG, "  → Not Annotate, delegating to original callback")
                return callback.onActionItemClicked(mode, item)
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                DebugLogger.log(TAG, "  ✗ onDestroyActionMode")
                callback.onDestroyActionMode(mode)
            }
        }
        return super.startActionMode(wrapped, type)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CodeWebView(
    content: String,
    filePath: String,
    modifier: Modifier = Modifier,
    onAnnotate: ((text: String, startOffset: Int, endOffset: Int) -> Unit)? = null,
    annotationsJson: String = "",
) {
    val annotateLabel = stringResource(R.string.annotation_context_annotate)
    val surfaceColor = MaterialTheme.colorScheme.surface
    val isDark = surfaceColor.red * 0.299f + surfaceColor.green * 0.587f + surfaceColor.blue * 0.114f < 0.5f
    val bgColorArgb = surfaceColor.toArgb()
    val language = remember(filePath) { extToLanguage(filePath) }
    val escapedContent = remember(content) {
        content.replace("\\", "\\\\").replace("`", "\\`").replace("$", "\\$")
    }

    val bridge = remember { SelectionBridge() }
    bridge.callback = onAnnotate

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
            AnnotateWebView(ctx, annotateLabel, bridge).apply {
                settings.javaScriptEnabled = true
                settings.userAgentString = "OCRemoteCodeViewer"
                settings.allowFileAccess = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = false
                setBackgroundColor(bgColorArgb)
                addJavascriptInterface(bridge, "AndroidBridge")

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.evaluateJavascript(
                            "setCode(`$escapedContent`, '$language'); setTheme($isDark);",
                            null
                        )
                        if (annotationsJson.isNotBlank() && annotationsJson != "[]") {
                            view?.evaluateJavascript("applyAnnotations('$annotationsJson');", null)
                        }
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
                if (annotationsJson.isNotBlank() && annotationsJson != "[]") {
                    webView.evaluateJavascript("applyAnnotations('$annotationsJson');", null)
                }
            }
        }
    )
}
