package dev.leonardo.ocremotev2.domain.model

/**
 * Pure-function Finite State Machine for session status.
 *
 * Two-layer architecture:
 * - Layer 1 (Core): Idle / Busy / Retry — mirrors server's SessionStatus
 * - Layer 2 (Activity): Waiting / Streaming / ToolCalling / Compacting — derived detail
 *
 * Statelessness: This object holds no mutable state. All state lives in
 * [SessionStatusManager]'s Map<sessionId, SessionFSMState>.
 *
 * Testability: transition() is a pure function — given (state, event), always
 * produces the same TransitionResult. No side effects.
 */
object SessionStatusFSM {

    data class TransitionResult(
        val newState: SessionFSMState,
        /** True if the transition indicates a likely lost SSE event (e.g., Activity event in Idle state) */
        val isSuspicious: Boolean,
        /** True if incomplete message markers should be cleared (e.g., abort, REST confirms Idle) */
        val clearIncompleteMarkers: Boolean
    )

    fun transition(state: SessionFSMState, event: FsmEvent): TransitionResult {
        val now = System.currentTimeMillis()

        return when (event) {
            // === Core events ===
            FsmEvent.ClientSendParts -> {
                if (state.core is SessionStatus.Idle) {
                    TransitionResult(
                        newState = state.copy(
                            core = SessionStatus.Busy,
                            activity = SessionActivity.Waiting,
                            lastEventAt = now,
                            lastCoreTransitionAt = now
                        ),
                        isSuspicious = false,
                        clearIncompleteMarkers = false
                    )
                } else {
                    TransitionResult(state.copy(lastEventAt = now), false, false)
                }
            }

            FsmEvent.ClientAbort -> TransitionResult(
                newState = SessionFSMState(
                    core = SessionStatus.Idle,
                    activity = null,
                    lastEventAt = now,
                    lastCoreTransitionAt = now
                ),
                isSuspicious = false,
                clearIncompleteMarkers = true
            )

            is FsmEvent.SseStatus -> handleSseStatus(state, event.status, now)

            FsmEvent.SseIdle -> TransitionResult(
                newState = state.copy(
                    core = SessionStatus.Idle,
                    activity = null,
                    lastEventAt = now,
                    lastCoreTransitionAt = now
                ),
                isSuspicious = false,
                clearIncompleteMarkers = false
            )

            is FsmEvent.SseError -> TransitionResult(
                newState = state.copy(
                    core = SessionStatus.Idle,
                    activity = null,
                    lastEventAt = now,
                    lastCoreTransitionAt = now
                ),
                isSuspicious = false,
                clearIncompleteMarkers = false
            )

            is FsmEvent.RestValidation -> TransitionResult(
                newState = state.copy(
                    core = event.status,
                    activity = if (event.status is SessionStatus.Busy) SessionActivity.Waiting else null,
                    lastEventAt = now,
                    lastCoreTransitionAt = now
                ),
                isSuspicious = false,
                clearIncompleteMarkers = event.status is SessionStatus.Idle
            )

            // === Activity events ===
            is FsmEvent.StepStarted -> handleActivityEvent(state, now) {
                state.copy(activity = SessionActivity.Waiting)
            }

            is FsmEvent.TextStarted -> handleActivityEvent(state, now) {
                state.copy(activity = SessionActivity.Streaming)
            }

            is FsmEvent.ToolInputStarted -> handleActivityEvent(state, now) {
                state.copy(activity = SessionActivity.ToolCalling(event.toolName, event.callId))
            }

            is FsmEvent.StepEnded -> handleActivityEvent(state, now) {
                if (event.finish == "tool-calls") {
                    state.copy(activity = SessionActivity.Waiting)
                } else {
                    state // keep current activity, wait for Core to go Idle
                }
            }

            FsmEvent.CompactionStarted -> handleActivityEvent(state, now) {
                state.copy(activity = SessionActivity.Compacting, savedActivity = state.activity)
            }

            FsmEvent.CompactionEnded -> handleActivityEvent(state, now) {
                state.copy(activity = state.savedActivity, savedActivity = null)
            }
        }
    }

    private fun handleSseStatus(state: SessionFSMState, status: SessionStatus, now: Long): TransitionResult {
        return when (status) {
            is SessionStatus.Busy -> {
                val isTransition = state.core !is SessionStatus.Busy
                TransitionResult(
                    newState = state.copy(
                        core = SessionStatus.Busy,
                        activity = if (isTransition) SessionActivity.Waiting else state.activity,
                        lastEventAt = now,
                        lastCoreTransitionAt = if (isTransition) now else state.lastCoreTransitionAt
                    ),
                    isSuspicious = false,
                    clearIncompleteMarkers = false
                )
            }
            is SessionStatus.Idle -> TransitionResult(
                newState = state.copy(
                    core = SessionStatus.Idle,
                    activity = null,
                    lastEventAt = now,
                    lastCoreTransitionAt = now
                ),
                isSuspicious = false,
                clearIncompleteMarkers = false
            )
            is SessionStatus.Retry -> TransitionResult(
                newState = state.copy(
                    core = status,
                    activity = null,
                    lastEventAt = now,
                    lastCoreTransitionAt = now
                ),
                isSuspicious = false,
                clearIncompleteMarkers = false
            )
        }
    }

    /**
     * Handle Activity events — only valid when Core is Busy.
     * If Core is not Busy, mark as suspicious (likely lost busy SSE event).
     */
    private inline fun handleActivityEvent(
        state: SessionFSMState,
        now: Long,
        update: () -> SessionFSMState
    ): TransitionResult {
        return if (state.core is SessionStatus.Busy) {
            TransitionResult(
                newState = update().copy(lastEventAt = now),
                isSuspicious = false,
                clearIncompleteMarkers = false
            )
        } else {
            // Activity event in non-Busy state = suspicious (busy event likely lost)
            TransitionResult(
                newState = state.copy(lastEventAt = now),
                isSuspicious = true,
                clearIncompleteMarkers = false
            )
        }
    }
}
