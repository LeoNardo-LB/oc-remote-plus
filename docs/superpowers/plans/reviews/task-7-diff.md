## Task 7 Diff: 0dd08b2e..827eb695
### Commits
827eb695 feat(state): EventDispatcher integrates SessionStateService — dual-write + 3 callbacks
### Stat
 .../ocremotev2/data/repository/EventDispatcher.kt  | 35 +++++++++++++++++++++-
 .../repository/EventDispatcherIntegrationTest.kt   | 35 +++++++++++++++++++++-
 2 files changed, 68 insertions(+), 2 deletions(-)
### Full Diff
diff --git a/app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/EventDispatcher.kt b/app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/EventDispatcher.kt
index 51836650..4c0ba9ac 100644
--- a/app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/EventDispatcher.kt
+++ b/app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/EventDispatcher.kt
@@ -33,32 +33,49 @@ class EventDispatcher @Inject constructor(
     private val messageHandler: MessageEventHandler,
     private val messagePartHandler: MessagePartHandler,
     private val messageUpdatedHandler: MessageUpdatedHandler,
     private val messageRemovedHandler: MessageRemovedHandler,
     private val permissionHandler: PermissionEventHandler,
     private val questionHandler: QuestionEventHandler,
     private val miscHandler: MiscEventHandler,
     private val sessionNextHandler: SessionNextEventHandler,
-    private val sessionStatusManager: SessionStatusManager
+    private val sessionStatusManager: SessionStatusManager,
+    // Transition bridge: Hilt injects a non-null instance (Dagger ignores the Kotlin default);
+    // the `= null` default only spares legacy test call sites from needing this param during the
+    // Task 7→12 parallel-run window. Deleted (made non-null) in Task 12 alongside SessionStatusManager.
+    private val sessionStateService: SessionStateService? = null
 ) {
     init {
         // L5 cross-validation: SessionStatusManager checks if Idle sessions have
         // incomplete assistant messages (time.completed == null), which indicates
         // a missed SSE event. The callback is set here because SessionStatusManager
         // cannot inject EventDispatcher (constructor simplified to avoid circular deps).
         sessionStatusManager.incompleteAssistantChecker = { sessionId ->
             hasIncompleteAssistant(sessionId)
         }
         // Resolve a session's directory so SessionStatusManager's REST self-healing
         // targets the correct server instance (status is isolated per-directory; a null
         // directory makes non-default-worktree sessions invisible and breaks the FSM recovery).
         sessionStatusManager.directoryResolver = { sessionId ->
             sessionHandler.sessions.value.find { it.id == sessionId }?.directory
         }
+
+        // SessionStateService callbacks (new single source of truth; mirrors sessionStatusManager
+        // setup above). Wired here for the same circular-dep-breaking reason. Nullable-safe because
+        // legacy test call sites default the constructor param to null during the Task 7→12 window.
+        sessionStateService?.incompleteChecker = IncompleteAssistantChecker { sessionId ->
+            hasIncompleteAssistant(sessionId)
+        }
+        sessionStateService?.directoryResolver = DirectoryResolver { sessionId ->
+            sessionHandler.sessions.value.find { it.id == sessionId }?.directory
+        }
+        sessionStateService?.messageForceCompleter = MessageForceCompleter { sessionId ->
+            messageHandler.markSessionIdle(sessionId)
+        }
     }
 
     // ============ Event Handler Registry (Open/Closed Principle) ============
     // Maps each SseEvent subclass to its single responsible handler.
     // To support a new event domain: add a bind() call below. processEvent itself
     // never changes — it just looks up this map. This replaces the previous
     // broadcast model where every event was sent to all 6 handlers and each
     // handler filtered internally via its own `when` block.
@@ -163,16 +180,17 @@ class EventDispatcher @Inject constructor(
         // handlers and each filtered internally via its own `when` block.
         val handler = registry[event::class]
         if (handler != null) {
             handler.handle(event, serverId)
         } else if (BuildConfig.DEBUG) {
             Log.w(TAG, "No handler registered for ${event::class.simpleName}")
         }
         forwardToStatusManager(event)
+        forwardToSessionStateService(event)
 
         // Cross-handler: SessionDeleted cascades cleanup to other handlers
         if (event is SseEvent.SessionDeleted) {
             val sessionId = event.info.id
             messageHandler.clearForSession(sessionId)
             permissionHandler.clearForSession(sessionId)
             questionHandler.clearForSession(sessionId)
             miscHandler.clearForSession(sessionId)
@@ -212,16 +230,31 @@ class EventDispatcher @Inject constructor(
      */
     private fun forwardToStatusManager(event: SseEvent) {
         val fsmSessionId = extractSessionId(event)
         if (fsmSessionId != null) {
             sessionStatusManager.onSseEvent(event, fsmSessionId)
         }
     }
 
+    /**
+     * Forward SSE event to [SessionStateService] for parallel FSM processing (dual-write).
+     * Transition bridge: both [SessionStatusManager] and [SessionStateService] receive the same
+     * status-relevant events until Task 12 deletes the old manager. Reuses [extractSessionId]
+     * — the exact same sessionId extraction + filtering as [forwardToStatusManager] — so the two
+     * FSMs never diverge.
+     */
+    private fun forwardToSessionStateService(event: SseEvent) {
+        val service = sessionStateService ?: return
+        val fsmSessionId = extractSessionId(event)
+        if (fsmSessionId != null) {
+            service.onSseEvent(event, fsmSessionId)
+        }
+    }
+
     /**
      * Extract sessionId from any [SseEvent] subclass.
      * Returns null for events that have no associated session.
      */
     private fun extractSessionId(event: SseEvent): String? {
         return when (event) {
             // Session lifecycle (status-relevant for FSM)
             is SseEvent.SessionStatus -> event.sessionId
diff --git a/app/src/test/kotlin/dev/leonardo/ocremotev2/data/repository/EventDispatcherIntegrationTest.kt b/app/src/test/kotlin/dev/leonardo/ocremotev2/data/repository/EventDispatcherIntegrationTest.kt
index a9cba5f1..0683bae3 100644
--- a/app/src/test/kotlin/dev/leonardo/ocremotev2/data/repository/EventDispatcherIntegrationTest.kt
+++ b/app/src/test/kotlin/dev/leonardo/ocremotev2/data/repository/EventDispatcherIntegrationTest.kt
@@ -1,50 +1,74 @@
 ﻿package dev.leonardo.ocremotev2.data.repository
 
 import dev.leonardo.ocremotev2.data.repository.handler.*
 import dev.leonardo.ocremotev2.domain.model.*
 import dev.leonardo.ocremotev2.domain.model.SseEvent
+import dev.leonardo.ocremotev2.domain.repository.SessionRepository
 import io.mockk.mockk
 import io.mockk.verify
+import kotlinx.coroutines.cancel
+import kotlinx.coroutines.test.TestScope
+import kotlinx.coroutines.test.UnconfinedTestDispatcher
+import kotlinx.coroutines.test.runCurrent
 import kotlinx.coroutines.test.runTest
+import org.junit.After
 import org.junit.Assert.*
 import org.junit.Before
 import org.junit.Test
+import javax.inject.Provider
 
 /**
  * Integration test verifying full SSE event processing pipelines:
  * SSE Event → EventDispatcher.processEvent() → StateFlows update.
  *
  * All handlers are REAL (not mocked) to verify actual integration behavior.
  * These tests complement the unit-level EventDispatcherTest with deep chain tests.
  */
 class EventDispatcherIntegrationTest {
 
     private lateinit var dispatcher: EventDispatcher
     private lateinit var sessionStatusManager: SessionStatusManager
+    // SessionStateService's statusFlow uses stateIn(scope, SharingStarted.Eagerly, …); Eagerly
+    // propagation needs an UnconfinedTestDispatcher + runCurrent() (see SessionStateServiceTest
+    // fixture note for why runTest's StandardTestDispatcher breaks). Each test gets a fresh scope
+    // so the staleness-guard coroutine from init doesn't leak across tests.
+    private lateinit var stateServiceScope: TestScope
+    private lateinit var sessionStateService: SessionStateService
 
     @Before
     fun setup() {
+        stateServiceScope = TestScope(UnconfinedTestDispatcher())
         val messageStore = MessageEventHandler()
         sessionStatusManager = mockk<SessionStatusManager>(relaxed = true)
+        sessionStateService = SessionStateService(
+            appScope = stateServiceScope,
+            sessionRepoProvider = Provider { mockk<SessionRepository>(relaxed = true) },
+        )
         dispatcher = EventDispatcher(
             sessionHandler = SessionEventHandler(),
             messageHandler = messageStore,
             messagePartHandler = MessagePartHandler(messageStore),
             messageUpdatedHandler = MessageUpdatedHandler(messageStore),
             messageRemovedHandler = MessageRemovedHandler(messageStore),
             permissionHandler = PermissionEventHandler(),
             questionHandler = QuestionEventHandler(),
             miscHandler = MiscEventHandler(),
             sessionNextHandler = SessionNextEventHandler(),
-            sessionStatusManager = sessionStatusManager
+            sessionStatusManager = sessionStatusManager,
+            sessionStateService = sessionStateService
         )
     }
 
+    @After
+    fun tearDown() {
+        stateServiceScope.cancel()
+    }
+
     private fun testSession(id: String) = Session(
         id = id, title = "Test-$id", time = Session.Time(created = 1000L, updated = 2000L)
     )
 
     // ============ Scenario 1: Tool Progress Full Chain ============
 
     @Test
     fun `tool progress full chain - started to progress to success`() = runTest {
@@ -803,9 +827,18 @@ class EventDispatcherIntegrationTest {
         dispatcher.syncAllSessionStatuses(statuses)
 
         // The FSM must be corrected in lockstep so the ChatScreen progress bar (which
         // reads SessionStatusManager.statusFlow) reflects REST authoritative state,
         // not only the SessionEventHandler layer.
         verify(exactly = 1) { sessionStatusManager.onRestValidation("s1", SessionStatus.Busy) }
         verify(exactly = 1) { sessionStatusManager.onRestValidation("s2", SessionStatus.Idle) }
     }
+
+    // ============ Scenario 11: SSE dual-write to SessionStateService ============
+
+    @Test
+    fun `SSE SessionStatus dual-writes to SessionStateService`() = runTest {
+        dispatcher.processEvent(SseEvent.SessionStatus(sessionId = "s1", status = SessionStatus.Busy), "svr1")
+        stateServiceScope.runCurrent()
+        assertEquals(SessionStatus.Busy, sessionStateService.statusFlow.value["s1"])
+    }
 }
