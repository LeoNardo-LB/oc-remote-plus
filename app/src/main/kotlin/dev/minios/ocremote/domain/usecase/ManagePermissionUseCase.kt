package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.dto.response.PermissionRequest
import dev.minios.ocremote.data.dto.response.QuestionRequest
import dev.minios.ocremote.data.api.ServerConnection
import javax.inject.Inject

/**
 * Use case: manage permission and question requests (reply, reject, list pending).
 * Temporary shell — delegates to OpenCodeApi. Full impl with tests in Phase 4.
 */
class ManagePermissionUseCase @Inject constructor(
    private val api: OpenCodeApi
) {
    // TODO: Phase 4 — replace api calls with ChatRepository methods

    suspend fun listPendingPermissions(conn: ServerConnection, directory: String?): List<PermissionRequest> =
        api.listPendingPermissions(conn, directory)

    suspend fun replyToPermission(conn: ServerConnection, requestId: String, reply: String, directory: String?): Boolean =
        api.replyToPermission(conn, requestId, reply, directory)

    suspend fun listPendingQuestions(conn: ServerConnection, directory: String?): List<QuestionRequest> =
        api.listPendingQuestions(conn, directory)

    suspend fun replyToQuestion(conn: ServerConnection, requestId: String, answers: List<List<String>>, directory: String?): Boolean =
        api.replyToQuestion(conn, requestId, answers, directory)

    suspend fun rejectQuestion(conn: ServerConnection, requestId: String, directory: String?): Boolean =
        api.rejectQuestion(conn, requestId, directory)
}
