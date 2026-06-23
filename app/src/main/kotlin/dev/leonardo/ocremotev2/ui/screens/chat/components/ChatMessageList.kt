package dev.leonardo.ocremotev2.ui.screens.chat.components

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
import dev.leonardo.ocremotev2.R
import dev.leonardo.ocremotev2.domain.model.CompactionStateInfo
import dev.leonardo.ocremotev2.domain.model.Part
import dev.leonardo.ocremotev2.domain.model.SessionStatus
import dev.leonardo.ocremotev2.domain.model.StepProgressInfo
import dev.leonardo.ocremotev2.domain.model.ToolProgressInfo
import dev.leonardo.ocremotev2.ui.components.ConfirmDialog
import dev.leonardo.ocremotev2.domain.model.SseEvent
import dev.leonardo.ocremotev2.ui.screens.chat.ChatMessage
import dev.leonardo.ocremotev2.ui.screens.chat.ChatViewModel
import dev.leonardo.ocremotev2.ui.screens.chat.InteractionState
import dev.leonardo.ocremotev2.ui.screens.chat.MessageListState
import dev.leonardo.ocremotev2.ui.screens.chat.SessionMetaState
import dev.leonardo.ocremotev2.ui.screens.chat.dialog.PermissionCard
import dev.leonardo.ocremotev2.ui.screens.chat.dialog.QuestionCard
import dev.leonardo.ocremotev2.ui.screens.chat.components.AlwaysConfirmDialog
import dev.leonardo.ocremotev2.ui.screens.chat.snapToBottom
import dev.leonardo.ocremotev2.ui.screens.chat.util.computeTurnGroups
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import dev.leonardo.ocremotev2.ui.theme.ShapeTokens
import dev.leonardo.ocremotev2.ui.theme.AlphaTokens
import dev.leonardo.ocremotev2.ui.theme.SpacingTokens

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
    onOpenFile: (filePath: String) -> Unit,
    onForceScrollToBottom: () -> Unit,
    agents: List<dev.leonardo.ocremotev2.domain.model.AgentInfo> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val turnGroups = remember(rawMessages) { computeTurnGroups(rawMessages) }

    val streamingMsgId = remember(rawMessages) {
        rawMessages.lastOrNull {
            it.isAssistant && it.message.time.completed == null
        }?.message?.id
    }

    val compensateState = remember { CompensateState() }
    val bannerCompensateState = remember { CompensateState() }

    // Per-message height tracking — ALL assistant messages get compensation,
    // not just the last streaming one. This prevents drift when non-streaming
    // messages (e.g. tool call cards, reasoning blocks) resize after content loads.
    val heightMap = remember { HashMap<String, Int>() }

    LaunchedEffect(streamingMsgId) {
        bannerCompensateState.lastHeight = 0
    }

    // Track whether user has scrolled away from bottom.
    // Once user scrolls (even slightly), shouldCompensate stays true
    // until they explicitly return via the scroll-to-bottom FAB.
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            compensateState.shouldCompensate = true
        }
    }

    // DIAG: poll all scroll params every 100ms while user is scrolled away
    LaunchedEffect(compensateState.shouldCompensate) {
        if (compensateState.shouldCompensate) {
            while (true) {
                val info = listState.layoutInfo
                val vis = info.visibleItemsInfo.joinToString(",") { "${it.key}:${it.size}@${it.offset}" }
                val hts = heightMap.entries.joinToString(",") { "${it.key.takeLast(8)}:${it.value}" }
                android.util.Log.d("ScrollDiag", "DUMP idx=${listState.firstVisibleItemIndex} off=${listState.firstVisibleItemScrollOffset} total=${info.totalItemsCount} scrollProg=${listState.isScrollInProgress} canBack=${listState.canScrollBackward} canFwd=${listState.canScrollForward} visN=${info.visibleItemsInfo.size} vis=[$vis] rev=${info.reverseLayout} hts=[$hts]")
                delay(100)
            }
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

                    // === Unified streaming banners ===
                    // All conditional banners merged into a single item to:
                    // ① Prevent item insertion/removal from causing index jumps
                    // ② Apply unified delta compensation for ALL height changes
                    // ③ Compensate both growth (delta > 0) and shrinkage (delta < 0)
                    item(key = "streaming_banners") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .layout { measurable, constraints ->
                                    val placeable = measurable.measure(
                                        constraints.copy(maxHeight = Constraints.Infinity)
                                    )
                                    val realHeight = placeable.height
                                    val delta = realHeight - bannerCompensateState.lastHeight
                                    // Skip compensation when streaming message is visible —
                                    // it has its own layout modifier that handles delta.
                                    val streamVisible = listState.layoutInfo.visibleItemsInfo.any {
                                        it.key == streamingMsgId
                                    }
                                    if (compensateState.shouldCompensate && delta != 0 && !streamVisible) {
                                        LazyListReflection.requestScrollToItemNoCancel(
                                            listState,
                                            listState.firstVisibleItemIndex,
                                            listState.firstVisibleItemScrollOffset + delta
                                        )
                                    }
                                    bannerCompensateState.lastHeight = realHeight
                                    layout(placeable.width, realHeight) {
                                        placeable.placeRelative(0, 0)
                                    }
                                }
                        ) {
                            // Revert banner
                            if (sessionMeta.revert != null) {
                                RevertBanner(onRedo = {
                                    viewModel.redoMessage { ok ->
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar(
                                                if (ok) context.getString(R.string.chat_messages_restored) else context.getString(R.string.chat_message_redo_failed)
                                            )
                                        }
                                    }
                                    onForceScrollToBottom()
                                })
                            }

                            // Compaction banner
                            if (currentCompaction != null && currentCompaction.isActive) {
                                CompactionBanner(state = currentCompaction)
                            }

                            // Retry banner
                            val retryStatus = sessionMeta.sessionStatus
                            if (retryStatus is SessionStatus.Retry) {
                                RetryBanner(retryStatus)
                            }

                            // Tool progress cards
                            activeTools.forEach { toolInfo ->
                                ToolProgressCard(toolInfo = toolInfo)
                            }

                            // Step progress indicator
                            if (currentStep != null) {
                                StepProgressIndicator(stepInfo = currentStep)
                            }

                            // Pending questions — show one at a time (oldest first)
                            interaction.pendingQuestions.firstOrNull()?.let { question ->
                                QuestionCard(
                                    question = question,
                                    positionLabel = if (interaction.pendingQuestions.size > 1) "1/${interaction.pendingQuestions.size}" else null,
                                    onSubmit = { answers ->
                                        viewModel.replyToQuestion(question.id, answers)
                                        onForceScrollToBottom()
                                    },
                                    onReject = {
                                        viewModel.rejectQuestion(question.id)
                                        onForceScrollToBottom()
                                    }
                                )
                            }

                            // Pending permissions — show one at a time (oldest first)
                            interaction.pendingPermissions.firstOrNull()?.let { permission ->
                                PermissionCard(
                                    permission = permission,
                                    positionLabel = if (interaction.pendingPermissions.size > 1) "1/${interaction.pendingPermissions.size}" else null,
                                    onOnce = {
                                        viewModel.replyToPermission(permission.id, "once")
                                        onForceScrollToBottom()
                                    },
                                    onAlways = { showAlwaysDialog = permission },
                                    onReject = {
                                        viewModel.replyToPermission(permission.id, "reject")
                                        onForceScrollToBottom()
                                    }
                                )
                            }
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
                        val itemModifier = if (msg.isAssistant) {
                            Modifier
                                .fillMaxWidth()
                                .layout { measurable, constraints ->
                                    val placeable = measurable.measure(
                                        constraints.copy(maxHeight = Constraints.Infinity)
                                    )
                                    val realHeight = placeable.height

                                    val msgId = msg.message.id
                                    val prevHeight = heightMap[msgId]
                                    val delta = if (prevHeight != null) realHeight - prevHeight else 0
                                    if (compensateState.shouldCompensate && delta > 0) {
                                        LazyListReflection.requestScrollToItemNoCancel(
                                            listState,
                                            listState.firstVisibleItemIndex,
                                            listState.firstVisibleItemScrollOffset + delta
                                        )
                                    }
                                    heightMap[msgId] = realHeight

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
                                    onOpenFile = onOpenFile,
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
                                            onForceScrollToBottom()
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

// --- Reflection: bypass requestScrollToItem's scroll{} mutex cancellation ---
// requestScrollToItem does two things:
//   ① if (isScrollInProgress) scroll {} ← grabs mutex, KILLS fling
//   ② scrollPosition.requestPosition + invalidateScope ← sets pending position
// We only want ② — set pending position without killing fling inertia.
// Reflection accesses private/internal fields directly.
private object LazyListReflection {
    private val scrollPositionField by lazy {
        Class.forName("androidx.compose.foundation.lazy.LazyListState")
            .getDeclaredField("scrollPosition")
            .apply { isAccessible = true }
    }

    private val requestPositionMethod by lazy {
        scrollPositionField.type
            .getDeclaredMethod("requestPositionAndForgetLastKnownKey",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
    }

    private val invalidatorField by lazy {
        Class.forName("androidx.compose.foundation.lazy.LazyListState")
            .getDeclaredField("measurementScopeInvalidator")
            .apply { isAccessible = true }
    }

    fun requestScrollToItemNoCancel(state: LazyListState, index: Int, scrollOffset: Int) {
        val scrollPosition = scrollPositionField.get(state)
        requestPositionMethod.invoke(scrollPosition, index, scrollOffset)
        // ObservableScopeInvalidator is a value class wrapping MutableState<Unit>.
        // Setting value = Unit triggers the invalidation.
        @Suppress("UNCHECKED_CAST")
        (invalidatorField.get(state) as androidx.compose.runtime.MutableState<Unit>).value = Unit
    }
}
