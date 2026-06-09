# V2 Session State Redesign

> **对齐目标：** OpenCode V2 事件溯源架构 + TUI (`packages/tui/src/context/sync-v2.tsx`)

## Goal

全面重写 Android 客户端会话状态管理，从当前 14 个 StateFlow + 8 路 combine 的过度复杂架构，迁移到 OpenCode V2 的单一 State 事件溯源模式。

## Architecture

**核心思想：** SSE 事件是唯一真相来源。客户端维护一个 `SessionState`，通过纯函数 Reducer 响应每个事件。REST 仅用于初始 Hydration 和历史分页。不做自己推断状态——从事件推导。

**数据流：** `SSE → EventParser → EventDeduplicator → EventReducer → SessionState → UI`

---

## 1. State Model

### 1.1 SessionState

```kotlin
data class SessionState(
    val messages: List<SessionMessage>,     // 最新在头部（prepend）
    val todos: List<TodoInfo>,
    val hasOlderMessages: Boolean,
    val isLoadingOlder: Boolean,
    val isInitialized: Boolean,
)

companion object {
    val EMPTY = SessionState(
        messages = emptyList(), todos = emptyList(),
        hasOlderMessages = false, isLoadingOlder = false, isInitialized = false,
    )
}
```

**状态推导（不存字段）：**
- `isBusy` = messages 中存在 `Assistant && time.completed == null`
- `isIdle` = !isBusy

### 1.2 SessionMessage — 8 种子类型

对齐 `packages/core/src/session/message.ts:180-193`

| 类型 | 关键字段 |
|------|---------|
| `User` | `id`, `text`, `files?`, `agents?`, `references?`, `time{created}` |
| `Assistant` | `id`, `agent`, `model`, `content: List<AssistantContent>`, `snapshot?{start?,end?}`, `finish?`, `cost?`, `tokens?`, `error?`, `time{created,completed?}` |
| `Shell` | `id`, `callId`, `command`, `output`, `time{created,completed?}` | ShellStarted 时 `output` 初始为 `""`，ShellEnded 时填充实际值 |
| `System` | `id`, `text`, `time{created}` |
| `Synthetic` | `id`, `sessionId`, `text`, `time{created}` |
| `AgentSwitched` | `id`, `agent`, `time{created}` |
| `ModelSwitched` | `id`, `model`, `time{created}` |
| `Compaction` | `id`, `reason`, `summary`, `recent`, `time{created}` |

### 1.3 AssistantContent — 3 种子类型

对齐 `packages/core/src/session/message.ts:139-141`

- `Text(id, text)`
- `Reasoning(id, text, providerMetadata?)`
- `Tool(id, name, state: ToolState, provider?, time{created,ran?,completed?,pruned?})`

### 1.4 ToolState — 4 种状态

对齐 `packages/core/src/session/message.ts:72-106`

- `Pending(input: String)`
- `Running(input, structured, content)`
- `Completed(input, structured, content, attachments?, outputPaths?, result?)`
- `Error(input, structured, content, error, result?)`

---

## 2. Event System

### 2.1 SSE Envelope

对齐 `packages/tui/src/context/sdk.tsx` 的 GlobalEvent

```kotlin
GlobalEvent { directory, workspace?, payload: { type: String, properties: JsonObject } }
```

### 2.2 SseEventV2 密封类

31 种事件（25 种产出消息 + 6 种被动），对齐 `sync-v2.tsx:120-421` 的 apply() switch-case

**追加类（创建新消息，7 种）：** AgentSwitched, ModelSwitched, Prompted, PromptPromoted, ContextUpdated, Synthetic, ShellStarted

**更新类（修改已有消息，4 种）：** ShellEnded, StepStarted, StepEnded, StepFailed

**流式 Delta（直接更新 Assistant.content，6 种）：** TextStarted/Delta/Ended, ReasoningStarted/Delta/Ended

**工具状态机（7 种）：** ToolInputStarted/Delta/Ended, ToolCalled, ToolProgress, ToolSuccess, ToolFailed

**压缩（1 种）：** CompactionEnded

**被动（不改变状态，6 种）：** PromptAdmitted, Retried, CompactionStarted, CompactionDelta, Moved, InterruptRequested

### 2.3 EventParser

`type` 字符串 → `SseEventV2` 分发，JSON 反序列化到具体事件类型。

---

## 3. EventReducer

纯函数 `reduce(state: SessionState, event: SseEventV2): SessionState`

对齐 `sync-v2.tsx:120-421` 的每个 case 分支。

核心逻辑：
- **prepend 模式**：新消息插入列表头部（`listOf(msg) + existing`）
- **Delta 处理**：`text.delta` → 找到 Assistant.content 中对应 Text，`text.copy(text = text + delta)`
- **Tool 状态机**：`pending → running → completed/error`，每次转换整个 state 替换
- **Step 生命周期**：`started` 关闭前一个 Assistant 的 completed，创建新的；`ended` 填充 finish/cost/tokens

---

## 4. SSE Connection

### 4.1 SseConnectionManager

对齐 `sdk.tsx:50-117`

- 端点：`GET {baseUrl}/global/event`
- 16ms 批处理窗口（累积事件，超时刷新）
- 指数退避重连：1s → 2s → 4s → … → 30s max
- 事件去重：`EventDeduplicator`（LinkedHashSet，LRU 1000）

### 4.2 EventDeduplicator

- 基于 event.id 去重
- LRU 驱逐，上限 1000

---

## 5. Hydration

对齐 `sync-v2.tsx:91-109`

```
1. 订阅 SSE → 事件进入 buffer（hydrating = true 期间暂存）
2. REST GET /api/session/{id}/message → 获取全量消息
3. 合并：[REST messages] + [local messages not in REST]
4. hydrating = false
5. 回放 buffer 中的事件 → reduce() 逐条应用
```

---

## 6. SDK Interface

```kotlin
interface OpenCodeV2Sdk {
    fun events(sessionId: String): Flow<SseEventV2>
    suspend fun messages(sessionId: String, limit: Int = 200, cursor: String? = null): MessagesResponse
    suspend fun prompt(sessionId: String, text: String, delivery: DeliveryMode = STEER, files: List<FileAttachment>? = null): PromptResponse
    suspend fun abort(sessionId: String)
}
```

---

## 7. ChatViewModel

缩减到 ~300 行，只负责：
- 持有 `MutableStateFlow<SessionState>`
- SSE → Reducer → state.update()
- Hydration
- sendMessage / loadOlderMessages
- chatMessages / isBusy 推导 StateFlow

---

## 8. Files to Create/Modify

| File | Action | Lines |
|------|--------|-------|
| `data/v2/SessionState.kt` | Create | ~40 |
| `data/v2/SessionMessage.kt` | Create | ~200 |
| `data/v2/AssistantContent.kt` | Create | ~40 |
| `data/v2/ToolState.kt` | Create | ~50 |
| `data/v2/SseEventV2.kt` | Create | ~150 |
| `data/v2/EventParser.kt` | Create | ~80 |
| `data/v2/EventReducer.kt` | Create | ~250 |
| `data/v2/EventDeduplicator.kt` | Create | ~30 |
| `data/v2/SseConnectionManager.kt` | Create | ~100 |
| `data/v2/OpenCodeV2Sdk.kt` | Create | ~40 |
| `data/v2/Types.kt` | Create | ~50 |
| `ui/screens/chat/ChatViewModel.kt` | Rewrite | ~300 (from 2069) |
| `ui/screens/chat/ChatScreen.kt` | Adapt | ~50 changed |
| `test/data/v2/EventReducerTest.kt` | Create | ~150 |
