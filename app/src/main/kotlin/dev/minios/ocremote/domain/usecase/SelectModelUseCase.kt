package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.api.ServerConnection
import dev.minios.ocremote.data.dto.response.ProvidersResponse
import dev.minios.ocremote.domain.repository.ServerRepository
import javax.inject.Inject

/**
 * Use case: select model and load providers.
 * Uses ServerRepository to resolve serverId → ServerConnection,
 * then delegates to OpenCodeApi for provider data.
 *
 * Note: Returns DTO ProvidersResponse because the ViewModel depends on
 * DTO-specific provider model structures (Map<String, ProviderModel>).
 * Full migration to domain types requires ViewModel refactoring.
 */
class SelectModelUseCase @Inject constructor(
    private val serverRepository: ServerRepository,
    private val api: OpenCodeApi
) {
    suspend fun loadProviders(serverId: String): ProvidersResponse {
        val config = serverRepository.getServer(serverId)
            ?: throw IllegalStateException("Server not found: $serverId")
        val conn = ServerConnection.from(config.url, config.username, config.password)
        return api.getProviders(conn)
    }
}
