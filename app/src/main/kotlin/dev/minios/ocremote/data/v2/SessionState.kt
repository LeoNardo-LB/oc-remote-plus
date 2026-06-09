package dev.minios.ocremote.data.v2

import kotlinx.serialization.Serializable

@Serializable
data class SessionState(
    val messages: List<SessionMessage> = emptyList(),
    val todos: List<TodoInfo> = emptyList(),
    val hasOlderMessages: Boolean = false,
    val isLoadingOlder: Boolean = false,
    val isInitialized: Boolean = false,
) {
    companion object {
        val EMPTY = SessionState()
    }
}

// Derived state (extension properties — computed, not stored)
val SessionState.isBusy: Boolean
    get() = messages.any { it is AssistantMessage && it.time.completed == null }

val SessionState.isIdle: Boolean
    get() = !isBusy
