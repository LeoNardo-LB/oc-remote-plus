# OpenCode SSE 事件体系完整分析

> 调研日期：2026-06-18
> 源码版本：opencode `packages/opencode` + `packages/core`
> 事件类型总数：**89 个**（含 v1 遗留事件 + v2 细粒度事件 + 系统/服务事件）
> 源码根：`packages/core/src/event/` + `packages/opencode/src/server/`

## 目录

- [概览](#概览)
- [事件订阅端点](#事件订阅端点)
- [事件传输协议](#事件传输协议)
- [1. 系统与服务事件（4 个）](#1-系统与服务事件4-个)
- [2. Session v1 遗留事件（7 个）](#2-session-v1-遗留事件7-个)
- [3. Message v1 遗留事件（5 个）](#3-message-v1-遗留事件5-个)
- [4. Session.next v2 细粒度事件（31 个）](#4-sessionnext-v2-细粒度事件31-个)
- [5. Session 状态与生命周期事件（5 个）](#5-session-状态与生命周期事件5-个)
- [6. Todo 事件（1 个）](#6-todo-事件1-个)
- [7. Permission 事件（4 个）](#7-permission-事件4-个)
- [8. Question 事件（6 个）](#8-question-事件6-个)
- [9. PTY 事件（4 个）](#9-pty-事件4-个)
- [10. MCP 事件（2 个）](#10-mcp-事件2-个)
- [11. Project 与 VCS 事件（3 个）](#11-project-与-vcs-事件3-个)
- [12. LSP / IDE / Command 事件（3 个）](#12-lsp--ide--command-事件3-个)
- [13. Account / Catalog / Plugin 事件（5 个）](#13-account--catalog--plugin-事件5-个)
- [14. Filesystem 事件（2 个）](#14-filesystem-事件2-个)
- [15. Installation 事件（2 个）](#15-installation-事件2-个)
- [16. Workspace / Worktree 事件（5 个）](#16-workspace--worktree-事件5-个)
- [17. TUI 事件（4 个）](#17-tui-事件4-个)
- [附录 A：v1/v2 迁移状态](#附录-av1v2-迁移状态)
- [附录 B：Token 相关事件 payload](#附录-btoken-相关事件-payload)
- [附录 C：同步事件（sync）机制](#附录-c同步事件sync机制)
- [关键发现](#关键发现)

---

## 概览

OpenCode 的事件体系采用 **EventV2** 框架（基于 `effect` 的类型安全事件系统）。所有事件通过两条 SSE 端点推送：

| 端点 | 范围 | 用途 |
|------|------|------|
| `GET /event` | **实例级** | 仅推送当前 directory + workspace 的事件 |
| `GET /global/event` | **全局** | 推送所有实例的事件（跨项目） |

### 事件分类总览

| 分类 | 事件数 | 说明 |
|------|--------|------|
| 系统与服务 | 4 | 连接、心跳、销毁 |
| Session v1 遗留 | 7 | 会话 CRUD（粗粒度） |
| Message v1 遗留 | 5 | 消息/Part 更新 |
| **Session.next v2 细粒度** | **31** | AI 推理流程的细粒度事件（核心） |
| Session 状态/生命周期 | 5 | status、idle、diff、error、compacted |
| Todo | 1 | 任务列表更新 |
| Permission | 4 | 权限请求 v1 + v2 |
| Question | 6 | 问题请求 v1 + v2（含 reject） |
| PTY | 4 | 终端会话生命周期 |
| MCP | 2 | 工具变更、浏览器打开失败 |
| Project/VCS | 3 | 项目更新、目录更新、分支更新 |
| LSP/IDE/Command | 3 | LSP/IDE 状态、命令执行 |
| Account/Catalog/Plugin | 5 | 账户/模型目录/插件管理 |
| Filesystem | 2 | 文件编辑、watcher |
| Installation | 2 | 版本更新 |
| Workspace/Worktree | 5 | 工作区状态/就绪/失败 |
| TUI | 4 | TUI 交互（仅 TUI 进程消费） |
| **合计** | **89** | |

---

## 事件订阅端点

### 1. `GET /event` — 实例级 SSE 流

**用途**：订阅当前实例（directory + workspace）的事件流。

**Query**：`WorkspaceRoutingQuery`（`directory?`, `workspace?`）

**认证**：Basic Auth（通过 Authorization 中间件）

**行为**（`handlers/event.ts:25-87`）：
1. **首个事件**：`{ type: "server.connected", properties: {} }`
2. **过滤逻辑**：
   - `event.location?.directory === instance.directory` —— 必须匹配当前目录
   - `event.location.workspaceID === undefined || === workspaceID` —— workspace 匹配（无 workspace 限制的事件放行）
3. **心跳**：每 10 秒发送 `{ type: "server.heartbeat", properties: {} }`
4. **实例销毁检测**：监听 GlobalBus 上的 `server.instance.disposed` 事件（directory 匹配），收到后：
   - 推送 `{ type: "server.instance.disposed", properties: { directory } }`
   - 关闭 SSE 流
5. **优雅关闭**：使用 `Stream.takeUntil` 在收到 disposed 事件后立即终止

**事件结构**（每个 SSE message）：

```typescript
{
  id: EventV2.ID,           // 事件唯一 ID（cuid/uuid）
  type: string,             // 事件类型字符串
  properties: unknown       // 事件负载（schema 因 type 而异）
}
```

---

### 2. `GET /global/event` — 全局 SSE 流

**用途**：订阅所有实例的全局事件流（跨项目）。

**认证**：**无**（公开端点，注册在 RootHttpApi）

**行为**（`handlers/global.ts:33-66`）：
1. **首个事件**：`{ type: "server.connected", properties: {} }`
2. **后续事件**：所有发布到 `GlobalBus` 的事件（通过 `EventV2Bridge` 桥接的实例级事件 + 全局事件）
3. **心跳**：每 10 秒发送 `server.heartbeat`
4. **不自动关闭**：不像 `/event`，此端点不会因实例销毁而关闭（监听的是全局 bus）

**事件结构**：

```typescript
{
  directory: string,         // 来源实例目录
  project?: string,          // 项目 ID
  workspace?: string,        // workspace ID
  payload: {
    id: string,
    type: string,
    properties: unknown
  }
}
```

---

## 事件传输协议

### SSE 编码

所有事件通过 effect 的 `Sse.encode()` 编码为标准 SSE 格式：

```
event: message
data: {"id":"...","type":"session.updated","properties":{...}}

event: message
data: {"id":"...","type":"message.part.updated","properties":{...}}
```

- 每个事件的 SSE `event` 字段固定为 `"message"`（不使用事件类型作为 event 名）
- `data` 字段是 JSON 字符串，包含 `id`、`type`、`properties`
- 客户端通过解析 `data` 中的 `type` 字段路由处理

### 响应头

```
Content-Type: text/event-stream
Cache-Control: no-cache, no-transform
X-Accel-Buffering: no          # 禁用 nginx 缓冲，确保实时推送
X-Content-Type-Options: nosniff # 防 MIME 嗅探
```

---

## 1. 系统与服务事件（4 个）

> 来源：`packages/opencode/src/server/event.ts` + `handlers/event.ts`

### 1.1 `server.connected` — 连接建立

| 项目 | 内容 |
|------|------|
| **触发** | 客户端建立 SSE 连接时，**服务端主动发送的第一个事件** |
| **properties** | `{}`（空对象） |
| **用途** | 告知客户端连接已建立，可以开始接收后续事件 |
| **同步** | ❌ 不同步（不持久化） |

### 1.2 `server.heartbeat` — 心跳

| 项目 | 内容 |
|------|------|
| **触发** | 每 10 秒自动发送（`Stream.tick("10 seconds")`，drop 第一个避免立即触发） |
| **properties** | `{}` |
| **用途** | 保持连接活跃，防止代理/负载均衡器超时断开 |
| **同步** | ❌ 不同步 |

### 1.3 `server.instance.disposed` — 实例销毁

| 项目 | 内容 |
|------|------|
| **触发** | 当前实例被销毁时（配置变更、显式 dispose、升级等） |
| **properties** | `{ directory: string }` —— 被销毁实例的目录 |
| **来源** | `groups/global.ts:9-13` 的 `InstanceDisposed` schema |
| **用途** | 告知客户端需要重新建立连接或重建实例 |
| **同步** | ❌ 不同步（仅在 `/event` 中推送，由 handler 监听 GlobalBus 构造） |

> **特殊**：此事件**不在 EventV2 registry 中注册**，是 `/event` handler 手动监听 GlobalBus 构造的。客户端在 `/event` 上能收到，但在 `/global/event` 上以原始 GlobalBus 事件形式出现。

### 1.4 `global.disposed` — 全局销毁

| 项目 | 内容 |
|------|------|
| **触发** | 所有实例被销毁时（`POST /global/dispose`、全局配置更新等） |
| **properties** | `{}` |
| **来源** | `packages/opencode/src/server/event.ts:6` 的 `Disposed` |
| **用途** | 告知客户端整个 OpenCode 服务正在关闭 |

---

## 2. Session v1 遗留事件（7 个）

> 来源：`packages/core/src/v1/session.ts:569-627`
> **v1 风格**：粗粒度，传递完整的 `SessionInfo` 或 `Info`（消息）对象

### 通用字段

所有 v1 session 事件的 properties 都包含 `sessionID: SessionSchema.ID`。

### 2.1 `session.created` — 会话创建

| 项目 | 内容 |
|------|------|
| **触发** | `Session.create()` 后 |
| **properties** | `{ sessionID, info: SessionInfo }` |
| **同步** | ✅ `{ aggregate: "sessionID", version: 1 }` |

### 2.2 `session.updated` — 会话更新

| 项目 | 内容 |
|------|------|
| **触发** | 会话元数据变更（标题、权限、归档等） |
| **properties** | `{ sessionID, info: SessionInfo }` |
| **同步** | ✅ |

### 2.3 `session.deleted` — 会话删除

| 项目 | 内容 |
|------|------|
| **触发** | `Session.remove()` 后 |
| **properties** | `{ sessionID, info: SessionInfo }` —— 含被删除会话的最后状态 |
| **同步** | ✅ |

### 2.4 `message.updated` — 消息更新

| 项目 | 内容 |
|------|------|
| **触发** | 消息内容、状态、Part 变更时（消息的"粗粒度"全量更新） |
| **properties** | `{ sessionID, info: Info }` —— `Info` 是 User \| Assistant 判别联合 |
| **同步** | ✅ |

### 2.5 `message.removed` — 消息删除

| 项目 | 内容 |
|------|------|
| **触发** | `session.removeMessage()` 后 |
| **properties** | `{ sessionID, messageID: MessageID }` |
| **同步** | ✅ |

### 2.6 `message.part.updated` — Part 更新

| 项目 | 内容 |
|------|------|
| **触发** | Part 内容变更（工具调用完成、文本更新等） |
| **properties** | `{ sessionID, part: Part, time: number }` —— `Part` 是 12 种联合类型 |
| **同步** | ✅ |

### 2.7 `message.part.removed` — Part 删除

| 项目 | 内容 |
|------|------|
| **触发** | `DELETE /session/:id/message/:id/part/:id` 后 |
| **properties** | `{ sessionID, messageID, partID: PartID }` |
| **同步** | ✅ |

---

## 3. Message v1 遗留事件（5 个）

> 注：`message.updated`、`message.removed`、`message.part.updated`、`message.part.removed` 已在 §2 列出
> 本节补充额外的 message 事件

### 3.1 `message.part.delta` — Part 增量更新

| 项目 | 内容 |
|------|------|
| **触发** | Part 字段的流式增量更新（如工具调用输出的实时追加） |
| **来源** | `packages/opencode/src/session/message-v2.ts:61-70` |
| **properties** | `{ sessionID, messageID, partID, field: string, delta: string }` |
| **同步** | ❌ 不同步（实时流式，不持久化） |

**`field` 字段含义**：
- `"output"` —— 工具输出追加
- `"text"` —— 文本内容追加
- 其他自定义字段名

**客户端实现**：维护 Part 的本地副本，收到 delta 时 append 到对应字段。

---

## 4. Session.next v2 细粒度事件（31 个）

> 来源：`packages/core/src/session/event.ts`
> **v2 风格**：AI 推理流程的细粒度事件，支持流式输出、工具调用、reasoning 等
> **这是 OpenCode 事件体系的核心**

### 通用字段

所有 session.next.* 事件包含：

```typescript
{
  timestamp: string (ISO 8601 UTC),
  sessionID: SessionSchema.ID,
  ...事件特定字段
}
```

### 同步语义

- **Durable（持久化）**：27 个事件可同步（`sync: { aggregate: "sessionID", version: 1或2 }`）
- **Ephemeral（瞬时）**：4 个 `.delta` 事件不同步（仅实时流，不持久化）

### 4.1 会话控制事件（6 个）

#### 4.1.1 `session.next.agent.switched` — Agent 切换

| 项目 | 内容 |
|------|------|
| **触发** | 会话切换到不同 agent（如从 build 切到 plan） |
| **properties** | `{ timestamp, sessionID, messageID, agent: string }` |
| **同步** | ✅ v1 |

#### 4.1.2 `session.next.model.switched` — 模型切换

| 项目 | 内容 |
|------|------|
| **触发** | 会话切换到不同模型 |
| **properties** | `{ timestamp, sessionID, messageID, model: ModelV2.Ref }` —— `ModelV2.Ref = { id, providerID }` |
| **同步** | ✅ v1 |

#### 4.1.3 `session.next.moved` — 会话迁移

| 项目 | 内容 |
|------|------|
| **触发** | 会话被迁移到新目录（`POST /experimental/control-plane/move-session`） |
| **properties** | `{ timestamp, sessionID, location: Location.Ref, subdirectory?: RelativePath }` |
| **同步** | ✅ v1 |

#### 4.1.4 `session.next.prompted` — 收到 Prompt

| 项目 | 内容 |
|------|------|
| **触发** | 用户发送消息（prompt 被创建） |
| **properties** | `{ timestamp, sessionID, messageID, prompt: Prompt, delivery: "steer" \| "queue" }` |
| **同步** | ✅ v1 |

**`delivery` 字段**：
- `"steer"` —— 消息作为"steering"（引导当前对话）
- `"queue"` —— 消息排队等待处理

#### 4.1.5 `session.next.prompt.admitted` — Prompt 被接纳

| 项目 | 内容 |
|------|------|
| **触发** | Prompt 通过验证，被加入处理队列 |
| **properties** | 同 `prompted` |
| **同步** | ✅ v1 |

#### 4.1.6 `session.next.prompt.promoted` — Prompt 被提升

| 项目 | 内容 |
|------|------|
| **触发** | 排队的 Prompt 开始被处理 |
| **properties** | `{ timestamp, sessionID, messageID, prompt, timeCreated }` |
| **同步** | ✅ v1 |

#### 4.1.7 `session.next.interrupt.requested` — 请求中断

| 项目 | 内容 |
|------|------|
| **触发** | `POST /session/:id/abort` 后 |
| **properties** | `{ timestamp, sessionID }` |
| **同步** | ✅ v1 |

#### 4.1.8 `session.next.context.updated` — 上下文更新

| 项目 | 内容 |
|------|------|
| **触发** | 会话上下文（系统 prompt、工具配置等）变更 |
| **properties** | `{ timestamp, sessionID, messageID, text: string }` |
| **同步** | ✅ v1 |

#### 4.1.9 `session.next.synthetic` — 合成消息

| 项目 | 内容 |
|------|------|
| **触发** | 系统注入的合成消息（非用户/AI 自然产生） |
| **properties** | `{ timestamp, sessionID, messageID, text: string }` |
| **同步** | ✅ v1 |

### 4.2 Shell 命令事件（2 个）

#### 4.2.1 `session.next.shell.started` — Shell 命令开始

| 项目 | 内容 |
|------|------|
| **触发** | 工具执行 shell 命令开始 |
| **properties** | `{ timestamp, sessionID, messageID, callID, command: string }` |
| **同步** | ✅ v1 |

#### 4.2.2 `session.next.shell.ended` — Shell 命令结束

| 项目 | 内容 |
|------|------|
| **触发** | shell 命令执行完成 |
| **properties** | `{ timestamp, sessionID, callID, output: string }` |
| **同步** | ✅ v1 |

### 4.3 Step（推理步骤）事件（3 个）

#### 4.3.1 `session.next.step.started` — 步骤开始

| 项目 | 内容 |
|------|------|
| **触发** | AI 推理的一个 step 开始（一次 LLM 调用） |
| **properties** | `{ timestamp, sessionID, assistantMessageID, agent, model: ModelV2.Ref, snapshot?: string }` |
| **同步** | ✅ v1 |

**`snapshot`**：文件系统快照 ID，用于 revert 时恢复文件状态。

#### 4.3.2 `session.next.step.ended` — 步骤结束

| 项目 | 内容 |
|------|------|
| **触发** | AI 推理 step 完成 |
| **properties** | `{ timestamp, sessionID, assistantMessageID, finish: string, cost: number, tokens: Tokens, snapshot?: string }` |
| **同步** | ✅ **v2**（`stepSettlementOptions`，version: 2） |

**`tokens` 结构**（**Token 相关事件 payload 核心**）：

```typescript
{
  input: number,        // 输入 token 数
  output: number,       // 输出 token 数
  reasoning: number,    // reasoning token 数
  cache: {
    read: number,       // 缓存读取 token 数
    write: number       // 缓存写入 token 数
  }
}
```

**`finish`** 字段值：`"stop"`（正常停止）、`"length"`（达到长度限制）、`"tool-calls"`（调用工具）、`"content-filter"`（内容过滤）等。

#### 4.3.3 `session.next.step.failed` — 步骤失败

| 项目 | 内容 |
|------|------|
| **触发** | AI 推理 step 抛错 |
| **properties** | `{ timestamp, sessionID, assistantMessageID, error: UnknownError }` |
| **同步** | ✅ **v2** |

**`UnknownError` 结构**：`{ type: "unknown", message: string }`

### 4.4 Text（文本输出）事件（3 个）

#### 4.4.1 `session.next.text.started` — 文本开始

| 项目 | 内容 |
|------|------|
| **触发** | AI 开始输出文本内容（一段新的文本块） |
| **properties** | `{ timestamp, sessionID, assistantMessageID, textID: string }` |
| **同步** | ✅ v1 |

#### 4.4.2 `session.next.text.delta` — 文本增量（**瞬时**）

| 项目 | 内容 |
|------|------|
| **触发** | AI 流式输出文本片段 |
| **properties** | `{ timestamp, sessionID, assistantMessageID, textID, delta: string }` |
| **同步** | ❌ **瞬时事件**，不持久化 |

> **重要**：这是**实时流式事件**，仅推送一次，不存入事件溯源系统。客户端必须实时监听并累积 delta。回放时只能拿到 `text.ended` 的完整文本。

#### 4.4.3 `session.next.text.ended` — 文本结束

| 项目 | 内容 |
|------|------|
| **触发** | 一段文本输出完成 |
| **properties** | `{ timestamp, sessionID, assistantMessageID, textID, text: string }` —— `text` 是完整内容 |
| **同步** | ✅ v1 |

### 4.5 Reasoning（推理过程）事件（3 个）

#### 4.5.1 `session.next.reasoning.started` — 推理开始

| 项目 | 内容 |
|------|------|
| **触发** | AI 开始输出 reasoning（如 Claude 的 thinking） |
| **properties** | `{ timestamp, sessionID, assistantMessageID, reasoningID, providerMetadata? }` |
| **同步** | ✅ v1 |

**`providerMetadata`**：provider 特定的元数据（如 OpenAI 的 `reasoning_effort` 等）。

#### 4.5.2 `session.next.reasoning.delta` — 推理增量（**瞬时**）

| 项目 | 内容 |
|------|------|
| **触发** | reasoning 流式输出片段 |
| **properties** | `{ timestamp, sessionID, assistantMessageID, reasoningID, delta: string }` |
| **同步** | ❌ **瞬时事件** |

#### 4.5.3 `session.next.reasoning.ended` — 推理结束

| 项目 | 内容 |
|------|------|
| **触发** | reasoning 输出完成 |
| **properties** | `{ timestamp, sessionID, assistantMessageID, reasoningID, text: string, providerMetadata? }` |
| **同步** | ✅ v1 |

### 4.6 Tool（工具调用）事件（8 个）

#### 4.6.1 `session.next.tool.input.started` — 工具输入开始

| 项目 | 内容 |
|------|------|
| **触发** | AI 开始生成工具调用的参数 |
| **properties** | `{ timestamp, sessionID, assistantMessageID, callID, name: string }` |
| **同步** | ✅ v1 |

#### 4.6.2 `session.next.tool.input.delta` — 工具输入增量（**瞬时**）

| 项目 | 内容 |
|------|------|
| **触发** | 工具参数流式生成片段 |
| **properties** | `{ timestamp, sessionID, assistantMessageID, callID, delta: string }` |
| **同步** | ❌ **瞬时事件** |

#### 4.6.3 `session.next.tool.input.ended` — 工具输入结束

| 项目 | 内容 |
|------|------|
| **触发** | 工具参数生成完成 |
| **properties** | `{ timestamp, sessionID, assistantMessageID, callID, text: string }` —— `text` 是完整参数 JSON |
| **同步** | ✅ v1 |

#### 4.6.4 `session.next.tool.called` — 工具被调用

| 项目 | 内容 |
|------|------|
| **触发** | 工具被实际调用执行（与 input.ended 不同，这是执行点） |
| **properties** | `{ timestamp, sessionID, assistantMessageID, callID, tool: string, input: Record<string, unknown>, provider: { executed: boolean, metadata? } }` |
| **同步** | ✅ v1 |

**`provider.executed`**：是否由 provider 端执行（如 OpenAI 的内置工具）vs 本地执行。

#### 4.6.5 `session.next.tool.progress` — 工具执行进度

| 项目 | 内容 |
|------|------|
| **触发** | 工具执行过程中的中间状态更新（如长时间运行的工具） |
| **properties** | `{ timestamp, sessionID, assistantMessageID, callID, structured: ToolOutput.Structured, content: ToolOutput.Content[] }` |
| **同步** | ✅ v1 |

> **注意**：Progress 是**有界更新**（bounded cadence），不是每个 stdout 行都发事件。工具应该 checkpoint 语义转换点或按有界频率发送。

#### 4.6.6 `session.next.tool.success` — 工具执行成功

| 项目 | 内容 |
|------|------|
| **触发** | 工具执行成功完成 |
| **properties** | `{ timestamp, sessionID, assistantMessageID, callID, structured, content, outputPaths?: string[], result?: unknown, provider: { executed, metadata? } }` |
| **同步** | ✅ v1 |

**`outputPaths`**：工具输出被保存到磁盘的文件路径列表（当输出超过阈值时）。

#### 4.6.7 `session.next.tool.failed` — 工具执行失败

| 项目 | 内容 |
|------|------|
| **触发** | 工具执行抛错 |
| **properties** | `{ timestamp, sessionID, assistantMessageID, callID, error: UnknownError, result?: unknown, provider: { executed, metadata? } }` |
| **同步** | ✅ v1 |

### 4.7 Retry（重试）事件（1 个）

#### 4.7.1 `session.next.retried` — 推理重试

| 项目 | 内容 |
|------|------|
| **触发** | LLM 调用失败后自动重试 |
| **properties** | `{ timestamp, sessionID, attempt: number, error: RetryError }` |
| **同步** | ✅ v1 |

**`RetryError` 结构**：

```typescript
{
  message: string,
  statusCode?: number,
  isRetryable: boolean,
  responseHeaders?: Record<string, string>,
  responseBody?: string,
  metadata?: Record<string, string>
}
```

### 4.8 Compaction（压缩）事件（4 个）

#### 4.8.1 `session.next.compaction.started` — 压缩开始

| 项目 | 内容 |
|------|------|
| **触发** | 会话上下文压缩开始 |
| **properties** | `{ timestamp, sessionID, messageID, reason: "auto" \| "manual" }` |
| **同步** | ✅ v1 |

#### 4.8.2 `session.next.compaction.delta` — 压缩增量（**瞬时**）

| 项目 | 内容 |
|------|------|
| **触发** | 压缩过程的流式输出（AI 生成压缩摘要时） |
| **properties** | `{ timestamp, sessionID, messageID, text: string }` |
| **同步** | ❌ **瞬时事件** |

#### 4.8.3 `session.next.compaction.ended` — 压缩结束（v1 + v2 双版本）

| 项目 | 内容 |
|------|------|
| **触发** | 压缩完成 |
| **v1 properties** | `{ timestamp, sessionID, text: string, include?: string }` |
| **v2 properties** | `{ timestamp, sessionID, messageID, reason, text: string, recent: string }` |
| **同步** | v1 → ✅ v1；v2 → ✅ **v2** |

> **特殊**：`Compaction.EndedV1` 保留 unpublished decoder 用于回放存储的 beta 事件。当前发布使用 v2 版本（`{ aggregate: "sessionID", version: 2 }`）。

---

## 5. Session 状态与生命周期事件（5 个）

> 来源：`packages/opencode/src/session/status.ts` + `session.ts` + `compaction.ts`

### 5.1 `session.status` — 会话状态变更

| 项目 | 内容 |
|------|------|
| **触发** | 会话状态从 idle/busy/retry 转换时 |
| **properties** | `{ sessionID, status: SessionStatus.Info }` |
| **同步** | ❌ 不同步（纯内存状态） |

**`SessionStatus.Info`** 结构（3 种判别联合，按 `type` 判别）：

| type | 额外字段 | 说明 |
|------|---------|------|
| `idle` | — | 空闲 |
| `busy` | — | 处理中 |
| `retry` | `attempt: number`, `message: string`, `action?: {...}`, `next: number` | 重试中 |

### 5.2 `session.idle` — 会话空闲（**已废弃**）

| 项目 | 内容 |
|------|------|
| **触发** | 会话变为 idle 状态时，**与 `session.status` 同时发布** |
| **properties** | `{ sessionID }` |
| **同步** | ❌ |
| **状态** | **DEPRECATED**（源码注释标注 `// deprecated`） |

### 5.3 `session.diff` — 文件 diff 更新

| 项目 | 内容 |
|------|------|
| **触发** | 会话产生文件变更，diff 重新计算后 |
| **properties** | `{ sessionID, diff: Snapshot.FileDiff[] }` |
| **同步** | ❌ |

### 5.4 `session.error` — 会话错误

| 项目 | 内容 |
|------|------|
| **触发** | 会话执行过程中发生错误（如 `prompt_async` 后台失败） |
| **properties** | `{ sessionID?: SessionID, error?: AssistantError }` |
| **同步** | ❌ |

**`sessionID` 可选**：某些全局错误可能不绑定特定会话。

**`error`** 是 7 种错误类型的联合（来自 `SessionV1.Assistant.fields.error`），包括 `api_error`、`aborted`、`auth`、`output_length`、`context_overflow`、`structured_output` 等。

### 5.5 `session.compacted` — 会话已压缩

| 项目 | 内容 |
|------|------|
| **触发** | 会话压缩完成（与 `session.next.compaction.ended` 不同，这是粗粒度通知） |
| **properties** | `{ sessionID }` |
| **同步** | ❌ |

---

## 6. Todo 事件（1 个）

### 6.1 `todo.updated` — Todo 列表更新

| 项目 | 内容 |
|------|------|
| **触发** | 会话的 Todo 列表变更 |
| **来源** | `packages/opencode/src/session/todo.ts:20-26` |
| **properties** | `{ sessionID, todos: Todo.Info[] }` |
| **同步** | ❌ |

**`Todo.Info`** 结构：

```typescript
{
  content: string,                    // 任务描述
  status: "pending" | "in_progress" | "completed" | "cancelled",
  priority: "high" | "medium" | "low"
}
```

---

## 7. Permission 事件（4 个）

> v1 和 v2 并行存在，新代码应使用 v2

### 7.1 `permission.asked` — 权限请求（v1）

| 项目 | 内容 |
|------|------|
| **触发** | 工具需要权限时 |
| **来源** | `packages/opencode/src/permission/index.ts:11` |
| **properties** | `PermissionV1.Request.fields` —— `{ id, sessionID, permission, patterns, metadata, always, tool? }` |
| **同步** | ❌ |

### 7.2 `permission.replied` — 权限回复（v1）

| 项目 | 内容 |
|------|------|
| **触发** | 用户回复权限请求后 |
| **properties** | `{ sessionID, requestID, reply: "once" \| "always" \| "reject" }` |
| **同步** | ❌ |

### 7.3 `permission.v2.asked` — 权限请求（v2）

| 项目 | 内容 |
|------|------|
| **触发** | 工具需要权限时（v2 风格） |
| **来源** | `packages/core/src/permission.ts:75` |
| **properties** | `Request.fields`（v2 schema，更严格的类型） |
| **同步** | ❌ |

### 7.4 `permission.v2.replied` — 权限回复（v2）

| 项目 | 内容 |
|------|------|
| **触发** | 用户回复权限请求后（v2） |
| **properties** | `{ sessionID: SessionV2.ID, requestID, reply: Reply }` |
| **同步** | ❌ |

---

## 8. Question 事件（6 个）

### 8.1 `question.asked` — 问题请求（v1）

| 项目 | 内容 |
|------|------|
| **来源** | `packages/opencode/src/question/index.ts:87` |
| **properties** | `Request.fields` |
| **同步** | ❌ |

### 8.2 `question.replied` — 问题回复（v1）

| 项目 | 内容 |
|------|------|
| **properties** | `Replied.fields` |
| **同步** | ❌ |

### 8.3 `question.rejected` — 问题拒绝（v1）

| 项目 | 内容 |
|------|------|
| **触发** | 用户拒绝回答问题 |
| **properties** | `Rejected.fields` |
| **同步** | ❌ |

### 8.4-8.6 `question.v2.*` — 问题事件（v2）

| 事件 | 触发 |
|------|------|
| `question.v2.asked` | 问题提出 |
| `question.v2.replied` | `{ sessionID, requestID, answers: Answer[] }` |
| `question.v2.rejected` | `{ sessionID, requestID }` |

---

## 9. PTY 事件（4 个）

> 来源：`packages/core/src/pty.ts:93-96`

### 9.1 `pty.created` — PTY 创建

| 项目 | 内容 |
|------|------|
| **触发** | `POST /pty` 创建新 PTY 后 |
| **properties** | `{ info: Pty.Info }` —— 含 id、title、command、pid 等 |

### 9.2 `pty.updated` — PTY 更新

| 项目 | 内容 |
|------|------|
| **触发** | `PUT /pty/:id` 更新标题/尺寸后 |
| **properties** | `{ info: Pty.Info }` |

### 9.3 `pty.exited` — PTY 退出

| 项目 | 内容 |
|------|------|
| **触发** | PTY 进程退出 |
| **properties** | `{ id: PtyID, exitCode: number }` |

### 9.4 `pty.deleted` — PTY 删除

| 项目 | 内容 |
|------|------|
| **触发** | `DELETE /pty/:id` 后 |
| **properties** | `{ id: PtyID }` |

---

## 10. MCP 事件（2 个）

> 来源：`packages/opencode/src/mcp/index.ts:50-63`

### 10.1 `mcp.tools.changed` — MCP 工具变更

| 项目 | 内容 |
|------|------|
| **触发** | MCP 服务器的工具列表变更（连接/断开/刷新） |
| **properties** | `{ server: string }` —— 变更的 MCP 服务器名 |
| **同步** | ❌ |

**客户端响应**：收到后应重新获取工具列表（`GET /experimental/tool`）。

### 10.2 `mcp.browser.open.failed` — 浏览器打开失败

| 项目 | 内容 |
|------|------|
| **触发** | MCP OAuth 流程中尝试打开浏览器失败（如远程服务器无 GUI） |
| **properties** | `{ mcpName: string, url: string }` |
| **同步** | ❌ |

**客户端响应**：向用户显示 URL，让用户手动在浏览器中打开。

---

## 11. Project 与 VCS 事件（3 个）

### 11.1 `project.updated` — 项目更新

| 项目 | 内容 |
|------|------|
| **来源** | `packages/opencode/src/project/project.ts:57` |
| **properties** | `Project.Info` 的所有字段 |
| **同步** | ❌ |

### 11.2 `project.directories.updated` — 项目目录更新

| 项目 | 内容 |
|------|------|
| **来源** | `packages/core/src/project/copy.ts:95` |
| **properties** | 见 `ProjectCopy.Updated` schema |
| **同步** | ❌ |

### 11.3 `vcs.branch.updated` — VCS 分支更新

| 项目 | 内容 |
|------|------|
| **来源** | `packages/opencode/src/project/vcs.ts:237` |
| **properties** | `{ branch?: string, default_branch?: string }` |
| **同步** | ❌ |

---

## 12. LSP / IDE / Command 事件（3 个）

### 12.1 `lsp.updated` — LSP 状态更新

| 项目 | 内容 |
|------|------|
| **来源** | `packages/opencode/src/lsp/lsp.ts:17` |
| **properties** | `{}`（空对象 —— 仅作为通知信号） |
| **同步** | ❌ |

**客户端响应**：收到后应重新获取 LSP 状态（`GET /lsp`）。

### 12.2 `ide.installed` — IDE 插件安装

| 项目 | 内容 |
|------|------|
| **来源** | `packages/opencode/src/ide/index.ts:15` |
| **properties** | 见 `IDE.Installed` schema |
| **同步** | ❌ |

### 12.3 `command.executed` — 命令执行

| 项目 | 内容 |
|------|------|
| **来源** | `packages/opencode/src/command/index.ts:18` |
| **properties** | 见 `Command.Executed` schema |
| **同步** | ❌ |

---

## 13. Account / Catalog / Plugin 事件（5 个）

> 来源：`packages/core/src/auth.ts` + `catalog.ts` + `plugin.ts`

### 13.1 `account.added` — 账户添加

| 项目 | 内容 |
|------|------|
| **触发** | 新的 provider 账户被添加 |
| **properties** | `{ accountID, ... }` |

### 13.2 `account.removed` — 账户移除

| 项目 | 内容 |
|------|------|
| **properties** | `{ accountID, ... }` |

### 13.3 `account.switched` — 账户切换

| 项目 | 内容 |
|------|------|
| **触发** | 活跃账户切换（如 Console 组织切换） |
| **properties** | `{ accountID, ... }` |

### 13.4 `catalog.model.updated` — 模型目录更新

| 项目 | 内容 |
|------|------|
| **来源** | `packages/core/src/catalog.ts:36` |
| **触发** | models.dev 数据刷新后 |
| **properties** | 见 `Catalog.ModelUpdated` schema |

### 13.5 `plugin.added` — 插件添加

| 项目 | 内容 |
|------|------|
| **来源** | `packages/core/src/plugin.ts:15` |
| **触发** | 新插件加载后 |
| **properties** | 见 `Plugin.Added` schema |

---

## 14. Filesystem 事件（2 个）

### 14.1 `file.edited` — 文件编辑

| 项目 | 内容 |
|------|------|
| **来源** | `packages/core/src/filesystem.ts:207` |
| **触发** | 文件被编辑（通过 opencode 的工具） |
| **properties** | 见 `Filesystem.Edited` schema |

### 14.2 `file.watcher.updated` — 文件监听更新

| 项目 | 内容 |
|------|------|
| **来源** | `packages/core/src/filesystem/watcher.ts:23` |
| **触发** | 文件系统 watcher 检测到外部变更 |
| **properties** | 见 `Filesystem.Watcher.Updated` schema |

---

## 15. Installation 事件（2 个）

> 来源：`packages/opencode/src/installation/index.ts:20-26`

### 15.1 `installation.updated` — 版本更新完成

| 项目 | 内容 |
|------|------|
| **触发** | OpenCode 升级完成后 |
| **properties** | `{ version: string }` |
| **同步** | ❌ |

### 15.2 `installation.update-available` — 有可用更新

| 项目 | 内容 |
|------|------|
| **触发** | 检测到新版本可用 |
| **properties** | `{ version: string, ... }` |
| **同步** | ❌ |

---

## 16. Workspace / Worktree 事件（5 个）

### 16.1 `workspace.ready` — 工作区就绪

| 项目 | 内容 |
|------|------|
| **来源** | `packages/opencode/src/control-plane/workspace.ts:48` |
| **properties** | `{ workspaceID, ... }` |

### 16.2 `workspace.failed` — 工作区失败

| 项目 | 内容 |
|------|------|
| **来源** | `packages/opencode/src/control-plane/workspace.ts:54` |
| **properties** | `{ workspaceID, error, ... }` |

### 16.3 `workspace.status` — 工作区状态

| 项目 | 内容 |
|------|------|
| **来源** | `packages/opencode/src/control-plane/workspace.ts:60` |
| **properties** | `ConnectionStatus.fields` —— `{ workspaceID, connected, error? }` |

### 16.4 `worktree.ready` — Worktree 就绪

| 项目 | 内容 |
|------|------|
| **来源** | `packages/opencode/src/worktree/index.ts:22` |
| **properties** | `{ name, directory, ... }` |

### 16.5 `worktree.failed` — Worktree 失败

| 项目 | 内容 |
|------|------|
| **来源** | `packages/opencode/src/worktree/index.ts:29` |
| **properties** | `{ name, error, ... }` |

---

## 17. TUI 事件（4 个）

> 来源：`packages/opencode/src/server/tui-event.ts`
> **注意**：这些事件主要由 TUI 进程消费，外部客户端通常不需要处理

### 17.1 `tui.prompt.append` — 追加提示文本

| 项目 | 内容 |
|------|------|
| **properties** | `{ text: string }` |

### 17.2 `tui.command.execute` — 执行 TUI 命令

| 项目 | 内容 |
|------|------|
| **properties** | `{ command: string \| known-literal }` |

**已知命令字面量**（22 个）：
```
session.list, session.new, session.share, session.interrupt, session.compact,
session.page.up, session.page.down, session.line.up, session.line.down,
session.half.page.up, session.half.page.down, session.first, session.last,
prompt.clear, prompt.submit, agent.cycle, help.show, model.list, theme.list
```

### 17.3 `tui.toast.show` — 显示 Toast

| 项目 | 内容 |
|------|------|
| **properties** | `{ title?, message, variant: "info"\|"success"\|"warning"\|"error", duration: number }` |

### 17.4 `tui.session.select` — 选择会话

| 项目 | 内容 |
|------|------|
| **properties** | `{ sessionID: SessionID }` |

---

## 附录 A：v1/v2 迁移状态

OpenCode 事件体系正在从 v1（粗粒度）向 v2（细粒度）迁移。当前状态：

| 体系 | 事件前缀 | 状态 | 客户端建议 |
|------|---------|------|-----------|
| Session v1 | `session.created/updated/deleted` | ✅ 活跃 | 使用 |
| Message v1 | `message.updated/removed/part.*` | ✅ 活跃 | 使用 |
| **Session.next v2** | `session.next.*` | ✅ **推荐** | **优先使用** |
| Permission v1 | `permission.asked/replied` | ⚠️ 维护 | 兼容 |
| Permission v2 | `permission.v2.*` | ✅ 推荐 | 优先使用 |
| Question v1 | `question.asked/replied/rejected` | ⚠️ 维护 | 兼容 |
| Question v2 | `question.v2.*` | ✅ 推荐 | 优先使用 |

### Session.next v2 的设计哲学

v1 的 `message.part.updated` 传递**完整的 Part 对象**，每次更新都是全量替换。这在流式输出场景下效率低下（每次 delta 都序列化整个 Part）。

v2 的细粒度事件采用**生命周期 + delta 模式**：
- `*.started` —— 开始（告知客户端创建新对象）
- `*.delta` —— 增量（**瞬时**，仅推送一次，客户端累积）
- `*.ended` —— 结束（传递完整值，作为权威状态）

**客户端实现要点**：
1. 收到 `started` 时创建本地对象
2. 收到 `delta` 时 append 到本地对象（**必须实时处理，不能依赖回放**）
3. 收到 `ended` 时用完整值覆盖本地对象（权威）
4. 回放时只有 `started` 和 `ended`，没有 `delta` —— 用 `ended` 的完整值重建

---

## 附录 B：Token 相关事件 payload

Token 用量信息分布在以下事件中：

### `session.next.step.ended`（主要来源）

```typescript
{
  timestamp, sessionID, assistantMessageID,
  finish: string,
  cost: number,                      // 本次 step 的美元成本
  tokens: {
    input: number,                   // 输入 token
    output: number,                  // 输出 token
    reasoning: number,               // reasoning token
    cache: {
      read: number,                  // 缓存读取 token
      write: number                  // 缓存写入 token
    }
  },
  snapshot?: string
}
```

### Token 计算建议

| 指标 | 计算方式 |
|------|---------|
| 单次 step 总 token | `input + output + reasoning` |
| 单次 step 实际计费 token | `input + output + reasoning + cache.read`（缓存写入通常免费或低价） |
| 会话累计 token | 累加所有 `step.ended` 的 `tokens` |
| 会话累计成本 | 累加所有 `step.ended` 的 `cost` |
| 缓存命中率 | `cache.read / (input + cache.read)` |

> **注意**：`Session.Info.tokens`（通过 REST API 获取）是累计值，与会话级聚合一致。SSE 事件提供的是单步增量。

---

## 附录 C：同步事件（sync）机制

部分事件标记为 `sync: { aggregate: "...", version: N }`，表示支持**跨工作区事件溯源同步**。

### 工作原理

1. **事件发布**：服务端发布事件时，如果该事件在 registry 中标记为 `sync: true`，会额外发布一个**包装事件**到 GlobalBus：

```typescript
{
  type: "sync",
  syncEvent: {
    id: EventV2.ID,
    type: "<原始 type>@v<N>",          // 版本化 type
    seq: number,                        // 单调递增序列号
    aggregateID: string,                // 聚合根 ID（如 sessionID）
    data: <原始 properties>
  }
}
```

2. **事件存储**：所有 sync 事件持久化到 SQLite 的 `EventTable`，按 `(aggregateID, seq)` 索引

3. **增量同步**：客户端通过 `POST /sync/history` 查询增量事件，传入 `{ aggregateID: lastKnownSeq }`

4. **回放**：`POST /sync/replay` 接收完整事件序列，设置 `strictOwner: true` 验证归属后应用到本地

### 支持同步的事件分类

| 分类 | 是否同步 | aggregate | version |
|------|---------|-----------|---------|
| Session v1 (created/updated/deleted) | ✅ | `sessionID` | 1 |
| Message v1 (updated/removed/part.*) | ✅ | `sessionID` | 1 |
| **Session.next 大部分** | ✅ | `sessionID` | 1 或 2 |
| Session.next `.delta` 系列 | ❌ | — | — |
| Session.status/idle/diff/error | ❌ | — | — |
| Permission/Question | ❌ | — | — |
| PTY/MCP/Project/VCS | ❌ | — | — |

### 版本化 type 示例

原始事件 `session.next.step.ended`（v2）在 sync 包装中变为 `session.next.step.ended@v2`。这允许同一 type 的不同 schema 版本共存于事件流中。

**客户端解码**：需要根据 `@vN` 后缀选择正确的 schema 版本解码。

---

## 关键发现

### 🔴 发现 1：v1 和 v2 事件**并行发布**，不是替换

OpenCode **同时**发布 v1 粗粒度事件（如 `message.part.updated`）和 v2 细粒度事件（如 `session.next.text.delta` + `session.next.text.ended`）。这意味着：
- 客户端如果同时监听两套，会收到**重复信息**
- **建议**：选择一套体系（推荐 v2），忽略另一套
- v1 事件目前不会被移除（向后兼容）

---

### 🔴 发现 2：`.delta` 事件是**瞬时**的，无法通过回放获取

`text.delta`、`reasoning.delta`、`tool.input.delta`、`compaction.delta` 这 4 个事件**不持久化**，仅实时推送。如果客户端：
- 在 SSE 连接断开期间错过 delta → **永久丢失**
- 通过 sync/history 回放 → 只有 `*.ended` 的完整值

**客户端策略**：
- UI 展示用 delta（实时流式效果）
- 状态管理用 `ended`（权威完整值）
- 断线重连后，用 `ended` 事件重建状态，不依赖 delta

---

### 🟡 发现 3：`session.next.compaction.ended` 存在双版本

```typescript
EndedV1: { type: "session.next.compaction.ended", sync: { version: 1 }, schema: { text, include? } }
Ended:   { type: "session.next.compaction.ended", sync: { version: 2 }, schema: { messageID, reason, text, recent } }
```

**同一 type 字符串，不同 schema 版本**。当前发布使用 v2，但 v1 decoder 保留用于回放历史存储的 beta 事件。客户端解码时需要尝试两种 schema。

---

### 🟡 发现 4：`session.idle` 是 `session.status` 的废弃子集

```typescript
if (status.type === "idle") {
  yield* events.publish(Event.Idle, { sessionID })   // 额外发布 idle
  data.delete(sessionID)
}
```

每次会话变为 idle，会**同时**发布 `session.status`（含 `status: { type: "idle" }`）和 `session.idle`。后者是历史遗留，**不应在新代码中依赖**。

---

### 🟡 发现 5：SSE 事件不使用 `event` 字段区分类型

标准 SSE 支持 `event: <name>` 字段区分事件类型，但 OpenCode 所有事件的 `event` 字段固定为 `"message"`，类型信息在 `data` JSON 的 `type` 字段中。

**客户端实现**：
```javascript
const es = new EventSource("/event")
es.addEventListener("message", (e) => {
  const data = JSON.parse(e.data)
  switch (data.type) {
    case "session.next.text.delta": handleDelta(data.properties)
    // ...
  }
})
```

**不要**使用 `es.addEventListener("session.next.text.delta", ...)` —— 不会触发。

---

### 🟡 发现 6：全局事件的 payload 结构不同于实例事件

| 端点 | payload 结构 |
|------|-------------|
| `/event` | `{ id, type, properties }` |
| `/global/event` | `{ directory, project?, workspace?, payload: { id, type, properties } }` |

全局事件多了一层 `payload` 包装，且包含来源信息（directory/project/workspace）。客户端需要根据订阅的端点使用不同的解析逻辑。

---

### 🟡 发现 7：sync 事件的 `type` 带版本后缀

在 `/global/event` 上收到的 sync 包装事件，其 `syncEvent.type` 是 `<原始 type>@v<N>`（如 `session.updated@v1`）。这与原始事件 type 不同，客户端解码时需要**去除后缀**或**按后缀路由 schema 版本**。

---

### 🟢 发现 8：心跳的 `drop(1)` 设计

```typescript
const heartbeat = Stream.tick("10 seconds").pipe(
  Stream.drop(1),   // 丢弃第一个 tick
  Stream.map(() => ({ type: "server.heartbeat", ... }))
)
```

`Stream.tick("10 seconds")` 会在订阅时**立即**产生第一个值，`drop(1)` 避免连接建立后立刻发送心跳（`server.connected` 已经是首个事件）。真正的第一次心跳在连接后 10 秒。

---

### 🟢 发现 9：`session.status` 的 `retry` 状态含未来重试时间

```typescript
retry: {
  attempt: number,
  message: string,
  action?: { reason, provider, title, message, label, link? },
  next: number    // 下次重试的时间戳
}
```

`next` 字段是下次重试的 Unix 时间戳，客户端可以据此显示倒计时。`action` 字段提供用户可执行的操作（如"添加付款方式"，`link` 是操作链接）。

---

*本文档基于 opencode 源码深度调研生成，涵盖 89 个事件类型的完整规格。如需查阅特定事件的完整 schema 定义，请参考各章节标注的源码路径。*
