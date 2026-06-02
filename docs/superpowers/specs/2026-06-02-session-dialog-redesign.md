# Session Dialog Redesign

Date: 2026-06-02

## Summary

Four UI improvements for the session list page:
1. Session/directory row titles support horizontal scrolling instead of truncation
2. OpenProjectDialog reimagined as a standard directory browser (replaces search-based dialog)
3. Long-press detail dialogs unified with a common `AppDialog` template
4. New "Create Session" action in the directory browser

## Design Decisions

- **D1**: Search functionality removed from OpenProjectDialog — simplified to pure directory browsing
- **D2**: Directory list shows only child directory names, not full paths
- **D3**: All dialogs use a unified `AppDialog` composable template
- **D4**: Buttons auto-layout: ≤2 horizontal Row, >2 vertical Column; danger actions use error-colored OutlinedButton
- **D5**: Current path displayed in a horizontally scrollable read-only bar (replaces search input)
- **D6**: Directory list occupies ~70% of dialog content area via `Modifier.weight(0.7f)`
- **D7**: Row titles (SessionRow, DirectoryTreeNode) use `horizontalScroll` instead of `TextOverflow.Ellipsis`

## Change 0: Row Title Horizontal Scrolling

### Affected components

1. **SessionRow** (`SessionRow.kt`): session title text
2. **DirectoryTreeNode** (`DirectoryTreeNode.kt`): directory display name text

### Implementation

- Replace `maxLines = 1, overflow = TextOverflow.Ellipsis` with `Modifier.horizontalScroll(rememberScrollState())` + `softWrap = false`
- Keep single-line display, but allow horizontal drag to reveal full text
- This is the same approach already used in `DirectoryRow.kt`

## Change 1: AppDialog Template

### New component: `AppDialog`

Location: `app/src/main/kotlin/dev/minios/ocremote/ui/components/AppDialog.kt`

```kotlin
@Composable
fun AppDialog(
    onDismiss: () -> Unit,
    title: String,
    isAmoled: Boolean = isAmoledTheme(),
    content: @Composable ColumnScope.() -> Unit,
    buttons: @Composable ColumnScope.() -> Unit,
)
```

Layout structure:
```
Dialog (usePlatformDefaultWidth = false)
AmoledSurface (isAmoledDark = isAmoled, shape = ShapeTokens.large, width = 92%, height = auto)
Column(fillMaxWidth)
  Header Row: title text (headlineSmall) + Close [x] IconButton
  HorizontalDivider
  Content Column: weight(1f)
  HorizontalDivider
  Buttons Column: padding 16dp
```

### Button layout helper: `AppDialogButtons`

```kotlin
@Composable
fun ColumnScope.AppDialogButtons(
    buttons: List<Pair<String, () -> Unit>>,
    styles: List<ButtonStyle> = emptyList(), // defaults to Secondary
)

enum ButtonStyle { Primary, Secondary, Danger }
```

Auto-layout behavior:
- If 1 button: full width `OutlinedButton`, centered
- If 2 buttons: Row with equal `weight(1f)`, horizontal arrangement
- If >2 buttons: vertical Column, each `fillMaxWidth`

### Button style convention

| Type | Component | Usage |
|------|-----------|-------|
| Primary | `FilledTonalButton` | Create, confirm |
| Secondary | `OutlinedButton` | Copy, rename |
| Danger | `OutlinedButton(colorScheme.error)` | Delete |

## Change 2: OpenProjectDialog Redesign

### Current vs New

| Item | Before | After |
|------|--------|-------|
| Search input | Yes (path nav + fuzzy search) | Removed |
| Path display | Small breadcrumb below input | Prominent scrollable path bar |
| Directory list | Shows full path per item | Shows only child directory name |
| Create folder | Floating FAB (bottom-right) | Fixed bottom button |
| Create session | Not available | New fixed bottom button |
| Search-related code | `isPathLike`, `resolvePath`, `searchDirectories`, debounce | All removed |

### New layout

Uses `AppDialog` template. Content slot contains:

1. **Path bar** (top of content area)
   - Back arrow `IconButton` (←) visible when `currentDir != homeDir`
   - Click back arrow → navigate to parent: `currentDir = parentFile.absolutePath`
   - Read-only path text with `horizontalScroll(rememberScrollState())`
   - Shows `tildeReplace(currentDir)` for short display

2. **Directory list** (content area, `weight(0.7f)`)
   - `LazyColumn` of child directories
   - Each row: folder icon + directory name only (not full path) + chevron-right icon
   - Click row body → select this directory (call `onSelect(absPath)`)
   - Click chevron-right → navigate into this directory (update currentDir, refresh list)
   - Empty state: centered hint text
   - Two click areas: Row `Modifier.clickable { onSelect }` + Chevron `Modifier.clickable { navigate }`

3. **Bottom buttons** (passed to `AppDialog` buttons slot)
   - "Create Session" (`FilledTonalButton`) — calls `onSelect(currentDir)`
   - "Create Directory" (`OutlinedButton`) — opens folder creation dialog

### Create Folder Dialog

Uses `AppDialog` template:
- Title: "Create Folder"
- Content: `OutlinedTextField` with label "Folder Name", error text below
- Buttons: "Create" (Primary) + "Cancel" (Secondary) — 2 buttons, horizontal
- On success: dismiss dialog, refresh current directory listing

### Code to remove

From `OpenProjectDialog.kt`:
- `searchQuery` state and all related logic
- `isPathLike()`, `resolvePath()` functions
- `searchResults`, `pathNavigatedDirs` state
- Search debounce `LaunchedEffect(searchQuery)`
- `isSearching` flag
- Focus requester for search field
- All search UI code in the `when` block

### Code to keep

- `currentDir`, `homeDir`, `directories` state
- `listDirectories()` calls
- `tildeReplace()` function
- `LaunchedEffect(Unit)` for initial load
- `LaunchedEffect(currentDir)` for re-listing on navigation
- Create folder dialog logic (migrate to AppDialog template)

## Change 3: Long-Press Dialog Redesign

### Session Details Dialog (SessionDetailsDialog)

Uses `AppDialog` template:
- Title: session title (scrollable)
- Content: ID, status, created/updated time, diff summary (same info as current)
- Buttons (3 → vertical Column per D4 rule):
  - Copy ID (Secondary)
  - Rename (Secondary)
  - Delete (Danger, error color)
- Close handled by [x] in header — remove "Close" button

### Directory Details Dialog (DirectoryDetailsDialog)

Uses `AppDialog` template:
- Title: directory name
- Content: full path (scrollable) + session count (from existing `TreeNode.Directory.sessionCount`)
- Buttons: Copy Path (single Secondary button, full width)
- Close handled by [x] in header

### Rename Dialog

Uses `AppDialog` template:
- Title: "Rename Session"
- Content: `OutlinedTextField` pre-filled with current session title
- Buttons: "Rename" (Primary) + "Cancel" (Secondary) — 2 buttons, horizontal

### Delete Confirmation Dialog

Uses `AppDialog` template:
- Title: "Delete Session"
- Content: confirmation text "Are you sure you want to delete this session?"
- Buttons: "Delete" (Danger) + "Cancel" (Secondary) — 2 buttons, horizontal

## Files to Modify

| File | Change |
|------|--------|
| `ui/components/AppDialog.kt` | **New file** — unified dialog template + `AppDialogButtons` helper |
| `ui/screens/sessions/components/OpenProjectDialog.kt` | Major rewrite: remove search, add path bar, simplify directory list, add create session button |
| `ui/screens/sessions/components/SessionRow.kt` | (1) Add `horizontalScroll` to title text; (2) Rewrite `SessionDetailsDialog` to use `AppDialog` |
| `ui/screens/sessions/components/DirectoryTreeNode.kt` | (1) Add `horizontalScroll` to display name text; (2) Rewrite `DirectoryDetailsDialog` to use `AppDialog` |
| `ui/screens/sessions/SessionListScreen.kt` | Rewrite `RenameDialog` / `DeleteDialog` to use `AppDialog` template |

## Verification

- `.\gradlew :app:compileDevDebugKotlin` — compile check after each file edit (also verifies all search code removed with no dangling references)
- `.\gradlew :app:testDevDebugUnitTest --rerun` — all unit tests pass
- Manual: OpenProjectDialog → browse directories (chevron navigates, body selects, back arrow returns), create session, create folder
- Manual: Long-press session → verify info displayed, buttons work (Copy ID, Rename, Delete with error color)
- Manual: Long-press directory → verify path shown, Copy Path works
- Manual: Row titles → long directory names / session titles can be horizontally scrolled
