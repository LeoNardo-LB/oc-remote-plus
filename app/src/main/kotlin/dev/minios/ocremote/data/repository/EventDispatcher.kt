package dev.minios.ocremote.data.repository

import android.util.Log
import dev.minios.ocremote.BuildConfig
import dev.minios.ocremote.data.repository.handler.*
import dev.minios.ocremote.domain.model.FileDiff
import dev.minios.ocremote.domain.model.Message
import dev.minios.ocremote.domain.model.MessageWithParts
import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.domain.model.Project
import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.domain.model.SessionNextEvent
import dev.minios.ocremote.domain.model.SessionStatus
import dev.minios.ocremote.domain.model.SseEvent
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "EventDispatcher"

/**
 * Event Dispatcher - replaces the monolithic EventReducer.
 *
 * Delegates SSE events to registered [SseEventHandler] instances.
 * Exposes read-only StateFlows aggregated from handlers.
 * Handles cross-cutting concerns (e.g. SessionDeleted cascading cleanup,
 * CommandExecuted session status reset).
 */
@Singleton
class EventDispatcher @Inject constructor(
    private val sessionHandler: SessionEventHandler,
    private val messageHandler: MessageEventHandler,
    private val permissionHandler: PermissionEventHandler,
    private val questionHandler: QuestionEventHandler,
    private val miscHandler: MiscEventHandler,
    private val sessionNextHandler: SessionNextEventHandler,
    private val sessionStatusManager: SessionStatusManager
) {
    init {
        // L5 cross-validation: SessionStatusManager checks if Idle sessions have
        // incomplete assistant messages (time.completed == null), which indicates
        // a missed SSE event. The callback is set here because SessionStatusManager
        // cannot inject EventDispatcher (constructor simplified to avoid circular deps).
        sessionStatusManager.incompleteAssistantChecker = { sessionId ->
            hasIncompleteAssistant(sessionId)
        }
    }

    // ============ Public State (read-only) ============

    val serverSessions: StateFlow<Map<String, Set<String>>> get() = sessionHandler.serverSessions
    val sessions: StateFlow<List<Session>> get() = sessionHandler.sessions
    val sessionStatuses: StateFlow<Map<String, SessionStatus>> get() = sessionHandler.sessionStatuses
    val messages: StateFlow<Map<String, List<Message>>> get() = messageHandler.messages
    val parts: StateFlow<Map<String, List<Part>>> get() = messageHandler.parts
    val sessionDiffs: StateFlow<Map<String, List<FileDiff>>> get() = sessionHandler.sessionDiffs
    val permissions: StateFlow<Map<String, List<SseEvent.PermissionAsked>>> get() = permissionHandler.permissions
    val questions: StateFlow<Map<String, List<SseEvent.QuestionAsked>>> get() = questionHandler.questions
    val todos: StateFlow<Map<String, List<SseEvent.TodoUpdated.Todo>>> get() = miscHandler.todos
    val vcsBranch: StateFlow<String?> get() = sessionHandler.vcsBranch
    val projectInfo: StateFlow<Project?> get() = sessionHandler.projectInfo
    val lastUserMessageTime: StateFlow<Map<String, Long>> get() = sessionHandler.lastUserMessageTime

    // Session Next state
    val currentAgent: StateFlow<Map<String, String>> get() = sessionNextHandler.currentAgent
    val currentModel: StateFlow<Map<String, Pair<String, String>>> get() = sessionNextHandler.currentModel
    val activeToolProgress: StateFlow<Map<String, List<ToolProgressInfo>>> get() = sessionNextHandler.activeToolProgress
    val stepProgress: StateFlow<Map<String, StepProgressInfo>> get() = sessionNextHandler.stepProgress
    val compactionState: StateFlow<Map<String, CompactionStateInfo>> get() = sessionNextHandler.compactionState
    val shellState: StateFlow<Map<String, ShellStateInfo>> get() = sessionNextHandler.shellState
    val retryState: StateFlow<Map<String, Int>> get() = sessionNextHandler.retryState
    val gapDetected: StateFlow<Set<String>> get() = sessionNextHandler.gapDetected

    // ============ Event Processing ============

    /**
     * Process an SSE event by dispatching to all handlers.
     * Handles cross-cutting concerns after dispatch:
     * - SessionDeleted: cascades cleanup to all handlers for the deleted session
     * - CommandExecuted: resets session status to Idle
     */
    fun processEvent(event: SseEvent, serverId: String) {
        sessionHandler.handle(event, serverId)
        messageHandler.handle(event, serverId)
        permissionHandler.handle(event, serverId)
        questionHandler.handle(event, serverId)
        miscHandler.handle(event, serverId)
        sessionNextHandler.handle(event, serverId)
        forwardToStatusManager(event)

        // Cross-handler: SessionDeleted cascades cleanup to other handlers
        if (event is SseEvent.SessionDeleted) {
            val sessionId = event.info.id
            messageHandler.clearForSession(sessionId)
            permissionHandler.clearForSession(sessionId)
            questionHandler.clearForSession(sessionId)
            miscHandler.clearForSession(sessionId)
            sessionNextHandler.clearForSession(sessionId)
        }

        // Cross-handler: CommandExecuted — only mark messages as completed.
        // Don't force session to Idle: the server sends session.status event
        // if the session actually becomes idle. Forcing Idle here causes
        // flickering when the agent continues to the next tool call.
        if (event is SseEvent.CommandExecuted) {
            messageHandler.markSessionIdle(event.sessionId)
        }

        // Track user message times for stable session sort ordering.
        if (event is SseEvent.MessageUpdated && event.info is Message.User) {
            sessionHandler.recordUserMessage(event.info.sessionId, event.info.time.created)
        }
    }

    /**
     * Check if a session has any assistant messages still streaming (time.completed == null).
     * Used by REST sync logic and L5 cross-validation checker.
     */
    private fun hasIncompleteAssistant(sessionId: String): Boolean {
        return messageHandler.messages.value[sessionId]
            .orEmpty()
            .filterIsInstance<Message.Assistant>()
            .any { it.time.completed == null }
    }

    // ============ FSM Forwarding (P1: parallel run) ============

    /**
     * Forward SSE event to [SessionStatusManager] for parallel FSM processing.
     * P1: Manager logs transitions only, no UI impact.
     */
    private fun forwardToStatusManager(event: SseEvent) {
        val fsmSessionId = extractSessionId(event)
        if (fsmSessionId != null) {
            sessionStatusManager.onSseEvent(event, fsmSessionId)
        }
    }

    /**
     * Extract sessionId from any [SseEvent] subclass.
     * Returns null for events that have no associated session.
     */
    private fun extractSessionId(event: SseEvent): String? {
        return when (event) {
            // Session lifecycle (status-relevant for FSM)
            is SseEvent.SessionStatus -> event.sessionId
            is SseEvent.SessionIdle -> event.sessionId
            is SseEvent.SessionError -> event.sessionId
            is SseEvent.SessionNext -> event.event.sessionId
            // Session lifecycle (info)
            is SseEvent.SessionCreated -> event.info.id
            is SseEvent.SessionUpdated -> event.info.id
            is SseEvent.SessionDeleted -> event.info.id
            is SseEvent.SessionDiff -> event.sessionId
            is SseEvent.SessionCompacted -> event.sessionId
            // Messages
            is SseEvent.MessageUpdated -> event.info.sessionId
            is SseEvent.MessageRemoved -> event.sessionId
            is SseEvent.MessagePartUpdated -> event.part.sessionId
            is SseEvent.MessagePartDelta -> event.sessionId
            is SseEvent.MessagePartRemoved -> event.sessionId
            // Permission / Question
            is SseEvent.PermissionAsked -> event.sessionId
            is SseEvent.PermissionReplied -> event.sessionId
            is SseEvent.QuestionAsked -> event.sessionId
            is SseEvent.QuestionReplied -> event.sessionId
            is SseEvent.QuestionRejected -> event.sessionId
            // Todo / Command
            is SseEvent.TodoUpdated -> event.sessionId
            is SseEvent.CommandExecuted -> event.sessionId
            // Events without sessionId
            is SseEvent.ServerConnected -> null
            is SseEvent.ServerHeartbeat -> null
            is SseEvent.ServerInstanceDisposed -> null
            is SseEvent.VcsBranchUpdated -> null
            is SseEvent.LspUpdated -> null
            is SseEvent.ProjectUpdated -> null
            is SseEvent.PtyCreated -> null
            is SseEvent.PtyUpdated -> null
            is SseEvent.PtyDeleted -> null
            is SseEvent.WorkspaceReady -> null
            is SseEvent.WorkspaceFailed -> null
            is SseEvent.FileEdited -> null
            is SseEvent.McpToolsChanged -> null
            is SseEvent.FileWatcherUpdated -> null
            is SseEvent.InstallationUpdated -> null
            is SseEvent.InstallationUpdateAvailable -> null
            is SseEvent.WorktreeReady -> null
            is SseEvent.WorktreeFailed -> null
        }
    }

    // ============ Delegated Operations ============

    fun setSessions(serverId: String, sessions: List<Session>) =
        sessionHandler.setSessions(serverId, sessions)

    fun updateSessionStatus(sessionId: String, status: SessionStatus) =
        sessionHandler.updateSessionStatus(sessionId, status)

    fun clearRevert(sessionId: String) =
        sessionHandler.clearRevert(sessionId)

    fun setRevert(sessionId: String, messageId: String) =
        sessionHandler.setRevert(sessionId, messageId)

    fun setMessages(sessionId: String, messages: List<MessageWithParts>) =
        messageHandler.setMessages(sessionId, messages)

    fun mergeMessages(sessionId: String, messages: List<MessageWithParts>) =
        messageHandler.mergeMessages(sessionId, messages)

    fun replaceMessages(sessionId: String, messages: List<MessageWithParts>) =
        messageHandler.replaceMessages(sessionId, messages)

    /**
     * Batch-update session statuses from REST data.
     *
     * Trusts REST as the authoritative source: if REST says a session is idle,
     * it IS idle. The server's in-memory status (`AgentCoordinator`) is the
     * ground truth, and REST queries it directly.
     *
     * When REST confirms idle for a session that was locally Busy, also
     * force-complete any incomplete assistant messages — this handles the
     * case where SSE completion events were lost (network glitch, reordering).
     */
    fun syncAllSessionStatuses(statuses: Map<String, SessionStatus>) {
        // Fix incomplete messages for sessions confirmed idle by REST
        for ((sessionId, status) in statuses) {
            if (status is SessionStatus.Idle && hasIncompleteAssistant(sessionId)) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "REST confirmed idle for $sessionId with incomplete messages — fixing")
                }
                messageHandler.markSessionIdle(sessionId)
            }
        }

        // Also handle sessions absent from REST response.
        // Server deletes idle sessions from its status map, so absence means idle.
        // Only force-fix if there are no incomplete messages (otherwise SSE may still be streaming).
        val currentStatuses = sessionHandler.sessionStatuses.value
        val mergedStatuses = statuses.toMutableMap()
        for ((sessionId, currentStatus) in currentStatuses) {
            if (currentStatus !is SessionStatus.Idle && sessionId !in statuses) {
                if (hasIncompleteAssistant(sessionId)) {
                    // Incomplete messages — SSE may still be active. Keep current status.
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "Protecting absent session $sessionId (has incomplete messages, was ${currentStatus::class.simpleName})")
                    }
                    mergedStatuses[sessionId] = currentStatus
                } else {
                    // No incomplete messages and absent from REST — confirmed idle
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "Session $sessionId absent from REST, no incomplete messages — marking Idle")
                    }
                }
            }
        }

        sessionHandler.updateAllSessionStatuses(mergedStatuses)
    }

    fun removePermission(permissionId: String) =
        permissionHandler.removePermission(permissionId)

    fun setPermissions(sessionId: String, permissions: List<SseEvent.PermissionAsked>) =
        permissionHandler.setPermissions(sessionId, permissions)

    fun removeQuestion(questionId: String) =
        questionHandler.removeQuestion(questionId)

    fun setQuestions(sessionId: String, questions: List<SseEvent.QuestionAsked>) =
        questionHandler.setQuestions(sessionId, questions)

    fun trackSequence(sessionId: String, seq: Long) {
        sessionNextHandler.trackSequence(sessionId, seq)
    }

    fun clearGap(sessionId: String) {
        sessionNextHandler.clearGap(sessionId)
    }

    // ============ Child-session Aggregation ============

    /** Aggregate permissions for a session including its child sessions. */
    fun getPermissionsWithChildren(sessionId: String, sessions: List<Session>) =
        permissionHandler.getPermissionsWithChildren(sessionId, sessions)

    /** Aggregate questions for a session including its child sessions. */
    fun getQuestionsWithChildren(sessionId: String, sessions: List<Session>) =
        questionHandler.getQuestionsWithChildren(sessionId, sessions)

    fun clearAll() {
        sessionHandler.clearAll()
        messageHandler.clearAll()
        permissionHandler.clearAll()
        questionHandler.clearAll()
        miscHandler.clearAll()
        sessionNextHandler.clearAll()
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

    // ============ State Correction ============

    /**
     * Force-mark all streaming messages in a session as completed AND set Idle.
     * Used only for explicit terminal actions: abort, or entering a session
     * where the server confirms idle but local data has stale incomplete messages.
     */
    fun markSessionIdle(sessionId: String) {
        messageHandler.markSessionIdle(sessionId)
        sessionHandler.updateSessionStatus(sessionId, SessionStatus.Idle)
    }

    /**
     * Update session status to Idle with SSE-freshness protection.
     * Does NOT modify messages.
     * Used by periodic REST polling when server confirms a session is idle.
     */
    fun markSessionIdleProtected(sessionId: String) {
        sessionHandler.updateSessionStatusProtected(sessionId, SessionStatus.Idle)
    }
}
