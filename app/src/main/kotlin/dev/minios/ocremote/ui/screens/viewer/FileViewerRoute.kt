package dev.minios.ocremote.ui.screens.viewer

import android.content.Intent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.minios.ocremote.R
import kotlinx.coroutines.launch

@Composable
fun FileViewerRoute(
    viewModel: FileViewerViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

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
        onToggleRenderMode = viewModel::toggleRenderMode
    )
}
