# Task 11 Report — Recovery paths → `syncFromRest`

**Status:** DONE_WITH_CONCERNS (4 of 6 paths unified; 2 deferred to Task 12)
**Commit:** `1ed85723` — `refactor(state): recovery paths collapse into syncFromRest`
**Files touched:** `SseConnectionManager.kt`, `SessionListViewModel.kt`
**Net diff:** +18 / −102 lines

---

## Paths unified (4 / 6)

| # | File | Method | Before | After |
|---|------|--------|--------|-------|
| 1 | SseConnectionManager | `preLoadSessions` | `syncSessionStatuses(conn)` | `sessionStateService.setServerId(server.id)` + `syncFromRest(projects)` |
| 2 | SseConnectionManager | `recoverMessages` (Phase 2) | `syncSessionStatuses(conn)` | fetch `projects` + `setServerId` + `syncFromRest(projects)` |
| 3 | SessionListViewModel | `loadSessions` | `syncSessionStatusesFromServer()` | `sessionStateService.setServerId(serverId)` + `syncFromRest(_projects.value)` |
| 4 | SessionListViewModel | `refreshSessions` | `syncSessionStatusesFromServer()` | `sessionStateService.setServerId(serverId)` + `syncFromRest(_projects.value)` |

## Paths DEFERRED (2 / 6)

| # | File | Method | Reason |
|---|------|--------|--------|
| 5 | SessionActionsDelegate | `syncSessionStatus` | No clean projects access — see below |
| 6 | SessionActionsDelegate | `refreshAndSync` | No clean projects access — see below |

**Why deferred:** `syncFromRest(projects)` requires the **full project list** (it aggregates across all worktrees and applies absence=idle semantics over the entire local FSM map). `SessionActionsDelegate` only has `sessionDirectoryProvider()` (the current session's single directory). The delegate is constructed in `ChatViewModel`, which has `sessionStateService` injected but **no `FileApi`** and no projects source. The only way to source projects cleanly would be to inject `FileApi` into `ChatViewModel` purely for this one sync — an invasive change outside Task 11's touch list (`ChatViewModel.kt` is not in scope) and arguably a dependency-creep regression.

The deferred paths' old code calls `sessionRepository.fetchSessionStatuses(...)` + `sessionRepository.syncAllSessionStatuses(...)` — both are `SessionRepository` interface methods, **not** the private methods deleted in Steps 1–2. So deferral leaves **no dead code and no breakage**; these two paths continue to update the legacy status map until Task 12 retires `SessionStatusManager` and cuts the FSM over fully.

---

## How `SessionStateService` is accessed in each file

| File | Access mechanism |
|------|------------------|
| **SseConnectionManager** | **Direct constructor injection** — added `private val sessionStateService: SessionStateService` (both classes are `@Singleton`, Hilt auto-wires, no DI module changes). Chosen over `eventDispatcher.sessionStateService!!` because direct injection is cleaner and avoids the nullable from Task 7. |
| **SessionListViewModel** | **Already injected** (Task 8+9) as `private val sessionStateService: SessionStateService`. No constructor change. |
| **SessionActionsDelegate** | N/A (deferred). |

### `currentServerId` wiring (important)
`SessionStateService.syncFromRest` reads an internal `currentServerId` and early-returns `SyncResult(0,0)` when null. Prior to this task, `setServerId` was **never called in production** (only in tests) — so `syncFromRest`/`triggerRestValidation` were no-ops. Each new call site now calls `sessionStateService.setServerId(...)` immediately before `syncFromRest(...)` so the service resolves the correct server connection for its internal `fetchSessionStatuses(sid, dir)` calls.

---

## Dead methods removed

| File | Method | Lines removed |
|------|--------|---------------|
| SseConnectionManager | `private suspend fun syncSessionStatuses(conn)` | ~42 |
| SessionListViewModel | `private fun RestSessionStatusInfo.toStatus()` | 5 |
| SessionListViewModel | `private suspend fun syncSessionStatuses(directory)` (was already unused — no callers) | 11 |
| SessionListViewModel | `private suspend fun syncSessionStatusesFromServer()` | 20 |

**Unused imports cleaned:** `RestSessionStatusInfo` (both files), `SessionStatus` (SseConnectionManager — only used in the deleted `when` block). `SessionStatus` import kept in SessionListViewModel (still used by `SessionItem` data class + `uiState` combine).

---

## Verification

**Compile:** `.\gradlew :app:compileDevDebugKotlin` → **BUILD SUCCESSFUL** (1m 2s)

**Tests:** `.\gradlew :app:testDevDebugUnitTest --rerun --tests "*SseConnection*" --tests "*SessionActions*" --tests "*SessionList*"` → **BUILD SUCCESSFUL** (53s)

| Suite | tests | failures | errors | skipped |
|-------|-------|----------|--------|---------|
| `SseConnectionManagerTest` | 2 | 0 | 0 | 0 |
| `SessionListViewModelPaginationTest` | 3 | 0 | 0 | 0 |
| `SessionListViewModelSearchTest` | 3 | 0 | 0 | 0 |

`*SessionActions*` matched no test class (none exists) — Gradle ran the other filters normally. Test fixtures needed **no changes**: `SessionListViewModel` tests already inject `sessionStateService = mockk(relaxed = true)`, so the new `setServerId`/`syncFromRest` calls auto-mock. `SseConnectionManagerTest` only asserts field types via reflection.

---

## Self-review

**Correctness**
- ✅ Each `syncFromRest` call is immediately preceded by `setServerId` with the correct serverId (`server.id` in SseConnectionManager, `serverId` field in SessionListViewModel) — so `currentServerId` resolves the right connection.
- ✅ `projects` passed to `syncFromRest` is the full list from `fileApi.listProjects(conn)` (SseConnectionManager) or `_projects.value` (SessionListViewModel, populated earlier in the same try-block). Matches the aggregation contract `syncFromRest` expects.
- ✅ The absence=idle + incomplete-protection semantics previously duplicated in each file now live solely in `syncFromRest` (single source of truth).
- ✅ `recoverMessages` Phase 2 now fetches `projects` itself (it didn't before) since `syncFromRest` needs them — one extra REST call per reconnect, acceptable.

**Concerns / known limitations**
1. **Multi-server `currentServerId` race (pre-existing pattern).** `SessionStateService.currentServerId` is a single global; if two servers' `preLoad`/`recover` run concurrently on `Dispatchers.IO`, one server's `setServerId` could overwrite the other's in the window before `syncFromRest` completes. The legacy `SessionStatusManager` has the **identical** single-global pattern (set only in `ChatViewModel.init`), so this is a pre-existing architectural property of the status layer, not introduced by Task 11. Task 12 (full cutover) should address per-server scoping.
2. **SessionActionsDelegate still writes to the legacy status map.** Until Task 12, the active-session REST correction (paths 5–6) updates `eventDispatcher.syncAllSessionStatuses` (legacy map) rather than the FSM. `ChatViewModel` already reads status from `sessionStateService.statusFlow` (FSM), so the deferred paths' REST correction is effectively invisible to ChatScreen until the Task 12 cutover. This is a pre-existing mid-migration gap, not a regression from Task 11.
3. **`SessionStateService.setServerId` is still not called from `ChatViewModel.init`.** This means the FSM's staleness guard (`checkStaleness` → `triggerRestValidation`) remains non-functional for the active chat until Task 12 wires it (parallel to the existing `sessionStatusManager.setServerId` call). Wiring it in `ChatViewModel` was out of Task 11's touch scope. The collapsed recovery paths (1–4) work correctly regardless, because they each call `setServerId` locally before `syncFromRest`.

**Scope discipline**
- No changes to `ChatMessageList.kt` (per constraints).
- No collateral edits; every line traces to the brief.
- Pre-existing unrelated working-tree change (`docs/.../task-3-diff.md`) and untracked review files were **not** staged — only the 2 intended source files.
