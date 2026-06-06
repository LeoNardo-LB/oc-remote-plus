package dev.minios.ocremote.domain.repository

import dev.minios.ocremote.domain.model.ProviderInfo

/**
 * Provider/model management for a connected server.
 */
interface ProviderRepository {
    suspend fun loadProviders(serverId: String): Result<List<ProviderInfo>>
    suspend fun setProviderEnabled(serverId: String, providerId: String, enabled: Boolean): Result<Unit>
    suspend fun connectProviderApi(serverId: String, providerId: String, apiKey: String): Result<Unit>
    suspend fun disconnectProvider(serverId: String, providerId: String): Result<Unit>
    suspend fun setModelVisible(serverId: String, providerId: String, modelId: String, visible: Boolean): Result<Unit>
    suspend fun saveServerConfig(serverId: String): Result<Unit>
}
