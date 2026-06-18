package dev.minios.ocremote.ui.screens.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.minios.ocremote.R
import dev.minios.ocremote.ui.theme.SpacingTokens

@Composable
fun WorkspaceRoute(
    viewModel: WorkspaceViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onOpenFile: (filePath: String) -> Unit,
    onOpenGitDiff: (filePath: String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    WorkspaceScreen(
        uiState = uiState,
        onBack = onBack,
        onSwitchPanel = viewModel::switchPanel,
        onRefreshRoot = viewModel::refreshRoot,
        onToggleShowIgnored = viewModel::toggleShowIgnored,
        onRefreshGit = viewModel::loadGitChanges,
        onOpenFile = onOpenFile,
        onOpenGitDiff = onOpenGitDiff
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(
    uiState: WorkspaceUiState,
    onBack: () -> Unit,
    onSwitchPanel: (WorkspacePanel) -> Unit,
    onRefreshRoot: () -> Unit,
    onToggleShowIgnored: () -> Unit,
    onRefreshGit: () -> Unit,
    onOpenFile: (String) -> Unit,
    onOpenGitDiff: (String) -> Unit
) {
    Scaffold(
        topBar = {
            WorkspaceTopBar(uiState = uiState, onBack = onBack, onSwitchPanel = onSwitchPanel)
        }
    ) { padding ->
        when (uiState.currentPanel) {
            WorkspacePanel.FILE_TREE -> FileTreePanelPlaceholder(
                uiState = uiState,
                onRefreshRoot = onRefreshRoot,
                onToggleShowIgnored = onToggleShowIgnored,
                onOpenFile = onOpenFile,
                modifier = Modifier.padding(padding)
            )
            WorkspacePanel.GIT_CHANGES -> GitChangesPanelPlaceholder(
                uiState = uiState,
                onRefreshGit = onRefreshGit,
                onOpenGitDiff = onOpenGitDiff,
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspaceTopBar(
    uiState: WorkspaceUiState,
    onBack: () -> Unit,
    onSwitchPanel: (WorkspacePanel) -> Unit
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = basename(uiState.directory),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (uiState.directory.isNotBlank()) {
                    Text(
                        text = uiState.directory,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(
                onClick = onBack,
                modifier = Modifier.testTag("back_button")
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back)
                )
            }
        },
        actions = {
            IconButton(
                onClick = { onSwitchPanel(WorkspacePanel.FILE_TREE) },
                modifier = Modifier.testTag("panel_file_tree")
            ) {
                Icon(
                    Icons.Filled.Folder,
                    contentDescription = stringResource(R.string.a11y_icon_toggle_directory),
                    tint = if (uiState.currentPanel == WorkspacePanel.FILE_TREE) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            IconButton(
                onClick = { onSwitchPanel(WorkspacePanel.GIT_CHANGES) },
                enabled = !uiState.isNonGit,
                modifier = Modifier.testTag("panel_git_changes")
            ) {
                BadgedBox(
                    badge = {
                        val count = uiState.gitChangeCount
                        if (count != null && count > 0) {
                            Badge { Text("$count") }
                        }
                    }
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.CompareArrows,
                        contentDescription = stringResource(R.string.a11y_icon_git_changes),
                        tint = if (uiState.currentPanel == WorkspacePanel.GIT_CHANGES) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    )
}

/**
 * Placeholder for the FileTreePanel that Task 13 will implement.
 * Real panel must match this signature so WorkspaceScreen only needs a call-site rename.
 */
@Composable
private fun FileTreePanelPlaceholder(
    uiState: WorkspaceUiState,
    onRefreshRoot: () -> Unit,
    onToggleShowIgnored: () -> Unit,
    onOpenFile: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(SpacingTokens.LG.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "FileTreePanel placeholder — Task 13",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Placeholder for the GitChangesPanel that Task 14 will implement.
 * Real panel must match this signature so WorkspaceScreen only needs a call-site rename.
 */
@Composable
private fun GitChangesPanelPlaceholder(
    uiState: WorkspaceUiState,
    onRefreshGit: () -> Unit,
    onOpenGitDiff: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(SpacingTokens.LG.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "GitChangesPanel placeholder — Task 14",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Returns the last path segment, or "/" for empty/root paths. */
private fun basename(path: String): String {
    if (path.isBlank()) return "/"
    val trimmed = path.trimEnd('/')
    if (trimmed.isEmpty()) return "/"
    return trimmed.substringAfterLast('/').ifBlank { trimmed }
}
