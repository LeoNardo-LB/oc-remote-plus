package dev.minios.ocremote.service

import android.util.Log
import dev.minios.ocremote.BuildConfig
import dev.minios.ocremote.data.api.NetworkMonitor
import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.api.ServerConnection
import dev.minios.ocremote.data.api.SseClient
import dev.minios.ocremote.data.api.SseReadTimeoutTracker
import dev.minios.ocremote.data.repository.EventDispatcher
import dev.minios.ocremote.domain.model.ServerConfig
import dev.minios.ocremote.domain.model.SessionStatus
import dev.minios.ocremote.domain.model.SseEvent
import dev.minios.ocremote.data.repository.SettingsDataStore
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
private const val COOLDOWN_CHECK_INTERVAL_MS = 30_000L

/**
 * Per-server connection state.
 */
data class ServerConnectionState(
    val config: ServerConfig,
    val conn: ServerConnection,
    val sseJob: Job,
    val isConnected: Boolean = false,
    val onEvent: (ServerConfig, SseEvent) -> Unit = { _, _ -> }
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
    private val settingsRepository: SettingsDataStore,
    private val networkMonitor: NetworkMonitor
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** All active/pending server connections keyed by serverId. */
    val connections = mutableMapOf<String, ServerConnectionState>()

    /** Per-server timeout trackers for SSE read timeout cooldown logic. */
    private val timeoutTrackers = mutableMapOf<String, SseReadTimeoutTracker>()

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
            config = server, conn = conn, sseJob = job, isConnected = false, onEvent = onEvent
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
        timeoutTrackers.remove(serverId)
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
        timeoutTrackers.clear()
        _connectedServerIds.value = emptySet()
        _connectingServerIds.value = emptySet()
        for (serverId in serverIds) {
            eventDispatcher.clearForServer(serverId)
        }
    }

    /**
     * Reconnect all active connections. Used when network recovers from a loss.
     * Restarts the SSE connection for each server so the auto-reconnect loop
     * resets its attempt counter and reconnects immediately.
     */
    fun reconnectAll() {
        for ((serverId, state) in connections.toMap()) {
            reconnectServer(serverId)
        }
    }

    /**
     * Reconnect a single server connection. Restarts the SSE connection so
     * the auto-reconnect loop resets its attempt counter and reconnects immediately.
     */
    private fun reconnectServer(serverId: String) {
        val state = connections[serverId] ?: return
        Log.i(TAG, "Reconnecting server $serverId after network recovery")
        timeoutTrackers[serverId]?.reset()
        state.sseJob.cancel()
        val newJob = startSseConnection(state.config, state.conn, state.onEvent)
        connections[serverId] = state.copy(sseJob = newJob)
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
            val tracker = timeoutTrackers.getOrPut(server.id) { SseReadTimeoutTracker() }

            while (isActive) {
                attempt++

                // If in cooldown, wait and skip reconnection attempt
                if (tracker.isInCooldown()) {
                    Log.i(TAG, "[${server.displayName}] SSE in cooldown, waiting ${COOLDOWN_CHECK_INTERVAL_MS}ms")
                    delay(COOLDOWN_CHECK_INTERVAL_MS)
                    continue
                }

                Log.i(TAG, "[${server.displayName}] SSE connection attempt #$attempt")

                // Pre-load sessions via REST API for all projects
                preLoadSessions(server, conn)

                // On reconnect (not first connection), recover messages missed during disconnection
                if (attempt > 1) {
                    recoverMessages(server, conn)
                }

                try {
                    sseClient.connectToGlobalEvents(conn)
                        .catch { error ->
                            Log.e(TAG, "[${server.displayName}] SSE stream error", error)
                            updateServerConnected(server.id, false)
                            tracker.recordTimeout()
                            throw error
                        }
                        .collect { event ->
                            if (connections[server.id]?.isConnected != true) {
                                updateServerConnected(server.id, true)
                                attempt = 0
                            }
                            tracker.recordSuccess()
                            // Dispatch to EventDispatcher for state updates
                            eventDispatcher.processEvent(event, server.id)
                            // Route to caller for notification handling
                            onEvent(server, event)
                        }

                    // Flow completed normally (server closed connection)
                    Log.w(TAG, "[${server.displayName}] SSE stream completed")
                    updateServerConnected(server.id, false)
                    if (tracker.shouldEnterCooldown()) {
                        tracker.enterCooldown()
                        Log.w(TAG, "[${server.displayName}] Entering SSE cooldown after ${tracker.consecutiveTimeouts} consecutive timeouts")
                    } else {
                        tracker.recordTimeout()
                    }
                } catch (e: CancellationException) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "[${server.displayName}] SSE job cancelled, not reconnecting")
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "[${server.displayName}] SSE connection failed: ${e.message}")
                    updateServerConnected(server.id, false)
                    if (tracker.shouldEnterCooldown()) {
                        tracker.enterCooldown()
                        Log.w(TAG, "[${server.displayName}] Entering SSE cooldown after ${tracker.consecutiveTimeouts} consecutive timeouts")
                    } else {
                        tracker.recordTimeout()
                    }
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
            // Initialize session statuses from server
            syncSessionStatuses(conn)
        } catch (e: Exception) {
            Log.w(TAG, "[${server.displayName}] Failed to pre-load sessions: ${e.message}")
        }
    }

    /**
     * Recover messages for all active sessions of a server after SSE reconnection.
     * Phase 1: Replace messages with REST data (source of truth).
     * Phase 2: Sync session statuses from server — only mark idle sessions as idle.
     */
    private suspend fun recoverMessages(server: ServerConfig, conn: ServerConnection) {
        val sessionIds = eventDispatcher.serverSessions.value[server.id] ?: return
        if (sessionIds.isEmpty()) return

        // Phase 1: Recover messages (REST as source of truth)
        Log.i(TAG, "[${server.displayName}] Recovering messages for ${sessionIds.size} sessions")
        var recoveredCount = 0
        for (sessionId in sessionIds) {
            try {
                val messages = api.listMessages(conn, sessionId)
                eventDispatcher.replaceMessages(sessionId, messages)
                recoveredCount++
            } catch (e: Exception) {
                Log.w(TAG, "[${server.displayName}] Failed to recover messages for session $sessionId: ${e.message}")
            }
        }
        Log.i(TAG, "[${server.displayName}] Recovered messages for $recoveredCount/${sessionIds.size} sessions")

        // Phase 2: Sync real session statuses from server
        syncSessionStatuses(conn)
    }

    /**
     * Fetch real session statuses from REST API and update the dispatcher.
     * Only marks sessions as idle when the server confirms they are idle.
     */
    private suspend fun syncSessionStatuses(conn: ServerConnection) {
        try {
            val result = api.fetchSessionStatus(conn)
            result.onSuccess { statuses ->
                val statusMap = statuses.mapValues { (_, info) ->
                    when (info.type) {
                        "busy" -> SessionStatus.Busy
                        "retry" -> SessionStatus.Retry(
                            attempt = info.attempt ?: 0,
                            message = info.message ?: "",
                            next = info.next ?: 0L
                        )
                        else -> SessionStatus.Idle
                    }
                }
                eventDispatcher.syncAllSessionStatuses(statusMap)

                // Only mark truly idle sessions with message completion fix (protected)
                for ((sessionId, status) in statusMap) {
                    if (status is SessionStatus.Idle) {
                        eventDispatcher.markSessionIdleProtected(sessionId)
                    }
                }
                Log.i(TAG, "Synced statuses for ${statusMap.size} sessions from REST")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sync session statuses: ${e.message}")
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
