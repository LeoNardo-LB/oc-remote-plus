package dev.leonardo.ocremotev2.domain.repository

import dev.leonardo.ocremotev2.domain.model.McpServerStatus
import dev.leonardo.ocremotev2.domain.model.ServerConnection

interface McpRepository {
    suspend fun getMcpServers(): Result<List<McpServerStatus>>
    suspend fun toggleMcpServer(name: String, connect: Boolean): Result<Boolean>
    fun setConnection(conn: ServerConnection)
}
