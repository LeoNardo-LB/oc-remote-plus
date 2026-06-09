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
            // Passive events have no id — always allow through
            else -> return false
        }
        return !seen.add(id)
    }

    fun reset() { seen.clear() }
}
