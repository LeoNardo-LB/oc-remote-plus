package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.dto.response.ProvidersResponse
import dev.minios.ocremote.data.api.ServerConnection
import javax.inject.Inject

/**
 * Use case: select model and load providers.
 * Temporary shell — delegates to OpenCodeApi. Full impl with tests in Phase 4.
 */
class SelectModelUseCase @Inject constructor(
    private val api: OpenCodeApi
) {
    // TODO: Phase 4 — replace api calls with ModelRepository methods

    suspend fun loadProviders(conn: ServerConnection): ProvidersResponse =
        api.getProviders(conn)
}
