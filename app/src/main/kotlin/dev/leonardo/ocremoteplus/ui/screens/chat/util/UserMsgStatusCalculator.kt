package dev.leonardo.ocremoteplus.ui.screens.chat.util

import dev.leonardo.ocremoteplus.domain.model.Message
import dev.leonardo.ocremoteplus.domain.model.SessionStatus
import dev.leonardo.ocremoteplus.domain.model.UserMsgStatus

/**
 * Derives the status of a user message for the per-message indicator.
 *
 * Covers the QUEUED gap: when FSM is Busy but no pending assistant exists yet
 * (first message, assistant hasn't created a message), the message is "Waiting"
 * rather than falling through to "Completed".
 *
 * @param messageId The user message ID to check
 * @param queuedMessageIds Set of queued message IDs (from MessageDataDelegate)
 * @param fsmStatus Current FSM session status
 * @param messages Full message list (ordered, oldest first)
 * @return The user message status
 */
fun calculateUserMsgStatus(
    messageId: String,
    queuedMessageIds: Set<String>,
    fsmStatus: SessionStatus,
    messages: List<Message>,
): UserMsgStatus = when {
    messageId in queuedMessageIds -> UserMsgStatus.Queued
    fsmStatus is SessionStatus.Busy -> {
        val messageIndex = messages.indexOfFirst { it.id == messageId }
        if (messageIndex < 0) return UserMsgStatus.Completed

        val nextAssistant = messages.drop(messageIndex + 1)
            .firstOrNull { it is Message.Assistant } as? Message.Assistant

        if (nextAssistant?.time?.completed == null) UserMsgStatus.Waiting
        else UserMsgStatus.Completed
    }
    else -> UserMsgStatus.Completed
}

/**
 * Batch-compute user message statuses for all user messages in the list.
 */
fun calculateAllUserMsgStatuses(
    messages: List<Message>,
    queuedMessageIds: Set<String>,
    fsmStatus: SessionStatus,
): Map<String, UserMsgStatus> {
    return messages
        .filterIsInstance<Message.User>()
        .associate { msg ->
            msg.id to calculateUserMsgStatus(msg.id, queuedMessageIds, fsmStatus, messages)
        }
}
