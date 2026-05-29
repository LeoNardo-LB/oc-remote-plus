package dev.minios.ocremote.service

import android.util.Log
import dev.minios.ocremote.BuildConfig
import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.api.ServerConnection
import dev.minios.ocremote.data.api.SseClient
import dev.minios.ocremote.data.repository.EventDispatcher
import dev.minios.ocremote.domain.model.ServerConfig
import dev.minios.ocremote.domain.model.SseEvent
import dev.minios.ocremote.data.repository.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SseConnManager"
private const val RECONNECT_BASE_DELAY_MS = 1_000L
private const val RECONNECT_MAX_DELAY_MS = 30_000L
private const val RECONNECT_BACKOFF_FACTOR = 2.0

/**
 * Per-server connection state.
 */
data class ServerConnectionState(
    val config: ServerConfig,
    val conn: ServerConnection,
    val sseJob: Job,
    val isConnected: Boolean = false
)

/**
 * Manages SSE connections to multiple servers.
 * Handles connection lifecycle, auto-reconnect, session pre-loading,
 * and event dispatching via [EventDispatcher].
 *
 * Event routing (e.g. for notifications) is delegated to the caller via [onEvent] callback.
 */
@Singleton
class SseConnectionManager @Inject constructor(
    private val api: OpenCodeApi,
    private val sseClient: SseClient,
    private val eventDispatcher: EventDispatcher,
    private val settingsRepository: SettingsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** All active/pending server connections keyed by serverId. */
    val connections = mutableMapOf<String, ServerConnectionState>()

    /** Observable set of server IDs that are actually connected (SSE stream active). */
    val connectedServerIds: StateFlow<Set<String>>
        get() = _connectedServerIds.asStateFlow()
    private val _connectedServerIds = MutableStateFlow<Set<String>>(emptySet())

    /** Observable set of server IDs that are attempting to connect. */
    val connectingServerIds: StateFlow<Set<String>>
        get() = _connectingServerIds.asStateFlow()
    private val _connectingServerIds = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Start an SSE connection to [server].
     *
     * @param server  The server configuration to connect to.
     * @param onEvent  Callback invoked for each SSE event (for notification routing, etc.).
     * @return The [Job] representing the connection coroutine.
     */
    fun startConnection(
        server: ServerConfig,
        onEvent: (ServerConfig, SseEvent) -> Unit
    ): Job {
        val conn = ServerConnection.from(server.url, server.username, server.password)
        val job = startSseConnection(server, conn, onEvent)

        connections[server.id] = ServerConnectionState(
            config = server, conn = conn, sseJob = job, isConnected = false
        )
        _connectingServerIds.update { it + server.id }

        return job
    }

    /**
     * Stop the SSE connection to a specific server.
     */
    fun stopConnection(serverId: String) {
        val state = connections.remove(serverId) ?: return
        state.sseJob.cancel()
        _connectedServerIds.update { it - serverId }
        _connectingServerIds.update { it - serverId }
        eventDispatcher.clearForServer(serverId)
    }

    /**
     * Stop all SSE connections.
     */
    fun stopAllConnections() {
        for ((_, state) in connections) {
            state.sseJob.cancel()
        }
        val serverIds = connections.keys.toList()
        connections.clear()
        _connectedServerIds.value = emptySet()
        _connectingServerIds.value = emptySet()
        for (serverId in serverIds) {
            eventDispatcher.clearForServer(serverId)
        }
    }

    /**
     * Check if a specific server is connected.
     */
    fun isConnected(serverId: String): Boolean {
        return connections[serverId]?.sseJob?.isActive == true
    }

    /**
     * Get the [ServerConnection] for a given server, if any.
     */
    fun getConnection(serverId: String): ServerConnection? {
        return connections[serverId]?.conn
    }

    /**
     * Cancel the internal coroutine scope. Call during service teardown.
     */
    fun cancelScope() {
        scope.cancel()
    }

    // ============ SSE Connection with Auto-Reconnect ============

    private fun startSseConnection(
        server: ServerConfig,
        conn: ServerConnection,
        onEvent: (ServerConfig, SseEvent) -> Unit
    ): Job {
        return scope.launch {
            var attempt = 0

            while (isActive) {
                attempt++
                Log.i(TAG, "[${server.displayName}] SSE connection attempt #$attempt")

                // Pre-load sessions via REST API for all projects
                preLoadSessions(server, conn)

                try {
                    sseClient.connectToGlobalEvents(conn)
                        .catch { error ->
                            Log.e(TAG, "[${server.displayName}] SSE stream error", error)
                            updateServerConnected(server.id, false)
                            throw error
                        }
                        .collect { event ->
                            if (connections[server.id]?.isConnected != true) {
                                updateServerConnected(server.id, true)
                                attempt = 0
                            }
                            // Dispatch to EventDispatcher for state updates
                            eventDispatcher.processEvent(event, server.id)
                            // Route to caller for notification handling
                            onEvent(server, event)
                        }

                    // Flow completed normally (server closed connection)
                    Log.w(TAG, "[${server.displayName}] SSE stream completed")
                    updateServerConnected(server.id, false)
                } catch (e: CancellationException) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "[${server.displayName}] SSE job cancelled, not reconnecting")
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "[${server.displayName}] SSE connection failed: ${e.message}")
                    updateServerConnected(server.id, false)
                }

                // If this server was removed from connections, stop the loop
                if (!connections.containsKey(server.id)) break

                val delayMs = calculateBackoff(attempt)
                Log.i(TAG, "[${server.displayName}] Reconnecting in ${delayMs}ms (attempt #$attempt)")
                delay(delayMs)
            }
        }
    }

    private suspend fun preLoadSessions(server: ServerConfig, conn: ServerConnection) {
        try {
            val projects = api.listProjects(conn)
            if (projects.isEmpty()) {
                // Fallback: load sessions without directory header (server CWD only)
                val sessions = api.listSessions(conn)
                eventDispatcher.setSessions(server.id, sessions)
                Log.i(TAG, "[${server.displayName}] Pre-loaded ${sessions.size} sessions (no projects)")
            } else {
                var totalSessions = 0
                for (project in projects) {
                    try {
                        val sessions = api.listSessions(conn, directory = project.worktree)
                        eventDispatcher.setSessions(server.id, sessions)
                        totalSessions += sessions.size
                    } catch (e: Exception) {
                        Log.w(TAG, "[${server.displayName}] Failed to pre-load sessions for project ${project.displayName}: ${e.message}")
                    }
                }
                Log.i(TAG, "[${server.displayName}] Pre-loaded $totalSessions sessions across ${projects.size} projects")
            }
        } catch (e: Exception) {
            Log.w(TAG, "[${server.displayName}] Failed to pre-load sessions: ${e.message}")
        }
    }

    private fun updateServerConnected(serverId: String, connected: Boolean) {
        val state = connections[serverId] ?: return
        connections[serverId] = state.copy(isConnected = connected)
        if (connected) {
            _connectingServerIds.update { it - serverId }
            _connectedServerIds.update { it + serverId }
        } else {
            _connectedServerIds.update { it - serverId }
            _connectingServerIds.update { it + serverId }
        }
    }

    private suspend fun calculateBackoff(attempt: Int): Long {
        val maxDelay = when (settingsRepository.reconnectMode.first()) {
            "aggressive" -> 5_000L
            "conservative" -> 60_000L
            else -> RECONNECT_MAX_DELAY_MS // normal: 30s
        }
        val delay = (RECONNECT_BASE_DELAY_MS * Math.pow(RECONNECT_BACKOFF_FACTOR, (attempt - 1).coerceAtLeast(0).toDouble())).toLong()
        return delay.coerceAtMost(maxDelay)
    }
}
