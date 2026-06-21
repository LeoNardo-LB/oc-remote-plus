package dev.leonardo.ocremotev2.ui.screens.workspace.tree

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leonardo.ocremotev2.R
import dev.leonardo.ocremotev2.domain.model.isDirectory
import dev.leonardo.ocremotev2.ui.screens.workspace.FileTreeNode
import dev.leonardo.ocremotev2.ui.screens.workspace.WorkspaceUiState
import dev.leonardo.ocremotev2.ui.theme.SpacingTokens

/**
 * File tree panel: renders the workspace root nodes as a flattened, depth-indented list.
 * Replaces Task 12's [FileTreePanelPlaceholder]; same parameter signature, pure call-site rename.
 *
 * Phase 1 scope: root tree only. Sub-directory expansion is a Phase 4 refinement
 * (directory clicks are no-ops here).
 */
@Composable
fun FileTreePanel(
    uiState: WorkspaceUiState,
    onRefreshRoot: () -> Unit,
    onToggleShowIgnored: () -> Unit,
    onOpenFile: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val emptyDirectoryMessage = stringResource(R.string.workspace_empty_directory)
    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(SpacingTokens.SM.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onRefreshRoot) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.workspace_refresh))
            }
            FilterChip(
                selected = uiState.showIgnored,
                onClick = onToggleShowIgnored,
                label = { Text(stringResource(R.string.workspace_show_ignored)) },
                leadingIcon = { Icon(Icons.Filled.Visibility, contentDescription = null) }
            )
        }
        when {
            uiState.rootLoading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.testTag("file_tree_loading"))
            }

            uiState.rootError != null -> FileTreeErrorState(
                error = uiState.rootError,
                onRetry = onRefreshRoot
            )

            uiState.rootNodes.isEmpty() -> FileTreeEmptyState(message = emptyDirectoryMessage)

            else -> {
                val flattened = remember(uiState.rootNodes, uiState.showIgnored) {
                    flatten(uiState.rootNodes, depth = 0, showIgnored = uiState.showIgnored)
                }
                LazyColumn {
                    items(flattened, key = { it.first.node.path }) { (node, depth) ->
                        FileTreeItem(node = node, depth = depth, onOpenFile = onOpenFile)
                    }
                }
            }
        }
    }
}

/**
 * Recursively flattens the tree into a (node, depth) list for [LazyColumn].
 * Ignored nodes are filtered out unless [showIgnored] is true.
 */
private fun flatten(
    nodes: List<FileTreeNode>,
    depth: Int,
    showIgnored: Boolean
): List<Pair<FileTreeNode, Int>> =
    nodes.filter { showIgnored || !it.node.ignored }.flatMap { node ->
        listOf(node to depth) + (node.children?.let { children ->
            flatten(children, depth + 1, showIgnored)
        } ?: emptyList())
    }

/**
 * Single row in the file tree. Files invoke [onOpenFile] with their path;
 * directories are no-ops in Phase 1 (expansion arrives in Phase 4).
 */
@Composable
fun FileTreeItem(
    node: FileTreeNode,
    depth: Int,
    onOpenFile: (String) -> Unit
) {
    val isDirectory = node.node.isDirectory()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (!isDirectory) onOpenFile(node.node.path)
                // TODO Phase 4: directory expansion via dirLoadEvents
            }
            .padding(start = (depth * SpacingTokens.LG).dp)
            .padding(vertical = SpacingTokens.SM.dp, horizontal = SpacingTokens.MD.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isDirectory) Icons.Filled.Folder else Icons.Filled.Description,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(SpacingTokens.SM.dp))
        Text(
            text = node.node.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (node.isLoading) {
            Spacer(Modifier.width(SpacingTokens.SM.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp
            )
        }
    }
}

@Composable
private fun FileTreeErrorState(
    error: Int,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.MD.dp)
        ) {
            Text(
                text = stringResource(error),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            TextButton(onClick = onRetry) { Text(stringResource(R.string.workspace_retry)) }
        }
    }
}

@Composable
private fun FileTreeEmptyState(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
