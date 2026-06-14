package dev.minios.ocremote.data.repository

import android.util.Log
import dev.minios.ocremote.BuildConfig
import dev.minios.ocremote.di.ApplicationScope
import dev.minios.ocremote.domain.model.FsmEvent
import dev.minios.ocremote.domain.model.SessionActivity
import dev.minios.ocremote.domain.model.SessionFSMState
import dev.minios.ocremote.domain.model.SessionNextEvent
import dev.minios.ocremote.domain.model.SessionStatus
import dev.minios.ocremote.domain.model.SessionStatusFSM
import dev.minios.ocremote.domain.model.SseEvent
import dev.minios.ocremote.domain.repository.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

private const val TAG = "SessionStatusManager"
private const val STALENESS_CHECK_INTERVAL_MS = 5_000L
private const val STALENESS_THRESHOLD_MS = 15_000L

/**
 * SessionStatusManager — the single source of truth for session status.
 *
 * Holds a [SessionFSMState] per session, driven by SSE events via [SessionStatusFSM].
 * Runs L2 staleness guard (periodic check for stale Busy sessions).
 *
 * Phase 1: Parallel run — does NOT replace existing SessionEventHandler._sessionStatuses.
 * Phase 2+ will switch UI consumers to read from [statusFlow].
 *
 * @param appScope For L2 guard coroutine lifecycle
 */
@Singleton
class SessionStatusManager @Inject constructor(
    @ApplicationScope private val appScope: CoroutineScope,
    private val sessionRepositoryProvider: Provider<SessionRepository>
) {
    private val _fsmStates = MutableStateFlow<Map<String, SessionFSMState>>(emptyMap())

    /** Layer 1: Core status per session — UI reads this for "is processing" */
    val statusFlow: StateFlow<Map<String, SessionStatus>> = _fsmStates
        .map { states -> states.mapValues { it.value.core } }
        .stateIn(appScope, SharingStarted.Eagerly, emptyMap())

    /** Layer 2: Activity detail per session — UI reads this for specific feedback */
    val activityFlow: StateFlow<Map<String, SessionActivity?>> = _fsmStates
        .map { states -> states.mapValues { it.value.activity } }
        .stateIn(appScope, SharingStarted.Eagerly, emptyMap())

    private var stalenessJob: Job? = null

    /**
     * L5 cross-validation checker — set by EventDispatcher.
     * Returns true if the session has assistant messages with time.completed == null
     * (indicates a missed SSE completion event).
     */
    var incompleteAssistantChecker: ((String) -> Boolean)? = null

    /** Current server ID — set by ChatViewModel so REST validation can query the correct server. */
    @Volatile
    private var currentServerId: String? = null

    init {
        startStalenessGuard()
    }

    // ============ Event Entry Points ============

    fun setServerId(serverId: String) {
        currentServerId = serverId
    }

    fun onSendParts(sessionId: String) {
        applyTransition(sessionId, FsmEvent.ClientSendParts)
    }

    fun onAbort(sessionId: String) {
        applyTransition(sessionId, FsmEvent.ClientAbort)
    }

    fun onSseEvent(event: SseEvent, sessionId: String) {
        val fsmEvent = mapSseEventToFsm(event, sessionId) ?: return
        applyTransition(sessionId, fsmEvent)
    }

    fun onRestValidation(sessionId: String, status: SessionStatus) {
        applyTransition(sessionId, FsmEvent.RestValidation(status))
    }

    fun clearSession(sessionId: String) {
        _fsmStates.update { it - sessionId }
    }

    // ============ Internal ============

    private fun applyTransition(sessionId: String, event: FsmEvent) {
        _fsmStates.update { current ->
            val state = current[sessionId] ?: SessionFSMState.initial()
            val result = SessionStatusFSM.transition(state, event)
            if (BuildConfig.DEBUG) {
                Log.d(
                    TAG,
                    "[$sessionId] ${state.core::class.simpleName} --${event::class.simpleName}--> ${result.newState.core::class.simpleName}" +
                        if (result.isSuspicious) " [SUSPICIOUS]" else ""
                )
            }
            if (result.isSuspicious) {
                triggerRestValidation(sessionId)
            }
            current + (sessionId to result.newState)
        }
    }

    private fun mapSseEventToFsm(event: SseEvent, sessionId: String): FsmEvent? {
        return when (event) {
            is SseEvent.SessionStatus -> FsmEvent.SseStatus(event.status)
            is SseEvent.SessionIdle -> FsmEvent.SseIdle
            is SseEvent.SessionError -> FsmEvent.SseError(event.error)
            is SseEvent.SessionNext -> mapSessionNextEvent(event.event, sessionId)
            else -> null
        }
    }

    private fun mapSessionNextEvent(event: SessionNextEvent, sessionId: String): FsmEvent? {
        return when (event) {
            is SessionNextEvent.StepStarted -> FsmEvent.StepStarted(sessionId)
            is SessionNextEvent.TextStarted -> FsmEvent.TextStarted(sessionId)
            is SessionNextEvent.ToolInputStarted -> FsmEvent.ToolInputStarted(sessionId, event.tool, event.callId)
            // StepEnded has no finish field in SessionNextEvent — pass null.
            // FSM treats non-"tool-calls" finish as "keep current Activity, wait for Core Idle".
            is SessionNextEvent.StepEnded -> FsmEvent.StepEnded(sessionId, finish = null)
            is SessionNextEvent.CompactionStarted -> FsmEvent.CompactionStarted
            is SessionNextEvent.CompactionEnded -> FsmEvent.CompactionEnded
            else -> null
        }
    }

    // ============ L2: Staleness Guard ============

    private fun startStalenessGuard() {
        stalenessJob?.cancel()
        stalenessJob = appScope.launch {
            while (isActive) {
                delay(STALENESS_CHECK_INTERVAL_MS)
                checkStaleness()
            }
        }
    }

    private fun checkStaleness() {
        val now = System.currentTimeMillis()
        _fsmStates.value.forEach { (sessionId, state) ->
            // L2: Busy but stale — no SSE events for too long
            if (state.core is SessionStatus.Busy) {
                val staleFor = now - state.lastEventAt
                if (staleFor > STALENESS_THRESHOLD_MS) {
                    Log.w(TAG, "[$sessionId] L2 stale for ${staleFor}ms, triggering REST validation")
                    triggerRestValidation(sessionId)
                }
            }
            // L5: Idle but has incomplete assistant messages — missed SSE completion
            if (state.core is SessionStatus.Idle) {
                val hasIncomplete = incompleteAssistantChecker?.invoke(sessionId) ?: false
                if (hasIncomplete) {
                    Log.w(TAG, "[$sessionId] L5 inconsistency: Idle but has incomplete assistant, triggering REST validation")
                    triggerRestValidation(sessionId)
                }
            }
        }
    }

    // ============ L3: REST Validation ============

    private fun triggerRestValidation(sessionId: String) {
        val sid = currentServerId ?: return
        appScope.launch {
            try {
                val result = sessionRepositoryProvider.get().fetchSessionStatuses(sid, directory = null)
                result.onSuccess { statuses ->
                    val serverStatus = statuses[sessionId]
                    if (serverStatus != null) {
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "[$sessionId] L3 REST validation: server says ${serverStatus::class.simpleName}")
                        }
                        onRestValidation(sessionId, serverStatus)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "[$sessionId] L3 REST validation failed: ${e.message}")
            }
        }
    }
}
