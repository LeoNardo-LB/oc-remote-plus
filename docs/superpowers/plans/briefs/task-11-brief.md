# Task 11: Recovery paths → syncFromRest (unify 6 paths into 1)

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/service/SseConnectionManager.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/SessionActionsDelegate.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/sessions/SessionListViewModel.kt`
- (Tests may need fixture updates)

**Goal:** All 6 recovery paths now call `sessionStateService.syncFromRest(projects)` instead of their own per-path status-fetch + sync logic.

## Background — read existing recovery code first

These 3 files each have recovery logic that fetches session statuses from REST and syncs them. Tasks 4+5 already built `SessionStateService.syncFromRest(projects)` which does: aggregate across all project worktrees + absence=idle semantics + apply via FSM. Now replace each file's per-path logic with a call to it.

**READ each file's current recovery code:**
1. `SseConnectionManager.kt`: `preLoadSessions` (~line 261-287) calls `syncSessionStatuses(conn)` after setSessions; `recoverMessages` (~line 294-314) Phase 2 also calls it. The private `syncSessionStatuses(conn)` (~line 320-350) does its own fetch+sync. ALSO: how does SseConnectionManager access SessionStateService? It may need injection, or access via `eventDispatcher.sessionStateService` (EventDispatcher has it from Task 7 — but it's nullable there; check). READ to determine.
2. `SessionListViewModel.kt`: `loadSessions` calls `syncSessionStatusesFromServer()` (~line 283) which calls `syncSessionStatuses()` (~line 341). These have the per-directory aggregation + toStatus logic. `SessionListViewModel` already has `sessionStateService` injected (Task 8+9).
3. `SessionActionsDelegate.kt`: `syncSessionStatus` (~line 105) and `refreshAndSync` (~line 128) call `sessionRepository.fetchSessionStatuses(...)` + `syncAllSessionStatuses(...)` + idle-handling. How does it access SessionStateService? It may need a provider/callback. READ.

## Step 1: SseConnectionManager — replace syncSessionStatuses with syncFromRest

1a. Get SessionStateService access: either inject it directly, or access via `eventDispatcher.sessionStateService!!` (EventDispatcher's is nullable from Task 7 — use `!!` since prod always has it, or add a non-null accessor). Cleanest: inject `SessionStateService` into SseConnectionManager constructor.

1b. In `preLoadSessions`: replace `syncSessionStatuses(conn)` with `sessionStateService.syncFromRest(projects)` (projects is the local `projects` variable already fetched).

1c. In `recoverMessages` Phase 2: replace `syncSessionStatuses(conn)` with `sessionStateService.syncFromRest(projects)`. NOTE: recoverMessages may not have `projects` — fetch via `fileApi.listProjects(conn)` OR pass projects in. Read the function to see.

1d. Delete the now-dead private `syncSessionStatuses(conn)` method.

## Step 2: SessionListViewModel — replace syncSessionStatusesFromServer

Replace `syncSessionStatusesFromServer()` call in `loadSessions` (~line 283) with `sessionStateService.syncFromRest(_projects.value)`. Delete the local `syncSessionStatuses`, `syncSessionStatusesFromServer`, `toStatus` methods (their logic now lives in SessionStateService.syncFromRest).

## Step 3: SessionActionsDelegate — replace syncSessionStatus + refreshAndSync

This is the trickiest — SessionActionsDelegate may not have SessionStateService access. Options:
- Add `sessionStateService` via the delegate's constructor/providers (it already has `sessionRepository` provider pattern).
- Or access via a provider/callback.

In `syncSessionStatus` and `refreshAndSync`, replace the `sessionRepository.fetchSessionStatuses(...) + syncAllSessionStatuses + idle-handling` block with `sessionStateService.syncFromRest(projects)`. The `projects` need sourcing — from a provider or by fetching. The existing code uses `sessionDirectoryProvider()` for single-session; syncFromRest needs ALL projects. If SessionActionsDelegate can't easily get all projects, it may need a `projectsProvider: () -> List<Project>` added.

READ SessionActionsDelegate's constructor to see what providers it has. If adding projectsProvider is too invasive, an alternative: keep SessionActionsDelegate using the OLD path (it'll be cleaned in Task 12 when sessionStatusManager is removed) and only unify SseConnectionManager + SessionListViewModel in this task. Report which paths you unified.

## Step 4: Compile + run tests

Run: `.\gradlew :app:testDevDebugUnitTest --rerun --tests "*SseConnection*" --tests "*SessionActions*" --tests "*SessionList*"` (240000ms)
Expected: PASS (update mocks as needed)

## Step 5: Commit

```bash
git add <modified files + tests>
git commit -m "refactor(state): recovery paths collapse into syncFromRest"
```
