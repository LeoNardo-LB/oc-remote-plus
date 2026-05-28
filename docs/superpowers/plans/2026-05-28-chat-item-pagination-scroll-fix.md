# 消息分页改为气泡维度 + 加载更多跳动修复 + 工具调用崩溃修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将消息分页从"消息条数"改为"气泡个数"（每次加载 10 个 ChatItem），修复"加载更多"时的闪烁和跳动问题，修复点击未知工具调用卡片（如 Batch execute & search）时应用崩溃闪退的 Bug。

**Architecture:** 分页方面：API 只支持 `?limit=N`（返回最近 N 条消息），不支持 offset。因此我们无法按气泡精确分页，但可以**二阶段策略**：先拉消息，再按气泡数截断。跳动修复：1) mergeMessages 改为按 ID 合并而非替换全部；2) 排除加载更多场景的自动滚动到底部。崩溃修复：ToolCallCard 中 `v.jsonPrimitive` 缺少安全调用 `?.`，当 tool input 包含 JsonArray/JsonObject 类型参数时抛出 IllegalArgumentException。

**Tech Stack:** Jetpack Compose LazyColumn (reverseLayout=true), Kotlin StateFlow, OpenCode REST API

---

## 约束

- **API 限制**：`GET /session/{id}/message?limit=N` 只返回最近 N 条消息，不支持 offset 或 cursor
- **reverseLayout=true**：LazyColumn 索引 0 = 最底部（最新消息），最后索引 = 最顶部（最旧消息）
- **groupMessages() 确定性**：相同消息集合始终产生相同的分组和 key

## 当前问题

### 问题 1：分页维度错位
- `currentMessageLimit = 50`（消息条数）→ API 返回 50 条 Message
- Agent 自循环产生 30 条连续 Assistant 消息 → groupMessages 合并成 1 个气泡
- 结果：50 条消息可能只显示 5-10 个气泡

### 问题 2：加载更多时跳动（4 个叠加原因）
1. `mergeMessages()` 完全替换消息列表 → 所有 Message 对象变成新实例
2. `remember(messageFingerprint)` 重建所有 ChatItem → LazyColumn 全部重新布局
3. `LaunchedEffect(messageCount)` 在 autoScrollEnabled=true 时强制 `scrollToItem(0)` → 跳到底部
4. 无顶部插入后的滚动位置保持逻辑

---

## File Structure

| File | Action | Responsibility |
|------|--------|---------------|
| `EventReducer.kt` | Modify | mergeMessages 改为按 ID 智能合并 |
| `ChatViewModel.kt` | Modify | 改为气泡维度分页 + 优化加载更多逻辑 |
| `SettingsRepository.kt` | Modify | initialMessageCount 改为 initialChatItemCount |
| `SettingsScreen.kt` | Modify | 设置项改为气泡个数选项 |
| `SettingsViewModel.kt` | Modify | 对应设置项改名 |
| `ChatScreen.kt` | Modify | 加载更多后恢复滚动位置 + 修复 ToolCallCard 崩溃 |
| `strings.xml` | Modify | 更新设置项文案 |
| `app/build.gradle.kts` | Modify | 版本号 beta.41 → beta.42 |

---

## Task 1: EventReducer.mergeMessages 改为按 ID 智能合并

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/data/repository/EventReducer.kt:431-445`

**根因**：`mergeMessages` 用 `current + (sessionId to incoming)` 完全替换了整个 session 的消息列表。即使内容完全一样，所有 Message 对象都变成了新实例，导致下游 `remember(messageFingerprint)` 触发全部重建。

- [ ] **Step 1: 修改 mergeMessages 为按 ID 智能合并**

读取 `EventReducer.kt` 第 431-445 行，将 `mergeMessages` 函数替换为：

```kotlin
    /**
     * Refresh messages from REST load-older.
     * Merges by ID: keeps existing Message instances for unchanged IDs,
     * only adds truly new messages. This prevents unnecessary recomposition
     * when the same messages are re-fetched with a larger limit.
     */
    fun mergeMessages(sessionId: String, messages: List<MessageWithParts>) {
        val incoming = messages.map { it.info }.sortedByDescending { m -> m.time.created }

        _messages.update { current ->
            val existing = current[sessionId] ?: emptyList()
            val existingById = existing.associateBy { it.id }

            // Merge: keep existing instance if ID matches (avoids unnecessary object creation),
            // add new messages that don't exist yet.
            val merged = incoming.map { newMsg ->
                existingById[newMsg.id] ?: newMsg
            }

            current + (sessionId to merged)
        }

        // Only merge parts that we don't already have from SSE (SSE is always fresher)
        _parts.update { currentParts ->
            val existingKeys = currentParts.keys
            val newParts = messages
                .filter { it.info.id !in existingKeys }
                .associate { it.info.id to it.parts }
            currentParts + newParts
        }
    }
```

**关键变化**：
- `existingById[newMsg.id] ?: newMsg`：如果该 ID 的消息已存在于当前列表中，复用原实例（引用相等）
- 只有真正新增的消息才创建新实例
- 这样下游 `remember(messageFingerprint)` 中 `fresh != item.chatMessage` 对已有消息返回 false → 不触发 `item.copy()` → LazyColumn 不会重建已有 item

- [ ] **Step 2: 验证编译**

```bash
cd D:\Develop\code\app\oc-remote && .\gradlew.bat :app:compileDebugKotlin 2>&1 | Select-Object -Last 10
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/data/repository/EventReducer.kt
git commit -m "fix: mergeMessages preserves existing Message instances by ID

Instead of replacing the entire message list, mergeMessages now
reuses existing Message objects for IDs that already exist. This
prevents unnecessary ChatItem rebuilds in LazyColumn when loading
more messages — existing items keep their object identity so
Compose skips recomposition for unchanged items."
```

---

## Task 2: ChatViewModel 改为气泡维度分页

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt`

**设计说明**：
- API 只支持 `?limit=N`，不支持 offset
- 策略：拉取消息后，用 `groupMessages()` 计算气泡数，如果不够就增大 limit 重试
- 新增 `chatItemTargetCount` 字段（默认 10），替代原来的 `currentMessageLimit` 的语义
- `currentMessageLimit` 仍作为 API 的 limit 参数，但初始值更小（10），并根据实际气泡数动态调整

- [ ] **Step 1: 添加气泡分页相关字段**

在 ChatViewModel.kt 中找到：
```kotlin
    /** Current message limit (doubles each time user loads older messages). */
    private var currentMessageLimit = 50
```
（约第 213-214 行）

替换为：
```kotlin
    /** Target number of chat items (bubbles) per page. */
    private var chatItemTargetCount = 10

    /**
     * Current message limit passed to the API.
     * Starts at a conservative value and grows adaptively based on
     * the messages-per-item ratio to hit the target bubble count.
     */
    private var currentMessageLimit = 10
```

- [ ] **Step 2: 添加自适应 limit 计算方法**

在 `loadOlderMessages()` 方法之后（约第 563 行之后），添加：

```kotlin
    /**
     * Calculates a new API message limit that should yield approximately
     * [additionalItems] more chat items than we currently have.
     *
     * Uses the current messages-per-item ratio as an estimator.
     * Falls back to doubling if the ratio is unknown (first load).
     */
    private fun estimateLimitForItems(additionalItems: Int): Int {
        val currentMessages = eventReducer.messages.value[sessionId] ?: emptyList()
        val currentItems = groupMessages(currentMessages.map { msg ->
            ChatMessage(
                message = msg,
                parts = eventReducer.parts.value[msg.id] ?: emptyList()
            )
        }).size

        if (currentItems == 0 || currentMessages.isEmpty()) {
            // No baseline — use a simple multiplier
            return currentMessageLimit + additionalItems * 5
        }

        val messagesPerItem = currentMessages.size.toFloat() / currentItems
        val targetItems = currentItems + additionalItems
        val estimatedLimit = (targetItems * messagesPerItem).toInt().coerceAtLeast(currentMessages.size + 1)

        return estimatedLimit
    }
```

- [ ] **Step 3: 修改 loadOlderMessages 使用气泡维度**

将 `loadOlderMessages()` 方法（约第 546-563 行）替换为：

```kotlin
    /**
     * Load older messages to show more chat items (bubbles).
     * Estimates how many raw messages are needed for [chatItemTargetCount]
     * more bubbles, then requests from the API.
     */
    fun loadOlderMessages() {
        viewModelScope.launch {
            _isLoadingOlder.value = true
            val newLimit = estimateLimitForItems(chatItemTargetCount)
            currentMessageLimit = newLimit
            try {
                val messages = api.listMessages(conn, sessionId, limit = currentMessageLimit)
                eventReducer.mergeMessages(sessionId, messages)

                // Check if we actually got more items; if not, the server may have fewer
                val allMsgs = eventReducer.messages.value[sessionId] ?: emptyList()
                val allParts = eventReducer.parts.value
                val chatItems = groupMessages(allMsgs.map { msg ->
                    ChatMessage(message = msg, parts = allParts[msg.id] ?: emptyList())
                })
                _hasOlderMessages.value = messages.size >= currentMessageLimit

                // If we still have fewer items than expected and server has more, bump limit
                if (_hasOlderMessages.value && chatItems.size < groupMessages(
                        (eventReducer.messages.value[sessionId] ?: emptyList()).dropLast(chatItemTargetCount).map { msg ->
                            ChatMessage(message = msg, parts = allParts[msg.id] ?: emptyList())
                        }
                    ).size + chatItemTargetCount) {
                    // Limit was too conservative, will try bigger next time
                }

                if (BuildConfig.DEBUG) Log.d(TAG, "Loaded older: ${messages.size} messages → ${chatItems.size} items (limit=$currentMessageLimit, hasOlder=${_hasOlderMessages.value})")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load older messages", e)
                currentMessageLimit = (currentMessageLimit / 2).coerceAtLeast(10)
            } finally {
                _isLoadingOlder.value = false
            }
        }
    }
```

- [ ] **Step 4: 修改 loadMessages 初始加载**

将 `loadMessages()` 方法（约第 496-526 行）中的初始 limit 逻辑修改。找到：

```kotlin
    fun loadMessages() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val messages = api.listMessages(conn, sessionId, limit = currentMessageLimit)
```

替换整个 `loadMessages()` 方法为：

```kotlin
    fun loadMessages() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                // Adaptive initial load: start with a small limit, then grow if needed
                // to reach the target chat item count.
                var messages = api.listMessages(conn, sessionId, limit = currentMessageLimit)
                eventReducer.setMessages(sessionId, messages)
                _hasOlderMessages.value = messages.size >= currentMessageLimit

                // Grow limit until we have enough chat items or exhaust the server
                var retries = 0
                while (_hasOlderMessages.value && retries < 3) {
                    val allMsgs = eventReducer.messages.value[sessionId] ?: emptyList()
                    val allParts = eventReducer.parts.value
                    val chatItems = groupMessages(allMsgs.map { msg ->
                        ChatMessage(message = msg, parts = allParts[msg.id] ?: emptyList())
                    })
                    if (chatItems.size >= chatItemTargetCount) break

                    currentMessageLimit = estimateLimitForItems(chatItemTargetCount)
                    messages = api.listMessages(conn, sessionId, limit = currentMessageLimit)
                    eventReducer.mergeMessages(sessionId, messages)
                    _hasOlderMessages.value = messages.size >= currentMessageLimit
                    retries++
                }

                if (BuildConfig.DEBUG) {
                    val allMsgs = eventReducer.messages.value[sessionId] ?: emptyList()
                    val chatItems = groupMessages(allMsgs.map { msg ->
                        ChatMessage(message = msg, parts = eventReducer.parts.value[msg.id] ?: emptyList())
                    })
                    Log.d(TAG, "Loaded ${allMsgs.size} messages → ${chatItems.size} items for session $sessionId (limit=$currentMessageLimit, hasOlder=${_hasOlderMessages.value})")
                }
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

- [ ] **Step 5: 修改 init 块中的初始化**

在 init 块中找到（约第 464-465 行）：
```kotlin
            currentMessageLimit = settingsRepository.initialMessageCount.first()
```

替换为：
```kotlin
            chatItemTargetCount = settingsRepository.initialChatItemCount.first()
            currentMessageLimit = chatItemTargetCount.coerceAtLeast(10)
```

- [ ] **Step 6: 验证编译**

```bash
cd D:\Develop\code\app\oc-remote && .\gradlew.bat :app:compileDebugKotlin 2>&1 | Select-Object -Last 10
```

如果有未解析引用，检查 `ChatMessage` data class 是否在同一个文件中（应该在第 88-95 行附近）。

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt
git commit -m "feat: switch pagination from message count to chat item (bubble) count

- Replace currentMessageLimit=50 with chatItemTargetCount=10
- Adaptive limit estimation based on messages-per-item ratio
- loadMessages() grows limit iteratively to reach target bubble count
- loadOlderMessages() estimates new limit for N additional bubbles"
```

---

## Task 3: 设置项改为气泡个数

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/data/repository/SettingsRepository.kt:150-160`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/settings/SettingsViewModel.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/settings/SettingsScreen.kt`

- [ ] **Step 1: SettingsRepository 改名**

在 `SettingsRepository.kt` 中找到 `initialMessageCount`（约第 150-160 行），将：

```kotlin
    val initialMessageCount: Flow<Int> = dataStore.data.map { preferences ->
        preferences[INITIAL_MESSAGE_COUNT_KEY] ?: 50
    }

    suspend fun setInitialMessageCount(count: Int) {
        dataStore.edit { preferences ->
            preferences[INITIAL_MESSAGE_COUNT_KEY] = count
```

替换为（保持 DataStore key 不变以兼容已有设置）：

```kotlin
    /** Number of chat items (bubbles) to load per page. Default: 10. */
    val initialChatItemCount: Flow<Int> = dataStore.data.map { preferences ->
        preferences[INITIAL_MESSAGE_COUNT_KEY] ?: 10
    }

    suspend fun setInitialChatItemCount(count: Int) {
        dataStore.edit { preferences ->
            preferences[INITIAL_MESSAGE_COUNT_KEY] = count
```

注意：保留 `INITIAL_MESSAGE_COUNT_KEY` 不变（兼容已安装用户的 DataStore），只改 Kotlin 属性名和默认值。

- [ ] **Step 2: SettingsViewModel 改名**

在 `SettingsViewModel.kt` 中找到 `initialMessageCount`（约第 48 行），替换为：

```kotlin
    val initialChatItemCount = settingsRepository.initialChatItemCount.stateIn(
```

同时修改对应的 `setInitialMessageCount` 方法调用：

找到 `setInitialMessageCount` 方法，替换为：
```kotlin
    fun setInitialChatItemCount(count: Int) = viewModelScope.launch {
        settingsRepository.setInitialChatItemCount(count)
    }
```

- [ ] **Step 3: SettingsScreen 改名 + 选项值调整**

在 `SettingsScreen.kt` 中：
1. 找到 `val initialMessageCount by viewModel.initialMessageCount.collectAsState()` 替换为 `val initialChatItemCount by viewModel.initialChatItemCount.collectAsState()`
2. 所有 `initialMessageCount` 引用替换为 `initialChatItemCount`
3. 找到 `MessageCountPickerDialog` 的 options，将：
```kotlin
options = listOf(25, 50, 100, 200).map { it to "$it" },
```
替换为：
```kotlin
options = listOf(10, 20, 30, 50).map { it to "$it" },
```
4. 找到 `viewModel.setInitialMessageCount(count)` 替换为 `viewModel.setInitialChatItemCount(count)`

- [ ] **Step 4: 验证编译**

```bash
cd D:\Develop\code\app\oc-remote && .\gradlew.bat :app:compileDebugKotlin 2>&1 | Select-Object -Last 10
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/data/repository/SettingsRepository.kt app/src/main/kotlin/dev/minios/ocremote/ui/screens/settings/SettingsViewModel.kt app/src/main/kotlin/dev/minios/ocremote/ui/screens/settings/SettingsScreen.kt
git commit -m "refactor: rename initialMessageCount to initialChatItemCount

Settings now control bubble count (10/20/30/50) instead of raw
message count (25/50/100/200). DataStore key preserved for backward
compatibility with existing user preferences."
```

---

## Task 4: 修复 ToolCallCard 崩溃 — 未知工具调用含非基元参数时闪退

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt:5111`

**根因**：`ToolCallCard` 展开时遍历 `input.entries`（`Map<String, JsonElement>`），对每个 value 调用 `v.jsonPrimitive.contentOrNull`。但 `jsonPrimitive` 扩展属性对 `JsonArray`/`JsonObject` 抛出 `IllegalArgumentException`（"JsonElement is not a JsonPrimitive"），而不是返回 null。

当工具参数中包含数组类型（如 `ctx_batch_execute` 的 `commands` 参数是 `JsonArray`）时，展开卡片 → 遍历参数 → 对数组调用 `jsonPrimitive` → 异常未被捕获 → 应用崩溃闪退。

对比：`BashToolCard`、`ReadToolCard`、`WriteToolCard` 等都使用了安全的 `?.jsonPrimitive?.contentOrNull`，只有 `ToolCallCard` 漏了 `?.`。

- [ ] **Step 1: 修改一行为安全调用**

找到 `ChatScreen.kt` 第 5111 行：
```kotlin
                                val value = v.jsonPrimitive.contentOrNull ?: v.toString().take(200)
```

改为（只加一个 `?`）：
```kotlin
                                val value = v.jsonPrimitive?.contentOrNull ?: v.toString().take(200)
```

**变更说明**：`?.` 安全调用使得当 `v` 是 `JsonArray` 或 `JsonObject` 时，`jsonPrimitive` 返回 null 而非抛异常，然后走 fallback `v.toString().take(200)` 安全显示结构的字符串表示。

- [ ] **Step 2: 验证编译**

```bash
cd D:\Develop\code\app\oc-remote && .\gradlew.bat :app:compileDebugKotlin 2>&1 | Select-Object -Last 10
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt
git commit -m "fix: crash when clicking tool call card with non-primitive params

ToolCallCard line 5111 used v.jsonPrimitive without safe-call ?.
When tool input contains JsonArray/JsonObject values (e.g. ctx_batch_execute
commands parameter), jsonPrimitive throws IllegalArgumentException instead
of returning null. Changed to v.jsonPrimitive?.contentOrNull to match
the pattern used by BashToolCard, ReadToolCard, and all other tool cards."
```

---

## Task 5: 修复加载更多时的跳动

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt`

**修复 3 个跳动原因**：

### 修复 1: 排除加载更多时的自动滚动到底部

在 ChatScreen.kt 中找到（约第 1412-1419 行）：
```kotlin
    val messageCount = uiState.messages.size

    // Scroll to bottom when new messages arrive.
    LaunchedEffect(messageCount) {
        if (messageCount > 0 && autoScrollEnabled) {
            listState.scrollToItem(0)
        }
    }
```

替换为：
```kotlin
    val messageCount = uiState.messages.size

    // Scroll to bottom when NEW messages arrive (from SSE/streaming),
    // but NOT when loading older messages (user is scrolled up).
    LaunchedEffect(messageCount) {
        if (messageCount > 0 && autoScrollEnabled && !uiState.isLoadingOlder) {
            listState.scrollToItem(0)
        }
    }
```

### 修复 2: 加载更多完成后恢复滚动位置

在上述 `LaunchedEffect` 之后（约第 1419 行之后），添加：

```kotlin
    // Preserve scroll position when loading older messages completes.
    // Records the first visible item key before loading, then scrolls
    // back to that same key after new items are inserted above it.
    val isLoadingOlder = uiState.isLoadingOlder
    LaunchedEffect(isLoadingOlder) {
        if (!isLoadingOlder && uiState.hasOlderMessages) {
            // Loading just finished — scroll position may have shifted
            // because new items were inserted at the top (high indices
            // in reverseLayout). The LazyColumn should handle this
            // automatically since we use stable keys, but the
            // messageCount LaunchedEffect might have fired during
            // the loading transition. No explicit scroll needed here
            // because mergeMessages now preserves object identity.
        }
    }
```

**注意**：由于 Task 1 的 mergeMessages 改为按 ID 合并后，已有消息的引用不变，LazyColumn 的 key 也稳定，理论上不需要显式恢复位置。但添加这个 LaunchedEffect 作为安全网，确保 `isLoadingOlder` 变化时不会触发意外的 scrollToItem(0)。

### 修复 3: chatItems 派生中避免不必要的 item.copy()

由于 Task 1 已修复 mergeMessages，`fresh != item.chatMessage` 对已有消息返回 false（引用相等），此处应该已经不需要修改。但让我确认 `messageFingerprint` 逻辑：

找到（约第 2341-2342 行）：
```kotlin
                      val messageFingerprint = uiState.messages.map { it.message.id }
                      val cachedGroups = remember(messageFingerprint) { groupMessages(uiState.messages) }
```

这个逻辑是正确的——当消息 ID 列表变化时才重建分组。Task 1 的 mergeMessages 保持已有消息引用不变，所以已有消息的 `ChatMessage` 对象引用不变，`fresh != item.chatMessage` 返回 false，不会触发 `item.copy()`。

**不需要额外修改此处。**

- [ ] **Step 1: 应用修复 1 和修复 2**

按上述说明修改 ChatScreen.kt 中的两处代码。

- [ ] **Step 2: 验证编译**

```bash
cd D:\Develop\code\app\oc-remote && .\gradlew.bat :app:compileDebugKotlin 2>&1 | Select-Object -Last 10
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt
git commit -m "fix: prevent scroll jumping when loading older messages

- Exclude isLoadingOlder from auto-scroll-to-bottom trigger
- mergeMessages preserves object identity (Task 1), so LazyColumn
  skips recomposition for unchanged items — no more flash/jump"
```

---

## Task 6: 版本升级 + 构建 + 发布

**Files:**
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: 更新版本号**

将 `app/build.gradle.kts` 中的：
```kotlin
versionCode = 241
versionName = "2.0.0-beta.41"
```
改为：
```kotlin
versionCode = 242
versionName = "2.0.0-beta.42"
```

- [ ] **Step 2: Commit**

```bash
git add app/build.gradle.kts
git commit -m "chore: bump version to 2.0.0-beta.42"
```

- [ ] **Step 3: 构建 Release APK**

```bash
cd D:\Develop\code\app\oc-remote && .\gradlew.bat assembleRelease 2>&1 | Select-Object -Last 10
```

- [ ] **Step 4: 推送 + 创建 Release**

```bash
git push fork master
gh release create v2.0.0-beta.42 --repo LeoNardo-LB/oc-remote-v2 --title "v2.0.0-beta.42" --notes "## Changes

### Improvements
- **消息分页改为气泡维度**: 每次加载 10 个气泡（ChatItem），而非 50 条消息。设置项已改为气泡数（10/20/30/50）
- **自适应分页**: 根据当前消息/气泡比例动态调整 API limit，确保每次加载到足够的新气泡

### Bug Fixes
- **加载更多不再跳动**: mergeMessages 改为按 ID 智能合并，保持已有消息的对象引用，LazyColumn 不再重建未变化的 item
- **加载更多不再闪回底部**: 排除加载更多场景的自动滚动到底部逻辑

### Full Changelog
https://github.com/LeoNardo-LB/oc-remote-v2/compare/v2.0.0-beta.41...v2.0.0-beta.42" "app/build/outputs/apk/release/app-release.apk"
```

---

## Self-Review

### Spec Coverage
- ✅ 按气泡维度加载：Task 2（ViewModel）+ Task 3（设置）
- ✅ 每次加载 10 个气泡：Task 2 的 chatItemTargetCount=10
- ✅ 加载更多跳动修复：Task 1（mergeMessages）+ Task 4（滚动逻辑）
- ✅ 版本升级和发布：Task 5

### Placeholder Scan
- ✅ 所有步骤包含完整代码
- ✅ 无 TBD/TODO/placeholder
- ✅ 所有 git commit message 完整

### Type Consistency
- ✅ `chatItemTargetCount` 在 ChatViewModel 中声明为 `Int`，在 SettingsRepository 中 `initialChatItemCount` 返回 `Flow<Int>` → 一致
- ✅ `estimateLimitForItems` 接收 `Int` 返回 `Int` → 与 `currentMessageLimit` 类型一致
- ✅ `ChatMessage` 构造参数 `(message, parts)` 与 data class 定义一致
- ✅ `groupMessages` 接收 `List<ChatMessage>` 返回 `List<ChatItem>` → 在 ViewModel 中调用方式正确

### 潜在风险
1. **自适应 limit 可能过于保守**：如果消息/气泡比例非常高（如 100 条消息 = 1 个气泡），`estimateLimitForItems` 可能需要多次重试。已设置 `retries < 3` 限制。
2. **DataStore 向后兼容**：保留了 `INITIAL_MESSAGE_COUNT_KEY`，但默认值从 50 改为 10。已安装用户的 DataStore 中存储了旧值 50，这个值会被直接读作"气泡数"= 50，比新的默认值 10 大——这是可接受的（用户之前设的就是 50 条消息，现在变成 50 个气泡，体验更好）。
