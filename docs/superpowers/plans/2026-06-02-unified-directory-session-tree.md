# Unified Directory Tree + Session List Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the dual-mode SessionListScreen with a single LazyColumn showing a collapsible directory tree + session list, using three-dot menus instead of swipe gestures.

**Architecture:** A `TreeNode` sealed interface (Directory | Session) drives a flat list consumed by `LazyColumn`. The ViewModel holds `expandedPaths: Set<String>` and derives `treeNodes: List<TreeNode>` from sessions + expanded state. Directory nodes are expandable rows with indent; session nodes use the existing SessionRow (refactored to remove SwipeToDismiss and add DropdownMenu).

**Tech Stack:** Kotlin, Jetpack Compose (Material3), Hilt, StateFlow, existing AlphaTokens/ShapeTokens/AppMotion theme tokens.

**Spec:** `docs/superpowers/specs/2026-06-02-unified-directory-session-tree-design.md`

---

## Prerequisites

- **JDK 21** — `build.gradle.kts` sets `jvmToolchain(21)`. Verify: `java -version`
- **Gradle proxy** — `gradle.properties` hardcodes `127.0.0.1:7897` HTTP proxy. If proxy is unreachable, comment out the 4 `systemProp.*` lines before building
- **KSP** — Project uses KSP (not kapt) for Hilt annotation processing. No special action needed
- **Compose BOM 2026.05.01** — Provides Material3 1.3+ (DropdownMenu, BasicAlertDialog stable)

---

## File Structure

> **Path prefix:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/` for Kotlin files, `app/src/main/res/values/` for resources, `app/src/test/kotlin/dev/minios/ocremote/ui/screens/sessions/` for tests.

| File | Action | Responsibility |
|------|--------|----------------|
| `sessions/components/TreeNode.kt` | Create | `TreeNode` sealed interface + `buildTreeNodes()` pure function |
| `sessions/SessionListViewModel.kt` | Major refactor | Remove `ListMode`, add `expandedPaths` + `treeNodes`, remove mode navigation |
| `sessions/components/SessionRow.kt` | Rewrite | Remove `SwipeToDismissBox`, add three-dot `DropdownMenu` |
| `sessions/components/DirectoryTreeNode.kt` | Create (replaces ProjectGroupRow) | Expandable directory row with indent, arrow, folder icon, session count, menu |
| `sessions/SessionListScreen.kt` | Rewrite | Single `LazyColumn` with `items(treeNodes)`, remove mode switching |
| `sessions/components/ProjectGroupRow.kt` | Delete | Replaced by `DirectoryTreeNode.kt` |
| `sessions/components/ProjectHeader.kt` | Delete | Dead code, unused |
| `app/src/main/res/values/strings.xml` | Modify | Add new menu strings |
| Test file | Create | Unit tests for `buildTreeNodes()` |

### Task Dependencies

```
Task 1 (TreeNode model) ──→ Task 2 (Unit tests)
     │
     ├──→ Task 4 (DirectoryTreeNode) ──┐
     │                                  ├──→ Task 6 (ViewModel) ──→ Task 7 (Screen) ──→ Task 8 (Cleanup)
     ├──→ Task 3 (Strings) ────────────┘         ↑
     │                                           │
     └──→ Task 5 (SessionRow) ──────────────────┘

Parallelizable: Task 3 || Task 4 || Task 5 (after Task 1)
Sequential: Task 6 → Task 7 → Task 8 → Task 9
```

---

### Task 1: Create TreeNode Model + Tree Construction Function

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/TreeNode.kt`

- [ ] **Step 1: Create the TreeNode sealed interface and buildTreeNodes function**

Create file `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/TreeNode.kt`:

```kotlin
package dev.minios.ocremote.ui.screens.sessions.components

import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.domain.model.SessionStatus
import dev.minios.ocremote.ui.screens.sessions.SessionItem

/**
 * Sealed interface for tree nodes displayed in the session list.
 * Directories are expandable containers; Sessions are leaf items.
 */
sealed interface TreeNode {
    val id: String

    data class Directory(
        override val id: String,           // full path string (used as unique key)
        val path: String,                  // raw file path
        val displayName: String,           // leaf segment for display
        val depth: Int,                    // indentation level (0 = root)
        val childDirectoryCount: Int,      // direct child directories
        val sessionCount: Int,             // sessions in this exact directory
        val totalSessionCount: Int,        // sessions including subdirectories
        val isExpanded: Boolean,
    ) : TreeNode

    data class Session(
        override val id: String,           // sessionId
        val session: SessionItem,          // existing session wrapper
        val depth: Int,                    // indentation level (parent depth + 1)
    ) : TreeNode
}

/**
 * Build a flat list of TreeNodes from sessions and expanded paths.
 *
 * Algorithm:
 * 1. Collect all unique directory paths from sessions
 * 2. Split each path by '/' and generate all ancestor segments
 * 3. Build a tree of Directory nodes, deduplicating by path
 * 4. Assign depth (root = 0)
 * 5. Count sessions per directory (exact match + recursive)
 * 6. Flatten: emit Directory node, if expanded emit children then sessions
 *
 * @param sessions All sessions for this server (already filtered)
 * @param expandedPaths Set of directory paths currently expanded
 * @param homeDir Server home directory for display name simplification
 * @return Flat list of TreeNodes in display order
 */
fun buildTreeNodes(
    sessions: List<Session>,
    expandedPaths: Set<String>,
    homeDir: String?,
    statuses: Map<String, SessionStatus> = emptyMap(),
): List<TreeNode> {
    // 1. Collect all unique directory paths
    val allPaths = sessions.map { it.directory }.filter { it.isNotBlank() }.toSet()

    // 2. Generate all ancestor segments
    val allSegments = mutableSetOf<String>()
    for (path in allPaths) {
        var current = path.trimEnd('/')
        while (current.isNotEmpty()) {
            allSegments.add(current)
            val lastSlash = current.lastIndexOf('/')
            if (lastSlash <= 0) break
            current = current.substring(0, lastSlash)
        }
    }

    // 3. Build directory info map: path → (displayName, depth, childDirs, sessionCount, totalSessionCount)
    val dirInfo = mutableMapOf<String, DirInfo>()
    for (path in allSegments) {
        val leaf = if (homeDir != null && path == homeDir.trimEnd('/')) {
            "~"
        } else {
            path.substringAfterLast('/')
        }
        val depth = if (path.startsWith("/")) path.count { it == '/' } - 1 else path.count { it == '/' }
        dirInfo[path] = DirInfo(
            displayName = leaf.ifBlank { path },
            depth = depth,
        )
    }

    // 4. Count sessions per directory (exact match)
    val exactCount = mutableMapOf<String, Int>()
    for (session in sessions) {
        val dir = session.directory
        if (dir.isNotBlank()) {
            exactCount[dir] = (exactCount[dir] ?: 0) + 1
        }
    }

    // 5. Count child directories
    val childDirs = mutableMapOf<String, Int>()
    for (path in allSegments) {
        val parent = path.substringBeforeLast('/')
        if (parent in allSegments) {
            childDirs[parent] = (childDirs[parent] ?: 0) + 1
        }
    }

    // 6. Compute total session count (recursive) using bottom-up accumulation
    val sortedPaths = allSegments.sortedBy { it.count { c -> c == '/' } }
    val totalSessionCount = mutableMapOf<String, Int>()
    for (path in sortedPaths) {
        totalSessionCount[path] = (exactCount[path] ?: 0)
    }
    // Propagate counts upward
    for (path in sortedPaths.sortedByDescending { it.count { c -> c == '/' } }) {
        val parent = path.substringBeforeLast('/')
        if (parent in allSegments && parent != path) {
            totalSessionCount[parent] = (totalSessionCount[parent] ?: 0) + (totalSessionCount[path] ?: 0)
        }
    }

    // 7. Flatten tree respecting visit order
    val result = mutableListOf<TreeNode>()
    val rootPaths = allSegments.filter { path ->
        val parent = path.substringBeforeLast('/')
        parent !in allSegments
    }.sortedBy { it }

    for (rootPath in rootPaths) {
        flattenSubtree(rootPath, allSegments, sessions, expandedPaths, exactCount, totalSessionCount, childDirs, dirInfo, homeDir, statuses, result)
    }

    // Handle sessions with empty/blank/root directory (no tree node)
    val noDirSessions = sessions.filter { it.directory.isBlank() || it.directory == "/" }
    for (session in noDirSessions) {
        result.add(TreeNode.Session(
            id = session.id,
            session = SessionItem(session = session, status = statuses[session.id] ?: SessionStatus.Idle),
            depth = 0,
        ))
    }

    return result
}

private data class DirInfo(
    val displayName: String,
    val depth: Int,
)

private fun flattenSubtree(
    path: String,
    allSegments: Set<String>,
    sessions: List<Session>,
    expandedPaths: Set<String>,
    exactCount: Map<String, Int>,
    totalSessionCount: Map<String, Int>,
    childDirs: Map<String, Int>,
    dirInfo: Map<String, DirInfo>,
    homeDir: String?,
    statuses: Map<String, SessionStatus>,
    result: MutableList<TreeNode>,
) {
    val info = dirInfo[path] ?: return

    // Emit directory node
    result.add(TreeNode.Directory(
        id = path,
        path = path,
        displayName = info.displayName,
        depth = info.depth,
        childDirectoryCount = childDirs[path] ?: 0,
        sessionCount = exactCount[path] ?: 0,
        totalSessionCount = totalSessionCount[path] ?: 0,
        isExpanded = path in expandedPaths,
    ))

    // If expanded, emit child directories (sorted) then sessions
    if (path in expandedPaths) {
        val childPaths = allSegments
            .filter { child ->
                child != path &&
                child.substringBeforeLast('/') == path &&
                child.startsWith("$path/")
            }
            .sortedBy { it }

        for (childPath in childPaths) {
            flattenSubtree(childPath, allSegments, sessions, expandedPaths, exactCount, totalSessionCount, childDirs, dirInfo, homeDir, statuses, result)
        }

        // Sessions in this directory (exact match, sorted by updated desc)
        val dirSessions = sessions
            .filter { it.directory == path }
            .sortedByDescending { it.time.updated }

        for (session in dirSessions) {
            result.add(TreeNode.Session(
                id = session.id,
                session = SessionItem(session = session, status = statuses[session.id] ?: SessionStatus.Idle),
                depth = info.depth + 1,
            ))
        }
    }
}
```

- [ ] **Step 2: Run compile check**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/TreeNode.kt
git commit -m "feat: add TreeNode model and buildTreeNodes function"
```

---

### Task 2: Write Unit Tests for Tree Construction

**Files:**
- Create: `app/src/test/kotlin/dev/minios/ocremote/ui/screens/sessions/TreeNodeTest.kt`

- [ ] **Step 1: Create the test file**

```kotlin
package dev.minios.ocremote.ui.screens.sessions

import dev.minios.ocremote.domain.model.SessionStatus
import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.ui.screens.sessions.components.TreeNode
import dev.minios.ocremote.ui.screens.sessions.components.buildTreeNodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeNodeTest {

    private fun makeSession(id: String, directory: String) = Session(
        id = id,
        directory = directory,
        time = Session.Time(created = 1000L, updated = 1000L),
    )

    @Test
    fun `empty sessions returns empty list`() {
        val result = buildTreeNodes(emptyList(), emptySet(), null)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `single directory with sessions - collapsed`() {
        val sessions = listOf(
            makeSession("s1", "/home/user/project-a"),
            makeSession("s2", "/home/user/project-a"),
        )

        val result = buildTreeNodes(sessions, emptySet(), "/home/user")

        // Should have directory nodes for /home, /home/user, /home/user/project-a
        // None expanded, so only root-level
        assertTrue(result.isNotEmpty())
        val dirs = result.filterIsInstance<TreeNode.Directory>()
        assertTrue(dirs.isNotEmpty())

        // No session nodes because nothing is expanded
        val sessNodes = result.filterIsInstance<TreeNode.Session>()
        assertTrue(sessNodes.isEmpty())
    }

    @Test
    fun `single directory with sessions - expanded`() {
        val sessions = listOf(
            makeSession("s1", "/home/user/project-a"),
            makeSession("s2", "/home/user/project-a"),
        )

        // Expand all paths
        val expanded = setOf("/home", "/home/user", "/home/user/project-a")
        val result = buildTreeNodes(sessions, expanded, null)

        val sessNodes = result.filterIsInstance<TreeNode.Session>()
        assertEquals(2, sessNodes.size)
        assertEquals("s1", sessNodes[0].id)
        assertEquals("s2", sessNodes[1].id)
    }

    @Test
    fun `depth reflects hierarchy level`() {
        val sessions = listOf(
            makeSession("s1", "/a/b/c"),
        )

        val expanded = setOf("/a", "/a/b", "/a/b/c")
        val result = buildTreeNodes(sessions, expanded, null)

        val dirs = result.filterIsInstance<TreeNode.Directory>()
        val pathToDepth = dirs.associate { it.path to it.depth }
        assertEquals(0, pathToDepth["/a"])
        assertEquals(1, pathToDepth["/a/b"])
        assertEquals(2, pathToDepth["/a/b/c"])
    }

    @Test
    fun `session depth is parent directory depth + 1`() {
        val sessions = listOf(
            makeSession("s1", "/a/b"),
        )

        val expanded = setOf("/a", "/a/b")
        val result = buildTreeNodes(sessions, expanded, null)

        val sessNode = result.filterIsInstance<TreeNode.Session>().first()
        assertEquals(2, sessNode.depth) // parent "/a/b" is depth 1, session is depth 2
    }

    @Test
    fun `session count tracks exact and total`() {
        val sessions = listOf(
            makeSession("s1", "/a"),
            makeSession("s2", "/a/b"),
        )

        val result = buildTreeNodes(sessions, emptySet(), null)

        val dirA = result.filterIsInstance<TreeNode.Directory>().first { it.path == "/a" }
        assertEquals(1, dirA.sessionCount)     // only s1 is directly in /a
        assertEquals(2, dirA.totalSessionCount) // s1 + s2 are under /a recursively
    }

    @Test
    fun `childDirectoryCount counts direct children`() {
        val sessions = listOf(
            makeSession("s1", "/a/b"),
            makeSession("s2", "/a/c"),
            makeSession("s3", "/a/b/d"),
        )

        val result = buildTreeNodes(sessions, emptySet(), null)

        val dirA = result.filterIsInstance<TreeNode.Directory>().first { it.path == "/a" }
        assertEquals(2, dirA.childDirectoryCount) // /a/b and /a/c
    }

    @Test
    fun `sessions with empty directory appear at root depth 0`() {
        val sessions = listOf(
            makeSession("s1", ""),
        )

        val result = buildTreeNodes(sessions, emptySet(), null)

        val sessNode = result.filterIsInstance<TreeNode.Session>().firstOrNull()
        assertEquals("s1", sessNode?.id)
        assertEquals(0, sessNode?.depth)
    }

    @Test
    fun `partial expand shows only expanded children`() {
        val sessions = listOf(
            makeSession("s1", "/a"),
            makeSession("s2", "/a/b"),
        )

        // Only expand /a, not /a/b
        val expanded = setOf("/a")
        val result = buildTreeNodes(sessions, expanded, null)

        val sessNodes = result.filterIsInstance<TreeNode.Session>()
        assertEquals(1, sessNodes.size) // only s1 (in /a), s2 not visible because /a/b not expanded
        assertEquals("s1", sessNodes[0].id)
    }
}
```

- [ ] **Step 2: Run the tests**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "dev.minios.ocremote.ui.screens.sessions.TreeNodeTest" --rerun`
Expected: All 8 tests PASS

- [ ] **Step 3: Commit**

```bash
git add app/src/test/kotlin/dev/minios/ocremote/ui/screens/sessions/TreeNodeTest.kt
git commit -m "test: add unit tests for buildTreeNodes algorithm"
```

---

### Task 3: Add String Resources for Three-Dot Menu

**Files:**
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Add new string resources**

Append these entries to `app/src/main/res/values/strings.xml` (after the existing session strings, before the closing `</resources>` tag):

```xml
    <!-- Directory / Session context menu -->
    <string name="menu_copy_path">Copy path</string>
    <string name="menu_copy_session_id">Copy session ID</string>
    <string name="menu_view_details">View details</string>
    <string name="menu_copied_to_clipboard">Copied to clipboard</string>
    <string name="directory_session_count">%1$d sessions</string>
    <string name="directory_details_path">Path</string>
    <string name="directory_details_sessions">Sessions</string>
    <string name="directory_details_subdirectories">Subdirectories</string>
    <string name="session_details_id">Session ID</string>
    <string name="session_details_status">Status</string>
    <string name="session_details_created">Created</string>
    <string name="session_details_updated">Updated</string>
    <string name="session_status_busy">Working</string>
    <string name="session_status_idle">Idle</string>
    <string name="session_status_retry">Retrying</string>
```

- [ ] **Step 1.5: Run lokit to sync translations**

Run: `lokit`
Expected: Translations synced across all 15 locales

- [ ] **Step 2: Run compile check**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add app/src/main/res/values/strings.xml
git commit -m "feat: add string resources for directory/session context menus"
```

---

### Task 4: Create DirectoryTreeNode Composable

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/DirectoryTreeNode.kt`

- [ ] **Step 1: Create the composable**

Create file `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/DirectoryTreeNode.kt`:

```kotlin
package dev.minios.ocremote.ui.screens.sessions.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.R
import dev.minios.ocremote.ui.components.AmoledSurface
import dev.minios.ocremote.ui.theme.AlphaTokens
import dev.minios.ocremote.ui.theme.ShapeTokens

@Composable
internal fun DirectoryTreeNode(
    node: TreeNode.Directory,
    onClick: () -> Unit,
    onCopyPath: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isAmoled = isAmoledTheme()
    var menuExpanded by remember { mutableStateOf(false) }
    var showDetailsDialog by remember { mutableStateOf(false) }

    val arrowRotation by animateFloatAsState(
        targetValue = if (node.isExpanded) 90f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "arrowRotation"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = minOf(node.depth * 16, 160).dp, end = 8.dp)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier
                .size(20.dp)
                .rotate(arrowRotation),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = if (node.isExpanded) Icons.Default.FolderOpen else Icons.Default.Folder,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = node.displayName,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        if (node.totalSessionCount > 0) {
            Text(
                text = stringResource(R.string.directory_session_count, node.totalSessionCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED),
            )
        }
        Box {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                containerColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceContainer,
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_copy_path)) },
                    onClick = {
                        menuExpanded = false
                        onCopyPath(node.path)
                    },
                    leadingIcon = {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_view_details)) },
                    onClick = {
                        menuExpanded = false
                        showDetailsDialog = true
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                )
            }
        }
    }

    if (showDetailsDialog) {
        DirectoryDetailsDialog(
            node = node,
            onDismiss = { showDetailsDialog = false },
            isAmoled = isAmoled,
        )
    }
}

@Composable
private fun DirectoryDetailsDialog(
    node: TreeNode.Directory,
    onDismiss: () -> Unit,
    isAmoled: Boolean,
) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        AmoledSurface(
            isAmoledDark = isAmoled,
            shape = ShapeTokens.largeMedium,
            normalTonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = node.displayName, style = MaterialTheme.typography.headlineSmall)
                HorizontalDivider()
                DetailRow(stringResource(R.string.directory_details_path), node.path)
                DetailRow(
                    stringResource(R.string.directory_details_sessions),
                    "${node.sessionCount} direct, ${node.totalSessionCount} total"
                )
                DetailRow(
                    stringResource(R.string.directory_details_subdirectories),
                    "${node.childDirectoryCount}"
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
```

- [ ] **Step 2: Run compile check**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/DirectoryTreeNode.kt
git commit -m "feat: add DirectoryTreeNode composable with three-dot menu"
```

---

### Task 5: Refactor SessionRow — Remove SwipeToDismiss, Add Three-Dot Menu

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/SessionRow.kt`

This is a full rewrite. The new SessionRow:
- Removes `SwipeToDismissBox` and its background content
- Removes `Card` wrapper — uses a flat `Row` with background
- Adds `depth: Int` parameter for indentation
- Adds three-dot `DropdownMenu` with Rename, Delete, Copy ID, View details
- Keeps existing: checkbox animation, status indicators, diff stats, AMOLED support

- [ ] **Step 1: Rewrite SessionRow.kt**

Replace the entire content of `SessionRow.kt` with:

```kotlin
package dev.minios.ocremote.ui.screens.sessions.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.minios.ocremote.R
import dev.minios.ocremote.domain.model.SessionStatus
import dev.minios.ocremote.ui.components.AmoledSurface
import dev.minios.ocremote.ui.components.indicators.PulsingDotsIndicator
import dev.minios.ocremote.ui.screens.sessions.SessionItem
import dev.minios.ocremote.ui.theme.AlphaTokens
import dev.minios.ocremote.ui.theme.DiffAdded
import dev.minios.ocremote.ui.theme.DiffRemoved
import dev.minios.ocremote.ui.theme.ShapeTokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SessionRow(
    item: SessionItem,
    depth: Int = 0,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onCopyId: (String) -> Unit = {},
) {
    val isAmoled = isAmoledTheme()
    val dateFormat = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    val addColor = DiffAdded
    val delColor = DiffRemoved

    var menuExpanded by remember { mutableStateOf(false) }
    var showDetailsDialog by remember { mutableStateOf(false) }

    val pressedColor = if (isAmoled) {
        MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.FAINT)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = AlphaTokens.FAINT)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(start = minOf(depth * 16, 160).dp, end = 8.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Selection checkbox
        AnimatedVisibility(
            visible = isSelectionMode,
            enter = expandHorizontally(expandFrom = Alignment.Start) + fadeIn(),
            exit = shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
        }

        // Status dot
        when (item.status) {
            is SessionStatus.Busy -> {
                PulsingDotsIndicator(
                    dotSize = 6.dp,
                    dotSpacing = 2.dp,
                    color = MaterialTheme.colorScheme.tertiary
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            is SessionStatus.Retry -> {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            else -> { /* Idle — no dot */ }
        }

        // Content column
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.session.title ?: stringResource(R.string.session_untitled),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
            )

            Spacer(modifier = Modifier.height(2.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = dateFormat.format(Date(item.session.time.updated)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
                )

                // Status label
                when (item.status) {
                    is SessionStatus.Busy -> {
                        Text(
                            text = stringResource(R.string.sessions_working),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    is SessionStatus.Retry -> {
                        Text(
                            text = stringResource(R.string.sessions_retrying),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    else -> {}
                }

                // Diff summary
                val summary = item.session.summary
                if (summary != null && (summary.additions > 0 || summary.deletions > 0)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        if (summary.additions > 0) {
                            Text(
                                text = stringResource(R.string.session_changes_additions, summary.additions),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = addColor,
                                ),
                            )
                        }
                        if (summary.deletions > 0) {
                            Text(
                                text = stringResource(R.string.session_changes_deletions, summary.deletions),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = delColor,
                                ),
                            )
                        }
                    }
                }
            }
        }

        // Three-dot menu (hidden during selection mode)
        if (!isSelectionMode) {
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    containerColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.session_rename)) },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_copy_session_id)) },
                        onClick = {
                            menuExpanded = false
                            onCopyId(item.session.id)
                        },
                        leadingIcon = {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_view_details)) },
                        onClick = {
                            menuExpanded = false
                            showDetailsDialog = true
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                    )
                }
            }
        }
    }

    // Details dialog
    if (showDetailsDialog) {
        SessionDetailsDialog(
            item = item,
            onDismiss = { showDetailsDialog = false },
            isAmoled = isAmoled,
        )
    }
}

@Composable
private fun SessionDetailsDialog(
    item: SessionItem,
    onDismiss: () -> Unit,
    isAmoled: Boolean,
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    BasicAlertDialog(onDismissRequest = onDismiss) {
        AmoledSurface(
            isAmoledDark = isAmoled,
            shape = ShapeTokens.largeMedium,
            normalTonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = item.session.title ?: stringResource(R.string.session_untitled),
                    style = MaterialTheme.typography.headlineSmall,
                )
                HorizontalDivider()
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
                }
            }
        }
    }
}

// Note: Same as DirectoryTreeNode.kt's DetailRow. File-private to avoid cross-file coupling.
// If styling diverges, extract to shared file.
@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
```

- [ ] **Step 2: Run compile check**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: PASS (may have warnings about unused imports in other files that reference old SessionRow signature — fix in Task 7)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/SessionRow.kt
git commit -m "refactor: rewrite SessionRow with three-dot menu, remove SwipeToDismiss"
```

---

### Task 6: Refactor ViewModel — Remove ListMode, Add Tree State

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListViewModel.kt`

Key changes:
- Remove `ListMode` enum, `ProjectGroup` data class
- Remove `_mode`, `_currentProject`, `selectProject()`, `navigateBack()`, `createSessionInCurrentProject()`
- Add `_expandedPaths: MutableStateFlow<Set<String>>`, `toggleDirectory()`
- Add `treeNodes` derived state in `SessionListUiState`
- Update `SessionListUiState` to remove `mode`, `projectGroups`, `currentProject` and add `treeNodes`
- Update `selectAll()` to work with tree nodes
- Keep selection mode, rename/delete, FAB, navigation, clipboard

- [ ] **Step 1: Update SessionListViewModel.kt**

Replace the entire file with:

```kotlin
package dev.minios.ocremote.ui.screens.sessions

import android.util.Log
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import dev.minios.ocremote.BuildConfig
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.minios.ocremote.data.dto.response.FileNode
import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.api.ServerConnection
import dev.minios.ocremote.data.repository.EventDispatcher
import dev.minios.ocremote.domain.model.Project
import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.domain.model.SessionStatus
import dev.minios.ocremote.domain.usecase.ManageSessionUseCase
import dev.minios.ocremote.ui.screens.sessions.components.TreeNode
import dev.minios.ocremote.ui.screens.sessions.components.buildTreeNodes
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URLDecoder
import javax.inject.Inject

private const val TAG = "SessionListViewModel"

data class SessionListUiState(
    val treeNodes: List<TreeNode> = emptyList(),
    val serverName: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val selectedIds: Set<String> = emptySet(),
    val isSelectionMode: Boolean = false,
)

data class SessionItem(
    val session: Session,
    val status: SessionStatus = SessionStatus.Idle
)

@HiltViewModel
class SessionListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val eventDispatcher: EventDispatcher,
    private val api: OpenCodeApi,
    private val manageSessionUseCase: ManageSessionUseCase
) : ViewModel() {

    val serverUrl: String = URLDecoder.decode(
        savedStateHandle.get<String>("serverUrl") ?: "", "UTF-8"
    )
    private val username: String = URLDecoder.decode(
        savedStateHandle.get<String>("username") ?: "", "UTF-8"
    )
    private val password: String = URLDecoder.decode(
        savedStateHandle.get<String>("password") ?: "", "UTF-8"
    )
    val serverName: String = URLDecoder.decode(
        savedStateHandle.get<String>("serverName") ?: "", "UTF-8"
    )
    val serverId: String = URLDecoder.decode(
        savedStateHandle.get<String>("serverId") ?: "", "UTF-8"
    )

    private val conn = ServerConnection.from(serverUrl, username, password.ifEmpty { null })

    private val _error = MutableStateFlow<String?>(null)
    private val _isLoading = MutableStateFlow(true)
    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    private val _homeDir = MutableStateFlow<String?>(null)
    private val _expandedPaths = MutableStateFlow<Set<String>>(emptySet())
    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    private val _navigateToSession = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val navigateToSession: SharedFlow<String> = _navigateToSession.asSharedFlow()

    @Suppress("UNCHECKED_CAST")
    val uiState: StateFlow<SessionListUiState> = combine(
        eventDispatcher.sessions,
        eventDispatcher.sessionStatuses,
        eventDispatcher.serverSessions,
        _isLoading,
        _error,
        _projects,
        _homeDir,
        _expandedPaths,
        _selectedIds
    ) { values ->
        val allSessions = values[0] as List<Session>
        val statuses = values[1] as Map<String, SessionStatus>
        val serverSessionMap = values[2] as Map<String, Set<String>>
        val isLoading = values[3] as Boolean
        val error = values[4] as String?
        val projects = values[5] as List<Project>
        val homeDir = values[6] as String?
        val expandedPaths = values[7] as Set<String>
        val selectedIds = values[8] as Set<String>

        // Performance: buildTreeNodes is O(N) where N = unique path segments.
        // For typical loads (<500 sessions) this is negligible.
        // If profiling shows issues, extract sessions→sortedList with distinctUntilChanged.
        val serverSessionIds = serverSessionMap[serverId].orEmpty()

        val filteredSessions = allSessions
            .filter { it.id in serverSessionIds && !it.isArchived && it.parentId == null }
            .sortedByDescending { it.time.updated }

        val treeNodes = buildTreeNodes(filteredSessions, expandedPaths, homeDir, statuses)

        SessionListUiState(
            treeNodes = treeNodes,
            serverName = serverName,
            isLoading = isLoading,
            error = error,
            selectedIds = selectedIds,
            isSelectionMode = selectedIds.isNotEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SessionListUiState())

    init {
        loadHomeDir()
        loadSessions()
    }

    private fun loadHomeDir() {
        viewModelScope.launch {
            getHomeDirectory()
        }
    }

    fun loadSessions() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val projects = api.listProjects(conn)
                _projects.value = projects
                if (BuildConfig.DEBUG) Log.d(TAG, "Loaded ${projects.size} projects for multi-project session fetch")

                if (projects.isEmpty()) {
                    val sessions = api.listSessions(conn)
                    eventDispatcher.setSessions(serverId, sessions)
                    if (BuildConfig.DEBUG) Log.d(TAG, "Loaded ${sessions.size} sessions (no projects)")
                } else {
                    var totalSessions = 0
                    for (project in projects) {
                        try {
                            val sessions = api.listSessions(conn, directory = project.worktree)
                            eventDispatcher.setSessions(serverId, sessions)
                            totalSessions += sessions.size
                            if (BuildConfig.DEBUG) Log.d(TAG, "Loaded ${sessions.size} sessions for project ${project.displayName}")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to load sessions for project ${project.displayName}: ${e.message}")
                        }
                    }
                    if (BuildConfig.DEBUG) Log.d(TAG, "Total: loaded $totalSessions sessions across ${projects.size} projects for server $serverId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load sessions", e)
                _error.value = e.message ?: "Failed to load sessions"
            } finally {
                // Auto-expand root directories on first load
                if (_expandedPaths.value.isEmpty()) {
                    val topDirs = allSessions
                        .mapNotNull { s -> s.directory.takeIf { it.isNotBlank() }?.trimEnd('/')?.substringBeforeLast('/') }
                        .filter { it.isNotEmpty() && !it.substring(1).contains('/') }
                        .toSet()
                    _expandedPaths.value = topDirs
                }
                _isLoading.value = false
            }
        }
    }

    fun createNewSession(directory: String? = null) {
        viewModelScope.launch {
            try {
                val session = manageSessionUseCase.createSession(conn, directory = directory)
                eventDispatcher.setSessions(serverId, listOf(session))
                if (BuildConfig.DEBUG) Log.d(TAG, "Created new session: ${session.id}")
                _navigateToSession.tryEmit(session.id)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create session", e)
                _error.value = e.message ?: "Failed to create session"
            }
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            try {
                val success = api.deleteSession(conn, sessionId)
                if (success) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Deleted session $sessionId")
                    loadSessions()
                } else {
                    _error.value = "Failed to delete session"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete session", e)
                _error.value = e.message ?: "Failed to delete session"
            }
        }
    }

    fun toggleSelection(sessionId: String) {
        _selectedIds.update { selected ->
            if (sessionId in selected) selected - sessionId else selected + sessionId
        }
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
    }

    fun selectAll() {
        val currentState = uiState.value
        val sessionIds = currentState.treeNodes
            .filterIsInstance<TreeNode.Session>()
            .map { it.id }
            .toSet()
        _selectedIds.value = sessionIds
    }

    fun deleteSelected() {
        viewModelScope.launch {
            val ids = _selectedIds.value
            if (ids.isEmpty()) return@launch
            try {
                val results = coroutineScope {
                    ids.map { id ->
                        async { id to api.deleteSession(conn, id) }
                    }.awaitAll()
                }
                val failed = results.filterNot { it.second }
                if (failed.isNotEmpty()) {
                    _error.value = "Failed to delete ${failed.size} session(s)"
                }
                clearSelection()
                loadSessions()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete selected sessions", e)
                _error.value = e.message ?: "Failed to delete selected sessions"
            }
        }
    }

    fun renameSession(sessionId: String, newTitle: String) {
        viewModelScope.launch {
            try {
                manageSessionUseCase.renameSession(conn, sessionId, newTitle)
                if (BuildConfig.DEBUG) Log.d(TAG, "Renamed session $sessionId to '$newTitle'")
                loadSessions()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to rename session", e)
                _error.value = e.message ?: "Failed to rename session"
            }
        }
    }

    // ============ Tree expand/collapse ============

    fun toggleDirectory(path: String) {
        _expandedPaths.update { paths ->
            if (path in paths) paths - path else paths + path
        }
    }

    fun copyToClipboard(text: String, context: Context) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("label", text))
    }

    // ============ Directory browsing for Open Project ============

    /** Get the server's home directory (cached). */
    suspend fun getHomeDirectory(): String {
        _homeDir.value?.let { return it }
        return try {
            val paths = api.getServerPaths(conn)
            val home = paths.home
            _homeDir.value = home
            if (BuildConfig.DEBUG) Log.d(TAG, "Server home directory: $home")
            home
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get server paths", e)
            "/"
        }
    }

    /** List directories in a given path on the server. */
    suspend fun listDirectories(directory: String): List<FileNode> {
        return try {
            val nodes = api.listDirectory(conn, path = "", directory = directory)
            nodes.filter { it.type == "directory" }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list directory: $directory", e)
            emptyList()
        }
    }

    /** Search for directories matching a query, scoped to a base directory. */
    suspend fun searchDirectories(query: String, directory: String): List<String> {
        return try {
            api.findFiles(conn, query = query, type = "directory", directory = directory, limit = 50)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to search directories", e)
            emptyList()
        }
    }

    /** Create a directory inside the currently browsed path. */
    suspend fun createDirectory(parentDirectory: String, folderName: String): Result<String> {
        val sanitized = folderName.trim().trim('/').replace(Regex("/+"), "/")
        if (sanitized.isBlank() || sanitized == "." || sanitized == "..") {
            return Result.failure(IllegalArgumentException("Invalid folder name"))
        }

        return runCatching {
            val targetDirectory = if (parentDirectory == "/") {
                "/$sanitized"
            } else {
                "${parentDirectory.trimEnd('/')}/$sanitized"
            }

            val tempSession = api.createSession(
                conn = conn,
                title = "mkdir",
                directory = parentDirectory,
            )

            try {
                val escaped = sanitized.replace("'", "'\"'\"'")
                val command = "mkdir -p -- '$escaped'"

                val runShellOk = runCatching {
                    api.runShellCommand(
                        conn = conn,
                        sessionId = tempSession.id,
                        command = command,
                        agent = "build",
                        directory = parentDirectory,
                    )
                }.getOrElse { false }

                if (!runShellOk) {
                    val executeOk = api.executeCommand(
                        conn = conn,
                        sessionId = tempSession.id,
                        command = "bash",
                        arguments = "-lc \"$command\"",
                        directory = parentDirectory,
                    )
                    if (!executeOk) {
                        throw IllegalStateException("Failed to create directory")
                    }
                }
            } finally {
                runCatching { api.deleteSession(conn, tempSession.id) }
            }

            repeat(6) {
                if (directoryExists(targetDirectory)) {
                    return@runCatching targetDirectory
                }
                delay(200)
            }

            throw IllegalStateException("Directory was not created")
        }
    }

    private suspend fun directoryExists(directory: String): Boolean {
        return try {
            api.listDirectory(conn, path = "", directory = directory)
            true
        } catch (_: Exception) {
            false
        }
    }
}
```

- [ ] **Step 2: Run compile check**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: FAIL — `SessionListScreen.kt` still references `ListMode`, `ProjectGroup`, `ProjectGroupRow`, etc. This is expected; Task 7 fixes the Screen.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListViewModel.kt
git commit -m "refactor: remove ListMode, add tree-based state to SessionListViewModel"
```

---

### Task 7: Rewrite SessionListScreen — Single LazyColumn

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListScreen.kt`

Replace the entire file with a single-LazyColumn approach:

```kotlin
package dev.minios.ocremote.ui.screens.sessions

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.R
import dev.minios.ocremote.ui.components.AmoledSurface
import dev.minios.ocremote.ui.components.indicators.PulsingDotsIndicator
import dev.minios.ocremote.ui.screens.sessions.components.DirectoryTreeNode
import dev.minios.ocremote.ui.screens.sessions.components.SessionRow
import dev.minios.ocremote.ui.screens.sessions.components.TreeNode
import dev.minios.ocremote.ui.screens.sessions.components.isAmoledTheme
import dev.minios.ocremote.ui.screens.sessions.components.OpenProjectDialog
import dev.minios.ocremote.ui.theme.AlphaTokens
import dev.minios.ocremote.ui.theme.ShapeTokens
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    viewModel: SessionListViewModel,
    onNavigateToChat: (sessionId: String, openTerminal: Boolean) -> Unit,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val isAmoled = isAmoledTheme()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(viewModel) {
        viewModel.navigateToSession
            .onEach { sessionId ->
                onNavigateToChat(sessionId, false)
            }
            .launchIn(this)
    }

    // Dialog states
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameSessionId by remember { mutableStateOf("") }
    var renameText by remember { mutableStateOf("") }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var deleteSessionId by remember { mutableStateOf("") }
    var deleteSessionTitle by remember { mutableStateOf("") }
    var showDeleteSelectedDialog by remember { mutableStateOf(false) }

    var showOpenProject by remember { mutableStateOf(false) }

    BackHandler(enabled = uiState.isSelectionMode) {
        viewModel.clearSelection()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (uiState.isSelectionMode) {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.sessions_selected_count, uiState.selectedIds.size),
                            style = MaterialTheme.typography.titleMedium
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                        }
                    },
                    actions = {
                        TextButton(onClick = { viewModel.selectAll() }) {
                            Text(stringResource(R.string.sessions_select_all))
                        }
                        IconButton(onClick = { showDeleteSelectedDialog = true }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.sessions_delete_selected),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            } else {
                TopAppBar(
                    title = {
                        Text(
                            text = uiState.serverName.ifEmpty { stringResource(R.string.sessions_title) },
                            style = MaterialTheme.typography.titleMedium
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    },
                    actions = {
                        IconButton(onClick = { showOpenProject = true }) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.sessions_open_project))
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (!uiState.isSelectionMode) {
                FloatingActionButton(
                    onClick = { viewModel.createNewSession() },
                    containerColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.primaryContainer,
                    contentColor = if (isAmoled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimaryContainer,
                    elevation = if (isAmoled) {
                        FloatingActionButtonDefaults.elevation(
                            defaultElevation = 0.dp,
                            pressedElevation = 0.dp,
                            focusedElevation = 0.dp,
                            hoveredElevation = 0.dp
                        )
                    } else {
                        FloatingActionButtonDefaults.elevation()
                    },
                    modifier = if (isAmoled) {
                        Modifier.border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.MEDIUM),
                            shape = FloatingActionButtonDefaults.shape
                        )
                    } else {
                        Modifier
                    }
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.sessions_new))
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                uiState.isLoading && uiState.treeNodes.isEmpty() -> {
                    PulsingDotsIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        dotSize = 12.dp,
                        dotSpacing = 8.dp
                    )
                }
                uiState.error != null && uiState.treeNodes.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = uiState.error ?: stringResource(R.string.session_unknown_error),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                        Button(onClick = { viewModel.loadSessions() }) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
                uiState.treeNodes.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.sessions_empty_directory),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {
                    val untitledLabel = stringResource(R.string.session_untitled)
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        // TODO: Add LazyListState scroll listener to close DropdownMenu on scroll
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        items(uiState.treeNodes, key = { it.id }) { node ->
                            // NOTE: animateItem() requires Compose Foundation 1.7.0+
                            // If not available, items appear instantly (acceptable degradation)
                            when (node) {
                                is TreeNode.Directory -> {
                                    DirectoryTreeNode(
                                        node = node,
                                        onClick = { viewModel.toggleDirectory(node.path) },
                                        onCopyPath = { path ->
                                            viewModel.copyToClipboard(path, context)
                                            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.menu_copied_to_clipboard)) }
                                        },
                                    )
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(
                                            alpha = if (isAmoled) AlphaTokens.FAINT else 0.2f
                                        )
                                    )
                                }
                                is TreeNode.Session -> {
                                    SessionRow(
                                        item = node.session,
                                        depth = node.depth,
                                        isSelectionMode = uiState.isSelectionMode,
                                        isSelected = node.id in uiState.selectedIds,
                                        onClick = {
                                            if (uiState.isSelectionMode) {
                                                viewModel.toggleSelection(node.id)
                                            } else {
                                                onNavigateToChat(node.id, false)
                                            }
                                        },
                                        onLongClick = { viewModel.toggleSelection(node.id) },
                                        onRename = {
                                            renameSessionId = node.id
                                            renameText = node.session.session.title ?: ""
                                            showRenameDialog = true
                                        },
                                        onDelete = {
                                            deleteSessionId = node.id
                                            deleteSessionTitle = node.session.session.title ?: untitledLabel
                                            showDeleteDialog = true
                                        },
                                        onCopyId = { id ->
                                            viewModel.copyToClipboard(id, context)
                                            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.menu_copied_to_clipboard)) }
                                        },
                                    )
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(
                                            alpha = if (isAmoled) AlphaTokens.FAINT else 0.2f
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Open Project dialog
    if (showOpenProject) {
        OpenProjectDialog(
            viewModel = viewModel,
            projects = emptyList(),
            onSelect = { directory ->
                showOpenProject = false
                viewModel.createNewSession(directory = directory)
            },
            onDismiss = { showOpenProject = false }
        )
    }

    // Delete selected dialog
    if (showDeleteSelectedDialog) {
        BasicAlertDialog(onDismissRequest = { showDeleteSelectedDialog = false }) {
            AmoledSurface(
                isAmoledDark = isAmoled,
                shape = ShapeTokens.largeMedium,
                normalTonalElevation = 6.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.sessions_delete_selected),
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text(stringResource(R.string.sessions_delete_selected_confirm, uiState.selectedIds.size))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showDeleteSelectedDialog = false }) {
                            Text(stringResource(R.string.cancel))
                        }
                        TextButton(
                            onClick = {
                                viewModel.deleteSelected()
                                showDeleteSelectedDialog = false
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text(stringResource(R.string.delete))
                        }
                    }
                }
            }
        }
    }

    // Rename dialog
    if (showRenameDialog) {
        BasicAlertDialog(onDismissRequest = { showRenameDialog = false }) {
            AmoledSurface(
                isAmoledDark = isAmoled,
                shape = ShapeTokens.largeMedium,
                normalTonalElevation = 6.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.session_rename),
                        style = MaterialTheme.typography.headlineSmall
                    )
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        label = { Text(stringResource(R.string.session_rename_title)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showRenameDialog = false }) {
                            Text(stringResource(R.string.cancel))
                        }
                        TextButton(
                            onClick = {
                                viewModel.renameSession(renameSessionId, renameText)
                                showRenameDialog = false
                            },
                            enabled = renameText.isNotBlank()
                        ) {
                            Text(stringResource(R.string.session_rename_button))
                        }
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        BasicAlertDialog(onDismissRequest = { showDeleteDialog = false }) {
            AmoledSurface(
                isAmoledDark = isAmoled,
                shape = ShapeTokens.largeMedium,
                normalTonalElevation = 6.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.session_delete),
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text(stringResource(R.string.session_delete_confirm, deleteSessionTitle))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showDeleteDialog = false }) {
                            Text(stringResource(R.string.cancel))
                        }
                        TextButton(
                            onClick = {
                                viewModel.deleteSession(deleteSessionId)
                                showDeleteDialog = false
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text(stringResource(R.string.delete))
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Run compile check**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListScreen.kt
git commit -m "refactor: rewrite SessionListScreen with single LazyColumn tree view"
```

---

### Task 8: Delete Dead Code — ProjectGroupRow.kt + ProjectHeader.kt

**Files:**
- Delete: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/ProjectGroupRow.kt`
- Delete: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/ProjectHeader.kt`

- [ ] **Step 1: Delete ProjectGroupRow.kt**

```bash
git rm app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/ProjectGroupRow.kt
```

- [ ] **Step 2: Delete ProjectHeader.kt**

```bash
git rm app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/ProjectHeader.kt
```

- [ ] **Step 3: Run compile check**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: PASS (no remaining references — SessionListScreen no longer imports these)

- [ ] **Step 4: Commit**

```bash
git commit -m "chore: delete dead code (ProjectGroupRow, ProjectHeader)"
```

---

### Task 9: Run Unit Tests + Final Verification

**Manual verification checklist (after all tests pass):**
- [ ] Directory nodes render with correct indentation
- [ ] Clicking directory expands/collapses, arrow rotates 90°
- [ ] Three-dot menu appears and all items work (Copy path, View details)
- [ ] Session row shows correct status indicators (Busy=dot, Retry=error)
- [ ] Long-press enters selection mode, checkboxes animate in
- [ ] AMOLED theme: black backgrounds, no shadows, correct borders
- [ ] Empty state shows "Empty directory" message

- [ ] **Step 1: Run all unit tests**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun`
Expected: All tests PASS (including the new TreeNodeTest)

- [ ] **Step 2: Run full compile check**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: PASS with no errors

- [ ] **Step 2.5: Run Release build check**

Run: `.\gradlew :app:assembleDevRelease`
Expected: PASS — verifies R8/ProGuard rules preserve all required classes

- [ ] **Step 3: Final commit (if any cleanup needed)**

If any import cleanup or minor fixes were needed, commit them:

```bash
git add -A
git commit -m "chore: cleanup after directory tree refactor"
```
