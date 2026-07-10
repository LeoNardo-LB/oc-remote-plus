package dev.leonardo.ocremotev2.fakes

import dev.leonardo.ocremotev2.domain.model.FileContent
import dev.leonardo.ocremotev2.domain.model.FileNode
import dev.leonardo.ocremotev2.domain.model.ContentType
import dev.leonardo.ocremotev2.domain.repository.FileRepository
import javax.inject.Singleton

@Singleton
class FakeFileRepository : FileRepository {

    var listDirectoryResult: Result<List<FileNode>> = Result.success(emptyList())
    var getFileContentResult: Result<FileContent> = Result.success(
        FileContent(path = "test.txt", type = ContentType.TEXT, content = "")
    )
    var findFilesResult: Result<List<String>> = Result.success(emptyList())

    override suspend fun listDirectory(
        serverId: String,
        directory: String,
        path: String
    ): Result<List<FileNode>> = listDirectoryResult

    override suspend fun getFileContent(
        serverId: String,
        directory: String,
        path: String
    ): Result<FileContent> = getFileContentResult

    override suspend fun findFiles(
        serverId: String,
        directory: String,
        query: String,
        limit: Int
    ): Result<List<String>> = findFilesResult
}
