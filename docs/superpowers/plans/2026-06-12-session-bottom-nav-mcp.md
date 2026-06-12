# Session Bottom Navigation + MCP Control Panel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 SessionListScreen 内新增底部导航栏（NavigationBar），Tab 1 为会话目录、Tab 2 为服务器级设置页（含 MCP 服务器启停面板）。

**Architecture:** 使用 HorizontalPager + NavigationBar 实现 2 Tab 切换。MCP API 集成遵循 Clean Architecture（Domain → Data → UI）。不新建 ViewModel，扩展 SessionListViewModel。

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Hilt, Ktor, kotlinx.serialization

**Spec:** `docs/specs/2026-06-12-session-bottom-nav-mcp-design.md`

---

## File Map

### Create
| File | Responsibility |
|------|---------------|
| `app/src/main/kotlin/dev/minios/ocremote/domain/model/McpServerStatus.kt` | MCP 服务器状态领域模型 |
| `app/src/main/kotlin/dev/minios/ocremote/domain/repository/McpRepository.kt` | MCP 仓库接口 |
| `app/src/main/kotlin/dev/minios/ocremote/data/dto/response/McpResponses.kt` | MCP API 响应 DTO |
| `app/src/main/kotlin/dev/minios/ocremote/data/repository/McpRepositoryImpl.kt` | MCP 仓库实现（合并 /mcp + /config） |
| `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/ServerSettingsContent.kt` | Page 1 设置页内容 |
| `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/McpServerRow.kt` | MCP 服务器单行组件 |

### Modify
| File | Change |
|------|--------|
| `app/build.gradle.kts` | 添加 compose-foundation 依赖 |
| `app/src/main/kotlin/dev/minios/ocremote/data/dto/response/ConfigResponses.kt` | 扩展 mcp 字段 + McpServerConfig |
| `app/src/main/kotlin/dev/minios/ocremote/data/api/OpenCodeApi.kt` | 添加 3 个 MCP API 方法 |
| `app/src/main/kotlin/dev/minios/ocremote/di/DomainModule.kt` | 添加 McpRepository @Binds |
| `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListViewModel.kt` | 添加 MCP 状态和操作方法 |
| `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListScreen.kt` | 添加 NavigationBar + HorizontalPager + 重构 |

---

## Task 1: 添加 compose-foundation 依赖

**Files:**
- Modify: `app/build.gradle.kts:116-126`

- [ ] **Step 1: 添加 foundation 依赖**

在 `app/build.gradle.kts` 的 compose 依赖块中（line 122 `material3` 之后），添加：

```kotlin
    implementation("androidx.compose.foundation:foundation")
```

完整上下文（line 116-127）应为：
```kotlin
    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2026.05.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material:material-icons-extended")
```

- [ ] **Step 2: 验证编译**

Run: `.\gradlew :app:compileDevDebugKotlin` (timeout 120s)
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/build.gradle.kts
git commit -m "build: add compose-foundation dependency for HorizontalPager"
```

---

## Task 2: Domain 层 — McpServerStatus 模型 + McpRepository 接口

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/domain/model/McpServerStatus.kt`
- Create: `app/src/main/kotlin/dev/minios/ocremote/domain/repository/McpRepository.kt`

- [ ] **Step 1: 创建 McpServerStatus 数据类**

```kotlin
package dev.minios.ocremote.domain.model

data class McpServerStatus(
    val name: String,
    val type: String,         // "local" | "remote"
    val status: String,       // connected | disabled | failed | needs_auth | needs_client_registration
    val command: List<String>? = null,  // local type
    val url: String? = null,            // remote type
)
```

- [ ] **Step 2: 创建 McpRepository 接口**

```kotlin
package dev.minios.ocremote.domain.repository

import dev.minios.ocremote.domain.model.McpServerStatus

interface McpRepository {
    suspend fun getMcpServers(): Result<List<McpServerStatus>>
    suspend fun toggleMcpServer(name: String, connect: Boolean): Result<Boolean>
}
```

- [ ] **Step 3: 验证编译**

Run: `.\gradlew :app:compileDevDebugKotlin` (timeout 120s)
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/domain/model/McpServerStatus.kt app/src/main/kotlin/dev/minios/ocremote/domain/repository/McpRepository.kt
git commit -m "feat: add McpServerStatus model and McpRepository interface"
```

---

## Task 3: Data 层 — DTO + Config 扩展

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/data/dto/response/McpResponses.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/data/dto/response/ConfigResponses.kt`

- [ ] **Step 1: 创建 MCP 响应 DTO**

```kotlin
package dev.minios.ocremote.data.dto.response

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class McpStatusEntry(
    val status: String  // connected | disabled | failed | needs_auth | needs_client_registration
)

@Serializable
data class McpServerConfig(
    val type: String? = null,
    val command: List<String>? = null,
    val enabled: Boolean = true,
    val url: String? = null,
    val environment: Map<String, String>? = null,
    val headers: Map<String, String>? = null
)
```

- [ ] **Step 2: 扩展 ServerConfigResponse 添加 mcp 字段**

在 `ConfigResponses.kt` 的 `ServerConfigResponse` data class 中添加 mcp 字段。修改后的完整文件：

```kotlin
package dev.minios.ocremote.data.dto.response

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ServerConfigResponse(
    @SerialName("disabled_providers") val disabledProviders: List<String> = emptyList(),
    @SerialName("enabled_providers") val enabledProviders: List<String>? = null,
    val model: String? = null,
    @SerialName("small_model") val smallModel: String? = null,
    @SerialName("default_agent") val defaultAgent: String? = null,
    val mcp: Map<String, McpServerConfig>? = null
)
```

> 注意：`McpServerConfig` 已在 `McpResponses.kt` 中定义，此处引用。由于 `mcp` 字段默认为 null，现有反序列化不受影响。

- [ ] **Step 3: 验证编译**

Run: `.\gradlew :app:compileDevDebugKotlin` (timeout 120s)
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/data/dto/response/McpResponses.kt app/src/main/kotlin/dev/minios/ocremote/data/dto/response/ConfigResponses.kt
git commit -m "feat: add MCP DTOs and extend ServerConfigResponse with mcp field"
```

---

## Task 4: Data 层 — OpenCodeApi MCP 方法 + McpRepositoryImpl

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/data/api/OpenCodeApi.kt`
- Create: `app/src/main/kotlin/dev/minios/ocremote/data/repository/McpRepositoryImpl.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/di/DomainModule.kt`

- [ ] **Step 1: 在 OpenCodeApi 中添加 3 个 MCP API 方法**

在文件末尾（`RestSessionStatusInfo` data class 之前或 class 的最后一个方法之后）添加一个新 section：

```kotlin
    // ============ MCP ============

    suspend fun getMcpStatus(conn: ServerConnection): Map<String, McpStatusEntry> {
        return httpClient.get("${conn.baseUrl}/mcp") {
            conn.auth(this)
        }.body()
    }

    suspend fun connectMcpServer(conn: ServerConnection, name: String): Boolean {
        return httpClient.post("${conn.baseUrl}/mcp/$name/connect") {
            conn.auth(this)
        }.body()
    }

    suspend fun disconnectMcpServer(conn: ServerConnection, name: String): Boolean {
        return httpClient.post("${conn.baseUrl}/mcp/$name/disconnect") {
            conn.auth(this)
        }.body()
    }
```

需要在文件顶部添加 import：
```kotlin
import dev.minios.ocremote.data.dto.response.McpStatusEntry
```

> 参考：`conn.auth(this)` 模式与现有方法一致（如 `getHealth`、`fetchSessionStatus`）。

- [ ] **Step 2: 创建 McpRepositoryImpl**

```kotlin
package dev.minios.ocremote.data.repository

import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.dto.response.McpServerConfig
import dev.minios.ocremote.domain.model.McpServerStatus
import dev.minios.ocremote.domain.repository.McpRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class McpRepositoryImpl @Inject constructor(
    private val api: OpenCodeApi
) : McpRepository {

    override suspend fun getMcpServers(): Result<List<McpServerStatus>> = runCatching {
        val conn = api.getCurrentConnection()
        val statusMap = api.getMcpStatus(conn)
        val configMap = api.getConfig(conn).mcp ?: emptyMap()

        statusMap.map { (name, entry) ->
            val config = configMap[name]
            McpServerStatus(
                name = name,
                type = config?.type ?: "local",
                status = entry.status,
                command = config?.command,
                url = config?.url,
            )
        }
    }

    override suspend fun toggleMcpServer(name: String, connect: Boolean): Result<Boolean> = runCatching {
        val conn = api.getCurrentConnection()
        if (connect) {
            api.connectMcpServer(conn, name)
        } else {
            api.disconnectMcpServer(conn, name)
        }
    }
}
```

> 注意：`api.getCurrentConnection()` 和 `api.getConfig()` 的调用方式需要与 OpenCodeApi 现有模式一致。如果 OpenCodeApi 没有这两个方法，需要调整——查看 OpenCodeApi 中如何获取当前 ServerConnection（通常是通过 EventDispatcher 或方法参数传入）。实际实现时可能需要将 ServerConnection 作为参数传入，而不是从 api 内部获取。

**重要**：实际编码时需要确认 `api.getCurrentConnection()` 是否存在。如果不存在，McpRepositoryImpl 的方法签名可能需要改为接收 ServerConnection 参数。查看 OpenCodeApi 中其他方法（如 `fetchSessionStatus`）如何获取连接——它们都接收 `conn: ServerConnection` 参数。因此应遵循相同模式：

```kotlin
@Singleton
class McpRepositoryImpl @Inject constructor(
    private val api: OpenCodeApi
) : McpRepository {

    private var connection: ServerConnection? = null

    fun setConnection(conn: ServerConnection) {
        connection = conn
    }

    private fun requireConnection(): ServerConnection =
        connection ?: throw IllegalStateException("ServerConnection not set")

    override suspend fun getMcpServers(): Result<List<McpServerStatus>> = runCatching {
        val conn = requireConnection()
        // ... 同上
    }

    override suspend fun toggleMcpServer(name: String, connect: Boolean): Result<Boolean> = runCatching {
        val conn = requireConnection()
        // ... 同上
    }
}
```

- [ ] **Step 3: 在 DomainModule.kt 添加 @Binds**

在 `DomainModule.kt` 末尾（`bindSettingsRepository` 之后）添加：

```kotlin
    @Binds
    abstract fun bindMcpRepository(impl: McpRepositoryImpl): McpRepository
```

需要在文件顶部添加 import：
```kotlin
import dev.minios.ocremote.data.repository.McpRepositoryImpl
import dev.minios.ocremote.domain.repository.McpRepository
```

- [ ] **Step 4: 验证编译**

Run: `.\gradlew :app:compileDevDebugKotlin` (timeout 120s)
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/data/api/OpenCodeApi.kt app/src/main/kotlin/dev/minios/ocremote/data/repository/McpRepositoryImpl.kt app/src/main/kotlin/dev/minios/ocremote/di/DomainModule.kt
git commit -m "feat: add MCP API methods, McpRepositoryImpl, and DI binding"
```

---

## Task 5: ViewModel 层 — 扩展 SessionListViewModel

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListViewModel.kt`

- [ ] **Step 1: 添加 MCP 相关字段和方法**

在 SessionListViewModel 的构造函数中添加 `McpRepository` 依赖：

```kotlin
@HiltViewModel
class SessionListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val eventDispatcher: EventDispatcher,
    private val api: OpenCodeApi,
    private val manageSessionUseCase: ManageSessionUseCase,
    private val deleteSessionUseCase: DeleteSessionUseCase,
    private val draftRepository: DraftRepository,
    private val mcpRepository: McpRepository  // 新增
) : ViewModel()
```

在 StateFlow 声明区域（约 line 112 之后）添加：

```kotlin
    private val _mcpServers = MutableStateFlow<List<McpServerStatus>>(emptyList())
    val mcpServers: StateFlow<List<McpServerStatus>> = _mcpServers.asStateFlow()

    private val _mcpLoading = MutableStateFlow<String?>(null)
    val mcpLoading: StateFlow<String?> = _mcpLoading.asStateFlow()
```

在 ViewModel 末尾添加方法：

```kotlin
    fun loadMcpServers() {
        viewModelScope.launch {
            mcpRepository.getMcpServers()
                .onSuccess { _mcpServers.value = it }
                .onFailure { /* 保持上次状态或空列表 */ }
        }
    }

    fun toggleMcpServer(name: String) {
        if (_mcpLoading.value == name) return  // 防止重复操作
        val server = _mcpServers.value.find { it.name == name } ?: return
        val connect = server.status != "connected"
        _mcpLoading.value = name

        viewModelScope.launch {
            mcpRepository.toggleMcpServer(name, connect)
                .onSuccess {
                    // 刷新全部状态
                    mcpRepository.getMcpServers()
                        .onSuccess { _mcpServers.value = it }
                }
                .onFailure {
                    // 失败时不更新列表，保持当前状态
                }
            _mcpLoading.value = null
        }
    }
```

需要在文件顶部添加 import：
```kotlin
import dev.minios.ocremote.domain.model.McpServerStatus
import dev.minios.ocremote.domain.repository.McpRepository
```

- [ ] **Step 2: 验证编译**

Run: `.\gradlew :app:compileDevDebugKotlin` (timeout 120s)
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListViewModel.kt
git commit -m "feat: add MCP state and operations to SessionListViewModel"
```

---

## Task 6: UI 层 — McpServerRow 组件

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/McpServerRow.kt`

- [ ] **Step 1: 创建 McpServerRow Composable**

```kotlin
package dev.minios.ocremote.ui.screens.sessions.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.domain.model.McpServerStatus

@Composable
fun McpServerRow(
    server: McpServerStatus,
    isLoading: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Build,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = server.name,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = buildString {
                    append(server.type)
                    append(" · ")
                    append(statusDot(server.status))
                    append(" ")
                    append(server.status)
                },
                style = MaterialTheme.typography.bodySmall,
                color = statusColor(server.status)
            )
        }
        Switch(
            checked = server.status == "connected",
            onCheckedChange = { onToggle() },
            enabled = !isLoading
                    && server.status != "needs_auth"
                    && server.status != "needs_client_registration"
        )
    }
}

private fun statusDot(status: String): String = when (status) {
    "connected" -> "●"
    "disabled" -> "○"
    "failed" -> "●"
    "needs_auth" -> "●"
    "needs_client_registration" -> "●"
    else -> "○"
}

private fun statusColor(status: String): Color = when (status) {
    "connected" -> Color(0xFF4CAF50)     // Green
    "disabled" -> Color.Gray
    "failed" -> Color(0xFFF44336)         // Red
    "needs_auth", "needs_client_registration" -> Color(0xFFFFC107) // Yellow/Amber
    else -> Color.Gray
}
```

- [ ] **Step 2: 验证编译**

Run: `.\gradlew :app:compileDevDebugKotlin` (timeout 120s)
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/McpServerRow.kt
git commit -m "feat: add McpServerRow composable with status indicator and switch"
```

---

## Task 7: UI 层 — ServerSettingsContent 页面

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/ServerSettingsContent.kt`

- [ ] **Step 1: 创建 ServerSettingsContent Composable**

```kotlin
package dev.minios.ocremote.ui.screens.sessions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.domain.model.McpServerStatus
import dev.minios.ocremote.ui.screens.sessions.components.McpServerRow

@Composable
fun ServerSettingsContent(
    mcpServers: List<McpServerStatus>,
    mcpLoading: String?,
    onToggleMcp: (name: String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            Text(
                text = "MCP Servers",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }

        if (mcpServers.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No MCP servers configured",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        items(count = mcpServers.size, key = { mcpServers[it].name }) { index ->
            val server = mcpServers[index]
            McpServerRow(
                server = server,
                isLoading = mcpLoading == server.name,
                onToggle = { onToggleMcp(server.name) }
            )
        }
    }
}
```

- [ ] **Step 2: 验证编译**

Run: `.\gradlew :app:compileDevDebugKotlin` (timeout 120s)
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/ServerSettingsContent.kt
git commit -m "feat: add ServerSettingsContent with MCP server list"
```

---

## Task 8: UI 层 — 重构 SessionListScreen 添加 NavigationBar + HorizontalPager

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListScreen.kt`

> ⚠️ 这是最大的改动。SessionListScreen 约 523 行。遵循 ChatScreen Editing Protocol：Read → Edit → Compile → Commit。

- [ ] **Step 1: 读取 SessionListScreen.kt 完整内容**

先用 Read 工具读取完整文件，理解当前结构。

- [ ] **Step 2: 添加 import**

在文件顶部 import 区域添加：

```kotlin
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import kotlinx.coroutines.launch
```

- [ ] **Step 3: 修改 Scaffold 结构**

关键变更：
1. 在 Scaffold 函数之前创建 `pagerState = rememberPagerState(pageCount = { 2 })` 和 `coroutineScope = rememberCoroutineScope()`
2. Scaffold 添加 `bottomBar = { ... }` 块
3. 将现有 content（PullToRefreshBox + LazyColumn 等）包裹在 HorizontalPager 中
4. 搜索栏和 FAB 用 `AnimatedVisibility(pagerState.currentPage == 0)` 包裹
5. Page 1 放置 ServerSettingsContent

Scaffold 结构变为：

```kotlin
val pagerState = rememberPagerState(pageCount = { 2 })
val coroutineScope = rememberCoroutineScope()

Scaffold(
    snackbarHost = { ... },  // 保持不变
    topBar = { ... },        // 保持不变
    floatingActionButton = {
        AnimatedVisibility(visible = pagerState.currentPage == 0) {
            // 现有 FAB 代码不变
        }
    },
    bottomBar = {
        NavigationBar {
            NavigationBarItem(
                selected = pagerState.currentPage == 0,
                onClick = { coroutineScope.launch { pagerState.animateScrollToPage(0) } },
                icon = { Icon(Icons.Default.List, contentDescription = null) },
                label = { Text("Sessions") }
            )
            NavigationBarItem(
                selected = pagerState.currentPage == 1,
                onClick = {
                    coroutineScope.launch { pagerState.animateScrollToPage(1) }
                    viewModel.loadMcpServers()
                },
                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                label = { Text("Settings") }
            )
        }
    }
) { padding ->
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.padding(padding)
    ) { page ->
        when (page) {
            0 -> {
                // 现有 content：搜索栏 + PullToRefreshBox + LazyColumn
                // 搜索栏用 AnimatedVisibility 包裹
            }
            1 -> {
                ServerSettingsContent(
                    mcpServers = viewModel.mcpServers.collectAsState().value,
                    mcpLoading = viewModel.mcpLoading.collectAsState().value,
                    onToggleMcp = viewModel::toggleMcpServer
                )
            }
        }
    }
}
```

- [ ] **Step 4: 验证编译**

Run: `.\gradlew :app:compileDevDebugKotlin` (timeout 120s)
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListScreen.kt
git commit -m "feat: add NavigationBar + HorizontalPager to SessionListScreen"
```

---

## Task 9: 最终验证

- [ ] **Step 1: 完整编译检查**

Run: `.\gradlew :app:compileDevDebugKotlin` (timeout 120s)
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 构建 debug APK**

Run: `.\gradlew :app:assembleDevDebug` (timeout 300s)
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 验收清单自查**

对照 spec 的 Acceptance Criteria：
- [ ] SessionListScreen 底部显示 NavigationBar（2 Tab）
- [ ] 点击 Tab 或滑动可切换页面
- [ ] Settings Tab 时搜索栏和 FAB 消失
- [ ] MCP 列表加载并显示名称、类型、状态、Switch
- [ ] needs_auth/needs_client_registration 状态下 Switch disabled
- [ ] 空列表显示提示文本

- [ ] **Step 4: 最终 commit（如有修复）**
