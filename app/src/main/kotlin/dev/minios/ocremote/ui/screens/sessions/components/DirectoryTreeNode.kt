package dev.minios.ocremote.ui.screens.sessions.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.R
import dev.minios.ocremote.ui.components.AmoledSurface
import dev.minios.ocremote.ui.theme.AlphaTokens
import dev.minios.ocremote.ui.theme.ShapeTokens

internal fun Modifier.treeLines(
    depth: Int,
    isLastChild: Boolean,
    lineColor: Color,
): Modifier = this.drawBehind {
    if (depth <= 0) return@drawBehind

    val indentWidth = 24.dp.toPx()
    val strokeWidth = 1.dp.toPx()

    for (level in 0 until depth) {
        val x = indentWidth * level + indentWidth / 2
        if (level == depth - 1) {
            val horizontalY = size.height / 2
            drawLine(lineColor, Offset(x, 0f), Offset(x, horizontalY), strokeWidth)
            drawLine(lineColor, Offset(x, horizontalY), Offset(x + indentWidth / 2, horizontalY), strokeWidth)
            if (!isLastChild) {
                drawLine(lineColor, Offset(x, horizontalY), Offset(x, size.height.toFloat()), strokeWidth)
            }
        } else {
            drawLine(lineColor, Offset(x, 0f), Offset(x, size.height.toFloat()), strokeWidth)
        }
    }
}

@Composable
internal fun DirectoryTreeNode(
    node: TreeNode.Directory,
    onClick: () -> Unit,
    onCopyPath: (String) -> Unit,
    modifier: Modifier = Modifier,
    isLastChild: Boolean = false,
) {
    val isAmoled = isAmoledTheme()
    var menuExpanded by remember { mutableStateOf(false) }
    var showDetailsDialog by remember { mutableStateOf(false) }

    val arrowRotation by animateFloatAsState(
        targetValue = if (node.isExpanded) 90f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "arrowRotation"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .treeLines(
                depth = node.depth,
                isLastChild = isLastChild,
                lineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.MUTED),
            )
            .padding(start = minOf(node.depth * 24, 360).dp, end = 8.dp)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier
                .size(20.dp)
                .rotate(arrowRotation),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = if (node.isExpanded) Icons.Default.FolderOpen else Icons.Default.Folder,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = node.displayName,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        if (node.totalSessionCount > 0) {
            Text(
                text = stringResource(R.string.directory_session_count, node.totalSessionCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED),
            )
        }
        Box {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                containerColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceContainer,
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_copy_path)) },
                    onClick = {
                        menuExpanded = false
                        onCopyPath(node.path)
                    },
                    leadingIcon = {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_view_details)) },
                    onClick = {
                        menuExpanded = false
                        showDetailsDialog = true
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                )
            }
        }
    }

    if (showDetailsDialog) {
        DirectoryDetailsDialog(
            node = node,
            onDismiss = { showDetailsDialog = false },
            isAmoled = isAmoled,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DirectoryDetailsDialog(
    node: TreeNode.Directory,
    onDismiss: () -> Unit,
    isAmoled: Boolean,
) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        AmoledSurface(
            isAmoledDark = isAmoled,
            shape = ShapeTokens.largeMedium,
            normalTonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = node.displayName, style = MaterialTheme.typography.headlineSmall)
                HorizontalDivider()
                DetailRow(stringResource(R.string.directory_details_path), node.path)
                DetailRow(
                    stringResource(R.string.directory_details_sessions),
                    "${node.sessionCount} direct, ${node.totalSessionCount} total"
                )
                DetailRow(
                    stringResource(R.string.directory_details_subdirectories),
                    "${node.childDirectoryCount}"
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
                }
            }
        }
    }
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
