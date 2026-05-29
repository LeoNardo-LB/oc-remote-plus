package dev.minios.ocremote.domain.repository

import dev.minios.ocremote.domain.model.CreateSessionOpts
import dev.minios.ocremote.domain.model.Session
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for session management.
 * Aligned with spec §4.1.1.
 * Implemented by the Data layer in Phase 3.
 */
interface SessionRepository {

    /**
     * Observe sessions for a specific server connection.
     * Phase 3 impl: delegates to EventReducer.sessionsFlow filtered by serverId.
     */
    fun getSessionsFlow(serverId: String): Flow<List<Session>>

    /**
     * Create a new session on the specified server with the given options.
     * Returns the created [Session] on success.
     */
    suspend fun createSession(serverId: String, opts: CreateSessionOpts): Result<Session>

    /**
     * Delete a session by its ID.
     */
    suspend fun deleteSession(sessionId: String): Result<Unit>

    /**
     * Switch the active session.
     * Phase 3 impl: delegates to OpenCodeApi or connection service.
     */
    suspend fun switchSession(sessionId: String): Result<Unit>
}
