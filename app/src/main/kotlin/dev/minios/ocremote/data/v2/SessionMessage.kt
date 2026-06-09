package dev.minios.ocremote.data.v2

import kotlinx.serialization.SerialName
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

@Serializable
@SerialName("pending")
data class ToolStatePending(override val input: String = "") : ToolState {
    override val status = "pending"
}

@Serializable
@SerialName("running")
data class ToolStateRunning(
    override val input: String = "",
    val structured: String? = null,
    val content: List<String>? = null,
) : ToolState {
    override val status = "running"
}

@Serializable
@SerialName("completed")
data class ToolStateCompleted(
    override val input: String = "",
    val structured: String? = null,
    val content: List<String>? = null,
    val attachments: List<FileAttachment>? = null,
    val outputPaths: List<String>? = null,
    val result: String? = null,
) : ToolState {
    override val status = "completed"
}

@Serializable
@SerialName("error")
data class ToolStateError(
    override val input: String = "",
    val structured: String? = null,
    val content: List<String>? = null,
    val error: String? = null,
    val result: String? = null,
) : ToolState {
    override val status = "error"
}

// --- AssistantContent (3 种) ---
@Serializable
@SerialName("text")
data class AssistantText(
    override val id: String = "",
    val text: String = "",
) : AssistantContent

@Serializable
@SerialName("reasoning")
data class AssistantReasoning(
    override val id: String = "",
    val text: String = "",
) : AssistantContent

@Serializable
@SerialName("tool")
data class AssistantTool(
    override val id: String = "",
    val name: String = "",
    val state: ToolState = ToolStatePending(),
    val time: TimeInfo = TimeInfo(created = 0L),
) : AssistantContent

// --- SessionMessage (8 种) ---
@Serializable
@SerialName("user")
data class UserMessage(
    override val id: String = "",
    override val sessionId: String = "",
    val text: String = "",
    val files: List<FileAttachment>? = null,
    val agents: List<String>? = null,
    val references: List<String>? = null,
    val time: TimeInfo = TimeInfo(),
) : SessionMessage

@Serializable
@SerialName("assistant")
data class AssistantMessage(
    override val id: String = "",
    override val sessionId: String = "",
    val agent: String = "",
    val model: ModelRef = ModelRef(),
    val content: List<AssistantContent> = emptyList(),
    val snapshot: SnapshotInfo? = null,
    val finish: String? = null,
    val cost: Double? = null,
    val tokens: TokenUsage? = null,
    val error: String? = null,
    val time: TimeInfo = TimeInfo(),
) : SessionMessage

@Serializable
@SerialName("shell")
data class ShellMessage(
    override val id: String = "",
    override val sessionId: String = "",
    val callId: String = "",
    val command: String = "",
    val output: String = "",
    val time: TimeInfo = TimeInfo(),
) : SessionMessage

@Serializable
@SerialName("system")
data class SystemMessage(
    override val id: String = "",
    override val sessionId: String = "",
    val text: String = "",
    val time: TimeInfo = TimeInfo(),
) : SessionMessage

@Serializable
@SerialName("synthetic")
data class SyntheticMessage(
    override val id: String = "",
    override val sessionId: String = "",
    val text: String = "",
    val time: TimeInfo = TimeInfo(),
) : SessionMessage

@Serializable
@SerialName("agent-switched")
data class AgentSwitchedMessage(
    override val id: String = "",
    override val sessionId: String = "",
    val agent: String = "",
    val time: TimeInfo = TimeInfo(),
) : SessionMessage

@Serializable
@SerialName("model-switched")
data class ModelSwitchedMessage(
    override val id: String = "",
    override val sessionId: String = "",
    val model: ModelRef = ModelRef(),
    val time: TimeInfo = TimeInfo(),
) : SessionMessage

@Serializable
@SerialName("compaction")
data class CompactionMessage(
    override val id: String = "",
    override val sessionId: String = "",
    val reason: String = "",
    val summary: String = "",
    val recent: String = "",
    val time: TimeInfo = TimeInfo(),
) : SessionMessage
