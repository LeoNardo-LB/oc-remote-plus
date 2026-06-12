# Session Bottom Navigation + MCP Control Panel

**Date**: 2026-06-12
**Status**: Approved

## Overview

在 SessionListScreen 内新增 Material 3 底部导航栏（`NavigationBar`），提供两个 Tab：会话目录和服务器级设置。服务器级设置页包含 MCP 服务器启停控制面板。

## Scope

- **Scope**: SessionListScreen 内部改造 + MCP API 集成
- **Out of scope**: 全局设置（仍在 HomeScreen TopBar 进入）、OAuth 认证流程、MCP 工具列表展示、MCP 配置编辑

## Navigation Structure

### Before

```
SessionListScreen (Scaffold)
├── TopAppBar (返回、服务器名、All/Archived 过滤)
├── 搜索栏
├── LazyColumn (会话目录树)
└── FAB (OpenProjectDialog)
```

### After

```
SessionListScreen (Scaffold)
├── TopAppBar (不变)
├── 搜索栏 (仅 Page 0 可见)
├── HorizontalPager (2 pages)
│   ├── Page 0: SessionListContent (现有内容抽成独立 Composable)
│   └── Page 1: ServerSettingsContent (新增)
├── FAB (仅 Page 0 可见)
└── NavigationBar (新增)
    ├── NavigationBarItem: Sessions (icon=List, selected=page==0)
    └── NavigationBarItem: Settings (icon=Settings, selected=page==1)
```

### Page Visibility

| Element       | Page 0 (Sessions) | Page 1 (Settings) |
|---------------|:-:|:-:|
| 搜索栏        | ✅ | ❌ |
| FAB           | ✅ | ❌ |
| TopAppBar     | ✅ | ✅ (不变) |
| NavigationBar | ✅ | ✅ |

搜索栏和 FAB 使用 `AnimatedVisibility` 跟随 page 切换平滑过渡。

## Server Settings Page (Page 1)

### Layout

```
LazyColumn {
  SectionHeader("MCP Servers")

  McpServerRow(
    name = "replicant",
    type = "local",           // local | remote
    status = "connected",     // connected | disabled | failed | needs_auth | needs_client_registration
    isLoading = false,
    onToggle = { ... }
  )

  McpServerRow(
    name = "playwright",
    type = "local",
    status = "disabled",
    isLoading = false,
    onToggle = { ... }
  )

  // ... other MCP servers
}
```

### McpServerRow Component

```
┌──────────────────────────────────────┐
│ 🔧  replicant               [Switch] │
│     local · ● connected              │
└──────────────────────────────────────┘
```

- **Icon**: `Icons.Default.Build` (复用现有 ToolCardRegistry 中 MCP 工具图标)
- **Title**: MCP 服务器名称
- **Subtitle**: `{type}` · 状态指示灯
  - ● 绿色 = `connected`
  - ○ 灰色 = `disabled`
  - ● 红色 = `failed`
  - ● 黄色 = `needs_auth` / `needs_client_registration`
- **Switch**: Material 3 `Switch`，loading 时 disabled
- **Loading state**: Switch disabled + subtitle 显示 "..." 或 spinning indicator

## MCP API

### Endpoints (verified against OpenCode server)

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/mcp` | GET | Returns all MCP servers with status: `Record<string, {status: string}>` |
| `/mcp/:name/connect` | POST | Connect (enable) a MCP server, returns `boolean` |
| `/mcp/:name/disconnect` | POST | Disconnect (disable) a MCP server, returns `boolean` |
| `/config` | GET | Full config including `mcp` field with server configs (type, command, enabled, url, etc.) |

### MCP Status Values (5-state)

```
connected | disabled | failed | needs_auth | needs_client_registration
```

### Toggle Flow

```
用户点击 Switch
  → setLoading(name)  // 防止重复操作
  → if (status == "connected")
      POST /mcp/:name/disconnect
    else
      POST /mcp/:name/connect
  → GET /mcp  // 刷新全部状态
  → clearLoading(name)
  → [on error] 显示 error 状态，clearLoading
```

### Initial Load

进入 Page 1 时：
1. `GET /mcp` — 获取所有服务器 status
2. `GET /config` — 获取 MCP 配置（type, command, url 等元信息）
3. 合并为 `List<McpServerStatus>`

### SSE

**不依赖 SSE `mcp.tools.changed`**。该事件仅用于工具列表变更通知，Web UI 和 TUI 都不用它来刷新 toggle 状态。与 OpenCode 官方实现一致。

## Architecture

遵循现有 Clean Architecture 三层结构。

### Domain Layer

**新增文件**: `domain/model/McpServerStatus.kt`

```kotlin
data class McpServerStatus(
    val name: String,
    val type: String,         // "local" | "remote"
    val status: String,       // 5-state
    val command: List<String>? = null,  // local type
    val url: String? = null,            // remote type
)
```

> 注：不包含 `enabled` 字段。UI 层直接依据 `status` 判断 Switch 状态（connected=开，其他=关），Toggle 操作也基于 status。config 中的 enabled 仅用于初始加载时决定是否显示，但运行时以 status 为准。

**新增接口**: `domain/repository/McpRepository.kt`

```kotlin
interface McpRepository {
    suspend fun getMcpServers(): Result<List<McpServerStatus>>
    suspend fun toggleMcpServer(name: String, connect: Boolean): Result<Boolean>
}
```

### Data Layer

**新增 DTO**: `data/dto/response/McpResponses.kt`

```kotlin
@Serializable
data class McpStatusEntry(
    val status: String  // connected | disabled | failed | needs_auth | needs_client_registration
)
```

Config 层面：现有 `ServerConfigResponse` 需扩展 `mcp` 字段：

```kotlin
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

GET /mcp 直接解析为 `Map<String, McpStatusEntry>`，不需要 McpStatusResponse 包装类。

**扩展 API**: `data/api/OpenCodeApi.kt`

```kotlin
suspend fun getMcpStatus(): Map<String, McpStatusEntry>
suspend fun connectMcpServer(name: String): Boolean
suspend fun disconnectMcpServer(name: String): Boolean
```

**新增实现**: `data/repository/McpRepositoryImpl.kt`

- 构造函数：`class McpRepositoryImpl @Inject constructor(private val api: OpenCodeApi)`
- 合并 `GET /mcp` (status) + `GET /config` (config) → `List<McpServerStatus>`
- `toggleMcpServer` 调 `connect`/`disconnect` API

**DI 注册**: `di/DomainModule.kt`

- 新增 `@Binds abstract fun bindMcpRepository(impl: McpRepositoryImpl): McpRepository`

### ViewModel

**扩展**: `SessionListViewModel.kt`

- 新增 `mcpServers: StateFlow<List<McpServerStatus>>`
- 新增 `mcpLoading: StateFlow<String?>`（当前正在操作的 MCP 名称，null 表示无操作）
- 新增 `loadMcpServers()`
- 新增 `toggleMcpServer(name: String)`
- 共享同一个服务器上下文（serverUrl/username/password），不新建 ViewModel

### UI Layer

**重构**: `SessionListScreen.kt`
- 现有内容抽成 `SessionListContent(...)` composable
- 新增 `HorizontalPager` + `NavigationBar`
- 搜索栏和 FAB 根据 `pagerState.currentPage` 控制可见性

**新增**: `ui/screens/sessions/components/McpServerRow.kt`
- 单行 MCP 服务器显示 + Switch toggle

**新增**: `ui/screens/sessions/ServerSettingsContent.kt`
- Page 1 内容，LazyColumn + SectionHeader + McpServerRow 列表

## Dependencies

需要新增的 Gradle 依赖：

```kotlin
// HorizontalPager 位于此包中
implementation("androidx.compose.foundation:foundation")
```

> 注：项目通过 Compose BOM 管理版本，无需指定具体版本号。

## Material 3 Components

- `NavigationBar` + `NavigationBarItem` — 底部导航栏
- `Switch` — MCP 启停开关
- `HorizontalPager`（`androidx.compose.foundation.pager`）— 页面切换（支持滑动）

## Key Decisions

1. **`NavigationBar` 仅在 SessionListScreen 内** — 不贯穿 Chat 等子页面
2. **`HorizontalPager` + `NavigationBar`** — 2 个 Tab 不需要嵌套 NavGraph 的复杂度
3. **不新建 ViewModel** — MCP 数据属于当前服务器连接，与 SessionList 共享上下文
4. **`POST /mcp/:name/connect|disconnect`** — 与 OpenCode 官方一致，运行时操作
5. **Toggle 后 `GET /mcp` 刷新** — 与 Web UI/TUI 一致，不依赖 SSE
6. **不处理 `needs_auth` / `needs_client_registration`** — TUI 也不处理，Android 端暂不实现 OAuth 及客户端注册流程。这两种状态下 Switch 显示为 disabled（不可操作），副标题显示黄色指示灯
7. **不做乐观更新** — 等 POST 完成后 GET 真实状态
8. **`GET /config` 获取 MCP 元信息** — 合并 status + config 为 `McpServerStatus`，一次进入 Page 1 时加载

## Error Handling & Edge Cases

### 网络错误
- POST connect/disconnect 失败时：Switch 回弹到原位 + Snackbar 显示错误信息
- 超时：使用 Ktor 默认超时配置
- 并发防护：`toggleMcpServer` 方法入口 `if (mcpLoading.value == name) return`，Switch 在 loading 时 disabled

### 服务器不支持 MCP API
- GET /mcp 返回 404 时：NavigationBar 仍显示 2 个 Tab，Page 1 显示 "This server does not support MCP management" 提示文本

### 空列表
- GET /mcp 返回空 Map 时：SectionHeader 下显示 "No MCP servers configured" 空状态提示

### 连接断开
- SSE 连接断开时保持上次已知 MCP 状态；重新连接后自动刷新 MCP 列表

## Acceptance Criteria

### 导航栏
- [ ] SessionListScreen 底部显示 NavigationBar，包含 2 个 Tab（Sessions、Settings）
- [ ] 点击 Tab 或左右滑动可切换页面，NavigationBar 选中状态同步
- [ ] 切换到 Settings Tab 时搜索栏和 FAB 平滑消失；切回 Sessions 时恢复
- [ ] 进入 Chat 等子页面后 NavigationBar 消失

### MCP 列表
- [ ] 进入 Settings Tab 时加载 MCP 服务器列表，每个显示名称、类型、状态指示灯、Switch
- [ ] 状态指示灯正确反映 5 种状态（绿=connected、灰=disabled、红=failed、黄=needs_auth/needs_client_registration）
- [ ] needs_auth 和 needs_client_registration 状态下 Switch disabled
- [ ] 无 MCP 服务器时显示空状态提示

### MCP Toggle
- [ ] 点击 Switch 切换 connected → disabled（POST disconnect）或 disabled/failed → connected（POST connect）
- [ ] Toggle 过程中 Switch disabled，其他 MCP 服务器不受影响
- [ ] Toggle 成功后列表刷新为最新状态
- [ ] Toggle 失败时 Switch 回弹 + Snackbar 提示错误
- [ ] 编译通过：`.\gradlew :app:compileDevDebugKotlin`

## Files to Modify/Create

### Modify
- `app/build.gradle.kts` — 添加 `compose-foundation` 依赖
- `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListScreen.kt` — 添加 NavigationBar + HorizontalPager
- `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListViewModel.kt` — 添加 MCP 状态和操作
- `app/src/main/kotlin/dev/minios/ocremote/data/api/OpenCodeApi.kt` — 添加 MCP API 方法
- `app/src/main/kotlin/dev/minios/ocremote/data/dto/response/ConfigResponses.kt` — 扩展 mcp 配置字段
- `app/src/main/kotlin/dev/minios/ocremote/di/DomainModule.kt` — 添加 McpRepository @Binds 绑定

### Create
- `app/src/main/kotlin/dev/minios/ocremote/domain/model/McpServerStatus.kt`
- `app/src/main/kotlin/dev/minios/ocremote/domain/repository/McpRepository.kt`
- `app/src/main/kotlin/dev/minios/ocremote/data/dto/response/McpResponses.kt`
- `app/src/main/kotlin/dev/minios/ocremote/data/repository/McpRepositoryImpl.kt`
- `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/ServerSettingsContent.kt`
- `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/McpServerRow.kt`
