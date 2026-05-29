package dev.minios.ocremote.data.repository.handler

import android.util.Log
import dev.minios.ocremote.BuildConfig
import dev.minios.ocremote.domain.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles session lifecycle events: created, updated, deleted, status, idle, diff, error, compacted.
 * Manages: sessions, sessionStatuses, serverSessions, sessionDiffs, vcsBranch, projectInfo
 */
@Singleton
class SessionEventHandler @Inject constructor() : SseEventHandler {

    companion object {
        private const val TAG = "SessionEventHandler"
    }

    private val _serverSessions = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val serverSessions: StateFlow<Map<String, Set<String>>> = _serverSessions.asStateFlow()

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    private val _sessionStatuses = MutableStateFlow<Map<String, SessionStatus>>(emptyMap())
    val sessionStatuses: StateFlow<Map<String, SessionStatus>> = _sessionStatuses.asStateFlow()

    private val _sessionDiffs = MutableStateFlow<Map<String, List<FileDiff>>>(emptyMap())
    val sessionDiffs: StateFlow<Map<String, List<FileDiff>>> = _sessionDiffs.asStateFlow()

    private val _vcsBranch = MutableStateFlow<String?>(null)
    val vcsBranch: StateFlow<String?> = _vcsBranch.asStateFlow()

    private val _projectInfo = MutableStateFlow<Project?>(null)
    val projectInfo: StateFlow<Project?> = _projectInfo.asStateFlow()

    override fun handle(event: SseEvent, serverId: String): Boolean {
        return when (event) {
            is SseEvent.ServerConnected -> { if (BuildConfig.DEBUG) Log.d(TAG, "Server connected"); true }
            is SseEvent.ServerHeartbeat -> true
            is SseEvent.ServerInstanceDisposed -> {
                if (BuildConfig.DEBUG) Log.d(TAG, "Server instance disposed: ${event.directory}"); true
            }
            is SseEvent.SessionCreated -> { handleSessionCreated(event, serverId); true }
            is SseEvent.SessionUpdated -> { handleSessionUpdated(event, serverId); true }
            is SseEvent.SessionDeleted -> { handleSessionDeleted(event); true }
            is SseEvent.SessionStatus -> { handleSessionStatus(event); true }
            is SseEvent.SessionIdle -> { handleSessionIdle(event); true }
            is SseEvent.SessionDiff -> { handleSessionDiff(event); true }
            is SseEvent.SessionError -> { handleSessionError(event); true }
            is SseEvent.SessionCompacted -> {
                Log.i(TAG, "Session ${event.sessionId} compacted"); true
            }
            is SseEvent.VcsBranchUpdated -> { _vcsBranch.value = event.branch; true }
            is SseEvent.ProjectUpdated -> { _projectInfo.value = event.info; true }
            else -> false
        }
    }

    private fun trackSession(serverId: String, sessionId: String) {
        _serverSessions.update { current ->
            val existing = current[serverId] ?: emptySet()
            current + (serverId to (existing + sessionId))
        }
    }

    private fun handleSessionCreated(event: SseEvent.SessionCreated, serverId: String) {
        trackSession(serverId, event.info.id)
        _sessions.update { (it + event.info).sortedByDescending { s -> s.time.updated } }
        _sessionStatuses.update { it + (event.info.id to SessionStatus.Idle) }
    }

    private fun handleSessionUpdated(event: SseEvent.SessionUpdated, serverId: String) {
        trackSession(serverId, event.info.id)
        _sessions.update { current ->
            val idx = current.indexOfFirst { it.id == event.info.id }
            if (idx >= 0) {
                current.toMutableList().apply { set(idx, event.info) }
            } else {
                if (BuildConfig.DEBUG) Log.d(TAG, "Session ${event.info.id} not found, upserting")
                (current + event.info).sortedByDescending { s -> s.time.updated }
            }
        }
    }

    private fun handleSessionDeleted(event: SseEvent.SessionDeleted) {
        val sessionId = event.info.id
        _sessions.update { it.filter { s -> s.id != sessionId } }
        _sessionStatuses.update { it - sessionId }
        _sessionDiffs.update { it - sessionId }
    }

    private fun handleSessionStatus(event: SseEvent.SessionStatus) {
        _sessionStatuses.update { it + (event.sessionId to event.status) }
        if (BuildConfig.DEBUG) Log.d(TAG, "Session ${event.sessionId} status: ${event.status}")
    }

    private fun handleSessionIdle(event: SseEvent.SessionIdle) {
        _sessionStatuses.update { it + (event.sessionId to SessionStatus.Idle) }
    }

    private fun handleSessionDiff(event: SseEvent.SessionDiff) {
        _sessionDiffs.update { it + (event.sessionId to event.diff) }
    }

    private fun handleSessionError(event: SseEvent.SessionError) {
        Log.e(TAG, "Session ${event.sessionId} error: ${event.error}")
    }

    // ============ Batch Operations ============

    fun setSessions(serverId: String, newSessions: List<Session>) {
        val sessionIds = newSessions.map { it.id }.toSet()
        _serverSessions.update { current ->
            val existing = current[serverId] ?: emptySet()
            current + (serverId to (existing + sessionIds))
        }
        _sessions.update { current ->
            val updated = current.toMutableList()
            for (session in newSessions) {
                val idx = updated.indexOfFirst { it.id == session.id }
                if (idx >= 0) updated[idx] = session else updated.add(session)
            }
            updated.sortedByDescending { it.time.updated }
        }
    }

    fun updateSessionStatus(sessionId: String, status: SessionStatus) {
        _sessionStatuses.update { it + (sessionId to status) }
        if (BuildConfig.DEBUG) Log.d(TAG, "Manually updated session $sessionId status to $status")
    }

    fun clearForServer(serverId: String) {
        val sessionIds = _serverSessions.value[serverId] ?: emptySet()
        if (sessionIds.isEmpty()) {
            _serverSessions.update { it - serverId }
            return
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "Clearing state for server $serverId (${sessionIds.size} sessions)")
        _serverSessions.update { it - serverId }
        _sessions.update { it.filter { s -> s.id !in sessionIds } }
        _sessionStatuses.update { it - sessionIds }
        _sessionDiffs.update { it - sessionIds }
    }

    fun clearAll() {
        _serverSessions.value = emptyMap()
        _sessions.value = emptyList()
        _sessionStatuses.value = emptyMap()
        _sessionDiffs.value = emptyMap()
        _vcsBranch.value = null
        _projectInfo.value = null
    }
}
