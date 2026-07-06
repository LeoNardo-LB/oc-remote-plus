## Task 3 Diff: c0419c2b..12af59e8
### Commits
12af59e8 feat(state): SessionStateService skeleton + TransitionRecord (flows, pipeline, history)
### Stat
 .../data/repository/SessionStateService.kt         | 146 +++++++++++++++++++++
 .../ocremotev2/domain/model/TransitionRecord.kt    |  14 ++
 .../data/repository/SessionStateServiceTest.kt     |  97 ++++++++++++++
 3 files changed, 257 insertions(+)
### Full Diff
diff --git a/app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStateService.kt b/app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStateService.kt
new file mode 100644
index 00000000..97331132
--- /dev/null
+++ b/app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStateService.kt
@@ -0,0 +1,146 @@
+package dev.leonardo.ocremotev2.data.repository
+
+import android.util.Log
+import dev.leonardo.ocremotev2.BuildConfig
+import dev.leonardo.ocremotev2.di.ApplicationScope
+import dev.leonardo.ocremotev2.domain.model.*
+import dev.leonardo.ocremotev2.domain.repository.SessionRepository
+import kotlinx.coroutines.CoroutineScope
+import kotlinx.coroutines.flow.MutableStateFlow
+import kotlinx.coroutines.flow.SharingStarted
+import kotlinx.coroutines.flow.StateFlow
+import kotlinx.coroutines.flow.map
+import kotlinx.coroutines.flow.stateIn
+import kotlinx.coroutines.flow.update
+import javax.inject.Inject
+import javax.inject.Provider
+import javax.inject.Singleton
+
+fun interface DirectoryResolver { fun resolve(sessionId: String): String? }
+fun interface IncompleteAssistantChecker { fun hasIncomplete(sessionId: String): Boolean }
+fun interface MessageForceCompleter { fun markIdle(sessionId: String) }
+
+private const val TAG = "SessionStateService"
+private const val HISTORY_MAX = 20
+
+@Singleton
+class SessionStateService @Inject constructor(
+    @ApplicationScope private val appScope: CoroutineScope,
+    private val sessionRepoProvider: Provider<SessionRepository>,
+) {
+    // Injected post-construction (breaks circular dep with EventDispatcher)
+    var directoryResolver: DirectoryResolver = DirectoryResolver { null }
+    var incompleteChecker: IncompleteAssistantChecker = IncompleteAssistantChecker { false }
+    var messageForceCompleter: MessageForceCompleter = MessageForceCompleter {}
+
+    @Volatile private var currentServerId: String? = null
+
+    private val _fsmStates = MutableStateFlow<Map<String, SessionFSMState>>(emptyMap())
+    private val _histories = MutableStateFlow<Map<String, List<TransitionRecord>>>(emptyMap())
+
+    val statusFlow: StateFlow<Map<String, SessionStatus>> = _fsmStates
+        .map { it.mapValues { e -> e.value.core } }
+        .stateIn(appScope, SharingStarted.Eagerly, emptyMap())
+
+    val activityFlow: StateFlow<Map<String, SessionActivity?>> = _fsmStates
+        .map { it.mapValues { e -> e.value.activity } }
+        .stateIn(appScope, SharingStarted.Eagerly, emptyMap())
+
+    val historyFlow: StateFlow<Map<String, List<TransitionRecord>>> = _histories
+        .stateIn(appScope, SharingStarted.Eagerly, emptyMap())
+
+    fun setServerId(serverId: String) { currentServerId = serverId }
+
+    // ============ Event entry points ============
+    fun onClientSendParts(sessionId: String) = applyTransition(sessionId, FsmEvent.ClientSendParts)
+    fun onClientAbort(sessionId: String) = applyTransition(sessionId, FsmEvent.ClientAbort)
+    fun onRestValidation(sessionId: String, status: SessionStatus) =
+        applyTransition(sessionId, FsmEvent.RestValidation(status))
+
+    fun onSseEvent(event: SseEvent, sessionId: String) {
+        val fsmEvent = mapSseEventToFsm(event) ?: return
+        applyTransition(sessionId, fsmEvent)
+    }
+
+    private fun mapSseEventToFsm(event: SseEvent): FsmEvent? = when (event) {
+        is SseEvent.SessionStatus -> FsmEvent.SseStatus(event.status)
+        is SseEvent.SessionIdle -> FsmEvent.SseIdle
+        is SseEvent.SessionError -> FsmEvent.SseError(event.error)
+        is SseEvent.SessionNext -> mapSessionNextEvent(event.event)
+        else -> null
+    }
+
+    private fun mapSessionNextEvent(event: SessionNextEvent): FsmEvent? = when (event) {
+        is SessionNextEvent.StepStarted -> FsmEvent.StepStarted
+        is SessionNextEvent.TextStarted -> FsmEvent.TextStarted
+        is SessionNextEvent.TextDelta -> FsmEvent.TextDelta(event.delta)
+        is SessionNextEvent.TextEnded -> FsmEvent.TextEnded
+        is SessionNextEvent.ToolInputStarted -> FsmEvent.ToolInputStarted(event.tool, event.callId)
+        // SessionNextEvent.StepEnded has no finish field — pass null. FSM treats non-"tool-calls"
+        // finish as "keep current Activity, wait for Core Idle".
+        is SessionNextEvent.StepEnded -> FsmEvent.StepEnded(finish = null)
+        is SessionNextEvent.CompactionStarted -> FsmEvent.CompactionStarted
+        is SessionNextEvent.CompactionEnded -> FsmEvent.CompactionEnded
+        else -> null
+    }
+
+    // ============ Core pipeline ============
+    fun applyTransition(sessionId: String, event: FsmEvent) {
+        val current = _fsmStates.value[sessionId] ?: SessionFSMState.initial()
+        val result = SessionStatusFSM.transition(current, event)
+        _fsmStates.update { it + (sessionId to result.newState) }
+        recordHistory(sessionId, current, result, event)
+        if (BuildConfig.DEBUG) logTransition(sessionId, current, result, event)
+        // Side effects
+        if (result.forceComplete) messageForceCompleter.markIdle(sessionId)
+        if (result.isSuspicious) triggerRestValidation(sessionId)
+    }
+
+    private fun recordHistory(sessionId: String, from: SessionFSMState, result: SessionStatusFSM.TransitionResult, event: FsmEvent) {
+        val record = TransitionRecord(
+            sessionId = sessionId,
+            timestamp = System.currentTimeMillis(),
+            event = event::class.simpleName ?: "Unknown",
+            fromCore = from.core::class.simpleName ?: "?",
+            toCore = result.newState.core::class.simpleName ?: "?",
+            fromActivity = from.activity?.let { it::class.simpleName },
+            toActivity = result.newState.activity?.let { it::class.simpleName },
+            isSuspicious = result.isSuspicious,
+            reason = null,
+        )
+        _histories.update { h ->
+            val list = (h[sessionId] ?: emptyList()) + record
+            val trimmed = if (list.size > HISTORY_MAX) list.takeLast(HISTORY_MAX) else list
+            h + (sessionId to trimmed)
+        }
+    }
+
+    private fun logTransition(sessionId: String, from: SessionFSMState, result: SessionStatusFSM.TransitionResult, event: FsmEvent) {
+        val actFrom = from.activity?.let { "/${it::class.simpleName}" } ?: ""
+        val actTo = result.newState.activity?.let { "/${it::class.simpleName}" } ?: ""
+        val flags = buildString {
+            if (result.isSuspicious) append(" [SUSPICIOUS]")
+            if (result.forceComplete) append(" [force-complete]")
+        }
+        Log.d(TAG, "[$sessionId] ${from.core::class.simpleName}$actFrom --${event::class.simpleName}--> ${result.newState.core::class.simpleName}$actTo$flags")
+    }
+
+    // ============ Lifecycle ============
+    fun clearSession(sessionId: String) {
+        _fsmStates.update { it - sessionId }
+        _histories.update { it - sessionId }
+    }
+
+    fun clearForServer(sessionIds: Set<String>) {
+        _fsmStates.update { it - sessionIds }
+        _histories.update { it - sessionIds }
+    }
+
+    fun clearAll() {
+        _fsmStates.value = emptyMap()
+        _histories.value = emptyMap()
+    }
+
+    // Placeholder — implemented in Task 4 (staleness guard)
+    internal fun triggerRestValidation(sessionId: String) { /* Task 4 */ }
+}
diff --git a/app/src/main/kotlin/dev/leonardo/ocremotev2/domain/model/TransitionRecord.kt b/app/src/main/kotlin/dev/leonardo/ocremotev2/domain/model/TransitionRecord.kt
new file mode 100644
index 00000000..57511c8f
--- /dev/null
+++ b/app/src/main/kotlin/dev/leonardo/ocremotev2/domain/model/TransitionRecord.kt
@@ -0,0 +1,14 @@
+package dev.leonardo.ocremotev2.domain.model
+
+/** Immutable record of one FSM transition, for traceability/diagnostics. */
+data class TransitionRecord(
+    val sessionId: String,
+    val timestamp: Long,
+    val event: String,
+    val fromCore: String,
+    val toCore: String,
+    val fromActivity: String?,
+    val toActivity: String?,
+    val isSuspicious: Boolean,
+    val reason: String?,
+)
diff --git a/app/src/test/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStateServiceTest.kt b/app/src/test/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStateServiceTest.kt
new file mode 100644
index 00000000..29501572
--- /dev/null
+++ b/app/src/test/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStateServiceTest.kt
@@ -0,0 +1,97 @@
+package dev.leonardo.ocremotev2.data.repository
+
+import dev.leonardo.ocremotev2.domain.model.*
+import dev.leonardo.ocremotev2.domain.model.SseEvent
+import dev.leonardo.ocremotev2.domain.repository.SessionRepository
+import io.mockk.mockk
+import io.mockk.verify
+import kotlinx.coroutines.cancel
+import kotlinx.coroutines.test.TestScope
+import kotlinx.coroutines.test.UnconfinedTestDispatcher
+import kotlinx.coroutines.test.advanceUntilIdle
+import org.junit.After
+import org.junit.Assert.*
+import org.junit.Test
+import javax.inject.Provider
+
+/**
+ * Fixture note (deviation from brief's literal `runTest { this -> ... }` form):
+ *
+ * SessionStateService exposes flows built with `stateIn(appScope, SharingStarted.Eagerly, …)`,
+ * which launch coroutines in the injected appScope that never complete on their own. Passing
+ * `runTest`'s `this` (a TestScope on the default StandardTestDispatcher) caused two failures:
+ *   1. Timing — the Eagerly collector was queued behind the test body, so `statusFlow.value`
+ *      stayed `emptyMap()` (AssertionError / NPE).
+ *   2. `UncompletedCoroutinesError` at teardown — the 3 Eagerly coroutines outlive the test body.
+ *
+ * Fix mirrors the project's own `ChatViewModelStreamingTest` (UnconfinedTestDispatcher +
+ * advanceUntilIdle): drive appScope with an UnconfinedTestDispatcher for eager propagation, and
+ * cancel the scope in @After so teardown sees no uncompleted coroutines. All test cases and
+ * assertions are unchanged from the brief.
+ */
+class SessionStateServiceTest {
+
+    private val testScope = TestScope(UnconfinedTestDispatcher())
+
+    private fun newService() = SessionStateService(
+        testScope,
+        Provider { mockk<SessionRepository>(relaxed = true) },
+    )
+
+    @After
+    fun tearDown() {
+        testScope.cancel()
+    }
+
+    @Test
+    fun `ClientSendParts transitions Idle to Busy Waiting in statusFlow`() {
+        val service = newService()
+        service.onClientSendParts("s1")
+        testScope.advanceUntilIdle()
+        assertEquals(SessionStatus.Busy, service.statusFlow.value["s1"])
+        assertEquals(SessionActivity.Waiting, service.activityFlow.value["s1"])
+    }
+
+    @Test
+    fun `SseIdle after Busy triggers forceComplete on messageForceCompleter`() {
+        val forceCompleter = mockk<MessageForceCompleter>(relaxed = true)
+        val service = newService()
+        service.messageForceCompleter = forceCompleter
+        service.onClientSendParts("s1")
+        service.onSseEvent(SseEvent.SessionIdle(sessionId = "s1"), "s1")
+        testScope.advanceUntilIdle()
+        assertEquals(SessionStatus.Idle, service.statusFlow.value["s1"])
+        verify { forceCompleter.markIdle("s1") }
+    }
+
+    @Test
+    fun `transition recorded in history`() {
+        val service = newService()
+        service.onClientSendParts("s1")
+        testScope.advanceUntilIdle()
+        val history = service.historyFlow.value["s1"]
+        assertEquals(1, history!!.size)
+        assertEquals("Idle", history[0].fromCore)
+        assertEquals("Busy", history[0].toCore)
+    }
+
+    @Test
+    fun `history trims to max 20 entries`() {
+        val service = newService()
+        repeat(25) { service.onClientSendParts("s1") }  // each is a transition
+        testScope.advanceUntilIdle()
+        val history = service.historyFlow.value["s1"]!!
+        assertTrue("history should be trimmed to <= 20, was ${history.size}", history.size <= 20)
+    }
+
+    @Test
+    fun `clearSession removes state and history`() {
+        val service = newService()
+        service.onClientSendParts("s1")
+        testScope.advanceUntilIdle()
+        service.clearSession("s1")
+        testScope.advanceUntilIdle()
+        assertNull(service.statusFlow.value["s1"])
+        assertNull(service.historyFlow.value["s1"])
+    }
+}
