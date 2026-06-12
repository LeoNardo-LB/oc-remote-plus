package dev.minios.ocremote.domain.repository

import dev.minios.ocremote.domain.model.McpServerStatus

interface McpRepository {
    suspend fun getMcpServers(): Result<List<McpServerStatus>>
    suspend fun toggleMcpServer(name: String, connect: Boolean): Result<Boolean>
    fun setConnection(conn: dev.minios.ocremote.data.api.ServerConnection)
}
