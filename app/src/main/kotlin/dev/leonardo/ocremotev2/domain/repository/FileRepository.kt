package dev.leonardo.ocremotev2.domain.repository

import dev.leonardo.ocremotev2.domain.model.FileContent
import dev.leonardo.ocremotev2.domain.model.FileNode

interface FileRepository {
    suspend fun listDirectory(serverId: String, directory: String, path: String): Result<List<FileNode>>
    suspend fun getFileContent(serverId: String, directory: String, path: String): Result<FileContent>
    suspend fun findFiles(serverId: String, directory: String, query: String, limit: Int = 50): Result<List<String>>
}
