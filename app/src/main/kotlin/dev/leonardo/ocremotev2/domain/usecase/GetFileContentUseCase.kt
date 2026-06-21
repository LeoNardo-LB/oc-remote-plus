package dev.leonardo.ocremotev2.domain.usecase

import dev.leonardo.ocremotev2.domain.repository.FileRepository
import javax.inject.Inject

/**
 * Use case: get file content on a server.
 */
class GetFileContentUseCase @Inject constructor(
    private val fileRepository: FileRepository
) {
    suspend operator fun invoke(serverId: String, directory: String, path: String) =
        fileRepository.getFileContent(serverId, directory, path)
}
