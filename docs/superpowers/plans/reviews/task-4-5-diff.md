## Task 4+5 Diff: 12af59e8..0dd08b2e
### Commits
0dd08b2e feat(state): staleness guard + triggerRestValidation absence=idle + syncFromRest unify recovery
### Stat
 .../data/repository/SessionStateService.kt         | 102 ++++++++++++++++++-
 .../data/repository/SessionStateServiceTest.kt     | 109 +++++++++++++++++++--
 2 files changed, 202 insertions(+), 9 deletions(-)
### Full Diff
diff --git a/app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStateService.kt b/app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStateService.kt
index 97331132..e71d732d 100644
--- a/app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStateService.kt
+++ b/app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStateService.kt
@@ -1,45 +1,82 @@
 package dev.leonardo.ocremotev2.data.repository
 
 import android.util.Log
 import dev.leonardo.ocremotev2.BuildConfig
 import dev.leonardo.ocremotev2.di.ApplicationScope
 import dev.leonardo.ocremotev2.domain.model.*
 import dev.leonardo.ocremotev2.domain.repository.SessionRepository
 import kotlinx.coroutines.CoroutineScope
+import kotlinx.coroutines.Job
+import kotlinx.coroutines.delay
+import kotlinx.coroutines.isActive
 import kotlinx.coroutines.flow.MutableStateFlow
 import kotlinx.coroutines.flow.SharingStarted
 import kotlinx.coroutines.flow.StateFlow
 import kotlinx.coroutines.flow.map
 import kotlinx.coroutines.flow.stateIn
 import kotlinx.coroutines.flow.update
+import kotlinx.coroutines.launch
 import javax.inject.Inject
 import javax.inject.Provider
 import javax.inject.Singleton
 
 fun interface DirectoryResolver { fun resolve(sessionId: String): String? }
 fun interface IncompleteAssistantChecker { fun hasIncomplete(sessionId: String): Boolean }
 fun interface MessageForceCompleter { fun markIdle(sessionId: String) }
 
 private const val TAG = "SessionStateService"
 private const val HISTORY_MAX = 20
+private const val STALENESS_CHECK_INTERVAL_MS = 5_000L
+private const val STALENESS_THRESHOLD_MS = 15_000L
+
+/** Result of a full REST → FSM sync, exposed for callers (e.g. manual refresh) to observe. */
+data class SyncResult(val totalSessions: Int, val busyCount: Int)
 
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
 
+    private var stalenessJob: Job? = null
+
+    init { startStalenessGuard() }
+
+    private fun startStalenessGuard() {
+        stalenessJob?.cancel()
+        stalenessJob = appScope.launch {
+            while (isActive) {
+                delay(STALENESS_CHECK_INTERVAL_MS)
+                checkStaleness()
+            }
+        }
+    }
+
+    private fun checkStaleness() {
+        val now = System.currentTimeMillis()
+        _fsmStates.value.forEach { (sessionId, state) ->
+            if (state.core is SessionStatus.Busy && now - state.lastEventAt > STALENESS_THRESHOLD_MS) {
+                Log.w(TAG, "[$sessionId] L2 stale for ${now - state.lastEventAt}ms, triggering REST validation")
+                triggerRestValidation(sessionId)
+            }
+            if (state.core is SessionStatus.Idle && incompleteChecker.hasIncomplete(sessionId)) {
+                Log.w(TAG, "[$sessionId] L5 inconsistency: Idle but has incomplete messages")
+                triggerRestValidation(sessionId)
+            }
+        }
+    }
+
     private val _fsmStates = MutableStateFlow<Map<String, SessionFSMState>>(emptyMap())
     private val _histories = MutableStateFlow<Map<String, List<TransitionRecord>>>(emptyMap())
 
     val statusFlow: StateFlow<Map<String, SessionStatus>> = _fsmStates
         .map { it.mapValues { e -> e.value.core } }
         .stateIn(appScope, SharingStarted.Eagerly, emptyMap())
 
     val activityFlow: StateFlow<Map<String, SessionActivity?>> = _fsmStates
@@ -136,11 +173,72 @@ class SessionStateService @Inject constructor(
         _histories.update { it - sessionIds }
     }
 
     fun clearAll() {
         _fsmStates.value = emptyMap()
         _histories.value = emptyMap()
     }
 
-    // Placeholder — implemented in Task 4 (staleness guard)
-    internal fun triggerRestValidation(sessionId: String) { /* Task 4 */ }
+    // ============ L3: REST validation (absence=idle closed loop) ============
+    //
+    // Triggered by:
+    //   - applyTransition when result.isSuspicious (lost SSE)
+    //   - checkStaleness (L2 stale Busy / L5 Idle-with-incomplete)
+    //   - External callers (e.g. manual refresh)
+    //
+    // Absence semantics: when the queried [directory] is the session's own directory and the
+    // session is absent from the server's status map, treat it as Idle (server drops idle
+    // sessions from the map). When [directory] is null (unknown instance), absence is ambiguous
+    // — skip to avoid false Idle.
+    internal fun triggerRestValidation(sessionId: String) {
+        val sid = currentServerId ?: return
+        val directory = directoryResolver.resolve(sessionId)
+        appScope.launch {
+            try {
+                val result = sessionRepoProvider.get().fetchSessionStatuses(sid, directory)
+                result.onSuccess { statuses ->
+                    val serverStatus = statuses[sessionId]
+                    if (serverStatus != null) {
+                        if (BuildConfig.DEBUG) Log.d(TAG, "[$sessionId] L3 REST validation: server says ${serverStatus::class.simpleName}")
+                        onRestValidation(sessionId, serverStatus)
+                    } else if (directory != null) {
+                        // Server deletes idle sessions from its status map — absence means idle.
+                        // Only trust this when we queried the session's own directory.
+                        if (BuildConfig.DEBUG) Log.d(TAG, "[$sessionId] L3 REST validation: absent from own directory -> idle")
+                        onRestValidation(sessionId, SessionStatus.Idle)
+                    }
+                    // directory == null + absent -> skip (avoid false idle on unknown instance)
+                }
+            } catch (e: Exception) {
+                Log.w(TAG, "[$sessionId] L3 REST validation failed: ${e.message}")
+            }
+        }
+    }
+
+    // ============ L4: Full REST sync (unify recovery) ============
+    //
+    // Pull every session status from the server across [projects]' directories (or a single
+    // instance-wide query when [projects] is empty), then fold in local absence semantics:
+    // a local non-Idle session absent from REST is treated as Idle — unless it has incomplete
+    // assistant messages, in which case the local state is preserved (SSE may still be streaming).
+    //
+    // Note: [SessionRepository.fetchSessionStatuses] already maps the raw REST DTO
+    // (`RestSessionStatusInfo`) to the domain [SessionStatus], so no per-entry conversion here.
+    suspend fun syncFromRest(projects: List<Project>): SyncResult {
+        val sid = currentServerId ?: return SyncResult(0, 0)
+        val aggregated = mutableMapOf<String, SessionStatus>()
+        val dirs: List<String?> = if (projects.isEmpty()) listOf(null) else projects.map { it.worktree }
+        for (dir in dirs) {
+            sessionRepoProvider.get().fetchSessionStatuses(sid, dir)
+                .onSuccess { aggregated += it }
+        }
+        // Absence semantics: local non-Idle absent from REST
+        for ((sessionId, state) in _fsmStates.value) {
+            if (state.core !is SessionStatus.Idle && sessionId !in aggregated) {
+                aggregated[sessionId] = if (incompleteChecker.hasIncomplete(sessionId)) state.core  // protect (SSE may still stream)
+                                         else SessionStatus.Idle                                       // absent = idle
+            }
+        }
+        for ((sessionId, status) in aggregated) applyTransition(sessionId, FsmEvent.RestValidation(status))
+        return SyncResult(aggregated.size, aggregated.count { it.value is SessionStatus.Busy })
+    }
 }
diff --git a/app/src/test/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStateServiceTest.kt b/app/src/test/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStateServiceTest.kt
index 29501572..134cc0f4 100644
--- a/app/src/test/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStateServiceTest.kt
+++ b/app/src/test/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStateServiceTest.kt
@@ -1,19 +1,21 @@
 package dev.leonardo.ocremotev2.data.repository
 
 import dev.leonardo.ocremotev2.domain.model.*
 import dev.leonardo.ocremotev2.domain.model.SseEvent
 import dev.leonardo.ocremotev2.domain.repository.SessionRepository
+import io.mockk.coEvery
 import io.mockk.mockk
 import io.mockk.verify
 import kotlinx.coroutines.cancel
+import kotlinx.coroutines.runBlocking
 import kotlinx.coroutines.test.TestScope
 import kotlinx.coroutines.test.UnconfinedTestDispatcher
-import kotlinx.coroutines.test.advanceUntilIdle
+import kotlinx.coroutines.test.runCurrent
 import org.junit.After
 import org.junit.Assert.*
 import org.junit.Test
 import javax.inject.Provider
 
 /**
  * Fixture note (deviation from brief's literal `runTest { this -> ... }` form):
  *
@@ -23,75 +25,168 @@ import javax.inject.Provider
  *   1. Timing — the Eagerly collector was queued behind the test body, so `statusFlow.value`
  *      stayed `emptyMap()` (AssertionError / NPE).
  *   2. `UncompletedCoroutinesError` at teardown — the 3 Eagerly coroutines outlive the test body.
  *
  * Fix mirrors the project's own `ChatViewModelStreamingTest` (UnconfinedTestDispatcher +
  * advanceUntilIdle): drive appScope with an UnconfinedTestDispatcher for eager propagation, and
  * cancel the scope in @After so teardown sees no uncompleted coroutines. All test cases and
  * assertions are unchanged from the brief.
+ *
+ * ---
+ * Task 4 fixture amendment (staleness guard):
+ *
+ * Task 4 starts a perpetual `while(isActive) { delay(STALENESS_CHECK_INTERVAL_MS); ... }`
+ * coroutine in `init`. Probe (see git history) confirmed that `advanceUntilIdle()` loops forever
+ * on such a coroutine (10s JUnit timeout, advanced virtual time to ~423 days). `runCurrent()`
+ * runs only tasks scheduled at the current virtual time and does NOT advance the clock, so the
+ * guard's first delay(5_000) is never reached. All assertions hold under `runCurrent()` because:
+ *   - `applyTransition` is synchronous and writes `_fsmStates` immediately.
+ *   - `statusFlow` uses `stateIn(appScope, SharingStarted.Eagerly, …)`; under UnconfinedTestDispatcher
+ *     the operator chain propagates synchronously, and `runCurrent()` flushes any queued dispatch.
+ *   - `triggerRestValidation` launches a coroutine whose body has no real suspension point under
+ *     relaxed MockK (`coEvery`'s stub returns immediately), so it completes during `runCurrent()`.
+ *
+ * Therefore ALL tests use `runCurrent()` instead of `advanceUntilIdle()`. The `@After cancel`
+ * still cancels the staleness guard's `Job` along with every other child of `testScope`.
  */
 class SessionStateServiceTest {
 
     private val testScope = TestScope(UnconfinedTestDispatcher())
 
     private fun newService() = SessionStateService(
         testScope,
         Provider { mockk<SessionRepository>(relaxed = true) },
     )
 
+    /** Build a service backed by [repo] so tests can stub `fetchSessionStatuses`. */
+    private fun newServiceWith(repo: SessionRepository) = SessionStateService(
+        testScope,
+        Provider { repo },
+    )
+
     @After
     fun tearDown() {
         testScope.cancel()
     }
 
     @Test
     fun `ClientSendParts transitions Idle to Busy Waiting in statusFlow`() {
         val service = newService()
         service.onClientSendParts("s1")
-        testScope.advanceUntilIdle()
+        testScope.runCurrent()
         assertEquals(SessionStatus.Busy, service.statusFlow.value["s1"])
         assertEquals(SessionActivity.Waiting, service.activityFlow.value["s1"])
     }
 
     @Test
     fun `SseIdle after Busy triggers forceComplete on messageForceCompleter`() {
         val forceCompleter = mockk<MessageForceCompleter>(relaxed = true)
         val service = newService()
         service.messageForceCompleter = forceCompleter
         service.onClientSendParts("s1")
         service.onSseEvent(SseEvent.SessionIdle(sessionId = "s1"), "s1")
-        testScope.advanceUntilIdle()
+        testScope.runCurrent()
         assertEquals(SessionStatus.Idle, service.statusFlow.value["s1"])
         verify { forceCompleter.markIdle("s1") }
     }
 
     @Test
     fun `transition recorded in history`() {
         val service = newService()
         service.onClientSendParts("s1")
-        testScope.advanceUntilIdle()
+        testScope.runCurrent()
         val history = service.historyFlow.value["s1"]
         assertEquals(1, history!!.size)
         assertEquals("Idle", history[0].fromCore)
         assertEquals("Busy", history[0].toCore)
     }
 
     @Test
     fun `history trims to max 20 entries`() {
         val service = newService()
         repeat(25) { service.onClientSendParts("s1") }  // each is a transition
-        testScope.advanceUntilIdle()
+        testScope.runCurrent()
         val history = service.historyFlow.value["s1"]!!
         assertTrue("history should be trimmed to <= 20, was ${history.size}", history.size <= 20)
     }
 
     @Test
     fun `clearSession removes state and history`() {
         val service = newService()
         service.onClientSendParts("s1")
-        testScope.advanceUntilIdle()
+        testScope.runCurrent()
         service.clearSession("s1")
-        testScope.advanceUntilIdle()
+        testScope.runCurrent()
         assertNull(service.statusFlow.value["s1"])
         assertNull(service.historyFlow.value["s1"])
     }
+
+    // ============ Task 4: triggerRestValidation absence=idle ============
+
+    @Test
+    fun `triggerRestValidation absence with known directory marks Idle`() {
+        val fakeRepo = mockk<SessionRepository>(relaxed = true)
+        coEvery { fakeRepo.fetchSessionStatuses(any(), any()) } returns Result.success(emptyMap())  // absent
+        val service = newServiceWith(fakeRepo)
+        service.setServerId("svr1")
+        service.directoryResolver = DirectoryResolver { "D:/proj" }
+        service.onClientSendParts("s1")
+        service.triggerRestValidation("s1")
+        testScope.runCurrent()
+        assertEquals(SessionStatus.Idle, service.statusFlow.value["s1"])
+    }
+
+    @Test
+    fun `triggerRestValidation absence with null directory stays Busy`() {
+        val fakeRepo = mockk<SessionRepository>(relaxed = true)
+        coEvery { fakeRepo.fetchSessionStatuses(any(), any()) } returns Result.success(emptyMap())
+        val service = newServiceWith(fakeRepo)
+        service.setServerId("svr1")
+        service.directoryResolver = DirectoryResolver { null }  // unknown dir
+        service.onClientSendParts("s1")
+        service.triggerRestValidation("s1")
+        testScope.runCurrent()
+        assertEquals(SessionStatus.Busy, service.statusFlow.value["s1"])  // no false idle
+    }
+
+    // ============ Task 5: syncFromRest ============
+
+    @Test
+    fun `syncFromRest aggregates multiple directories`() {
+        val fakeRepo = mockk<SessionRepository>(relaxed = true)
+        coEvery { fakeRepo.fetchSessionStatuses("svr1", "D:/projA") } returns Result.success(mapOf("s1" to SessionStatus.Busy))
+        coEvery { fakeRepo.fetchSessionStatuses("svr1", "D:/projB") } returns Result.success(mapOf("s2" to SessionStatus.Busy))
+        val service = newServiceWith(fakeRepo)
+        service.setServerId("svr1")
+        val result = runBlocking { service.syncFromRest(listOf(Project(worktree = "D:/projA"), Project(worktree = "D:/projB"))) }
+        testScope.runCurrent()
+        assertEquals(2, result.totalSessions)
+        assertEquals(SessionStatus.Busy, service.statusFlow.value["s1"])
+        assertEquals(SessionStatus.Busy, service.statusFlow.value["s2"])
+    }
+
+    @Test
+    fun `syncFromRest marks absent non-idle session Idle when no incomplete`() {
+        val fakeRepo = mockk<SessionRepository>(relaxed = true)
+        coEvery { fakeRepo.fetchSessionStatuses(any(), any()) } returns Result.success(emptyMap())
+        val service = newServiceWith(fakeRepo)
+        service.setServerId("svr1")
+        service.onClientSendParts("s1")  // local Busy
+        runBlocking { service.syncFromRest(listOf(Project(worktree = "D:/p"))) }
+        testScope.runCurrent()
+        assertEquals(SessionStatus.Idle, service.statusFlow.value["s1"])
+    }
+
+    @Test
+    fun `syncFromRest protects absent session with incomplete messages`() {
+        val fakeRepo = mockk<SessionRepository>(relaxed = true)
+        coEvery { fakeRepo.fetchSessionStatuses(any(), any()) } returns Result.success(emptyMap())
+        val service = newServiceWith(fakeRepo)
+        service.setServerId("svr1")
+        service.directoryResolver = DirectoryResolver { "D:/p" }
+        service.incompleteChecker = IncompleteAssistantChecker { true }
+        service.onClientSendParts("s1")
+        runBlocking { service.syncFromRest(listOf(Project(worktree = "D:/p"))) }
+        testScope.runCurrent()
+        assertEquals(SessionStatus.Busy, service.statusFlow.value["s1"])  // protected
+    }
 }
