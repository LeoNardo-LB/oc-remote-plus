package dev.minios.ocremote.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStatusFSMTest {

    private val now = 1700000000000L
    private fun idleState() = SessionFSMState.initial(now)
    private fun busyState(activity: SessionActivity? = SessionActivity.Waiting) =
        SessionFSMState(SessionStatus.Busy, activity, now, now)

    // ========== Core transitions ==========

    @Test
    fun `idle to busy on ClientSendParts`() {
        val result = SessionStatusFSM.transition(idleState(), FsmEvent.ClientSendParts)
        assertEquals(SessionStatus.Busy, result.newState.core)
        assertEquals(SessionActivity.Waiting, result.newState.activity)
        assertFalse(result.isSuspicious)
    }

    @Test
    fun `idle to busy on SseStatus busy`() {
        val result = SessionStatusFSM.transition(idleState(), FsmEvent.SseStatus(SessionStatus.Busy))
        assertEquals(SessionStatus.Busy, result.newState.core)
        assertEquals(SessionActivity.Waiting, result.newState.activity)
    }

    @Test
    fun `busy stays busy on duplicate SseStatus busy (idempotent)`() {
        val state = busyState(SessionActivity.Streaming)
        val result = SessionStatusFSM.transition(state, FsmEvent.SseStatus(SessionStatus.Busy))
        assertEquals(SessionStatus.Busy, result.newState.core)
        // Activity should NOT reset to Waiting on duplicate busy
        assertEquals(SessionActivity.Streaming, result.newState.activity)
    }

    @Test
    fun `busy to idle on SseStatus idle`() {
        val result = SessionStatusFSM.transition(busyState(), FsmEvent.SseStatus(SessionStatus.Idle))
        assertEquals(SessionStatus.Idle, result.newState.core)
        assertEquals(null, result.newState.activity)
    }

    @Test
    fun `busy to idle on SseIdle (legacy event)`() {
        val result = SessionStatusFSM.transition(busyState(), FsmEvent.SseIdle)
        assertEquals(SessionStatus.Idle, result.newState.core)
    }

    @Test
    fun `busy to idle on SseError`() {
        val result = SessionStatusFSM.transition(busyState(), FsmEvent.SseError("fatal"))
        assertEquals(SessionStatus.Idle, result.newState.core)
        assertEquals(null, result.newState.activity)
    }

    @Test
    fun `busy to retry on SseStatus retry`() {
        val retry = SessionStatus.Retry(attempt = 1, message = "timeout", next = now + 5000)
        val result = SessionStatusFSM.transition(busyState(), FsmEvent.SseStatus(retry))
        assertEquals(retry, result.newState.core)
    }

    @Test
    fun `retry to busy on SseStatus busy`() {
        val retry = SessionStatus.Retry(attempt = 1, message = "timeout", next = now + 5000)
        val state = SessionFSMState(retry, null, now, now)
        val result = SessionStatusFSM.transition(state, FsmEvent.SseStatus(SessionStatus.Busy))
        assertEquals(SessionStatus.Busy, result.newState.core)
        assertEquals(SessionActivity.Waiting, result.newState.activity)
    }

    @Test
    fun `retry to idle on SseStatus idle`() {
        val retry = SessionStatus.Retry(attempt = 1, message = "timeout", next = now + 5000)
        val state = SessionFSMState(retry, null, now, now)
        val result = SessionStatusFSM.transition(state, FsmEvent.SseStatus(SessionStatus.Idle))
        assertEquals(SessionStatus.Idle, result.newState.core)
    }

    @Test
    fun `any to idle on ClientAbort`() {
        val result = SessionStatusFSM.transition(busyState(SessionActivity.Streaming), FsmEvent.ClientAbort)
        assertEquals(SessionStatus.Idle, result.newState.core)
        assertEquals(null, result.newState.activity)
        assertTrue(result.clearIncompleteMarkers)
    }

    // ========== REST validation overrides ==========

    @Test
    fun `RestValidation idle overrides any state`() {
        val result = SessionStatusFSM.transition(busyState(), FsmEvent.RestValidation(SessionStatus.Idle))
        assertEquals(SessionStatus.Idle, result.newState.core)
        assertTrue(result.clearIncompleteMarkers)
    }

    @Test
    fun `RestValidation busy overrides idle`() {
        val result = SessionStatusFSM.transition(idleState(), FsmEvent.RestValidation(SessionStatus.Busy))
        assertEquals(SessionStatus.Busy, result.newState.core)
        assertEquals(SessionActivity.Waiting, result.newState.activity)
    }

    // ========== Activity transitions ==========

    @Test
    fun `stepStarted sets activity to Waiting when Busy`() {
        val result = SessionStatusFSM.transition(busyState(), FsmEvent.StepStarted("sid"))
        assertEquals(SessionActivity.Waiting, result.newState.activity)
        assertFalse(result.isSuspicious)
    }

    @Test
    fun `textStarted sets activity to Streaming when Busy`() {
        val result = SessionStatusFSM.transition(busyState(), FsmEvent.TextStarted("sid"))
        assertEquals(SessionActivity.Streaming, result.newState.activity)
    }

    @Test
    fun `toolInputStarted sets activity to ToolCalling when Busy`() {
        val result = SessionStatusFSM.transition(busyState(), FsmEvent.ToolInputStarted("sid", "read", "call-1"))
        assertEquals(SessionActivity.ToolCalling("read", "call-1"), result.newState.activity)
    }

    @Test
    fun `stepEnded with tool-calls returns to Waiting`() {
        val state = busyState(SessionActivity.ToolCalling("read", "call-1"))
        val result = SessionStatusFSM.transition(state, FsmEvent.StepEnded("sid", "tool-calls"))
        assertEquals(SessionActivity.Waiting, result.newState.activity)
    }

    @Test
    fun `stepEnded with stop keeps current activity`() {
        val state = busyState(SessionActivity.Streaming)
        val result = SessionStatusFSM.transition(state, FsmEvent.StepEnded("sid", "stop"))
        assertEquals(SessionActivity.Streaming, result.newState.activity)
    }

    // ========== Compaction ==========

    @Test
    fun `compactionStarted saves current activity and switches to Compacting`() {
        val state = busyState(SessionActivity.Streaming)
        val result = SessionStatusFSM.transition(state, FsmEvent.CompactionStarted)
        assertEquals(SessionActivity.Compacting, result.newState.activity)
        assertEquals(SessionActivity.Streaming, result.newState.savedActivity)
    }

    @Test
    fun `compactionEnded restores saved activity`() {
        val state = SessionFSMState(
            core = SessionStatus.Busy,
            activity = SessionActivity.Compacting,
            lastEventAt = now,
            lastCoreTransitionAt = now,
            savedActivity = SessionActivity.Streaming
        )
        val result = SessionStatusFSM.transition(state, FsmEvent.CompactionEnded)
        assertEquals(SessionActivity.Streaming, result.newState.activity)
        assertEquals(null, result.newState.savedActivity)
    }

    // ========== Illegal transitions (suspicious) ==========

    @Test
    fun `stepStarted when Idle is suspicious`() {
        val result = SessionStatusFSM.transition(idleState(), FsmEvent.StepStarted("sid"))
        assertTrue(result.isSuspicious)
    }

    @Test
    fun `textStarted when Idle is suspicious`() {
        val result = SessionStatusFSM.transition(idleState(), FsmEvent.TextStarted("sid"))
        assertTrue(result.isSuspicious)
    }

    @Test
    fun `toolInputStarted when Idle is suspicious`() {
        val result = SessionStatusFSM.transition(idleState(), FsmEvent.ToolInputStarted("sid", "read", "call-1"))
        assertTrue(result.isSuspicious)
    }

    // ========== Core to Idle clears activity ==========

    @Test
    fun `transition to Idle always clears activity`() {
        val state = busyState(SessionActivity.ToolCalling("write", "call-2"))
        val result = SessionStatusFSM.transition(state, FsmEvent.SseStatus(SessionStatus.Idle))
        assertEquals(null, result.newState.activity)
    }
}
