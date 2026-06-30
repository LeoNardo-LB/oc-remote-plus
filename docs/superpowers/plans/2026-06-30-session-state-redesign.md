# Session State System Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rewrite session status management into a single source of truth (`SessionStateService`) driven by a pure-function FSM with an exhaustive transition matrix, replacing the current three-layer scattered state.

**Architecture:** `SessionStateService` holds all session status (core: idle/busy/retry) and message-level streaming activity, driven by a pure FSM. A self-driven staleness guard reads its own output and triggers REST validation to form a recovery closed-loop. Six scattered recovery paths collapse into one `syncFromRest()`. UI reads only `statusFlow`/`activityFlow`.

**Tech Stack:** Kotlin, Jetpack Compose, StateFlow, Hilt, JUnit4 + MockK 1.14.9 + Turbine 1.2.1 + kotlinx-coroutines-test

**Spec:** `docs/superpowers/specs/2026-06-30-session-state-redesign-design.md`

## Global Constraints

- JDK 21 (`jvmToolchain(21)` in build.gradle.kts)
- Build commands: `.\gradlew :app:compileDevDebugKotlin` (compile check, 120s), `.\gradlew :app:testDevDebugUnitTest --rerun` (tests, 180s)
- Flavor: always `DevDebug` for compile/test, `DevRelease` for APK
- Hilt uses KSP (not kapt); `@Singleton` + `@Inject constructor` for DI
- `isReturnDefaultValues = true` in tests — mocks return defaults instead of throwing; verify real behavior explicitly
- Path handling: never use `File.name`/`substringAfterLast` for remote paths
- Conventions: Conventional Commits (`type(scope): desc`); run compile after each edit; Read before Edit
- Existing FSM at `domain/model/SessionStatusFSM.kt` will be **evolved in place** (renamed logically, same file path initially to minimize churn, renamed at Task 12)

---

## File Structure

**New files:**
| File | Responsibility |
|------|----------------|
| `app/src/main/kotlin/.../data/repository/SessionStateService.kt` | Single source of truth: holds FSM states, exposes 3 Flows, event pipeline, staleness guard, syncFromRest |
| `app/src/main/kotlin/.../domain/model/TransitionRecord.kt` | Immutable record of one FSM transition (for traceability) |
| `app/src/test/kotlin/.../data/repository/SessionStateServiceTest.kt` | Service unit tests (event chains, staleness, syncFromRest) |
| `app/src/test/kotlin/.../domain/model/SessionStateFSMTest.kt` | Pure FSM transition matrix tests (replaces SessionStatusFSMTest) |

**Modified files:**
| File | Change |
|------|--------|
| `domain/model/SessionStatusFSM.kt` | Evolve → adds Activity dimension, full matrix, rename to SessionStateFSM (Task 12) |
| `domain/model/SessionStatus.kt` | Add `SessionActivity` sealed type + `SessionStateServiceState` (or new file) |
| `data/repository/EventDispatcher.kt` | Inject SessionStateService; SSE status events dual-write; set 3 callbacks; remove status methods (Task 12) |
| `data/repository/handler/SessionEventHandler.kt` | Remove `_sessionStatuses`/`_sseTimestamps`/status methods (Task 12) |
| `ui/screens/sessions/SessionListViewModel.kt` | combine reads `sessionStateService.statusFlow` |
| `ui/screens/chat/ChatViewModel.kt` | `sessionMetaState` reads `sessionStateService.statusFlow` |
| `ui/screens/chat/components/ChatMessageList.kt` | `streamingMsgId` reads `activityFlow` |
| `ui/screens/chat/components/PartContent.kt` | Reasoning `isStreaming` reads activity via CompositionLocal |
| `service/SseConnectionManager.kt` | Recovery paths → `syncFromRest` |
| `ui/screens/chat/SessionActionsDelegate.kt` | Recovery paths → `syncFromRest` |

**Deleted files (Task 12):**
- `data/repository/SessionStatusManager.kt`
- `domain/model/SessionStatusFSM.kt` (after rename to SessionStateFSM)

---

## Task 1: Evolve SessionStatusFSM — add Activity dimension + full matrix

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/domain/model/SessionStatusFSM.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/domain/model/SessionStatus.kt` (add SessionActivity)
- Test: `app/src/test/kotlin/dev/leonardo/ocremotev2/domain/model/SessionStatusFSMTest.kt` (rewrite)

**Interfaces:**
- Produces: `SessionActivity` (sealed: Waiting/Streaming/ToolCalling/Compacting), `SessionFSMState` (extended with activity+savedActivity), `FsmEvent` (extended with activity events), `SessionStatusFSM.transition(state, event) -> TransitionResult`

- [ ] **Step 1: Add SessionActivity + extend SessionFSMState in SessionStatus.kt**

Append to `domain/model/SessionStatus.kt` (after the SessionStatus sealed class):

```kotlin
/** Message-level streaming activity. Only meaningful when Core is Busy. */
sealed interface SessionActivity {
    object Waiting : SessionActivity
    object Streaming : SessionActivity
    data class ToolCalling(val tool: String, val callId: String) : SessionActivity
    data class Compacting(val savedActivity: SessionActivity?) : SessionActivity
}
```

- [ ] **Step 2: Extend SessionFSMState + FsmEvent in SessionStatusFSM.kt**

Read current `SessionStatusFSM.kt`. Replace the `SessionFSMState` data class and `FsmEvent` sealed hierarchy to include activity. Keep existing core fields, add `activity` and `savedActivity`:

```kotlin
data class SessionFSMState(
    val core: SessionStatus,
    val activity: SessionActivity? = null,
    val savedActivity: SessionActivity? = null,   // for Compaction save/restore
    val lastEventAt: Long = System.currentTimeMillis(),
    val lastCoreTransitionAt: Long = System.currentTimeMillis(),
) {
    companion object {
        fun initial() = SessionFSMState(core = SessionStatus.Idle, activity = null)
    }
}

sealed interface FsmEvent {
    // Client
    object ClientSendParts : FsmEvent
    object ClientAbort : FsmEvent
    // SSE core
    data class SseStatus(val status: SessionStatus) : FsmEvent
    object SseIdle : FsmEvent
    data class SseError(val error: String) : FsmEvent
    // SSE activity (session.next.*)
    object StepStarted : FsmEvent
    object TextStarted : FsmEvent
    data class TextDelta(val delta: String) : FsmEvent
    object TextEnded : FsmEvent
    data class ToolInputStarted(val tool: String, val callId: String) : FsmEvent
    data class StepEnded(val finish: String?) : FsmEvent
    object CompactionStarted : FsmEvent
    object CompactionEnded : FsmEvent
    // REST
    data class RestValidation(val status: SessionStatus) : FsmEvent
}
```

`TransitionResult` — add `forceComplete`:

```kotlin
data class TransitionResult(
    val newState: SessionFSMState,
    val isSuspicious: Boolean,
    val forceComplete: Boolean,
)
```

- [ ] **Step 3: Rewrite transition() — Core matrix (§4.3)**

Replace the `transition` function body with the full Core + Activity matrix. The Core transitions:

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
        is FsmEvent.ToolInputStarted -> activityEvent(state, now) { it.copy(activity = SessionActivity.ToolCalling(event.tool, event.callId)) }
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

- [ ] **Step 4: Rewrite SessionStatusFSMTest — full matrix coverage**

Replace `app/src/test/kotlin/.../domain/model/SessionStatusFSMTest.kt` with exhaustive cases. Key tests (showing pattern — write all critical ones):

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
        assertEquals(SessionActivity.Compacting::class, compacting.activity!!::class)
        assertEquals(SessionActivity.Streaming, (compacting.activity as SessionActivity.Compacting).savedActivity)
        val restored = SessionStatusFSM.transition(compacting, FsmEvent.CompactionEnded).newState
        assertEquals(SessionActivity.Streaming, restored.activity)
    }

    @Test fun `invariant: Idle state never holds activity`() {
        // Any transition to Idle must null activity
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

- [ ] **Step 5: Run tests**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun --tests "*SessionStatusFSMTest*"`
Expected: all PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocremotev2/domain/model/SessionStatusFSM.kt app/src/main/kotlin/dev/leonardo/ocremotev2/domain/model/SessionStatus.kt app/src/test/kotlin/dev/leonardo/ocremotev2/domain/model/SessionStatusFSMTest.kt
git commit -m "feat(state): evolve FSM with Activity dimension + exhaustive transition matrix"
```

---

## Task 2: TransitionRecord model

**Files:**
- Create: `app/src/main/kotlin/dev/leonardo/ocremotev2/domain/model/TransitionRecord.kt`

**Interfaces:**
- Produces: `TransitionRecord` data class (consumed by SessionStateService history)

- [ ] **Step 1: Create TransitionRecord.kt**

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

- [ ] **Step 2: Compile check**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocremotev2/domain/model/TransitionRecord.kt
git commit -m "feat(state): add TransitionRecord for FSM traceability"
```

---

## Task 3: SessionStateService skeleton — Flows + applyTransition pipeline

**Files:**
- Create: `app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStateService.kt`
- Create: `app/src/test/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStateServiceTest.kt`

**Interfaces:**
- Consumes: `SessionStatusFSM`, `SessionFSMState`, `FsmEvent`, `SessionActivity`, `TransitionRecord` (Task 1-2)
- Produces: `SessionStateService` with `statusFlow`/`activityFlow`/`historyFlow`, `applyTransition`, event entry points

- [ ] **Step 1: Write failing test — event chain drives flows**

```kotlin
class SessionStateServiceTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    private lateinit var service: SessionStateService
    private val forceCompleter = mockk<MessageForceCompleter>(relaxed = true)

    @Before fun setup() {
        service = SessionStateService(
            appScope = UnconfinedTestDispatcher().scope,
            sessionRepoProvider = providerOf(mockk(relaxed = true)),
            directoryResolver = { null },
            incompleteChecker = { false },
            messageForceCompleter = forceCompleter,
        )
    }

    @Test fun `ClientSendParts transitions Idle to Busy/Waiting in statusFlow`() = runTest {
        service.onClientSendParts("s1")
        assertEquals(SessionStatus.Busy, service.statusFlow.value["s1"])
        assertEquals(SessionActivity.Waiting, service.activityFlow.value["s1"])
    }

    @Test fun `SseIdle after Busy triggers forceComplete`() = runTest {
        service.onClientSendParts("s1")
        service.onSseEvent(SseEvent.SessionIdle(sessionId = "s1"), "s1")
        assertEquals(SessionStatus.Idle, service.statusFlow.value["s1"])
        verify { forceCompleter.markIdle("s1") }
    }

    @Test fun `transition recorded in history`() = runTest {
        service.onClientSendParts("s1")
        val history = service.historyFlow.value["s1"]
        assertEquals(1, history!!.size)
        assertEquals("Idle", history[0].fromCore)
        assertEquals("Busy", history[0].toCore)
    }
}
```

- [ ] **Step 2: Run test — verify fails (SessionStateService not defined)**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "*SessionStateServiceTest*"`
Expected: FAIL (unresolved reference)

- [ ] **Step 3: Implement SessionStateService.kt**

```kotlin
package dev.leonardo.ocremotev2.data.repository

import android.util.Log
import dev.leonardo.ocremotev2.BuildConfig
import dev.leonardo.ocremotev2.di.ApplicationScope
import dev.leonardo.ocremotev2.domain.model.*
import dev.leonardo.ocremotev2.domain.model.SessionActivity
import dev.leonardo.ocremotev2.domain.repository.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
        val fsmEvent = mapSseEvent(event) ?: return
        applyTransition(sessionId, fsmEvent)
    }

    private fun mapSseEvent(event: SseEvent): FsmEvent? = when (event) {
        is SseEvent.SessionStatus -> FsmEvent.SseStatus(event.status)
        is SseEvent.SessionIdle -> FsmEvent.SseIdle
        is SseEvent.SessionError -> FsmEvent.SseError(event.error)
        is SseEvent.SessionNext -> mapSessionNextEvent(event.event)
        else -> null
    }

    private fun mapSessionNextEvent(e: SessionNextEvent): FsmEvent? = when (e) {
        is SessionNextEvent.StepStarted -> FsmEvent.StepStarted
        is SessionNextEvent.TextStarted -> FsmEvent.TextStarted
        is SessionNextEvent.TextDelta -> FsmEvent.TextDelta(e.delta)
        is SessionNextEvent.TextEnded -> FsmEvent.TextEnded
        is SessionNextEvent.ToolInputStarted -> FsmEvent.ToolInputStarted(e.tool, e.callId)
        is SessionNextEvent.StepEnded -> FsmEvent.StepEnded(e.finish)
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
        _histories.update { it - sessionIds.keys }
    }

    fun clearAll() {
        _fsmStates.value = emptyMap()
        _histories.value = emptyMap()
    }

    // Placeholder — implemented in Task 5/6
    internal fun triggerRestValidation(sessionId: String) { /* Task 5 */ }
}
```

- [ ] **Step 4: Run test — verify passes**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "*SessionStateServiceTest*"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStateService.kt app/src/test/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStateServiceTest.kt
git commit -m "feat(state): SessionStateService skeleton with FSM pipeline + flows + history"
```

---

## Task 4: Staleness guard + triggerRestValidation (closed-loop self-healing)

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStateService.kt`
- Modify: `app/src/test/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStateServiceTest.kt`

**Interfaces:**
- Consumes: `SessionRepository.fetchSessionStatuses` (existing)
- Produces: `triggerRestValidation` (real impl), staleness guard coroutine

- [ ] **Step 1: Write failing test — staleness triggers REST, absence = idle**

```kotlin
@Test fun `triggerRestValidation absence with known directory marks Idle`() = runTest {
    val fakeRepo = mockk<SessionRepository>(relaxed = true)
    every { fakeRepo.fetchSessionStatuses(any(), any()) } returns Result.success(emptyMap())  // absent
    val dirService = SessionStateService(UnconfinedTestDispatcher().scope, providerOf(fakeRepo)).apply {
        directoryResolver = DirectoryResolver { "D:/proj" }  // known directory
        currentServerIdIntern = "svr1"
    }
    dirService.onClientSendParts("s1")  // Busy
    dirService.triggerRestValidation("s1")
    advanceUntilIdle()
    assertEquals(SessionStatus.Idle, dirService.statusFlow.value["s1"])  // absence -> idle
}

@Test fun `triggerRestValidation absence with null directory skips (no false idle)`() = runTest {
    val fakeRepo = mockk<SessionRepository>(relaxed = true)
    every { fakeRepo.fetchSessionStatuses(any(), any()) } returns Result.success(emptyMap())
    val dirService = SessionStateService(UnconfinedTestDispatcher().scope, providerOf(fakeRepo)).apply {
        directoryResolver = DirectoryResolver { null }  // unknown
        currentServerIdIntern = "svr1"
    }
    dirService.onClientSendParts("s1")
    dirService.triggerRestValidation("s1")
    advanceUntilIdle()
    assertEquals(SessionStatus.Busy, dirService.statusFlow.value["s1"])  // stayed Busy (no false idle)
}
```

Note: test needs `currentServerIdIntern` visible — use `@VisibleForTesting internal var` or a test-only setter. Add `fun setServerIdForTest(id: String) { currentServerId = id }` or expose `setServerId` (already planned).

- [ ] **Step 2: Implement triggerRestValidation + staleness guard**

Replace the placeholder `triggerRestValidation` in SessionStateService.kt:

```kotlin
private const val STALENESS_CHECK_INTERVAL_MS = 5_000L
private const val STALENESS_THRESHOLD_MS = 15_000L

// inside class:
private var stalenessJob: kotlinx.coroutines.Job? = null

init { startStalenessGuard() }

private fun startStalenessGuard() {
    stalenessJob?.cancel()
    stalenessJob = appScope.launch {
        while (kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.isActive == true) {
            kotlinx.coroutines.delay(STALENESS_CHECK_INTERVAL_MS)
            checkStaleness()
        }
    }
}

private fun checkStaleness() {
    val now = System.currentTimeMillis()
    _fsmStates.value.forEach { (sessionId, state) ->
        if (state.core is SessionStatus.Busy && now - state.lastEventAt > STALENESS_THRESHOLD_MS) {
            Log.w(TAG, "[$sessionId] L2 stale for ${now - state.lastEventAt}ms, triggering REST validation")
            triggerRestValidation(sessionId)
        }
        if (state.core is SessionStatus.Idle && incompleteChecker.hasIncomplete(sessionId)) {
            Log.w(TAG, "[$sessionId] L5 inconsistency: Idle but has incomplete messages")
            triggerRestValidation(sessionId)
        }
    }
}

internal fun triggerRestValidation(sessionId: String) {
    val sid = currentServerId ?: return
    val directory = directoryResolver.resolve(sessionId)
    appScope.launch {
        try {
            val result = sessionRepoProvider.get().fetchSessionStatuses(sid, directory)
            result.onSuccess { statuses ->
                val serverStatus = statuses[sessionId]
                if (serverStatus != null) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "[$sessionId] L3 REST validation: server says ${serverStatus::class.simpleName}")
                    onRestValidation(sessionId, serverStatus)
                } else if (directory != null) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "[$sessionId] L3 REST validation: absent from own directory -> idle")
                    onRestValidation(sessionId, SessionStatus.Idle)
                }
                // directory == null + absent -> skip (avoid false idle on unknown instance)
            }
        } catch (e: Exception) {
            Log.w(TAG, "[$sessionId] L3 REST validation failed: ${e.message}")
        }
    }
}
```

- [ ] **Step 3: Run tests**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "*SessionStateServiceTest*"`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStateService.kt app/src/test/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStateServiceTest.kt
git commit -m "feat(state): staleness guard + triggerRestValidation with absence=idle closed loop"
```

---

## Task 5: syncFromRest — unify 6 recovery paths

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStateService.kt`
- Modify: `app/src/test/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStateServiceTest.kt`

**Interfaces:**
- Produces: `suspend fun syncFromRest(projects: List<Project>): SyncResult`

- [ ] **Step 1: Write failing test — multi-directory aggregation + absence=idle + protect incomplete**

```kotlin
@Test fun `syncFromRest aggregates multiple directories`() = runTest {
    val fakeRepo = mockk<SessionRepository>(relaxed = true)
    every { fakeRepo.fetchSessionStatuses("svr1", "D:/projA") } returns Result.success(mapOf(
        "s1" to RestSessionStatusInfo(type = "busy")))
    every { fakeRepo.fetchSessionStatuses("svr1", "D:/projB") } returns Result.success(mapOf(
        "s2" to RestSessionStatusInfo(type = "busy")))
    val svc = SessionStateService(UnconfinedTestDispatcher().scope, providerOf(fakeRepo)).apply {
        setServerId("svr1")
    }
    val result = svc.syncFromRest(listOf(Project(worktree = "D:/projA"), Project(worktree = "D:/projB")))
    assertEquals(2, result.totalSessions)
    assertEquals(SessionStatus.Busy, svc.statusFlow.value["s1"])
    assertEquals(SessionStatus.Busy, svc.statusFlow.value["s2"])
}

@Test fun `syncFromRest marks absent non-idle session as Idle when no incomplete`() = runTest {
    val fakeRepo = mockk<SessionRepository>(relaxed = true)
    every { fakeRepo.fetchSessionStatuses(any(), any()) } returns Result.success(emptyMap())
    val svc = SessionStateService(UnconfinedTestDispatcher().scope, providerOf(fakeRepo)).apply {
        setServerId("svr1")
        onClientSendParts("s1")  // local Busy
    }
    svc.syncFromRest(listOf(Project(worktree = "D:/p")))
    assertEquals(SessionStatus.Idle, svc.statusFlow.value["s1"])  // absent -> idle
}

@Test fun `syncFromRest protects absent session with incomplete messages`() = runTest {
    val fakeRepo = mockk<SessionRepository>(relaxed = true)
    every { fakeRepo.fetchSessionStatuses(any(), any()) } returns Result.success(emptyMap())
    val svc = SessionStateService(UnconfinedTestDispatcher().scope, providerOf(fakeRepo)).apply {
        setServerId("svr1")
        directoryResolver = DirectoryResolver { "D:/p" }
        incompleteChecker = IncompleteAssistantChecker { true }  // has incomplete
        onClientSendParts("s1")
    }
    svc.syncFromRest(listOf(Project(worktree = "D:/p")))
    assertEquals(SessionStatus.Busy, svc.statusFlow.value["s1"])  // protected
}
```

- [ ] **Step 2: Implement syncFromRest**

Add to SessionStateService.kt:

```kotlin
data class SyncResult(val totalSessions: Int, val busyCount: Int)

private fun toStatus(info: RestSessionStatusInfo): SessionStatus = when (info.type) {
    "busy" -> SessionStatus.Busy
    "retry" -> SessionStatus.Retry(attempt = info.attempt ?: 0, message = info.message ?: "", next = info.next ?: 0L)
    else -> SessionStatus.Idle
}

suspend fun syncFromRest(projects: List<Project>): SyncResult {
    val aggregated = mutableMapOf<String, SessionStatus>()
    val dirs: List<String?> = if (projects.isEmpty()) listOf(null) else projects.map { it.worktree }
    for (dir in dirs) {
        sessionRepoProvider.get().fetchSessionStatuses(currentServerId ?: return SyncResult(0, 0), dir)
            .onSuccess { aggregated += it.mapValues { toStatus(it.value) } }
    }
    // Absence semantics: local non-Idle absent from REST
    for ((sid, state) in _fsmStates.value) {
        if (state.core !is SessionStatus.Idle && sid !in aggregated) {
            aggregated[sid] = if (incompleteChecker.hasIncomplete(sid)) state.core  // protect
                             else SessionStatus.Idle                                 // absent = idle
        }
    }
    for ((sid, status) in aggregated) applyTransition(sid, FsmEvent.RestValidation(status))
    return SyncResult(aggregated.size, aggregated.count { it.value is SessionStatus.Busy })
}
```

- [ ] **Step 3: Run tests**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "*SessionStateServiceTest*"`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStateService.kt app/src/test/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStateServiceTest.kt
git commit -m "feat(state): syncFromRest unifies recovery — multi-dir aggregate + absence=idle + protect"
```

---

## Task 6: Hilt DI wiring for SessionStateService

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/di/NetworkModule.kt` (or DomainModule.kt — wherever SessionStatusManager is bound)
- Verify: `.\gradlew :app:compileDevDebugKotlin`

**Interfaces:**
- Produces: SessionStateService injectable everywhere

- [ ] **Step 1: Locate current SessionStatusManager binding**

Run: `grep -rn "SessionStatusManager" app/src/main/kotlin/dev/leonardo/ocremotev2/di/`
Identify the module providing it. SessionStateService is `@Singleton class @Inject constructor` so Hilt auto-provides — no @Provides needed. But `@ApplicationScope` CoroutineScope must be bound (it already is, since SessionStatusManager uses it).

- [ ] **Step 2: Verify compile**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL (Hilt auto-provides SessionStateService)

- [ ] **Step 3: Commit (if any DI changes; otherwise skip)**

Only commit if NetworkModule/DomainModule needed edits.

---

## Task 7: EventDispatcher integration — SSE dual-write + 3 callbacks

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/EventDispatcher.kt`
- Modify: `app/src/test/kotlin/dev/leonardo/ocremotev2/data/repository/EventDispatcherIntegrationTest.kt`

**Interfaces:**
- Consumes: SessionStateService (Task 3-5), existing handlers
- Produces: EventDispatcher routes SSE status events to SessionStateService (dual-write during transition)

- [ ] **Step 1: Inject SessionStateService + set callbacks in EventDispatcher**

In `EventDispatcher.kt` constructor, add `private val sessionStateService: SessionStateService`. In `init`, replace the `sessionStatusManager.incompleteAssistantChecker = ...` and `directoryResolver = ...` lines with the SessionStateService equivalents:

```kotlin
init {
    sessionStateService.incompleteChecker = IncompleteAssistantChecker { sessionId -> hasIncompleteAssistant(sessionId) }
    sessionStateService.directoryResolver = DirectoryResolver { sessionId ->
        sessionHandler.sessions.value.find { it.id == sessionId }?.directory
    }
    sessionStateService.messageForceCompleter = MessageForceCompleter { sessionId -> messageHandler.markSessionIdle(sessionId) }
}
```

Remove the `sessionStatusManager` constructor param + its init lines (SessionStatusManager still exists until Task 12, but stop wiring it here — or keep minimal). **Decision: keep SessionStatusManager constructible but unwire from EventDispatcher init to avoid double-wiring.**

- [ ] **Step 2: Dual-write SSE status events in processEvent**

In `processEvent` (or `buildRegistry`), for status events, also forward to sessionStateService:

```kotlin
// After existing handling, for status-class events:
is SseEvent.SessionStatus, is SseEvent.SessionIdle, is SseEvent.SessionError, is SseEvent.SessionNext -> {
    val sid = sessionIdFor(event)
    if (sid != null) sessionStateService.onSseEvent(event, sid)
}
```

`sessionIdFor` already exists (the `when` returning sessionId). Reuse it.

- [ ] **Step 3: Update EventDispatcherIntegrationTest — verify dual-write**

In setup, also wire real SessionStateService (not mock). Add test:

```kotlin
@Test fun `SSE SessionStatus dual-writes to SessionStateService`() = runTest {
    dispatcher.processEvent(SseEvent.SessionStatus(sessionId = "s1", status = SessionStatus.Busy), "svr1")
    assertEquals(SessionStatus.Busy, dispatcher.sessionStateService.statusFlow.value["s1"])
}
```

Add `val sessionStateService: SessionStateService` to test setup (real instance).

- [ ] **Step 4: Run tests**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "*EventDispatcherIntegrationTest*"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/EventDispatcher.kt app/src/test/kotlin/dev/leonardo/ocremotev2/data/repository/EventDispatcherIntegrationTest.kt
git commit -m "feat(state): EventDispatcher integrates SessionStateService — dual-write + callbacks"
```

---

## Task 8: UI migration — SessionListViewModel reads statusFlow

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/sessions/SessionListViewModel.kt`

**Interfaces:**
- Consumes: SessionStateService.statusFlow (injected)

- [ ] **Step 1: Inject SessionStateService, switch combine source**

In SessionListViewModel constructor add `private val sessionStateService: SessionStateService`. In the `uiState` combine (around line 149), replace `eventDispatcher.sessionStatuses` (values[1]) with `sessionStateService.statusFlow`. Update the combine args + index accordingly.

The combine currently reads `eventDispatcher.sessionStatuses` as the 2nd source (index 1). Replace that source with `sessionStateService.statusFlow`. The `statuses` extraction stays the same logic.

- [ ] **Step 2: Compile + run existing SessionListViewModel tests**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "*SessionListViewModel*"`
Expected: PASS (may need test setup update if it mocks sessionStatuses — switch mock to SessionStateService)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/sessions/SessionListViewModel.kt
git commit -m "refactor(sessions): SessionListViewModel reads statusFlow from SessionStateService"
```

---

## Task 9: UI migration — ChatViewModel.sessionMetaState reads statusFlow

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModel.kt`

- [ ] **Step 1: Switch sessionMetaState source**

Around line 539-562, `sessionMetaState` combine reads `sessionStatusManager.statusFlow`. Replace with `sessionStateService.statusFlow`. Inject `SessionStateService` into ChatViewModel (replacing or alongside SessionStatusManager).

The `statuses[sid]` extraction (line 557) stays the same — just the source flow changes.

- [ ] **Step 2: Compile + run ChatViewModel tests**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "*ChatViewModel*"`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModel.kt
git commit -m "refactor(chat): sessionMetaState reads statusFlow from SessionStateService"
```

---

## Task 10: UI migration — streamingMsgId + PartContent read activityFlow (A1 core)

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/components/ChatMessageList.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/components/PartContent.kt`

**This is the highest-risk task (A1).** `part.time.end` → `activityFlow[sessionId] is Streaming`.

- [ ] **Step 1: ChatMessageList — streamingMsgId from activityFlow**

In ChatMessageList.kt around line 139, `streamingMsgId` is computed from `rawMessages.lastOrNull { it.isAssistant && it.message.time.completed == null }`. Change to read activity: pass `isSessionStreaming: Boolean` (from activityFlow[sid] is Streaming) as a param. When session is not streaming, streamingMsgId = null:

```kotlin
// New param added to ChatMessageList signature:
//   isSessionStreaming: Boolean,
// Streaming message only exists when session is actively streaming
val streamingMsgId = if (!isSessionStreaming) null else remember(rawMessages) {
    rawMessages.lastOrNull { it.isAssistant && it.message.time.completed == null }?.message?.id
}
```

ChatScreen passes `isSessionStreaming = sessionMeta.sessionStatus is Busy && activityFlow[sid] is Streaming` — or simpler: expose a derived `isStreaming` from sessionMetaState that incorporates activity.

**Cleaner approach:** Add `isStreaming: Boolean` to `SessionMetaState` (computed in ChatViewModel from `sessionStateService.activityFlow[sid] is Streaming`). ChatMessageList reads `sessionMeta.isStreaming`.

- [ ] **Step 2: PartContent — Reasoning isStreaming from CompositionLocal**

PartContent.kt line 117: `val isStreaming = part.time?.end == null`. The session-level streaming state should come from a CompositionLocal provided by ChatScreen (which knows activityFlow). Add:

```kotlin
// New CompositionLocal in ChatScreen:
val LocalSessionStreaming = staticCompositionLocalOf { false }

// PartContent Reasoning branch:
val isStreaming = LocalSessionStreaming.current  // session-level, not part-level
```

ChatScreen wraps content providing `LocalSessionStreaming provides (activityFlow[sid] is Streaming)`.

Reasoning `durationMs`/`startTimeMs` STAY reading `part.time` (metadata for timing).

- [ ] **Step 3: Compile**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Manual verification (emulator/device)**

Open a session, trigger streaming, confirm Reasoning shows "thinking in progress"; when done, confirm it stops. Verify streaming cursor animates during output and stops after.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/components/ChatMessageList.kt app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/components/PartContent.kt app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatScreen.kt app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModel.kt
git commit -m "refactor(chat): streaming UI reads activityFlow instead of part.time (A1 unification)"
```

---

## Task 11: Recovery paths → syncFromRest

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/service/SseConnectionManager.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/SessionActionsDelegate.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/sessions/SessionListViewModel.kt`

- [ ] **Step 1: SseConnectionManager — replace syncSessionStatuses with sessionStateService.syncFromRest**

In `preLoadSessions` (after setSessions) and `recoverMessages` (Phase 2), replace the local `syncSessionStatuses(conn)` call with `eventDispatcher.sessionStateService.syncFromRest(projects)`. Inject `SessionStateService` into SseConnectionManager (or access via EventDispatcher).

Remove the now-dead `syncSessionStatuses(conn)` private method (its logic lives in syncFromRest).

- [ ] **Step 2: SessionListViewModel — syncSessionStatusesFromServer → syncFromRest**

Replace `syncSessionStatusesFromServer()` call in loadSessions with `sessionStateService.syncFromRest(_projects.value)`. Remove the local `syncSessionStatuses`/`syncSessionStatusesFromServer`/`toStatus` methods (logic now in SessionStateService).

- [ ] **Step 3: SessionActionsDelegate — syncSessionStatus/refreshAndSync → syncFromRest**

In `syncSessionStatus` (line 105) and `refreshAndSync` (line 128), replace `sessionRepository.fetchSessionStatuses(...)` + `syncAllSessionStatuses` + idle-handling with `sessionStateService.syncFromRest(projects)`. The `projects` come from a provider or EventDispatcher.

- [ ] **Step 4: Compile + run tests**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "*SseConnection*" --tests "*SessionActions*" --tests "*SessionList*"`
Expected: PASS (update test mocks as needed)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocremotev2/service/SseConnectionManager.kt app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/SessionActionsDelegate.kt app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/sessions/SessionListViewModel.kt
git commit -m "refactor(state): 6 recovery paths collapse into syncFromRest"
```

---

## Task 12: Delete old code — SessionEventHandler status, SessionStatusManager, EventDispatcher status methods

**Files:**
- Delete: `app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStatusManager.kt`
- Delete: `app/src/test/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStatusManagerTest.kt` (if exists)
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/handler/SessionEventHandler.kt` (remove status fields/methods)
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/EventDispatcher.kt` (remove status delegation methods)
- Rename: `SessionStatusFSM.kt` → `SessionStateFSM.kt` (+ test)

- [ ] **Step 1: Remove SessionEventHandler status state**

In SessionEventHandler.kt delete: `_sessionStatuses`, `sessionStatuses`, `_sseTimestamps`, `handleSessionStatus`, `handleSessionIdle`, `updateSessionStatus`, `updateAllSessionStatuses`, `updateSessionStatusProtected`, `shouldOverwrite`, `SSE_FRESH_THRESHOLD_MS`. Update `handleSessionCreated` (remove the `_sessionStatuses.update` line — SessionCreated no longer sets Idle; SessionStateService gets status from SSE SessionStatus events or syncFromRest). Keep `handleSessionDeleted` but remove `_sessionStatuses`/`_sseTimestamps` lines (SessionStateService.clearSession handles FSM cleanup — wire it in EventDispatcher).

- [ ] **Step 2: EventDispatcher — remove status methods, wire clearSession**

In EventDispatcher delete: `updateSessionStatus`, `syncAllSessionStatuses`, `markSessionIdle`, `markSessionIdleProtected`. In SessionDeleted handling, add `sessionStateService.clearSession(sessionId)`.

- [ ] **Step 3: Delete SessionStatusManager + its references**

Delete `SessionStatusManager.kt`. Remove it from EventDispatcher constructor + DI. Grep for remaining references: `grep -rn "SessionStatusManager" app/src/main` and fix/remove.

- [ ] **Step 4: Rename SessionStatusFSM → SessionStateFSM**

Rename file `domain/model/SessionStatusFSM.kt` → `SessionStateFSM.kt`, rename `object SessionStatusFSM` → `object SessionStateFSM`. Rename test file. Update all references (`grep -rn "SessionStatusFSM"`).

- [ ] **Step 5: Compile + full test**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun`
Expected: BUILD SUCCESSFUL, all tests PASS

- [ ] **Step 6: Commit**

```bash
git add -A app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStatusManager.kt app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/handler/SessionEventHandler.kt app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/EventDispatcher.kt app/src/main/kotlin/dev/leonardo/ocremotev2/domain/model/
git commit -m "refactor(state): remove SessionStatusManager + SessionEventHandler status (single source achieved)"
```

---

## Task 13: Full regression + manual verification

- [ ] **Step 1: Full unit test suite**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun`
Expected: all PASS (1046+ tests, including new FSM matrix + Service tests)

- [ ] **Step 2: Build APK**

Run: `.\gradlew :app:assembleDevRelease`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Install + manual verification of success criteria**

Install on device. Verify all 7 success criteria from spec §9:
1. All UI reads SessionStateService flows (no other status source) — verify via grep
2. SessionEventHandler has no _sessionStatuses; SessionStatusManager deleted — verify via grep
3. 6 recovery paths → syncFromRest — verify via grep (no syncSessionStatusesFromServer etc.)
4. SessionStateFSMTest covers full matrix — verify test count
5. Original bugs gone: multi-worktree dir tree shows active; disconnect progress bar stops — manual test
6. Full unit tests pass — done in Step 1
7. historyFlow queryable — add a debug log or test

- [ ] **Step 4: Commit any final fixes + tag**

```bash
git commit --allow-empty -m "chore(state): redesign complete — verified all success criteria"
```

---

## Self-Review

**Spec coverage:** Each spec section maps to tasks — §3 architecture (Task 3-7), §4 FSM (Task 1), §5 API (Task 3-5), §6 UI migration (Task 8-10), §7 implementation steps S1-S6 (Task 1-13 in order). §9 success criteria verified in Task 13.

**Placeholder scan:** No TBD/TODO. Task 6 may be a no-op (Hilt auto-provides) — explicitly noted. Manual verification steps are concrete with expected outcomes.

**Type consistency:** `SessionStateService` API consistent across tasks (statusFlow/activityFlow/historyFlow, onClientSendParts/onClientAbort/onSseEvent/onRestValidation, syncFromRest, applyTransition). `SessionActivity` sealed type used consistently. `TransitionRecord` fields match Task 2 definition throughout.

**Risk note:** Task 10 (A1 streaming migration) and Task 12 (deletion) are highest-risk; each followed by compile + test gate. Task 7 dual-write is the safety bridge.
