# Session Page: Pull-to-Refresh & Directory Prefill

**Date:** 2026-06-02
**Branch:** `feature/session-page-refresh-and-dir-prefill`
**Worktree:** `.worktrees/session-page-refresh-and-dir-prefill`

---

## Overview

Two UX improvements to the session list page (`SessionListScreen`):

1. **Pull-to-refresh** — swipe down at list top to trigger full session reload.
2. **Directory prefill** — when creating a new session via FAB "+", prefill the directory selector with the last toggled directory (if still expanded) or fall back to the base directory.

---

## Feature 1: Pull-to-Refresh

### Behavior

- User swipes down on the session list → `PullToRefreshBox` indicator appears.
- Triggers the same session-fetching logic as `loadSessions()`, but via a dedicated `refreshSessions()` method that uses `_isRefreshing` (not `_isLoading`) and preserves current `expandedPaths` state.
- Indicator dismisses when loading completes.
- No incremental/delta logic — full reload replaces current data.

### Changes

#### `SessionListViewModel.kt`

- Add `private val _isRefreshing = MutableStateFlow(false)`.
- Add `fun refreshSessions()`: sets `_isRefreshing = true`, calls existing `loadSessions()` logic, sets `_isRefreshing = false` on completion.
- Expose `isRefreshing` in the `combine` that builds `SessionListUiState`.

#### `SessionListUiState`

- Add field: `val isRefreshing: Boolean = false`.

#### `SessionListScreen.kt`

- Wrap the entire `when { ... }` content block (currently inside `Box`) with Material3 `PullToRefreshBox`. This replaces the outer `Box`.
- Bind `isRefreshing` to `uiState.isRefreshing`.
- `onRefresh` callback calls `viewModel.refreshSessions()`.
- Refresh preserves current directory expansion state — `expandedPaths` is not modified during refresh.

#### Dependency

- Material3 BOM `2026.05.01` includes `PullToRefreshBox` (available since `material3` 1.3.0). Verify import at implementation time.

---

## Feature 2: Directory Prefill on New Session

### Behavior

- Track the **last directory the user toggled** (expanded or collapsed) via `toggleDirectory(path)`.
- When user clicks FAB "+":
  - If `lastToggledDirectory` is still in `expandedPaths` (i.e., currently expanded) → pass its full path as `initialDirectory` to `OpenProjectDialog`.
  - Otherwise → pass `baseDirectory` as `initialDirectory`.
  - If both are null → `OpenProjectDialog` starts from home (current behavior).

### Examples

| User Action | `lastToggledDirectory` | Expanded? | FAB Prefill |
|---|---|---|---|
| Expand `/home/proj-a` | `/home/proj-a` | Yes | `/home/proj-a` |
| Expand `/home/proj-b` | `/home/proj-b` | Yes | `/home/proj-b` |
| Collapse `/home/proj-b` | `/home/proj-b` | No | `baseDirectory` |
| Re-expand `/home/proj-a` | `/home/proj-a` | Yes | `/home/proj-a` |

### Changes

#### `SessionListViewModel.kt`

- Add `private val _lastToggledDirectory = MutableStateFlow<String?>(null)`.
- In `toggleDirectory(path)`: always set `_lastToggledDirectory.value = path` regardless of whether expanding or collapsing. The prefill logic checks `expandedPaths` at query time, not at recording time.
- In the `combine` that builds `SessionListUiState`, compute `prefillDirectory`:
  ```
  prefillDirectory = if (lastToggledDirectory != null && lastToggledDirectory in expandedPaths)
                        lastToggledDirectory
                      else
                        baseDirectory
  ```

#### `SessionListUiState`

- Add field: `val prefillDirectory: String? = null` — computed in ViewModel, Screen layer does no path logic.

#### `SessionListScreen.kt`

- FAB `onClick`: pass `uiState.prefillDirectory` directly to `OpenProjectDialog(initialDirectory = uiState.prefillDirectory, ...)`.

#### `OpenProjectDialog.kt`

- Add parameter: `initialDirectory: String? = null`.
- In `LaunchedEffect(Unit)`: if `initialDirectory` is not null, call `viewModel.listDirectories(initialDirectory)` and set `currentDir = initialDirectory`. Note: the variable in OpenProjectDialog is `currentDir`, not `currentPath`.
- If null, current behavior unchanged (start from home).

---

## Files Touched

| File | Change Type |
|---|---|
| `ui/screens/sessions/SessionListViewModel.kt` | Add refresh logic, lastToggledDirectory tracking, prefillDirectory computation, isRefreshing state |
| `ui/screens/sessions/SessionListScreen.kt` | Add PullToRefreshBox wrapper, pass prefillDirectory to Dialog |
| `ui/screens/sessions/components/OpenProjectDialog.kt` | Add initialDirectory parameter, init-time directory navigation |

---

## Out of Scope

- Incremental/delta refresh.
- Quick-create button on directory rows.
- Changes to chat screen or other screens.
- Changes to API layer.
