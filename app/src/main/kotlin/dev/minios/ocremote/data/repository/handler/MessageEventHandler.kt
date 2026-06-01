package dev.minios.ocremote.data.repository.handler

import dev.minios.ocremote.domain.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles message and part events: updated, removed, part updated/delta/removed.
 * Manages: messages, parts
 */
@Singleton
class MessageEventHandler @Inject constructor() : SseEventHandler {

    companion object {
        private const val TAG = "MessageEventHandler"
    }

    private val _messages = MutableStateFlow<Map<String, List<Message>>>(emptyMap())
    val messages: StateFlow<Map<String, List<Message>>> = _messages.asStateFlow()

    private val _parts = MutableStateFlow<Map<String, List<Part>>>(emptyMap())
    val parts: StateFlow<Map<String, List<Part>>> = _parts.asStateFlow()

    override fun handle(event: SseEvent, serverId: String): Boolean {
        return when (event) {
            is SseEvent.MessageUpdated -> { handleMessageUpdated(event); true }
            is SseEvent.MessageRemoved -> { handleMessageRemoved(event); true }
            is SseEvent.MessagePartUpdated -> { handleMessagePartUpdated(event); true }
            is SseEvent.MessagePartDelta -> { handleMessagePartDelta(event); true }
            is SseEvent.MessagePartRemoved -> { handleMessagePartRemoved(event); true }
            else -> false
        }
    }

    private fun handleMessageUpdated(event: SseEvent.MessageUpdated) {
        val sessionId = event.info.sessionId
        _messages.update { current ->
            val sessionMessages = current[sessionId]?.toMutableList() ?: mutableListOf()
            val idx = sessionMessages.indexOfFirst { it.id == event.info.id }
            if (idx >= 0) {
                sessionMessages[idx] = event.info
            } else {
                sessionMessages.add(event.info)
                sessionMessages.sortBy { it.time.created }
            }
            current + (sessionId to sessionMessages)
        }
    }

    private fun handleMessageRemoved(event: SseEvent.MessageRemoved) {
        _messages.update { current ->
            val sessionMessages = current[event.sessionId]?.filter { it.id != event.messageId }
            if (sessionMessages != null) current + (event.sessionId to sessionMessages) else current
        }
        _parts.update { it - event.messageId }
    }

    private fun handleMessagePartUpdated(event: SseEvent.MessagePartUpdated) {
        val messageId = event.part.messageId
        _parts.update { current ->
            val messageParts = current[messageId]?.toMutableList() ?: mutableListOf()
            val idx = messageParts.indexOfFirst { it.id == event.part.id }
            if (idx >= 0) {
                messageParts[idx] = mergeToolInput(messageParts[idx], event.part)
            } else {
                messageParts.add(event.part)
            }
            current + (messageId to messageParts)
        }
    }

    /**
     * Preserve tool input from previous state when the new state has empty input.
     * Server may omit input in running/completed events, causing deserialization to default to emptyMap().
     */
    private fun mergeToolInput(old: Part, new: Part): Part {
        if (old is Part.Tool && new is Part.Tool) {
            val oldInput = toolStateInput(old.state)
            if (oldInput.isNotEmpty()) {
                val newState = when (val s = new.state) {
                    is ToolState.Pending -> if (s.input.isEmpty()) s.copy(input = oldInput) else s
                    is ToolState.Running -> if (s.input.isEmpty()) s.copy(input = oldInput) else s
                    is ToolState.Completed -> if (s.input.isEmpty()) s.copy(input = oldInput) else s
                    is ToolState.Error -> if (s.input.isEmpty()) s.copy(input = oldInput) else s
                }
                return if (newState !== new.state) new.copy(state = newState) else new
            }
        }
        return new
    }

    private fun toolStateInput(state: ToolState): Map<String, kotlinx.serialization.json.JsonElement> {
        return when (state) {
            is ToolState.Pending -> state.input
            is ToolState.Running -> state.input
            is ToolState.Completed -> state.input
            is ToolState.Error -> state.input
        }
    }

    private fun handleMessagePartDelta(event: SseEvent.MessagePartDelta) {
        _parts.update { current ->
            val messageParts = current[event.messageId]?.toMutableList() ?: return@update current
            val idx = messageParts.indexOfFirst { it.id == event.partId }
            if (idx < 0) return@update current
            val part = messageParts[idx]
            val updated = when (part) {
                is Part.Text -> part.copy(text = part.text + event.delta)
                is Part.Reasoning -> part.copy(text = part.text + event.delta)
                else -> part
            }
            messageParts[idx] = updated
            current + (event.messageId to messageParts)
        }
    }

    private fun handleMessagePartRemoved(event: SseEvent.MessagePartRemoved) {
        _parts.update { current ->
            val messageParts = current[event.messageId]?.filter { it.id != event.partId }
            if (messageParts != null) current + (event.messageId to messageParts) else current
        }
    }

    // ============ Batch Operations ============

    fun setMessages(sessionId: String, newMessages: List<MessageWithParts>) {
        _messages.update { it + (sessionId to newMessages.map { m -> m.info }.sortedBy { m -> m.time.created }) }
        val partsMap = newMessages.associate { it.info.id to it.parts }
        _parts.update { it + partsMap }
    }

    fun mergeMessages(sessionId: String, newMessages: List<MessageWithParts>) {
        val incoming = newMessages.map { it.info }.sortedBy { m -> m.time.created }
        _messages.update { current ->
            val existing = current[sessionId] ?: emptyList()
            val existingById = existing.associateBy { it.id }
            current + (sessionId to incoming.map { newMsg -> existingById[newMsg.id] ?: newMsg })
        }
        _parts.update { currentParts ->
            val existingKeys = currentParts.keys
            val newParts = newMessages
                .filter { it.info.id !in existingKeys }
                .associate { it.info.id to it.parts }
            currentParts + newParts
        }
    }

    fun clearForSession(sessionId: String) {
        _messages.update { it - sessionId }
    }

    fun clearForServer(sessionIds: Set<String>) {
        val messageIds = _messages.value
            .filterKeys { it in sessionIds }.values.flatten()
            .map { it.id }.toSet()
        _messages.update { it - sessionIds }
        _parts.update { it - messageIds }
    }

    fun clearAll() {
        _messages.value = emptyMap()
        _parts.value = emptyMap()
    }

    /**
     * Mark all incomplete assistant messages in a session as completed.
     * Called when REST fallback detects server is idle but UI shows streaming.
     */
    fun markSessionIdle(sessionId: String) {
        val current = _messages.value[sessionId] ?: return
        val now = System.currentTimeMillis()
        val updated = current.map { msg ->
            if (msg is Message.Assistant && msg.time.completed == null) {
                msg.copy(time = msg.time.copy(completed = now))
            } else {
                msg
            }
        }
        _messages.value = _messages.value + (sessionId to updated)

        // Mark all incomplete Reasoning parts with time.end
        val messageIds = current.map { it.id }
        var partsUpdated = _parts.value
        for (msgId in messageIds) {
            val msgParts = partsUpdated[msgId]
            if (msgParts != null) {
                val updatedParts = msgParts.map { part ->
                    if (part is Part.Reasoning && part.time?.end == null) {
                        part.copy(time = Part.Reasoning.Time(
                            start = part.time?.start ?: now,
                            end = now
                        ))
                    } else {
                        part
                    }
                }
                partsUpdated = partsUpdated + (msgId to updatedParts)
            }
        }
        _parts.value = partsUpdated
    }
}
