# OpenCode Server API Reference

> 逆向自 oc-remote 客户端源码 + OpenCode WebUI 源码分析。覆盖 V1 + V2 全部接口。

---

## 目录

- [通用机制](#通用机制)
- [1. Global 端点](#1-global-端点)
- [2. Server / Path 端点](#2-server--path-端点)
- [3. Project 端点](#3-project-端点)
- [4. Agent / Command / Skill 端点](#4-agent--command--skill-端点)
- [5. Session 端点](#5-session-端点)
- [6. Message 端点](#6-message-端点)
- [7. Permission 端点](#7-permission-端点)
- [8. Question 端点](#8-question-端点)
- [9. Provider / Auth 端点](#9-provider--auth-端点)
- [10. Config 端点](#10-config-端点)
- [11. Instance 端点](#11-instance-端点)
- [12. PTY 端点](#12-pty-端点)
- [13. File / Find 端点](#13-file--find-端点)
- [14. SSE 事件体系](#14-sse-事件体系)
- [15. 数据模型](#15-数据模型)
- [16. Token / Context Usage](#16-token--context-usage)

---

## 通用机制

### Base URL

```
http://{host}:{port}
```

### 认证

- **方式**: HTTP Basic Auth
- **Header**: `Authorization: Basic base64(username:password)`
- **默认用户名**: `opencode`
- **密码**: 环境变量 `OPENCODE_SERVER_PASSWORD` 配置

### 目录作用域 Header

- **Header**: `x-opencode-directory: <URL-encoded-path>`
- 大多数端点支持此 header，用于指定项目工作目录上下文
- 不传则使用服务端默认目录

### 通用请求/响应格式

- **Content-Type**: `application/json`
- 字段命名约定: camelCase，ID 后缀大写（如 `sessionID`, `providerID`）

---

## 1. Global 端点

### GET `/global/health`

健康检查。

**响应** `200`:

```json
{
  "healthy": true,
  "version": "string (可选)",
  "uptime": 12345   // long, 可选, 秒
}
```

### GET `/global/config`

获取全局配置。

**响应** `200`: [`ServerConfigResponse`](#serverconfigresponse)

### PATCH `/global/config`

更新全局配置。

**请求体**: [`ServerConfigPatch`](#serverconfigpatch)

**响应** `200`: [`ServerConfigResponse`](#serverconfigresponse)

### GET `/global/event`

全局 SSE 事件流（长连接）。推送所有目录的所有事件。

**Headers**: `Accept: text/event-stream`, 可选 `x-opencode-directory`

**事件格式**:
```
data: {"directory": "/path/to/project", "payload": {"type": "session.created", "properties": {...}}}
```

> 全局端点用 `payload` 包装事件，per-instance 端点（`GET /event`）直接发送 `{type, properties}`。

**心跳**: `server.heartbeat` 事件，40 秒超时断连。

### POST `/global/dispose`

销毁全局实例，强制刷新 provider/auth 状态。

**响应** `200`: 成功返回 `true`

---

## 2. Server / Path 端点

### GET `/path`

获取服务器路径信息。

**响应** `200`: [`ServerPaths`](#serverpaths)

---

## 3. Project 端点

### GET `/project`

列出所有项目。

**响应** `200`: `List<`[`Project`](#project)`>`

### GET `/project/current`

获取当前项目。

**响应** `200`: [`Project`](#project)

---

## 4. Agent / Command / Skill 端点

### GET `/agent`

列出可用 Agent。

**响应** `200`: `List<`[`AgentInfo`](#agentinfo)`>`

### GET `/command`

列出可用斜杠命令。

**响应** `200`: `List<`[`CommandInfo`](#commandinfo)`>`

### GET `/skill`

列出可用 Skills。

| Query 参数 | 类型 | 说明 |
|-----------|------|------|
| — | — | — |

**Headers**: 可选 `x-opencode-directory`

**响应** `200`: `List<`[`SkillInfo`](#skillinfo)`>`

---

## 5. Session 端点

### GET `/session`

列出会话。

| Query 参数 | 类型 | 默认 | 说明 |
|-----------|------|------|------|
| `roots` | bool | `true` | 仅返回根会话（非 fork） |
| `search` | string? | — | 搜索关键词 |
| `cursor` | string? | — | 分页游标 |
| `limit` | int | `50` | 分页大小 |

**Headers**: 可选 `x-opencode-directory`

**响应** `200`: `List<`[`Session`](#session)`>`

### GET `/session/status`

批量获取会话状态。

**Headers**: 可选 `x-opencode-directory`

**响应** `200`: `Map<sessionId, `[`RestSessionStatusInfo`](#restsessionstatusinfo)`>`

### POST `/session`

创建会话。

**请求体**:

```json
{
  "title": "string? (可选)",
  "parentID": "string? (可选，用于创建子会话)"
}
```

**Headers**: 可选 `x-opencode-directory`

**响应** `200`: [`Session`](#session)

### POST `/session/import`

从分享 URL 导入会话。

**请求体**:

```json
{
  "url": "string (分享链接)"
}
```

**响应** `200`: [`Session`](#session)

### GET `/session/{sessionId}`

获取单个会话详情。

| Path 参数 | 类型 | 说明 |
|----------|------|------|
| `sessionId` | string | 会话 ID |

**响应** `200`: [`Session`](#session)

### PATCH `/session/{sessionId}`

更新会话。可传任意字段。

**请求体**（更新标题）:

```json
{
  "title": "新标题"
}
```

**响应** `200`: [`Session`](#session)

### DELETE `/session/{sessionId}`

删除会话。

**响应** `204`: 成功

### POST `/session/{sessionId}/abort`

中止会话当前操作。

**Headers**: 可选 `x-opencode-directory`

**响应** `204`: 成功

### GET `/session/{sessionId}/diff`

获取会话文件差异。

**响应** `200`: `List<`[`FileDiff`](#filediff)`>`

### POST `/session/{sessionId}/summarize`

压缩/总结会话。

**请求体**:

```json
{
  "providerID": "string",
  "modelID": "string"
}
```

**响应** `204`: 成功

### POST `/session/{sessionId}/revert`

回退到指定消息。

**请求体**:

```json
{
  "messageID": "string"
}
```

**响应** `200`: [`Session`](#session)

### POST `/session/{sessionId}/unrevert`

撤销回退。

**响应** `200`: [`Session`](#session)

### POST `/session/{sessionId}/fork`

分叉会话。

**请求体**:

```json
{
  "messageID": "string? (可选，从指定消息分叉)"
}
```

**响应** `200`: [`Session`](#session)

### POST `/session/{sessionId}/share`

分享会话（生成链接）。

**响应** `200`: [`Session`](#session)（含 `share.url`）

### DELETE `/session/{sessionId}/share`

取消分享。

**响应** `200`: [`Session`](#session)

### GET `/session/{sessionId}/children`

获取子会话列表。

**响应** `200`: `List<`[`Session`](#session)`>`

### GET `/session/{sessionId}/todo`

获取会话 Todo 列表。

**响应** `200`: `List<`[`TodoItem`](#todoitem)`>`

### POST `/session/{sessionId}/command`

执行服务器端斜杠命令。

**请求体**:

```json
{
  "command": "string",
  "arguments": "string (默认空)",
  "agent": "string? (可选)",
  "model": "string? (可选)",
  "variant": "string? (可选)",
  "parts": [{"type": "...", "text": "..."}]? (可选)"
}
```

**Headers**: 可选 `x-opencode-directory`

**响应** `204`: 成功

### POST `/session/{sessionId}/shell`

在会话中运行 shell 命令。

**请求体**: [`ShellRequest`](#shellrequest)

**Headers**: 可选 `x-opencode-directory`

**响应** `204`: 成功

### POST `/session/{sessionId}/prompt_async`

异步发送 prompt（核心接口）。返回 204 后，所有结果通过 SSE 推送。

**请求体**: [`PromptRequest`](#promptrequest)

**Headers**: 可选 `x-opencode-directory`

**响应** `204`: No Content（fire-and-forget）

---

## 6. Message 端点

### GET `/session/{sessionId}/message`

获取消息列表。

| Query 参数 | 类型 | 说明 |
|-----------|------|------|
| `limit` | int? | 限制返回数量 |

**响应** `200`: `List<`[`MessageWithParts`](#messagewithparts)`>`

### GET `/session/{sessionId}/message/{messageId}`

获取单条消息详情。

**响应** `200`: [`MessageWithParts`](#messagewithparts)

### DELETE `/session/{sessionId}/message/{messageId}`

删除消息。

**响应** `204`: 成功

### DELETE `/session/{sessionId}/message/{messageId}/part/{partIndex}`

删除消息中的某个 Part。

| Path 参数 | 类型 | 说明 |
|----------|------|------|
| `sessionId` | string | 会话 ID |
| `messageId` | string | 消息 ID |
| `partIndex` | int | Part 索引 |

**响应** `204`: 成功

---

## 7. Permission 端点

### GET `/permission`

列出待处理的权限请求。

**Headers**: 可选 `x-opencode-directory`

**响应** `200`: `List<`[`PermissionRequest`](#permissionrequest)`>`

### POST `/permission/{requestID}/reply`

回复权限请求。

**请求体**:

```json
{
  "reply": "once | always | reject",
  "message": "string? (可选)"
}
```

**Headers**: 可选 `x-opencode-directory`

**响应** `204`: 成功

---

## 8. Question 端点

### GET `/question`

列出待处理的问题请求。

**Headers**: 可选 `x-opencode-directory`

**响应** `200`: `List<`[`QuestionRequest`](#questionrequest)`>`

### POST `/question/{requestID}/reply`

回复问题。

**请求体**:

```json
{
  "answers": [
    ["option1", "option2"],   // 第一个问题的选中项
    ["optionA"]               // 第二个问题的选中项
  ]
}
```

> 外层数组对应每个 question，内层数组是对应 question 的选项（支持多选）。

**Headers**: 可选 `x-opencode-directory`

**响应** `204`: 成功

### POST `/question/{requestID}/reject`

拒绝问题。

**Headers**: 可选 `x-opencode-directory`

**响应** `204`: 成功

---

## 9. Provider / Auth 端点

### GET `/config/providers`

获取 Providers + Models 列表（旧版接口）。

**响应** `200`: [`ProvidersResponse`](#providersresponse)

### GET `/provider`

获取 Provider 目录（V2，含连接状态）。

**响应** `200`: [`ProviderCatalogResponse`](#providercatalogresponse)

### GET `/provider/auth`

获取可用认证方式。

**响应** `200`: `Map<providerId, List<`[`ProviderAuthMethod`](#providerauthmethod)`>>`

### POST `/provider/{providerID}/oauth/authorize`

发起 OAuth 授权。

**请求体**:

```json
{
  "method": 0   // 认证方式索引
}
```

**响应** `200`: [`ProviderOauthAuthorization`](#provideroauthauthorization)` | null`

### POST `/provider/{providerID}/oauth/callback`

完成 OAuth 回调。

**请求体**:

```json
{
  "method": 0,
  "code": "string? (可选)"
}
```

**响应** `204`: 成功

### PUT `/auth/{providerID}`

设置 API Key。

**请求体**:

```json
{
  "type": "api",
  "key": "string"
}
```

**响应** `204`: 成功

### DELETE `/auth/{providerID}`

删除 Provider 认证信息。

**响应** `204`: 成功

---

## 10. Config 端点

### GET `/config`

获取项目级配置。

**响应** `200`: [`ServerConfigResponse`](#serverconfigresponse)

### PATCH `/config`

更新项目级配置。

**请求体**: [`ServerConfigPatch`](#serverconfigpatch)

**响应** `200`: [`ServerConfigResponse`](#serverconfigresponse)

---

## 11. Instance 端点

### POST `/instance/dispose`

销毁当前实例。

**响应** `200`: 成功

### GET `/event`

Per-instance SSE 事件流（长连接）。仅推送指定目录的事件。

**Headers**: `Accept: text/event-stream`, 可选 `x-opencode-directory`

**事件格式**:
```
data: {"type": "session.created", "properties": {...}}
```

> 与全局端点不同，per-instance 端点直接发送 `{type, properties}`，无 `payload` 包装。

---

## 12. PTY 端点

### POST `/pty`

创建 PTY 终端。

**请求体**: [`PtyCreateRequest`](#ptycreaterequest)

**Headers**: 可选 `x-opencode-directory`

**响应** `200`: [`PtyInfo`](#ptyinfo)

### PUT `/pty/{ptyId}`

更新 PTY（尺寸等）。

**请求体**: [`PtyUpdateRequest`](#ptyupdaterequest)

**Headers**: 可选 `x-opencode-directory`

**响应** `204`: 成功

### DELETE `/pty/{ptyId}`

删除 PTY。

**响应** `204`: 成功

### GET `/pty/{ptyId}/connect` (WebSocket)

WebSocket 连接 PTY 终端。

**URL**: `ws(s)://host:port/pty/{ptyId}/connect?cursor={int}`

| Query 参数 | 类型 | 默认 | 说明 |
|-----------|------|------|------|
| `cursor` | int | `-1` | 恢复读取位置 |

**Headers**: `Authorization`, 可选 `x-opencode-directory`

**通信**: WebSocket 文本帧双向传输

### GET `/pty/shells`

列出可用 Shell。

**Headers**: 可选 `x-opencode-directory`

**响应** `200`: `List<`[`ShellInfo`](#shellinfo)`>`

---

## 13. File / Find 端点

### GET `/file`

列出目录内容。

| Query 参数 | 类型 | 默认 | 说明 |
|-----------|------|------|------|
| `path` | string | `""` | 目录路径 |

**Headers**: 可选 `x-opencode-directory`

**响应** `200`: `List<`[`FileNode`](#filenode)`>`

### GET `/file/content`

读取文件内容。

| Query 参数 | 类型 | 说明 |
|-----------|------|------|
| `path` | string | 文件路径 |

**响应** `200`: [`FileContent`](#filecontent)

### GET `/file/status`

获取 Git 文件状态。

**Headers**: 可选 `x-opencode-directory`

**响应** `200`: `List<`[`FileStatusInfo`](#filestatusinfo)`>`

### GET `/find`

文本搜索。

| Query 参数 | 类型 | 说明 |
|-----------|------|------|
| `pattern` | string | 搜索模式 |

**响应** `200`: `List<`[`SearchMatch`](#searchmatch)`>`

### GET `/find/file`

文件名搜索。

| Query 参数 | 类型 | 说明 |
|-----------|------|------|
| `query` | string | 搜索查询 |
| `type` | string? | 文件类型 |
| `dirs` | string? | 目录过滤 |
| `limit` | int? | 结果限制 |

**Headers**: 可选 `x-opencode-directory`

**响应** `200`: `List<string>`（文件路径列表）

### GET `/find/symbol`

符号搜索。

| Query 参数 | 类型 | 说明 |
|-----------|------|------|
| `query` | string | 搜索查询 |

**Headers**: 可选 `x-opencode-directory`

**响应** `200`: `List<`[`SymbolInfo`](#symbolinfo)`>`

---

## 14. SSE 事件体系

### 14.1 连接方式

| 端点 | 作用 |
|------|------|
| `GET /global/event` | 全局事件流（所有目录） |
| `GET /event` | 实例级事件流（指定目录） |

### 14.2 事件格式

**全局端点** (`/global/event`):
```
data: {"directory": "/path", "payload": {"type": "session.created", "properties": {...}}}
```

**Per-instance 端点** (`/event`):
```
data: {"type": "session.created", "properties": {...}}
```

### 14.3 服务端事件

| type | 数据字段 | 说明 |
|------|---------|------|
| `server.connected` | — | 首次连接成功 |
| `server.heartbeat` | — | 心跳（40s 超时） |
| `server.instance.disposed` | `directory` | 实例已销毁 |

### 14.4 Session 生命周期事件

| type | 数据字段 | 说明 |
|------|---------|------|
| `session.created` | `info: Session` | 会话创建 |
| `session.updated` | `info: Session` | 会话更新（标题、tokens、cost 等全部字段） |
| `session.deleted` | `info: Session` | 会话删除 |
| `session.status` | `sessionID, status: SessionStatus` | 状态变更 |
| `session.idle` | `sessionID` | 会话空闲 |
| `session.error` | `sessionID?, error: string` | 会话错误 |
| `session.diff` | `sessionID, diff: FileDiff[]` | 文件差异更新 |
| `session.compacted` | `sessionID` | 会话已压缩 |

### 14.5 Message 事件

| type | 数据字段 | 说明 |
|------|---------|------|
| `message.updated` | `info: Message` | 消息完成/更新（含 tokens、cost） |
| `message.removed` | `sessionID, messageID` | 消息删除 |
| `message.part.updated` | `part: Part` | Part 完整更新（含 step-finish 的 tokens） |
| `message.part.delta` | `sessionID, messageID, partID, field, delta` | Part 增量更新（流式文本） |
| `message.part.removed` | `sessionID, messageID, partID` | Part 删除 |

### 14.6 Permission 事件

| type | 数据字段 | 说明 |
|------|---------|------|
| `permission.asked` | `id, sessionID, permission, patterns, always, metadata?, tool?` | 权限请求 |
| `permission.replied` | `sessionID, requestID` | 权限已回复 |

### 14.7 Question 事件

| type | 数据字段 | 说明 |
|------|---------|------|
| `question.asked` | `id, sessionID, questions[], tool?` | 问题请求 |
| `question.replied` | `sessionID, requestID` | 已回复 |
| `question.rejected` | `sessionID, requestID` | 已拒绝 |

### 14.8 Todo 事件

| type | 数据字段 | 说明 |
|------|---------|------|
| `todo.updated` | `sessionID, todos[]` | Todo 列表更新 |

### 14.9 PTY 事件

| type | 数据字段 | 说明 |
|------|---------|------|
| `pty.created` | `id, title, command, cwd` | PTY 创建 |
| `pty.updated` | `id, title, command, status` | PTY 更新 |
| `pty.deleted` | `id` | PTY 删除 |

### 14.10 VCS / Project / LSP 事件

| type | 数据字段 | 说明 |
|------|---------|------|
| `vcs.branch.updated` | `branch` | Git 分支切换 |
| `project.updated` | `info: Project` | 项目更新 |
| `lsp.updated` | — | LSP 索引更新 |

### 14.11 Workspace / Worktree 事件

| type | 数据字段 | 说明 |
|------|---------|------|
| `workspace.ready` | `workspaceID` | 工作区就绪 |
| `workspace.failed` | `workspaceID, error?` | 工作区失败 |
| `worktree.ready` | `path` | 工作树就绪 |
| `worktree.failed` | `path, error?` | 工作树失败 |

### 14.12 其他事件

| type | 数据字段 | 说明 |
|------|---------|------|
| `file.edited` | `path` | 文件被编辑 |
| `file.watcher.updated` | `path` | 文件监视器更新 |
| `mcp.tools.changed` | `server` | MCP 工具变更 |
| `command.executed` | `name, sessionID, arguments, messageID` | 命令执行完成 |
| `installation.updated` | `version` | 已安装新版本 |
| `installation.update_available` | `version` | 有新版本可用 |

### 14.13 Session Next 细粒度事件

这是一套用于实时状态跟踪的高频事件，共 **22 种**。`type` 统一以 `session.next.` 开头。

#### Agent / Model 切换

| type | 数据字段 |
|------|---------|
| `session.next.agent.switched` | `sessionID, agent` |
| `session.next.model.switched` | `sessionID, providerID, modelID` |

#### 文本流式

| type | 数据字段 |
|------|---------|
| `session.next.text.started` | `sessionID, messageID, partID` |
| `session.next.text.delta` | `sessionID, messageID, partID, delta` |
| `session.next.text.ended` | `sessionID, messageID, partID` |

#### 推理流式

| type | 数据字段 |
|------|---------|
| `session.next.reasoning.started` | `sessionID, messageID, partID` |
| `session.next.reasoning.delta` | `sessionID, messageID, partID, delta` |
| `session.next.reasoning.ended` | `sessionID, messageID, partID` |

#### 工具执行

| type | 数据字段 |
|------|---------|
| `session.next.tool.input.started` | `sessionID, messageID, partID, callID, tool` |
| `session.next.tool.input.delta` | `sessionID, messageID, partID, callID, delta` |
| `session.next.tool.called` | `sessionID, messageID, partID, callID, tool, input?` |
| `session.next.tool.progress` | `sessionID, messageID, partID, callID, progress?, title?` |
| `session.next.tool.success` | `sessionID, messageID, partID, callID, output` |
| `session.next.tool.failed` | `sessionID, messageID, partID, callID, error` |

#### Step 生命周期

| type | 数据字段 |
|------|---------|
| `session.next.step.started` | `sessionID, messageID, step, agent?, model?` |
| `session.next.step.ended` | `sessionID, messageID, step` |
| `session.next.step.failed` | `sessionID, messageID, step, error` |

#### Shell

| type | 数据字段 |
|------|---------|
| `session.next.shell.started` | `sessionID, messageID, partID, command?` |
| `session.next.shell.ended` | `sessionID, messageID, partID, exitCode` |

#### Compaction

| type | 数据字段 |
|------|---------|
| `session.next.compaction.started` | `sessionID, messageID, reason?` |
| `session.next.compaction.delta` | `sessionID, messageID, delta` |
| `session.next.compaction.ended` | `sessionID, messageID` |

#### 其他

| type | 数据字段 |
|------|---------|
| `session.next.prompted` | `sessionID, messageID` |
| `session.next.retried` | `sessionID, attempt, error` |
| `session.next.synthetic` | `sessionID, messageID` |

---

## 15. 数据模型

### Session

```json
{
  "id": "string (UUID)",
  "slug": "string",
  "projectID": "string",
  "directory": "string",
  "parentID": "string? (null = 根会话)",
  "title": "string?",
  "version": "string",
  "time": {
    "created": 1234567890,    // unix ms
    "updated": 1234567890,
    "compacting": 1234567890?,  // 可选
    "archived": 1234567890?     // 非null = 已归档
  },
  "summary": {
    "additions": 0,
    "deletions": 0,
    "files": 0,
    "diffs": [{ "file", "before", "after", "additions", "deletions", "status?" }]
  }?,
  "share": { "url": "string" }?,
  "permission": [{ "permission", "pattern", "action" }]*,
  "revert": { "messageID", "partID?", "snapshot?", "diff?" }?,
  // --- V2 新增 ---
  "workspaceID": "string?",
  "path": "string?",
  "cost": 0.0?,
  "tokens": {                       // Session 级累计 tokens
    "input": 0,
    "output": 0,
    "reasoning": 0,
    "cache": { "read": 0, "write": 0 }
  }?,
  "agent": "string?",
  "model": {
    "id": "string",
    "providerID": "string",
    "variant": "string?"
  }?
}
```

### Message（多态，由 `role` 字段区分）

#### User Message

```json
{
  "id": "string",
  "sessionID": "string",
  "role": "user",
  "time": { "created": 1234567890, "completed": 1234567890? },
  "agent": "string?",
  "model": { "providerID": "string", "modelID": "string" }?,
  "format": { "type": "string", "schema": "json?", "retryCount": 0? }?,
  "summary": { "title?", "body?", "diffs?" }?,
  "system": "string?",
  "tools": { "string": bool }?,
  "variant": "string?"
}
```

#### Assistant Message

```json
{
  "id": "string",
  "sessionID": "string",
  "role": "assistant",
  "time": { "created": 1234567890, "completed": 1234567890? },
  "parentID": "string",
  "modelID": "string?",
  "providerID": "string?",
  "agent": "string?",
  "mode": "string?",
  "path": { "cwd": "string", "root": "string" }?,
  "cost": 0.0?,                      // 本次调用的费用（美元）
  "tokens": {                        // 单次 LLM 调用的 token 消耗
    "input": 0,                      // non-cached input tokens（不包含 cache）
    "output": 0,                     // output tokens（包含 reasoning）
    "total": 0?,                     // 可选: input + output + reasoning + cache
    "reasoning": 0,                  // output 中用于推理的部分
    "cache": { "read": 0, "write": 0 }  // cached input tokens（独立于 input）
  }?,
  "finish": "string?",               // stop_reason
  "error": { "name": "string", "data": { "message": "..." }? }?,
  "structured": "json?",
  "variant": "string?",
  "summary": true?
}
```

### MessageWithParts

```json
{
  "info": "<Message object>",
  "parts": ["<Part objects>"]
}
```

### Part（多态，由 `type` 字段区分）

| type | 说明 | 特有字段 |
|------|------|---------|
| `text` | 文本内容 | `text`, `synthetic?`, `ignored?`, `time?: {start, end?}`, `metadata?` |
| `reasoning` | 推理内容 | `text`, `time?, metadata?` |
| `tool` | 工具调用 | `callID`, `tool`, `state: ToolState` |
| `step-start` | 步骤开始 | `snapshot?` |
| `step-finish` | 步骤结束 | `reason`, `snapshot?`, `cost?`, `tokens?: {input, output, total?, reasoning, cache?}` |
| `file` | 文件附件 | `mime`, `filename?`, `url?`, `source?` |
| `snapshot` | 代码快照 | `snapshot` |
| `patch` | 代码补丁 | `hash`, `files: string[]` |
| `subtask` | 子任务 | `prompt`, `description?`, `agent?`, `model?: {providerID, modelID}`, `command?` |
| `compaction` | 压缩标记 | `auto: bool` |
| `retry` | 重试 | `attempt`, `error?`, `time?: {created}` |
| `abort` | 中止 | `reason` |
| `agent` | Agent 切换 | `name`, `source?` |
| `session-turn` | 会话轮次 | — |

**公共字段**（所有 Part 都有）: `id`, `sessionID`, `messageID`

### ToolState（多态，由 `status` 字段区分）

| status | 说明 | 字段 |
|--------|------|------|
| `pending` | 待执行 | `input: Map`, `raw?` |
| `running` | 执行中 | `input`, `title?`, `metadata?`, `time?: {start}` |
| `completed` | 完成 | `input`, `output`, `title?`, `metadata?`, `time?: {start, end, compacted?}`, `attachments?: [{type, data?}]` |
| `error` | 出错 | `input`, `error`, `metadata?`, `time?: {start, end}` |

### Project

```json
{
  "id": "string",
  "worktree": "string",
  "name": "string?",
  "path": "string",
  "vcs": "string?",
  "directory": "string?"
}
```

### FileDiff

```json
{
  "file": "string",
  "before": "string",
  "after": "string",
  "additions": 0,
  "deletions": 0,
  "status": "added | deleted | modified?"
}
```

### PromptRequest

```json
{
  "parts": [
    { "type": "text", "text": "string" },
    { "type": "file", "path": "string", "mime": "string" },
    { "type": "url", "url": "string", "mime": "string", "filename": "string?" }
  ],
  "model": { "providerID": "string", "modelID": "string" }?,
  "agent": "string?",
  "variant": "string?",
  "format": { "type": "string", "schema": "string?" }?,
  "system": "string?",
  "noReply": true?
}
```

### ShellRequest

```json
{
  "agent": "string",
  "model": { "providerID": "string", "modelID": "string" }?,
  "command": "string"
}
```

### ServerConfigResponse

```json
{
  "disabled_providers": ["string"],
  "enabled_providers": ["string"]?,
  "model": "string?",
  "small_model": "string?",
  "default_agent": "string?"
}
```

### ServerConfigPatch

```json
{
  "disabled_providers": ["string"]?,
  "model": "string?",
  "small_model": "string?",
  "default_agent": "string?"
}
```

### ServerPaths

```json
{
  "home": "string",
  "state": "string",
  "config": "string",
  "worktree": "string",
  "directory": "string"
}
```

### ProvidersResponse

```json
{
  "providers": [ProviderInfo],
  "default": { "providerId": "modelId" }
}
```

### ProviderCatalogResponse

```json
{
  "all": [ProviderInfo],
  "default": { "providerId": "modelId" },
  "connected": ["providerId"]
}
```

### ProviderInfo

```json
{
  "id": "string",
  "name": "string",
  "source": "string",
  "env": ["string"],
  "key": "string?",
  "options": {},
  "models": {
    "modelId": {
      "id": "string",
      "providerID": "string",
      "name": "string",
      "family": "string?",
      "status": "active",
      "capabilities": {
        "temperature": false,
        "reasoning": false,
        "attachment": false,
        "toolcall": false
      }?,
      "cost": {
        "input": 0.0,
        "output": 0.0,
        "cache": { "read": 0.0, "write": 0.0 }?
      }?,
      "limit": {
        "context": 0,
        "input": 0?,
        "output": 0
      }?,
      "variants": {}?
    }
  }
}
```

### ProviderAuthMethod

```json
{
  "type": "string",
  "label": "string"
}
```

### ProviderOauthAuthorization

```json
{
  "url": "string",
  "method": "string",
  "instructions": "string"
}
```

### AgentInfo

```json
{
  "name": "string",
  "description": "string?",
  "mode": "string (默认 primary)",
  "hidden": false,
  "color": "string?"
}
```

### CommandInfo

```json
{
  "name": "string",
  "description": "string?",
  "source": "string?",
  "hints": ["string"]
}
```

### SkillInfo

```json
{
  "name": "string",
  "description": "string?",
  "location": "string",
  "content": "string"
}
```

### PtyInfo

```json
{
  "id": "string",
  "title": "string",
  "command": "string",
  "args": ["string"],
  "cwd": "string",
  "status": "string",
  "pid": 0
}
```

### PtyCreateRequest

```json
{
  "title": "string?",
  "cwd": "string?"
}
```

### PtyUpdateRequest

```json
{
  "title": "string?",
  "size": { "rows": 0, "cols": 0 }?
}
```

### ShellInfo

```json
{
  "path": "string",
  "name": "string",
  "acceptable": true
}
```

### FileNode

```json
{
  "name": "string",
  "path": "string",
  "type": "string",
  "absolute": "string?",
  "ignored": false,
  "size": 0?,
  "modified": 0?
}
```

### FileContent

```json
{
  "type": "string",
  "content": "string"
}
```

### SearchMatch

```json
{
  "path": "string",
  "lines": "string",
  "lineNumber": 0,
  "absoluteOffset": 0
}
```

### SymbolInfo

```json
{
  "name": "string",
  "kind": "string",
  "path": "string",
  "line": 0?,
  "language": "string?"
}
```

### FileStatusInfo

```json
{
  "path": "string",
  "status": "string",
  "staged": false
}
```

### PermissionRequest

```json
{
  "id": "string",
  "sessionID": "string",
  "permission": "string",
  "patterns": ["string"],
  "metadata": {}?,
  "always": true?,
  "tool": { "messageID": "string", "callID": "string" }?
}
```

### QuestionRequest

```json
{
  "id": "string",
  "sessionID": "string",
  "questions": [
    {
      "question": "string",
      "header": "string",
      "multiple": false,
      "custom": true,
      "options": [
        { "label": "string", "description": "string" }
      ]
    }
  ],
  "tool": { "messageID": "string", "callID": "string" }?
}
```

### TodoItem

```json
{
  "id": "string",
  "content": "string",
  "status": "pending | in_progress | completed",
  "priority": "high | medium | low"
}
```

### RestSessionStatusInfo

```json
{
  "type": "idle | busy | retry",
  "attempt": 0?,
  "message": "string?",
  "next": 0?        // 下次重试时间戳 (unix ms)
}
```

---

## 16. Token / Context Usage

### 概述

OpenCode 的 token 体系分为**两层**：

| 层级 | 来源 | 说明 |
|------|------|------|
| **消息级** | `Message.Assistant.tokens` | 单次 LLM 调用的 token 消耗 |
| **Session 级** | `Session.tokens` | 整个 session 的累计 token（V2） |

### 消息级 Token 语义

```json
{
  "input": 1234,         // 本次 LLM 调用的 non-cached input tokens
  "output": 567,         // 本次 LLM 调用的 output tokens
  "total": 1801?,        // 可选总计 = input + output + reasoning + cache.read + cache.write
  "reasoning": 89,       // output 中用于推理的部分
  "cache": {
    "read": 500,         // 从 cache 读取的 input tokens（不包含在 input 中）
    "write": 200         // 写入 cache 的 input tokens（不包含在 input 中）
  }
}
```

**关键语义（已通过实际 API 调用验证）**：
- `input` **不包含** `cacheRead` / `cacheWrite`，各字段是独立的非重叠值
- `output` **包含** `reasoning`
- `total` = `input + output + reasoning + cache.read + cache.write`
- 每条 assistant 消息的 `tokens` 是**单次 LLM API 调用**的消耗，非累计

**实际 API 返回示例**（两轮对话）：
```json
// 第 1 轮 assistant message:
{ "total": 37498, "input": 37490, "output": 4, "reasoning": 4, "cache": { "read": 0, "write": 0 } }
// 验证: 37490 + 4 + 4 + 0 + 0 = 37498 ✓

// 第 2 轮 assistant message（有 cache 命中）:
{ "total": 37545, "input": 94, "output": 3, "reasoning": 8, "cache": { "read": 37440, "write": 0 } }
// 验证: 94 + 3 + 8 + 37440 + 0 = 37545 ✓
// 注意: input=94 不包含 cacheRead=37440
```

### Session 级 Token 语义

```json
{
  "tokens": {
    "input": 12345,      // 累计 non-cached input tokens
    "output": 2345,      // 累计 output tokens
    "reasoning": 567,    // 累计 reasoning tokens
    "cache": {
      "read": 3000,      // 累计 cache read（不包含在 input 中）
      "write": 1500      // 累计 cache write（不包含在 input 中）
    }
  },
  "cost": 0.12           // 累计费用（美元）
}
```

**更新机制**: 服务端通过 Projector SQL 累加。每当 `step-finish` Part 到达时自动累加到 session 行。当消息/part 被删除或 revert 时执行减法。

**实际 API 返回示例**：
```json
// 上述两轮对话后，session.tokens:
{ "input": 37584, "output": 7, "reasoning": 12, "cache": { "read": 37440, "write": 0 } }
// 验证: input = 37490 + 94 = 37584 ✓
//        cacheRead = 0 + 37440 = 37440 ✓
```

**Revert 后的 tokens 变化**：
```json
// revert 到第 1 条消息后，发送新消息:
// session.tokens:
{ "input": 52, "output": 10, "reasoning": 26, "cache": { "read": 37440, "write": 0 } }
// 注意: revert 后 session.tokens 被减去了被 revert 的消息的 tokens，只保留剩余消息的累计
// session.revert 被清除（新消息发送后变为 undefined）
```

### Context Window 使用率计算

Context window 限制的是 **input tokens**（LLM 接收的 prompt 大小）。output 和 reasoning 是 LLM 生成的，不占 input context window。

**正确的计算方式**：
```
usedContext = input + cacheRead + cacheWrite   // 真正占用 context window 的 input 部分
usage% = usedContext / model.limit.context * 100
```

**OpenCode WebUI 的计算方式**（粗略估算）：
```
1. 找到最后一条有 token 消耗的 assistant message
2. total = input + output + reasoning + cache.read + cache.write
3. limit = provider.models[message.modelID].limit.context
4. usage% = total / limit * 100
```

> **注意**: WebUI 把 output/reasoning 也算进了 context usage，这是不精确的。更准确的计算应该只算 input 部分。

**ACP (Agent Client Protocol) 的用法**（最精确）：
```json
{
  "used": input + cacheRead,    // 只取 input + cacheRead（不含 cacheWrite）
  "size": contextLimit,
  "cost": { "amount": 0.12, "currency": "USD" }
}
```

### Context Window Limit 获取

```
GET /config/providers → providers[].models[].limit.context
```

三级 fallback（oc-remote 实现）:
1. `tokenStats.contextWindow`（之前计算的缓存值）
2. `session.model` → 从 provider 列表中查找对应 model 的 `limit.context`
3. 当前选中 model 的 `contextWindow`

### 推荐的客户端实现

1. **Session 级 Token 聚合**: 优先使用 `session.updated` SSE 事件中的 `Session.tokens` 字段
2. **Fallback**: 累加所有 assistant 消息的 `tokens`（而非只取最后一条）
3. **Cost**: 累加所有 assistant 消息的 `cost`
4. **Context Usage 进度条**: `(input + cacheRead + cacheWrite) / model.limit.context * 100`
5. **Total Tokens 显示**: `input + output + reasoning + cacheRead + cacheWrite`（全部 token 消耗）

### Provider Model Context Limit 实际数据

```
GET /config/providers → providers[].models[].limit.context

Provider: zhipuai-coding-plan (Zhipu AI Coding Plan)
  glm-5.1          — context=200000
  glm-5v-turbo     — context=200000
  glm-5-turbo      — context=200000
  glm-4.5-air      — context=131072
  glm-4.6v         — context=128000

Provider: deepseek (DeepSeek)
  deepseek-chat         — context=1000000
  deepseek-reasoner     — context=1000000
  deepseek-v4-flash     — context=1000000
  deepseek-v4-pro       — context=1000000

Provider: opencode-go (OpenCode Go)
  minimax-m3       — context=512000
  kimi-k2.6        — context=262144
  qwen3.7-max      — context=1000000
  minimax-m2.7     — context=204800
```

---

## 端点总览

| # | 方法 | 路径 | 说明 |
|---|------|------|------|
| 1 | GET | `/global/health` | 健康检查 |
| 2 | GET | `/global/config` | 全局配置 |
| 3 | PATCH | `/global/config` | 更新全局配置 |
| 4 | GET | `/global/event` | 全局 SSE 事件流 |
| 5 | POST | `/global/dispose` | 销毁全局实例 |
| 6 | GET | `/path` | 服务器路径信息 |
| 7 | GET | `/project` | 列出项目 |
| 8 | GET | `/project/current` | 当前项目 |
| 9 | GET | `/agent` | 列出 Agent |
| 10 | GET | `/command` | 列出命令 |
| 11 | GET | `/skill` | 列出 Skills |
| 12 | GET | `/session` | 列出会话 |
| 13 | GET | `/session/status` | 会话状态批量查询 |
| 14 | POST | `/session` | 创建会话 |
| 15 | POST | `/session/import` | 导入会话 |
| 16 | GET | `/session/{id}` | 获取会话详情 |
| 17 | PATCH | `/session/{id}` | 更新会话 |
| 18 | DELETE | `/session/{id}` | 删除会话 |
| 19 | POST | `/session/{id}/abort` | 中止会话 |
| 20 | GET | `/session/{id}/diff` | 文件差异 |
| 21 | POST | `/session/{id}/summarize` | 压缩会话 |
| 22 | POST | `/session/{id}/revert` | 回退消息 |
| 23 | POST | `/session/{id}/unrevert` | 撤销回退 |
| 24 | POST | `/session/{id}/fork` | 分叉会话 |
| 25 | POST | `/session/{id}/share` | 分享会话 |
| 26 | DELETE | `/session/{id}/share` | 取消分享 |
| 27 | GET | `/session/{id}/children` | 子会话列表 |
| 28 | GET | `/session/{id}/todo` | Todo 列表 |
| 29 | POST | `/session/{id}/command` | 执行命令 |
| 30 | POST | `/session/{id}/shell` | Shell 命令 |
| 31 | POST | `/session/{id}/prompt_async` | 异步发送 prompt |
| 32 | GET | `/session/{id}/message` | 消息列表 |
| 33 | GET | `/session/{id}/message/{mid}` | 消息详情 |
| 34 | DELETE | `/session/{id}/message/{mid}` | 删除消息 |
| 35 | DELETE | `/session/{id}/message/{mid}/part/{idx}` | 删除 Part |
| 36 | GET | `/permission` | 待处理权限 |
| 37 | POST | `/permission/{id}/reply` | 回复权限 |
| 38 | GET | `/question` | 待处理问题 |
| 39 | POST | `/question/{id}/reply` | 回复问题 |
| 40 | POST | `/question/{id}/reject` | 拒绝问题 |
| 41 | GET | `/config/providers` | Provider 列表 |
| 42 | GET | `/provider` | Provider 目录 |
| 43 | GET | `/provider/auth` | 认证方式 |
| 44 | POST | `/provider/{id}/oauth/authorize` | OAuth 授权 |
| 45 | POST | `/provider/{id}/oauth/callback` | OAuth 回调 |
| 46 | PUT | `/auth/{id}` | 设置 API Key |
| 47 | DELETE | `/auth/{id}` | 删除认证 |
| 48 | GET | `/config` | 项目配置 |
| 49 | PATCH | `/config` | 更新项目配置 |
| 50 | POST | `/instance/dispose` | 销毁实例 |
| 51 | GET | `/event` | 实例 SSE 事件流 |
| 52 | POST | `/pty` | 创建 PTY |
| 53 | PUT | `/pty/{id}` | 更新 PTY |
| 54 | DELETE | `/pty/{id}` | 删除 PTY |
| 55 | WS | `/pty/{id}/connect` | WebSocket PTY |
| 56 | GET | `/pty/shells` | 可用 Shell |
| 57 | GET | `/file` | 目录列表 |
| 58 | GET | `/file/content` | 文件内容 |
| 59 | GET | `/file/status` | Git 文件状态 |
| 60 | GET | `/find` | 文本搜索 |
| 61 | GET | `/find/file` | 文件搜索 |
| 62 | GET | `/find/symbol` | 符号搜索 |

**总计**: 62 个 HTTP/WS 端点 + 52 种 SSE 事件类型
