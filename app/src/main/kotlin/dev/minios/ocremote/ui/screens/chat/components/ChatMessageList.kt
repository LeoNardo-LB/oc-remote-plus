package dev.minios.ocremote.ui.screens.chat.components

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.ErrorOutline
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
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.R
import dev.minios.ocremote.domain.model.CompactionStateInfo
import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.domain.model.SessionStatus
import dev.minios.ocremote.domain.model.StepProgressInfo
import dev.minios.ocremote.domain.model.ToolProgressInfo
import dev.minios.ocremote.ui.components.ConfirmDialog
import dev.minios.ocremote.ui.components.DialogButtonRole
import dev.minios.ocremote.ui.components.DialogButtons
import dev.minios.ocremote.ui.screens.chat.ChatMessage
import dev.minios.ocremote.ui.screens.chat.ChatViewModel
import dev.minios.ocremote.ui.screens.chat.InteractionState
import dev.minios.ocremote.ui.screens.chat.MessageListState
import dev.minios.ocremote.ui.screens.chat.SessionMetaState
import dev.minios.ocremote.ui.screens.chat.dialog.PermissionCard
import dev.minios.ocremote.ui.screens.chat.dialog.QuestionCard
import dev.minios.ocremote.ui.screens.chat.components.AlwaysConfirmDialog
import dev.minios.ocremote.domain.model.SseEvent
import dev.minios.ocremote.ui.screens.chat.snapToBottom
import dev.minios.ocremote.ui.screens.chat.util.computeTurnGroups
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import dev.minios.ocremote.ui.theme.ShapeTokens
import dev.minios.ocremote.ui.theme.AlphaTokens
import dev.minios.ocremote.ui.theme.SpacingTokens

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
    messageState: MessageListState,
    sessionMeta: SessionMetaState,
    interaction: InteractionState,
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
    agents: List<dev.minios.ocremote.domain.model.AgentInfo> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val turnGroups = remember(rawMessages) { computeTurnGroups(rawMessages) }

    val streamingMsgId = remember(rawMessages) {
        rawMessages.lastOrNull {
            it.isAssistant && it.message.time.completed == null
        }?.message?.id
    }

    val compensateState = remember(streamingMsgId) { CompensateState() }

    // Track whether user has scrolled away from bottom.
    // When shouldCompensate=true, SSE height growth is counteracted via
    // requestScrollToItem inside the layout modifier — no height freeze.
    LaunchedEffect(listState.isScrollInProgress, isAtBottom) {
        if (listState.isScrollInProgress) {
            compensateState.shouldCompensate = true
        } else if (isAtBottom) {
            compensateState.shouldCompensate = false
        }
    }

    // Real-time status from ChatRepository — domain types
    val currentSessionId = viewModel.sessionId
    val toolProgress by viewModel.chatRepositoryExposed.getActiveToolProgressForSession(currentSessionId).collectAsStateWithLifecycle(initialValue = null)
    val stepProgress by viewModel.chatRepositoryExposed.getStepProgressForSession(currentSessionId).collectAsStateWithLifecycle(initialValue = null)
    val compactionState by viewModel.chatRepositoryExposed.getCompactionStateForSession(currentSessionId).collectAsStateWithLifecycle(initialValue = null)
    val activeTools = toolProgress.orEmpty().map { 
        ToolProgressInfo(callId = it.callId, partId = it.partId, tool = it.tool, status = it.status, progress = it.progress, title = it.title)
    }
    val currentStep = stepProgress?.let { 
        StepProgressInfo(step = it.step, agent = it.agent, model = it.model)
    }
    val currentCompaction = compactionState?.let { 
        CompactionStateInfo(isActive = it.isActive, reason = it.reason)
    }

    Column(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            var showAlwaysDialog by remember { mutableStateOf<SseEvent.PermissionAsked?>(null) }
            val pullToRefreshState = rememberPullToRefreshState()
            PullToRefreshBox(
                isRefreshing = messageState.isLoadingOlder,
                onRefresh = {
                    if (messageState.hasOlderMessages) viewModel.loadOlderMessages()
                },
                state = pullToRefreshState,
                modifier = Modifier.fillMaxSize()
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                        .pointerInput(Unit) { detectTapGestures(onTap = { keyboardController?.hide() }) },
                    contentPadding = PaddingValues(
                        start = SpacingTokens.MD.dp,
                        top = SpacingTokens.SM.dp,
                        end = SpacingTokens.MD.dp,
                        bottom = SpacingTokens.SM.dp
                    ),
                    reverseLayout = true,
                    verticalArrangement = Arrangement.spacedBy(messageSpacing)
                ) {
                    // reverseLayout=true: items declared first render at the BOTTOM.
                    // Visual order (top→bottom): oldest msgs → newest msgs → revert → pending.
                    // Declaration order is bottom-up: pending (bottom) → messages (top).

                    // Pending questions (declared first = bottom-most visually)
                    // 批量问题操作栏 - 当有2个及以上问题时显示
                    if (interaction.pendingQuestions.size > 1) {
                        item(key = "question_batch_actions") {
                            QuestionBatchActionBar(
                                count = interaction.pendingQuestions.size,
                                onSkipAll = {
                                    interaction.pendingQuestions.forEach { question ->
                                        viewModel.rejectQuestion(question.id)
                                    }
                                }
                            )
                        }
                    }
                    items(
                        interaction.pendingQuestions.reversed(),
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
                    if (interaction.pendingPermissions.size > 1) {
                        item(key = "perm_batch_actions") {
                            PermissionBatchActionBar(
                                count = interaction.pendingPermissions.size,
                                onAllowAll = {
                                    interaction.pendingPermissions.forEach { perm ->
                                        viewModel.replyToPermission(perm.id, "once")
                                    }
                                },
                                onRejectAll = {
                                    interaction.pendingPermissions.forEach { perm ->
                                        viewModel.replyToPermission(perm.id, "reject")
                                    }
                                }
                            )
                        }
                    }
                    // Pending permissions
                    items(
                        interaction.pendingPermissions.reversed(),
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
                    if (sessionMeta.revert != null) {
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

                    // Compaction banner
                    if (currentCompaction != null && currentCompaction.isActive) {
                        item(key = "compaction_banner") {
                            CompactionBanner(state = currentCompaction)
                        }
                    }

                    // Retry banner — shown when session is in Retry status
                    val retryStatus = sessionMeta.sessionStatus
                    if (retryStatus is SessionStatus.Retry) {
                        item(key = "retry_banner") {
                            RetryBanner(retryStatus)
                        }
                    }

                    // Tool progress cards
                    if (activeTools.isNotEmpty()) {
                        items(
                            activeTools,
                            key = { "tool_progress_${it.callId}" }
                        ) { toolInfo ->
                            ToolProgressCard(toolInfo = toolInfo)
                        }
                    }

                    // Step progress indicator
                    if (currentStep != null) {
                        item(key = "step_progress") {
                            StepProgressIndicator(stepInfo = currentStep)
                        }
                    }

                    // Chat messages: displayItems is already newest-first (descending).
                    // reverseLayout=true renders index 0 (newest) at the bottom.
                    // Visual result: oldest at top, newest at bottom.
                    itemsIndexed(
                        displayItems,
                        key = { _, item -> item.second.message.id },
                        contentType = { _, item -> if (item.second.isUser) "user" else "assistant" }
                    ) { _, (rawIndex, msg) ->
                        val isStreamingMsg = msg.message.id == streamingMsgId
                        val itemModifier = if (isStreamingMsg) {
                            Modifier
                                .fillMaxWidth()
                                .layout { measurable, constraints ->
                                    val placeable = measurable.measure(
                                        constraints.copy(maxHeight = Constraints.Infinity)
                                    )
                                    val realHeight = placeable.height

                                    // Compensate SSE height growth — NO FREEZE
                                    // When item grows by delta, anchor reduces offset by delta.
                                    // requestScrollToItem restores offset in same/next measure pass.
                                    if (compensateState.shouldCompensate && !listState.isScrollInProgress) {
                                        val delta = realHeight - compensateState.lastHeight
                                        if (delta > 0) {
                                            // Compensate delta + 20% to cover second-pass SSE growth
                                            val compensated = (delta * 1.2f).toInt()
                                            android.util.Log.d("FREEZE", "COMP: delta=$delta comp=$compensated real=$realHeight off=${listState.firstVisibleItemScrollOffset}")
                                            listState.requestScrollToItem(
                                                listState.firstVisibleItemIndex,
                                                listState.firstVisibleItemScrollOffset + compensated
                                            )
                                        }
                                    }
                                    compensateState.lastHeight = realHeight

                                    layout(placeable.width, realHeight) {
                                        placeable.placeRelative(0, 0)
                                    }
                                }
                        } else Modifier.fillMaxWidth()
                        Box(modifier = itemModifier) {
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
                                    agents = agents,
                                    copyText = if (isTurnLast) {
                                        val turnTexts = turnMessagesForMsg
                                        remember(turnTexts) {
                                            turnTexts.asReversed()
                                                .flatMap { m -> m.parts.filterIsInstance<Part.Text>().map { it.text } }
                                                .joinToString("\n\n")
                                                .takeIf { it.isNotBlank() }
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
                                            .padding(vertical = SpacingTokens.XS.dp, horizontal = SpacingTokens.XXL.dp),
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
                                            modifier = Modifier.padding(horizontal = SpacingTokens.MD.dp)
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
                                    isQueued = chatMessage.message.id in messageState.queuedMessageIds,
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
                        } // Box freeze
                    }
                }
            } // PullToRefreshBox

            // Scroll-to-bottom FAB
            if (!isAtBottom) {
                SmallFloatingActionButton(
                    onClick = {
                        coroutineScope.launch {
                            listState.snapToBottom()
                            compensateState.shouldCompensate = false
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = SpacingTokens.SM.dp),
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
            .padding(horizontal = SpacingTokens.LG.dp),
        shape = ShapeTokens.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = SpacingTokens.LG.dp, vertical = SpacingTokens.SM.dp),
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
            .padding(horizontal = SpacingTokens.LG.dp, vertical = SpacingTokens.XS.dp),
        shape = ShapeTokens.medium,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = SpacingTokens.LG.dp, vertical = SpacingTokens.SM.dp),
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

@Composable
private fun RetryBanner(retry: SessionStatus.Retry) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = AlphaTokens.FAINT),
        shape = ShapeTokens.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SpacingTokens.XS.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.padding(SpacingTokens.MD.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.size(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.session_status_retry, retry.attempt, retry.attempt),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                if (retry.message.isNotBlank()) {
                    Text(
                        text = retry.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = AlphaTokens.HIGH)
                    )
                }
            }
        }
    }
}

private class CompensateState {
    var lastHeight: Int = 0
    var shouldCompensate: Boolean = false
}
