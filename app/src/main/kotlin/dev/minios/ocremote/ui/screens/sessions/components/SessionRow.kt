package dev.minios.ocremote.ui.screens.sessions.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.minios.ocremote.R
import dev.minios.ocremote.domain.model.SessionStatus
import dev.minios.ocremote.ui.components.AppDialog
import dev.minios.ocremote.ui.components.AppDialogButtons
import dev.minios.ocremote.ui.components.ButtonStyle
import dev.minios.ocremote.ui.screens.sessions.SessionItem
import dev.minios.ocremote.ui.theme.AlphaTokens
import dev.minios.ocremote.ui.theme.DiffAdded
import dev.minios.ocremote.ui.theme.DiffRemoved
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SessionRow(
    item: SessionItem,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onCopyId: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    val addColor = DiffAdded
    val delColor = DiffRemoved

    var showDetailsDialog by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 64.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showDetailsDialog = true },
            )
            .padding(start = 28.dp, end = 8.dp)
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Status icon
        val statusIconColor = when (item.status) {
            is SessionStatus.Busy -> MaterialTheme.colorScheme.tertiary
            is SessionStatus.Retry -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
        }
        Icon(
            imageVector = Icons.Outlined.ChatBubbleOutline,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = statusIconColor,
        )
        Spacer(modifier = Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.session.title ?: stringResource(R.string.session_untitled),
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
                    text = dateFormat.format(Date(item.session.time.updated)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
                )

                // Status label
                when (item.status) {
                    is SessionStatus.Busy -> {
                        Text(
                            text = stringResource(R.string.sessions_working),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    is SessionStatus.Retry -> {
                        Text(
                            text = stringResource(R.string.sessions_retrying),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    else -> {}
                }

                // Diff summary
                val summary = item.session.summary
                if (summary != null && (summary.additions > 0 || summary.deletions > 0)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        if (summary.additions > 0) {
                            Text(
                                text = stringResource(R.string.session_changes_additions, summary.additions),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = addColor,
                                ),
                            )
                        }
                        if (summary.deletions > 0) {
                            Text(
                                text = stringResource(R.string.session_changes_deletions, summary.deletions),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = delColor,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    // Details dialog with actions
    if (showDetailsDialog) {
        val isAmoled = isAmoledTheme()
        SessionDetailsDialog(
            item = item,
            onDismiss = { showDetailsDialog = false },
            onRename = {
                showDetailsDialog = false
                onRename()
            },
            onDelete = {
                showDetailsDialog = false
                onDelete()
            },
            onCopyId = {
                onCopyId(item.session.id)
            },
            isAmoled = isAmoled,
        )
    }
}

@Composable
private fun SessionDetailsDialog(
    item: SessionItem,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onCopyId: () -> Unit,
    isAmoled: Boolean,
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    AppDialog(
        onDismiss = onDismiss,
        title = item.session.title ?: stringResource(R.string.session_untitled),
        isAmoled = isAmoled,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DetailRow(stringResource(R.string.session_details_id), item.session.id)
                DetailRow(
                    stringResource(R.string.session_details_status),
                    when (item.status) {
                        is SessionStatus.Busy -> stringResource(R.string.session_status_busy)
                        is SessionStatus.Retry -> stringResource(R.string.session_status_retry)
                        else -> stringResource(R.string.session_status_idle)
                    }
                )
                DetailRow(
                    stringResource(R.string.session_details_created),
                    dateFormat.format(Date(item.session.time.created))
                )
                DetailRow(
                    stringResource(R.string.session_details_updated),
                    dateFormat.format(Date(item.session.time.updated))
                )
                val summary = item.session.summary
                if (summary != null) {
                    DetailRow(
                        "Diff",
                        "+${summary.additions} -${summary.deletions} (${summary.files} files)"
                    )
                }
            }
        },
        buttons = {
            AppDialogButtons(
                buttons = listOf(
                    Triple(stringResource(R.string.menu_copy_session_id), ButtonStyle.Secondary, onCopyId),
                    Triple(stringResource(R.string.session_rename), ButtonStyle.Secondary) { onDismiss(); onRename() },
                    Triple(stringResource(R.string.session_delete), ButtonStyle.Danger) { onDismiss(); onDelete() },
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
