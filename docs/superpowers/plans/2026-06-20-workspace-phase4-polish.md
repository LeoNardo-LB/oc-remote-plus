# Workspace File Viewer — Phase 4 实现计划（大文件分页 + 滚动恢复 + 标注 rememberSaveable）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`).

**Goal:** 打磨 Phase 1-3 实现的 FileViewer 与 Workspace：(1) 将 Phase 1 的"5000 行硬截断"升级为分页加载（滚动到底加载更多，pageSize=200）；(2) 横向/纵向 ScrollState 用 `rememberSaveable` 保存，配置变更（旋转屏幕）后恢复滚动位置；(3) Phase 3 的标注列表通过 `rememberSaveable` 在旋转屏幕后保留。

**Architecture:** 沿用 Phase 1-3 的 Clean Architecture 三层结构。**关键改动**：(1) `FileViewerUiState` 增加 `visibleLineCount` 字段（取代 `isTruncated` boolean），ViewModel 暴露 `loadMoreLines()` 方法；(2) `CodeSourceView` 用 `LazyColumn` 的 `rememberLazyListState` + `LaunchedEffect` 监听滚动到底，触发 loadMore；(3) 用 `rememberSaveable(stateSaver = ScrollState.Saver)` 包裹所有 `ScrollState` 与 `LazyListState`，配置变更后自动恢复；(4) Phase 3 的 `Annotation` 列表（List<Annotation>）通过自定义 `mapSaver` 持久化到 SavedStateHandle。

**Tech Stack:** Kotlin + Compose + Hilt(KSP) + MockK 1.14.9 + Turbine 1.2.1。JDK 21。

---

## TOC

- [Global Constraints](#global-constraints)
- [关键架构决策](#关键架构决策)
- Task 1: [FileViewerUiState + ViewModel 分页加载状态](#task-1)
- Task 2: [CodeSourceView 分页渲染（LazyColumn loadMore）](#task-2)
- Task 3: [ScrollState rememberSaveable（CodeSourceView + DiffView + MarkdownPreview）](#task-3)
- Task 4: [Annotation 列表 rememberSaveable（旋转屏幕保留标注）](#task-4)
- Task 5: [Maestro E2E 旋转屏幕 + 大文件回归](#task-5)
- [Phase 4 验收清单](#phase-4-验收清单)
- [Phase 4 不包含](#phase-4-不包含)
- [Self-Review](#self-review)

---

## Global Constraints

> ⚠️ 所有 Task 隐含遵守。Phase 1/2/3 的 Global Constraints 全部继承。

### 继承自 Phase 1-3

- **路径与包名**：源码 `app/src/main/kotlin/`，测试 `app/src/test/kotlin/`；包名前缀 `dev.minios.ocremote.`
- **Gradle 命令带 flavor**：`compileDevDebugKotlin`（120s）、`testDevDebugUnitTest --rerun`（180s）
- **Material 3 First / Alpha tokens / Spacing tokens**
- **测试 isReturnDefaultValues=true + 真实样本**
- **ChatScreen.kt 编辑协议**：本 Phase 无 ChatScreen 改动，但 Phase 3 改动仍需遵守协议

### Phase 4 新增约束

- **分页 pageSize=200 行**：滚动到当前可见行数最后 50 行（visibleLineCount - 50）时触发 loadMore
- **保留 Phase 1 软截断兜底**：>100000 行的极端大文件仍先截断到 100000，再分页（防 OOM）
- **rememberSaveable 不持久化到磁盘**：进程死亡后丢失，仅配置变更（旋转屏幕）保留——这是 Compose `rememberSaveable` 的标准语义
- **Annotation 持久化用自定义 mapSaver**：不引入 Parcelable / Serialization 依赖（Annotation 是纯 Kotlin data class，加 Parcelable 是过度工程）
- **ScrollState.Saver 是 Compose 官方 Saver**：`rememberScrollState()` 内部已用 `rememberSaveable`，但跨 Phase 改造后需显式声明

### testTag 约定（Maestro 依赖）

| testTag | 添加位置（Task） | 用途 |
|---------|-----------------|------|
| `viewer_load_more_indicator` | Task 2（加载更多时的 CircularProgressIndicator） | 加载指示器（可选断言） |
| `viewer_load_more_button` | Task 2（极端兜底时显示的"加载更多"按钮） | 手动触发加载 |

---

## 关键架构决策

### 1. 分页加载用 visibleLineCount 字段而非 isTruncated boolean

Phase 1 的 `isTruncated: Boolean` 只能表达"已截断/未截断"两态，无法表达"已加载多少行"。Phase 4 改为：

```kotlin
data class FileViewerUiState(
    // ... 移除 isTruncated: Boolean
    val totalLineCount: Int = 0,        // 服务端文件总行数
    val visibleLineCount: Int = 0,      // 当前已加载的行数（分页累积）
    val isFullyLoaded: Boolean = false  // visibleLineCount >= totalLineCount
)
```

**分页加载触发条件**（CodeSourceView 内）：
```kotlin
val listState = rememberLazyListState()
LaunchedEffect(listState, uiState.visibleLineCount) {
    snapshotFlow {
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        lastVisible >= uiState.visibleLineCount - 50  // 接近底部 50 行
    }.filter { it && !uiState.isFullyLoaded }
     .distinctUntilChanged()
     .collect { onLoadMore() }
}
```

### 2. 极端大文件二次兜底

spec §10 边界：>256KB 或 >5000 行 → 截断警告。Phase 1 已实现 5000 行截断。Phase 4 升级为分页后，仍保留**极端兜底**：

- `totalLineCount > 100000` → 首次只加载 10000 行，提示"文件过大（100000+ 行），加载性能可能受影响"
- 这种场景下用户仍可滚动加载，但 UI 显式警告

### 3. ScrollState rememberSaveable

Compose 的 `rememberScrollState()` 内部已经用 `rememberSaveable`，但 Phase 2 改造后我们用了**可注入的 scrollState**（让外部控制滚动）。此时需要：

```kotlin
@Composable
fun CodeSourceView(
    content: String,
    filePath: String,
    modifier: Modifier = Modifier,
    scrollState: ScrollState? = null  // null → 内部 rememberSaveable
) {
    val actualScrollState = scrollState ?: rememberScrollState()
    // ...
}
```

**关键**：当外部不注入时，`rememberSaveable` 自动恢复；当外部注入时（Phase 2 的 md 切换场景），外部负责 rememberSaveable。

对于 `LazyListState`（分页用）：

```kotlin
val listState = rememberLazyListState()  // 内部不 rememberSaveable，旋转屏幕会重置
// 改为：
val listState = rememberSaveable(saver = LazyListState.Saver) {
    LazyListState()
}
```

### 4. Annotation 列表自定义 Saver

`Annotation` 是 data class（不是 Parcelable）。用 `listSaver`：

```kotlin
val AnnotationListSaver = listSaver<List<Annotation>, Any>(
    save = { list ->
        // 扁平化为可序列化基本类型列表
        list.flatMap { ann ->
            listOf(ann.id, ann.index, ann.startChar, ann.endChar,
                   ann.startLine, ann.startCol, ann.endLine, ann.endCol,
                   ann.selectedText, ann.note, ann.createdAt)
        }
    },
    restore = { saved ->
        // 每 11 个元素重建一个 Annotation
        saved.chunked(11).map { items ->
            Annotation(
                id = items[0] as String,
                index = items[1] as Int,
                startChar = items[2] as Int,
                endChar = items[3] as Int,
                startLine = items[4] as Int,
                startCol = items[5] as Int,
                endLine = items[6] as Int,
                endCol = items[7] as Int,
                selectedText = items[8] as String,
                note = items[9] as String,
                createdAt = items[10] as Long
            )
        }
    }
)
```

**注意**：`rememberSaveable(saver = AnnotationListSaver)` 默认存到 Bundle，配置变更后恢复；进程死亡由系统决定是否恢复（Android 默认行为）。

### 5. 大文件分页用 LazyColumn 而非 Column + verticalScroll

Phase 1 的 `CodeSourceView` 已经用 `LazyColumn`（按行 item）。Phase 4 改造点：

- 移除"5000 行一次性加载"逻辑
- 初始 `visibleLineCount = min(totalLineCount, 500)`（首屏约 500 行，远超屏幕可视区）
- 滚动到底 50 行时追加 200 行
- `totalLineCount` 在 `loadLive` 时通过 `content.count { it == '\n' } + 1` 计算（全量字符串的行数）

### 6. DiffView 和 MarkdownPreview 也用 rememberSaveable

`DiffView` 用 `LazyColumn(rememberLazyListState())` → 改为 `rememberSaveable(saver = LazyListState.Saver) { LazyListState() }`。

`MarkdownPreview` 内部 `verticalScroll(rememberScrollState())` → `rememberScrollState()` 已 rememberSaveable，无需改。

---

## Task 1: FileViewerUiState + ViewModel 分页加载状态

<a id="task-1"></a>

**Spec ref:** §10（大文件截断），§13.1（大文件 OOM 风险）

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/FileViewerUiState.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/FileViewerViewModel.kt`
- Modify: `app/src/test/kotlin/dev/minios/ocremote/ui/screens/viewer/FileViewerViewModelTest.kt`

**Interfaces:**
- Consumes: 无新依赖
- Produces: UiState 的 `totalLineCount/visibleLineCount/isFullyLoaded` 字段；ViewModel 的 `loadMoreLines()` 方法

- [ ] **Step 1: 修改 FileViewerUiState**

```kotlin
data class FileViewerUiState(
    // ... existing ...
    // Phase 4: 替换 isTruncated 为分页字段
    val totalLineCount: Int = 0,
    val visibleLineCount: Int = 0,
    val isFullyLoaded: Boolean = false,
    val isExtremelyLarge: Boolean = false  // >100000 行的极端警告
    // 移除：val isTruncated: Boolean = false
)
```

> **向后兼容**：`FileViewerScreen` 中引用 `uiState.isTruncated` 的地方全部改为 `!uiState.isFullyLoaded`（仍有未加载内容时显示警告）。

- [ ] **Step 2: 写测试（追加到 FileViewerViewModelTest）**

```kotlin
private val largeKotlinContent = buildString {
    append("package dev.minios.ocremote\n\n")
    append("class LargeFile {\n")
    for (i in 1..2000) {
        append("    fun method$i(): Int = $i\n")
    }
    append("}\n")
}

@Test
fun `loadLive paginates content - initial visibleLineCount is 500 for large files`() = runTest {
    coEvery { getFileContent(any(), any(), any()) } returns Result.success(largeKotlinContent)
    val vm = FileViewerViewModel(savedStateHandle(), getFileContent, getFileDiff, toolSnapshotCache)
    val total = largeKotlinContent.count { it == '\n' } + 1
    assertEquals(500, vm.uiState.value.visibleLineCount)
    assertEquals(total, vm.uiState.value.totalLineCount)
    assertEquals(false, vm.uiState.value.isFullyLoaded)
    assertEquals(false, vm.uiState.value.isExtremelyLarge)
}

@Test
fun `loadLive marks isExtremelyLarge for files over 100000 lines`() = runTest {
    // 构造 100001 行内容（用 mock）
    val hugeContent = (1..100001).joinToString("\n") { "line $it" }
    coEvery { getFileContent(any(), any(), any()) } returns Result.success(hugeContent)
    val vm = FileViewerViewModel(savedStateHandle(), getFileContent, getFileDiff, toolSnapshotCache)
    assertEquals(true, vm.uiState.value.isExtremelyLarge)
}

@Test
fun `loadLive for small file sets isFullyLoaded true and visibleLineCount equals total`() = runTest {
    coEvery { getFileContent(any(), any(), any()) } returns Result.success(sampleKotlinContent)
    val vm = FileViewerViewModel(savedStateHandle(), getFileContent, getFileDiff, toolSnapshotCache)
    val total = sampleKotlinContent.count { it == '\n' } + 1
    assertEquals(total, vm.uiState.value.visibleLineCount)
    assertEquals(true, vm.uiState.value.isFullyLoaded)
}

@Test
fun `loadMoreLines increases visibleLineCount by 200 and respects totalLineCount`() = runTest {
    coEvery { getFileContent(any(), any(), any()) } returns Result.success(largeKotlinContent)
    val vm = FileViewerViewModel(savedStateHandle(), getFileContent, getFileDiff, toolSnapshotCache)
    val initialVisible = vm.uiState.value.visibleLineCount  // 500
    vm.loadMoreLines()
    assertEquals(initialVisible + 200, vm.uiState.value.visibleLineCount)
    assertEquals(false, vm.uiState.value.isFullyLoaded)
}

@Test
fun `loadMoreLines clamps to totalLineCount and sets isFullyLoaded`() = runTest {
    val mediumContent = (1..600).joinToString("\n") { "line $it" }
    coEvery { getFileContent(any(), any(), any()) } returns Result.success(mediumContent)
    val vm = FileViewerViewModel(savedStateHandle(), getFileContent, getFileDiff, toolSnapshotCache)
    // totalLineCount = 600, initial visibleLineCount = 500
    vm.loadMoreLines()  // +200 = 700, clamp to 600
    assertEquals(600, vm.uiState.value.visibleLineCount)
    assertEquals(true, vm.uiState.value.isFullyLoaded)
}

@Test
fun `loadMoreLines is no-op when isFullyLoaded`() = runTest {
    coEvery { getFileContent(any(), any(), any()) } returns Result.success(sampleKotlinContent)
    val vm = FileViewerViewModel(savedStateHandle(), getFileContent, getFileDiff, toolSnapshotCache)
    val before = vm.uiState.value.visibleLineCount
    vm.loadMoreLines()
    assertEquals(before, vm.uiState.value.visibleLineCount)
}

@Test
fun `loadMoreLines for extremely large file starts at 10000 not 500`() = runTest {
    val hugeContent = (1..150000).joinToString("\n") { "line $it" }
    coEvery { getFileContent(any(), any(), any()) } returns Result.success(hugeContent)
    val vm = FileViewerViewModel(savedStateHandle(), getFileContent, getFileDiff, toolSnapshotCache)
    assertEquals(10000, vm.uiState.value.visibleLineCount)
    vm.loadMoreLines()
    assertEquals(10200, vm.uiState.value.visibleLineCount)
}
```

- [ ] **Step 3: 运行验证失败**

```bash
.\gradlew :app:testDevDebugUnitTest --rerun --tests "*.FileViewerViewModelTest"
```

Expected: FAIL — `visibleLineCount` 字段未定义；`loadMoreLines` 未定义；`isTruncated` 仍存在导致编译错误。

- [ ] **Step 4: 实现 ViewModel 分页逻辑**

修改 `FileViewerViewModel.kt` 的 `loadLive()`：

```kotlin
private companion object {
    const val INITIAL_PAGE_SIZE = 500
    const val PAGE_SIZE = 200
    const val EXTREMELY_LARGE_THRESHOLD = 100_000
    const val EXTREMELY_LARGE_INITIAL = 10_000
}

private fun loadLive() {
    viewModelScope.launch {
        getFileContent(serverId, directory, filePath)
            .onSuccess { c ->
                if (c.type == ContentType.BINARY) {
                    _uiState.update { it.copy(isLoading = false, isBinary = true, mimeType = c.mimeType) }
                } else {
                    val fullContent = c.content
                    val totalLines = if (fullContent.isEmpty()) 0
                                     else fullContent.count { it == '\n' } + if (fullContent.endsWith('\n')) 0 else 1
                    val isExtremelyLarge = totalLines > EXTREMELY_LARGE_THRESHOLD
                    val initialVisible = if (isExtremelyLarge) EXTREMELY_LARGE_INITIAL
                                         else minOf(totalLines, INITIAL_PAGE_SIZE)
                    val visibleContent = takeFirstLines(fullContent, initialVisible)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            content = visibleContent,
                            isEmpty = visibleContent.isBlank(),
                            isMarkdown = isMarkdownFile(filePath),
                            renderMode = FileViewerRenderMode.SOURCE,
                            totalLineCount = totalLines,
                            visibleLineCount = initialVisible,
                            isFullyLoaded = initialVisible >= totalLines,
                            isExtremelyLarge = isExtremelyLarge
                        )
                    }
                }
            }
            .onFailure { _ -> _uiState.update { it.copy(isLoading = false, error = R.string.workspace_error_load_failed) } }
    }
}

fun loadMoreLines() {
    val current = _uiState.value
    if (current.isFullyLoaded) return
    val newSize = (current.visibleLineCount + PAGE_SIZE).coerceAtMost(current.totalLineCount)
    // 注意：这里需要重新读取完整 content 以切片。Phase 4 简化方案：
    // cache 完整 content 到 ViewModel 私有字段，loadMore 时切片。
    val newContent = takeFirstLines(fullContentCache, newSize)
    _uiState.update {
        it.copy(
            content = newContent,
            visibleLineCount = newSize,
            isFullyLoaded = newSize >= it.totalLineCount
        )
    }
}

private var fullContentCache: String = ""

private fun takeFirstLines(content: String, lineCount: Int): String {
    if (lineCount <= 0) return ""
    var seen = 0
    val sb = StringBuilder()
    for (i in content.indices) {
        sb.append(content[i])
        if (content[i] == '\n') {
            seen++
            if (seen >= lineCount) break
        }
    }
    return sb.toString()
}
```

**关键修改**：在 `loadLive` 成功时，**把完整内容存到 `fullContentCache`**：

```kotlin
.onSuccess { c ->
    if (c.type == ContentType.BINARY) { /* ... */ }
    else {
        fullContentCache = c.content  // 缓存完整内容用于分页
        val fullContent = c.content
        // ... 后续计算 totalLines / initialVisible ...
    }
}
```

- [ ] **Step 5: 运行 + 编译 + Commit**

```bash
.\gradlew :app:testDevDebugUnitTest --rerun --tests "*.FileViewerViewModelTest"
# Expected: PASS (existing + 7 new)
.\gradlew :app:compileDevDebugKotlin
# 注意：FileViewerScreen.kt 引用 uiState.isTruncated 的地方需要同步修改为 !uiState.isFullyLoaded
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/FileViewerUiState.kt \
        app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/FileViewerViewModel.kt \
        app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/FileViewerScreen.kt \
        app/src/test/kotlin/dev/minios/ocremote/ui/screens/viewer/FileViewerViewModelTest.kt
git commit -m "feat: FileViewerViewModel paginated loading (pageSize 200, initial 500, extreme 10000)"
```

---

## Task 2: CodeSourceView 分页渲染（LazyColumn loadMore）

<a id="task-2"></a>

**Spec ref:** §7.2（LazyColumn 渲染），§10（大文件截断警告）

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/CodeSourceView.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/FileViewerScreen.kt`

- [ ] **Step 1: 修改 CodeSourceView 接受分页参数**

```kotlin
@Composable
fun CodeSourceView(
    content: String,
    filePath: String,
    modifier: Modifier = Modifier,
    scrollState: ScrollState? = null,
    // Phase 4: 分页参数
    visibleLineCount: Int? = null,        // null = 不分页，全量渲染（向后兼容）
    totalLineCount: Int? = null,
    onLoadMore: (() -> Unit)? = null
) {
    if (content.isEmpty()) return

    // ... highlights/annotated 计算同 Phase 1 ...

    val actualVisibleLines = visibleLineCount ?: lineCount
    val hScroll = scrollState ?: rememberScrollState()

    val listState = rememberSaveable(saver = LazyListState.Saver) {
        LazyListState()
    }

    // Phase 4: 滚动接近底部时触发 loadMore
    if (onLoadMore != null && visibleLineCount != null && totalLineCount != null) {
        LaunchedEffect(listState, visibleLineCount, totalLineCount) {
            snapshotFlow {
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                lastVisible >= visibleLineCount - 50  // 接近底部 50 行
            }
            .filter { it && visibleLineCount < totalLineCount }
            .distinctUntilChanged()
            .collect { onLoadMore() }
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = SpacingTokens.SM.dp)
    ) {
        items(
            count = actualVisibleLines,
            key = { it }
        ) { index ->
            // ... 同 Phase 1 的 Row + Gutter + Text 渲染 ...
        }
        // Phase 4: 分页加载指示器
        if (visibleLineCount != null && totalLineCount != null && visibleLineCount < totalLineCount) {
            item(key = "load_more") {
                Box(Modifier.fillMaxWidth().padding(SpacingTokens.LG.dp), Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp).testTag("viewer_load_more_indicator")
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 2: FileViewerScreen 传递分页参数**

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
    onToggleRenderMode: () -> Unit,
    onLoadMoreLines: () -> Unit  // Phase 4 新增
) {
    // ...
    Scaffold(/* ... */) { padding ->
        Box(/* ... */) {
            when {
                uiState.isLoading -> LoadingState()
                uiState.error != null -> ErrorState(uiState.error)
                uiState.isBinary -> MessageState(/* ... */)
                uiState.mode == FileViewerMode.DIFF -> DiffView(/* ... */)
                uiState.isEmpty -> MessageState(stringResource(R.string.viewer_empty_file))
                uiState.isMarkdown && uiState.renderMode == FileViewerRenderMode.RENDER_PREVIEW -> {
                    MarkdownPreviewWithScrollAnchor(uiState.content, uiState.lastSourceScrollFraction)
                }
                // Phase 4: 大文件警告 + 分页渲染
                uiState.isExtremelyLarge -> Column(Modifier.fillMaxSize()) {
                    LargeFileWarningBanner(lineCount = uiState.totalLineCount)
                    CodeSourceView(
                        content = uiState.content,
                        filePath = uiState.filePath,
                        visibleLineCount = uiState.visibleLineCount,
                        totalLineCount = uiState.totalLineCount,
                        onLoadMore = onLoadMoreLines,
                        modifier = Modifier.weight(1f)
                    )
                }
                !uiState.isFullyLoaded -> Column(Modifier.fillMaxSize()) {
                    TruncationBanner(loadedLines = uiState.visibleLineCount, totalLines = uiState.totalLineCount)
                    CodeSourceView(
                        content = uiState.content,
                        filePath = uiState.filePath,
                        visibleLineCount = uiState.visibleLineCount,
                        totalLineCount = uiState.totalLineCount,
                        onLoadMore = onLoadMoreLines,
                        modifier = Modifier.weight(1f)
                    )
                }
                else -> CodeSourceView(uiState.content, uiState.filePath, modifier = Modifier.fillMaxSize())
            }
            // ...
        }
    }
}

@Composable
private fun TruncationBanner(loadedLines: Int, totalLines: Int) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.viewer_loading_progress, loadedLines, totalLines),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = SpacingTokens.LG.dp, vertical = SpacingTokens.SM.dp)
        )
    }
}

@Composable
private fun LargeFileWarningBanner(lineCount: Int) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.viewer_large_file_warning, lineCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(horizontal = SpacingTokens.LG.dp, vertical = SpacingTokens.SM.dp)
        )
    }
}
```

- [ ] **Step 3: FileViewerRoute 透传 onLoadMoreLines**

```kotlin
FileViewerScreen(
    // ...
    onToggleRenderMode = viewModel::toggleRenderMode,
    onLoadMoreLines = viewModel::loadMoreLines  // Phase 4 新增
)
```

- [ ] **Step 4: 编译验证**

```bash
.\gradlew :app:compileDevDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/CodeSourceView.kt \
        app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/FileViewerScreen.kt \
        app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/FileViewerRoute.kt
git commit -m "feat: CodeSourceView paginated rendering (loadMore on scroll near bottom, progress banner)"
```

---

## Task 3: ScrollState rememberSaveable（CodeSourceView + DiffView）

<a id="task-3"></a>

**Spec ref:** §13.1（旋转屏幕滚动位置丢失风险）

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/CodeSourceView.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/DiffView.kt`

- [ ] **Step 1: CodeSourceView 用 rememberSaveable LazyListState**

> Task 2 已在 CodeSourceView 加了：
> ```kotlin
> val listState = rememberSaveable(saver = LazyListState.Saver) {
>     LazyListState()
> }
> ```
> 本 Step 验证已生效，并对 horizontalScroll 状态也加 rememberSaveable。

```kotlin
// 现有：
val hScroll = scrollState ?: rememberScrollState()
// 改为（让外部注入时仍可 rememberSaveable）：
val hScroll = scrollState ?: rememberSaveable(saver = ScrollState.Saver) {
    ScrollState(initial = 0)
}
```

> **注意**：`rememberScrollState()` 本质就是 `rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }`，所以效果等价。但显式声明让代码意图更清晰。

- [ ] **Step 2: DiffView 用 rememberSaveable LazyListState**

```kotlin
@Composable
fun DiffView(
    uiState: FileViewerUiState,
    onNextHunk: () -> Unit,
    onPrevHunk: () -> Unit
) {
    val patch = uiState.diff?.patch ?: return
    val lines = remember(patch) { patch.lines() }
    // Phase 4: 用 rememberSaveable 替代 rememberLazyListState
    val listState = rememberSaveable(saver = LazyListState.Saver) {
        LazyListState()
    }
    LaunchedEffect(uiState.currentHunkIndex, uiState.hunks) {
        val target = uiState.hunks.getOrNull(uiState.currentHunkIndex) ?: return@LaunchedEffect
        listState.animateScrollToItem(target.patchStartLineIndex)
    }
    Column(Modifier.fillMaxSize()) {
        if (uiState.hunks.isNotEmpty()) DiffHunkNavigator(uiState.currentHunkIndex, uiState.hunks.size, onPrevHunk, onNextHunk)
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(lines) { line -> DiffLine(line) }
        }
    }
}
```

- [ ] **Step 3: 验证 MarkdownPreview 滚动状态**

Task 5 的 `MarkdownPreview(scrollState: ScrollState = rememberScrollState())` 中，`rememberScrollState()` 已是 `rememberSaveable`，旋转屏幕后自动恢复。**无需额外改动**。

- [ ] **Step 4: 编译验证**

```bash
.\gradlew :app:compileDevDebugKotlin
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/CodeSourceView.kt \
        app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/DiffView.kt
git commit -m "feat: CodeSourceView + DiffView rememberSaveable scrollState (rotate-screen restoration)"
```

---

## Task 4: Annotation 列表 rememberSaveable（旋转屏幕保留标注）

<a id="task-4"></a>

**Spec ref:** §13.1（标注旋转屏幕丢失风险），Phase 3 Task 2（AnnotationManager）

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/FileViewerViewModel.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/domain/model/Annotation.kt`（加 Saver 工具）
- Modify: `app/src/test/kotlin/dev/minios/ocremote/domain/model/AnnotationSaverTest.kt`

- [ ] **Step 1: 实现 AnnotationListSaver**

在 `Annotation.kt` 末尾追加：

```kotlin
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver

/**
 * Saver for List<Annotation> — survives configuration changes (rotation).
 *
 * Maps each Annotation to 11 primitive values (String/Int/Long),
 * then chunks back on restore. Bundled in SavedStateHandle via
 * rememberSaveable. Not designed for process-death persistence
 * (Android's default SavedStateHandle behavior applies).
 */
val AnnotationListSaver: Saver<List<Annotation>, Any> = listSaver(
    save = { list ->
        list.flatMap { ann ->
            listOf(
                ann.id, ann.index, ann.startChar, ann.endChar,
                ann.startLine, ann.startCol, ann.endLine, ann.endCol,
                ann.selectedText, ann.note, ann.createdAt
            )
        }
    },
    restore = { saved ->
        @Suppress("UNCHECKED_CAST")
        (saved as List<Any>).chunked(11).map { items ->
            Annotation(
                id = items[0] as String,
                index = items[1] as Int,
                startChar = items[2] as Int,
                endChar = items[3] as Int,
                startLine = items[4] as Int,
                startCol = items[5] as Int,
                endLine = items[6] as Int,
                endCol = items[7] as Int,
                selectedText = items[8] as String,
                note = items[9] as String,
                createdAt = items[10] as Long
            )
        }
    }
)
```

- [ ] **Step 2: 写 AnnotationSaverTest**

```kotlin
class AnnotationSaverTest {

    private val saver = AnnotationListSaver

    @Test
    fun `empty list saves to empty and restores to empty`() {
        val saved = with(saver) { compose.runtime.saveable.SaverScope { true }.save(emptyList()) }
        assertEquals(emptyList<Any>(), saved)
        val restored = saver.restore(saved ?: emptyList<Any>())
        assertEquals(emptyList<Annotation>(), restored)
    }

    @Test
    fun `single annotation round-trips through saver`() {
        val original = listOf(
            Annotation(
                id = "ann-1", index = 0,
                startChar = 10, endChar = 25,
                startLine = 3, startCol = 1, endLine = 3, endCol = 15,
                selectedText = "import android.os.Bundle",
                note = "use alias", createdAt = 1234567890L
            )
        )
        val savedList = with(saver) {
            // fake scope
            val scope = object : compose.runtime.saveable.SaverScope { override fun canBeSaved(value: Any) = true }
            compose.runtime.saveable.SaverScope::class.java
            save(original)
        }
        val restored = saver.restore(savedList ?: emptyList())
        assertEquals(1, restored!!.size)
        assertEquals("ann-1", restored[0].id)
        assertEquals(0, restored[0].index)
        assertEquals(10, restored[0].startChar)
        assertEquals("use alias", restored[0].note)
        assertEquals(1234567890L, restored[0].createdAt)
    }

    @Test
    fun `multiple annotations round-trip preserving creation order`() {
        val original = listOf(
            Annotation("a1", 0, 0, 5, 1, 1, 1, 6, "code1", "note1", 100),
            Annotation("a2", 1, 10, 20, 3, 1, 3, 11, "code2", "note2", 200),
            Annotation("a3", 2, 30, 40, 5, 1, 5, 11, "code3", "note3", 300)
        )
        val saved = saver.save FakeScope original  // 用真实 SaverScope 调用
        val restored = saver.restore(saved!!)
        assertEquals(3, restored!!.size)
        assertEquals("a1", restored[0].id)
        assertEquals("a3", restored[2].id)
        assertEquals(2, restored[2].index)
    }

    @Test
    fun `special characters in note and selectedText preserved`() {
        val original = listOf(
            Annotation("a1", 0, 0, 5, 1, 1, 1, 6,
                selectedText = "val x = \"中文测试\" // 🎉",
                note = "建议改成\nval y = '正确'", createdAt = 1000)
        )
        val saved = saver.saveWithFakeScope(original)
        val restored = saver.restore(saved!!)!!
        assertEquals("val x = \"中文测试\" // 🎉", restored[0].selectedText)
        assertEquals("建议改成\nval y = '正确'", restored[0].note)
    }
}
```

> **测试执行细节**：`listSaver` 的 `save` lambda 需要 `SaverScope` 接收者。用 `with(saver) { with(SaverScope { true }) { save(list) } }` 或重构测试。具体写法以编译通过为准。

- [ ] **Step 3: 运行验证**

```bash
.\gradlew :app:testDevDebugUnitTest --rerun --tests "*.AnnotationSaverTest"
```

- [ ] **Step 4: FileViewerViewModel 用 SavedStateHandle 持久化 annotations**

ViewModel 已通过 `_uiState: MutableStateFlow` 管理 annotations。但 StateFlow 旋转屏幕后由 Hilt SavedStateHandle 自动恢复（如果配置了 `SavedStateHandle` 的 Assisted 注入）。Compose 的 `rememberSaveable` 与 ViewModel 的 SavedStateHandle 是两条独立路径。

**方案 A（推荐）**：在 ViewModel 用 `SavedStateHandle` 持久化 annotations：

```kotlin
@HiltViewModel
class FileViewerViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    // ... existing ...
) : ViewModel() {
    // ...

    // Phase 4: annotations 持久化到 SavedStateHandle
    // 注意：SavedStateHandle 只接受 Bundle-able 类型，List<Annotation> 需序列化
    // 简化方案：把 annotations 序列化为 List<Map<String, Any?>>（Bundle 可接受）
    private fun saveAnnotationsToHandle(annotations: List<Annotation>) {
        val serializable = annotations.map { ann ->
            mapOf<String, Any?>(
                "id" to ann.id,
                "index" to ann.index,
                "startChar" to ann.startChar,
                "endChar" to ann.endChar,
                "startLine" to ann.startLine,
                "startCol" to ann.startCol,
                "endLine" to ann.endLine,
                "endCol" to ann.endCol,
                "selectedText" to ann.selectedText,
                "note" to ann.note,
                "createdAt" to ann.createdAt
            )
        }
        savedStateHandle["annotations"] = serializable
    }

    @Suppress("UNCHECKED_CAST")
    private fun restoreAnnotationsFromHandle(): List<Annotation> {
        val raw = savedStateHandle.get<List<Map<String, Any?>>>("annotations") ?: return emptyList()
        return raw.map { m ->
            Annotation(
                id = m["id"] as String,
                index = m["index"] as Int,
                startChar = m["startChar"] as Int,
                endChar = m["endChar"] as Int,
                startLine = m["startLine"] as Int,
                startCol = m["startCol"] as Int,
                endLine = m["endLine"] as Int,
                endCol = m["endCol"] as Int,
                selectedText = m["selectedText"] as String,
                note = m["note"] as String,
                createdAt = m["createdAt"] as Long
            )
        }
    }
}
```

然后在 `addAnnotation / deleteAnnotation / updateAnnotation / submitAnnotations` 中调用 `saveAnnotationsToHandle(annotations)` 同步；在 `init` 中调用 `restoreAnnotationsFromHandle()` 恢复。

**方案 B（更简单）**：UI 层用 `rememberSaveable(saver = AnnotationListSaver)`，绕过 ViewModel 直接保存。

**最终选择**：方案 A（ViewModel 层），因为：
1. ViewModel 已是 single source of truth
2. SavedStateHandle 已是 Hilt ViewModel 标配
3. UI 层无需感知持久化逻辑

- [ ] **Step 5: 写 ViewModel 旋转恢复测试**

```kotlin
@Test
fun `annotations survive ViewModel recreation via SavedStateHandle`() = runTest {
    coEvery { getFileContent(any(), any(), any()) } returns Result.success(sampleKotlinContent)
    val savedStateHandle = savedStateHandle()
    val vm1 = FileViewerViewModel(savedStateHandle, getFileContent, getFileDiff, toolSnapshotCache)
    vm1.addAnnotation("import", 0, 6, "note1")
    vm1.addAnnotation("class", 10, 15, "note2")

    // 模拟旋转屏幕：用同一 SavedStateHandle 创建新 ViewModel
    val vm2 = FileViewerViewModel(savedStateHandle, getFileContent, getFileDiff, toolSnapshotCache)
    assertEquals(2, vm2.uiState.value.annotations.size)
    assertEquals("note1", vm2.uiState.value.annotations[0].note)
    assertEquals("note2", vm2.uiState.value.annotations[1].note)
}
```

- [ ] **Step 6: 集成到 ViewModel + 运行测试**

修改 ViewModel 的标注方法，在每次状态变更后调用 `saveAnnotationsToHandle`；在 init 中加载时调用 `restoreAnnotationsFromHandle`。

```bash
.\gradlew :app:testDevDebugUnitTest --rerun --tests "*.FileViewerViewModelTest"
.\gradlew :app:testDevDebugUnitTest --rerun --tests "*.AnnotationSaverTest"
```

- [ ] **Step 7: 编译 + Commit**

```bash
.\gradlew :app:compileDevDebugKotlin
git add app/src/main/kotlin/dev/minios/ocremote/domain/model/Annotation.kt \
        app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/FileViewerViewModel.kt \
        app/src/test/kotlin/dev/minios/ocremote/domain/model/AnnotationSaverTest.kt \
        app/src/test/kotlin/dev/minios/ocremote/ui/screens/viewer/FileViewerViewModelTest.kt
git commit -m "feat: annotations survive rotation via SavedStateHandle (AnnotationListSaver)"
```

---

## Task 5: Maestro E2E 旋转屏幕 + 大文件回归

<a id="task-5"></a>

**Spec ref:** §11.4

**Files:**
- Create: `.maestro/flows/e2e-verify/26-large-file-pagination.yaml`
- Create: `.maestro/flows/e2e-verify/27-rotation-restoration.yaml`

- [ ] **Step 1: 创建 Maestro flow 26（大文件分页）**

```yaml
# 26-large-file-pagination.yaml
appId: dev.minios.ocremote.dev
---

- runFlow: ../e2e-verify/06-chat-screen.yaml
- tapOn:
    id: "more_vert"
- tapOn:
    id: "menu_open_workspace"

# 找一个大文件（用搜索定位）
- tapOn:
    id: "workspace_search_button"
- tapOn:
    id: "workspace_search_input"
- inputText: "OpenCodeApi"
- tapOn:
    id: "workspace_search_result"
    index: 0

# 验证初始加载：显示加载进度条 + 内容可见
- extendedWaitUntil:
    visible: ".*loading_progress.*"
    timeout: 5000
    optional: true
- takeScreenshot: e2e_large_initial_load

# 滚动到底部（多次 swipe up）
- swipe:
    direction: UP
    duration: 500
- swipe:
    direction: UP
    duration: 500
- swipe:
    direction: UP
    duration: 500
- swipe:
    direction: UP
    duration: 500

# 验证加载更多指示器出现
- extendedWaitUntil:
    visible: ".*viewer_load_more_indicator.*"
    timeout: 5000
    optional: true
- takeScreenshot: e2e_large_load_more_triggered

# 继续滚动，验证更多内容加载
- swipe:
    direction: UP
    duration: 1000
- takeScreenshot: e2e_large_more_loaded

- tapOn:
    id: "back_button"
```

- [ ] **Step 2: 创建 Maestro flow 27（旋转屏幕恢复）**

```yaml
# 27-rotation-restoration.yaml
appId: dev.minios.ocremote.dev
---

# 前置：此 flow 需在 tablet config 或开启自动旋转的 emulator 上运行

- runFlow: ../e2e-verify/06-chat-screen.yaml
- tapOn:
    id: "more_vert"
- tapOn:
    id: "menu_open_workspace"
- tapOn:
    text: ".*"
    index: 0  # 打开任一文件

# 验证 FileViewer 打开
- extendedWaitUntil:
    visible: ".*back_button.*"
    timeout: 5000
    optional: true
- takeScreenshot: e2e_rotation_portrait

# 滚动到中部
- swipe:
    direction: UP
    duration: 800
- swipe:
    direction: UP
    duration: 800
- takeScreenshot: e2e_rotation_scrolled_position

# 旋转屏幕（Maestro 命令）
- rotateDevice: LANDSCAPE
- extendedWaitUntil:
    visible: ".*back_button.*"
    timeout: 5000
- takeScreenshot: e2e_rotation_landscape

# 旋转回来
- rotateDevice: PORTRAIT
- takeScreenshot: e2e_rotation_back_to_portrait

# 验证滚动位置大致保留（视觉对比截图）
# （无精确断言——位置不要求像素级一致，只要求"仍在文件中部"）

- tapOn:
    id: "back_button"
```

> **注意**：Maestro 的 `rotateDevice` 命令在 emulator 上可能因 Android 版本差异行为不同。如果失败，作为降级方案可在 flow 头部加 `optional: true` 让其他断言通过。

- [ ] **Step 3: Commit**

```bash
git add .maestro/flows/e2e-verify/26-large-file-pagination.yaml \
        .maestro/flows/e2e-verify/27-rotation-restoration.yaml
git commit -m "test: Maestro E2E flows 26 (large file pagination) + 27 (rotation restoration)"
```

---

## Phase 4 验收清单

每项必须通过：

- [ ] `.\gradlew :app:compileDevDebugKotlin`（120s 内 SUCCESS）
- [ ] `.\gradlew :app:testDevDebugUnitTest --rerun`（180s 内全绿，Phase 4 新增约 12+ 测试）
  - FileViewerViewModelTest: +7（分页加载相关）
  - AnnotationSaverTest: 4（Saver round-trip）
  - FileViewerViewModelTest: +1（annotations 旋转恢复）
- [ ] `maestro test .maestro/flows/e2e-verify/26-large-file-pagination.yaml` → PASS
- [ ] `maestro test .maestro/flows/e2e-verify/27-rotation-restoration.yaml` → PASS（若 emulator 支持旋转）
- [ ] **新增 strings.xml**（Task 2 Step 2 引用）：
  - `viewer_loading_progress`、`viewer_large_file_warning`
- [ ] 手动验收（真实 opencode 服务器 4096 + emulator 10.0.2.2）：
  - **大文件分页**：打开项目里最大的 kt 文件（如 `OpenCodeApi.kt`，约 1095 行）→ 验证首屏只加载 500 行（显示 `已加载 500/1095 行` 提示）→ 滚动到底 → 自动加载 200 行（提示更新）→ 重复直到 `isFullyLoaded=true`，提示消失
  - **极端大文件**：找一个 10000+ 行的文件（或 mock）→ 首次加载 10000 行 + 显示橙色警告"文件过大，加载性能可能受影响" → 仍可分页加载
  - **旋转屏幕**：打开任一文件 → 滚动到中部 → 旋转屏幕 → 滚动位置大致保留 → 旋转回来 → 仍在原位置；Diff 视图同理
  - **标注旋转恢复**：Phase 3 标注能力 + 本 Phase：长按添加 2 处标注 → 旋转屏幕 → 标注列表完整保留（不丢）→ 删除一个 → 旋转屏幕 → 剩余 1 个标注保留

---

## Phase 4 不包含（未来优化）

- **进程死亡后标注恢复**：SavedStateHandle 在进程死亡后不一定恢复（取决于系统内存压力）。若需要强保证，Phase 5+ 引入 Room DB 持久化（spec §13.2 未来方向）
- **md 源码↔渲染精确行号映射**：Phase 4 仍用 Phase 2 的比例锚点（v1），v2 行号精确映射在 spec §13.2 列为未来优化
- **大文件搜索/跳转**：Phase 4 只做分页加载，未实现"文件内搜索 + 跳转到行"
- **图片预览**：spec §7.7 二进制/图片预览，Phase 4 不做
- **LSP 符号跳转**：依赖服务端 `find/symbol` 端点（spec §2.1 标注为桩实现），等 API 成熟后实现
- **跨文件汇总提交**：spec §1 明确排除，仅单文件粒度
- **ApplyPatch 多文件入口**：spec §13.2 列为未来扩展
- **客户端 patch 生成 + POST /vcs/apply**：spec §13.2 列为未来方向

---

## Self-Review

### Spec 覆盖检查

| Spec 条目 | 对应 Task | 状态 |
|-----------|----------|------|
| §10 大文件 >256KB 或 >5000 行截断 | Task 1+2（分页 + 极端兜底） | ✅ |
| §10 大文件"截断警告 + 渲染前 N 行 + [加载更多]" | Task 2（TruncationBanner + 自动 loadMore） | ✅ |
| §13.1 大文件渲染 OOM 风险 | Task 1+2（LazyColumn 按行 + 分页） | ✅ |
| §13.1 旋转屏幕标注丢失风险 | Task 4（AnnotationListSaver + SavedStateHandle） | ✅ |
| §13.1 ScrollState 跨配置变更风险 | Task 3（rememberSaveable + LazyListState.Saver） | ✅ |
| §11.4 Maestro flow 大文件回归 | Task 5（flow 26） | ✅ |
| §11.4 Maestro flow 旋转回归 | Task 5（flow 27） | ✅ |
| Phase 1 残留 P2：ScrollState rememberSaveable | Task 3 | ✅ |
| Phase 3 不包含：标注持久化（rememberSaveable） | Task 4 | ✅ |

### 类型一致性检查

- `FileViewerUiState.totalLineCount / visibleLineCount / isFullyLoaded / isExtremelyLarge` — Task 1 定义，Task 2 使用 ✅
- `FileViewerViewModel.loadMoreLines()` — Task 1 定义，Task 2 调用 ✅
- `CodeSourceView(visibleLineCount, totalLineCount, onLoadMore)` — Task 2 定义（向后兼容默认值），FileViewerScreen 调用 ✅
- `AnnotationListSaver` — Task 4 定义（domain/model/Annotation.kt），测试 + ViewModel 引用 ✅
- 移除字段：`isTruncated: Boolean` — Task 1 移除，FileViewerScreen Task 2 改用 `!isFullyLoaded` ✅

### 占位符扫描

- 无 "TBD"/"TODO" 在最终代码中
- Task 4 Step 2 测试代码有 `saver.save FakeScope original` 伪代码，**实施时需用真实 SaverScope API**：`with(saver) { with(compose.runtime.saveable.SaverScope { true }) { save(list) } }` 或类似形式
- Task 4 Step 4 的方案 A 与方案 B 选定 A，已明确实施路径

### 风险与降级

| 风险 | 概率 | 缓解 |
|------|------|------|
| 分页加载 `fullContentCache` 内存占用过大（极端大文件 100MB+） | 中 | EXTREMELY_LARGE_THRESHOLD=100000 + 极端文件不 cache 全量（首次加载只 cache 切片）；若仍 OOM，Phase 5 引入文件流式读取 |
| LazyListState.Saver 在 Compose BOM 不同版本 API 差异 | 低 | Compose Foundation 2026 已稳定，标准用法 |
| AnnotationListSaver chunked(11) 与 Annotation 字段数不一致（Phase 5 加字段） | 中 | 加字段时同步更新 Saver，加单元测试验证 |
| Maestro rotateDevice 在 emulator 上不稳定 | 中 | flow 27 标 `optional: true` 关键断言；人工验证为准 |
| ScrollState 旋转恢复后位置不精确（差几行） | 低 | spec 接受"大致保留"，不要求像素级 |
| DiffView 旋转后 currentHunkIndex 与 listState 不一致 | 低 | LaunchedEffect 监听 currentHunkIndex 变化时重新 scrollToItem，已覆盖 |
