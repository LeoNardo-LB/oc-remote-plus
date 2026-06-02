# Session Page: Pull-to-Refresh & Directory Prefill — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add pull-to-refresh and directory prefill to the session list page.

**Architecture:** Two independent features touching 3 files. Feature 1 wraps `LazyColumn` with `PullToRefreshBox` and adds a `refreshSessions()` method. Feature 2 tracks last toggled directory in ViewModel and passes it as `initialDirectory` to `OpenProjectDialog`.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), StateFlow

**Worktree:** `.worktrees/session-page-refresh-and-dir-prefill` on branch `feature/session-page-refresh-and-dir-prefill`

**Working directory:** `D:\Develop\code\app\oc-remote\.worktrees\session-page-refresh-and-dir-prefill`

---

## File Map

| File | Responsibility |
|---|---|
| `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListViewModel.kt` | Add `_isRefreshing` + `_lastToggledDirectory` flows, `refreshSessions()` method, `prefillDirectory` computation in combine |
| `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListScreen.kt` | Wrap LazyColumn with PullToRefreshBox, pass `prefillDirectory` to OpenProjectDialog |
| `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/OpenProjectDialog.kt` | Add `initialDirectory` parameter, use it to set initial `currentDir` |

---

## Task 1: Add pull-to-refresh state and method to ViewModel

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListViewModel.kt`

- [ ] **Step 1: Add `isRefreshing` field to `SessionListUiState`**

In `SessionListUiState` data class (line 42), add field after `baseDirectories`:

```kotlin
data class SessionListUiState(
    val treeNodes: List<TreeNode> = emptyList(),
    val serverName: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val selectedIds: Set<String> = emptySet(),
    val isSelectionMode: Boolean = false,
    val baseDirectory: String? = null,
    val baseDirectories: Set<String> = emptySet(),
    val isRefreshing: Boolean = false,
)
```

- [ ] **Step 2: Add `_isRefreshing` flow and include in `combine`**

After `_baseDirectory` declaration (line 89), add:

```kotlin
private val _isRefreshing = MutableStateFlow(false)
```

Add `_isRefreshing` to the `combine` call (line 94). Change from 9 to 10 flows — append `_isRefreshing` after `_baseDirectory`:

```kotlin
val uiState: StateFlow<SessionListUiState> = combine(
    eventDispatcher.sessions,
    eventDispatcher.sessionStatuses,
    eventDispatcher.serverSessions,
    _isLoading,
    _error,
    _projects,
    _expandedPaths,
    _selectedIds,
    _baseDirectory,
    _isRefreshing
) { values ->
```

In the `combine` body, add after the `baseDirectory` extraction (after line 113):

```kotlin
val isRefreshing = values[9] as Boolean
```

Update the `SessionListUiState(...)` constructor (line 132) to include:

```kotlin
isRefreshing = isRefreshing,
```

- [ ] **Step 3: Add `refreshSessions()` method**

After `loadSessions()` (after line 198), add:

```kotlin
fun refreshSessions() {
    viewModelScope.launch {
        _isRefreshing.value = true
        _error.value = null
        try {
            val projects = api.listProjects(conn)
            _projects.value = projects
            if (projects.isEmpty()) {
                val sessions = api.listSessions(conn)
                eventDispatcher.setSessions(serverId, sessions)
            } else {
                for (project in projects) {
                    try {
                        val sessions = api.listSessions(conn, directory = project.worktree)
                        eventDispatcher.setSessions(serverId, sessions)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to refresh sessions for project ${project.displayName}: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh sessions", e)
            _error.value = e.message ?: "Failed to refresh sessions"
        } finally {
            _isRefreshing.value = false
        }
    }
}
```

Note: This duplicates `loadSessions()` logic but without `_isLoading = true` and without the first-load auto-expand logic. This is intentional — refresh should not show the full-screen loading spinner or reset expand state. Key differences from `loadSessions()`:
- Uses `_isRefreshing` instead of `_isLoading`
- No `finally` block auto-expand logic
- `finally` block only sets `_isRefreshing.value = false`
- Concurrent safety: if `loadSessions()` is already running (`_isLoading == true`), `refreshSessions()` should skip (check at start and return early)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListViewModel.kt
git commit -m "feat: add isRefreshing state and refreshSessions() to SessionListViewModel"
```

---

## Task 2: Add PullToRefreshBox to SessionListScreen

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListScreen.kt`

- [ ] **Step 1: Add import for PullToRefreshBox**

Add to imports section (after line 43 `BasicAlertDialog`):

```kotlin
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
```

- [ ] **Step 2: Wrap the content with PullToRefreshBox**

Replace the `Box(modifier = Modifier.fillMaxSize().padding(padding))` block (lines 169-270) with a `PullToRefreshBox` wrapping it. The structure becomes:

```kotlin
) { padding ->
    PullToRefreshBox(
        isRefreshing = uiState.isRefreshing,
        onRefresh = { viewModel.refreshSessions() },
        modifier = Modifier.fillMaxSize().padding(padding)
    ) {
        when {
            uiState.isLoading && uiState.treeNodes.isEmpty() -> {
                // ... existing PulsingDotsIndicator block unchanged
            }
            uiState.error != null && uiState.treeNodes.isEmpty() -> {
                // ... existing error block unchanged
            }
            uiState.treeNodes.isEmpty() -> {
                // ... existing empty block unchanged
            }
            else -> {
                // ... existing LazyColumn block unchanged
            }
        }
    }
}
```

Key: Remove the outer `Box(modifier = Modifier.fillMaxSize().padding(padding))` and replace with `PullToRefreshBox`. Keep all inner `when` branches exactly the same.

- [ ] **Step 3: Compile check**

```bash
.\gradlew :app:compileDevDebugKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Verify PullToRefreshBox structure**

Verify in code review (compile check only confirms syntax):
- PullToRefreshBox wraps the entire `when` block (replacing the outer `Box(modifier=fillMaxSize().padding(padding))`)
- `isRefreshing` bound to `uiState.isRefreshing` (not `uiState.isLoading`)
- `onRefresh` calls `viewModel.refreshSessions()` (not `viewModel.loadSessions()`)
- Empty list state: PullToRefreshBox wraps the empty-state Text — no pull gesture expected

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListScreen.kt
git commit -m "feat: add pull-to-refresh to session list page"
```

---

## Task 3: Add directory prefill tracking to ViewModel

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListViewModel.kt`

- [ ] **Step 1: Add `_lastToggledDirectory` flow**

After `_isRefreshing` (added in Task 1), add:

```kotlin
private val _lastToggledDirectory = MutableStateFlow<String?>(null)
```

- [ ] **Step 2: Update `toggleDirectory()` to track last toggled**

Replace the existing `toggleDirectory` method (lines 288-293):

```kotlin
fun toggleDirectory(path: String) {
    val normalized = path.replace('\\', '/')
    _lastToggledDirectory.value = normalized
    _expandedPaths.update { paths ->
        if (normalized in paths) paths - normalized else paths + normalized
    }
}
```

- [ ] **Step 3: Compute `prefillDirectory` in `combine`**

Add `_lastToggledDirectory` to the `combine` call — now 11 flows total (10 after Task 1, +1 here). Append after `_isRefreshing`:

```kotlin
val uiState: StateFlow<SessionListUiState> = combine(
    eventDispatcher.sessions,
    eventDispatcher.sessionStatuses,
    eventDispatcher.serverSessions,
    _isLoading,
    _error,
    _projects,
    _expandedPaths,
    _selectedIds,
    _baseDirectory,
    _isRefreshing,
    _lastToggledDirectory
) { values ->
```

Add extraction after `isRefreshing`:

```kotlin
val lastToggledDirectory = values[10] as String?
```

Compute `prefillDirectory` before `SessionListUiState(...)`:

```kotlin
val prefillDirectory = if (lastToggledDirectory != null && lastToggledDirectory in expandedPaths)
    lastToggledDirectory
else
    baseDirectory
```

Add `prefillDirectory` field to `SessionListUiState`:

```kotlin
data class SessionListUiState(
    val treeNodes: List<TreeNode> = emptyList(),
    val serverName: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val selectedIds: Set<String> = emptySet(),
    val isSelectionMode: Boolean = false,
    val baseDirectory: String? = null,
    val baseDirectories: Set<String> = emptySet(),
    val isRefreshing: Boolean = false,
    val prefillDirectory: String? = null,
)
```

Pass it in the constructor:

```kotlin
SessionListUiState(
    treeNodes = treeNodes,
    serverName = serverName,
    isLoading = isLoading,
    error = error,
    selectedIds = selectedIds,
    isSelectionMode = selectedIds.isNotEmpty(),
    baseDirectory = baseDirectory,
    baseDirectories = emptySet(),
    isRefreshing = isRefreshing,
    prefillDirectory = prefillDirectory,
)
```

- [ ] **Step 4: Compile check**

```bash
.\gradlew :app:compileDevDebugKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListViewModel.kt
git commit -m "feat: track last toggled directory and compute prefillDirectory"
```

---

## Task 4: Pass prefillDirectory from Screen to OpenProjectDialog

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListScreen.kt`

- [ ] **Step 1: Pass `initialDirectory` to FAB's OpenProjectDialog**

In the `showOpenProject` dialog block (lines 274-284), add `initialDirectory`:

```kotlin
// Open Project dialog
if (showOpenProject) {
    OpenProjectDialog(
        viewModel = viewModel,
        projects = emptyList(),
        initialDirectory = uiState.prefillDirectory,
        onSelect = { directory ->
            showOpenProject = false
            viewModel.createNewSession(directory = directory)
        },
        onDismiss = { showOpenProject = false }
    )
}
```

The `showBaseDirDialog` dialog (lines 287-296) does NOT get `initialDirectory` — it's for setting base directory, not creating sessions.

- [ ] **Step 2: Compile check**

```bash
.\gradlew :app:compileDevDebugKotlin
```

Expected: FAIL — `OpenProjectDialog` doesn't accept `initialDirectory` yet. This is expected; will be fixed in Task 5.

Actually, skip this compile check and proceed directly to Task 5. These two tasks should be implemented together.

- [ ] **Step 2 (revised): Commit together with Task 5**

---

## Task 5: Add `initialDirectory` parameter to OpenProjectDialog

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/OpenProjectDialog.kt`

- [ ] **Step 1: Add `initialDirectory` parameter to function signature**

Change the function signature (line 76-81):

```kotlin
@Composable
internal fun OpenProjectDialog(
    viewModel: SessionListViewModel,
    projects: List<Project>,
    initialDirectory: String? = null,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
```

- [ ] **Step 2: Update `LaunchedEffect(Unit)` to use `initialDirectory`**

Replace the initial load `LaunchedEffect(Unit)` (lines 137-144):

```kotlin
// Load home directory and initial listing
LaunchedEffect(Unit) {
    val home = viewModel.getHomeDirectory()
    homeDir = home
    val startDir = initialDirectory ?: home
    currentDir = startDir
    isLoading = true
    directories = viewModel.listDirectories(startDir)
    isLoading = false
}
```

Key change: `val startDir = initialDirectory ?: home` — if `initialDirectory` is provided, use it as the starting directory instead of home. `homeDir` is still set to `home` for tilde-replacement and search purposes.

- [ ] **Step 3: Compile check**

```bash
.\gradlew :app:compileDevDebugKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit (Tasks 4 + 5 together)**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListScreen.kt app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/OpenProjectDialog.kt
git commit -m "feat: prefill directory selector with last toggled directory"
```

---

## Task 6: Final build verification

- [ ] **Step 1: Full compile check**

```bash
.\gradlew :app:compileDevDebugKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run unit tests**

```bash
.\gradlew :app:testDevDebugUnitTest --rerun
```

Expected: All tests pass. If any pre-existing test fails (unrelated to our changes), report to user.

- [ ] **Step 3: Manual verification checklist**

Build and run the dev debug APK, then verify:
1. **Pull-to-refresh**: Session list with data → swipe down → refresh indicator appears → data updates → indicator disappears
2. **Refresh preserves state**: Expand directory → pull-to-refresh → directory remains expanded
3. **Directory prefill (expanded)**: Expand directory A → tap FAB "+" → OpenProjectDialog opens at directory A
4. **Directory prefill (collapsed)**: Collapse directory A → tap FAB "+" → OpenProjectDialog opens at baseDirectory (or home if no base)
5. **Directory prefill (never expanded)**: Fresh load, no directory toggled → tap FAB "+" → OpenProjectDialog opens at baseDirectory (or home)
6. **Error during refresh**: Disconnect network → pull-to-refresh → error message shown → refresh indicator disappears

- [ ] **Step 3: Verify commit history**

```bash
git log --oneline -5
```

Expected: 4-5 commits on `feature/session-page-refresh-and-dir-prefill`:
1. `docs: session page refresh & dir prefill design spec`
2. `docs: fix spec - resolve UiState computation contradiction`
3. `feat: add isRefreshing state and refreshSessions() to SessionListViewModel`
4. `feat: add pull-to-refresh to session list page`
5. `feat: track last toggled directory and compute prefillDirectory`
6. `feat: prefill directory selector with last toggled directory`
