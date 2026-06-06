package dev.minios.ocremote.ui.screens.chat.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.minios.ocremote.R
import dev.minios.ocremote.ui.components.AmoledDefaultBorder
import dev.minios.ocremote.ui.components.DialogButtonRole
import dev.minios.ocremote.ui.components.DialogButtons
import dev.minios.ocremote.ui.components.amoledDialogParams
import dev.minios.ocremote.ui.screens.chat.util.formatTokenCount
import dev.minios.ocremote.ui.screens.chat.util.isAmoledTheme
import dev.minios.ocremote.ui.theme.AlphaTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatTopBar(
    sessionTitle: String,
    messageCount: Int,
    totalInputTokens: Int,
    totalOutputTokens: Int,
    totalReasoningTokens: Int = 0,
    totalCacheReadTokens: Int = 0,
    totalCacheWriteTokens: Int = 0,
    totalCost: Double,
    sessionParentId: String?,
    shareUrl: String?,
    contextWindow: Int = 0,
    lastContextTokens: Int = 0,
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
    currentAgentName: String? = null,
    currentModelId: String? = null,
) {
    var showMenu by remember { mutableStateOf(false) }
    var showContextDialog by remember { mutableStateOf(false) }

    TopAppBar(
        title = {
            Column {
                Text(
                    text = sessionTitle.ifBlank { stringResource(R.string.chat_title_placeholder) },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // Subtitle: message count, total tokens (all types), and cost
                val totalTokens = totalInputTokens + totalOutputTokens + totalReasoningTokens + totalCacheReadTokens + totalCacheWriteTokens
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
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED)
                        )
                    }
                }
                if (!currentAgentName.isNullOrBlank() || !currentModelId.isNullOrBlank()) {
                    val agentParts = mutableListOf<String>()
                    if (!currentAgentName.isNullOrBlank()) agentParts.add(currentAgentName)
                    if (!currentModelId.isNullOrBlank()) agentParts.add(currentModelId)
                    Text(
                        text = agentParts.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.MUTED)
                    )
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
                // Context progress indicator
                val showContext = contextWindow > 0 && lastContextTokens > 0
                if (showContext) {
                    val percentage = Math.round(lastContextTokens.toDouble() / contextWindow * 100).toInt()
                        .coerceIn(0, 100)
                    val contextColor = when {
                        percentage >= 90 -> MaterialTheme.colorScheme.error
                        percentage >= 70 -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.primary
                    }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .clickable { showContextDialog = true }
                    ) {
                        CircularProgressIndicator(
                            progress = { percentage / 100f },
                            strokeWidth = 3.dp,
                            color = contextColor,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            text = "$percentage",
                            style = MaterialTheme.typography.labelSmall,
                            color = contextColor
                        )
                    }
                }

                // Context detail dialog
                if (showContextDialog) {
                    val contextParams = amoledDialogParams()
                    BasicAlertDialog(
                        onDismissRequest = { showContextDialog = false },
                        properties = DialogProperties(usePlatformDefaultWidth = false),
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(0.92f),
                            color = contextParams.containerColor,
                            tonalElevation = contextParams.tonalElevation,
                            border = contextParams.border,
                            shape = contextParams.shape,
                        ) {
                            Column(modifier = Modifier.padding(24.dp)) {
                                Text(
                                    text = stringResource(R.string.chat_context_detail_title),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Spacer(Modifier.height(16.dp))
                                TokenUsageCard(
                                    inputTokens = totalInputTokens,
                                    outputTokens = totalOutputTokens,
                                    reasoningTokens = totalReasoningTokens,
                                    cacheReadTokens = totalCacheReadTokens,
                                    cacheWriteTokens = totalCacheWriteTokens,
                                    totalCost = totalCost,
                                    contextWindow = contextWindow
                                )
                                Spacer(Modifier.height(16.dp))
                                DialogButtons(
                                    buttons = listOf(
                                        Triple(stringResource(R.string.close), DialogButtonRole.Primary) { showContextDialog = false },
                                    )
                                )
                            }
                        }
                    }
                }

                Box {
                    val isAmoled = isAmoledTheme()
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        containerColor = MaterialTheme.colorScheme.surface,
                        border = if (isAmoled) AmoledDefaultBorder else null
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.tool_terminal)) },
                            onClick = {
                                showMenu = false
                                onTerminalMode()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Terminal, contentDescription = stringResource(R.string.a11y_icon_terminal))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_open_in_web)) },
                            onClick = {
                                showMenu = false
                                onOpenInWebView()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Language, contentDescription = stringResource(R.string.a11y_icon_language))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_new_session)) },
                            onClick = {
                                showMenu = false
                                onNewSession()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.a11y_icon_add))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_fork_session)) },
                            onClick = {
                                showMenu = false
                                onForkSession()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.CopyAll, contentDescription = stringResource(R.string.a11y_icon_copy_all))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_compact_session)) },
                            onClick = {
                                showMenu = false
                                onCompactSession()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Compress, contentDescription = stringResource(R.string.a11y_icon_compress))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_review_changes)) },
                            onClick = {
                                showMenu = false
                                onReviewChanges()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.RateReview, contentDescription = stringResource(R.string.a11y_icon_rate_review))
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
                                    Icon(Icons.Default.LinkOff, contentDescription = stringResource(R.string.a11y_icon_unlink))
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
                                    Icon(Icons.Default.Share, contentDescription = stringResource(R.string.a11y_icon_share))
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
                                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.a11y_icon_edit))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_export_session)) },
                            onClick = {
                                showMenu = false
                                onExport()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.FileDownload, contentDescription = stringResource(R.string.a11y_icon_file_download))
                            }
                        )
                    }
                }
            }
        }
    )
}

