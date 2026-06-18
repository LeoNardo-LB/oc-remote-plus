package dev.minios.ocremote.ui.screens.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun FileViewerRoute(
    viewModel: FileViewerViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    FileViewerScreen(
        uiState = uiState,
        onBack = onBack,
        onNextHunk = viewModel::nextHunk,
        onPrevHunk = viewModel::prevHunk
    )
}
