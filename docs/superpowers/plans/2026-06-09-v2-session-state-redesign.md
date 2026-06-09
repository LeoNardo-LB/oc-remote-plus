# V2 Session State Redesign — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** 全面重写 Android 客户端会话状态管理，从当前 24 StateFlow 架构迁移到 V2 事件溯源模式（单一 `SessionState` + 纯函数 `EventReducer`）

**Architecture:** SSE 事件 → EventParser → EventDeduplicator → EventReducer → SessionState → UI。新代码放在 `data/v2/` 包下，与旧 V1 代码隔离。ChatViewModel 重写后从 ~2069 行缩减到 ~300 行。

**Tech Stack:** Kotlin, kotlinx.serialization 1.11.0, Ktor OkHttp, Coroutines StateFlow, Hilt DI

**Dependency chain:** Task1 (types) → Task2 (events) → Task3 (parser+dedup) → Task4 (reducer) → Task5 (connection) → Task6 (sdk) → Task7 (viewmodel) → Task8 (screen) → Task9 (tests) → Task10 (build)

---

### Task 1: 创建数据模型类型

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/data/v2/Types.kt`
- Create: `app/src/main/kotlin/dev/minios/ocremote/data/v2/SessionMessage.kt`
- Create: `app/src/main/kotlin/dev/minios/ocremote/data/v2/SessionState.kt`

**Context:** 对齐 `packages/core/src/session/message.ts`。所有类型用 `@Serializable` 注解，sealed class/interface 用于联合类型。

**Types.kt** — 基础类型共享定义：
```kotlin
package dev.minios.ocremote.data.v2

import kotlinx.serialization.Serializable

@Serializable
data class TimeInfo(
    val created: Long,           // DateTimeUtcFromMillis
    val completed: Long? = null,
)

@Serializable
data class SnapshotInfo(
    val start: String? = null,
    val end: String? = null,
)

@Serializable
data class TokenUsage(
    val input: Long,
    val output: Long,
    val reasoning: Long,
    val cache: CacheUsage = CacheUsage(),
)

@Serializable
data class CacheUsage(val read: Long = 0, val write: Long = 0)

@Serializable
data class ModelRef(
    val providerID: String,
    val modelID: String,
)

@Serializable
data class FileAttachment(
    val name: String,
    val path: String,
    val type: String? = null,
)

@Serializable
data class Prompt(
    val text: String,
    val files: List<FileAttachment>? = null,
    val agents: List<String>? = null,
    val references: List<String>? = null,
)

@Serializable
data class TodoInfo(
    val id: String,
    val text: String,
    val completed: Boolean = false,
)
```

**SessionMessage.kt** — 8 种消息子类型：
```kotlin
package dev.minios.ocremote.data.v2

import kotlinx.serialization.Serializable

@Serializable
sealed interface SessionMessage {
    val id: String
    val sessionId: String
}

@Serializable
sealed interface AssistantContent {
    val id: String
}

// --- Tool States (4 种) ---
@Serializable
sealed interface ToolState {
    val status: String
    val input: String
}

@Serializable @kotlinx.serialization.SerialName("pending")
data class ToolStatePending(override val input: String) : ToolState {
    override val status = "pending"
}

@Serializable @kotlinx.serialization.SerialName("running")
data class ToolStateRunning(
    override val input: String,
    val structured: String? = null,
    val content: List<String>? = null,
) : ToolState {
    override val status = "running"
}

@Serializable @kotlinx.serialization.SerialName("completed")
data class ToolStateCompleted(
    override val input: String,
    val structured: String? = null,
    val content: List<String>? = null,
    val attachments: List<FileAttachment>? = null,
    val outputPaths: List<String>? = null,
    val result: String? = null,
) : ToolState {
    override val status = "completed"
}

@Serializable @kotlinx.serialization.SerialName("error")
data class ToolStateError(
    override val input: String,
    val structured: String? = null,
    val content: List<String>? = null,
    val error: String? = null,
    val result: String? = null,
) : ToolState {
    override val status = "error"
}

// --- AssistantContent (3 种) ---
@Serializable @kotlinx.serialization.SerialName("text")
data class AssistantText(
    override val id: String,
    val text: String,
) : AssistantContent

@Serializable @kotlinx.serialization.SerialName("reasoning")
data class AssistantReasoning(
    override val id: String,
    val text: String,
) : AssistantContent

@Serializable @kotlinx.serialization.SerialName("tool")
data class AssistantTool(
    override val id: String,
    val name: String,
    val state: ToolState,
    val time: TimeInfo = TimeInfo(created = 0L),
) : AssistantContent

// --- SessionMessage (8 种) ---
@Serializable @kotlinx.serialization.SerialName("user")
data class UserMessage(
    override val id: String,
    override val sessionId: String,
    val text: String,
    val files: List<FileAttachment>? = null,
    val agents: List<String>? = null,
    val references: List<String>? = null,
    val time: TimeInfo,
) : SessionMessage

@Serializable @kotlinx.serialization.SerialName("assistant")
data class AssistantMessage(
    override val id: String,
    override val sessionId: String,
    val agent: String,
    val model: ModelRef,
    val content: List<AssistantContent>,
    val snapshot: SnapshotInfo? = null,
    val finish: String? = null,
    val cost: Double? = null,
    val tokens: TokenUsage? = null,
    val error: String? = null,
    val time: TimeInfo,
) : SessionMessage

@Serializable @kotlinx.serialization.SerialName("shell")
data class ShellMessage(
    override val id: String,
    override val sessionId: String,
    val callId: String,
    val command: String,
    val output: String,            // ShellStarted 时初始为 ""
    val time: TimeInfo,
) : SessionMessage

@Serializable @kotlinx.serialization.SerialName("system")
data class SystemMessage(
    override val id: String,
    override val sessionId: String,
    val text: String,
    val time: TimeInfo,
) : SessionMessage

@Serializable @kotlinx.serialization.SerialName("synthetic")
data class SyntheticMessage(
    override val id: String,
    override val sessionId: String,
    val text: String,
    val time: TimeInfo,
) : SessionMessage

@Serializable @kotlinx.serialization.SerialName("agent-switched")
data class AgentSwitchedMessage(
    override val id: String,
    override val sessionId: String,
    val agent: String,
    val time: TimeInfo,
) : SessionMessage

@Serializable @kotlinx.serialization.SerialName("model-switched")
data class ModelSwitchedMessage(
    override val id: String,
    override val sessionId: String,
    val model: ModelRef,
    val time: TimeInfo,
) : SessionMessage

@Serializable @kotlinx.serialization.SerialName("compaction")
data class CompactionMessage(
    override val id: String,
    override val sessionId: String,
    val reason: String,
    val summary: String,
    val recent: String,
    val time: TimeInfo,
) : SessionMessage
```

**SessionState.kt** — 单一大 State：
```kotlin
package dev.minios.ocremote.data.v2

@kotlinx.serialization.Serializable
data class SessionState(
    val messages: List<SessionMessage> = emptyList(),  // 最新在头部（prepend）
    val todos: List<TodoInfo> = emptyList(),
    val hasOlderMessages: Boolean = false,
    val isLoadingOlder: Boolean = false,
    val isInitialized: Boolean = false,
) {
    companion object {
        val EMPTY = SessionState()
    }
}

// 状态推导（扩展属性）
val SessionState.isBusy: Boolean
    get() = messages.any { it is AssistantMessage && it.time.completed == null }

val SessionState.isIdle: Boolean get() = !isBusy
```

- [ ] **Step 1: Create Types.kt** with TimeInfo, SnapshotInfo, TokenUsage, CacheUsage, ModelRef, FileAttachment, Prompt, TodoInfo
- [ ] **Step 2: Create SessionMessage.kt** with 4 ToolState subtypes, 3 AssistantContent subtypes, 8 SessionMessage subtypes
- [ ] **Step 3: Create SessionState.kt** with SessionState data class + isBusy/isIdle extensions
- [ ] **Step 4: Compile check** `.\gradlew :app:compileDevDebugKotlin` (may have unresolved refs — expected, just check that data classes compile clean)
- [ ] **Step 5: Commit**

---

### Task 2: 创建 SSE 事件类型

**File:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/data/v2/SseEventV2.kt`

**Context:** 对齐 `sync-v2.tsx:120-421` 的 31 种事件。使用 `@Serializable` sealed class，`type` 字段做多态分发。

**SseEventV2.kt** — 31 种事件：
```kotlin
package dev.minios.ocremote.data.v2

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// SSE Envelope
@Serializable
data class GlobalEvent(
    val directory: String,
    val workspace: String? = null,
    val payload: SseEventPayload,
)

@Serializable
data class SseEventPayload(
    val type: String,           // 事件类型字符串 "session.next.xxx"
    val properties: JsonObject, // 事件属性（具体化后反序列化为具体事件类型）
)

// --- 所有 V2 事件（31 种）---
@Serializable
sealed interface SseEventV2 {
    val type: String
}

// 追加类（7 种）
@Serializable @kotlinx.serialization.SerialName("session.next.agent.switched")
data class AgentSwitchedEvent(@kotlinx.serialization.Transient override val type: String = "session.next.agent.switched", val sessionID: String, val agent: String, val id: String) : SseEventV2

@Serializable @kotlinx.serialization.SerialName("session.next.model.switched")
data class ModelSwitchedEvent(@kotlinx.serialization.Transient override val type: String = "session.next.model.switched", val sessionID: String, val model: ModelRef, val id: String) : SseEventV2

@Serializable @kotlinx.serialization.SerialName("session.next.prompted")
data class PromptedEvent(@kotlinx.serialization.Transient override val type: String = "session.next.prompted", val sessionID: String, val id: String, val prompt: Prompt) : SseEventV2

@Serializable @kotlinx.serialization.SerialName("session.next.prompt.promoted")
data class PromptPromotedEvent(@kotlinx.serialization.Transient override val type: String = "session.next.prompt.promoted", val sessionID: String, val id: String, val prompt: Prompt) : SseEventV2

@Serializable @kotlinx.serialization.SerialName("session.next.context.updated")
data class ContextUpdatedEvent(@kotlinx.serialization.Transient override val type: String = "session.next.context.updated", val sessionID: String, val text: String, val id: String) : SseEventV2

@Serializable @kotlinx.serialization.SerialName("session.next.synthetic")
data class SyntheticEvent(@kotlinx.serialization.Transient override val type: String = "session.next.synthetic", val sessionID: String, val id: String, val text: String) : SseEventV2

@Serializable @kotlinx.serialization.SerialName("session.next.shell.started")
data class ShellStartedEvent(@kotlinx.serialization.Transient override val type: String = "session.next.shell.started", val sessionID: String, val id: String, val callID: String, val command: String) : SseEventV2

// 更新类（4 种）
@Serializable @kotlinx.serialization.SerialName("session.next.shell.ended")
data class ShellEndedEvent(@kotlinx.serialization.Transient override val type: String = "session.next.shell.ended", val sessionID: String, val id: String, val callID: String, val output: String) : SseEventV2

@Serializable @kotlinx.serialization.SerialName("session.next.step.started")
data class StepStartedEvent(@kotlinx.serialization.Transient override val type: String = "session.next.step.started", val sessionID: String, val id: String, val agent: String, val model: ModelRef) : SseEventV2

@Serializable @kotlinx.serialization.SerialName("session.next.step.ended")
data class StepEndedEvent(@kotlinx.serialization.Transient override val type: String = "session.next.step.ended", val sessionID: String, val id: String, val finish: String, val cost: Double, val tokens: TokenUsage) : SseEventV2

@Serializable @kotlinx.serialization.SerialName("session.next.step.failed")
data class StepFailedEvent(@kotlinx.serialization.Transient override val type: String = "session.next.step.failed", val sessionID: String, val id: String, val error: String) : SseEventV2

// 流式 Delta（6 种）
@Serializable @kotlinx.serialization.SerialName("session.next.text.started")
data class TextStartedEvent(@kotlinx.serialization.Transient override val type: String = "session.next.text.started", val sessionID: String, val id: String, val stepID: String) : SseEventV2

@Serializable @kotlinx.serialization.SerialName("session.next.text.delta")
data class TextDeltaEvent(@kotlinx.serialization.Transient override val type: String = "session.next.text.delta", val sessionID: String, val id: String, val stepID: String, val delta: String) : SseEventV2

@Serializable @kotlinx.serialization.SerialName("session.next.text.ended")
data class TextEndedEvent(@kotlinx.serialization.Transient override val type: String = "session.next.text.ended", val sessionID: String, val id: String, val stepID: String) : SseEventV2

@Serializable @kotlinx.serialization.SerialName("session.next.reasoning.started")
data class ReasoningStartedEvent(@kotlinx.serialization.Transient override val type: String = "session.next.reasoning.started", val sessionID: String, val id: String, val stepID: String) : SseEventV2

@Serializable @kotlinx.serialization.SerialName("session.next.reasoning.delta")
data class ReasoningDeltaEvent(@kotlinx.serialization.Transient override val type: String = "session.next.reasoning.delta", val sessionID: String, val id: String, val stepID: String, val delta: String) : SseEventV2

@Serializable @kotlinx.serialization.SerialName("session.next.reasoning.ended")
data class ReasoningEndedEvent(@kotlinx.serialization.Transient override val type: String = "session.next.reasoning.ended", val sessionID: String, val id: String, val stepID: String) : SseEventV2

// 工具状态机（7 种）
@Serializable @kotlinx.serialization.SerialName("session.next.tool.input.started")
data class ToolInputStartedEvent(@kotlinx.serialization.Transient override val type: String = "session.next.tool.input.started", val sessionID: String, val id: String, val stepID: String) : SseEventV2

@Serializable @kotlinx.serialization.SerialName("session.next.tool.input.delta")
data class ToolInputDeltaEvent(@kotlinx.serialization.Transient override val type: String = "session.next.tool.input.delta", val sessionID: String, val id: String, val stepID: String, val delta: String) : SseEventV2

@Serializable @kotlinx.serialization.SerialName("session.next.tool.input.ended")
data class ToolInputEndedEvent(@kotlinx.serialization.Transient override val type: String = "session.next.tool.input.ended", val sessionID: String, val id: String, val stepID: String) : SseEventV2

@Serializable @kotlinx.serialization.SerialName("session.next.tool.called")
data class ToolCalledEvent(@kotlinx.serialization.Transient override val type: String = "session.next.tool.called", val sessionID: String, val id: String, val stepID: String, val name: String, val input: String) : SseEventV2

@Serializable @kotlinx.serialization.SerialName("session.next.tool.progress")
data class ToolProgressEvent(@kotlinx.serialization.Transient override val type: String = "session.next.tool.progress", val sessionID: String, val id: String, val stepID: String) : SseEventV2

@Serializable @kotlinx.serialization.SerialName("session.next.tool.success")
data class ToolSuccessEvent(@kotlinx.serialization.Transient override val type: String = "session.next.tool.success", val sessionID: String, val id: String, val stepID: String, val outputPaths: List<String>? = null, val result: String? = null) : SseEventV2

@Serializable @kotlinx.serialization.SerialName("session.next.tool.failed")
data class ToolFailedEvent(@kotlinx.serialization.Transient override val type: String = "session.next.tool.failed", val sessionID: String, val id: String, val stepID: String, val error: String) : SseEventV2

// 压缩（1 种）
@Serializable @kotlinx.serialization.SerialName("session.next.compaction.ended")
data class CompactionEndedEvent(@kotlinx.serialization.Transient override val type: String = "session.next.compaction.ended", val sessionID: String, val id: String, val reason: String, val summary: String, val recent: String) : SseEventV2

// 被动（6 种 — 不改变状态，仅用于日志/调试）
@Serializable @kotlinx.serialization.SerialName("session.next.prompt.admitted")
data class PromptAdmittedEvent(@kotlinx.serialization.Transient override val type: String = "session.next.prompt.admitted", val sessionID: String) : SseEventV2

@Serializable @kotlinx.serialization.SerialName("session.next.retried")
data class RetriedEvent(@kotlinx.serialization.Transient override val type: String = "session.next.retried", val sessionID: String) : SseEventV2

@Serializable @kotlinx.serialization.SerialName("session.next.compaction.started")
data class CompactionStartedEvent(@kotlinx.serialization.Transient override val type: String = "session.next.compaction.started", val sessionID: String) : SseEventV2

@Serializable @kotlinx.serialization.SerialName("session.next.compaction.delta")
data class CompactionDeltaEvent(@kotlinx.serialization.Transient override val type: String = "session.next.compaction.delta", val sessionID: String, val delta: String) : SseEventV2

@Serializable @kotlinx.serialization.SerialName("session.next.moved")
data class MovedEvent(@kotlinx.serialization.Transient override val type: String = "session.next.moved", val sessionID: String) : SseEventV2

@Serializable @kotlinx.serialization.SerialName("session.next.interrupt.requested")
data class InterruptRequestedEvent(@kotlinx.serialization.Transient override val type: String = "session.next.interrupt.requested", val sessionID: String) : SseEventV2
```

- [ ] **Step 1: Create SseEventV2.kt** with GlobalEvent, SseEventPayload, and all 31 SseEventV2 subtypes
- [ ] **Step 2: Compile check** `.\gradlew :app:compileDevDebugKotlin` — expected: data class compilation passes, EventParser reference not yet created
- [ ] **Step 3: Commit**

---

### Task 3: 创建 EventParser + EventDeduplicator

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/data/v2/EventParser.kt`
- Create: `app/src/main/kotlin/dev/minios/ocremote/data/v2/EventDeduplicator.kt`

**Context:**
- EventParser: 从 SSE 原始 JSON 字符串解析 `GlobalEvent` → 提取 `payload.type` → 反序列化 `payload.properties` 到具体 `SseEventV2` 子类型
- EventDeduplicator: 基于 `event.id` 去重，LRU LinkedHashSet，上限 1000

**EventParser.kt:**
```kotlin
package dev.minios.ocremote.data.v2

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

object EventParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun parse(raw: String): SseEventV2? {
        return try {
            val global = json.decodeFromString<GlobalEvent>(raw)
            val type = global.payload.type
            val props = global.payload.properties
            parse(type, props)
        } catch (e: Exception) {
            null
        }
    }

    private fun parse(type: String, props: kotlinx.serialization.json.JsonObject): SseEventV2? {
        return try {
            when {
                type == "session.next.agent.switched" -> json.decodeFromJsonElement<AgentSwitchedEvent>(props)
                type == "session.next.model.switched" -> json.decodeFromJsonElement<ModelSwitchedEvent>(props)
                type == "session.next.prompted" -> json.decodeFromJsonElement<PromptedEvent>(props)
                type == "session.next.prompt.admitted" -> json.decodeFromJsonElement<PromptAdmittedEvent>(props)
                type == "session.next.prompt.promoted" -> json.decodeFromJsonElement<PromptPromotedEvent>(props)
                type == "session.next.context.updated" -> json.decodeFromJsonElement<ContextUpdatedEvent>(props)
                type == "session.next.synthetic" -> json.decodeFromJsonElement<SyntheticEvent>(props)
                type == "session.next.shell.started" -> json.decodeFromJsonElement<ShellStartedEvent>(props)
                type == "session.next.shell.ended" -> json.decodeFromJsonElement<ShellEndedEvent>(props)
                type == "session.next.step.started" -> json.decodeFromJsonElement<StepStartedEvent>(props)
                type == "session.next.step.ended" -> json.decodeFromJsonElement<StepEndedEvent>(props)
                type == "session.next.step.failed" -> json.decodeFromJsonElement<StepFailedEvent>(props)
                type == "session.next.text.started" -> json.decodeFromJsonElement<TextStartedEvent>(props)
                type == "session.next.text.delta" -> json.decodeFromJsonElement<TextDeltaEvent>(props)
                type == "session.next.text.ended" -> json.decodeFromJsonElement<TextEndedEvent>(props)
                type == "session.next.reasoning.started" -> json.decodeFromJsonElement<ReasoningStartedEvent>(props)
                type == "session.next.reasoning.delta" -> json.decodeFromJsonElement<ReasoningDeltaEvent>(props)
                type == "session.next.reasoning.ended" -> json.decodeFromJsonElement<ReasoningEndedEvent>(props)
                type == "session.next.tool.input.started" -> json.decodeFromJsonElement<ToolInputStartedEvent>(props)
                type == "session.next.tool.input.delta" -> json.decodeFromJsonElement<ToolInputDeltaEvent>(props)
                type == "session.next.tool.input.ended" -> json.decodeFromJsonElement<ToolInputEndedEvent>(props)
                type == "session.next.tool.called" -> json.decodeFromJsonElement<ToolCalledEvent>(props)
                type == "session.next.tool.progress" -> json.decodeFromJsonElement<ToolProgressEvent>(props)
                type == "session.next.tool.success" -> json.decodeFromJsonElement<ToolSuccessEvent>(props)
                type == "session.next.tool.failed" -> json.decodeFromJsonElement<ToolFailedEvent>(props)
                type == "session.next.retried" -> json.decodeFromJsonElement<RetriedEvent>(props)
                type == "session.next.compaction.started" -> json.decodeFromJsonElement<CompactionStartedEvent>(props)
                type == "session.next.compaction.delta" -> json.decodeFromJsonElement<CompactionDeltaEvent>(props)
                type == "session.next.compaction.ended" -> json.decodeFromJsonElement<CompactionEndedEvent>(props)
                type == "session.next.moved" -> json.decodeFromJsonElement<MovedEvent>(props)
                type == "session.next.interrupt.requested" -> json.decodeFromJsonElement<InterruptRequestedEvent>(props)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
}
```

**EventDeduplicator.kt:**
```kotlin
package dev.minios.ocremote.data.v2

class EventDeduplicator(private val maxSize: Int = 1000) {
    private val seen = LinkedHashSet<String>(maxSize)

    fun isDuplicate(event: SseEventV2): Boolean {
        val id = when (event) {
            is AgentSwitchedEvent -> event.id
            is ModelSwitchedEvent -> event.id
            is PromptedEvent -> event.id
            is PromptPromotedEvent -> event.id
            is ContextUpdatedEvent -> event.id
            is SyntheticEvent -> event.id
            is ShellStartedEvent -> event.id
            is ShellEndedEvent -> event.id
            is StepStartedEvent -> event.id
            is StepEndedEvent -> event.id
            is StepFailedEvent -> event.id
            is TextStartedEvent -> event.id
            is TextDeltaEvent -> event.id
            is TextEndedEvent -> event.id
            is ReasoningStartedEvent -> event.id
            is ReasoningDeltaEvent -> event.id
            is ReasoningEndedEvent -> event.id
            is ToolInputStartedEvent -> event.id
            is ToolInputDeltaEvent -> event.id
            is ToolInputEndedEvent -> event.id
            is ToolCalledEvent -> event.id
            is ToolProgressEvent -> event.id
            is ToolSuccessEvent -> event.id
            is ToolFailedEvent -> event.id
            is CompactionEndedEvent -> event.id
            // Passive events have no id — always allow
            else -> return false
        }
        return !seen.add(id)
    }

    fun reset() { seen.clear() }
}
```

- [ ] **Step 1: Create EventParser.kt** with `GlobalEvent` deserialization + type→subtype dispatch (31-way when)
- [ ] **Step 2: Create EventDeduplicator.kt** with LinkedHashSet LRU dedup
- [ ] **Step 3: Compile check** `.\gradlew :app:compileDevDebugKotlin`
- [ ] **Step 4: Commit**

---

### Task 4: 创建 EventReducer

**File:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/data/v2/EventReducer.kt`

**Context:** 纯函数 `reduce(state: SessionState, event: SseEventV2): SessionState`。对齐 `sync-v2.tsx:120-421`。核心操作：prepend 新消息到列表头部，Delta 直接更新 content 中的文本，Tool 状态机替换 state。

**EventReducer.kt:**
```kotlin
package dev.minios.ocremote.data.v2

object EventReducer {

    fun reduce(state: SessionState, event: SseEventV2): SessionState {
        return when (event) {
            // === 追加类（prepend 新消息到头部）===
            is AgentSwitchedEvent -> state.copy(
                messages = listOf(AgentSwitchedMessage(event.id, event.sessionID, event.agent, TimeInfo(created = now()))).plus(state.messages)
            )
            is ModelSwitchedEvent -> state.copy(
                messages = listOf(ModelSwitchedMessage(event.id, event.sessionID, event.model, TimeInfo(created = now()))).plus(state.messages)
            )
            is PromptedEvent -> state.copy(
                messages = listOf(UserMessage(event.id, event.sessionID, event.prompt.text, event.prompt.files, event.prompt.agents, event.prompt.references, TimeInfo(created = now()))).plus(state.messages)
            )
            is PromptPromotedEvent -> state.copy(
                messages = listOf(UserMessage(event.id, event.sessionID, event.prompt.text, event.prompt.files, event.prompt.agents, event.prompt.references, TimeInfo(created = now()))).plus(state.messages)
            )
            is ContextUpdatedEvent -> state.copy(
                messages = listOf(SystemMessage(event.id, event.sessionID, event.text, TimeInfo(created = now()))).plus(state.messages)
            )
            is SyntheticEvent -> state.copy(
                messages = listOf(SyntheticMessage(event.id, event.sessionID, event.text, TimeInfo(created = now()))).plus(state.messages)
            )
            is ShellStartedEvent -> state.copy(
                messages = listOf(ShellMessage(event.id, event.sessionID, event.callID, event.command, output = "", TimeInfo(created = now()))).plus(state.messages)
            )

            // === 更新类 ===
            is ShellEndedEvent -> updateMessage<ShellMessage>(state, event.id) { it.copy(output = event.output, time = it.time.copy(completed = now())) }
            is StepStartedEvent -> {
                // 关闭前一个 Assistant（设置 completed），创建新 Assistant
                val updated = state.messages.map { msg ->
                    if (msg is AssistantMessage && msg.time.completed == null)
                        msg.copy(time = msg.time.copy(completed = now()))
                    else msg
                }
                val newAssistant = AssistantMessage(event.id, event.sessionID, event.agent, event.model, content = emptyList(), time = TimeInfo(created = now()))
                state.copy(messages = listOf(newAssistant).plus(updated))
            }
            is StepEndedEvent -> updateMessage<AssistantMessage>(state, event.id) {
                it.copy(finish = event.finish, cost = event.cost, tokens = event.tokens, time = it.time.copy(completed = now()))
            }
            is StepFailedEvent -> updateMessage<AssistantMessage>(state, event.id) {
                it.copy(error = event.error, time = it.time.copy(completed = now()))
            }

            // === 流式 Delta：找到 Assistant 的 content 中对应项，直接拼接文本 ===
            is TextStartedEvent -> addContent(state, event.stepID, AssistantText(event.id, text = ""))
            is TextDeltaEvent -> updateContent(state, event.stepID, event.id) { (it as AssistantText).copy(text = it.text + event.delta) }
            is TextEndedEvent -> state // no-op for now
            is ReasoningStartedEvent -> addContent(state, event.stepID, AssistantReasoning(event.id, text = ""))
            is ReasoningDeltaEvent -> updateContent(state, event.stepID, event.id) { (it as AssistantReasoning).copy(text = it.text + event.delta) }
            is ReasoningEndedEvent -> state

            // === 工具状态机 ===
            is ToolInputStartedEvent -> addContent(state, event.stepID,
                AssistantTool(event.id, name = "", state = ToolStatePending(input = ""), time = TimeInfo(created = now()))
            )
            is ToolInputDeltaEvent -> updateContent(state, event.stepID, event.id) { content ->
                when (content) {
                    is AssistantTool -> content.copy(state = when (val s = content.state) {
                        is ToolStatePending -> s.copy(input = s.input + event.delta)
                        else -> s
                    })
                    else -> content
                }
            }
            is ToolInputEndedEvent -> state // input complete, wait for tool.called
            is ToolCalledEvent -> updateContent(state, event.stepID, event.id) { content ->
                when (content) {
                    is AssistantTool -> content.copy(name = event.name, state = ToolStateRunning(input = event.input))
                    else -> content
                }
            }
            is ToolProgressEvent -> updateContent(state, event.stepID, event.id) { content ->
                when (content) {
                    is AssistantTool -> content.copy(state = when (val s = content.state) {
                        is ToolStatePending -> ToolStateRunning(input = s.input)
                        is ToolStateRunning -> ToolStateRunning(input = s.input, structured = s.structured)
                        else -> s
                    })
                    else -> content
                }
            }
            is ToolSuccessEvent -> updateContent(state, event.stepID, event.id) { content ->
                when (content) {
                    is AssistantTool -> content.copy(
                        state = ToolStateCompleted(input = (content.state.input), outputPaths = event.outputPaths, result = event.result),
                        time = content.time.copy(completed = now())
                    )
                    else -> content
                }
            }
            is ToolFailedEvent -> updateContent(state, event.stepID, event.id) { content ->
                when (content) {
                    is AssistantTool -> content.copy(
                        state = ToolStateError(input = (content.state.input), error = event.error),
                        time = content.time.copy(completed = now())
                    )
                    else -> content
                }
            }

            // === 压缩 ===
            is CompactionEndedEvent -> state.copy(
                messages = listOf(CompactionMessage(event.id, event.sessionID, event.reason, event.summary, event.recent, TimeInfo(created = now()))).plus(state.messages)
            )

            // === 被动事件：不改变状态 ===
            is PromptAdmittedEvent, is RetriedEvent, is CompactionStartedEvent, is CompactionDeltaEvent, is MovedEvent, is InterruptRequestedEvent -> state
        }
    }

    // --- Helpers ---
    private fun now(): Long = System.currentTimeMillis()

    private inline fun <reified T : SessionMessage> updateMessage(state: SessionState, id: String, transform: (T) -> T): SessionState {
        return state.copy(messages = state.messages.map { if (it.id == id && it is T) transform(it) else it })
    }

    private fun addContent(state: SessionState, stepID: String, content: AssistantContent): SessionState {
        return state.copy(messages = state.messages.map { msg ->
            if (msg is AssistantMessage && msg.id == stepID) msg.copy(content = msg.content + content) else msg
        })
    }

    private fun updateContent(state: SessionState, stepID: String, contentID: String, transform: (AssistantContent) -> AssistantContent): SessionState {
        return state.copy(messages = state.messages.map { msg ->
            if (msg is AssistantMessage && msg.id == stepID)
                msg.copy(content = msg.content.map { if (it.id == contentID) transform(it) else it })
            else msg
        })
    }
}
```

- [ ] **Step 1: Create EventReducer.kt** with all 31 event cases + 3 helper methods
- [ ] **Step 2: Compile check** `.\gradlew :app:compileDevDebugKotlin`
- [ ] **Step 3: Commit**

---

### Task 5: 创建 SseConnectionManager

**File:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/data/v2/SseConnectionManager.kt`

**Context:** Ktor SSE 客户端，16ms 批处理窗口，指数退避重连（1s → 30s max）。使用 `kotlinx.coroutines.channels.Channel` 做事件缓冲。

**SseConnectionManager.kt:**
```kotlin
package dev.minios.ocremote.data.v2

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow

class SseConnectionManager(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val parser: EventParser = EventParser,
    private val deduplicator: EventDeduplicator = EventDeduplicator(),
) {
    private val batchWindowMs = 16L
    private val initialDelayMs = 1000L
    private val maxDelayMs = 30000L

    fun connect(): Flow<SseEventV2> = flow {
        var delayMs = initialDelayMs
        while (currentCoroutineContext().isActive) {
            try {
                val channel = Channel<SseEventV2>(Channel.UNLIMITED)
                val batchJob = launch { batchCollect(channel) { emit(it) } } // simplified — see below
                httpClient.prepareGet("$baseUrl/global/event") {
                    headers { append("Accept", "text/event-stream") }
                }.execute { response ->
                    val channel = response.bodyAsChannel()
                    while (!channel.isClosedForRead) {
                        val line = channel.readUTF8Line() ?: continue
                        if (line.startsWith("data: ")) {
                            val data = line.removePrefix("data: ")
                            val event = parser.parse(data)
                            if (event != null && deduplicator.isDuplicate(event).not()) {
                                emit(event) // direct emit for simplicity (no batch in MVP)
                            }
                        }
                    }
                }
                batchJob.cancel()
                return@flow // success
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(maxDelayMs)
            }
        }
    }

    // MVP: direct emit, batch can be added later
    private suspend fun batchCollect(channel: Channel<SseEventV2>, emit: suspend (SseEventV2) -> Unit) {
        // Placeholder for 16ms batch window implementation
    }
}
```

**Note:** 初始版本使用直接 emit，16ms 批处理窗口作为后续优化。Ktor SSE 需要 `"text/event-stream"` Accept header。

- [ ] **Step 1: Create SseConnectionManager.kt** with Ktor SSE connection + exponential backoff + event parsing pipeline
- [ ] **Step 2: Compile check** `.\gradlew :app:compileDevDebugKotlin`
- [ ] **Step 3: Commit**

---

### Task 6: 创建 OpenCodeV2Sdk

**File:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/data/v2/OpenCodeV2Sdk.kt`

**Context:** 接口 + 实现。封装 events、messages、prompt、abort 四个端点。

**OpenCodeV2Sdk.kt:**
```kotlin
package dev.minios.ocremote.data.v2

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

// --- Types ---
@Serializable
data class MessagesResponse(
    val data: List<SessionMessage>,
    val nextCursor: String? = null,
)

@Serializable
data class PromptRequest(
    val prompt: Prompt,
    val delivery: String = "steer",  // "steer" | "queue"
)

@Serializable
data class PromptResponse(
    val id: String,
    val sessionID: String,
)

// --- Interface ---
interface OpenCodeV2Sdk {
    fun events(): Flow<SseEventV2>
    suspend fun messages(sessionID: String, limit: Int = 200, cursor: String? = null): MessagesResponse
    suspend fun prompt(sessionID: String, text: String, delivery: String = "steer", files: List<FileAttachment>? = null): PromptResponse
    suspend fun abort(sessionID: String)
}

// --- Implementation ---
class OpenCodeV2SdkImpl(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val connectionManager: SseConnectionManager,
) : OpenCodeV2Sdk {

    override fun events(): Flow<SseEventV2> = connectionManager.connect()

    override suspend fun messages(sessionID: String, limit: Int, cursor: String?): MessagesResponse {
        return httpClient.get("$baseUrl/api/session/$sessionID/message") {
            parameter("limit", limit)
            cursor?.let { parameter("cursor", it) }
        }.body()
    }

    override suspend fun prompt(sessionID: String, text: String, delivery: String, files: List<FileAttachment>?): PromptResponse {
        return httpClient.post("$baseUrl/api/session/$sessionID/prompt") {
            setBody(PromptRequest(Prompt(text, files), delivery))
        }.body()
    }

    override suspend fun abort(sessionID: String) {
        httpClient.post("$baseUrl/api/session/$sessionID/abort")
    }
}
```

- [ ] **Step 1: Create OpenCodeV2Sdk.kt** with MessagesResponse, PromptRequest, PromptResponse, interface + impl
- [ ] **Step 2: Compile check** `.\gradlew :app:compileDevDebugKotlin`
- [ ] **Step 3: Commit**

---

### Task 7: 重写 ChatViewModel

**File:**
- Rewrite: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt`

**Context:** 从 24 StateFlow + 60 方法缩减到 ~300 行。核心变更：
1. 新增 `_state: MutableStateFlow<SessionState>` 替代消息相关 StateFlow
2. SSE → Reducer → state.update()
3. Hydration: REST GET → reconcile → replay buffer
4. 保留非消息功能（drafts, providers, agents, commands, terminal）不变
5. 推导 `chatMessages: StateFlow<List<ChatMessage>>` 给 UI
6. `isBusy` 从 `SessionState.isBusy` 推导

**实现要求：**
- 保留所有现有 public 方法签名（ChatScreen 依赖）
- 旧消息 StateFlow (`_messages`, `_isLoading`, `_isRefreshing`, `_error`, `_isSending`, `_hasOlderMessages`, `_isLoadingOlder`) 全部移除
- 新增 `_sessionState: MutableStateFlow<SessionState>`
- `loadSession()` → hydrate + SSE subscribe
- `sendMessage()` → sdk.prompt()
- 保留 `loadOlderMessages()` 但改用 `sdk.messages(cursor=...)`
- 所有非消息功能（draft, provider, agent, command, terminal, permission, question）**保留不变**

- [ ] **Step 1: Read current ChatViewModel.kt** (2069 lines) to understand method signatures
- [ ] **Step 2: Rewrite** — single MutableStateFlow<SessionState>, hydrate + SSE subscribe, preserve public API
- [ ] **Step 3: Compile check** `.\gradlew :app:compileDevDebugKotlin` — fix any compile errors
- [ ] **Step 4: Iterate** until compile passes
- [ ] **Step 5: Commit**

---

### Task 8: 适配 ChatScreen

**File:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt`

**Context:** ~50 行改动。将 `viewModel.messages.collectAsState()` 等改为新的 `viewModel.chatMessages.collectAsState()`。保持 UI 组件不变。

**改动范围：**
- `viewModel.messages` → `viewModel.chatMessages`
- `viewModel.isLoading` → `viewModel.isLoading` (保留，从 SessionState.isInitialized 推导)
- 业务状态 (BusyState, SendingState) 从 SessionState 推导
- 其他 UI 绑定适配新的 StateFlow 名称

- [ ] **Step 1: Read ChatScreen.kt** to identify all viewModel field references
- [ ] **Step 2: Adapt** all field references to new ChatViewModel API (~50 lines)
- [ ] **Step 3: Compile check** `.\gradlew :app:compileDevDebugKotlin` — fix any compile errors
- [ ] **Step 4: Commit**

---

### Task 9: 创建 EventReducerTest

**File:**
- Create: `app/src/test/kotlin/dev/minios/ocremote/data/v2/EventReducerTest.kt`

**Context:** 覆盖 Reducer 的 31 个 when 分支，验证：
1. 追加事件正确 prepend 新消息
2. Delta 事件正确拼接文本
3. Tool 状态机正确转换（pending → running → completed/error）
4. Step 生命周期正确管理 Assistant
5. 被动事件不改变状态
6. 消息列表顺序保持（最新在头部）

**测试用例（~10 个重点场景）：**
```kotlin
package dev.minios.ocremote.data.v2

import org.junit.Assert.*
import org.junit.Test
import com.google.common.truth.Truth.assertThat

class EventReducerTest {
    private val sessionId = "test-session"

    @Test fun `prompted event prepends UserMessage`() {
        val state = SessionState.EMPTY
        val event = PromptedEvent(sessionID = sessionId, id = "m1", prompt = Prompt(text = "hello"))
        val result = EventReducer.reduce(state, event)
        assertThat(result.messages).hasSize(1)
        assertThat(result.messages[0]).isInstanceOf(UserMessage::class.java)
        assertThat((result.messages[0] as UserMessage).text).isEqualTo("hello")
    }

    @Test fun `text delta appends to existing AssistantText`() {
        val assistant = AssistantMessage(id = "step1", sessionId = sessionId, agent = "default", model = ModelRef("p", "m"), content = listOf(AssistantText(id = "t1", text = "Hello")), time = TimeInfo(created = 0))
        val state = SessionState.EMPTY.copy(messages = listOf(assistant))
        val event = TextDeltaEvent(sessionID = sessionId, id = "t1", stepID = "step1", delta = " World")
        val result = EventReducer.reduce(state, event)
        val text = (result.messages[0] as AssistantMessage).content[0] as AssistantText
        assertThat(text.text).isEqualTo("Hello World")
    }

    @Test fun `tool state machine pending to running to completed`() {
        // Step: ToolInputStarted → ToolCalled → ToolSuccess
        val state = SessionState.EMPTY.copy(messages = listOf(
            AssistantMessage(id = "step1", sessionId = sessionId, agent = "default", model = ModelRef("p", "m"), content = emptyList(), time = TimeInfo(created = 0))
        ))
        val s1 = EventReducer.reduce(state, ToolInputStartedEvent(sessionID = sessionId, id = "t1", stepID = "step1"))
        val s2 = EventReducer.reduce(s1, ToolCalledEvent(sessionID = sessionId, id = "t1", stepID = "step1", name = "read", input = "{}"))
        val tool = (s2.messages[0] as AssistantMessage).content[0] as AssistantTool
        assertThat(tool.state).isInstanceOf(ToolStateRunning::class.java)
        assertThat(tool.name).isEqualTo("read")

        val s3 = EventReducer.reduce(s2, ToolSuccessEvent(sessionID = sessionId, id = "t1", stepID = "step1", result = "done"))
        val toolDone = (s3.messages[0] as AssistantMessage).content[0] as AssistantTool
        assertThat(toolDone.state).isInstanceOf(ToolStateCompleted::class.java)
    }

    @Test fun `step started closes previous assistant`() {
        val old = AssistantMessage(id = "old", sessionId = sessionId, agent = "a", model = ModelRef("p", "m"), content = emptyList(), time = TimeInfo(created = 0))
        val state = SessionState.EMPTY.copy(messages = listOf(old))
        val event = StepStartedEvent(sessionID = sessionId, id = "new", agent = "b", model = ModelRef("p2", "m2"))
        val result = EventReducer.reduce(state, event)
        assertThat(result.messages).hasSize(2)
        // Old assistant should now have completed time
        assertThat((result.messages[1] as AssistantMessage).time.completed).isNotNull()
        // New assistant at head
        assertThat(result.messages[0].id).isEqualTo("new")
    }

    @Test fun `shell lifecycle`() {
        val s1 = EventReducer.reduce(SessionState.EMPTY, ShellStartedEvent(sessionID = sessionId, id = "sh1", callID = "c1", command = "ls"))
        val shell1 = s1.messages[0] as ShellMessage
        assertThat(shell1.output).isEmpty()
        val s2 = EventReducer.reduce(s1, ShellEndedEvent(sessionID = sessionId, id = "sh1", callID = "c1", output = "file.txt"))
        assertThat((s2.messages[0] as ShellMessage).output).isEqualTo("file.txt")
    }

    @Test fun `passive events do not change state`() {
        val state = SessionState.EMPTY.copy(messages = listOf(SystemMessage("s1", sessionId, "test", TimeInfo(created = 0))))
        val result = EventReducer.reduce(state, PromptAdmittedEvent(sessionID = sessionId))
        assertThat(result).isEqualTo(state)
    }

    @Test fun `messages order newest first`() {
        val s1 = EventReducer.reduce(SessionState.EMPTY, PromptedEvent(sessionID = sessionId, id = "m1", prompt = Prompt(text = "first")))
        val s2 = EventReducer.reduce(s1, PromptedEvent(sessionID = sessionId, id = "m2", prompt = Prompt(text = "second")))
        assertThat(s2.messages).hasSize(2)
        assertThat((s2.messages[0] as UserMessage).text).isEqualTo("second")
        assertThat((s2.messages[1] as UserMessage).text).isEqualTo("first")
    }
}
```

- [ ] **Step 1: Create EventReducerTest.kt** with 7+ test cases covering all event categories
- [ ] **Step 2: Run tests** `.\gradlew :app:testDevDebugUnitTest --tests "dev.minios.ocremote.data.v2.EventReducerTest"` — expected: all pass
- [ ] **Step 3: Commit**

---

### Task 10: 全量编译 + 测试验证

- [ ] **Step 1: Run ALL unit tests** `.\gradlew :app:testDevDebugUnitTest --rerun` (timeout: 300s)
  - Expected: all tests pass, no regressions
- [ ] **Step 2: Full build** `.\gradlew :app:assembleDevRelease` (timeout: 300s)
  - Expected: BUILD SUCCESSFUL
- [ ] **Step 3: Fix any failures** — iterate until 100% pass
- [ ] **Step 4: Commit final changes**

