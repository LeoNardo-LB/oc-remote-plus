package dev.minios.ocremote.domain.repository

import dev.minios.ocremote.domain.model.Message
import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.domain.model.PermissionState
import dev.minios.ocremote.domain.model.QuestionState
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for chat operations.
 * Aligned with spec §4.1.1.
 * Implemented by the Data layer in Phase 3.
 */
interface ChatRepository {

    /**
     * Observe the list of messages (with parts) for a session.
     * Phase 3 impl: delegates to EventDispatcher.messages, maps to domain Message.
     */
    fun getMessagesFlow(sessionId: String): Flow<List<Message>>

    /**
     * Observe the list of pending permission requests for a session.
     */
    fun getPermissionsFlow(sessionId: String): Flow<List<PermissionState>>

    /**
     * Observe the list of pending questions for a session.
     */
    fun getQuestionsFlow(sessionId: String): Flow<List<QuestionState>>

    /**
     * Send a message (list of parts) to the given session.
     * Returns the resulting [Message] on success, or an exception on failure.
     */
    suspend fun sendMessage(sessionId: String, parts: List<Part>): Result<Message>

    /**
     * Reply to a permission request.
     */
    suspend fun replyPermission(permissionId: String, reply: String): Result<Boolean>

    /**
     * Reply to a question.
     */
    suspend fun replyQuestion(questionId: String, answer: String): Result<Boolean>

    /**
     * Get the mutable map of tool expanded states for the current session.
     * Used by UI to track which tool cards are expanded.
     */
    fun getToolExpandedStates(): MutableMap<String, Boolean>
}
