package dev.leonardo.ocremotev2.data.repository

import dev.leonardo.ocremotev2.data.api.OpenCodeApi
import dev.leonardo.ocremotev2.domain.model.ServerConnection
import dev.leonardo.ocremotev2.domain.model.AgentInfo
import dev.leonardo.ocremotev2.domain.model.CommandInfo
import dev.leonardo.ocremotev2.domain.repository.AgentRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgentRepositoryImpl @Inject constructor(
    private val api: OpenCodeApi,
    private val serverRepo: ServerDataStore
) : AgentRepository {

    override suspend fun listAgents(serverId: String): Result<List<AgentInfo>> = runCatching {
        val conn = resolveConnection(serverId)
        api.listAgents(conn).map { it.toDomain() }
    }

    override suspend fun switchAgent(serverId: String, sessionId: String, agentId: String): Result<Unit> {
        return Result.failure(UnsupportedOperationException("switchAgent not yet supported by API"))
    }

    override suspend fun loadCommands(serverId: String): Result<List<CommandInfo>> = runCatching {
        val conn = resolveConnection(serverId)
        api.listCommands(conn).map { it.toDomain() }
    }

    override suspend fun searchFiles(
        serverId: String,
        query: String,
        dirs: String,
        directory: String?,
        limit: Int
    ): Result<List<String>> = runCatching {
        val conn = resolveConnection(serverId)
        api.findFiles(conn, query, dirs = dirs, directory = directory, limit = limit)
    }

    private suspend fun resolveConnection(serverId: String): ServerConnection {
        val config = serverRepo.getServer(serverId)
            ?: throw IllegalStateException("Server config not found: $serverId")
        return ServerConnection.from(config.url, config.username, config.password)
    }
}

private fun dev.leonardo.ocremotev2.data.dto.response.AgentInfo.toDomain() = AgentInfo(
    name = name,
    description = description,
    mode = mode,
    hidden = hidden,
    color = color,
)

private fun dev.leonardo.ocremotev2.data.dto.response.CommandInfo.toDomain() = CommandInfo(
    name = name,
    description = description,
    source = source,
    hints = hints,
)
