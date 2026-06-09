package dev.minios.ocremote.data.v2

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.JsonObject

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

    private fun parse(type: String, props: JsonObject): SseEventV2? {
        return try {
            when (type) {
                "session.next.agent.switched" -> json.decodeFromJsonElement<AgentSwitchedEvent>(props)
                "session.next.model.switched" -> json.decodeFromJsonElement<ModelSwitchedEvent>(props)
                "session.next.prompted" -> json.decodeFromJsonElement<PromptedEvent>(props)
                "session.next.prompt.admitted" -> json.decodeFromJsonElement<PromptAdmittedEvent>(props)
                "session.next.prompt.promoted" -> json.decodeFromJsonElement<PromptPromotedEvent>(props)
                "session.next.context.updated" -> json.decodeFromJsonElement<ContextUpdatedEvent>(props)
                "session.next.synthetic" -> json.decodeFromJsonElement<SyntheticEvent>(props)
                "session.next.shell.started" -> json.decodeFromJsonElement<ShellStartedEvent>(props)
                "session.next.shell.ended" -> json.decodeFromJsonElement<ShellEndedEvent>(props)
                "session.next.step.started" -> json.decodeFromJsonElement<StepStartedEvent>(props)
                "session.next.step.ended" -> json.decodeFromJsonElement<StepEndedEvent>(props)
                "session.next.step.failed" -> json.decodeFromJsonElement<StepFailedEvent>(props)
                "session.next.text.started" -> json.decodeFromJsonElement<TextStartedEvent>(props)
                "session.next.text.delta" -> json.decodeFromJsonElement<TextDeltaEvent>(props)
                "session.next.text.ended" -> json.decodeFromJsonElement<TextEndedEvent>(props)
                "session.next.reasoning.started" -> json.decodeFromJsonElement<ReasoningStartedEvent>(props)
                "session.next.reasoning.delta" -> json.decodeFromJsonElement<ReasoningDeltaEvent>(props)
                "session.next.reasoning.ended" -> json.decodeFromJsonElement<ReasoningEndedEvent>(props)
                "session.next.tool.input.started" -> json.decodeFromJsonElement<ToolInputStartedEvent>(props)
                "session.next.tool.input.delta" -> json.decodeFromJsonElement<ToolInputDeltaEvent>(props)
                "session.next.tool.input.ended" -> json.decodeFromJsonElement<ToolInputEndedEvent>(props)
                "session.next.tool.called" -> json.decodeFromJsonElement<ToolCalledEvent>(props)
                "session.next.tool.progress" -> json.decodeFromJsonElement<ToolProgressEvent>(props)
                "session.next.tool.success" -> json.decodeFromJsonElement<ToolSuccessEvent>(props)
                "session.next.tool.failed" -> json.decodeFromJsonElement<ToolFailedEvent>(props)
                "session.next.retried" -> json.decodeFromJsonElement<RetriedEvent>(props)
                "session.next.compaction.started" -> json.decodeFromJsonElement<CompactionStartedEvent>(props)
                "session.next.compaction.delta" -> json.decodeFromJsonElement<CompactionDeltaEvent>(props)
                "session.next.compaction.ended" -> json.decodeFromJsonElement<CompactionEndedEvent>(props)
                "session.next.moved" -> json.decodeFromJsonElement<MovedEvent>(props)
                "session.next.interrupt.requested" -> json.decodeFromJsonElement<InterruptRequestedEvent>(props)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
}
