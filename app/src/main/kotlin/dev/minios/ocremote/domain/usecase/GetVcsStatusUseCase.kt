package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.domain.repository.VcsRepository
import javax.inject.Inject

/**
 * Use case: get VCS status for a directory on a server.
 */
class GetVcsStatusUseCase @Inject constructor(
    private val vcsRepository: VcsRepository
) {
    suspend operator fun invoke(serverId: String, directory: String) =
        vcsRepository.getStatus(serverId, directory)
}
