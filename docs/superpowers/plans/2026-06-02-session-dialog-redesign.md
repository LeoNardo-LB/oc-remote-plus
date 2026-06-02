# Session Dialog Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Unify session page dialogs with an AppDialog template, simplify OpenProjectDialog to a standard directory browser, and add horizontal scrolling for row titles.

**Architecture:** New `AppDialog` composable serves as the universal dialog shell (title + content + buttons). OpenProjectDialog removes search and becomes a pure directory browser. SessionRow/DirectoryTreeNode row titles gain horizontal scroll. All long-press dialogs migrate to AppDialog.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, AMOLED theme support (AmoledSurface), AlphaTokens/ShapeTokens

**Spec:** `docs/superpowers/specs/2026-06-02-session-dialog-redesign.md`

---

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `ui/components/AppDialog.kt` | **Create** | Unified dialog template + `AppDialogButtons` helper + `ButtonStyle` enum |
| `ui/screens/sessions/components/SessionRow.kt` | Modify | (1) horizontalScroll on title (2) SessionDetailsDialog → AppDialog |
| `ui/screens/sessions/components/DirectoryTreeNode.kt` | Modify | (1) horizontalScroll on displayName (2) DirectoryDetailsDialog → AppDialog |
| `ui/screens/sessions/components/OpenProjectDialog.kt` | Modify | Major rewrite: remove search, add path bar, add create session button |
| `ui/screens/sessions/SessionListScreen.kt` | Modify | RenameDialog/DeleteDialog → AppDialog |

---

### Task 1: Create AppDialog Template

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/ui/components/AppDialog.kt`

- [ ] **Step 1: Create AppDialog.kt**

Create the file with three public APIs:

```kotlin
package dev.minios.ocremote.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.minios.ocremote.ui.theme.AlphaTokens
import dev.minios.ocremote.ui.theme.ShapeTokens

enum class ButtonStyle { Primary, Secondary, Danger }

@Composable
fun AppDialog(
    onDismiss: () -> Unit,
    title: String,
    isAmoled: Boolean = isAmoledTheme(),
    content: @Composable ColumnScope.() -> Unit,
    buttons: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        AmoledSurface(
            isAmoledDark = isAmoled,
            shape = ShapeTokens.large,
            normalTonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(0.92f),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT)
                )

                // Content
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    content = content
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT)
                )

                // Buttons
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    content = buttons
                )
            }
        }
    }
}

/**
 * Convenience helper for auto-layout buttons inside AppDialog.
 * count <= 2: horizontal Row with equal weight
 * count > 2: vertical Column
 */
@Composable
fun ColumnScope.AppDialogButtons(
    buttons: List<Triple<String, ButtonStyle, () -> Unit>>,
) {
    if (buttons.size <= 2) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            buttons.forEach { (label, style, onClick) ->
                DialogButton(
                    label = label,
                    style = style,
                    onClick = onClick,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            buttons.forEach { (label, style, onClick) ->
                DialogButton(
                    label = label,
                    style = style,
                    onClick = onClick,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun DialogButton(
    label: String,
    style: ButtonStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (style) {
        ButtonStyle.Primary -> FilledTonalButton(
            onClick = onClick,
            modifier = modifier
        ) { Text(label) }
        ButtonStyle.Secondary -> OutlinedButton(
            onClick = onClick,
            modifier = modifier
        ) { Text(label) }
        ButtonStyle.Danger -> OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            color = MaterialTheme.colorScheme.error
        ) { Text(label, color = MaterialTheme.colorScheme.error) }
    }
}
```

- [ ] **Step 2: Compile check**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```
git add app/src/main/kotlin/dev/minios/ocremote/ui/components/AppDialog.kt
git commit -m "feat: add AppDialog unified dialog template"
```

---

### Task 2: Row Title Horizontal Scrolling

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/SessionRow.kt:99-104`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/DirectoryTreeNode.kt:76-81`

- [ ] **Step 1: Add imports to SessionRow.kt**

Add at the top of the import block:
```kotlin
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
```

- [ ] **Step 2: Replace title Text in SessionRow.kt (line 99-104)**

Replace:
```kotlin
Text(
    text = item.session.title ?: stringResource(R.string.session_untitled),
    style = MaterialTheme.typography.bodyMedium,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
)
```

With:
```kotlin
Text(
    text = item.session.title ?: stringResource(R.string.session_untitled),
    style = MaterialTheme.typography.bodyMedium,
    softWrap = false,
    modifier = Modifier.horizontalScroll(rememberScrollState())
)
```

Remove the now-unused `TextOverflow` import if no other code in the file uses it.

- [ ] **Step 3: Add imports to DirectoryTreeNode.kt**

Add at the top of the import block:
```kotlin
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
```

- [ ] **Step 4: Replace display name Text in DirectoryTreeNode.kt (line 76-81)**

Replace:
```kotlin
Text(
    text = node.displayName,
    style = MaterialTheme.typography.bodyMedium,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
)
```

With:
```kotlin
Text(
    text = node.displayName,
    style = MaterialTheme.typography.bodyMedium,
    softWrap = false,
    modifier = Modifier.horizontalScroll(rememberScrollState())
)
```

Remove the now-unused `TextOverflow` import if no other code in the file uses it.

- [ ] **Step 5: Compile check**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/SessionRow.kt app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/DirectoryTreeNode.kt
git commit -m "feat: horizontal scroll for session/directory row titles"
```

---

### Task 3: Migrate SessionDetailsDialog to AppDialog

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/SessionRow.kt:186-273`

- [ ] **Step 1: Rewrite SessionDetailsDialog composable (lines 186-273)**

Replace the entire `SessionDetailsDialog` function body. The new version uses `AppDialog` template:

```kotlin
@Composable
private fun SessionDetailsDialog(
    item: SessionItem,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onCopyId: () -> Unit,
    isAmoled: Boolean,
) {
    AppDialog(
        onDismiss = onDismiss,
        title = item.session.title ?: stringResource(R.string.session_untitled),
        isAmoled = isAmoled,
        content = {
            // Details section
            DetailRow(label = stringResource(R.string.session_id), value = item.session.id)
            DetailRow(label = stringResource(R.string.session_status), value = item.session.status)
            DetailRow(
                label = stringResource(R.string.session_created),
                value = item.session.createdAt?.formatDateTime() ?: "N/A"
            )
            DetailRow(
                label = stringResource(R.string.session_updated),
                value = item.session.updatedAt?.formatDateTime() ?: "N/A"
            )
            item.session.diffSummary?.let { diff ->
                DetailRow(label = "Diff", value = diff)
            }
        },
        buttons = {
            AppDialogButtons(
                buttons = listOf(
                    Triple(stringResource(R.string.session_copy_id), ButtonStyle.Secondary, onCopyId),
                    Triple(stringResource(R.string.session_rename), ButtonStyle.Secondary) { onDismiss(); onRename() },
                    Triple(stringResource(R.string.session_delete), ButtonStyle.Danger) { onDismiss(); onDelete() },
                )
            )
        }
    )
}
```

Key changes:
- `BasicAlertDialog` + `AmoledSurface` → `AppDialog`
- 4 text+icon click rows → `AppDialogButtons` with 3 `Triple(label, style, onClick)` entries
- "Close" button removed (handled by [x] in header)
- `@OptIn(ExperimentalMaterial3Api::class)` annotation can be removed if no other code needs it

- [ ] **Step 2: Add AppDialog imports**

Add to imports:
```kotlin
import dev.minios.ocremote.ui.components.AppDialog
import dev.minios.ocremote.ui.components.AppDialogButtons
import dev.minios.ocremote.ui.components.ButtonStyle
```

Remove unused imports: `BasicAlertDialog`, and any Material3 API imports only used by the old dialog (check before removing).

- [ ] **Step 3: Compile check**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/SessionRow.kt
git commit -m "refactor: migrate SessionDetailsDialog to AppDialog template"
```

---

### Task 4: Migrate DirectoryDetailsDialog to AppDialog

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/DirectoryTreeNode.kt:106-156`

- [ ] **Step 1: Rewrite DirectoryDetailsDialog composable (lines 106-156)**

Replace the entire `DirectoryDetailsDialog` function body:

```kotlin
@Composable
private fun DirectoryDetailsDialog(
    node: TreeNode.Directory,
    onDismiss: () -> Unit,
    onCopyPath: () -> Unit,
    isAmoled: Boolean,
) {
    AppDialog(
        onDismiss = onDismiss,
        title = node.displayName,
        isAmoled = isAmoled,
        content = {
            DetailRow(label = stringResource(R.string.session_path), value = node.path)
            DetailRow(label = stringResource(R.string.session_count), value = node.sessionCount.toString())
        },
        buttons = {
            AppDialogButtons(
                buttons = listOf(
                    Triple(stringResource(R.string.session_copy_path), ButtonStyle.Secondary, onCopyPath),
                )
            )
        }
    )
}
```

- [ ] **Step 2: Add AppDialog imports**

```kotlin
import dev.minios.ocremote.ui.components.AppDialog
import dev.minios.ocremote.ui.components.AppDialogButtons
import dev.minios.ocremote.ui.components.ButtonStyle
```

Remove unused: `BasicAlertDialog`, `@OptIn(ExperimentalMaterial3Api::class)` if no longer needed.

- [ ] **Step 3: Compile check**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/DirectoryTreeNode.kt
git commit -m "refactor: migrate DirectoryDetailsDialog to AppDialog template"
```

---

### Task 5: Migrate RenameDialog and DeleteDialog to AppDialog

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListScreen.kt:304-387`

- [ ] **Step 1: Add AppDialog imports to SessionListScreen.kt**

```kotlin
import dev.minios.ocremote.ui.components.AppDialog
import dev.minios.ocremote.ui.components.AppDialogButtons
import dev.minios.ocremote.ui.components.ButtonStyle
```

- [ ] **Step 2: Rewrite RenameDialog block (lines 304-347)**

Replace the `if (showRenameDialog) { BasicAlertDialog(...) }` block with:

```kotlin
if (showRenameDialog) {
    AppDialog(
        onDismiss = { showRenameDialog = false },
        title = stringResource(R.string.session_rename),
        content = {
            OutlinedTextField(
                value = renameText,
                onValueChange = { renameText = it },
                label = { Text(stringResource(R.string.session_rename_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        buttons = {
            AppDialogButtons(
                buttons = listOf(
                    Triple(stringResource(R.string.cancel), ButtonStyle.Secondary) { showRenameDialog = false },
                    Triple(stringResource(R.string.session_rename), ButtonStyle.Primary) {
                        viewModel.renameSession(renameSessionId, renameText)
                        showRenameDialog = false
                    },
                )
            )
        }
    )
}
```

- [ ] **Step 3: Rewrite DeleteDialog block (lines 349-387)**

Replace the `if (showDeleteDialog) { BasicAlertDialog(...) }` block with:

```kotlin
if (showDeleteDialog) {
    AppDialog(
        onDismiss = { showDeleteDialog = false },
        title = stringResource(R.string.session_delete),
        content = {
            Text(
                text = stringResource(R.string.session_delete_confirm, deleteSessionTitle),
                style = MaterialTheme.typography.bodyMedium
            )
        },
        buttons = {
            AppDialogButtons(
                buttons = listOf(
                    Triple(stringResource(R.string.cancel), ButtonStyle.Secondary) { showDeleteDialog = false },
                    Triple(stringResource(R.string.session_delete), ButtonStyle.Danger) {
                        viewModel.deleteSession(deleteSessionId)
                        showDeleteDialog = false
                    },
                )
            )
        }
    )
}
```

- [ ] **Step 4: Clean up unused imports**

Remove if no longer used elsewhere: `BasicAlertDialog`, `ButtonDefaults`, `AmoledSurface`, `AmoledCard` imports, `@OptIn(ExperimentalMaterial3Api::class)`.

- [ ] **Step 5: Compile check**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListScreen.kt
git commit -m "refactor: migrate RenameDialog and DeleteDialog to AppDialog template"
```

---

### Task 6: Rewrite OpenProjectDialog

This is the largest task. The dialog changes from a search-based browser to a standard directory browser.

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/OpenProjectDialog.kt`

- [ ] **Step 1: Rewrite OpenProjectDialog.kt**

The file should be fully rewritten. Key structure:

1. **State**: `currentDir`, `homeDir`, `directories` (keep), `isLoading` (keep). Remove all search-related state (`searchQuery`, `searchResults`, `pathNavigatedDirs`, `isSearching`, `focusRequester`).

2. **LaunchedEffect(Unit)**: Load home dir, set `currentDir = initialDirectory ?: home`, list directories. Remove search query initialization.

3. **LaunchedEffect(currentDir)**: Re-list directories on navigation.

4. **Keep**: `tildeReplace()`, create folder dialog state and logic.

5. **Remove**: `isPathLike()`, `resolvePath()`, search debounce LaunchedEffect.

6. **UI structure** using `AppDialog`:

```
AppDialog(
    title = "Open Project",
    content = {
        // Path bar
        Row(verticalAlignment = CenterVertically) {
            if (currentDir != homeDir) {
                IconButton(back arrow) { navigate to parent }
            }
            Text(currentDir, horizontalScroll)
        }
        // Directory list (weight(0.7f))
        LazyColumn(modifier = Modifier.weight(0.7f)) {
            items(directories) { node ->
                Row {
                    Icon(Folder)
                    Text(node.name)  // only child name, not full path
                    IconButton(ChevronRight) { currentDir = absPath }  // navigate into
                    // Row clickable -> onSelect(absPath)
                }
            }
        }
    },
    buttons = {
        AppDialogButtons(listOf(
            Triple("Create Session", Primary) { onSelect(currentDir) },
            Triple("Create Directory", Secondary) { showCreateFolderDialog = true },
        ))
    }
)
```

7. **Create folder dialog**: Also use `AppDialog` instead of `AlertDialog`.

8. **Imports**: Add `AppDialog`, `AppDialogButtons`, `ButtonStyle`, remove `BasicTextField`, `FocusRequester`, search-related icons. Add `horizontalScroll`, `rememberScrollState` for path bar.

- [ ] **Step 2: Compile check**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/OpenProjectDialog.kt
git commit -m "refactor: rewrite OpenProjectDialog as standard directory browser"
```

---

### Task 7: Final Verification

- [ ] **Step 1: Run full unit tests**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun`
Expected: All tests pass

- [ ] **Step 2: Run full build**

Run: `.\gradlew :app:assembleBetaRelease`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Manual verification checklist**

- [ ] Open OpenProjectDialog → path bar shows current directory
- [ ] Chevron-right navigates into subdirectory, path bar updates
- [ ] Row body click calls onSelect with the directory path
- [ ] Back arrow returns to parent directory
- [ ] "Create Session" button calls onSelect(currentDir)
- [ ] "Create Directory" button opens folder creation dialog
- [ ] Long-press session → details shown with Copy ID / Rename / Delete buttons
- [ ] Long-press directory → path shown with Copy Path button
- [ ] Session row title can be horizontally scrolled when long
- [ ] Directory row title can be horizontally scrolled when long
