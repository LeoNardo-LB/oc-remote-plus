package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.data.dto.response.AgentInfo
import dev.minios.ocremote.data.dto.response.CommandInfo
import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.api.ServerConnection
import javax.inject.Inject

/**
 * Use case: manage agents, commands, and file search.
 * Temporary shell — delegates to OpenCodeApi. Full impl with tests in Phase 4.
 */
class ManageAgentUseCase @Inject constructor(
    private val api: OpenCodeApi
) {
    // TODO: Phase 4 — replace api calls with AgentRepository methods

    suspend fun loadAgents(conn: ServerConnection): List<AgentInfo> =
        api.listAgents(conn)

    suspend fun loadCommands(conn: ServerConnection): List<CommandInfo> =
        api.listCommands(conn)

    suspend fun searchFiles(conn: ServerConnection, query: String, dirs: String, directory: String?, limit: Int): List<String> =
        api.findFiles(conn, query, dirs, directory, limit)
}
