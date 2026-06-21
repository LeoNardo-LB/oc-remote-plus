# FileTreePanel 目录展开/懒加载改进 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Workspace 文件树面板实现目录展开/收起和懒加载功能——点击目录展开显示子节点，再次点击收起；未加载过的目录首次展开时异步拉取子目录列表并显示 loading 指示器。

**Architecture:** 在 `WorkspaceUiState` 中追踪已展开目录路径集合（`expandedDirs`）和正在加载的目录路径集合（`loadingDirs`）。提取纯逻辑函数 `flattenTree`（只递归已展开目录）和 `withChildren`（递归更新树节点）到独立文件 `FileTreeUtils.kt` 以保证可测试性。ViewModel 新增 `toggleExpand(path)` 公开方法，`loadDirectory` 子目录成功路径直接通过 `withChildren` 将结果写入 rootNodes 树。UI 层观察 uiState，目录点击调用 `onToggleExpand`，目录节点显示展开/收起箭头图标。

**Tech Stack:** Jetpack Compose (Material 3), Kotlin Coroutines StateFlow/SharedFlow, Hilt, MockK 1.14.9 + Turbine + kotlinx-coroutines-test (unit tests), Maestro (E2E)

## Global Constraints

- **Material 3 First**：颜色用 `MaterialTheme.colorScheme` 语义色，图标用 Material Icons，间距用 `SpacingTokens` token
- **Alpha tokens**（`AlphaTokens`）：`SELECTED(0.12f) / FAINT(0.35f) / MUTED(0.50f) / MEDIUM(0.70f) / HIGH(0.80f)`
- **Spacing tokens**（`SpacingTokens`）：`XS(4) / SM(8) / MD(12) / LG(16) / XL(24) / XXL(32)`，用 `.dp` 扩展
- **Gradle flavor**：所有编译/测试命令必须带 flavor，Kotlin 编译检查 `.\gradlew :app:compileDevDebugKotlin`（120s 超时），单元测试 `.\gradlew :app:testDevDebugUnitTest --rerun`（180s 超时）
- **测试基础设施**：JUnit 4 + MockK + `coEvery` 显式 mock，`isReturnDefaultValues = true`（mock 默认返回值不抛异常），`UnconfinedTestDispatcher` 默认用，`StandardTestDispatcher` 用于取消/竞态测试
- **源码路径**：`app/src/main/kotlin/dev/minios/ocremote/`，测试 `app/src/test/kotlin/dev/minios/ocremote/`
- **Ktor OkHttp engine**：不切换引擎
- **不修改未提及文件**，除非影响分析表明必须修改
- **每步可验证**：每个 Task 完成后必须运行编译检查或单元测试

---

## File Structure

| 文件 | 动作 | 职责 |
|------|------|------|
| `WorkspaceUiState.kt` | Modify | 新增 `expandedDirs`、`loadingDirs` 字段 |
| `FileTreeUtils.kt` | Create | 纯逻辑函数：`flattenTree`（展平树）+ `withChildren`（递归更新树） |
| `FileTreeUtilsTest.kt` | Create | `flattenTree` + `withChildren` 单元测试 |
| `WorkspaceViewModel.kt` | Modify | 新增 `toggleExpand`，改造 `loadDirectory` 子目录路径 |
| `WorkspaceViewModelTest.kt` | Modify | 新增 toggleExpand / 树更新 / loadingDirs 相关测试 |
| `FileTreePanel.kt` | Modify | 改用 `flattenTree`，`FileTreeItem` 加展开箭头/loading 指示器，新增 `onToggleExpand` 参数 |
| `WorkspaceScreen.kt` | Modify | `WorkspaceRoute`/`WorkspaceScreen` 传递 `onToggleExpand` |
| `strings.xml` | Modify | 新增 `a11y_icon_expand_directory` / `a11y_icon_collapse_directory` |
| `e2e-workspace-file-tree.yaml` | Modify | 新增目录展开/收起测试步骤 |

---

## Task 1: WorkspaceUiState 展开状态字段

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/workspace/WorkspaceUiState.kt:15-27`

**Interfaces:**
- Consumes: 无（纯数据结构变更）
- Produces: `WorkspaceUiState.expandedDirs: Set<String>`、`WorkspaceUiState.loadingDirs: Set<String>`，供后续 Task 的 ViewModel 和 UI 使用

- [ ] **Step 1: 修改 WorkspaceUiState 添加两个字段**

将 `WorkspaceUiState` data class（第 15-27 行）替换为以下内容（新增 `expandedDirs` 和 `loadingDirs` 字段）：

```kotlin
data class WorkspaceUiState(
    val currentPanel: WorkspacePanel = WorkspacePanel.FILE_TREE,
    val directory: String = "",
    val rootNodes: List<FileTreeNode> = emptyList(),
    val rootLoading: Boolean = true,
    val rootError: Int? = null,
    val showIgnored: Boolean = false,
    val expandedDirs: Set<String> = emptySet(),
    val loadingDirs: Set<String> = emptySet(),
    val gitChanges: List<VcsChange> = emptyList(),
    val gitLoading: Boolean = false,
    val gitError: Int? = null,
    val isNonGit: Boolean = false,
    val gitChangeCount: Int? = null
)
```

- [ ] **Step 2: 编译检查**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL（无编译错误，新字段有默认值，不破坏现有调用）

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/workspace/WorkspaceUiState.kt
git commit -m "feat(workspace): add expandedDirs and loadingDirs to WorkspaceUiState"
```

---

## Task 2: FileTreeUtils 提取 + 单元测试

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/workspace/FileTreeUtils.kt`
- Create: `app/src/test/kotlin/dev/minios/ocremote/ui/screens/workspace/FileTreeUtilsTest.kt`

**Interfaces:**
- Consumes: `FileTreeNode`（Task 0 已有）、`FileNode.isDirectory()`（domain model 已有）
- Produces:
  - `internal fun flattenTree(nodes: List<FileTreeNode>, expandedDirs: Set<String>, showIgnored: Boolean, depth: Int = 0): List<Pair<FileTreeNode, Int>>` — 只递归进入 `expandedDirs` 中的目录
  - `internal fun List<FileTreeNode>.withChildren(path: String, children: List<FileTreeNode>): List<FileTreeNode>` — 递归查找 path 对应节点并设置 children

- [ ] **Step 1: 编写 FileTreeUtilsTest 失败测试**

创建测试文件 `app/src/test/kotlin/dev/minios/ocremote/ui/screens/workspace/FileTreeUtilsTest.kt`：

```kotlin
package dev.leonardo.ocremotev2.ui.screens.workspace

import dev.leonardo.ocremotev2.domain.model.FileNode
import dev.leonardo.ocremotev2.domain.model.FileType
import org.junit.Test

class FileTreeUtilsTest {

    private fun dir(
        name: String,
        path: String,
        ignored: Boolean = false,
        children: List<FileTreeNode>? = null
    ) = FileTreeNode(
        FileNode(name, path, "/abs/$path", FileType.DIRECTORY, ignored),
        children
    )

    private fun file(name: String, path: String, ignored: Boolean = false) =
        FileTreeNode(FileNode(name, path, "/abs/$path", FileType.FILE, ignored))

    // ===== flattenTree =====

    @Test
    fun `flattenTree empty list returns empty`() {
        assert(flattenTree(emptyList(), emptySet(), false).isEmpty())
    }

    @Test
    fun `flattenTree flat list no expansion`() {
        val nodes = listOf(
            dir("src", "src"),
            file("README.md", "README.md")
        )
        val result = flattenTree(nodes, emptySet(), false)
        assert(result.size == 2) { "Expected 2 nodes, got ${result.size}" }
        assert(result[0].first.node.name == "src")
        assert(result[0].second == 0) { "Root depth should be 0" }
        assert(result[1].first.node.name == "README.md")
        assert(result[1].second == 0)
    }

    @Test
    fun `flattenTree descends into expanded directory`() {
        val nodes = listOf(
            dir("src", "src", children = listOf(
                file("Main.kt", "src/Main.kt"),
                dir("test", "src/test")
            )),
            file("README.md", "README.md")
        )
        val result = flattenTree(nodes, setOf("src"), false)
        assert(result.size == 4) { "Expected 4, got ${result.size}" }
        assert(result[0].first.node.name == "src")
        assert(result[0].second == 0)
        assert(result[1].first.node.name == "Main.kt")
        assert(result[1].second == 1) { "Child depth should be 1" }
        assert(result[2].first.node.name == "test")
        assert(result[2].second == 1)
        assert(result[3].first.node.name == "README.md")
        assert(result[3].second == 0)
    }

    @Test
    fun `flattenTree nested expansion`() {
        val nodes = listOf(
            dir("src", "src", children = listOf(
                dir("main", "src/main", children = listOf(
                    file("App.kt", "src/main/App.kt")
                ))
            ))
        )
        val result = flattenTree(nodes, setOf("src", "src/main"), false)
        assert(result.size == 3) { "Expected 3, got ${result.size}" }
        assert(result[0].second == 0)  // src
        assert(result[1].second == 1)  // main
        assert(result[2].second == 2)  // App.kt
    }

    @Test
    fun `flattenTree does not descend into collapsed directory`() {
        val nodes = listOf(
            dir("src", "src", children = listOf(
                file("Main.kt", "src/Main.kt")
            ))
        )
        val result = flattenTree(nodes, emptySet(), false)
        assert(result.size == 1) { "Should only show root, got ${result.size}" }
    }

    @Test
    fun `flattenTree does not descend into directory with null children`() {
        val nodes = listOf(
            dir("src", "src", children = null)
        )
        val result = flattenTree(nodes, setOf("src"), false)
        assert(result.size == 1) { "Should not descend into null children" }
    }

    @Test
    fun `flattenTree filters ignored when showIgnored false`() {
        val nodes = listOf(
            file("app.kt", "app.kt"),
            file(".gitignore", ".gitignore", ignored = true)
        )
        val result = flattenTree(nodes, emptySet(), false)
        assert(result.size == 1) { "Ignored file should be filtered" }
        assert(result[0].first.node.name == "app.kt")
    }

    @Test
    fun `flattenTree shows ignored when showIgnored true`() {
        val nodes = listOf(
            file("app.kt", "app.kt"),
            file(".gitignore", ".gitignore", ignored = true)
        )
        val result = flattenTree(nodes, emptySet(), true)
        assert(result.size == 2)
    }

    @Test
    fun `flattenTree ignores inside expanded directory are filtered`() {
        val nodes = listOf(
            dir("src", "src", children = listOf(
                file("app.kt", "src/app.kt"),
                file("secret.env", "src/secret.env", ignored = true)
            ))
        )
        val result = flattenTree(nodes, setOf("src"), showIgnored = false)
        assert(result.size == 2) { "Expected src + app.kt only, got ${result.size}" }
    }

    // ===== withChildren =====

    @Test
    fun `withChildren sets children on matching root node`() {
        val nodes = listOf(
            dir("src", "src"),
            file("README.md", "README.md")
        )
        val children = listOf(file("Main.kt", "src/Main.kt"))
        val result = nodes.withChildren("src", children)
        assert(result[0].children?.size == 1) { "src should have 1 child" }
        assert(result[0].children!![0].node.name == "Main.kt")
        // README.md is a FILE with emptyList() children originally — unchanged
    }

    @Test
    fun `withChildren sets children on nested node`() {
        val nodes = listOf(
            dir("src", "src", children = listOf(
                dir("main", "src/main")
            ))
        )
        val children = listOf(file("App.kt", "src/main/App.kt"))
        val result = nodes.withChildren("src/main", children)
        assert(result[0].children!![0].children?.size == 1)
        assert(result[0].children!![0].children!![0].node.name == "App.kt")
    }

    @Test
    fun `withChildren returns unchanged when path not found`() {
        val nodes = listOf(
            dir("src", "src")
        )
        val result = nodes.withChildren("nonexistent", listOf(file("x", "x")))
        assert(result[0].children == null) { "Should remain unchanged" }
    }

    @Test
    fun `withChildren does not descend into null children`() {
        val nodes = listOf(
            dir("src", "src", children = null)
        )
        val result = nodes.withChildren("src/deep", listOf(file("x", "x")))
        assert(result[0].children == null)
    }
}
```

- [ ] **Step 2: 运行测试验证编译失败（函数未定义）**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun --tests "*.FileTreeUtilsTest"`
Expected: COMPILATION ERROR（`flattenTree` / `withChildren` unresolved reference）

- [ ] **Step 3: 创建 FileTreeUtils.kt 实现**

创建文件 `app/src/main/kotlin/dev/minios/ocremote/ui/screens/workspace/FileTreeUtils.kt`：

```kotlin
package dev.leonardo.ocremotev2.ui.screens.workspace

import dev.leonardo.ocremotev2.domain.model.isDirectory

/**
 * Flattens the file tree into a (node, depth) list for [LazyColumn] rendering.
 *
 * Only descends into directories whose [FileNode.path] is in [expandedDirs].
 * Ignored nodes are filtered out unless [showIgnored] is true.
 *
 * @param nodes       tree root (or sub-tree) to flatten
 * @param expandedDirs set of directory paths that are currently expanded
 * @param showIgnored if false, nodes with [FileNode.ignored] = true are skipped
 * @param depth       current indentation depth (0 for root)
 */
internal fun flattenTree(
    nodes: List<FileTreeNode>,
    expandedDirs: Set<String>,
    showIgnored: Boolean,
    depth: Int = 0
): List<Pair<FileTreeNode, Int>> =
    nodes.filter { showIgnored || !it.node.ignored }.flatMap { treeNode ->
        listOf(treeNode to depth) +
            if (treeNode.node.isDirectory() &&
                treeNode.node.path in expandedDirs &&
                treeNode.children != null
            ) {
                flattenTree(treeNode.children, expandedDirs, showIgnored, depth + 1)
            } else {
                emptyList()
            }
    }

/**
 * Returns a new tree where the node at [path] has its [children] replaced.
 *
 * Recursively searches all directories that already have non-null children.
 * Returns the original list unchanged if [path] is not found or lies behind
 * a node whose children have not been loaded yet (null).
 *
 * @param path     the [FileNode.path] of the target directory node
 * @param children new children list to assign
 */
internal fun List<FileTreeNode>.withChildren(
    path: String,
    children: List<FileTreeNode>
): List<FileTreeNode> = map { treeNode ->
    when {
        treeNode.node.path == path -> treeNode.copy(children = children)
        treeNode.node.isDirectory() && treeNode.children != null ->
            treeNode.copy(children = treeNode.children.withChildren(path, children))
        else -> treeNode
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun --tests "*.FileTreeUtilsTest"`
Expected: BUILD SUCCESSFUL，13 tests pass

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/workspace/FileTreeUtils.kt \
        app/src/test/kotlin/dev/minios/ocremote/ui/screens/workspace/FileTreeUtilsTest.kt
git commit -m "feat(workspace): add flattenTree and withChildren pure functions with tests"
```

---

## Task 3: WorkspaceViewModel toggleExpand + loadDirectory 改造

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/workspace/WorkspaceViewModel.kt:56-85`
- Modify: `app/src/test/kotlin/dev/minios/ocremote/ui/screens/workspace/WorkspaceViewModelTest.kt`（追加测试）

**Interfaces:**
- Consumes: `withChildren`（Task 2 产出）、`flattenTree`（Task 2 产出，本 Task 不直接使用但 ViewModel 可见）、`WorkspaceUiState.expandedDirs` / `loadingDirs`（Task 1 产出）
- Produces: `WorkspaceViewModel.toggleExpand(path: String): Unit` — UI 层点击目录时调用

**改造摘要：**
1. 新增 `toggleExpand(path)` 公开方法
2. `loadDirectory` 子目录成功路径：通过 `withChildren` 更新 rootNodes 树 + 设置 expandedDirs + 清除 loadingDirs
3. `loadDirectory` 子目录失败路径：清除 loadingDirs
4. `loadDirectory` 子目录开始：设置 loadingDirs
5. `refreshRoot`：清空 expandedDirs 和 loadingDirs

- [ ] **Step 1: 在 WorkspaceViewModelTest 追加 5 个失败测试**

在 `WorkspaceViewModelTest.kt` 的最后一个测试（Test 13 `blank serverId sets rootError...`，第 324 行 `}` 之前）之前，插入以下 5 个测试。即在第 311 行 `// ===== Test 13` 注释之前插入：

```kotlin
    // ===== Test 14: toggleExpand expands uncached directory =====
    @Test
    fun `toggleExpand expands uncached directory`() = runTest {
        val subNodes = listOf(
            FileNode("Main.kt", "src/Main.kt", "/home/user/project/src/Main.kt", FileType.FILE, false),
            FileNode("Utils.kt", "src/Utils.kt", "/home/user/project/src/Utils.kt", FileType.FILE, false)
        )
        coEvery { listDirectory(serverId, directory, "") } returns Result.success(sampleFileNodes)
        coEvery { listDirectory(serverId, directory, "src") } returns Result.success(subNodes)
        coEvery { getVcsStatus(serverId, directory) } returns Result.success(sampleGitChanges)

        val vm = WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus)

        vm.toggleExpand("src")

        val state = vm.uiState.value
        assert("src" in state.expandedDirs) { "src should be in expandedDirs" }
        assert("src" !in state.loadingDirs) { "src should not be in loadingDirs after success" }
        val srcNode = state.rootNodes.find { it.node.name == "src" }
        assert(srcNode != null) { "src node should exist in rootNodes" }
        assert(srcNode!!.children?.isNotEmpty() == true) { "src should have children after expand" }
        assert(srcNode.children!!.size == 2) { "Expected 2 children, got ${srcNode.children!!.size}" }
    }

    // ===== Test 15: toggleExpand collapses expanded directory =====
    @Test
    fun `toggleExpand collapses expanded directory`() = runTest {
        val subNodes = listOf(
            FileNode("Main.kt", "src/Main.kt", "/home/user/project/src/Main.kt", FileType.FILE, false)
        )
        coEvery { listDirectory(serverId, directory, "") } returns Result.success(sampleFileNodes)
        coEvery { listDirectory(serverId, directory, "src") } returns Result.success(subNodes)
        coEvery { getVcsStatus(serverId, directory) } returns Result.success(sampleGitChanges)

        val vm = WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus)

        vm.toggleExpand("src")
        vm.toggleExpand("src")  // collapse

        val state = vm.uiState.value
        assert("src" !in state.expandedDirs) { "src should not be in expandedDirs after collapse" }
    }

    // ===== Test 16: toggleExpand cached directory does not call API again =====
    @Test
    fun `toggleExpand cached directory does not call API again`() = runTest {
        val subNodes = listOf(
            FileNode("Main.kt", "src/Main.kt", "/home/user/project/src/Main.kt", FileType.FILE, false)
        )
        coEvery { listDirectory(serverId, directory, "") } returns Result.success(sampleFileNodes)
        coEvery { listDirectory(serverId, directory, "src") } returns Result.success(subNodes)
        coEvery { getVcsStatus(serverId, directory) } returns Result.success(sampleGitChanges)

        val vm = WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus)

        vm.toggleExpand("src")   // first expand — API call
        vm.toggleExpand("src")   // collapse
        vm.toggleExpand("src")   // re-expand — cached, no API call

        coVerify(exactly = 1) { listDirectory(serverId, directory, "src") }
    }

    // ===== Test 17: loadDirectory subdirectory failure clears loadingDirs =====
    @Test
    fun `loadDirectory subdirectory failure clears loadingDirs`() = runTest {
        coEvery { listDirectory(serverId, directory, "") } returns Result.success(sampleFileNodes)
        coEvery { listDirectory(serverId, directory, "src") } returns Result.failure(
            RuntimeException("Network error")
        )
        coEvery { getVcsStatus(serverId, directory) } returns Result.success(sampleGitChanges)

        val vm = WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus)

        vm.toggleExpand("src")

        val state = vm.uiState.value
        assert("src" !in state.loadingDirs) { "loadingDirs should be cleared on failure" }
        assert("src" !in state.expandedDirs) { "should not expand on failure" }
    }

    // ===== Test 18: refreshRoot clears expandedDirs and loadingDirs =====
    @Test
    fun `refreshRoot clears expandedDirs and loadingDirs`() = runTest {
        val subNodes = listOf(
            FileNode("Main.kt", "src/Main.kt", "/home/user/project/src/Main.kt", FileType.FILE, false)
        )
        coEvery { listDirectory(serverId, directory, "") } returns Result.success(sampleFileNodes)
        coEvery { listDirectory(serverId, directory, "src") } returns Result.success(subNodes)
        coEvery { getVcsStatus(serverId, directory) } returns Result.success(sampleGitChanges)

        val vm = WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus)
        vm.toggleExpand("src")  // expand src

        assert("src" in vm.uiState.value.expandedDirs) { "Precondition: src should be expanded" }

        vm.refreshRoot()

        val state = vm.uiState.value
        assert(state.expandedDirs.isEmpty()) { "expandedDirs should be cleared on refresh" }
        assert(state.loadingDirs.isEmpty()) { "loadingDirs should be cleared on refresh" }
    }
```

- [ ] **Step 2: 运行测试验证失败（toggleExpand 未定义）**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun --tests "*.WorkspaceViewModelTest.toggleExpand*"`
Expected: COMPILATION ERROR（`toggleExpand` unresolved reference）

- [ ] **Step 3: 修改 WorkspaceViewModel — 改造 loadDirectory**

将 `WorkspaceViewModel.kt` 第 56-79 行的 `loadDirectory` 方法替换为以下代码：

```kotlin
    fun loadDirectory(path: String) {
        if (serverId.isBlank()) return
        dirCache[path]?.let { return }
        loadJobs[path]?.cancel()
        if (path.isEmpty()) {
            _uiState.update { it.copy(rootLoading = true, rootError = null) }
        } else {
            _uiState.update { it.copy(loadingDirs = it.loadingDirs + path) }
        }
        loadJobs[path] = viewModelScope.launch {
            listDirectory(serverId, directory, path)
                .onSuccess { nodes ->
                    dirCache[path] = nodes
                    if (path.isEmpty()) {
                        _uiState.update {
                            it.copy(rootNodes = nodes.toTreeNodes(), rootLoading = false)
                        }
                    } else {
                        _uiState.update { state ->
                            state.copy(
                                rootNodes = state.rootNodes.withChildren(path, nodes.toTreeNodes()),
                                expandedDirs = state.expandedDirs + path,
                                loadingDirs = state.loadingDirs - path
                            )
                        }
                        _dirLoadEvents.tryEmit(DirectoryLoadResult(path, nodes, null))
                    }
                }
                .onFailure { e ->
                    if (path.isEmpty()) {
                        _uiState.update {
                            it.copy(rootLoading = false, rootError = R.string.workspace_error_load_failed)
                        }
                    } else {
                        _uiState.update { it.copy(loadingDirs = it.loadingDirs - path) }
                        _dirLoadEvents.tryEmit(DirectoryLoadResult(path, emptyList(), e.message))
                    }
                }
        }
    }
```

- [ ] **Step 4: 修改 WorkspaceViewModel — 新增 toggleExpand 方法**

在 `loadDirectory` 方法之后（`refreshRoot` 方法之前，原第 80 行位置）插入以下方法：

```kotlin
    fun toggleExpand(path: String) {
        val state = _uiState.value
        when {
            // Already expanded → collapse
            path in state.expandedDirs ->
                _uiState.update { it.copy(expandedDirs = it.expandedDirs - path) }
            // Cached but not expanded → expand (children already in tree from prior load)
            path in dirCache ->
                _uiState.update { it.copy(expandedDirs = it.expandedDirs + path) }
            // Not loaded → trigger async load
            else -> {
                _uiState.update { it.copy(loadingDirs = it.loadingDirs + path) }
                loadDirectory(path)
            }
        }
    }
```

- [ ] **Step 5: 修改 WorkspaceViewModel — refreshRoot 清空展开状态**

将 `WorkspaceViewModel.kt` 中的 `refreshRoot` 方法（原第 81-85 行）替换为以下代码：

```kotlin
    fun refreshRoot() {
        dirCache.clear()
        loadJobs.values.forEach { it.cancel() }
        _uiState.update { it.copy(expandedDirs = emptySet(), loadingDirs = emptySet()) }
        loadDirectory("")
    }
```

- [ ] **Step 6: 编译检查**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 运行全部 ViewModel 测试验证通过**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun --tests "*.WorkspaceViewModelTest"`
Expected: BUILD SUCCESSFUL，18 tests pass（原 13 + 新增 5）

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/workspace/WorkspaceViewModel.kt \
        app/src/test/kotlin/dev/minios/ocremote/ui/screens/workspace/WorkspaceViewModelTest.kt
git commit -m "feat(workspace): add toggleExpand and lazy-load subdirectory children"
```

---

## Task 4: 字符串资源 + FileTreePanel + FileTreeItem UI 改造

**Files:**
- Modify: `app/src/main/res/values/strings.xml:693`（workspace 字符串区域）
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/workspace/tree/FileTreePanel.kt`

**Interfaces:**
- Consumes: `WorkspaceUiState.expandedDirs` / `loadingDirs`（Task 1 产出）、`flattenTree`（Task 2 产出）
- Produces: `FileTreePanel` 新增参数 `onToggleExpand: (String) -> Unit`；`FileTreeItem` 显示展开/收起箭头 + loading spinner

- [ ] **Step 1: 添加字符串资源**

在 `app/src/main/res/values/strings.xml` 中，找到第 693 行 `<string name="workspace_retry">Retry</string>`，在其下方插入两行：

```xml
    <string name="a11y_icon_expand_directory">Expand directory</string>
    <string name="a11y_icon_collapse_directory">Collapse directory</string>
```

- [ ] **Step 2: 改造 FileTreePanel.kt — 全文替换**

将 `FileTreePanel.kt` 全文替换为以下内容（改动点：import 新增箭头图标、`flattenTree` 替代 `flatten`、`FileTreePanel` 签名新增 `onToggleExpand`、`FileTreeItem` 新增 `isExpanded`/`isLoading`/`onToggleExpand` 参数 + 展开箭头 UI、移除旧 private `flatten` 函数）：

```kotlin
package dev.leonardo.ocremotev2.ui.screens.workspace.tree

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leonardo.ocremotev2.R
import dev.leonardo.ocremotev2.domain.model.isDirectory
import dev.leonardo.ocremotev2.ui.screens.workspace.FileTreeNode
import dev.leonardo.ocremotev2.ui.screens.workspace.WorkspaceUiState
import dev.leonardo.ocremotev2.ui.screens.workspace.flattenTree
import dev.leonardo.ocremotev2.ui.theme.SpacingTokens

/**
 * File tree panel: renders the workspace as a flattened, depth-indented list.
 * Directories can be expanded/collapsed via [onToggleExpand]; sub-directory
 * children are lazily loaded on first expansion.
 */
@Composable
fun FileTreePanel(
    uiState: WorkspaceUiState,
    onRefreshRoot: () -> Unit,
    onToggleShowIgnored: () -> Unit,
    onOpenFile: (String) -> Unit,
    onToggleExpand: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val emptyDirectoryMessage = stringResource(R.string.workspace_empty_directory)
    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(SpacingTokens.SM.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onRefreshRoot) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.workspace_refresh))
            }
            FilterChip(
                selected = uiState.showIgnored,
                onClick = onToggleShowIgnored,
                label = { Text(stringResource(R.string.workspace_show_ignored)) },
                leadingIcon = { Icon(Icons.Filled.Visibility, contentDescription = null) }
            )
        }
        when {
            uiState.rootLoading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.testTag("file_tree_loading"))
            }

            uiState.rootError != null -> FileTreeErrorState(
                error = uiState.rootError,
                onRetry = onRefreshRoot
            )

            uiState.rootNodes.isEmpty() -> FileTreeEmptyState(message = emptyDirectoryMessage)

            else -> {
                val flattened = remember(
                    uiState.rootNodes,
                    uiState.expandedDirs,
                    uiState.showIgnored
                ) {
                    flattenTree(uiState.rootNodes, uiState.expandedDirs, uiState.showIgnored)
                }
                LazyColumn {
                    items(flattened, key = { it.first.node.path }) { (treeNode, depth) ->
                        FileTreeItem(
                            treeNode = treeNode,
                            depth = depth,
                            isExpanded = treeNode.node.path in uiState.expandedDirs,
                            isLoading = treeNode.node.path in uiState.loadingDirs,
                            onOpenFile = onOpenFile,
                            onToggleExpand = onToggleExpand
                        )
                    }
                }
            }
        }
    }
}

/**
 * Single row in the file tree.
 * - Directories invoke [onToggleExpand] with their path and show an expand/collapse arrow.
 * - Files invoke [onOpenFile] with their path.
 * - When [isLoading] is true (sub-directory being fetched), a small spinner replaces the arrow.
 */
@Composable
fun FileTreeItem(
    treeNode: FileTreeNode,
    depth: Int,
    isExpanded: Boolean,
    isLoading: Boolean,
    onOpenFile: (String) -> Unit,
    onToggleExpand: (String) -> Unit
) {
    val isDirectory = treeNode.node.isDirectory()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (isDirectory) onToggleExpand(treeNode.node.path)
                else onOpenFile(treeNode.node.path)
            }
            .padding(start = (depth * SpacingTokens.LG).dp)
            .padding(vertical = SpacingTokens.SM.dp, horizontal = SpacingTokens.MD.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isDirectory) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    imageVector = if (isExpanded) Icons.Filled.KeyboardArrowDown
                                  else Icons.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(
                        if (isExpanded) R.string.a11y_icon_collapse_directory
                        else R.string.a11y_icon_expand_directory
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(SpacingTokens.XS.dp))
        }

        Icon(
            imageVector = if (isDirectory) Icons.Filled.Folder else Icons.Filled.Description,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(SpacingTokens.SM.dp))
        Text(
            text = treeNode.node.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun FileTreeErrorState(
    error: Int,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.MD.dp)
        ) {
            Text(
                text = stringResource(error),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            TextButton(onClick = onRetry) { Text(stringResource(R.string.workspace_retry)) }
        }
    }
}

@Composable
private fun FileTreeEmptyState(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
```

- [ ] **Step 3: 编译检查（预期失败 — WorkspaceScreen 尚未传递 onToggleExpand）**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: COMPILE ERROR — `WorkspaceScreen.kt` 调用 `FileTreePanel` 缺少 `onToggleExpand` 参数

> **注意：** 此编译错误是预期的，将在 Task 5 中修复。此处不做 commit，直接进入 Task 5。

---

## Task 5: WorkspaceScreen 接线 + 编译通过

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/workspace/WorkspaceScreen.kt:34-87`

**Interfaces:**
- Consumes: `FileTreePanel` 的新签名（Task 4 产出）、`WorkspaceViewModel.toggleExpand`（Task 3 产出）
- Produces: `WorkspaceRoute` 和 `WorkspaceScreen` 新增 `onToggleExpand` 参数透传

- [ ] **Step 1: 修改 WorkspaceRoute 传递 onToggleExpand**

在 `WorkspaceScreen.kt` 第 42-51 行的 `WorkspaceScreen(...)` 调用中，在 `onToggleShowIgnored = viewModel::toggleShowIgnored,` 之后添加一行：

```kotlin
        onToggleExpand = viewModel::toggleExpand,
```

修改后的 `WorkspaceRoute` 函数体（第 34-52 行）完整替换为：

```kotlin
@Composable
fun WorkspaceRoute(
    viewModel: WorkspaceViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onOpenFile: (filePath: String) -> Unit,
    onOpenGitDiff: (filePath: String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    WorkspaceScreen(
        uiState = uiState,
        onBack = onBack,
        onSwitchPanel = viewModel::switchPanel,
        onRefreshRoot = viewModel::refreshRoot,
        onToggleShowIgnored = viewModel::toggleShowIgnored,
        onToggleExpand = viewModel::toggleExpand,
        onRefreshGit = viewModel::loadGitChanges,
        onOpenFile = onOpenFile,
        onOpenGitDiff = onOpenGitDiff
    )
}
```

- [ ] **Step 2: 修改 WorkspaceScreen 函数签名 + FileTreePanel 调用**

在 `WorkspaceScreen.kt` 第 56-65 行的 `WorkspaceScreen` 函数签名中，在 `onToggleShowIgnored: () -> Unit,` 之后添加一行：

```kotlin
    onToggleExpand: (String) -> Unit,
```

然后修改第 72-78 行的 `FileTreePanel(...)` 调用，添加 `onToggleExpand = onToggleExpand,`：

```kotlin
            WorkspacePanel.FILE_TREE -> FileTreePanel(
                uiState = uiState,
                onRefreshRoot = onRefreshRoot,
                onToggleShowIgnored = onToggleShowIgnored,
                onOpenFile = onOpenFile,
                onToggleExpand = onToggleExpand,
                modifier = Modifier.padding(padding)
            )
```

修改后的完整 `WorkspaceScreen` 函数（第 55-87 行）：

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(
    uiState: WorkspaceUiState,
    onBack: () -> Unit,
    onSwitchPanel: (WorkspacePanel) -> Unit,
    onRefreshRoot: () -> Unit,
    onToggleShowIgnored: () -> Unit,
    onToggleExpand: (String) -> Unit,
    onRefreshGit: () -> Unit,
    onOpenFile: (String) -> Unit,
    onOpenGitDiff: (String) -> Unit
) {
    Scaffold(
        topBar = {
            WorkspaceTopBar(uiState = uiState, onBack = onBack, onSwitchPanel = onSwitchPanel)
        }
    ) { padding ->
        when (uiState.currentPanel) {
            WorkspacePanel.FILE_TREE -> FileTreePanel(
                uiState = uiState,
                onRefreshRoot = onRefreshRoot,
                onToggleShowIgnored = onToggleShowIgnored,
                onOpenFile = onOpenFile,
                onToggleExpand = onToggleExpand,
                modifier = Modifier.padding(padding)
            )
            WorkspacePanel.GIT_CHANGES -> GitChangesPanel(
                uiState = uiState,
                onRefresh = onRefreshGit,
                onOpenDiff = onOpenGitDiff,
                modifier = Modifier.padding(padding)
            )
        }
    }
}
```

- [ ] **Step 3: 编译检查**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 运行全部单元测试确认无回归**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun`
Expected: BUILD SUCCESSFUL，全部测试通过

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/values/strings.xml \
        app/src/main/kotlin/dev/minios/ocremote/ui/screens/workspace/tree/FileTreePanel.kt \
        app/src/main/kotlin/dev/minios/ocremote/ui/screens/workspace/WorkspaceScreen.kt
git commit -m "feat(workspace): wire expand/collapse UI with lazy-load indicators"
```

---

## Task 6: Maestro E2E flow 更新

**Files:**
- Modify: `maestro/e2e-workspace-file-tree.yaml:74`

**Interfaces:**
- Consumes: Task 1-5 全部功能
- Produces: E2E 回归测试覆盖目录展开/收起

- [ ] **Step 1: 在 Maestro flow 中添加目录展开/收起步骤**

在 `maestro/e2e-workspace-file-tree.yaml` 第 74 行（`- takeScreenshot: e2e_ws_file_tree`）之后、第 76 行（`# Tap a file`）之前，插入以下步骤：

```yaml

# Expand a directory by tapping on it
- tapOn:
    text: "src"
    optional: true

- extendedWaitUntil:
    visible: ".*"
    timeout: 5000

- takeScreenshot: e2e_ws_dir_expanded

# Collapse by tapping the same directory again
- tapOn:
    text: "src"
    optional: true

- takeScreenshot: e2e_ws_dir_collapsed
```

- [ ] **Step 2: 验证 YAML 语法**

Run（在项目根目录）: `maestro validate maestro/e2e-workspace-file-tree.yaml`
Expected: 输出 "Config validated successfully" 或等价成功消息

> **注意：** 如果环境中未安装 `maestro` CLI，跳过此步骤，在真机/模拟器上手动运行 flow 验证。完整 E2E 运行需要连接的 OpenCode 服务器（`10.0.2.2:4096`）。

- [ ] **Step 3: Commit**

```bash
git add maestro/e2e-workspace-file-tree.yaml
git commit -m "test(workspace): add directory expand/collapse steps to E2E flow"
```

---

## Self-Review

### 1. Spec coverage

| 需求 | 覆盖 Task |
|------|-----------|
| 展开状态管理（expandedDirs） | Task 1 (字段) + Task 3 (toggleExpand 逻辑) |
| 目录点击行为：已展开→收起 | Task 3 (toggleExpand `path in expandedDirs` 分支) |
| 目录点击行为：已缓存→展开 | Task 3 (toggleExpand `path in dirCache` 分支) |
| 目录点击行为：未缓存→loading+加载+展开 | Task 3 (toggleExpand `else` 分支 + loadDirectory 改造) |
| flatten 只递进已展开目录 | Task 2 (flattenTree `expandedDirs` 参数) |
| FileTreeItem 展开箭头 | Task 4 (KeyboardArrowDown / KeyboardArrowRight) |
| FileTreeItem loading spinner | Task 4 (isLoading 分支 CircularProgressIndicator) |
| dirLoadEvents → 树更新 | Task 3 (loadDirectory 成功路径 withChildren) |
| 目录排序（目录在前） | 已有 `toTreeNodes()` 逻辑，无需改动 |
| 单元测试（flatten + expand/collapse） | Task 2 (FileTreeUtilsTest) + Task 3 (ViewModelTest 新增 5 个) |
| Maestro E2E | Task 6 |

### 2. Placeholder scan

- 无 "TBD" / "TODO" / "implement later"
- 无 "add error handling" 等模糊指令
- 所有代码步骤都包含完整代码块
- Gradle 命令均带 flavor 和预期输出

### 3. Type consistency

| 符号 | 定义 Task | 使用 Task |
|------|----------|----------|
| `WorkspaceUiState.expandedDirs: Set<String>` | Task 1 | Task 3 (VM), Task 4 (UI) |
| `WorkspaceUiState.loadingDirs: Set<String>` | Task 1 | Task 3 (VM), Task 4 (UI) |
| `flattenTree(nodes, expandedDirs, showIgnored, depth)` | Task 2 | Task 4 (FileTreePanel) |
| `List<FileTreeNode>.withChildren(path, children)` | Task 2 | Task 3 (loadDirectory) |
| `WorkspaceViewModel.toggleExpand(path: String)` | Task 3 | Task 5 (WorkspaceRoute) |
| `FileTreePanel(..., onToggleExpand: (String) -> Unit, ...)` | Task 4 | Task 5 (WorkspaceScreen) |
| `FileTreeItem(treeNode, depth, isExpanded, isLoading, onOpenFile, onToggleExpand)` | Task 4 | Task 4 (FileTreePanel 内部) |

所有签名一致。

### 4. 边界条件覆盖

- **空树**：`flattenTree` Test "empty list returns empty"
- **目录 children=null + 已展开**：`flattenTree` Test "does not descend into null children"
- **收起再展开**：ViewModelTest Test 16 "cached directory does not call API"
- **加载失败**：ViewModelTest Test 17 "failure clears loadingDirs"
- **refreshRoot 重置**：ViewModelTest Test 18 "clears expandedDirs and loadingDirs"
- **withChildren 路径不存在**：FileTreeUtilsTest "returns unchanged when path not found"
- **withChildren null children 不递归**：FileTreeUtilsTest "does not descend into null children"
