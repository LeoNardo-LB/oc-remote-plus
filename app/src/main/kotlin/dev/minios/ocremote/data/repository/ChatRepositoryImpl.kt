package dev.minios.ocremote.data.repository

import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.api.ServerConnection
import dev.minios.ocremote.data.dto.request.PromptPart
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
    private val serverRepo: ServerRepository
) : ChatRepository {

    private val toolExpandedStates = mutableMapOf<String, Boolean>()

    // ============ State Observations ============

    override fun getMessagesFlow(sessionId: String): Flow<List<Message>> =
        eventDispatcher.messages.map { it[sessionId] ?: emptyList() }

    override fun getPermissionsFlow(sessionId: String): Flow<List<PermissionState>> =
        eventDispatcher.permissions.map { events ->
            (events[sessionId] ?: emptyList()).map { it.toPermissionState() }
        }

    override fun getQuestionsFlow(sessionId: String): Flow<List<QuestionState>> =
        eventDispatcher.questions.map { events ->
            (events[sessionId] ?: emptyList()).map { it.toQuestionState() }
        }

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

    override fun getToolExpandedStates(): MutableMap<String, Boolean> = toolExpandedStates

    // ============ Private Helpers ============

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
