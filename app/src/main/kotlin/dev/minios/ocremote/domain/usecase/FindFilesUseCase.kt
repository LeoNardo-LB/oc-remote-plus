package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.domain.repository.FileRepository
import javax.inject.Inject

class FindFilesUseCase @Inject constructor(
    private val fileRepository: FileRepository
) {
    suspend operator fun invoke(serverId: String, directory: String, query: String, limit: Int = 50) =
        fileRepository.findFiles(serverId, directory, query, limit)
}
