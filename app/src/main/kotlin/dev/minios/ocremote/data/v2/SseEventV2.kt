package dev.minios.ocremote.data.v2

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// SSE Envelope (what we receive from the wire)
@Serializable
data class GlobalEvent(
    val directory: String,
    val workspace: String? = null,
    val payload: SseEventPayload,
)

@Serializable
data class SseEventPayload(
    val type: String,
    val properties: JsonObject,
)

// Base sealed interface for all V2 events
@Serializable
sealed interface SseEventV2

// ========================================================================
// 追加类（7 种 — 创建新消息）
// ========================================================================

@Serializable
@SerialName("session.next.agent.switched")
data class AgentSwitchedEvent(
    val sessionID: String,
    val agent: String,
    val id: String,
) : SseEventV2

@Serializable
@SerialName("session.next.model.switched")
data class ModelSwitchedEvent(
    val sessionID: String,
    val model: ModelRef,
    val id: String,
) : SseEventV2

@Serializable
@SerialName("session.next.prompted")
data class PromptedEvent(
    val sessionID: String,
    val id: String,
    val prompt: Prompt,
) : SseEventV2

@Serializable
@SerialName("session.next.prompt.promoted")
data class PromptPromotedEvent(
    val sessionID: String,
    val id: String,
    val prompt: Prompt,
) : SseEventV2

@Serializable
@SerialName("session.next.context.updated")
data class ContextUpdatedEvent(
    val sessionID: String,
    val text: String,
    val id: String,
) : SseEventV2

@Serializable
@SerialName("session.next.synthetic")
data class SyntheticEvent(
    val sessionID: String,
    val id: String,
    val text: String,
) : SseEventV2

@Serializable
@SerialName("session.next.shell.started")
data class ShellStartedEvent(
    val sessionID: String,
    val id: String,
    val callID: String,
    val command: String,
) : SseEventV2

// ========================================================================
// 更新类（4 种 — 修改已有消息）
// ========================================================================

@Serializable
@SerialName("session.next.shell.ended")
data class ShellEndedEvent(
    val sessionID: String,
    val id: String,
    val callID: String,
    val output: String,
) : SseEventV2

@Serializable
@SerialName("session.next.step.started")
data class StepStartedEvent(
    val sessionID: String,
    val id: String,
    val agent: String,
    val model: ModelRef,
) : SseEventV2

@Serializable
@SerialName("session.next.step.ended")
data class StepEndedEvent(
    val sessionID: String,
    val id: String,
    val finish: String,
    val cost: Double,
    val tokens: TokenUsage,
) : SseEventV2

@Serializable
@SerialName("session.next.step.failed")
data class StepFailedEvent(
    val sessionID: String,
    val id: String,
    val error: String,
) : SseEventV2

// ========================================================================
// 流式 Delta（6 种 — 直接更新 Assistant.content）
// ========================================================================

@Serializable
@SerialName("session.next.text.started")
data class TextStartedEvent(
    val sessionID: String,
    val id: String,
    val stepID: String,
) : SseEventV2

@Serializable
@SerialName("session.next.text.delta")
data class TextDeltaEvent(
    val sessionID: String,
    val id: String,
    val stepID: String,
    val delta: String,
) : SseEventV2

@Serializable
@SerialName("session.next.text.ended")
data class TextEndedEvent(
    val sessionID: String,
    val id: String,
    val stepID: String,
) : SseEventV2

@Serializable
@SerialName("session.next.reasoning.started")
data class ReasoningStartedEvent(
    val sessionID: String,
    val id: String,
    val stepID: String,
) : SseEventV2

@Serializable
@SerialName("session.next.reasoning.delta")
data class ReasoningDeltaEvent(
    val sessionID: String,
    val id: String,
    val stepID: String,
    val delta: String,
) : SseEventV2

@Serializable
@SerialName("session.next.reasoning.ended")
data class ReasoningEndedEvent(
    val sessionID: String,
    val id: String,
    val stepID: String,
) : SseEventV2

// ========================================================================
// 工具状态机（7 种 — Tool 生命周期）
// ========================================================================

@Serializable
@SerialName("session.next.tool.input.started")
data class ToolInputStartedEvent(
    val sessionID: String,
    val id: String,
    val stepID: String,
) : SseEventV2

@Serializable
@SerialName("session.next.tool.input.delta")
data class ToolInputDeltaEvent(
    val sessionID: String,
    val id: String,
    val stepID: String,
    val delta: String,
) : SseEventV2

@Serializable
@SerialName("session.next.tool.input.ended")
data class ToolInputEndedEvent(
    val sessionID: String,
    val id: String,
    val stepID: String,
) : SseEventV2

@Serializable
@SerialName("session.next.tool.called")
data class ToolCalledEvent(
    val sessionID: String,
    val id: String,
    val stepID: String,
    val name: String,
    val input: String,
) : SseEventV2

@Serializable
@SerialName("session.next.tool.progress")
data class ToolProgressEvent(
    val sessionID: String,
    val id: String,
    val stepID: String,
) : SseEventV2

@Serializable
@SerialName("session.next.tool.success")
data class ToolSuccessEvent(
    val sessionID: String,
    val id: String,
    val stepID: String,
    val outputPaths: List<String>? = null,
    val result: String? = null,
) : SseEventV2

@Serializable
@SerialName("session.next.tool.failed")
data class ToolFailedEvent(
    val sessionID: String,
    val id: String,
    val stepID: String,
    val error: String,
) : SseEventV2

// ========================================================================
// 压缩（1 种）
// ========================================================================

@Serializable
@SerialName("session.next.compaction.ended")
data class CompactionEndedEvent(
    val sessionID: String,
    val id: String,
    val reason: String,
    val summary: String,
    val recent: String,
) : SseEventV2

// ========================================================================
// 被动事件（6 种 — 不改变 SessionState，仅用于日志/调试）
// ========================================================================

@Serializable
@SerialName("session.next.prompt.admitted")
data class PromptAdmittedEvent(
    val sessionID: String,
) : SseEventV2

@Serializable
@SerialName("session.next.retried")
data class RetriedEvent(
    val sessionID: String,
) : SseEventV2

@Serializable
@SerialName("session.next.compaction.started")
data class CompactionStartedEvent(
    val sessionID: String,
) : SseEventV2

@Serializable
@SerialName("session.next.compaction.delta")
data class CompactionDeltaEvent(
    val sessionID: String,
    val delta: String,
) : SseEventV2

@Serializable
@SerialName("session.next.moved")
data class MovedEvent(
    val sessionID: String,
) : SseEventV2

@Serializable
@SerialName("session.next.interrupt.requested")
data class InterruptRequestedEvent(
    val sessionID: String,
) : SseEventV2
