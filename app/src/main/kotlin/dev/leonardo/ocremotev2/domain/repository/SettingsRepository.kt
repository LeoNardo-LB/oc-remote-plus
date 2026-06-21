package dev.leonardo.ocremotev2.domain.repository

import dev.leonardo.ocremotev2.domain.model.AppSettings
import kotlinx.coroutines.flow.Flow

/**
 * Domain-layer interface for application settings.
 * Aligned with spec §4.1.1.
 * Implemented by the Data layer in Phase 3.
 */
interface SettingsRepository {

    /**
     * Observe the aggregated application settings.
     */
    fun getSettingsFlow(): Flow<AppSettings>

    /**
     * Update the application settings.
     */
    suspend fun updateSettings(settings: AppSettings): Result<Unit>

    /**
     * Observe the set of hidden model keys for a server.
     * Key format: "providerId:modelId".
     */
    fun hiddenModels(serverId: String): Flow<Set<String>>
}
