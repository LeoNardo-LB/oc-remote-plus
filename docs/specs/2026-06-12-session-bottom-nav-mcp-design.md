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
    val enabled: Boolean,     // from config
    val command: List<String>? = null,  // local type
    val url: String? = null,            // remote type
)
```

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
data class McpStatusResponse(
    // Map<String, McpStatusEntry> — GET /mcp 返回
)

@Serializable
data class McpStatusEntry(
    val status: String
)
```

Config 层面：现有 `ServerConfigResponse` 需扩展 `mcp` 字段（`Map<String, McpServerConfig>`）。

**扩展 API**: `data/api/OpenCodeApi.kt`

```kotlin
suspend fun getMcpStatus(): Map<String, McpStatusEntry>
suspend fun connectMcpServer(name: String): Boolean
suspend fun disconnectMcpServer(name: String): Boolean
```

**新增实现**: `data/repository/McpRepositoryImpl.kt`

- 合并 `GET /mcp` (status) + `GET /config` (config) → `List<McpServerStatus>`
- `toggleMcpServer` 调 `connect`/`disconnect` API

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

## Material 3 Components

- `NavigationBar` + `NavigationBarItem` — 底部导航栏
- `Switch` — MCP 启停开关
- `HorizontalPager` — 页面切换（支持滑动）

## Key Decisions

1. **`NavigationBar` 仅在 SessionListScreen 内** — 不贯穿 Chat 等子页面
2. **`HorizontalPager` + `NavigationBar`** — 2 个 Tab 不需要嵌套 NavGraph 的复杂度
3. **不新建 ViewModel** — MCP 数据属于当前服务器连接，与 SessionList 共享上下文
4. **`POST /mcp/:name/connect|disconnect`** — 与 OpenCode 官方一致，运行时操作
5. **Toggle 后 `GET /mcp` 刷新** — 与 Web UI/TUI 一致，不依赖 SSE
6. **不处理 `needs_auth`** — TUI 也不处理，Android 端暂不实现 OAuth
7. **不做乐观更新** — 等 POST 完成后 GET 真实状态

## Files to Modify/Create

### Modify
- `app/src/main/kotlin/.../ui/screens/sessions/SessionListScreen.kt` — 添加 NavigationBar + HorizontalPager
- `app/src/main/kotlin/.../ui/screens/sessions/SessionListViewModel.kt` — 添加 MCP 状态和操作
- `app/src/main/kotlin/.../data/api/OpenCodeApi.kt` — 添加 MCP API 方法
- `app/src/main/kotlin/.../data/dto/response/ConfigResponses.kt` — 扩展 mcp 配置字段

### Create
- `app/src/main/kotlin/.../domain/model/McpServerStatus.kt`
- `app/src/main/kotlin/.../domain/repository/McpRepository.kt`
- `app/src/main/kotlin/.../data/dto/response/McpResponses.kt`
- `app/src/main/kotlin/.../data/repository/McpRepositoryImpl.kt`
- `app/src/main/kotlin/.../ui/screens/sessions/ServerSettingsContent.kt`
- `app/src/main/kotlin/.../ui/screens/sessions/components/McpServerRow.kt`
