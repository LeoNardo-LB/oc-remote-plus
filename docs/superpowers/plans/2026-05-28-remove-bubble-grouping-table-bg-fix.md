# 取消气泡分组 + 推理展开/折叠 + 表格背景修复 + 分页简化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 取消 `groupMessages()` 气泡分组、每条 Message 独立渲染、推理内容可展开/折叠（默认折叠，可配置）、表格背景色填满列宽、分页简化为纯消息计数。

**Architecture:** 删除 `ChatItem` sealed class 和 `groupMessages()`。LazyColumn 从 `items(chatItems)` 改为 `items(uiState.messages)`。User 消息用 `ChatMessageBubble`，Assistant 消息用新 `AssistantMessageCard`。推理块新增展开/折叠交互，默认折叠，通过 Settings 可切换默认行为。表格 MeasurePolicy 加 Phase 2 re-measure。分页回归简单 `limit *= 2`。

**Tech Stack:** Jetpack Compose LazyColumn (reverseLayout=true), Kotlin StateFlow

---

## File Structure

| File | Action | Responsibility |
|------|--------|---------------|
| `ChatParts.kt` | **Delete** ChatItem + groupMessages | 不再需要气泡分组 |
| `ChatScreen.kt` | Modify | 替换 chatItems 为 raw messages；新增 AssistantMessageCard；ReasoningBlock 展开/折叠；表格 re-measure；修复 jsonPrimitive 闪退 |
| `ChatViewModel.kt` | Modify | 删除 groupMessages 引用；简化分页回 limit 翻倍；删除 estimateLimitForItems；新增 expandReasoning 状态 |
| `SettingsRepository.kt` | Modify | 新增 expandReasoning 设置；initialChatItemCount → initialMessageCount |
| `SettingsViewModel.kt` | Modify | 新增 expandReasoning；对应改名 |
| `SettingsScreen.kt` | Modify | 新增推理展开 toggle；选项值 + 变量名 |
| `app/build.gradle.kts` | Modify | 版本号 beta.42 → beta.43 |

---

## Task 1: ChatScreen — 替换 chatItems 为 raw messages，新增 AssistantMessageCard

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt`

这是最大的改动。需要：
1. 删除 `chatItems` 派生逻辑（含 `messageFingerprint`/`cachedGroups`）
2. LazyColumn 改为 `items(uiState.messages)`
3. 新建 `AssistantMessageCard` 函数（只渲染单条 assistant 消息）
4. 连续 Assistant 消息做视觉衔接

### Step 1: 删除 chatItems 派生逻辑

用 Read 读取 ChatScreen.kt 第 2335-2367 行确认内容，然后用 Edit 将以下代码：
```kotlin
                 else -> {
                      val messageSpacing = if (LocalCompactMessages.current) 4.dp else 12.dp
                       // Cache group structure keyed on message IDs only (not parts content).
                       // Parts deltas change every frame during streaming; the grouping structure
                       // only changes when messages are added/removed, so we key on IDs to avoid
                       // recomputing the grouping on every text delta.
                       val messageFingerprint = uiState.messages.map { it.message.id }
                       val cachedGroups = remember(messageFingerprint) { groupMessages(uiState.messages) }
                       // Refresh chatItems with the latest parts data on every recomposition.
                       // The grouping structure stays the same (same keys), but ChatMessage.parts
                       // are updated so bubbles render the latest streaming content.
                       val chatItems = if (cachedGroups.isEmpty()) {
                           cachedGroups
                       } else {
                           val msgById = uiState.messages.associateBy { it.message.id }
                           cachedGroups.map { item ->
                               when (item) {
                                   is ChatItem.UserMessage -> {
                                       val fresh = msgById[item.chatMessage.message.id]
                                       if (fresh != null && fresh != item.chatMessage) item.copy(chatMessage = fresh) else item
                                   }
                                   is ChatItem.AssistantTurn -> {
                                       val freshMsgs = item.messages.map { msg ->
                                           msgById[msg.message.id] ?: msg
                                       }
                                       // Only create a new instance if something actually changed
                                       // to avoid unnecessary LazyColumn item recompositions.
                                       if (freshMsgs == item.messages) item else item.copy(messages = freshMsgs)
                                   }
                               }
                           }
                       }
```
替换为：
```kotlin
                 else -> {
                      val messageSpacing = if (LocalCompactMessages.current) 4.dp else 12.dp

                       // Tag each assistant message with whether it follows another assistant
                       // in reverseLayout chronological order. Used for visual continuity:
                       // consecutive assistants share reduced spacing, no card separation.
                       val isAssistantContinuation = remember(uiState.messages.size) {
                           uiState.messages.mapIndexed { index, msg ->
                               val prevMsg = uiState.messages.getOrNull(index + 1)
                               msg.isAssistant && prevMsg?.isAssistant == true
                           }
                       }
                       // Use raw messages directly — each Message is one LazyColumn item.
                       // No groupMessages() grouping needed. Streaming text updates only
                       // recompose the single affected Message, not the entire "turn".
                       val rawMessages = uiState.messages
```

### Step 2: 重写 LazyColumn items 区域（主 session）

用 Read 读取第 2432-2567 行（从 `// Chat messages` 到 `// "Load earlier messages"` 之前），整体替换。

旧代码是 `items(chatItems, key = { it.key }, contentType = ...)` 分发 `UserMessage` / `AssistantTurn`。

替换为：
```kotlin
                           // Chat messages: newest-first (rawMessages is sorted newest-first).
                           // In reverseLayout=true, newest at index 0 = bottom. Correct.
                           itemsIndexed(
                               rawMessages,
                               key = { _, msg -> msg.message.id },
                               contentType = { _, msg -> if (msg.isUser) "user" else "assistant" }
                           ) { index, msg ->
                               when {
                                   msg.isAssistant -> {
                                       val isContinuation = isAssistantContinuation.getOrElse(index) { false }
                                       AssistantMessageCard(
                                           chatMessage = msg,
                                           isContinuation = isContinuation,
                                           onViewSubSession = onNavigateToChildSession,
                                           onCopyText = {
                                               val text = msg.parts.filterIsInstance<Part.Text>()
                                                   .joinToString("\n") { it.text }
                                               if (text.isNotBlank()) {
                                                   clipboardManager.setText(
                                                       androidx.compose.ui.text.AnnotatedString(text)
                                                   )
                                                   coroutineScope.launch {
                                                       snackbarHostState.showSnackbar(context.getString(R.string.chat_copied_clipboard))
                                                   }
                                               }
                                           }
                                       )
                                   }
                                   msg.isUser -> {
                                       // Keep existing user message rendering from ChatMessageBubble
                                       // (unchanged logic, just adapted to receive ChatMessage directly)
                                       val chatMessage = msg

                                       val isCompactionTrigger = chatMessage.parts.any { it is Part.Compaction }

                                       if (isCompactionTrigger) {
                                           var showRevertDialog by remember { mutableStateOf(false) }

                                           if (showRevertDialog) {
                                               AlertDialog(
                                                   onDismissRequest = { showRevertDialog = false },
                                                   title = { Text(stringResource(R.string.chat_revert_title)) },
                                                   text = { Text(stringResource(R.string.chat_revert_message)) },
                                                   confirmButton = {
                                                       TextButton(
                                                           onClick = {
                                                               showRevertDialog = false
                                                               viewModel.revertMessage(chatMessage.message.id) { ok ->
                                                                   coroutineScope.launch {
                                                                       snackbarHostState.showSnackbar(
                                                                           if (ok) context.getString(R.string.chat_messages_restored) else context.getString(R.string.chat_message_redo_failed)
                                                                       )
                                                                   }
                                                               }
                                                           }
                                                       ) {
                                                           Text(stringResource(R.string.chat_revert), color = MaterialTheme.colorScheme.error)
                                                       }
                                                   },
                                                   dismissButton = {
                                                       TextButton(onClick = { showRevertDialog = false }) {
                                                           Text(stringResource(R.string.cancel))
                                                       }
                                                   }
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
                                                   color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                               )
                                               Text(
                                                   text = stringResource(R.string.chat_summarized),
                                                   style = MaterialTheme.typography.labelSmall,
                                                   color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                                   modifier = Modifier.padding(horizontal = 12.dp)
                                               )
                                               HorizontalDivider(
                                                   modifier = Modifier.weight(1f),
                                                   color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                               )
                                           }
                                           return@itemsIndexed
                                       }

                                       ChatMessageBubble(
                                           chatMessage = chatMessage,
                                           isQueued = chatMessage.message.id in uiState.queuedMessageIds,
                                           onViewSubSession = onNavigateToChildSession,
                                           onRevert = if (uiState.sessionParentId == null) {{
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
                                           }} else null,
                                           onCopyText = {
                                               val text = chatMessage.parts
                                                   .filterIsInstance<Part.Text>()
                                                   .joinToString("\n") { it.text }
                                               if (text.isNotBlank()) {
                                                   clipboardManager.setText(
                                                       androidx.compose.ui.text.AnnotatedString(text)
                                                   )
                                                   coroutineScope.launch {
                                                       snackbarHostState.showSnackbar(context.getString(R.string.chat_copied_clipboard))
                                                   }
                                               }
                                           }
                                       )
                                   }
                               }
                           }
```

### Step 3: 子 session LazyColumn 同样替换

子 session 的 LazyColumn 在第 2691-2813 行也用了 `chatItems`，需要替换为 `itemsIndexed(rawMessages, ...)`。

用 Read 读取第 2691-2813 行，整体替换为与 Step 2 类似的逻辑，但注意子 session 的差异：
- `onRevert = null`（子 session 不支持 revert）
- 没有 `sessionParentId` 检查
- `onViewSubSession` 回调名可能不同，需确认

具体代码与 Step 2 主体相同，只需把 `onRevert` 回调设为 `null`，`onViewSubSession` 使用子 session 已有的回调参数名。

> **注意**：子 session 和主 session 共享同一个 `else ->` 分支的代码。Step 1 的 `isAssistantContinuation` 和 `rawMessages` 变量定义在 `else ->` 块中，两个 LazyColumn 都能访问。

### Step 4: 添加 AssistantMessageCard 函数

在 `ChatMessageBubble` 函数附近（约 3855 行之后），添加新函数：

```kotlin
/**
 * Renders a SINGLE assistant message with its parts interleaved.
 * Unlike the old AssistantTurnBubble, this handles exactly one ChatMessage,
 * so streaming text updates only recompose THIS card, not all sibling messages.
 *
 * @param isContinuation True when this message is part of a consecutive assistant
 *   sequence (Agent loop). When true, the "Response" header is omitted and
 *   top spacing is reduced for visual continuity.
 */
@Composable
private fun AssistantMessageCard(
    chatMessage: ChatMessage,
    isContinuation: Boolean,
    onViewSubSession: ((String) -> Unit)? = null,
    onCopyText: (() -> Unit)? = null,
) {
    val isAmoled = isAmoledTheme()
    val backgroundColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh
    val textColor = if (isAmoled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface
    val bubbleBorder = if (isAmoled) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f))
    } else null

    val assistantMsg = chatMessage.message as? Message.Assistant
    val errorText = formatAssistantErrorMessage(assistantMsg?.error)
    val renderableParts = filterRenderableParts(chatMessage.parts)

    if (renderableParts.isEmpty() && errorText == null) return

    val compact = LocalCompactMessages.current
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        // Visual bridge for consecutive assistants: reduced top spacing, no
        // full card separation. The first assistant in a sequence gets the
        // normal "Response" header; continuations skip it.
        Surface(
            shape = RoundedCornerShape(
                topStart = 4.dp,
                topEnd = 18.dp,
                bottomStart = 18.dp,
                bottomEnd = 18.dp
            ),
            color = backgroundColor,
            border = bubbleBorder,
            tonalElevation = if (isAmoled) 0.dp else 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(
                    horizontal = if (compact) 10.dp else 16.dp,
                    vertical = if (compact) 8.dp else 14.dp
                ),
                verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 10.dp)
            ) {
                // "Response" header — only on the first of a consecutive sequence
                if (!isContinuation) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            if (assistantMsg?.providerId != null) {
                                ProviderIcon(
                                    providerId = assistantMsg.providerId,
                                    size = 12.dp,
                                    tint = textColor.copy(alpha = 0.4f)
                                )
                            }
                            Text(
                                text = stringResource(R.string.chat_response),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    letterSpacing = 0.8.sp,
                                    fontWeight = FontWeight.Medium
                                ),
                                color = textColor.copy(alpha = 0.4f)
                            )
                        }
                        if (onCopyText != null) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = stringResource(R.string.chat_copy),
                                modifier = Modifier
                                    .size(15.dp)
                                    .clickable { performHaptic(hapticView, hapticOn); onCopyText() },
                                tint = textColor.copy(alpha = 0.3f)
                            )
                        }
                    }
                }

                // Render parts in original order
                for (part in renderableParts) {
                    key(part.id) {
                        PartContent(
                            part = part,
                            textColor = textColor,
                            isUser = false,
                            onViewSubSession = onViewSubSession,
                            turnAgentName = if (part is Part.Tool && part.tool == "task") {
                                val agentParts = chatMessage.parts.filterIsInstance<Part.Agent>()
                                agentParts.firstOrNull()?.name?.takeIf { it.isNotBlank() }
                            } else null
                        )
                    }
                }

                // Error display
                if (errorText != null) {
                    Surface(
                        color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = if (isAmoled) 0.75f else 0.35f)),
                        tonalElevation = 0.dp,
                    ) {
                        ErrorPayloadContent(
                            text = errorText,
                            textStyle = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                        )
                    }
                }

                // Token/cost footer — reduced visibility on continuations
                val footerAlpha = if (isContinuation) 0.2f else 0.4f
                if (chatMessage.isAssistant) {
                    val cost = assistantMsg?.cost
                    val tokens = assistantMsg?.tokens
                    if (cost != null || tokens != null) {
                        Text(
                            text = buildCostTokenFooter(cost, tokens),
                            style = MaterialTheme.typography.labelSmall,
                            color = textColor.copy(alpha = footerAlpha),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
```

**注意**：`buildCostTokenFooter` 和 `formatAssistantErrorMessage` 函数如果不存在需要添加。先 grep 确认是否有这些函数。如果没有，从旧 `AssistantTurnBubble` 代码中提取相关逻辑内联到 `AssistantMessageCard` 中。

实际上，`formatAssistantErrorMessage` 在 ChatScreen.kt 中已经存在（约 3757 行）。`buildCostTokenFooter` 需要检查。

用 grep 搜索 `buildCostTokenFooter`。如果不存在，在 `AssistantMessageCard` 中将该行替换为直接构造字符串：
```kotlin
    val cost = assistantMsg?.cost
    val tokens = assistantMsg?.tokens
    if (cost != null || tokens != null) {
        val parts = mutableListOf<String>()
        if (tokens != null) parts.add("↑${tokens.input} ↓${tokens.output}")
        if (cost != null) parts.add("$${"%.4f".format(cost)}")
        val footer = parts.joinToString(" · ")
        if (footer.isNotBlank()) {
            Text(text = footer, style = MaterialTheme.typography.labelSmall,
                color = textColor.copy(alpha = 0.4f), maxLines = 1)
        }
    }
```

### Step 5: 连续 Assistant 的顶部 spacing 收紧

`isContinuation = true` 的消息需要用更小的顶部 margin。在 `itemsIndexed` 渲染中，给 continuity 消息的 Column 加 `Modifier.padding(top = if (isContinuation) 2.dp else messageSpacing)`。

实际上，更好的做法是在 `AssistantMessageCard` 里面处理：continuation 的上一个消息底部用小间距，continuation 本身的 Surface 也缩进。

简化处理：在 `itemsIndexed` 外层的 verticalArrangement 保持不变，在 `AssistantMessageCard` 内部用 `Modifier.padding(top = if (isContinuation) 2.dp else 0.dp)`：

修改 `AssistantMessageCard` 函数的 Column wrapper：
```kotlin
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (isContinuation) 2.dp else 0.dp),
        horizontalAlignment = Alignment.Start
    ) {
```

### Step 6: 更新 chatItemsCount → messageCount 引用

在 ChatScreen.kt 中 grep 搜索 `chatItemsCount`（约第 1474-1478 行），替换为 `messageCount`：

```kotlin
// 旧：
val hasStats = uiState.chatItemsCount > 0 || totalTokens > 0 || uiState.totalCost > 0
if (hasStats) {
    val parts = mutableListOf<String>()
    if (uiState.chatItemsCount > 0) {
        parts.add(stringResource(R.string.chat_items_count, uiState.chatItemsCount))
    }

// 新：
val hasStats = uiState.messageCount > 0 || totalTokens > 0 || uiState.totalCost > 0
if (hasStats) {
    val parts = mutableListOf<String>()
    if (uiState.messageCount > 0) {
        parts.add(stringResource(R.string.chat_items_count, uiState.messageCount))
    }
```

> **注意**：字符串资源 key `chat_items_count` 暂时保留不改名（值为 `"%1$d messages"`，语义仍然正确），Task 7 会统一处理。

### Step 7: 编译验证

```bash
cd D:\Develop\code\app\oc-remote && .\gradlew.bat :app:compileDebugKotlin 2>&1 | Select-Object -Last 15
```

预期：BUILD SUCCESSFUL。如有未解析引用，检查：
- `Icons.Default.ContentCopy` 的 import
- `filterRenderableParts` 的 import（在 ChatParts.kt 中，同 package 无需 import）
- `performHaptic` 是否在作用域内

### Step 8: Commit

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt
git commit -m "refactor: replace ChatItem grouping with per-message LazyColumn items

- Each Message is now an independent LazyColumn item (no turn grouping)
- New AssistantMessageCard handles single assistant message rendering
- Consecutive assistants get visual continuity via isContinuation flag
- Streaming text updates only recompose the affected message card
- Fixes click target instability during streaming (no more remember(messages))"
```

---

## Task 2: ChatViewModel — 删除 groupMessages 引用，简化分页

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt`

### Step 1: 删除 estimateLimitForItems 方法

用 Read 读取 estimateLimitForItems 函数（约第 600-630 行），整体删除。

### Step 2: 删除 chatItemTargetCount，还原 currentMessageLimit

找到约第 213-218 行：
```kotlin
    /** Target number of chat items (bubbles) per page. */
    private var chatItemTargetCount = 10
    private var currentMessageLimit = 10
```
替换为：
```kotlin
    /** Number of messages to load per page. Doubles each "load older" click. */
    private var currentMessageLimit = 20
```

### Step 3: 修改 init 块中的初始化

> **注意**：此处仍使用 `initialChatItemCount`（当前名称），Task 7 会统一改名为 `initialMessageCount`。

找到约第 464-465 行：
```kotlin
            chatItemTargetCount = settingsRepository.initialChatItemCount.first()
            currentMessageLimit = chatItemTargetCount.coerceAtLeast(10)
```
替换为：
```kotlin
            currentMessageLimit = settingsRepository.initialChatItemCount.first()
```

### Step 4: 修改 ChatUiState 的 chatItemsCount

找到约第 44 行：
```kotlin
    val chatItemsCount: Int = 0,
```
替换为：
```kotlin
    val messageCount: Int = 0,
```

找到约第 390-396 行：
```kotlin
        val chatItemsCount = groupMessages(chatMessages).size
        ...
            chatItemsCount = chatItemsCount,
```
替换为：
```kotlin
        val messageCount = chatMessages.size
        ...
            messageCount = messageCount,
```

### Step 5: 还原 loadMessages() 为简单翻倍

读取 `loadMessages()` 函数（约第 496-545 行），整体替换为：
```kotlin
    fun loadMessages() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val messages = api.listMessages(conn, sessionId, limit = currentMessageLimit)
                eventReducer.setMessages(sessionId, messages)
                _hasOlderMessages.value = messages.size >= currentMessageLimit
                if (BuildConfig.DEBUG) Log.d(TAG, "Loaded ${messages.size} messages for session $sessionId (limit=$currentMessageLimit, hasOlder=${_hasOlderMessages.value})")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load messages", e)
                if (e is OutOfMemoryError || (e.cause is OutOfMemoryError)) {
                    Log.w(TAG, "OOM loading messages, retrying with smaller limit")
                    currentMessageLimit = (currentMessageLimit / 2).coerceAtLeast(10)
                    try {
                        val messages = api.listMessages(conn, sessionId, limit = currentMessageLimit)
                        eventReducer.mergeMessages(sessionId, messages)
                        _hasOlderMessages.value = messages.size >= currentMessageLimit
                        if (BuildConfig.DEBUG) Log.d(TAG, "Retry succeeded: loaded ${messages.size} messages (limit=$currentMessageLimit)")
                    } catch (retryEx: Exception) {
                        Log.e(TAG, "Retry also failed", retryEx)
                        _error.value = retryEx.message ?: "Failed to load messages"
                    }
                } else {
                    _error.value = e.message ?: "Failed to load messages"
                }
            } finally {
                _isLoading.value = false
            }
        }
    }
```

### Step 6: 还原 loadOlderMessages() 为简单翻倍

读取 `loadOlderMessages()`（约第 568-595 行），替换为：
```kotlin
    fun loadOlderMessages() {
        viewModelScope.launch {
            _isLoadingOlder.value = true
            currentMessageLimit *= 2
            try {
                val messages = api.listMessages(conn, sessionId, limit = currentMessageLimit)
                eventReducer.mergeMessages(sessionId, messages)
                _hasOlderMessages.value = messages.size >= currentMessageLimit
                if (BuildConfig.DEBUG) Log.d(TAG, "Loaded older: ${messages.size} messages (limit=$currentMessageLimit, hasOlder=${_hasOlderMessages.value})")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load older messages", e)
                currentMessageLimit /= 2
            } finally {
                _isLoadingOlder.value = false
            }
        }
    }
```

### Step 7: ChatScreen 中 chatItemsCount 已在 Task 1 Step 6 处理

Task 1 Step 6 已将 ChatScreen.kt 中的 `chatItemsCount` 替换为 `messageCount`，此处无需额外操作。

### Step 8: 编译验证

```bash
cd D:\Develop\code\app\oc-remote && .\gradlew.bat :app:compileDebugKotlin 2>&1 | Select-Object -Last 15
```

### Step 9: Commit

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt
git commit -m "refactor: simplify pagination to message count, remove bubble grouping from ViewModel

- Remove estimateLimitForItems and chatItemTargetCount
- Restore simple limit-doubling pagination (20 → 40 → 80 ...)
- Rename chatItemsCount to messageCount in ChatUiState
- Each message is now its own LazyColumn item"
```

---

## Task 3: ChatParts.kt — 删除 ChatItem 和 groupMessages

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatParts.kt`

### Step 1: 删除 ChatItem sealed class（第 1-21 行）

用 Edit 删除整个 `ChatItem` sealed class 定义。

### Step 2: 删除 groupMessages 函数（第 23-59 行）

用 Edit 删除整个 `groupMessages` 函数。

### Step 3: 更新文件顶部的文档注释

将：
```kotlin
/**
 * Represents a renderable item in the chat list.
 * Consecutive assistant messages are grouped into a single AssistantTurn.
 */
internal sealed class ChatItem {
```

替换为（删除 sealed class 后的新第一行）：
```kotlin
/**
 * Utility functions for filtering and categorizing chat parts.
 */
```

### Step 4: 编译验证

确认所有对 `ChatItem`、`groupMessages` 的引用都已清除。如有编译错误（某个文件仍 import 了 `ChatItem`），逐一修复。

### Step 5: Commit

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatParts.kt
git commit -m "refactor: delete ChatItem sealed class and groupMessages function

Each Message is now rendered independently in LazyColumn. No more
grouping of consecutive assistant messages into turns."
```

---

## Task 4: ReasoningBlock 展开/折叠 + 设置项

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/data/repository/SettingsRepository.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/settings/SettingsViewModel.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/settings/SettingsScreen.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt`

### Step 1: SettingsRepository 加 expandReasoning

用 Read 读取 SettingsRepository.kt 第 216-224 行 (`collapseTools` 属性)，在其后添加：

```kotlin
    /**
     * Whether reasoning blocks are expanded by default. Default: false (collapsed).
     */
    val expandReasoning: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[EXPAND_REASONING_KEY] ?: false
    }

    suspend fun setExpandReasoning(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[EXPAND_REASONING_KEY] = enabled
        }
    }
```

用 grep 搜索 `COLLAPSE_TOOLS_KEY` 找到 Preference Key 定义区域，在其附近添加：

```kotlin
    private val EXPAND_REASONING_KEY = booleanPreferencesKey("expand_reasoning")
```

### Step 2: SettingsViewModel 加 expandReasoning

用 Read 读取 SettingsViewModel.kt 约第 78-80 行（`collapseTools`），在其后添加：

```kotlin
    val expandReasoning = settingsRepository.expandReasoning.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), false
    )
```

用 grep 搜索 `setCollapseTools`，在其后添加：

```kotlin
    fun setExpandReasoning(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setExpandReasoning(enabled)
        }
    }
```

### Step 3: SettingsScreen 加 toggle

用 grep 搜索 `settings_auto_expand_tools_desc`（约第 265 行），在该 `ListItem` 块之后、`HorizontalDivider` 之前添加：

```kotlin
            // Expand reasoning by default
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_expand_reasoning)) },
                supportingContent = { Text(stringResource(R.string.settings_expand_reasoning_desc)) },
                leadingContent = {
                    Icon(Icons.Default.Psychology, contentDescription = null)  // material-icons-extended 已依赖
                },
                trailingContent = {
                    Switch(
                        checked = expandReasoning,
                        onCheckedChange = { viewModel.setExpandReasoning(it) },
                        colors = switchColors
                    )
                },
                modifier = Modifier.clickable { viewModel.setExpandReasoning(!expandReasoning) }
            )
```

同时在 SettingsScreen.kt 顶部（约第 74 行，`collapseTools` 之后）添加：

```kotlin
    val expandReasoning by viewModel.expandReasoning.collectAsState()
```

### Step 4: ChatViewModel 加 expandReasoning state

用 Read 读取 ChatViewModel.kt 约第 192 行（`collapseTools` 定义附近），添加新字段：

```kotlin
    val expandReasoning = settingsRepository.expandReasoning.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), false
    )
```

### Step 5: ChatScreen — 替换 ReasoningBlock 调用 + 注入 CompositionLocal

用 Read 读取 ChatScreen.kt 第 203 行 (`LocalCollapseTools`)，在其下方添加：

```kotlin
val LocalExpandReasoning = compositionLocalOf { false }
```

找到 ChatScreen.kt 约第 4402-4405 行（`Part.Reasoning` 渲染）：
```kotlin
        is Part.Reasoning -> {
            if (part.text.isNotBlank()) {
                ReasoningBlock(text = part.text)
            }
        }
```
替换为：
```kotlin
        is Part.Reasoning -> {
            if (part.text.isNotBlank()) {
                ReasoningBlock(text = part.text, defaultExpanded = LocalExpandReasoning.current)
            }
        }
```

找到约第 1455 行（`LocalCollapseTools provides collapseTools`），在其下方添加：
```kotlin
        LocalExpandReasoning provides expandReasoning,
```

### Step 6: 重写 ReasoningBlock 为可折叠

用 Read 读取 ChatScreen.kt 第 4966-5004 行（原 `ReasoningBlock` 函数），整体替换为：

```kotlin
/**
 * Reasoning block with expand/collapse. Collapsed by default, showing only
 * a "[展开思考]" header line. Clicking the header toggles visibility of
 * the reasoning text. The default state is controlled by [defaultExpanded].
 */
@Composable
private fun ReasoningBlock(text: String, defaultExpanded: Boolean = false) {
    val isAmoled = isAmoledTheme()
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current
    var expanded by remember { mutableStateOf(defaultExpanded) }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceContainer,
        border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            // Left accent border
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )

            Column(
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .clickable { performHaptic(hapticView, hapticOn); expanded = !expanded }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.chat_status_thinking),
                        style = MaterialTheme.typography.labelSmall.copy(
                            letterSpacing = 0.6.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded)
                            stringResource(R.string.chat_collapse)
                        else
                            stringResource(R.string.chat_expand),
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                    )
                }

                // Reasoning text — hidden when collapsed
                AnimatedVisibility(visible = expanded) {
                    Column {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                lineHeight = 20.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}
```

### Step 7: 字符串资源

在 `values/strings.xml` 中添加：
```xml
<string name="settings_expand_reasoning">Auto-expand reasoning</string>
<string name="settings_expand_reasoning_desc">Show thinking process by default; tap header to toggle</string>
```

在 `values-zh-rCN/strings.xml` 中添加：
```xml
<string name="settings_expand_reasoning">自动展开推理内容</string>
<string name="settings_expand_reasoning_desc">默认展开思考过程；关闭后点击标题手动展开</string>
```

> **注意**：其他 locale 文件（de, es, fr, it, ja, ko, pl, pt-rBR, ru, tr, uk, ar, id）暂不添加翻译，
> Android 会 fallback 到 `values/strings.xml` 的英文默认值。后续可逐步补充。

### Step 8: 编译验证

```bash
cd D:\Develop\code\app\oc-remote && .\gradlew.bat :app:compileDebugKotlin 2>&1 | Select-Object -Last 15
```

### Step 9: Commit

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt app/src/main/kotlin/dev/minios/ocremote/ui/screens/settings/SettingsScreen.kt app/src/main/kotlin/dev/minios/ocremote/ui/screens/settings/SettingsViewModel.kt app/src/main/kotlin/dev/minios/ocremote/data/repository/SettingsRepository.kt app/src/main/res/values/strings.xml
git commit -m "feat: collapsible reasoning blocks with expand/collapse toggle

- ReasoningBlock now shows header only when collapsed, full text when expanded
- Default collapsed; configurable via Settings > expandReasoning toggle
- New LocalExpandReasoning CompositionLocal for per-message override"
```

---

## Task 5: 修复 CTX 工具点击闪退（jsonPrimitive 不安全转型）

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt`

### 根因分析

`ToolCallCard`（第 5112 行）遍历 `input.entries` 显示参数时：
```kotlin
val value = v.jsonPrimitive?.contentOrNull ?: v.toString().take(200)
```

`v.jsonPrimitive` 是 kotlinx.serialization 的扩展属性，本质是 **强制转型**（`this as JsonPrimitive`）。
当 `v` 是 `JsonArray`（如 `ctx_batch_execute` 的 `commands`/`queries` 参数）或 `JsonObject` 时，
转型失败抛出 `IllegalArgumentException`，导致应用闪退。

`?.` 安全调用操作符**无法保护**此场景——异常在 `jsonPrimitive` 属性求值时就已经抛出，
`?.` 只作用于返回值，而转型在返回值之前就失败了。

CTX 工具参数结构示例：
- `ctx_batch_execute`: `commands` = JsonArray, `queries` = JsonArray
- `ctx_fetch_and_index`: `requests` = JsonArray
- `ctx_search`: `queries` = JsonArray

### Step 1: 修复 ToolCallCard 中的不安全 jsonPrimitive 调用

用 Read 读取 ChatScreen.kt 第 5109-5114 行，将：
```kotlin
                        val inputText = input.entries
                            .filter { (_, v) -> v.toString().length <= 500 }
                            .joinToString("\n") { (k, v) ->
                                val value = v.jsonPrimitive?.contentOrNull ?: v.toString().take(200)
                                "$k: $value"
                            }
```
替换为：
```kotlin
                        val inputText = input.entries
                            .filter { (_, v) -> v.toString().length <= 500 }
                            .joinToString("\n") { (k, v) ->
                                val value = (v as? JsonPrimitive)?.contentOrNull ?: v.toString().take(200)
                                "$k: $value"
                            }
```

**关键变化**：`v.jsonPrimitive?.contentOrNull` → `(v as? JsonPrimitive)?.contentOrNull`
- `as? JsonPrimitive` 是安全转型，当 `v` 是 `JsonArray`/`JsonObject` 时返回 `null`
- `?.contentOrNull` 然后对 `null` 安全短路
- fallback `v.toString().take(200)` 将 JsonArray/JsonObject 转为可读字符串

### Step 2: 检查其他潜在的不安全 jsonPrimitive 调用

用 grep 搜索 ChatScreen.kt 中所有 `.jsonPrimitive` 调用。确认以下模式是安全的：

- `input["key"]?.jsonPrimitive?.contentOrNull` — 安全，因为 `input["key"]` 返回 `JsonElement?`，
  `?.` 在 null 时短路，且已知工具的这些 key 对应的值都是 `JsonPrimitive`
- `metadata?.get("key")?.jsonPrimitive?.contentOrNull` — 同理安全

**唯一需要修复的就是 Step 1 中的那一行**。其他所有调用都是对特定 key 的访问，
而那些 key 的值在已知工具中都是 `JsonPrimitive`。

### Step 3: 编译验证

```bash
cd D:\Develop\code\app\oc-remote && .\gradlew.bat :app:compileDebugKotlin 2>&1 | Select-Object -Last 10
```

### Step 4: Commit

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt
git commit -m "fix: crash when clicking CTX tool cards (jsonPrimitive cast on JsonArray)

v.jsonPrimitive is a forced cast (this as JsonPrimitive) that throws
IllegalArgumentException when the value is a JsonArray or JsonObject.
CTX tools like ctx_batch_execute have array parameters (commands, queries)
that trigger this crash.

Fix: use safe cast (v as? JsonPrimitive)?.contentOrNull instead of
v.jsonPrimitive?.contentOrNull, falling back to v.toString() for
non-primitive values."
```

---

## Task 6: 表格背景色填满列宽（UniformColumnMeasurePolicy re-measure）

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt`

### Step 1: 修改 UniformColumnMeasurePolicy.measure()

用 Read 读取 UniformColumnMeasurePolicy 类的 `measure` 方法（约第 4819-4870 行）。

将当前的实现（只测量一次然后 placement）替换为两遍测量：

```kotlin
    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        if (measurables.isEmpty()) return layout(0, 0) {}

        // Phase 1: Measure all cells with loose width to discover natural sizes
        val looseConstraints = Constraints(
            minWidth = 0,
            maxWidth = constraints.maxWidth,
            minHeight = 0,
            maxHeight = constraints.maxHeight,
        )
        val naturalSizes = measurables.map { it.measure(looseConstraints) }

        // Compute per-column max width from natural sizes
        val colWidths = IntArray(columnCount) { 0 }
        naturalSizes.forEachIndexed { index, placeable ->
            val col = index % columnCount
            colWidths[col] = maxOf(colWidths[col], placeable.width)
        }

        // Phase 2: Re-measure every cell with FIXED column width.
        // This makes the cell composable's background fill the full column width
        // instead of being constrained to the cell's natural text width.
        val placeables = measurables.mapIndexed { index, measurable ->
            val col = index % columnCount
            val colConstraint = constraints.copy(
                minWidth = colWidths[col],
                maxWidth = colWidths[col],
            )
            measurable.measure(colConstraint)
        }

        // Compute per-row max height from the final placeables
        val actualRowCount = (placeables.size + columnCount - 1) / columnCount
        val rowHeights = IntArray(actualRowCount) { 0 }
        placeables.forEachIndexed { index, placeable ->
            val row = index / columnCount
            rowHeights[row] = maxOf(rowHeights[row], placeable.height)
        }

        // Total dimensions
        val totalWidth = colWidths.sum()
        val totalHeight = rowHeights.sum()

        // Place in grid
        layout(totalWidth, totalHeight) {
            var y = 0
            for (row in 0 until actualRowCount) {
                var x = 0
                for (col in 0 until columnCount) {
                    val idx = row * columnCount + col
                    if (idx < placeables.size) {
                        val p = placeables[idx]
                        val dy = (rowHeights[row] - p.height) / 2
                        p.placeRelative(x, y + dy)
                    }
                    x += colWidths[col]
                }
                y += rowHeights[row]
            }
        }
    }
```

**关键变化**：Phase 1 用自然宽度测出每列最大值 → Phase 2 用固定 `minWidth = maxWidth = colWidths[col]` 重新测量每个 cell → Box 的 `background` 会填满整列宽度。

### Step 2: 编译验证

```bash
cd D:\Develop\code\app\oc-remote && .\gradlew.bat :app:compileDebugKotlin 2>&1 | Select-Object -Last 10
```

### Step 3: Commit

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt
git commit -m "fix: table cell background fills full column width

Two-pass measurement in UniformColumnMeasurePolicy:
Phase 1 measures natural sizes to compute col widths.
Phase 2 re-measures with fixed column width so cell background
fills the entire column, not just the natural text width."
```

---

## Task 7: 设置项回滚

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/data/repository/SettingsRepository.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/settings/SettingsViewModel.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/settings/SettingsScreen.kt`

### Step 1: SettingsRepository 回滚

将 `initialChatItemCount` 改为 `initialMessageCount`，默认值改回 20：

```kotlin
    val initialMessageCount: Flow<Int> = dataStore.data.map { preferences ->
        preferences[INITIAL_MESSAGE_COUNT_KEY] ?: 20
    }

    suspend fun setInitialMessageCount(count: Int) {
        dataStore.edit { preferences ->
            preferences[INITIAL_MESSAGE_COUNT_KEY] = count
```

### Step 2: SettingsViewModel 回滚

grep `initialChatItemCount`，全部替换为 `initialMessageCount`。

### Step 3: SettingsScreen 回滚

grep `initialChatItemCount` → `initialMessageCount`。

将 `MessageCountPickerDialog` 的 options 改为基于消息条数的选项：
```kotlin
options = listOf(20, 50, 100, 200).map { it to "$it" },
```

### Step 4: 字符串资源 key 改名

在 `values/strings.xml` 和所有 locale 文件中：
- `chat_items_count` → `chat_message_count`（值不变，仍为 `"%1$d messages"`）
- `settings_initial_chat_item_count` → `settings_initial_message_count`（标题和描述）

在 ChatScreen.kt 中 grep `R.string.chat_items_count`，替换为 `R.string.chat_message_count`。

### Step 5: 编译验证

```bash
cd D:\Develop\code\app\oc-remote && .\gradlew.bat :app:compileDebugKotlin 2>&1 | Select-Object -Last 10
```

### Step 6: Commit

```bash
git add app/src/main/kotlin/dev/minios/ocremote/data/repository/SettingsRepository.kt app/src/main/kotlin/dev/minios/ocremote/ui/screens/settings/SettingsViewModel.kt app/src/main/kotlin/dev/minios/ocremote/ui/screens/settings/SettingsScreen.kt
git commit -m "revert: rename initialChatItemCount back to initialMessageCount

Settings control message count (20/50/100/200) instead of bubble count.
Consistent with removal of ChatItem grouping — each message is one item."
```

---

## Task 8: 版本升级 + 构建 + 发布

**Files:**
- Modify: `app/build.gradle.kts`

### Step 1: 更新版本号

```kotlin
versionCode = 243
versionName = "2.0.0-beta.43"
```

### Step 2: Commit + Build + Push + Release

```bash
git add app/build.gradle.kts
git commit -m "chore: bump version to 2.0.0-beta.43"
.\gradlew.bat assembleRelease
git push fork master
gh release create v2.0.0-beta.43 --repo LeoNardo-LB/oc-remote-v2 --title "v2.0.0-beta.43" --notes "## Changes

### Refactor
- **取消气泡分组**: 每条 Message 独立渲染为 LazyColumn item，不再通过 groupMessages() 合并连续 Assistant 消息
- **分页简化**: 恢复简单 limit 翻倍机制（每页 20 条消息），删除自适应估算逻辑
- **单消息卡片**: 新增 AssistantMessageCard 渲染单条 assistant 消息，流式更新只重组所在消息

### New Features
- **推理展开/折叠**: ReasoningBlock 默认折叠，点击标题展开，Settings 可配置默认行为

### Bug Fixes
- **CTX 工具闪退**: 修复点击 ctx_batch_execute 等 CTX 工具卡片时崩溃的问题（jsonPrimitive 对 JsonArray 的不安全转型）
- **表格背景色填满列宽**: UniformColumnMeasurePolicy 增加 Phase 2 re-measure，cell 背景填满整列宽度
- **流式点击修复**: 取消 turn 级别 remember(messages)，流式文本更新不再波及同 turn 的其他工具卡/表格

### Full Changelog
https://github.com/LeoNardo-LB/oc-remote-v2/compare/v2.0.0-beta.42...v2.0.0-beta.43" "app/build/outputs/apk/release/app-release.apk"
```

---

## Self-Review

### Spec Coverage
- ✅ 取消气泡分组：Task 3（删除 ChatItem）+ Task 1（ChatScreen 换 raw messages）
- ✅ 每条 Message 独立 LazyColumn item：Task 1
- ✅ 分页回归简单翻倍：Task 2
- ✅ 推理展开/折叠：Task 4（ReasoningBlock + 设置项）
- ✅ CTX 工具闪退修复：Task 5（jsonPrimitive 安全转型）
- ✅ 表格背景色填满列宽：Task 6
- ✅ 设置项回滚：Task 7
- ✅ 版本升级：Task 8

### Placeholder Scan
- ✅ 所有步骤包含完整代码
- ✅ 无 TBD/TODO/placeholder
- ✅ 无"add appropriate error handling"类模糊描述
- ✅ jsonPrimitive 安全转型已明确为 `(v as? JsonPrimitive)?.contentOrNull`
- ✅ Task 2 使用 `initialChatItemCount`（当前名称），Task 7 统一改名
- ✅ 子 session LazyColumn 替换有明确说明

### Type Consistency
- ✅ `AssistantMessageCard` 参数 `(chatMessage: ChatMessage, isContinuation: Boolean, ...)` 与调用处一致
- ✅ `isAssistantContinuation` 计算 `remember(uiState.messages.size)` → 当消息数量变化时重建
- ✅ `rawMessages` = `uiState.messages` 类型 `List<ChatMessage>`，与 `itemsIndexed` 参数匹配
- ✅ `currentMessageLimit` 初始值 20，与设置项默认值一致
- ✅ `ChatMessage.isAssistant` / `ChatMessage.isUser` 属性已确认存在（ChatViewModel.kt 第 96-97 行）
- ✅ `Icons.Default.Psychology` 在 material-icons-extended 中可用（项目已依赖）
- ✅ `Icons.Default.ExpandLess/ExpandMore` 项目已大量使用
- ✅ `R.string.chat_collapse`/`chat_expand`/`chat_status_thinking` 已存在于所有 locale
- ✅ `(v as? JsonPrimitive)?.contentOrNull` 类型安全，JsonArray/JsonObject 安全降级为 toString
