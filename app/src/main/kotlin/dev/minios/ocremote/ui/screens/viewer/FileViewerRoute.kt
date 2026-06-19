package dev.minios.ocremote.ui.screens.viewer

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun FileViewerRoute(
    viewModel: FileViewerViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    FileViewerScreen(
        uiState = uiState,
        onBack = onBack,
        onNextHunk = viewModel::nextHunk,
        onPrevHunk = viewModel::prevHunk,
        onCopyPath = {
            clipboard.setText(AnnotatedString(uiState.filePath))
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
        }
    )
}
