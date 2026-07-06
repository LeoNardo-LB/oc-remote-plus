## Task 11 Diff: 668384e3..1ed85723
### Commits
1ed85723 refactor(state): recovery paths collapse into syncFromRest
### Stat
 .../ocremotev2/service/SseConnectionManager.kt     | 65 ++++------------------
 .../ui/screens/sessions/SessionListViewModel.kt    | 55 +++---------------
 2 files changed, 18 insertions(+), 102 deletions(-)
### Full Diff
diff --git a/app/src/main/kotlin/dev/leonardo/ocremotev2/service/SseConnectionManager.kt b/app/src/main/kotlin/dev/leonardo/ocremotev2/service/SseConnectionManager.kt
index 19940dce..f62534f4 100644
--- a/app/src/main/kotlin/dev/leonardo/ocremotev2/service/SseConnectionManager.kt
+++ b/app/src/main/kotlin/dev/leonardo/ocremotev2/service/SseConnectionManager.kt
@@ -2,24 +2,23 @@
 
 import android.util.Log
 import java.util.concurrent.ConcurrentHashMap
 import dev.leonardo.ocremotev2.BuildConfig
 import dev.leonardo.ocremotev2.data.api.NetworkMonitor
 import dev.leonardo.ocremotev2.data.api.file.FileApi
 import dev.leonardo.ocremotev2.data.api.message.MessageApi
 import dev.leonardo.ocremotev2.data.api.session.SessionApi
-import dev.leonardo.ocremotev2.data.api.RestSessionStatusInfo
 import dev.leonardo.ocremotev2.domain.model.ServerConnection
 import dev.leonardo.ocremotev2.data.api.SseClient
 import dev.leonardo.ocremotev2.data.api.SseReadTimeoutTracker
 import dev.leonardo.ocremotev2.data.repository.EventDispatcher
+import dev.leonardo.ocremotev2.data.repository.SessionStateService
 import dev.leonardo.ocremotev2.domain.model.Project
 import dev.leonardo.ocremotev2.domain.model.ServerConfig
-import dev.leonardo.ocremotev2.domain.model.SessionStatus
 import dev.leonardo.ocremotev2.domain.model.SseEvent
 import dev.leonardo.ocremotev2.data.repository.SettingsDataStore
 import kotlinx.coroutines.*
 import kotlinx.coroutines.flow.MutableStateFlow
 import kotlinx.coroutines.flow.StateFlow
 import kotlinx.coroutines.flow.asStateFlow
 import kotlinx.coroutines.flow.catch
 import kotlinx.coroutines.flow.first
@@ -54,17 +53,18 @@ data class ServerConnectionState(
 @Singleton
 class SseConnectionManager @Inject constructor(
     private val sessionApi: SessionApi,
     private val messageApi: MessageApi,
     private val fileApi: FileApi,
     private val sseClient: SseClient,
     private val eventDispatcher: EventDispatcher,
     private val settingsRepository: SettingsDataStore,
-    private val networkMonitor: NetworkMonitor
+    private val networkMonitor: NetworkMonitor,
+    private val sessionStateService: SessionStateService,
 ) {
     private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
 
     /** All active/pending server connections keyed by serverId. */
     val connections = ConcurrentHashMap<String, ServerConnectionState>()
 
     /** Per-server timeout trackers for SSE read timeout cooldown logic. */
     private val timeoutTrackers = ConcurrentHashMap<String, SseReadTimeoutTracker>()
@@ -275,18 +275,20 @@ class SseConnectionManager @Inject constructor(
                         eventDispatcher.setSessions(server.id, sessions)
                         totalSessions += sessions.size
                     } catch (e: Exception) {
                         Log.w(TAG, "[${server.displayName}] Failed to pre-load sessions for project ${project.displayName}: ${e.message}")
                     }
                 }
                 Log.i(TAG, "[${server.displayName}] Pre-loaded $totalSessions sessions across ${projects.size} projects")
             }
-            // Initialize session statuses from server
-            syncSessionStatuses(conn)
+            // Initialize session statuses from server via the unified FSM pipeline
+            // (aggregate across project worktrees + absence=idle + incomplete-protection).
+            sessionStateService.setServerId(server.id)
+            sessionStateService.syncFromRest(projects)
         } catch (e: Exception) {
             Log.w(TAG, "[${server.displayName}] Failed to pre-load sessions: ${e.message}")
         }
     }
 
     /**
      * Recover messages for all active sessions of a server after SSE reconnection.
      * Phase 1: Replace messages with REST data (source of truth).
@@ -305,65 +307,20 @@ class SseConnectionManager @Inject constructor(
                 eventDispatcher.replaceMessages(sessionId, messages)
                 recoveredCount++
             } catch (e: Exception) {
                 Log.w(TAG, "[${server.displayName}] Failed to recover messages for session $sessionId: ${e.message}")
             }
         }
         Log.i(TAG, "[${server.displayName}] Recovered messages for $recoveredCount/${sessionIds.size} sessions")
 
-        // Phase 2: Sync real session statuses from server
-        syncSessionStatuses(conn)
-    }
-
-    /**
-     * Fetch real session statuses from REST API and update the dispatcher.
-     * Only marks sessions as idle when the server confirms they are idle.
-     */
-    private suspend fun syncSessionStatuses(conn: ServerConnection) {
-        try {
-            // Aggregate across ALL project worktrees: the server isolates status
-            // per-directory, so a single null-directory query would miss non-default
-            // worktrees' active sessions (treated as idle). See session-status-sync
-            // investigation for root cause.
-            val projects = fileApi.listProjects(conn)
-            val aggregated = mutableMapOf<String, RestSessionStatusInfo>()
-            if (projects.isEmpty()) {
-                sessionApi.fetchSessionStatus(conn).onSuccess { aggregated.putAll(it) }
-            } else {
-                for (project in projects) {
-                    sessionApi.fetchSessionStatus(conn, directory = project.worktree)
-                        .onSuccess { aggregated.putAll(it) }
-                }
-            }
-            val statusMap = aggregated.mapValues { (_, info) ->
-                when (info.type) {
-                    "busy" -> SessionStatus.Busy
-                    "retry" -> SessionStatus.Retry(
-                        attempt = info.attempt ?: 0,
-                        message = info.message ?: "",
-                        next = info.next ?: 0L
-                    )
-                    else -> SessionStatus.Idle
-                }
-            }
-            eventDispatcher.syncAllSessionStatuses(statusMap)
-
-                // Mark idle sessions with SSE-freshness protection (status only, no message fix).
-                // syncAllSessionStatuses already prevents downgrade for sessions with
-                // incomplete assistant messages (hasIncompleteAssistant check).
-                for ((sessionId, status) in statusMap) {
-                    if (status is SessionStatus.Idle) {
-                        eventDispatcher.markSessionIdleProtected(sessionId)
-                    }
-                }
-                Log.i(TAG, "Synced statuses for ${statusMap.size} sessions from REST across ${projects.size} projects")
-        } catch (e: Exception) {
-            Log.w(TAG, "Failed to sync session statuses: ${e.message}")
-        }
+        // Phase 2: Sync real session statuses from server via the unified FSM pipeline.
+        val projects = fileApi.listProjects(conn)
+        sessionStateService.setServerId(server.id)
+        sessionStateService.syncFromRest(projects)
     }
 
     private fun updateServerConnected(serverId: String, connected: Boolean) {
         val state = connections[serverId] ?: return
         connections.replace(serverId, state.copy(isConnected = connected))
         if (connected) {
             _connectingServerIds.update { it - serverId }
             _connectedServerIds.update { it + serverId }
diff --git a/app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/sessions/SessionListViewModel.kt b/app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/sessions/SessionListViewModel.kt
index 0b38cd20..cd1838ea 100644
--- a/app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/sessions/SessionListViewModel.kt
+++ b/app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/sessions/SessionListViewModel.kt
@@ -8,17 +8,16 @@ import androidx.lifecycle.SavedStateHandle
 import dev.leonardo.ocremotev2.BuildConfig
 import androidx.lifecycle.ViewModel
 import androidx.lifecycle.viewModelScope
 import dagger.hilt.android.lifecycle.HiltViewModel
 import dev.leonardo.ocremotev2.data.dto.response.FileNodeDto
 import dev.leonardo.ocremotev2.data.dto.response.ServerPaths
 import dev.leonardo.ocremotev2.data.api.file.FileApi
 import dev.leonardo.ocremotev2.data.api.session.SessionApi
-import dev.leonardo.ocremotev2.data.api.RestSessionStatusInfo
 import dev.leonardo.ocremotev2.data.api.system.SystemApi
 import dev.leonardo.ocremotev2.data.api.terminal.TerminalApi
 import dev.leonardo.ocremotev2.domain.model.ServerConnection
 import dev.leonardo.ocremotev2.data.repository.EventDispatcher
 import dev.leonardo.ocremotev2.data.repository.SessionStateService
 import dev.leonardo.ocremotev2.domain.model.Project
 import dev.leonardo.ocremotev2.domain.model.Session
 import dev.leonardo.ocremotev2.domain.model.SessionStatus
@@ -277,18 +276,20 @@ class SessionListViewModel @Inject constructor(
                             totalSessions += sessions.size
                             if (BuildConfig.DEBUG) Log.d(TAG, "Loaded ${sessions.size} sessions for project ${project.displayName}")
                         } catch (e: Exception) {
                             Log.w(TAG, "Failed to load sessions for project ${project.displayName}: ${e.message}")
                         }
                     }
                     if (BuildConfig.DEBUG) Log.d(TAG, "Total: loaded $totalSessions sessions across ${projects.size} projects for server $serverId")
                 }
-                // Sync session statuses from server (one-time, no polling)
-                syncSessionStatusesFromServer()
+                // Sync session statuses from server via the unified FSM pipeline
+                // (aggregate across project worktrees + absence=idle + incomplete-protection).
+                sessionStateService.setServerId(serverId)
+                sessionStateService.syncFromRest(_projects.value)
             } catch (e: Exception) {
                 Log.e(TAG, "Failed to load sessions", e)
                 _error.value = e.message ?: "Failed to load sessions"
             }             finally {
                 if (_expandedPaths.value.isEmpty()) {
                     // Expand all directories by default on first load
                     val currentSessions = eventDispatcher.sessions.value
                     val base = _baseDirectory.value?.replace('\\', '/')?.trimEnd('/')
@@ -325,70 +326,28 @@ class SessionListViewModel @Inject constructor(
                         try {
                             val sessions = sessionApi.listSessions(conn, directory = project.worktree, search = _searchQuery.value)
                             eventDispatcher.setSessions(serverId, sessions)
                         } catch (e: Exception) {
                             Log.w(TAG, "Failed to refresh sessions for project ${project.displayName}: ${e.message}")
                         }
                     }
                 }
-                // Sync session statuses from server
-                syncSessionStatusesFromServer()
+                // Sync session statuses from server via the unified FSM pipeline.
+                sessionStateService.setServerId(serverId)
+                sessionStateService.syncFromRest(_projects.value)
             } catch (e: Exception) {
                 Log.e(TAG, "Failed to refresh sessions", e)
                 _error.value = e.message ?: "Failed to refresh sessions"
             } finally {
                 _isRefreshing.value = false
             }
         }
     }
 
-    private fun RestSessionStatusInfo.toStatus(): SessionStatus = when (type) {
-        "busy" -> SessionStatus.Busy
-        "retry" -> SessionStatus.Retry(attempt = attempt ?: 0, message = message ?: "", next = next ?: 0L)
-        else -> SessionStatus.Idle
-    }
-
-    private suspend fun syncSessionStatuses(directory: String? = null) {
-        try {
-            val result = sessionApi.fetchSessionStatus(conn, directory = directory)
-            result.onSuccess { statuses ->
-                if (BuildConfig.DEBUG) Log.d(TAG, "Polled ${statuses.size} session statuses for directory: ${directory ?: "all"}")
-                eventDispatcher.syncAllSessionStatuses(statuses.mapValues { it.value.toStatus() })
-            }
-        } catch (e: Exception) {
-            Log.w(TAG, "Failed to sync session statuses: ${e.message}")
-        }
-    }
-
-    /**
-     * Aggregate session statuses across ALL project worktrees. The server isolates
-     * status per-directory (per server instance); a single null-directory query only
-     * returns the default instance's sessions, leaving other worktrees' active
-     * sessions invisible (treated as idle). Must query each project worktree.
-     */
-    private suspend fun syncSessionStatusesFromServer() {
-        val projects = _projects.value
-        val aggregated = mutableMapOf<String, RestSessionStatusInfo>()
-        try {
-            if (projects.isEmpty()) {
-                sessionApi.fetchSessionStatus(conn).onSuccess { aggregated.putAll(it) }
-            } else {
-                for (project in projects) {
-                    sessionApi.fetchSessionStatus(conn, directory = project.worktree)
-                        .onSuccess { aggregated.putAll(it) }
-                }
-            }
-            if (BuildConfig.DEBUG) Log.d(TAG, "Aggregated ${aggregated.size} session statuses across ${projects.size} projects")
-            eventDispatcher.syncAllSessionStatuses(aggregated.mapValues { it.value.toStatus() })
-        } catch (e: Exception) {
-            Log.w(TAG, "Failed to sync session statuses: ${e.message}")
-        }
-    }
-
     fun deleteSession(sessionId: String) {
         viewModelScope.launch {
             try {
                 val result = deleteSessionUseCase(serverId, sessionId)
                 if (result.isSuccess) {
                     if (BuildConfig.DEBUG) Log.d(TAG, "Deleted session $sessionId")
                     loadSessions()
                 } else {
