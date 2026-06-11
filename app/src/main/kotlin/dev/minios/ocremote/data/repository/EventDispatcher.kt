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
    private val sessionNextHandler: SessionNextEventHandler
) {
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
        // ============ Premature Idle Protection ============
        // Intercept session.idle / session.status(idle) when the session still has
        // incomplete assistant messages (time.completed == null). The server sends
        // idle events between tool calls / agent dispatches, but the conversation
        // is not truly done. Blocking these prevents status flickering in both
        // ChatScreen and SessionListScreen.
        if (isPrematureIdle(event)) {
            // Still dispatch to other handlers (messages, permissions, etc.)
            // but skip sessionHandler to preserve Busy status.
            messageHandler.handle(event, serverId)
            permissionHandler.handle(event, serverId)
            questionHandler.handle(event, serverId)
            miscHandler.handle(event, serverId)
            sessionNextHandler.handle(event, serverId)
            return
        }

        sessionHandler.handle(event, serverId)
        messageHandler.handle(event, serverId)
        permissionHandler.handle(event, serverId)
        questionHandler.handle(event, serverId)
        miscHandler.handle(event, serverId)
        sessionNextHandler.handle(event, serverId)

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
     * Check if a session idle/status(idle) SSE event is premature — i.e. the session
     * still has assistant messages with time.completed == null, meaning the AI is
     * still actively generating content.
     */
    private fun isPrematureIdle(event: SseEvent): Boolean {
        val sessionId: String
        val isIdle: Boolean
        when (event) {
            is SseEvent.SessionIdle -> {
                sessionId = event.sessionId
                isIdle = true
            }
            is SseEvent.SessionStatus -> {
                sessionId = event.sessionId
                isIdle = event.status is SessionStatus.Idle
            }
            else -> return false
        }
        if (!isIdle) return false
        return hasIncompleteAssistant(sessionId)
    }

    /**
     * Check if a session has any assistant messages still streaming (time.completed == null).
     */
    private fun hasIncompleteAssistant(sessionId: String): Boolean {
        return messageHandler.messages.value[sessionId]
            .orEmpty()
            .filterIsInstance<Message.Assistant>()
            .any { it.time.completed == null }
    }

    // ============ Delegated Operations ============

    fun setSessions(serverId: String, sessions: List<Session>) =
        sessionHandler.setSessions(serverId, sessions)

    fun updateSessionStatus(sessionId: String, status: SessionStatus) =
        sessionHandler.updateSessionStatus(sessionId, status)

    fun clearRevert(sessionId: String) =
        sessionHandler.clearRevert(sessionId)

    fun setMessages(sessionId: String, messages: List<MessageWithParts>) =
        messageHandler.setMessages(sessionId, messages)

    fun mergeMessages(sessionId: String, messages: List<MessageWithParts>) =
        messageHandler.mergeMessages(sessionId, messages)

    fun replaceMessages(sessionId: String, messages: List<MessageWithParts>) =
        messageHandler.replaceMessages(sessionId, messages)

    fun syncAllSessionStatuses(statuses: Map<String, SessionStatus>) {
        // Filter out Idle downgrades for sessions with incomplete assistant messages.
        // REST polling may report idle during tool-call gaps, but the conversation
        // is still active if messages are streaming.
        val protectedStatuses = statuses.mapValues { (sessionId, status) ->
            if (status is SessionStatus.Idle && hasIncompleteAssistant(sessionId)) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Protecting session $sessionId from REST Idle (has incomplete messages)")
                }
                // Keep current status instead of downgrading
                sessionHandler.sessionStatuses.value[sessionId] ?: status
            } else {
                status
            }
        }
        sessionHandler.updateAllSessionStatuses(protectedStatuses)
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
     * Force-mark all streaming messages in a session as completed.
     * Used when REST fallback detects the server reports "idle" but
     * the UI may still show "thinking" due to missed SSE events.
     */
    fun markSessionIdle(sessionId: String) {
        sessionHandler.updateSessionStatus(sessionId, SessionStatus.Idle)
    }

    /**
     * Like [markSessionIdle] but uses SSE-freshness protection on the status update.
     * Won't set Idle if SSE recently (within 5s) updated the status to Busy/Retry.
     * Message completion still proceeds regardless.
     */
    fun markSessionIdleProtected(sessionId: String) {
        sessionHandler.updateSessionStatusProtected(sessionId, SessionStatus.Idle)
    }
}
