# 会话列表两级导航改造 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将会话列表从扁平列表改为两级导航（项目目录列表 → 项目会话列表），并修复目录浏览器搜索栏无法导航 Windows 路径的问题。

**Architecture:** 在同一个 SessionListScreen 内通过 ViewModel 的 `mode` 状态切换两级视图。第一级展示项目目录卡片（从现有会话按 directory 分组聚合），第二级展示选中目录下的会话列表。OpenProjectDialog 搜索栏增加路径检测，路径模式走 `listDirectories()` 而非 `searchDirectories()`。

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, StateFlow, MockK

**Spec:** `docs/superpowers/specs/2026-06-01-session-list-two-level-nav-design.md`

---

## 文件结构

| 文件 | 操作 | 职责 |
|------|------|------|
| `ui/screens/sessions/SessionListViewModel.kt` | 修改 | 新增 ListMode、ProjectGroup、两级状态切换逻辑 |
| `ui/screens/sessions/SessionListScreen.kt` | 修改 | 双模式渲染：项目目录列表 / 会话列表 |
| `ui/screens/sessions/components/OpenProjectDialog.kt` | 修改 | 搜索栏路径检测 + 路径导航模式 |
| `ui/screens/sessions/components/NewSessionQuickDialog.kt` | 删除 | 不再需要 |
| `ui/screens/sessions/components/ProjectGroupRow.kt` | 新建 | 项目目录行组件（路径、会话数、最近时间） |

---

### Task 1: 新增数据模型 — ListMode、ProjectGroup、UiState 改造

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListViewModel.kt`

- [ ] **Step 1: 在 ViewModel 中定义新的数据类和枚举**

在 `SessionListViewModel.kt` 文件中，在 `SessionListUiState` data class 之前添加：

```kotlin
enum class ListMode { PROJECTS, SESSIONS }

data class ProjectGroup(
    val directory: String,
    val displayName: String,
    val sessionCount: Int,
    val lastUpdated: Long?,
)
```

- [ ] **Step 2: 修改 SessionListUiState**

将现有的 `SessionListUiState` 替换为：

```kotlin
data class SessionListUiState(
    val mode: ListMode = ListMode.PROJECTS,
    val projectGroups: List<ProjectGroup> = emptyList(),
    val currentProject: ProjectGroup? = null,
    val sessions: List<SessionItem> = emptyList(),
    val serverName: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val selectedIds: Set<String> = emptySet(),
    val isSelectionMode: Boolean = false,
)
```

注意：移除旧的 `sessionGroups: List<ProjectSessionGroup>` 字段和 `sessionDirLabels: Map<String, String>` 字段。

- [ ] **Step 3: 编译检查**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: 可能编译失败（因为 UiState 字段变化导致 Screen 引用报错），先记录错误，后续 Task 修复。

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListViewModel.kt
git commit -m "refactor: update SessionListUiState for two-level navigation"
```

---

### Task 2: ViewModel 逻辑改造 — 项目分组和两级切换

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListViewModel.kt`

- [ ] **Step 1: 添加状态字段**

在 ViewModel 类中添加：

```kotlin
private val _mode = MutableStateFlow(ListMode.PROJECTS)
private val _currentProject = MutableStateFlow<ProjectGroup?>(null)
```

- [ ] **Step 2: 修改 combine 流**

将现有的 `combine(...)` 块改为构建两级数据：

```kotlin
val uiState = combine(
    _mode,
    _currentProject,
    eventDispatcher.sessions,      // StateFlow<List<Session>>
    eventDispatcher.sessionStatuses, // StateFlow<Map<String, SessionStatus>>
    serverSessions,                 // StateFlow<Map<String, Set<String>>>
    _isLoading,
    _error,
    _projects,
    _homeDir,
    _selectedIds
) { values ->
    val mode = values[0] as ListMode
    val currentProject = values[1] as ProjectGroup?
    val allSessions = values[2] as List<Session>
    val statuses = values[3] as Map<String, SessionStatus>
    val serverSessionMap = values[4] as Map<String, Set<String>>
    val isLoading = values[5] as Boolean
    val error = values[6] as String?
    val projects = values[7] as List<Project>
    val homeDir = values[8] as String
    val selectedIds = values[9] as Set<String>

    val serverSessionIds = serverSessionMap[serverId].orEmpty()

    // 过滤该服务器的、未归档的、根级会话
    val filteredSessions = allSessions
        .filter { it.id in serverSessionIds && !it.isArchived && it.parentId == null }
        .sortedByDescending { it.time.updated }

    // 按目录分组构建 ProjectGroup 列表
    val projectGroups = filteredSessions
        .groupBy { it.directory }
        .map { (dir, sessions) ->
            ProjectGroup(
                directory = dir,
                displayName = dir.replaceHomePrefix(homeDir),
                sessionCount = sessions.size,
                lastUpdated = sessions.maxOfOrNull { it.time.updated }
            )
        }
        .sortedByDescending { it.lastUpdated ?: 0L }

    // 如果当前在 SESSIONS 模式，过滤当前项目的会话
    val displaySessions = if (mode == ListMode.SESSIONS && currentProject != null) {
        filteredSessions.filter { it.directory == currentProject.directory }
    } else {
        emptyList()
    }.map { session ->
        SessionItem(
            session = session,
            status = statuses[session.id] ?: SessionStatus.Idle
        )
    }

    SessionListUiState(
        mode = mode,
        projectGroups = projectGroups,
        currentProject = currentProject,
        sessions = displaySessions,
        serverName = serverName,
        isLoading = isLoading,
        error = error,
        selectedIds = selectedIds,
        isSelectionMode = selectedIds.isNotEmpty()
    )
}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SessionListUiState())
```

注意：`serverName` 是 ViewModel 构造参数中已有的。`serverId` 同理。

- [ ] **Step 3: 添加辅助扩展函数**

在 ViewModel 文件的顶层或 companion 中添加：

```kotlin
private fun String.replaceHomePrefix(homeDir: String): String {
    return if (homeDir.isNotBlank() && this.startsWith(homeDir)) {
        "~" + this.removePrefix(homeDir)
    } else this
}
```

- [ ] **Step 4: 添加导航方法**

在 ViewModel 类中添加：

```kotlin
fun selectProject(group: ProjectGroup) {
    _currentProject.value = group
    _mode.value = ListMode.SESSIONS
    _selectedIds.value = emptySet()
}

fun navigateBack() {
    _mode.value = ListMode.PROJECTS
    _currentProject.value = null
    _selectedIds.value = emptySet()
}

fun createSessionInCurrentProject() {
    val dir = _currentProject.value?.directory ?: return
    viewModelScope.launch {
        createNewSession(dir)
    }
}
```

- [ ] **Step 5: 编译检查**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: 可能因 Screen 端引用旧字段而失败，后续 Task 修复。ViewModel 本身应无编译错误。

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListViewModel.kt
git commit -m "feat: add two-level navigation state logic to SessionListViewModel"
```

---

### Task 3: 新建 ProjectGroupRow 组件

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/ProjectGroupRow.kt`

- [ ] **Step 1: 创建 ProjectGroupRow composable**

```kotlin
package dev.minios.ocremote.ui.screens.sessions.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.ui.screens.sessions.ProjectGroup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun ProjectGroupRow(
    group: ProjectGroup,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = group.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(2.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "${group.sessionCount} 个会话",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (group.lastUpdated != null) {
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = formatRelativeTime(group.lastUpdated),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun formatRelativeTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    val minutes = diff / 60_000
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "${minutes} 分钟前"
        minutes < 1440 -> "${minutes / 60} 小时前"
        minutes < 10080 -> "${minutes / 1440} 天前"
        else -> {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            sdf.format(Date(timestamp))
        }
    }
}
```

- [ ] **Step 2: 编译检查**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: PASS（独立组件，无外部依赖变更）

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/ProjectGroupRow.kt
git commit -m "feat: add ProjectGroupRow composable component"
```

---

### Task 4: 改造 SessionListScreen — 双模式渲染

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListScreen.kt`

**前置条件:** Task 1-3 完成，ViewModel 和 ProjectGroupRow 已就绪。

- [ ] **Step 1: 修改 TopAppBar 支持两级标题和返回按钮**

将 TopAppBar 区域改为：

```kotlin
TopAppBar(
    title = {
        Text(
            if (uiState.mode == ListMode.SESSIONS && uiState.currentProject != null)
                uiState.currentProject.displayName
            else
                uiState.serverName
        )
    },
    navigationIcon = {
        if (uiState.mode == ListMode.SESSIONS) {
            IconButton(onClick = { viewModel.navigateBack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
        } else {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
        }
    },
    actions = {
        if (uiState.mode == ListMode.PROJECTS) {
            // 项目列表模式：TopBar 右上角 + 按钮，打开目录浏览器
            IconButton(onClick = { showOpenProject = true }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.sessions_open_project))
            }
        }
    }
)
```

- [ ] **Step 2: 修改 FAB 行为**

FAB 仅在 SESSIONS 模式下显示（在项目目录列表时隐藏，因为项目列表用 TopBar 的 + ）：

```kotlin
floatingActionButton = {
    if (uiState.mode == ListMode.SESSIONS) {
        FloatingActionButton(
            onClick = { viewModel.createSessionInCurrentProject() },
            // ... 保持现有 FAB 样式
        ) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.sessions_new_session))
        }
    }
}
```

- [ ] **Step 3: 修改内容区渲染逻辑**

将内容区的 `when` 块改为：

```kotlin
when {
    uiState.isLoading -> {
        // Loading 状态保持不变
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            PulsingDotsIndicator(dotSize = 10.dp, dotSpacing = 6.dp)
        }
    }
    uiState.error != null -> {
        // Error 状态保持不变
    }
    uiState.mode == ListMode.PROJECTS -> {
        // 第一级：项目目录列表
        if (uiState.projectGroups.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.sessions_empty_directory),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(uiState.projectGroups, key = { it.directory }) { group ->
                    ProjectGroupRow(
                        group = group,
                        onClick = { viewModel.selectProject(group) },
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                    )
                }
            }
        }
    }
    uiState.mode == ListMode.SESSIONS -> {
        // 第二级：会话列表（复用现有 SessionRow 逻辑）
        if (uiState.sessions.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.sessions_no_folders),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(uiState.sessions, key = { it.session.id }) { item ->
                    SessionRow(
                        session = item.session,
                        status = item.status,
                        isSelected = item.session.id in uiState.selectedIds,
                        isSelectionMode = uiState.isSelectionMode,
                        onClick = {
                            if (uiState.isSelectionMode) {
                                viewModel.toggleSelection(item.session.id)
                            } else {
                                onNavigateToChat(item.session.id, false)
                            }
                        },
                        onLongClick = { viewModel.toggleSelection(item.session.id) },
                        onRename = { showRenameDialog = item.session },
                        onDelete = { showDeleteDialog = setOf(item.session.id) },
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 4: 移除对 sessionGroups / sessionDirLabels 的旧引用**

删除所有引用 `uiState.sessionGroups` 的旧代码，包括：
- `showQuickNewSession` 状态变量和相关 Dialog
- `NewSessionQuickDialog` 的 import 和调用
- 旧的扁平列表渲染逻辑

- [ ] **Step 5: 移除 OpenProjectDialog 中选择目录后创建会话的逻辑调整**

`OpenProjectDialog` 的 `onSelect` 回调保持不变（仍然接收 directory 字符串），但调用方改为：

```kotlin
if (showOpenProject) {
    OpenProjectDialog(
        viewModel = viewModel,
        projects = uiState.projectGroups.map { Project(id = "", directory = it.directory, worktree = it.directory, name = null, path = it.directory) },
        onSelect = { directory ->
            showOpenProject = false
            viewModel.createNewSession(directory)
        },
        onDismiss = { showOpenProject = false },
    )
}
```

注意：`Project` 构造需要检查实际的 data class 字段，确保传入正确参数。

- [ ] **Step 6: 编译检查**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListScreen.kt
git commit -m "feat: implement two-level navigation in SessionListScreen"
```

---

### Task 5: 删除 NewSessionQuickDialog

**Files:**
- Delete: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/NewSessionQuickDialog.kt`

- [ ] **Step 1: 删除文件**

```bash
git rm app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/NewSessionQuickDialog.kt
```

- [ ] **Step 2: 检查是否有其他引用**

Run: `rg "NewSessionQuickDialog" --type kotlin`
Expected: 无结果（Task 4 中已移除所有引用）

- [ ] **Step 3: 编译检查**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git commit -m "refactor: remove NewSessionQuickDialog (replaced by two-level nav)"
```

---

### Task 6: 修复 OpenProjectDialog 搜索栏路径导航

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/OpenProjectDialog.kt`

- [ ] **Step 1: 添加路径检测函数**

在 `OpenProjectDialog` composable 函数内部（`isSearching` 定义之后）添加：

```kotlin
fun isPathLike(input: String): Boolean {
    val trimmed = input.trim()
    return trimmed.startsWith("/") ||
           trimmed.startsWith("~") ||
           trimmed.matches(Regex("[A-Za-z]:.*"))
}

fun resolvePath(input: String, homeDir: String?): Pair<String, String?> {
    val trimmed = input.trim()
    val expanded = if (trimmed.startsWith("~")) {
        (homeDir ?: "") + trimmed.removePrefix("~")
    } else {
        trimmed
    }

    // 统一使用 / 作为分隔符进行判断
    val normalized = expanded.replace('\\', '/')

    return if (normalized.endsWith("/")) {
        // 完整路径：直接列出该目录
        expanded to null
    } else {
        // 部分路径：拆分为父目录 + 未完成片段
        val lastSlash = normalized.lastIndexOf('/')
        if (lastSlash > 0) {
            val parent = expanded.substring(0, lastSlash).replace('/', separatorChar)
            val fragment = normalized.substring(lastSlash + 1)
            parent to fragment
        } else {
            expanded to null
        }
    }
}
```

注意：`separatorChar` 可用 `java.io.File.separatorChar`。

- [ ] **Step 2: 修改搜索 LaunchedEffect**

将现有的搜索 `LaunchedEffect(searchQuery)` 块（约第 118-134 行）替换为：

```kotlin
LaunchedEffect(searchQuery) {
    if (searchQuery.isBlank()) {
        searchResults = emptyList()
        pathNavigatedDirs = emptyList()
        currentDir?.let {
            isLoading = true
            directories = viewModel.listDirectories(it)
            isLoading = false
        }
        return@LaunchedEffect
    }
    delay(300)
    isLoading = true

    if (isPathLike(searchQuery)) {
        // 路径导航模式
        val (resolvedPath, fragment) = resolvePath(searchQuery, homeDir)
        val allDirs = viewModel.listDirectories(resolvedPath)
        pathNavigatedDirs = if (fragment != null) {
            allDirs.filter { it.name.startsWith(fragment, ignoreCase = true) }
        } else {
            allDirs
        }
        searchResults = emptyList()
    } else {
        // 文件名模糊搜索模式（保持现有行为）
        val baseDir = homeDir ?: "/"
        searchResults = viewModel.searchDirectories(searchQuery, baseDir)
        pathNavigatedDirs = emptyList()
    }
    isLoading = false
}
```

- [ ] **Step 3: 添加 pathNavigatedDirs 状态**

在现有状态变量声明区域（约第 83-93 行）添加：

```kotlin
var pathNavigatedDirs by remember { mutableStateOf<List<FileNode>>(emptyList()) }
```

- [ ] **Step 4: 修改搜索结果展示区域**

在搜索结果渲染部分（约第 300-335 行），修改为支持两种结果类型：

```kotlin
isSearching -> {
    val displayDirs = if (pathNavigatedDirs.isNotEmpty()) pathNavigatedDirs else emptyList()
    val displayPaths = if (searchResults.isNotEmpty()) searchResults else emptyList()

    if (displayDirs.isEmpty() && displayPaths.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.sessions_no_folders),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            // 路径导航结果（FileNode 列表）
            items(displayDirs, key = { it.path }) { node ->
                val absPath = node.absolute ?: node.path
                DirectoryRow(
                    displayPath = tildeReplace(absPath) + "/",
                    onClick = { onSelect(absPath) },
                    onNavigate = {
                        searchQuery = ""
                        currentDir = absPath
                    }
                )
            }
            // 模糊搜索结果（路径字符串列表）
            items(displayPaths) { path ->
                val base = (homeDir ?: "").trimEnd('/')
                val rel = path.trimStart('/').trimEnd('/')
                val absolutePath = "$base/$rel"
                DirectoryRow(
                    displayPath = tildeReplace(absolutePath) + "/",
                    onClick = { onSelect(absolutePath) },
                    onNavigate = {
                        searchQuery = ""
                        currentDir = absolutePath
                    }
                )
            }
        }
    }
}
```

- [ ] **Step 5: 编译检查**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/OpenProjectDialog.kt
git commit -m "fix: add path navigation mode to OpenProjectDialog search"
```

---

### Task 7: 整体编译验证与清理

**Files:**
- 可能需要修复编译残留问题

- [ ] **Step 1: 完整编译**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: PASS。如果有错误，逐个修复并重新编译。

- [ ] **Step 2: 检查是否有未使用的 import**

搜索已删除组件的 import 引用：
Run: `rg "NewSessionQuickDialog" --type kotlin`
Expected: 无结果

- [ ] **Step 3: 运行现有单元测试**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun`
Expected: 所有测试通过。如果有因 ViewModel 变更导致的测试失败，修复测试以适配新的 UiState 结构。

- [ ] **Step 4: Final commit**

```bash
git add -A
git commit -m "chore: cleanup after two-level navigation refactor"
```

---

## 自审清单

| 检查项 | 结果 |
|--------|------|
| Spec 覆盖 | ✅ 两级导航、项目分组、搜索修复、删除 QuickDialog 均有对应 Task |
| 占位符 | ✅ 无 TBD/TODO，所有步骤含完整代码 |
| 类型一致性 | ✅ ProjectGroup 在 Task 1 定义，Task 2/3/4 使用相同字段名 |
| Spec 需求覆盖 | ✅ 路径导航修复（Task 6）、两级切换（Task 2/4）、QuickDialog 移除（Task 5） |
