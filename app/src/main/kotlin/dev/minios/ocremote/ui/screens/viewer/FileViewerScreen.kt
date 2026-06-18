package dev.minios.ocremote.ui.screens.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.R
import dev.minios.ocremote.ui.theme.SpacingTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileViewerScreen(
    uiState: FileViewerUiState,
    onBack: () -> Unit,
    onNextHunk: () -> Unit,
    onPrevHunk: () -> Unit
) {
    Scaffold(
        topBar = { FileViewerTopBar(uiState = uiState, onBack = onBack) }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when {
                uiState.isLoading -> LoadingState()
                uiState.error != null -> ErrorState(message = uiState.error)
                uiState.isBinary -> MessageState(
                    message = stringResource(R.string.viewer_binary_not_supported),
                    detail = uiState.mimeType?.let { stringResource(R.string.viewer_binary_mime, it) }
                )
                uiState.mode == FileViewerMode.DIFF -> DiffViewPlaceholder()
                uiState.isEmpty -> MessageState(message = stringResource(R.string.viewer_empty_file))
                uiState.isTruncated -> Column(Modifier.fillMaxSize()) {
                    TruncationBanner()
                    CodeSourceView(
                        content = uiState.content,
                        filePath = uiState.filePath,
                        modifier = Modifier.weight(1f)
                    )
                }
                else -> CodeSourceView(
                    content = uiState.content,
                    filePath = uiState.filePath,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileViewerTopBar(
    uiState: FileViewerUiState,
    onBack: () -> Unit
) {
    TopAppBar(
        title = {
            Text(
                text = uiState.filePath.substringAfterLast('/').ifBlank { uiState.filePath },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        navigationIcon = {
            IconButton(
                onClick = onBack,
                modifier = Modifier.testTag("back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back)
                )
            }
        }
    )
}

@Composable
private fun LoadingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorState(message: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(SpacingTokens.LG.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun MessageState(
    message: String,
    detail: String? = null
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(SpacingTokens.LG.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp)
        ) {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun TruncationBanner() {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = stringResource(R.string.viewer_truncated),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(
                horizontal = SpacingTokens.LG.dp,
                vertical = SpacingTokens.SM.dp
            )
        )
    }
}

@Composable
private fun DiffViewPlaceholder() {
    Box(
        modifier = Modifier.fillMaxSize().padding(SpacingTokens.LG.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.viewer_diff_placeholder),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
