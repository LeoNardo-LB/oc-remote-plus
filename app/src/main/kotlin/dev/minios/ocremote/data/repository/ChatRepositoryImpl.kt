package dev.minios.ocremote.data.repository

import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.api.ServerConnection
import dev.minios.ocremote.data.dto.common.ModelSelection
import dev.minios.ocremote.data.dto.request.PromptPart
import dev.minios.ocremote.data.repository.handler.CompactionStateInfo
import dev.minios.ocremote.data.repository.handler.StepProgressInfo
import dev.minios.ocremote.data.repository.handler.ToolProgressInfo
import dev.minios.ocremote.domain.model.*
import dev.minios.ocremote.domain.repository.ChatRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [ChatRepository].
 * Bridges domain interface with EventDispatcher (state) and OpenCodeApi (network).
 *
 * Phase 3: compiled but not yet wired to UseCases. Phase 4 will migrate
 * ViewModel/OpenCodeApi direct calls to go through this repository.
 */
@Singleton
class ChatRepositoryImpl @Inject constructor(
    private val api: OpenCodeApi,
    private val eventDispatcher: EventDispatcher,
    private val serverRepo: ServerDataStore
) : ChatRepository {

    private val toolExpandedStates = mutableMapOf<String, Boolean>()

    // ============ State Observations ============

    override fun getMessagesFlow(sessionId: String): Flow<List<Message>> =
        eventDispatcher.messages.map { it[sessionId] ?: emptyList() }

    override fun getParts(sessionId: String): Flow<List<Part>> =
        eventDispatcher.parts.map { it[sessionId] ?: emptyList() }

    override fun getPermissionsFlow(sessionId: String): Flow<List<PermissionState>> =
        eventDispatcher.permissions.map { events ->
            (events[sessionId] ?: emptyList()).map { it.toPermissionState() }
        }

    override fun getQuestionsFlow(sessionId: String): Flow<List<QuestionState>> =
        eventDispatcher.questions.map { events ->
            (events[sessionId] ?: emptyList()).map { it.toQuestionState() }
        }

    // ============ EventDispatcher Flow Exposure ============

    override fun getActiveToolProgress(serverId: String): Flow<List<ToolProgressInfo>?> =
        eventDispatcher.activeToolProgress.map { it[serverId] }

    override fun getStepProgress(serverId: String): Flow<StepProgressInfo?> =
        eventDispatcher.stepProgress.map { it[serverId] }

    override fun getCompactionState(serverId: String): Flow<CompactionStateInfo?> =
        eventDispatcher.compactionState.map { it[serverId] }

    // ============ Network Operations ============

    override suspend fun sendMessage(sessionId: String, parts: List<Part>): Result<Message> = runCatching {
        val conn = resolveConnectionForSession(sessionId)
        val promptParts = parts.map { it.toPromptPart() }
        api.promptAsync(conn, sessionId, promptParts)
        // The actual message arrives via SSE — return a lightweight placeholder.
        // Callers should observe [getMessagesFlow] for the real Message.
        Message.User(
            id = "",
            sessionId = sessionId,
            time = TimeInfo(System.currentTimeMillis())
        )
    }

    override suspend fun replyPermission(permissionId: String, reply: String): Result<Boolean> = runCatching {
        val sessionId = findSessionForPermission(permissionId)
            ?: throw IllegalStateException("Session not found for permission $permissionId")
        val conn = resolveConnectionForSession(sessionId)
        api.replyToPermission(conn, permissionId, reply)
    }

    override suspend fun replyQuestion(questionId: String, answer: String): Result<Boolean> = runCatching {
        val sessionId = findSessionForQuestion(questionId)
            ?: throw IllegalStateException("Session not found for question $questionId")
        val conn = resolveConnectionForSession(sessionId)
        api.replyToQuestion(conn, questionId, listOf(listOf(answer)))
    }

    override suspend fun promptAsync(
        serverId: String,
        sessionId: String,
        parts: List<PromptPart>,
        model: ModelSelection?,
        agent: String?,
        variant: String?,
        directory: String?
    ): Result<Unit> = runCatching {
        val conn = resolveConnection(serverId)
        api.promptAsync(conn, sessionId, parts, model, agent, variant, directory)
    }

    override suspend fun revertSession(serverId: String, sessionId: String, messageId: String): Result<Unit> = runCatching {
        val conn = resolveConnection(serverId)
        api.revertSession(conn, sessionId, messageId)
    }

    override suspend fun unrevertSession(serverId: String, sessionId: String): Result<Unit> = runCatching {
        val conn = resolveConnection(serverId)
        api.unrevertSession(conn, sessionId)
    }

    override suspend fun respondPermission(
        serverId: String,
        permissionId: String,
        reply: String,
        directory: String?
    ): Result<Boolean> = runCatching {
        val conn = resolveConnection(serverId)
        api.replyToPermission(conn, permissionId, reply, directory = directory)
    }

    override suspend fun selectModel(serverId: String, providerId: String, modelId: String): Result<Unit> = runCatching {
        val conn = resolveConnection(serverId)
        // TODO: Phase 4 — verify if OpenCodeApi has a dedicated updateModel endpoint
        // For now, use config patch as fallback
        api.updateConfig(conn, dev.minios.ocremote.data.dto.request.ServerConfigPatch())
    }

    // ============ Pending Queries ============

    override suspend fun listPendingPermissions(serverId: String, directory: String?): Result<List<PermissionState>> = runCatching {
        val conn = resolveConnection(serverId)
        api.listPendingPermissions(conn, directory).map { it.toDomainPermissionState() }
    }

    override suspend fun listPendingQuestions(serverId: String, directory: String?): Result<List<QuestionState>> = runCatching {
        val conn = resolveConnection(serverId)
        api.listPendingQuestions(conn, directory).map { it.toDomainQuestionState() }
    }

    override suspend fun replyToQuestion(
        serverId: String,
        requestId: String,
        answers: List<List<String>>,
        directory: String?
    ): Result<Boolean> = runCatching {
        val conn = resolveConnection(serverId)
        api.replyToQuestion(conn, requestId, answers, directory)
    }

    override suspend fun rejectQuestion(
        serverId: String,
        requestId: String,
        directory: String?
    ): Result<Boolean> = runCatching {
        val conn = resolveConnection(serverId)
        api.rejectQuestion(conn, requestId, directory)
    }

    // ============ Undo/Redo ============

    override suspend fun undoRedo(serverId: String, sessionId: String, action: String): Result<Unit> = runCatching {
        val conn = resolveConnection(serverId)
        // The API uses separate revert/unrevert endpoints, not a unified undoRedo.
        // This method dispatches based on action parameter.
        when (action) {
            "undo" -> {
                // Find last user message for undo — callers should provide messageId directly
                // For the unified undoRedo method, revert without specific messageId
                // is not supported by the API. Use revertSession with a messageId instead.
                throw UnsupportedOperationException("Use revertSession(serverId, sessionId, messageId) for undo")
            }
            "redo" -> {
                api.unrevertSession(conn, sessionId)
            }
            else -> throw IllegalArgumentException("Invalid action: $action. Must be 'undo' or 'redo'")
        }
    }

    // ============ Command Execution ============

    override suspend fun executeCommand(
        serverId: String,
        sessionId: String,
        command: String,
        arguments: String,
        directory: String?
    ): Result<Boolean> = runCatching {
        val conn = resolveConnection(serverId)
        api.executeCommand(conn, sessionId, command, arguments, directory)
    }

    override suspend fun runShellCommand(
        serverId: String,
        sessionId: String,
        command: String,
        agent: String,
        providerId: String?,
        modelId: String?,
        directory: String?
    ): Result<Boolean> = runCatching {
        val conn = resolveConnection(serverId)
        val model = if (providerId != null && modelId != null) {
            ModelSelection(providerId = providerId, modelId = modelId)
        } else null
        api.runShellCommand(conn, sessionId, command, agent, model, directory)
    }

    override fun getToolExpandedStates(): MutableMap<String, Boolean> = toolExpandedStates

    // ============ Private Helpers ============

    private suspend fun resolveConnection(serverId: String): ServerConnection {
        val config = serverRepo.getServer(serverId)
            ?: throw IllegalStateException("Server config not found: $serverId")
        return ServerConnection.from(config.url, config.username, config.password)
    }

    private suspend fun resolveConnectionForSession(sessionId: String): ServerConnection {
        val serverId = eventDispatcher.serverSessions.value.entries
            .find { sessionId in it.value }?.key
            ?: throw IllegalStateException("No server found for session $sessionId")
        val config = serverRepo.getServer(serverId)
            ?: throw IllegalStateException("Server config not found: $serverId")
        return ServerConnection.from(config.url, config.username, config.password)
    }

    private fun findSessionForPermission(permissionId: String): String? =
        eventDispatcher.permissions.value.entries
            .firstOrNull { (_, perms) -> perms.any { it.id == permissionId } }
            ?.key

    private fun findSessionForQuestion(questionId: String): String? =
        eventDispatcher.questions.value.entries
            .firstOrNull { (_, qs) -> qs.any { it.id == questionId } }
            ?.key

    // ============ Mappers ============

    private fun SseEvent.PermissionAsked.toPermissionState() = PermissionState(
        id = id,
        sessionId = sessionId,
        permission = permission,
        patterns = patterns,
        metadata = metadata,
        always = always,
        tool = tool
    )

    private fun SseEvent.QuestionAsked.toQuestionState() = QuestionState(
        id = id,
        sessionId = sessionId,
        questions = questions.map { q ->
            QuestionState.Question(
                header = q.header,
                question = q.question,
                multiple = q.multiple,
                custom = q.custom,
                options = q.options.map { o ->
                    QuestionState.Option(label = o.label, description = o.description)
                }
            )
        },
        tool = tool
    )

    private fun dev.minios.ocremote.data.dto.response.PermissionRequest.toDomainPermissionState() = PermissionState(
        id = id,
        sessionId = sessionId,
        permission = permission,
        patterns = patterns,
        // metadata is Map<String, JsonElement> in DTO but Map<String, String> in domain
        metadata = metadata?.mapValues { it.value.toString() },
        always = always?.toString()?.toBoolean() ?: false,
        tool = tool
    )

    private fun dev.minios.ocremote.data.dto.response.QuestionRequest.toDomainQuestionState() = QuestionState(
        id = id,
        sessionId = sessionId,
        questions = questions.map { q ->
            QuestionState.Question(
                header = q.header,
                question = q.question,
                multiple = q.multiple,
                custom = q.custom,
                options = q.options.map { o ->
                    QuestionState.Option(label = o.label, description = o.description)
                }
            )
        },
        tool = tool
    )

    private fun Part.toPromptPart(): PromptPart = when (this) {
        is Part.Text -> PromptPart(type = "text", text = this.text)
        is Part.File -> PromptPart(
            type = "file",
            mime = this.mime,
            url = this.url,
            filename = this.filename
        )
        else -> PromptPart(type = "text", text = "")
    }
}
