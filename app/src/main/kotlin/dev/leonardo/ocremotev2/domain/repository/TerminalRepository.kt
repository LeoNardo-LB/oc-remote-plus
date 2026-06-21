package dev.leonardo.ocremotev2.domain.repository

import dev.leonardo.ocremotev2.domain.model.TerminalEvent
import kotlinx.coroutines.flow.Flow

interface TerminalRepository {
    fun connectTerminal(serverId: String, sessionId: String): Flow<TerminalEvent>
    suspend fun sendInput(serverId: String, sessionId: String, data: String): Result<Unit>
    suspend fun resize(serverId: String, sessionId: String, cols: Int, rows: Int): Result<Unit>
}
