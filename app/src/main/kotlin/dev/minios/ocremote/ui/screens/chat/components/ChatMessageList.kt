package dev.minios.ocremote.ui.screens.chat.components

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.R
import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.ui.components.ConfirmDialog
import dev.minios.ocremote.ui.components.DialogButtonRole
import dev.minios.ocremote.ui.components.DialogButtons
import dev.minios.ocremote.ui.screens.chat.ChatMessage
import dev.minios.ocremote.ui.screens.chat.ChatUiState
import dev.minios.ocremote.ui.screens.chat.ChatViewModel
import dev.minios.ocremote.ui.screens.chat.dialog.PermissionCard
import dev.minios.ocremote.ui.screens.chat.dialog.QuestionCard
import dev.minios.ocremote.ui.screens.chat.components.AlwaysConfirmDialog
import dev.minios.ocremote.domain.model.SseEvent
import dev.minios.ocremote.ui.screens.chat.util.computeTurnGroups
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import dev.minios.ocremote.ui.theme.ShapeTokens
import dev.minios.ocremote.ui.theme.AlphaTokens

/**
 * Shared composable for both main-session and sub-session message lists.
 *
 * Contains: PullToRefreshBox > LazyColumn (pending questions/permissions, revert banner,
 * message items) + scroll-to-bottom FAB + streaming message card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatMessageList(
    listState: LazyListState,
    uiState: ChatUiState,
    rawMessages: List<ChatMessage>,
    displayItems: List<Pair<Int, ChatMessage>>,
    isAtBottom: Boolean,
    isAmoled: Boolean,
    messageSpacing: Dp,
    isMainSession: Boolean,
    coroutineScope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    context: Context,
    clipboardManager: ClipboardManager,
    keyboardController: SoftwareKeyboardController?,
    viewModel: ChatViewModel,
    navigateToChildSession: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val turnGroups = computeTurnGroups(rawMessages)

    Column(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            var showAlwaysDialog by remember { mutableStateOf<SseEvent.PermissionAsked?>(null) }
            val pullToRefreshState = rememberPullToRefreshState()
            PullToRefreshBox(
                isRefreshing = uiState.isLoadingOlder,
                onRefresh = {
                    if (uiState.hasOlderMessages) viewModel.loadOlderMessages()
                },
                state = pullToRefreshState,
                modifier = Modifier.fillMaxSize()
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                        .pointerInput(Unit) { detectTapGestures(onTap = { keyboardController?.hide() }) },
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        top = 8.dp,
                        end = 12.dp,
                        bottom = 8.dp
                    ),
                    reverseLayout = true,
                    verticalArrangement = Arrangement.spacedBy(messageSpacing)
                ) {
                    // reverseLayout=true: items declared first render at the BOTTOM.
                    // Visual order (top→bottom): oldest msgs → newest msgs → revert → pending.
                    // Declaration order is bottom-up: pending (bottom) → messages (top).

                    // Pending questions (declared first = bottom-most visually)
                    // 批量问题操作栏 - 当有2个及以上问题时显示
                    if (uiState.pendingQuestions.size > 1) {
                        item(key = "question_batch_actions") {
                            QuestionBatchActionBar(
                                count = uiState.pendingQuestions.size,
                                onSkipAll = {
                                    uiState.pendingQuestions.forEach { question ->
                                        viewModel.rejectQuestion(question.id)
                                    }
                                }
                            )
                        }
                    }
                    items(
                        uiState.pendingQuestions.reversed(),
                        key = { "question_${it.id}" }
                    ) { question ->
                        QuestionCard(
                            question = question,
                            onSubmit = { answers ->
                                viewModel.replyToQuestion(question.id, answers)
                            },
                            onReject = {
                                viewModel.rejectQuestion(question.id)
                            }
                        )
                    }

                    // 批量权限操作栏 - 当有2个及以上权限时显示
                    if (uiState.pendingPermissions.size > 1) {
                        item(key = "perm_batch_actions") {
                            PermissionBatchActionBar(
                                count = uiState.pendingPermissions.size,
                                onAllowAll = {
                                    uiState.pendingPermissions.forEach { perm ->
                                        viewModel.replyToPermission(perm.id, "once")
                                    }
                                },
                                onRejectAll = {
                                    uiState.pendingPermissions.forEach { perm ->
                                        viewModel.replyToPermission(perm.id, "reject")
                                    }
                                }
                            )
                        }
                    }
                    // Pending permissions
                    items(
                        uiState.pendingPermissions.reversed(),
                        key = { "perm_${it.id}" }
                    ) { permission ->
                        PermissionCard(
                            permission = permission,
                            onOnce = { viewModel.replyToPermission(permission.id, "once") },
                            onAlways = { showAlwaysDialog = permission },
                            onReject = { viewModel.replyToPermission(permission.id, "reject") }
                        )
                    }

                    // Revert banner
                    if (uiState.revert != null) {
                        item(key = "revert_banner") {
                            RevertBanner(onRedo = {
                                viewModel.redoMessage { ok ->
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar(
                                            if (ok) context.getString(R.string.chat_messages_restored) else context.getString(R.string.chat_message_redo_failed)
                                        )
                                    }
                                }
                            })
                        }
                    }

                    // Chat messages: displayItems.reversed() so newest is at index 0 (bottom in reverseLayout).
                    // Visual result: oldest at top, newest at bottom.
                    itemsIndexed(
                        displayItems.reversed(),
                        key = { _, item -> item.second.message.id },
                        contentType = { _, item -> if (item.second.isUser) "user" else "assistant" }
                    ) { _, (rawIndex, msg) ->
                        when {
                            msg.isAssistant -> {
                                val turnMessagesForMsg = turnGroups[rawIndex] ?: listOf(msg)
                                val isTurnLast = rawIndex == rawMessages.lastIndex || rawMessages.getOrNull(rawIndex + 1)?.isAssistant != true

                                MessageCard(
                                    role = MessageCardRole.ASSISTANT,
                                    turnMessages = turnMessagesForMsg,
                                    currentMessage = msg,
                                    onViewSubSession = navigateToChildSession,
                                    isAmoled = isAmoled,
                                    isTurnLast = isTurnLast,
                                    onCopyText = if (isTurnLast) {
                                        {
                                            val messages = turnMessagesForMsg
                                            val text = messages.flatMap { m ->
                                                m.parts.filterIsInstance<Part.Text>().map { it.text }
                                            }.joinToString("\n\n")
                                            if (text.isNotBlank()) {
                                                clipboardManager.setText(AnnotatedString(text))
                                                coroutineScope.launch {
                                                    snackbarHostState.showSnackbar(
                                                        context.getString(R.string.chat_copied_clipboard)
                                                    )
                                                }
                                            }
                                        }
                                    } else null
                                )
                            }
                            msg.isUser -> {
                                val chatMessage = msg

                                val isCompactionTrigger = chatMessage.parts.any { it is Part.Compaction }

                                if (isCompactionTrigger) {
                                    var showRevertDialog by remember { mutableStateOf(false) }

                                    if (showRevertDialog) {
                                        ConfirmDialog(
                                            title = stringResource(R.string.chat_revert_title),
                                            message = stringResource(R.string.chat_revert_message),
                                            confirmLabel = stringResource(R.string.chat_revert),
                                            onDismiss = { showRevertDialog = false },
                                            onConfirm = {
                                                showRevertDialog = false
                                                viewModel.revertMessage(chatMessage.message.id) { ok ->
                                                    coroutineScope.launch {
                                                        snackbarHostState.showSnackbar(
                                                            if (ok) context.getString(R.string.chat_messages_restored) else context.getString(R.string.chat_message_redo_failed)
                                                        )
                                                    }
                                                }
                                            },
                                        )
                                    }

                                    @OptIn(ExperimentalFoundationApi::class)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .combinedClickable(
                                                onClick = { },
                                                onLongClick = { showRevertDialog = true }
                                            )
                                            .padding(vertical = 4.dp, horizontal = 32.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        HorizontalDivider(
                                            modifier = Modifier.weight(1f),
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT)
                                        )
                                        Text(
                                            text = stringResource(R.string.chat_summarized),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED),
                                            modifier = Modifier.padding(horizontal = 12.dp)
                                        )
                                        HorizontalDivider(
                                            modifier = Modifier.weight(1f),
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT)
                                        )
                                    }
                                    return@itemsIndexed
                                }

                                MessageCard(
                                    role = MessageCardRole.USER,
                                    currentMessage = chatMessage,
                                    isQueued = chatMessage.message.id in uiState.queuedMessageIds,
                                    onViewSubSession = navigateToChildSession,
                                    onRevert = if (isMainSession) {
                                        {
                                            val revertText = chatMessage.parts
                                                .filterIsInstance<Part.Text>()
                                                .joinToString("\n") { it.text }
                                            viewModel.revertMessage(chatMessage.message.id, revertText) { ok ->
                                                coroutineScope.launch {
                                                    snackbarHostState.showSnackbar(
                                                        if (ok) context.getString(R.string.chat_message_reverted) else context.getString(R.string.chat_message_revert_failed)
                                                    )
                                                }
                                            }
                                        }
                                    } else null,
                                    onCopyText = {
                                        val text = chatMessage.parts
                                            .filterIsInstance<Part.Text>()
                                            .joinToString("\n") { it.text }
                                        if (text.isNotBlank()) {
                                            clipboardManager.setText(
                                                AnnotatedString(text)
                                            )
                                            coroutineScope.launch {
                                                snackbarHostState.showSnackbar(context.getString(R.string.chat_copied_clipboard))
                                            }
                                        }
                                    },
                                    isAmoled = isAmoled
                                )
                            }
                        }
                    }
                }
            } // PullToRefreshBox

            // Scroll-to-bottom FAB
            if (!isAtBottom) {
                SmallFloatingActionButton(
                    onClick = {
                        coroutineScope.launch {
                            // reverseLayout=true: item 0 = bottom (newest messages)
                            listState.snapToBottom()
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.chat_scroll_bottom),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            // Always-allow confirmation dialog
            showAlwaysDialog?.let { perm ->
                AlwaysConfirmDialog(
                    toolName = perm.permission,
                    directoryPattern = viewModel.getSessionDirectory() ?: "*",
                    onConfirm = {
                        viewModel.savePermissionRule(perm, viewModel.getSessionDirectory() ?: "*")
                        viewModel.replyToPermission(perm.id, "always")
                        showAlwaysDialog = null
                    },
                    onDismiss = { showAlwaysDialog = null }
                )
            }
        } // Box(weight)
    } // Column
}

/** Snap scroll to absolute bottom for reverseLayout LazyColumn. */
private suspend fun LazyListState.snapToBottom() {
    if (layoutInfo.totalItemsCount == 0) return
    scrollToItem(0)
    repeat(3) {
        delay(16)
        if (!canScrollBackward) return
        scroll { scrollBy(-10_000f) }
    }
}

/**
 * 批量权限操作栏：全部允许 / 全部拒绝
 */
@Composable
private fun PermissionBatchActionBar(
    count: Int,
    onAllowAll: () -> Unit,
    onRejectAll: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = ShapeTokens.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$count 项权限请求",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            DialogButtons(
                buttons = listOf(
                    Triple("全部拒绝", DialogButtonRole.Danger) { onRejectAll() },
                    Triple("全部允许", DialogButtonRole.Primary) { onAllowAll() },
                )
            )
        }
    }
}

/**
 * 批量问题操作栏：全部跳过（question 的 reply 需要 answers 参数，批量回答不实际）
 */
@Composable
private fun QuestionBatchActionBar(
    count: Int,
    onSkipAll: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = ShapeTokens.medium,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$count 项问题请求",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            DialogButtons(
                buttons = listOf(
                    Triple("全部跳过", DialogButtonRole.Secondary) { onSkipAll() },
                )
            )
        }
    }
}
