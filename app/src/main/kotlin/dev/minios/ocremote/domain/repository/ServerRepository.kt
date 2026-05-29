package dev.minios.ocremote.domain.repository

import android.content.Context
import dev.minios.ocremote.domain.model.LocalServerState
import dev.minios.ocremote.domain.model.ProviderInfo
import dev.minios.ocremote.domain.model.ServerConfig
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for server management.
 * Aligned with spec §4.1.1.
 * Implemented by the Data layer in Phase 3.
 *
 * Phase 4 UseCase 需要 connect/disconnect/testConnection/local-server/providers 等方法。
 * Phase 3 impl: connect/disconnect → OpenCodeConnectionService,
 *               local server → LocalServerManager,
 *               providers → OpenCodeApi.
 */
interface ServerRepository {

    // ── Server CRUD ──

    /**
     * Observe the list of configured servers.
     */
    fun getServersFlow(): Flow<List<ServerConfig>>

    /**
     * Add a new server configuration.
     */
    suspend fun addServer(config: ServerConfig): Result<Unit>

    /**
     * Remove a server configuration by its ID.
     */
    suspend fun removeServer(id: String): Result<Unit>

    /**
     * Update an existing server configuration.
     */
    suspend fun updateServer(server: ServerConfig): Result<Unit>

    /**
     * Get a server by its ID. Returns null if not found.
     */
    suspend fun getServer(id: String): ServerConfig?

    // ── Connection lifecycle ──
    // Phase 3 impl delegates to OpenCodeConnectionService

    /**
     * Establish a connection to the given server.
     * Phase 3 impl: delegates to OpenCodeConnectionService.connect(server).
     */
    suspend fun connect(server: ServerConfig): Result<Unit>

    /**
     * Disconnect from the server identified by serverId.
     * Phase 3 impl: delegates to OpenCodeConnectionService.disconnect(serverId).
     */
    suspend fun disconnect(serverId: String): Result<Unit>

    /**
     * Test connectivity to a server.
     * Returns true if the server is reachable and healthy.
     */
    suspend fun testConnection(server: ServerConfig): Result<Boolean>

    // ── Local server management ──
    // Phase 3 impl delegates to LocalServerManager

    /**
     * Get the shell command used to set up the local server (Termux).
     */
    fun getLocalSetupCommand(): String

    /**
     * Run the local server setup process.
     */
    suspend fun setupLocalServer(context: Context): Result<Unit>

    /**
     * Start the local server process.
     */
    suspend fun startLocalServer(context: Context): Result<Unit>

    /**
     * Stop the local server process.
     */
    suspend fun stopLocalServer(context: Context): Result<Unit>

    /**
     * Query the current local server status.
     */
    suspend fun getLocalServerState(): Result<LocalServerState>

    // ── Provider management ──
    // Phase 3 impl delegates to OpenCodeApi

    /**
     * Load available providers for a connected server.
     */
    suspend fun loadProviders(serverId: String): Result<List<ProviderInfo>>

    /**
     * Enable or disable a specific provider.
     */
    suspend fun setProviderEnabled(serverId: String, providerId: String, enabled: Boolean): Result<Unit>

    /**
     * Connect a provider API key.
     */
    suspend fun connectProviderApi(serverId: String, providerId: String, apiKey: String): Result<Unit>

    /**
     * Disconnect a provider.
     */
    suspend fun disconnectProvider(serverId: String, providerId: String): Result<Unit>

    /**
     * Set model visibility within a provider.
     */
    suspend fun setModelVisible(serverId: String, providerId: String, modelId: String, visible: Boolean): Result<Unit>

    /**
     * Persist the server's current configuration.
     */
    suspend fun saveServerConfig(serverId: String): Result<Unit>
}
