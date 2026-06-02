package dev.minios.ocremote.ui.screens.sessions.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import androidx.compose.foundation.layout.height
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.R
import dev.minios.ocremote.ui.components.AppDialog
import dev.minios.ocremote.ui.components.AppDialogButtons
import dev.minios.ocremote.ui.components.ButtonStyle
import dev.minios.ocremote.ui.theme.AlphaTokens

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DirectoryTreeNode(
    node: TreeNode.Directory,
    onClick: () -> Unit,
    onCopyPath: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isAmoled = isAmoledTheme()
    var showDetailsDialog by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 64.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showDetailsDialog = true },
            )
            .padding(start = 12.dp, end = 8.dp)
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (node.isExpanded) Icons.Default.FolderOpen else Icons.Default.Folder,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = node.displayName,
                style = MaterialTheme.typography.bodyMedium,
                softWrap = false,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
            Spacer(modifier = Modifier.height(1.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.directory_session_count, node.sessionCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
                )
            }
        }
    }

    if (showDetailsDialog) {
        DirectoryDetailsDialog(
            node = node,
            onDismiss = { showDetailsDialog = false },
            onCopyPath = { onCopyPath(node.path) },
            isAmoled = isAmoled,
        )
    }
}

@Composable
private fun DirectoryDetailsDialog(
    node: TreeNode.Directory,
    onDismiss: () -> Unit,
    onCopyPath: () -> Unit,
    isAmoled: Boolean,
) {
    AppDialog(
        onDismiss = onDismiss,
        title = node.displayName,
        isAmoled = isAmoled,
        content = {
            DetailRow(label = stringResource(R.string.session_path), value = node.path)
            DetailRow(label = stringResource(R.string.session_count), value = node.sessionCount.toString())
        },
        buttons = {
            AppDialogButtons(
                buttons = listOf(
                    Triple(stringResource(R.string.session_copy_path), ButtonStyle.Secondary, onCopyPath),
                )
            )
        }
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
