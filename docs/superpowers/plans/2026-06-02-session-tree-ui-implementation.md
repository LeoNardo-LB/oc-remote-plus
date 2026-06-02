# Session Tree UI Improvements — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor dual-dialog system into unified AppDialog, fix 8 UI issues on session list screen.

**Architecture:** Single AppDialog with optional params (`showClose`, `showDividers`, `maxBodyHeight`, `scrollable`) replaces SettingsPickerDialog. Picker list logic extracted into reusable `AppPickerList` composable. All changes are UI-layer only (Compose), no ViewModel or Domain changes.

**Tech Stack:** Jetpack Compose, Material 3, Kotlin, Hilt

---

## File Structure

| File | Action | Purpose |
|------|--------|---------|
| `ui/components/AppDialog.kt` | **Modify** | Add params, change padding, new button styles |
| `ui/components/AppPickerList.kt` | **Create** | Reusable picker selection list (extracted from SettingsPickerDialog) |
| `ui/screens/settings/components/SettingsPickerDialog.kt` | **Delete** | Replaced by AppDialog + AppPickerList |
| `ui/screens/settings/components/ThemePickerDialog.kt` | **Modify** | Use AppDialog + AppPickerList |
| `ui/screens/settings/components/LanguagePickerDialog.kt` | **Modify** | Use AppDialog + AppPickerList |
| `ui/screens/settings/components/FontSizePickerDialog.kt` | **Modify** | Use AppDialog + AppPickerList |
| `ui/screens/settings/components/ImageCompressionDialog.kt` | **Modify** | Use AppDialog + AppPickerList (2 calls) |
| `ui/screens/settings/components/MessageCountPickerDialog.kt` | **Modify** | Use AppDialog + AppPickerList |
| `ui/screens/settings/components/ReconnectModePickerDialog.kt` | **Modify** | Use AppDialog + AppPickerList |
| `ui/screens/sessions/components/SessionRow.kt` | **Modify** | DetailRow→SelectionContainer, P7 icons |
| `ui/screens/sessions/components/DirectoryTreeNode.kt` | **Modify** | DetailRow→SelectionContainer, P8 active count |
| `ui/screens/sessions/components/DirectoryRow.kt` | **Modify** | P5 folder icon tint |
| `ui/screens/sessions/components/TreeNode.kt` | **Modify** | P8 `activeSessionCount` field |
| `ui/screens/sessions/SessionListScreen.kt` | **Modify** | P6 TopBar two-line |
| `ui/screens/sessions/components/OpenProjectDialog.kt` | **Modify** | Adapt to new AppDialog API |
| `res/values/strings.xml` | **Modify** | P8 new string resource |

16 files total (1 delete, 1 create, 14 modify).

---

### Task 1: Refactor AppDialog — Add Parameters + Change Padding + New Buttons

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/components/AppDialog.kt`

- [ ] **Step 1: Add new parameters to AppDialog signature**

Open `AppDialog.kt`. Change the function signature from:
```kotlin
fun AppDialog(
    onDismiss: () -> Unit,
    title: String,
    isAmoled: Boolean = isAmoledTheme(),
    content: @Composable ColumnScope.() -> Unit,
    buttons: @Composable ColumnScope.() -> Unit,
)
```
To:
```kotlin
@Composable
fun AppDialog(
    onDismiss: () -> Unit,
    title: String,
    isAmoled: Boolean = isAmoledTheme(),
    showClose: Boolean = true,
    showDividers: Boolean = true,
    maxBodyHeight: Dp? = null,
    scrollable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
    buttons: @Composable ColumnScope.() -> Unit,
)
```
Add import: `import androidx.compose.ui.unit.Dp`

- [ ] **Step 2: Change padding values**

In the header Row (currently `padding(start = 24.dp, end = 8.dp, top = 20.dp, bottom = 12.dp)`):
Change to `padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 8.dp)`.

In the content Column (currently `padding(horizontal = 24.dp, vertical = 16.dp)`):
Change to `padding(horizontal = 16.dp, vertical = 12.dp)`.

In the buttons Column (currently `padding(horizontal = 24.dp, vertical = 16.dp)`):
Change to `padding(horizontal = 16.dp, vertical = 12.dp)`.

- [ ] **Step 3: Make dividers conditional**

Wrap each `HorizontalDivider(...)` in `if (showDividers) { ... }`.

- [ ] **Step 4: Make close button conditional**

Wrap the `IconButton(onClick = onDismiss) { ... }` in `if (showClose) { ... }`.

- [ ] **Step 5: Support scrollable content**

Replace the content `Column`:
```kotlin
Column(
    modifier = Modifier
        .weight(1f, fill = false)
        .padding(horizontal = 16.dp, vertical = 12.dp),
    content = content,
)
```
With conditional:
```kotlin
if (scrollable) {
    val heightMod = if (maxBodyHeight != null) Modifier.heightIn(max = maxBodyHeight) else Modifier
    LazyColumn(
        modifier = Modifier
            .weight(1f, fill = false)
            .then(heightMod)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        item { content() }
    }
} else {
    Column(
        modifier = Modifier
            .weight(1f, fill = false)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        content = content,
    )
}
```
Add imports: `import androidx.compose.foundation.layout.heightIn`, `import androidx.compose.foundation.lazy.LazyColumn`, `import androidx.compose.foundation.lazy.items`

- [ ] **Step 6: Replace button implementations**

In `DialogButton()`:
```kotlin
ButtonStyle.Primary -> {
    FilledTonalButton(onClick = onClick, modifier = modifier) { Text(text) }
}
ButtonStyle.Secondary -> {
    OutlinedButton(onClick = onClick, modifier = modifier) { Text(text) }
}
ButtonStyle.Danger -> {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) { Text(text) }
}
```
Add imports: `import androidx.compose.material3.FilledTonalButton`, `import androidx.compose.material3.OutlinedButton`
Remove unused `TextButton` import.

- [ ] **Step 7: Verify compilation**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL (existing callers still work because new params have defaults)

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/components/AppDialog.kt
git commit -m "refactor: unify AppDialog with scrollable/picker support, M3 buttons, compact padding"
```

---

### Task 2: Create AppPickerList Component

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/ui/components/AppPickerList.kt`

- [ ] **Step 1: Write AppPickerList composable**

Create new file with content:

```kotlin
package dev.minios.ocremote.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.ui.theme.AlphaTokens
import dev.minios.ocremote.ui.theme.ShapeTokens
import dev.minios.ocremote.ui.screens.sessions.components.isAmoledTheme

/**
 * Reusable single-selection list for picker dialogs.
 * Handles highlight, check icon, and AMOLED theming.
 */
@Composable
fun <K> AppPickerList(
    options: List<Pair<K, String>>,
    selectedKey: K,
    onSelect: (K) -> Unit,
) {
    val isAmoled = isAmoledTheme()

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(options, key = { it.first.toString() }) { (key, label) ->
            val isSelected = key == selectedKey
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ShapeTokens.medium)
                    .background(
                        when {
                            isSelected && isAmoled -> Color.Black
                            isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = AlphaTokens.MUTED)
                            else -> Color.Transparent
                        }
                    )
                    .then(
                        if (isSelected && isAmoled) {
                            Modifier.border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.MEDIUM),
                                shape = ShapeTokens.medium,
                            )
                        } else Modifier
                    )
                    .clickable { onSelect(key) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                )
                if (isSelected) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/components/AppPickerList.kt
git commit -m "feat: add reusable AppPickerList component"
```

---

### Task 3: Migrate 6 Picker Dialogs + Delete SettingsPickerDialog

**Files:**
- Modify: `ui/screens/settings/components/ThemePickerDialog.kt`
- Modify: `ui/screens/settings/components/ReconnectModePickerDialog.kt`
- Modify: `ui/screens/settings/components/MessageCountPickerDialog.kt`
- Modify: `ui/screens/settings/components/LanguagePickerDialog.kt`
- Modify: `ui/screens/settings/components/ImageCompressionDialog.kt`
- Modify: `ui/screens/settings/components/FontSizePickerDialog.kt`
- Delete: `ui/screens/settings/components/SettingsPickerDialog.kt`

- [ ] **Step 1: Migrate ThemePickerDialog**

Replace `SettingsPickerDialog(...)` with:
```kotlin
AppDialog(
    onDismiss = onDismiss,
    title = stringResource(R.string.dialog_select_theme),
    showClose = false,
    showDividers = false,
    scrollable = true,
    maxBodyHeight = 480.dp,
    content = {
        AppPickerList(
            options = listOf(
                "system" to stringResource(R.string.settings_theme_system),
                "light" to stringResource(R.string.settings_theme_light),
                "dark" to stringResource(R.string.settings_theme_dark)
            ),
            selectedKey = currentTheme,
            onSelect = onThemeSelected,
        )
    },
    buttons = {
        AppDialogButtons(
            listOf(
                Triple(stringResource(R.string.cancel), ButtonStyle.Secondary, onDismiss),
            )
        )
    }
)
```
Update imports: remove `SettingsPickerDialog`, add `AppDialog`, `AppDialogButtons`, `ButtonStyle`, `AppPickerList`, `dp`.

- [ ] **Step 2: Migrate ReconnectModePickerDialog**

Same pattern, title = `stringResource(R.string.dialog_select_reconnect_mode)`, options = aggressive/normal/conservative.

- [ ] **Step 3: Migrate MessageCountPickerDialog**

Same pattern, title = `stringResource(R.string.settings_initial_messages)`, options = `listOf(20, 50, 100, 200).map { it to "$it" }`.

- [ ] **Step 4: Migrate LanguagePickerDialog**

Same pattern, title = `stringResource(R.string.dialog_select_language)`, options = all languages. **maxBodyHeight = 520.dp**.

- [ ] **Step 5: Migrate FontSizePickerDialog**

Same pattern, title = `stringResource(R.string.settings_font_size)`, options = small/medium/large.

- [ ] **Step 6: Migrate ImageCompressionDialog (2 calls)**

Both `ImageCompressionMaxSideDialog` and `ImageCompressionQualityDialog` migrate to same pattern.

- [ ] **Step 7: Delete SettingsPickerDialog.kt**

Delete: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/settings/components/SettingsPickerDialog.kt`

- [ ] **Step 8: Verify compilation**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/settings/components/
git commit -m "refactor: migrate 6 picker dialogs to unified AppDialog, remove SettingsPickerDialog"
```

---

### Task 4: DetailRow → SelectionContainer Table

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/SessionRow.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/DirectoryTreeNode.kt`

- [ ] **Step 1: Rewrite DetailRow in SessionRow.kt**

Find the two `DetailRow` calls (SessionDetailsDialog content block, currently lines 193-218). Replace the `Column(verticalArrangement = Arrangement.spacedBy(4.dp))` with:

```kotlin
SelectionContainer {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        DetailRow(stringResource(R.string.session_details_id), item.session.id)
        DetailRow(
            stringResource(R.string.session_details_status),
            when (item.status) {
                is SessionStatus.Busy -> stringResource(R.string.session_status_busy)
                is SessionStatus.Retry -> stringResource(R.string.session_status_retry)
                else -> stringResource(R.string.session_status_idle)
            }
        )
        DetailRow(
            stringResource(R.string.session_details_created),
            dateFormat.format(Date(item.session.time.created))
        )
        DetailRow(
            stringResource(R.string.session_details_updated),
            dateFormat.format(Date(item.session.time.updated))
        )
        val summary = item.session.summary
        if (summary != null) {
            DetailRow(
                "Diff",
                "+${summary.additions} -${summary.deletions} (${summary.files} files)"
            )
        }
    }
}
```

And rewrite the private `DetailRow` function from:
```kotlin
@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}
```
To:
```kotlin
@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.3f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.7f),
        )
    }
}
```
Add imports: `import androidx.compose.foundation.text.selection.SelectionContainer`, `import androidx.compose.foundation.layout.Spacer`, `import androidx.compose.foundation.layout.width`

- [ ] **Step 2: Rewrite DetailRow in DirectoryTreeNode.kt**

In `DirectoryDetailsDialog`, replace the content block. Currently:
```kotlin
content = {
    DetailRow(label = stringResource(R.string.session_path), value = node.path)
    DetailRow(label = stringResource(R.string.session_count), value = node.sessionCount.toString())
},
```

Change to:
```kotlin
content = {
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            DetailRow(label = stringResource(R.string.session_path), value = node.path)
            DetailRow(label = stringResource(R.string.session_count), value = node.sessionCount.toString())
        }
    }
},
```

Same `DetailRow` rewrite as above. Add imports: `SelectionContainer`, `Spacer`, `width`. Also add `Column`, `Arrangement`.

- [ ] **Step 3: Verify compilation**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/SessionRow.kt
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/DirectoryTreeNode.kt
git commit -m "refactor: change DetailRow to horizontal table with SelectionContainer for long-press copy"
```

---

### Task 5: P5 — DirectoryRow Folder Icon Tint

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/DirectoryRow.kt`

- [ ] **Step 1: Change folder icon tint**

Line 59, change:
```kotlin
tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
```
To:
```kotlin
tint = MaterialTheme.colorScheme.primary
```

Also remove the unused `AlphaTokens` import if no other usage in this file (check — it's also used in lines 69 and 80 for the annotated string, so keep it).

- [ ] **Step 2: Verify compilation**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/DirectoryRow.kt
git commit -m "fix: match FAB dialog folder icon tint to directory tree (primary)"
```

---

### Task 6: P6 — TopAppBar Two-Line Layout

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListScreen.kt`

- [ ] **Step 1: Replace TopAppBar title with two-line Column**

In `SessionListScreen.kt`, find the `TopAppBar` (lines 109-138). Change the `title` block from:
```kotlin
title = {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(ShapeTokens.small)
            .clickable { showBaseDirDialog = true }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = uiState.baseDirectory?.let { dir ->
                dir.replace('\\', '/').trimEnd('/')
            } ?: uiState.serverName.ifEmpty { stringResource(R.string.sessions_title) },
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            imageVector = Icons.Default.ArrowDropDown,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
},
```

To:
```kotlin
title = {
    Column(
        modifier = Modifier
            .clip(ShapeTokens.small)
            .clickable { showBaseDirDialog = true }
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = uiState.serverName.ifEmpty { stringResource(R.string.sessions_title) },
            style = MaterialTheme.typography.titleMedium,
        )
        if (uiState.baseDirectory != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = uiState.baseDirectory!!.replace('\\', '/').trimEnd('/'),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(2.dp))
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            // No base directory selected, still show arrow for consistency
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
},
```

Note: The old code merged server name into the base directory text — now we separate them. Line 1 = server name, line 2 = base directory. Arrow now on line 2.

Also remove unused `Row` import if no longer needed (check — it's used elsewhere in the file, so keep).

- [ ] **Step 2: Remove unused imports**

If `Spacer` import line was only used in the old title block (check — it's at line 14, may still be used in FAB area or elsewhere). Keep it.

- [ ] **Step 3: Verify compilation**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListScreen.kt
git commit -m "feat: show server name + base directory in two-line TopAppBar"
```

---

### Task 7: P7 — Session Status Icons

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/SessionRow.kt`

- [ ] **Step 1: Change icon and color mapping**

Find the icon block (lines 74-85). Change from:
```kotlin
val statusIconColor = when (item.status) {
    is SessionStatus.Busy -> MaterialTheme.colorScheme.tertiary
    is SessionStatus.Retry -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
}
Icon(
    imageVector = Icons.Outlined.ChatBubbleOutline,
    contentDescription = null,
    modifier = Modifier.size(20.dp),
    tint = statusIconColor,
)
```
To:
```kotlin
val (statusIcon, statusIconColor) = when (item.status) {
    is SessionStatus.Busy -> Icons.Filled.ChatBubble to MaterialTheme.colorScheme.tertiary
    is SessionStatus.Retry -> Icons.Filled.ErrorOutline to MaterialTheme.colorScheme.error
    else -> Icons.Outlined.ChatBubbleOutline to MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.FAINT)
}
Icon(
    imageVector = statusIcon,
    contentDescription = null,
    modifier = Modifier.size(20.dp),
    tint = statusIconColor,
)
```

Update imports: add `import androidx.compose.material.icons.filled.ChatBubble`, add `import androidx.compose.material.icons.filled.ErrorOutline`.

Remove the old import for `ChatBubbleOutline` from `Icons.Outlined` — actually check: the Idle state still uses `Icons.Outlined.ChatBubbleOutline`, so keep the `Icons.Outlined` import (already imported at line 18).

- [ ] **Step 2: Update status label colors too (for consistency)**

In the status label block (lines 106-123), the `Busy` label uses `MaterialTheme.colorScheme.tertiary` — this is correct and should stay. The `Retry` label uses `error` — correct.

- [ ] **Step 3: Verify compilation**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/SessionRow.kt
git commit -m "feat: use Filled/Outlined/ErrorOutline icons for Busy/Idle/Retry session states"
```

---

### Task 8: P8 — Active Session Count on Directory Items

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/TreeNode.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/DirectoryTreeNode.kt`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Add activeSessionCount to TreeNode.Directory**

In `TreeNode.kt`, change the `Directory` data class:
```kotlin
data class Directory(
    override val id: String,
    val path: String,
    val displayName: String,
    val sessionCount: Int,
    val activeSessionCount: Int,  // ← ADD
    val isExpanded: Boolean,
) : TreeNode
```

- [ ] **Step 2: Compute activeSessionCount in buildTreeNodes()**

In the `for ((dirKey, dirSessionList) in dirSessions)` loop (line 79), before creating `TreeNode.Directory`, add:
```kotlin
val activeCount = dirSessionList.count { session ->
    val status = statuses[session.id]
    status is SessionStatus.Busy
}
```

Then update the `TreeNode.Directory` constructor call to include `activeSessionCount = activeCount`:
```kotlin
result.add(TreeNode.Directory(
    id = dirKey,
    path = fullPath,
    displayName = fullPath,
    sessionCount = dirSessionList.size,
    activeSessionCount = activeCount,
    isExpanded = isExpanded,
))
```

- [ ] **Step 3: Add string resource**

In `app/src/main/res/values/strings.xml`, add near the existing `directory_session_count` entry:

```xml
<string name="directory_session_count_active">%1$d/%2$d sessions active</string>
```

Check existing entry with `grep directory_session_count` to confirm exact file location.

- [ ] **Step 4: Update UI in DirectoryTreeNode.kt**

Find the session count display (lines 77-86). Replace:
```kotlin
Row(
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Text(
        text = stringResource(R.string.directory_session_count, node.sessionCount),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
    )
}
```
With:
```kotlin
Row(
    horizontalArrangement = Arrangement.spacedBy(2.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    if (node.activeSessionCount > 0) {
        Text(
            text = "${node.activeSessionCount}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        )
        Text(
            text = "/${node.sessionCount} ${stringResource(R.string.sessions_active)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
        )
    } else {
        Text(
            text = stringResource(R.string.directory_session_count, node.sessionCount),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
        )
    }
}
```

Add a new string resource for "active" part. Run `grep directory_session_count` first to see the existing resource, then add:

```xml
<string name="sessions_active">active</string>
```

(Alternatively adjust `directory_session_count` — the spec says "x/y sessions active" format. Since we need i18n flexibility, a separate `sessions_active` string is cleaner.)

- [ ] **Step 5: Verify compilation**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/TreeNode.kt
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/DirectoryTreeNode.kt
git add app/src/main/res/values/strings.xml
git commit -m "feat: show active session count on directory items (x/y sessions active)"
```

---

### Task 9: Final Verification + OpenProjectDialog Adaption

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/OpenProjectDialog.kt`

- [ ] **Step 1: Adapt OpenProjectDialog to new AppDialog API**

`OpenProjectDialog` calls `AppDialog(...)` at lines 105 and 214. The new params have defaults, so they should still work without changes. Verify by compiling — if `compileDevDebugKotlin` passes, no changes needed.

If there are issues (e.g., scrollable content for the create-folder dialog), address them.

- [ ] **Step 2: Run full compilation**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Check unit tests (optional — no UI tests exist for these components)**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun`
Expected: All existing tests pass (no new tests expected)

- [ ] **Step 4: Commit**

```bash
git add .
git commit -m "chore: verify all changes compile and tests pass"
```

---

## Verification Checklist

- [ ] `.\gradlew :app:compileDevDebugKotlin` passes
- [ ] `.\gradlew :app:testDevDebugUnitTest --rerun` passes
- [ ] AppDialog shows: Primary=FilledTonalButton, Secondary=OutlinedButton, Danger=OutlinedButton(error)
- [ ] Picker dialogs use unified AppDialog with consistent styling
- [ ] Long-press in session/directory details dialog triggers text selection
- [ ] TopAppBar shows server name (line 1) + base directory path (line 2)
- [ ] Busy sessions show filled chat icon, Idle show outlined, Retry show error icon
- [ ] Directory items show "3/5 sessions active" (active count in primary color)
- [ ] FAB dialog folder icons are primary color
