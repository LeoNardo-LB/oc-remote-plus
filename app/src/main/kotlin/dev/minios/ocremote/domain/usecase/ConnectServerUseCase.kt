package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.domain.model.ServerConfig
import dev.minios.ocremote.domain.repository.ServerRepository
import javax.inject.Inject

/**
 * Use case: connect to a server.
 * Used by Phase 4 HomeViewModel.
 */
class ConnectServerUseCase @Inject constructor(
    private val serverRepository: ServerRepository
) {
    suspend operator fun invoke(server: ServerConfig): Result<Unit> =
        serverRepository.connect(server)
}
