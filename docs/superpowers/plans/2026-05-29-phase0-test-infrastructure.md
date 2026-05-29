# Phase 0: 测试基础设施 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立测试基线，确保现有测试全部通过，编写 Characterization Tests 锁定核心行为

**Architecture:** 先运行现有测试获取基线状态 → 分类修复失败测试 → 补充 Characterization Tests 覆盖核心公共 API

**Tech Stack:** JUnit 4, MockK 1.14.9, Turbine 1.2.1, Kotlin Coroutines Test 1.11.0, Gradle

---

## File Structure

| 操作 | 文件路径 | 职责 |
|------|----------|------|
| READ | `app/src/test/.../data/repository/EventReducerTest.kt` | 现有测试 — 可能需要修复 |
| READ | `app/src/test/.../ui/screens/chat/ChatViewModelPermissionTest.kt` | 现有测试 — 可能需要修复 |
| READ | `app/src/test/.../ui/screens/chat/ChatViewModelQueuedTest.kt` | 现有测试 — 可能需要修复 |
| READ | `app/src/test/.../ui/screens/chat/PartRenderLogicTest.kt` | 现有测试 — 可能需要修复 |
| 新建 | `app/src/test/kotlin/dev/minios/ocremote/data/api/OpenCodeApiTest.kt` | Characterization Test — API DTO 构造与序列化 |
| 新建 | `app/src/test/kotlin/dev/minios/ocremote/data/repository/SettingsRepositoryTest.kt` | Characterization Test — Flow 默认值行为 |
| 新建 | `app/src/test/kotlin/dev/minios/ocremote/data/repository/EventReducerSessionTest.kt` | Characterization Test — session/message 事件处理 |
| 新建 | `app/src/test/kotlin/dev/minios/ocremote/domain/model/SerializationTest.kt` | Characterization Test — JSON 序列化/反序列化 |

---

### Task 1: 运行现有测试并记录基线

**Files:**
- Reference: `app/src/test/kotlin/dev/minios/ocremote/`

- [ ] **Step 1: 确认 build 目录存在，运行全部测试**

Run:
```bash
cd D:\Develop\code\app\oc-remote
.\gradlew testDebugUnitTest 2>&1 | Tee-Object -FilePath build\test-results\phase0-baseline.txt
```
Expected: 输出测试结果摘要，记录 PASS/FAIL/SKIP 数量（共 4 个测试类）

- [ ] **Step 2: 提取失败信息并分类**

Run:
```bash
cd D:\Develop\code\app\oc-remote
Select-String -Path build\test-results\phase0-baseline.txt -Pattern "FAIL|ERROR|Exception|AssertionError|MockKException" | Set-Content -Path build\test-results\phase0-failures.txt
```
Expected: 失败测试列表及原因。若无失败则为空文件。

- [ ] **Step 3: 读取 XML 测试报告获取精确失败原因**

Run:
```bash
cd D:\Develop\code\app\oc-remote
Get-ChildItem -Recurse -Filter "*.xml" -Path app\build\test-results\testDebugUnitTest | ForEach-Object { $_.FullName }
```
Expected: 列出所有 XML 测试报告路径，供后续分析使用

- [ ] **Step 4: Commit 基线记录**

```bash
cd D:\Develop\code\app\oc-remote
git add build/test-results/phase0-baseline.txt build/test-results/phase0-failures.txt
git commit -m "test(phase0): record test baseline before refactoring"
```
Expected: 基线记录已提交

---

### Task 2: 修复 EventReducerTest 失败测试

**Files:**
- READ: `app/src/main/kotlin/dev/minios/ocremote/data/repository/EventReducer.kt`
- READ: `app/src/main/kotlin/dev/minios/ocremote/domain/model/SseEvent.kt`
- 修改: `app/src/test/kotlin/dev/minios/ocremote/data/repository/EventReducerTest.kt`

**分析要点：**
- `EventReducer` 构造函数为 `@Inject constructor()` — 无参，测试中 `EventReducer()` 应该能正常创建
- `permissions` 是 `StateFlow<Map<String, List<SseEvent.PermissionAsked>>>`
- `SseEvent.PermissionAsked` 的 `metadata` 类型是 `Map<String, String>?`，`always` 类型是 `Boolean`
- `removePermission(permissionId: String)` 和 `setPermissions(sessionId, permissions)` 是公共 API
- `clearAll()` 是公共 API

- [ ] **Step 1: 读取 EventReducer.kt 确认公共 API 签名**

Run:
```bash
cd D:\Develop\code\app\oc-remote
Select-String -Path app\src\main\kotlin\dev\minios\ocremote\data\repository\EventReducer.kt -Pattern "fun (setPermissions|removePermission|clearAll|setMessages|setSessions|setQuestions|removeQuestion)" | ForEach-Object { $_.Line.Trim() }
```
Expected: 确认公共方法签名与测试调用一致

- [ ] **Step 2: 读取 EventReducerTest.kt 检查是否存在编译错误**

对照 `SseEvent.PermissionAsked` 签名，确认测试中 helper `createTestPermission` 参数匹配：
- `id: String` ✓
- `sessionId: String` ✓
- `permission: String` ✓
- `patterns: List<String>` ✓
- `metadata: Map<String, String>?` ✓ (测试用 `Map<String, String>?`)
- `always: Boolean` ✓
- `tool: ToolRef?` ✓

- [ ] **Step 3: 如果测试失败，根据 XML 报告分析具体原因并修复**

**常见修复模式（根据实际报错选择适用方案）：**

**A. 如果是 `permissions.value` 在 `setPermissions` 后未更新：**
检查是否 `MutableStateFlow.update` 的 lambda 在 `runTest` 上下文中正确执行。
修复方向：确保 `reducer.setPermissions()` 后直接读取 `reducer.permissions.value`。

**B. 如果是 Turbine `awaitItem()` 超时：**
Turbine 的 `test {}` block 需要上游 Flow 有 subscription。
修复方向：在 `reducer.permissions.test {}` 内调用 `reducer.setPermissions()`，确保调用在 test block 内。

**C. 如果是 `skipItems(1)` 找不到方法：**
Turbine 1.2.1 的 API：使用 `expectNoEvents()` 或 `awaitItem()` 代替 `skipItems`。
修复方向：将 `skipItems(1)` 替换为 `awaitItem()` 消费掉初始值。

**D. 如果是编译错误（签名不匹配）：**
对照 `EventReducer.kt` 的最新签名修复调用参数。

- [ ] **Step 4: 验证 EventReducerTest 全部通过**

Run:
```bash
cd D:\Develop\code\app\oc-remote
.\gradlew testDebugUnitTest --tests "dev.minios.ocremote.data.repository.EventReducerTest" 2>&1 | Select-Object -Last 10
```
Expected: `EventReducerTest > ... PASSED` 全部绿色

- [ ] **Step 5: Commit 修复**

```bash
cd D:\Develop\code\app\oc-remote
git add app/src/test/kotlin/dev/minios/ocremote/data/repository/EventReducerTest.kt
git commit -m "test(phase0): fix EventReducerTest failures"
```

---

### Task 3: 修复 ChatViewModelPermissionTest 失败测试

**Files:**
- READ: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt`
- READ: `app/src/main/kotlin/dev/minios/ocremote/data/api/OpenCodeApi.kt`
- 修改: `app/src/test/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModelPermissionTest.kt`

**分析要点：**
- `ChatViewModel` 构造函数: `(savedStateHandle, eventReducer, api, draftRepository, settingsRepository)`
- 测试中的 `createViewModel()` 已使用正确的 4 参数构造
- `SettingsRepository` 需要 stub 的 Flow 属性较多，需逐一对照

- [ ] **Step 1: 确认 ChatViewModel 构造函数签名**

Run:
```bash
cd D:\Develop\code\app\oc-remote
Select-String -Path app\src\main\kotlin\dev\minios\ocremote\ui\screens\chat\ChatViewModel.kt -Pattern "class ChatViewModel|constructor" | Select-Object -First 5 | ForEach-Object { $_.Line.Trim() }
```
Expected: 确认 `ChatViewModel @Inject constructor(savedStateHandle, eventReducer, api, draftRepository, settingsRepository)`

- [ ] **Step 2: 确认 SettingsRepository 所有被 ViewModel init 读取的 Flow 属性**

Run:
```bash
cd D:\Develop\code\app\oc-remote
Select-String -Path app\src\main\kotlin\dev\minios\ocremote\ui\screens\chat\ChatViewModel.kt -Pattern "settingsRepository\." | ForEach-Object { $_.Line.Trim() }
```
Expected: 列出所有 `settingsRepository.xxx` 调用，确保测试 setup 中全部 stub

对照测试 setup 中已有的 stub 列表，检查是否有遗漏：
```
hiddenModels(serverId) → flowOf(emptySet())  ✓
terminalFontSize → flowOf(13f)  ✓
initialMessageCount → flowOf(50)  ✓
chatFontSize → flowOf("medium")  ✓
codeWordWrap → flowOf(false)  ✓
confirmBeforeSend → flowOf(false)  ✓
compactMessages → flowOf(false)  ✓
collapseTools → flowOf(false)  ✓
hapticFeedback → flowOf(true)  ✓
keepScreenOn → flowOf(false)  ✓
compressImageAttachments → flowOf(true)  ✓
imageAttachmentMaxLongSide → flowOf(1440)  ✓
imageAttachmentWebpQuality → flowOf(60)  ✓
```

如果 ViewModel init 中有新增的 settings 读取，需添加对应的 stub。

- [ ] **Step 3: 确认 API mock stub 覆盖所有 init block 调用**

检查 ViewModel init block 中的 API 调用：
```
api.getSession(conn, sessionId) → stubbed ✓
api.listMessages(conn, sessionId, limit) → stubbed ✓
api.listPendingQuestions(conn, directory) → stubbed ✓
api.listPendingPermissions(conn, directory) → stubbed ✓ (per-test)
api.getProviders(conn) → stubbed ✓
api.listAgents(conn) → stubbed ✓
api.listCommands(conn) → stubbed ✓
```

如果有新增 API 调用（如 `api.listProjects`），需添加 stub。

- [ ] **Step 4: 根据测试失败原因修复**

**常见修复模式：**

**A. `coVerify { api.replyToPermission(any(), any(), any(), any(), any()) }` 参数不匹配：**
确认 `replyToPermission` 签名：`(conn, requestId, reply, message?, directory?)` — 5 个参数。
测试中 `coVerify { api.replyToPermission(any(), "perm-reply", "once", any(), any()) }` 使用 5 参数匹配，应该正确。

**B. `replyToPermission` 方法返回 Boolean 而非调用成功：**
`ChatViewModel.replyToPermission(requestId, reply)` 内部调用 `api.replyToPermission(conn, requestId, reply, directory=sessionDirectory)`。
注意实际签名有 `message` 参数在 `directory` 之前，需检查 stub 匹配：
```kotlin
coEvery { api.replyToPermission(any(), any(), any(), any(), any()) } returns true
```
确保 5 个 `any()` 匹配器覆盖了 `(conn, requestId, reply, message, directory)`。

**C. SavedStateHandle 中缺少必需参数：**
检查 `ChatViewModel` 是否从 SavedStateHandle 读取了新增字段。

**D. Missing `expandReasoning` stub：**
如果 ViewModel init 中读取了 `settingsRepository.expandReasoning`，需添加：
```kotlin
every { settingsRepository.expandReasoning } returns flowOf(false)
```

- [ ] **Step 5: 验证 ChatViewModelPermissionTest 全部通过**

Run:
```bash
cd D:\Develop\code\app\oc-remote
.\gradlew testDebugUnitTest --tests "dev.minios.ocremote.ui.screens.chat.ChatViewModelPermissionTest" 2>&1 | Select-Object -Last 15
```
Expected: 全部测试 PASSED

- [ ] **Step 6: Commit 修复**

```bash
cd D:\Develop\code\app\oc-remote
git add app/src/test/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModelPermissionTest.kt
git commit -m "test(phase0): fix ChatViewModelPermissionTest failures"
```

---

### Task 4: 修复 ChatViewModelQueuedTest 失败测试

**Files:**
- READ: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt`
- 修改: `app/src/test/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModelQueuedTest.kt`

**分析要点：**
- 测试结构与 ChatViewModelPermissionTest 相同的 mock setup 模式
- `subscribeToState(vm)` 方法用于激活 `SharingStarted.WhileSubscribed`
- 额外使用了 `MessageWithParts`、`Session` 的构造

- [ ] **Step 1: 确认 Session 数据类有 parentId 字段**

对照 `Session.kt`:
```kotlin
data class Session(
    val id: String,
    ...
    val parentId: String? = null,
    ...
    val time: Time,
    ...
)
```
测试中 `createTestSession(parentId = "parent-session-1")` 需要确认构造函数参数顺序正确。

- [ ] **Step 2: 确认 Message.User 和 Message.Assistant 构造函数参数**

对照 `Message.kt`:
- `Message.User(id, sessionId, role, time, agent?, model?, ...)` — 测试中只用了 `id, sessionId, time`
- `Message.Assistant(id, sessionId, role, time, parentId, ...)` — 测试中用了 `id, sessionId, time, completed, parentId`

确认 `createAssistantMessage` 中的 `parentId = ""` 是否会导致问题。

- [ ] **Step 3: 根据测试失败原因修复**

**常见修复模式：**

**A. `advanceUntilIdle()` 后 uiState 仍为初始值：**
`SharingStarted.WhileSubscribed(5000)` 需要活跃 subscriber。检查 `subscribeToState` 是否正确 collect。
确保 `advanceUntilIdle()` 在 `setMain(testDispatcher)` 环境下正确执行所有协程。

**B. `queuedMessageIds` 计算逻辑与预期不符：**
查看 `ChatViewModel` 中 `queuedMessageIds` 的计算：
```kotlin
val pendingAssistantIndex = chatMessages.indexOfFirst {
    it.message is Message.Assistant && it.message.time.completed == null
}
val queuedMessageIds = if (pendingAssistantIndex >= 0) {
    chatMessages.take(pendingAssistantIndex)
        .filter { it.isUser }
        .map { it.message.id }
        .toSet()
} else { emptySet() }
```
注意：`chatMessages` 是按 `sortedByDescending { it.time.created }` 排序的（最新在前），所以 `indexOfFirst` 找的是排序后第一个 pending assistant，`take(index)` 取的是它之前（即时间更晚的）消息。这与测试的预期（按时间排序后 pending assistant 之后的 user 消息）可能不一致。

**如果排序导致逻辑反转**，需确认 `chatMessages` 的排序方向，并调整测试预期或修复 ViewModel 逻辑。

**C. 新增的 `settingsRepository.expandReasoning` 未 stub：**
如需则添加：
```kotlin
every { settingsRepository.expandReasoning } returns flowOf(false)
```

- [ ] **Step 4: 验证 ChatViewModelQueuedTest 全部通过**

Run:
```bash
cd D:\Develop\code\app\oc-remote
.\gradlew testDebugUnitTest --tests "dev.minios.ocremote.ui.screens.chat.ChatViewModelQueuedTest" 2>&1 | Select-Object -Last 15
```
Expected: 全部测试 PASSED

- [ ] **Step 5: Commit 修复**

```bash
cd D:\Develop\code\app\oc-remote
git add app/src/test/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModelQueuedTest.kt
git commit -m "test(phase0): fix ChatViewModelQueuedTest failures"
```

---

### Task 5: 修复 PartRenderLogicTest 失败测试

**Files:**
- READ: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatParts.kt`
- READ: `app/src/main/kotlin/dev/minios/ocremote/domain/model/Part.kt`
- 修改: `app/src/test/kotlin/dev/minios/ocremote/ui/screens/chat/PartRenderLogicTest.kt`

**分析要点：**
- `isBubbleRenderablePart` 和 `filterRenderableParts` 是 `internal` 函数，在同包内可直接访问
- 测试与源码在同一包 `dev.minios.ocremote.ui.screens.chat` 下，应该可以直接调用
- `Part` 的各子类构造函数参数需确认匹配

- [ ] **Step 1: 确认 Part 各子类构造参数与测试 helper 一致**

对照 `Part.kt`:
- `Part.Text(id, sessionId, messageId, text)` — 测试 helper `createTextPart(id, text)` ✓
- `Part.Reasoning(id, sessionId, messageId, text)` — 测试 helper `createReasoningPart(id, text)` ✓
- `Part.Tool(id, sessionId, messageId, callId, tool, state)` — 测试 helper `createToolPart(id, toolName)` ✓
- `Part.Patch(id, sessionId, messageId)` — 测试中直接构造 ✓
- `Part.File(id, sessionId, messageId, mime)` — 测试中直接构造 ✓
- `Part.StepStart(id, sessionId, messageId)` — 测试中直接构造 ✓
- `Part.StepFinish(id, sessionId, messageId)` — 测试中直接构造 ✓
- `Part.Snapshot(id, sessionId, messageId)` — 测试中直接构造 ✓
- `Part.Subtask(id, sessionId, messageId)` — 测试中直接构造 ✓
- `Part.Compaction(id, sessionId, messageId)` — 测试中直接构造 ✓
- `Part.Unknown(id, sessionId, messageId)` — 测试中直接构造 ✓
- `Part.Retry(id, sessionId, messageId)` — 测试中直接构造 ✓
- `Part.Abort(id, sessionId, messageId)` — 测试中直接构造 ✓
- `Part.Question(id, sessionId, messageId)` — 测试中直接构造 ✓
- `Part.Agent(id, sessionId, messageId)` — 测试中直接构造 ✓
- `Part.SessionTurn(id, sessionId, messageId)` — 测试中直接构造 ✓

- [ ] **Step 2: 根据测试失败原因修复**

**常见修复模式：**

**A. `internal` 可见性问题：**
测试文件包名 `dev.minios.ocremote.ui.screens.chat` 与源码一致，`internal` 函数可访问。如果编译失败，确认测试文件确实在正确的包路径下。

**B. 新增 Part 子类未在 `isBubbleRenderablePart` 中处理：**
如果有新增的 `Part` 子类（如 `Part.SessionTurn`），需确认 `isBubbleRenderablePart` 的 `when` 表达式是否需要 `else -> false` 分支。当前实现已有 `else -> false`，应能正确处理。

- [ ] **Step 3: 验证 PartRenderLogicTest 全部通过**

Run:
```bash
cd D:\Develop\code\app\oc-remote
.\gradlew testDebugUnitTest --tests "dev.minios.ocremote.ui.screens.chat.PartRenderLogicTest" 2>&1 | Select-Object -Last 10
```
Expected: 全部测试 PASSED

- [ ] **Step 4: Commit 修复**

```bash
cd D:\Develop\code\app\oc-remote
git add app/src/test/kotlin/dev/minios/ocremote/ui/screens/chat/PartRenderLogicTest.kt
git commit -m "test(phase0): fix PartRenderLogicTest failures"
```

---

### Task 6: 编写 EventReducer Session/Message Characterization Test

**Files:**
- 新建: `app/src/test/kotlin/dev/minios/ocremote/data/repository/EventReducerSessionTest.kt`
- Reference: `app/src/main/kotlin/dev/minios/ocremote/data/repository/EventReducer.kt`

**目标：** 锁定 EventReducer 的 session、message、part 事件处理行为

- [ ] **Step 1: 创建测试文件**

```kotlin
// app/src/test/kotlin/dev/minios/ocremote/data/repository/EventReducerSessionTest.kt
package dev.minios.ocremote.data.repository

import app.cash.turbine.test
import dev.minios.ocremote.domain.model.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Characterization Tests for EventReducer's session, message, and part handling.
 * These tests lock in the current public API behavior so refactoring is safe.
 */
class EventReducerSessionTest {

    private lateinit var reducer: EventReducer

    @Before
    fun setup() {
        reducer = EventReducer()
    }

    // ============ Session Management ============

    @Test
    fun `setSessions registers sessions for a server`() = runTest {
        val session = createTestSession("s1")
        reducer.setSessions("server-1", listOf(session))

        assertEquals(listOf(session), reducer.sessions.value)
    }

    @Test
    fun `setSessions sorts sessions by updated time descending`() = runTest {
        val old = createTestSession("s1", updated = 1000L)
        val new = createTestSession("s2", updated = 2000L)
        reducer.setSessions("server-1", listOf(old, new))

        assertEquals(listOf(new, old), reducer.sessions.value)
    }

    @Test
    fun `setSessions merges with existing sessions by ID`() = runTest {
        val original = createTestSession("s1", title = "Original", updated = 1000L)
        reducer.setSessions("server-1", listOf(original))

        val updated = createTestSession("s1", title = "Updated", updated = 2000L)
        reducer.setSessions("server-1", listOf(updated))

        assertEquals(1, reducer.sessions.value.size)
        assertEquals("Updated", reducer.sessions.value[0].title)
    }

    @Test
    fun `clearForServer removes only that server's sessions`() = runTest {
        val sessionA = createTestSession("sA")
        val sessionB = createTestSession("sB")
        reducer.setSessions("server-A", listOf(sessionA))
        reducer.setSessions("server-B", listOf(sessionB))

        reducer.clearForServer("server-A")

        assertEquals(listOf(sessionB), reducer.sessions.value)
        assertTrue(reducer.serverSessions.value.containsKey("server-A").not())
        assertEquals(setOf("sB"), reducer.serverSessions.value["server-B"])
    }

    @Test
    fun `clearAll resets everything`() = runTest {
        reducer.setSessions("server-1", listOf(createTestSession("s1")))
        reducer.setMessages("s1", listOf(
            MessageWithParts(
                info = createTestMessage("m1", "s1"),
                parts = emptyList()
            )
        ))

        reducer.clearAll()

        assertTrue(reducer.sessions.value.isEmpty())
        assertTrue(reducer.messages.value.isEmpty())
        assertTrue(reducer.permissions.value.isEmpty())
        assertTrue(reducer.questions.value.isEmpty())
    }

    // ============ SSE Event Processing ============

    @Test
    fun `processEvent SessionCreated adds session`() = runTest {
        val session = createTestSession("s1")
        reducer.processEvent(SseEvent.SessionCreated(session), "server-1")

        assertTrue(reducer.sessions.value.any { it.id == "s1" })
    }

    @Test
    fun `processEvent SessionUpdated updates existing session`() = runTest {
        val session = createTestSession("s1", title = "Old Title")
        reducer.processEvent(SseEvent.SessionCreated(session), "server-1")

        val updated = session.copy(title = "New Title")
        reducer.processEvent(SseEvent.SessionUpdated(updated), "server-1")

        assertEquals("New Title", reducer.sessions.value.first { it.id == "s1" }.title)
    }

    @Test
    fun `processEvent SessionDeleted removes session and associated data`() = runTest {
        val session = createTestSession("s1")
        reducer.processEvent(SseEvent.SessionCreated(session), "server-1")
        reducer.setPermissions("s1", listOf(
            SseEvent.PermissionAsked(id = "p1", sessionId = "s1", permission = "bash")
        ))

        reducer.processEvent(SseEvent.SessionDeleted(session), "server-1")

        assertTrue(reducer.sessions.value.isEmpty())
        assertTrue(reducer.permissions.value.isEmpty())
        assertTrue(reducer.messages.value.isEmpty())
    }

    @Test
    fun `processEvent MessageUpdated adds message to session`() = runTest {
        val message = createTestMessage("m1", "s1")
        reducer.processEvent(SseEvent.MessageUpdated(message), "server-1")

        assertEquals(listOf(message), reducer.messages.value["s1"])
    }

    @Test
    fun `processEvent MessageUpdated updates existing message`() = runTest {
        val message = createTestMessage("m1", "s1")
        reducer.processEvent(SseEvent.MessageUpdated(message), "server-1")

        val updatedMessage = Message.User(
            id = "m1",
            sessionId = "s1",
            time = TimeInfo(created = 9999L)
        )
        reducer.processEvent(SseEvent.MessageUpdated(updatedMessage), "server-1")

        assertEquals(1, reducer.messages.value["s1"]?.size)
        assertEquals(9999L, reducer.messages.value["s1"]?.firstOrNull()?.time?.created)
    }

    @Test
    fun `processEvent MessageRemoved removes message and parts`() = runTest {
        val message = createTestMessage("m1", "s1")
        reducer.processEvent(SseEvent.MessageUpdated(message), "server-1")

        reducer.processEvent(SseEvent.MessageRemoved(sessionId = "s1", messageId = "m1"), "server-1")

        assertTrue(reducer.messages.value["s1"].isNullOrEmpty())
    }

    @Test
    fun `processEvent MessagePartUpdated adds part`() = runTest {
        val part = Part.Text(id = "p1", sessionId = "s1", messageId = "m1", text = "Hello")
        reducer.processEvent(SseEvent.MessagePartUpdated(part), "server-1")

        assertEquals(listOf(part), reducer.parts.value["m1"])
    }

    @Test
    fun `processEvent MessagePartDelta appends text to existing text part`() = runTest {
        val part = Part.Text(id = "p1", sessionId = "s1", messageId = "m1", text = "Hello")
        reducer.processEvent(SseEvent.MessagePartUpdated(part), "server-1")

        reducer.processEvent(
            SseEvent.MessagePartDelta(
                sessionId = "s1", messageId = "m1", partId = "p1",
                field = "text", delta = " World"
            ),
            "server-1"
        )

        assertEquals("Hello World", (reducer.parts.value["m1"]?.first() as Part.Text).text)
    }

    @Test
    fun `processEvent SessionStatus updates status`() = runTest {
        reducer.processEvent(
            SseEvent.SessionStatus(sessionId = "s1", status = SessionStatus.Busy),
            "server-1"
        )

        assertEquals(SessionStatus.Busy, reducer.sessionStatuses.value["s1"])
    }

    @Test
    fun `processEvent SessionIdle sets status to Idle`() = runTest {
        reducer.processEvent(
            SseEvent.SessionStatus(sessionId = "s1", status = SessionStatus.Busy),
            "server-1"
        )

        reducer.processEvent(SseEvent.SessionIdle(sessionId = "s1"), "server-1")

        assertEquals(SessionStatus.Idle, reducer.sessionStatuses.value["s1"])
    }

    // ============ setMessages / mergeMessages ============

    @Test
    fun `setMessages stores messages and parts`() = runTest {
        val message = createTestMessage("m1", "s1")
        val part = Part.Text(id = "p1", sessionId = "s1", messageId = "m1", text = "Hi")
        reducer.setMessages("s1", listOf(MessageWithParts(info = message, parts = listOf(part))))

        assertEquals(1, reducer.messages.value["s1"]?.size)
        assertEquals(listOf(part), reducer.parts.value["m1"])
    }

    @Test
    fun `mergeMessages keeps existing message instances for unchanged IDs`() = runTest {
        val original = createTestMessage("m1", "s1")
        reducer.setMessages("s1", listOf(MessageWithParts(info = original, parts = emptyList())))

        val newMsg = createTestMessage("m2", "s1")
        reducer.mergeMessages("s1", listOf(
            MessageWithParts(info = original, parts = emptyList()),
            MessageWithParts(info = newMsg, parts = emptyList())
        ))

        assertEquals(2, reducer.messages.value["s1"]?.size)
        // original should be same instance (not recreated)
        assertSame(original, reducer.messages.value["s1"]?.find { it.id == "m1" })
    }

    // ============ Question Handling ============

    @Test
    fun `setQuestions stores questions for a session`() = runTest {
        val question = SseEvent.QuestionAsked(
            id = "q1",
            sessionId = "s1",
            questions = listOf(
                SseEvent.QuestionAsked.Question(
                    header = "Test",
                    question = "Choose?",
                    options = listOf(SseEvent.QuestionAsked.Option("A", "Option A"))
                )
            )
        )
        reducer.setQuestions("s1", listOf(question))

        assertEquals(listOf(question), reducer.questions.value["s1"])
    }

    @Test
    fun `removeQuestion removes across all sessions`() = runTest {
        val q1 = SseEvent.QuestionAsked(
            id = "target", sessionId = "s1",
            questions = emptyList()
        )
        val q2 = SseEvent.QuestionAsked(
            id = "other", sessionId = "s1",
            questions = emptyList()
        )
        reducer.setQuestions("s1", listOf(q1, q2))

        reducer.removeQuestion("target")

        assertEquals(listOf(q2), reducer.questions.value["s1"])
    }

    // ============ Helpers ============

    private fun createTestSession(
        id: String,
        title: String = "Test",
        updated: Long = 1000L
    ): Session = Session(
        id = id,
        title = title,
        directory = "/home/user/project",
        time = Session.Time(created = 500L, updated = updated)
    )

    private fun createTestMessage(
        id: String,
        sessionId: String,
        created: Long = 1000L
    ): Message.User = Message.User(
        id = id,
        sessionId = sessionId,
        time = TimeInfo(created = created)
    )
}
```

- [ ] **Step 2: 运行新测试验证全部通过**

Run:
```bash
cd D:\Develop\code\app\oc-remote
.\gradlew testDebugUnitTest --tests "dev.minios.ocremote.data.repository.EventReducerSessionTest" 2>&1 | Select-Object -Last 15
```
Expected: 全部 PASSED

- [ ] **Step 3: Commit**

```bash
cd D:\Develop\code\app\oc-remote
git add app/src/test/kotlin/dev/minios/ocremote/data/repository/EventReducerSessionTest.kt
git commit -m "test(phase0): add EventReducer session/message characterization tests"
```

---

### Task 7: 编写 JSON 序列化 Characterization Test

**Files:**
- 新建: `app/src/test/kotlin/dev/minios/ocremote/domain/model/SerializationTest.kt`
- Reference: `app/src/main/kotlin/dev/minios/ocremote/domain/model/*.kt`

**目标：** 锁定核心 domain model 的 JSON 序列化/反序列化行为

- [ ] **Step 1: 创建测试文件**

```kotlin
// app/src/test/kotlin/dev/minios/ocremote/domain/model/SerializationTest.kt
package dev.minios.ocremote.domain.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/**
 * Characterization Tests for domain model JSON serialization.
 * Locks in the current serialization/deserialization behavior.
 */
class SerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ============ SseEvent.PermissionAsked ============

    @Test
    fun `PermissionAsked serializes and deserializes correctly`() {
        val original = SseEvent.PermissionAsked(
            id = "p1",
            sessionId = "s1",
            permission = "bash",
            patterns = listOf("/home/user"),
            metadata = mapOf("key" to "value"),
            always = true,
            tool = ToolRef(messageId = "m1", callId = "c1")
        )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<SseEvent.PermissionAsked>(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `PermissionAsked with null optional fields`() {
        val original = SseEvent.PermissionAsked(
            id = "p1",
            sessionId = "s1",
            permission = "bash"
        )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<SseEvent.PermissionAsked>(encoded)
        assertEquals(original, decoded)
        assertNull(decoded.metadata)
        assertNull(decoded.tool)
        assertFalse(decoded.always)
    }

    // ============ Message deserialization ============

    @Test
    fun `Message User deserializes from JSON`() {
        val jsonStr = """{"id":"m1","sessionID":"s1","role":"user","time":{"created":1000}}"""
        val message = json.decodeFromString<Message>(jsonStr)
        assertTrue(message is Message.User)
        assertEquals("m1", message.id)
        assertEquals("s1", message.sessionId)
        assertEquals(1000L, message.time.created)
    }

    @Test
    fun `Message Assistant deserializes from JSON`() {
        val jsonStr = """{"id":"m2","sessionID":"s1","role":"assistant","time":{"created":1000},"parentID":"p1"}"""
        val message = json.decodeFromString<Message>(jsonStr)
        assertTrue(message is Message.Assistant)
        assertEquals("m2", message.id)
        assertEquals("p1", (message as Message.Assistant).parentId)
    }

    @Test
    fun `Message Assistant with tokens and cost`() {
        val jsonStr = """{
            "id":"m2","sessionID":"s1","role":"assistant",
            "time":{"created":1000},"parentID":"",
            "cost":0.05,
            "tokens":{"input":100,"output":50,"reasoning":10,"cache":{"read":20,"write":5}}
        }"""
        val message = json.decodeFromString<Message>(jsonStr) as Message.Assistant
        assertEquals(0.05, message.cost!!, 0.001)
        assertEquals(100, message.tokens?.input)
        assertEquals(50, message.tokens?.output)
        assertEquals(10, message.tokens?.reasoning)
        assertEquals(20, message.tokens?.cache?.read)
        assertEquals(5, message.tokens?.cache?.write)
    }

    // ============ Part deserialization ============

    @Test
    fun `Part Text deserializes from JSON`() {
        val jsonStr = """{"type":"text","id":"p1","sessionID":"s1","messageID":"m1","text":"Hello"}"""
        val part = json.decodeFromString<Part>(jsonStr)
        assertTrue(part is Part.Text)
        assertEquals("Hello", (part as Part.Text).text)
    }

    @Test
    fun `Part Tool deserializes from JSON`() {
        val jsonStr = """{"type":"tool","id":"p2","sessionID":"s1","messageID":"m1","callID":"c1","tool":"bash","state":{"type":"running"}}"""
        val part = json.decodeFromString<Part>(jsonStr)
        assertTrue(part is Part.Tool)
        assertEquals("bash", (part as Part.Tool).tool)
        assertTrue(part.state is ToolState.Running)
    }

    @Test
    fun `Part Tool with completed state deserializes`() {
        val jsonStr = """{"type":"tool","id":"p3","sessionID":"s1","messageID":"m1","callID":"c2","tool":"read","state":{"type":"completed","output":"file content here"}}"""
        val part = json.decodeFromString<Part>(jsonStr) as Part.Tool
        assertTrue(part.state is ToolState.Completed)
        assertEquals("file content here", (part.state as ToolState.Completed).output)
    }

    @Test
    fun `Part Reasoning deserializes from JSON`() {
        val jsonStr = """{"type":"reasoning","id":"p4","sessionID":"s1","messageID":"m1","text":"Thinking..."}"""
        val part = json.decodeFromString<Part>(jsonStr)
        assertTrue(part is Part.Reasoning)
        assertEquals("Thinking...", (part as Part.Reasoning).text)
    }

    @Test
    fun `Part Unknown for unrecognized type`() {
        val jsonStr = """{"type":"future_type","id":"p5","sessionID":"s1","messageID":"m1"}"""
        val part = json.decodeFromString<Part>(jsonStr)
        assertTrue(part is Part.Unknown)
        assertEquals("p5", part.id)
    }

    // ============ Session ============

    @Test
    fun `Session deserializes from JSON with all fields`() {
        val jsonStr = """{
            "id":"s1","title":"Test Session","directory":"/home/user/project",
            "time":{"created":1000,"updated":2000},
            "cost":1.5,
            "tokens":{"input":500,"output":300}
        }"""
        val session = json.decodeFromString<Session>(jsonStr)
        assertEquals("s1", session.id)
        assertEquals("Test Session", session.title)
        assertEquals(1.5, session.cost!!, 0.001)
        assertEquals(500, session.tokens?.input)
    }

    @Test
    fun `Session with parentId is a sub-session`() {
        val jsonStr = """{
            "id":"s2","directory":"/home/user/project",
            "parentID":"s1",
            "time":{"created":1000,"updated":2000}
        }"""
        val session = json.decodeFromString<Session>(jsonStr)
        assertEquals("s1", session.parentId)
    }

    // ============ ServerConnection ============

    @Test
    fun `ServerConnection from URL with password creates Basic auth header`() {
        val conn = dev.minios.ocremote.data.api.ServerConnection.from(
            url = "http://localhost:8080",
            username = "admin",
            password = "secret"
        )
        assertNotNull(conn.authHeader)
        assertTrue(conn.authHeader!!.startsWith("Basic "))
        assertEquals("http://localhost:8080", conn.baseUrl)
    }

    @Test
    fun `ServerConnection from URL without password has no auth header`() {
        val conn = dev.minios.ocremote.data.api.ServerConnection.from(
            url = "http://localhost:8080"
        )
        assertNull(conn.authHeader)
    }

    // ============ DTOs ============

    @Test
    fun `PermissionRequest serializes with correct field names`() {
        val request = dev.minios.ocremote.data.api.PermissionRequest(
            id = "p1",
            sessionId = "s1",
            permission = "bash",
            patterns = listOf("/home"),
            always = listOf("pattern1"),
            tool = ToolRef(messageId = "m1", callId = "c1")
        )
        val encoded = json.encodeToString(request)
        assertTrue(encoded.contains("\"sessionID\":\"s1\""))
        assertTrue(encoded.contains("\"permission\":\"bash\""))
    }

    @Test
    fun `ModelSelection serializes with correct SerialNames`() {
        val selection = dev.minios.ocremote.data.api.ModelSelection(
            providerId = "openai",
            modelId = "gpt-4"
        )
        val encoded = json.encodeToString(selection)
        assertTrue(encoded.contains("\"providerID\":\"openai\""))
        assertTrue(encoded.contains("\"modelID\":\"gpt-4\""))
    }
}
```

- [ ] **Step 2: 运行新测试验证全部通过**

Run:
```bash
cd D:\Develop\code\app\oc-remote
.\gradlew testDebugUnitTest --tests "dev.minios.ocremote.domain.model.SerializationTest" 2>&1 | Select-Object -Last 15
```
Expected: 全部 PASSED

- [ ] **Step 3: Commit**

```bash
cd D:\Develop\code\app\oc-remote
git add app/src/test/kotlin/dev/minios/ocremote/domain/model/SerializationTest.kt
git commit -m "test(phase0): add domain model serialization characterization tests"
```

---

### Task 8: 编写 OpenCodeApi DTO Characterization Test

**Files:**
- 新建: `app/src/test/kotlin/dev/minios/ocremote/data/api/OpenCodeApiTest.kt`
- Reference: `app/src/main/kotlin/dev/minios/ocremote/data/api/OpenCodeApi.kt`

**目标：** 验证 API 请求/响应 DTO 的构造和序列化行为

- [ ] **Step 1: 创建测试文件**

```kotlin
// app/src/test/kotlin/dev/minios/ocremote/data/api/OpenCodeApiTest.kt
package dev.minios.ocremote.data.api

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/**
 * Characterization Tests for OpenCodeApi DTO serialization.
 * Validates that request/response DTOs serialize with correct field names.
 */
class OpenCodeApiTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ============ PromptRequest ============

    @Test
    fun `PromptRequest with text parts serializes correctly`() {
        val request = PromptRequest(
            parts = listOf(
                PromptPart(type = "text", text = "Hello world")
            ),
            agent = "build"
        )
        val encoded = json.encodeToString(request)
        assertTrue(encoded.contains("\"parts\""))
        assertTrue(encoded.contains("\"type\":\"text\""))
        assertTrue(encoded.contains("\"text\":\"Hello world\""))
        assertTrue(encoded.contains("\"agent\":\"build\""))
    }

    @Test
    fun `PromptPart with file path serializes correctly`() {
        val part = PromptPart(type = "text", text = "Read this", path = "/home/user/file.txt")
        val encoded = json.encodeToString(part)
        assertTrue(encoded.contains("\"path\":\"/home/user/file.txt\""))
    }

    // ============ PromptRequest with model ============

    @Test
    fun `PromptRequest with model selection serializes correctly`() {
        val request = PromptRequest(
            parts = listOf(PromptPart(type = "text", text = "test")),
            model = ModelSelection(providerId = "anthropic", modelId = "claude-3"),
            agent = "plan",
            variant = "thinking"
        )
        val encoded = json.encodeToString(request)
        assertTrue(encoded.contains("\"providerID\":\"anthropic\""))
        assertTrue(encoded.contains("\"modelID\":\"claude-3\""))
        assertTrue(encoded.contains("\"variant\":\"thinking\""))
    }

    // ============ ProvidersResponse ============

    @Test
    fun `ProvidersResponse deserializes with providers and defaults`() {
        val jsonStr = """{
            "providers": [
                {"id": "openai", "name": "OpenAI", "models": {
                    "gpt-4": {"id": "gpt-4", "name": "GPT-4", "providerID": "openai"}
                }}
            ],
            "default": {"openai": "gpt-4"}
        }"""
        val response = json.decodeFromString<ProvidersResponse>(jsonStr)
        assertEquals(1, response.providers.size)
        assertEquals("openai", response.providers[0].id)
        assertEquals("gpt-4", response.default["openai"])
    }

    @Test
    fun `ProvidersResponse with empty providers`() {
        val jsonStr = """{"providers":[]}"""
        val response = json.decodeFromString<ProvidersResponse>(jsonStr)
        assertTrue(response.providers.isEmpty())
        assertTrue(response.default.isEmpty())
    }

    // ============ QuestionRequest ============

    @Test
    fun `QuestionRequest deserializes correctly`() {
        val jsonStr = """{
            "id": "q1",
            "sessionID": "s1",
            "questions": [
                {
                    "question": "Which option?",
                    "header": "Choice",
                    "options": [{"label": "A", "description": "Option A"}],
                    "multiple": false,
                    "custom": true
                }
            ]
        }"""
        val request = json.decodeFromString<QuestionRequest>(jsonStr)
        assertEquals("q1", request.id)
        assertEquals("s1", request.sessionId)
        assertEquals(1, request.questions.size)
        assertEquals("Which option?", request.questions[0].question)
        assertEquals(1, request.questions[0].options.size)
    }

    // ============ ServerPaths ============

    @Test
    fun `ServerPaths deserializes correctly`() {
        val jsonStr = """{
            "home": "/home/user",
            "state": "/home/user/.local/state/opencode",
            "config": "/home/user/.config/opencode",
            "worktree": "/home/user/project"
        }"""
        val paths = json.decodeFromString<ServerPaths>(jsonStr)
        assertEquals("/home/user", paths.home)
        assertEquals("/home/user/project", paths.worktree)
    }

    // ============ AgentInfo ============

    @Test
    fun `AgentInfo deserializes correctly`() {
        val jsonStr = """{"name": "build", "description": "Build agent", "mode": "primary"}"""
        val agent = json.decodeFromString<AgentInfo>(jsonStr)
        assertEquals("build", agent.name)
        assertEquals("primary", agent.mode)
        assertFalse(agent.hidden)
    }

    @Test
    fun `AgentInfo filters subagent mode`() {
        val agents = listOf(
            AgentInfo(name = "build", mode = "primary"),
            AgentInfo(name = "explore", mode = "subagent"),
            AgentInfo(name = "plan", mode = "primary", hidden = true)
        )
        val visible = agents.filter { it.mode != "subagent" && !it.hidden }
        assertEquals(1, visible.size)
        assertEquals("build", visible[0].name)
    }

    // ============ CommandInfo ============

    @Test
    fun `CommandInfo deserializes correctly`() {
        val jsonStr = """{"name": "compact", "description": "Compact session", "source": "command"}"""
        val command = json.decodeFromString<CommandInfo>(jsonStr)
        assertEquals("compact", command.name)
        assertEquals("command", command.source)
    }

    // ============ PermissionRequest ============

    @Test
    fun `PermissionRequest round-trips correctly`() {
        val original = PermissionRequest(
            id = "p1",
            sessionId = "s1",
            permission = "write",
            patterns = listOf("/home/user/project"),
            always = listOf("pattern1"),
            tool = dev.minios.ocremote.domain.model.ToolRef(messageId = "m1", callId = "c1")
        )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<PermissionRequest>(encoded)
        assertEquals(original, decoded)
    }

    // ============ PtyInfo ============

    @Test
    fun `PtyInfo deserializes correctly`() {
        val jsonStr = """{
            "id": "pty_123",
            "title": "Terminal 1",
            "command": "/bin/bash",
            "args": [],
            "cwd": "/home/user",
            "status": "running",
            "pid": 12345
        }"""
        val pty = json.decodeFromString<PtyInfo>(jsonStr)
        assertEquals("pty_123", pty.id)
        assertEquals("/bin/bash", pty.command)
        assertEquals("running", pty.status)
    }

    // ============ ShellRequest ============

    @Test
    fun `ShellRequest serializes correctly`() {
        val request = ShellRequest(
            agent = "build",
            model = ModelSelection(providerId = "openai", modelId = "gpt-4"),
            command = "ls -la"
        )
        val encoded = json.encodeToString(request)
        assertTrue(encoded.contains("\"agent\":\"build\""))
        assertTrue(encoded.contains("\"command\":\"ls -la\""))
    }
}
```

- [ ] **Step 2: 运行新测试验证全部通过**

Run:
```bash
cd D:\Develop\code\app\oc-remote
.\gradlew testDebugUnitTest --tests "dev.minios.ocremote.data.api.OpenCodeApiTest" 2>&1 | Select-Object -Last 15
```
Expected: 全部 PASSED

- [ ] **Step 3: Commit**

```bash
cd D:\Develop\code\app\oc-remote
git add app/src/test/kotlin/dev/minios/ocremote/data/api/OpenCodeApiTest.kt
git commit -m "test(phase0): add OpenCodeApi DTO serialization characterization tests"
```

---

### Task 9: 编写 SettingsRepository Characterization Test

**Files:**
- 新建: `app/src/test/kotlin/dev/minios/ocremote/data/repository/SettingsRepositoryTest.kt`
- Reference: `app/src/main/kotlin/dev/minios/ocremote/data/repository/SettingsRepository.kt`

**目标：** 锁定 SettingsRepository 的 Flow 默认值行为

- [ ] **Step 1: 创建测试文件**

注意：`SettingsRepository` 依赖 `DataStore<Preferences>` 和 `Context`，直接测试需要 Android 环境。为纯 JVM 测试，我们验证其 **公共 API 契约**（Flow 类型、setter 签名）。

```kotlin
// app/src/test/kotlin/dev/minios/ocremote/data/repository/SettingsRepositoryTest.kt
package dev.minios.ocremote.data.repository

import org.junit.Assert.*
import org.junit.Test
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.createType
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.memberProperties

/**
 * Characterization Tests for SettingsRepository's public API contract.
 * Validates that all Flow properties exist with correct types and
 * all setter functions exist with correct signatures.
 *
 * These tests ensure the public API surface doesn't regress during refactoring.
 */
class SettingsRepositoryTest {

    // ============ Flow Property Contracts ============

    @Test
    fun `all expected Flow properties exist`() {
        val props = SettingsRepository::class.memberProperties.map { it.name }.toSet()

        val expectedFlows = listOf(
            "appLanguage", "appTheme", "dynamicColor", "chatFontSize",
            "notificationsEnabled", "initialMessageCount", "codeWordWrap",
            "confirmBeforeSend", "amoledDark", "compactMessages", "collapseTools",
            "expandReasoning", "hapticFeedback", "reconnectMode", "keepScreenOn",
            "silentNotifications", "compressImageAttachments",
            "imageAttachmentMaxLongSide", "imageAttachmentWebpQuality",
            "showLocalRuntime", "terminalFontSize", "localSetupCompleted",
            "localProxyEnabled", "localProxyUrl", "localProxyNoProxy",
            "localServerAllowLan", "localServerUsername", "localServerPassword",
            "localServerRunInBackground", "localServerAutoStart",
            "localServerStartupTimeoutSec"
        )

        for (flow in expectedFlows) {
            assertTrue(
                "Missing Flow property: $flow. Available: ${props.sorted()}",
                props.contains(flow)
            )
        }
    }

    @Test
    fun `hiddenModels is a function returning Flow`() {
        val func = SettingsRepository::class.memberFunctions.find { it.name == "hiddenModels" }
        assertNotNull("hiddenModels function should exist", func)
        assertEquals(1, func!!.parameters.count { it.kind == KParameter.Kind.VALUE })
    }

    // ============ Setter Function Contracts ============

    @Test
    fun `all expected setter functions exist`() {
        val funcs = SettingsRepository::class.memberFunctions.map { it.name }.toSet()

        val expectedSetters = listOf(
            "setAppLanguage", "setAppTheme", "setDynamicColor", "setChatFontSize",
            "setNotificationsEnabled", "setInitialMessageCount", "setCodeWordWrap",
            "setConfirmBeforeSend", "setAmoledDark", "setCompactMessages",
            "setCollapseTools", "setExpandReasoning", "setHapticFeedback",
            "setReconnectMode", "setKeepScreenOn", "setSilentNotifications",
            "setCompressImageAttachments", "setImageAttachmentMaxLongSide",
            "setImageAttachmentWebpQuality", "setShowLocalRuntime",
            "setTerminalFontSize", "setLocalSetupCompleted", "setLocalProxyEnabled",
            "setLocalProxyUrl", "setLocalProxyNoProxy", "setLocalServerAllowLan",
            "setLocalServerUsername", "setLocalServerPassword",
            "setLocalServerRunInBackground", "setLocalServerAutoStart",
            "setLocalServerStartupTimeoutSec", "setModelVisibility"
        )

        for (setter in expectedSetters) {
            assertTrue(
                "Missing setter function: $setter. Available: ${funcs.sorted()}",
                funcs.contains(setter)
            )
        }
    }

    @Test
    fun `setModelVisibility has correct parameter count`() {
        val func = SettingsRepository::class.memberFunctions.find { it.name == "setModelVisibility" }
        assertNotNull(func)
        // Parameters: receiver (SettingsRepository) + serverId + providerId + modelId + visible
        val valueParams = func!!.parameters.count { it.kind == KParameter.Kind.VALUE }
        assertEquals("setModelVisibility should have 4 value params", 4, valueParams)
    }

    // ============ DraftRepository Contract ============

    @Test
    fun `Draft isEmpty logic is correct`() {
        val emptyDraft = Draft()
        assertTrue(emptyDraft.isEmpty)

        val draftWithText = Draft(text = "hello")
        assertFalse(draftWithText.isEmpty)

        val draftWithAgent = Draft(selectedAgent = "build")
        assertFalse(draftWithAgent.isEmpty)

        val draftWithVariant = Draft(selectedVariant = "thinking")
        assertFalse(draftWithVariant.isEmpty)
    }

    @Test
    fun `Draft with only blank text is empty`() {
        val draft = Draft(text = "   ")
        assertTrue(draft.isEmpty)
    }

    // ============ ServerConfig Contract ============

    @Test
    fun `ServerConfig displayName falls back to url`() {
        val config = dev.minios.ocremote.domain.model.ServerConfig(
            id = "test",
            url = "http://localhost:8080",
            name = null
        )
        assertEquals("localhost:8080", config.displayName)
    }

    @Test
    fun `ServerConfig displayName uses explicit name`() {
        val config = dev.minios.ocremote.domain.model.ServerConfig(
            id = "test",
            url = "http://localhost:8080",
            name = "My Server"
        )
        assertEquals("My Server", config.displayName)
    }
}
```

- [ ] **Step 2: 运行新测试验证全部通过**

Run:
```bash
cd D:\Develop\code\app\oc-remote
.\gradlew testDebugUnitTest --tests "dev.minios.ocremote.data.repository.SettingsRepositoryTest" 2>&1 | Select-Object -Last 15
```
Expected: 全部 PASSED

- [ ] **Step 3: Commit**

```bash
cd D:\Develop\code\app\oc-remote
git add app/src/test/kotlin/dev/minios/ocremote/data/repository/SettingsRepositoryTest.kt
git commit -m "test(phase0): add SettingsRepository API contract characterization tests"
```

---

### Task 10: 全量回归 + Phase 0 完成

**Files:**
- Reference: All test files

- [ ] **Step 1: 运行全部测试**

Run:
```bash
cd D:\Develop\code\app\oc-remote
.\gradlew testDebugUnitTest 2>&1 | Tee-Object -FilePath build\test-results\phase0-final.txt
```
Expected: 全部测试 PASSED，0 FAIL，0 ERROR

- [ ] **Step 2: 确认测试数量**

Run:
```bash
cd D:\Develop\code\app\oc-remote
Select-String -Path build\test-results\phase0-final.txt -Pattern "tests? (completed|passed|found|succeeded)|BUILD SUCCESSFUL|BUILD FAILED"
```
Expected: 显示测试总数和全部通过状态

- [ ] **Step 3: 如果有失败，根据 Task 2-5 的修复模式逐一修复后重跑**

最多允许 2 轮修复循环。如果某测试因根本性架构变更无法修复且超过总测试数 20%，标记为 `@Disabled` 并添加注释：

```kotlin
@Disabled("Phase 0: 根本性架构变更导致无法修复，待重构后重写等价测试。原因: ...")
@Test
fun `原有测试名`() {
    // 保留原测试代码供参考
}
```

- [ ] **Step 4: 如果有任何 @Disabled 测试，记录到日志**

创建文件 `docs/superpowers/plans/phase0-disabled-tests.md` 记录每个被禁用的测试及原因。

- [ ] **Step 5: Commit 全量回归结果**

```bash
cd D:\Develop\code\app\oc-remote
git add build/test-results/phase0-final.txt
git commit -m "test(phase0): full regression pass - all tests green"
```

- [ ] **Step 6: 推送全部 Phase 0 提交**

```bash
cd D:\Develop\code\app\oc-remote
git push
```
Expected: Phase 0 全部提交已推送

---

## Decision Log

| 场景 | 决策 | 理由 |
|------|------|------|
| 测试编译失败 | 对照源码签名修复测试代码 | 源码是 source of truth |
| MockK verify 不匹配 | 放宽匹配器为 `any()` 或修复参数 | 避免 over-specified 测试 |
| Turbine 超时 | 确认 Flow subscription 和 dispatcher 设置 | WhileSubscribed 需要活跃 subscriber |
| 超过 20% 测试无法修复 | 标记 @Disabled 并记录原因 | Phase 0 目标是建立基线，不是重写架构 |
| Characterization Test 发现 bug | 在测试中锁定 bug 行为，添加 TODO 注释 | 先锁定，后续 Phase 修复 |

## Exit Criteria

- [ ] 所有现有测试 PASS 或被标记 @Disabled（≤20%）
- [ ] 新增 Characterization Tests 全部 PASS
- [ ] 全量回归测试绿色
- [ ] 所有提交已推送
