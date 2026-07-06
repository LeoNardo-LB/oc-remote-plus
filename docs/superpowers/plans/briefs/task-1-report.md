# Task 1 Report: Evolve FSM — Activity savedActivity + drop sessionId + add TextDelta/TextEnded + forceComplete

**Status:** DONE

**Commit:** `c0419c2b` — `feat(state): evolve FSM — Activity savedActivity + drop sessionId + add TextDelta/TextEnded + forceComplete`

**Test summary:** `SessionStatusFSMTest` — 8 tests, 8 passed, 0 failed, 0 errors.

---

## What I did

Applied Steps 1–6 of the revised `task-1-brief.md` verbatim, evolving the FSM types in place across the 4 listed files. TDD ordering: wrote tests first (RED), implemented (GREEN), committed.

### Step 1 — `SessionFSMState.kt`
- `SessionActivity.Compacting`: changed from `data object Compacting` to `data class Compacting(val savedActivity: SessionActivity?)`.
- `FsmEvent` activity events: dropped `sessionId` from `StepStarted`/`TextStarted`/`ToolInputStarted`/`StepEnded` (now `data object` / shorter data class). Added `data class TextDelta(val delta: String)` and `data object TextEnded`.
- Verified `SessionNextEvent` (in `SessionNextEvent.kt`) already declares `TextDelta(sessionId, messageId, partId, delta)` and `TextEnded(sessionId, messageId, partId)` — no invention needed.

### Step 2 — `SessionStatusFSM.kt`
- `TransitionResult.clearIncompleteMarkers` → `forceComplete` (same semantics, renamed).
- Rewrote `transition()` into a flat `when` dispatching to small private helpers: `clientSendParts`, `toIdle(now, forceComplete)`, `handleSseStatus`, `restValidation`, `activityEvent` (inline, validates Core==Busy else suspicious), `stepEnded`.
- `CompactionStarted` now stores prior activity inside `Compacting(savedActivity = it.activity)`; `CompactionEnded` restores via `(it.activity as? Compacting)?.savedActivity`.
- `SseIdle` / `SseError` / `ClientAbort` now all route through `toIdle(…, forceComplete = true)` (unifying behavior; previously only `ClientAbort` and `RestValidation(Idle)` set the flag — now `SseIdle`/`SseError` also force-complete, matching the brief's spec).
- Removed the old `handleActivityEvent` helper (superseded by `activityEvent`).

### Step 3 — `SessionStatusManager.kt`
- `mapSessionNextEvent(event)` — dropped `sessionId` param; added `TextDelta`/`TextEnded` branches; updated `StepStarted`/`TextStarted`/`ToolInputStarted`/`StepEnded` to the new no-sessionId constructors.
- `mapSseEventToFsm(event)` — `sessionId` became unused after the above, so removed it per the brief's instruction; updated its caller.
- `onSseEvent` — updated the single inner call `mapSseEventToFsm(event)`; `sessionId` is still used for `applyTransition`, so the public signature is unchanged.

### Step 4 — `SessionStatusFSMTest.kt`
- Replaced the entire file with the 8 tests from the brief.
- **Two deviations from the brief's verbatim test code (both required to compile — bugs in the brief itself):**
  1. **Method names**: the brief used `->` inside backticked names (e.g. `` `Idle + ClientSendParts -> Busy_Waiting` ``). Kotlin rejects `>` in identifiers even in backticks (`Name contains illegal characters: >`). Replaced `->` with `to` in all 5 affected names.
  2. **`assertNull` argument order**: the brief wrote `assertNull(r.newState.activity, "After $e, …")`. JUnit's `assertNull(message, actual)` requires the message first; with actual-first Kotlin resolves to the `(String, Object)` overload and reports `actual type is 'SessionActivity?', but 'String!' was expected`. Swapped to `assertNull("After $e, activity must be null", r.newState.activity)`.

## TDD evidence

### RED (before implementation)

Command: `.\gradlew :app:testDevDebugUnitTest --rerun --tests "*SessionStatusFSMTest*"`

Result: `:app:compileDevDebugUnitTestKotlin FAILED` — compile errors (expected, tests target not-yet-existing API):
```
e: ...SessionStatusFSMTest.kt:20:23 Unresolved reference 'forceComplete'.
e: ...SessionStatusFSMTest.kt:25:69 Unresolved reference 'TextEnded'.
e: ...SessionStatusFSMTest.kt:40:51 Argument type mismatch: actual type is 'Unit', but 'FsmEvent' was expected.
e: ...SessionStatusFSMTest.kt:49:22 Unresolved reference 'forceComplete'.
e: ...SessionStatusFSMTest.kt:56:101 Unresolved reference 'savedActivity'.
e: ...SessionStatusFSMTest.kt:66:24 Argument type mismatch: actual type is 'SessionActivity?', but 'String!' was expected.
e: ...SessionStatusFSMTest.kt:74:22 Unresolved reference 'forceComplete'.
```
(The two `Argument type mismatch` errors are the brief-verbatim bugs noted above; both were fixed before implementation, not papered over.)

### GREEN (after Steps 1–3)

Command: `.\gradlew :app:testDevDebugUnitTest --rerun --tests "*SessionStatusFSMTest*"`
```
> Task :app:compileDevDebugKotlin
> Task :app:compileDevDebugUnitTestKotlin
> Task :app:testDevDebugUnitTest
BUILD SUCCESSFUL in 1m 9s
```
Test results XML (`*SessionStatusFSMTest*.xml`):
```
name=dev.leonardo.ocremotev2.domain.model.SessionStatusFSMTest tests=8 failures=0 errors=0 skipped=0
```

### Compile check

Command: `.\gradlew :app:compileDevDebugKotlin`
```
> Task :app:compileDevDebugKotlin UP-TO-DATE
BUILD SUCCESSFUL in 18s
```
Confirms `SessionStatusManager.kt` (main sources) compiles against the evolved API.

## Files changed

| File | Change |
|------|--------|
| `app/src/main/kotlin/dev/leonardo/ocremotev2/domain/model/SessionFSMState.kt` | `Compacting` → data class(savedActivity); `FsmEvent` activity events drop sessionId; +TextDelta/TextEnded |
| `app/src/main/kotlin/dev/leonardo/ocremotev2/domain/model/SessionStatusFSM.kt` | `TransitionResult.clearIncompleteMarkers`→`forceComplete`; `transition()` rewritten with helpers |
| `app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStatusManager.kt` | `mapSessionNextEvent`/`mapSseEventToFsm` drop sessionId; +TextDelta/TextEnded mapping |
| `app/src/test/kotlin/dev/leonardo/ocremotev2/domain/model/SessionStatusFSMTest.kt` | Rewritten: 8 tests targeting new API |

Diff stat: `4 files changed, 153 insertions(+), 313 deletions(-)`.

`ChatMessageList.kt` (pre-existing uncommitted change) was **not** touched. `docs/superpowers/plans/briefs/` remains untracked.

## Self-review

### Constraint compliance
- ✅ Touched ONLY the 4 files in the brief's Files list.
- ✅ `ChatMessageList.kt` left alone (still dirty from pre-existing change).
- ✅ Verified `SessionNextEvent.TextDelta`/`TextEnded` exist before using — no invention.
- ✅ Call chain kept compiling: `mapSessionNextEvent` → `mapSseEventToFsm` → `onSseEvent` all updated consistently.
- ✅ JDK 21 / Gradle timeouts honored (180s test, 120s compile).

### Correctness checks
- ✅ Grep for stale references: no remaining `clearIncompleteMarkers`, no `data object Compacting`, no old `FsmEvent.StepStarted(…)`/`TextStarted(…)`/`TextEnded(…)`/`ToolInputStarted(…, sessionId, …)`/`StepEnded(…, sessionId, …)` call sites.
- ✅ `TransitionResult` has no external readers besides `SessionStatusManager.applyTransition` (which reads only `newState` + `isSuspicious`, both preserved) and the test.
- ✅ `CompactionEnded` restore verified end-to-end by the `CompactionStarted saves activity, CompactionEnded restores` test.

### Behavioral notes for downstream tasks
- **`SseIdle` / `SseError` now set `forceComplete = true`** (routed through `toIdle(…, forceComplete = true)`). The old code set `clearIncompleteMarkers = false` for these two. This matches the brief's explicit spec (Step 2b: `FsmEvent.SseIdle -> toIdle(state, now, forceComplete = true)` and `is FsmEvent.SseError -> toIdle(state, now, forceComplete = true)`). Downstream Task 2 (`SessionStateService`) should be aware that SSE-driven idle now force-completes incomplete markers — this is intentional (server said "idle", so any in-flight assistant message is definitively done).
- **`SessionFSMState.savedActivity` field is now vestigial.** The field still exists (kept to minimize churn per the brief) but is only ever written to `null` (by `toIdle`/`restValidation`/`handleSseStatus(Retry)`). The actual save/restore now happens inside `Compacting.savedActivity`. The field can be removed in a later task if desired; it is not in scope here.
- **`TextDelta` is a "soft" activity setter.** When Core is Busy and activity is not already `Streaming`, a `TextDelta` promotes it to `Streaming` (handles a missed `TextStarted`). If already `Streaming`, it's a no-op (avoids needlessly recreating the state). This matches the brief.

## Concerns

None blocking. Two minor items flagged for awareness (not blockers):
1. The brief's verbatim test code had two compile bugs (illegal `>` in names, wrong `assertNull` arg order) — both fixed. Future briefs should validate that sample code compiles.
2. `SessionFSMState.savedActivity` is now dead storage (see Behavioral notes). Harmless but could be cleaned up in a later task.
