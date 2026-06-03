# 会话状态闪烁修复 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复目录树界面上会话状态来回闪烁的问题（Busy ↔ Idle 交替出现），建立「SSE 优先、REST 兜底」的状态同步优先级。

**Architecture:** 当前 `updateAllSessionStatuses` 使用 `current + statuses` 无条件覆盖，REST 快照可能覆盖 SSE 刚推送的实时状态。修复策略：REST 同步不应将已有 Busy/Retry 状态降级为 Idle——只有 SSE 事件才能标记会话空闲。

**Tech Stack:** Kotlin, StateFlow, Jetpack Compose

---

## 问题分析

### 已确认事实

1. **服务端保证**：父 Agent 在子 Agent 执行期间 session status 始终为 `busy`（`runLoop` 循环顶部 `status.set(sessionID, { type: "busy" })`，只在 `finishRun` 时设 idle）
2. **服务端 API 语义**：`GET /session/status` 返回当前快照，idle 的 session 从 map 中删除不返回
3. **客户端 `updateAllSessionStatuses`** 使用 `current + statuses` 合并，无条件覆盖
4. **客户端 `CommandExecuted` 事件** 自动将该 session 设为 Idle（`EventDispatcher.kt:68-72`）

### 可能的闪烁原因（按可能性排序）

| # | 原因 | 可能性 | 证据 |
|---|------|--------|------|
| A | `CommandExecuted` 事件将 Busy 的 session 强制设为 Idle，但紧接着 agent 继续下一轮工具调用 → SSE 推送 busy | **高** | `EventDispatcher.kt:68-72` 无条件设 Idle |
| B | SSE 重连时 REST sync 快照覆盖了 SSE 实时状态 | **中** | `updateAllSessionStatuses` 无条件合并 |
| C | 其他未知时序问题 | **低** | 需真机调试确认 |

### 修复策略

**核心原则**：SSE 事件是实时数据源，REST 是冷启动/恢复时的兜底。REST 同步不应「降级」SSE 已确认的 Busy/Retry 状态。

**具体做法**：
1. `updateAllSessionStatuses` 增加「不降级」保护：不将已有的 Busy/Retry 覆盖为 Idle
2. `CommandExecuted` 不再无条件设 Idle——只在确实没有活跃 SSE 状态时才设
3. 新增「SSE 最后更新时间戳」机制，REST 同步仅在 SSE 数据过期（>5s）时才覆盖

---

## File Structure

| 文件 | 改动类型 | 职责 |
|------|---------|------|
| `data/repository/handler/SessionEventHandler.kt` | 修改 | 增加「不降级」保护逻辑 + 时间戳追踪 |
| `data/repository/EventDispatcher.kt` | 修改 | `CommandExecuted` 不再无条件设 Idle |
| `service/SseConnectionManager.kt` | 修改 | `syncSessionStatuses` 区分首次连接 vs 重连 |
| `ui/screens/chat/ChatViewModel.kt` | 修改 | `syncSessionStatus` 增加「不降级」逻辑 |
| `ui/screens/sessions/SessionListViewModel.kt` | 修改 | `syncSessionStatusesFromServer` 增加「不降级」逻辑 |

---

## Task 1: SessionEventHandler 增加「不降级」保护 + SSE 时间戳

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/data/repository/handler/SessionEventHandler.kt`

**目标**：追踪每个 session 状态的最后 SSE 更新时间，REST 同步时不覆盖「新鲜」的 SSE 状态。

- [ ] **Step 1: 添加 SSE 时间戳追踪**

在 `_sessionStatuses` 声明附近新增 `_sseTimestamps`：

```kotlin
// 在 _sessionStatuses 声明之后（约第31行之后）新增：
/** Tracks when each session's status was last updated by SSE events. */
private val _sseTimestamps = MutableStateFlow<Map<String, Long>>(emptyMap())
```

- [ ] **Step 2: SSE 写入点更新时间戳**

在 `handleSessionStatus` 和 `handleSessionIdle` 方法中更新时间戳：

```kotlin
// handleSessionStatus（约第105-108行）
private fun handleSessionStatus(event: SseEvent.SessionStatus) {
    _sessionStatuses.update { it + (event.sessionId to event.status) }
    _sseTimestamps.update { it + (event.sessionId to System.currentTimeMillis()) }
}

// handleSessionIdle（约第110-113行）
private fun handleSessionIdle(event: SseEvent.SessionIdle) {
    _sessionStatuses.update { it + (event.sessionId to SessionStatus.Idle) }
    _sseTimestamps.update { it + (event.sessionId to System.currentTimeMillis()) }
}
```

- [ ] **Step 3: 修改 `updateAllSessionStatuses` 增加「不降级」逻辑**

替换原有方法：

```kotlin
/**
 * Batch-update session statuses from REST data.
 * Protective: won't downgrade Busy/Retry to Idle if SSE updated the status
 * within [sseFreshThresholdMs] milliseconds ago.
 */
fun updateAllSessionStatuses(statuses: Map<String, SessionStatus>) {
    val now = System.currentTimeMillis()
    val timestamps = _sseTimestamps.value
    _sessionStatuses.update { current ->
        val merged = current.toMutableMap()
        for ((sessionId, newStatus) in statuses) {
            val existing = current[sessionId]
            if (shouldOverwrite(existing, newStatus, timestamps[sessionId], now)) {
                merged[sessionId] = newStatus
            }
        }
        merged.toMap()
    }
    if (BuildConfig.DEBUG) Log.d(TAG, "Batch updated ${statuses.size} session statuses (protected)")
}

companion object {
    private const val TAG = "SessionEventHandler"
    /** SSE status is considered fresh within this window. REST won't overwrite. */
    private const val SSE_FRESH_THRESHOLD_MS = 5000L
}

/**
 * Determine if REST data should overwrite existing status.
 * Rules:
 * - No existing status → always overwrite (cold start)
 * - REST says Busy/Retry → always overwrite (upgrade)
 * - REST says Idle but SSE recently said Busy/Retry → don't overwrite (protect)
 * - REST says Idle and SSE data is stale (>5s) → overwrite (trust REST)
 */
private fun shouldOverwrite(
    existing: SessionStatus?,
    newStatus: SessionStatus,
    lastSseUpdate: Long?,
    now: Long
): Boolean {
    if (existing == null) return true  // Cold start, no data yet
    if (newStatus !is SessionStatus.Idle) return true  // REST says active, always trust
    // REST says Idle. Check if SSE recently said active.
    if (existing !is SessionStatus.Idle && lastSseUpdate != null) {
        val age = now - lastSseUpdate
        if (age < SSE_FRESH_THRESHOLD_MS) {
            Log.d(TAG, "Protecting ${existing::class.simpleName} status (SSE age=${age}ms < ${SSE_FRESH_THRESHOLD_MS}ms)")
            return false
        }
    }
    return true
}
```

- [ ] **Step 4: 在 `handleSessionCreated` 中也更新时间戳**

```kotlin
// handleSessionCreated（约第82行）
private fun handleSessionCreated(event: SseEvent.SessionCreated) {
    // ... existing code ...
    _sseTimestamps.update { it + (event.session.id to System.currentTimeMillis()) }
}
```

- [ ] **Step 5: 在清理方法中清除时间戳**

```kotlin
// clearForServer — 在现有清除逻辑中添加时间戳清除
// 在 _sessionStatuses.update 之后添加：
_sseTimestamps.update { current ->
    current.filterKeys { sid ->
        // 保留不属于该 server 的 session 时间戳
        // 需要从 _sessions 中判断，简化处理：只清除已有的
        true // 下面的 clearAll 会全部清除
    }
}

// clearAll — 添加时间戳清除
fun clearAll() {
    // ... existing code ...
    _sseTimestamps.value = emptyMap()
}
```

- [ ] **Step 6: 验证编译**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/data/repository/handler/SessionEventHandler.kt
git commit -m "fix: protect SSE status from REST downgrade — add shouldOverwrite + SSE timestamp tracking"
```

---

## Task 2: EventDispatcher — CommandExecuted 不再无条件设 Idle

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/data/repository/EventDispatcher.kt`

**目标**：`CommandExecuted` 事件不应强制将 session 设为 Idle，因为 agent 可能在下一个 loop iteration 立即变回 Busy。改为让 SSE `session.status` 事件控制状态。

- [ ] **Step 1: 移除 CommandExecuted 的强制 Idle 逻辑**

在 `processEvent` 方法中（约第68-72行），修改 `CommandExecuted` 处理：

```kotlin
// 替换：
if (event is SseEvent.CommandExecuted) {
    event.sessionId.let { sid ->
        sessionHandler.updateSessionStatus(sid, SessionStatus.Idle)
    }
}

// 改为：
if (event is SseEvent.CommandExecuted) {
    // Don't force Idle here — the server will send session.status event
    // if the session actually becomes idle. Forcing Idle here causes
    // flickering when the agent continues to the next tool call.
    // Only mark messages as completed (streaming → done).
    messageHandler.markSessionIdle(event.sessionId)
}
```

**注意**：`markSessionIdle` 的调用仍然保留在 `EventDispatcher` 的 `markSessionIdle` 方法中（第141-144行），那里同时做 `messageHandler.markSessionIdle` + `sessionHandler.updateSessionStatus(Idle)`。`CommandExecuted` 只需关心消息完成，不关心 session 状态。session 状态由 `session.status` SSE 事件控制。

- [ ] **Step 2: 验证编译**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/data/repository/EventDispatcher.kt
git commit -m "fix: CommandExecuted no longer forces Idle — let SSE session.status control state"
```

---

## Task 3: SseConnectionManager — syncSessionStatuses 区分首次 vs 重连

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/service/SseConnectionManager.kt`

**目标**：首次连接时 REST 作为权威源（全量覆盖），重连时 REST 作为兜底（不降级保护）。

- [ ] **Step 1: 给 `syncSessionStatuses` 添加参数区分首次/重连**

```kotlin
// 修改方法签名（约第257行）：
private suspend fun syncSessionStatuses(conn: ServerConnection, isInitialSync: Boolean = false) {
    val result = api.fetchSessionStatus(conn)
    result.onSuccess { statuses ->
        val statusMap = statuses.mapValues { (_, info) ->
            when (info.type) {
                "busy" -> SessionStatus.Busy
                "retry" -> SessionStatus.Retry(
                    attempt = info.attempt ?: 0,
                    message = info.message ?: "",
                    next = info.next ?: 0L
                )
                else -> SessionStatus.Idle
            }
        }
        if (isInitialSync) {
            // 首次连接：REST 为权威源，无条件写入
            eventDispatcher.syncAllSessionStatuses(statusMap)
        } else {
            // 重连/恢复：REST 为兜底，不降级 SSE 状态
            eventDispatcher.syncAllSessionStatuses(statusMap)
            // Task 1 的 shouldOverwrite 保护已内置于 updateAllSessionStatuses
        }
        // 只对真正 idle 的 session 执行消息完成修正
        for ((sessionId, status) in statusMap) {
            if (status is SessionStatus.Idle) {
                eventDispatcher.markSessionIdle(sessionId)
            }
        }
    }
}
```

**注意**：`isInitialSync` 参数目前只是语义标记，实际保护逻辑已在 Task 1 的 `shouldOverwrite` 中实现。首次连接时由于没有 SSE 时间戳（`_sseTimestamps` 为空），`shouldOverwrite` 会返回 `true`（cold start 分支）。重连时由于有 SSE 时间戳，保护机制生效。

- [ ] **Step 2: 调用处传入 `isInitialSync`**

```kotlin
// preLoadSessions 中（约第220行）：
syncSessionStatuses(conn, isInitialSync = true)

// recoverMessages 中（约第250行）：
syncSessionStatuses(conn, isInitialSync = false)
```

- [ ] **Step 3: 验证编译**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/service/SseConnectionManager.kt
git commit -m "fix: syncSessionStatuses distinguishes initial vs reconnect sync"
```

---

## Task 4: ChatViewModel — syncSessionStatus 使用保护逻辑

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt`

**目标**：`syncSessionStatus` 中的 `markSessionIdle` 调用也需要考虑 SSE 时间戳保护。

- [ ] **Step 1: 修改 `syncSessionStatus` 方法**

```kotlin
// 修改 syncSessionStatus 方法（约第648-673行）：
fun syncSessionStatus() {
    viewModelScope.launch {
        val result = api.fetchSessionStatus(conn)
        result.onSuccess { statuses ->
            val statusMap = statuses.mapValues { (_, info) ->
                when (info.type) {
                    "busy" -> SessionStatus.Busy
                    "retry" -> SessionStatus.Retry(
                        attempt = info.attempt ?: 0,
                        message = info.message ?: "",
                        next = info.next ?: 0L
                    )
                    else -> SessionStatus.Idle
                }
            }
            eventDispatcher.syncAllSessionStatuses(statusMap)
            // 只有 REST 明确说当前 session 是 idle，且 markIdle 不被保护拦截时，才修正消息
            val currentStatus = statusMap[sessionId]
            if (currentStatus == null || currentStatus is SessionStatus.Idle) {
                // markSessionIdle 会调用 updateSessionStatus(Idle)，
                // 但 Task 1 的保护逻辑已在 syncAllSessionStatuses 中生效
                // 这里额外做消息完成修正
                eventDispatcher.markSessionIdle(sessionId)
            }
        }
    }
}
```

**说明**：逻辑与之前相同，但 `syncAllSessionStatuses` 内部的 `shouldOverwrite` 保护会阻止将 SSE 刚更新的 Busy 降级为 Idle。`markSessionIdle` 仍然会被调用（修正流式消息），但 `updateSessionStatus(Idle)` 部分会被保护拦截。

**但这里有个问题**：`markSessionIdle` 同时做两件事——修正消息 + 设状态。如果保护机制阻止了状态变更，消息修正也不应该执行吗？

**答案**：不应该。消息修正（标记 streaming 消息为 completed）是独立的，与状态显示无关。即使状态保持 Busy（因为 SSE 保护），消息层面仍需修正（避免消息永远显示 "thinking"）。所以 `markSessionIdle` 应该拆分。

- [ ] **Step 2: 拆分 EventDispatcher.markSessionIdle**

修改 `EventDispatcher.markSessionIdle`（约第141-144行）：

```kotlin
// 保持现有方法不变（同时做消息修正 + 状态变更）：
fun markSessionIdle(sessionId: String) {
    messageHandler.markSessionIdle(sessionId)
    sessionHandler.updateSessionStatus(sessionId, SessionStatus.Idle)
}
```

不拆分。理由：`sessionHandler.updateSessionStatus` 不经过 `shouldOverwrite` 保护（它是直接更新，不走 `updateAllSessionStatuses`），所以需要单独处理。

**修正方案**：在 `SessionEventHandler` 中新增保护版本的 `updateSessionStatus`：

- [ ] **Step 3: SessionEventHandler 增加保护的 updateSessionStatus**

```kotlin
// 在 updateSessionStatus 方法（约第141行）之后新增：
/**
 * Update a single session's status with SSE-freshness protection.
 * Won't overwrite Busy/Retry with Idle if SSE updated it recently.
 */
fun updateSessionStatusProtected(sessionId: String, newStatus: SessionStatus) {
    val now = System.currentTimeMillis()
    val existing = _sessionStatuses.value[sessionId]
    val lastSseUpdate = _sseTimestamps.value[sessionId]
    if (shouldOverwrite(existing, newStatus, lastSseUpdate, now)) {
        _sessionStatuses.update { it + (sessionId to newStatus) }
    }
}
```

- [ ] **Step 4: EventDispatcher 新增保护的 markSessionIdle**

```kotlin
// 在 markSessionIdle 方法之后新增：
fun markSessionIdleProtected(sessionId: String) {
    messageHandler.markSessionIdle(sessionId)
    sessionHandler.updateSessionStatusProtected(sessionId, SessionStatus.Idle)
}
```

- [ ] **Step 5: ChatViewModel 使用保护的版本**

```kotlin
// 修改 syncSessionStatus 中的调用：
fun syncSessionStatus() {
    viewModelScope.launch {
        val result = api.fetchSessionStatus(conn)
        result.onSuccess { statuses ->
            val statusMap = statuses.mapValues { (_, info) ->
                when (info.type) {
                    "busy" -> SessionStatus.Busy
                    "retry" -> SessionStatus.Retry(
                        attempt = info.attempt ?: 0,
                        message = info.message ?: "",
                        next = info.next ?: 0L
                    )
                    else -> SessionStatus.Idle
                }
            }
            eventDispatcher.syncAllSessionStatuses(statusMap)
            val currentStatus = statusMap[sessionId]
            if (currentStatus == null || currentStatus is SessionStatus.Idle) {
                eventDispatcher.markSessionIdleProtected(sessionId)
            }
        }
    }
}
```

- [ ] **Step 6: SseConnectionManager 也使用保护的版本**

```kotlin
// 修改 syncSessionStatuses 中的 markSessionIdle 调用（约第277-280行）：
for ((sessionId, status) in statusMap) {
    if (status is SessionStatus.Idle) {
        eventDispatcher.markSessionIdleProtected(sessionId)
    }
}
```

- [ ] **Step 7: 验证编译**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/data/repository/handler/SessionEventHandler.kt app/src/main/kotlin/dev/minios/ocremote/data/repository/EventDispatcher.kt app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt app/src/main/kotlin/dev/minios/ocremote/service/SseConnectionManager.kt
git commit -m "fix: use protected markSessionIdle in REST sync paths"
```

---

## Task 5: 真机验证 + 诊断日志

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/data/repository/handler/SessionEventHandler.kt`

**目标**：添加临时诊断日志，在真机上确认闪烁原因和保护机制是否生效。

- [ ] **Step 1: 在 shouldOverwrite 中添加详细日志**

```kotlin
private fun shouldOverwrite(
    existing: SessionStatus?,
    newStatus: SessionStatus,
    lastSseUpdate: Long?,
    now: Long
): Boolean {
    if (existing == null) {
        Log.d(TAG, "shouldOverwrite: no existing status → overwrite with $newStatus")
        return true
    }
    if (newStatus !is SessionStatus.Idle) {
        Log.d(TAG, "shouldOverwrite: REST says $newStatus → overwrite (upgrade)")
        return true
    }
    if (existing !is SessionStatus.Idle && lastSseUpdate != null) {
        val age = now - lastSseUpdate
        if (age < SSE_FRESH_THRESHOLD_MS) {
            Log.d(TAG, "shouldOverwrite: PROTECTED $existing (SSE age=${age}ms < ${SSE_FRESH_THRESHOLD_MS}ms)")
            return false
        }
        Log.d(TAG, "shouldOverwrite: REST Idle overwrites $existing (SSE age=${age}ms >= ${SSE_FRESH_THRESHOLD_MS}ms)")
        return true
    }
    Log.d(TAG, "shouldOverwrite: REST Idle, existing Idle → overwrite")
    return true
}
```

- [ ] **Step 2: 编译安装到真机测试**

Run: `.\gradlew :app:assembleDevDebug`
Install on device and test:
1. 打开目录树页面
2. 启动一个 agent 执行多步骤任务
3. 观察目录树中会话状态是否稳定显示 Busy
4. 查看 logcat 中 `SessionEventHandler` 标签的日志，确认保护机制是否触发

- [ ] **Step 3: 确认后移除诊断日志（或保留为 Debug 级别）**

诊断日志已使用 `Log.d`（Debug 级别），Release 构建由 ProGuard 可选择性移除。可暂时保留。

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/data/repository/handler/SessionEventHandler.kt
git commit -m "debug: add diagnostic logs for session status overwrite protection"
```

---

## 验收标准

1. **目录树页面**：Agent 执行多步骤任务时，会话状态稳定显示 Busy，不闪烁
2. **Agent 完成后**：状态正确切换为 Idle（SSE `session.idle` 事件触发）
3. **SSE 重连后**：状态正确恢复（REST 同步 + 保护机制协调）
4. **冷启动**：首次加载时状态正确初始化（无 SSE 时间戳，REST 直接写入）
5. **用户手动中止**：立即变为 Idle（`abortSession` 乐观更新，不受保护限制）
