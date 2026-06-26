package dev.leonardo.ocremotev2.data.repository.handler

import android.util.Log
import dev.leonardo.ocremotev2.BuildConfig
import dev.leonardo.ocremotev2.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared state store for message and part data.
 *
 * Owns the `_messages`, `_parts`, and `assistantMessageIds` state that is tightly
 * coupled across the message/part lifecycle (e.g. [handleMessagePartUpdated]
 * consults `assistantMessageIds` populated by [handleMessageUpdated];
 * [handleMessageUpdated] seeds `_parts` for user messages). Because of this
 * coupling the per-sub-event dispatch lives in dedicated handlers
 * ([MessagePartHandler], [MessageUpdatedHandler], [MessageRemovedHandler])
 * which inject this store and delegate to its `internal` handlers.
 */
@Singleton
class MessageEventHandler @Inject constructor() {

    private companion object {
        const val TAG = "MsgEventHandler"
    }

    private val _messages = MutableStateFlow<Map<String, List<Message>>>(emptyMap())
    val messages: StateFlow<Map<String, List<Message>>> = _messages.asStateFlow()

    private val _parts = MutableStateFlow<Map<String, List<Part>>>(emptyMap())
    val parts: StateFlow<Map<String, List<Part>>> = _parts.asStateFlow()

    /** Set of assistant message IDs for fast O(1) lookup in PartUpdated handler. */
    private val assistantMessageIds = mutableSetOf<String>()

    // ── SSE delta batching (48ms window) ──────────────────────────────
    // Buffers incoming deltas and flushes every 48ms to reduce
    // recomposition frequency. Each flush = 1 StateFlow update = 1
    // recomposition = 1 layout modifier measure pass.
    private data class PendingDelta(
        val messageId: String,
        val partId: String,
        val sessionId: String,
        val delta: String,
        val type: String  // "text" or "reasoning"
    )

    private val batchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pendingDeltas = mutableListOf<PendingDelta>()
    private val pendingLock = Any()
    private var batchJob: Job? = null

    private fun scheduleFlush() {
        // Do NOT cancel an in-flight timer — that starves flushes when token
        // arrival rate > 1/48ms. Let deltas accumulate; the running timer will
        // flush them all when it fires.
        if (batchJob?.isActive == true) return
        batchJob = batchScope.launch {
            delay(48)
            flushPendingDeltas()
        }
    }

    private fun flushPendingDeltas() {
        val batch: List<PendingDelta>
        synchronized(pendingLock) {
            if (pendingDeltas.isEmpty()) return
            batch = pendingDeltas.toList()
            pendingDeltas.clear()
        }

        _parts.update { current ->
            var updated = current
            for (entry in batch) {
                val messageParts = updated[entry.messageId]?.toMutableList() ?: mutableListOf()
                val idx = messageParts.indexOfFirst { it.id == entry.partId }
                if (idx >= 0) {
                    val part = messageParts[idx]
                    val newPart = when (part) {
                        is Part.Text -> {
                            if (part.text.endsWith(entry.delta)) part  // dedup
                            else part.copy(text = part.text + entry.delta)
                        }
                        is Part.Reasoning -> part.copy(text = part.text + entry.delta)
                        else -> part
                    }
                    messageParts[idx] = newPart
                } else {
                    messageParts.add(Part.Text(
                        id = entry.partId,
                        sessionId = entry.sessionId,
                        messageId = entry.messageId,
                        text = entry.delta
                    ))
                }
                updated = updated + (entry.messageId to messageParts)
            }
            updated
        }
    }

    /** Immediately flush any pending deltas (for tests). */
    internal fun forceFlushDeltas() {
        batchJob?.cancel()
        batchJob = null
        flushPendingDeltas()
    }
    // ── End SSE delta batching ────────────────────────────────────────

    /**
     * Aggregate entry point dispatching a message event to the matching handler.
     *
     * Retained as a convenience for direct unit testing of this store. Production
     * dispatch routes through the per-sub-event handlers ([MessagePartHandler],
     * [MessageUpdatedHandler], [MessageRemovedHandler]) registered in
     * EventDispatcher's registry, which delegate back here via the `internal`
     * handlers below.
     */
    fun handle(event: SseEvent, serverId: String): Boolean {
        return when (event) {
            is SseEvent.MessageUpdated -> { handleMessageUpdated(event); true }
            is SseEvent.MessageRemoved -> { handleMessageRemoved(event); true }
            is SseEvent.MessagePartUpdated -> { handleMessagePartUpdated(event); true }
            is SseEvent.MessagePartDelta -> { handleMessagePartDelta(event); true }
            is SseEvent.MessagePartRemoved -> { handleMessagePartRemoved(event); true }
            else -> false
        }
    }

    internal fun handleMessageUpdated(event: SseEvent.MessageUpdated) {
        val sessionId = event.info.sessionId
        val role = when (event.info) { is Message.User -> "user"; is Message.Assistant -> "assistant" }
        _messages.update { current ->
            val sessionMessages = current[sessionId]?.toMutableList() ?: mutableListOf()
            val idx = sessionMessages.indexOfFirst { it.id == event.info.id }
            val isUpdate = idx >= 0
            if (idx >= 0) {
                sessionMessages[idx] = event.info
            } else {
                sessionMessages.add(event.info)
                sessionMessages.sortBy { it.time.created }
            }
            val total = sessionMessages.size
            Log.i(TAG, "[MsgUpdated] id=${event.info.id.take(12)} role=$role session=${sessionId.take(12)} " +
                "${if (isUpdate) "UPDATE" else "NEW"} total=$total")
            current + (sessionId to sessionMessages)
        }
        if (event.info is Message.Assistant) {
            assistantMessageIds.add(event.info.id)
        }
        // SSE MessageUpdated carries no parts; for User messages, seed parts map from summary
        // so that consumers (notifications, UI) can read text without waiting for REST sync.
        val info = event.info
        if (info is Message.User) {
            _parts.update { current ->
                if (current.containsKey(info.id)) {
                    current
                } else {
                    val summaryText = info.summary?.body?.takeIf { it.isNotBlank() }
                        ?: info.summary?.title?.takeIf { it.isNotBlank() }
                    if (summaryText != null) {
                        current + (info.id to listOf(Part.Text(
                            id = "${info.id}_summary",
                            sessionId = sessionId,
                            messageId = info.id,
                            text = summaryText
                        )))
                    } else {
                        current
                    }
                }
            }
        }
    }

    /**
     * Remove messages with id >= [revertMessageId] from the cache.
     * Called by [EventDispatcher.clearRevert] to prevent reverted messages
     * from briefly reappearing when the revert filter is cleared.
     */
    fun pruneRevertedMessages(sessionId: String, revertMessageId: String) {
        val removedIds = _messages.value[sessionId]
            ?.filter { it.id >= revertMessageId }
            ?.map { it.id }
            ?.toSet()
            ?: return
        if (removedIds.isEmpty()) return

        _messages.update { current ->
            val sessionMessages = current[sessionId] ?: return@update current
            current + (sessionId to sessionMessages.filter { it.id < revertMessageId })
        }
        _parts.update { it.filterKeys { msgId -> msgId !in removedIds } }
        assistantMessageIds.removeAll(removedIds)

        if (BuildConfig.DEBUG) Log.d(TAG, "Pruned ${removedIds.size} reverted messages for session ${sessionId.take(12)}")
    }

    internal fun handleMessageRemoved(event: SseEvent.MessageRemoved) {
        _messages.update { current ->
            val sessionMessages = current[event.sessionId]?.filter { it.id != event.messageId }
            if (sessionMessages != null) current + (event.sessionId to sessionMessages) else current
        }
        _parts.update { it - event.messageId }
        assistantMessageIds.remove(event.messageId)
    }

    internal fun handleMessagePartUpdated(event: SseEvent.MessagePartUpdated) {
        val messageId = event.part.messageId
        val partId = event.part.id
        @Suppress("DEPRECATION")
        val thread = Thread.currentThread().id
        _parts.update { current ->
            val messageParts = current[messageId]?.toMutableList() ?: mutableListOf()
            val idx = messageParts.indexOfFirst { it.id == partId }
            if (idx >= 0) {
                val old = messageParts[idx]
                val merged = mergePart(old, event.part)
                // Diagnostic: log text changes for Text/Reasoning parts
                if (old is Part.Text && event.part is Part.Text) {
                    val oldLen = old.text.length
                    val newLen = (merged as Part.Text).text.length
                    val incLen = event.part.text.length
                    if (newLen != incLen) {
                        Log.w(TAG, "[PartUpdated] t=$thread msg=$messageId part=$partId " +
                            "old=$oldLen inc=$incLen merged=$newLen " +
                            "(kept SSE text, discarded REST snapshot)")
                    }
                }
                messageParts[idx] = merged
            } else {
                // New part arriving — strip text for assistant messages.
                // Uses assistantMessageIds set (populated by handleMessageUpdated
                // and REST sync) for O(1) lookup. More reliable than StateFlow
                // snapshot reads which may miss recently created messages.
                val isAssistantPart = messageId in assistantMessageIds
                val partToAdd = if (isAssistantPart) {
                    when (event.part) {
                        is Part.Text -> {
                            if (event.part.text.isNotEmpty()) {
                                Log.w(TAG, "[PartUpdated] t=$thread msg=$messageId part=$partId " +
                                    "stripping assistant text=${event.part.text.length}, " +
                                    "will be re-accumulated by SSE deltas or REST sync")
                                event.part.copy(text = "")
                            } else {
                                event.part
                            }
                        }
                        is Part.Reasoning -> {
                            if (event.part.text.isNotEmpty()) {
                                Log.w(TAG, "[PartUpdated] t=$thread msg=$messageId part=$partId " +
                                    "stripping assistant reasoning text=${event.part.text.length}")
                                event.part.copy(text = "")
                            } else {
                                event.part
                            }
                        }
                        else -> event.part
                    }
                } else {
                    event.part
                }
                messageParts.add(partToAdd)
            }
            current + (messageId to messageParts)
        }
    }

    /**
     * Merge Part update: for Text/Reasoning, SSE delta-driven text takes priority.
     *
     * During streaming, SSE deltas accumulate text incrementally. REST syncs may
     * return a snapshot that is fresher than the delta accumulation (e.g. REST
     * returns "你好世界" while SSE has only accumulated "你好"). If we take the
     * REST snapshot's longer text, subsequent SSE deltas (which the server already
     * sent before the REST call) will append content already in the snapshot,
     * causing duplication.
     *
     * Fix: If existing (SSE) has any text, keep it — SSE is the streaming source
     * of truth. Only take incoming's text when existing is empty (part just created).
     * Always take incoming's metadata (time, etc.) since REST may have newer metadata.
     */
    private fun mergePart(existing: Part, incoming: Part): Part {
        return when {
            existing is Part.Text && incoming is Part.Text -> {
                // Longer text wins: SSE streaming accumulates longer text over time,
                // REST snapshots may be stale. If incoming is longer (fresh complete
                // replacement), use it; otherwise keep existing (protects streaming text).
                if (incoming.text.length >= existing.text.length) incoming
                else existing.copy(time = incoming.time, metadata = incoming.metadata)
            }
            existing is Part.Reasoning && incoming is Part.Reasoning -> {
                if (incoming.text.length >= existing.text.length) incoming
                else existing.copy(time = incoming.time, metadata = incoming.metadata)
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

    internal fun handleMessagePartDelta(event: SseEvent.MessagePartDelta) {
        // Buffer delta for batch flush (48ms window) — reduces recomposition
        // frequency from per-token to ~20/sec, eliminating layout jitter.
        val partType = when (_parts.value[event.messageId]
            ?.firstOrNull { it.id == event.partId }) {
            is Part.Reasoning -> "reasoning"
            else -> "text"
        }
        synchronized(pendingLock) {
            pendingDeltas.add(PendingDelta(
                messageId = event.messageId,
                partId = event.partId,
                sessionId = event.sessionId,
                delta = event.delta,
                type = partType
            ))
        }
        scheduleFlush()
    }

    internal fun handleMessagePartRemoved(event: SseEvent.MessagePartRemoved) {
        _parts.update { current ->
            val messageParts = current[event.messageId]?.filter { it.id != event.partId }
            if (messageParts != null) current + (event.messageId to messageParts) else current
        }
    }

    // ============ Batch Operations ============

    fun setMessages(sessionId: String, newMessages: List<MessageWithParts>) {
        @Suppress("DEPRECATION")
        val thread = Thread.currentThread().id
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
        newMessages.forEach { if (it.info is Message.Assistant) assistantMessageIds.add(it.info.id) }
        val partsMap = newMessages.associate { it.info.id to it.parts }
        _parts.update { current ->
            val merged = partsMap.mapValues { (messageId, incomingParts) ->
                val existingParts = current[messageId]
                if (existingParts != null) {
                    // Diagnostic: check for text length regression after merge
                    for (inc in incomingParts) {
                        if (inc is Part.Text) {
                            val ex = existingParts.find { it.id == inc.id }
                            if (ex is Part.Text && ex.text.length > inc.text.length) {
                                Log.w(TAG, "[setMessages] t=$thread msg=${messageId.take(8)} " +
                                    "part=${inc.id.take(8)} SSE=${ex.text.length} > REST=${inc.text.length} " +
                                    "→ keeping SSE text")
                            }
                        }
                    }
                    mergePartsList(existingParts, incomingParts)
                } else {
                    incomingParts
                }
            }
            current + merged
        }
    }

    fun mergeMessages(sessionId: String, newMessages: List<MessageWithParts>) {
        val incoming = newMessages.map { it.info }.sortedBy { m -> m.time.created }
        _messages.update { current ->
            val existing = current[sessionId] ?: emptyList()
            val existingById = existing.associateBy { it.id }
            current + (sessionId to incoming.map { newMsg -> existingById[newMsg.id] ?: newMsg })
        }
        newMessages.forEach { if (it.info is Message.Assistant) assistantMessageIds.add(it.info.id) }
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
        @Suppress("DEPRECATION")
        val thread = Thread.currentThread().id
        _messages.update { current ->
            val existing = current[sessionId] ?: emptyList()
            val incomingById = newMessages.associateBy { it.info.id }
            val merged = (existing + newMessages.map { it.info })
                .distinctBy { it.id }
                .map { msg -> incomingById[msg.id]?.info ?: msg }
                .sortedBy { it.time.created }
            current + (sessionId to merged)
        }
        newMessages.forEach { if (it.info is Message.Assistant) assistantMessageIds.add(it.info.id) }
        val partsMap = newMessages.associate { it.info.id to it.parts }
        _parts.update { current ->
            val merged = partsMap.mapValues { (messageId, incomingParts) ->
                val existingParts = current[messageId]
                if (existingParts != null) {
                    // Diagnostic: check for text length regression after merge
                    for (inc in incomingParts) {
                        if (inc is Part.Text) {
                            val ex = existingParts.find { it.id == inc.id }
                            if (ex is Part.Text && ex.text.length > inc.text.length) {
                                Log.w(TAG, "[replaceMessages] t=$thread msg=${messageId.take(8)} " +
                                    "part=${inc.id.take(8)} SSE=${ex.text.length} > REST=${inc.text.length} " +
                                    "→ keeping SSE text")
                            }
                        }
                    }
                    mergePartsList(existingParts, incomingParts)
                } else {
                    incomingParts
                }
            }
            current + merged
        }
    }

    fun clearForSession(sessionId: String) {
        val messageIds = _messages.value[sessionId]?.map { it.id }?.toSet() ?: emptySet()
        _messages.update { it - sessionId }
        _parts.update { it - messageIds }
        assistantMessageIds.removeAll(messageIds)
    }

    fun clearForServer(sessionIds: Set<String>) {
        val messageIds = _messages.value
            .filterKeys { it in sessionIds }.values.flatten()
            .map { it.id }.toSet()
        _messages.update { it - sessionIds }
        _parts.update { it - messageIds }
        assistantMessageIds.removeAll(messageIds)
    }

    fun clearAll() {
        _messages.value = emptyMap()
        _parts.value = emptyMap()
        assistantMessageIds.clear()
    }

    /**
     * Mark all incomplete assistant messages in a session as completed.
     * Called when REST fallback detects server is idle but UI shows streaming.
     */
    fun markSessionIdle(sessionId: String) {
        _messages.update { current ->
            val sessionMessages = current[sessionId] ?: return@update current
            val now = System.currentTimeMillis()
            val updated = sessionMessages.map { msg ->
                if (msg is Message.Assistant && msg.time.completed == null) {
                    msg.copy(time = msg.time.copy(completed = now))
                } else {
                    msg
                }
            }
            current + (sessionId to updated)
        }

        // Mark all incomplete Reasoning parts with time.end
        _parts.update { current ->
            val sessionMessages = _messages.value[sessionId] ?: return@update current
            val messageIds = sessionMessages.map { it.id }
            var changed = false
            val updated = current.toMutableMap()
            for (msgId in messageIds) {
                val msgParts = updated[msgId] ?: continue
                val updatedParts = msgParts.map { part ->
                    if (part is Part.Reasoning && part.time?.end == null) {
                        changed = true
                        part.copy(time = Part.Reasoning.Time(
                            start = part.time?.start ?: System.currentTimeMillis(),
                            end = System.currentTimeMillis()
                        ))
                    } else {
                        part
                    }
                }
                if (changed) updated[msgId] = updatedParts
            }
            if (changed) updated else current
        }
    }
}
