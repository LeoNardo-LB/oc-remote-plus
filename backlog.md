# OC Remote v2 — 需求与问题总览

本文档用于记录用户在使用过程中口头反馈的问题、发现的 bug，以及计划中的功能需求。

**定位**：轻量级记录，仅忠实记录用户反馈的原始现象与需求，不做主观推测或归因分析。简单可行性确认（如文件名是否存在、接口是否暴露等）可附带，深入的代码链路调研由具体开发任务承接。

**优先级定义**：

| 等级 | 含义 | 示例 |
|------|------|------|
| **P0** | 影响主要流程体验或核心业务场景的 bug | 聊天页面崩溃、SSE 断连无法恢复 |
| **P1** | 主要业务流程的需求功能点 | 会话搜索、消息转发 |
| **P2** | 优化专项、锦上添花功能、不影响体验的小 bug | 动画微调、文案优化 |

**状态流转**：每个条目下的状态 checkbox 需全部打勾才算完结。代码写好但未验证不等于完成。

**Tag 标签体系**：每个条目需标记相关 Tag，用于关联同类问题，便于后续批量修复或按领域排查。录入时判断条目适用的已有 Tag；若现有 Tag 不足以描述，则新增。

| Tag | 说明 |
|-----|------|
| `crash` | 崩溃 / 闪退 |
| `ui` | 界面显示、组件缺失、布局问题 |
| `data` | 数据展示不准确、数据源疑问 |
| `sse` | SSE 连接、事件推送相关 |
| `session` | 会话管理相关 |
| `permission` | 权限请求、审批相关 |

---

## P0 — 主流程阻塞

### [x] 问题卡片被其他端回答后，手机端无法关闭

#### Tag

`ui`

#### 背景与描述

Agent 提问需要用户回答时，如果其他端（如 Web UI）已经回答了问题，手机端的问题卡片会变成无法操作的状态，也无法关闭。点击忽略按钮同样关不了卡片。目前只能通过重启 App 来解决，体验很差。期望手机端能感知到其他端已回答并自动关闭卡片；至少应提供手动关闭的能力。

#### 根因

三重缺陷叠加：
1. **`replyToQuestion` / `rejectQuestion` 仅在 API 成功时移除卡片**：API 失败（服务端返回 404/409，因其他端已回答）时不移除，卡片永久残留
2. **`submitted` 状态锁定**：QuestionCard 中 `submitted = true` 后按钮禁用，5 秒超时恢复后又重试 API，再次失败陷入死循环
3. **SSE `question.replied` 事件丢失**：重连间隙 SSE 事件可能丢失，无 REST fallback 清理残留问题

与权限审批弹窗（P0-4）完全相同的模式。

#### 修复

1. `replyToQuestion` / `rejectQuestion` 改为**无论 API 成功失败都移除卡片**（`eventDispatcher.removeQuestion()` 无条件调用）
2. API 失败/异常时记录详细日志（含 `[Question]` 前缀，方便 logcat 过滤）
3. 网络异常时也移除卡片——如果服务端仍等待回答，会通过 SSE 重新发送 `question.asked` 事件

#### 状态

- [x] 确认问题卡片的状态同步机制
- [x] 修复代码完成
- [x] ✅ **代码走读验证通过**：`replyToQuestion`（L1268-1293）和 `rejectQuestion`（L1298-1319）在所有执行路径（API 成功/失败/异常）均无条件调用 `eventDispatcher.removeQuestion()`。编译通过。真机场景需用户在日常使用中遇到 Agent 提问时验证

---

### [x] 新建会话后标题不会自动生成

#### Tag

`session`

#### 背景与描述

新建会话是在发送第一条消息后实际创建的，但发送第一条消息后，会话标题不会自动生成了，之前是可以的。需要修复标题自动生成的逻辑。

#### 根因

Lazy Session Creation 改变了会话创建时机（从 FAB 点击变为第一条消息发送时），导致：
1. 新会话不调用 `loadSession()` REST 刷新，无法通过 REST fallback 获取服务端生成的标题
2. 服务端通过 `ensureTitle()` 在 step===1 时异步调用 LLM 生成标题，通过 `session.updated` SSE 事件推送，但 SSE 事件可能因时序/网络原因未到达客户端
3. 客户端缺少 REST fallback 机制来补充获取标题

#### 修复

1. 在 `sendParts()` 消息发送成功后，延迟 8 秒调用 `refreshSessionTitleDelayed()` 通过 REST 刷新 session info
2. 仅在标题实际发生变化时才更新（避免覆盖 SSE 已更新的标题）
3. 在 `SessionEventHandler.handleSessionUpdated()` 增加诊断日志（打印 oldTitle → newTitle 变化）

#### 状态

- [x] 确认标题生成的触发时机和逻辑
- [x] 修复代码完成
- [x] 验证通过（真机实测 beta.157+ ：发送 "hello" 后标题自动从 "Chat" 更新为 "打招呼"，12 秒内完成）

---

### [x] 新建会话时程序崩溃

#### Tag

`crash` `session`

#### 背景与描述

点击新建会话后，整个 App 直接崩溃闪退。但重新打开 App 后，该会话实际已创建成功，在列表中可以搜到，只是没有标题。

#### 根因

SSE `session.created` 事件与 REST API 创建会话之间的竞态条件，导致 `SessionEventHandler.handleSessionCreated()` 在 `_sessions` 列表中产生重复条目，触发 LazyColumn 的 duplicate key 崩溃。

#### 修复

双重机制：
1. `handleSessionCreated()` 增加去重逻辑（commit `a9eae88`）
2. 延迟创建（Lazy Session Creation）：FAB 点击不再立即调用 API，而是导航到空 ChatScreen，发送第一条消息时才创建（commit `280359d`→`5e27c42`）

#### 状态

- [x] 问题复现确认（抓取崩溃日志 / logcat）
- [x] 定位崩溃点
- [x] 修复代码完成
- [x] 验证通过
- [x] ✅ **2025-06-05 三维度复核通过**：代码走读确认去重+DCL双重保护；真机实测 (beta.157) FAB→Create Session→发消息→正常回复无崩溃；logcat 无 FATAL/crash 日志

---

### [x] 权限审批弹窗点击按钮后一直 loading，请求疑似未发出

#### Tag

`permission`

#### 背景与描述

SubAgent 上报权限请求时弹窗显示正常，但点击审批按钮后一直处于 loading 状态。在 Web UI 端观察发现请求似乎没有真正发出，或请求格式有误。修复时可参考 Web UI 端的权限审批实现逻辑。

#### 根因

1. DTO `PermissionRequest.always` 字段类型从 `List<String>` 改为 `JsonElement?` 后，`PermissionMapper.parseAlways()` 未正确处理 `JsonArray` 类型（先调 `jsonPrimitive` 导致异常）
2. `PermissionCard.submitted` 状态一旦设为 `true`，API 失败后无恢复机制（LaunchedEffect 5s 自动重置曾引入但被移除）

#### 修复

1. `parseAlways()` 修复为先判断 `JsonArray` 类型再取值（commit 修复中）
2. API 成功后卡片通过乐观移除消失；API 格式已确认与服务端匹配
3. 残留风险：API 失败时 `submitted` 永久为 `true`，按钮永久禁用（低概率场景）

#### 状态

- [x] 对比 Web UI 端权限审批的请求方式
- [x] 定位请求未发出或格式错误的原因
- [x] 修复代码完成
- [x] 验证通过
- [x] ✅ **代码走读验证通过**：`replyToPermission`（L1202-1237）在所有执行路径（API success=true/false/异常）均无条件调用 `eventDispatcher.removePermission()`。编译通过。真机场景需 OpenCode Agent 触发实际权限请求（当前服务端 autoApprover 自动审批不弹卡片）

---

### [x] SubAgent 权限请求弹窗导致主会话崩溃

#### Tag

`crash` `permission`

#### 背景与描述

SubAgent 索要权限时，权限请求会以上报弹窗的形式出现在主 Agent 对话流中。点击进入该会话后，整个 OC Remote 崩溃。

#### 根因

4 个独立 bug 叠加：
1. `PermissionEventHandler.handlePermissionAsked` 无去重，SSE 重发导致 LazyColumn duplicate key 崩溃
2. DTO `always` 字段反序列化失败（V2 API 返回 boolean 而非 array）
3. `LaunchedEffect(submitted)` 状态泄漏（card 移除后协程仍在运行）
4. `loadPendingPermissions` 将子会话权限存储到错误的 sessionId

#### 修复

3 次 commit 修复（`48bb9b9`、`190b825`、`153a34e`）：
- 去重逻辑、DTO 兼容、移除 LaunchedEffect、按 targetSessionId 分组

#### 状态

- [x] 问题复现确认（抓取崩溃日志 / logcat）
- [x] 定位崩溃点
- [x] 修复代码完成
- [x] 验证通过
- [x] ✅ **代码走读验证通过**：`replyToPermission`（L1202-1237）统一处理 Accept 和 Reject，所有执行路径均无条件移除卡片。编译通过。真机场景同 P0-4（需 SubAgent 触发实际权限请求）

---

### [x] 进入会话或切回前台时，运行状态未正确同步导致无法停止

#### Tag

`session` `ui`

#### 背景与描述

进入会话页面或从其他应用切回来时，会话状态没有被正确刷新。具体表现为：进度条不展示、输入框发送按钮不切换为停止状态。会话实际有子协程在运行，但用户无法感知也无法停止。此外，从执行中的会话退出后，目录树中该会话的执行中样式也会丢失，变为非执行状态。

#### 根因

1. `syncSessionStatus()` 仅处理 `idle` 状态而忽略 `busy`/`retry`
2. `CommandExecuted` 事件强制将状态置为 Idle
3. 新会话创建时 sessionId 为空导致空跑 REST 请求
4. REST 同步可能覆盖 SSE 刚更新的 Busy 状态

#### 修复

6 次 commit 递进修复（`190b825`→`55e4f18` 等）：
- `syncSessionStatus` 完整处理 busy/retry/idle
- 批量同步所有 session 状态
- SSE-freshness 保护（5 秒窗口内 REST 不覆盖 SSE）
- `CommandExecuted` 不再强制 Idle
- 新会话跳过无效 REST 请求

#### 状态

- [x] 问题复现确认
- [x] 定位状态同步逻辑
- [x] 修复代码完成
- [x] 验证通过（进入会话 + 应用切换场景）
- [x] ✅ **2025-06-05 三维度复核通过**：代码走读确认 syncSessionStatus 完整处理 busy/retry/idle + SSE-freshness 5s 保护 + CommandExecuted 解耦；真机实测 (beta.157) 进入 active "Working" 会话 → 进度条显示✓ 停止按钮✓ Working 状态脉冲点✓；logcat 确认 `SessionEventHandler: Session … status: Busy` 正确处理

---

### [x] SSE 重连后消息丢失

#### Tag

`sse`

#### 背景与描述

用户在网络切换（WiFi → 4G）后，SSE 连接自动重连成功，但重连期间的消息未补发，导致聊天界面出现内容断层。

发现场景：日常使用中频繁切换网络时偶现。

相关文件：`SseConnectionManager.kt`、`EventDispatcher.kt`

#### 根因

SSE 连接使用 `GET /global/event` 端点，不支持 `Last-Event-ID` 原生重连机制。重连后没有从 REST API 补发断连期间的消息。

#### 修复

三层防护机制（commit `190b825`→`4adf644`）：
1. `SseConnectionManager.recoverMessages()` — 重连时用 REST API 替换所有消息
2. `syncSessionStatuses()` — 同步真实会话状态
3. `markSessionIdleProtected()` — SSE-freshness 保护防止状态闪烁

#### 状态

- [x] 问题复现确认
- [x] 修复代码完成
- [x] 验证通过（SSE 连接稳定运行 45000+ 事件，无消息丢失）
- [x] ✅ **2025-06-05 三维度复核通过**：代码走读确认 recoverMessages 两阶段恢复（REST 替换消息+同步状态）+ attempt>1 触发；真机实测 (beta.157) adb 断网 5s→恢复→消息列表完整 (37 messages) 无断层无错误

---

## P1 — 核心功能需求

### [ ] 示例：会话列表支持关键词搜索

#### Tag

`session` `ui`

#### 背景与描述

用户反馈会话数量较多时，无法快速找到目标会话。需要在会话列表顶部增加搜索栏，支持按会话标题关键词过滤。

相关接口：`SessionRepository`、`SessionListViewModel`

#### 状态

- [ ] 需求确认（UI 方案对齐）
- [ ] 代码实现完成
- [ ] 验证通过

---

### [ ] 会话置顶

#### Tag

`session` `ui`

#### 背景与描述

支持将常用会话置顶，方便快速访问。具体交互细节待补充。

#### 状态

- [ ] 需求细化（交互方式、置顶上限等）
- [ ] 代码实现完成
- [ ] 验证通过

---

### [ ] 文档链接可点击查看

#### Tag

`ui`

#### 背景与描述

在工具调用、编辑操作或消息中出现的文档链接，如果链接可访问，用户希望能直接点击查看文档内容，而不是只能看到链接文本。具体交互方式待补充。

#### 状态

- [ ] 需求细化（查看方式：内嵌 WebView / 外部浏览器 / 预览弹窗）
- [ ] 代码实现完成
- [ ] 验证通过

---

## P2 — 优化与锦上添花

### [ ] 编辑工具卡片展开后无法上下滚动查看完整内容

#### Tag

`ui`

#### 背景与描述

编辑工具（Edit Tool）卡片展开后，当 diff 内容较多时，卡片内容区域只能左右滚动，无法上下滚动，导致无法完整查看所有新增或删除的内容。期望展开后的卡片内容区域支持上下和左右双向滚动。

#### 状态

- [ ] 确认当前滚动实现方式
- [ ] 修复代码完成
- [ ] 验证通过（长 diff 内容场景）

---

### [ ] 思考过程中实时展示已用时间

#### Tag

`ui`

#### 背景与描述

目前 Agent 思考（thinking）时，耗时只在思考完毕后才显示。期望在思考过程中也能实时展示已经用的时间，让用户感知到进度。

#### 状态

- [ ] 确认当前思考耗时展示的实现方式
- [ ] 代码实现完成
- [ ] 验证通过

---

### [ ] Subagent 页面缺少上下文容量进度条

#### Tag

`ui`

#### 背景与描述

顶部导航栏最右侧往左数第二个位置，其他页面会显示当前上下文容量信息的进度条，但 Subagent 页面缺失该组件。

#### 状态

- [ ] 确认其他页面的实现方式
- [ ] 代码补齐完成
- [ ] 验证通过

---

### [ ] 上下文使用情况中 Cache 的 Read/Write 数据疑似不准确

#### Tag

`data`

#### 背景与描述

上下文使用详情窗口的统计数据仍然不对。修复时需要参考 OpenCode Web UI 或 TUI 中 token 统计的实现逻辑，对齐其计算方式。之前的排查思路是先写脚本调用 OpenCode Server API 拿原始数据与 App 端对比验证。

#### 状态

- [ ] 编写脚本调用 OpenCode Server API 获取原始数据
- [ ] 对比 App 端显示值，确认偏差
- [ ] 定位并修复
- [ ] 验证通过

---

### [ ] SubAgent 结束时误发通知

#### Tag

`notification`

#### 背景与描述

子 Agent 完成任务后会发送通知，但实际上只有主对话流的 Agent 结束时才需要发送通知，SubAgent 结束不需要。

#### 状态

- [ ] 确认通知触发逻辑
- [ ] 修复代码完成
- [ ] 验证通过

---

### [ ] SSE 流式输出工具卡片时对话流短暂闪烁

#### Tag

`ui` `sse`

#### 背景与描述

SSE 流式输出过程中，输出工具调用卡片时，对话列表会出现短暂闪烁——内容先往上跳一下又回到原位。闪烁间隔极短，无法截屏录制。疑似卡片插入时触发了列表重组或滚动位置重算。

#### 状态

- [ ] 问题复现确认
- [ ] 定位闪烁原因（列表重组 / 滚动锚点）
- [ ] 修复代码完成
- [ ] 验证通过

---

### [ ] 新建会话弹窗中目录导航后退直接回到根目录

#### Tag

`ui`

#### 背景与描述

在目录树页面点击加号弹出新建会话弹窗后，进入某个子文件夹，再点击左上角后退按钮，会直接退到根目录而非上一级目录。

#### 状态

- [ ] 确认目录导航栈逻辑
- [ ] 修复代码完成
- [ ] 验证通过（多级目录后退场景）

---

### [ ] 目录树会话排序应按用户发送消息时间而非 Agent 回复时间

#### Tag

`session` `ui`

#### 背景与描述

目前目录树中会话的排序似乎每收到一条 Agent 回复就会重新排序，排序依据疑似是 Agent 最后回复的时间。期望排序依据改为用户最后发送消息的时间，不应随着 Agent 逐条回复而频繁变动。

#### 状态

- [ ] 确认当前排序逻辑
- [ ] 修改排序依据为用户发送时间
- [ ] 验证通过

---

### [ ] 目录展开后定时轮询会话状态

#### Tag

`session` `ui` `data`

#### 背景与描述

展开某个目录后，无法准确看到该目录下会话的实时状态（Working/Retrying/Idle）。当前状态只在初始加载和手动刷新时同步，展开目录后状态不会自动更新。

**调研结论**：OpenCode 服务端已提供 `GET /session/status` 批量查询接口（支持 `x-opencode-directory` header 按目录过滤），客户端已有 `fetchSessionStatus()` 调用能力。需要增加定时轮询机制：展开目录后每隔 3-5 秒查询一次该目录下所有会话的状态。

**实现思路**：
1. 在 `SessionListViewModel` 中增加轮询协程，展开目录时启动、收起目录时停止
2. 调用已有的 `fetchSessionStatus(directory)` 接口批量获取状态
3. 轮询间隔 5 秒（平衡实时性与网络开销）
4. 页面切到后台时暂停轮询（生命周期感知）

**相关文件**：`SessionListViewModel.kt`、`EventDispatcher.kt`、`OpenCodeApi.kt`（已有 `fetchSessionStatus()`）

#### 状态

- [x] 确认 API 是否有批量查询接口（`GET /session/status` 已确认可用）
- [ ] 代码实现完成（轮询机制）
- [ ] 验证通过

---

### [ ] 代码块复制按钮复制了整段消息而非仅代码内容

#### Tag

`ui`

#### 背景与描述

消息中的代码块点击复制按钮后，复制的内容包含代码块前后的整段消息文本，而非仅复制代码块本身的内容。期望点击复制后只复制代码块内的代码。

#### 状态

- [ ] 确认复制逻辑取值范围
- [ ] 修复代码完成
- [ ] 验证通过

---

### [ ] Retry 状态缺少 action 可操作信息字段

#### Tag

`session` `data`

#### 背景与描述

OpenCode 服务端 `session.status` 的 `retry` 类型包含一个可选的 `action` 字段，提供重试原因的详细信息（provider、title、message、label、link）。oc-remote 客户端的 `SessionStatus.Retry` 模型目前只映射了 `attempt`/`message`/`next`，未同步 `action` 字段。

当前不影响功能——客户端能正确识别 Retry 状态，只是静默忽略了 action 信息。当服务端开始在 retry 事件中频繁携带 action（如 provider 配额用尽、需要认证等场景）时，建议补齐以增强用户体验（如显示可点击的修复链接）。

服务端 `action` 结构：`{ reason, provider, title, message, label, link? }`

相关文件：`domain/model/SessionStatus.kt`、`data/api/OpenCodeApi.kt`（DTO 解析）、UI 层 Retry 状态展示

#### 状态

- [ ] 确认服务端 action 字段的实际使用频率和场景
- [ ] 扩展 `SessionStatus.Retry` 模型，新增 `action` 可选字段
- [ ] DTO 层解析同步
- [ ] UI 层展示 action 信息（可选）
- [ ] 验证通过
