package dev.leonardo.ocremotev2.domain.usecase

import dev.leonardo.ocremotev2.domain.model.ServerConfig
import dev.leonardo.ocremotev2.domain.repository.ServerRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Use case: observe the list of configured servers.
 * Delegates to [ServerRepository.getServersFlow].
 */
class GetServerListUseCase @Inject constructor(
    private val serverRepository: ServerRepository
) {
    operator fun invoke(): Flow<List<ServerConfig>> {
        return serverRepository.getServersFlow()
    }
}
