# 会话状态同步修复 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 彻底修复会话运行状态不同步（P0-3）和 SSE 重连后消息丢失（P0-4）问题。

**Architecture:** 建立「REST 初始化 + SSE 实时驱动」双层机制。REST 用于冷启动/重连/恢复时的状态同步和消息恢复（全量覆盖），SSE 用于后续实时更新。一次 REST `fetchSessionStatus` 调用的全量数据不浪费，批量写入所有会话状态。

**Tech Stack:** Kotlin, Jetpack Compose, StateFlow, Hilt, Ktor

**Spec:** `docs/superpowers/specs/2026-06-03-session-status-sync-design.md`

---

## File Structure

| 文件 | 改动类型 | 职责 |
|------|---------|------|
| `data/repository/handler/MessageEventHandler.kt` | 新增方法 | `replaceMessages()` — REST 数据直接覆盖本地消息和 parts |
| `data/repository/handler/SessionEventHandler.kt` | 新增方法 | `updateAllSessionStatuses()` — 批量写入所有会话状态 |
| `data/repository/EventDispatcher.kt` | 新增方法 | `replaceMessages()` + `syncAllSessionStatuses()` 代理 |
| `service/SseConnectionManager.kt` | 重写/修改方法 | `recoverMessages()` 重写 + `preLoadSessions()` 追加状态初始化 |
| `ui/screens/chat/ChatViewModel.kt` | 修改方法 | `syncSessionStatus()` — 全量写入 + 移除 `_isLoading` 副作用 |
| `ui/screens/sessions/SessionListViewModel.kt` | 修改方法 | `refreshSessions()` + `loadSessions()` 追加状态同步 |

---

## Task 1: 新增 `replaceMessages()` — MessageEventHandler

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/data/repository/handler/MessageEventHandler.kt`

- [ ] **Step 1: 在 `clearForSession()` 方法之前（约行 118），新增 `replaceMessages()` 方法**

```kotlin
    /**
     * Replace all messages and parts for a session with REST data.
     * Unlike [mergeMessages], this treats REST as the source of truth,
     * overwriting any existing local data. Used for SSE reconnection recovery.
     */
    fun replaceMessages(sessionId: String, newMessages: List<MessageWithParts>) {
        _messages.update {
            it + (sessionId to newMessages.map { m -> m.info }.sortedBy { m -> m.time.created })
        }
        val partsMap = newMessages.associate { it.info.id to it.parts }
        _parts.update { it + partsMap }
    }
```

- [ ] **Step 2: 验证编译**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/data/repository/handler/MessageEventHandler.kt
git commit -m "feat: add replaceMessages() for REST-based message recovery"
```

---

## Task 2: 新增 `updateAllSessionStatuses()` — SessionEventHandler

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/data/repository/handler/SessionEventHandler.kt`

- [ ] **Step 1: 在 `updateSessionStatus()` 方法之后（约行 147），新增 `updateAllSessionStatuses()` 方法**

```kotlin
    /**
     * Batch-update session statuses from REST data.
     * Used when fetchSessionStatus returns all session states at once.
     */
    fun updateAllSessionStatuses(statuses: Map<String, SessionStatus>) {
        _sessionStatuses.update { current ->
            current + statuses
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "Batch updated ${statuses.size} session statuses")
    }
```

- [ ] **Step 2: 验证编译**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/data/repository/handler/SessionEventHandler.kt
git commit -m "feat: add updateAllSessionStatuses() for batch status sync"
```

---

## Task 3: 新增代理方法 — EventDispatcher

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/data/repository/EventDispatcher.kt`

- [ ] **Step 1: 在 `mergeMessages()` 代理方法之后（约行 106），新增两个代理方法**

```kotlin
    fun replaceMessages(sessionId: String, messages: List<MessageWithParts>) =
        messageHandler.replaceMessages(sessionId, messages)

    fun syncAllSessionStatuses(statuses: Map<String, SessionStatus>) =
        sessionHandler.updateAllSessionStatuses(statuses)
```

- [ ] **Step 2: 验证编译**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/data/repository/EventDispatcher.kt
git commit -m "feat: add replaceMessages + syncAllSessionStatuses proxies"
```

---

## Task 4: 重写 `recoverMessages()` + 修改 `preLoadSessions()` — SseConnectionManager

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/service/SseConnectionManager.kt`

- [ ] **Step 1: 重写 `recoverMessages()` 方法（行 228–245）**

替换整个方法体为：

```kotlin
    /**
     * Recover messages for all active sessions of a server after SSE reconnection.
     * Phase 1: Replace messages with REST data (source of truth).
     * Phase 2: Sync session statuses from server — only mark idle sessions as idle.
     */
    private suspend fun recoverMessages(server: ServerConfig, conn: ServerConnection) {
        val sessionIds = eventDispatcher.serverSessions.value[server.id] ?: return
        if (sessionIds.isEmpty()) return

        // Phase 1: Recover messages (REST as source of truth)
        Log.i(TAG, "[${server.displayName}] Recovering messages for ${sessionIds.size} sessions")
        var recoveredCount = 0
        for (sessionId in sessionIds) {
            try {
                val messages = api.listMessages(conn, sessionId)
                eventDispatcher.replaceMessages(sessionId, messages)
                recoveredCount++
            } catch (e: Exception) {
                Log.w(TAG, "[${server.displayName}] Failed to recover messages for session $sessionId: ${e.message}")
            }
        }
        Log.i(TAG, "[${server.displayName}] Recovered messages for $recoveredCount/${sessionIds.size} sessions")

        // Phase 2: Sync real session statuses from server
        syncSessionStatuses(conn)
    }
```

- [ ] **Step 2: 在 `recoverMessages()` 方法之后，新增 `syncSessionStatuses()` 私有方法**

```kotlin
    /**
     * Fetch real session statuses from REST API and update the dispatcher.
     * Only marks sessions as idle when the server confirms they are idle.
     */
    private suspend fun syncSessionStatuses(conn: ServerConnection) {
        try {
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

                // Only mark truly idle sessions with message completion fix
                for ((sessionId, status) in statusMap) {
                    if (status is SessionStatus.Idle) {
                        eventDispatcher.markSessionIdle(sessionId)
                    }
                }
                Log.i(TAG, "Synced statuses for ${statusMap.size} sessions from REST")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sync session statuses: ${e.message}")
        }
    }
```

- [ ] **Step 3: 修改 `preLoadSessions()` — 在方法末尾的 `} catch` 之前追加状态同步调用**

当前 `preLoadSessions()` 的结构是：
```kotlin
    private suspend fun preLoadSessions(server: ServerConfig, conn: ServerConnection) {
        try {
            // ... 加载 sessions 逻辑 ...
        } catch (e: Exception) {
            Log.w(TAG, "...Failed to pre-load sessions: ${e.message}")
        }
    }
```

在 `} catch (e: Exception) {` 这行之前（即 try 块的最后一行之后），追加：

```kotlin
            // Initialize session statuses from server
            syncSessionStatuses(conn)
```

修改后完整方法：
```kotlin
    private suspend fun preLoadSessions(server: ServerConfig, conn: ServerConnection) {
        try {
            val projects = api.listProjects(conn)
            if (projects.isEmpty()) {
                // Fallback: load sessions without directory header (server CWD only)
                val sessions = api.listSessions(conn)
                eventDispatcher.setSessions(server.id, sessions)
                Log.i(TAG, "[${server.displayName}] Pre-loaded ${sessions.size} sessions (no projects)")
            } else {
                var totalSessions = 0
                for (project in projects) {
                    try {
                        val sessions = api.listSessions(conn, directory = project.worktree)
                        eventDispatcher.setSessions(server.id, sessions)
                        totalSessions += sessions.size
                    } catch (e: Exception) {
                        Log.w(TAG, "[${server.displayName}] Failed to pre-load sessions for project ${project.displayName}: ${e.message}")
                    }
                }
                Log.i(TAG, "[${server.displayName}] Pre-loaded $totalSessions sessions across ${projects.size} projects")
            }
            // Initialize session statuses from server
            syncSessionStatuses(conn)
        } catch (e: Exception) {
            Log.w(TAG, "[${server.displayName}] Failed to pre-load sessions: ${e.message}")
        }
    }
```

- [ ] **Step 4: 确认 `SessionStatus` 的 import 存在**

`SseConnectionManager.kt` 文件顶部需要 import `dev.minios.ocremote.domain.model.SessionStatus`。检查是否已有此 import，如没有则添加。

- [ ] **Step 5: 验证编译**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/service/SseConnectionManager.kt
git commit -m "fix: rewrite recoverMessages + preLoadSessions with real status sync"
```

---

## Task 5: 改进 `syncSessionStatus()` — ChatViewModel

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt`

- [ ] **Step 1: 替换 `syncSessionStatus()` 方法（行 645–665）**

替换为：

```kotlin
    /**
     * Query the OpenCode server for actual session statuses and correct
     * any UI state drift caused by missed SSE events.
     *
     * Writes ALL session statuses from REST response (not just current session).
     * Only marks the current session's messages as completed if it's truly idle.
     *
     * Triggered on:
     * - Entering a session (LaunchedEffect(sessionId))
     * - Resuming from background (DisposableEffect ON_RESUME)
     */
    fun syncSessionStatus() {
        viewModelScope.launch {
            val result = api.fetchSessionStatus(conn)
            result.onSuccess { statuses ->
                // Batch-update ALL session statuses (one REST call, all sessions)
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

                // Only mark current session messages as completed if truly idle
                val currentStatus = statusMap[sessionId]
                if (currentStatus == null || currentStatus is SessionStatus.Idle) {
                    eventDispatcher.markSessionIdle(sessionId)
                }
            }
        }
    }
```

**关键变化**:
- `statuses[sessionId] ?: return@onSuccess` → `statuses.mapValues { ... }` 全量转换
- `eventDispatcher.updateSessionStatus(sessionId, ...)` → `eventDispatcher.syncAllSessionStatuses(statusMap)` 全量写入
- 移除 `_isLoading.value = false`（不归此方法管）

- [ ] **Step 2: 验证编译**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt
git commit -m "fix: syncSessionStatus writes all session statuses, removes _isLoading side effect"
```

---

## Task 6: 新增状态同步 — SessionListViewModel

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListViewModel.kt`

- [ ] **Step 1: 在 `refreshSessions()` 方法的 try 块末尾（行 239，`} catch` 之前）追加状态同步**

当前 `refreshSessions()` try 块末尾是空行（行 240），在 `} catch` 之前追加：

```kotlin
                // Sync session statuses from server
                syncSessionStatusesFromServer()
```

- [ ] **Step 2: 在 `loadSessions()` 方法的 try 块中，所有 session 加载完成之后（行 212 附近，`} catch` 之前的 `}` 之前），追加状态同步**

在 `} catch (e: Exception) {` 之前（即 try 块的最后一行），追加：

```kotlin
                // Sync session statuses from server
                syncSessionStatusesFromServer()
```

注意：`loadSessions()` 中 `finally` 块处理 `_isLoading` 和 `_expandedPaths`，`syncSessionStatusesFromServer()` 应放在 `finally` 之前的 try 块中。

查看 `loadSessions()` 结构：
```
try {
    val projects = ...
    _projects.value = projects
    if (projects.isEmpty()) { ... } else { ... }
    // ← 在这里追加 syncSessionStatusesFromServer()
} catch (e: Exception) {  // 行 195
    ...
} finally {               // 行 200
    ...
}
```

- [ ] **Step 3: 在类中新增 `syncSessionStatusesFromServer()` 私有方法**

在 `refreshSessions()` 方法之后（约行 243 之后）新增：

```kotlin
    /**
     * Sync session statuses from server via REST API.
     * Batch-updates all session statuses so the session list shows correct
     * busy/idle/retry states even after cold start or background recovery.
     */
    private suspend fun syncSessionStatusesFromServer() {
        try {
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
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sync session statuses: ${e.message}")
        }
    }
```

- [ ] **Step 4: 确认 `SessionStatus` 的 import 存在**

`SessionListViewModel.kt` 文件顶部需要 import `dev.minios.ocremote.domain.model.SessionStatus`。检查是否已有此 import，如没有则添加。

- [ ] **Step 5: 验证编译**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListViewModel.kt
git commit -m "feat: add session status sync to SessionListViewModel"
```

---

## Task 7: 最终编译验证 + Bump 版本 + 发版

**Files:**
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: 完整编译 Beta Release**

Run: `.\gradlew :app:assembleBetaRelease`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Bump 版本**

在 `app/build.gradle.kts` 中：
- `versionCode`: 342 → 343
- `versionName`: `"2.0.0-beta.142"` → `"2.0.0-beta.143"`

- [ ] **Step 3: Commit + Tag + Push**

```bash
git add app/build.gradle.kts
git commit -m "release: v2.0.0-beta.143"
git tag v2.0.0-beta.143
git push origin master
git push origin v2.0.0-beta.143
```

- [ ] **Step 4: 创建 GitHub Release**

```bash
gh release create v2.0.0-beta.143 --title "v2.0.0-beta.143" --notes "## Bug Fixes

### P0-3: 会话运行状态彻底修复
- **根因**: SSE 重连后盲目将所有会话标为 Idle；冷启动/会话列表页无状态校正
- **修复**: 
  - SSE 重连后通过 REST 查询真实状态，只对真正 idle 的会话标记完成
  - 冷启动时初始化所有会话状态
  - 进入会话/后台恢复时全量同步所有会话状态
  - 会话列表页首次加载和下拉刷新时同步状态
  - 移除 syncSessionStatus 对 _isLoading 的副作用

### P0-4: SSE 重连后消息丢失彻底修复
- **根因**: mergeMessages 保留本地旧数据，丢弃 REST 新数据
- **修复**: 新增 replaceMessages，REST 返回的完整数据直接覆盖本地" --prerelease "app\build\outputs\apk\beta\release\app-beta-release.apk#app-beta-release.apk"
```
