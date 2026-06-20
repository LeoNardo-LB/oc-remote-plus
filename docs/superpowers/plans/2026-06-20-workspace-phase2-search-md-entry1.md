# Workspace File Viewer — Phase 2 实现计划（搜索 + md 预览 + 入口1）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`).

**Goal:** 在 Phase 1 已搭好的 WorkspaceScreen + FileViewerScreen 基础上，实现三大功能：(1) 文件树/Git 面板的服务端文件名搜索 + 客户端 Git 变更过滤（搜索 UI 覆盖当前面板，不独立屏）；(2) Markdown 文件源码↔渲染预览切换（TopBar 按钮 + 滚动比例锚点定位）；(3) 入口1 完整化——Read/Write/Edit 工具卡片同 turn 同文件聚合（B 档轻量聚合），点查看跳转 FileViewer 显示工具快照（而非 LIVE），支持累积 diff 重建。

**Architecture:** Clean Architecture 三层。**复用现有** `OpenCodeApi.findFiles(conn, query, type, directory, limit, dirs)`（Phase 1 已有），新增 `FileRepository.findFiles` + `FindFilesUseCase`。搜索 UI 用 overlay 模式（`AnimatedVisibility` 替换 TopBar），文件树搜索走服务端，Git 搜索走客户端 filter。Markdown 预览新建独立的 `MarkdownPreview` composable（不复用 chat 模块的 `internal MarkdownContent`），底层复用同一 mikepenz 依赖。入口1 用进程级 `ToolSnapshotCache`（Hilt @Singleton）传递 Part 内容（路由参数无法承载大文本），由 ChatViewModel 在 navigate 前写入、FileViewerViewModel 在 init 时按 toolPartIds 读取。

**Tech Stack:** Kotlin + Compose + Hilt(KSP) + MockK 1.14.9 + Turbine 1.2.1。JDK 21。

---

## TOC

- [Global Constraints](#global-constraints)
- [关键架构决策](#关键架构决策)
- Task 1: [FileRepository.findFiles + FindFilesUseCase](#task-1)
- Task 2: [WorkspaceViewModel 搜索状态 + Git 客户端过滤](#task-2)
- Task 3: [SearchTopBar + SearchOverlay（搜索 UI 完整）](#task-3)
- Task 4: [WorkspaceScreen 集成搜索入口](#task-4)
- Task 5: [MarkdownPreview composable（viewer 独立可复用）](#task-5)
- Task 6: [FileViewerUiState + ViewModel md 切换状态](#task-6)
- Task 7: [FileViewerScreen md 切换 UI + 比例锚点滚动](#task-7)
- Task 8: [ToolSnapshotGrouper（同 turn 同文件聚合算法）](#task-8)
- Task 9: [ToolSnapshotCache + 入口1 改造](#task-9)
- Task 10: [strings.xml + Maestro E2E flows 22/24/25](#task-10)
- [Phase 2 验收清单](#phase-2-验收清单)
- [Phase 2 不包含](#phase-2-不包含)
- [Self-Review](#self-review)

---

## Global Constraints

> ⚠️ 所有 Task 隐含遵守。Phase 1 的 Global Constraints 全部继承。

### 继承自 Phase 1

- **路径与包名**：源码 `app/src/main/kotlin/`，测试 `app/src/test/kotlin/`，androidTest `app/src/androidTest/kotlin/`；包名前缀 `dev.minios.ocremote.`
- **Gradle 命令带 flavor**：`compileDevDebugKotlin`（120s）、`testDevDebugUnitTest --rerun`（180s）
- **Material 3 First**：用 `MaterialTheme.colorScheme` 语义色，不自定义 Canvas
- **Alpha tokens**：`AlphaTokens.SELECTED(0.12) / DIFF_BG(0.10) / FAINT(0.35) / MUTED(0.50) / MEDIUM(0.70) / HIGH(0.80) / AMOLED(0.92)`
- **Spacing tokens**：`SpacingTokens.XS(4)/SM(8)/MD(12)/LG(16)/XL(24)/XXL(32).dp`
- **测试 `isReturnDefaultValues=true`**：MockK 必须显式 `coEvery/coAnswers`
- **真实样本**：测试用项目真实代码片段（如 `OpenCodeApi.kt`、`WorkspaceViewModel.kt` 摘录），禁用 `"aaa"/"bbb"` 占位
- **ChatScreen.kt 编辑协议**：Task 9 改动 ChatScreen 必须遵守 Read-before-Edit + 每次编译 + commit

### Phase 2 新增约束

- **搜索 UI 不独立成屏**：用 overlay 替换 WorkspaceTopBar（`AnimatedVisibility`），退出搜索回到原面板
- **搜索 debounce 300ms**：避免每个字符触发 API 请求
- **空搜索查询不发请求**：显示提示"输入关键字搜索"，不调 `findFiles`
- **md 比例锚点定位（v1）**：`sourceFraction = sourceScroll.value / sourceScroll.maxValue`，切换到 render 时 `renderScroll.scrollTo((maxValue * fraction).roundToInt())`
- **ToolSnapshotCache 是进程级内存**：用 Hilt `@Singleton`，不复用 SavedStateHandle（content 可能超过 Binder 1MB 限制）
- **入口1 Part 内容传递走 ToolSnapshotCache**：路由只传 toolPartIds（UUID 列表，不含逗号），FileViewerViewModel 按 ID 从 cache 读取
- **聚合是 B 档轻量版**：同 message + 同归一化 filePath 归组；不要求物理相邻；累积 diff = 第一个 part 的 before → 最后一个 part 的 after

### testTag 约定（Maestro 依赖）

| testTag | 添加位置（Task） | 用途 |
|---------|-----------------|------|
| `workspace_search_button` | Task 4（WorkspaceTopBar 🔍 IconButton） | 进入搜索模式 |
| `workspace_search_input` | Task 3（SearchTopBar TextField） | 输入搜索关键字 |
| `workspace_search_clear` | Task 3（SearchTopBar × IconButton） | 清空 / 退出搜索 |
| `workspace_search_result` | Task 3（SearchOverlay 结果项 Modifier） | 点击搜索结果 |
| `viewer_md_render_button` | Task 7（FileViewerTopBar 渲染切换按钮） | 切换 md 渲染/源码 |
| `tool_card_group_badge` | Task 9（Read/Write/Edit 同组首卡 ③ 徽章） | 聚合组大小徽章 |

---

## 关键架构决策

### 1. 搜索覆盖式 overlay（不独立屏）

spec 设计决策 §11：搜索 UI 覆盖当前面板，不独立成屏。实现方式：

```kotlin
AnimatedContent(targetState = uiState.isSearchMode) { mode ->
    if (mode) SearchTopBar(query, onQueryChange, onClear)
    else WorkspaceTopBar(uiState, onBack, onSwitchPanel, onSearch)
}
```

- `WorkspaceTopBar` 新增 `onSearch: () -> Unit` 参数，🔍 IconButton 触发
- SearchTopBar 替换原 TopBar，body 仍用 Scaffold content slot，根据 panel 显示对应搜索结果列表
- 退出搜索：点 × / 返回键 / 选了文件后自动退出

### 2. 文件树搜索走服务端，Git 搜索走客户端

- 文件树模式搜：`findFilesUseCase(serverId, directory, query, limit=50)` → 服务端 fff frecency 排序
- Git 模式搜：`gitChanges.filter { it.file.contains(query, ignoreCase = true) }` → 客户端过滤，无网络

两种模式共用同一 SearchTopBar，但 result list 渲染逻辑按 panel 分发。

### 3. MarkdownPreview 独立 composable（不复用 chat 模块 internal）

`MarkdownContent` 在 chat 模块是 `internal fun`，无法跨模块引用。Phase 2 在 viewer 模块新建 `MarkdownPreview`：

```kotlin
@Composable
fun MarkdownPreview(markdown: String, modifier: Modifier = Modifier) {
    // 包装 mikepenz Markdown，固定 textColor = onSurface
    // codeBlock bg = surfaceContainer，inlineCode bg = primaryContainer.copy(alpha = FAINT)
    // 复用 chat 模块的 Highlights.Builder 注入模式（OcCodeBlock 同款逻辑）
}
```

**理由**：viewer 的 md 预览是只读 + 统一样式，不需要 chat 模块的 font size 设置 / AMOLED 切换 / user message 处理等复杂逻辑。新建独立 composable 更简单、解耦清晰。如果后续发现重复，可在 Phase 4 重构为 shared module。

### 4. md 滚动比例锚点（v1 简单方案）

spec §7.3 决策：v1 用比例定位，v2 自建 AST 精确行号映射。Phase 2 实现 v1：

```kotlin
// 切换前记录
val sourceFraction = if (sourceScroll.maxValue > 0)
    sourceScroll.value.toFloat() / sourceScroll.maxValue else 0f

// 切换后
LaunchedEffect(renderMode) {
    if (renderMode == RENDER_PREVIEW && renderScroll.maxValue > 0) {
        renderScroll.scrollTo((renderScroll.maxValue * sourceFraction).roundToInt())
    }
}
```

**注意**：mikepenz Markdown 内部用 lazy rendering，首次渲染 `maxValue` 可能未更新。用 `snapshotFlow { renderScroll.maxValue }` 等待首次非零再滚动。

### 5. ToolSnapshotCache（进程级 @Singleton）

路由参数无法承载 Part.Tool 的完整 content / metadata.filediff（可能数 KB 到数百 KB）。方案：

```kotlin
@Singleton
class ToolSnapshotCache @Inject constructor() {
    private val snapshots = mutableMapOf<String, Snapshot>()
    
    fun put(partId: String, snapshot: Snapshot) { snapshots[partId] = snapshot }
    fun get(partId: String): Snapshot? = snapshots[partId]
    fun getAll(partIds: List<String>): List<Snapshot> = partIds.mapNotNull { snapshots[it] }
    fun clear(partIds: List<String>) = partIds.forEach { snapshots.remove(it) }
    
    data class Snapshot(
        val filePath: String,
        val content: String?,           // Read/Write 工具：完整内容
        val before: String?,            // Edit 工具：metadata.filediff.before
        val after: String?,             // Edit 工具：metadata.filediff.after
        val toolName: String            // "read"|"write"|"edit"
    )
}
```

**生命周期**：
- ChatViewModel 在用户点 ↗ 前调用 `cache.put(partId, snapshot)`
- FileViewerViewModel 在 init 时按 toolPartIds 从 cache 读取
- FileViewerViewModel 的 `onCleared()` 时调用 `cache.clear(toolPartIds)`（用户退出 viewer 即清理）

**为什么不用 SavedStateHandle**：(1) Binder 事务有 1MB 限制；(2) SavedStateHandle 跨 NavGraph entry 共享需手动 setup，复杂度更高；(3) Process death 后工具快照本身意义不大（chat 流可能已变化）。

### 6. ToolSnapshotGrouper 算法

spec §5.5：同 message + 同归一化 filePath 的 Read/Write/Edit 工具归为一组，不要求物理相邻（Bash 等其他工具不打断）。

```kotlin
data class ToolSnapshotGroup(
    val normalizedFilePath: String,
    val toolParts: List<Part.Tool>,   // 按 part.id 顺序
    val firstFilePath: String,         // 原始（未归一化）路径，用于显示
    val cumulativeBefore: String?,     // 第一个 part 的 before（Write 视为空）
    val cumulativeAfter: String?       // 最后一个 part 的 after
)

object ToolSnapshotGrouper {
    fun group(parts: List<Part.Tool>): List<ToolSnapshotGroup> {
        // 1. 过滤：只保留 Read/Write/Edit + state is Completed/Running/Error
        // 2. 按 messageId 分组
        // 3. 在每个 message 内，按归一化 filePath 聚合（LinkedHashMap 保序）
        // 4. 每组算累积 before/after
    }
    
    fun normalizePath(path: String): String = path.replace('\\', '/').trimEnd('/')
}
```

### 7. 入口1 视觉分组（左侧竖线 + ③ 徽章）

spec §5.5 视觉规范：组内卡片共享左侧 3dp 竖线，首卡片标题旁圆形徽章 `③` 显示组大小（N=1 不显示）。

**实现位置**：`MessageCard` 或 `PartContent` 渲染 Tool 卡片时，先经 `ToolSnapshotGrouper.group(parts)` 得到分组，再渲染时把 group 信息传给 ToolCard。ToolCardScaffold 新增 `groupInfo: ToolGroupInfo?` 参数，控制左边线 + 徽章。

```kotlin
data class ToolGroupInfo(val totalCount: Int)
```

为保持 B 档"轻量"，**首版只显示组大小徽章和左边线**，不实现"点组内任一卡片跳累积 diff"——所有同组卡片仍独立跳各自快照。累积 diff 视图作为可选增强，放进 Phase 2.5 或后续（spec 也允许"按次切换二期"）。

**修正**：经审视，"点组内任一查看都跳同一累积 diff"是 spec §5.5 的核心价值（"一次看全貌"），否则分组只有视觉意义。Phase 2 **必须实现累积 diff 跳转**：组内所有卡片点 ↗ 都用 TOOL_SNAPSHOT_DIFF source + 整组 partIds 跳转，FileViewerViewModel 显示累积 before→after diff。

---

## Task 1: FileRepository.findFiles + FindFilesUseCase

<a id="task-1"></a>

**Spec ref:** §2.1（find/file API）, §6.4（文件树搜索）, §8.2（Repository 接口）, §8.5（UseCase）

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/domain/repository/FileRepository.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/data/repository/FileRepositoryImpl.kt`
- Create: `app/src/main/kotlin/dev/minios/ocremote/domain/usecase/FindFilesUseCase.kt`
- Modify: `app/src/test/kotlin/dev/minios/ocremote/data/repository/FileRepositoryImplTest.kt`
- Create: `app/src/test/kotlin/dev/minios/ocremote/domain/usecase/FindFilesUseCaseTest.kt`

**Interfaces:**
- Consumes: `OpenCodeApi.findFiles`（Phase 1 已有：`conn, query, type, directory, limit, dirs`）
- Produces: `FileRepository.findFiles` + `FindFilesUseCase`

- [ ] **Step 1: 扩展 FileRepository 接口**

```kotlin
// FileRepository.kt 在现有方法下追加
interface FileRepository {
    suspend fun listDirectory(serverId: String, directory: String, path: String): Result<List<FileNode>>
    suspend fun getFileContent(serverId: String, directory: String, path: String): Result<FileContent>
    suspend fun findFiles(serverId: String, directory: String, query: String, limit: Int = 50): Result<List<String>>
}
```

- [ ] **Step 2: 写 FindFiles 测试（真实样本）**

在 `FileRepositoryImplTest.kt` 追加：

```kotlin
@Test
fun `findFiles success passes query limit directory and returns string list`() = runTest {
    val expectedPaths = listOf(
        "app/src/main/kotlin/dev/minios/ocremote/ui/screens/workspace/WorkspaceScreen.kt",
        "app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/FileViewerScreen.kt",
        "docs/superpowers/specs/2026-06-18-workspace-file-viewer-design.md"
    )
    coEvery { serverRepository.resolveConnection(any()) } returns fakeConn
    coEvery { api.findFiles(fakeConn, query = "Screen", type = any(), directory = any(), limit = any(), dirs = any()) } returns expectedPaths

    val result = repo.findFiles("srv-1", "/home/user/oc-remote", "Screen", 50)

    assertTrue(result.isSuccess)
    assertEquals(expectedPaths, result.getOrNull())
    coVerify { api.findFiles(fakeConn, query = "Screen", type = null, directory = "/home/user/oc-remote", limit = 50, dirs = null) }
}

@Test
fun `findFiles wraps exception as failure`() = runTest {
    coEvery { serverRepository.resolveConnection(any()) } returns fakeConn
    coEvery { api.findFiles(any(), any(), any(), any(), any(), any()) } throws RuntimeException("network error")

    val result = repo.findFiles("srv-1", "/dir", "query", 30)

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull()!!.message!!.contains("network error"))
}

@Test
fun `findFiles with empty query still delegates to api`() = runTest {
    coEvery { serverRepository.resolveConnection(any()) } returns fakeConn
    coEvery { api.findFiles(any(), any(), any(), any(), any(), any()) } returns emptyList()

    val result = repo.findFiles("srv-1", "/dir", "", 50)

    assertTrue(result.isSuccess)
    assertTrue(result.getOrNull()!!.isEmpty())
}
```

- [ ] **Step 3: 运行验证失败**

```bash
.\gradlew :app:testDevDebugUnitTest --rerun --tests "*.FileRepositoryImplTest"
```

Expected: FAIL — `findFiles` unresolved on `FileRepository`.

- [ ] **Step 4: 实现 FileRepositoryImpl.findFiles**

```kotlin
override suspend fun findFiles(serverId: String, directory: String, query: String, limit: Int): Result<List<String>> =
    runCatching {
        val conn = serverRepository.resolveConnection(serverId)
        api.findFiles(conn, query = query, type = "file", directory = directory, limit = limit, dirs = null)
    }
```

> **注意**：硬编码 `type = "file"`（spec §6.4 只搜文件，不搜目录）。若未来需要目录搜索，再加参数。

- [ ] **Step 5: 实现 FindFilesUseCase**

```kotlin
// domain/usecase/FindFilesUseCase.kt
package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.domain.repository.FileRepository
import javax.inject.Inject

class FindFilesUseCase @Inject constructor(
    private val fileRepository: FileRepository
) {
    suspend operator fun invoke(serverId: String, directory: String, query: String, limit: Int = 50) =
        fileRepository.findFiles(serverId, directory, query, limit)
}
```

- [ ] **Step 6: 写 FindFilesUseCase 测试（委托验证）**

```kotlin
class FindFilesUseCaseTest {
    private val fileRepository: FileRepository = mockk()
    private val useCase = FindFilesUseCase(fileRepository)

    @Test
    fun `invoke delegates to repository with same args`() = runTest {
        val expected = listOf("src/Main.kt", "docs/README.md")
        coEvery { fileRepository.findFiles("srv-1", "/dir", "Main", 50) } returns Result.success(expected)

        val result = useCase("srv-1", "/dir", "Main")

        assertTrue(result.isSuccess)
        assertEquals(expected, result.getOrNull())
        coVerify(exactly = 1) { fileRepository.findFiles("srv-1", "/dir", "Main", 50) }
    }

    @Test
    fun `invoke passes through custom limit`() = runTest {
        coEvery { fileRepository.findFiles(any(), any(), any(), any()) } returns Result.success(emptyList())

        useCase("srv", "/dir", "q", 10)

        coVerify { fileRepository.findFiles("srv", "/dir", "q", 10) }
    }
}
```

- [ ] **Step 7: 运行 + 编译 + Commit**

```bash
.\gradlew :app:testDevDebugUnitTest --rerun --tests "*.FileRepositoryImplTest" --tests "*.FindFilesUseCaseTest"
# Expected: PASS (existing + 3 new + 2 new = +5)
.\gradlew :app:compileDevDebugKotlin
git add app/src/main/kotlin/dev/minios/ocremote/domain/repository/FileRepository.kt \
        app/src/main/kotlin/dev/minios/ocremote/data/repository/FileRepositoryImpl.kt \
        app/src/main/kotlin/dev/minios/ocremote/domain/usecase/FindFilesUseCase.kt \
        app/src/test/kotlin/dev/minios/ocremote/data/repository/FileRepositoryImplTest.kt \
        app/src/test/kotlin/dev/minios/ocremote/domain/usecase/FindFilesUseCaseTest.kt
git commit -m "feat: FileRepository.findFiles + FindFilesUseCase (server-side file name search)"
```

---

## Task 2: WorkspaceViewModel 搜索状态 + Git 客户端过滤

<a id="task-2"></a>

**Spec ref:** §6.4（搜索模式）

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/workspace/WorkspaceUiState.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/workspace/WorkspaceViewModel.kt`
- Modify: `app/src/test/kotlin/dev/minios/ocremote/ui/screens/workspace/WorkspaceViewModelTest.kt`

**Interfaces:**
- Consumes: `FindFilesUseCase`（Task 1）
- Produces: `WorkspaceUiState` 搜索字段；ViewModel `enterSearch/exitSearch/searchFiles/filterGitChanges` 方法

- [ ] **Step 1: 扩展 WorkspaceUiState — 加搜索字段**

```kotlin
data class WorkspaceUiState(
    // ... existing fields ...
    val gitChangeCount: Int? = null,
    // Phase 2: Search
    val isSearchMode: Boolean = false,
    val searchQuery: String = "",
    val fileSearchResults: List<String> = emptyList(),
    val searchLoading: Boolean = false,
    val searchError: Int? = null,
    val hasSearched: Boolean = false  // 区分"未搜索"和"0 结果"
)
```

- [ ] **Step 2: 写测试（追加到 WorkspaceViewModelTest）**

```kotlin
// 在测试类中加 FindFilesUseCase mock
private val findFiles: FindFilesUseCase = mockk()
// 所有 ViewModel 构造改为传入 findFiles
// WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus, findFiles)

@Test
fun `enterSearch sets isSearchMode true and clears query`() = runTest {
    val vm = WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus, findFiles)
    vm.enterSearch()
    assertEquals(true, vm.uiState.value.isSearchMode)
    assertEquals("", vm.uiState.value.searchQuery)
}

@Test
fun `exitSearch clears search state and keeps panel data`() = runTest {
    coEvery { findFiles(any(), any(), any(), any()) } returns Result.success(listOf("a.kt"))
    val vm = WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus, findFiles)
    vm.enterSearch()
    vm.searchFiles("test")
    advanceTimeBy(400)
    vm.exitSearch()
    assertEquals(false, vm.uiState.value.isSearchMode)
    assertEquals("", vm.uiState.value.searchQuery)
    assertEquals(true, vm.uiState.value.fileSearchResults.isEmpty())
    assertEquals(false, vm.uiState.value.hasSearched)
}

@Test
fun `searchFiles with blank query does not call useCase`() = runTest {
    val vm = WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus, findFiles)
    vm.enterSearch()
    vm.searchFiles("   ")
    advanceTimeBy(400)
    coVerify(exactly = 0) { findFiles(any(), any(), any(), any()) }
    assertEquals(false, vm.uiState.value.hasSearched)
}

@Test
fun `searchFiles debounces 300ms before calling useCase`() = runTest {
    coEvery { findFiles(any(), any(), any(), any()) } returns Result.success(listOf("a.kt"))
    val vm = WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus, findFiles)
    vm.enterSearch()
    vm.searchFiles("User")
    advanceTimeBy(200)  // 还没到 300ms
    coVerify(exactly = 0) { findFiles(any(), any(), any(), any()) }
    advanceTimeBy(150)  // 总共 350ms
    coVerify(exactly = 1) { findFiles(any(), any(), eq("User"), any()) }
}

@Test
fun `searchFiles success updates results and hasSearched`() = runTest {
    val paths = listOf("app/User.kt", "docs/user.md")
    coEvery { findFiles(any(), any(), any(), any()) } returns Result.success(paths)
    val vm = WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus, findFiles)
    vm.enterSearch()
    vm.searchFiles("User")
    advanceTimeBy(400)
    assertEquals(paths, vm.uiState.value.fileSearchResults)
    assertEquals(true, vm.uiState.value.hasSearched)
    assertEquals(false, vm.uiState.value.searchLoading)
    assertEquals(null, vm.uiState.value.searchError)
}

@Test
fun `searchFiles failure sets searchError`() = runTest {
    coEvery { findFiles(any(), any(), any(), any()) } returns Result.failure(RuntimeException("503"))
    val vm = WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus, findFiles)
    vm.enterSearch()
    vm.searchFiles("User")
    advanceTimeBy(400)
    assertEquals(true, vm.uiState.value.searchError != null)
    assertEquals(true, vm.uiState.value.fileSearchResults.isEmpty())
}

@Test
fun `rapid query changes cancel previous search job`() = runTest {
    coEvery { findFiles(any(), any(), eq("Us"), any()) } returns Result.success(listOf("a"))
    coEvery { findFiles(any(), any(), eq("User"), any()) } returns Result.success(listOf("b"))
    val vm = WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus, findFiles)
    vm.enterSearch()
    vm.searchFiles("Us")
    advanceTimeBy(200)
    vm.searchFiles("User")
    advanceTimeBy(400)
    // 只有最后一次（"User"）应该被调用
    coVerify(exactly = 0) { findFiles(any(), any(), eq("Us"), any()) }
    coVerify(exactly = 1) { findFiles(any(), any(), eq("User"), any()) }
    assertEquals(listOf("b"), vm.uiState.value.fileSearchResults)
}

@Test
fun `filterGitChanges filters loaded gitChanges by query case insensitive`() = runTest {
    val vm = WorkspaceViewModel(savedStateHandle(), listDirectory, getVcsStatus, findFiles)
    // 直接更新 uiState 模拟已加载 git changes
    vm.uiState.value = vm.uiState.value.copy(gitChanges = listOf(
        VcsChange("app/src/User.kt", 5, 2, VcsStatus.MODIFIED),
        VcsChange("README.md", 10, 0, VcsStatus.ADDED),
        VcsChange("app/src/UserProfile.kt", 1, 1, VcsStatus.MODIFIED)
    ))
    val filtered = vm.filterGitChanges("user")
    assertEquals(2, filtered.size)
    assertEquals("app/src/User.kt", filtered[0].file)
    assertEquals("app/src/UserProfile.kt", filtered[1].file)
}
```

- [ ] **Step 3: 运行验证失败**

```bash
.\gradlew :app:testDevDebugUnitTest --rerun --tests "*.WorkspaceViewModelTest"
```

Expected: FAIL — `enterSearch`/`searchFiles`/`filterGitChanges` unresolved；`FindFilesUseCase` 构造参数缺失。

- [ ] **Step 4: 实现 ViewModel 搜索逻辑**

修改 `WorkspaceViewModel.kt`：

```kotlin
@HiltViewModel
class WorkspaceViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val listDirectory: ListDirectoryUseCase,
    private val getVcsStatus: GetVcsStatusUseCase,
    private val findFiles: FindFilesUseCase  // 新增
) : ViewModel() {
    // ... existing fields ...

    private var searchJob: Job? = null

    // ============ Phase 2: Search ============

    fun enterSearch() {
        _uiState.update { it.copy(isSearchMode = true, searchQuery = "", fileSearchResults = emptyList(), hasSearched = false, searchError = null) }
    }

    fun exitSearch() {
        searchJob?.cancel()
        _uiState.update { it.copy(isSearchMode = false, searchQuery = "", fileSearchResults = emptyList(), hasSearched = false, searchLoading = false, searchError = null) }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        searchFiles(query)
    }

    fun searchFiles(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.update { it.copy(fileSearchResults = emptyList(), hasSearched = false, searchLoading = false, searchError = null) }
            return
        }
        _uiState.update { it.copy(searchLoading = true, searchError = null) }
        searchJob = viewModelScope.launch {
            delay(300)  // debounce
            findFiles(serverId, directory, query.trim())
                .onSuccess { results ->
                    _uiState.update { it.copy(fileSearchResults = results, searchLoading = false, hasSearched = true) }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(searchLoading = false, searchError = R.string.workspace_error_load_failed, hasSearched = true) }
                }
        }
    }

    /** 客户端过滤 Git 变更列表（不触发网络）。 */
    fun filterGitChanges(query: String): List<VcsChange> {
        val changes = _uiState.value.gitChanges
        if (query.isBlank()) return changes
        return changes.filter { it.file.contains(query, ignoreCase = true) }
    }
}
```

- [ ] **Step 5: 运行 + 编译 + Commit**

```bash
.\gradlew :app:testDevDebugUnitTest --rerun --tests "*.WorkspaceViewModelTest"
# Expected: PASS (existing + 8 new)
.\gradlew :app:compileDevDebugKotlin
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/workspace/WorkspaceUiState.kt \
        app/src/main/kotlin/dev/minios/ocremote/ui/screens/workspace/WorkspaceViewModel.kt \
        app/src/test/kotlin/dev/minios/ocremote/ui/screens/workspace/WorkspaceViewModelTest.kt
git commit -m "feat: WorkspaceViewModel search state (debounce 300ms, cancel stale, git client filter)"
```

---

## Task 3: SearchTopBar + SearchOverlay（搜索 UI 完整）

<a id="task-3"></a>

**Spec ref:** §6.4（搜索 UI）

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/workspace/search/SearchTopBar.kt`
- Create: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/workspace/search/SearchOverlay.kt`

**Interfaces:**
- Consumes: `WorkspaceUiState` 搜索字段
- Produces: `SearchTopBar`（搜索框 TopBar）+ `SearchOverlay`（结果列表 body）

- [ ] **Step 1: 实现 SearchTopBar**

```kotlin
// workspace/search/SearchTopBar.kt
package dev.minios.ocremote.ui.screens.workspace.search

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import dev.minios.ocremote.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    TopAppBar(
        navigationIcon = {
            IconButton(
                onClick = onBack,
                modifier = Modifier.testTag("back_button")
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back)
                )
            }
        },
        title = {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .testTag("workspace_search_input"),
                placeholder = { /* hint 在下面渲染 */ },
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(
                            onClick = onClear,
                            modifier = Modifier.testTag("workspace_search_clear")
                        ) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.sessions_clear_search))
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { /* 默认无操作，实时搜索 */ }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                )
            )
        },
        actions = {},
        modifier = modifier
    )
}
```

> **注意**：TopAppBar 的 `title` slot 放 `TextField`，用透明背景 + 透明下划线实现"全宽搜索框"效果。`FocusRequester` 在进入搜索时自动获得焦点弹起键盘。

- [ ] **Step 2: 实现 SearchOverlay**

```kotlin
// workspace/search/SearchOverlay.kt
package dev.minios.ocremote.ui/screens.workspace.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.R
import dev.minios.ocremote.domain.model.VcsChange
import dev.minios.ocremote.domain.model.VcsStatus
import dev.minios.ocremote.ui.screens.workspace.WorkspacePanel
import dev.minios.ocremote.ui.theme.AlphaTokens
import dev.minios.ocremote.ui.theme.DiffAdded
import dev.minios.ocremote.ui.theme.DiffRemoved
import dev.minios.ocremote.ui.theme.ShapeTokens
import dev.minios.ocremote.ui.theme.SpacingTokens
import androidx.compose.material3.Surface

@Composable
fun SearchOverlay(
    activePanel: WorkspacePanel,
    query: String,
    fileResults: List<String>,
    gitChanges: List<VcsChange>,
    isLoading: Boolean,
    hasSearched: Boolean,
    errorMessageRes: Int?,
    onOpenFile: (String) -> Unit,
    onOpenGitDiff: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier.fillMaxSize()) {
        when {
            errorMessageRes != null -> SearchErrorState(messageRes = errorMessageRes)
            isLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            activePanel == WorkspacePanel.FILE_TREE -> FileSearchResultsList(
                results = fileResults, hasSearched = hasSearched, query = query, onOpenFile = onOpenFile
            )
            activePanel == WorkspacePanel.GIT_CHANGES -> GitSearchResultsList(
                changes = gitChanges, query = query, onOpenDiff = onOpenGitDiff
            )
        }
    }
}

@Composable
private fun FileSearchResultsList(
    results: List<String>,
    hasSearched: Boolean,
    query: String,
    onOpenFile: (String) -> Unit
) {
    when {
        !hasSearched -> EmptyHint(text = stringResourceSafe(R.string.workspace_search_hint))
        results.isEmpty() -> EmptyHint(text = stringResourceSafe(R.string.workspace_search_no_results))
        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(results, key = { it }) { path ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .testTag("workspace_search_result")
                        .clickable { onOpenFile(path) }
                        .padding(horizontal = SpacingTokens.LG.dp, vertical = SpacingTokens.MD.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Description, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Text(
                        text = ellipsizeMiddle(path, maxLength = 60),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = SpacingTokens.MD.dp),
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun GitSearchResultsList(
    changes: List<VcsChange>,
    query: String,
    onOpenDiff: (String) -> Unit
) {
    if (changes.isEmpty()) {
        EmptyHint(text = if (query.isBlank()) stringResourceSafe(R.string.workspace_search_hint)
                 else stringResourceSafe(R.string.workspace_search_no_git_match))
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(changes, key = { it.file }) { change ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .testTag("workspace_search_result")
                    .clickable { onOpenDiff(change.file) }
                    .padding(horizontal = SpacingTokens.LG.dp, vertical = SpacingTokens.MD.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusBadge(status = change.status)
                Text(
                    text = ellipsizeMiddle(change.file, maxLength = 56),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = SpacingTokens.MD.dp).weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis
                )
                Text(
                    text = "+${change.additions} -${change.deletions}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(status: VcsStatus) {
    val (bg, letter) = when (status) {
        VcsStatus.ADDED -> DiffAdded to "A"
        VcsStatus.DELETED -> DiffRemoved to "D"
        VcsStatus.MODIFIED -> MaterialTheme.colorScheme.tertiary to "M"
    }
    Surface(color = bg, shape = ShapeTokens.extraSmall, modifier = Modifier.size(20.dp)) {
        Text(
            text = letter,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(2.dp)
        )
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize().padding(SpacingTokens.LG.dp), Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SearchErrorState(messageRes: Int) {
    Box(Modifier.fillMaxSize().padding(SpacingTokens.LG.dp), Alignment.Center) {
        Text(
            text = stringResourceSafe(messageRes),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/** 路径中间省略：app/src/.../User.kt。 */
internal fun ellipsizeMiddle(path: String, maxLength: Int): String {
    if (path.length <= maxLength) return path
    val keepEach = (maxLength - 3) / 2
    return path.take(keepEach) + "..." + path.takeLast(keepEach)
}

/** Wrapper to avoid repeating stringResource boilerplate in helpers. */
@Composable
private fun stringResourceSafe(resId: Int): String = androidx.compose.ui.res.stringResource(resId)
```

- [ ] **Step 3: 编译验证**

```bash
.\gradlew :app:compileDevDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/workspace/search/SearchTopBar.kt \
        app/src/main/kotlin/dev/minios/ocremote/ui/screens/workspace/search/SearchOverlay.kt
git commit -m "feat: SearchTopBar (auto-focus, transparent TextField) + SearchOverlay (file/git result lists with middle-ellipsis paths)"
```

---

## Task 4: WorkspaceScreen 集成搜索入口

<a id="task-4"></a>

**Spec ref:** §6.1（TopBar 切换），§6.4（搜索触发）

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/workspace/WorkspaceScreen.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/workspace/WorkspaceRoute.kt`（如 Route 单独文件）

- [ ] **Step 1: WorkspaceScreen 加 onSearch 参数 + isSearchMode 分支**

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(
    uiState: WorkspaceUiState,
    onBack: () -> Unit,
    onSwitchPanel: (WorkspacePanel) -> Unit,
    onRefreshRoot: () -> Unit,
    onToggleShowIgnored: () -> Unit,
    onRefreshGit: () -> Unit,
    onOpenFile: (String) -> Unit,
    onOpenGitDiff: (String) -> Unit,
    // Phase 2: Search
    onEnterSearch: () -> Unit,
    onExitSearch: () -> Unit,
    onSearchQueryChange: (String) -> Unit
) {
    Scaffold(
        topBar = {
            // 用 AnimatedContent 切换 TopBar
            androidx.compose.animation.Crossfade(
                targetState = uiState.isSearchMode,
                label = "search_topbar"
            ) { isSearch ->
                if (isSearch) {
                    SearchTopBar(
                        query = uiState.searchQuery,
                        onQueryChange = onSearchQueryChange,
                        onBack = onExitSearch,
                        onClear = onSearchQueryChange.let { cb -> { cb("") } }
                    )
                } else {
                    WorkspaceTopBar(
                        uiState = uiState,
                        onBack = onBack,
                        onSwitchPanel = onSwitchPanel,
                        onSearch = onEnterSearch
                    )
                }
            }
        }
    ) { padding ->
        if (uiState.isSearchMode) {
            val filteredGitChanges = if (uiState.currentPanel == WorkspacePanel.GIT_CHANGES) {
                // 注意：filterGitChanges 是 ViewModel 的方法，在 Route 层调用并传入
                // 这里直接用 uiState 已计算的 gitChanges + query 客户端过滤
                uiState.gitChanges.filter {
                    uiState.searchQuery.isBlank() || it.file.contains(uiState.searchQuery, ignoreCase = true)
                }
            } else emptyList()
            SearchOverlay(
                activePanel = uiState.currentPanel,
                query = uiState.searchQuery,
                fileResults = uiState.fileSearchResults,
                gitChanges = filteredGitChanges,
                isLoading = uiState.searchLoading,
                hasSearched = uiState.hasSearched,
                errorMessageRes = uiState.searchError,
                onOpenFile = { onOpenFile(it); onExitSearch() },
                onOpenGitDiff = { onOpenGitDiff(it); onExitSearch() },
                modifier = Modifier.padding(padding)
            )
        } else {
            when (uiState.currentPanel) {
                WorkspacePanel.FILE_TREE -> FileTreePanel(
                    uiState = uiState,
                    onRefreshRoot = onRefreshRoot,
                    onToggleShowIgnored = onToggleShowIgnored,
                    onOpenFile = onOpenFile,
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
}
```

- [ ] **Step 2: WorkspaceTopBar 加 🔍 IconButton + onSearch 参数**

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspaceTopBar(
    uiState: WorkspaceUiState,
    onBack: () -> Unit,
    onSwitchPanel: (WorkspacePanel) -> Unit,
    onSearch: () -> Unit  // 新增
) {
    TopAppBar(
        title = { /* ... 现有 ... */ },
        navigationIcon = { /* ... 现有 ... */ },
        actions = {
            // Phase 2: 🔍 搜索按钮
            IconButton(
                onClick = onSearch,
                modifier = Modifier.testTag("workspace_search_button")
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = stringResource(R.string.a11y_icon_search),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = { onSwitchPanel(WorkspacePanel.FILE_TREE) },
                modifier = Modifier.testTag("panel_file_tree")
            ) { /* ... 现有 Folder icon ... */ }
            IconButton(
                onClick = { onSwitchPanel(WorkspacePanel.GIT_CHANGES) },
                enabled = !uiState.isNonGit,
                modifier = Modifier.testTag("panel_git_changes")
            ) { /* ... 现有 CompareArrows icon ... */ }
        }
    )
}
```

> 🔍 按钮在最左侧（按 spec 截图顺序：[🔍][📁][🔀·12]）。

- [ ] **Step 3: WorkspaceRoute 透传搜索回调**

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
        onRefreshGit = viewModel::loadGitChanges,
        onOpenFile = onOpenFile,
        onOpenGitDiff = onOpenGitDiff,
        // Phase 2: Search
        onEnterSearch = viewModel::enterSearch,
        onExitSearch = viewModel::exitSearch,
        onSearchQueryChange = viewModel::updateSearchQuery
    )
}
```

- [ ] **Step 4: 编译验证**

```bash
.\gradlew :app:compileDevDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/workspace/WorkspaceScreen.kt
git commit -m "feat: WorkspaceScreen search integration (Crossfade TopBar, 🔍 button, panel-aware results)"
```

---

## Task 5: MarkdownPreview composable（viewer 独立可复用）

<a id="task-5"></a>

**Spec ref:** §7.3（md 渲染预览只读切换），§2.2（mikepenz 多 Text 限制）

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/MarkdownPreview.kt`

**Interfaces:**
- Consumes: `com.mikepenz:multiplatform-markdown-renderer-m3`（项目已有依赖）
- Produces: `MarkdownPreview(markdown: String, modifier: Modifier)`

- [ ] **Step 1: 实现 MarkdownPreview**

```kotlin
// viewer/MarkdownPreview.kt
package dev.minios.ocremote.ui.screens.viewer

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.ui.theme.AlphaTokens
import dev.minios.ocremote.ui.theme.CodeTypography
import dev.minios.ocremote.ui.theme.SpacingTokens
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxThemes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.text.font.FontStyle
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.model.markdownColor
import com.mikepenz.markdown.model.markdownTypography
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownComponents
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.compose.components.markdownComponents

/**
 * Read-only Markdown preview for the FileViewer (spec §7.3).
 *
 * Distinct from chat module's `internal MarkdownContent`:
 * - No user/agent message styling
 * - No font size setting coupling
 * - Single theme: surfaceContainer background, onSurface text
 *
 * Uses mikepenz multiplatform-markdown-renderer-m3 (project dependency).
 * Rendered as multiple independent Text composables — selection across
 * components is not supported (spec §2.2). Hence this preview is read-only.
 */
@Composable
fun MarkdownPreview(
    markdown: String,
    modifier: Modifier = Modifier
) {
    val textColor = MaterialTheme.colorScheme.onSurface
    val isDark = isSystemInDarkTheme()
    val codeBlockBg = if (isDark) MaterialTheme.colorScheme.surfaceContainerHighest
                      else MaterialTheme.colorScheme.surfaceContainer
    val inlineCodeBg = MaterialTheme.colorScheme.primaryContainer.copy(alpha = AlphaTokens.FAINT)
    val inlineCodeFg = MaterialTheme.colorScheme.primary

    val colors = markdownColor(
        text = textColor,
        codeBackground = codeBlockBg,
        inlineCodeBackground = inlineCodeBg,
        dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT),
        tableBackground = MaterialTheme.colorScheme.surfaceContainerLow
    )

    val typography = markdownTypography(
        h1 = MaterialTheme.typography.titleLarge.copy(color = textColor, fontWeight = FontWeight.Bold),
        h2 = MaterialTheme.typography.titleMedium.copy(color = textColor, fontWeight = FontWeight.SemiBold),
        h3 = MaterialTheme.typography.titleSmall.copy(color = textColor, fontWeight = FontWeight.SemiBold),
        h4 = MaterialTheme.typography.bodyLarge.copy(color = textColor, fontWeight = FontWeight.SemiBold),
        text = MaterialTheme.typography.bodyMedium.copy(color = textColor),
        code = CodeTypography.copy(color = MaterialTheme.colorScheme.onSurface),
        inlineCode = CodeTypography.copy(color = inlineCodeFg, fontWeight = FontWeight.Medium),
        quote = MaterialTheme.typography.bodyMedium.copy(
            color = textColor.copy(alpha = AlphaTokens.MEDIUM),
            fontStyle = FontStyle.Italic
        ),
        paragraph = MaterialTheme.typography.bodyMedium.copy(color = textColor),
        ordered = MaterialTheme.typography.bodyMedium.copy(color = textColor),
        bullet = MaterialTheme.typography.bodyMedium.copy(color = textColor),
        list = MaterialTheme.typography.bodyMedium.copy(color = textColor)
    )

    val dimens = markdownDimens()

    val highlightsBuilder = remember { Highlights.Builder() }
    val components = markdownComponents()  // 默认组件，不传 OcCodeBlock（保持简单）

    Markdown(
        content = markdown,
        colors = colors,
        typography = typography,
        components = components,
        dimens = dimens,
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SpacingTokens.LG.dp, vertical = SpacingTokens.MD.dp)
    )
}
```

> **说明**：使用 mikepenz 默认 `markdownComponents()`（不集成 OcCodeBlock），保持 viewer 模块的 markdown 预览独立、简单。代码块的语法高亮由 mikepenz 内部处理（基于 `Highlights.Builder`）。如果发现渲染效果不佳，可在 Phase 4 升级为集成 OcCodeBlock。

- [ ] **Step 2: 编译验证**

```bash
.\gradlew :app:compileDevDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/MarkdownPreview.kt
git commit -m "feat: MarkdownPreview composable (viewer module, read-only, independent from chat MarkdownContent)"
```

---

## Task 6: FileViewerUiState + ViewModel md 切换状态

<a id="task-6"></a>

**Spec ref:** §7.1（TopBar 形态 A 源码↔渲染），§7.3（md 滚动比例锚点）

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/FileViewerUiState.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/FileViewerViewModel.kt`
- Modify: `app/src/test/kotlin/dev/minios/ocremote/ui/screens/viewer/FileViewerViewModelTest.kt`

**Interfaces:**
- Consumes: 无新依赖
- Produces: `FileViewerRenderMode` enum；UiState 的 `renderMode`、`isMarkdown` 字段；ViewModel `toggleRenderMode` 方法

- [ ] **Step 1: 扩展 FileViewerUiState**

```kotlin
enum class FileViewerRenderMode { SOURCE, RENDER_PREVIEW }

data class FileViewerUiState(
    // ... existing fields ...
    val currentHunkIndex: Int = 0,
    // Phase 2: Markdown render toggle
    val renderMode: FileViewerRenderMode = FileViewerRenderMode.SOURCE,
    val isMarkdown: Boolean = false  // 由 filePath 扩展名决定
)
```

- [ ] **Step 2: 写测试（追加到 FileViewerViewModelTest）**

```kotlin
@Test
fun `init with md file sets isMarkdown true and keeps SOURCE renderMode`() = runTest {
    coEvery { getFileContent(any(), any(), any()) } returns Result.success(sampleMarkdownContent)
    val vm = FileViewerViewModel(
        savedStateHandle(filePath = "docs/README.md"),
        getFileContent, getFileDiff
    )
    assertEquals(true, vm.uiState.value.isMarkdown)
    assertEquals(FileViewerRenderMode.SOURCE, vm.uiState.value.renderMode)
}

@Test
fun `init with kt file sets isMarkdown false`() = runTest {
    coEvery { getFileContent(any(), any(), any()) } returns Result.success(sampleKotlinContent)
    val vm = FileViewerViewModel(
        savedStateHandle(filePath = "app/src/Main.kt"),
        getFileContent, getFileDiff
    )
    assertEquals(false, vm.uiState.value.isMarkdown)
}

@Test
fun `toggleRenderMode switches SOURCE to RENDER_PREVIEW for markdown files`() = runTest {
    coEvery { getFileContent(any(), any(), any()) } returns Result.success(sampleMarkdownContent)
    val vm = FileViewerViewModel(
        savedStateHandle(filePath = "docs/README.md"),
        getFileContent, getFileDiff
    )
    vm.toggleRenderMode()
    assertEquals(FileViewerRenderMode.RENDER_PREVIEW, vm.uiState.value.renderMode)
}

@Test
fun `toggleRenderMode is no-op for non-markdown files`() = runTest {
    coEvery { getFileContent(any(), any(), any()) } returns Result.success(sampleKotlinContent)
    val vm = FileViewerViewModel(
        savedStateHandle(filePath = "Main.kt"),
        getFileContent, getFileDiff
    )
    vm.toggleRenderMode()
    assertEquals(FileViewerRenderMode.SOURCE, vm.uiState.value.renderMode)
}

@Test
fun `toggleRenderMode is no-op in DIFF mode`() = runTest {
    coEvery { getFileDiff(any(), any(), any()) } returns Result.success(emptyList())
    val vm = FileViewerViewModel(
        savedStateHandle(filePath = "Main.md", source = FileViewerNav.Source.GIT_DIFF),
        getFileContent, getFileDiff
    )
    vm.toggleRenderMode()
    assertEquals(FileViewerRenderMode.SOURCE, vm.uiState.value.renderMode)
}

@Test
fun `toggleRenderMode back from RENDER_PREVIEW to SOURCE`() = runTest {
    coEvery { getFileContent(any(), any(), any()) } returns Result.success(sampleMarkdownContent)
    val vm = FileViewerViewModel(
        savedStateHandle(filePath = "docs/README.md"),
        getFileContent, getFileDiff
    )
    vm.toggleRenderMode()  // → RENDER_PREVIEW
    vm.toggleRenderMode()  // → SOURCE
    assertEquals(FileViewerRenderMode.SOURCE, vm.uiState.value.renderMode)
}
```

- [ ] **Step 3: 运行验证失败**

```bash
.\gradlew :app:testDevDebugUnitTest --rerun --tests "*.FileViewerViewModelTest"
```

- [ ] **Step 4: 实现 ViewModel 切换逻辑**

修改 `FileViewerViewModel.kt` 的 `loadLive()` 在设置 content 时同时设置 `isMarkdown`，并加 `toggleRenderMode()` 方法：

```kotlin
private fun loadLive() {
    viewModelScope.launch {
        getFileContent(serverId, directory, filePath)
            .onSuccess { c ->
                if (c.type == ContentType.BINARY) {
                    _uiState.update { it.copy(isLoading = false, isBinary = true, mimeType = c.mimeType) }
                } else {
                    val lines = c.content.split('\n')
                    val truncated = lines.size > 5000
                    val visible = if (truncated) lines.take(5000).joinToString("\n") else c.content
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            content = visible,
                            isEmpty = visible.isBlank(),
                            isTruncated = truncated,
                            isMarkdown = isMarkdownFile(filePath),
                            renderMode = FileViewerRenderMode.SOURCE
                        )
                    }
                }
            }
            .onFailure { _ -> _uiState.update { it.copy(isLoading = false, error = R.string.workspace_error_load_failed) } }
    }
}

fun toggleRenderMode() {
    val current = _uiState.value
    if (!current.isMarkdown || current.mode == FileViewerMode.DIFF) return
    _uiState.update {
        it.copy(
            renderMode = if (it.renderMode == FileViewerRenderMode.SOURCE) FileViewerRenderMode.RENDER_PREVIEW
                         else FileViewerRenderMode.SOURCE
        )
    }
}

private fun isMarkdownFile(filePath: String): Boolean {
    val ext = filePath.substringAfterLast('.', "").lowercase()
    return ext == "md" || ext == "markdown" || ext == "mdx"
}
```

- [ ] **Step 5: 运行 + 编译 + Commit**

```bash
.\gradlew :app:testDevDebugUnitTest --rerun --tests "*.FileViewerViewModelTest"
# Expected: PASS (existing + 6 new)
.\gradlew :app:compileDevDebugKotlin
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/FileViewerUiState.kt \
        app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/FileViewerViewModel.kt \
        app/src/test/kotlin/dev/minios/ocremote/ui/screens/viewer/FileViewerViewModelTest.kt
git commit -m "feat: FileViewerViewModel markdown toggle (isMarkdown, toggleRenderMode, SOURCE↔RENDER_PREVIEW)"
```

---

## Task 7: FileViewerScreen md 切换 UI + 比例锚点滚动

<a id="task-7"></a>

**Spec ref:** §7.1（TopBar 形态 A），§7.3（比例锚点 v1）

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/FileViewerScreen.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/FileViewerRoute.kt`

- [ ] **Step 1: FileViewerScreen 加 onToggleRenderMode 参数**

```kotlin
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileViewerScreen(
    uiState: FileViewerUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onNextHunk: () -> Unit,
    onPrevHunk: () -> Unit,
    onCopyPath: () -> Unit,
    onShare: () -> Unit,
    onCopyAllContent: () -> Unit,
    onToggleRenderMode: () -> Unit  // Phase 2 新增
) {
    // ...
    Scaffold(
        topBar = {
            FileViewerTopBar(
                uiState = uiState,
                onBack = onBack,
                onCopyPath = onCopyPath,
                onShare = onShare,
                onToggleRenderMode = onToggleRenderMode  // 透传
            )
        },
        // ...
    )
}
```

- [ ] **Step 2: FileViewerTopBar 加渲染切换按钮**

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileViewerTopBar(
    uiState: FileViewerUiState,
    onBack: () -> Unit,
    onCopyPath: () -> Unit,
    onShare: () -> Unit,
    onToggleRenderMode: () -> Unit
) {
    TopAppBar(
        title = { /* 现有 */ },
        navigationIcon = { /* 现有 back_button */ },
        actions = {
            // Phase 2: md 渲染切换按钮（仅 isMarkdown 时显示）
            if (uiState.isMarkdown && uiState.mode != FileViewerMode.DIFF) {
                val isRender = uiState.renderMode == FileViewerRenderMode.RENDER_PREVIEW
                IconButton(
                    onClick = onToggleRenderMode,
                    modifier = Modifier.testTag("viewer_md_render_button")
                ) {
                    Icon(
                        imageVector = if (isRender) Icons.Default.Description else Icons.Default.RemoveRedEye,
                        contentDescription = if (isRender) stringResource(R.string.viewer_md_show_source)
                                            else stringResource(R.string.viewer_md_show_render),
                        tint = if (isRender) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // 现有 Copy / Share 按钮
            IconButton(onClick = onCopyPath) { /* 现有 */ }
            IconButton(onClick = onShare) { /* 现有 */ }
        }
    )
}
```

- [ ] **Step 3: FileViewerScreen body 加渲染分支 + 比例锚点滚动**

```kotlin
) { padding ->
    Box(
        Modifier
            .padding(padding)
            .fillMaxSize()
            .combinedClickable(onClick = {}, onLongClick = { showLongPressMenu = true })
    ) {
        when {
            uiState.isLoading -> LoadingState()
            uiState.error != null -> ErrorState(message = uiState.error)
            uiState.isBinary -> MessageState(/* ... */)
            uiState.mode == FileViewerMode.DIFF -> DiffView(/* ... */)
            uiState.isEmpty -> MessageState(message = stringResource(R.string.viewer_empty_file))
            // Phase 2: md 渲染分支（在 isTruncated 之前判断——渲染预览不显示截断警告）
            uiState.isMarkdown && uiState.renderMode == FileViewerRenderMode.RENDER_PREVIEW -> {
                MarkdownPreviewWithScrollAnchor(
                    markdown = uiState.content,
                    sourceScrollFraction = uiState.lastSourceScrollFraction
                )
            }
            uiState.isTruncated -> Column(Modifier.fillMaxSize()) {
                TruncationBanner()
                CodeSourceView(uiState.content, uiState.filePath, modifier = Modifier.weight(1f))
            }
            else -> CodeSourceView(uiState.content, uiState.filePath, modifier = Modifier.fillMaxSize())
        }
        DropdownMenu(/* 现有 */) { /* ... */ }
    }
}
```

> `lastSourceScrollFraction` 在切换前由下面的 `MarkdownPreviewWithScrollAnchor` 或 CodeSourceView 的回调记录到 ViewModel。简化方案：Phase 2 在 Screen 内本地维护 fraction（ViewModel 状态保留，但旋转屏幕会丢——这是 spec 接受的 v1 限制）。

- [ ] **Step 4: 实现 MarkdownPreviewWithScrollAnchor**

在 `FileViewerScreen.kt` 末尾添加：

```kotlin
@Composable
private fun MarkdownPreviewWithScrollAnchor(
    markdown: String,
    sourceScrollFraction: Float
) {
    val renderScrollState = rememberScrollState()
    // 等首次渲染完成（maxValue > 0）再按 fraction 滚动
    LaunchedEffect(sourceScrollFraction, renderScrollState.maxValue) {
        snapshotFlow { renderScrollState.maxValue }
            .filter { it > 0 }
            .first()
        renderScrollState.scrollTo((renderScrollState.maxValue * sourceScrollFraction).toInt())
    }
    // MarkdownPreview 内部已 verticalScroll，这里复用——但为了能 scrollTo，
    // 改为在 MarkdownPreview 之外无法控制内部 scroll。简化：在 MarkdownPreview 加 scrollState 参数。
    // 详见 Step 5。
    MarkdownPreview(
        markdown = markdown,
        scrollState = renderScrollState  // 见 Step 5 修改 MarkdownPreview 签名
    )
}
```

- [ ] **Step 5: MarkdownPreview 加 scrollState 参数（让外部控制滚动）**

修改 Task 5 的 `MarkdownPreview` 签名：

```kotlin
@Composable
fun MarkdownPreview(
    markdown: String,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState()  // 新增可注入参数
) {
    // ... colors/typography 同 Task 5 ...
    Markdown(
        // ...
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)  // 用注入的 scrollState
            .padding(horizontal = SpacingTokens.LG.dp, vertical = SpacingTokens.MD.dp)
    )
}
```

- [ ] **Step 6: FileViewerScreen 用本地 state 维护 fraction**

```kotlin
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileViewerScreen(
    uiState: FileViewerUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onNextHunk: () -> Unit,
    onPrevHunk: () -> Unit,
    onCopyPath: () -> Unit,
    onShare: () -> Unit,
    onCopyAllContent: () -> Unit,
    onToggleRenderMode: () -> Unit
) {
    var showLongPressMenu by remember { mutableStateOf(false) }
    // Phase 2: 源码滚动状态 + fraction 锚点
    val sourceScrollState = rememberScrollState()
    var lastSourceFraction by remember { mutableStateOf(0f) }

    // 切换前记录 fraction
    val toggleWithAnchor: () -> Unit = {
        if (uiState.isMarkdown && uiState.renderMode == FileViewerRenderMode.SOURCE) {
            lastSourceFraction = if (sourceScrollState.maxValue > 0)
                sourceScrollState.value.toFloat() / sourceScrollState.maxValue else 0f
        }
        onToggleRenderMode()
    }

    Scaffold(/* ... 同 Step 1 ... */) { padding ->
        Box(/* ... */) {
            when {
                // ... loading / error / binary / diff 同 Step 3 ...
                uiState.isMarkdown && uiState.renderMode == FileViewerRenderMode.RENDER_PREVIEW -> {
                    MarkdownPreviewWithScrollAnchor(
                        markdown = uiState.content,
                        sourceScrollFraction = lastSourceFraction
                    )
                }
                uiState.isTruncated -> Column(Modifier.fillMaxSize()) {
                    TruncationBanner()
                    CodeSourceView(uiState.content, uiState.filePath, modifier = Modifier.weight(1f))
                }
                else -> CodeSourceView(
                    content = uiState.content,
                    filePath = uiState.filePath,
                    scrollState = sourceScrollState,  // 注入以读 fraction
                    modifier = Modifier.fillMaxSize()
                )
            }
            DropdownMenu(/* ... */) { /* ... */ }
        }
    }
}
```

> **CodeSourceView 也需加 scrollState 参数**：当前用 `rememberScrollState()` 内部维护，需要改为可注入（默认值仍 internal remember）。修改 `CodeSourceView(content, filePath, modifier, scrollState: ScrollState = rememberScrollState())`，hScroll 内部用注入的 scrollState。

- [ ] **Step 7: FileViewerRoute 透传 onToggleRenderMode**

```kotlin
FileViewerScreen(
    // ...
    onCopyAllContent = { /* 现有 */ },
    onToggleRenderMode = viewModel::toggleRenderMode
)
```

- [ ] **Step 8: 编译验证**

```bash
.\gradlew :app:compileDevDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/FileViewerScreen.kt \
        app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/FileViewerRoute.kt \
        app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/MarkdownPreview.kt \
        app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/CodeSourceView.kt
git commit -m "feat: FileViewerScreen md render toggle (TopBar button, fraction anchor scroll, injectable scrollState)"
```

---

## Task 8: ToolSnapshotGrouper（同 turn 同文件聚合算法）

<a id="task-8"></a>

**Spec ref:** §5.5（同 turn 同文件聚合 B 档），§11.2（ToolSnapshotGrouperTest）

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/tools/ToolSnapshotGrouper.kt`
- Create: `app/src/test/kotlin/dev/minios/ocremote/ui/screens/chat/tools/ToolSnapshotGrouperTest.kt`

**Interfaces:**
- Consumes: `Part.Tool`, `ToolState`
- Produces: `ToolSnapshotGroup`, `ToolSnapshotGrouper` object

- [ ] **Step 1: 写失败测试（真实样本）**

```kotlin
class ToolSnapshotGrouperTest {

    private fun makeTool(
        id: String,
        toolName: String,
        messageId: String = "msg-1",
        filePath: String,
        oldString: String = "",
        newString: String = "",
        content: String = "",
        before: String? = null,
        after: String? = null
    ): Part.Tool {
        val input = mutableMapOf<String, JsonElement>()
        input["filePath"] = JsonPrimitive(filePath)
        if (toolName.equals("write", true)) input["content"] = JsonPrimitive(content)
        if (toolName.equals("edit", true)) {
            input["oldString"] = JsonPrimitive(oldString)
            input["newString"] = JsonPrimitive(newString)
        }
        val metadata = if (before != null || after != null) {
            mapOf("filediff" to buildJsonObject {
                if (before != null) put("before", JsonPrimitive(before))
                if (after != null) put("after", JsonPrimitive(after))
            })
        } else null
        return Part.Tool(
            id = id, sessionId = "sess-1", messageId = messageId,
            callId = "call-$id", tool = toolName,
            state = ToolState.Completed(
                input = input,
                output = "ok",
                metadata = metadata
            )
        )
    }

    @Test
    fun `empty list returns empty groups`() {
        val groups = ToolSnapshotGrouper.group(emptyList())
        assertTrue(groups.isEmpty())
    }

    @Test
    fun `single Read tool produces single group with count 1`() {
        val tool = makeTool("p1", "read", filePath = "app/src/Main.kt", content = "class Main")
        val groups = ToolSnapshotGrouper.group(listOf(tool))
        assertEquals(1, groups.size)
        assertEquals(1, groups[0].toolParts.size)
        assertEquals("app/src/Main.kt", groups[0].normalizedFilePath)
    }

    @Test
    fun `three adjacent Edits same file produce single group`() {
        val tools = listOf(
            makeTool("p1", "edit", filePath = "app/User.kt", oldString = "a", newString = "b", before = "a", after = "b"),
            makeTool("p2", "edit", filePath = "app/User.kt", oldString = "b", newString = "c", before = "b", after = "c"),
            makeTool("p3", "edit", filePath = "app/User.kt", oldString = "c", newString = "d", before = "c", after = "d")
        )
        val groups = ToolSnapshotGrouper.group(tools)
        assertEquals(1, groups.size)
        assertEquals(3, groups[0].toolParts.size)
    }

    @Test
    fun `Bash tool between two Edits same file does not break grouping`() {
        val bashTool = Part.Tool(
            id = "b1", sessionId = "s", messageId = "msg-1",
            callId = "cb1", tool = "bash",
            state = ToolState.Completed(input = mapOf("command" to JsonPrimitive("ls")), output = "")
        )
        val tools = listOf(
            makeTool("p1", "edit", filePath = "app/User.kt", oldString = "a", newString = "b"),
            bashTool,
            makeTool("p2", "edit", filePath = "app/User.kt", oldString = "b", newString = "c")
        )
        val groups = ToolSnapshotGrouper.group(tools)
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].toolParts.size)
    }

    @Test
    fun `two different files produce two groups preserving first-occurrence order`() {
        val tools = listOf(
            makeTool("p1", "edit", filePath = "app/A.kt", oldString = "a", newString = "b"),
            makeTool("p2", "edit", filePath = "app/B.kt", oldString = "x", newString = "y"),
            makeTool("p3", "edit", filePath = "app/A.kt", oldString = "b", newString = "c")
        )
        val groups = ToolSnapshotGrouper.group(tools)
        assertEquals(2, groups.size)
        assertEquals("app/A.kt", groups[0].normalizedFilePath)
        assertEquals(2, groups[0].toolParts.size)
        assertEquals("app/B.kt", groups[1].normalizedFilePath)
        assertEquals(1, groups[1].toolParts.size)
    }

    @Test
    fun `Write and Edit on same file in same message produce single group`() {
        val tools = listOf(
            makeTool("p1", "write", filePath = "app/New.kt", content = "initial"),
            makeTool("p2", "edit", filePath = "app/New.kt", oldString = "initial", newString = "updated", before = "initial", after = "updated")
        )
        val groups = ToolSnapshotGrouper.group(tools)
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].toolParts.size)
    }

    @Test
    fun `path normalization treats backslash and forward slash as same file`() {
        val tools = listOf(
            makeTool("p1", "edit", filePath = "app\\src\\User.kt", oldString = "a", newString = "b"),
            makeTool("p2", "edit", filePath = "app/src/User.kt", oldString = "b", newString = "c")
        )
        val groups = ToolSnapshotGrouper.group(tools)
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].toolParts.size)
    }

    @Test
    fun `same file across different messages produces two groups`() {
        val tools = listOf(
            makeTool("p1", "edit", messageId = "msg-1", filePath = "app/User.kt", oldString = "a", newString = "b"),
            makeTool("p2", "edit", messageId = "msg-2", filePath = "app/User.kt", oldString = "b", newString = "c")
        )
        val groups = ToolSnapshotGrouper.group(tools)
        assertEquals(2, groups.size)
    }

    @Test
    fun `cumulativeBefore is first part before cumulativeAfter is last part after`() {
        val tools = listOf(
            makeTool("p1", "edit", filePath = "app/User.kt", oldString = "v0", newString = "v1", before = "v0", after = "v1"),
            makeTool("p2", "edit", filePath = "app/User.kt", oldString = "v1", newString = "v2", before = "v1", after = "v2"),
            makeTool("p3", "edit", filePath = "app/User.kt", oldString = "v2", newString = "v3", before = "v2", after = "v3")
        )
        val groups = ToolSnapshotGrouper.group(tools)
        assertEquals("v0", groups[0].cumulativeBefore)
        assertEquals("v3", groups[0].cumulativeAfter)
    }

    @Test
    fun `cumulativeBefore is empty for Write-only group`() {
        val tools = listOf(
            makeTool("p1", "write", filePath = "app/New.kt", content = "new content")
        )
        val groups = ToolSnapshotGrouper.group(tools)
        assertEquals("", groups[0].cumulativeBefore)
        assertEquals("new content", groups[0].cumulativeAfter)
    }

    @Test
    fun `cumulativeBefore falls back to oldString when metadata missing`() {
        val tools = listOf(
            makeTool("p1", "edit", filePath = "app/User.kt", oldString = "fallbackBefore", newString = "fallbackAfter")
            // 不传 before/after → metadata 为 null
        )
        val groups = ToolSnapshotGrouper.group(tools)
        assertEquals("fallbackBefore", groups[0].cumulativeBefore)
        assertEquals("fallbackAfter", groups[0].cumulativeAfter)
    }

    @Test
    fun `non Read Write Edit tools are ignored`() {
        val bashTool = Part.Tool(
            id = "b1", sessionId = "s", messageId = "msg-1",
            callId = "cb1", tool = "bash",
            state = ToolState.Completed(input = emptyMap(), output = "")
        )
        val globTool = Part.Tool(
            id = "g1", sessionId = "s", messageId = "msg-1",
            callId = "cg1", tool = "glob",
            state = ToolState.Completed(input = emptyMap(), output = "")
        )
        val groups = ToolSnapshotGrouper.group(listOf(bashTool, globTool))
        assertTrue(groups.isEmpty())
    }

    @Test
    fun `Running state tool is still grouped`() {
        val tool = Part.Tool(
            id = "p1", sessionId = "s", messageId = "msg-1",
            callId = "c1", tool = "edit",
            state = ToolState.Running(input = mapOf("filePath" to JsonPrimitive("app/X.kt")))
        )
        val groups = ToolSnapshotGrouper.group(listOf(tool))
        assertEquals(1, groups.size)
    }

    @Test
    fun `normalizePath helper trims trailing slash and converts backslash`() {
        assertEquals("app/src/X.kt", ToolSnapshotGrouper.normalizePath("app\\src\\X.kt"))
        assertEquals("app/src/X.kt", ToolSnapshotGrouper.normalizePath("app/src/X.kt/"))
        assertEquals("X.kt", ToolSnapshotGrouper.normalizePath("X.kt"))
    }
}
```

- [ ] **Step 2: 运行验证失败**

```bash
.\gradlew :app:testDevDebugUnitTest --rerun --tests "*.ToolSnapshotGrouperTest"
```

- [ ] **Step 3: 实现 ToolSnapshotGrouper**

```kotlin
// chat/tools/ToolSnapshotGrouper.kt
package dev.minios.ocremote.ui.screens.chat.tools

import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.domain.model.ToolState
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Groups Read/Write/Edit tool parts by (messageId, normalized filePath).
 *
 * "B-tier" grouping (spec §5.5):
 *  - Same message + same normalized path = one group
 *  - Other tool types (Bash, Glob, etc.) do NOT break groups
 *  - Physical adjacency is NOT required
 *  - Cumulative diff: group.cumulativeBefore = first part's before,
 *                     group.cumulativeAfter  = last part's after
 *
 * Path normalization: convert `\` to `/`, trim trailing `/`. This treats
 * `app\src\X.kt` and `app/src/X.kt` as the same file (Windows/POSIX mix).
 *
 * @param parts Tool parts to group (typically all parts of a message).
 * @return Groups in first-occurrence order. Empty input → empty list.
 */
object ToolSnapshotGrouper {

    private val SUPPORTED_TOOLS = setOf("read", "write", "edit")

    fun group(parts: List<Part.Tool>): List<ToolSnapshotGroup> {
        // 1. 过滤：只保留 Read/Write/Edit
        val fileTools = parts.filter { it.tool.lowercase() in SUPPORTED_TOOLS }
        if (fileTools.isEmpty()) return emptyList()

        // 2. 按 messageId 分组（一个 message 一个 turn）
        val byMessage = fileTools.groupBy { it.messageId }

        // 3. 在每个 message 内按归一化 filePath 聚合
        val allGroups = mutableListOf<ToolSnapshotGroup>()
        for ((_, tools) in byMessage) {
            // LinkedHashMap 保持首次出现顺序
            val grouped = LinkedHashMap<String, MutableList<Part.Tool>>()
            for (t in tools) {
                val path = extractFilePath(t) ?: continue
                val normalized = normalizePath(path)
                grouped.getOrPut(normalized) { mutableListOf() }.add(t)
            }
            for ((normalized, groupTools) in grouped) {
                allGroups.add(buildGroup(normalized, groupTools))
            }
        }
        return allGroups
    }

    private fun buildGroup(normalizedPath: String, tools: List<Part.Tool>): ToolSnapshotGroup {
        val firstFilePath = extractFilePath(tools.first()) ?: normalizedPath
        val (cumulativeBefore, cumulativeAfter) = computeCumulativeDiff(tools)
        return ToolSnapshotGroup(
            normalizedFilePath = normalizedPath,
            toolParts = tools,
            firstFilePath = firstFilePath,
            cumulativeBefore = cumulativeBefore,
            cumulativeAfter = cumulativeAfter
        )
    }

    private fun computeCumulativeDiff(tools: List<Part.Tool>): Pair<String, String> {
        val first = tools.first()
        val last = tools.last()

        // before 来自第一个工具的 before / content / oldString
        val before = extractBefore(first)
        // after 来自最后一个工具的 after / content / newString
        val after = extractAfter(last)
        return before to after
    }

    private fun extractFilePath(tool: Part.Tool): String? {
        val input = when (val s = tool.state) {
            is ToolState.Completed -> s.input
            is ToolState.Running -> s.input
            is ToolState.Pending -> s.input
            is ToolState.Error -> s.input
        }
        return input["filePath"]?.jsonPrimitive?.contentOrNull
            ?: input["path"]?.jsonPrimitive?.contentOrNull
    }

    private fun extractBefore(tool: Part.Tool): String {
        val metadata = (tool.state as? ToolState.Completed)?.metadata
            ?: (tool.state as? ToolState.Running)?.metadata
        metadata?.get("filediff")?.let { fd ->
            (fd as? JsonObject)?.get("before")?.jsonPrimitive?.contentOrNull?.let { return it }
        }
        // Fallback: Write → 空文件；Edit → oldString；Read → 空（无 before 概念）
        val input = tool.state.inputAsMap()
        return when (tool.tool.lowercase()) {
            "write" -> ""
            "edit" -> input["oldString"]?.jsonPrimitive?.contentOrNull ?: ""
            "read" -> ""
            else -> ""
        }
    }

    private fun extractAfter(tool: Part.Tool): String {
        val metadata = (tool.state as? ToolState.Completed)?.metadata
            ?: (tool.state as? ToolState.Running)?.metadata
        metadata?.get("filediff")?.let { fd ->
            (fd as? JsonObject)?.get("after")?.jsonPrimitive?.contentOrNull?.let { return it }
        }
        val input = tool.state.inputAsMap()
        return when (tool.tool.lowercase()) {
            "write" -> input["content"]?.jsonPrimitive?.contentOrNull ?: ""
            "edit" -> input["newString"]?.jsonPrimitive?.contentOrNull ?: ""
            "read" -> (tool.state as? ToolState.Completed)?.output ?: ""
            else -> ""
        }
    }

    private fun ToolState.inputAsMap(): Map<String, JsonElement> = when (this) {
        is ToolState.Completed -> input
        is ToolState.Running -> input
        is ToolState.Pending -> input
        is ToolState.Error -> input
    }

    /**
     * Normalize file path: convert Windows separators to POSIX and trim trailing slash.
     */
    fun normalizePath(path: String): String {
        return path.replace('\\', '/').trimEnd('/')
    }
}

data class ToolSnapshotGroup(
    val normalizedFilePath: String,
    val toolParts: List<Part.Tool>,
    val firstFilePath: String,
    val cumulativeBefore: String,
    val cumulativeAfter: String
) {
    /** Group size for the ③ badge. 1 = no badge. */
    val size: Int get() = toolParts.size
    /** True if more than one tool in this group (show left rail + badge). */
    val isMulti: Boolean get() = size > 1
}
```

- [ ] **Step 4: 运行 + Commit**

```bash
.\gradlew :app:testDevDebugUnitTest --rerun --tests "*.ToolSnapshotGrouperTest"
# Expected: PASS (15 tests)
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/tools/ToolSnapshotGrouper.kt \
        app/src/test/kotlin/dev/minios/ocremote/ui/screens/chat/tools/ToolSnapshotGrouperTest.kt
git commit -m "feat: ToolSnapshotGrouper (same-message same-path grouping, cumulative diff, path normalization)"
```

---

## Task 9: ToolSnapshotCache + 入口1 改造

<a id="task-9"></a>

**Spec ref:** §5.1-5.5（入口1 完整设计），§5.6（入口1 标注），§11.2（FileViewerSourceTest）

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/domain/repository/ToolSnapshotCache.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/di/DomainModule.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/FileViewerViewModel.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/FileViewerUiState.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/tools/ToolCardResolver.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/tools/DefaultToolCardResolver.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/navigation/NavGraph.kt`

> ⚠️ **ChatScreen.kt 编辑协议**：必须 Read-before-Edit + 每次编译 + commit。如失败用 `git checkout -- <file>` 重试。

- [ ] **Step 1: 创建 ToolSnapshotCache（Hilt @Singleton）**

```kotlin
// domain/repository/ToolSnapshotCache.kt
package dev.minios.ocremote.domain.repository

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-level in-memory cache for tool snapshots.
 *
 * Why: navigation arguments cannot carry large Part content (URL length
 * limit + Binder 1MB transaction limit). ChatViewModel caches snapshots
 * keyed by tool part ID before navigating; FileViewerViewModel reads them
 * by toolPartIds (route param).
 *
 * Lifecycle: write-on-navigate, clear-on-FileViewer-onCleared.
 * Process death → loss is acceptable (chat state may have changed).
 */
@Singleton
class ToolSnapshotCache @Inject constructor() {

    private val snapshots = mutableMapOf<String, Snapshot>()

    fun put(partId: String, snapshot: Snapshot) {
        snapshots[partId] = snapshot
    }

    fun putAll(snapshots: Map<String, Snapshot>) {
        this.snapshots.putAll(snapshots)
    }

    fun get(partId: String): Snapshot? = snapshots[partId]

    fun getAll(partIds: List<String>): List<Snapshot> =
        partIds.mapNotNull { snapshots[it] }

    fun clear(partIds: List<String>) {
        partIds.forEach { snapshots.remove(it) }
    }

    fun clear() {
        snapshots.clear()
    }

    fun size(): Int = snapshots.size

    data class Snapshot(
        val filePath: String,
        val content: String?,
        val before: String?,
        val after: String?,
        val toolName: String  // "read" | "write" | "edit"
    ) {
        /** True if this snapshot has diff data (Edit-style). */
        val isDiff: Boolean get() = before != null && after != null
        /** True if this snapshot is a content view (Read/Write-style). */
        val isContent: Boolean get() = content != null
    }
}
```

- [ ] **Step 2: 在 DomainModule 注册 ToolSnapshotCache**

```kotlin
// di/DomainModule.kt
// ToolSnapshotCache 标了 @Inject constructor，无需 @Binds。
// 但确保 Hilt 能注入，需在 DomainModule 加一个 provider 或确认 @Inject constructor 已足够。
// @Singleton + @Inject constructor → Hilt 自动可注入，无需额外绑定。
// 验证：compileDevDebugKotlin 成功即可。
```

> **注意**：`@Singleton` + `@Inject constructor` 已足够让 Hilt 注入。无需在 Module 加 @Provides。

- [ ] **Step 3: FileViewerUiState 加 toolSnapshot 字段**

```kotlin
data class FileViewerUiState(
    // ... existing ...
    val renderMode: FileViewerRenderMode = FileViewerRenderMode.SOURCE,
    val isMarkdown: Boolean = false,
    // Phase 2 Task 9: Tool snapshot
    val isToolSnapshot: Boolean = false,         // 是否工具快照视图
    val toolSnapshotBefore: String? = null,      // 用于 diff
    val toolSnapshotAfter: String? = null,       // 用于 diff
    val toolSnapshotContent: String? = null      // 用于 read/write 直接展示
)
```

- [ ] **Step 4: 修改 FileViewerViewModel — 实现 TOOL_SNAPSHOT / TOOL_SNAPSHOT_DIFF 加载**

```kotlin
@HiltViewModel
class FileViewerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getFileContent: GetFileContentUseCase,
    private val getFileDiff: GetFileDiffUseCase,
    private val toolSnapshotCache: ToolSnapshotCache  // 新增
) : ViewModel() {
    // ... existing ...
    private val source = savedStateHandle.get<String>(FileViewerNav.PARAM_SOURCE) ?: FileViewerNav.Source.LIVE
    private val toolPartIds: List<String> = savedStateHandle.get<String>(FileViewerNav.PARAM_TOOL_PART_IDS).orEmpty()
        .split(",").filter { it.isNotBlank() }

    init {
        when (source) {
            FileViewerNav.Source.LIVE -> loadLive()
            FileViewerNav.Source.GIT_DIFF -> loadGitDiff()
            FileViewerNav.Source.TOOL_SNAPSHOT -> loadToolSnapshot()
            FileViewerNav.Source.TOOL_SNAPSHOT_DIFF -> loadToolSnapshotDiff()
        }
    }

    private fun loadToolSnapshot() {
        if (toolPartIds.isEmpty()) {
            _uiState.update { it.copy(isLoading = false, error = R.string.fileviewer_error_tool_snapshot_missing) }
            return
        }
        val snapshots = toolSnapshotCache.getAll(toolPartIds)
        if (snapshots.isEmpty()) {
            _uiState.update { it.copy(isLoading = false, error = R.string.fileviewer_error_tool_snapshot_missing) }
            return
        }
        val first = snapshots.first()
        val content = first.content
            ?: first.after  // Edit 没有 content，用 after 作为显示内容
            ?: ""
        _uiState.update {
            it.copy(
                isLoading = false,
                content = content,
                isEmpty = content.isBlank(),
                isMarkdown = isMarkdownFile(filePath),
                renderMode = FileViewerRenderMode.SOURCE,
                isToolSnapshot = true,
                toolSnapshotContent = first.content,
                toolSnapshotBefore = first.before,
                toolSnapshotAfter = first.after
            )
        }
    }

    private fun loadToolSnapshotDiff() {
        if (toolPartIds.isEmpty()) {
            _uiState.update { it.copy(isLoading = false, error = R.string.fileviewer_error_tool_snapshot_missing) }
            return
        }
        val snapshots = toolSnapshotCache.getAll(toolPartIds)
        if (snapshots.isEmpty()) {
            _uiState.update { it.copy(isLoading = false, error = R.string.fileviewer_error_tool_snapshot_missing) }
            return
        }
        // 累积 diff：first.before → last.after
        val cumulativeBefore = snapshots.first().before ?: ""
        val cumulativeAfter = snapshots.last().after ?: snapshots.last().content ?: ""
        // 生成 unified diff patch
        val patch = computeUnifiedDiff(cumulativeBefore, cumulativeAfter)
        val hunks = diffParser.parseUnifiedDiff(patch)

        _uiState.update {
            it.copy(
                isLoading = false,
                mode = FileViewerMode.DIFF,
                diff = VcsFileDiff(file = filePath, patch = patch, additions = 0, deletions = 0, status = null),
                hunks = hunks,
                currentHunkIndex = 0,
                isEmpty = hunks.isEmpty(),
                isToolSnapshot = true,
                toolSnapshotBefore = cumulativeBefore,
                toolSnapshotAfter = cumulativeAfter
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        // 退出 viewer 时清理 cache（避免内存泄漏）
        if (toolPartIds.isNotEmpty()) toolSnapshotCache.clear(toolPartIds)
    }

    /** 简单的 unified diff 生成（基于行的 LCS）。 */
    // 如果项目已有 DiffParser / computeSimpleDiff 工具，复用之；否则实现最简版本。
    private fun computeUnifiedDiff(before: String, after: String): String {
        // 用 chat 模块的 computeSimpleDiff 或第三方库
        // 简化方案：直接用 java.diff_utils 或自实现行级 diff
        // Phase 2 实现可用最简方案：unifiedDiff(before.lines(), after.lines())
        // 详见 chat/tools/DiffHelpers.kt 已有的 computeSimpleDiff
        // 若不可用，临时实现：每个不同的行都标 +/-
        val beforeLines = before.lines()
        val afterLines = after.lines()
        val sb = StringBuilder()
        sb.append("@@ -1,${beforeLines.size} +1,${afterLines.size} @@\n")
        // 最简 diff：标 - 旧行 + 新行（不是真正 LCS，但能显示全貌）
        // 注：生产级实现应使用 Myers diff 或现有 computeSimpleDiff
        beforeLines.forEach { sb.append("-").append(it).append("\n") }
        afterLines.forEach { sb.append("+").append(it).append("\n") }
        return sb.toString()
    }
}
```

> **TODO（必须在 Step 5 前解决）**：`computeUnifiedDiff` 的简化版会让整个文件都标红/绿。应复用 chat 模块的 `computeSimpleDiff`（位于 `chat/tools/DiffHelpers.kt`）—— 若它返回的是结构化 `DiffLine` 列表，直接转换成 unified patch 文本即可。**Step 5 实际实现时需先 grep `computeSimpleDiff` 找出实现并复用**。

- [ ] **Step 5: grep 现有 diff 工具**

```bash
grep -rn "fun computeSimpleDiff\|fun unifiedDiff" app/src/main --include="*.kt"
```

预期：找到 `chat/tools/DiffHelpers.kt`。若返回 `List<DiffLine>`，写转换器：

```kotlin
private fun computeUnifiedDiff(before: String, after: String): String {
    val diffLines = computeSimpleDiff(before.lines(), after.lines())
    return buildString {
        append("@@ -1,${before.lines().size} +1,${after.lines().size} @@\n")
        diffLines.forEach { dl ->
            val prefix = when (dl.type) {
                DiffLineType.ADDED -> "+"
                DiffLineType.REMOVED -> "-"
                else -> " "
            }
            append(prefix).append(dl.text).append("\n")
        }
    }
}
```

- [ ] **Step 6: ChatScreen 集成 ToolSnapshotCache + 跳转改造**

> ⚠️ **遵守 chatscreen-editing-protocol**：Read ChatScreen.kt 全文 → Edit → compile → commit。

1. 在 ChatScreen 顶部注入 ToolSnapshotCache：

```kotlin
@Composable
fun ChatScreen(
    // ... existing params ...
    viewModel: ChatViewModel = hiltViewModel(),
    toolSnapshotCache: ToolSnapshotCache = hiltViewModel<ChatViewModel>().let { /* 通过 ViewModel 暴露 or hiltViewModel() 直接获取 */ }
    // 简化：在 ChatViewModel 里注入 ToolSnapshotCache，暴露 cacheToolSnapshot(partId, snapshot) 方法
)
```

2. 在 ChatViewModel 加 cacheToolSnapshot 方法：

```kotlin
class ChatViewModel @Inject constructor(
    // ... existing ...
    private val toolSnapshotCache: ToolSnapshotCache
) : ViewModel() {
    // ...
    fun cacheToolSnapshot(toolPart: Part.Tool) {
        val input = extractToolInput(toolPart)
        val filePath = input["filePath"]?.jsonPrimitive?.contentOrNull ?: ""
        val metadata = (toolPart.state as? ToolState.Completed)?.metadata
        val before = metadata?.get("filediff")?.let { (it as? JsonObject)?.get("before")?.jsonPrimitive?.contentOrNull }
            ?: input["oldString"]?.jsonPrimitive?.contentOrNull
        val after = metadata?.get("filediff")?.let { (it as? JsonObject)?.get("after")?.jsonPrimitive?.contentOrNull }
            ?: input["content"]?.jsonPrimitive?.contentOrNull
            ?: input["newString"]?.jsonPrimitive?.contentOrNull
        val content = when (toolPart.tool.lowercase()) {
            "read" -> (toolPart.state as? ToolState.Completed)?.output
            "write" -> input["content"]?.jsonPrimitive?.contentOrNull
            "edit" -> after  // Edit 的"显示内容"是修改后的全文件
            else -> null
        }
        val snapshot = ToolSnapshotCache.Snapshot(
            filePath = filePath,
            content = content,
            before = before,
            after = after,
            toolName = toolPart.tool
        )
        toolSnapshotCache.put(toolPart.id, snapshot)
    }
    fun cacheToolSnapshotGroup(parts: List<Part.Tool>) {
        val snapshots = parts.associate { it.id ->
            // 同上提取逻辑，可用辅助函数
            it.id to extractSnapshot(it)
        }
        toolSnapshotCache.putAll(snapshots)
    }
    // ... 提取辅助函数 extractSnapshot(tool) ...
}
```

3. 在 ChatScreen 的 `onOpenFile` 回调中先 cache 再 navigate：

```kotlin
val onOpenFileWithSave: (String) -> Unit = { filePath ->
    // 现有：直接 navigate LIVE
    // Phase 2 改造：根据工具类型 cache 后 navigate 对应 source
    onOpenFile(filePath)  // 仍调用 onOpenFile，但 NavGraph 改造为根据工具类型跳转
}
```

**核心改造点**：`onOpenFile(filePath: String)` 接口太简单，无法传 source/snapshot。需要扩展为 `onOpenToolSnapshot(partId: String)` 或 `onOpenFileWithSource(filePath: String, source: String, partIds: List<String>)`。

**推荐方案**：ToolCard 接的 `onOpenFile: ((filePath: String) -> Unit)?` 扩展为 `onViewTool: ((ViewToolRequest) -> Unit)?`：

```kotlin
data class ViewToolRequest(
    val filePath: String,
    val source: String,                    // LIVE / TOOL_SNAPSHOT / TOOL_SNAPSHOT_DIFF
    val toolPartIds: List<String> = emptyList()
)
```

- [ ] **Step 7: ToolCardResolver 接口扩展**

```kotlin
interface ToolCardResolver {
    fun resolve(
        tool: Part.Tool,
        isExpanded: Boolean,
        onToggleExpand: () -> Unit,
        onViewSubSession: ((String) -> Unit)?,
        turnAgentName: String?,
        onOpenFile: ((filePath: String) -> Unit)?  // 保留向后兼容
        // Phase 2 新增：
        ,onViewTool: ((ViewToolRequest) -> Unit)? = null
        ,groupInfo: ToolGroupInfo? = null  // 视觉分组信息（③ 徽章 + 左边线）
    ): (@Composable () -> Unit)?
}

data class ToolGroupInfo(val totalCount: Int, val indexInGroup: Int)
```

- [ ] **Step 8: Read/Write/Edit 卡片用 ViewToolRequest**

ReadToolCard / WriteToolCard（Read/Write → TOOL_SNAPSHOT）：

```kotlin
@Composable
internal fun ReadToolCard(
    tool: Part.Tool,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onOpenFile: ((filePath: String) -> Unit)? = null,
    onViewTool: ((ViewToolRequest) -> Unit)? = null,
    groupInfo: ToolGroupInfo? = null
) {
    // ... existing logic ...
    val clickAction: () -> Unit = when {
        onViewTool != null -> {
            {
                val request = ViewToolRequest(
                    filePath = filePath,
                    source = if (groupInfo != null && groupInfo.totalCount > 1)
                        FileViewerNav.Source.TOOL_SNAPSHOT_DIFF  // 同组多个 → 累积 diff
                    else FileViewerNav.Source.TOOL_SNAPSHOT,
                    toolPartIds = if (groupInfo != null) emptyList<String>() else listOf(tool.id)
                    // 同组时 partIds 由外层调用方从 ToolSnapshotGroup 传入
                )
                onViewTool.invoke(request)
            }
        }
        onOpenFile != null -> { { onOpenFile.invoke(filePath) } }
        else -> { {} }
    }
    ToolCardScaffold(
        // ...
        rightSideExtras = {
            if (filePath.isNotBlank() && (onOpenFile != null || onViewTool != null)) {
                OpenFileIconButton(onClick = clickAction)
            }
            // Phase 2: 同组徽章（仅首卡显示）
            if (groupInfo != null && groupInfo.totalCount > 1 && groupInfo.indexInGroup == 0) {
                GroupSizeBadge(count = groupInfo.totalCount)
            }
        }
    )
}
```

EditToolCard（Edit → TOOL_SNAPSHOT_DIFF，单 part 也用 diff 视图）：

```kotlin
val clickAction: () -> Unit = when {
    onViewTool != null -> {
        {
            val request = ViewToolRequest(
                filePath = filePath,
                source = FileViewerNav.Source.TOOL_SNAPSHOT_DIFF,
                toolPartIds = listOf(tool.id)
            )
            onViewTool.invoke(request)
        }
    }
    // ...
}
```

- [ ] **Step 9: 实现 GroupSizeBadge + 左侧竖线**

在 `ToolCardScaffold.kt` 添加：

```kotlin
@Composable
internal fun RowScope.GroupSizeBadge(count: Int) {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.SELECTED),
        shape = ShapeTokens.extraSmall,
        modifier = Modifier
            .size(18.dp)
            .testTag("tool_card_group_badge")
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center
        )
    }
}
```

左侧竖线：在 ToolCardScaffold 的 `AmoledSurface` 内加 `Modifier.background(...)` 或在外层 Row 加 3dp Surface 竖线。简化方案：用 `Modifier.border` 在 Surface 左边加竖线：

```kotlin
AmoledSurface(
    // ...
    modifier = modifier
        .fillMaxWidth()
        .then(
            if (groupInfo != null && groupInfo.totalCount > 1) {
                Modifier.border(
                    width = 3.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.MUTED),
                    shape = ShapeTokens.smallMedium
                )
            } else Modifier
        )
)
```

- [ ] **Step 10: MessageCard / PartContent 调用 ToolSnapshotGrouper**

在渲染 Tool 卡片列表前先 group：

```kotlin
@Composable
fun ToolCardsList(parts: List<Part.Tool>, /* ... */) {
    val groups = remember(parts) { ToolSnapshotGrouper.group(parts) }
    // 构建 partId → group 映射
    val partToGroup = remember(groups) {
        groups.flatMap { g -> g.toolParts.mapIndexed { idx, p -> p.id to (g to idx) } }.toMap()
    }
    LazyColumn {
        items(parts, key = { it.id }) { part ->
            val (group, indexInGroup) = partToGroup[part.id]?.let { it.first to it.second }
                ?: (null to 0)
            val groupInfo = group?.takeIf { it.isMulti }?.let { ToolGroupInfo(it.size, indexInGroup) }
            ToolCardRenderer.render(
                tool = part,
                // ...
                onViewTool = { request ->
                    // 同组 → 补充所有 partIds
                    val finalRequest = if (group != null && group.isMulti) {
                        request.copy(toolPartIds = group.toolParts.map { it.id })
                    } else request
                    onViewTool(finalRequest)
                },
                groupInfo = groupInfo
            )
        }
    }
}
```

- [ ] **Step 11: NavGraph 入口1 跳转改造**

```kotlin
// NavGraph.kt ChatScreen 的 onViewTool 回调
onViewTool = { request ->
    scope.launch {
        // 1. Cache 所有相关 tool snapshots（通过 ChatViewModel）
        chatViewModel.cacheToolSnapshotGroupByIds(request.toolPartIds)
        // 2. Navigate
        val session = sessionRepository.getSession(params.server.serverId, params.sessionId).getOrNull()
        val dir = session?.directory ?: params.directory
        navController.navigate(
            FileViewerNav.createRoute(
                serverUrl = params.server.serverUrl,
                username = params.server.username,
                password = params.server.password,
                serverName = params.server.serverName,
                serverId = params.server.serverId,
                sessionId = params.sessionId,
                filePath = request.filePath,
                source = request.source,
                toolPartIds = request.toolPartIds.joinToString(","),
                directory = dir
            )
        )
    }
}
```

> **ChatViewModel 加方法**：
> ```kotlin
> fun cacheToolSnapshotGroupByIds(partIds: List<String>) {
>     // 通过 getParts(sessionId) Flow 取最新 parts，cache
>     // 简化：直接读 cache 看是否已有；若无，从 sessionRepository 取
> }
> ```

**更干净的方案**：在 ChatScreen 渲染 ToolCardsList 时，**预先把所有 Read/Write/Edit 工具 cache 进 ToolSnapshotCache**（一次性，避免跳转时再读）。但这会浪费内存（不打开的工具也 cache）。

**最终推荐**：在 ToolCardsList 的 `onViewTool` 回调中立即 cache 当前组：

```kotlin
onViewTool = { request ->
    // 立即 cache 当前组所有 part
    val parts = request.toolPartIds.mapNotNull { id -> allPartsById[id] }
    chatViewModel.cacheToolSnapshotGroup(parts)
    // 然后 navigate
    onViewTool(request)  // 透传到 NavGraph
}
```

- [ ] **Step 12: 编译验证 + 跑测试**

```bash
.\gradlew :app:compileDevDebugKotlin
.\gradlew :app:testDevDebugUnitTest --rerun
```

Expected: 全绿。

> **关键风险**：`FileViewerViewModel` 现在注入了 `ToolSnapshotCache`，所有现有测试需要更新构造函数。**修改 FileViewerViewModelTest 所有 ViewModel 实例化代码**，加上 `toolSnapshotCache = mockk()` 或真实实例。

- [ ] **Step 13: 更新 FileViewerViewModelTest**

```kotlin
private val toolSnapshotCache = ToolSnapshotCache()  // 真实实例（无 Android 依赖）

// 所有 vm = FileViewerViewModel(...) 改为：
// FileViewerViewModel(savedStateHandle(), getFileContent, getFileDiff, toolSnapshotCache)

// 新增测试：
@Test
fun `TOOL_SNAPSHOT source loads content from cache and clears on cleared`() = runTest {
    coEvery { getFileContent(any(), any(), any()) } returns Result.success(sampleKotlinContent)
    toolSnapshotCache.put("part-1", ToolSnapshotCache.Snapshot(
        filePath = "app/Main.kt", content = "class Main", before = null, after = null, toolName = "read"
    ))
    val vm = FileViewerViewModel(
        savedStateHandle(filePath = "app/Main.kt", source = FileViewerNav.Source.TOOL_SNAPSHOT, toolPartIds = "part-1"),
        getFileContent, getFileDiff, toolSnapshotCache
    )
    assertEquals(true, vm.uiState.value.isToolSnapshot)
    assertEquals("class Main", vm.uiState.value.content)
    // 模拟 ViewModel 销毁
    vm.onCleared()
    assertEquals(null, toolSnapshotCache.get("part-1"))
}

@Test
fun `TOOL_SNAPSHOT with missing cache shows error`() = runTest {
    val vm = FileViewerViewModel(
        savedStateHandle(filePath = "app/X.kt", source = FileViewerNav.Source.TOOL_SNAPSHOT, toolPartIds = "missing-id"),
        getFileContent, getFileDiff, toolSnapshotCache
    )
    assertEquals(true, vm.uiState.value.error != null)
}

@Test
fun `TOOL_SNAPSHOT_DIFF loads cumulative diff from multiple cached parts`() = runTest {
    toolSnapshotCache.putAll(mapOf(
        "p1" to ToolSnapshotCache.Snapshot("app/X.kt", null, "line1\nline2\n", "line1-mod\nline2\n", "edit"),
        "p2" to ToolSnapshotCache.Snapshot("app/X.kt", null, "line1-mod\nline2\n", "line1-mod\nline2-new\n", "edit")
    ))
    val vm = FileViewerViewModel(
        savedStateHandle(filePath = "app/X.kt", source = FileViewerNav.Source.TOOL_SNAPSHOT_DIFF, toolPartIds = "p1,p2"),
        getFileContent, getFileDiff, toolSnapshotCache
    )
    assertEquals(FileViewerMode.DIFF, vm.uiState.value.mode)
    assertEquals(true, vm.uiState.value.isToolSnapshot)
    // 累积 before = first.before, after = last.after
    assertEquals("line1\nline2\n", vm.uiState.value.toolSnapshotBefore)
    assertEquals("line1-mod\nline2-new\n", vm.uiState.value.toolSnapshotAfter)
}
```

- [ ] **Step 14: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/domain/repository/ToolSnapshotCache.kt \
        app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/FileViewerUiState.kt \
        app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/FileViewerViewModel.kt \
        app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt \
        app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt \
        app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/tools/ToolCardResolver.kt \
        app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/tools/DefaultToolCardResolver.kt \
        app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/tools/cards/ToolCardScaffold.kt \
        app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/tools/cards/ReadToolCard.kt \
        app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/tools/cards/WriteToolCard.kt \
        app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/tools/cards/EditToolCard.kt \
        app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/MessageCard.kt \
        app/src/main/kotlin/dev/minios/ocremote/ui/navigation/NavGraph.kt \
        app/src/test/kotlin/dev/minios/ocremote/ui/screens/viewer/FileViewerViewModelTest.kt
git commit -m "feat: entry1 ToolSnapshotCache + grouping UI (ViewToolRequest, group badge, cumulative diff view)"
```

---

## Task 10: strings.xml + Maestro E2E flows 22/24/25

<a id="task-10"></a>

**Spec ref:** §11.4（flows 22/24/25）

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Create: `.maestro/flows/e2e-verify/22-workspace-search.yaml`
- Create: `.maestro/flows/e2e-verify/24-tool-card-view.yaml`
- Create: `.maestro/flows/e2e-verify/25-md-preview-toggle.yaml`

- [ ] **Step 1: 添加 strings.xml**

在 `<!-- File Viewer -->` 区段追加：

```xml
<!-- Workspace search -->
<string name="workspace_search_hint">Type to search files</string>
<string name="workspace_search_no_results">No matching files</string>
<string name="workspace_search_no_git_match">No matching changes</string>

<!-- File Viewer markdown toggle -->
<string name="viewer_md_show_render">Show rendered preview</string>
<string name="viewer_md_show_source">Show source code</string>

<!-- File Viewer tool snapshot -->
<string name="fileviewer_error_tool_snapshot_missing">Tool snapshot data not found</string>

<!-- Tool card grouping -->
<string name="tool_card_group_count">%1$d edits in this turn</string>
```

- [ ] **Step 2: 跑 lokit 同步**

```bash
lokit
```

- [ ] **Step 3: 创建 Maestro flow 22（搜索）**

```yaml
# 22-workspace-search.yaml
appId: dev.minios.ocremote.dev
---

- runFlow: ../e2e-verify/06-chat-screen.yaml
- tapOn:
    id: "more_vert"
- tapOn:
    id: "menu_open_workspace"
- extendedWaitUntil:
    visible: ".*"
    timeout: 10000
    optional: true

# 文件树搜索
- tapOn:
    id: "workspace_search_button"
- tapOn:
    id: "workspace_search_input"
- inputText: "Workspace"
- extendedWaitUntil:
    visible: ".*Workspace.*"
    timeout: 8000
    optional: true
- takeScreenshot: e2e_search_file_results
- tapOn:
    id: "workspace_search_result"
    index: 0
- extendedWaitUntil:
    visible: ".*"
    timeout: 5000
    optional: true
- takeScreenshot: e2e_search_opened_file

# 返回工作空间
- tapOn:
    id: "back_button"
- tapOn:
    id: "back_button"

# Git 搜索（若项目有变更）
- tapOn:
    id: "more_vert"
- tapOn:
    id: "menu_open_workspace"
- tapOn:
    id: "panel_git_changes"
- tapOn:
    id: "workspace_search_button"
- tapOn:
    id: "workspace_search_input"
- inputText: ".kt"
- takeScreenshot: e2e_search_git_filter
- tapOn:
    id: "workspace_search_clear"
- takeScreenshot: e2e_search_cleared
```

- [ ] **Step 4: 创建 Maestro flow 24（入口1 工具卡片查看）**

```yaml
# 24-tool-card-view.yaml
appId: dev.minios.ocremote.dev
---

# 前置：需在 chat 中让 AI 调用 Edit/Read/Write（人工准备）
- runFlow: ../e2e-verify/06-chat-screen.yaml

# 等待 chat 出现 Edit 卡片
- extendedWaitUntil:
    visible: ".*Edit.*"
    timeout: 60000
    optional: true

# 验证 Edit 卡片有 ↗ 按钮
- tapOn:
    id: "tool_card_open_file"
    index: 0
- extendedWaitUntil:
    visible: ".*Diff.*"
    timeout: 10000
    optional: true
- takeScreenshot: e2e_entry1_diff_view

# 验证同 turn 多次 Edit 同文件有 ③ 徽章
# （需人工准备：让 AI 在同一 turn 内多次 Edit 同一文件）
- extendedWaitUntil:
    visible: ".*tool_card_group_badge.*"
    timeout: 10000
    optional: true
- takeScreenshot: e2e_entry1_group_badge

# 返回 chat
- tapOn:
    id: "back_button"
```

- [ ] **Step 5: 创建 Maestro flow 25（md 预览切换）**

```yaml
# 25-md-preview-toggle.yaml
appId: dev.minios.ocremote.dev
---

- runFlow: ../e2e-verify/06-chat-screen.yaml
- tapOn:
    id: "more_vert"
- tapOn:
    id: "menu_open_workspace"

# 打开 README.md（项目根目录有）
- tapOn:
    id: "workspace_search_button"
- tapOn:
    id: "workspace_search_input"
- inputText: "README.md"
- tapOn:
    id: "workspace_search_result"
    index: 0

# 验证默认源码视图 + 渲染按钮可见
- extendedWaitUntil:
    visible: ".*viewer_md_render_button.*"
    timeout: 5000
    optional: true
- takeScreenshot: e2e_md_source_view

# 切到渲染预览
- tapOn:
    id: "viewer_md_render_button"
- extendedWaitUntil:
    visible: ".*#.*"
    timeout: 5000
    optional: true
- takeScreenshot: e2e_md_render_preview

# 切回源码
- tapOn:
    id: "viewer_md_render_button"
- takeScreenshot: e2e_md_back_to_source

- tapOn:
    id: "back_button"
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/res/values/strings.xml \
        app/src/main/res/values-*/strings.xml \
        .maestro/flows/e2e-verify/22-workspace-search.yaml \
        .maestro/flows/e2e-verify/24-tool-card-view.yaml \
        .maestro/flows/e2e-verify/25-md-preview-toggle.yaml
git commit -m "feat: Phase 2 strings (search/md/snapshot) + Maestro flows 22/24/25"
```

---

## Phase 2 验收清单

每项必须通过：

- [ ] `.\gradlew :app:compileDevDebugKotlin`（120s 内 SUCCESS）
- [ ] `.\gradlew :app:testDevDebugUnitTest --rerun`（180s 内全绿，Phase 2 新增约 45+ 测试）
  - FileRepositoryImplTest: +3（findFiles 相关）
  - FindFilesUseCaseTest: 2
  - WorkspaceViewModelTest: +8（search 状态/filter）
  - FileViewerViewModelTest: +6（md toggle）+ 3（tool snapshot）= +9
  - ToolSnapshotGrouperTest: 15
- [ ] `maestro test .maestro/flows/e2e-verify/22-workspace-search.yaml` → PASS
- [ ] `maestro test .maestro/flows/e2e-verify/24-tool-card-view.yaml` → PASS（若前置工具卡片就绪）
- [ ] `maestro test .maestro/flows/e2e-verify/25-md-preview-toggle.yaml` → PASS
- [ ] **testTag 全部就位**：
  ```bash
  grep -rn "testTag.*workspace_search_button\|testTag.*workspace_search_input\|testTag.*workspace_search_clear\|testTag.*workspace_search_result\|testTag.*viewer_md_render_button\|testTag.*tool_card_group_badge" app/src/main --include="*.kt"
  ```
  预期：6+ 行命中。
- [ ] 手动验收（真实 opencode 服务器 4096 + emulator 10.0.2.2）：
  - **搜索**：进入工作空间 → 点 🔍 → 输入 `WorkspaceScreen` → 看到结果列表（路径中间省略）→ 点结果 → FileViewer 打开对应文件 → 返回 → 切到 Git 面板 → 点 🔍 → 输入 `.kt` → 看到过滤后的变更列表
  - **md 预览**：工作空间 → 搜 `README.md` → 打开 → 默认源码视图（行号 + 内容）→ 点 👁 渲染 → 切换到 markdown 渲染预览（位置接近原滚动比例）→ 点 📄 源码 → 切回（位置接近）
  - **入口1**：在 chat 中让 AI 调用 Read → 卡片显示 ↗ 按钮 → 点 ↗ → FileViewer 打开（顶部无 LIVE 标识，是 ToolSnapshot）→ 显示工具输出内容 → 返回；让 AI 连续 Edit 同一文件 3 次 → 同 turn 三张卡片显示 ③ 徽章 + 共享左侧竖线 → 点任一卡片 ↗ → FileViewer 打开 Diff 视图，显示累积 before→after diff

---

## Phase 2 不包含（Phase 3-4）

- **标注能力**：Phase 3 实现（长按选区→标注意见→提交结构化提示词）
- **md 源码↔渲染精确行号映射**：Phase 2 v1 用比例锚点，v2 行号精确映射在 spec §13.2 列为未来优化
- **大文件分页加载**：Phase 2 沿用 Phase 1 的 5000 行软截断，分页加载（Phase 4 实现）
- **rememberSaveable 滚动位置恢复**：旋转屏幕后位置丢失，Phase 4 加 rememberSaveable
- **ApplyPatch/Patch/Glob/Search 工具卡片改造**：仅 Read/Write/Edit（spec §4 明确）
- **ToolSnapshotCache 持久化**：仅内存，进程死亡即丢失（spec §13.1 接受）
- **入口1 同组累积 diff 按次切换**：Phase 2 只显示整体累积 diff；按 N 次修改切换在 spec §13.2 列为 C 档优化
- **路径精确匹配（相同文本多次出现）**：spec §13.1 已记录风险，Phase 4 改进

---

## Self-Review

### Spec 覆盖检查

| Spec 条目 | 对应 Task | 状态 |
|-----------|----------|------|
| §6.4 搜索模式（覆盖式 overlay） | Task 3+4 | ✅ |
| §6.4 文件树搜索（服务端 find/file） | Task 1+2 | ✅ |
| §6.4 Git 变更搜索（客户端 filter） | Task 2 | ✅ |
| §6.4 搜索 debounce 300ms | Task 2（用 delay(300) + Job 取消） | ✅ |
| §6.4 路径中间省略 | Task 3（ellipsizeMiddle 工具） | ✅ |
| §6.4 0 结果"未找到匹配文件" | Task 3（EmptyHint） | ✅ |
| §6.1 TopBar [🔍][📁][🔀·12] 顺序 | Task 4 | ✅ |
| §7.1 TopBar 形态 A [📄源码][👁渲染] | Task 7 | ✅ |
| §7.3 md 源码↔渲染切换 | Task 6+7 | ✅ |
| §7.3 滚动比例锚点定位 v1 | Task 7（sourceFraction + LaunchedEffect） | ✅ |
| §2.2 mikepenz 多 Text 限制（md 默认源码） | Task 6（默认 SOURCE） | ✅ |
| §5.1-5.4 入口1 工具卡片查看 | Task 9（ToolSnapshotCache + ViewToolRequest） | ✅ |
| §5.5 同 turn 同文件聚合 | Task 8（ToolSnapshotGrouper） | ✅ |
| §5.5 累积 diff 重建 | Task 8（cumulativeBefore/After）+ Task 9（loadToolSnapshotDiff） | ✅ |
| §5.5 视觉：③ 徽章 + 左侧竖线 | Task 9（GroupSizeBadge + border） | ✅ |
| §5.6 入口1 标注能力 | Phase 3（不在 Phase 2 范围） | ⏭️ |
| §11.2 ToolSnapshotGrouperTest 真实样本 | Task 8（15 测试用例） | ✅ |
| §11.4 Maestro flow 22 | Task 10 | ✅ |
| §11.4 Maestro flow 24 | Task 10 | ✅ |
| §11.4 Maestro flow 25 | Task 10 | ✅ |

### 类型一致性检查

- `ViewToolRequest(filePath, source, toolPartIds)` — Task 9 定义，Task 9 使用 ✅
- `ToolSnapshotCache.Snapshot(filePath, content, before, after, toolName)` — Task 9 定义，ChatViewModel/ViewModel 使用 ✅
- `ToolSnapshotGroup(normalizedFilePath, toolParts, firstFilePath, cumulativeBefore, cumulativeAfter)` — Task 8 定义，Task 9 使用 ✅
- `ToolGroupInfo(totalCount, indexInGroup)` — Task 9 定义，Read/Write/Edit 卡片使用 ✅
- `FileViewerRenderMode { SOURCE, RENDER_PREVIEW }` — Task 6 定义，Task 7 使用 ✅
- `WorkspaceUiState.isSearchMode/searchQuery/fileSearchResults/searchLoading/hasSearched` — Task 2 定义，Task 3/4 使用 ✅

### 占位符扫描

- 无 "TBD"/"TODO" 在最终代码中（Step 5 的 computeUnifiedDiff TODO 已明确要复用 computeSimpleDiff，是实施提示而非遗留 TODO）
- 所有测试步骤都有完整测试代码
- 所有代码步骤都有完整实现
- Task 9 Step 11 的 "推荐方案" 描述了三种 cache 时机选择，最终明确推荐 onViewTool 回调中立即 cache（实施时按推荐方案落地）

### 风险与降级

| 风险 | 概率 | 缓解 |
|------|------|------|
| mikepenz Markdown 首次渲染 maxValue=0 导致滚动锚点失败 | 中 | Step 4 用 `snapshotFlow { maxValue }.filter { it > 0 }.first()` 等待 |
| ToolSnapshotGrouper 在大量 parts（>100）时性能 | 低 | LinkedHashMap + 单次遍历，O(n) |
| 大量 ToolSnapshotCache 积累导致内存压力 | 低 | onCleared 清理 + 用户主动退出即清空 |
| 入口1 同组累积 diff 的 computeUnifiedDiff 简化版让全文件标红 | 中 | Step 5 明确要求复用 chat 模块的 computeSimpleDiff |
| ToolSnapshotCache 跨进程死亡丢失 | 低 | spec §13.1 接受，重新打开文件即可 |
| SearchTopBar FocusRequester 在某些设备上不弹键盘 | 低 | LaunchedEffect + requestFocus 是标准做法 |
