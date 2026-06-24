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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import dev.leonardo.ocremotev2.R
import org.json.JSONArray

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

private class CodeWebViewWithAnnotate(
    context: Context,
    private val annotateLabel: String,
    private val onAnnotate: (text: String, startOffset: Int, endOffset: Int) -> Unit,
) : WebView(context) {

    private val handler = Handler(Looper.getMainLooper())

    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode {
        val wrappedCallback = AnnotateActionCallback(
            original = callback,
            annotateLabel = annotateLabel,
            onItemClick = { mode ->
                // Capture selection BEFORE mode.finish() clears it
                evaluateJavascript("getSelectionInfo()") { result ->
                    try {
                        val arr = JSONArray(result ?: "[\"\", -1]")
                        val text = arr.optString(0, "")
                        val start = arr.optInt(1, -1)
                        if (text.isNotBlank() && start >= 0) {
                            val end = start + text.length
                            Log.d(TAG, "Annotate OK: '${text.take(50)}...' [$start-$end]")
                            handler.post {
                                onAnnotate(text, start, end)
                                mode.finish()
                            }
                        } else {
                            Log.w(TAG, "Annotate skip: text='${text.take(30)}' start=$start")
                            handler.post { mode.finish() }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Annotate parse failed: ${result?.take(80)}", e)
                        handler.post { mode.finish() }
                    }
                }
            }
        )
        return super.startActionMode(wrappedCallback, type)
    }

    override fun startActionMode(callback: ActionMode.Callback): ActionMode =
        startActionMode(callback, ActionMode.TYPE_PRIMARY)
}

private class AnnotateActionCallback(
    private val original: ActionMode.Callback,
    private val annotateLabel: String,
    private val onItemClick: (ActionMode) -> Unit,
) : ActionMode.Callback {

    private val annotateItemId = 0x1001

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        val result = original.onCreateActionMode(mode, menu)
        menu.add(0, annotateItemId, 100, annotateLabel)
        return result
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean =
        original.onPrepareActionMode(mode, menu)

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        if (item.itemId == annotateItemId) {
            onItemClick(mode)  // Don't finish here — let callback do it after JS returns
            return true
        }
        return original.onActionItemClicked(mode, item)
    }

    override fun onDestroyActionMode(mode: ActionMode) = original.onDestroyActionMode(mode)
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

    val annotateRef = remember { mutableStateOf<((text: String, start: Int, end: Int) -> Unit)?>(null) }
    annotateRef.value = onAnnotate

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
                onAnnotate = { text, start, end -> annotateRef.value?.invoke(text, start, end) }
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
                // Apply annotation highlights after code is set
                if (annotationsJson.isNotBlank() && annotationsJson != "[]") {
                    webView.evaluateJavascript(
                        "applyAnnotations('$annotationsJson')",
                        null
                    )
                }
            }
        }
    )
}
