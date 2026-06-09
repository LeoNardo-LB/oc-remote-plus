package dev.minios.ocremote.data.v2

object EventReducer {

    fun reduce(state: SessionState, event: SseEventV2): SessionState {
        return when (event) {
            // === Append Events (prepend new message to head) ===
            is AgentSwitchedEvent -> state.copy(
                messages = listOf(
                    AgentSwitchedMessage(event.id, event.sessionID, event.agent, TimeInfo(created = now()))
                ) + state.messages
            )
            is ModelSwitchedEvent -> state.copy(
                messages = listOf(
                    ModelSwitchedMessage(event.id, event.sessionID, event.model, TimeInfo(created = now()))
                ) + state.messages
            )
            is PromptedEvent -> state.copy(
                messages = listOf(
                    UserMessage(
                        event.id, event.sessionID, event.prompt.text,
                        event.prompt.files, event.prompt.agents, event.prompt.references,
                        TimeInfo(created = now())
                    )
                ) + state.messages
            )
            is PromptPromotedEvent -> state.copy(
                messages = listOf(
                    UserMessage(
                        event.id, event.sessionID, event.prompt.text,
                        event.prompt.files, event.prompt.agents, event.prompt.references,
                        TimeInfo(created = now())
                    )
                ) + state.messages
            )
            is ContextUpdatedEvent -> state.copy(
                messages = listOf(
                    SystemMessage(event.id, event.sessionID, event.text, TimeInfo(created = now()))
                ) + state.messages
            )
            is SyntheticEvent -> state.copy(
                messages = listOf(
                    SyntheticMessage(event.id, event.sessionID, event.text, TimeInfo(created = now()))
                ) + state.messages
            )
            is ShellStartedEvent -> state.copy(
                messages = listOf(
                    ShellMessage(
                        event.id, event.sessionID, event.callID, event.command,
                        output = "", TimeInfo(created = now())
                    )
                ) + state.messages
            )

            // === Update Events ===
            is ShellEndedEvent -> updateMessage<ShellMessage>(state, event.id) {
                it.copy(output = event.output, time = it.time.copy(completed = now()))
            }
            is StepStartedEvent -> {
                // Close previous Assistant (set completed time), create new one
                val updated = state.messages.map { msg ->
                    if (msg is AssistantMessage && msg.time.completed == null)
                        msg.copy(time = msg.time.copy(completed = now()))
                    else msg
                }
                val newAssistant = AssistantMessage(
                    event.id, event.sessionID, event.agent, event.model,
                    content = emptyList(), time = TimeInfo(created = now())
                )
                state.copy(messages = listOf(newAssistant) + updated)
            }
            is StepEndedEvent -> updateMessage<AssistantMessage>(state, event.id) {
                it.copy(
                    finish = event.finish, cost = event.cost, tokens = event.tokens,
                    time = it.time.copy(completed = now())
                )
            }
            is StepFailedEvent -> updateMessage<AssistantMessage>(state, event.id) {
                it.copy(error = event.error, time = it.time.copy(completed = now()))
            }

            // === Streaming Deltas: find Assistant.content item, append text ===
            is TextStartedEvent -> addContent(state, event.stepID, AssistantText(event.id, text = ""))
            is TextDeltaEvent -> updateContent(state, event.stepID, event.id) { content ->
                when (content) {
                    is AssistantText -> content.copy(text = content.text + event.delta)
                    else -> content
                }
            }
            is TextEndedEvent -> state

            is ReasoningStartedEvent -> addContent(state, event.stepID, AssistantReasoning(event.id, text = ""))
            is ReasoningDeltaEvent -> updateContent(state, event.stepID, event.id) { content ->
                when (content) {
                    is AssistantReasoning -> content.copy(text = content.text + event.delta)
                    else -> content
                }
            }
            is ReasoningEndedEvent -> state

            // === Tool State Machine ===
            is ToolInputStartedEvent -> addContent(
                state, event.stepID,
                AssistantTool(
                    event.id, name = "", state = ToolStatePending(input = ""),
                    time = TimeInfo(created = now())
                )
            )
            is ToolInputDeltaEvent -> updateContent(state, event.stepID, event.id) { content ->
                when (content) {
                    is AssistantTool -> content.copy(
                        state = when (val s = content.state) {
                            is ToolStatePending -> s.copy(input = s.input + event.delta)
                            else -> s
                        }
                    )
                    else -> content
                }
            }
            is ToolInputEndedEvent -> state
            is ToolCalledEvent -> updateContent(state, event.stepID, event.id) { content ->
                when (content) {
                    is AssistantTool -> content.copy(
                        name = event.name,
                        state = ToolStateRunning(input = event.input)
                    )
                    else -> content
                }
            }
            is ToolProgressEvent -> updateContent(state, event.stepID, event.id) { content ->
                when (content) {
                    is AssistantTool -> content.copy(
                        state = when (val s = content.state) {
                            is ToolStatePending -> ToolStateRunning(input = s.input)
                            is ToolStateRunning -> s  // keep as-is
                            else -> s
                        }
                    )
                    else -> content
                }
            }
            is ToolSuccessEvent -> updateContent(state, event.stepID, event.id) { content ->
                when (content) {
                    is AssistantTool -> content.copy(
                        state = ToolStateCompleted(
                            input = (content.state as? ToolStateRunning)?.input ?: content.state.input,
                            outputPaths = event.outputPaths,
                            result = event.result
                        ),
                        time = content.time.copy(completed = now())
                    )
                    else -> content
                }
            }
            is ToolFailedEvent -> updateContent(state, event.stepID, event.id) { content ->
                when (content) {
                    is AssistantTool -> content.copy(
                        state = ToolStateError(
                            input = (content.state as? ToolStateRunning)?.input ?: content.state.input,
                            error = event.error
                        ),
                        time = content.time.copy(completed = now())
                    )
                    else -> content
                }
            }

            // === Compaction ===
            is CompactionEndedEvent -> state.copy(
                messages = listOf(
                    CompactionMessage(
                        event.id, event.sessionID, event.reason,
                        event.summary, event.recent, TimeInfo(created = now())
                    )
                ) + state.messages
            )

            // === Passive Events: no state change ===
            is PromptAdmittedEvent, is RetriedEvent,
            is CompactionStartedEvent, is CompactionDeltaEvent,
            is MovedEvent, is InterruptRequestedEvent -> state
        }
    }

    // --- Private Helpers ---
    private fun now(): Long = System.currentTimeMillis()

    private inline fun <reified T : SessionMessage> updateMessage(
        state: SessionState, id: String, transform: (T) -> T
    ): SessionState {
        return state.copy(
            messages = state.messages.map { msg ->
                if (msg.id == id && msg is T) transform(msg) else msg
            }
        )
    }

    private fun addContent(
        state: SessionState, stepID: String, content: AssistantContent
    ): SessionState {
        return state.copy(
            messages = state.messages.map { msg ->
                if (msg is AssistantMessage && msg.id == stepID)
                    msg.copy(content = msg.content + content)
                else msg
            }
        )
    }

    private fun updateContent(
        state: SessionState, stepID: String, contentID: String,
        transform: (AssistantContent) -> AssistantContent
    ): SessionState {
        return state.copy(
            messages = state.messages.map { msg ->
                if (msg is AssistantMessage && msg.id == stepID)
                    msg.copy(content = msg.content.map { content ->
                        if (content.id == contentID) transform(content) else content
                    })
                else msg
            }
        )
    }
}
