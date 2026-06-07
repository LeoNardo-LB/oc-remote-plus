package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.domain.model.ProvidersResponse
import dev.minios.ocremote.domain.repository.ProviderRepository
import javax.inject.Inject

/**
 * Use case: load provider catalog for model selection.
 * Routes through ProviderRepository instead of direct OpenCodeApi access.
 */
class SelectModelUseCase @Inject constructor(
    private val providerRepository: ProviderRepository
) {
    suspend fun loadProviders(serverId: String): ProvidersResponse {
        return providerRepository.loadProviderCatalog(serverId).getOrThrow()
    }
}
