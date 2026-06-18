# Workspace File Viewer — Phase 1 实现计划 v2（基础设施 + 入口2/3 只读查看）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`).
>
> **本计划经 doc-consistency-review 9 维度审查后重写**，修正了 v1 的 5 个 P0 + 55 个 P1 问题。审查报告：`docs/superpowers/review/doc-review-report.md`。关键架构修正已**内联到各 Task**（不再放手尾附录）。

**Goal:** 搭建工作空间 Data/Domain/UI 全栈地基，实现入口2（文件树浏览）和入口3（Git 变更查看），FileViewer 支持源码视图和 Diff 视图（不含标注/md 预览/搜索——Phase 2/3）。

**Architecture:** Clean Architecture 三层。**复用现有** `OpenCodeApi.listDirectory/readFile/findFiles`（补 directory 参数），新增 VCS 端点。Repository 用 `serverId + directory` 模式（与现有 SessionRepositoryImpl 一致），Impl 注入 `ServerRepository` 复用 `resolveConnection`。新建 `VcsFileDiff` domain model（既有 `FileDiff` 是 SSE before/after 格式，与 `GET /vcs/diff` 的 patch 格式不兼容）。

**Tech Stack:** Kotlin + Compose + Hilt(KSP) + Ktor + MockK 1.14.9 + Turbine 1.2.1。JDK 21。

---

## TOC

- [Global Constraints](#global-constraints) ← **执行前必读**
- [关键架构决策](#关键架构决策已修正) ← **执行前必读**（含 serverId/directory/VcsFileDiff）
- Task 1: [DTO 重命名 + VCS DTO](#task-1)
- Task 2: [Domain Model（含 VcsFileDiff）](#task-2)
- Task 3: [Mapper（FileDiffDto→VcsFileDiff）](#task-3)
- Task 4: [OpenCodeApi（补 readFile.directory + 新增 VCS）](#task-4)
- Task 5: [FileRepository（含 resolveConnection 前置）](#task-5)
- Task 6: [VcsRepository](#task-6)
- Task 7: [DI 绑定](#task-7)
- Task 8: [UseCase（4 个）](#task-8)
- Task 9: [路由（WorkspaceNav + FileViewerNav，含 directory）](#task-9)
- Task 10: [WorkspaceViewModel](#task-10)
- Task 11: [FileViewerViewModel + DiffParser](#task-11)
- Task 12: [WorkspaceScreen + TopBar](#task-12)
- Task 13: [FileTreePanel](#task-13)
- Task 14: [GitChangesPanel](#task-14)
- Task 15: [FileViewerScreen + CodeSourceView](#task-15)
- Task 16: [DiffView（Hunk 导航）](#task-16)
- Task 17: [入口2 — ChatTopBar 菜单](#task-17)
- Task 18: [Maestro E2E + testTag](#task-18)
- [Phase 1 验收清单](#phase-1-验收清单)

---

## Global Constraints

> ⚠️ 所有 Task 隐含遵守。执行前必读。

### 路径与包名

- 源码：`app/src/main/kotlin/`（非 `java/`）；测试：`app/src/test/kotlin/`；androidTest：`app/src/androidTest/kotlin/`
- 包名前缀：`dev.minios.ocremote.`
- 资源：`app/src/main/res/values/strings.xml` + 15 个 `values-*` locale

### 构建环境（D6 修正：前置依赖必须显式化）

- **JDK 21 必需**：`java -version` 输出 21。`gradle.properties:20` 已硬编码 `org.gradle.java.home`（更换机器需更新此路径）。`app/build.gradle.kts:81-86` 用 `jvmToolchain(21)` + `VERSION_21`。
- **SDK 矩阵**：compileSdk=36 / minSdk=26 / targetSdk=35；Compose BOM 2026.05.01；测试 MockK 1.14.9 + Turbine 1.2.1（已配，无需改）
- **Gradle 命令必须带 flavor**：`compileDevDebugKotlin`（编译，120s）、`testDevDebugUnitTest --rerun`（单测，180s）
- **代理**：`gradle.properties:23-26` 硬编码 127.0.0.1:7897。若代理不可达，注释这 4 行 `systemProp.*` 即可无代理构建。CI 默认无代理。
- **Gradle daemon 卡死兜底**：`gradle.properties` 已设 `org.gradle.daemon=false`。若命令在 BUILD SUCCESSFUL 后不返回，跑 `.\gradlew --stop` 清理残留进程再重试。

### 真实服务器与 emulator（D6 修正）

- **opencode 服务器**：宿主机端口 4096；用户名 `opencode`；密码从环境变量读（PowerShell `$env:OPENCODE_SERVER_PASSWORD`，bash `$OPENCODE_SERVER_PASSWORD`）
- **Basic Auth 鉴权**：`Authorization: Basic base64(user:pass)`。`ServerConnection.from(url, user, pass)` 内部生成此头
- **emulator 主机回环**：emulator 内访问宿主机必须用 `10.0.2.2`（不能用 localhost/127.0.0.1）。App 内配置服务器 URL 应为 `http://10.0.2.2:4096`
- **Maestro CLI**：E2E 测试工具。安装见 https://docs.maestro.mobile.dev/。需可执行 `maestro --version`。Task 18 前确认已安装 + emulator 已启动 + dev flavor APK 已安装

### 项目规范

- **Material 3 First**：用 `MaterialTheme.colorScheme` 语义色，不自定义 Canvas
- **Alpha tokens**：`dev.minios.ocremote.ui.theme.AlphaTokens`（SELECTED=0.12 / DIFF_BG=0.10 / FAINT=0.35 / MUTED=0.50 / MEDIUM=0.70 / HIGH=0.80 / AMOLED=0.92）
- **Spacing tokens**：`SpacingTokens.XS(4)/SM(8)/MD(12)/LG(16)/XL(24)/XXL(32).dp`
- **Shape tokens**：`ShapeTokens.none/extraSmall/smallMedium/small/mediumSmall/medium/large/largeMedium/extraLarge`（⚠️ **无 XS/SM/MD 简写**，D3-004 修正）
- **颜色**：`DiffAdded`(0xFF4CAF50 绿) / `DiffRemoved`(0xFFE53935 红) 是仅有的 Diff 色（⚠️ **无 DiffAddedBg/DiffAddedFg**，用 `.copy(alpha=AlphaTokens.DIFF_BG)` 派生，D3-005 修正）
- **Hilt**：KSP（非 kapt）；`@Binds abstract fun` 模式
- **路由**：参照 `ChatNav.kt`，String 参数必须 `URLEncoder.encode(...,"UTF-8")` / `URLDecoder.decode`
- **测试 `isReturnDefaultValues=true`**：MockK 必须**显式 `coEvery/coAnswers`**，禁止依赖 mock 默认值
- **真实样本**：测试用项目真实代码（如 `OpenCodeApi.kt` 片段、真实 git diff），禁用 `"val x = 1"`/`"aaa"`/`"bbb"` 占位（D7-003 修正）
- **ChatScreen.kt 编辑协议**（Task 17 遵守）：不并行编辑、Read-before-Edit、每次 Edit 后 `compileDevDebugKotlin`、commit；失败 `git checkout` 重试。⚠️ `docs/chatscreen-editing-protocol.md` 示例命令 `compileDebugKotlin` 过时，本 plan 用 `compileDevDebugKotlin`
- **lokit**：本地化同步 CLI（config: `lokit.yaml`），同步命令 `lokit`（无参数）。不可用时降级手动复制 strings.xml

### testTag 约定（D6-009/D7-001 修正：跨 Task 依赖清单）

Task 18 Maestro flow 依赖以下 testTag，**必须在对应 Task 的 composable 中用 `Modifier.testTag(...)` 添加**：

| testTag | 添加位置（Task） | 用途 |
|---------|-----------------|------|
| `more_vert` | Task 17（ChatTopBar MoreVert IconButton） | 入口2 起点 |
| `menu_open_workspace` | Task 17（DropdownMenuItem） | 入口2 菜单项 |
| `panel_file_tree` | Task 12（WorkspaceScreen 📁 IconButton） | 面板切换 |
| `panel_git_changes` | Task 12（WorkspaceScreen 🔀 IconButton） | 面板切换 |
| `back_button` | Task 12/15（TopBar 返回 IconButton） | 返回导航 |

---

## 关键架构决策（已修正）

> ⚠️ 本节是 v1 plan 经审查后的**正确版本**，取代 v1 的 conn 模式。

### 1. Repository 用 `serverId + directory`，不是 `conn`（D2-001/D3-001 修正）

**现有模式**（`SessionRepositoryImpl`/`ServerRepositoryImpl`）：接口方法接收 `serverId: String`，Impl 内部 `resolveConnection(serverId)` 获取 `ServerConnection`。

**directory 参数**：OpenCode 是多项目架构，`GET /file`、`/file/content`、`/find/file`、`/vcs/*` 均需 `x-opencode-directory` header 路由到正确实例。现有 `OpenCodeApi.listDirectory(conn, path, directory)` 已通过 `directoryHeader(directory)` 传递。

**正确签名**：
```kotlin
// Repository 接口
suspend fun listDirectory(serverId: String, directory: String, path: String): Result<List<FileNode>>
suspend fun getFileContent(serverId: String, directory: String, path: String): Result<FileContent>

// UseCase
suspend operator fun invoke(serverId: String, directory: String, path: String): Result<List<FileNode>>

// ViewModel（从 SavedStateHandle 读 serverId + directory）
private val serverId = savedStateHandle.get<String>(ServerRouteParams.PARAM_SERVER_ID).orEmpty()
private val directory = URLDecoder.decode(savedStateHandle.get<String>(WorkspaceNav.PARAM_DIRECTORY).orEmpty(), "UTF-8")
```

### 2. 复用现有 OpenCodeApi 方法（D3-006 修正）

现有 `OpenCodeApi` 已有（line 974-1022）：
- `listDirectory(conn, path, directory): List<FileNode>` ← 复用
- `findFiles(conn, query, type, directory, limit, dirs): List<String>` ← 复用（Phase 2 用）
- `readFile(conn, path): FileContent` ← **需补 directory 参数**
- `searchText(conn, pattern): List<SearchMatch>` ← 复用

**Task 4 只做**：给 `readFile` 补 directory 参数 + 新增 3 个 VCS 方法。不重写 listFiles/findFiles。

### 3. 新建 VcsFileDiff（D2-002/D3 修正）

既有 `FileDiff`（`domain/model/SseEvent.kt:258`）字段是 `{file, before, after, additions, deletions, status}`（SSE 事件用）。
`GET /vcs/diff` 返回 `{file, patch, additions, deletions, status}`（含 unified diff patch 字符串）。
**两者格式不兼容**，必须新建 `VcsFileDiff`。

### 4. resolveConnection 提升为公共方法（D6-007 修正）

`ServerRepositoryImpl:198` 的 `private suspend fun resolveConnection(serverId)` 需提升为 `ServerRepository` 接口的公共方法，让 FileRepositoryImpl/VcsRepositoryImpl 复用。**Task 5 Step 1 是前置 mini-task**。

### 5. Highlights 库显式依赖（D4-001/D6-008 修正）

`dev.snipme:highlights` 当前是经 `com.mikepenz:multiplatform-markdown-renderer-code` 的 transitive 依赖。Task 15 需在 `app/build.gradle.kts` 显式声明直接依赖（版本对齐 mikepenz-code 引入的版本，用 `.\gradlew :app:dependencies` 查询）。

API：`Highlights.Builder().code(content).language(lang).build()` → `highlight.getHighlights(): List<CodeHighlight>`，子类 `ColorHighlight`（含 `color: Int` + `location: Pair<Int,Int>`）和 `BoldHighlight`（仅 `location`）。参考 `MarkdownContent.kt:226` 和 `OcCodeBlock.kt:27` 的现有用法。

---

## Task 1: DTO 重命名 + VCS DTO

<a id="task-1"></a>

**Spec ref:** §8.1, §8.3

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/data/dto/response/FileResponses.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/data/api/OpenCodeApi.kt`（同步重命名引用）

**Interfaces:**
- Produces: `FileNodeDto`/`FileContentDto`/`SearchMatchDto`（重命名）+ `VcsChangeDto`/`VcsBranchDto`/`FileDiffDto`（新增）

- [ ] **Step 1: grep 现有引用**

`grep -rn "\bFileNode\b\|\bFileContent\b\|\bSearchMatch\b" app/src/main --include="*.kt"`
预期：`OpenCodeApi.kt` 的 `listDirectory`/`readFile`/`findFiles`/`searchText` 返回类型引用这些 DTO。

- [ ] **Step 2: 重命名 + 补充 VCS DTO + @SerialName 修正**

`FileResponses.kt` 改为：
```kotlin
package dev.minios.ocremote.data.dto.response

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class SearchMatchDto(  // ⚠️ 字段与 API 不完全匹配（API 是 path:{text}, line_number），Phase 1 不用 /find 端点，保留现状加注释
    val path: String,
    val lines: String,
    val lineNumber: Int,
    val absoluteOffset: Int
)

@Serializable
data class FileContentDto(
    val type: String,           // "text" | "binary"
    val content: String,
    val diff: String? = null,   // D3-003 修正：补 diff 字段
    val patch: JsonElement? = null,  // D3-003：补 patch 字段（结构化对象）
    val encoding: String? = null,
    val mimeType: String? = null
)

@Serializable
data class FileNodeDto(
    val name: String,
    val path: String,
    val type: String,
    val absolute: String? = null,
    val ignored: Boolean = false,
    val size: Long? = null,
    val modified: Long? = null
)

@Serializable
data class ServerPaths(
    val home: String = "", val state: String = "", val config: String = "",
    val worktree: String = "", val directory: String = ""
)

// ============ VCS DTOs ============

@Serializable
data class VcsChangeDto(
    val file: String,
    val additions: Int = 0,
    val deletions: Int = 0,
    val status: String? = null
)

@Serializable
data class VcsBranchDto(
    val branch: String? = null,
    @SerialName("default_branch") val defaultBranch: String? = null  // D3-002 修正：API 返回 snake_case
)

@Serializable
data class FileDiffDto(
    val file: String? = null,
    val patch: String? = null,
    val additions: Int = 0,
    val deletions: Int = 0,
    val status: String? = null
)
```
加 `import kotlinx.serialization.json.JsonElement`。

- [ ] **Step 3: 修复 OpenCodeApi 引用**

`OpenCodeApi.kt` 中 `readFile` 返回 `FileContent` → 改 `FileContentDto`；`listDirectory` 返回 `List<FileNode>` → 改 `List<FileNodeDto>`；`findFiles` 返回不变（`List<String>`）；`searchText` 返回 `List<SearchMatch>` → 改 `List<SearchMatchDto>`。

- [ ] **Step 4: 编译验证**

`.\gradlew :app:compileDevDebugKotlin` → BUILD SUCCESSFUL

- [ ] **Step 5: 强验收（D7-011 修正）**

`grep -rn "\bFileNode\b\|\bFileContent\b\|\bSearchMatch\b" app/src/main --include="*.kt" | findstr /v Dto`
预期：无输出（所有引用已加 Dto 后缀）。注：domain/model/ 的 domain 类在 Task 2 创建。

- [ ] **Step 6: Commit**

`git add -A && git commit -m "refactor: rename File DTOs to *Dto, add VCS DTOs with @SerialName fix"`

---

## Task 2: Domain Model（含 VcsFileDiff）

<a id="task-2"></a>

**Spec ref:** §8.1

**Files:**
- Create: `domain/model/FileNode.kt`、`domain/model/FileContent.kt`、`domain/model/Vcs.kt`、`domain/model/VcsFileDiff.kt`

**Interfaces:**
- Produces: `FileNode`/`FileType`/`FileContent`/`ContentType`/`VcsChange`/`VcsStatus`/`VcsBranchInfo`/`VcsDiffMode`/`VcsFileDiff`

- [ ] **Step 1: 创建 domain model 文件**

`domain/model/FileNode.kt`:
```kotlin
package dev.minios.ocremote.domain.model
data class FileNode(val name: String, val path: String, val absolute: String,
    val type: FileType, val ignored: Boolean, val size: Long? = null, val modified: Long? = null)
enum class FileType { FILE, DIRECTORY }
fun FileNode.isDirectory() = type == FileType.DIRECTORY
```

`domain/model/FileContent.kt`:
```kotlin
package dev.minios.ocremote.domain.model
data class FileContent(val path: String, val type: ContentType, val content: String, val mimeType: String? = null)
enum class ContentType { TEXT, BINARY }
```

`domain/model/Vcs.kt`:
```kotlin
package dev.minios.ocremote.domain.model
data class VcsChange(val file: String, val additions: Int, val deletions: Int, val status: VcsStatus)
enum class VcsStatus { ADDED, DELETED, MODIFIED }
data class VcsBranchInfo(val branch: String?, val defaultBranch: String?)
enum class VcsDiffMode(val apiValue: String) { GIT("git"), BRANCH("branch") }
```

`domain/model/VcsFileDiff.kt`（⚠️ **新建，不复用 FileDiff**，D2-002 修正）:
```kotlin
package dev.minios.ocremote.domain.model
/** GET /vcs/diff 返回项。与既有 FileDiff(before/after, SSE 用) 不同——本类含 unified diff patch。 */
data class VcsFileDiff(
    val file: String,
    val patch: String?,
    val additions: Int,
    val deletions: Int,
    val status: VcsStatus?
)
```

- [ ] **Step 2: 编译 + Commit**

`.\gradlew :app:compileDevDebugKotlin` → SUCCESS
`git add -A && git commit -m "feat: add domain models incl. VcsFileDiff (distinct from SSE FileDiff)"`

---

## Task 3: Mapper（FileDiffDto→VcsFileDiff）

<a id="task-3"></a>

**Spec ref:** §8.1

**Files:**
- Create: `data/mapper/FileMapper.kt`、`data/mapper/VcsMapper.kt`
- Test: `test/.../data/mapper/FileMapperTest.kt`、`test/.../data/mapper/VcsMapperTest.kt`

- [ ] **Step 1: 写 FileMapper 测试（真实样本，D7-003 修正）**

用项目真实代码片段做测试数据（如 `OpenCodeApi.kt` 的前几行）。测试用例：
1. `FileNodeDto type=file → FileType.FILE`（数据：真实 `{"name":"OpenCodeApi.kt","path":"data/api/OpenCodeApi.kt","type":"file",...}`）
2. `FileNodeDto type=directory → DIRECTORY`
3. `FileNodeDto type=unknown → FILE + 日志`
4. `FileNodeDto absolute=null → ""`
5. `FileContentDto type=text → TEXT`（数据：`{"type":"text","content":"package dev.minios..."}`）
6. `FileContentDto type=binary → BINARY + mimeType`

- [ ] **Step 2: 运行验证失败** → `testDevDebugUnitTest --rerun --tests "*.FileMapperTest"` → FAIL

- [ ] **Step 3: 实现 FileMapper**

```kotlin
package dev.minios.ocremote.data.mapper
// imports...
fun FileNodeDto.toDomain(): FileNode = FileNode(name, path, absolute ?: "",
    type = when(type) { "directory" -> FileType.DIRECTORY; "file" -> FileType.FILE
        else -> { Log.w("FileMapper","Unknown type='$type'"); FileType.FILE } },
    ignored = ignored, size = size, modified = modified)

fun FileContentDto.toDomain(path: String): FileContent = FileContent(path,
    type = when(type) { "binary" -> ContentType.BINARY; "text" -> ContentType.TEXT
        else -> { Log.w("FileMapper","Unknown type='$type'"); ContentType.TEXT } },
    content = content, mimeType = mimeType)
```

- [ ] **Step 4: 运行验证通过** → `PASS（6 tests）`

- [ ] **Step 5: 写 VcsMapper 测试 + 实现**

测试用例（6 个）：VcsChangeDto added/deleted/modified/null→MODIFIED；VcsBranchDto 保 null；**FileDiffDto → VcsFileDiff**（验证 patch 字段保留）。

```kotlin
fun VcsChangeDto.toDomain(): VcsChange = VcsChange(file, additions, deletions, parseStatus(status))
fun VcsBranchDto.toDomain(): VcsBranchInfo = VcsBranchInfo(branch, defaultBranch)
fun FileDiffDto.toDomain(): VcsFileDiff = VcsFileDiff(file ?: "", patch, additions, deletions,
    status?.let { parseStatus(it) })  // ⚠️ 返回 VcsFileDiff，不是 FileDiff（D2-002 修正）
private fun parseStatus(s: String?): VcsStatus = when(s) {
    "added" -> VcsStatus.ADDED; "deleted" -> VcsStatus.DELETED; "modified" -> VcsStatus.MODIFIED
    null -> VcsStatus.MODIFIED; else -> VcsStatus.MODIFIED }
```

- [ ] **Step 6: 运行 + Commit** → `PASS（6 tests）`
`git commit -m "feat: add File/Vcs mappers (FileDiffDto→VcsFileDiff)"`

---

## Task 4: OpenCodeApi（补 readFile.directory + 新增 VCS）

<a id="task-4"></a>

**Spec ref:** §8.3 | **复用现有**（D3-006 修正）

**Files:**
- Modify: `data/api/OpenCodeApi.kt`
- Test: `test/.../data/api/OpenCodeApiVcsTest.kt`

> ⚠️ **不重写 listDirectory/findFiles**（已有 directory 参数）。只补 readFile.directory + 新增 VCS。

- [ ] **Step 1: 给 readFile 补 directory 参数**

现有（line 1003）：
```kotlin
suspend fun readFile(conn: ServerConnection, path: String): FileContentDto {
    return httpClient.get("${conn.baseUrl}/file/content") {
        conn.authHeader?.let { header("Authorization", it) }
        parameter("path", path)
    }.body()
}
```
改为加 `directory: String? = null` + `directoryHeader(directory)`：
```kotlin
suspend fun readFile(conn: ServerConnection, path: String, directory: String? = null): FileContentDto {
    return httpClient.get("${conn.baseUrl}/file/content") {
        conn.authHeader?.let { header("Authorization", it) }
        directoryHeader(directory)
        parameter("path", path)
    }.body()
}
```

- [ ] **Step 2: 新增 3 个 VCS 方法**（这些不存在，需新增）

在 OpenCodeApi 类内新增：
```kotlin
// ============ VCS ============
suspend fun getVcs(conn: ServerConnection, directory: String? = null): VcsBranchDto {
    return httpClient.get("${conn.baseUrl}/vcs") {
        conn.authHeader?.let { header("Authorization", it) }
        directoryHeader(directory)
    }.body()
}
suspend fun getVcsStatus(conn: ServerConnection, directory: String? = null): List<VcsChangeDto> {
    return httpClient.get("${conn.baseUrl}/vcs/status") {
        conn.authHeader?.let { header("Authorization", it) }
        directoryHeader(directory)
    }.body()
}
suspend fun getVcsDiff(conn: ServerConnection, mode: String, context: Int = 3, directory: String? = null): List<FileDiffDto> {
    return httpClient.get("${conn.baseUrl}/vcs/diff") {
        conn.authHeader?.let { header("Authorization", it) }
        directoryHeader(directory)
        parameter("mode", mode); parameter("context", context)
    }.body()
}
```

- [ ] **Step 3: 写 VCS API 测试（MockEngine + 真实 JSON 样本）**

测试用例（3 个）：
1. `getVcsStatus parses 3 changes (added/modified/deleted)` — 输入真实风格 JSON
2. `getVcsDiff passes mode=git and directory header` — 验证 query + header
3. `getVcs parses branch with default_branch` — 验证 @SerialName 生效

用 MockEngine 工厂（参照现有测试模式），`ServerConnection.from("http://localhost:4096","opencode","pass")` 构造 conn（D3-007 修正：两字段）。

- [ ] **Step 4: 运行 + Commit** → `PASS（3 tests）`
`git commit -m "feat: add directory to readFile, new VCS endpoints (branch/status/diff)"`

---

## Task 5: FileRepository（含 resolveConnection 前置）

<a id="task-5"></a>

**Spec ref:** §8.2 | ⚠️ **Step 1 是前置 mini-task**（D6-007 修正）

**Files:**
- Modify: `domain/repository/ServerRepository.kt`（接口加 resolveConnection）
- Modify: `data/repository/ServerRepositoryImpl.kt`（private → override）
- Create: `domain/repository/FileRepository.kt`、`data/repository/FileRepositoryImpl.kt`
- Modify: `di/DomainModule.kt`
- Test: `test/.../data/repository/FileRepositoryImplTest.kt`

- [ ] **Step 1: 前置 — resolveConnection 提升为公共方法**

`ServerRepository.kt` 接口加：
```kotlin
suspend fun resolveConnection(serverId: String): ServerConnection
```
`ServerRepositoryImpl.kt` 把 `private suspend fun resolveConnection` 改为 `override suspend fun resolveConnection`（去掉 private，加 override）。
`grep -rn "resolveConnection" app/src/main --include="*.kt"` 确认无破坏。
`compileDevDebugKotlin` → SUCCESS。
`git commit -m "refactor: expose resolveConnection as public ServerRepository method"`

- [ ] **Step 2: 写 FileRepository 接口（serverId + directory）**

```kotlin
// domain/repository/FileRepository.kt
interface FileRepository {
    suspend fun listDirectory(serverId: String, directory: String, path: String): Result<List<FileNode>>
    suspend fun getFileContent(serverId: String, directory: String, path: String): Result<FileContent>
}
```

- [ ] **Step 3: 写 Impl 测试（MockK，真实数据）**

测试用例（4 个）：
1. `listDirectory success maps DTOs + passes directory` — 验证调 `api.listDirectory(conn, path, directory)` 三个参数
2. `listDirectory wraps exception as failure`
3. `getFileContent success injects path`
4. `getFileContent wraps exception`

Mock `serverRepository.resolveConnection(serverId)` 返回固定 conn（用 `ServerConnection.from(...)`）；Mock `api.listDirectory(conn, any(), any())` 返回真实风格 List<FileNodeDto>。

- [ ] **Step 4: 实现 FileRepositoryImpl**

```kotlin
@Singleton
class FileRepositoryImpl @Inject constructor(
    private val api: OpenCodeApi,
    private val serverRepository: ServerRepository  // 复用 resolveConnection
) : FileRepository {
    override suspend fun listDirectory(serverId: String, directory: String, path: String): Result<List<FileNode>> =
        runCatching { val conn = serverRepository.resolveConnection(serverId)
            api.listDirectory(conn, path, directory).map { it.toDomain() } }
    override suspend fun getFileContent(serverId: String, directory: String, path: String): Result<FileContent> =
        runCatching { val conn = serverRepository.resolveConnection(serverId)
            api.readFile(conn, path, directory).toDomain(path) }
}
```

- [ ] **Step 5: DI 绑定 + 运行测试 + Commit**

`DomainModule` 加：`@Binds abstract fun bindFileRepository(impl: FileRepositoryImpl): FileRepository`
→ `PASS（4 tests）`
`git commit -m "feat: FileRepository (serverId+directory mode, reuses resolveConnection)"`

---

## Task 6: VcsRepository

<a id="task-6"></a>

**Spec ref:** §8.2

**Files:** Create `domain/repository/VcsRepository.kt`、`data/repository/VcsRepositoryImpl.kt`；Modify `DomainModule.kt`；Test `VcsRepositoryImplTest.kt`

- [ ] **Step 1: 接口**

```kotlin
interface VcsRepository {
    suspend fun getBranch(serverId: String, directory: String): Result<VcsBranchInfo>
    suspend fun getStatus(serverId: String, directory: String): Result<List<VcsChange>>
    suspend fun getDiff(serverId: String, directory: String, mode: VcsDiffMode, context: Int = 3): Result<List<VcsFileDiff>>  // ⚠️ VcsFileDiff 不是 FileDiff
}
```

- [ ] **Step 2: 测试（4 个）** — getBranch/getStatus/getDiff success + getStatus failure

- [ ] **Step 3: 实现**

```kotlin
@Singleton
class VcsRepositoryImpl @Inject constructor(private val api: OpenCodeApi, private val serverRepository: ServerRepository) : VcsRepository {
    override suspend fun getBranch(serverId: String, directory: String) = runCatching {
        val conn = serverRepository.resolveConnection(serverId); api.getVcs(conn, directory).toDomain() }
    override suspend fun getStatus(serverId: String, directory: String) = runCatching {
        val conn = serverRepository.resolveConnection(serverId); api.getVcsStatus(conn, directory).map { it.toDomain() } }
    override suspend fun getDiff(serverId: String, directory: String, mode: VcsDiffMode, context: Int) = runCatching {
        val conn = serverRepository.resolveConnection(serverId); api.getVcsDiff(conn, mode.apiValue, context, directory).map { it.toDomain() } }
}
```

- [ ] **Step 4: DI + 测试 + Commit** → `PASS（4 tests）`
`git commit -m "feat: VcsRepository (serverId+directory, returns VcsFileDiff)"`

---

## Task 7: DI 绑定

<a id="task-7"></a>

**Files:** Modify `di/DomainModule.kt`

- [ ] **Step 1: 确认 Task 5/6 已加绑定**

`grep "bindFileRepository\|bindVcsRepository" di/DomainModule.kt` → 应有 2 行。

- [ ] **Step 2: 编译 + Commit**（若无改动跳过 commit）

---

## Task 8: UseCase（4 个）

<a id="task-8"></a>

**Spec ref:** §8.5

**Files:** Create 4 个 UseCase + Test `WorkspaceUseCasesTest.kt`

- [ ] **Step 1: 测试（4 个，验证委托）**

每个 UseCase 测试：调用 → 验证 Repository 同参数被调 → 返回 Repository 结果。

- [ ] **Step 2: 实现 4 个 UseCase**

```kotlin
class ListDirectoryUseCase @Inject constructor(private val r: FileRepository) {
    suspend operator fun invoke(serverId: String, directory: String, path: String) = r.listDirectory(serverId, directory, path) }
class GetFileContentUseCase @Inject constructor(private val r: FileRepository) {
    suspend operator fun invoke(serverId: String, directory: String, path: String) = r.getFileContent(serverId, directory, path) }
class GetVcsStatusUseCase @Inject constructor(private val r: VcsRepository) {
    suspend operator fun invoke(serverId: String, directory: String) = r.getStatus(serverId, directory) }
class GetFileDiffUseCase @Inject constructor(private val r: VcsRepository) {
    suspend operator fun invoke(serverId: String, directory: String, mode: VcsDiffMode = VcsDiffMode.GIT, context: Int = 3) = r.getDiff(serverId, directory, mode, context) }
```

- [ ] **Step 3: 测试 + Commit** → `PASS（4 tests）`
`git commit -m "feat: 4 workspace UseCases (serverId+directory)"`

---

## Task 9: 路由（含 directory）

<a id="task-9"></a>

**Spec ref:** §9

**Files:** Create `routes/WorkspaceNav.kt`、`routes/FileViewerNav.kt`；Modify `Screen.kt`、`NavGraph.kt`；Test 2 个

- [ ] **Step 1: WorkspaceNav（含 directory，参照 ChatNav）**

```kotlin
object WorkspaceNav {
    const val ROUTE = "workspace"
    const val PARAM_SESSION_ID = "sessionId"
    const val PARAM_DIRECTORY = "directory"
    val navArguments = ServerRouteParams.navArguments + listOf(
        navArgument(PARAM_SESSION_ID) { type = NavType.StringType },
        navArgument(PARAM_DIRECTORY) { type = NavType.StringType; defaultValue = "" })
    val routePattern get() = "$ROUTE?${ServerRouteParams.queryPattern()}&$PARAM_SESSION_ID={$PARAM_SESSION_ID}&$PARAM_DIRECTORY={$PARAM_DIRECTORY}"
    data class Params(val server: ServerRouteParams, val sessionId: String, val directory: String)
    fun createRoute(s: ServerRouteParams, sessionId: String, directory: String): String =
        "$ROUTE?${ServerRouteParams.queryString(s.serverUrl,s.username,s.password,s.serverName,s.serverId)}&$PARAM_SESSION_ID=${URLEncoder.encode(sessionId,"UTF-8")}&$PARAM_DIRECTORY=${URLEncoder.encode(directory,"UTF-8")}"
    fun fromEntry(e: NavBackStackEntry) = Params(e.serverRouteParams(),
        URLDecoder.decode(e.arguments?.getString(PARAM_SESSION_ID).orEmpty(),"UTF-8"),
        URLDecoder.decode(e.arguments?.getString(PARAM_DIRECTORY).orEmpty(),"UTF-8"))
}
```

- [ ] **Step 2: FileViewerNav（含 directory + source + toolPartIds）**

```kotlin
object FileViewerNav {
    const val ROUTE = "file_viewer"
    const val PARAM_SESSION_ID = "sessionId"
    const val PARAM_FILE_PATH = "filePath"
    const val PARAM_SOURCE = "source"  // "live"|"git_diff"|"tool_snapshot"|"tool_snapshot_diff"
    const val PARAM_TOOL_PART_IDS = "toolPartIds"  // 逗号分隔;Precondition: part ID 不含逗号(UUID)
    const val PARAM_DIRECTORY = "directory"
    object Source { const val LIVE="live"; const val GIT_DIFF="git_diff"; const val TOOL_SNAPSHOT="tool_snapshot"; const val TOOL_SNAPSHOT_DIFF="tool_snapshot_diff" }
    // navArguments / routePattern / createRoute / fromEntry 同模式,含 directory 参数
    // createRoute 所有 String 参数 URLEncoder.encode
}
```

- [ ] **Step 3: Screen.kt 加 Workspace/FileViewer**

- [ ] **Step 4: NavGraph 注册 placeholder**（Task 12/15 替换）

```kotlin
composable(WorkspaceNav.routePattern, arguments = WorkspaceNav.navArguments) { Text("Workspace placeholder (Task 12)") }
composable(FileViewerNav.routePattern, arguments = FileViewerNav.navArguments) { Text("FileViewer placeholder (Task 15)") }
```

- [ ] **Step 5: 测试 + Commit** → `PASS（6 tests: WorkspaceNav 3 + FileViewerNav 3）`
`git commit -m "feat: Workspace/FileViewer routes with directory param"`

---

## Task 10: WorkspaceViewModel

<a id="task-10"></a>

**Spec ref:** §6.1-6.3 | **修正**：serverId+directory（D2-001）、补全逻辑（D5-003）、并发处理（D8-004）

**Files:** Create `ui/screens/workspace/WorkspaceUiState.kt`、`WorkspaceViewModel.kt`；Test `WorkspaceViewModelTest.kt`

- [ ] **Step 1: UiState**

```kotlin
enum class WorkspacePanel { FILE_TREE, GIT_CHANGES }
data class FileTreeNode(val node: FileNode, val children: List<FileTreeNode>? = null,
    val isLoading: Boolean = false, val error: String? = null)
data class WorkspaceUiState(
    val currentPanel: WorkspacePanel = WorkspacePanel.FILE_TREE,
    val rootNodes: List<FileTreeNode> = emptyList(), val rootLoading: Boolean = true, val rootError: String? = null,
    val showIgnored: Boolean = false,
    val gitChanges: List<VcsChange> = emptyList(), val gitLoading: Boolean = false,
    val gitError: String? = null, val isNonGit: Boolean = false, val gitChangeCount: Int? = null)

data class DirectoryLoadResult(val path: String, val nodes: List<FileNode>, val error: String?)  // 子目录加载事件
```

- [ ] **Step 2: 写测试（含并发，D8-004 修正）**

用例（13 个）：
1. `init triggers root load + git prefetch`
2. `loadDirectory success`
3. `loadDirectory cache hit (same path twice = 1 API call)`
4. `loadDirectory failure sets rootError`
5. `refreshRoot clears cache + reloads`
6. `switchPanel GIT triggers getStatus if not loaded`
7. `switchPanel GIT non-git sets isNonGit`
8. `switchPanel FILE_TREE no reload`
9. `toggleShowIgnored`
10. `git prefetch failure leaves count null`
11. **`loadDirectory during refreshRoot cancels stale`**（并发）
12. **`rapid duplicate loadDirectory debounced`**（并发）
13. **`blank serverId sets rootError without calling useCase`**（D8-008 防御）

ViewModel 注入：`SavedStateHandle`（含 serverId+directory）、`ListDirectoryUseCase`、`GetVcsStatusUseCase`。**不注入 ServerConnectionRepository**（D2-010 修正）。

- [ ] **Step 3: 实现 ViewModel（补全，非占位）**

```kotlin
@HiltViewModel
class WorkspaceViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val listDirectory: ListDirectoryUseCase,
    private val getVcsStatus: GetVcsStatusUseCase
) : ViewModel() {
    private val serverId = savedStateHandle.get<String>(ServerRouteParams.PARAM_SERVER_ID).orEmpty()
    private val directory = URLDecoder.decode(savedStateHandle.get<String>(WorkspaceNav.PARAM_DIRECTORY).orEmpty(), "UTF-8")
    private val _uiState = MutableStateFlow(WorkspaceUiState())
    val uiState = _uiState.asStateFlow()
    private val dirCache = mutableMapOf<String, List<FileNode>>()
    private val loadJobs = mutableMapOf<String, Job>()  // D8-004: 跟踪取消

    private val _dirLoadEvents = MutableSharedFlow<DirectoryLoadResult>()
    val dirLoadEvents = _dirLoadEvents.asSharedFlow()  // 子目录展开通知

    init {
        if (serverId.isBlank()) {
            _uiState.update { it.copy(rootError = "服务器配置缺失", rootLoading = false) }; return
        }
        loadDirectory(""); prefetchGitCount()
    }

    fun loadDirectory(path: String) {
        if (serverId.isBlank()) return
        dirCache[path]?.let { /* 缓存命中,UI 直接用 */ return }
        loadJobs[path]?.cancel()  // D8-004: 取消同 path 旧请求
        if (path.isEmpty()) _uiState.update { it.copy(rootLoading = true, rootError = null) }
        loadJobs[path] = viewModelScope.launch {
            listDirectory(serverId, directory, path)
                .onSuccess { nodes ->
                    dirCache[path] = nodes
                    if (path.isEmpty()) _uiState.update { it.copy(rootNodes = nodes.toTreeNodes(), rootLoading = false) }
                    else _dirLoadEvents.tryEmit(DirectoryLoadResult(path, nodes, null))  // 通知 UI 展开
                }
                .onFailure { e ->
                    val msg = e.message ?: "加载失败"
                    if (path.isEmpty()) _uiState.update { it.copy(rootLoading = false, rootError = msg) }
                    else _dirLoadEvents.tryEmit(DirectoryLoadResult(path, emptyList(), msg))
                }
        }
    }

    fun refreshRoot() { dirCache.clear(); loadJobs.values.forEach { it.cancel() }; loadDirectory("") }
    fun switchPanel(p: WorkspacePanel) {
        _uiState.update { it.copy(currentPanel = p) }
        if (p == GIT_CHANGES && _uiState.value.gitChanges.isEmpty() && !_uiState.value.isNonGit && !_uiState.value.gitLoading) loadGitChanges()
    }
    fun loadGitChanges() {
        if (serverId.isBlank()) return
        _uiState.update { it.copy(gitLoading = true, gitError = null, isNonGit = false) }
        viewModelScope.launch {
            getVcsStatus(serverId, directory)
                .onSuccess { c -> _uiState.update { it.copy(gitChanges = c, gitLoading = false, gitChangeCount = c.size, isNonGit = false) } }
                .onFailure { e ->
                    val msg = e.message.orEmpty()
                    val nonGit = msg.contains("non-git", true) || msg.contains("not a git")
                    _uiState.update { it.copy(gitLoading = false, isNonGit = nonGit, gitError = if (nonGit) null else msg) }
                }
        }
    }
    private fun prefetchGitCount() { viewModelScope.launch {
        getVcsStatus(serverId, directory).onSuccess { c -> _uiState.update { it.copy(gitChangeCount = c.size) } } } }
    fun toggleShowIgnored() { _uiState.update { it.copy(showIgnored = !it.showIgnored) } }
    private fun List<FileNode>.toTreeNodes() = sortedWith(compareBy({ !it.isDirectory() }, { it.name.lowercase() }))
        .map { FileTreeNode(it, if (it.isDirectory()) null else emptyList()) }
}
```

- [ ] **Step 4: 测试 + Commit** → `PASS（13 tests）`
`git commit -m "feat: WorkspaceViewModel (serverId+directory, concurrency, cache)"`

---

## Task 11: FileViewerViewModel + DiffParser

<a id="task-11"></a>

**Spec ref:** §7.1-7.2, §7.6 | **修正**：补全 loadLive/loadGitDiff（D5-002）、修复 parseUnifiedDiff（D4-004）、DiffHunk.patchStartLineIndex（D4-005）

**Files:** Create `viewer/FileViewerUiState.kt`、`FileViewerViewModel.kt`、`DiffParser.kt`；Test 2 个

- [ ] **Step 1: UiState + DiffHunk（加 patchStartLineIndex）**

```kotlin
enum class FileViewerMode { SOURCE, DIFF }
data class DiffHunk(val startLine: Int, val patchStartLineIndex: Int, val type: DiffHunkType, val rawPatch: String)
// startLine = after 文件起始行（显示用）；patchStartLineIndex = patch 文本内行索引（滚动用）D4-005
enum class DiffHunkType { ADDED, REMOVED, MODIFIED }
data class FileViewerUiState(
    val filePath: String = "", val mode: FileViewerMode = FileViewerMode.SOURCE,
    val isLoading: Boolean = true, val content: String = "", val isBinary: Boolean = false,
    val mimeType: String? = null, val error: String? = null, val isEmpty: Boolean = false,
    val isTruncated: Boolean = false,  // D8-003: Phase 1 软截断
    val diff: VcsFileDiff? = null, val hunks: List<DiffHunk> = emptyList(), val currentHunkIndex: Int = 0)
```

- [ ] **Step 2: DiffParser 测试（含边界，D8-002 修正）**

用例（8 个）：空 patch、单 hunk、多 hunk、**真实样本**（项目 git diff）、**malformed（无 @@）→ 空**、**binary diff 行 → 空**、**混合 +/- hunk → MODIFIED**（D4-004）、**CRLF patch**。

- [ ] **Step 3: 实现 DiffParser（修复 hunk type 推断，D4-004）**

```kotlin
private val HUNK_HEADER = Regex("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@")
fun parseUnifiedDiff(patch: String): List<DiffHunk> {
    val lines = patch.lines()
    val hunks = mutableListOf<DiffHunk>()
    var i = 0
    while (i < lines.size) {
        val m = HUNK_HEADER.find(lines[i])
        if (m != null) {
            val startLine = m.groupValues[2].toIntOrNull() ?: 1
            val patchStartIdx = i
            val body = StringBuilder()
            var hasAdded = false; var hasRemoved = false  // D4-004: 双标志
            i++
            while (i < lines.size && !lines[i].startsWith("@@")) {
                body.append(lines[i]).append('\n')
                when { lines[i].startsWith("+") -> hasAdded = true; lines[i].startsWith("-") -> hasRemoved = true }
                i++
            }
            val type = when { hasAdded && hasRemoved -> DiffHunkType.MODIFIED; hasAdded -> DiffHunkType.ADDED; hasRemoved -> DiffHunkType.REMOVED; else -> DiffHunkType.MODIFIED }
            hunks.add(DiffHunk(startLine, patchStartIdx, type, lines.subList(patchStartIdx, i).joinToString("\n")))
        } else i++
    }
    return hunks
}
```

- [ ] **Step 4: FileViewerViewModel 测试 + 实现（补全 loadLive/loadGitDiff）**

用例（9 个）：LIVE 成功、GIT_DIFF 成功（解析 hunks）、**TOOL_SNAPSHOT Phase 1 抛 UnsupportedOperationException**（D2-006 修正：不静默降级）、load failure、binary、空 content（isEmpty）、**空 patch（hunks 空）**、nextHunk、prevHunk clamp。

```kotlin
@HiltViewModel
class FileViewerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getFileContent: GetFileContentUseCase,
    private val getFileDiff: GetFileDiffUseCase
) : ViewModel() {
    private val serverId = savedStateHandle.get<String>(ServerRouteParams.PARAM_SERVER_ID).orEmpty()
    private val directory = URLDecoder.decode(savedStateHandle.get<String>(FileViewerNav.PARAM_DIRECTORY).orEmpty(), "UTF-8")
    private val filePath = URLDecoder.decode(savedStateHandle.get<String>(FileViewerNav.PARAM_FILE_PATH).orEmpty(), "UTF-8")
    private val source = savedStateHandle.get<String>(FileViewerNav.PARAM_SOURCE) ?: FileViewerNav.Source.LIVE
    private val _uiState = MutableStateFlow(FileViewerUiState(filePath = filePath))
    val uiState = _uiState.asStateFlow()

    init {
        when (source) {
            FileViewerNav.Source.LIVE -> loadLive()
            FileViewerNav.Source.GIT_DIFF -> loadGitDiff()
            FileViewerNav.Source.TOOL_SNAPSHOT, FileViewerNav.Source.TOOL_SNAPSHOT_DIFF ->
                _uiState.update { it.copy(isLoading = false, error = "工具快照视图在 Phase 2 实现") }  // D2-006: 显式不支持而非静默降级
        }
    }

    private fun loadLive() {
        viewModelScope.launch {
            getFileContent(serverId, directory, filePath)
                .onSuccess { c ->
                    if (c.type == ContentType.BINARY) _uiState.update { it.copy(isLoading = false, isBinary = true, mimeType = c.mimeType) }
                    else {
                        // D8-003: Phase 1 软截断（>5000 行）
                        val lines = c.content.split('\n')
                        val truncated = lines.size > 5000
                        val visible = if (truncated) lines.take(5000).joinToString("\n") else c.content
                        _uiState.update { it.copy(isLoading = false, content = visible, isEmpty = visible.isBlank(), isTruncated = truncated) }
                    }
                }
                .onFailure { e -> _uiState.update { it.copy(isLoading = false, error = e.message ?: "加载失败") } }
        }
    }

    private fun loadGitDiff() {
        viewModelScope.launch {
            getFileDiff(serverId, directory, VcsDiffMode.GIT)
                .onSuccess { diffs ->
                    val target = diffs.find { it.file == filePath || it.file.endsWith(filePath) }
                    val hunks = target?.patch?.let { parseUnifiedDiff(it) } ?: emptyList()
                    _uiState.update { it.copy(isLoading = false, mode = FileViewerMode.DIFF, diff = target, hunks = hunks,
                        currentHunkIndex = 0, isEmpty = hunks.isEmpty()) }
                }
                .onFailure { e -> _uiState.update { it.copy(isLoading = false, error = e.message ?: "加载失败") } }
        }
    }

    fun nextHunk() { _uiState.update { it.copy(currentHunkIndex = (it.currentHunkIndex + 1).coerceAtMost(it.hunks.size - 1)) } }
    fun prevHunk() { _uiState.update { it.copy(currentHunkIndex = (it.currentHunkIndex - 1).coerceAtLeast(0)) } }
}
```

- [ ] **Step 5: 测试 + Commit** → `PASS（8+9 tests）`
`git commit -m "feat: FileViewerViewModel + DiffParser (fixed hunk type, patchStartLineIndex)"`

---

## Task 12: WorkspaceScreen + TopBar

<a id="task-12"></a>

**Spec ref:** §6.1

**Files:** Create `WorkspaceScreen.kt`、`WorkspaceRoute.kt`；Modify `NavGraph.kt`

- [ ] **Step 1: WorkspaceRoute + Screen**

```kotlin
@Composable
fun WorkspaceRoute(viewModel: WorkspaceViewModel = hiltViewModel(), onBack: () -> Unit,
    onOpenFile: (filePath: String) -> Unit, onOpenGitDiff: (filePath: String) -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    WorkspaceScreen(uiState, onBack, viewModel::switchPanel, viewModel::refreshRoot,
        viewModel::toggleShowIgnored, viewModel::loadGitChanges, onOpenFile, onOpenGitDiff)
}

@Composable
fun WorkspaceScreen(uiState: WorkspaceUiState, onBack: () -> Unit, onSwitchPanel: (WorkspacePanel) -> Unit,
    onRefreshRoot: () -> Unit, onToggleShowIgnored: () -> Unit, onRefreshGit: () -> Unit,
    onOpenFile: (String) -> Unit, onOpenGitDiff: (String) -> Unit) {
    Scaffold(topBar = { WorkspaceTopBar(uiState, onBack, onSwitchPanel) }) { padding ->
        when (uiState.currentPanel) {
            WorkspacePanel.FILE_TREE -> FileTreePanel(uiState, onRefreshRoot, onToggleShowIgnored, onOpenFile, modifier = Modifier.padding(padding))
            WorkspacePanel.GIT_CHANGES -> GitChangesPanel(uiState, onRefreshGit, onOpenGitDiff, modifier = Modifier.padding(padding))
        }
    }
}

@Composable
private fun WorkspaceTopBar(uiState: WorkspaceUiState, onBack: () -> Unit, onSwitchPanel: (WorkspacePanel) -> Unit) {
    TopAppBar(
        title = { Column { Text(basename(uiState.directory))  // session.directory 的 basename
            Text(uiState.directory, style = bodySmall, maxLines = 1, overflow = Ellipsis) } },
        navigationIcon = { IconButton(onClick = onBack, modifier = Modifier.testTag("back_button")) { Icon(ArrowBack, "返回") } },
        actions = {
            // 🔍 Phase 2 才启用,Phase 1 隐藏
            IconButton(onClick = { onSwitchPanel(WorkspacePanel.FILE_TREE) },
                modifier = Modifier.testTag("panel_file_tree")) {  // D6-009 testTag
                Icon(Folder, "文件树", tint = if (uiState.currentPanel == FILE_TREE) primary else onSurfaceVariant) }
            IconButton(onClick = { onSwitchPanel(WorkspacePanel.GIT_CHANGES) }, enabled = !uiState.isNonGit,
                modifier = Modifier.testTag("panel_git_changes")) {  // D6-009 testTag
                BadgedBox(badge = { if (uiState.gitChangeCount != null && uiState.gitChangeCount > 0) Badge { Text("${uiState.gitChangeCount}") } }) {
                    Icon(CompareArrows, "Git 变更", tint = if (uiState.currentPanel == GIT_CHANGES) primary else onSurfaceVariant) } }
        })
}
```

- [ ] **Step 2: NavGraph 替换 placeholder + 回调实现**

```kotlin
composable(WorkspaceNav.routePattern, arguments = WorkspaceNav.navArguments) { entry ->
    val p = WorkspaceNav.fromEntry(entry)
    val navController = NavController.current  // 实际从 LocalNavController 获取,按现有 NavGraph 模式
    WorkspaceRoute(
        onBack = { navController.popBackStack() },
        onOpenFile = { filePath -> navController.navigate(FileViewerNav.createRoute(p.server, p.sessionId, p.directory, filePath, FileViewerNav.Source.LIVE)) },
        onOpenGitDiff = { filePath -> navController.navigate(FileViewerNav.createRoute(p.server, p.sessionId, p.directory, filePath, FileViewerNav.Source.GIT_DIFF)) })
}
```
（`createRoute` 签名需匹配 Step 9 定义，含 directory 参数。NavController 获取方式参照 NavGraph.kt 现有写法。）

- [ ] **Step 3: 编译 + Commit**
`git commit -m "feat: WorkspaceScreen + TopBar with testTag panel switching"`

---

## Task 13: FileTreePanel

<a id="task-13"></a>

**Spec ref:** §6.2 | **修正**：补全 composable（D5-004）

**Files:** Create `tree/FileTreePanel.kt`、`tree/FileTreeItem.kt`；Test androidTest

- [ ] **Step 1: FileTreeItem + 扁平化 + Panel**

```kotlin
@Composable
fun FileTreePanel(uiState: WorkspaceUiState, onRefresh: () -> Unit, onToggleIgnored: () -> Unit,
    onOpenFile: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        // 次级工具条
        Row(Modifier.fillMaxWidth().padding(8.dp), Arrangement.SpaceBetween) {
            IconButton(onClick = onRefresh) { Icon(Refresh, "刷新") }
            FilterChip(selected = uiState.showIgnored, onClick = onToggleIgnored,
                label = { Text("显示隐藏") }, leadingIcon = { Icon(Visibility, null) })
        }
        when {
            uiState.rootLoading -> Box(Modifier.fillMaxSize(), Center) { CircularProgressIndicator() }
            uiState.rootError != null -> ErrorState(uiState.rootError, onRefresh)
            uiState.rootNodes.isEmpty() -> EmptyState("空目录")
            else -> {
                val flattened = remember(uiState.rootNodes, uiState.showIgnored) { flatten(uiState.rootNodes, 0, uiState.showIgnored) }
                LazyColumn { items(flattened) { (node, depth) -> FileTreeItem(node, depth, onOpenFile) } }
            }
        }
    }
}

private fun flatten(nodes: List<FileTreeNode>, depth: Int, showIgnored: Boolean): List<Pair<FileTreeNode, Int>> =
    nodes.filter { showIgnored || !it.node.ignored }.flatMap { listOf(it to depth) +
        (it.children?.let { flatten(it, depth + 1, showIgnored) } ?: emptyList()) }

@Composable
fun FileTreeItem(node: FileTreeNode, depth: Int, onOpenFile: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = (depth * 16).dp).clickable {
        if (node.node.isDirectory()) { /* TODO Phase 1: viewModel.loadDirectory(node.path) via callback */ }
        else onOpenFile(node.node.path)
    }.padding(vertical = 8.dp, horizontal = 12.dp), verticalAlignment = CenterVertically) {
        Icon(if (node.node.isDirectory()) Folder else Description, null, tint = onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(node.node.name, style = bodyMedium)
        if (node.isLoading) { Spacer(Modifier.width(8.dp)); CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp) }
    }
}
```

> ⚠️ Phase 1 子目录展开：FileTreeItem 的目录点击需要回调到 ViewModel.loadDirectory(path)。简化方案：WorkspaceScreen 持有展开状态 `expandedPaths: Set<String>`，collect `viewModel.dirLoadEvents` 更新。Phase 1 可先只支持根目录展开一层，深层在 Phase 4 完善。在 WorkspaceScreen 加 `val dirEvents by viewModel.dirLoadEvents.collectAsStateWithLifecycle(initialValue = null)` 并维护展开 map。

- [ ] **Step 2: androidTest（5 场景，注入 fake uiState）** → `PASS（5 tests）`
- [ ] **Step 3: Commit**
`git commit -m "feat: FileTreePanel with flatten + lazy column + showIgnored filter"`

---

## Task 14: GitChangesPanel

<a id="task-14"></a>

**Spec ref:** §6.3 | **修正**：补全 composable + Expected（D5-004/D7-005）

**Files:** Create `git/GitChangesPanel.kt`、`git/GitChangeItem.kt`；Test androidTest

- [ ] **Step 1: GitChangeItem + Panel + 状态计数**

```kotlin
@Composable
fun GitChangeItem(change: VcsChange, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick).padding(horizontal = 12.dp, vertical = 10.dp), CenterVertically) {
        Surface(color = when (change.status) { ADDED -> DiffAdded; DELETED -> DiffRemoved; MODIFIED -> MaterialTheme.colorScheme.tertiary },
            shape = ShapeTokens.extraSmall, modifier = Modifier.size(20.dp)) {  // D3-004: extraSmall 非 XS
            Text(change.status.name.first().toString(), color = onTertiaryContainer, fontSize = 11.sp, fontWeight = Bold,
                textAlign = Center, modifier = Modifier.padding(2.dp)) }
        Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) {
            Text(change.file, style = bodyMedium, maxLines = 1, overflow = Ellipsis)
            Text("+${change.additions} -${change.deletions}", style = bodySmall, color = onSurfaceVariant) }
    }
}

@Composable
fun GitChangesPanel(uiState: WorkspaceUiState, onRefresh: () -> Unit, onOpenDiff: (String) -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxSize()) {
        val changes = uiState.gitChanges
        val stats = remember(changes) { changes.groupingBy { it.status }.eachCount() }
        Row(Modifier.fillMaxWidth().padding(8.dp), SpaceBetween) {
            IconButton(onClick = onRefresh) { Icon(Refresh, "刷新") }
            Text("${changes.size} 个变更 (${stats[MODIFIED] ?: 0} M, ${stats[ADDED] ?: 0} A, ${stats[DELETED] ?: 0} D)", style = bodySmall)
        }
        when {
            uiState.gitLoading -> Box(Modifier.fillMaxSize(), Center) { CircularProgressIndicator() }
            uiState.isNonGit -> EmptyState("当前项目不是 Git 仓库")
            uiState.gitError != null -> ErrorState(uiState.gitError, onRefresh)
            changes.isEmpty() -> EmptyState("工作区干净，无变更")
            else -> LazyColumn { items(changes) { c -> GitChangeItem(c) { onOpenDiff(c.file) }; Divider() } }
        }
    }
}
```

- [ ] **Step 2: androidTest（5 场景）** + Run `connectedDevDebugAndroidTest --tests "*.GitChangesPanelTest"` → `PASS（5 tests）`（D7-005 修正）
- [ ] **Step 3: Commit**
`git commit -m "feat: GitChangesPanel with status badge + stats count + non-git state"`

---

## Task 15: FileViewerScreen + CodeSourceView

<a id="task-15"></a>

**Spec ref:** §7.1-7.2 | **修正**：补全三函数（D5-001 P0）+ Highlights 依赖（D4-001）

**Files:** Modify `app/build.gradle.kts`（加 highlights 依赖）；Create `viewer/FileViewerScreen.kt`、`FileViewerRoute.kt`、`CodeSourceView.kt`；Modify `NavGraph.kt`

- [ ] **Step 0: 加 Highlights 直接依赖**

`.\gradlew :app:dependencies --configuration devDebugRuntimeClasspath | findstr highlights` 查版本。
`app/build.gradle.kts` dependencies 加：`implementation("dev.snipme:highlights:<查到的版本>")`。

- [ ] **Step 1: CodeSourceView（补全三函数）**

```kotlin
@Composable
fun CodeSourceView(content: String, filePath: String, modifier: Modifier = Modifier) {
    val language = rememberLanguage(filePath)
    val highlights = remember(content, language) { buildHighlights(content, language) }
    val annotated = remember(content, highlights) { buildAnnotatedStringFromHighlights(content, highlights) }
    val lineCount = remember(content) { content.count { it == '\n' } + 1 }
    val hScroll = rememberScrollState(); val vScroll = rememberScrollState()
    Box(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize().horizontalScroll(hScroll)) {
            LineNumberGutter(lineCount)  // 行号 gutter
            Text(annotated, style = CodeTypography, modifier = Modifier.weight(1f).verticalScroll(vScroll).padding(8.dp))
        }
    }
}

// 函数 1: 构建 Highlights
private fun buildHighlights(content: String, language: String): Highlights =
    Highlights.Builder().code(content).language(language).build()

// 函数 2: Highlights → AnnotatedString（D4-004 修正：处理 ColorHighlight/BoldHighlight）
@OptIn(ExperimentalStdlibApi::class)
private fun buildAnnotatedStringFromHighlights(content: String, highlights: Highlights): AnnotatedString =
    buildAnnotatedString {
        append(content)
        highlights.getHighlights().forEach { h ->
            val (start, end) = h.location.first to h.location.last
            when (h) {
                is ColorHighlight -> addStyle(SpanStyle(color = Color(h.color)), start, end + 1)
                is BoldHighlight -> addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end + 1)
            }
        }
    }

// 函数 3: 行号 gutter
@Composable
private fun LineNumberGutter(lineCount: Int) {
    Column(Modifier.width(40.dp).padding(end = 8.dp)) {
        repeat(lineCount) { i -> Text("${i + 1}", style = CodeTypography, color = onSurfaceVariant, textAlign = End, modifier = Modifier.fillMaxWidth()) }
    }
}

private fun rememberLanguage(filePath: String): String = when (filePath.substringAfterLast('.', "").lowercase()) {
    "kt", "kts" -> "kotlin"; "java" -> "java"; "xml" -> "xml"; "json" -> "json"
    "yaml", "yml" -> "yaml"; "md" -> "markdown"; "sh", "bash" -> "bash"
    "py" -> "python"; "ts" -> "typescript"; "js" -> "javascript"; "go" -> "go"; "rs" -> "rust"
    else -> "text"
}
```

> ⚠️ `Highlights.Builder().language(lang)` 的 lang 字符串需匹配库支持的枚举（`dev.snipme.highlights.model.Language`）。若不确定，先 spike：`.\gradlew :app:compileDevDebugKotlin` 验证。若 30 分钟内跑不通，**降级**：CodeSourceView 只渲染纯文本+行号（无高亮），加 TODO 注释"语法高亮 Phase 4 补全"（Correction 4 降级方案，D5-009 时间盒 2 小时）。

- [ ] **Step 2: FileViewerRoute + Screen**

```kotlin
@Composable
fun FileViewerRoute(viewModel: FileViewerViewModel = hiltViewModel(), onBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    FileViewerScreen(uiState, onBack, viewModel::nextHunk, viewModel::prevHunk)
}

@Composable
fun FileViewerScreen(uiState: FileViewerUiState, onBack: () -> Unit, onNextHunk: () -> Unit, onPrevHunk: () -> Unit) {
    Scaffold(topBar = { FileViewerTopBar(uiState, onBack) }) { padding ->
        Box(Modifier.padding(padding)) {
            when {
                uiState.isLoading -> Box(Modifier.fillMaxSize(), Center) { CircularProgressIndicator() }
                uiState.error != null -> ErrorState(uiState.error, null)
                uiState.isBinary -> EmptyState("二进制文件，不支持预览\nmimeType: ${uiState.mimeType ?: "未知"}")
                uiState.mode == DIFF -> DiffView(uiState, onNextHunk, onPrevHunk)  // Task 16
                uiState.isEmpty -> EmptyState("文件为空")
                uiState.isTruncated -> { Column { Surface(tertiaryContainer) { Text("⚠ 文件较大，已截断显示前 5000 行") }; CodeSourceView(uiState.content, uiState.filePath) } }
                else -> CodeSourceView(uiState.content, uiState.filePath)
            }
        }
    }
}
```

- [ ] **Step 3: androidTest（3 场景）** → `PASS（3 tests）`（D7-008 修正）
- [ ] **Step 4: NavGraph 替换 + Commit**
`git commit -m "feat: FileViewerScreen + CodeSourceView (Highlights→AnnotatedString, truncation)"`

---

## Task 16: DiffView（Hunk 导航）

<a id="task-16"></a>

**Spec ref:** §7.6 | **修正**：修复代码（D5-005）+ 颜色 token（D3-005）+ patchStartLineIndex（D4-005）

**Files:** Create `viewer/DiffView.kt`；Test `DiffViewTest.kt`（parseUnifiedDiff 已在 Task 11 测）

- [ ] **Step 1: DiffView（修复滚动 + 颜色）**

```kotlin
@Composable
fun DiffView(uiState: FileViewerUiState, onNextHunk: () -> Unit, onPrevHunk: () -> Unit) {
    val patch = uiState.diff?.patch ?: return
    val lines = remember(patch) { patch.lines() }
    val listState = rememberLazyListState()
    // D4-005: 用 patchStartLineIndex 直接定位，不用 indexOfFirst 反查
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

@Composable
private fun DiffLine(line: String) {
    // D3-005: 用 DiffAdded/DiffRemoved + alpha，不引用不存在的 DiffAddedBg
    val (bg, fg) = when {
        line.startsWith("+") -> DiffAdded.copy(alpha = AlphaTokens.DIFF_BG) to DiffAdded
        line.startsWith("-") -> DiffRemoved.copy(alpha = AlphaTokens.DIFF_BG) to DiffRemoved
        line.startsWith("@@") -> primaryContainer to onPrimaryContainer
        else -> Color.Transparent to MaterialTheme.colorScheme.onSurface
    }
    Text(line, style = CodeTypography, color = fg, modifier = Modifier.fillMaxWidth().background(bg).padding(horizontal = 8.dp, vertical = 1.dp))
}

@Composable
private fun DiffHunkNavigator(current: Int, total: Int, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(8.dp), Arrangement.End, CenterVertically) {
        IconButton(onClick = onPrev, enabled = current > 0) { Icon(ExpandLess, "上一个") }
        IconButton(onClick = onNext, enabled = current < total - 1) { Icon(ExpandMore, "下一个") }
        Spacer(Modifier.width(8.dp)); Text("[${current + 1}/$total]", style = bodySmall)
    }
}
```

- [ ] **Step 2: androidTest（hunk 跳转）** → `PASS（1 test: 渲染 3 hunk → 点⬇️ → [2/3]）`（D7-009 修正）
- [ ] **Step 3: Commit**
`git commit -m "feat: DiffView with hunk navigation (fixed scroll + color tokens)"`

---

## Task 17: 入口2 — ChatTopBar 菜单

<a id="task-17"></a>

**Spec ref:** §6.5 | ⚠️ 遵守 chatscreen-editing-protocol（Read-before-Edit + 编译 + commit）

**Files:** Modify `chat/components/ChatTopBar.kt`、`chat/ChatScreen.kt`、`chat/ChatRoute.kt`、`res/values/strings.xml` + lokit 同步

- [ ] **Step 1: strings.xml 加** `<string name="chat_menu_open_workspace">查看工作空间</string>` + 跑 `lokit`

- [ ] **Step 2: Read ChatTopBar.kt 全文**（协议要求）

- [ ] **Step 3: ChatTopBar 加 onOpenWorkspace 参数 + DropdownMenuItem + testTag**

```kotlin
// 函数参数加: onOpenWorkspace: () -> Unit
// DropdownMenu 内加:
DropdownMenuItem(text = { Text(stringResource(R.string.chat_menu_open_workspace)) },
    onClick = { showMenu = false; onOpenWorkspace() },
    leadingIcon = { Icon(Icons.Default.Folder, null) },
    modifier = Modifier.testTag("menu_open_workspace"))  // D6-009 testTag
// MoreVert IconButton 加:
IconButton(onClick = { showMenu = true }, modifier = Modifier.testTag("more_vert")) { Icon(MoreVert, ...) }  // D6-009 testTag
```

- [ ] **Step 4: ChatScreen.kt 透传 onOpenWorkspace**（Read → Edit → compile）

- [ ] **Step 5: ChatRoute.kt 实现导航**

```kotlin
onOpenWorkspace = {
    val p = ChatNav.Params(...)  // 当前 chat 的 server params + sessionId + directory
    navController.navigate(WorkspaceNav.createRoute(p.server, p.sessionId, p.directory))
}
```

- [ ] **Step 6: compileDevDebugKotlin**（协议要求；失败 git checkout 重试）→ SUCCESS
- [ ] **Step 7: Commit**
`git commit -m "feat: add 'View Workspace' menu to ChatTopBar with testTag (入口2)"`

---

## Task 18: Maestro E2E + testTag 验证

<a id="task-18"></a>

**Spec ref:** §11.4 | **修正**：testTag 依赖（D6-009/D7-001）+ 真实样本（D7-003）

**Files:** Create `.maestro/flows/e2e-verify/20-workspace-file-tree.yaml`、`21-workspace-git-changes.yaml`；Create `test/resources/workspace-samples/sample-diff-kotlin.patch`

- [ ] **Step 1: 真实样本库**

`app/src/test/resources/workspace-samples/`：
- `sample-diff-kotlin.patch`：`git diff HEAD~1 -- app/src/main/kotlin/dev/minios/ocremote/data/api/OpenCodeApi.kt` 截取含 2+ hunk 的真实 diff
- `sample-kotlin.kt`：截取 `OpenCodeApi.kt` 前 50 行（Task 3/15 测试用）
- `sample-vcs-status.json`：真实 `GET /vcs/status` 响应（curl 获取）

- [ ] **Step 2: 验证 testTag 全部就位**

`grep -rn "testTag.*more_vert\|testTag.*panel_file_tree\|testTag.*panel_git_changes\|testTag.*back_button\|testTag.*menu_open_workspace" app/src/main --include="*.kt"`
预期：5 行命中（Task 12/17 添加的）。

- [ ] **Step 3: flow 20（文件树）**

```yaml
appId: dev.minios.ocremote.dev
---
- runFlow: ../e2e-verify/06-chat-screen.yaml
- tapOn:
    id: "more_vert"
- tapOn:
    id: "menu_open_workspace"
- assertVisible: ".*"  # directory basename
- tapOn:
    id: "panel_file_tree"
- tapOn:
    text: ".*"  # 任一文件树项
    index: 0
- tapOn:
    id: "back_button"
```

- [ ] **Step 4: flow 21（Git 变更）**

```yaml
appId: dev.minios.ocremote.dev
---
- runFlow: ../e2e-verify/06-chat-screen.yaml
- tapOn:
    id: "more_vert"
- tapOn:
    id: "menu_open_workspace"
- tapOn:
    id: "panel_git_changes"
- assertVisible: ".*"  # 变更列表或"工作区干净"
```

- [ ] **Step 5: 前置检查 + 跑 flow**

前置：`maestro --version` 可执行；emulator 已启动（`adb devices`）；App 内服务器 URL = `http://10.0.2.2:4096`，密码 = `$env:OPENCODE_SERVER_PASSWORD`。
`maestro test .maestro/flows/e2e-verify/20-workspace-file-tree.yaml` → `PASS（flow 20）`
`maestro test .maestro/flows/e2e-verify/21-workspace-git-changes.yaml` → `PASS（flow 21）`

- [ ] **Step 6: Commit**
`git commit -m "test: Maestro E2E flows 20/21 + real test samples"`

---

## Phase 1 验收清单

每项必须通过：

- [ ] `.\gradlew :app:compileDevDebugKotlin`（120s 内 SUCCESS）
- [ ] `.\gradlew :app:testDevDebugUnitTest --rerun`（180s 内全绿，含 Task 1-11/17 共 ~70 个测试）
- [ ] `.\gradlew :app:connectedDevDebugAndroidTest --tests "*.workspace.*"`（emulator 全绿，~19 个 androidTest）
- [ ] `maestro test .maestro/flows/e2e-verify/20-workspace-file-tree.yaml` → PASS
- [ ] `maestro test .maestro/flows/e2e-verify/21-workspace-git-changes.yaml` → PASS
- [ ] **核心工具类分支覆盖**（D7-007 修正）：DiffParser.parseUnifiedDiff（空/单/多/malformed/binary/混合）、FileMapper/VcsMapper（所有 type 分支）、WorkspaceViewModel（cache/并发/error）、FileViewerViewModel（LIVE/GIT_DIFF/截断/hunk 跳转）—— 用 JaCoCo 或人工 review 确认每分支有测试
- [ ] 手动验收（真实 opencode 服务器 4096 + emulator 10.0.2.2）：
  - 入口2：`more_vert` → `menu_open_workspace` → 文件树展开 → 点文件 → FileViewer 源码视图（验证：TopBar 标题 = 文件 basename、内容首行可见、行号显示）→ `back_button` 返回
  - 入口3：`panel_git_changes` → 变更列表（验证：状态色块 A 绿/M 黄/D 红、+N -M 数字）→ 点变更文件 → Diff 视图（验证：+/- 行着色、点⬇️ 滚动 + [N/M] 递增、首 hunk ⬆️ disabled）→ 返回
  - 边界：空目录"空文件夹"、ignored 隐藏切换、目录加载失败"加载失败[重试]"、非 git 项目 Git 按钮 disabled、二进制"不支持预览"、大文件截断警告

## Phase 1 不包含（Phase 2-4）

- 🔍 搜索模式（Phase 2，用 findFiles API）
- md 渲染预览切换（Phase 2）
- 入口1 工具卡片改造 + 同 turn 聚合（Phase 2）
- 标注能力（Phase 3，复用 SessionTerminalInline.kt 的 TextToolbar 模式）
- 大文件加载更多（Phase 4，Phase 1 已有软截断兜底）

## Self-Review（v2，对照审查报告）

**P0 全部修复**：
- ✅ Corrections 内联到各 Task（不再有文末附录）
- ✅ directory 参数全链路（Task 4 readFile 补 + Task 5/6 Repository + Task 9 路由 + Task 10/11 ViewModel）
- ✅ VcsFileDiff 新建（Task 2），FileDiffDto→VcsFileDiff（Task 3）
- ✅ CodeSourceView 三函数补全（Task 15）
- ✅ 复用现有 OpenCodeApi.listDirectory/findFiles（Task 4 不重写）

**P1 关键修复**：
- ✅ serverId 模式（Task 5/6/8/10/11）
- ✅ resolveConnection 提升公共方法（Task 5 Step 1 前置）
- ✅ Highlights 直接依赖（Task 15 Step 0）
- ✅ parseUnifiedDiff hunk type 修复（Task 11，双标志）
- ✅ DiffHunk.patchStartLineIndex（Task 11，滚动修复）
- ✅ DiffView 颜色用 DiffAdded.copy(alpha)（Task 16）
- ✅ ShapeTokens.extraSmall 非 XS（Task 14）
- ✅ VcsBranchDto @SerialName（Task 1）
- ✅ 测试用真实样本（Task 18 Step 1 样本库）
- ✅ Expected 量化测试数（所有 Task）
- ✅ testTag 跨 Task 清单（Global Constraints + Task 12/17）
- ✅ 前置依赖（JDK 21/Maestro/10.0.2.2/凭证/lokit/daemon 兜底/Basic Auth/SDK/代理 fallback）
- ✅ Task 11 TOOL_SNAPSHOT 显式不支持（不静默降级）
- ✅ 并发测试（Task 10 用例 11-13）
- ✅ Phase 1 大文件软截断（Task 11 isTruncated）
- ✅ 网络错误用 Ktor 真实异常（Task 5/6 测试建议）

**残留 P2**（不阻塞，记录为改进）：
- spec 的 FileDiff 复用声明等设计错误未同步修正（plan 已用正确架构，spec 后续单独修）
- ScrollState 跨配置变更（rememberSaveable）可在 Phase 4 优化
- 标注能力边界（超长意见/数量上限）在 Phase 3 spec 补充

