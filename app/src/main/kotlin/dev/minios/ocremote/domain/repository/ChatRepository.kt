package dev.minios.ocremote.domain.repository

import dev.minios.ocremote.domain.model.AutoApproveRule
import dev.minios.ocremote.domain.model.CompactionStateInfo
import dev.minios.ocremote.domain.model.Message
import dev.minios.ocremote.domain.model.ModelSelection
import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.domain.model.PermissionState
import dev.minios.ocremote.domain.model.PromptPart
import dev.minios.ocremote.domain.model.QuestionState
import dev.minios.ocremote.domain.model.StepProgressInfo
import dev.minios.ocremote.domain.model.ToolProgressInfo
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for chat operations.
 * Aligned with spec §4.1.1.
 * Implemented by the Data layer in Phase 3.
 */
interface ChatRepository {

    // ============ State Observations ============

    /**
     * Observe the list of messages (with parts) for a session.
     * Phase 3 impl: delegates to EventDispatcher.messages, maps to domain Message.
     */
    fun getMessagesFlow(sessionId: String): Flow<List<Message>>

    /**
     * Observe the list of parts for a session.
     */
    fun getParts(sessionId: String): Flow<List<Part>>

    /**
     * Observe the list of pending permission requests for a session.
     */
    fun getPermissionsFlow(sessionId: String): Flow<List<PermissionState>>

    /**
     * Observe the list of pending questions for a session.
     */
    fun getQuestionsFlow(sessionId: String): Flow<List<QuestionState>>

    // ============ EventDispatcher Flow Exposure ============

    /**
     * Observe active tool progress for a server.
     */
    fun getActiveToolProgress(serverId: String): Flow<List<ToolProgressInfo>?>

    /**
     * Observe step progress for a server.
     */
    fun getStepProgress(serverId: String): Flow<StepProgressInfo?>

    /**
     * Observe compaction state for a server.
     */
    fun getCompactionState(serverId: String): Flow<CompactionStateInfo?>

    // ============ Network Operations ============

    /**
     * Send a message (list of parts) to the given session.
     * Returns the resulting [Message] on success, or an exception on failure.
     */
    suspend fun sendMessage(sessionId: String, parts: List<Part>): Result<Message>

    /**
     * Reply to a permission request by ID.
     */
    suspend fun replyPermission(permissionId: String, reply: String): Result<Boolean>

    /**
     * Reply to a question by ID.
     */
    suspend fun replyQuestion(questionId: String, answer: String): Result<Boolean>

    /**
     * Send a prompt asynchronously (fire-and-forget).
     */
    suspend fun promptAsync(
        serverId: String,
        sessionId: String,
        parts: List<PromptPart>,
        model: ModelSelection? = null,
        agent: String? = null,
        variant: String? = null,
        directory: String? = null
    ): Result<Unit>

    /**
     * Revert (undo) messages starting from the given messageId.
     */
    suspend fun revertSession(serverId: String, sessionId: String, messageId: String): Result<Unit>

    /**
     * Unrevert (redo) the last reverted message in a session.
     */
    suspend fun unrevertSession(serverId: String, sessionId: String): Result<Unit>

    /**
     * Respond to a permission request with server context.
     */
    suspend fun respondPermission(
        serverId: String,
        permissionId: String,
        reply: String,
        directory: String? = null
    ): Result<Boolean>

    /**
     * Select a model for the server.
     */
    suspend fun selectModel(serverId: String, providerId: String, modelId: String): Result<Unit>

    // ============ Pending Queries ============

    /**
     * List pending permission requests for a server.
     */
    suspend fun listPendingPermissions(serverId: String, directory: String? = null): Result<List<PermissionState>>

    /**
     * List pending question requests for a server.
     */
    suspend fun listPendingQuestions(serverId: String, directory: String? = null): Result<List<QuestionState>>

    /**
     * Reply to a question request with multiple answers.
     */
    suspend fun replyToQuestion(
        serverId: String,
        requestId: String,
        answers: List<List<String>>,
        directory: String? = null
    ): Result<Boolean>

    /**
     * Reject a question request.
     */
    suspend fun rejectQuestion(
        serverId: String,
        requestId: String,
        directory: String? = null
    ): Result<Boolean>

    // ============ Undo/Redo ============

    /**
     * Undo or redo messages in a session.
     * @param action "undo" or "redo"
     */
    suspend fun undoRedo(serverId: String, sessionId: String, action: String): Result<Unit>

    // ============ Command Execution ============

    /**
     * Execute a server-side command in a session.
     */
    suspend fun executeCommand(
        serverId: String,
        sessionId: String,
        command: String,
        arguments: String = "",
        directory: String? = null
    ): Result<Boolean>

    /**
     * Run a shell command in a session.
     */
    suspend fun runShellCommand(
        serverId: String,
        sessionId: String,
        command: String,
        agent: String,
        providerId: String? = null,
        modelId: String? = null,
        directory: String? = null
    ): Result<Boolean>

    // ============ UI State ============

    /**
     * Get the read-only map of tool expanded states for the current session.
     * Used by UI to track which tool cards are expanded.
     */
    fun getToolExpandedStates(): Map<String, Boolean>

    /**
     * Set the expanded state for a specific tool card.
     */
    fun setToolExpanded(toolId: String, expanded: Boolean)

    // ============ Permission Auto-Approve ============

    /**
     * Persist a new permission auto-approve rule (user chose "always approve").
     */
    suspend fun addPermissionAutoApproveRule(rule: AutoApproveRule)
}
