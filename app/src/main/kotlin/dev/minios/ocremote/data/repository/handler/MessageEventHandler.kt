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
                messageParts[idx] = mergePart(messageParts[idx], event.part)
            } else {
                messageParts.add(event.part)
            }
            current + (messageId to messageParts)
        }
    }

    /**
     * Merge Part update: for Text/Reasoning, keep the longer text content.
     * Server may send intermediate snapshots during streaming whose text is
     * shorter than what the client has already accumulated via deltas.
     */
    private fun mergePart(existing: Part, incoming: Part): Part {
        return when {
            existing is Part.Text && incoming is Part.Text -> {
                if (incoming.text.length >= existing.text.length) incoming
                else existing.copy(
                    text = existing.text,
                    time = incoming.time,
                    metadata = incoming.metadata
                )
            }
            existing is Part.Reasoning && incoming is Part.Reasoning -> {
                if (incoming.text.length >= existing.text.length) incoming
                else existing.copy(
                    text = existing.text,
                    time = incoming.time,
                    metadata = incoming.metadata
                )
            }
            else -> incoming
        }
    }

    private fun mergePartsList(existingParts: List<Part>, incomingParts: List<Part>): List<Part> {
        val existingById = existingParts.associateBy { it.id }
        return incomingParts.map { incoming ->
            val existing = existingById[incoming.id]
            if (existing != null) mergePart(existing, incoming) else incoming
        }
    }

    /**
     * Merge SSE and REST versions of a message.
     * SSE is fresher for content (streaming), but REST may have completion
     * info that SSE hasn't delivered yet.
     */
    private fun mergeMessageMeta(sse: Message, rest: Message): Message {
        // For User messages: REST is authoritative (no streaming)
        if (sse is Message.User) return rest
        if (sse !is Message.Assistant) return rest

        // For Assistant messages:
        // - If SSE says completed (streaming finished), trust SSE completely
        // - If SSE says NOT completed but REST says completed, trust REST's completed time
        //   but keep SSE's other fields (finish, tokens, cost may be fresher)
        return if (sse.time.completed != null) {
            sse  // SSE has final state, prefer it
        } else if (rest.time.completed != null) {
            // REST says completed but SSE hasn't seen it yet — merge completed time
            sse.copy(time = sse.time.copy(completed = rest.time.completed))
        } else {
            // Neither has completed — prefer SSE (fresher streaming state)
            sse
        }
    }

    private fun handleMessagePartDelta(event: SseEvent.MessagePartDelta) {
        _parts.update { current ->
            val messageParts = current[event.messageId]?.toMutableList() ?: mutableListOf()
            val idx = messageParts.indexOfFirst { it.id == event.partId }
            if (idx >= 0) {
                val part = messageParts[idx]
                val updated = when (part) {
                    is Part.Text -> part.copy(text = part.text + event.delta)
                    is Part.Reasoning -> part.copy(text = part.text + event.delta)
                    else -> part
                }
                messageParts[idx] = updated
            } else {
                // Defensive: create synthetic part when partId not found
                val syntheticPart = Part.Text(
                    id = event.partId,
                    sessionId = event.sessionId,
                    messageId = event.messageId,
                    text = event.delta
                )
                messageParts.add(syntheticPart)
            }
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
        val incomingMessageIds = newMessages.map { it.info.id }.toSet()
        _messages.update { current ->
            val existing = current[sessionId] ?: emptyList()
            val incomingById = newMessages.associateBy { it.info.id }
            // Merge strategy:
            // - Messages present in both: prefer SSE version (fresher) unless REST has completed=true
            //   and SSE has completed=null (edge case: SSE missed the completion event)
            // - Messages only in SSE: preserved (may be actively streaming)
            // - Messages only in REST: added (missed by SSE)
            val merged = (existing + newMessages.map { it.info })
                .distinctBy { it.id }
                .map { msg ->
                    val incoming = incomingById[msg.id]
                    if (incoming != null) {
                        val sseVersion = msg
                        val restVersion = incoming.info
                        mergeMessageMeta(sseVersion, restVersion)
                    } else {
                        msg
                    }
                }
                .sortedBy { it.time.created }
            current + (sessionId to merged)
        }
        val partsMap = newMessages.associate { it.info.id to it.parts }
        _parts.update { current ->
            val merged = partsMap.mapValues { (messageId, incomingParts) ->
                val existingParts = current[messageId]
                if (existingParts != null) {
                    mergePartsList(existingParts, incomingParts)
                } else {
                    incomingParts
                }
            }
            // Only clean up parts belonging to THIS session — never touch other sessions' parts.
            val survivingMessageIds = (_messages.value[sessionId] ?: emptyList()).map { it.id }.toSet()
            val prevSessionMessageIds = previousMessageIdsFor(sessionId)
            val staleKeys = prevSessionMessageIds.filter { messageId ->
                messageId !in survivingMessageIds && messageId !in incomingMessageIds
            }
            (current + merged) - staleKeys.toSet()
        }
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

    /**
     * Replace all messages and parts for a session with REST data.
     * Unlike [mergeMessages], this treats REST as the source of truth,
     * overwriting any existing local data. Used for SSE reconnection recovery.
     *
     * SSE-only messages (not in REST response) are preserved to handle
     * the window between REST snapshot and new SSE connection establishment.
     */
    fun replaceMessages(sessionId: String, newMessages: List<MessageWithParts>) {
        val incomingMessageIds = newMessages.map { it.info.id }.toSet()
        _messages.update { current ->
            val existing = current[sessionId] ?: emptyList()
            val incomingById = newMessages.associateBy { it.info.id }
            val merged = (existing + newMessages.map { it.info })
                .distinctBy { it.id }
                .map { msg -> incomingById[msg.id]?.info ?: msg }
                .sortedBy { it.time.created }
            current + (sessionId to merged)
        }
        val partsMap = newMessages.associate { it.info.id to it.parts }
        _parts.update { current ->
            val merged = partsMap.mapValues { (messageId, incomingParts) ->
                val existingParts = current[messageId]
                if (existingParts != null) {
                    mergePartsList(existingParts, incomingParts)
                } else {
                    incomingParts
                }
            }
            // Only clean up parts belonging to THIS session — never touch other sessions' parts.
            val survivingMessageIds = (_messages.value[sessionId] ?: emptyList()).map { it.id }.toSet()
            val prevSessionMessageIds = previousMessageIdsFor(sessionId)
            val staleKeys = prevSessionMessageIds.filter { messageId ->
                messageId !in survivingMessageIds && messageId !in incomingMessageIds
            }
            (current + merged) - staleKeys.toSet()
        }
    }

    /**
     * Collect all message IDs that were previously associated with [sessionId].
     * Derived from the current _messages snapshot for that session, which represents
     * the full set of message IDs we may need to clean up.
     * This ensures stale-key cleanup is scoped to the target session only.
     */
    private fun previousMessageIdsFor(sessionId: String): Set<String> =
        _messages.value[sessionId]?.map { it.id }?.toSet() ?: emptySet()

    fun clearForSession(sessionId: String) {
        val messageIds = _messages.value[sessionId]?.map { it.id }?.toSet() ?: emptySet()
        _messages.update { it - sessionId }
        _parts.update { it - messageIds }
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
