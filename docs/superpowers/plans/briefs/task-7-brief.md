# Task 7: EventDispatcher integrates SessionStateService — SSE dual-write + 3 callbacks

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/EventDispatcher.kt`
- Modify: `app/src/test/kotlin/dev/leonardo/ocremotev2/data/repository/EventDispatcherIntegrationTest.kt`

**Interfaces:**
- Consumes: `SessionStateService` (Task 3-5: onSseEvent, 3 callbacks — directoryResolver/incompleteChecker/messageForceCompleter), existing EventDispatcher structure
- Produces: EventDispatcher routes SSE status events to SessionStateService (dual-write alongside existing SessionStatusManager during transition); SessionStateService's 3 callbacks wired

## Background — read EventDispatcher.kt first

Before changing anything, READ `EventDispatcher.kt` to understand the current structure:
- Constructor (line ~31-42): injects 9 handlers + `sessionStatusManager`
- `init` (line ~43-57): sets `sessionStatusManager.incompleteAssistantChecker` and `sessionStatusManager.directoryResolver` — THIS IS THE PATTERN TO MIRROR for sessionStateService
- `processEvent` (line ~160-194): dispatches to registry, then calls `forwardToStatusManager(event)` (line ~170) — THIS IS THE DUAL-WRITE PATTERN TO MIRROR
- `hasIncompleteAssistant(sessionId)` (line ~200): private, used by the incompleteAssistantChecker callback
- `forwardToStatusManager(event)`: read this function to see how it extracts sessionId and forwards — mirror it for sessionStateService

## Step 1: Inject SessionStateService + wire 3 callbacks in init

**1a.** Add `private val sessionStateService: SessionStateService` as the LAST constructor param (after sessionStatusManager). Keep sessionStatusManager for now (deleted in Task 12).

**1b.** In `init`, AFTER the existing sessionStatusManager callback setup, add the SessionStateService callbacks (mirror the existing pattern — they use the same logic):

```kotlin
// SessionStateService callbacks (new single source of truth; mirrors sessionStatusManager setup)
sessionStateService.incompleteChecker = IncompleteAssistantChecker { sessionId -> hasIncompleteAssistant(sessionId) }
sessionStateService.directoryResolver = DirectoryResolver { sessionId ->
    sessionHandler.sessions.value.find { it.id == sessionId }?.directory
}
sessionStateService.messageForceCompleter = MessageForceCompleter { sessionId -> messageHandler.markSessionIdle(sessionId) }
```

Imports needed: `dev.leonardo.ocremotev2.data.repository.DirectoryResolver`, `IncompleteAssistantChecker`, `MessageForceCompleter` (these are top-level fun interfaces in SessionStateService.kt, same package — may not need import if same package; verify).

## Step 2: Dual-write SSE status events to SessionStateService

Read the existing `forwardToStatusManager(event)` function to understand how it extracts sessionId and forwards events. Create a parallel `forwardToSessionStateService(event)` that does the same but calls `sessionStateService.onSseEvent(event, sessionId)`.

Then in `processEvent`, right after the existing `forwardToStatusManager(event)` call (line ~170), add:
```kotlin
forwardToSessionStateService(event)
```

The new function should mirror forwardToStatusManager's event filtering (only forward status-relevant events: SessionStatus, SessionIdle, SessionError, SessionNext) and sessionId extraction. If forwardToStatusManager uses a helper to get sessionId (e.g. a `when` returning sessionId, or reads event.sessionId), reuse that exact approach — do not invent a new one.

If forwardToStatusManager's sessionId extraction is complex or inline, the cleanest mirror is: extract a shared private helper `private fun sessionIdFor(event: SseEvent): String?` (if not already present) that both forward functions use. But ONLY refactor if it's a clean extraction — otherwise just duplicate the extraction logic in forwardToSessionStateService (dual-write is temporary, deleted in Task 12).

## Step 3: Update EventDispatcherIntegrationTest

The test's `setup()` currently constructs EventDispatcher with a `mockk<SessionStatusManager>(relaxed=true)`. Add a REAL `SessionStateService` (not mock) and pass it as the new constructor param.

SessionStateService needs `appScope: CoroutineScope` + `sessionRepoProvider: Provider<SessionRepository>`. In the test, use `runTest`'s scope and a `Provider { mockk(relaxed=true) }` (see SessionStateServiceTest for the fixture pattern — it uses UnconfinedTestDispatcher). Since EventDispatcherIntegrationTest uses `runTest { }`, you may need to construct the SessionStateService outside runTest with an UnconfinedTestDispatcher, or adapt. Store it as a field `lateinit var sessionStateService: SessionStateService`.

Add this test:
```kotlin
@Test fun `SSE SessionStatus dual-writes to SessionStateService`() = runTest {
    dispatcher.processEvent(SseEvent.SessionStatus(sessionId = "s1", status = SessionStatus.Busy), "svr1")
    assertEquals(SessionStatus.Busy, sessionStateService.statusFlow.value["s1"])
}
```

Note: SessionStateService.statusFlow uses SharingStarted.Eagerly — accessing `.value` after processEvent should work if the scope is UnconfinedTestDispatcher (Eagerly propagates synchronously). If `.value` is empty, the test fixture needs runCurrent/advanceUntilIdle — mirror SessionStateServiceTest's approach.

## Step 4: Run tests

Run: `.\gradlew :app:testDevDebugUnitTest --rerun --tests "*EventDispatcherIntegrationTest*"` (180000ms)
Expected: PASS (existing + new dual-write test)

## Step 5: Commit

```bash
git add app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/EventDispatcher.kt app/src/test/kotlin/dev/leonardo/ocremotev2/data/repository/EventDispatcherIntegrationTest.kt
git commit -m "feat(state): EventDispatcher integrates SessionStateService — dual-write + 3 callbacks"
```
