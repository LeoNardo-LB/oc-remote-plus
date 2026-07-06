# Task 12: Delete old code — achieve single source of truth

**Files (modify/delete):**
- Delete: `app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStatusManager.kt`
- Delete: `app/src/test/.../SessionStatusManagerTest.kt` (if exists)
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/handler/SessionEventHandler.kt` (remove status state/methods)
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/EventDispatcher.kt` (remove status methods + sessionStatusManager param; harden sessionStateService non-null)
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/SessionActionsDelegate.kt` (deferred paths — must migrate now)
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModel.kt` (init setServerId)
- Rename: `SessionStatusFSM.kt` → `SessionStateFSM.kt` (+ object name + test file + all refs)
- (Other files referencing deleted symbols — grep + fix)

**Goal:** Single source of truth achieved. SessionStatusManager gone; SessionEventHandler holds no status; all status reads/writes via SessionStateService.

## CRITICAL: dependency order — do these in sequence

### Step 1: Migrate SessionActionsDelegate (deferred from Task 11) — MUST be first

SessionActionsDelegate's `syncSessionStatus` (line ~105) and `refreshAndSync` (line ~128) still call `sessionRepository.fetchSessionStatuses(...)` + `sessionRepository.syncAllSessionStatuses(...)`. Step 2 deletes `syncAllSessionStatuses`, so migrate these FIRST.

READ SessionActionsDelegate to see what providers it has. It needs SessionStateService access + projects. Options (pick the cleanest after reading):
- (a) Add `sessionStateService: SessionStateService` to the delegate (it's constructed by ChatViewModel which has it).
- (b) For projects: add a `projectsProvider: () -> List<Project>` provider, OR reuse an existing one, OR call `sessionStateService.syncFromRest(emptyList())` if only single-session validation is needed (syncFromRest with empty projects queries the default directory — may not cover all, but for on-resume/enter-session single-session it may suffice via triggerRestValidation).

The cleanest for single-session entry/resume: replace the fetch+syncAll+idle-handling with `sessionStateService.triggerRestValidation(sessionId)` (internal — make it accessible, or add a public `validateSession(sessionId)` wrapper). triggerRestValidation does the right thing: resolve directory, fetch, absence=idle. This avoids needing all-projects for the single-session case.

If you use triggerRestValidation: add a public fun in SessionStateService: `fun requestValidation(sessionId: String) = triggerRestValidation(sessionId)` (triggerRestValidation is already `internal`).

For refreshAndSync's message-refresh part (`refreshMessages()`), keep it — only the status-sync part changes.

### Step 2: EventDispatcher — remove status methods + sessionStatusManager

2a. Delete methods: `updateSessionStatus`, `syncAllSessionStatuses`, `markSessionIdle`, `markSessionIdleProtected` (and any helpers only they used).

2b. Remove `sessionStatusManager` constructor param + its init callback lines. Harden `sessionStateService` to non-null (remove the `? = null` from Task 7 — now safe since all callers updated). Update the 6 test files that construct EventDispatcher (they'll need to pass sessionStateService — same as Task 7's nullable workaround, now non-null).

2c. In SessionDeleted handling (processEvent), add `sessionStateService.clearSession(sessionId)`.

2d. Remove `forwardToStatusManager(event)` call + the function itself (only forwardToSessionStateService remains).

### Step 3: SessionEventHandler — remove status state

Delete: `_sessionStatuses`, `sessionStatuses` (the StateFlow), `_sseTimestamps`, `handleSessionStatus`, `handleSessionIdle`, `updateSessionStatus`, `updateAllSessionStatuses`, `updateSessionStatusProtected`, `shouldOverwrite`, `SSE_FRESH_THRESHOLD_MS`. In `handleSessionCreated`, remove the `_sessionStatuses.update{...}` line (status comes from SSE SessionStatus events / syncFromRest, not creation). In `handleSessionDeleted`, remove `_sessionStatuses`/`_sseTimestamps` lines.

Grep for `sessionStatuses`/`sessionHandler.sessionStatuses` across the codebase — any reader must now read `sessionStateService.statusFlow`. Fix all (e.g., SessionListViewModel already migrated in Task 8+9; verify no stragglers).

### Step 4: Delete SessionStatusManager.kt + test

Delete the file. Grep `SessionStatusManager` — fix/remove all refs (EventDispatcher constructor already cleaned in Step 2; DI module if any; tests).

### Step 5: Rename SessionStatusFSM → SessionStateFSM

Rename file `domain/model/SessionStatusFSM.kt` → `SessionStateFSM.kt`. Rename `object SessionStatusFSM` → `object SessionStateFSM`. Rename test file `SessionStatusFSMTest.kt` → `SessionStateFSMTest.kt`. Grep `SessionStatusFSM` — update all refs (SessionStateService, SessionStatusManager-deleted, tests).

### Step 6: ChatViewModel.init — setServerId

In ChatViewModel `init` (or wherever sessionStatusManager.setServerId was called), add `sessionStateService.setServerId(serverId)`. This activates the staleness guard for the current server. Grep `sessionStatusManager.setServerId` / `setServerId` to find existing call sites.

### Step 7: Compile + full test

Run: `.\gradlew :app:testDevDebugUnitTest --rerun` (300000ms)
Expected: BUILD SUCCESSFUL, all tests PASS

This will surface any missed reference — fix iteratively. Expect some test fixture updates (EventDispatcher construction, mock removals).

### Step 8: Commit

```bash
git add -A app/src/main/kotlin app/src/test/kotlin
git commit -m "refactor(state): remove SessionStatusManager + SessionEventHandler status + rename FSM — single source achieved"
```

## Notes

- This is the largest cleanup — expect multiple compile-fix iterations. Work methodically: grep before deleting each symbol.
- The deferred SessionActionsDelegate paths are the trickiest — read carefully before changing.
- If a deletion cascades into too many files (scope explosion), report DONE_WITH_CONCERNS listing what's done and what remains.
- `ChatMessageList.kt` AutoPag logs — leave.
