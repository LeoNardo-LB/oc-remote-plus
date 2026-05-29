package dev.minios.ocremote.data.repository

import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.api.ServerConnection
import dev.minios.ocremote.domain.model.CreateSessionOpts
import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.domain.repository.SessionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [SessionRepository].
 * Bridges domain interface with EventDispatcher (state) and OpenCodeApi (network).
 *
 * Phase 3: compiled but not yet wired to UseCases. Phase 4 will migrate
 * ViewModel/OpenCodeApi direct calls to go through this repository.
 */
@Singleton
class SessionRepositoryImpl @Inject constructor(
    private val api: OpenCodeApi,
    private val eventDispatcher: EventDispatcher,
    private val serverRepo: ServerRepository
) : SessionRepository {

    // ============ State Observations ============

    override fun getSessionsFlow(serverId: String): Flow<List<Session>> {
        // Combine server→session mapping with the global sessions list so that
        // changes to either trigger a re-emission.
        return combine(
            eventDispatcher.serverSessions,
            eventDispatcher.sessions
        ) { mapping, allSessions ->
            val sessionIds = mapping[serverId] ?: emptySet()
            if (sessionIds.isEmpty()) emptyList()
            else allSessions.filter { it.id in sessionIds }
        }
    }

    // ============ Network Operations ============

    override suspend fun createSession(serverId: String, opts: CreateSessionOpts): Result<Session> = runCatching {
        val conn = resolveConnection(serverId)
        api.createSession(
            conn = conn,
            title = opts.title,
            parentId = opts.parentId,
            directory = opts.directory
        )
    }

    override suspend fun deleteSession(sessionId: String): Result<Unit> = runCatching {
        val conn = resolveConnectionForSession(sessionId)
        api.deleteSession(conn, sessionId)
    }

    override suspend fun switchSession(sessionId: String): Result<Unit> = runCatching {
        // Switching is a UI/navigation concern — no server-side API call needed.
        // The session's data is already tracked by EventDispatcher.
        Unit
    }

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
        return resolveConnection(serverId)
    }
}
