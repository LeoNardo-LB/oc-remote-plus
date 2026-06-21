package dev.leonardo.ocremotev2.data.repository

import dev.leonardo.ocremotev2.data.api.OpenCodeApi
import dev.leonardo.ocremotev2.domain.model.ServerConnection
import dev.leonardo.ocremotev2.domain.model.McpServerStatus
import dev.leonardo.ocremotev2.domain.repository.McpRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class McpRepositoryImpl @Inject constructor(
    private val api: OpenCodeApi
) : McpRepository {

    @Volatile
    private var connection: ServerConnection? = null

    override fun setConnection(conn: ServerConnection) {
        connection = conn
    }

    private fun requireConnection(): ServerConnection =
        connection ?: throw IllegalStateException("McpRepository: ServerConnection not set. Call setConnection() first.")

    override suspend fun getMcpServers(): Result<List<McpServerStatus>> = runCatching {
        val conn = requireConnection()
        val statusMap = api.getMcpStatus(conn)
        val configMap = api.getConfig(conn).mcp ?: emptyMap()

        statusMap.map { (name, entry) ->
            val config = configMap[name]
            McpServerStatus(
                name = name,
                type = config?.type ?: "local",
                status = entry.status,
                command = config?.command,
                url = config?.url,
            )
        }
    }

    override suspend fun toggleMcpServer(name: String, connect: Boolean): Result<Boolean> = runCatching {
        val conn = requireConnection()
        if (connect) {
            api.connectMcpServer(conn, name)
        } else {
            api.disconnectMcpServer(conn, name)
        }
    }
}
