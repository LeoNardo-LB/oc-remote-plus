package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.domain.repository.FileRepository
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
