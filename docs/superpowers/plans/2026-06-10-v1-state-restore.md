# V1 状态管理恢复 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 恢复 V1 (beta.168) 的核心状态管理逻辑（isSending、isLoading、error、pendingMessageIds、分页、冷却机制），同时保留当前版本的架构改进（sseJob、防闪烁、滚动恢复、data 层修复）。

**Architecture:** 在现有 ChatViewModel 中恢复 V1 的 StateFlow 定义和 combine 逻辑，将 sendParts 改回完整流程（乐观发送 + 草稿恢复 + 错误处理），ChatInputBar 恢复 isSending 参数和 BreathingCircleIndicator。所有改动在 master 分支上进行，每个 Task 后编译验证。

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, StateFlow, combine

**基准版本:** `f6724c4` (v2.0.0-beta.168) — V2 改造前的最后一个稳定版本

**对比范围:** 11 个文件有变更，本计划涉及 4 个核心文件：
- `ChatViewModel.kt` — 主要改动
- `ChatInputBar.kt` — 参数和 UI 恢复
- `ChatScreen.kt` — 适配 isSending 传递
- `MessageCard.kt` — 清理调试日志

---

## 文件变更地图

| 文件 | 操作 | 职责 |
|------|------|------|
| `ChatViewModel.kt` | Modify | 恢复 V1 StateFlow + data class + combine + sendParts + 分页 + 冷却 |
| `ChatInputBar.kt` | Modify | 恢复 isSending 参数 + BreathingCircleIndicator，保留文本框高度改进 |
| `ChatScreen.kt` | Modify | 适配 isSending/isBusy 双参数传递 |
| `MessageCard.kt` | Modify | 清理残留 Log.d 调试日志 |

**不修改的文件（确认无差异或已改进）:**
- `EventDispatcher.kt` — V1 与 HEAD 完全一致
- `SessionEventHandler.kt` — V1 与 HEAD 完全一致
- `ChatMessageList.kt` — HEAD 改进（remember key + displayItems），保留
- `ReasoningBlock.kt` — HEAD 改进（clamp + 200ms），保留
- Data 层 5 个文件 — 全部是改进，保留

---

## Task 1: 恢复 ChatViewModel data class 和 StateFlow 声明

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt`

**说明:** 恢复 V1 的 `InteractionState`（加回 `isSending`）、`MessageListState`（加回 `pendingMessageIds`）、以及所有丢失的 StateFlow 声明。

- [ ] **Step 1: 恢复 InteractionState data class**

将当前的：
```kotlin
data class InteractionState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val pendingPermissions: List<SseEvent.PermissionAsked> = emptyList(),
    val pendingQuestions: List<SseEvent.QuestionAsked> = emptyList(),
)
```

改为（恢复 `isSending` 字段）：
```kotlin
data class InteractionState(
    val isLoading: Boolean = true,
    val isSending: Boolean = false,
    val error: String? = null,
    val pendingPermissions: List<SseEvent.PermissionAsked> = emptyList(),
    val pendingQuestions: List<SseEvent.QuestionAsked> = emptyList(),
)
```

- [ ] **Step 2: 恢复 MessageListState data class**

将当前的：
```kotlin
data class MessageListState(
    val messages: List<ChatMessage> = emptyList(),
    val messageCount: Int = 0,
    val hasOlderMessages: Boolean = false,
    val isLoadingOlder: Boolean = false,
    val toolExpandedStates: Map<String, Boolean> = emptyMap(),
    val queuedMessageIds: Set<String> = emptySet(),
)
```

改为（恢复 `pendingMessageIds` 字段）：
```kotlin
data class MessageListState(
    val messages: List<ChatMessage> = emptyList(),
    val messageCount: Int = 0,
    val hasOlderMessages: Boolean = false,
    val isLoadingOlder: Boolean = false,
    val toolExpandedStates: Map<String, Boolean> = emptyMap(),
    val queuedMessageIds: Set<String> = emptySet(),
    val pendingMessageIds: Set<String> = emptySet(),
)
```

- [ ] **Step 3: 恢复 StateFlow 声明**

在 ChatViewModel class body 中，找到当前 `_messagesList` / `_partsList` 声明区域附近，恢复以下 StateFlow：

```kotlin
// ============ Loading State ============
private val _isLoading = MutableStateFlow(true)
private val _isRefreshing = MutableStateFlow(false)  // Background refresh — no UI wipe
/** Timestamp of last successful refresh. Used to skip unnecessary ON_RESUME refreshes. */
private var lastRefreshTimeMs: Long = 0L
private val _error = MutableStateFlow<String?>(null)
private val _isSending = MutableStateFlow(false)
```

在 `// ============ Optimistic Send ============` 注释处（或 `_toolExpandedStates` 附近）恢复：

```kotlin
// ============ Optimistic Send ============
/** Locally-generated IDs for optimistic messages. Used to distinguish from server-confirmed. */
private val _pendingMessageIds = MutableStateFlow<Set<String>>(emptySet())
```

在 `// ============ Pagination ============` 注释处恢复：

```kotlin
// ============ Pagination ============
/** Number of messages to load per page. Doubles each "load older" click. */
private var currentMessageLimit = 20
/** Whether there are more messages on the server beyond the current limit. */
private val _hasOlderMessages = MutableStateFlow(false)
/** Whether a "load older" request is in flight. */
private val _isLoadingOlder = MutableStateFlow(false)
```

在 companion object 中恢复：

```kotlin
const val REFRESH_COOLDOWN_MS = 5_000L  // Skip refresh if last one was < 5s ago
```

- [ ] **Step 4: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL（可能有 unused warnings，正常）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt
git commit -m "refactor: restore V1 StateFlow declarations (isSending, isLoading, error, pendingMessageIds, pagination)"
```

---

## Task 2: 恢复 interactionState 和 messageListState combine

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt`

**说明:** 将两个核心 combine 恢复到 V1 逻辑。`interactionState` 使用 `_isLoading`、`_error`、`_isSending`。`messageListState` 恢复 `flatMapLatest(_sessionId)` 模式和 8 源 combine。

**⚠️ 关键设计决策:** 当前版本的 `messageListState` 使用本地 `_messagesList` + `_partsList`（SSE 驱动），V1 使用 `messagePaging.observeMessages(sid)` + `chatRepository.getAllPartsMap()`。两者路径不同但 V1 更成熟。恢复 V1 路径。

- [ ] **Step 1: 恢复 interactionState combine**

将当前的 3 源 combine（`_messagesList`, `statusesFlow`, `sessionsFlow`）替换为 V1 的 5 源 combine：

```kotlin
val interactionState: StateFlow<InteractionState> = combine(
    _sessionId,
    _isLoading,
    _error,
    _isSending,
    sessionRepository.getSessionsFlow(serverId),
) { args ->
    val sid = args[0] as String
    val loading = args[1] as Boolean
    val error = args[2] as String?
    val sending = args[3] as Boolean
    @Suppress("UNCHECKED_CAST")
    val allSessions = args[4] as List<Session>

    InteractionState(
        isLoading = loading,
        isSending = sending,
        error = error,
        pendingPermissions = chatRepository.getPermissionsWithChildren(sid, allSessions),
        pendingQuestions = chatRepository.getQuestionsWithChildren(sid, allSessions),
    )
}.stateIn(
    viewModelScope,
    SharingStarted.WhileSubscribed(5000),
    InteractionState()
)
```

- [ ] **Step 2: 恢复 messageListState combine**

将当前的 3 源 combine（`_messagesList`, `_partsList`, `_toolExpandedStates`）替换为 V1 的 `flatMapLatest(_sessionId)` + 8 源 combine：

```kotlin
val messageListState: StateFlow<MessageListState> = _sessionId.flatMapLatest { sid ->
    combine(
        sessionRepository.getSessionsFlow(serverId),
        messagePaging.observeMessages(sid),
        chatRepository.getAllPartsMap(),
        _isLoading,
        _hasOlderMessages,
        _isLoadingOlder,
        _toolExpandedStates,
        _pendingMessageIds,
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val allSessions = args[0] as List<Session>
        val sessionMessages = args[1] as List<Message>
        val allParts = args[2] as Map<String, List<Part>>
        val loading = args[3] as Boolean
        val hasOlderMessages = args[4] as Boolean
        val isLoadingOlder = args[5] as Boolean
        @Suppress("UNCHECKED_CAST")
        val toolExpandedStates = args[6] as Map<String, Boolean>
        @Suppress("UNCHECKED_CAST")
        val pendingMessageIds = args[7] as Set<String>

        val session = allSessions.find { it.id == sid }
        val revertState = session?.revert

        val chatMessages = if (loading && sessionMessages.isEmpty()) {
            emptyList()
        } else {
            val sorted = sessionMessages.sortedBy { it.time.created }
            val visible = if (revertState != null) {
                sorted.filter { it.id < revertState.messageId }
            } else {
                sorted
            }
            visible.map { msg ->
                ChatMessage(
                    message = msg,
                    parts = allParts[msg.id] ?: emptyList()
                )
            }
        }

        val pendingAssistantIndex = chatMessages.indexOfLast {
            it.message is Message.Assistant && it.message.time.completed == null
        }
        val queuedMessageIds = if (pendingAssistantIndex >= 0) {
            chatMessages.drop(pendingAssistantIndex + 1)
                .filter { it.isUser }
                .map { it.message.id }
                .toSet()
        } else {
            emptySet<String>()
        }

        MessageListState(
            messages = chatMessages,
            messageCount = chatMessages.size,
            hasOlderMessages = hasOlderMessages,
            isLoadingOlder = isLoadingOlder,
            toolExpandedStates = toolExpandedStates,
            queuedMessageIds = queuedMessageIds,
            pendingMessageIds = pendingMessageIds,
        )
    }
}.stateIn(
    viewModelScope,
    SharingStarted.WhileSubscribed(5000),
    MessageListState()
)
```

**⚠️ 注意:** 恢复 V1 的 `messageListState` 后，`_messagesList` 和 `_partsList` 这两个 StateFlow 以及 `sseJob` 可能不再需要。但 `sseJob` 在 `abortSession()` 和 `onCleared()` 中使用，且 `startObservingMessages()` 是 SSE 生命周期的管理入口。**保留 `sseJob` 和 `startObservingMessages()`**，但确认它们不会与 V1 的 `messagePaging.observeMessages()` 冲突。如果冲突，需要移除 `sseJob` 相关代码，因为 V1 的 `messagePaging.observeMessages(sid)` 已经通过 `flatMapLatest` 自动响应 SSE 数据变化。

- [ ] **Step 3: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt
git commit -m "refactor: restore V1 interactionState and messageListState combine logic"
```

---

## Task 3: 恢复 sendParts 完整流程

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt`

**说明:** 恢复 V1 的 sendParts 流程：乐观发送标记 → isSending 状态 → 发送 → 失败恢复草稿 + error 设置 → 清除 isSending。

- [ ] **Step 1: 恢复 sendParts**

将当前的 `sendParts` 方法替换为 V1 版本（使用 `sendMessageUseCase.sendPrompt`，保留当前的 model 参数构建方式）：

```kotlin
private fun sendParts(parts: List<PromptPart>) {
    val pendingId = "pending-${java.util.UUID.randomUUID()}"
    _pendingMessageIds.update { it + pendingId }
    viewModelScope.launch {
        _isSending.value = true
        try {
            val currentSessionId = ensureSession()
            val model = if (_selectedProviderId.value != null && _selectedModelId.value != null) {
                ModelSelection(
                    providerId = _selectedProviderId.value!!,
                    modelId = _selectedModelId.value!!
                )
            } else null

            sendMessageUseCase.sendPrompt(
                serverId = serverId,
                sessionId = currentSessionId,
                parts = parts,
                model = model,
                agent = modelConfigState.value.selectedAgent,
                variant = _selectedVariant.value,
                directory = sessionDirectory
            )
            if (BuildConfig.DEBUG) Log.d(TAG, "Sent prompt to session $currentSessionId (${parts.size} parts)")
            refreshSessionTitleDelayed(currentSessionId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message", e)
            _pendingMessageIds.update { it - pendingId }
            // Restore draft from the failed send
            val failedText = parts.filter { it.type == "text" }.mapNotNull { it.text }.joinToString("\n")
            if (failedText.isNotBlank()) {
                _restoredDraft.value = RevertedDraftPayload(text = failedText)
            }
            _error.value = e.message ?: "Failed to send message"
        } finally {
            _isSending.value = false
        }
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt
git commit -m "fix: restore V1 sendParts with optimistic send, draft recovery, and error handling"
```

---

## Task 4: 恢复 refreshIfNeeded、loadMessages、loadOlderMessages

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt`

**说明:** 恢复 V1 的冷却机制、显式 _isLoading 管理、分页加载。

- [ ] **Step 1: 恢复 loadMessages**

将当前的 `loadMessages` 替换为 V1 版本（带 _isLoading + OOM 重试）：

```kotlin
fun loadMessages() {
    viewModelScope.launch {
        _isLoading.value = true
        _error.value = null
        try {
            val messages = manageSessionUseCase.listMessages(serverId, sessionId, limit = currentMessageLimit)
            chatRepository.setMessages(sessionId, messages)
            _hasOlderMessages.value = messages.size >= currentMessageLimit

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Loaded ${messages.size} messages for session $sessionId (limit=$currentMessageLimit, hasOlder=${_hasOlderMessages.value})")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load messages", e)
            if (e is OutOfMemoryError || (e.cause is OutOfMemoryError)) {
                Log.w(TAG, "OOM loading messages, retrying with smaller limit")
                currentMessageLimit = (currentMessageLimit / 2).coerceAtLeast(10)
                try {
                    val messages = manageSessionUseCase.listMessages(serverId, sessionId, limit = currentMessageLimit)
                    chatRepository.mergeMessages(sessionId, messages)
                    _hasOlderMessages.value = messages.size >= currentMessageLimit
                    if (BuildConfig.DEBUG) Log.d(TAG, "Retry succeeded: loaded ${messages.size} messages (limit=$currentMessageLimit)")
                } catch (retryEx: Throwable) {
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

- [ ] **Step 2: 恢复 refreshMessages**

将当前的 `refreshMessages` 替换为 V1 版本（带 _isRefreshing）：

```kotlin
private suspend fun refreshMessages() {
    _isRefreshing.value = true
    try {
        val messages = manageSessionUseCase.listMessages(serverId, sessionId, limit = currentMessageLimit)
        chatRepository.setMessages(sessionId, messages)
        _hasOlderMessages.value = messages.size >= currentMessageLimit
    } catch (e: Throwable) {
        Log.e(TAG, "Failed to refresh messages", e)
    } finally {
        _isRefreshing.value = false
    }
}
```

- [ ] **Step 3: 恢复 refreshIfNeeded（冷却机制）**

将当前的 `refreshIfNeeded` 替换为 V1 版本（带 REFRESH_COOLDOWN_MS）：

```kotlin
fun refreshIfNeeded() {
    val elapsed = System.currentTimeMillis() - lastRefreshTimeMs
    if (elapsed >= REFRESH_COOLDOWN_MS) {
        refreshSession()
    }
}
```

- [ ] **Step 4: 恢复 refreshAndSync（更新 lastRefreshTimeMs）**

确认 `refreshAndSync` 方法末尾有：
```kotlin
lastRefreshTimeMs = System.currentTimeMillis()
```

- [ ] **Step 5: 恢复 loadOlderMessages**

将当前的 no-op 替换为 V1 版本（翻倍加载）：

```kotlin
fun loadOlderMessages() {
    viewModelScope.launch {
        _isLoadingOlder.value = true
        currentMessageLimit = currentMessageLimit * 2
        try {
            val messages = manageSessionUseCase.listMessages(serverId, sessionId, limit = currentMessageLimit)
            chatRepository.mergeMessages(sessionId, messages)
            _hasOlderMessages.value = messages.size >= currentMessageLimit

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Loaded older: ${messages.size} messages (limit=$currentMessageLimit, hasOlder=${_hasOlderMessages.value})")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load older messages", e)
            currentMessageLimit = currentMessageLimit / 2
        } finally {
            _isLoadingOlder.value = false
        }
    }
}
```

- [ ] **Step 6: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt
git commit -m "fix: restore V1 refresh cooldown, explicit loading state, and pagination"
```

---

## Task 5: 恢复 ChatInputBar isSending + BreathingCircleIndicator

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/input/ChatInputBar.kt`

**说明:** 恢复 `isSending` 参数，但**保留**当前的文本框高度稳定性改进（alpha 替代条件渲染）和 `sessionStatus` 参数（因为下游仍在使用）。最终签名同时包含 `isSending` 和 `sessionStatus`。

- [ ] **Step 1: 修改函数签名**

在 `ChatInputBar` 参数列表中，将 `sessionStatus: SessionStatus = SessionStatus.Idle` 替换为两个参数：

```kotlin
isSending: Boolean,
isBusy: Boolean = false,
```

移除 `sessionStatus` 参数。在函数体内部删除：
```kotlin
val isBusy = sessionStatus is SessionStatus.Busy || sessionStatus is SessionStatus.Retry
```

（因为 `isBusy` 现在是直接参数）

同时移除不再需要的 `SessionStatus` import（如果不再被其他代码使用）。

- [ ] **Step 2: 恢复 canSend 逻辑**

将当前的：
```kotlin
val canSend = (text.isNotBlank() || attachments.isNotEmpty()) && (!isShellMode || !isBusy)
```

恢复为（加回 `!isSending`）：
```kotlin
val canSend = (text.isNotBlank() || attachments.isNotEmpty()) && !isSending && (!isShellMode || !isBusy)
```

- [ ] **Step 3: 恢复发送按钮图标（BreathingCircleIndicator）**

在发送按钮的 `content` lambda 中，将当前的：
```kotlin
if (showStop) {
    Icon(Icons.Default.Stop, ...)
} else {
    Icon(Icons.AutoMirrored.Filled.Send, ...)
}
```

恢复为（加回 isSending 分支）：
```kotlin
if (showStop) {
    Icon(
        Icons.Default.Stop,
        contentDescription = stringResource(R.string.chat_stop),
        modifier = Modifier.size(20.dp),
        tint = MaterialTheme.colorScheme.error
    )
} else if (isSending) {
    BreathingCircleIndicator(
        size = 20.dp,
        color = MaterialTheme.colorScheme.primary
    )
} else {
    Icon(
        Icons.AutoMirrored.Filled.Send,
        contentDescription = if (isShellMode) {
            stringResource(R.string.chat_send_shell)
        } else {
            stringResource(R.string.chat_send)
        },
        modifier = Modifier.size(20.dp),
        tint = if (canSend) {
            MaterialTheme.colorScheme.primary
        } else if (isShellMode && isAmoled && !isSending) {
            MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.MUTED)
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.FAINT)
        }
    )
}
```

- [ ] **Step 4: 恢复 shell mode 按钮样式中的 isSending**

将按钮样式中 `isBusy` 的 shell mode 条件恢复为 `isSending`：
- `isShellMode && !isBusy` → `isShellMode && !isSending`（在背景色和边框色两处）

- [ ] **Step 5: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/input/ChatInputBar.kt
git commit -m "fix: restore isSending param and BreathingCircleIndicator in ChatInputBar"
```

---

## Task 6: 适配 ChatScreen isSending 传递

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt`

**说明:** ChatScreen 中传递给 ChatInputBar 的参数需要适配新的签名。

- [ ] **Step 1: 修改 ChatInputBar 调用**

在 ChatScreen.kt 中找到 ChatInputBar 调用处，将当前的：
```kotlin
sessionStatus = sessionMeta.sessionStatus,
```

替换为 V1 风格的双参数：
```kotlin
isSending = interaction.isSending,
isBusy = sessionMeta.sessionStatus is SessionStatus.Busy || sessionMeta.sessionStatus is SessionStatus.Retry,
```

- [ ] **Step 2: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt
git commit -m "fix: adapt ChatScreen to pass isSending and isBusy to ChatInputBar"
```

---

## Task 7: 清理 MessageCard 调试日志 + 处理残留

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/MessageCard.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt`

**说明:** 清理残留调试日志，处理 `_messagesList`/`_partsList`/`sseJob` 是否仍然需要的问题。

- [ ] **Step 1: 清理 MessageCard 调试日志**

在 MessageCard.kt 中找到并删除：
```kotlin
android.util.Log.d("AgentTag", "[MessageCard] agentName=$agentName...")
```

以及 MessageEventParser.kt 中的调试日志（如果有）：
```kotlin
Log.d("AgentTag", "[SSE] Assistant parsed: id=${it.id}, agent=${it.agent}, modelId=${it.modelId}")
```

- [ ] **Step 2: 评估 _messagesList / _partsList / sseJob 去留**

恢复 V1 的 `messageListState` 后，数据路径变为：
- V1: `messagePaging.observeMessages(sid)` → EventDispatcher → chatRepository → combine 自动响应
- 当前: `_messagesList` / `_partsList` ← `sseJob` 手动收集

**决策:** 如果 `sseJob` 的 `startObservingMessages()` 与 `messagePaging.observeMessages()` 功能重叠（都是观察 SSE 数据变化），移除 `_messagesList`、`_partsList`、`sseJob`、`startObservingMessages()`，以及构造函数中不再需要的 `httpClient`、`sseClient` 参数。

**但**需要确认 `loadSession()` 和 `ensureSession()` 中是否还依赖这些。检查方法：
1. 搜索 `sseJob` 的所有引用
2. 搜索 `_messagesList` 的所有引用
3. 搜索 `startObservingMessages` 的所有引用

如果这些只在 Task 2 恢复的 V1 路径中被间接替代，则安全移除。

- [ ] **Step 3: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/MessageCard.kt
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt
git commit -m "chore: clean up debug logs and remove unused SSE local buffer"
```

---

## Task 8: 全量编译验证 + 单元测试

**Files:**
- 无新文件

- [ ] **Step 1: 全量编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 运行单元测试**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun`
Expected: 所有测试通过

- [ ] **Step 3: Release 编译**

Run: `.\gradlew :app:assembleBetaRelease`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 最终 commit（如有测试修复）**

```bash
git add -A
git commit -m "test: update tests for V1 state restoration"
```

---

## 自检清单

### Spec Coverage
- [x] 恢复 isSending 状态 — Task 1 + 2 + 3 + 5 + 6
- [x] 恢复发送失败草稿恢复 — Task 3
- [x] 恢复 error 全局状态 — Task 1 + 2
- [x] 恢复 isLoading 首次加载状态 — Task 1 + 4
- [x] 恢复分页 loadOlderMessages — Task 4
- [x] 恢复 pendingMessageIds 乐观发送 — Task 1 + 2 + 3
- [x] 恢复 ON_RESUME 冷却机制 — Task 4
- [x] 恢复 BreathingCircleIndicator — Task 5
- [x] 清理调试日志 — Task 7
- [x] 保留 sessionMetaState 防闪烁 — 不修改 sessionMetaState
- [x] 保留 savedLazyIndex — 不修改滚动恢复
- [x] 保留 data 层修复 — 不修改 data 层文件
- [x] 保留 ChatInputBar 文本框高度改进 — Task 5 中明确保留

### Placeholder Scan
- 无 TBD/TODO/placeholder — 所有代码段均为完整 V1 代码

### Type Consistency
- InteractionState.isSending: Boolean — Task 1 声明，Task 2 赋值，Task 5 使用 ✅
- MessageListState.pendingMessageIds: Set<String> — Task 1 声明，Task 2 赋值 ✅
- ChatInputBar.isSending: Boolean — Task 5 声明，Task 6 传递 ✅
- ChatInputBar.isBusy: Boolean — Task 5 声明，Task 6 传递 ✅
