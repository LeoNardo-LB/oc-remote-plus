# 会话列表两级导航改造设计

**日期**: 2026-06-01
**状态**: Draft

## 背景

当前会话列表界面（SessionListScreen）将所有项目的会话以扁平列表展示。用户点 + 右下角按钮时，需弹出目录浏览器（OpenProjectDialog）选择工作目录才能新建会话。

### 问题

1. **搜索路径无法导航**：在目录浏览器的搜索栏输入 Windows 路径（如 `D:\Projects`）搜不到结果，因为搜索被限定在 home 目录下做文件名匹配，而非路径导航
2. **交互不直观**：用户期望先看到"项目目录列表"，再点进某个目录看会话，而非一开始就是扁平会话列表 + 弹窗选目录

## 目标

将会话列表改为两级导航结构：

1. **第一级 — 项目目录列表**：展示所有有会话的项目目录，支持浏览和添加新目录
2. **第二级 — 项目会话列表**：展示某个目录下的会话，支持新建会话

同时修复搜索栏的路径导航问题。

## 交互设计

### 第一级：项目目录列表

```
┌─────────────────────────────────────┐
│  ← 服务器名                      [+] │
│                                     │
│  📁 ~/code/my-app                   │
│     3 个会话 · 最近: 2 小时前        │
│                                     │
│  📁 D:\Work\backend                 │
│     1 个会话 · 最近: 昨天            │
│                                     │
│  📁 ~/open-source/tool              │
│     5 个会话 · 最近: 3 天前          │
└─────────────────────────────────────┘
```

- 顶部：服务器名称 + 返回按钮
- 列表：每个项目目录一行，显示路径（`~` 替代 home）、会话数、最近活动时间
- 右上角 + 按钮：打开 OpenProjectDialog 浏览/新建目录，选完后直接在该目录新建会话并跳转
- 点击某行：进入第二级（该目录的会话列表）
- 长按某行：进入选择模式（支持删除目录/批量操作）
- 空状态：显示"暂无项目，点击 + 添加"

### 第二级：项目会话列表

```
┌─────────────────────────────────────┐
│  ← ~/code/my-app               [+]  │
│                                     │
│  💬 实现登录功能                     │
│     2 小时前 · 12 条消息             │
│                                     │
│  💬 修复 Bug #123                   │
│     昨天 · 8 条消息                  │
│                                     │
│  💬 重构数据库层                     │
│     3 天前 · 25 条消息               │
│                                     │
│  空状态: "暂无会话，点击 + 新建"      │
└─────────────────────────────────────┘
```

- 顶部：项目目录路径 + 返回按钮
- 列表：复用现有 SessionRow 组件
- 右下角 + 按钮：直接在该目录下新建会话（无需再选目录），调用 `createNewSession(directory=currentProject.directory)`
- 长按：进入选择模式（重命名/删除会话）
- 点返回：回到第一级

### 导航逻辑变化

```
当前：
  HomeScreen → SessionListScreen（扁平会话列表）
    FAB+ → 弹窗选目录 → 新建会话 → ChatScreen

改造后：
  HomeScreen → SessionListScreen（项目目录列表）
    点击目录 → 同一页面切换为会话列表模式
      FAB+ → 直接新建会话 → ChatScreen
    右上+ → OpenProjectDialog → 选目录 → 新建会话 → ChatScreen
```

不新增导航路由，在同一个 SessionListScreen 内通过状态切换两级视图。

## 数据模型变更

### UiState

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

enum class ListMode { PROJECTS, SESSIONS }

data class ProjectGroup(
    val directory: String,
    val displayName: String,
    val sessionCount: Int,
    val lastUpdated: Long?,
)
```

### ViewModel 变更

新增方法：

- `selectProject(directory: String)` — 切换到第二级，过滤该目录的会话
- `navigateBack()` — 从第二级返回第一级
- `createSessionInCurrentProject()` — 在当前选中目录下新建会话

修改方法：

- `loadSessions()` — 改为构建 `projectGroups` 列表（按 directory 分组统计会话数和最近时间）
- `createNewSession(directory)` — 保持不变

移除/简化：

- `NewSessionQuickDialog` 不再需要（新建会话时目录已确定）

## OpenProjectDialog 搜索栏修复

### 问题根因

1. 搜索始终以 `homeDir` 为基准，输入 `D:\xxx` 在 home 目录下搜索不到
2. `findFiles` API 是文件名匹配，不是路径导航
3. 路径拼接假设 Unix 风格（`/` 分隔符）

### 修复方案

在搜索 `LaunchedEffect` 中增加路径检测逻辑：

```kotlin
// 路径检测：Windows 盘符 / Unix 绝对路径 / ~ 开头
fun isPathLike(input: String): Boolean {
    val trimmed = input.trim()
    return trimmed.startsWith("/") ||              // Unix 绝对路径
           trimmed.startsWith("~") ||              // home 目录
           trimmed.matches(Regex("[A-Za-z]:.*"))   // Windows 盘符
}
```

**路径模式**：调用 `listDirectories(resolvedPath)` 列出目录内容
**搜索模式**：调用 `searchDirectories(query, homeDir)` 保持现有模糊搜索

路径解析规则：
- `D:\` → 直接列出 D 盘根目录
- `D:\Pro` → 拆分为 `D:\` + `Pro`，列出 D 盘下以 `Pro` 开头的子目录
- `~/co` → `~` 替换为 homeDir，列出 home 下以 `co` 开头的子目录
- `/home/user` → 列出该目录内容

展示适配：路径模式下复用现有的 `DirectoryRow` 组件展示目录列表。

## 修改文件清单

| 文件 | 改动类型 | 说明 |
|------|----------|------|
| `SessionListViewModel.kt` | 修改 | 启用项目分组、增加两级状态、路径导航逻辑 |
| `SessionListScreen.kt` | 修改 | 双模式渲染（项目列表 / 会话列表） |
| `OpenProjectDialog.kt` | 修改 | 修复搜索栏路径导航 |
| `NewSessionQuickDialog.kt` | 移除 | 不再需要 |
| `ui/screens/sessions/components/ProjectHeader.kt` | 可能修改 | 复用为项目行组件 |

## 不变的部分

- 导航路由（NavGraph、SessionListRoute、SessionListNav）不变
- API 层（OpenCodeApi）不变
- Domain 模型不变
- ChatScreen 不变
- SSE 事件处理不变
- 选择模式（长按多选、重命名、删除）在第二级保持不变

## 风险与注意事项

1. **ChatScreen 返回行为**：从 ChatScreen 按返回时，应回到第二级（该项目的会话列表）而非第一级
2. **SSE 实时更新**：新会话创建的 SSE 事件需正确更新当前项目的会话列表
3. **Windows 路径兼容**：路径拼接和比较需同时支持 `\` 和 `/` 分隔符
4. **空项目处理**：用户可能通过 OpenProjectDialog 选择一个没有会话的新目录，此时应自动跳转到第二级空状态
