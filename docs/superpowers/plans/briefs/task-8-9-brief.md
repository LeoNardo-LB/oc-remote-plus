# Task 8+9 (merged): UI ViewModels read statusFlow from SessionStateService

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/sessions/SessionListViewModel.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModel.kt`
- (Tests may need fixture updates — see Step 3)

**Interfaces:**
- Consumes: `SessionStateService.statusFlow: StateFlow<Map<String, SessionStatus>>` (Task 3-7)
- Produces: SessionListViewModel.uiState and ChatViewModel.sessionMetaState derive session status from SessionStateService instead of the old sources

## Background — read the existing combine blocks first

**Task 8 — SessionListViewModel:** READ the `uiState` combine (around line 149-242). It currently reads `eventDispatcher.sessionStatuses` as one of the combined flows (extracted via `values[N] as ... statuses`). You're replacing THAT source with `sessionStateService.statusFlow`. The extraction logic (`statuses[session.id] ?: SessionStatus.Idle` in buildTreeNodes/RECENT branch) stays the same — only the source flow changes. Also inject `SessionStateService` into the constructor.

**Task 9 — ChatViewModel:** READ `sessionMetaState` combine (around line 539-562). It currently reads `sessionStatusManager.statusFlow`. Replace with `sessionStateService.statusFlow`. The `statuses[sid] ?: SessionStatus.Idle` extraction stays. Inject `SessionStateService` (ChatViewModel already has `sessionStatusManager` — add `sessionStateService` alongside, keep sessionStatusManager for now until Task 12).

## Step 1: SessionListViewModel — inject + switch source

1a. Add `private val sessionStateService: SessionStateService` to SessionListViewModel constructor (after eventDispatcher or wherever fits the existing param order).

1b. In the `uiState` combine, replace the `eventDispatcher.sessionStatuses` source with `sessionStateService.statusFlow`. Update the combine's argument list and the `values[N]` index extraction accordingly (if sessionStatuses was at index 1, sessionStateService.statusFlow takes its place at the same index — but VERIFY the actual index in the current code).

The combine uses ~14 flows; only the statuses source changes. Be careful with index shifts.

## Step 2: ChatViewModel — inject + switch source

2a. Add `private val sessionStateService: SessionStateService` to ChatViewModel constructor.

2b. In `sessionMetaState` combine, replace `sessionStatusManager.statusFlow` with `sessionStateService.statusFlow`. Update index extraction if needed.

## Step 3: Test fixtures (may need updates)

- `SessionListViewModelSearchTest` / `SessionListViewModelPaginationTest`: if they mock `eventDispatcher.sessionStatuses` (or construct SessionListViewModel with specific statuses), they may need to mock `sessionStateService.statusFlow` instead, OR inject a real SessionStateService and drive it. READ these test files; update only what breaks compilation/assertions. If a test constructs SessionListViewModel manually, add `sessionStateService` param (real or mock).
- ChatViewModel tests (`ChatViewModelStreamingTest`, etc.): similar — if they construct ChatViewModel with `sessionStatusManager`, add `sessionStateService`.

Goal: keep tests green. If a test's setup is complex, prefer injecting a real SessionStateService (with UnconfinedTestDispatcher scope + Provider{mockk}) and driving status via `service.onClientSendParts`/`onRestValidation` rather than mocking the flow.

## Step 4: Compile + run tests

Run: `.\gradlew :app:testDevDebugUnitTest --rerun --tests "*SessionListViewModel*" --tests "*ChatViewModel*"` (240000ms)
Expected: PASS

If many tests break due to the constructor change and fixing them balloons scope, report DONE_WITH_CONCERNS listing the affected test files — the controller will decide whether to fix in-task or defer.

## Step 5: Commit

```bash
git add app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/sessions/SessionListViewModel.kt app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModel.kt <any test files updated>
git commit -m "refactor(ui): SessionListViewModel + ChatViewModel read statusFlow from SessionStateService"
```
