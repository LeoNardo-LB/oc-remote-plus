package dev.minios.ocremote.ui.screens.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.CopyAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.R
import dev.minios.ocremote.ui.screens.chat.util.formatTokenCount
import dev.minios.ocremote.ui.screens.chat.util.isAmoledTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatTopBar(
    sessionTitle: String,
    messageCount: Int,
    totalInputTokens: Int,
    totalOutputTokens: Int,
    totalCost: Double,
    sessionParentId: String?,
    shareUrl: String?,
    onNavigateBack: () -> Unit,
    onTerminalMode: () -> Unit,
    onOpenInWebView: () -> Unit,
    onNewSession: () -> Unit,
    onForkSession: () -> Unit,
    onCompactSession: () -> Unit,
    onReviewChanges: () -> Unit,
    onShare: () -> Unit,
    onUnshare: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    TopAppBar(
        title = {
            Column {
                Text(
                    text = sessionTitle.ifBlank { stringResource(R.string.chat_title_placeholder) },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // Subtitle: chat items count, total tokens and cost for the session
                val totalTokens = totalInputTokens + totalOutputTokens
                val hasStats = messageCount > 0 || totalTokens > 0 || totalCost > 0
                if (hasStats) {
                    val parts = mutableListOf<String>()
                    if (messageCount > 0) {
                        parts.add(stringResource(R.string.chat_items_count, messageCount))
                    }
                    if (totalTokens > 0) {
                        parts.add(stringResource(R.string.chat_tokens_summary, formatTokenCount(totalTokens)))
                    }
                    if (totalCost > 0) {
                        parts.add(stringResource(R.string.chat_cost_format, String.format("%.4f", totalCost)))
                    }
                    if (parts.isNotEmpty()) {
                        Text(
                            text = parts.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
        },
        actions = {
            if (sessionParentId == null) {
                Box {
                    val isAmoled = isAmoledTheme()
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        containerColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
                        border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.tool_terminal)) },
                            onClick = {
                                showMenu = false
                                onTerminalMode()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Terminal, contentDescription = null)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_open_in_web)) },
                            onClick = {
                                showMenu = false
                                onOpenInWebView()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Language, contentDescription = null)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_new_session)) },
                            onClick = {
                                showMenu = false
                                onNewSession()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Add, contentDescription = null)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_fork_session)) },
                            onClick = {
                                showMenu = false
                                onForkSession()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.CopyAll, contentDescription = null)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_compact_session)) },
                            onClick = {
                                showMenu = false
                                onCompactSession()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Compress, contentDescription = null)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_review_changes)) },
                            onClick = {
                                showMenu = false
                                onReviewChanges()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.RateReview, contentDescription = null)
                            },
                        )
                        // Show Share or Unshare depending on current share status
                        if (shareUrl != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.cmd_unshare)) },
                                onClick = {
                                    showMenu = false
                                    onUnshare()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.LinkOff, contentDescription = null)
                                }
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_share_session)) },
                                onClick = {
                                    showMenu = false
                                    onShare()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Share, contentDescription = null)
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_rename_session)) },
                            onClick = {
                                showMenu = false
                                onRename()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Edit, contentDescription = null)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_export_session)) },
                            onClick = {
                                showMenu = false
                                onExport()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.FileDownload, contentDescription = null)
                            }
                        )
                    }
                }
            }
        }
    )
}
