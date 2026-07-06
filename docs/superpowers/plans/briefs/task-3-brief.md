# Task 3 (incl. merged Task 2): TransitionRecord + SessionStateService skeleton

**Files:**
- Create: `app/src/main/kotlin/dev/leonardo/ocremotev2/domain/model/TransitionRecord.kt`
- Create: `app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStateService.kt`
- Create: `app/src/test/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStateServiceTest.kt`

**Interfaces:**
- Consumes: `SessionStatusFSM.transition`, `SessionFSMState`, `FsmEvent`, `SessionActivity`, `SseEvent`, `SessionNextEvent`, `SessionRepository`, `@ApplicationScope CoroutineScope`, `javax.inject.Provider`
- Produces: `TransitionRecord` (data class), `SessionStateService` (@Singleton) with `statusFlow`/`activityFlow`/`historyFlow`, event entry points, `applyTransition` pipeline, 3 callback interfaces (`DirectoryResolver`/`IncompleteAssistantChecker`/`MessageForceCompleter`)

## Step 1: Create TransitionRecord.kt

```kotlin
package dev.leonardo.ocremotev2.domain.model

/** Immutable record of one FSM transition, for traceability/diagnostics. */
data class TransitionRecord(
    val sessionId: String,
    val timestamp: Long,
    val event: String,
    val fromCore: String,
    val toCore: String,
    val fromActivity: String?,
    val toActivity: String?,
    val isSuspicious: Boolean,
    val reason: String?,
)
```

## Step 2: Create SessionStateService.kt

Use this exact implementation. It mirrors the existing `SessionStatusManager` structure (verified) — same mapSseEventToFsm/mapSessionNextEvent pattern (see SessionStatusManager.kt:134-158), same applyTransition flow but with history + forceComplete side effects.

```kotlin
package dev.leonardo.ocremotev2.data.repository

import android.util.Log
import dev.leonardo.ocremotev2.BuildConfig
import dev.leonardo.ocremotev2.di.ApplicationScope
import dev.leonardo.ocremotev2.domain.model.*
import dev.leonardo.ocremotev2.domain.repository.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

fun interface DirectoryResolver { fun resolve(sessionId: String): String? }
fun interface IncompleteAssistantChecker { fun hasIncomplete(sessionId: String): Boolean }
fun interface MessageForceCompleter { fun markIdle(sessionId: String) }

private const val TAG = "SessionStateService"
private const val HISTORY_MAX = 20

@Singleton
class SessionStateService @Inject constructor(
    @ApplicationScope private val appScope: CoroutineScope,
    private val sessionRepoProvider: Provider<SessionRepository>,
) {
    // Injected post-construction (breaks circular dep with EventDispatcher)
    var directoryResolver: DirectoryResolver = DirectoryResolver { null }
    var incompleteChecker: IncompleteAssistantChecker = IncompleteAssistantChecker { false }
    var messageForceCompleter: MessageForceCompleter = MessageForceCompleter {}

    @Volatile private var currentServerId: String? = null

    private val _fsmStates = MutableStateFlow<Map<String, SessionFSMState>>(emptyMap())
    private val _histories = MutableStateFlow<Map<String, List<TransitionRecord>>>(emptyMap())

    val statusFlow: StateFlow<Map<String, SessionStatus>> = _fsmStates
        .map { it.mapValues { e -> e.value.core } }
        .stateIn(appScope, SharingStarted.Eagerly, emptyMap())

    val activityFlow: StateFlow<Map<String, SessionActivity?>> = _fsmStates
        .map { it.mapValues { e -> e.value.activity } }
        .stateIn(appScope, SharingStarted.Eagerly, emptyMap())

    val historyFlow: StateFlow<Map<String, List<TransitionRecord>>> = _histories
        .stateIn(appScope, SharingStarted.Eagerly, emptyMap())

    fun setServerId(serverId: String) { currentServerId = serverId }

    // ============ Event entry points ============
    fun onClientSendParts(sessionId: String) = applyTransition(sessionId, FsmEvent.ClientSendParts)
    fun onClientAbort(sessionId: String) = applyTransition(sessionId, FsmEvent.ClientAbort)
    fun onRestValidation(sessionId: String, status: SessionStatus) =
        applyTransition(sessionId, FsmEvent.RestValidation(status))

    fun onSseEvent(event: SseEvent, sessionId: String) {
        val fsmEvent = mapSseEventToFsm(event) ?: return
        applyTransition(sessionId, fsmEvent)
    }

    private fun mapSseEventToFsm(event: SseEvent): FsmEvent? = when (event) {
        is SseEvent.SessionStatus -> FsmEvent.SseStatus(event.status)
        is SseEvent.SessionIdle -> FsmEvent.SseIdle
        is SseEvent.SessionError -> FsmEvent.SseError(event.error)
        is SseEvent.SessionNext -> mapSessionNextEvent(event.event)
        else -> null
    }

    private fun mapSessionNextEvent(event: SessionNextEvent): FsmEvent? = when (event) {
        is SessionNextEvent.StepStarted -> FsmEvent.StepStarted
        is SessionNextEvent.TextStarted -> FsmEvent.TextStarted
        is SessionNextEvent.TextDelta -> FsmEvent.TextDelta(event.delta)
        is SessionNextEvent.TextEnded -> FsmEvent.TextEnded
        is SessionNextEvent.ToolInputStarted -> FsmEvent.ToolInputStarted(event.tool, event.callId)
        // SessionNextEvent.StepEnded has no finish field — pass null. FSM treats non-"tool-calls"
        // finish as "keep current Activity, wait for Core Idle".
        is SessionNextEvent.StepEnded -> FsmEvent.StepEnded(finish = null)
        is SessionNextEvent.CompactionStarted -> FsmEvent.CompactionStarted
        is SessionNextEvent.CompactionEnded -> FsmEvent.CompactionEnded
        else -> null
    }

    // ============ Core pipeline ============
    fun applyTransition(sessionId: String, event: FsmEvent) {
        val current = _fsmStates.value[sessionId] ?: SessionFSMState.initial()
        val result = SessionStatusFSM.transition(current, event)
        _fsmStates.update { it + (sessionId to result.newState) }
        recordHistory(sessionId, current, result, event)
        if (BuildConfig.DEBUG) logTransition(sessionId, current, result, event)
        // Side effects
        if (result.forceComplete) messageForceCompleter.markIdle(sessionId)
        if (result.isSuspicious) triggerRestValidation(sessionId)
    }

    private fun recordHistory(sessionId: String, from: SessionFSMState, result: SessionStatusFSM.TransitionResult, event: FsmEvent) {
        val record = TransitionRecord(
            sessionId = sessionId,
            timestamp = System.currentTimeMillis(),
            event = event::class.simpleName ?: "Unknown",
            fromCore = from.core::class.simpleName ?: "?",
            toCore = result.newState.core::class.simpleName ?: "?",
            fromActivity = from.activity?.let { it::class.simpleName },
            toActivity = result.newState.activity?.let { it::class.simpleName },
            isSuspicious = result.isSuspicious,
            reason = null,
        )
        _histories.update { h ->
            val list = (h[sessionId] ?: emptyList()) + record
            val trimmed = if (list.size > HISTORY_MAX) list.takeLast(HISTORY_MAX) else list
            h + (sessionId to trimmed)
        }
    }

    private fun logTransition(sessionId: String, from: SessionFSMState, result: SessionStatusFSM.TransitionResult, event: FsmEvent) {
        val actFrom = from.activity?.let { "/${it::class.simpleName}" } ?: ""
        val actTo = result.newState.activity?.let { "/${it::class.simpleName}" } ?: ""
        val flags = buildString {
            if (result.isSuspicious) append(" [SUSPICIOUS]")
            if (result.forceComplete) append(" [force-complete]")
        }
        Log.d(TAG, "[$sessionId] ${from.core::class.simpleName}$actFrom --${event::class.simpleName}--> ${result.newState.core::class.simpleName}$actTo$flags")
    }

    // ============ Lifecycle ============
    fun clearSession(sessionId: String) {
        _fsmStates.update { it - sessionId }
        _histories.update { it - sessionId }
    }

    fun clearForServer(sessionIds: Set<String>) {
        _fsmStates.update { it - sessionIds }
        _histories.update { it - sessionIds }
    }

    fun clearAll() {
        _fsmStates.value = emptyMap()
        _histories.value = emptyMap()
    }

    // Placeholder — implemented in Task 4 (staleness guard)
    internal fun triggerRestValidation(sessionId: String) { /* Task 4 */ }
}
```

## Step 3: Write SessionStateServiceTest (adapted to project's runTest pattern)

The project does NOT have `MainDispatcherRule` or `providerOf`. Use the project's existing test pattern: `runTest { }` (see EventDispatcherIntegrationTest.kt:1-12), construct the service with the test's `TestScope` as appScope, and a `Provider<SessionRepository>` wrapping a relaxed mockk. Access `.value` on flows (SharingStarted.Eagerly makes them hot).

```kotlin
package dev.leonardo.ocremotev2.data.repository

import dev.leonardo.ocremotev2.domain.model.*
import dev.leonardo.ocremotev2.domain.model.SseEvent
import dev.leonardo.ocremotev2.domain.repository.SessionRepository
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import javax.inject.Provider

class SessionStateServiceTest {

    @Test
    fun `ClientSendParts transitions Idle to Busy/Waiting in statusFlow`() = runTest {
        val service = SessionStateService(this, Provider { mockk<SessionRepository>(relaxed = true) })
        service.onClientSendParts("s1")
        assertEquals(SessionStatus.Busy, service.statusFlow.value["s1"])
        assertEquals(SessionActivity.Waiting, service.activityFlow.value["s1"])
    }

    @Test
    fun `SseIdle after Busy triggers forceComplete on messageForceCompleter`() = runTest {
        val forceCompleter = mockk<MessageForceCompleter>(relaxed = true)
        val service = SessionStateService(this, Provider { mockk<SessionRepository>(relaxed = true) })
        service.messageForceCompleter = forceCompleter
        service.onClientSendParts("s1")
        service.onSseEvent(SseEvent.SessionIdle(sessionId = "s1"), "s1")
        assertEquals(SessionStatus.Idle, service.statusFlow.value["s1"])
        verify { forceCompleter.markIdle("s1") }
    }

    @Test
    fun `transition recorded in history`() = runTest {
        val service = SessionStateService(this, Provider { mockk<SessionRepository>(relaxed = true) })
        service.onClientSendParts("s1")
        val history = service.historyFlow.value["s1"]
        assertEquals(1, history!!.size)
        assertEquals("Idle", history[0].fromCore)
        assertEquals("Busy", history[0].toCore)
    }

    @Test
    fun `history trims to max 20 entries`() = runTest {
        val service = SessionStateService(this, Provider { mockk<SessionRepository>(relaxed = true) })
        repeat(25) { service.onClientSendParts("s1") }  // each is a transition
        val history = service.historyFlow.value["s1"]!!
        assertTrue("history should be trimmed to <= 20, was ${history.size}", history.size <= 20)
    }

    @Test
    fun `clearSession removes state and history`() = runTest {
        val service = SessionStateService(this, Provider { mockk<SessionRepository>(relaxed = true) })
        service.onClientSendParts("s1")
        service.clearSession("s1")
        assertNull(service.statusFlow.value["s1"])
        assertNull(service.historyFlow.value["s1"])
    }
}
```

Note: `runTest { ... }` makes `this` a `TestScope` (which IS a `CoroutineScope`), so it can be passed as the `appScope` parameter. The `@ApplicationScope` qualifier is irrelevant for direct construction in tests (no Hilt). If `Provider { mockk(...) }` doesn't compile, use `javax.inject.Provider { ... }` explicitly.

## Step 4: Run tests

Run: `.\gradlew :app:testDevDebugUnitTest --rerun --tests "*SessionStateServiceTest*"` (180000ms)
Expected: all PASS

If a test fails because statusFlow.value is empty (timing), the issue is SharingStarted.Eagerly needing the scope active — `runTest`'s TestScope should drive it; if not, report as DONE_WITH_CONCERNS with the failure.

## Step 5: Commit

```bash
git add app/src/main/kotlin/dev/leonardo/ocremotev2/domain/model/TransitionRecord.kt app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStateService.kt app/src/test/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStateServiceTest.kt
git commit -m "feat(state): SessionStateService skeleton + TransitionRecord (flows, pipeline, history)"
```
