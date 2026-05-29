package dev.minios.ocremote.data.repository.handler

import dev.minios.ocremote.domain.model.SseEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles permission events: asked, replied.
 * Manages: permissions
 */
@Singleton
class PermissionEventHandler @Inject constructor() : SseEventHandler {

    private val _permissions = MutableStateFlow<Map<String, List<SseEvent.PermissionAsked>>>(emptyMap())
    val permissions: StateFlow<Map<String, List<SseEvent.PermissionAsked>>> = _permissions.asStateFlow()

    override fun handle(event: SseEvent, serverId: String): Boolean {
        return when (event) {
            is SseEvent.PermissionAsked -> { handlePermissionAsked(event); true }
            is SseEvent.PermissionReplied -> { handlePermissionReplied(event); true }
            else -> false
        }
    }

    private fun handlePermissionAsked(event: SseEvent.PermissionAsked) {
        _permissions.update { current ->
            val sessionPerms = current[event.sessionId]?.toMutableList() ?: mutableListOf()
            sessionPerms.add(event)
            current + (event.sessionId to sessionPerms)
        }
    }

    private fun handlePermissionReplied(event: SseEvent.PermissionReplied) {
        _permissions.update { current ->
            val sessionPerms = current[event.sessionId]?.filter { it.id != event.requestId }
            if (sessionPerms != null) current + (event.sessionId to sessionPerms) else current
        }
    }

    fun removePermission(permissionId: String) {
        _permissions.update { current ->
            current.mapValues { (_, perms) -> perms.filter { it.id != permissionId } }
        }
    }

    fun setPermissions(sessionId: String, perms: List<SseEvent.PermissionAsked>) {
        _permissions.update { current ->
            if (perms.isEmpty()) current - sessionId else current + (sessionId to perms)
        }
    }

    fun clearForSession(sessionId: String) {
        _permissions.update { it - sessionId }
    }

    fun clearForServer(sessionIds: Set<String>) {
        _permissions.update { it - sessionIds }
    }

    fun clearAll() {
        _permissions.value = emptyMap()
    }
}
