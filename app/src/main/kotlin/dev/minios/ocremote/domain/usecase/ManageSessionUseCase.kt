package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.api.ServerConnection
import dev.minios.ocremote.domain.model.MessageWithParts
import dev.minios.ocremote.domain.model.Session
import javax.inject.Inject

/**
 * Use case: manage session lifecycle (load, refresh, create, fork, rename).
 * Temporary shell — delegates to OpenCodeApi. Full impl with tests in Phase 4.
 */
class ManageSessionUseCase @Inject constructor(
    private val api: OpenCodeApi
) {
    // TODO: Phase 4 — replace api calls with ChatRepository/SessionRepository methods

    suspend fun getSession(conn: ServerConnection, sessionId: String): Session =
        api.getSession(conn, sessionId)

    suspend fun listMessages(conn: ServerConnection, sessionId: String, limit: Int): List<MessageWithParts> =
        api.listMessages(conn, sessionId, limit)

    suspend fun createSession(conn: ServerConnection, directory: String?): Session =
        api.createSession(conn, directory = directory)

    suspend fun forkSession(conn: ServerConnection, sessionId: String): Session =
        api.forkSession(conn, sessionId)

    suspend fun renameSession(conn: ServerConnection, sessionId: String, title: String) {
        api.updateSession(conn, sessionId, title)
    }

    suspend fun abortSession(conn: ServerConnection, sessionId: String, directory: String?) {
        api.abortSession(conn, sessionId, directory)
    }

    suspend fun deleteMessage(conn: ServerConnection, sessionId: String, messageId: String): Boolean =
        api.deleteMessage(conn, sessionId, messageId)

    suspend fun deleteMessagePart(conn: ServerConnection, sessionId: String, messageId: String, partIndex: Int): Boolean =
        api.deleteMessagePart(conn, sessionId, messageId, partIndex)

    suspend fun archiveSession(conn: ServerConnection, sessionId: String): Session =
        api.updateSessionFields(conn, sessionId, mapOf("archived" to true))

    suspend fun unarchiveSession(conn: ServerConnection, sessionId: String): Session =
        api.updateSessionFields(conn, sessionId, mapOf("archived" to false))

    suspend fun importSession(conn: ServerConnection, shareUrl: String): Session =
        api.importSession(conn, shareUrl)
}
