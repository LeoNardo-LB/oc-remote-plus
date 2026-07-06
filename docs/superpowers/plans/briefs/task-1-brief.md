# Task 1 (REVISED): Evolve FSM — Activity savedActivity + remove sessionId + add TextDelta/TextEnded

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/domain/model/SessionFSMState.kt` (evolve SessionActivity + FsmEvent)
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/domain/model/SessionStatusFSM.kt` (rewrite transition + helpers)
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStatusManager.kt` (adapt mapSessionNextEvent)
- Modify: `app/src/test/kotlin/dev/leonardo/ocremotev2/domain/model/SessionStatusFSMTest.kt` (rewrite tests)

**Interfaces:**
- Produces: evolved `SessionActivity` (Compacting now holds savedActivity), evolved `FsmEvent` (activity events lose sessionId; +TextDelta/TextEnded), `SessionStatusFSM.transition` with new `forceComplete` flag in TransitionResult

## Background (current structure)

`SessionFSMState.kt` ALREADY contains `SessionActivity` (sealed class: Waiting/Streaming/ToolCalling(toolName,callId)/Compacting as data object), `SessionFSMState` (data class with core/activity/lastEventAt/lastCoreTransitionAt/savedActivity), and `FsmEvent` (sealed class). `SessionStatusFSM.kt` contains `object SessionStatusFSM` with `transition()` + `TransitionResult(newState, isSuspicious, clearIncompleteMarkers)`.

`SessionStatusManager.mapSessionNextEvent` builds FsmEvent with sessionId — must adapt.

## Step 1: Evolve SessionActivity + FsmEvent in SessionFSMState.kt

In `SessionFSMState.kt`:

**1a. SessionActivity — change Compacting from data object to data class with savedActivity:**

```kotlin
// BEFORE:
//     data object Compacting : SessionActivity()
// AFTER:
    data class Compacting(val savedActivity: SessionActivity?) : SessionActivity()
```
Keep Waiting, Streaming, ToolCalling(toolName, callId) unchanged.

**1b. FsmEvent — remove sessionId from activity events, add TextDelta + TextEnded:**

```kotlin
sealed class FsmEvent {
    // === Core events === (unchanged)
    data class SseStatus(val status: SessionStatus) : FsmEvent()
    data object SseIdle : FsmEvent()
    data class SseError(val message: String) : FsmEvent()
    data object ClientSendParts : FsmEvent()
    data object ClientAbort : FsmEvent()
    data class RestValidation(val status: SessionStatus) : FsmEvent()

    // === Activity events (session.next.*) ===
    data object StepStarted : FsmEvent()                           // was: data class StepStarted(sessionId)
    data object TextStarted : FsmEvent()                           // was: data class TextStarted(sessionId)
    data class TextDelta(val delta: String) : FsmEvent()           // NEW
    data object TextEnded : FsmEvent()                             // NEW
    data class ToolInputStarted(val toolName: String?, val callId: String?) : FsmEvent()  // was: +(sessionId)
    data class StepEnded(val finish: String?) : FsmEvent()         // was: +(sessionId)
    data object CompactionStarted : FsmEvent()                     // unchanged
    data object CompactionEnded : FsmEvent()                       // unchanged
}
```

## Step 2: Rewrite SessionStatusFSM.transition + TransitionResult

In `SessionStatusFSM.kt`:

**2a. TransitionResult — rename `clearIncompleteMarkers` to `forceComplete` (same semantics):**

```kotlin
data class TransitionResult(
    val newState: SessionFSMState,
    val isSuspicious: Boolean,
    val forceComplete: Boolean,   // was: clearIncompleteMarkers
)
```

**2b. Rewrite transition() to handle the new event types + Activity dimension:**

```kotlin
fun transition(state: SessionFSMState, event: FsmEvent): TransitionResult {
    val now = System.currentTimeMillis()
    return when (event) {
        FsmEvent.ClientSendParts -> clientSendParts(state, now)
        FsmEvent.ClientAbort -> toIdle(state, now, forceComplete = true)
        is FsmEvent.SseStatus -> handleSseStatus(state, event.status, now)
        FsmEvent.SseIdle -> toIdle(state, now, forceComplete = true)
        is FsmEvent.SseError -> toIdle(state, now, forceComplete = true)
        is FsmEvent.RestValidation -> restValidation(state, event.status, now)
        // Activity events
        FsmEvent.StepStarted -> activityEvent(state, now) { it.copy(activity = SessionActivity.Waiting) }
        FsmEvent.TextStarted -> activityEvent(state, now) { it.copy(activity = SessionActivity.Streaming) }
        is FsmEvent.TextDelta -> activityEvent(state, now) { if (it.activity is SessionActivity.Streaming) it else it.copy(activity = SessionActivity.Streaming) }
        FsmEvent.TextEnded -> activityEvent(state, now) { it.copy(activity = SessionActivity.Waiting) }
        is FsmEvent.ToolInputStarted -> activityEvent(state, now) { it.copy(activity = SessionActivity.ToolCalling(event.toolName, event.callId)) }
        is FsmEvent.StepEnded -> stepEnded(state, event.finish, now)
        FsmEvent.CompactionStarted -> activityEvent(state, now) { it.copy(activity = SessionActivity.Compacting(savedActivity = it.activity)) }
        FsmEvent.CompactionEnded -> activityEvent(state, now) { it.copy(activity = (it.activity as? SessionActivity.Compacting)?.savedActivity) }
    }
}

private fun clientSendParts(state: SessionFSMState, now: Long) = when (state.core) {
    is SessionStatus.Idle -> TransitionResult(
        state.copy(core = SessionStatus.Busy, activity = SessionActivity.Waiting, lastEventAt = now, lastCoreTransitionAt = now),
        isSuspicious = false, forceComplete = false)
    else -> TransitionResult(state.copy(lastEventAt = now), false, false)
}

private fun toIdle(state: SessionFSMState, now: Long, forceComplete: Boolean) = TransitionResult(
    state.copy(core = SessionStatus.Idle, activity = null, savedActivity = null, lastEventAt = now, lastCoreTransitionAt = now),
    isSuspicious = false, forceComplete = forceComplete)

private fun handleSseStatus(state: SessionFSMState, status: SessionStatus, now: Long) = when (status) {
    is SessionStatus.Busy -> {
        val isTransition = state.core !is SessionStatus.Busy
        TransitionResult(state.copy(core = SessionStatus.Busy,
            activity = if (isTransition) SessionActivity.Waiting else state.activity,
            lastEventAt = now, lastCoreTransitionAt = if (isTransition) now else state.lastCoreTransitionAt),
            false, false)
    }
    is SessionStatus.Idle -> toIdle(state, now, forceComplete = true)
    is SessionStatus.Retry -> TransitionResult(
        state.copy(core = status, activity = null, savedActivity = null, lastEventAt = now, lastCoreTransitionAt = now),
        false, false)
}

private fun restValidation(state: SessionFSMState, status: SessionStatus, now: Long) = TransitionResult(
    state.copy(core = status,
        activity = if (status is SessionStatus.Busy) SessionActivity.Waiting else null,
        savedActivity = null, lastEventAt = now, lastCoreTransitionAt = now),
    isSuspicious = false, forceComplete = status is SessionStatus.Idle)

/** Activity events: valid only when Core is Busy; otherwise suspicious (likely missed Busy). */
private inline fun activityEvent(state: SessionFSMState, now: Long, update: (SessionFSMState) -> SessionFSMState): TransitionResult =
    if (state.core is SessionStatus.Busy) TransitionResult(update(state).copy(lastEventAt = now), false, false)
    else TransitionResult(state.copy(lastEventAt = now), isSuspicious = true, forceComplete = false)

private fun stepEnded(state: SessionFSMState, finish: String?, now: Long): TransitionResult {
    if (state.core !is SessionStatus.Busy)
        return TransitionResult(state.copy(lastEventAt = now), isSuspicious = true, forceComplete = false)
    val newActivity = if (finish == "tool-calls") SessionActivity.Waiting else state.activity
    return TransitionResult(state.copy(activity = newActivity, lastEventAt = now), false, false)
}
```

Remove any now-unused private helpers from the old implementation (e.g. old `handleActivityEvent` if superseded). Keep the file compiling.

## Step 3: Adapt SessionStatusManager.mapSessionNextEvent

In `SessionStatusManager.kt`, the `mapSessionNextEvent` function (around line 135) currently builds FsmEvent with sessionId. Update to match the new event types (no sessionId; add TextDelta/TextEnded):

```kotlin
private fun mapSessionNextEvent(event: SessionNextEvent): FsmEvent? {
    return when (event) {
        is SessionNextEvent.StepStarted -> FsmEvent.StepStarted
        is SessionNextEvent.TextStarted -> FsmEvent.TextStarted
        is SessionNextEvent.TextDelta -> FsmEvent.TextDelta(event.delta)
        is SessionNextEvent.TextEnded -> FsmEvent.TextEnded
        is SessionNextEvent.ToolInputStarted -> FsmEvent.ToolInputStarted(event.tool, event.callId)
        is SessionNextEvent.StepEnded -> FsmEvent.StepEnded(finish = null)
        is SessionNextEvent.CompactionStarted -> FsmEvent.CompactionStarted
        is SessionNextEvent.CompactionEnded -> FsmEvent.CompactionEnded
        else -> null
    }
}
```

Note: the `sessionId` parameter is removed from `mapSessionNextEvent`. Find its caller (`mapSseEventToFsm` around line 125, which calls `mapSessionNextEvent(event.event, sessionId)`) and update the call to drop sessionId: `mapSessionNextEvent(event.event)`. The `sessionId` param of `mapSseEventToFsm` itself may become unused — if so, remove it and update its caller (`onSseEvent`). Keep the call chain consistent and compiling.

IMPORTANT: verify `SessionNextEvent` actually has `TextDelta` and `TextEnded` subtypes. If it does not, do NOT invent them — report as DONE_WITH_CONCERNS. (They are expected to exist since SessionNextEventHandler handles them.)

## Step 4: Rewrite SessionStatusFSMTest

Replace `app/src/test/kotlin/.../domain/model/SessionStatusFSMTest.kt`:

```kotlin
class SessionStatusFSMTest {
    private val idle = SessionFSMState.initial()
    private val busyWaiting = idle.copy(core = SessionStatus.Busy, activity = SessionActivity.Waiting)
    private val busyStreaming = busyWaiting.copy(activity = SessionActivity.Streaming)

    @Test fun `Idle + ClientSendParts -> Busy/Waiting`() {
        val r = SessionStatusFSM.transition(idle, FsmEvent.ClientSendParts)
        assertEquals(SessionStatus.Busy, r.newState.core)
        assertEquals(SessionActivity.Waiting, r.newState.activity)
        assertFalse(r.forceComplete)
    }

    @Test fun `Busy/Streaming + TextEnded -> Busy/Waiting`() {
        val r = SessionStatusFSM.transition(busyStreaming, FsmEvent.TextEnded)
        assertEquals(SessionActivity.Waiting, r.newState.activity)
        assertEquals(SessionStatus.Busy, r.newState.core)
    }

    @Test fun `Busy/Streaming + SseIdle -> Idle/null + forceComplete`() {
        val r = SessionStatusFSM.transition(busyStreaming, FsmEvent.SseIdle)
        assertEquals(SessionStatus.Idle, r.newState.core)
        assertNull(r.newState.activity)
        assertTrue(r.forceComplete)
    }

    @Test fun `Idle + TextStarted -> suspicious, unchanged`() {
        val r = SessionStatusFSM.transition(idle, FsmEvent.TextStarted)
        assertEquals(idle.core, r.newState.core)
        assertTrue(r.isSuspicious)
    }

    @Test fun `Busy + RestValidation(Idle) -> Idle/null + forceComplete`() {
        val r = SessionStatusFSM.transition(busyStreaming, FsmEvent.RestValidation(SessionStatus.Idle))
        assertEquals(SessionStatus.Idle, r.newState.core)
        assertTrue(r.forceComplete)
    }

    @Test fun `CompactionStarted saves activity, CompactionEnded restores`() {
        val compacting = SessionStatusFSM.transition(busyStreaming, FsmEvent.CompactionStarted).newState
        assertTrue(compacting.activity is SessionActivity.Compacting)
        assertEquals(SessionActivity.Streaming, (compacting.activity as SessionActivity.Compacting).savedActivity)
        val restored = SessionStatusFSM.transition(compacting, FsmEvent.CompactionEnded).newState
        assertEquals(SessionActivity.Streaming, restored.activity)
    }

    @Test fun `invariant: Idle state never holds activity`() {
        val events = listOf(FsmEvent.SseIdle, FsmEvent.ClientAbort, FsmEvent.RestValidation(SessionStatus.Idle))
        for (e in events) {
            val r = SessionStatusFSM.transition(busyStreaming, e)
            assertNull(r.newState.activity, "After $e, activity must be null")
            assertEquals(SessionStatus.Idle, r.newState.core)
        }
    }

    @Test fun `ClientAbort always forceComplete`() {
        val r = SessionStatusFSM.transition(busyStreaming, FsmEvent.ClientAbort)
        assertTrue(r.forceComplete)
    }
}
```

Note: existing SessionStatusFSMTest may have used `clearIncompleteMarkers` — replace with `forceComplete`. Existing tests using FsmEvent with sessionId (e.g. `FsmEvent.StepStarted("s1")`) must be updated to the new no-sessionId form.

## Step 5: Run tests

Run: `.\gradlew :app:testDevDebugUnitTest --rerun --tests "*SessionStatusFSMTest*"` (timeout 180000ms)
Expected: all PASS

Also run a broader compile check to ensure SessionStatusManager still compiles:
`.\gradlew :app:compileDevDebugKotlin` (timeout 120000ms)
Expected: BUILD SUCCESSFUL

## Step 6: Commit

```bash
git add app/src/main/kotlin/dev/leonardo/ocremotev2/domain/model/SessionFSMState.kt app/src/main/kotlin/dev/leonardo/ocremotev2/domain/model/SessionStatusFSM.kt app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStatusManager.kt app/src/test/kotlin/dev/leonardo/ocremotev2/domain/model/SessionStatusFSMTest.kt
git commit -m "feat(state): evolve FSM — Activity savedActivity + drop sessionId + add TextDelta/TextEnded + forceComplete"
```
