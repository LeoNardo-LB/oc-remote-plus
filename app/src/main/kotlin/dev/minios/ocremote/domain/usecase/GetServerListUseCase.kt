package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.domain.model.ServerConfig
import dev.minios.ocremote.domain.repository.ServerRepository
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
