# OC Remote 全量差距修复设计

> 对比 OpenCode WebUI/TUI，系统性修复 OC Remote Android 客户端的 85 项功能差距。
> 日期：2026-06-04
> 状态：已批准

## 背景

基于对 OpenCode 源码（`D:/Develop/code/github/opencode`）与 OC Remote 源码的 5 维度并行深度对比分析，共识别 85 项差距（P0×12, P1×30, P2×43）。本设计文档规划全量修复方案。

## 约束

- **纯客户端改动**：不改动 OpenCode 服务端代码
- **服务端版本要求**：需要 OpenCode 服务端版本 ≥ `v2.0.0`（session.next.* 事件自 SSE 协议 v2 起支持）
- **SSE 事件协议**：参考 OpenCode 源码 `packages/core/src/session/event.ts`（事件定义）和 `packages/opencode/src/server/routes/instance/httpapi/handlers/event.ts`（SSE 端点）
- **不引入新 UI 依赖**：仅使用 Material 3 + Compose 原生组件
- **严格测试覆盖**：P0/P1 每项修复必须有单元测试（MockK + Turbine + coroutines-test）
- **遵守 ChatScreen Editing Protocol**：Read → Edit → compileDevDebugKotlin → commit
- **适度重构拆分**：允许将 ChatScreen 大组件提取到 `chat/components/` 独立文件

## 关键发现

`session.next.*` 系列 25 种细粒度事件（工具执行进度、推理链生命周期、step 状态等）**已确认通过 SSE 端点完整暴露**。这些事件使用 `EventV2.define()` 定义，通过 `publishEvent()` → `listeners` 路径推送到 SSE handler，外部客户端可正常接收。

证据链：`session/event.ts`(定义) → `processor.ts`(发布) → `EventV2Bridge`(桥接) → `publishEvent()`(分发) → `eventResponse()`(SSE 订阅)

## 架构决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| session.next.* 事件建模 | 新建 `SessionNextEvent` sealed class，嵌套在 SseEvent 中 | 避免 SseEvent 从 ~44 膨胀到 ~70，按职责分组 |
| 网络状态检测 | Hilt `@Singleton NetworkMonitor` + `ConnectivityManager` | 单例共享状态，可注入 ViewModel 和 SSE 重连 |
| API 结果类型 | 新建 `ApiResult<T>` sealed class（Success/Error 两个变体，Loading 状态由 ViewModel 层单独管理） | 统一成功/错误状态，取代裸异常抛出 |
| API 重试策略 | 应用层 `retryWithPolicy()` 协程扩展 | 比拦截器更灵活，能处理 rate limit 业务逻辑 |
| 权限记忆存储 | DataStore (Preferences) | 轻量级，适合 key-value 规则存储 |
| ChatScreen 拆分 | 按职责提取 Composable 到 `chat/components/` | 遵循已有包结构，不改变架构模式 |
| 工具渲染扩展 | `ToolCardResolver` 接口 + Map 注册表 | 新增工具不需要改 ChatScreen 主文件 |

## 分层方案

按能力维度分 6 层，每层可独立发布。**注**：层间存在 3 组事实依赖：(1) 1.8 依赖 1.4 NetworkMonitor；(2) 3.11 依赖 1.3 retryWithPolicy 和 1.9 SessionRetryCard；(3) 5.1 PermissionAutoApprover 需要 Hilt 模块注册。每层发布前确保上游依赖已就绪，层内自包含。

### Layer 1: 网络容错基建（10 项）

**目标**：让客户端在任意网络条件下可靠运行，不崩溃、不假死、错误信息清晰。

**新增文件**：~8 个 | **改动文件**：~6 个 | **测试文件**：~8 个

| # | 修复项 | 技术方案 | 改动范围 | P |
|---|--------|---------|---------|---|
| 1.1 | SSE 读取级超时 | `SseClient` 的 SSE 行读取循环中（`connectToInstanceEvents`/`connectToGlobalEvents`）添加 `withTimeoutOrNull(READ_TIMEOUT_MS)` 包装单次 `readRawLineBytes()` 等效操作，默认超时 30s（通过 AppSettings 可配置）。超时后视为半死连接，break 触发重连循环（复用 1.3 的 RetryPolicy 退避策略）。连续超时 5 次后进入较长的冷却期（5 分钟）避免频繁重连 | `SseClient.kt` | P0 |
| 1.2 | HTTP 状态码分类 | 新建 `ApiResult<T>` sealed class（Success/Error），`mapHttpError()` 按状态码映射：401→AuthError, 403→ForbiddenError, 404→NotFoundError, 429→RateLimitError(retryAfterMs), 5xx→ServerError | `data/api/`, `domain/model/` | P0 |
| 1.3 | API 请求重试 | 新建 `RetryPolicy` data class（maxAttempts=3, initialDelay=500ms, maxDelay=10s, factor=2.0）+ `suspend fun <T> retryWithPolicy(policy, block)` 扩展函数。瞬态错误识别：IOException / SocketTimeoutException / 5xx / RateLimitError。**重试全部耗尽后**：返回 `ApiResult.Error`（携带最后一次异常），由调用方（ViewModel/Repository）决定——网络类错误展示 1.9 SessionRetryCard + 重试按钮，业务类错误展示对应错误提示 | `data/api/` | P0 |
| 1.4 | 网络状态检测 | 新建 `NetworkMonitor` @Singleton Hilt 模块，`ConnectivityManager.NetworkCallback` → `StateFlow<NetworkState>`（Available/Losing/Lost/Unavailable）。注入 ViewModel 和 OpenCodeConnectionService | `di/`, `data/` | P0 |
| 1.5 | Rate Limit 处理 | `mapHttpError()` 中解析 `retry-after` / `retry-after-ms` 响应头，返回 `RateLimitError(retryAfterMillis)`。UI 层展示倒计时提示 | `data/api/` | P1 |
| 1.6 | 全局未捕获异常 | `Application.onCreate()` 中设置 `Thread.setDefaultUncaughtExceptionHandler`，记录到文件 + 重启主 Activity（带错误信息 Intent） | `Application.kt` | P1 |
| 1.7 | 连接错误全屏 UI | 新建 `ConnectionErrorScreen` composable：不可达服务器名称 + 重试倒计时（每秒） + 其他可用服务器切换列表 | `ui/screens/` | P1 |
| 1.8 | 网络恢复自动重连 | `OpenCodeConnectionService` 注入 `NetworkMonitor`，`NetworkState.Available` 时触发 SSE 重连 + REST 状态刷新。**防抖**：`StateFlow.debounce(2000ms)` 或在重连逻辑中检查 `isReconnecting` 标志，避免快速切换导致的并发重连。上次重连未完成时新事件排队等待 | `service/` | P1 |
| 1.9 | 重试状态 UI | 新建 `SessionRetryCard` composable：CircularProgressIndicator + "Retrying (attempt N)..." + 倒计时秒数 + 错误消息（>80字符截断） | `ui/screens/chat/components/` | P1 |
| 1.10 | 多服务器健康检查 | `ServerRepository` 定期 `checkHealth()`（可配置间隔，默认 30s），暴露 `StateFlow<Map<ServerId, ServerHealth>>`。HomeScreen 展示各服务器在线状态 | `data/repository/` | P2 |

### Layer 2: 会话管理增强（9 项）

**目标**：规模化会话管理，支持大量会话的高效操作。

**新增文件**：~5 个 | **改动文件**：~6 个 | **测试文件**：~5 个

| # | 修复项 | 技术方案 | 改动范围 | P |
|---|--------|---------|---------|---|
| 2.1 | 会话列表搜索 | `OpenCodeApi.listSessions()` 添加 `search: String?` 参数（服务端已支持）。UI：`SearchBar` composable（Material3）+ 实时搜索（debounce 300ms） | `OpenCodeApi.kt`, `SessionListVM.kt`, `SessionListScreen.kt` | P0 |
| 2.2 | 分页/懒加载 | `listSessions()` 添加 `cursor: String?` / `limit: Int` 参数。`SessionListVM` 维护 cursor 列表。UI：`LazyColumn` + `derivedStateOf { lastVisibleIndex }` 触发 `loadMore()` | `OpenCodeApi.kt`, `SessionListVM.kt` | P0 |
| 2.3 | 上下文使用量可视化 | 新建 `ContextUsageBar` composable：Material3 `LinearProgressIndicator`，颜色按使用比例变化（<70% primary, 70-90% tertiary, >90% error）。数据源：从 `Part.StepFinish.Tokens` 累积，或调用 `GET /session/{id}/context` API | `ui/screens/chat/components/` | P0 |
| 2.4 | 会话归档/取消归档 | `OpenCodeApi.updateSession()` 支持 `archivedAt` 字段。`SessionListScreen` 添加 archived 过滤切换（顶部 FilterChip）。`Session.isArchived` 已有模型字段 | `OpenCodeApi.kt`, `SessionListVM.kt`, `SessionListScreen.kt` | P0 |
| 2.5 | 消息/Part 删除 | 新增 `deleteMessage(sessionId, messageId)` 和 `deleteMessagePart(sessionId, messageId, partIndex)` API 调用。UI：长按消息弹出 ContextMenu（删除选项）。`EventDispatcher` 处理 `message.removed` 事件更新本地状态 | `OpenCodeApi.kt`, `ChatVM.kt` | P1 |
| 2.6 | Revert/Unrevert 完善 | 验证现有 `undoMessage()` / `redoMessage()` 与 API 对齐。补充：撤销后恢复输入框文本、SSE `session.updated` 事件后刷新消息列表 | `ChatVM.kt` | P1 |
| 2.7 | 会话导入 | 服务端存在 `/session/import` 和 `/session/share` API：`OpenCodeApi` 新增 `importSession(shareUrl)`（解析 share URL → `ExportData` → 调用 import API）。如果 share URL 为旧版本格式，降级为解析后逐条重建；降级路径在首次验证 API 兼容性后确定。UI：Settings 或 SessionList 添加"导入"按钮 | `OpenCodeApi.kt`, `SessionListVM.kt` | P1 |
| 2.8 | System Prompt 查看 | 新建 `SystemPromptDialog` composable（底部 Sheet）。数据源：会话配置 API 或从消息列表中提取 system 类型消息 | `ui/screens/chat/dialog/` | P1 |
| 2.9 | 全局列表搜索+分页 | 确保全局列表（跨项目）也支持 `search` + `cursor` 参数。`listGlobalSessions()` 同步修改 | `OpenCodeApi.kt`, `SessionListVM.kt` | P0 |

### Layer 3: 事件处理精细化（12 项）

**目标**：消费全部 25 种 `session.next.*` 事件，实现细粒度实时状态追踪。

**这是最复杂的层**，涉及新事件类型体系、流式状态机、多个新 UI 组件。

**核心架构变更**：

```kotlin
// SseEvent.kt 中新增嵌套 sealed class
sealed class SessionNextEvent {
    abstract val sessionId: String

    // Agent/Model 切换
    data class AgentSwitched(override val sessionId: String, val agent: String) : SessionNextEvent()
    data class ModelSwitched(override val sessionId: String, val model: String) : SessionNextEvent()

    // Text 流式生命周期
    data class TextStarted(override val sessionId: String, val partId: String) : SessionNextEvent()
    data class TextDelta(override val sessionId: String, val partId: String, val text: String) : SessionNextEvent()
    data class TextEnded(override val sessionId: String, val partId: String) : SessionNextEvent()

    // Reasoning 流式生命周期
    data class ReasoningStarted(override val sessionId: String, val reasoningId: String) : SessionNextEvent()
    data class ReasoningDelta(override val sessionId: String, val reasoningId: String, val text: String) : SessionNextEvent()
    data class ReasoningEnded(override val sessionId: String, val reasoningId: String) : SessionNextEvent()

    // Tool 执行生命周期
    data class ToolInputStarted(override val sessionId: String, val toolCallId: String, val toolName: String) : SessionNextEvent()
    data class ToolInputDelta(override val sessionId: String, val toolCallId: String, val delta: String) : SessionNextEvent()
    data class ToolCalled(override val sessionId: String, val toolCallId: String, val toolName: String, val input: String) : SessionNextEvent()
    data class ToolProgress(override val sessionId: String, val toolCallId: String, val progress: JsonElement) : SessionNextEvent()
    data class ToolSuccess(override val sessionId: String, val toolCallId: String, val output: String?) : SessionNextEvent()
    data class ToolFailed(override val sessionId: String, val toolCallId: String, val error: String) : SessionNextEvent()

    // Step 生命周期
    data class StepStarted(override val sessionId: String, val stepIndex: Int, val agent: String?, val model: String?) : SessionNextEvent()
    data class StepEnded(override val sessionId: String, val stepIndex: Int, val finishInfo: StepFinishInfo?) : SessionNextEvent()
    data class StepFailed(override val sessionId: String, val stepIndex: Int, val error: String) : SessionNextEvent()

    // Shell 命令
    data class ShellStarted(override val sessionId: String, val command: String) : SessionNextEvent()
    data class ShellEnded(override val sessionId: String, val output: String?) : SessionNextEvent()

    // Compaction（上下文压缩）
    data class CompactionStarted(override val sessionId: String, val reason: String) : SessionNextEvent()
    data class CompactionDelta(override val sessionId: String, val text: String) : SessionNextEvent()
    data class CompactionEnded(override val sessionId: String, val summary: String?) : SessionNextEvent()

    // 其他
    data class Prompted(override val sessionId: String, val messageId: String) : SessionNextEvent()
    data class Retried(override val sessionId: String, val attempt: Int, val reason: String?) : SessionNextEvent()
    data class Synthetic(override val sessionId: String, val event: String) : SessionNextEvent()
}
```

**新增文件**：~10 个 | **改动文件**：~8 个 | **测试文件**：~10 个

| # | 修复项 | 技术方案 | 改动范围 | P |
|---|--------|---------|---------|---|
| 3.1 | session.next.* 事件解析 | `SseClient.parseEventByType()` 中新增 `session.next.` 前缀判断：匹配时调用 `parseSessionNextEvent(json)` 工厂函数。该函数按 type 字段第三段（`session.next.{category}.{action}`）映射到 `SessionNextEvent` 具体子类。事件 payload（properties 字段）反序列化为对应 data class。**异常处理**：JSON 解析失败 → Log.w + 跳过该事件继续 SSE 循环；未知子类型 → 封装为 `SessionNextEvent.Unknown(type, rawJson)` 可选展示；字段缺失 → 使用默认值/可选类型，不中断流 | `SseClient.kt`, `SseEvent.kt` | P0 |
| 3.2 | EventDispatcher 扩展 | 新增 `handleSessionNextEvent(event: SessionNextEvent)` 方法，按事件类型更新对应 session 的 UI 状态。**线程安全**：使用 `MutableStateFlow.update { }` 原子更新确保 SSE 事件（Dispatchers.IO）和 REST 回调（Dispatchers.Main）并发安全。完整的 [事件类型 → UI 状态字段] 映射表见下方附录 | `EventDispatcher.kt` | P0 |
| 3.3 | Text 流式状态机 | 新建 `StreamingStateTracker` 类：维护 `Map<PartId, StreamingState>` (Idle → Started → Streaming → Ended)。`textDelta()` 自动检测并插入 textStart（幂等性）。未正常关闭的流标记为 `Interrupted`。**清理策略**：Ended/Interrupted 状态保留最近 50 条后淘汰最旧条目；会话销毁时全量清理该 session 的所有状态 | `data/repository/` | P1 |
| 3.4 | Reasoning 流式追踪 | 复用 `StreamingStateTracker`，reasoning 事件独立追踪。支持多个并行推理块（reasoningId 区分） | `data/repository/` | P1 |
| 3.5 | Tool 执行进度展示 | 新建 `ToolProgressCard` composable：显示工具名 + 状态（InputStarted/InputDelta/Called/Progress/Success/Failed）+ 进度动画。替换现有 `ToolCallCard` 的 Pending 状态 | `ui/screens/chat/components/` | P1 |
| 3.6 | Step 进度追踪 | `StepProgressIndicator` composable：Material3 `LinearProgressIndicator`（indeterminate 模式）+ "Step N" + 当前 agent/model 信息。数据源：`StepStarted`/`StepEnded` 事件 | `ui/screens/chat/components/` | P1 |
| 3.7 | Agent/Model 实时显示 | 在 ChatScreen 顶部栏（现有 title 区域）显示当前 agent + model，来自 `AgentSwitched`/`ModelSwitched` 事件。使用 `Text` composable + `Info` icon | `ChatScreen.kt` | P2 |
| 3.8 | Markdown 流式优化 | 新建 `StreamingMarkdownState`：追踪未闭合代码围栏数量。分离已确认文本（可安全渲染）和未确认文本（live tail）。已确认部分正常渲染，未确认部分用 monospace Text 展示 | `ui/screens/chat/markdown/` | P1 |
| 3.9 | Compaction 实时展示 | `CompactionBanner` composable："Compressing context..." + 进度动画 + 原因（auto/manual）。`CompactionEnded` 后显示 "Context compressed" 通知 | `ui/screens/chat/components/` | P2 |
| 3.10 | Shell 事件处理 | `ShellStarted` → 终端标签状态更新为 "Running: {command}"。`ShellEnded` → 恢复空闲状态 | `EventDispatcher.kt` | P2 |
| 3.11 | Retry 事件展示 | `Retried` 事件 → `SessionRetryCard` 更新重试次数和原因 | `EventDispatcher.kt` | P1 |
| 3.12 | 事件序列验证 | 可选实现。`EventDispatcher` 中维护 `lastEventSeq: Map<SessionId, Long>`，检测 gap 时触发 REST 刷新。低优先级，SSE 本身有 DB 事务序列保证 | `EventDispatcher.kt` | P2 |

### Layer 4: 消息渲染补全（9 项）

**目标**：补全消息交互和工具渲染的体验差距。

**新增文件**：~8 个 | **改动文件**：~4 个 | **测试文件**：~6 个

| # | 修复项 | 技术方案 | 改动范围 | P |
|---|--------|---------|---------|---|
| 4.1 | 消息发送乐观更新 | `ChatVM.sendMessage()` 调用 API 前插入 `Message.User` 到 `_messages` StateFlow（标记 `isPending=true`）。SSE `message.updated` 确认后替换为正式消息。发送失败时移除 pending 消息 + 恢复输入。**超时保护**：pending 消息附带时间戳，SSE 断开 60s 后批量标记为 `SendFailed`（展示"发送失败"+重试按钮）。SSE 重连后通过 REST API（`fetchSessionStatus`）检查消息是否已实际送达 | `ChatVM.kt` | P1 |
| 4.2 | 发送失败恢复输入 | `sendMessage()` catch 块中保存 draft 到 `_restoredDraft` StateFlow。UI 监听并填充到输入框。与乐观更新联动：失败时移除 pending 消息 + 恢复输入 | `ChatVM.kt`, `ChatScreen.kt` | P1 |
| 4.3 | Assistant 复制按钮 | `CopyButton` composable（Material3 `IconButton` + `Icons.Default.ContentCopy`），使用 `ClipboardManager.setPrimaryClip(ClipData.newPlainText(label, text))`。放在 assistant 消息右上角 | `ui/screens/chat/components/` | P1 |
| 4.4 | 消息元信息显示 | `MessageMetaInfo` composable：模型名 + 耗时（来自时间戳差值）+ token 数（来自 `Part.StepFinish.Tokens`）。放在 assistant 消息底部，使用 `Alpha.MUTED` 透明度 | `ui/screens/chat/components/` | P1 |
| 4.5 | GlobToolCard | 新建 `GlobToolCard` composable：显示 glob 模式 + 匹配文件数 + 可展开文件列表（LazyColumn, max height 200dp）。使用 Material3 `Card` + `AnimatedVisibility` 控制展开/折叠 | `ui/screens/chat/components/` | P1 |
| 4.6 | WebFetchToolCard | 新建 `WebFetchToolCard`：显示 URL + 抓取内容摘要（markdown 渲染前 500 字符 + "展开更多"） | `ui/screens/chat/components/` | P1 |
| 4.7 | WebSearchToolCard | 新建 `WebSearchToolCard`：搜索查询 + 结果列表（标题 + URL + 摘要）。使用 `LazyColumn` + `ListItem` | `ui/screens/chat/components/` | P1 |
| 4.8 | ApplyPatchToolCard | 新建 `ApplyPatchToolCard`：文件路径 + diff 预览（使用现有 `DiffView` 组件） | `ui/screens/chat/components/` | P1 |
| 4.9 | 工具渲染扩展机制 | 新建 `ToolCardResolver` 接口：`fun resolve(toolName: String): @Composable (ToolPart) -> Unit`。默认实现用 Map 注册（可 `put` 扩展）。`PartContent` 中通过 resolver 获取渲染函数，而非 when 分支 | `ui/screens/chat/components/` | P1 |

### Layer 5: 权限与工具体验（8 项）

**目标**：补全权限管理、用量追踪、思考链展示的体验。

**新增文件**：~7 个 | **改动文件**：~5 个 | **测试文件**：~6 个

| # | 修复项 | 技术方案 | 改动范围 | P |
|---|--------|---------|---------|---|
| 5.1 | 权限自动批准记忆 | `PermissionAutoApprover` 类：读取 DataStore 中 `Set<PermissionRule>`（toolName + sessionId + directoryPattern）。`PermissionAsked` 事件到达时先检查匹配，命中则自动回复 `once`，否则展示 UI。"always" 规则持久化到 DataStore | `data/repository/`, `di/` | P0 |
| 5.2 | "Always" 二次确认 | `AlwaysConfirmDialog` composable（Material3 `AlertDialog`）：显示工具名 + 将匹配的目录模式 + "确认始终允许"按钮。从 `PermissionCard` 的 `onAlways` 触发 | `ui/screens/chat/dialog/` | P1 |
| 5.3 | Reject 附加消息 | `RejectWithMessageDialog` composable：`OutlinedTextField` 输入拒绝原因 + "拒绝"按钮。从 `PermissionCard` 的 `onReject` 触发（子代理时才显示输入框） | `ui/screens/chat/dialog/` | P1 |
| 5.4 | 权限配置 UI | Settings 中添加"权限规则"页：显示已保存的 auto-approve 规则列表 + 删除按钮 | `ui/screens/settings/` | P2 |
| 5.5 | Token/成本跟踪 UI | `TokenUsageCard` composable：显示 input/output/reasoning/cache.read/cache.write token 数。使用 `Row` + `LinearProgressIndicator`。数据源：累积 `Part.StepFinish.Tokens` | `ui/screens/chat/components/` | P1 |
| 5.6 | Reasoning 思考链展示 | 增强 `ReasoningBlock`：添加 "Thinking..." 动画（Material3 `CircularProgressIndicator` + `Alpha.MUTED`）+ 可折叠展开（`AnimatedVisibility`）+ 流式文本。使用 `StreamingStateTracker` 的 reasoning 状态 | `ui/screens/chat/components/` | P1 |
| 5.7 | 工具调用链展示 | 增强 `TaskToolCard`：展开后显示子代理 prompt + 执行状态 + 输出摘要。链接到子会话（如有） | `ui/screens/chat/components/` | P1 |
| 5.8 | Diff 渲染增强 | `DiffView` 组件添加语法高亮色：使用现有 `Color.DiffAdded` / `Color.DiffRemoved` 颜色 token，行号显示，添加/删除行数统计 | `ui/screens/chat/components/` | P1 |

### Layer 6: 边缘场景打磨（37 项 P2）

不展开详细方案，按需选取、逐步推进。包含：

- **SSE/连接**：事件序列验证、实例释放级联刷新、连接健康检查、Shell 实时输出
- **消息**：Shell 命令模式、自定义命令、工具分组折叠、消息内联编辑、超长输出截断、Diff 虚拟化
- **会话**：导出选项（Redact/Sanitize）、Workspace 分组、Diff 查看、会话树可视化
- **错误**：离线请求队列、服务器版本检查、错误日志导出
- **工具**：RepoClone/LSP/Skill/Plan 等低频工具卡片、工具输出折叠交互
- **权限**：目录级权限 UI、权限信息详细展示
- **其他**：多行输入、输入历史、Agent Part UI、FileEdited/McpToolsChanged UI 反应

## 文件影响估算

| 层级 | 新增文件 | 改动文件 | 测试文件 | 预估代码行 |
|------|---------|---------|---------|-----------|
| Layer 1 | ~8 | ~6 | ~8 | ~1,500 |
| Layer 2 | ~5 | ~6 | ~5 | ~1,200 |
| Layer 3 | ~10 | ~8 | ~10 | ~2,500 |
| Layer 4 | ~8 | ~4 | ~6 | ~1,800 |
| Layer 5 | ~7 | ~5 | ~6 | ~1,500 |
| Layer 6 | 按需 | 按需 | 按需 | ~2,000 |
| **合计** | **~38** | **~29** | **~35** | **~10,500** |

## 测试策略

- **框架**：JUnit 4 + MockK 1.14.9 + Turbine 1.2.1 + coroutines-test（与现有 35 个测试文件保持一致）
- **覆盖率目标**：P0 项 100% 核心路径覆盖，P1 项关键路径覆盖
- **测试重点**：
  - `RetryPolicy` 重试逻辑（瞬态识别、退避计算、最大次数）
  - `mapHttpError()` 状态码映射
  - `NetworkMonitor` 状态变化
  - `EventDispatcher` 新事件处理
  - `StreamingStateTracker` 状态机转换
  - `PermissionAutoApprover` 规则匹配
  - `ApiResult` 成功/失败分支
- **验证命令**：`.\gradlew :app:testDevDebugUnitTest --rerun` + `.\gradlew :app:compileDevDebugKotlin`
- **覆盖率度量**：JaCoCo / Kover（待集成），行覆盖率阈值 ≥ 80%（P0 项 ≥ 90%）

### 分层验收标准

每层完成时必须通过以下验收 checklist，方可进入下一层。

#### Layer 1 验收标准
- [ ] SSE 读取超时后 5s 内检测到连接断开并触发重连
- [ ] 模拟 401/403/404/429/5xx 各一次，UI 展示对应的差异化错误信息
- [ ] 模拟瞬态 5xx 3 次后重试耗尽，UI 展示 SessionRetryCard + 手动重试按钮
- [ ] 飞行模式开关各一次，UI 展示连接错误全屏页 + 网络恢复后 5s 内自动重连
- [ ] 429 响应带 retry-after 头时，UI 展示倒计时 + 到时自动重试

#### Layer 2 验收标准
- [ ] 输入搜索词 300ms 后列表刷新，空结果展示空状态
- [ ] 滑动到底部自动加载下一页（验证 100+ session 分页）
- [ ] 归档/取消归档后列表立即更新
- [ ] 上下文使用量超过 90% 时颜色变为 error 色

#### Layer 3 验收标准
- [ ] 收到 25 种 session.next.* 事件各一次，EventDispatcher 正确分发且无未处理事件
- [ ] 收到格式错误的 session.next 事件时不中断 SSE 流
- [ ] 收到未知子类型时不崩溃，记录 log
- [ ] Text started → delta×5 → ended 完整序列：流式文本正确增量展示
- [ ] Tool input started → called → success 完整序列：ToolProgressCard 正确更新
- [ ] Compaction started → delta → ended：CompactionBanner 正确展示

#### Layer 4 验收标准
- [ ] 发送消息后 0ms 内（乐观更新）UI 即显示 pending 消息
- [ ] SSE 确认后 pending→confirmed 平滑过渡无闪烁
- [ ] 发送失败后输入框恢复原文本
- [ ] 长按消息弹出 ContextMenu 含删除选项

#### Layer 5 验收标准
- [ ] 规则命中时自动回复 once，无需用户交互
- [ ] "always"规则持久化到 DataStore，重启后生效
- [ ] DataStore 读取失败时全部降级为手动确认
- [ ] Reasoning 思考链在流式过程中显示"Thinking..."动画 + 可折叠

## 风险与缓解

| 风险 | 缓解措施 |
|------|---------|
| ChatScreen.kt 过大（1100+ 行，经 Phase 2 拆分后已从 8200+ 降至 1100） | 大量新组件提取到 `chat/components/` 独立文件，进一步减少 ChatScreen 体积 |
| session.next.* 事件数据结构不确定 | 先用 `JsonObject` 接收，逐步添加类型安全的 data class。SseClient 日志记录所有未识别字段 |
| 权限自动批准的安全性 | 自动批准仅限 `once`（单次会话内有效），`always` 需要二次确认对话框 |
| 大量新代码引入回归风险 | 严格逐层测试：每完成一层运行全量单元测试 + 编译检查 |
| DataStore 权限规则数据丢失 | DataStore 默认有原子写入保证；启动时如果读取失败则降级为手动确认 |
| 上游 OpenCode API 变更导致客户端适配失效 | 核心 API 调用集中封装在 OpenCodeApi.kt，变更时仅需修改单点；事件支付按需消费，未知事件类型静默跳过 |
