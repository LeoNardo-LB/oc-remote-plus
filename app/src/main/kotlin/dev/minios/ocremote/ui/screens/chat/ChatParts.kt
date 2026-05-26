package dev.minios.ocremote.ui.screens.chat

import dev.minios.ocremote.domain.model.Part

/**
 * Represents a renderable item in the chat list.
 * Consecutive assistant messages are grouped into a single AssistantTurn.
 */
internal sealed class ChatItem {
    abstract val key: String

    data class UserMessage(
        override val key: String,
        val chatMessage: ChatMessage
    ) : ChatItem()

    data class AssistantTurn(
        override val key: String,
        val messages: List<ChatMessage>
    ) : ChatItem()
}

/**
 * Groups a flat list of ChatMessages into ChatItems.
 * Consecutive assistant messages are merged into a single AssistantTurn.
 */
internal fun groupMessages(messages: List<ChatMessage>): List<ChatItem> {
    val items = mutableListOf<ChatItem>()
    var currentAssistantGroup = mutableListOf<ChatMessage>()

    fun flushAssistantGroup() {
        if (currentAssistantGroup.isNotEmpty()) {
            items.add(ChatItem.AssistantTurn(
                key = "turn_${currentAssistantGroup.first().message.id}",
                messages = currentAssistantGroup.reversed()
            ))
            currentAssistantGroup = mutableListOf()
        }
    }

    for (msg in messages) {
        if (msg.isUser) {
            flushAssistantGroup()
            items.add(ChatItem.UserMessage(
                key = msg.message.id,
                chatMessage = msg
            ))
        } else {
            currentAssistantGroup.add(msg)
        }
    }
    flushAssistantGroup()

    return items
}

/**
 * Determines whether a Part should be rendered inside a chat bubble.
 * Non-renderable parts (StepStart, StepFinish, Snapshot, Subtask, Compaction,
 * Agent, SessionTurn, Unknown) are filtered out before display.
 */
internal fun isBubbleRenderablePart(part: Part): Boolean {
    return when (part) {
        is Part.Text,
        is Part.Reasoning,
        is Part.Patch,
        is Part.File,
        is Part.Permission,
        is Part.Question,
        is Part.Abort,
        is Part.Retry,
        is Part.Tool -> true
        else -> false
    }
}

/**
 * Filters a list of Parts to only include renderable ones,
 * preserving the original order from the server.
 *
 * This is the core logic that was fixed: previously parts were split into
 * contentParts and stepParts groups and rendered out of order. Now the
 * original interleaving (Text → Tool → Reasoning → Tool → Text) is preserved.
 */
internal fun filterRenderableParts(parts: List<Part>): List<Part> {
    return parts.filter(::isBubbleRenderablePart)
}
