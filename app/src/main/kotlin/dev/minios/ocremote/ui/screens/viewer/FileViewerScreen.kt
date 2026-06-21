package dev.minios.ocremote.ui.screens.viewer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.R
import dev.minios.ocremote.ui.theme.SpacingTokens
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileViewerScreen(
    uiState: FileViewerUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onNextHunk: () -> Unit,
    onPrevHunk: () -> Unit,
    onCopyPath: () -> Unit,
    onShare: () -> Unit,
    onCopyAllContent: () -> Unit,
    onToggleRenderMode: () -> Unit
) {
    var showLongPressMenu by remember { mutableStateOf(false) }
    // Phase 2: source scroll state + fraction anchor for md render toggle
    val sourceLazyListState = rememberLazyListState()
    var lastSourceFraction by remember { mutableStateOf(0f) }
    val sourceLineCount = remember(uiState.content) {
        if (uiState.content.isEmpty()) 1
        else uiState.content.count { it == '\n' } + 1
    }

    val toggleWithAnchor: () -> Unit = {
        if (uiState.isMarkdown && uiState.renderMode == FileViewerRenderMode.SOURCE) {
            lastSourceFraction = if (sourceLineCount > 0 && sourceLazyListState.layoutInfo.totalItemsCount > 0) {
                sourceLazyListState.firstVisibleItemIndex.toFloat() / sourceLineCount
            } else 0f
        }
        onToggleRenderMode()
    }

    Scaffold(
        topBar = {
            FileViewerTopBar(
                uiState = uiState,
                onBack = onBack,
                onCopyPath = onCopyPath,
                onShare = onShare,
                onToggleRenderMode = toggleWithAnchor
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .combinedClickable(
                    onClick = {},
                    onLongClick = { showLongPressMenu = true }
                )
        ) {
            when {
                uiState.isLoading -> LoadingState()
                uiState.error != null -> ErrorState(message = uiState.error)
                uiState.isBinary -> MessageState(
                    message = stringResource(R.string.viewer_binary_not_supported),
                    detail = uiState.mimeType?.let { stringResource(R.string.viewer_binary_mime, it) }
                )
                uiState.mode == FileViewerMode.DIFF -> DiffView(
                    uiState = uiState,
                    onNextHunk = onNextHunk,
                    onPrevHunk = onPrevHunk
                )
                uiState.isEmpty -> MessageState(message = stringResource(R.string.viewer_empty_file))
                // Phase 2: markdown render preview (before truncation check — preview shows full)
                uiState.isMarkdown && uiState.renderMode == FileViewerRenderMode.RENDER_PREVIEW -> {
                    MarkdownPreviewWithScrollAnchor(
                        markdown = uiState.content,
                        sourceScrollFraction = lastSourceFraction
                    )
                }
                uiState.isTruncated -> Column(Modifier.fillMaxSize()) {
                    TruncationBanner()
                    CodeSourceView(
                        content = uiState.content,
                        filePath = uiState.filePath,
                        lazyListState = sourceLazyListState,
                        modifier = Modifier.weight(1f)
                    )
                }
                else -> CodeSourceView(
                    content = uiState.content,
                    filePath = uiState.filePath,
                    lazyListState = sourceLazyListState,
                    modifier = Modifier.fillMaxSize()
                )
            }
            DropdownMenu(
                expanded = showLongPressMenu,
                onDismissRequest = { showLongPressMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.a11y_icon_copy_all)) },
                    onClick = {
                        onCopyAllContent()
                        showLongPressMenu = false
                    },
                    leadingIcon = {
                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileViewerTopBar(
    uiState: FileViewerUiState,
    onBack: () -> Unit,
    onCopyPath: () -> Unit,
    onShare: () -> Unit,
    onToggleRenderMode: () -> Unit
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
        },
        actions = {
            // Phase 2: md render toggle (only for markdown in SOURCE mode)
            if (uiState.isMarkdown && uiState.mode != FileViewerMode.DIFF) {
                val isRender = uiState.renderMode == FileViewerRenderMode.RENDER_PREVIEW
                IconButton(
                    onClick = onToggleRenderMode,
                    modifier = Modifier.testTag("viewer_md_render_button")
                ) {
                    Icon(
                        imageVector = if (isRender) Icons.Default.Description else Icons.Default.RemoveRedEye,
                        contentDescription = if (isRender) stringResource(R.string.viewer_md_show_source)
                        else stringResource(R.string.viewer_md_show_render),
                        tint = if (isRender) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = onCopyPath) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.a11y_icon_copy_path)
                )
            }
            IconButton(onClick = onShare) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = stringResource(R.string.a11y_icon_share)
                )
            }
        }
    )
}

/**
 * Markdown preview that scrolls to [sourceScrollFraction] of its max scroll range,
 * waiting for first non-zero maxValue (async rendering).
 */
@Composable
private fun MarkdownPreviewWithScrollAnchor(
    markdown: String,
    sourceScrollFraction: Float
) {
    val renderScrollState = rememberScrollState()
    LaunchedEffect(sourceScrollFraction) {
        snapshotFlow { renderScrollState.maxValue }
            .filter { it > 0 }
            .first()
        renderScrollState.scrollTo((renderScrollState.maxValue * sourceScrollFraction).toInt())
    }
    MarkdownPreview(
        markdown = markdown,
        scrollState = renderScrollState
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
private fun ErrorState(message: Int) {
    Box(
        modifier = Modifier.fillMaxSize().padding(SpacingTokens.LG.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(message),
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
