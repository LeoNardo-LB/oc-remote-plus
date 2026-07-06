## Task 12 Diff: 1ed85723..c87eff47
### Commits
c87eff47 refactor(state): remove SessionStatusManager + SessionEventHandler status + rename FSM — single source achieved
### Stat
 .../ocremotev2/data/repository/EventDispatcher.kt  | 143 ++-----------
 .../data/repository/SessionRepositoryImpl.kt       |  16 --
 .../data/repository/SessionStateService.kt         |  14 +-
 .../data/repository/SessionStatusManager.kt        | 225 ---------------------
 .../data/repository/handler/SessionEventHandler.kt | 109 +---------
 .../{SessionStatusFSM.kt => SessionStateFSM.kt}    |   6 +-
 .../domain/repository/SessionRepository.kt         |  24 ---
 .../ocremotev2/ui/screens/chat/ChatViewModel.kt    |  17 +-
 .../ui/screens/chat/MessageDataDelegate.kt         |  12 +-
 .../ui/screens/chat/SessionActionsDelegate.kt      |  41 ++--
 .../data/repository/ChatRepositoryImplTest.kt      |   2 +-
 .../repository/EventDispatcherIntegrationTest.kt   |  27 +--
 .../data/repository/EventDispatcherTest.kt         |  34 +++-
 .../data/repository/SessionRepositoryImplTest.kt   |   2 +-
 .../repository/handler/SessionEventHandlerTest.kt  |  25 +--
 ...sionStatusFSMTest.kt => SessionStateFSMTest.kt} |  22 +-
 .../ui/screens/chat/ChatViewModelDeleteTest.kt     |   6 +-
 .../ui/screens/chat/ChatViewModelPermissionTest.kt |   6 +-
 .../ui/screens/chat/ChatViewModelQueuedTest.kt     |   7 +-
 .../ui/screens/chat/ChatViewModelSendTest.kt       |   4 -
 .../ui/screens/chat/ChatViewModelStreamingTest.kt  |   4 -
 21 files changed, 121 insertions(+), 625 deletions(-)
### Full Diff
diff --git a/app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/EventDispatcher.kt b/app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/EventDispatcher.kt
index 4c0ba9ac..039ac003 100644
--- a/app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/EventDispatcher.kt
+++ b/app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/EventDispatcher.kt
@@ -35,43 +35,25 @@ class EventDispatcher @Inject constructor(
     private val messageUpdatedHandler: MessageUpdatedHandler,
     private val messageRemovedHandler: MessageRemovedHandler,
     private val permissionHandler: PermissionEventHandler,
     private val questionHandler: QuestionEventHandler,
     private val miscHandler: MiscEventHandler,
     private val sessionNextHandler: SessionNextEventHandler,
-    private val sessionStatusManager: SessionStatusManager,
-    // Transition bridge: Hilt injects a non-null instance (Dagger ignores the Kotlin default);
-    // the `= null` default only spares legacy test call sites from needing this param during the
-    // Task 7→12 parallel-run window. Deleted (made non-null) in Task 12 alongside SessionStatusManager.
-    private val sessionStateService: SessionStateService? = null
+    private val sessionStateService: SessionStateService,
 ) {
     init {
-        // L5 cross-validation: SessionStatusManager checks if Idle sessions have
-        // incomplete assistant messages (time.completed == null), which indicates
-        // a missed SSE event. The callback is set here because SessionStatusManager
-        // cannot inject EventDispatcher (constructor simplified to avoid circular deps).
-        sessionStatusManager.incompleteAssistantChecker = { sessionId ->
+        // SessionStateService callbacks — wired here to break the circular dep
+        // (EventDispatcher ← SessionStateService via Provider, but callbacks
+        // need messageHandler which lives in EventDispatcher's scope).
+        sessionStateService.incompleteChecker = IncompleteAssistantChecker { sessionId ->
             hasIncompleteAssistant(sessionId)
         }
-        // Resolve a session's directory so SessionStatusManager's REST self-healing
-        // targets the correct server instance (status is isolated per-directory; a null
-        // directory makes non-default-worktree sessions invisible and breaks the FSM recovery).
-        sessionStatusManager.directoryResolver = { sessionId ->
+        sessionStateService.directoryResolver = DirectoryResolver { sessionId ->
             sessionHandler.sessions.value.find { it.id == sessionId }?.directory
         }
-
-        // SessionStateService callbacks (new single source of truth; mirrors sessionStatusManager
-        // setup above). Wired here for the same circular-dep-breaking reason. Nullable-safe because
-        // legacy test call sites default the constructor param to null during the Task 7→12 window.
-        sessionStateService?.incompleteChecker = IncompleteAssistantChecker { sessionId ->
-            hasIncompleteAssistant(sessionId)
-        }
-        sessionStateService?.directoryResolver = DirectoryResolver { sessionId ->
-            sessionHandler.sessions.value.find { it.id == sessionId }?.directory
-        }
-        sessionStateService?.messageForceCompleter = MessageForceCompleter { sessionId ->
+        sessionStateService.messageForceCompleter = MessageForceCompleter { sessionId ->
             messageHandler.markSessionIdle(sessionId)
         }
     }
 
     // ============ Event Handler Registry (Open/Closed Principle) ============
     // Maps each SseEvent subclass to its single responsible handler.
@@ -142,13 +124,14 @@ class EventDispatcher @Inject constructor(
     }
 
     // ============ Public State (read-only) ============
 
     val serverSessions: StateFlow<Map<String, Set<String>>> get() = sessionHandler.serverSessions
     val sessions: StateFlow<List<Session>> get() = sessionHandler.sessions
-    val sessionStatuses: StateFlow<Map<String, SessionStatus>> get() = sessionHandler.sessionStatuses
+    /** Facade over [SessionStateService.statusFlow] — the single source of truth for session status. */
+    val sessionStatuses: StateFlow<Map<String, SessionStatus>> get() = sessionStateService.statusFlow
     val messages: StateFlow<Map<String, List<Message>>> get() = messageHandler.messages
     val parts: StateFlow<Map<String, List<Part>>> get() = messageHandler.parts
     val sessionDiffs: StateFlow<Map<String, List<FileDiff>>> get() = sessionHandler.sessionDiffs
     val permissions: StateFlow<Map<String, List<SseEvent.PermissionAsked>>> get() = permissionHandler.permissions
     val questions: StateFlow<Map<String, List<SseEvent.QuestionAsked>>> get() = questionHandler.questions
     val todos: StateFlow<Map<String, List<SseEvent.TodoUpdated.Todo>>> get() = miscHandler.todos
@@ -181,23 +164,23 @@ class EventDispatcher @Inject constructor(
         val handler = registry[event::class]
         if (handler != null) {
             handler.handle(event, serverId)
         } else if (BuildConfig.DEBUG) {
             Log.w(TAG, "No handler registered for ${event::class.simpleName}")
         }
-        forwardToStatusManager(event)
         forwardToSessionStateService(event)
 
         // Cross-handler: SessionDeleted cascades cleanup to other handlers
         if (event is SseEvent.SessionDeleted) {
             val sessionId = event.info.id
             messageHandler.clearForSession(sessionId)
             permissionHandler.clearForSession(sessionId)
             questionHandler.clearForSession(sessionId)
             miscHandler.clearForSession(sessionId)
             sessionNextHandler.clearForSession(sessionId)
+            sessionStateService.clearSession(sessionId)
         }
 
         // Cross-handler: CommandExecuted — only mark messages as completed.
         // Don't force session to Idle: the server sends session.status event
         // if the session actually becomes idle. Forcing Idle here causes
         // flickering when the agent continues to the next tool call.
@@ -219,37 +202,21 @@ class EventDispatcher @Inject constructor(
         return messageHandler.messages.value[sessionId]
             .orEmpty()
             .filterIsInstance<Message.Assistant>()
             .any { it.time.completed == null }
     }
 
-    // ============ FSM Forwarding (P1: parallel run) ============
-
-    /**
-     * Forward SSE event to [SessionStatusManager] for parallel FSM processing.
-     * P1: Manager logs transitions only, no UI impact.
-     */
-    private fun forwardToStatusManager(event: SseEvent) {
-        val fsmSessionId = extractSessionId(event)
-        if (fsmSessionId != null) {
-            sessionStatusManager.onSseEvent(event, fsmSessionId)
-        }
-    }
+    // ============ FSM Forwarding ============
 
     /**
-     * Forward SSE event to [SessionStateService] for parallel FSM processing (dual-write).
-     * Transition bridge: both [SessionStatusManager] and [SessionStateService] receive the same
-     * status-relevant events until Task 12 deletes the old manager. Reuses [extractSessionId]
-     * — the exact same sessionId extraction + filtering as [forwardToStatusManager] — so the two
-     * FSMs never diverge.
+     * Forward SSE event to [SessionStateService] (the single source of truth) for FSM processing.
      */
     private fun forwardToSessionStateService(event: SseEvent) {
-        val service = sessionStateService ?: return
         val fsmSessionId = extractSessionId(event)
         if (fsmSessionId != null) {
-            service.onSseEvent(event, fsmSessionId)
+            sessionStateService.onSseEvent(event, fsmSessionId)
         }
     }
 
     /**
      * Extract sessionId from any [SseEvent] subclass.
      * Returns null for events that have no associated session.
@@ -306,15 +273,12 @@ class EventDispatcher @Inject constructor(
 
     // ============ Delegated Operations ============
 
     fun setSessions(serverId: String, sessions: List<Session>) =
         sessionHandler.setSessions(serverId, sessions)
 
-    fun updateSessionStatus(sessionId: String, status: SessionStatus) =
-        sessionHandler.updateSessionStatus(sessionId, status)
-
     fun clearRevert(sessionId: String) {
         // Prune reverted messages from cache BEFORE clearing the filter.
         // Otherwise the filter drops, reverted messages briefly reappear,
         // then the server's message.removed SSE catches up — visible flash.
         val revert = sessionHandler.sessions.value
             .find { it.id == sessionId }?.revert
@@ -333,71 +297,12 @@ class EventDispatcher @Inject constructor(
     fun mergeMessages(sessionId: String, messages: List<MessageWithParts>) =
         messageHandler.mergeMessages(sessionId, messages)
 
     fun replaceMessages(sessionId: String, messages: List<MessageWithParts>) =
         messageHandler.replaceMessages(sessionId, messages)
 
-    /**
-     * Batch-update session statuses from REST data.
-     *
-     * Trusts REST as the authoritative source: if REST says a session is idle,
-     * it IS idle. The server's in-memory status (`AgentCoordinator`) is the
-     * ground truth, and REST queries it directly.
-     *
-     * When REST confirms idle for a session that was locally Busy, also
-     * force-complete any incomplete assistant messages — this handles the
-     * case where SSE completion events were lost (network glitch, reordering).
-     */
-    fun syncAllSessionStatuses(statuses: Map<String, SessionStatus>) {
-        // Fix incomplete messages for sessions confirmed idle by REST
-        for ((sessionId, status) in statuses) {
-            if (status is SessionStatus.Idle && hasIncompleteAssistant(sessionId)) {
-                if (BuildConfig.DEBUG) {
-                    Log.d(TAG, "REST confirmed idle for $sessionId with incomplete messages — fixing")
-                }
-                messageHandler.markSessionIdle(sessionId)
-            }
-        }
-
-        // Also handle sessions absent from REST response.
-        // Server deletes idle sessions from its status map, so absence means idle.
-        // Only force-fix if there are no incomplete messages (otherwise SSE may still be streaming).
-        val currentStatuses = sessionHandler.sessionStatuses.value
-        val mergedStatuses = statuses.toMutableMap()
-        for ((sessionId, currentStatus) in currentStatuses) {
-            if (currentStatus !is SessionStatus.Idle && sessionId !in statuses) {
-                if (hasIncompleteAssistant(sessionId)) {
-                    // Incomplete messages — SSE may still be active. Keep current status.
-                    if (BuildConfig.DEBUG) {
-                        Log.d(TAG, "Protecting absent session $sessionId (has incomplete messages, was ${currentStatus::class.simpleName})")
-                    }
-                    mergedStatuses[sessionId] = currentStatus
-                } else {
-                    // No incomplete messages and absent from REST — confirmed idle.
-                    // Must explicitly set Idle: updateAllSessionStatuses only updates keys
-                    // present in its input map, so without this the stale Busy persists.
-                    if (BuildConfig.DEBUG) {
-                        Log.d(TAG, "Session $sessionId absent from REST, no incomplete messages — marking Idle")
-                    }
-                    mergedStatuses[sessionId] = SessionStatus.Idle
-                }
-            }
-        }
-
-        sessionHandler.updateAllSessionStatuses(mergedStatuses)
-
-        // Sync to SessionStatusManager FSM so UI consumers reading statusFlow (e.g.
-        // ChatScreen's busy progress bar) are corrected in lockstep with this REST
-        // authoritative update — not only the SessionEventHandler layer. Without this,
-        // a REST-confirmed Idle would fix the session list but leave the in-chat
-        // progress bar stuck on Busy (read from the FSM).
-        for ((sessionId, status) in mergedStatuses) {
-            sessionStatusManager.onRestValidation(sessionId, status)
-        }
-    }
-
     fun removePermission(permissionId: String) =
         permissionHandler.removePermission(permissionId)
 
     fun setPermissions(sessionId: String, permissions: List<SseEvent.PermissionAsked>) =
         permissionHandler.setPermissions(sessionId, permissions)
 
@@ -429,39 +334,19 @@ class EventDispatcher @Inject constructor(
         sessionHandler.clearAll()
         messageHandler.clearAll()
         permissionHandler.clearAll()
         questionHandler.clearAll()
         miscHandler.clearAll()
         sessionNextHandler.clearAll()
+        sessionStateService.clearAll()
     }
 
     fun clearForServer(serverId: String) {
         val sessionIds = sessionHandler.serverSessions.value[serverId] ?: emptySet()
         sessionHandler.clearForServer(serverId)
         messageHandler.clearForServer(sessionIds)
         permissionHandler.clearForServer(sessionIds)
         questionHandler.clearForServer(sessionIds)
         miscHandler.clearForServer(sessionIds)
         sessionNextHandler.clearForServer(sessionIds)
     }
-
-    // ============ State Correction ============
-
-    /**
-     * Force-mark all streaming messages in a session as completed AND set Idle.
-     * Used only for explicit terminal actions: abort, or entering a session
-     * where the server confirms idle but local data has stale incomplete messages.
-     */
-    fun markSessionIdle(sessionId: String) {
-        messageHandler.markSessionIdle(sessionId)
-        sessionHandler.updateSessionStatus(sessionId, SessionStatus.Idle)
-    }
-
-    /**
-     * Update session status to Idle with SSE-freshness protection.
-     * Does NOT modify messages.
-     * Used by periodic REST polling when server confirms a session is idle.
-     */
-    fun markSessionIdleProtected(sessionId: String) {
-        sessionHandler.updateSessionStatusProtected(sessionId, SessionStatus.Idle)
-    }
 }
diff --git a/app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/SessionRepositoryImpl.kt b/app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/SessionRepositoryImpl.kt
index f093701b..46a92e20 100644
--- a/app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/SessionRepositoryImpl.kt
+++ b/app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/SessionRepositoryImpl.kt
@@ -222,28 +222,12 @@ class SessionRepositoryImpl @Inject constructor(
     // ============ Write Operations (State Updates) ============
 
     override fun setSessions(serverId: String, sessions: List<Session>) {
         eventDispatcher.setSessions(serverId, sessions)
     }
 
-    override fun updateSessionStatus(sessionId: String, status: SessionStatus) {
-        eventDispatcher.updateSessionStatus(sessionId, status)
-    }
-
-    override fun syncAllSessionStatuses(statuses: Map<String, SessionStatus>) {
-        eventDispatcher.syncAllSessionStatuses(statuses)
-    }
-
-    override fun markSessionIdleProtected(sessionId: String) {
-        eventDispatcher.markSessionIdleProtected(sessionId)
-    }
-
-    override fun markSessionIdle(sessionId: String) {
-        eventDispatcher.markSessionIdle(sessionId)
-    }
-
     // ============ Session Status Sync ============
 
     override suspend fun fetchSessionStatuses(serverId: String, directory: String?): Result<Map<String, SessionStatus>> = runCatching {
         val conn = resolveConnection(serverId)
         val rawStatuses = sessionApi.fetchSessionStatus(conn, directory = directory).getOrThrow()
         rawStatuses.mapValues { (_, info) ->
diff --git a/app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStateService.kt b/app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStateService.kt
index e71d732d..798edf7d 100644
--- a/app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStateService.kt
+++ b/app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStateService.kt
@@ -85,12 +85,20 @@ class SessionStateService @Inject constructor(
 
     val historyFlow: StateFlow<Map<String, List<TransitionRecord>>> = _histories
         .stateIn(appScope, SharingStarted.Eagerly, emptyMap())
 
     fun setServerId(serverId: String) { currentServerId = serverId }
 
+    /**
+     * Public wrapper for [triggerRestValidation] — lets external callers (e.g.
+     * [SessionActionsDelegate] single-session entry/resume) request an authoritative
+     * REST status check for one session. The FSM's forceComplete mechanism handles
+     * incomplete-message fixing automatically when REST confirms Idle.
+     */
+    fun requestValidation(sessionId: String) = triggerRestValidation(sessionId)
+
     // ============ Event entry points ============
     fun onClientSendParts(sessionId: String) = applyTransition(sessionId, FsmEvent.ClientSendParts)
     fun onClientAbort(sessionId: String) = applyTransition(sessionId, FsmEvent.ClientAbort)
     fun onRestValidation(sessionId: String, status: SessionStatus) =
         applyTransition(sessionId, FsmEvent.RestValidation(status))
 
@@ -121,22 +129,22 @@ class SessionStateService @Inject constructor(
         else -> null
     }
 
     // ============ Core pipeline ============
     fun applyTransition(sessionId: String, event: FsmEvent) {
         val current = _fsmStates.value[sessionId] ?: SessionFSMState.initial()
-        val result = SessionStatusFSM.transition(current, event)
+        val result = SessionStateFSM.transition(current, event)
         _fsmStates.update { it + (sessionId to result.newState) }
         recordHistory(sessionId, current, result, event)
         if (BuildConfig.DEBUG) logTransition(sessionId, current, result, event)
         // Side effects
         if (result.forceComplete) messageForceCompleter.markIdle(sessionId)
         if (result.isSuspicious) triggerRestValidation(sessionId)
     }
 
-    private fun recordHistory(sessionId: String, from: SessionFSMState, result: SessionStatusFSM.TransitionResult, event: FsmEvent) {
+    private fun recordHistory(sessionId: String, from: SessionFSMState, result: SessionStateFSM.TransitionResult, event: FsmEvent) {
         val record = TransitionRecord(
             sessionId = sessionId,
             timestamp = System.currentTimeMillis(),
             event = event::class.simpleName ?: "Unknown",
             fromCore = from.core::class.simpleName ?: "?",
             toCore = result.newState.core::class.simpleName ?: "?",
@@ -149,13 +157,13 @@ class SessionStateService @Inject constructor(
             val list = (h[sessionId] ?: emptyList()) + record
             val trimmed = if (list.size > HISTORY_MAX) list.takeLast(HISTORY_MAX) else list
             h + (sessionId to trimmed)
         }
     }
 
-    private fun logTransition(sessionId: String, from: SessionFSMState, result: SessionStatusFSM.TransitionResult, event: FsmEvent) {
+    private fun logTransition(sessionId: String, from: SessionFSMState, result: SessionStateFSM.TransitionResult, event: FsmEvent) {
         val actFrom = from.activity?.let { "/${it::class.simpleName}" } ?: ""
         val actTo = result.newState.activity?.let { "/${it::class.simpleName}" } ?: ""
         val flags = buildString {
             if (result.isSuspicious) append(" [SUSPICIOUS]")
             if (result.forceComplete) append(" [force-complete]")
         }
diff --git a/app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStatusManager.kt b/app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStatusManager.kt
deleted file mode 100644
index ce1c49e5..00000000
--- a/app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStatusManager.kt
+++ /dev/null
@@ -1,225 +0,0 @@
-﻿package dev.leonardo.ocremotev2.data.repository
-
-import android.util.Log
-import dev.leonardo.ocremotev2.BuildConfig
-import dev.leonardo.ocremotev2.di.ApplicationScope
-import dev.leonardo.ocremotev2.domain.model.FsmEvent
-import dev.leonardo.ocremotev2.domain.model.SessionActivity
-import dev.leonardo.ocremotev2.domain.model.SessionFSMState
-import dev.leonardo.ocremotev2.domain.model.SessionNextEvent
-import dev.leonardo.ocremotev2.domain.model.SessionStatus
-import dev.leonardo.ocremotev2.domain.model.SessionStatusFSM
-import dev.leonardo.ocremotev2.domain.model.SseEvent
-import dev.leonardo.ocremotev2.domain.repository.SessionRepository
-import kotlinx.coroutines.CoroutineScope
-import kotlinx.coroutines.Job
-import kotlinx.coroutines.delay
-import kotlinx.coroutines.flow.MutableStateFlow
-import kotlinx.coroutines.flow.SharingStarted
-import kotlinx.coroutines.flow.StateFlow
-import kotlinx.coroutines.flow.map
-import kotlinx.coroutines.flow.stateIn
-import kotlinx.coroutines.flow.update
-import kotlinx.coroutines.isActive
-import kotlinx.coroutines.launch
-import javax.inject.Inject
-import javax.inject.Provider
-import javax.inject.Singleton
-
-private const val TAG = "SessionStatusManager"
-private const val STALENESS_CHECK_INTERVAL_MS = 5_000L
-private const val STALENESS_THRESHOLD_MS = 15_000L
-
-/**
- * SessionStatusManager — the single source of truth for session status.
- *
- * Holds a [SessionFSMState] per session, driven by SSE events via [SessionStatusFSM].
- * Runs L2 staleness guard (periodic check for stale Busy sessions).
- *
- * Phase 1: Parallel run — does NOT replace existing SessionEventHandler._sessionStatuses.
- * Phase 2+ will switch UI consumers to read from [statusFlow].
- *
- * @param appScope For L2 guard coroutine lifecycle
- */
-@Singleton
-class SessionStatusManager @Inject constructor(
-    @param:ApplicationScope private val appScope: CoroutineScope,
-    private val sessionRepositoryProvider: Provider<SessionRepository>
-) {
-    private val _fsmStates = MutableStateFlow<Map<String, SessionFSMState>>(emptyMap())
-
-    /** Layer 1: Core status per session — UI reads this for "is processing" */
-    val statusFlow: StateFlow<Map<String, SessionStatus>> = _fsmStates
-        .map { states -> states.mapValues { it.value.core } }
-        .stateIn(appScope, SharingStarted.Eagerly, emptyMap())
-
-    /** Layer 2: Activity detail per session — UI reads this for specific feedback */
-    val activityFlow: StateFlow<Map<String, SessionActivity?>> = _fsmStates
-        .map { states -> states.mapValues { it.value.activity } }
-        .stateIn(appScope, SharingStarted.Eagerly, emptyMap())
-
-    private var stalenessJob: Job? = null
-
-    /**
-     * L5 cross-validation checker — set by EventDispatcher.
-     * Returns true if the session has assistant messages with time.completed == null
-     * (indicates a missed SSE completion event).
-     */
-    var incompleteAssistantChecker: ((String) -> Boolean)? = null
-
-    /**
-     * Resolves a session's directory — set by EventDispatcher.
-     * REST validation must target the session's own directory: the server isolates
-     * session status per-directory, so a null/wrong directory returns only the default
-     * instance's statuses and the target session is invisible (treated as idle),
-     * defeating the L2/L3 self-healing guard.
-     */
-    var directoryResolver: ((String) -> String?)? = null
-
-    /** Current server ID — set by ChatViewModel so REST validation can query the correct server. */
-    @Volatile
-    private var currentServerId: String? = null
-
-    init {
-        startStalenessGuard()
-    }
-
-    // ============ Event Entry Points ============
-
-    fun setServerId(serverId: String) {
-        currentServerId = serverId
-    }
-
-    fun onSendParts(sessionId: String) {
-        applyTransition(sessionId, FsmEvent.ClientSendParts)
-    }
-
-    fun onAbort(sessionId: String) {
-        applyTransition(sessionId, FsmEvent.ClientAbort)
-    }
-
-    fun onSseEvent(event: SseEvent, sessionId: String) {
-        val fsmEvent = mapSseEventToFsm(event) ?: return
-        applyTransition(sessionId, fsmEvent)
-    }
-
-    fun onRestValidation(sessionId: String, status: SessionStatus) {
-        applyTransition(sessionId, FsmEvent.RestValidation(status))
-    }
-
-    fun clearSession(sessionId: String) {
-        _fsmStates.update { it - sessionId }
-    }
-
-    // ============ Internal ============
-
-    private fun applyTransition(sessionId: String, event: FsmEvent) {
-        _fsmStates.update { current ->
-            val state = current[sessionId] ?: SessionFSMState.initial()
-            val result = SessionStatusFSM.transition(state, event)
-            if (BuildConfig.DEBUG) {
-                Log.d(
-                    TAG,
-                    "[$sessionId] ${state.core::class.simpleName} --${event::class.simpleName}--> ${result.newState.core::class.simpleName}" +
-                        if (result.isSuspicious) " [SUSPICIOUS]" else ""
-                )
-            }
-            if (result.isSuspicious) {
-                triggerRestValidation(sessionId)
-            }
-            current + (sessionId to result.newState)
-        }
-    }
-
-    private fun mapSseEventToFsm(event: SseEvent): FsmEvent? {
-        return when (event) {
-            is SseEvent.SessionStatus -> FsmEvent.SseStatus(event.status)
-            is SseEvent.SessionIdle -> FsmEvent.SseIdle
-            is SseEvent.SessionError -> FsmEvent.SseError(event.error)
-            is SseEvent.SessionNext -> mapSessionNextEvent(event.event)
-            else -> null
-        }
-    }
-
-    private fun mapSessionNextEvent(event: SessionNextEvent): FsmEvent? {
-        return when (event) {
-            is SessionNextEvent.StepStarted -> FsmEvent.StepStarted
-            is SessionNextEvent.TextStarted -> FsmEvent.TextStarted
-            is SessionNextEvent.TextDelta -> FsmEvent.TextDelta(event.delta)
-            is SessionNextEvent.TextEnded -> FsmEvent.TextEnded
-            is SessionNextEvent.ToolInputStarted -> FsmEvent.ToolInputStarted(event.tool, event.callId)
-            // StepEnded has no finish field in SessionNextEvent — pass null.
-            // FSM treats non-"tool-calls" finish as "keep current Activity, wait for Core Idle".
-            is SessionNextEvent.StepEnded -> FsmEvent.StepEnded(finish = null)
-            is SessionNextEvent.CompactionStarted -> FsmEvent.CompactionStarted
-            is SessionNextEvent.CompactionEnded -> FsmEvent.CompactionEnded
-            else -> null
-        }
-    }
-
-    // ============ L2: Staleness Guard ============
-
-    private fun startStalenessGuard() {
-        stalenessJob?.cancel()
-        stalenessJob = appScope.launch {
-            while (isActive) {
-                delay(STALENESS_CHECK_INTERVAL_MS)
-                checkStaleness()
-            }
-        }
-    }
-
-    private fun checkStaleness() {
-        val now = System.currentTimeMillis()
-        _fsmStates.value.forEach { (sessionId, state) ->
-            // L2: Busy but stale — no SSE events for too long
-            if (state.core is SessionStatus.Busy) {
-                val staleFor = now - state.lastEventAt
-                if (staleFor > STALENESS_THRESHOLD_MS) {
-                    Log.w(TAG, "[$sessionId] L2 stale for ${staleFor}ms, triggering REST validation")
-                    triggerRestValidation(sessionId)
-                }
-            }
-            // L5: Idle but has incomplete assistant messages — missed SSE completion
-            if (state.core is SessionStatus.Idle) {
-                val hasIncomplete = incompleteAssistantChecker?.invoke(sessionId) ?: false
-                if (hasIncomplete) {
-                    Log.w(TAG, "[$sessionId] L5 inconsistency: Idle but has incomplete assistant, triggering REST validation")
-                    triggerRestValidation(sessionId)
-                }
-            }
-        }
-    }
-
-    // ============ L3: REST Validation ============
-
-    private fun triggerRestValidation(sessionId: String) {
-        val sid = currentServerId ?: return
-        val directory = directoryResolver?.invoke(sessionId)
-        appScope.launch {
-            try {
-                val result = sessionRepositoryProvider.get().fetchSessionStatuses(sid, directory = directory)
-                result.onSuccess { statuses ->
-                    val serverStatus = statuses[sessionId]
-                    if (serverStatus != null) {
-                        if (BuildConfig.DEBUG) {
-                            Log.d(TAG, "[$sessionId] L3 REST validation: server says ${serverStatus::class.simpleName}")
-                        }
-                        onRestValidation(sessionId, serverStatus)
-                    } else if (directory != null) {
-                        // Server deletes idle sessions from its status map, so absence means idle.
-                        // Only trust this when we queried the session's own directory; a null
-                        // directory (session not in local list) is ambiguous — skip to avoid
-                        // wrongly downgrading a genuinely-busy session under an unknown instance.
-                        if (BuildConfig.DEBUG) {
-                            Log.d(TAG, "[$sessionId] L3 REST validation: absent from its own directory -> idle")
-                        }
-                        onRestValidation(sessionId, SessionStatus.Idle)
-                    }
-                }
-            } catch (e: Exception) {
-                Log.w(TAG, "[$sessionId] L3 REST validation failed: ${e.message}")
-            }
-        }
-    }
-}
diff --git a/app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/handler/SessionEventHandler.kt b/app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/handler/SessionEventHandler.kt
index 0a511a7c..8117f6e1 100644
--- a/app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/handler/SessionEventHandler.kt
+++ b/app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/handler/SessionEventHandler.kt
@@ -8,36 +8,33 @@ import kotlinx.coroutines.flow.StateFlow
 import kotlinx.coroutines.flow.asStateFlow
 import kotlinx.coroutines.flow.update
 import javax.inject.Inject
 import javax.inject.Singleton
 
 /**
- * Handles session lifecycle events: created, updated, deleted, status, idle, diff, error, compacted.
- * Manages: sessions, sessionStatuses, serverSessions, sessionDiffs, vcsBranch, projectInfo
+ * Handles session lifecycle events: created, updated, deleted, diff, error, compacted.
+ * Manages: sessions, serverSessions, sessionDiffs, vcsBranch, projectInfo
+ *
+ * Session STATUS is no longer tracked here — [dev.leonardo.ocremotev2.data.repository.SessionStateService]
+ * is the single source of truth. SessionStatus/SessionIdle events are acknowledged here
+ * (so the dispatcher's registry routes them) but the actual FSM transition happens in
+ * [dev.leonardo.ocremotev2.data.repository.EventDispatcher.forwardToSessionStateService].
  */
 @Singleton
 class SessionEventHandler @Inject constructor() : SseEventHandler {
 
     companion object {
         private const val TAG = "SessionEventHandler"
-        /** SSE status is considered fresh within this window. REST won't overwrite. */
-        private const val SSE_FRESH_THRESHOLD_MS = 5000L
     }
 
     private val _serverSessions = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
     val serverSessions: StateFlow<Map<String, Set<String>>> = _serverSessions.asStateFlow()
 
     private val _sessions = MutableStateFlow<List<Session>>(emptyList())
     val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()
 
-    private val _sessionStatuses = MutableStateFlow<Map<String, SessionStatus>>(emptyMap())
-    val sessionStatuses: StateFlow<Map<String, SessionStatus>> = _sessionStatuses.asStateFlow()
-
-    /** Tracks when each session's status was last updated by SSE events. */
-    private val _sseTimestamps = MutableStateFlow<Map<String, Long>>(emptyMap())
-
     private val _sessionDiffs = MutableStateFlow<Map<String, List<FileDiff>>>(emptyMap())
     val sessionDiffs: StateFlow<Map<String, List<FileDiff>>> = _sessionDiffs.asStateFlow()
 
     private val _vcsBranch = MutableStateFlow<String?>(null)
     val vcsBranch: StateFlow<String?> = _vcsBranch.asStateFlow()
 
@@ -59,14 +56,16 @@ class SessionEventHandler @Inject constructor() : SseEventHandler {
             is SseEvent.ServerInstanceDisposed -> {
                 if (BuildConfig.DEBUG) Log.d(TAG, "Server instance disposed: ${event.directory}"); true
             }
             is SseEvent.SessionCreated -> { handleSessionCreated(event, serverId); true }
             is SseEvent.SessionUpdated -> { handleSessionUpdated(event, serverId); true }
             is SseEvent.SessionDeleted -> { handleSessionDeleted(event); true }
-            is SseEvent.SessionStatus -> { handleSessionStatus(event); true }
-            is SseEvent.SessionIdle -> { handleSessionIdle(event); true }
+            // Status/idle events are acknowledged here but processed by SessionStateService
+            // via EventDispatcher.forwardToSessionStateService — no local state to update.
+            is SseEvent.SessionStatus -> true
+            is SseEvent.SessionIdle -> true
             is SseEvent.SessionDiff -> { handleSessionDiff(event); true }
             is SseEvent.SessionError -> { handleSessionError(event); true }
             is SseEvent.SessionCompacted -> {
                 Log.i(TAG, "Session ${event.sessionId} compacted"); true
             }
             is SseEvent.VcsBranchUpdated -> { _vcsBranch.value = event.branch; true }
@@ -89,14 +88,12 @@ class SessionEventHandler @Inject constructor() : SseEventHandler {
             if (idx >= 0) {
                 current.toMutableList().apply { set(idx, event.info) }
             } else {
                 (current + event.info).sortedByDescending { s -> s.time.updated }
             }
         }
-        _sessionStatuses.update { it + (event.info.id to SessionStatus.Idle) }
-        _sseTimestamps.update { it + (event.info.id to System.currentTimeMillis()) }
     }
 
     private fun handleSessionUpdated(event: SseEvent.SessionUpdated, serverId: String) {
         Log.i(TAG, "SessionUpdated: id=${event.info.id} title=${event.info.title}")
         trackSession(serverId, event.info.id)
         _sessions.update { current ->
@@ -111,28 +108,15 @@ class SessionEventHandler @Inject constructor() : SseEventHandler {
         }
     }
 
     private fun handleSessionDeleted(event: SseEvent.SessionDeleted) {
         val sessionId = event.info.id
         _sessions.update { it.filter { s -> s.id != sessionId } }
-        _sessionStatuses.update { it - sessionId }
-        _sseTimestamps.update { it - sessionId }
         _sessionDiffs.update { it - sessionId }
     }
 
-    private fun handleSessionStatus(event: SseEvent.SessionStatus) {
-        _sessionStatuses.update { it + (event.sessionId to event.status) }
-        _sseTimestamps.update { it + (event.sessionId to System.currentTimeMillis()) }
-        if (BuildConfig.DEBUG) Log.d(TAG, "Session ${event.sessionId} status: ${event.status}")
-    }
-
-    private fun handleSessionIdle(event: SseEvent.SessionIdle) {
-        _sessionStatuses.update { it + (event.sessionId to SessionStatus.Idle) }
-        _sseTimestamps.update { it + (event.sessionId to System.currentTimeMillis()) }
-    }
-
     private fun handleSessionDiff(event: SseEvent.SessionDiff) {
         _sessionDiffs.update { it + (event.sessionId to event.diff) }
     }
 
     private fun handleSessionError(event: SseEvent.SessionError) {
         Log.e(TAG, "Session ${event.sessionId} error: ${event.error}")
@@ -157,90 +141,21 @@ class SessionEventHandler @Inject constructor() : SseEventHandler {
                 }
             }
             updated.sortedByDescending { it.time.updated }
         }
     }
 
-    fun updateSessionStatus(sessionId: String, status: SessionStatus) {
-        _sessionStatuses.update { it + (sessionId to status) }
-        if (BuildConfig.DEBUG) Log.d(TAG, "Manually updated session $sessionId status to $status")
-    }
-
-    /**
-     * Batch-update session statuses from REST data.
-     * REST is the authoritative source — always overwrites local state.
-     * SSE freshness protection is NOT applied here because REST directly
-     * queries the server's in-memory status, which is the ground truth.
-     *
-     * Sessions absent from REST response are NOT touched here — the caller
-     * (EventDispatcher.syncAllSessionStatuses) handles that logic.
-     */
-    fun updateAllSessionStatuses(statuses: Map<String, SessionStatus>) {
-        _sessionStatuses.update { current ->
-            val merged = current.toMutableMap()
-            for ((sessionId, newStatus) in statuses) {
-                merged[sessionId] = newStatus
-            }
-            merged.toMap()
-        }
-        if (BuildConfig.DEBUG) Log.d(TAG, "Batch updated ${statuses.size} session statuses")
-    }
-
-    /**
-     * Update a single session's status with SSE-freshness protection.
-     * Won't overwrite Busy/Retry with Idle if SSE updated it recently.
-     */
-    fun updateSessionStatusProtected(sessionId: String, newStatus: SessionStatus) {
-        val now = System.currentTimeMillis()
-        val existing = _sessionStatuses.value[sessionId]
-        val lastSseUpdate = _sseTimestamps.value[sessionId]
-        if (shouldOverwrite(existing, newStatus, lastSseUpdate, now)) {
-            _sessionStatuses.update { it + (sessionId to newStatus) }
-        }
-    }
-
-    /**
-     * Determine if REST data should overwrite existing status.
-     * Rules:
-     * - No existing status → always overwrite (cold start)
-     * - REST says Busy/Retry → always overwrite (upgrade)
-     * - REST says Idle but SSE recently said Busy/Retry → don't overwrite (protect)
-     * - REST says Idle and SSE data is stale (>5s) → overwrite (trust REST)
-     */
-    private fun shouldOverwrite(
-        existing: SessionStatus?,
-        newStatus: SessionStatus,
-        lastSseUpdate: Long?,
-        now: Long
-    ): Boolean {
-        if (existing == null) return true // Cold start, no data yet
-        if (newStatus !is SessionStatus.Idle) return true // REST says active, always trust
-        // REST says Idle. Check if SSE recently said active.
-        if (existing !is SessionStatus.Idle && lastSseUpdate != null) {
-            val age = now - lastSseUpdate
-            if (age < SSE_FRESH_THRESHOLD_MS) {
-                if (BuildConfig.DEBUG) {
-                    Log.d(TAG, "Protecting ${existing::class.simpleName} status (SSE age=${age}ms < ${SSE_FRESH_THRESHOLD_MS}ms)")
-                }
-                return false
-            }
-        }
-        return true
-    }
-
     fun clearForServer(serverId: String) {
         val sessionIds = _serverSessions.value[serverId] ?: emptySet()
         if (sessionIds.isEmpty()) {
             _serverSessions.update { it - serverId }
             return
         }
         if (BuildConfig.DEBUG) Log.d(TAG, "Clearing state for server $serverId (${sessionIds.size} sessions)")
         _serverSessions.update { it - serverId }
         _sessions.update { it.filter { s -> s.id !in sessionIds } }
-        _sessionStatuses.update { it - sessionIds }
-        _sseTimestamps.update { it - sessionIds }
         _sessionDiffs.update { it - sessionIds }
         _lastUserMessageTime.update { it - sessionIds }
     }
 
     /**
      * Clear the revert state for a session.
@@ -275,14 +190,12 @@ class SessionEventHandler @Inject constructor() : SseEventHandler {
         }
     }
 
     fun clearAll() {
         _serverSessions.value = emptyMap()
         _sessions.value = emptyList()
-        _sessionStatuses.value = emptyMap()
-        _sseTimestamps.value = emptyMap()
         _sessionDiffs.value = emptyMap()
         _lastUserMessageTime.value = emptyMap()
         _vcsBranch.value = null
         _projectInfo.value = null
     }
 }
diff --git a/app/src/main/kotlin/dev/leonardo/ocremotev2/domain/model/SessionStatusFSM.kt b/app/src/main/kotlin/dev/leonardo/ocremotev2/domain/model/SessionStateFSM.kt
similarity index 97%
rename from app/src/main/kotlin/dev/leonardo/ocremotev2/domain/model/SessionStatusFSM.kt
rename to app/src/main/kotlin/dev/leonardo/ocremotev2/domain/model/SessionStateFSM.kt
index 50f1d516..0dfebd1c 100644
--- a/app/src/main/kotlin/dev/leonardo/ocremotev2/domain/model/SessionStatusFSM.kt
+++ b/app/src/main/kotlin/dev/leonardo/ocremotev2/domain/model/SessionStateFSM.kt
@@ -1,22 +1,22 @@
-﻿package dev.leonardo.ocremotev2.domain.model
+package dev.leonardo.ocremotev2.domain.model
 
 /**
  * Pure-function Finite State Machine for session status.
  *
  * Two-layer architecture:
  * - Layer 1 (Core): Idle / Busy / Retry — mirrors server's SessionStatus
  * - Layer 2 (Activity): Waiting / Streaming / ToolCalling / Compacting — derived detail
  *
  * Statelessness: This object holds no mutable state. All state lives in
- * [SessionStatusManager]'s Map<sessionId, SessionFSMState>.
+ * [dev.leonardo.ocremotev2.data.repository.SessionStateService]'s Map<sessionId, SessionFSMState>.
  *
  * Testability: transition() is a pure function — given (state, event), always
  * produces the same TransitionResult. No side effects.
  */
-object SessionStatusFSM {
+object SessionStateFSM {
 
     data class TransitionResult(
         val newState: SessionFSMState,
         /** True if the transition indicates a likely lost SSE event (e.g., Activity event in Idle state) */
         val isSuspicious: Boolean,
         /** True if incomplete message markers should be force-completed (e.g., abort, REST confirms Idle) */
diff --git a/app/src/main/kotlin/dev/leonardo/ocremotev2/domain/repository/SessionRepository.kt b/app/src/main/kotlin/dev/leonardo/ocremotev2/domain/repository/SessionRepository.kt
index 5534dc54..7f626650 100644
--- a/app/src/main/kotlin/dev/leonardo/ocremotev2/domain/repository/SessionRepository.kt
+++ b/app/src/main/kotlin/dev/leonardo/ocremotev2/domain/repository/SessionRepository.kt
@@ -150,36 +150,12 @@ interface SessionRepository {
     /**
      * Inject REST-loaded sessions into the state holder.
      * Used when REST fallback loads session info before SSE delivers it.
      */
     fun setSessions(serverId: String, sessions: List<Session>)
 
-    /**
-     * Update a single session's status optimistically.
-     */
-    fun updateSessionStatus(sessionId: String, status: SessionStatus)
-
-    /**
-     * Batch-update all session statuses from a REST response.
-     */
-    fun syncAllSessionStatuses(statuses: Map<String, SessionStatus>)
-
-    /**
-     * Mark a session as idle with SSE-freshness protection.
-     * Won't overwrite a recent SSE Busy/Retry status (within 5s).
-     * Does NOT modify messages — safe for periodic REST polling.
-     */
-    fun markSessionIdleProtected(sessionId: String)
-
-    /**
-     * Force-mark a session as idle: sets status AND completes all incomplete messages.
-     * Use ONLY for explicit terminal actions (abort, server-restart fix).
-     * Do NOT call from periodic polling — breaks premature-idle protection.
-     */
-    fun markSessionIdle(sessionId: String)
-
     // ============ Session Status Sync ============
 
     /**
      * Fetch all session statuses from the server via REST.
      * Used as a fallback when SSE events may have been missed.
      * @return Map of sessionId → [SessionStatus].
diff --git a/app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModel.kt b/app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModel.kt
index bcfa916d..e03484e4 100644
--- a/app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModel.kt
+++ b/app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModel.kt
@@ -14,13 +14,12 @@ import dev.leonardo.ocremotev2.domain.model.AgentInfo
 import dev.leonardo.ocremotev2.domain.model.CommandInfo
 import dev.leonardo.ocremotev2.domain.model.ModelSelection
 import dev.leonardo.ocremotev2.domain.model.PromptPart
 import dev.leonardo.ocremotev2.domain.model.ProviderCatalog
 import dev.leonardo.ocremotev2.data.repository.ServerTerminalRegistry
 import dev.leonardo.ocremotev2.data.repository.SessionStateService
-import dev.leonardo.ocremotev2.data.repository.SessionStatusManager
 import dev.leonardo.ocremotev2.ui.screens.chat.tools.ToolCardResolver
 import dev.leonardo.ocremotev2.ui.screens.chat.util.ContextBreakdown
 import dev.leonardo.ocremotev2.ui.screens.chat.util.ContextDetailState
 import dev.leonardo.ocremotev2.ui.screens.chat.util.MessageCount
 import dev.leonardo.ocremotev2.ui.screens.chat.util.ProviderModel
 import dev.leonardo.ocremotev2.ui.screens.chat.util.SessionTimestamps
@@ -223,13 +222,12 @@ class ChatViewModel @Inject constructor(
     private val chatRepository: ChatRepository,
     private val sessionRepository: SessionRepository,
     private val messagePaging: MessagePaginationUseCase,
     private val tokenStatsTracker: TokenStatsTracker,
     private val httpClient: io.ktor.client.HttpClient,
     private val sseClient: SseClient,
-    private val sessionStatusManager: SessionStatusManager,
     private val sessionStateService: SessionStateService,
     private val sessionFocusHolder: dev.leonardo.ocremotev2.service.SessionFocusHolder,
     private val appNotificationManager: dev.leonardo.ocremotev2.service.AppNotificationManager,
     private val toolSnapshotCache: dev.leonardo.ocremotev2.domain.repository.ToolSnapshotCache,
 ) : ViewModel() {
 
@@ -355,13 +353,13 @@ class ChatViewModel @Inject constructor(
      */
     fun onSessionUnfocused() {
         sessionFocusHolder.setActiveFocus(null, null)
     }
 
     init {
-        sessionStatusManager.setServerId(serverId)
+        sessionStateService.setServerId(serverId)
     }
 
     // ============ Model Config Delegate (Phase 3 Task 4 — A cluster) ============
     // Owns provider/agent/model/variant/command selection and the modelConfigState
     // resolution pipeline (with self-feedback side effects). Consumes sessionIdFlow
     // from sessionLifecycle; exposes selectedAgentValue/selectedVariantValue for
@@ -391,13 +389,13 @@ class ChatViewModel @Inject constructor(
     // ViewModel but never touch this delegate's private state directly.
     private val messageData: MessageDataDelegate = MessageDataDelegate(
         manageSessionUseCase = manageSessionUseCase,
         managePermissionUseCase = managePermissionUseCase,
         chatRepository = chatRepository,
         messagePaging = messagePaging,
-        sessionStatusManager = sessionStatusManager,
+        sessionStateService = sessionStateService,
         sessionRepository = sessionRepository,
         settingsRepository = settingsRepository,
         serverId = serverId,
         sessionIdFlow = sessionLifecycle.sessionIdFlow,
         sessionDirectoryProvider = { sessionLifecycle.sessionDirectory },
         scope = viewModelScope,
@@ -452,23 +450,23 @@ class ChatViewModel @Inject constructor(
         undoRedoUseCase = undoRedoUseCase,
         manageSessionUseCase = manageSessionUseCase,
         managePermissionUseCase = managePermissionUseCase,
         manageTerminalUseCase = manageTerminalUseCase,
         sessionRepository = sessionRepository,
         chatRepository = chatRepository,
+        sessionStateService = sessionStateService,
         serverId = serverId,
         scope = viewModelScope,
         sessionIdProvider = { sessionLifecycle.sessionId },
         sessionDirectoryProvider = { sessionLifecycle.sessionDirectory },
         modelConfigProvider = { modelConfigState.value },
         messageListProvider = { messageListState.value.messages },
         ensureSession = { sessionLifecycle.ensureSession() },
         loadSessionInfo = { sessionLifecycle.loadSession() },
         awaitSessionLoaded = { sessionLifecycle.sessionLoaded.await() },
         refreshMessages = { messageData.refreshMessages() },
-        fixIncompleteMessagesIfIdle = { messageData.fixIncompleteMessagesIfIdle(it) },
         loadPendingQuestions = { messageData.loadPendingQuestions() },
         loadPendingPermissions = { messageData.loadPendingPermissions() },
         restoreRevertedDraft = { draftDelegate.restoreRevertedDraft(it) },
     )
 
     // ============ Settings (exposed for ChatScreen) ============
@@ -958,13 +956,13 @@ class ChatViewModel @Inject constructor(
         scrollSignal.requestScrollToTop()
         val pendingId = "pending-${java.util.UUID.randomUUID()}"
         messageData.onSendStarted(pendingId)
         viewModelScope.launch {
             try {
                 val currentSessionId = sessionLifecycle.ensureSession()
-                sessionStatusManager.onSendParts(currentSessionId)
+                sessionStateService.onClientSendParts(currentSessionId)
                 // P5-5: read from modelConfigState (resolved effective value) instead of
                 // raw _selectedProviderId which may be null on new session's first send.
                 val modelCfg = modelConfigState.value
                 val model = if (modelCfg.selectedProviderId != null && modelCfg.selectedModelId != null) {
                     ModelSelection(
                         providerId = modelCfg.selectedProviderId,
@@ -1015,13 +1013,13 @@ class ChatViewModel @Inject constructor(
     /**
      * Abort the current session — coordinator.
      * Delegates REST abort + markIdle to [sessionActions], then handles
      * SSE job cancel/restart (B↔C↔G orchestration).
      */
     fun abortSession() {
-        sessionStatusManager.onAbort(sessionId)
+        sessionStateService.onClientAbort(sessionId)
         viewModelScope.launch {
             try {
                 messageData.cancelSseJob()
                 sessionActions.abortSession()
                 if (BuildConfig.DEBUG) Log.d(TAG, "Aborted session $sessionId")
                 // P5-2: restart sseJob to avoid _rawMessagesList freeze.
@@ -1075,19 +1073,18 @@ class ChatViewModel @Inject constructor(
      *  reconnects SSE, restores draft. */
     fun revertMessage(messageId: String, revertedText: String? = null, onResult: (Boolean) -> Unit) {
         viewModelScope.launch {
             try {
                 // Halt: if session is busy (AI generating), abort first before reverting.
                 // Same pattern as OpenCode WebUI: halt(sessionID).then(() => revert(input))
-                val currentStatus = sessionStatusManager.statusFlow.value[sessionId]
+                val currentStatus = sessionStateService.statusFlow.value[sessionId]
                 if (currentStatus is SessionStatus.Busy || currentStatus is SessionStatus.Retry) {
                     if (BuildConfig.DEBUG) Log.d(TAG, "Revert: halting busy session $sessionId")
-                    sessionStatusManager.onAbort(sessionId)
+                    sessionStateService.onClientAbort(sessionId)
                     messageData.cancelSseJob()
                     runCatching { sessionRepository.abort(serverId, sessionId, sessionLifecycle.sessionDirectory) }
-                    sessionRepository.markSessionIdle(sessionId)
                     // NOTE: startObservingMessages deferred to after setRevert below
                 }
 
                 undoRedoUseCase.revertSession(serverId, sessionId, messageId)
                 if (BuildConfig.DEBUG) Log.d(TAG, "Reverted session $sessionId to message $messageId")
 
diff --git a/app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/MessageDataDelegate.kt b/app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/MessageDataDelegate.kt
index 0f99b430..8416cd0c 100644
--- a/app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/MessageDataDelegate.kt
+++ b/app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/MessageDataDelegate.kt
@@ -1,11 +1,11 @@
 package dev.leonardo.ocremotev2.ui.screens.chat
 
 import android.util.Log
 import dev.leonardo.ocremotev2.BuildConfig
-import dev.leonardo.ocremotev2.data.repository.SessionStatusManager
+import dev.leonardo.ocremotev2.data.repository.SessionStateService
 import dev.leonardo.ocremotev2.domain.model.Message
 import dev.leonardo.ocremotev2.domain.model.Part
 import dev.leonardo.ocremotev2.domain.model.Session
 import dev.leonardo.ocremotev2.domain.model.SessionStatus
 import dev.leonardo.ocremotev2.domain.model.SseEvent
 import dev.leonardo.ocremotev2.domain.repository.ChatRepository
@@ -55,13 +55,13 @@ private const val TAG = "MessageDataDelegate"
 @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
 internal class MessageDataDelegate(
     private val manageSessionUseCase: ManageSessionUseCase,
     private val managePermissionUseCase: ManagePermissionUseCase,
     private val chatRepository: ChatRepository,
     private val messagePaging: MessagePaginationUseCase,
-    private val sessionStatusManager: SessionStatusManager,
+    private val sessionStateService: SessionStateService,
     private val sessionRepository: SessionRepository,
     private val settingsRepository: SettingsRepository,
     private val serverId: String,
     private val sessionIdFlow: StateFlow<String>,
     private val sessionDirectoryProvider: () -> String?,
     private val scope: CoroutineScope,
@@ -119,13 +119,13 @@ internal class MessageDataDelegate(
             chatRepository.getAllPartsMap(),
             _isLoading,
             _hasOlderMessages,
             _isLoadingOlder,
             _toolExpandedStates,
             _pendingMessageIds,
-            sessionStatusManager.statusFlow,
+            sessionStateService.statusFlow,
         ) { args ->
             @Suppress("UNCHECKED_CAST")
             val allSessions = args[0] as List<Session>
             @Suppress("UNCHECKED_CAST")
             val sessionMessages = args[1] as List<Message>
             @Suppress("UNCHECKED_CAST")
@@ -360,19 +360,23 @@ internal class MessageDataDelegate(
     /**
      * Fix messages with time.completed == null when the server confirms the session is idle.
      * This handles the server-restart scenario: after restart, all sessions are idle in-memory,
      * but the database preserves interrupted messages with finished_at = NULL.
      * We must NOT call this during periodic polling — only on explicit user actions
      * (entering session, aborting) to avoid breaking premature-idle protection.
+     *
+     * Routes through [SessionStateService.onRestValidation] — the FSM's forceComplete
+     * mechanism triggers [MessageEventHandler.markSessionIdle] via the callback wired
+     * in [EventDispatcher]'s init block.
      */
     fun fixIncompleteMessagesIfIdle(sid: String) {
         val messages = _rawMessagesList.value
         val hasIncomplete = messages.any { it is Message.Assistant && it.time.completed == null }
         if (hasIncomplete) {
             if (BuildConfig.DEBUG) Log.d(TAG, "Fixing incomplete messages for session $sid (server confirmed idle)")
-            sessionRepository.markSessionIdle(sid)
+            sessionStateService.onRestValidation(sid, SessionStatus.Idle)
         }
     }
 
     fun loadOlderMessages() {
         val sid = sessionIdFlow.value
         scope.launch {
diff --git a/app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/SessionActionsDelegate.kt b/app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/SessionActionsDelegate.kt
index 8e37f0eb..793c2fb7 100644
--- a/app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/SessionActionsDelegate.kt
+++ b/app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/SessionActionsDelegate.kt
@@ -1,16 +1,16 @@
 package dev.leonardo.ocremotev2.ui.screens.chat
 
 import android.util.Log
 import dev.leonardo.ocremotev2.BuildConfig
 import dev.leonardo.ocremotev2.R
+import dev.leonardo.ocremotev2.data.repository.SessionStateService
 import dev.leonardo.ocremotev2.domain.model.AutoApproveRule
 import dev.leonardo.ocremotev2.domain.model.ModelSelection
 import dev.leonardo.ocremotev2.domain.model.Part
 import dev.leonardo.ocremotev2.domain.model.Session
-import dev.leonardo.ocremotev2.domain.model.SessionStatus
 import dev.leonardo.ocremotev2.domain.model.SseEvent
 import dev.leonardo.ocremotev2.domain.repository.ChatRepository
 import dev.leonardo.ocremotev2.domain.repository.SessionRepository
 import dev.leonardo.ocremotev2.domain.usecase.ManagePermissionUseCase
 import dev.leonardo.ocremotev2.domain.usecase.ManageSessionUseCase
 import dev.leonardo.ocremotev2.domain.usecase.ManageTerminalUseCase
@@ -48,23 +48,23 @@ internal class SessionActionsDelegate(
     private val undoRedoUseCase: UndoRedoUseCase,
     private val manageSessionUseCase: ManageSessionUseCase,
     private val managePermissionUseCase: ManagePermissionUseCase,
     private val manageTerminalUseCase: ManageTerminalUseCase,
     private val sessionRepository: SessionRepository,
     private val chatRepository: ChatRepository,
+    private val sessionStateService: SessionStateService,
     private val serverId: String,
     private val scope: CoroutineScope,
     private val sessionIdProvider: () -> String,
     private val sessionDirectoryProvider: () -> String?,
     private val modelConfigProvider: () -> ModelConfigState,
     private val messageListProvider: () -> List<ChatMessage>,
     private val ensureSession: suspend () -> String,
     private val loadSessionInfo: suspend () -> Unit,
     private val awaitSessionLoaded: suspend () -> Unit,
     private val refreshMessages: suspend () -> Unit,
-    private val fixIncompleteMessagesIfIdle: (String) -> Unit,
     private val loadPendingQuestions: suspend () -> Unit,
     private val loadPendingPermissions: suspend () -> Unit,
     private val restoreRevertedDraft: (RevertedDraftPayload) -> Unit,
 ) {
     private val sessionId: String get() = sessionIdProvider()
 
@@ -94,56 +94,42 @@ internal class SessionActionsDelegate(
         if (elapsed >= REFRESH_COOLDOWN_MS) {
             refreshSession()
         }
     }
 
     /**
-     * Query the OpenCode server for actual session statuses and correct
+     * Query the OpenCode server for the actual session status and correct
      * any UI state drift caused by missed SSE events.
      *
      * Triggered on entering a session and resuming from background.
+     * Delegates to [SessionStateService.requestValidation] — the FSM's
+     * forceComplete mechanism handles incomplete-message fixing when REST
+     * confirms Idle.
      */
     fun syncSessionStatus() {
         scope.launch {
             if (sessionId.isNotBlank()) {
                 awaitSessionLoaded()
-            }
-            val result = sessionRepository.fetchSessionStatuses(
-                serverId, directory = sessionDirectoryProvider()
-            )
-            result.onSuccess { statusMap ->
-                sessionRepository.syncAllSessionStatuses(statusMap)
-                val currentStatus = statusMap[sessionId]
-                if (currentStatus is SessionStatus.Idle) {
-                    sessionRepository.markSessionIdleProtected(sessionId)
-                    fixIncompleteMessagesIfIdle(sessionId)
-                }
+                sessionStateService.requestValidation(sessionId)
             }
         }
     }
 
     /**
      * Combined refresh + sync — runs in a single coroutine to avoid
      * state conflicts between parallel REST responses.
+     *
+     * Status validation delegates to [SessionStateService.requestValidation];
+     * the FSM's forceComplete mechanism handles incomplete-message fixing.
      */
     private suspend fun refreshAndSync() {
         loadSessionInfo()
         refreshMessages()
         if (sessionId.isNotBlank()) {
             awaitSessionLoaded()
-        }
-        val result = sessionRepository.fetchSessionStatuses(
-            serverId, directory = sessionDirectoryProvider()
-        )
-        result.onSuccess { statusMap ->
-            sessionRepository.syncAllSessionStatuses(statusMap)
-            val currentStatus = statusMap[sessionId]
-            if (currentStatus is SessionStatus.Idle) {
-                sessionRepository.markSessionIdleProtected(sessionId)
-                fixIncompleteMessagesIfIdle(sessionId)
-            }
+            sessionStateService.requestValidation(sessionId)
         }
         loadPendingQuestions()
         loadPendingPermissions()
         lastRefreshTimeMs = System.currentTimeMillis()
     }
 
@@ -493,19 +479,20 @@ internal class SessionActionsDelegate(
                 onResult(false)
             }
         }
     }
 
     /**
-     * Abort REST call — cancels the session on the server and marks it idle.
+     * Abort REST call — cancels the session on the server and marks it idle
+     * via the FSM (ClientAbort → Idle + forceComplete messages).
      * SSE job cancel/restart is handled by [ChatViewModel.abortSession] coordinator.
      */
     suspend fun abortSession() {
         sessionRepository.abort(serverId, sessionId, sessionDirectoryProvider())
         if (BuildConfig.DEBUG) Log.d(TAG, "Aborted session $sessionId")
-        sessionRepository.markSessionIdle(sessionId)
+        sessionStateService.onClientAbort(sessionId)
     }
 
     // ============ Commands ============
 
     /** Execute a server-side command (e.g. /init, /review, MCP commands). */
     fun executeCommand(command: String, arguments: String = "", onResult: (Boolean) -> Unit) {
diff --git a/app/src/test/kotlin/dev/leonardo/ocremotev2/data/repository/ChatRepositoryImplTest.kt b/app/src/test/kotlin/dev/leonardo/ocremotev2/data/repository/ChatRepositoryImplTest.kt
index 322c1858..4934a8be 100644
--- a/app/src/test/kotlin/dev/leonardo/ocremotev2/data/repository/ChatRepositoryImplTest.kt
+++ b/app/src/test/kotlin/dev/leonardo/ocremotev2/data/repository/ChatRepositoryImplTest.kt
@@ -52,13 +52,13 @@ class ChatRepositoryImplTest {
             messageUpdatedHandler = MessageUpdatedHandler(messageHandler),
             messageRemovedHandler = MessageRemovedHandler(messageHandler),
             permissionHandler = permissionHandler,
             questionHandler = questionHandler,
             miscHandler = miscHandler,
             sessionNextHandler = SessionNextEventHandler(),
-            sessionStatusManager = mockk<SessionStatusManager>(relaxed = true)
+            sessionStateService = mockk<SessionStateService>(relaxed = true)
         )
         repo = ChatRepositoryImpl(messageApi, sessionApi, terminalApi, providerApi, eventDispatcher, serverRepo, permissionAutoApprover)
     }
 
     // ============ getMessagesFlow ============
 
diff --git a/app/src/test/kotlin/dev/leonardo/ocremotev2/data/repository/EventDispatcherIntegrationTest.kt b/app/src/test/kotlin/dev/leonardo/ocremotev2/data/repository/EventDispatcherIntegrationTest.kt
index 0683bae3..f474e484 100644
--- a/app/src/test/kotlin/dev/leonardo/ocremotev2/data/repository/EventDispatcherIntegrationTest.kt
+++ b/app/src/test/kotlin/dev/leonardo/ocremotev2/data/repository/EventDispatcherIntegrationTest.kt
@@ -2,13 +2,12 @@
 
 import dev.leonardo.ocremotev2.data.repository.handler.*
 import dev.leonardo.ocremotev2.domain.model.*
 import dev.leonardo.ocremotev2.domain.model.SseEvent
 import dev.leonardo.ocremotev2.domain.repository.SessionRepository
 import io.mockk.mockk
-import io.mockk.verify
 import kotlinx.coroutines.cancel
 import kotlinx.coroutines.test.TestScope
 import kotlinx.coroutines.test.UnconfinedTestDispatcher
 import kotlinx.coroutines.test.runCurrent
 import kotlinx.coroutines.test.runTest
 import org.junit.After
@@ -24,25 +23,23 @@ import javax.inject.Provider
  * All handlers are REAL (not mocked) to verify actual integration behavior.
  * These tests complement the unit-level EventDispatcherTest with deep chain tests.
  */
 class EventDispatcherIntegrationTest {
 
     private lateinit var dispatcher: EventDispatcher
-    private lateinit var sessionStatusManager: SessionStatusManager
     // SessionStateService's statusFlow uses stateIn(scope, SharingStarted.Eagerly, …); Eagerly
     // propagation needs an UnconfinedTestDispatcher + runCurrent() (see SessionStateServiceTest
     // fixture note for why runTest's StandardTestDispatcher breaks). Each test gets a fresh scope
     // so the staleness-guard coroutine from init doesn't leak across tests.
     private lateinit var stateServiceScope: TestScope
     private lateinit var sessionStateService: SessionStateService
 
     @Before
     fun setup() {
         stateServiceScope = TestScope(UnconfinedTestDispatcher())
         val messageStore = MessageEventHandler()
-        sessionStatusManager = mockk<SessionStatusManager>(relaxed = true)
         sessionStateService = SessionStateService(
             appScope = stateServiceScope,
             sessionRepoProvider = Provider { mockk<SessionRepository>(relaxed = true) },
         )
         dispatcher = EventDispatcher(
             sessionHandler = SessionEventHandler(),
@@ -51,14 +48,13 @@ class EventDispatcherIntegrationTest {
             messageUpdatedHandler = MessageUpdatedHandler(messageStore),
             messageRemovedHandler = MessageRemovedHandler(messageStore),
             permissionHandler = PermissionEventHandler(),
             questionHandler = QuestionEventHandler(),
             miscHandler = MiscEventHandler(),
             sessionNextHandler = SessionNextEventHandler(),
-            sessionStatusManager = sessionStatusManager,
-            sessionStateService = sessionStateService
+            sessionStateService = sessionStateService,
         )
     }
 
     @After
     fun tearDown() {
         stateServiceScope.cancel()
@@ -326,13 +322,14 @@ class EventDispatcherIntegrationTest {
     @Test
     fun `SessionDeleted cascade clears ALL handler state for session`() = runTest {
         val session = testSession("s1")
 
         // Set up state across all handlers for session s1
         dispatcher.processEvent(SseEvent.SessionCreated(session), "svr1")
-        dispatcher.updateSessionStatus("s1", SessionStatus.Busy)
+        dispatcher.processEvent(SseEvent.SessionStatus(sessionId = "s1", status = SessionStatus.Busy), "svr1")
+        stateServiceScope.runCurrent()
 
         dispatcher.processEvent(SseEvent.MessageUpdated(
             Message.User(id = "m1", sessionId = "s1", time = TimeInfo(1000L))
         ), "svr1")
 
         dispatcher.processEvent(SseEvent.PermissionAsked(
@@ -812,30 +809,12 @@ class EventDispatcherIntegrationTest {
 
         assertFalse(dispatcher.serverSessions.value.containsKey("svr1"))
         assertTrue(dispatcher.serverSessions.value.containsKey("svr2"))
         assertEquals(setOf("s3", "s4"), dispatcher.serverSessions.value["svr2"])
     }
 
-    // ============ syncAllSessionStatuses → SessionStatusManager FSM sync ============
-
-    @Test
-    fun `syncAllSessionStatuses propagates each status to SessionStatusManager FSM`() = runTest {
-        val statuses = mapOf(
-            "s1" to SessionStatus.Busy,
-            "s2" to SessionStatus.Idle
-        )
-
-        dispatcher.syncAllSessionStatuses(statuses)
-
-        // The FSM must be corrected in lockstep so the ChatScreen progress bar (which
-        // reads SessionStatusManager.statusFlow) reflects REST authoritative state,
-        // not only the SessionEventHandler layer.
-        verify(exactly = 1) { sessionStatusManager.onRestValidation("s1", SessionStatus.Busy) }
-        verify(exactly = 1) { sessionStatusManager.onRestValidation("s2", SessionStatus.Idle) }
-    }
-
     // ============ Scenario 11: SSE dual-write to SessionStateService ============
 
     @Test
     fun `SSE SessionStatus dual-writes to SessionStateService`() = runTest {
         dispatcher.processEvent(SseEvent.SessionStatus(sessionId = "s1", status = SessionStatus.Busy), "svr1")
         stateServiceScope.runCurrent()
diff --git a/app/src/test/kotlin/dev/leonardo/ocremotev2/data/repository/EventDispatcherTest.kt b/app/src/test/kotlin/dev/leonardo/ocremotev2/data/repository/EventDispatcherTest.kt
index 4b5960f9..4dda91c6 100644
--- a/app/src/test/kotlin/dev/leonardo/ocremotev2/data/repository/EventDispatcherTest.kt
+++ b/app/src/test/kotlin/dev/leonardo/ocremotev2/data/repository/EventDispatcherTest.kt
@@ -1,50 +1,69 @@
 ﻿package dev.leonardo.ocremotev2.data.repository
 
 import dev.leonardo.ocremotev2.data.repository.handler.*
 import dev.leonardo.ocremotev2.domain.model.*
 import dev.leonardo.ocremotev2.domain.model.SseEvent
+import dev.leonardo.ocremotev2.domain.repository.SessionRepository
 import io.mockk.mockk
+import kotlinx.coroutines.cancel
+import kotlinx.coroutines.test.TestScope
+import kotlinx.coroutines.test.UnconfinedTestDispatcher
+import kotlinx.coroutines.test.runCurrent
 import kotlinx.coroutines.test.runTest
+import org.junit.After
 import org.junit.Assert.*
 import org.junit.Before
 import org.junit.Test
+import javax.inject.Provider
 
 class EventDispatcherTest {
 
     private lateinit var dispatcher: EventDispatcher
     private lateinit var sessionHandler: SessionEventHandler
     private lateinit var messageHandler: MessageEventHandler
     private lateinit var permissionHandler: PermissionEventHandler
     private lateinit var questionHandler: QuestionEventHandler
     private lateinit var miscHandler: MiscEventHandler
     private lateinit var sessionNextHandler: SessionNextEventHandler
+    private lateinit var stateServiceScope: TestScope
+    private lateinit var sessionStateService: SessionStateService
 
     @Before
     fun setup() {
+        stateServiceScope = TestScope(UnconfinedTestDispatcher())
         sessionHandler = SessionEventHandler()
         messageHandler = MessageEventHandler()
         permissionHandler = PermissionEventHandler()
         questionHandler = QuestionEventHandler()
         miscHandler = MiscEventHandler()
         sessionNextHandler = SessionNextEventHandler()
+        sessionStateService = SessionStateService(
+            appScope = stateServiceScope,
+            sessionRepoProvider = Provider { mockk<SessionRepository>(relaxed = true) },
+        )
 
         dispatcher = EventDispatcher(
             sessionHandler = sessionHandler,
             messageHandler = messageHandler,
             messagePartHandler = MessagePartHandler(messageHandler),
             messageUpdatedHandler = MessageUpdatedHandler(messageHandler),
             messageRemovedHandler = MessageRemovedHandler(messageHandler),
             permissionHandler = permissionHandler,
             questionHandler = questionHandler,
             miscHandler = miscHandler,
             sessionNextHandler = sessionNextHandler,
-            sessionStatusManager = mockk<SessionStatusManager>(relaxed = true)
+            sessionStateService = sessionStateService,
         )
     }
 
+    @After
+    fun tearDown() {
+        stateServiceScope.cancel()
+    }
+
     private fun testSession(id: String) = Session(
         id = id, title = "Test", time = Session.Time(created = 1000L, updated = 2000L)
     )
 
     // ============ Event Dispatching ============
 
@@ -118,15 +137,18 @@ class EventDispatcherTest {
     // ============ Cross-handler: CommandExecuted resets session status ============
 
     @Test
     fun `CommandExecuted does NOT reset session status to Idle`() = runTest {
         val session = testSession("s1")
         dispatcher.processEvent(SseEvent.SessionCreated(session), "server1")
-        dispatcher.updateSessionStatus("s1", SessionStatus.Busy)
+        // Set status to Busy via SSE (the authoritative path)
+        dispatcher.processEvent(SseEvent.SessionStatus(sessionId = "s1", status = SessionStatus.Busy), "server1")
+        stateServiceScope.runCurrent()
 
         dispatcher.processEvent(SseEvent.CommandExecuted(name = "bash", sessionId = "s1"), "server1")
+        stateServiceScope.runCurrent()
 
         // P0-4 fix: CommandExecuted no longer forces Idle — session.status SSE event controls state
         assertEquals(SessionStatus.Busy, dispatcher.sessionStatuses.value["s1"])
     }
 
     // ============ Clear Operations ============
@@ -254,18 +276,12 @@ class EventDispatcherTest {
         val s2 = testSession("s2")
         dispatcher.setSessions("server1", listOf(s1, s2))
         assertEquals(2, dispatcher.sessions.value.size)
         assertTrue(dispatcher.serverSessions.value["server1"]!!.contains("s1"))
     }
 
-    @Test
-    fun `delegated updateSessionStatus works`() {
-        dispatcher.updateSessionStatus("s1", SessionStatus.Busy)
-        assertEquals(SessionStatus.Busy, dispatcher.sessionStatuses.value["s1"])
-    }
-
     // ============ Initial State ============
 
     @Test
     fun `initial state is empty`() = runTest {
         assertTrue(dispatcher.sessions.value.isEmpty())
         assertTrue(dispatcher.sessionStatuses.value.isEmpty())
@@ -294,13 +310,13 @@ class EventDispatcherTest {
         assertTrue(dispatcher.sessions.value.isEmpty())
     }
 
     @Test
     fun `SessionError does not change sessions`() = runTest {
         dispatcher.processEvent(SseEvent.SessionError("s1", "something failed"), "server1")
-        assertTrue(dispatcher.sessionStatuses.value.isEmpty())
+        assertTrue(dispatcher.sessions.value.isEmpty())
     }
 
     // ============ SessionNext Event Integration ============
 
     @Test
     fun `SessionNext event routed to SessionNextHandler`() = runTest {
diff --git a/app/src/test/kotlin/dev/leonardo/ocremotev2/data/repository/SessionRepositoryImplTest.kt b/app/src/test/kotlin/dev/leonardo/ocremotev2/data/repository/SessionRepositoryImplTest.kt
index e7a088c0..8633d263 100644
--- a/app/src/test/kotlin/dev/leonardo/ocremotev2/data/repository/SessionRepositoryImplTest.kt
+++ b/app/src/test/kotlin/dev/leonardo/ocremotev2/data/repository/SessionRepositoryImplTest.kt
@@ -40,13 +40,13 @@ class SessionRepositoryImplTest {
             messageUpdatedHandler = MessageUpdatedHandler(messageHandler),
             messageRemovedHandler = MessageRemovedHandler(messageHandler),
             permissionHandler = permissionHandler,
             questionHandler = questionHandler,
             miscHandler = miscHandler,
             sessionNextHandler = SessionNextEventHandler(),
-            sessionStatusManager = mockk<SessionStatusManager>(relaxed = true)
+            sessionStateService = mockk<SessionStateService>(relaxed = true)
         )
         repo = SessionRepositoryImpl(sessionApi, messageApi, eventDispatcher, serverRepo)
     }
 
     private fun testSession(id: String) = Session(
         id = id, title = "Test $id",
diff --git a/app/src/test/kotlin/dev/leonardo/ocremotev2/data/repository/handler/SessionEventHandlerTest.kt b/app/src/test/kotlin/dev/leonardo/ocremotev2/data/repository/handler/SessionEventHandlerTest.kt
index 00696062..bf4aa30d 100644
--- a/app/src/test/kotlin/dev/leonardo/ocremotev2/data/repository/handler/SessionEventHandlerTest.kt
+++ b/app/src/test/kotlin/dev/leonardo/ocremotev2/data/repository/handler/SessionEventHandlerTest.kt
@@ -28,13 +28,12 @@ class SessionEventHandlerTest {
         val event = SseEvent.SessionCreated(session)
 
         val handled = handler.handle(event, "server1")
 
         assertTrue(handled)
         assertEquals(listOf(session), handler.sessions.value)
-        assertEquals(SessionStatus.Idle, handler.sessionStatuses.value["s1"])
     }
 
     @Test
     fun `handles SessionUpdated - update existing`() = runTest {
         val session = testSession("s1")
         handler.handle(SseEvent.SessionCreated(session), "server1")
@@ -59,32 +58,30 @@ class SessionEventHandlerTest {
         handler.handle(SseEvent.SessionCreated(testSession("s2")), "server1")
 
         handler.handle(SseEvent.SessionDeleted(testSession("s1")), "server1")
 
         assertEquals(1, handler.sessions.value.size)
         assertEquals("s2", handler.sessions.value[0].id)
-        assertFalse(handler.sessionStatuses.value.containsKey("s1"))
     }
 
     @Test
-    fun `handles SessionStatus`() = runTest {
+    fun `handles SessionStatus - acknowledged, no local status state`() = runTest {
         handler.handle(SseEvent.SessionCreated(testSession("s1")), "server1")
 
-        handler.handle(SseEvent.SessionStatus("s1", SessionStatus.Busy), "server1")
-
-        assertEquals(SessionStatus.Busy, handler.sessionStatuses.value["s1"])
+        // SessionStatus is acknowledged (returns true) but no longer tracked locally —
+        // SessionStateService is the single source of truth for status.
+        val handled = handler.handle(SseEvent.SessionStatus("s1", SessionStatus.Busy), "server1")
+        assertTrue(handled)
     }
 
     @Test
-    fun `handles SessionIdle`() = runTest {
+    fun `handles SessionIdle - acknowledged, no local status state`() = runTest {
         handler.handle(SseEvent.SessionCreated(testSession("s1")), "server1")
-        handler.handle(SseEvent.SessionStatus("s1", SessionStatus.Busy), "server1")
 
-        handler.handle(SseEvent.SessionIdle("s1"), "server1")
-
-        assertEquals(SessionStatus.Idle, handler.sessionStatuses.value["s1"])
+        val handled = handler.handle(SseEvent.SessionIdle("s1"), "server1")
+        assertTrue(handled)
     }
 
     @Test
     fun `handles SessionDiff`() = runTest {
         val diffs = listOf(FileDiff(file = "test.kt", status = "modified"))
         handler.handle(SseEvent.SessionDiff("s1", diffs), "server1")
@@ -155,18 +152,12 @@ class SessionEventHandlerTest {
         handler.handle(SseEvent.SessionCreated(testSession("s2")), "server1")
 
         val serverSessionMap = handler.serverSessions.value
         assertEquals(setOf("s1", "s2"), serverSessionMap["server1"])
     }
 
-    @Test
-    fun `updateSessionStatus manually sets status`() = runTest {
-        handler.updateSessionStatus("s1", SessionStatus.Busy)
-        assertEquals(SessionStatus.Busy, handler.sessionStatuses.value["s1"])
-    }
-
     @Test
     fun `handles ServerHeartbeat`() = runTest {
         assertTrue(handler.handle(SseEvent.ServerHeartbeat, "server1"))
     }
 
     @Test
diff --git a/app/src/test/kotlin/dev/leonardo/ocremotev2/domain/model/SessionStatusFSMTest.kt b/app/src/test/kotlin/dev/leonardo/ocremotev2/domain/model/SessionStateFSMTest.kt
similarity index 72%
rename from app/src/test/kotlin/dev/leonardo/ocremotev2/domain/model/SessionStatusFSMTest.kt
rename to app/src/test/kotlin/dev/leonardo/ocremotev2/domain/model/SessionStateFSMTest.kt
index a7fd7513..badef027 100644
--- a/app/src/test/kotlin/dev/leonardo/ocremotev2/domain/model/SessionStatusFSMTest.kt
+++ b/app/src/test/kotlin/dev/leonardo/ocremotev2/domain/model/SessionStateFSMTest.kt
@@ -1,76 +1,76 @@
-﻿package dev.leonardo.ocremotev2.domain.model
+package dev.leonardo.ocremotev2.domain.model
 
 import org.junit.Assert.assertEquals
 import org.junit.Assert.assertFalse
 import org.junit.Assert.assertNull
 import org.junit.Assert.assertTrue
 import org.junit.Test
 
-class SessionStatusFSMTest {
+class SessionStateFSMTest {
 
     private val idle = SessionFSMState.initial()
     private val busyWaiting = idle.copy(core = SessionStatus.Busy, activity = SessionActivity.Waiting)
     private val busyStreaming = busyWaiting.copy(activity = SessionActivity.Streaming)
 
     @Test
     fun `Idle + ClientSendParts to Busy_Waiting`() {
-        val r = SessionStatusFSM.transition(idle, FsmEvent.ClientSendParts)
+        val r = SessionStateFSM.transition(idle, FsmEvent.ClientSendParts)
         assertEquals(SessionStatus.Busy, r.newState.core)
         assertEquals(SessionActivity.Waiting, r.newState.activity)
         assertFalse(r.forceComplete)
     }
 
     @Test
     fun `Busy_Streaming + TextEnded to Busy_Waiting`() {
-        val r = SessionStatusFSM.transition(busyStreaming, FsmEvent.TextEnded)
+        val r = SessionStateFSM.transition(busyStreaming, FsmEvent.TextEnded)
         assertEquals(SessionActivity.Waiting, r.newState.activity)
         assertEquals(SessionStatus.Busy, r.newState.core)
     }
 
     @Test
     fun `Busy_Streaming + SseIdle to Idle_null + forceComplete`() {
-        val r = SessionStatusFSM.transition(busyStreaming, FsmEvent.SseIdle)
+        val r = SessionStateFSM.transition(busyStreaming, FsmEvent.SseIdle)
         assertEquals(SessionStatus.Idle, r.newState.core)
         assertNull(r.newState.activity)
         assertTrue(r.forceComplete)
     }
 
     @Test
     fun `Idle + TextStarted to suspicious, unchanged`() {
-        val r = SessionStatusFSM.transition(idle, FsmEvent.TextStarted)
+        val r = SessionStateFSM.transition(idle, FsmEvent.TextStarted)
         assertEquals(idle.core, r.newState.core)
         assertTrue(r.isSuspicious)
     }
 
     @Test
     fun `Busy + RestValidation_Idle to Idle_null + forceComplete`() {
-        val r = SessionStatusFSM.transition(busyStreaming, FsmEvent.RestValidation(SessionStatus.Idle))
+        val r = SessionStateFSM.transition(busyStreaming, FsmEvent.RestValidation(SessionStatus.Idle))
         assertEquals(SessionStatus.Idle, r.newState.core)
         assertTrue(r.forceComplete)
     }
 
     @Test
     fun `CompactionStarted saves activity, CompactionEnded restores`() {
-        val compacting = SessionStatusFSM.transition(busyStreaming, FsmEvent.CompactionStarted).newState
+        val compacting = SessionStateFSM.transition(busyStreaming, FsmEvent.CompactionStarted).newState
         assertTrue(compacting.activity is SessionActivity.Compacting)
         assertEquals(SessionActivity.Streaming, (compacting.activity as SessionActivity.Compacting).savedActivity)
-        val restored = SessionStatusFSM.transition(compacting, FsmEvent.CompactionEnded).newState
+        val restored = SessionStateFSM.transition(compacting, FsmEvent.CompactionEnded).newState
         assertEquals(SessionActivity.Streaming, restored.activity)
     }
 
     @Test
     fun `invariant - Idle state never holds activity`() {
         val events = listOf(FsmEvent.SseIdle, FsmEvent.ClientAbort, FsmEvent.RestValidation(SessionStatus.Idle))
         for (e in events) {
-            val r = SessionStatusFSM.transition(busyStreaming, e)
+            val r = SessionStateFSM.transition(busyStreaming, e)
             assertNull("After $e, activity must be null", r.newState.activity)
             assertEquals(SessionStatus.Idle, r.newState.core)
         }
     }
 
     @Test
     fun `ClientAbort always forceComplete`() {
-        val r = SessionStatusFSM.transition(busyStreaming, FsmEvent.ClientAbort)
+        val r = SessionStateFSM.transition(busyStreaming, FsmEvent.ClientAbort)
         assertTrue(r.forceComplete)
     }
 }
diff --git a/app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModelDeleteTest.kt b/app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModelDeleteTest.kt
index 01ed92b8..8780057a 100644
--- a/app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModelDeleteTest.kt
+++ b/app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModelDeleteTest.kt
@@ -7,13 +7,12 @@ import dev.leonardo.ocremotev2.data.api.SseClient
 import dev.leonardo.ocremotev2.domain.model.AppSettings
 import dev.leonardo.ocremotev2.domain.model.ProvidersResponse
 import dev.leonardo.ocremotev2.domain.repository.ChatRepository
 import dev.leonardo.ocremotev2.domain.repository.DraftRepository
 import dev.leonardo.ocremotev2.data.repository.EventDispatcher
 import dev.leonardo.ocremotev2.data.repository.SessionStateService
-import dev.leonardo.ocremotev2.data.repository.SessionStatusManager
 import dev.leonardo.ocremotev2.service.SessionFocusHolder
 import dev.leonardo.ocremotev2.service.AppNotificationManager
 import dev.leonardo.ocremotev2.data.repository.handler.*
 import dev.leonardo.ocremotev2.domain.model.MessageWithParts
 import dev.leonardo.ocremotev2.domain.model.Session
 import dev.leonardo.ocremotev2.domain.repository.SessionRepository
@@ -60,13 +59,12 @@ class ChatViewModelDeleteTest {
     private lateinit var manageTerminalUseCase: ManageTerminalUseCase
     private lateinit var draftUseCase: DraftUseCase
     private lateinit var shareExportUseCase: ShareExportUseCase
     private lateinit var undoRedoUseCase: UndoRedoUseCase
     private lateinit var messagePaging: MessagePaginationUseCase
     private val tokenStatsTracker = TokenStatsTracker()
-    private val sessionStatusManager: SessionStatusManager = mockk(relaxed = true)
     private val sessionStateService: SessionStateService = mockk(relaxed = true)
     private val sessionFocusHolder = mockk<SessionFocusHolder>(relaxed = true)
     private val appNotificationManager = mockk<AppNotificationManager>(relaxed = true)
     private val toolSnapshotCache = ToolSnapshotCache()
 
     private val testSessionId = "session-123"
@@ -87,15 +85,14 @@ class ChatViewModelDeleteTest {
             messageUpdatedHandler = MessageUpdatedHandler(messageStore),
             messageRemovedHandler = MessageRemovedHandler(messageStore),
             permissionHandler = PermissionEventHandler(),
             questionHandler = QuestionEventHandler(),
             miscHandler = MiscEventHandler(),
             sessionNextHandler = SessionNextEventHandler(),
-            sessionStatusManager = sessionStatusManager
+            sessionStateService = sessionStateService
         )
-        every { sessionStatusManager.statusFlow } returns eventDispatcher.sessionStatuses
         every { sessionStateService.statusFlow } returns MutableStateFlow(emptyMap())
 
         mockkStatic(Log::class)
         every { Log.d(any(), any()) } returns 0
         every { Log.e(any(), any()) } returns 0
         every { Log.e(any(), any(), any()) } returns 0
@@ -239,13 +236,12 @@ class ChatViewModelDeleteTest {
                 every { sessRepo.getCurrentModelFlow(any()) } returns eventDispatcher.currentModel
             },
             messagePaging = messagePaging,
             tokenStatsTracker = tokenStatsTracker,
             httpClient = mockk(relaxed = true),
             sseClient = mockk(relaxed = true),
-            sessionStatusManager = sessionStatusManager,
             sessionStateService = sessionStateService,
             sessionFocusHolder = sessionFocusHolder,
             scrollSignal = SessionScrollSignal(),
             appNotificationManager = appNotificationManager,
             toolSnapshotCache = toolSnapshotCache
         )
diff --git a/app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModelPermissionTest.kt b/app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModelPermissionTest.kt
index ecfb0644..a5240c60 100644
--- a/app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModelPermissionTest.kt
+++ b/app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModelPermissionTest.kt
@@ -9,13 +9,12 @@ import dev.leonardo.ocremotev2.domain.model.AppSettings
 import dev.leonardo.ocremotev2.domain.model.ProvidersResponse
 import dev.leonardo.ocremotev2.domain.model.PermissionState
 import dev.leonardo.ocremotev2.domain.repository.ChatRepository
 import dev.leonardo.ocremotev2.domain.repository.DraftRepository
 import dev.leonardo.ocremotev2.data.repository.EventDispatcher
 import dev.leonardo.ocremotev2.data.repository.SessionStateService
-import dev.leonardo.ocremotev2.data.repository.SessionStatusManager
 import dev.leonardo.ocremotev2.service.SessionFocusHolder
 import dev.leonardo.ocremotev2.service.AppNotificationManager
 import dev.leonardo.ocremotev2.data.repository.handler.*
 import dev.leonardo.ocremotev2.domain.model.Session
 import dev.leonardo.ocremotev2.domain.model.SseEvent
 import dev.leonardo.ocremotev2.domain.model.ToolRef
@@ -71,13 +70,12 @@ class ChatViewModelPermissionTest {
     private lateinit var manageTerminalUseCase: ManageTerminalUseCase
     private lateinit var draftUseCase: DraftUseCase
     private lateinit var shareExportUseCase: ShareExportUseCase
     private lateinit var undoRedoUseCase: UndoRedoUseCase
     private lateinit var messagePaging: MessagePaginationUseCase
     private val tokenStatsTracker = TokenStatsTracker()
-    private val sessionStatusManager: SessionStatusManager = mockk(relaxed = true)
     private val sessionStateService: SessionStateService = mockk(relaxed = true)
     private val sessionFocusHolder = mockk<SessionFocusHolder>(relaxed = true)
     private val appNotificationManager = mockk<AppNotificationManager>(relaxed = true)
     private val toolSnapshotCache = ToolSnapshotCache()
 
     private val testSessionId = "session-123"
@@ -99,15 +97,14 @@ class ChatViewModelPermissionTest {
             messageUpdatedHandler = MessageUpdatedHandler(messageStore),
             messageRemovedHandler = MessageRemovedHandler(messageStore),
             permissionHandler = PermissionEventHandler(),
             questionHandler = QuestionEventHandler(),
             miscHandler = MiscEventHandler(),
             sessionNextHandler = SessionNextEventHandler(),
-            sessionStatusManager = sessionStatusManager
+            sessionStateService = sessionStateService
         )
-        every { sessionStatusManager.statusFlow } returns eventDispatcher.sessionStatuses
         every { sessionStateService.statusFlow } returns MutableStateFlow(emptyMap())
 
         mockkStatic(Log::class)
         every { Log.d(any(), any()) } returns 0
         every { Log.e(any(), any()) } returns 0
         every { Log.e(any(), any(), any()) } returns 0
@@ -243,13 +240,12 @@ class ChatViewModelPermissionTest {
                 every { it.getCurrentModelFlow(any()) } returns eventDispatcher.currentModel
             },
             messagePaging = messagePaging,
             tokenStatsTracker = tokenStatsTracker,
             httpClient = mockk(relaxed = true),
             sseClient = mockk(relaxed = true),
-            sessionStatusManager = sessionStatusManager,
             sessionStateService = sessionStateService,
             sessionFocusHolder = sessionFocusHolder,
             scrollSignal = SessionScrollSignal(),
             appNotificationManager = appNotificationManager,
             toolSnapshotCache = toolSnapshotCache
         )
diff --git a/app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModelQueuedTest.kt b/app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModelQueuedTest.kt
index 6742a243..ef4078e2 100644
--- a/app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModelQueuedTest.kt
+++ b/app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModelQueuedTest.kt
@@ -6,13 +6,12 @@ import dev.leonardo.ocremotev2.data.repository.ServerTerminalRegistry
 import io.ktor.client.HttpClient
 import dev.leonardo.ocremotev2.data.api.SseClient
 import dev.leonardo.ocremotev2.domain.model.AppSettings
 import dev.leonardo.ocremotev2.domain.model.ProvidersResponse
 import dev.leonardo.ocremotev2.data.repository.EventDispatcher
 import dev.leonardo.ocremotev2.data.repository.SessionStateService
-import dev.leonardo.ocremotev2.data.repository.SessionStatusManager
 import dev.leonardo.ocremotev2.service.SessionFocusHolder
 import dev.leonardo.ocremotev2.service.AppNotificationManager
 import dev.leonardo.ocremotev2.data.repository.handler.*
 import dev.leonardo.ocremotev2.domain.model.*
 import dev.leonardo.ocremotev2.domain.repository.ChatRepository
 import dev.leonardo.ocremotev2.domain.repository.SessionRepository
@@ -73,13 +72,12 @@ class ChatViewModelQueuedTest {
     private val manageTerminalUseCase: ManageTerminalUseCase = mockk(relaxed = true)
     private val draftUseCase: DraftUseCase = mockk(relaxed = true)
     private val shareExportUseCase: ShareExportUseCase = mockk(relaxed = true)
     private val undoRedoUseCase: UndoRedoUseCase = mockk(relaxed = true)
     private val messagePaging: MessagePaginationUseCase = mockk(relaxed = true)
     private val tokenStatsTracker = TokenStatsTracker()
-    private val sessionStatusManager: SessionStatusManager = mockk(relaxed = true)
     private val sessionStateService: SessionStateService = mockk(relaxed = true)
     private val sessionFocusHolder = mockk<SessionFocusHolder>(relaxed = true)
     private val appNotificationManager = mockk<AppNotificationManager>(relaxed = true)
     private val toolSnapshotCache = ToolSnapshotCache()
 
     private val testSessionId = "test-session-1"
@@ -107,16 +105,16 @@ class ChatViewModelQueuedTest {
             messageUpdatedHandler = MessageUpdatedHandler(messageStore),
             messageRemovedHandler = MessageRemovedHandler(messageStore),
             permissionHandler = PermissionEventHandler(),
             questionHandler = QuestionEventHandler(),
             miscHandler = MiscEventHandler(),
             sessionNextHandler = SessionNextEventHandler(),
-            sessionStatusManager = sessionStatusManager
+            sessionStateService = sessionStateService
         )
-        every { sessionStatusManager.statusFlow } returns testStatusFlow
         every { sessionStateService.statusFlow } returns testStatusFlow
+        every { sessionStateService.activityFlow } returns MutableStateFlow(emptyMap())
 
         mockkStatic(Log::class)
         every { Log.d(any(), any()) } returns 0
         every { Log.e(any(), any()) } returns 0
         every { Log.e(any(), any(), any()) } returns 0
         every { Log.w(any(), any<String>()) } returns 0
@@ -285,13 +283,12 @@ class ChatViewModelQueuedTest {
                 coEvery { sessRepo.fetchSessionStatuses(any(), any()) } returns Result.success(emptyMap())
             },
             messagePaging = messagePaging,
             tokenStatsTracker = tokenStatsTracker,
             httpClient = mockk(relaxed = true),
             sseClient = mockk(relaxed = true),
-            sessionStatusManager = sessionStatusManager,
             sessionStateService = sessionStateService,
             sessionFocusHolder = sessionFocusHolder,
             scrollSignal = SessionScrollSignal(),
             appNotificationManager = appNotificationManager,
             toolSnapshotCache = toolSnapshotCache
         )
diff --git a/app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModelSendTest.kt b/app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModelSendTest.kt
index 6cbbc2e7..0c727d21 100644
--- a/app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModelSendTest.kt
+++ b/app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModelSendTest.kt
@@ -2,13 +2,12 @@
 
 import dev.leonardo.ocremotev2.domain.repository.ToolSnapshotCache
 import android.util.Log
 import androidx.lifecycle.SavedStateHandle
 import dev.leonardo.ocremotev2.data.repository.ServerTerminalRegistry
 import dev.leonardo.ocremotev2.data.repository.SessionStateService
-import dev.leonardo.ocremotev2.data.repository.SessionStatusManager
 import dev.leonardo.ocremotev2.service.SessionFocusHolder
 import dev.leonardo.ocremotev2.service.AppNotificationManager
 import io.ktor.client.HttpClient
 import dev.leonardo.ocremotev2.data.api.SseClient
 import dev.leonardo.ocremotev2.domain.model.AppSettings
 import dev.leonardo.ocremotev2.domain.model.ProvidersResponse
@@ -52,13 +51,12 @@ class ChatViewModelSendTest {
     private val manageTerminalUseCase: ManageTerminalUseCase = mockk(relaxed = true)
     private val draftUseCase: DraftUseCase = mockk(relaxed = true)
     private val shareExportUseCase: ShareExportUseCase = mockk(relaxed = true)
     private val undoRedoUseCase: UndoRedoUseCase = mockk(relaxed = true)
     private val messagePaging: MessagePaginationUseCase = mockk(relaxed = true)
     private val tokenStatsTracker = TokenStatsTracker()
-    private val sessionStatusManager: SessionStatusManager = mockk(relaxed = true)
     private val sessionStateService: SessionStateService = mockk(relaxed = true)
     private val sessionFocusHolder = mockk<SessionFocusHolder>(relaxed = true)
     private val appNotificationManager = mockk<AppNotificationManager>(relaxed = true)
     private val toolSnapshotCache = ToolSnapshotCache()
 
     @After
@@ -129,13 +127,12 @@ class ChatViewModelSendTest {
             "username"   to "testuser",
             "password"   to "testpass",
             "serverName" to "TestServer",
             "serverId"   to "test-server",
             "sessionId"  to "test-session"
         ))
-        every { sessionStatusManager.statusFlow } returns MutableStateFlow(emptyMap())
         every { sessionStateService.statusFlow } returns MutableStateFlow(emptyMap())
         return ChatViewModel(
             savedStateHandle = savedState,
             sendMessageUseCase = sendMessageUseCase,
             manageSessionUseCase = manageSessionUseCase,
             managePermissionUseCase = managePermissionUseCase,
@@ -158,13 +155,12 @@ class ChatViewModelSendTest {
                 every { it.getCurrentModelFlow(any()) } returns flowOf(emptyMap())
             },
             messagePaging = messagePaging,
             tokenStatsTracker = tokenStatsTracker,
             httpClient = mockk(relaxed = true),
             sseClient = mockk(relaxed = true),
-            sessionStatusManager = sessionStatusManager,
             sessionStateService = sessionStateService,
             sessionFocusHolder = sessionFocusHolder,
             scrollSignal = SessionScrollSignal(),
             appNotificationManager = appNotificationManager,
             toolSnapshotCache = toolSnapshotCache
         )
diff --git a/app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModelStreamingTest.kt b/app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModelStreamingTest.kt
index 442cae16..8abf2b9f 100644
--- a/app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModelStreamingTest.kt
+++ b/app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModelStreamingTest.kt
@@ -2,13 +2,12 @@
 
 import dev.leonardo.ocremotev2.domain.repository.ToolSnapshotCache
 import android.util.Log
 import androidx.lifecycle.SavedStateHandle
 import dev.leonardo.ocremotev2.data.repository.ServerTerminalRegistry
 import dev.leonardo.ocremotev2.data.repository.SessionStateService
-import dev.leonardo.ocremotev2.data.repository.SessionStatusManager
 import dev.leonardo.ocremotev2.service.SessionFocusHolder
 import dev.leonardo.ocremotev2.service.AppNotificationManager
 import io.ktor.client.HttpClient
 import dev.leonardo.ocremotev2.data.api.SseClient
 import dev.leonardo.ocremotev2.domain.model.AppSettings
 import dev.leonardo.ocremotev2.domain.model.Message
@@ -55,13 +54,12 @@ class ChatViewModelStreamingTest {
     private val manageTerminalUseCase: ManageTerminalUseCase = mockk(relaxed = true)
     private val draftUseCase: DraftUseCase = mockk(relaxed = true)
     private val shareExportUseCase: ShareExportUseCase = mockk(relaxed = true)
     private val undoRedoUseCase: UndoRedoUseCase = mockk(relaxed = true)
     private val messagePaging: MessagePaginationUseCase = mockk(relaxed = true)
     private val tokenStatsTracker = TokenStatsTracker()
-    private val sessionStatusManager: SessionStatusManager = mockk(relaxed = true)
     private val sessionStateService: SessionStateService = mockk(relaxed = true)
     private val sessionFocusHolder = mockk<SessionFocusHolder>(relaxed = true)
     private val appNotificationManager = mockk<AppNotificationManager>(relaxed = true)
     private val toolSnapshotCache = ToolSnapshotCache()
 
     private val messagesFlow = MutableStateFlow<List<Message>>(emptyList())
@@ -147,13 +145,12 @@ class ChatViewModelStreamingTest {
             every { it.getSessionsFlow(any()) } returns flowOf(listOf(createTestSession()))
             every { it.getSessionStatusesFlow(any()) } returns flowOf(emptyMap())
             every { it.getCurrentAgentFlow(any()) } returns flowOf(emptyMap())
             every { it.getCurrentModelFlow(any()) } returns flowOf(emptyMap())
             coEvery { it.fetchSessionStatuses(any(), any()) } returns Result.success(emptyMap())
         }
-        every { sessionStatusManager.statusFlow } returns MutableStateFlow(emptyMap())
         every { sessionStateService.statusFlow } returns MutableStateFlow(emptyMap())
     }
 
     @After
     fun teardown() {
         Dispatchers.resetMain()
@@ -214,13 +211,12 @@ class ChatViewModelStreamingTest {
             chatRepository = chatRepository,
             sessionRepository = sessionRepository,
             messagePaging = messagePaging,
             tokenStatsTracker = tokenStatsTracker,
             httpClient = mockk(relaxed = true),
             sseClient = mockk(relaxed = true),
-            sessionStatusManager = sessionStatusManager,
             sessionStateService = sessionStateService,
             sessionFocusHolder = sessionFocusHolder,
             scrollSignal = SessionScrollSignal(),
             appNotificationManager = appNotificationManager,
             toolSnapshotCache = toolSnapshotCache
         )
