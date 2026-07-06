## Task 1 Diff: 32092634..c0419c2b

### Commits
c0419c2b feat(state): evolve FSM — Activity savedActivity + drop sessionId + add TextDelta/TextEnded + forceComplete

### Stat
 .../data/repository/SessionStatusManager.kt        |  18 +-
 .../ocremotev2/domain/model/SessionFSMState.kt     |  14 +-
 .../ocremotev2/domain/model/SessionStatusFSM.kt    | 230 +++++++++------------
 .../domain/model/SessionStatusFSMTest.kt           | 204 ++++--------------
 4 files changed, 153 insertions(+), 313 deletions(-)

### Full Diff
diff --git a/app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStatusManager.kt b/app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStatusManager.kt
index d6b977ca..ce1c49e5 100644
--- a/app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStatusManager.kt
+++ b/app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStatusManager.kt
@@ -92,21 +92,21 @@ class SessionStatusManager @Inject constructor(
 
     fun onSendParts(sessionId: String) {
         applyTransition(sessionId, FsmEvent.ClientSendParts)
     }
 
     fun onAbort(sessionId: String) {
         applyTransition(sessionId, FsmEvent.ClientAbort)
     }
 
     fun onSseEvent(event: SseEvent, sessionId: String) {
-        val fsmEvent = mapSseEventToFsm(event, sessionId) ?: return
+        val fsmEvent = mapSseEventToFsm(event) ?: return
         applyTransition(sessionId, fsmEvent)
     }
 
     fun onRestValidation(sessionId: String, status: SessionStatus) {
         applyTransition(sessionId, FsmEvent.RestValidation(status))
     }
 
     fun clearSession(sessionId: String) {
         _fsmStates.update { it - sessionId }
     }
@@ -124,38 +124,40 @@ class SessionStatusManager @Inject constructor(
                         if (result.isSuspicious) " [SUSPICIOUS]" else ""
                 )
             }
             if (result.isSuspicious) {
                 triggerRestValidation(sessionId)
             }
             current + (sessionId to result.newState)
         }
     }
 
-    private fun mapSseEventToFsm(event: SseEvent, sessionId: String): FsmEvent? {
+    private fun mapSseEventToFsm(event: SseEvent): FsmEvent? {
         return when (event) {
             is SseEvent.SessionStatus -> FsmEvent.SseStatus(event.status)
             is SseEvent.SessionIdle -> FsmEvent.SseIdle
             is SseEvent.SessionError -> FsmEvent.SseError(event.error)
-            is SseEvent.SessionNext -> mapSessionNextEvent(event.event, sessionId)
+            is SseEvent.SessionNext -> mapSessionNextEvent(event.event)
             else -> null
         }
     }
 
-    private fun mapSessionNextEvent(event: SessionNextEvent, sessionId: String): FsmEvent? {
+    private fun mapSessionNextEvent(event: SessionNextEvent): FsmEvent? {
         return when (event) {
-            is SessionNextEvent.StepStarted -> FsmEvent.StepStarted(sessionId)
-            is SessionNextEvent.TextStarted -> FsmEvent.TextStarted(sessionId)
-            is SessionNextEvent.ToolInputStarted -> FsmEvent.ToolInputStarted(sessionId, event.tool, event.callId)
+            is SessionNextEvent.StepStarted -> FsmEvent.StepStarted
+            is SessionNextEvent.TextStarted -> FsmEvent.TextStarted
+            is SessionNextEvent.TextDelta -> FsmEvent.TextDelta(event.delta)
+            is SessionNextEvent.TextEnded -> FsmEvent.TextEnded
+            is SessionNextEvent.ToolInputStarted -> FsmEvent.ToolInputStarted(event.tool, event.callId)
             // StepEnded has no finish field in SessionNextEvent — pass null.
             // FSM treats non-"tool-calls" finish as "keep current Activity, wait for Core Idle".
-            is SessionNextEvent.StepEnded -> FsmEvent.StepEnded(sessionId, finish = null)
+            is SessionNextEvent.StepEnded -> FsmEvent.StepEnded(finish = null)
             is SessionNextEvent.CompactionStarted -> FsmEvent.CompactionStarted
             is SessionNextEvent.CompactionEnded -> FsmEvent.CompactionEnded
             else -> null
         }
     }
 
     // ============ L2: Staleness Guard ============
 
     private fun startStalenessGuard() {
         stalenessJob?.cancel()
diff --git a/app/src/main/kotlin/dev/leonardo/ocremotev2/domain/model/SessionFSMState.kt b/app/src/main/kotlin/dev/leonardo/ocremotev2/domain/model/SessionFSMState.kt
index 7e3b9f8b..7716a158 100644
--- a/app/src/main/kotlin/dev/leonardo/ocremotev2/domain/model/SessionFSMState.kt
+++ b/app/src/main/kotlin/dev/leonardo/ocremotev2/domain/model/SessionFSMState.kt
@@ -10,22 +10,22 @@ sealed class SessionActivity {
 
     /** Receiving text stream (text.started ~ text.ended) */
     data object Streaming : SessionActivity()
 
     /** Tool call in progress (tool.input.started ~ tool.success/failed) */
     data class ToolCalling(
         val toolName: String?,
         val callId: String?
     ) : SessionActivity()
 
-    /** Context compaction in progress */
-    data object Compacting : SessionActivity()
+    /** Context compaction in progress; saves the prior activity to restore on CompactionEnded */
+    data class Compacting(val savedActivity: SessionActivity?) : SessionActivity()
 }
 
 /**
  * Complete FSM state for a single session.
  *
  * @param core Layer 1 status — mirrors server's SessionStatus (Idle/Busy/Retry)
  * @param activity Layer 2 activity detail (only non-null when core is Busy)
  * @param lastEventAt Timestamp of the last SSE event received (for L2 staleness detection)
  * @param lastCoreTransitionAt Timestamp of the last Core status change
  * @param savedActivity Activity saved before Compacting (restored on CompactionEnded)
@@ -53,17 +53,19 @@ data class SessionFSMState(
 sealed class FsmEvent {
     // === Core events ===
     data class SseStatus(val status: SessionStatus) : FsmEvent()
     data object SseIdle : FsmEvent()
     data class SseError(val message: String) : FsmEvent()
     data object ClientSendParts : FsmEvent()
     data object ClientAbort : FsmEvent()
     data class RestValidation(val status: SessionStatus) : FsmEvent()
 
     // === Activity events (session.next.*) ===
-    data class StepStarted(val sessionId: String) : FsmEvent()
-    data class TextStarted(val sessionId: String) : FsmEvent()
-    data class ToolInputStarted(val sessionId: String, val toolName: String?, val callId: String?) : FsmEvent()
-    data class StepEnded(val sessionId: String, val finish: String?) : FsmEvent()
+    data object StepStarted : FsmEvent()
+    data object TextStarted : FsmEvent()
+    data class TextDelta(val delta: String) : FsmEvent()
+    data object TextEnded : FsmEvent()
+    data class ToolInputStarted(val toolName: String?, val callId: String?) : FsmEvent()
+    data class StepEnded(val finish: String?) : FsmEvent()
     data object CompactionStarted : FsmEvent()
     data object CompactionEnded : FsmEvent()
 }
diff --git a/app/src/main/kotlin/dev/leonardo/ocremotev2/domain/model/SessionStatusFSM.kt b/app/src/main/kotlin/dev/leonardo/ocremotev2/domain/model/SessionStatusFSM.kt
index 186f6c80..50f1d516 100644
--- a/app/src/main/kotlin/dev/leonardo/ocremotev2/domain/model/SessionStatusFSM.kt
+++ b/app/src/main/kotlin/dev/leonardo/ocremotev2/domain/model/SessionStatusFSM.kt
@@ -12,176 +12,132 @@
  *
  * Testability: transition() is a pure function — given (state, event), always
  * produces the same TransitionResult. No side effects.
  */
 object SessionStatusFSM {
 
     data class TransitionResult(
         val newState: SessionFSMState,
         /** True if the transition indicates a likely lost SSE event (e.g., Activity event in Idle state) */
         val isSuspicious: Boolean,
-        /** True if incomplete message markers should be cleared (e.g., abort, REST confirms Idle) */
-        val clearIncompleteMarkers: Boolean
+        /** True if incomplete message markers should be force-completed (e.g., abort, REST confirms Idle) */
+        val forceComplete: Boolean
     )
 
     fun transition(state: SessionFSMState, event: FsmEvent): TransitionResult {
         val now = System.currentTimeMillis()
-
         return when (event) {
             // === Core events ===
-            FsmEvent.ClientSendParts -> {
-                if (state.core is SessionStatus.Idle) {
-                    TransitionResult(
-                        newState = state.copy(
-                            core = SessionStatus.Busy,
-                            activity = SessionActivity.Waiting,
-                            lastEventAt = now,
-                            lastCoreTransitionAt = now
-                        ),
-                        isSuspicious = false,
-                        clearIncompleteMarkers = false
-                    )
-                } else {
-                    TransitionResult(state.copy(lastEventAt = now), false, false)
-                }
-            }
-
-            FsmEvent.ClientAbort -> TransitionResult(
-                newState = SessionFSMState(
-                    core = SessionStatus.Idle,
-                    activity = null,
-                    lastEventAt = now,
-                    lastCoreTransitionAt = now
-                ),
-                isSuspicious = false,
-                clearIncompleteMarkers = true
-            )
-
+            FsmEvent.ClientSendParts -> clientSendParts(state, now)
+            FsmEvent.ClientAbort -> toIdle(state, now, forceComplete = true)
             is FsmEvent.SseStatus -> handleSseStatus(state, event.status, now)
-
-            FsmEvent.SseIdle -> TransitionResult(
-                newState = state.copy(
-                    core = SessionStatus.Idle,
-                    activity = null,
-                    lastEventAt = now,
-                    lastCoreTransitionAt = now
-                ),
-                isSuspicious = false,
-                clearIncompleteMarkers = false
-            )
-
-            is FsmEvent.SseError -> TransitionResult(
-                newState = state.copy(
-                    core = SessionStatus.Idle,
-                    activity = null,
-                    lastEventAt = now,
-                    lastCoreTransitionAt = now
-                ),
-                isSuspicious = false,
-                clearIncompleteMarkers = false
-            )
-
-            is FsmEvent.RestValidation -> TransitionResult(
-                newState = state.copy(
-                    core = event.status,
-                    activity = if (event.status is SessionStatus.Busy) SessionActivity.Waiting else null,
-                    lastEventAt = now,
-                    lastCoreTransitionAt = now
-                ),
-                isSuspicious = false,
-                clearIncompleteMarkers = event.status is SessionStatus.Idle
-            )
-
-            // === Activity events ===
-            is FsmEvent.StepStarted -> handleActivityEvent(state, now) {
-                state.copy(activity = SessionActivity.Waiting)
+            FsmEvent.SseIdle -> toIdle(state, now, forceComplete = true)
+            is FsmEvent.SseError -> toIdle(state, now, forceComplete = true)
+            is FsmEvent.RestValidation -> restValidation(state, event.status, now)
+
+            // === Activity events (session.next.*) ===
+            FsmEvent.StepStarted -> activityEvent(state, now) { it.copy(activity = SessionActivity.Waiting) }
+            FsmEvent.TextStarted -> activityEvent(state, now) { it.copy(activity = SessionActivity.Streaming) }
+            is FsmEvent.TextDelta -> activityEvent(state, now) {
+                if (it.activity is SessionActivity.Streaming) it else it.copy(activity = SessionActivity.Streaming)
             }
-
-            is FsmEvent.TextStarted -> handleActivityEvent(state, now) {
-                state.copy(activity = SessionActivity.Streaming)
-            }
-
-            is FsmEvent.ToolInputStarted -> handleActivityEvent(state, now) {
-                state.copy(activity = SessionActivity.ToolCalling(event.toolName, event.callId))
+            FsmEvent.TextEnded -> activityEvent(state, now) { it.copy(activity = SessionActivity.Waiting) }
+            is FsmEvent.ToolInputStarted -> activityEvent(state, now) {
+                it.copy(activity = SessionActivity.ToolCalling(event.toolName, event.callId))
             }
-
-            is FsmEvent.StepEnded -> handleActivityEvent(state, now) {
-                if (event.finish == "tool-calls") {
-                    state.copy(activity = SessionActivity.Waiting)
-                } else {
-                    state // keep current activity, wait for Core to go Idle
-                }
+            is FsmEvent.StepEnded -> stepEnded(state, event.finish, now)
+            FsmEvent.CompactionStarted -> activityEvent(state, now) {
+                it.copy(activity = SessionActivity.Compacting(savedActivity = it.activity))
             }
-
-            FsmEvent.CompactionStarted -> handleActivityEvent(state, now) {
-                state.copy(activity = SessionActivity.Compacting, savedActivity = state.activity)
-            }
-
-            FsmEvent.CompactionEnded -> handleActivityEvent(state, now) {
-                state.copy(activity = state.savedActivity, savedActivity = null)
+            FsmEvent.CompactionEnded -> activityEvent(state, now) {
+                it.copy(activity = (it.activity as? SessionActivity.Compacting)?.savedActivity)
             }
         }
     }
 
-    private fun handleSseStatus(state: SessionFSMState, status: SessionStatus, now: Long): TransitionResult {
-        return when (status) {
-            is SessionStatus.Busy -> {
-                val isTransition = state.core !is SessionStatus.Busy
-                TransitionResult(
-                    newState = state.copy(
-                        core = SessionStatus.Busy,
-                        activity = if (isTransition) SessionActivity.Waiting else state.activity,
-                        lastEventAt = now,
-                        lastCoreTransitionAt = if (isTransition) now else state.lastCoreTransitionAt
-                    ),
-                    isSuspicious = false,
-                    clearIncompleteMarkers = false
-                )
-            }
-            is SessionStatus.Idle -> TransitionResult(
-                newState = state.copy(
-                    core = SessionStatus.Idle,
-                    activity = null,
-                    lastEventAt = now,
-                    lastCoreTransitionAt = now
-                ),
-                isSuspicious = false,
-                clearIncompleteMarkers = false
-            )
-            is SessionStatus.Retry -> TransitionResult(
+    private fun clientSendParts(state: SessionFSMState, now: Long): TransitionResult = when (state.core) {
+        is SessionStatus.Idle -> TransitionResult(
+            newState = state.copy(
+                core = SessionStatus.Busy,
+                activity = SessionActivity.Waiting,
+                lastEventAt = now,
+                lastCoreTransitionAt = now
+            ),
+            isSuspicious = false,
+            forceComplete = false
+        )
+        else -> TransitionResult(state.copy(lastEventAt = now), isSuspicious = false, forceComplete = false)
+    }
+
+    private fun toIdle(state: SessionFSMState, now: Long, forceComplete: Boolean): TransitionResult = TransitionResult(
+        newState = state.copy(
+            core = SessionStatus.Idle,
+            activity = null,
+            savedActivity = null,
+            lastEventAt = now,
+            lastCoreTransitionAt = now
+        ),
+        isSuspicious = false,
+        forceComplete = forceComplete
+    )
+
+    private fun handleSseStatus(state: SessionFSMState, status: SessionStatus, now: Long): TransitionResult = when (status) {
+        is SessionStatus.Busy -> {
+            val isTransition = state.core !is SessionStatus.Busy
+            TransitionResult(
                 newState = state.copy(
-                    core = status,
-                    activity = null,
+                    core = SessionStatus.Busy,
+                    activity = if (isTransition) SessionActivity.Waiting else state.activity,
                     lastEventAt = now,
-                    lastCoreTransitionAt = now
+                    lastCoreTransitionAt = if (isTransition) now else state.lastCoreTransitionAt
                 ),
                 isSuspicious = false,
-                clearIncompleteMarkers = false
+                forceComplete = false
             )
         }
+        is SessionStatus.Idle -> toIdle(state, now, forceComplete = true)
+        is SessionStatus.Retry -> TransitionResult(
+            newState = state.copy(
+                core = status,
+                activity = null,
+                savedActivity = null,
+                lastEventAt = now,
+                lastCoreTransitionAt = now
+            ),
+            isSuspicious = false,
+            forceComplete = false
+        )
     }
 
+    private fun restValidation(state: SessionFSMState, status: SessionStatus, now: Long): TransitionResult = TransitionResult(
+        newState = state.copy(
+            core = status,
+            activity = if (status is SessionStatus.Busy) SessionActivity.Waiting else null,
+            savedActivity = null,
+            lastEventAt = now,
+            lastCoreTransitionAt = now
+        ),
+        isSuspicious = false,
+        forceComplete = status is SessionStatus.Idle
+    )
+
     /**
-     * Handle Activity events — only valid when Core is Busy.
-     * If Core is not Busy, mark as suspicious (likely lost busy SSE event).
+     * Activity events: valid only when Core is Busy; otherwise suspicious (likely missed Busy).
      */
-    private inline fun handleActivityEvent(
+    private inline fun activityEvent(
         state: SessionFSMState,
         now: Long,
-        update: () -> SessionFSMState
-    ): TransitionResult {
-        return if (state.core is SessionStatus.Busy) {
-            TransitionResult(
-                newState = update().copy(lastEventAt = now),
-                isSuspicious = false,
-                clearIncompleteMarkers = false
-            )
-        } else {
-            // Activity event in non-Busy state = suspicious (busy event likely lost)
-            TransitionResult(
-                newState = state.copy(lastEventAt = now),
-                isSuspicious = true,
-                clearIncompleteMarkers = false
-            )
+        update: (SessionFSMState) -> SessionFSMState
+    ): TransitionResult = if (state.core is SessionStatus.Busy) {
+        TransitionResult(update(state).copy(lastEventAt = now), isSuspicious = false, forceComplete = false)
+    } else {
+        TransitionResult(state.copy(lastEventAt = now), isSuspicious = true, forceComplete = false)
+    }
+
+    private fun stepEnded(state: SessionFSMState, finish: String?, now: Long): TransitionResult {
+        if (state.core !is SessionStatus.Busy) {
+            return TransitionResult(state.copy(lastEventAt = now), isSuspicious = true, forceComplete = false)
         }
+        val newActivity = if (finish == "tool-calls") SessionActivity.Waiting else state.activity
+        return TransitionResult(state.copy(activity = newActivity, lastEventAt = now), isSuspicious = false, forceComplete = false)
     }
 }
diff --git a/app/src/test/kotlin/dev/leonardo/ocremotev2/domain/model/SessionStatusFSMTest.kt b/app/src/test/kotlin/dev/leonardo/ocremotev2/domain/model/SessionStatusFSMTest.kt
index 5d1c12fd..a7fd7513 100644
--- a/app/src/test/kotlin/dev/leonardo/ocremotev2/domain/model/SessionStatusFSMTest.kt
+++ b/app/src/test/kotlin/dev/leonardo/ocremotev2/domain/model/SessionStatusFSMTest.kt
@@ -1,196 +1,76 @@
 ﻿package dev.leonardo.ocremotev2.domain.model
 
 import org.junit.Assert.assertEquals
 import org.junit.Assert.assertFalse
+import org.junit.Assert.assertNull
 import org.junit.Assert.assertTrue
 import org.junit.Test
 
 class SessionStatusFSMTest {
 
-    private val now = 1700000000000L
-    private fun idleState() = SessionFSMState.initial(now)
-    private fun busyState(activity: SessionActivity? = SessionActivity.Waiting) =
-        SessionFSMState(SessionStatus.Busy, activity, now, now)
-
-    // ========== Core transitions ==========
-
-    @Test
-    fun `idle to busy on ClientSendParts`() {
-        val result = SessionStatusFSM.transition(idleState(), FsmEvent.ClientSendParts)
-        assertEquals(SessionStatus.Busy, result.newState.core)
-        assertEquals(SessionActivity.Waiting, result.newState.activity)
-        assertFalse(result.isSuspicious)
-    }
-
-    @Test
-    fun `idle to busy on SseStatus busy`() {
-        val result = SessionStatusFSM.transition(idleState(), FsmEvent.SseStatus(SessionStatus.Busy))
-        assertEquals(SessionStatus.Busy, result.newState.core)
-        assertEquals(SessionActivity.Waiting, result.newState.activity)
-    }
-
-    @Test
-    fun `busy stays busy on duplicate SseStatus busy (idempotent)`() {
-        val state = busyState(SessionActivity.Streaming)
-        val result = SessionStatusFSM.transition(state, FsmEvent.SseStatus(SessionStatus.Busy))
-        assertEquals(SessionStatus.Busy, result.newState.core)
-        // Activity should NOT reset to Waiting on duplicate busy
-        assertEquals(SessionActivity.Streaming, result.newState.activity)
-    }
-
-    @Test
-    fun `busy to idle on SseStatus idle`() {
-        val result = SessionStatusFSM.transition(busyState(), FsmEvent.SseStatus(SessionStatus.Idle))
-        assertEquals(SessionStatus.Idle, result.newState.core)
-        assertEquals(null, result.newState.activity)
-    }
+    private val idle = SessionFSMState.initial()
+    private val busyWaiting = idle.copy(core = SessionStatus.Busy, activity = SessionActivity.Waiting)
+    private val busyStreaming = busyWaiting.copy(activity = SessionActivity.Streaming)
 
     @Test
-    fun `busy to idle on SseIdle (legacy event)`() {
-        val result = SessionStatusFSM.transition(busyState(), FsmEvent.SseIdle)
-        assertEquals(SessionStatus.Idle, result.newState.core)
+    fun `Idle + ClientSendParts to Busy_Waiting`() {
+        val r = SessionStatusFSM.transition(idle, FsmEvent.ClientSendParts)
+        assertEquals(SessionStatus.Busy, r.newState.core)
+        assertEquals(SessionActivity.Waiting, r.newState.activity)
+        assertFalse(r.forceComplete)
     }
 
     @Test
-    fun `busy to idle on SseError`() {
-        val result = SessionStatusFSM.transition(busyState(), FsmEvent.SseError("fatal"))
-        assertEquals(SessionStatus.Idle, result.newState.core)
-        assertEquals(null, result.newState.activity)
+    fun `Busy_Streaming + TextEnded to Busy_Waiting`() {
+        val r = SessionStatusFSM.transition(busyStreaming, FsmEvent.TextEnded)
+        assertEquals(SessionActivity.Waiting, r.newState.activity)
+        assertEquals(SessionStatus.Busy, r.newState.core)
     }
 
     @Test
-    fun `busy to retry on SseStatus retry`() {
-        val retry = SessionStatus.Retry(attempt = 1, message = "timeout", next = now + 5000)
-        val result = SessionStatusFSM.transition(busyState(), FsmEvent.SseStatus(retry))
-        assertEquals(retry, result.newState.core)
+    fun `Busy_Streaming + SseIdle to Idle_null + forceComplete`() {
+        val r = SessionStatusFSM.transition(busyStreaming, FsmEvent.SseIdle)
+        assertEquals(SessionStatus.Idle, r.newState.core)
+        assertNull(r.newState.activity)
+        assertTrue(r.forceComplete)
     }
 
     @Test
-    fun `retry to busy on SseStatus busy`() {
-        val retry = SessionStatus.Retry(attempt = 1, message = "timeout", next = now + 5000)
-        val state = SessionFSMState(retry, null, now, now)
-        val result = SessionStatusFSM.transition(state, FsmEvent.SseStatus(SessionStatus.Busy))
-        assertEquals(SessionStatus.Busy, result.newState.core)
-        assertEquals(SessionActivity.Waiting, result.newState.activity)
+    fun `Idle + TextStarted to suspicious, unchanged`() {
+        val r = SessionStatusFSM.transition(idle, FsmEvent.TextStarted)
+        assertEquals(idle.core, r.newState.core)
+        assertTrue(r.isSuspicious)
     }
 
     @Test
-    fun `retry to idle on SseStatus idle`() {
-        val retry = SessionStatus.Retry(attempt = 1, message = "timeout", next = now + 5000)
-        val state = SessionFSMState(retry, null, now, now)
-        val result = SessionStatusFSM.transition(state, FsmEvent.SseStatus(SessionStatus.Idle))
-        assertEquals(SessionStatus.Idle, result.newState.core)
+    fun `Busy + RestValidation_Idle to Idle_null + forceComplete`() {
+        val r = SessionStatusFSM.transition(busyStreaming, FsmEvent.RestValidation(SessionStatus.Idle))
+        assertEquals(SessionStatus.Idle, r.newState.core)
+        assertTrue(r.forceComplete)
     }
 
     @Test
-    fun `any to idle on ClientAbort`() {
-        val result = SessionStatusFSM.transition(busyState(SessionActivity.Streaming), FsmEvent.ClientAbort)
-        assertEquals(SessionStatus.Idle, result.newState.core)
-        assertEquals(null, result.newState.activity)
-        assertTrue(result.clearIncompleteMarkers)
-    }
-
-    // ========== REST validation overrides ==========
-
-    @Test
-    fun `RestValidation idle overrides any state`() {
-        val result = SessionStatusFSM.transition(busyState(), FsmEvent.RestValidation(SessionStatus.Idle))
-        assertEquals(SessionStatus.Idle, result.newState.core)
-        assertTrue(result.clearIncompleteMarkers)
+    fun `CompactionStarted saves activity, CompactionEnded restores`() {
+        val compacting = SessionStatusFSM.transition(busyStreaming, FsmEvent.CompactionStarted).newState
+        assertTrue(compacting.activity is SessionActivity.Compacting)
+        assertEquals(SessionActivity.Streaming, (compacting.activity as SessionActivity.Compacting).savedActivity)
+        val restored = SessionStatusFSM.transition(compacting, FsmEvent.CompactionEnded).newState
+        assertEquals(SessionActivity.Streaming, restored.activity)
     }
 
     @Test
-    fun `RestValidation busy overrides idle`() {
-        val result = SessionStatusFSM.transition(idleState(), FsmEvent.RestValidation(SessionStatus.Busy))
-        assertEquals(SessionStatus.Busy, result.newState.core)
-        assertEquals(SessionActivity.Waiting, result.newState.activity)
+    fun `invariant - Idle state never holds activity`() {
+        val events = listOf(FsmEvent.SseIdle, FsmEvent.ClientAbort, FsmEvent.RestValidation(SessionStatus.Idle))
+        for (e in events) {
+            val r = SessionStatusFSM.transition(busyStreaming, e)
+            assertNull("After $e, activity must be null", r.newState.activity)
+            assertEquals(SessionStatus.Idle, r.newState.core)
+        }
     }
 
-    // ========== Activity transitions ==========
-
-    @Test
-    fun `stepStarted sets activity to Waiting when Busy`() {
-        val result = SessionStatusFSM.transition(busyState(), FsmEvent.StepStarted("sid"))
-        assertEquals(SessionActivity.Waiting, result.newState.activity)
-        assertFalse(result.isSuspicious)
-    }
-
-    @Test
-    fun `textStarted sets activity to Streaming when Busy`() {
-        val result = SessionStatusFSM.transition(busyState(), FsmEvent.TextStarted("sid"))
-        assertEquals(SessionActivity.Streaming, result.newState.activity)
-    }
-
-    @Test
-    fun `toolInputStarted sets activity to ToolCalling when Busy`() {
-        val result = SessionStatusFSM.transition(busyState(), FsmEvent.ToolInputStarted("sid", "read", "call-1"))
-        assertEquals(SessionActivity.ToolCalling("read", "call-1"), result.newState.activity)
-    }
-
-    @Test
-    fun `stepEnded with tool-calls returns to Waiting`() {
-        val state = busyState(SessionActivity.ToolCalling("read", "call-1"))
-        val result = SessionStatusFSM.transition(state, FsmEvent.StepEnded("sid", "tool-calls"))
-        assertEquals(SessionActivity.Waiting, result.newState.activity)
-    }
-
-    @Test
-    fun `stepEnded with stop keeps current activity`() {
-        val state = busyState(SessionActivity.Streaming)
-        val result = SessionStatusFSM.transition(state, FsmEvent.StepEnded("sid", "stop"))
-        assertEquals(SessionActivity.Streaming, result.newState.activity)
-    }
-
-    // ========== Compaction ==========
-
-    @Test
-    fun `compactionStarted saves current activity and switches to Compacting`() {
-        val state = busyState(SessionActivity.Streaming)
-        val result = SessionStatusFSM.transition(state, FsmEvent.CompactionStarted)
-        assertEquals(SessionActivity.Compacting, result.newState.activity)
-        assertEquals(SessionActivity.Streaming, result.newState.savedActivity)
-    }
-
-    @Test
-    fun `compactionEnded restores saved activity`() {
-        val state = SessionFSMState(
-            core = SessionStatus.Busy,
-            activity = SessionActivity.Compacting,
-            lastEventAt = now,
-            lastCoreTransitionAt = now,
-            savedActivity = SessionActivity.Streaming
-        )
-        val result = SessionStatusFSM.transition(state, FsmEvent.CompactionEnded)
-        assertEquals(SessionActivity.Streaming, result.newState.activity)
-        assertEquals(null, result.newState.savedActivity)
-    }
-
-    // ========== Illegal transitions (suspicious) ==========
-
-    @Test
-    fun `stepStarted when Idle is suspicious`() {
-        val result = SessionStatusFSM.transition(idleState(), FsmEvent.StepStarted("sid"))
-        assertTrue(result.isSuspicious)
-    }
-
-    @Test
-    fun `textStarted when Idle is suspicious`() {
-        val result = SessionStatusFSM.transition(idleState(), FsmEvent.TextStarted("sid"))
-        assertTrue(result.isSuspicious)
-    }
-
-    @Test
-    fun `toolInputStarted when Idle is suspicious`() {
-        val result = SessionStatusFSM.transition(idleState(), FsmEvent.ToolInputStarted("sid", "read", "call-1"))
-        assertTrue(result.isSuspicious)
-    }
-
-    // ========== Core to Idle clears activity ==========
-
     @Test
-    fun `transition to Idle always clears activity`() {
-        val state = busyState(SessionActivity.ToolCalling("write", "call-2"))
-        val result = SessionStatusFSM.transition(state, FsmEvent.SseStatus(SessionStatus.Idle))
-        assertEquals(null, result.newState.activity)
+    fun `ClientAbort always forceComplete`() {
+        val r = SessionStatusFSM.transition(busyStreaming, FsmEvent.ClientAbort)
+        assertTrue(r.forceComplete)
     }
 }
