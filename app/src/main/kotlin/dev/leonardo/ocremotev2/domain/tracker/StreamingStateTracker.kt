package dev.leonardo.ocremotev2.domain.tracker

/**
 * Streaming state for a single part (text or reasoning).
 */
enum class StreamingState {
    Idle,
    Started,
    Streaming,
    Ended
}

/**
 * Tracks streaming state per part ID with bounded history.
 *
 * Lifecycle: Idle -> Started -> Streaming -> Ended
 * - Started: first event for this part (TextStarted / ReasoningStarted)
 * - Streaming: at least one delta received
 * - Ended: streaming complete (TextEnded / ReasoningEnded)
 *
 * Thread safety: designed for single-threaded dispatcher use (Main + Dispatchers.Default).
 * For multi-threaded access, external synchronization is required.
 */
class StreamingStateTracker {

    private data class Entry(
        val state: StreamingState,
        val sessionId: String? = null
    )

    private val states = LinkedHashMap<String, Entry>()

    companion object {
        const val DEFAULT_MAX_ENTRIES = 50
    }

    fun getState(partId: String): StreamingState =
        states[partId]?.state ?: StreamingState.Idle

    fun onStarted(partId: String, sessionId: String? = null) {
        states[partId] = Entry(StreamingState.Started, sessionId)
    }

    fun onDelta(partId: String, delta: String) {
        val current = states[partId]
        if (current != null && current.state != StreamingState.Ended) {
            states[partId] = Entry(StreamingState.Streaming, current.sessionId)
        }
    }

    fun onEnded(partId: String) {
        val current = states[partId]
        if (current != null) {
            states[partId] = Entry(StreamingState.Ended, current.sessionId)
        }
    }

    fun clear(partId: String) {
        states.remove(partId)
    }

    fun clearAll() {
        states.clear()
    }

    fun clearForSession(sessionId: String) {
        val toRemove = states.entries
            .filter { it.value.sessionId == sessionId }
            .map { it.key }
        toRemove.forEach { states.remove(it) }
    }

    /**
     * Remove oldest entries beyond [maxEntries].
     * Prioritizes removing Ended entries first, then oldest Started/Streaming.
     */
    fun cleanup(maxEntries: Int = DEFAULT_MAX_ENTRIES) {
        if (states.size <= maxEntries) return

        // Remove Ended entries first (oldest first)
        val endedKeys = states.entries
            .filter { it.value.state == StreamingState.Ended }
            .map { it.key }
        for (key in endedKeys) {
            if (states.size <= maxEntries) return
            states.remove(key)
        }

        // Still too many? Remove oldest entries
        val iterator = states.keys.iterator()
        while (states.size > maxEntries && iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }
    }
}
