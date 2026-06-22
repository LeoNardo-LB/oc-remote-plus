package dev.leonardo.ocremotev2.ui.screens.viewer

import android.content.Intent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.leonardo.ocremotev2.R
import kotlinx.coroutines.launch

@Composable
fun FileViewerRoute(
    viewModel: FileViewerViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onSubmitted: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var isSubmitting by remember { mutableStateOf(false) }

    FileViewerScreen(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onNextHunk = viewModel::nextHunk,
        onPrevHunk = viewModel::prevHunk,
        onCopyPath = {
            clipboard.setText(AnnotatedString(uiState.filePath))
            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.menu_copied_to_clipboard)) }
        },
        onShare = {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, uiState.content.ifBlank { uiState.filePath })
            }
            runCatching {
                context.startActivity(Intent.createChooser(sendIntent, null))
            }
        },
        onCopyAllContent = {
            clipboard.setText(AnnotatedString(uiState.content))
            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.menu_copied_to_clipboard)) }
        },
        onToggleRenderMode = viewModel::toggleRenderMode,
        onSwitchToSource = viewModel::switchToSource,
        // Phase 3: Annotation callbacks
        onAddAnnotation = { selectedText, note ->
            // KNOWN LIMITATION: indexOf returns the first occurrence of selectedText.
            // Duplicate text will anchor to the wrong location. This is inherent
            // to clipboard-based selection capture (Phase 3 plan §known-limitations).
            val startChar = uiState.content.indexOf(selectedText)
            if (startChar >= 0) {
                viewModel.addAnnotation(selectedText, startChar, startChar + selectedText.length, note)
            }
        },
        onDeleteAnnotation = viewModel::deleteAnnotation,
        onUpdateAnnotation = viewModel::updateAnnotation,
        onLoadMoreLines = viewModel::loadMoreLines,
        onSubmitAnnotations = { overallNote ->
            if (!isSubmitting) {
                isSubmitting = true
                scope.launch {
                    val result = viewModel.submitAnnotations(overallNote)
                    isSubmitting = false
                    if (result.isSuccess) {
                        snackbarHostState.showSnackbar(context.getString(R.string.annotation_submitted_toast))
                        onSubmitted()
                    } else {
                        snackbarHostState.showSnackbar(context.getString(R.string.annotation_submit_failed))
                    }
                }
            }
        }
    )
}
