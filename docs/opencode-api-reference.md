# OpenCode Server API Reference

> 基于 opencode 源码（`packages/opencode` + `packages/core`，2026-06 版本）深度调研验证。
> 覆盖 **140+ HTTP/WebSocket 端点** + **89 种 SSE 事件类型**。
> 每个描述均有源码证据；不确定的标注 `[待确认]`。
>
> 配套深度调研报告（按功能组）：`docs/opencode-api-deep-research/1~5-*.md`。

---

## 目录

- [通用机制](#通用机制)
- [1. Global 端点](#1-global-端点)（6 个）
- [2. Config 端点](#2-config-端点)（3 个）
- [3. Provider 端点](#3-provider-端点)（4 个）
- [4. MCP 端点](#4-mcp-端点)（8 个）
- [5. Project 端点](#5-project-端点)（5 个）
- [6. ProjectCopy 端点（实验性）](#6-projectcopy-端点实验性)（3 个）
- [7. Workspace 端点（实验性）](#7-workspace-端点实验性)（7 个）
- [8. Reference 端点](#8-reference-端点)（1 个）
- [9. Session 端点](#9-session-端点)（20 个）
- [10. Message 端点](#10-message-端点)（6 个）
- [11. Permission 端点](#11-permission-端点)（3 个）
- [12. Question 端点](#12-question-端点)（3 个）
- [13. PTY 端点](#13-pty-端点)（8 个）
- [14. TUI 端点](#14-tui-端点)（13 个）
- [15. 控制平面基础端点](#15-控制平面基础端点)（3 个）
- [16. Sync 端点](#16-sync-端点)（4 个）
- [17. Experimental 端点](#17-experimental-端点)（12 个）
- [18. Instance / VCS / 元信息端点](#18-instance--vcs--元信息端点)（12 个）
- [19. 跨项目控制平面端点](#19-跨项目控制平面端点)（1 个）
- [20. File / Find 端点](#20-file--find-端点)（6 个）
- [21. SSE 事件体系](#21-sse-事件体系)（89 种）
- [22. 数据模型](#22-数据模型)
- [23. Token / Context Usage](#23-token--context-usage)
- [端点总览](#端点总览)

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
- **Query 参数替代**: `?auth_token=base64(user:pass)`（优先级高于 Header，用于 WebSocket 等无法设置 Header 的场景）
- **⚠️ Global 端点无认证**: `GET/PATCH /global/*`、`POST /global/dispose`、`POST /global/upgrade` 注册在 `RootHttpApi`，**不经过 Authorization 中间件**。生产环境应通过反向代理加认证层或限制监听地址（仅 localhost）。

### 目录作用域 Header

- **Header**: `x-opencode-directory: <URL-encoded-path>`
- 用于指定项目工作目录上下文

### Workspace Routing Query（公共参数）

几乎所有实例级端点都接受这两个可选 query 参数（`middleware/workspace-routing.ts`）：

| 参数 | 类型 | 说明 |
|------|------|------|
| `directory` | `string?` | 工作目录。缺省取 `x-opencode-directory` 头或 `process.cwd()` |
| `workspace` | `string?` | 工作区 ID。用于路由到远程工作区（通过 sync 连接代理） |

> **重要**: 这两个参数虽不在各端点的业务 schema 中声明，但中间件会读取。HttpApi 会拒绝未声明却携带的参数（返回 400），所以端点 schema 需展开 `WorkspaceRoutingQueryFields`。

**路由逻辑**:
1. `workspace` 指定远程工作区 → 请求被**代理转发**到远程实例
2. `workspace` 指定本地工作区 → 使用该工作区的 `directory`
3. 未指定 → 使用 session 的 directory 或 `directory` 参数

### 通用请求/响应格式

- **Content-Type**: `application/json`
- 字段命名约定: camelCase，ID 后缀大写（如 `sessionID`, `providerID`）
- **DELETE 可带 body**: 部分 DELETE 端点（如 `/experimental/project/:id/copy`）要求 JSON body，需显式设置 `Content-Type`

### 错误码体系

| HTTP | 错误类 | 触发场景 |
|------|--------|---------|
| 400 | `InvalidRequestError` / `HttpApiError.BadRequest` | 参数校验失败 |
| 401 | `UnauthorizedError` | 未认证 |
| 403 | `ForbiddenError` / `PtyForbiddenError` | 无权限 / Origin 校验失败 |
| 404 | `ApiNotFoundError` / `SessionNotFoundError` / `MessageNotFoundError` / `ProjectNotFoundError` / `PtyNotFoundError` / `McpServerNotFoundError` / `PermissionNotFoundError` / `QuestionNotFoundError` | 资源不存在 |
| 409 | `SessionBusyError` / `ConflictError` | 会话忙碌（shell/revert/unrevert/deleteMessage）/ 资源冲突 |
| 500 | `UnknownError` / `HttpApiError.InternalServerError` | 未知内部错误（含路径逃逸检查 `Effect.die`） |
| 502 | `UpstreamError` | 上游服务错误 |
| 503 | `ServiceUnavailableError` | 服务不可用（如 sync 断开） |
| 504 | `TimeoutError` | 超时 |

---

## 1. Global 端点

> 路由：`groups/global.ts` · 注册在 `RootHttpApi`，**不经过** Instance/Workspace/Authorization 中间件

### GET `/global/health`

健康检查（无认证，可用于 k8s/lb 探针）。

**响应** `200`:

```json
{
  "healthy": true,
  "version": "string (OpenCode 版本)"
}
```

> `healthy` 恒为 `true`（服务不可达时本身已无法响应）。

### GET `/global/config`

获取全局配置（`~/.config/opencode/opencode.json`，跨所有项目）。无认证。

**响应** `200`: [`ConfigInfo`](#configinfo)

### PATCH `/global/config`

更新全局配置。**无认证**。

**请求体**: [`ConfigInfo`](#configinfo)（完整配置对象，全量替换）

**响应** `200`: [`ConfigInfo`](#configinfo)

**⚠️ 关键副作用**: 若配置实际变化（`result.changed === true`），异步触发 `disposeAllInstancesAndEmitGlobalDisposed()` —— **销毁所有运行中的实例**（包括进行中的会话）。客户端需监听 `global.disposed` 事件并重新建立连接。

### GET `/global/event`

全局 SSE 事件流（长连接）。推送所有目录的所有事件。无认证。

**Headers**: `Accept: text/event-stream`，可选 `x-opencode-directory`

**事件格式**（全局端点用 `payload` 包装）:

```
data: {"directory": "/path", "project": "...", "workspace": "...", "payload": {"id": "...", "type": "session.created", "properties": {...}}}
```

**连接生命周期**:
1. 首个事件：`{ type: "server.connected", properties: {} }`
2. 心跳：每 10 秒 `server.heartbeat`
3. 不会因实例销毁关闭（监听全局 bus）

### POST `/global/dispose`

销毁所有 OpenCode 实例，释放资源。无认证。

**响应** `200`: `boolean`（恒 `true`）

> 同步执行 `disposeAllInstancesAndEmitGlobalDisposed()`，阻塞直到完成。

### POST `/global/upgrade`

升级 OpenCode 到指定或最新版本。无认证。

**请求体**（允许空 body）:
```json
{ "target": "string? (目标版本号，缺省取最新)" }
```

**响应** `200`: 联合类型
```json
// 成功
{ "success": true, "version": "string" }
// 失败
{ "success": false, "error": "string" }
```

升级成功后发布全局事件 `installation.updated`（携带新版本号）。

---

## 2. Config 端点

> 路由：`groups/config.ts` · Handler：`handlers/config.ts`

### GET `/config`

获取当前实例配置（合并全局配置 + 项目级 `opencode.json`）。

**响应** `200`: [`ConfigInfo`](#configinfo)

### PATCH `/config`

更新当前实例配置。

**请求体**: [`ConfigInfo`](#configinfo)（完整配置对象，**全量替换**——未提供字段会被清除。客户端应先 GET 再修改后 PATCH）

**响应** `200`: [`ConfigInfo`](#configinfo)

**⚠️ 关键行为**:
1. `configSvc.update(payload)` 写入项目级配置文件
2. `markInstanceForDisposal()` **标记当前实例待销毁**（配置变更需重启实例生效）—— SSE/WS 连接会断开
3. 返回的是**输入 payload**，不重新读取磁盘（可能与下次 GET 因 JSON 规范化而不一致）

### GET `/config/providers`

列出**配置文件中显式定义**的 provider（不含 models.dev 内置）。

**响应** `200`:
```json
{
  "providers": [ProviderInfo],
  "default": { "providerId": "modelId" }
}
```

**与 `/provider` 的区别**: 此端点只返回用户自定义配置；`/provider` 返回所有可用 provider（models.dev + 已连接），更适合 UI 选择。

---

## 3. Provider 端点

> 路由：`groups/provider.ts`

### GET `/provider`

获取所有可用 AI provider（models.dev 内置 + 已连接），含连接状态。

**响应** `200`:
```json
{
  "all": [ProviderInfo],
  "default": { "providerId": "modelId" },
  "connected": ["providerId"]
}
```

**过滤逻辑**: 若配置了 `enabled_providers` 则只保留白名单内；排除 `disabled_providers`。

### GET `/provider/auth`

获取每个 provider 支持的认证方式（OAuth/API Key）。

**响应** `200`: `Record<providerId, `[`ProviderAuthMethod`](#providerauthmethod)`[]>`

### POST `/provider/{providerID}/oauth/authorize`

启动 OAuth 认证。

**请求体**:
```json
{
  "method": 0,                          // 认证方式索引（来自 /provider/auth 列表）
  "inputs": { "key": "value" }          // 可选，用户填写的 prompt 答案
}
```

**响应** `200`: [`ProviderOauthAuthorization`](#provideroauthauthorization)` | null`

> **⚠️ 返回 `null` 而非空对象**: 当 `authorize()` 返回 `undefined`（如已认证、无需重定向），HTTP body 是 JSON `null`。客户端应先检查 `result === null` 再访问字段。

### POST `/provider/{providerID}/oauth/callback`

完成 OAuth 回调，用授权码换取 token。

**请求体**:
```json
{
  "method": 0,              // 必须与 authorize 时一致
  "code": "string?"         // code method 时必填；auto method 时缺省
}
```

**响应** `200`: `boolean`（恒 `true`）

**错误子类型**（统一 `ProviderAuthApiError` HTTP 400，通过 `name` 区分）:
- `ProviderAuthOauthMissing` — provider 不支持 OAuth
- `ProviderAuthOauthCodeMissing` — code method 缺少 code
- `ProviderAuthOauthCallbackFailed` — token 交换失败
- `ProviderAuthValidationFailed` — 输入校验失败（含 `field` + `message`）

---

## 4. MCP 端点

> 路由：`groups/mcp.ts` · Handler：`handlers/mcp.ts` · 核心：`MCP.Service`

### GET `/mcp`

获取所有 MCP 服务器的当前状态。

**响应** `200`: `Record<string, `[`MCPStatus`](#mcpstatus)`>`（key 为 MCP 名称）

### POST `/mcp`

动态添加新的 MCP 服务器。

**请求体**:
```json
{
  "name": "string",
  "config": ConfigMCPInfo    // local 或 remote 联合，见数据模型
}
```

**响应** `200`: `Record<string, `[`MCPStatus`](#mcpstatus)`>`（仅含新添加的服务器）

### POST `/mcp/{name}/auth`

启动 MCP OAuth 认证流程（两步式）。

**响应** `200`:
```json
{
  "authorizationUrl": "string",    // 浏览器需访问的 URL
  "oauthState": "string"           // OAuth state，用于回调验证
}
```

**错误**: `UnsupportedOAuthError`（400，MCP 不支持 OAuth）/ `McpServerNotFoundError`（404）

### POST `/mcp/{name}/auth/callback`

完成 MCP OAuth 认证（两步式的第二步）。

**请求体**: `{ "code": "string" }`

**响应** `200`: [`MCPStatus`](#mcpstatus)（通常为 `connected`）

### POST `/mcp/{name}/auth/authenticate`

**一键式** MCP OAuth 认证：服务端自动开浏览器并**阻塞**等待回调完成。

**响应** `200`: [`MCPStatus`](#mcpstatus)

> 不适用于无头/远程场景（无法打开浏览器会一直挂起）。

### DELETE `/mcp/{name}/auth`

移除 MCP OAuth 凭据。

**响应** `200`: `{ "success": true }`

### POST `/mcp/{name}/connect`

显式触发 MCP 服务器连接（通常配置启用时自动连接，此端点用于手动重连）。

**响应** `200`: `boolean`（恒 `true`）

### POST `/mcp/{name}/disconnect`

显式断开 MCP 服务器。

**响应** `200`: `boolean`（恒 `true`）

---

## 5. Project 端点

> 路由：`groups/project.ts`

### GET `/project`

列出所有曾经用 OpenCode 打开过的项目。

**响应** `200`: `List<`[`Project`](#project)`>`

### GET `/project/current`

获取当前实例对应的项目（从实例上下文直接获取，无需查询）。

**响应** `200`: [`Project`](#project)

### POST `/project/git/init`

为当前项目初始化 git 仓库。

**响应** `200`: [`Project`](#project)（含 vcs 字段）

**⚠️ 关键行为**: 比较新旧 `id`/`vcs`/`worktree`，任一变化则 `markInstanceForReload()`（**reload 非 disposal**：保留实例进程和进行中会话，但 LSP/文件监听可能需重新初始化）。

### PATCH `/project/{projectID}`

更新项目元数据。

**请求体**（所有字段可选，**全量替换**语义）:
```json
{
  "name": "string?",
  "icon": "string?",
  "commands": { "key": "value" }?
}
```

**响应** `200`: [`Project`](#project)

**错误**: `ProjectNotFoundError`（404）

### GET `/project/{projectID}/directories`

列出指定项目在本地已知的所有目录（含 worktree）。

**响应** `200`: `ProjectV2.Directories`

---

## 6. ProjectCopy 端点（实验性）

> 路由：`groups/project-copy.ts` · 路径前缀 `/experimental/project/:projectID/copy` · 核心：`ProjectCopy.Service`

**项目副本**是项目的物理拷贝（如 git worktree、文件复制），用于在不影响原项目的情况下实验/分支工作。

### POST `/experimental/project/{projectID}/copy`

使用指定策略创建项目副本。

**请求体**:
```json
{
  "strategy": "git-worktree",         // 策略 ID
  "directory": "/abs/path",           // 目标目录绝对路径
  "name": "string?",                  // 副本名（缺省由 LLM 生成 3-4 词名称，失败 fallback 随机 slug）
  "context": "string?"                // 任务描述（用于 AI 生成名称）
}
```

**响应** `200`: [`ProjectCopyInfo`](#projectcopyinfo)

**错误**（`ApiProjectCopyError` HTTP 400）: `SourceDirectoryNotFoundError` / `DestinationExistsError` / `DirectoryUnavailableError` / `StrategyNotFoundError`。`forceRequired: true` 时可通过 remove 端点的 `force: true` 强制。

### DELETE `/experimental/project/{projectID}/copy`

移除项目副本（**DELETE 带 body**）。

**请求体**:
```json
{ "directory": "/abs/path", "force": false }
```

**响应** `204`

> ⚠️ 某些 HTTP 代理可能剥离 DELETE body，导致服务端收到空 body → 400。fetch API 支持。

### POST `/experimental/project/{projectID}/copy/refresh`

扫描本地，发现并注册未被系统知晓的项目副本。

**请求体**: 空 body（必需）

**响应** `204`

---

## 7. Workspace 端点（实验性）

> 路由：`groups/workspace.ts` · 路径前缀 `/experimental/workspace`

**工作区**是项目的并行工作单元（类似分支），通过 adapter 支持本地/远程 SSH/Docker 等。

### GET `/experimental/workspace/adapter`

列出当前项目可用的所有工作区适配器类型。

**响应** `200`: `List<`[`WorkspaceAdapterEntry`](#workspaceadapterentry)`>`

### GET `/experimental/workspace`

列出当前项目的所有工作区。

**响应** `200`: `List<`[`WorkspaceInfo`](#workspaceinfo)`>`

### POST `/experimental/workspace`

创建新工作区。

**请求体**: `Workspace.CreateInput`（去除 `projectID`，自动从实例上下文获取）

**响应** `200`: [`WorkspaceInfo`](#workspaceinfo)

**错误**: `ApiWorkspaceCreateError`（400，插件错误以 defect 形式穿透，handler 通过 `Effect.catchCause` 提取真实 error message）

### POST `/experimental/workspace/sync-list`

注册 adapter 中存在但本地数据库缺失的工作区。

**响应** `204`

### GET `/experimental/workspace/status`

获取当前项目所有工作区的连接状态（过滤为当前项目）。

**响应** `200`: `List<`[`WorkspaceConnectionStatus`](#workspaceconnectionstatus)`>`

### DELETE `/experimental/workspace/{id}`

移除工作区。

**响应** `200`: [`WorkspaceInfo`](#workspaceinfo)` | undefined`

### POST `/experimental/workspace/warp`

迁移会话到目标工作区，或从工作区脱离到本地项目。

**请求体**:
```json
{
  "id": "workspaceID | null",     // null 表示显式脱离工作区（区分"未提供"和"显式脱离"）
  "sessionID": "ses_...",
  "copyChanges": true              // 是否复制本地未提交变更（通过 git patch）
}
```

**响应** `204`

**错误**: `ApiWorkspaceWarpError`（400）/ `ApiVcsApplyError`（400，`reason: "non-git" | "not-clean"`）/ `ApiNotFoundError`（404，工作区不存在）

---

## 8. Reference 端点

> 路由：`groups/reference.ts` · 核心：`Reference.Service`

**引用**是配置中定义的命名快捷方式（本地路径或 git 仓库），通过 `@alias` 让 AI 快速访问常用代码/文档。

### GET `/reference`

列出当前工作区已解析的配置引用。

**响应** `200`: `List<ReferenceDescriptor>`（按 `kind` 判别联合）

```jsonc
// 本地引用
{ "name": "alias", "kind": "local", "path": "/abs/path" }
// Git 引用（branch 仅当非默认分支时出现）
{ "name": "alias", "kind": "git", "repository": "url", "path": "sub/path", "branch": "dev" }
// 无效引用（解析失败）
{ "name": "alias", "kind": "invalid", "repository": "url?", "message": "错误信息" }
```

---

## 9. Session 端点

> 路由：`groups/session.ts` · Handler：`handlers/session.ts`

### GET `/session`

列出会话（按最近更新排序）。

| Query 参数 | 类型 | 默认 | 说明 |
|-----------|------|------|------|
| `roots` | bool（`"true"/"false"` 字符串） | — | 仅返回根会话（非 fork） |
| `scope` | `"project"` | — | 限定为当前 project scope |
| `path` | string | — | 路径过滤 |
| `start` | number | — | 起始时间戳过滤 |
| `search` | string | — | 搜索关键词 |
| `limit` | number | — | 数量限制 |

> `roots` 用自定义 `QueryBoolean`（接受 `"true"/"false"` 字符串），非原生 boolean。

**响应** `200`: `List<`[`Session`](#session)`>`

### GET `/session/status`

批量获取所有会话状态（idle/busy/retry）。**状态纯内存维护，不持久化**——idle 会话不出现在 map 中（缺失默认 idle）。

**响应** `200`: `Map<sessionId, `[`RestSessionStatusInfo`](#restsessionstatusinfo)`>`

### POST `/session`

创建会话。**空 body 也能创建**（使用全部默认值）。

**请求体**（所有字段可选）:
```json
{
  "parentID": "ses_...?",
  "title": "string?",
  "agent": "string?",
  "model": { "id": "string", "providerID": "string", "variant": "string?" }?,
  "metadata": { "key": "value" }?,
  "permission": [...?, ...]?,
  "workspaceID": "string?"
}
```

**响应** `200`: [`Session`](#session)

> 使用 raw handler 手动解析 body，支持空 body。`permission` 字段会被展开为数组。

### POST `/session/import`

从分享 URL 导入会话。

**请求体**: `{ "url": "string" }`

**响应** `200`: [`Session`](#session)

### GET `/session/{sessionId}`

获取单个会话详情。**不验证 sessionID 是否存在**时返回 404（`ApiNotFoundError`）。

**响应** `200`: [`Session`](#session)

### PATCH `/session/{sessionId}`

更新会话。

**请求体**（所有字段可选）:
```json
{
  "title": "string?",
  "metadata": { "key": "value" }?,       // ⚠️ 完全替换
  "permission": [...],                    // ⚠️ 合并（Permission.merge），非替换！
  "time": { "archived": 1234567890 }?
}
```

**响应** `200`: [`Session`](#session)

> **⚠️ 隐藏陷阱**: `metadata` 是**替换**，`permission` 是**合并**。客户端若期望 permission 替换会踩坑。执行顺序：title → metadata → permission → archived。

### DELETE `/session/{sessionId}`

删除会话（永久删除会话及所有关联数据）。**不检查会话是否忙碌**。

**响应** `200`: `boolean`

### POST `/session/{sessionId}/abort`

中止会话当前操作。**不检查会话是否存在**（直接调用 cancel，幂等）。

**响应** `200`: `boolean`

### GET `/session/{sessionId}/diff`

获取会话文件差异。**不验证 sessionID 是否存在**（直接传给 summary 服务）。

| Query 参数 | 类型 | 说明 |
|-----------|------|------|
| `messageID` | string? | 指定消息 ID，不传则计算整个会话的 diff |

**响应** `200`: `List<`[`FileDiff`](#filediff)`>`

### POST `/session/{sessionId}/summarize`

压缩/总结会话（使用 AI 压缩保留关键信息）。

**请求体**:
```json
{
  "providerID": "string",       // 必填
  "modelID": "string",          // 必填
  "auto": false                 // 是否自动压缩（默认 false）
}
```

**响应** `200`: `boolean`

**内部流程**: cleanup 回滚状态 → 获取所有消息 → 找最后 user 消息的 agent（缺省 defaultAgent）→ compactSvc.create() → promptSvc.loop() 阻塞直到完成。

### POST `/session/{sessionId}/revert`

回滚到指定消息，**撤销文件变更**并恢复先前状态。

**请求体**:
```json
{ "messageID": "string", "partID": "string?" }
```

**响应** `200`: [`Session`](#session)

**错误**: 404 / 409（`SessionBusyError`，会话忙碌时）

### POST `/session/{sessionId}/unrevert`

恢复所有之前被回滚的消息。**无请求体**。

**响应** `200`: [`Session`](#session)

**错误**: 404 / 409（忙碌）

### POST `/session/{sessionId}/fork`

分叉会话。**空 body 也能 fork**（在最新消息处分叉）。

**请求体**: `{ "messageID": "string?" }`

**响应** `200`: [`Session`](#session)（新会话标题为 `原标题 (fork #N)`，N 递增）

### POST `/session/{sessionId}/share`

分享会话（生成链接）。

**响应** `200`: [`Session`](#session)（含 `share.url`）

**错误**: 500（`InternalServerError`，分享失败映射为 500 而非 400，因为是存储/网络问题）/ 404

### DELETE `/session/{sessionId}/share`

取消分享。

**响应** `200`: [`Session`](#session)

**错误**: 500 / 404

### GET `/session/{sessionId}/children`

获取子会话列表（从指定会话 fork 出来的所有子会话）。

**响应** `200`: `List<`[`Session`](#session)`>`

### GET `/session/{sessionId}/todo`

获取会话 Todo 列表（数据库 `TodoTable` 查询）。

**响应** `200`: `List<`[`TodoItem`](#todoitem)`>`

### POST `/session/{sessionId}/command`

执行服务器端斜杠命令。

**请求体**:
```json
{
  "command": "init",
  "arguments": "string (默认空)",
  "agent": "string?",
  "model": "string?",                       // ⚠️ 字符串格式 "providerID/modelID"
  "variant": "string?",
  "messageID": "string?",
  "parts": [{ "type": "file", ... }]?        // 仅 file 类型附件
}
```

**响应** `200`: [`MessageWithParts`](#messagewithparts)

> ⚠️ **`model` 字段格式不一致**: command 的 model 是字符串 `"providerID/modelID"`，prompt 的 model 是对象 `{ providerID, modelID }`。

### POST `/session/{sessionId}/shell`

在会话中运行 shell 命令。

**请求体**: [`ShellRequest`](#shellrequest)

**响应** `200`: [`MessageWithParts`](#messagewithparts)

**错误**: 400 / 404 / **409（`SessionBusyError`，shell 检查会话忙碌）**

### POST `/session/{sessionId}/message`

**同步发送消息**：阻塞等待整个 AI 响应循环完成后返回。

**请求体**: [`PromptRequest`](#promptrequest)

**响应** `200`: [`MessageWithParts`](#messagewithparts)（`application/json`）

> **⚠️ 不是流式响应**（尽管 OpenAPI 描述为 streaming）: handler 用 `HttpServerResponse.stream(Stream.make(JSON.stringify(message)))` 包装，**只推送一个 JSON chunk**（最终消息）。真正的实时流式必须通过 SSE 事件监听（`session.*` / `message.*`）。
>
> **客户端建议**: 需要实时反馈的场景应使用 `prompt_async` + SSE。

### POST `/session/{sessionId}/prompt_async`

异步发送 prompt（fire-and-forget）。

**请求体**: [`PromptRequest`](#promptrequest)

**响应** `204`

> **⚠️ 错误是"静默"的**: 返回 204 后，后台任务可能失败。错误通过 `session.error` SSE 事件通知（error 为 `NamedError.Unknown`）。客户端**必须监听 SSE** 才能感知异步失败。

### POST `/session/{sessionId}/init`

初始化 AGENTS.md（分析当前应用并创建文件）。

**请求体**:
```json
{
  "modelID": "string",        // 必填
  "providerID": "string",     // 必填
  "messageID": "string"       // 必填
}
```

**响应** `200`: `boolean`

内部调用 `promptSvc.command()`，command 为 `"init"`，使用 `initialize.txt` 模板。

---

## 10. Message 端点

### GET `/session/{sessionId}/message`

获取消息列表（含 user 和 assistant）。

| Query 参数 | 类型 | 说明 |
|-----------|------|------|
| `limit` | int ≥ 0 | 分页大小 |
| `before` | string | 游标（base64url 编码的 `{id, time}`） |

**响应** `200`: `List<`[`MessageWithParts`](#messagewithparts)`>`

**⚠️ 分页隐藏行为**（非标准 Header）:
- 传 `before` 但没传 `limit` → **400**
- `before` 解码失败 → **400**
- `limit` 未传或为 0 → 返回全部消息（无分页）
- `limit > 0` → 查询 `limit + 1` 条，按 `time_created DESC, id DESC` 排序后 `reverse()` 恢复时间正序
- 有更多数据时响应包含额外 Header:
  - `Link: <完整URL>; rel="next"`
  - `X-Next-Cursor: <cursor>`
  - `Access-Control-Expose-Headers: Link, X-Next-Cursor`

**游标格式**: `base64url(JSON.stringify({ id: MessageID, time: number }))`

### GET `/session/{sessionId}/message/{messageId}`

获取单条消息详情（`WHERE id = messageID AND session_id = sessionID`）。

**响应** `200`: [`MessageWithParts`](#messagewithparts)

### DELETE `/session/{sessionId}/message/{messageId}`

永久删除消息及其所有 part。**不撤销文件变更**（与 revert 不同）。**检查会话忙碌状态**（忙碌 → 409）。

**响应** `200`: `boolean`

### DELETE `/session/{sessionId}/message/{messageId}/part/{partId}`

删除消息中的某个 Part。**不检查忙碌状态**（与 deleteMessage 不同）。

**响应** `200`: `boolean`

### PATCH `/session/{sessionId}/message/{messageId}/part/{partId}`

更新 Part（全量替换）。

**请求体**: 完整的 [`Part`](#part多态由-type-字段区分) 对象

**响应** `200`: [`Part`](#part多态由-type-字段区分)

> **ID 一致性校验**: handler 验证三重 ID 匹配（`payload.id === partID`、`payload.messageID === messageID`、`payload.sessionID === sessionID`），任一不符返回 400。

---

## 11. Permission 端点

### GET `/permission`

列出**所有会话**的待处理权限请求。

**响应** `200`: `List<`[`PermissionRequest`](#permissionrequest)`>`

### POST `/permission/{requestID}/reply`

回复权限请求。

**请求体**:
```json
{
  "reply": "once | always | reject",
  "message": "string?"
}
```

**响应** `200`: `boolean`

**错误**: 400 / `PermissionNotFoundError`（404）

### POST `/session/{sessionId}/permissions/{permissionID}`（**已废弃**）

> ⚠️ **DEPRECATED**: OpenAPI 标注 `deprecated: true`。新接口为上面的 `POST /permission/:requestID/reply`。

**请求体**: `{ "response": "once | always | reject" }`

**响应** `200`: `boolean`

---

## 12. Question 端点

### GET `/question`

列出**所有会话**的待处理问题请求。

**响应** `200`: `List<`[`QuestionRequest`](#questionrequest)`>`

### POST `/question/{requestID}/reply`

回复问题。

**请求体**:
```json
{
  "answers": [
    ["option1", "option2"],    // 第一个问题的选中项（label 数组）
    ["optionA"]                // 第二个问题的选中项
  ]
}
```

> 外层数组对应每个 question，内层数组是对应 question 的选项（支持多选）。

**响应** `200`: `boolean`

**错误**: 400 / `QuestionNotFoundError`（404）

### POST `/question/{requestID}/reject`

拒绝问题。**无请求体**。

**响应** `200`: `boolean`

---

## 13. PTY 端点

> 路由：`groups/pty.ts` · 核心：`Pty.Service` + `PtyTicket.Service`

### GET `/pty/shells`

列出可用 shell。

**响应** `200`: `List<`[`ShellInfo`](#shellinfo)`>`

**平台检测**:
- Windows: `pwsh` → `powershell` → `git-bash` → `cmd.exe`
- Unix: `/etc/shells`（失败回退 `["/bin/bash", "/bin/zsh", "/bin/sh"]`）

`fish`/`nu` 标记为 `acceptable: false`。

### GET `/pty`

列出当前实例管理的所有活跃 PTY 会话。

**响应** `200`: `List<`[`PtyInfo`](#ptyinfo)`>`

### POST `/pty`

创建 PTY 终端。

**请求体**: [`PtyCreateRequest`](#ptycreaterequest)（所有字段可选）

**响应** `200`: [`PtyInfo`](#ptyinfo)（含生成的 `id` 和 `pid`）

**创建流程**:
1. `PtyPreparation.prepareCreate()` 准备参数（command/args/cwd/env 合并）
2. 生成 `PtyID.ascending()`（`pty_` 前缀升序 ULID）
3. 底层 spawn（Bun 用 `bun-pty`，Node 用 `@lydell/node-pty`）
4. 注册 `onData` → 广播给订阅者 + 追加到 2MB 环形缓冲区
5. 注册 `onExit` → 标记 `status: "exited"` → 发布 `pty.exited` → 自动移除会话
6. 发布 `pty.created`

### GET `/pty/{ptyId}`

获取单个 PTY 会话信息。

**响应** `200`: [`PtyInfo`](#ptyinfo)

**错误**: 404 `PtyNotFoundError`

### PUT `/pty/{ptyId}`

更新 PTY（标题/尺寸）。

**请求体**: [`PtyUpdateRequest`](#ptyupdaterequest)

**响应** `200`: [`PtyInfo`](#ptyinfo)

> `size` 提供时调用 `process.resize(cols, rows)`。

### DELETE `/pty/{ptyId}`

终止并移除 PTY 会话。

**响应** `200`: `boolean`

**行为**: `teardown()` → dispose 监听器 → `process.kill()` → 关闭所有 WebSocket 订阅者 → 发布 `pty.deleted`。

### POST `/pty/{ptyId}/connect-token`

获取 WebSocket 连接票据（**三步流程的第 1 步**）。

**Headers**: **必需** `x-opencode-ticket: 1` + Basic Auth + 合法 CORS Origin

**响应** `200`:
```json
{ "ticket": "uuid-v4", "expires_in": 60 }
```

**错误**: 403 `PtyForbiddenError`（Origin 不匹配或缺 `x-opencode-ticket` 头）/ 404

> **票据生命周期**: UUID v4，60 秒 TTL，容量上限 10,000（LRU），绑定 `(ptyID, directory, workspaceID)` 三元组，**一次性消费**。
>
> **为什么需要票据**: WebSocket 升级请求由浏览器发起，`Authorization` 头无法在 `new WebSocket(url)` 中设置。客户端先用 HTTP 获取一次性票据，再放在 WebSocket URL query 中完成认证。

### GET `/pty/{ptyId}/connect`（WebSocket）

WebSocket 连接 PTY 终端。

**URL**: `ws(s)://host:port/pty/{ptyId}/connect?ticket={uuid}&cursor={int}`

| Query 参数 | 类型 | 默认 | 说明 |
|-----------|------|------|------|
| `ticket` | string | — | connect-token 获取的票据。提供时跳过 Basic Auth |
| `cursor` | int | — | 历史回放起始字节偏移。`-1` = 跳过历史；非整数或 `< -1` → 400 |
| `directory` | string | — | 工作区路由 |
| `workspace` | string | — | 工作区 ID |

**认证**: 特殊 `PtyConnectAuthorization` — 有 ticket 跳过 Basic Auth，无 ticket 则需 Basic Auth。

**连接后服务端发送**:
1. **历史缓冲回放**（如果 cursor 有效）: 每个 PTY 维护 2MB 环形缓冲区，按 64KB 分块发送
   - `cursor` 缺省/0: 回放全部历史
   - `cursor` 为正整数: 从该偏移回放
   - `cursor = -1`: 跳过历史
2. **控制帧（meta frame）**: `[0x00, ...UTF-8 JSON bytes]`，内容 `{ "cursor": <当前总字节数> }`，告知客户端当前游标位置
3. **双向数据流**:
   - 服务端 → 客户端: PTY 子进程 stdout/stderr 原始字节流
   - 客户端 → 服务端: string 或 ArrayBuffer/Uint8Array（UTF-8 解码后转发，解码失败静默丢弃）

**关闭事件**:
| 事件 | 行为 |
|------|------|
| PTY 不存在 | 404（升级前检查） |
| cursor 非法 | 400 |
| 票据无效/Origin 不匹配 | 403 |
| 服务端正在关闭 | `CloseEvent(1001, "server closing")` |
| PTY 会话连接后消失 | `CloseEvent(4404, "session not found")` |

---

## 14. TUI 端点

> 路由：`groups/tui.ts` · 核心：`server/tui-event.ts` + `server/shared/tui-control.ts`
> **双通道架构**: 通道 A（事件推送，fire-and-forget）+ 通道 B（请求-响应队列）

### 通道 A：事件推送端点（10 个）

所有 POST 端点返回 `boolean`（恒 `true`），通过 `EventV2Bridge.Service.publish()` 发布事件到内部总线，TUI 订阅执行。

#### POST `/tui/append-prompt`

追加提示文本（**追加非替换**，不自动提交）。

**请求体**: `{ "text": "string" }`

#### POST `/tui/open-help`

打开帮助对话框。发布 `CommandExecute { command: "help.show" }`。

#### POST `/tui/open-sessions`

打开会话列表对话框。发布 `CommandExecute { command: "session.list" }`。

#### POST `/tui/open-themes`

打开主题对话框。**⚠️ 源码 bug**: 发布的是 `session.list`（与 `open-sessions` 相同，疑似源码笔误）。

#### POST `/tui/open-models`

打开模型选择对话框。发布 `CommandExecute { command: "model.list" }`。

#### POST `/tui/submit-prompt`

提交当前输入框内容。发布 `CommandExecute { command: "prompt.submit" }`。

#### POST `/tui/clear-prompt`

清空提示输入。发布 `CommandExecute { command: "prompt.clear" }`。

#### POST `/tui/execute-command`

执行 TUI 命令（**遗留别名映射**，未知命令映射为 `undefined` 静默失败）。

**请求体**: `{ "command": "string" }`

| 输入（旧名） | 映射到（新名） |
|-------------|---------------|
| `session_new` | `session.new` |
| `session_share` | `session.share` |
| `session_interrupt` | `session.interrupt` |
| `session_compact` | `session.compact` |
| `messages_page_up/down` | `session.page.up/down` |
| `messages_line_up/down` | `session.line.up/down` |
| `messages_half_page_up/down` | `session.half.page.up/down` |
| `messages_first/last` | `session.first/last` |
| `agent_cycle` | `agent.cycle` |

> **建议**: 新代码用 `/tui/publish` 直接发送 `CommandExecute` 事件。

#### POST `/tui/show-toast`

显示 Toast 通知。

**请求体**:
```json
{
  "title": "string?",
  "message": "string",                          // 必填
  "variant": "info | success | warning | error",
  "duration": 5000                              // PositiveInt，默认 5000ms
}
```

#### POST `/tui/publish`

**最通用的 TUI 控制端点**，一个请求可触发任何 TUI 事件。

**请求体**（4 种事件联合，按 `type` 判别）:
```jsonc
{ "type": "tui.prompt.append", "properties": { "text": "..." } }
{ "type": "tui.command.execute", "properties": { "command": "session.new" } }
{ "type": "tui.toast.show", "properties": { "message": "...", "variant": "info" } }
{ "type": "tui.session.select", "properties": { "sessionID": "ses_..." } }
```

#### POST `/tui/select-session`

导航到指定会话。

**请求体**: `{ "sessionID": "ses_..." }`

**错误**: 400（sessionID 不以 `"ses"` 开头）/ 404（会话不存在）

### 通道 B：请求-响应队列端点（2 个）

> 两个**全局单例** AsyncQueue（`request` 和 `response`），**无请求 ID 关联**，并发场景可能错配，适合串行场景。

#### GET `/tui/control/next`

TUI 端长轮询，阻塞等待外部进程通过队列发来的请求。

**响应** `200`: `TuiRequest`
```json
{ "path": "/sync/replay", "body": {} }
```

#### POST `/tui/control/response`

TUI 处理完请求后提交响应。

**请求体**: 任意 JSON（`Schema.Unknown`）

**响应** `200`: `boolean`

---

## 15. 控制平面基础端点

> 路由：`groups/control.ts` · 注册在 `RootHttpApi`，**不经过** Instance/Workspace/Authorization 中间件

### PUT `/auth/{providerID}`

设置认证凭据。

**请求体**: `AuthInfo`（OAuth/API Key/WellKnown 联合）
```jsonc
// OAuth
{ "type": "oauth", "client_id": "...", "client_secret": "...", "authorization_url": "...", "token_url": "..." }
// API Key
{ "type": "api_key", "api_key": "...", "headers": {} }
// WellKnown (OpenID Connect)
{ "type": "well_known", "url": "..." }
```

**响应** `200`: `boolean`

### DELETE `/auth/{providerID}`

移除认证凭据。

**响应** `200`: `boolean`

### POST `/log`

写入服务端日志（让客户端把自身日志写入 OpenCode 服务端统一日志流，便于调试）。

**请求体**:
```json
{
  "service": "string",                          // 服务名（标注用）
  "level": "debug | info | warn | error",
  "message": "string",
  "extra": { "key": "value" }                   // 额外元数据（作为 Effect 日志标注）
}
```

**响应** `200`: `boolean`

---

## 16. Sync 端点

> 路由：`groups/sync.ts` · 核心：`EventV2` 事件系统 + `EventTable`（SQLite 持久化）
> **事件溯源（Event Sourcing）同步模式**

### POST `/sync/start`

为当前项目中所有有活跃会话的工作区启动同步循环。

**响应** `200`: `boolean`

> 用 `Effect.ignore` + `Effect.forkIn(scope)` 在请求作用域内 fork 后台任务，同步循环生命周期绑定到实例 scope。

### POST `/sync/replay`

验证并回放完整事件历史到本地 EventTable。

**请求体**:
```json
{
  "directory": "/source/workspace",
  "events": [
    {
      "id": "evt_...",
      "aggregateID": "ses_...",
      "seq": 0,
      "type": "session.updated",
      "data": {}
    }
  ]
}
```

> `events` 至少 1 个，按 seq 升序。`aggregateID`（驼峰命名）。

**响应** `200`: `{ "sessionID": "string" }`（以第一个事件的 aggregateID 作为会话标识）

**处理**: 设置 `strictOwner: true` 验证事件归属后写入。

### POST `/sync/steal`

将会话"窃取"到当前工作区（更新会话归属）。

**请求体**: `{ "sessionID": "ses_..." }`

**响应** `200`: `{ "sessionID": "ses_..." }`（原样返回）

**错误**: 400（无 workspaceID 时）

### POST `/sync/history`

增量查询事件历史。

**请求体**: `Record<aggregateID, lastKnownSeq>`

**响应** `200`: `List<HistoryEvent>`（按 seq 升序）

```json
{
  "id": "evt_...",
  "aggregate_id": "ses_...",    // ⚠️ 蛇形命名（与 ReplayEvent 的 aggregateID 不同！）
  "seq": 0,
  "type": "session.updated",
  "data": {}
}
```

**查询逻辑**: 排除所有 `(aggregate_id = key AND seq <= value)`，返回其他所有事件（包括未在 payload 中列出的 aggregate 的全部历史）。

> ⚠️ **命名不一致**: `ReplayEvent.aggregateID`（驼峰，API 输入）vs `HistoryEvent.aggregate_id`（蛇形，SQLite 列映射）。客户端需做字段名映射。

---

## 17. Experimental 端点

> 路由：`groups/experimental.ts` · 路径前缀 `/experimental`

### GET `/experimental/console`

获取 Console 组织状态。

**响应** `200`:
```json
{
  "consoleManagedProviders": ["providerId"],
  "activeOrgName": "string?",
  "switchableOrgCount": 0
}
```

### GET `/experimental/console/orgs`

列出可切换的 Console 组织。

**响应** `200`: `{ "orgs": List<ConsoleOrgOption> }`

```json
{
  "accountID": "string",
  "accountEmail": "string",
  "accountUrl": "string",
  "orgID": "string",
  "orgName": "string",
  "active": true
}
```

### POST `/experimental/console/switch`

切换活跃 Console 组织。

**请求体**: `{ "accountID": "string", "orgID": "string" }`

**响应** `200`: `boolean`

### GET `/experimental/tool`

列出工具（含参数 schema）。

| Query 参数 | 类型 | 说明 |
|-----------|------|------|
| `provider` | string | **必填** provider ID |
| `model` | string | **必填** model ID |

**响应** `200`: `List<{ id, description, parameters }>`（parameters 是 JSON Schema）

### GET `/experimental/tool/ids`

列出所有已注册工具 ID（内置 + 动态注册）。

**响应** `200`: `string[]`

### GET `/experimental/worktree`

列出当前项目的所有工作树目录。

**响应** `200`: `string[]`

### POST `/experimental/worktree`

创建 git worktree 并运行启动脚本（**允许空 body**）。

**请求体**: `{ "name": "string?", "startCommand": "string?" }?`

**响应** `200`: [`WorktreeInfo`](#worktreeinfo)

### DELETE `/experimental/worktree`

删除 git worktree 及其分支。

**请求体**: `{ "directory": "string" }`

**响应** `200`: `boolean`

> 删除后还调用 `project.removeSandbox(projectID, directory)` 清理项目元数据。

### POST `/experimental/worktree/reset`

重置 worktree 分支到主分支。

**请求体**: `{ "directory": "string" }`

**响应** `200`: `boolean`

**Worktree 错误**（`WorktreeApiError` HTTP 400）: `WorktreeNotGitError` / `WorktreeNameGenerationFailedError` / `WorktreeCreateFailedError` / `WorktreeStartCommandFailedError` / `WorktreeRemoveFailedError` / `WorktreeResetFailedError` / `WorktreeListFailedError`

### GET `/experimental/session`

跨项目列出所有 OpenCode 会话（按更新时间排序）。

| Query 参数 | 类型 | 默认 | 说明 |
|-----------|------|------|------|
| `roots` | bool | — | 只返回根会话 |
| `start` | number | — | 起始偏移 |
| `cursor` | number | — | 分页游标（时间戳） |
| `search` | string | — | 搜索关键词 |
| `limit` | number | 100 | 每页数量（实际请求 `limit+1` 判断是否有更多） |
| `archived` | bool | — | 是否包含归档会话（默认排除） |

**响应** `200`: `List<Session.GlobalInfo>` + 可选 `x-next-cursor` 响应头

### POST `/experimental/session/{sessionId}/background`

将阻塞当前会话的同步子代理转为后台运行。

**响应** `200`: `boolean`（是否有任务被提升为后台）

> **门控**: 需运行时标志 `experimentalBackgroundSubagents` 开启，否则恒返回 `false`。

### GET `/experimental/resource`

获取所有已连接 MCP 服务器的资源列表。

**响应** `200`: `Record<string, MCP.Resource>`

---

## 18. Instance / VCS / 元信息端点

> 路由：`groups/instance.ts`

### POST `/instance/dispose`

标记当前实例为待销毁（非阻塞，实际清理由生命周期管理器异步执行）。

**响应** `200`: `boolean`

### GET `/path`

获取服务器路径信息。

**响应** `200`: [`ServerPaths`](#serverpaths)

### GET `/vcs`

获取 VCS 分支信息。

**响应** `200`: `{ "branch": "string?", "default_branch": "string?" }`

### GET `/vcs/status`

获取变更文件列表（不含 patch）。

**响应** `200`: `List<{ file, additions, deletions, status: "added"|"deleted"|"modified" }>`

### GET `/vcs/diff`

获取 diff（含 patch）。

| Query 参数 | 类型 | 说明 |
|-----------|------|------|
| `mode` | `"git" | "branch"` | **必填**。`git` = 工作树差异；`branch` = 与默认分支的差异 |
| `context` | number | diff 上下文行数（≥0） |

**响应** `200`: `List<{ file, patch?, additions, deletions, status? }>`

### GET `/vcs/diff/raw`

获取原始 patch 文本。

**响应** `200`: `text/x-diff` 文本

### POST `/vcs/apply`

应用 patch。

**请求体**: patch 文本

**响应** `200`: `{ "applied": true }`

**错误**: `ApiVcsApplyError`（400，`reason: "non-git" | "not-clean"`）

### GET `/agent`

列出所有可用 AI agent。

**响应** `200`: `List<`[`AgentInfo`](#agentinfo)`>`

### GET `/command`

列出所有可用斜杠命令。

**响应** `200`: `List<`[`CommandInfo`](#commandinfo)`>`

### GET `/skill`

列出所有可用 skill。

**响应** `200`: `List<`[`SkillInfo`](#skillinfo)`>`

### GET `/lsp`

获取 LSP 服务器状态。

**响应** `200`: `List<LSP.Status>`

### GET `/formatter`

获取格式化器状态。

**响应** `200`: `List<Format.Status>`

---

## 19. 跨项目控制平面端点

> 路由：`groups/control-plane.ts` · 注册在 `RootHttpApi`，无实例中间件

### POST `/experimental/control-plane/move-session`

跨目录迁移会话（可选转移本地代码变更）。

**请求体**:
```json
{
  "sessionID": "ses_...",
  "destination": { "directory": "/abs/path" },
  "moveChanges": true                // 是否转移本地未提交变更（通过 git patch）
}
```

**响应** `204`

**迁移流程**:
1. 获取会话当前位置，若与目标相同则直接返回
2. 校验源/目标目录属于**同一项目**（`projectID` 必须匹配，否则 `DestinationProjectMismatchError`）
3. 若 `moveChanges` 且目录不同: `git.patch(sourceDir)` → `git.applyPatch(destDir)`
4. 发布 `SessionEvent.Moved`（含新 location 和 subdirectory）
5. 若有 patch: `git.softResetChanges(sourceDir)` 清理源目录

**错误消息**（`ApiMoveSessionError` HTTP 400）:
- `Session not found: <sessionID>`
- `Destination directory belongs to another project`
- `Unable to apply your changes in the destination directory. The files may conflict with existing changes.`

---

## 20. File / Find 端点

> 路由：`groups/file.ts` · 核心：ripgrep + fff（frecency）+ LSP

### GET `/find`

文本搜索（ripgrep）。

| Query 参数 | 类型 | 说明 |
|-----------|------|------|
| `pattern` | string | ripgrep 兼容的正则/字面量模式 |

**响应** `200`: `List<`[`SearchMatch`](#searchmatch)`>`

> **硬编码 limit: 10**（客户端无法调整）。搜索失败用 `Effect.orDie` 转化为 defect（500）。

### GET `/find/file`

模糊文件搜索（fff 引擎，不可用降级到 ripgrep `FileSystem.find`）。

| Query 参数 | 类型 | 默认 | 说明 |
|-----------|------|------|------|
| `query` | string | — | 搜索关键词（必填） |
| `dirs` | `"true" \| "false"` | — | 是否包含目录（`"false"` 等价 `type=file`） |
| `type` | `"file" \| "directory"` | — | 限定类型（覆盖 `dirs`） |
| `limit` | int 1-200 | 10 | 结果数量上限 |

**响应** `200`: `string[]`（文件路径列表）

> ⚠️ **双引擎结果不一致**: fff 基于 frecency + 模糊评分，ripgrep 基于字面匹配，两次相同查询顺序可能不同。

### GET `/find/symbol`

符号搜索（LSP workspace symbols）。

| Query 参数 | 类型 | 说明 |
|-----------|------|------|
| `query` | string | 搜索查询 |

**响应** `200`: `List<`[`SymbolInfo`](#symbolinfo)`>`

> **⚠️ 桩实现**: 当前实现恒返回空数组 `[]`。替代方案：直接调用 LSP 或通过 PTY 运行 ctags/grep。

### GET `/file`

列出目录内容。

| Query 参数 | 类型 | 说明 |
|-----------|------|------|
| `path` | string | 相对路径（相对于实例 directory） |

**响应** `200`: `List<`[`FileNode`](#filenode)`>`

### GET `/file/content`

读取文件内容（文本或 base64 二进制）。

| Query 参数 | 类型 | 说明 |
|-----------|------|------|
| `path` | string | 文件路径 |

**响应** `200`: [`FileContent`](#filecontent)

**安全行为**:
- 路径逃逸检查: `FSUtil.contains(directory, file)`，逃逸 → `Effect.die`（**500 而非 400**）
- 文件不存在 → 返回 `{ type: "text", content: "" }`（空字符串，**非 404**）
- 文本内容 `trim()` 去除首尾空白

### GET `/file/status`

获取文件变更状态。

**响应** `200`: `List<`[`FileStatusInfo`](#filestatusinfo)`>`

> **⚠️ 桩实现**: 当前实现恒返回空数组 `[]`。替代方案：使用 `GET /vcs/status`。

---

## 21. SSE 事件体系

### 21.1 连接方式

| 端点 | 范围 | 认证 | 用途 |
|------|------|------|------|
| `GET /event` | **实例级**（当前 directory + workspace） | Basic Auth | 推送当前实例的事件 |
| `GET /global/event` | **全局**（跨项目） | 无 | 推送所有实例的事件 |

**`/event` 行为**:
1. 首个事件: `{ type: "server.connected", properties: {} }`
2. 过滤: `event.location?.directory === instance.directory` && workspace 匹配
3. 心跳: 每 10 秒 `server.heartbeat`
4. 实例销毁检测: 监听 GlobalBus 的 `server.instance.disposed`（directory 匹配）→ 推送后关闭 SSE 流

**`/global/event` 行为**:
1. 首个事件: `server.connected`
2. 后续: 所有 GlobalBus 事件（实例级事件通过 `EventV2Bridge` 桥接）
3. 心跳: 每 10 秒
4. **不自动关闭**（监听全局 bus）

### 21.2 事件格式

**`/event` 端点**（无包装）:
```
event: message
data: {"id":"...","type":"session.updated","properties":{...}}
```

**`/global/event` 端点**（`payload` 包装 + 来源信息）:
```
event: message
data: {"directory":"/path","project":"...","workspace":"...","payload":{"id":"...","type":"session.updated","properties":{...}}}
```

**⚠️ 关键**:
- 所有事件的 SSE `event` 字段固定为 `"message"`，**类型信息在 `data` JSON 的 `type` 字段中**
- 客户端**不要**使用 `es.addEventListener("session.next.text.delta", ...)` —— 不会触发
- 正确做法: `es.addEventListener("message", (e) => { const data = JSON.parse(e.data); switch(data.type) {...} })`

**响应头**:
```
Content-Type: text/event-stream
Cache-Control: no-cache, no-transform
X-Accel-Buffering: no          # 禁用 nginx 缓冲
X-Content-Type-Options: nosniff
```

### 21.3 事件分类总览（89 种）

| 分类 | 事件数 | 同步 | 说明 |
|------|--------|------|------|
| 系统与服务 | 4 | ❌ | 连接、心跳、销毁 |
| Session v1 遗留 | 7 | ✅ | 会话 CRUD（粗粒度） |
| Message v1 遗留 | 5 | 部分 | 消息/Part 更新（含 delta 瞬时） |
| **Session.next v2 细粒度** | **31** | 27✅/4❌ | AI 推理流程的核心事件 |
| Session 状态/生命周期 | 5 | ❌ | status、idle、diff、error、compacted |
| Todo | 1 | ❌ | 任务列表更新 |
| Permission v1 + v2 | 4 | ❌ | 权限请求（双轨迁移） |
| Question v1 + v2 | 6 | ❌ | 问题请求（双轨迁移） |
| PTY | 4 | ❌ | 终端会话生命周期 |
| MCP | 2 | ❌ | 工具变更、浏览器打开失败 |
| Project/VCS | 3 | ❌ | 项目更新、目录更新、分支更新 |
| LSP/IDE/Command | 3 | ❌ | LSP/IDE 状态、命令执行 |
| Account/Catalog/Plugin | 5 | ❌ | 账户/模型目录/插件管理 |
| Filesystem | 2 | ❌ | 文件编辑、watcher |
| Installation | 2 | ❌ | 版本更新 |
| Workspace/Worktree | 5 | ❌ | 工作区状态/就绪/失败 |
| TUI | 4 | ❌ | TUI 交互（仅 TUI 进程消费） |
| **合计** | **89** | | |

### 21.4 系统与服务事件（4 个）

| type | properties | 触发 |
|------|-----------|------|
| `server.connected` | `{}` | 客户端建立 SSE 连接时，服务端主动发送的首个事件 |
| `server.heartbeat` | `{}` | 每 10 秒（`Stream.tick` drop 第一个避免立即触发） |
| `server.instance.disposed` | `{ directory }` | 当前实例被销毁时（配置变更、显式 dispose、升级）。**不在 EventV2 registry**，是 `/event` handler 手动监听 GlobalBus 构造 |
| `global.disposed` | `{}` | 所有实例被销毁时（`POST /global/dispose`、全局配置更新） |

### 21.5 Session v1 遗留事件（7 个，✅ 同步）

> 粗粒度，传递完整 `SessionInfo` 或 `Info` 对象

| type | properties | 触发 |
|------|-----------|------|
| `session.created` | `{ sessionID, info: Session }` | `Session.create()` 后 |
| `session.updated` | `{ sessionID, info: Session }` | 会话**元数据**变更（标题、权限、归档等） |
| `session.deleted` | `{ sessionID, info: Session }` | `Session.remove()` 后（含被删除会话的最后状态） |
| `message.updated` | `{ sessionID, info: Message }` | 消息内容/状态/Part 变更（粗粒度全量更新） |
| `message.removed` | `{ sessionID, messageID }` | `session.removeMessage()` 后 |
| `message.part.updated` | `{ sessionID, part: Part, time }` | Part 内容变更（工具调用完成、文本更新等） |
| `message.part.removed` | `{ sessionID, messageID, partID }` | `DELETE .../part/:id` 后 |

> **⚠️ Token 相关**: `session.updated` 的触发条件是**元数据变更**（标题/权限/归档），**token 累加不触发 `session.updated`**（详见 [§23 Token](#23-token--context-usage)）。

### 21.6 Message v1 流式事件（1 个，❌ 瞬时）

| type | properties | 触发 |
|------|-----------|------|
| `message.part.delta` | `{ sessionID, messageID, partID, field, delta }` | Part 字段的流式增量更新（`field`: `"output"` 工具输出追加 / `"text"` 文本追加 / 自定义） |

> **瞬时事件**，不持久化，断线丢失。

### 21.7 Session.next v2 细粒度事件（31 个，核心）

> v2 风格: 生命周期 + delta 模式（`*.started` → `*.delta` → `*.ended`）
> 通用字段: `{ timestamp: ISO 8601 UTC, sessionID, ... }`

**同步语义**: 27 个 Durable（可同步），4 个 Ephemeral（瞬时 `.delta`）

#### 会话控制事件（9 个）

| type | properties（除 timestamp/sessionID） | 同步 |
|------|-------------------------------------|------|
| `session.next.agent.switched` | `messageID, agent` | ✅ v1 |
| `session.next.model.switched` | `messageID, model: { id, providerID }` | ✅ v1 |
| `session.next.moved` | `location: Location.Ref, subdirectory?` | ✅ v1 |
| `session.next.prompted` | `messageID, prompt, delivery: "steer" \| "queue"` | ✅ v1 |
| `session.next.prompt.admitted` | 同 prompted | ✅ v1 |
| `session.next.prompt.promoted` | `messageID, prompt, timeCreated` | ✅ v1 |
| `session.next.interrupt.requested` | — | ✅ v1 |
| `session.next.context.updated` | `messageID, text` | ✅ v1 |
| `session.next.synthetic` | `messageID, text` | ✅ v1 |

**`delivery` 字段**: `"steer"`（引导当前对话）/ `"queue"`（排队等待处理）

#### Shell 命令事件（2 个）

| type | properties | 同步 |
|------|-----------|------|
| `session.next.shell.started` | `messageID, callID, command` | ✅ v1 |
| `session.next.shell.ended` | `callID, output` | ✅ v1 |

#### Step（推理步骤）事件（3 个）

| type | properties | 同步 |
|------|-----------|------|
| `session.next.step.started` | `assistantMessageID, agent, model, snapshot?` | ✅ v1 |
| `session.next.step.ended` | `assistantMessageID, finish, cost, tokens, snapshot?` | ✅ **v2** |
| `session.next.step.failed` | `assistantMessageID, error: { type: "unknown", message }` | ✅ **v2** |

**`finish` 值**: `"stop"` / `"length"` / `"tool-calls"` / `"content-filter"` 等

**`tokens` 结构**（**Token 相关事件 payload 核心**）:
```typescript
{
  input: number,        // 输入 token（不含 cache）
  output: number,       // 输出 token（含 reasoning）
  reasoning: number,    // output 中用于推理的部分
  cache: { read: number, write: number }  // 缓存读写（独立于 input）
}
```

**`snapshot`**: 文件系统快照 ID，用于 revert 时恢复文件状态。

#### Text（文本输出）事件（3 个）

| type | properties | 同步 |
|------|-----------|------|
| `session.next.text.started` | `assistantMessageID, textID` | ✅ v1 |
| `session.next.text.delta` | `assistantMessageID, textID, delta` | ❌ **瞬时** |
| `session.next.text.ended` | `assistantMessageID, textID, text`（完整内容） | ✅ v1 |

#### Reasoning（推理过程）事件（3 个）

| type | properties | 同步 |
|------|-----------|------|
| `session.next.reasoning.started` | `assistantMessageID, reasoningID, providerMetadata?` | ✅ v1 |
| `session.next.reasoning.delta` | `assistantMessageID, reasoningID, delta` | ❌ **瞬时** |
| `session.next.reasoning.ended` | `assistantMessageID, reasoningID, text, providerMetadata?` | ✅ v1 |

#### Tool（工具调用）事件（8 个）

| type | properties | 同步 |
|------|-----------|------|
| `session.next.tool.input.started` | `assistantMessageID, callID, name` | ✅ v1 |
| `session.next.tool.input.delta` | `assistantMessageID, callID, delta` | ❌ **瞬时** |
| `session.next.tool.input.ended` | `assistantMessageID, callID, text`（完整参数 JSON） | ✅ v1 |
| `session.next.tool.called` | `assistantMessageID, callID, tool, input, provider: { executed, metadata? }` | ✅ v1 |
| `session.next.tool.progress` | `assistantMessageID, callID, structured, content`（**有界更新**，非每行 stdout） | ✅ v1 |
| `session.next.tool.success` | `assistantMessageID, callID, structured, content, outputPaths?, result?, provider` | ✅ v1 |
| `session.next.tool.failed` | `assistantMessageID, callID, error, result?, provider` | ✅ v1 |

> 已知命令字面量（22 个）: `session.list/new/share/interrupt/compact`、`session.page.up/down`、`session.line.up/down`、`session.half.page.up/down`、`session.first/last`、`prompt.clear/submit`、`agent.cycle`、`help.show`、`model.list`、`theme.list`

**`provider.executed`**: 是否由 provider 端执行（如 OpenAI 内置工具）vs 本地执行。
**`outputPaths`**: 工具输出保存到磁盘的文件路径列表（当输出超过阈值时）。

#### Retry 事件（1 个）

| type | properties | 同步 |
|------|-----------|------|
| `session.next.retried` | `attempt, error: { message, statusCode?, isRetryable, responseHeaders?, responseBody?, metadata? }` | ✅ v1 |

#### Compaction（压缩）事件（4 个）

| type | properties | 同步 |
|------|-----------|------|
| `session.next.compaction.started` | `messageID, reason: "auto" \| "manual"` | ✅ v1 |
| `session.next.compaction.delta` | `messageID, text` | ❌ **瞬时** |
| `session.next.compaction.ended` | v1: `{ text, include? }` / **v2**: `{ messageID, reason, text, recent }` | v1✅ / v2✅ |

> **⚠️ 双版本**: `compaction.ended` 同一 type 字符串有两个 schema 版本。当前发布使用 v2，v1 decoder 保留用于回放存储的 beta 事件，客户端解码时需尝试两种 schema。

### 21.8 Session 状态与生命周期事件（5 个，❌ 不同步）

| type | properties | 触发 |
|------|-----------|------|
| `session.status` | `{ sessionID, status: SessionStatus.Info }` | 状态从 idle/busy/retry 转换时 |
| `session.idle` | `{ sessionID }` | 会话变 idle 时**与 `session.status` 同时发布**（**DEPRECATED**，新代码不应依赖） |
| `session.diff` | `{ sessionID, diff: FileDiff[] }` | 文件 diff 重新计算后 |
| `session.error` | `{ sessionID?, error? }` | 会话执行错误（如 `prompt_async` 后台失败）。`sessionID` 可选（全局错误）；`error` 是 7 种错误类型联合（`api_error`/`aborted`/`auth`/`output_length`/`context_overflow`/`structured_output` 等） |
| `session.compacted` | `{ sessionID }` | 会话压缩完成（粗粒度通知，与 `session.next.compaction.ended` 不同） |

**`SessionStatus.Info`**（3 种判别联合）:

| type | 额外字段 | 说明 |
|------|---------|------|
| `idle` | — | 空闲 |
| `busy` | — | 处理中 |
| `retry` | `attempt`, `message`, `action?: { reason, provider, title, message, label, link? }`, `next` | 重试中（`next` 是下次重试 Unix 时间戳，可显示倒计时） |

### 21.9 Todo 事件（1 个）

| type | properties | 触发 |
|------|-----------|------|
| `todo.updated` | `{ sessionID, todos: Todo.Info[] }` | Todo 列表变更 |

### 21.10 Permission 事件（4 个，v1 + v2 双轨）

| type | properties | 同步 |
|------|-----------|------|
| `permission.asked`（v1） | `{ id, sessionID, permission, patterns, metadata, always, tool? }` | ❌ |
| `permission.replied`（v1） | `{ sessionID, requestID, reply: "once" \| "always" \| "reject" }` | ❌ |
| `permission.v2.asked`（v2） | `Request.fields`（v2 schema，更严格类型） | ❌ |
| `permission.v2.replied`（v2） | `{ sessionID, requestID, reply }` | ❌ |

> **v1 和 v2 并行发布**: 客户端建议选择 v2，忽略 v1。

### 21.11 Question 事件（6 个，v1 + v2 双轨）

| type | properties |
|------|-----------|
| `question.asked`（v1） | `Request.fields` |
| `question.replied`（v1） | `Replied.fields` |
| `question.rejected`（v1） | `Rejected.fields` |
| `question.v2.asked` | v2 schema |
| `question.v2.replied` | `{ sessionID, requestID, answers: Answer[] }` |
| `question.v2.rejected` | `{ sessionID, requestID }` |

### 21.12 PTY 事件（4 个）

| type | properties | 触发 |
|------|-----------|------|
| `pty.created` | `{ info: PtyInfo }` | `POST /pty` 创建后 |
| `pty.updated` | `{ info: PtyInfo }` | `PUT /pty/:id` 更新后 |
| `pty.exited` | `{ id, exitCode }` | PTY 进程退出 |
| `pty.deleted` | `{ id }` | `DELETE /pty/:id` 后 |

### 21.13 MCP 事件（2 个）

| type | properties | 触发 |
|------|-----------|------|
| `mcp.tools.changed` | `{ server }` | MCP 工具列表变更（收到后应重新获取 `GET /experimental/tool`） |
| `mcp.browser.open.failed` | `{ mcpName, url }` | MCP OAuth 流程中浏览器打开失败（向用户显示 URL 手动打开） |

### 21.14 Project 与 VCS 事件（3 个）

| type | properties | 触发 |
|------|-----------|------|
| `project.updated` | `Project.Info` 所有字段 | 项目元数据变更 |
| `project.directories.updated` | `ProjectCopy.Updated` schema | 项目目录列表变更 |
| `vcs.branch.updated` | `{ branch?, default_branch? }` | Git 分支切换 |

### 21.15 LSP / IDE / Command 事件（3 个）

| type | properties | 触发 |
|------|-----------|------|
| `lsp.updated` | `{}`（仅通知信号） | LSP 索引变更（收到后应重新获取 `GET /lsp`） |
| `ide.installed` | `IDE.Installed` schema | IDE 插件安装 |
| `command.executed` | `{ name, sessionID, arguments, messageID }` | 命令执行完成 |

### 21.16 Account / Catalog / Plugin 事件（5 个）

| type | properties | 触发 |
|------|-----------|------|
| `account.added` | `{ accountID, ... }` | 新 provider 账户添加 |
| `account.removed` | `{ accountID, ... }` | 账户移除 |
| `account.switched` | `{ accountID, ... }` | 活跃账户切换（如 Console 组织切换） |
| `catalog.model.updated` | `Catalog.ModelUpdated` schema | models.dev 数据刷新后 |
| `plugin.added` | `Plugin.Added` schema | 新插件加载后 |

### 21.17 Filesystem 事件（2 个）

| type | properties | 触发 |
|------|-----------|------|
| `file.edited` | `Filesystem.Edited` schema | 文件被编辑（通过 opencode 工具） |
| `file.watcher.updated` | `Filesystem.Watcher.Updated` schema | 文件系统 watcher 检测到外部变更 |

### 21.18 Installation 事件（2 个）

| type | properties | 触发 |
|------|-----------|------|
| `installation.updated` | `{ version }` | OpenCode 升级完成 |
| `installation.update-available` | `{ version, ... }` | 检测到新版本可用 |

### 21.19 Workspace / Worktree 事件（5 个）

| type | properties | 触发 |
|------|-----------|------|
| `workspace.ready` | `{ workspaceID, ... }` | 工作区就绪 |
| `workspace.failed` | `{ workspaceID, error, ... }` | 工作区失败 |
| `workspace.status` | `{ workspaceID, connected, error? }` | 工作区状态变更 |
| `worktree.ready` | `{ name, directory, ... }` | 工作树就绪 |
| `worktree.failed` | `{ name, error, ... }` | 工作树失败 |

### 21.20 TUI 事件（4 个）

> 主要由 TUI 进程消费，外部客户端通常不需要处理

| type | properties |
|------|-----------|
| `tui.prompt.append` | `{ text }` |
| `tui.command.execute` | `{ command: string \| known-literal }` |
| `tui.toast.show` | `{ title?, message, variant, duration }` |
| `tui.session.select` | `{ sessionID }` |

### 21.21 v1/v2 迁移状态

| 体系 | 事件前缀 | 状态 | 客户端建议 |
|------|---------|------|-----------|
| Session v1 | `session.created/updated/deleted` | ✅ 活跃 | 使用 |
| Message v1 | `message.updated/removed/part.*` | ✅ 活跃 | 使用 |
| **Session.next v2** | `session.next.*` | ✅ **推荐** | **优先使用** |
| Permission v1 | `permission.asked/replied` | ⚠️ 维护 | 兼容 |
| Permission v2 | `permission.v2.*` | ✅ 推荐 | 优先使用 |
| Question v1 | `question.asked/replied/rejected` | ⚠️ 维护 | 兼容 |
| Question v2 | `question.v2.*` | ✅ 推荐 | 优先使用 |

**v1 和 v2 并行发布**: 同时收到两套事件（重复信息），建议选择一套（推荐 v2），忽略另一套。v1 不会移除（向后兼容）。

### 21.22 Sync 同步机制

部分事件标记为 `sync: { aggregate: "sessionID", version: N }`，支持跨工作区事件溯源同步。

**支持同步的事件分类**:

| 分类 | 是否同步 | aggregate | version |
|------|---------|-----------|---------|
| Session v1 (created/updated/deleted) | ✅ | `sessionID` | 1 |
| Message v1 (updated/removed/part.*) | ✅ | `sessionID` | 1 |
| **Session.next 大部分** | ✅ | `sessionID` | 1 或 2 |
| Session.next `.delta` 系列 | ❌ | — | — |
| Session.status/idle/diff/error | ❌ | — | — |
| Permission/Question | ❌ | — | — |
| PTY/MCP/Project/VCS | ❌ | — | — |

**sync 包装事件**（在 `/global/event` 上额外发布）:
```typescript
{
  type: "sync",
  syncEvent: {
    id: EventV2.ID,
    type: "<原始 type>@v<N>",          // 版本化 type，如 session.updated@v1
    seq: number,                        // 单调递增序列号
    aggregateID: string,                // 聚合根 ID
    data: <原始 properties>
  }
}
```

> 客户端解码需根据 `@vN` 后缀选择正确的 schema 版本。

---

## 22. 数据模型

### Session

```json
{
  "id": "ses_...",
  "slug": "string",
  "projectID": "prj_...",
  "workspaceID": "string?",
  "directory": "/abs/path",
  "path": "string?",
  "parentID": "ses_...?",
  "summary": {
    "additions": 0, "deletions": 0, "files": 0,
    "diffs": [{ "file", "before", "after", "additions", "deletions", "status?" }]
  }?,
  "share": { "url": "string" }?,
  "title": "string",
  "agent": "string?",
  "model": { "id": "string", "providerID": "string", "variant": "string?" }?,
  "version": "string",
  "metadata": { "key": "value" }?,
  "time": {
    "created": 1234567890,
    "updated": 1234567890,
    "compacting": 1234567890?,
    "archived": 1234567890?
  },
  "permission": [{ "permission", "pattern", "action": "allow|deny|ask" }]*?,
  "revert": { "messageID", "partID?", "snapshot?", "diff?" }?,
  // --- V2 新增 ---
  "cost": 0.0?,
  "tokens": {                       // ⚠️ Session 级无 total 字段！
    "input": 0,
    "output": 0,
    "reasoning": 0,
    "cache": { "read": 0, "write": 0 }
  }?
}
```

> **⚠️ Session.tokens 无 `total` 字段**（`total` 只在 Message/StepFinish 级存在）。

### Message（多态，由 `role` 字段区分）

#### User Message

```json
{
  "id": "msg_...",
  "sessionID": "ses_...",
  "role": "user",
  "time": { "created": 1234567890 },
  "agent": "string",
  "model": { "providerID": "string", "modelID": "string", "variant": "string?" }?,
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
  "id": "msg_...",
  "sessionID": "ses_...",
  "role": "assistant",
  "time": { "created": 1234567890, "completed": 1234567890? },
  "parentID": "msg_...",
  "modelID": "string?",
  "providerID": "string?",
  "agent": "string?",
  "mode": "string?",
  "path": { "cwd": "string", "root": "string" }?,
  "cost": 0.0?,                      // ⚠️ 累加（+=）：每个 step 的 cost 累加到 message.cost
  "tokens": {                        // ⚠️ 覆盖（=）：每个 step 覆盖为最新值（非累加）
    "input": 0,
    "output": 0,
    "total": 0?,                     // 可选，= input + output + reasoning + cache.read + cache.write
    "reasoning": 0,
    "cache": { "read": 0, "write": 0 }
  }?,
  "finish": "string?",
  "error": { "name": "string", "data": { "message": "..." }? }?,
  "structured": "json?",
  "variant": "string?",
  "summary": true?
}
```

> **⚠️ 关键语义**: `Message.tokens` 用 `=` 覆盖（`processor.ts:717`），代表**最后一个 step** 的消耗；`Message.cost` 用 `+=` 累加（`processor.ts:716`），代表所有 step 的累计费用。详见 [§23](#23-token--context-usage)。

### MessageWithParts

```json
{ "info": "<Message>", "parts": ["<Part>"] }
```

### Part（多态，由 `type` 字段区分）

| type | 说明 | 特有字段 |
|------|------|---------|
| `text` | 文本内容 | `text`, `synthetic?`, `ignored?`, `time?: {start, end?}`, `metadata?` |
| `reasoning` | 推理内容 | `text`, `time?: {start, end?}`, `metadata?` |
| `tool` | 工具调用 | `callID`, `tool`, `state: ToolState`, `metadata?` |
| `step-start` | 步骤开始 | `snapshot?` |
| `step-finish` | 步骤结束 | `reason`, `cost`, `tokens: {input, output, total?, reasoning, cache}`, `snapshot?` |
| `file` | 文件附件 | `mime`, `filename?`, `url?`, `source?` |
| `snapshot` | 代码快照 | `snapshot` |
| `patch` | 代码补丁 | `hash`, `files: string[]` |
| `subtask` | 子任务 | `prompt`, `description?`, `agent?`, `model?: {providerID, modelID}`, `command?` |
| `compaction` | 压缩标记 | `auto: bool`, `overflow?`, `tail_start_id?` |
| `retry` | 重试 | `attempt`, `error?`, `time?: {created}` |
| `abort` | 中止 | `reason` |
| `agent` | Agent 切换 | `name`, `source?` |

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
  "id": "prj_...",
  "name": "string",
  "icon": "string?",
  "directory": "/abs/path",
  "worktree": "/worktree/path",
  "vcs": { "branch": "string?", "default_branch": "string?" }?,
  "commands": { "key": "value" }?
}
```

### FileDiff

```json
{
  "file": "string?",
  "patch": "string?",
  "additions": 0,
  "deletions": 0,
  "status": "added | deleted | modified?"
}
```

### PromptRequest

```json
{
  "parts": [
    { "type": "text", "text": "string", "id?": "...", "synthetic?": false },
    { "type": "file", "mime": "string", "url": "string", "filename?": "...", "source?": "..." },
    { "type": "agent", "name": "string", "source?": "..." },
    { "type": "subtask", "prompt": "...", "description": "...", "agent": "...", "model?": {...}, "command?": "..." }
  ],
  "messageID": "msg_...?",
  "model": { "providerID": "string", "modelID": "string" }?,
  "agent": "string?",
  "variant": "string?",
  "format": { "type": "string", "schema": "string?" }?,
  "system": "string?",
  "noReply": true?,
  "tools": { "string": bool }?        // @deprecated（已合并到 permissions）
}
```

### ShellRequest

```json
{
  "agent": "string",                  // 必填
  "model": { "providerID": "string", "modelID": "string" }?,
  "command": "string",
  "messageID": "msg_...?"
}
```

### ConfigInfo

完整配置对象（`packages/core/src/v1/config/config.ts`），所有字段**可选**。关键字段:

| 字段 | 类型 | 说明 |
|------|------|------|
| `$schema` | string? | JSON schema 引用 |
| `shell` | string? | 默认 shell |
| `logLevel` | `"DEBUG"\|"INFO"\|"WARN"\|"ERROR"?` | 日志级别 |
| `server` | object? | 服务器配置 |
| `model` | string? | 默认模型（`provider/model` 格式） |
| `small_model` | string? | 小模型（标题生成等） |
| `default_agent` | string? | 默认 agent（缺省 `build`） |
| `username` | string? | 显示用户名 |
| `agent` | object? | agent 配置（`build`/`plan`/`general`/`explore`/`title`/`summary`/`compaction` + 自定义） |
| `provider` | `Record<string, ConfigProviderV1.Info>?` | provider 配置覆盖 |
| `mcp` | `Record<string, ConfigMCPV1.Info \| { enabled: boolean }>?` | MCP 服务器配置 |
| `disabled_providers` | string[]? | 禁用的 provider |
| `enabled_providers` | string[]? | 仅启用的 provider（白名单） |
| `lsp` | object? | LSP 配置 |
| `formatter` | object? | 格式化器配置 |
| `permission` | object? | 权限规则 |
| `instructions` | string[]? | 额外指令文件 |
| `compaction` | `{ auto?, prune?, tail_turns?, preserve_recent_tokens?, reserved? }?` | 压缩配置 |
| `experimental` | object? | 实验性选项（`batch_tool`、`openTelemetry`、`mcp_timeout` 等） |

> `PATCH /config` 和 `PATCH /global/config` 都是**全量替换**语义。

### ServerPaths

```json
{
  "home": "string",
  "state": "string",         // DB/日志目录
  "config": "string",        // 配置目录
  "worktree": "string",
  "directory": "string"      // 当前工作目录
}
```

### ProviderInfo

```json
{
  "id": "string",
  "name": "string",
  "source": "env | config | custom | api",     // Provider 来源
  "env": ["ANTHROPIC_API_KEY"],
  "key": "string?",                            // API key（已脱敏占位）
  "options": {},
  "models": {
    "modelId": {
      "id": "string",
      "name": "string",
      "toolCallType": "string",
      "attachment": false,
      "reasoning": false,
      "cost": { "input": 0.0, "output": 0.0, "cache": { "read": 0.0, "write": 0.0 }? }?,
      "limit": { "context": 0, "input": 0?, "output": 0 }?,
      "status": "stable | beta | deprecated",
      "release_date": "string"
    }
  }
}
```

### ProviderAuthMethod

```json
{
  "type": "oauth | api",
  "label": "string",
  "prompts": [
    { "type": "text", "key": "...", "message": "...", "placeholder?": "...", "when?": {...} },
    { "type": "select", "key": "...", "message": "...", "options": [{...}], "when?": {...} }
  ]?
}
```

`when` 条件显示: `{ key, op: "eq"|"neq", value }`（根据 provider 已有状态决定是否显示）

### ProviderOauthAuthorization

```json
{
  "url": "string",
  "method": "auto | code",
  "instructions": "string"
}
```

### MCPStatus（5 种状态判别联合）

| status | 额外字段 | 说明 |
|--------|---------|------|
| `connected` | — | 已连接成功 |
| `disabled` | — | 配置中禁用 |
| `failed` | `error: string` | 连接失败 |
| `needs_auth` | — | 需要完成 OAuth 认证 |
| `needs_client_registration` | `error: string` | 需要动态客户端注册（RFC 7591） |

### ConfigMCPInfo（MCP 配置，local/remote 联合）

**Local MCP**（`type: "local"`）:
```json
{
  "type": "local",
  "command": ["cmd", "arg1"],
  "environment": { "key": "value" }?,
  "enabled": true?,
  "timeout": 5000?              // PositiveInt，ms，默认 5000
}
```

**Remote MCP**（`type: "remote"`）:
```json
{
  "type": "remote",
  "url": "https://...",
  "enabled": true?,
  "headers": { "key": "value" }?,
  "oauth": { "clientId?": "...", "clientSecret?": "...", "scope?": "...", "callbackPort?": 19876, "redirectUri?": "..." } | false?,
  "timeout": 5000?
}
```

> `oauth: false` 禁用自动 OAuth 检测。`clientId` 缺省触发动态注册 RFC 7591。

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
  "id": "pty_01HZ...",
  "title": "Terminal XXXX",
  "command": "/bin/bash",
  "args": ["-l"],
  "cwd": "/abs/path",
  "status": "running | exited",
  "pid": 12345                    // Windows ConPTY 异步分配，spawn 时可能为 0
}
```

### PtyCreateRequest

```json
{
  "command": "string?",
  "args": ["string"]?,
  "cwd": "string?",
  "title": "string?",
  "env": { "key": "value" }?
}
```

### PtyUpdateRequest

```json
{
  "title": "string?",
  "size": { "rows": 24, "cols": 80 }?
}
```

### ShellInfo

```json
{
  "path": "/bin/bash",
  "name": "bash",
  "acceptable": true
}
```

### FileNode

```json
{
  "name": "basename",
  "path": "relative/path",
  "absolute": "/abs/path",
  "type": "file | directory",
  "ignored": false
}
```

### FileContent

```json
{
  "type": "text | binary",
  "content": "string",            // 文本内容 或 base64 编码
  "diff": "string?",              // git diff（可选）
  "patch": {}?,                   // 结构化 patch
  "encoding": "base64"?,          // 仅 binary
  "mimeType": "string?"           // 仅 binary
}
```

### SearchMatch

```json
{
  "path": { "text": "string" },
  "lines": { "text": "string" },
  "line_number": 0,
  "absolute_offset": 0,
  "submatches": [{ "match": { "text": "string" }, "start": 0, "end": 0 }]
}
```

### SymbolInfo

```json
{
  "name": "string",
  "kind": 0,                      // LSP SymbolKind 枚举值
  "location": { "uri": "string", "range": { "start": {}, "end": {} } },
  "containerName": "string?"
}
```

### FileStatusInfo

```json
{ "file": "string", "additions": 0, "deletions": 0, "status": "added | deleted | modified" }
```

### PermissionRequest

```json
{
  "id": "per_...",
  "sessionID": "ses_...",
  "permission": "string",
  "patterns": ["string"],
  "metadata": {}?,
  "always": true?,
  "tool": { "messageID": "msg_...", "callID": "call_..." }?
}
```

### QuestionRequest

```json
{
  "id": "que_...",
  "sessionID": "ses_...",
  "questions": [
    {
      "question": "string",
      "header": "string",
      "options": [{ "label": "string", "description": "string" }],
      "multiple": false?,
      "custom": true?
    }
  ],
  "tool": { "messageID": "msg_...", "callID": "call_..." }?
}
```

### TodoItem

```json
{
  "content": "string",
  "status": "pending | in_progress | completed | cancelled",
  "priority": "high | medium | low"
}
```

### RestSessionStatusInfo

```json
{
  "type": "idle | busy | retry",
  "attempt": 0?,
  "message": "string?",
  "action": { "reason": "...", "provider": "...", "title": "...", "message": "...", "label": "...", "link": "..." }?,
  "next": 0?        // retry 时：下次重试时间戳 (unix ms)
}
```

### WorkspaceInfo

```json
{
  "id": "string",
  "projectID": "prj_...",
  "name": "string",
  "directory": "/abs/path",
  "adapter": "local | ssh | docker"?,
  "extra": {}?
}
```

### WorkspaceConnectionStatus

```json
{ "workspaceID": "string", "connected": true, "error": "string?" }
```

### WorkspaceAdapterEntry

```json
{ "id": "local | ssh | docker", "name": "string", "available": true }
```

### ProjectCopyInfo

```json
{
  "projectID": "prj_...",
  "strategy": "git-worktree",
  "directory": "/abs/path",
  "name": "string",
  "time": { "created": 1234567890 }
}
```

### WorktreeInfo

```json
{ "name": "string", "branch": "string?", "directory": "/abs/path" }
```

---

## 23. Token / Context Usage

### 概述

OpenCode 的 token 体系分为**两层**:

| 层级 | 来源 | 说明 |
|------|------|------|
| **消息级** | `Message.Assistant.tokens` | **最后一个 step** 的 token 消耗（覆盖语义，非累加） |
| **Session 级** | `Session.tokens` | 整个 session 的累计 token（数据库 SQL 累加） |

**`Tokens` 结构**（两层共用，**Session 级无 `total` 字段**）:
```json
{
  "input": 0,                    // non-cached input tokens（不含 cache）
  "output": 0,                   // output tokens（含 reasoning）
  "reasoning": 0,                // output 中用于推理的部分
  "cache": { "read": 0, "write": 0 }   // cached input tokens（独立于 input）
}
```

### 消息级 Token 语义

**关键语义（源码验证）**:
- `input` **不包含** `cacheRead` / `cacheWrite`，各字段是独立的非重叠值
- `output` **包含** `reasoning`
- `total`（Message 级可选）= `input + output + reasoning + cache.read + cache.write`

**⚠️ 覆盖 vs 累加（关键区别）**:
- `Message.tokens` 用 `=` **覆盖**（源码 `processor.ts:717`: `ctx.assistantMessage.tokens = usage.tokens`）—— 每个 step 结束时**覆盖为最新值**，代表最后一个 step 的消耗
- `Message.cost` 用 `+=` **累加**（源码 `processor.ts:716`: `ctx.assistantMessage.cost += usage.cost`）—— 每个 step 累加，代表所有 step 的累计费用

> **客户端实现陷阱**: 想获取消息的**总 token** 不能直接读 `Message.tokens`（它只是最后一个 step），应通过累加所有 `step-finish` Part 的 tokens 或监听 `session.next.step.ended` 累加。

**实际 API 返回示例**（两轮对话）:
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

**更新机制（源码 `projector.ts:96-107`）**:

Session.tokens 通过 SQL UPDATE 累加（不是事件累加器）:
```sql
UPDATE session_table SET
  cost = cost + ?,
  tokens_input = tokens_input + ?,
  tokens_output = tokens_output + ?,
  tokens_reasoning = tokens_reasoning + ?,
  tokens_cache_read = tokens_cache_read + ?,
  tokens_cache_write = tokens_cache_write + ?,
  time_updated = ?
WHERE id = ?
```

- 每个 `step-finish` Part 到达时触发累加（sign=1）
- 消息/part 被删除或 revert 时执行减法（sign=-1）

**⚠️ 静默更新，不广播 SSE**:
- `applyUsage` 只更新数据库，**不发布 `session.updated` SSE 事件**
- `session.updated` 仅在**元数据变更**（标题、权限、归档）时触发
- 因此: 客户端通过 REST `GET /session/:id` 拿到的 `Session.tokens` 是最新的（DB 已更新），但通过 SSE `session.updated` 拿到的可能是**过期值**

**客户端实时获取 token 的正确方式**:
1. **监听 `session.next.step.ended`**: 携带单步 `tokens` 和 `cost`，客户端累加
2. **或监听 `message.part.updated`**: 含 `step-finish` Part 的 `tokens`
3. **不要依赖 `session.updated`**: 它的 `Session.tokens` 不反映最新累加

**实际 API 返回示例**:
```json
// 上述两轮对话后，session.tokens:
{ "input": 37584, "output": 7, "reasoning": 12, "cache": { "read": 37440, "write": 0 } }
// 验证: input = 37490 + 94 = 37584 ✓
//        cacheRead = 0 + 37440 = 37440 ✓
// 注意: Session 级无 total 字段
```

**Revert 后的 tokens 变化**:
```json
// revert 到第 1 条消息后，发送新消息:
// session.tokens:
{ "input": 52, "output": 10, "reasoning": 26, "cache": { "read": 37440, "write": 0 } }
// 注意: revert 后 session.tokens 被减去了被 revert 消息的 tokens（sign=-1）
// session.revert 被清除（新消息发送后变为 undefined）
```

### Context Window 使用率计算

Context window 限制的是 **input tokens**（LLM 接收的 prompt 大小）。output 和 reasoning 是 LLM 生成的，不占 input context window。

**⚠️ 正确的计算方式（基于源码 `acp/usage.ts:207`）**:
```
usedContext = input + cacheRead     // 不含 cacheWrite
usage% = usedContext / model.limit.context * 100
```

> **源码证据**（`acp/usage.ts:207`）: `used: message.tokens.input + message.tokens.cache.read`
>
> **为什么不含 cacheWrite**: cacheWrite 是本次写入缓存的 input tokens，下次调用会从 cacheRead 读出。从 context window 占用角度，cacheRead 已经代表了缓存命中部分，cacheWrite 是其中的写入子集（会计入下次的 cacheRead）。ACP 协议采用 `input + cacheRead` 作为 used 口径。

**三种参考口径对比**:

| 口径 | 计算 | 用途 |
|------|------|------|
| **ACP used（推荐）** | `input + cacheRead` | LLM 实际"看到"的 prompt 大小 |
| **WebUI 总量估算** | `input + output + reasoning + cache.read + cache.write` | 显示"总 token 消耗"（粗略，含生成部分） |
| **严格 input context** | `input + cacheRead + cacheWrite` | LLM 接收的原始 prompt 字节数（含本次写入 cache 的部分） |

> **注意**: WebUI 把 output/reasoning 也算进 context usage 是不精确的（output 是生成的，不占 input window）。

### Context Window Limit 获取

```
GET /config/providers → providers[].models[].limit.context
```

**三级 fallback（oc-remote 实现）**:
1. `tokenStats.contextWindow`（之前计算的缓存值）
2. `session.model` → 从 provider 列表中查找对应 model 的 `limit.context`
3. 当前选中 model 的 `contextWindow`

### 推荐的客户端实现

1. **Session 级 Token 聚合（REST）**: 优先使用 `GET /session/:id` 返回的 `Session.tokens`（DB 累加值，准确）
2. **Session 级 Token 实时（SSE）**: **不要**依赖 `session.updated`，应累加 `session.next.step.ended` 事件的 `tokens`
3. **消息级 Token**: `Message.tokens` 是最后一个 step（覆盖），需要总 token 应累加所有 `step-finish` Part
4. **Cost**: `Message.cost` 和 `Session.cost` 都是累加（+=）
5. **Context Usage 进度条**: `(input + cacheRead) / model.limit.context * 100`（ACP 口径）
6. **Total Tokens 显示**: `input + output + reasoning + cacheRead + cacheWrite`（全部 token 消耗）

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

> 共 **140+ HTTP/WebSocket 端点**（含实验性）

### 1. Global（6 个）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/global/health` | 健康检查（无认证） |
| GET | `/global/config` | 全局配置（无认证） |
| PATCH | `/global/config` | 更新全局配置（无认证，销毁所有实例） |
| GET | `/global/event` | 全局 SSE 事件流（无认证） |
| POST | `/global/dispose` | 销毁所有实例（无认证） |
| POST | `/global/upgrade` | 升级 OpenCode（无认证） |

### 2. Config（3 个）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/config` | 实例配置 |
| PATCH | `/config` | 更新实例配置（销毁当前实例） |
| GET | `/config/providers` | 配置中的 Provider |

### 3. Provider（4 个）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/provider` | 所有 Provider（含连接状态） |
| GET | `/provider/auth` | Provider 认证方式 |
| POST | `/provider/{id}/oauth/authorize` | OAuth 授权（返回可能 null） |
| POST | `/provider/{id}/oauth/callback` | OAuth 回调 |

### 4. MCP（8 个）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/mcp` | MCP 服务器状态 |
| POST | `/mcp` | 添加 MCP 服务器 |
| POST | `/mcp/{name}/auth` | MCP OAuth 启动 |
| POST | `/mcp/{name}/auth/callback` | MCP OAuth 回调 |
| POST | `/mcp/{name}/auth/authenticate` | MCP OAuth 一键式 |
| DELETE | `/mcp/{name}/auth` | 移除 MCP 凭据 |
| POST | `/mcp/{name}/connect` | 连接 MCP |
| POST | `/mcp/{name}/disconnect` | 断开 MCP |

### 5. Project（5 个）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/project` | 列出项目 |
| GET | `/project/current` | 当前项目 |
| POST | `/project/git/init` | 初始化 Git（触发 reload） |
| PATCH | `/project/{id}` | 更新项目 |
| GET | `/project/{id}/directories` | 项目目录列表 |

### 6. ProjectCopy（3 个，experimental）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/experimental/project/{id}/copy` | 创建副本（AI 名称生成） |
| DELETE | `/experimental/project/{id}/copy` | 移除副本（**带 body**） |
| POST | `/experimental/project/{id}/copy/refresh` | 刷新副本列表 |

### 7. Workspace（7 个，experimental）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/experimental/workspace/adapter` | 工作区适配器 |
| GET | `/experimental/workspace` | 列出工作区 |
| POST | `/experimental/workspace` | 创建工作区 |
| POST | `/experimental/workspace/sync-list` | 同步工作区列表 |
| GET | `/experimental/workspace/status` | 工作区连接状态 |
| DELETE | `/experimental/workspace/{id}` | 移除工作区 |
| POST | `/experimental/workspace/warp` | 迁移会话到工作区（id:null 脱离） |

### 8. Reference（1 个）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/reference` | 配置的引用（local/git/invalid 联合） |

### 9. Session（20 个）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/session` | 列出会话 |
| GET | `/session/status` | 会话状态批量查询 |
| POST | `/session` | 创建会话（空 body 也可） |
| POST | `/session/import` | 导入分享会话 |
| GET | `/session/{id}` | 获取会话 |
| PATCH | `/session/{id}` | 更新会话（permission 合并 / metadata 替换） |
| DELETE | `/session/{id}` | 删除会话 |
| POST | `/session/{id}/abort` | 中止（不检查存在性） |
| GET | `/session/{id}/diff` | 文件差异（不检查存在性） |
| POST | `/session/{id}/summarize` | 压缩总结 |
| POST | `/session/{id}/revert` | 回退消息（撤销文件变更） |
| POST | `/session/{id}/unrevert` | 撤销回退 |
| POST | `/session/{id}/fork` | 分叉（空 body 也可） |
| POST | `/session/{id}/share` | 分享（失败 500） |
| DELETE | `/session/{id}/share` | 取消分享 |
| GET | `/session/{id}/children` | 子会话列表 |
| GET | `/session/{id}/todo` | Todo 列表 |
| POST | `/session/{id}/command` | 执行命令（model 字符串格式） |
| POST | `/session/{id}/shell` | Shell 命令（检查忙碌） |
| POST | `/session/{id}/message` | **同步发送消息**（非流式，阻塞等完整响应） |
| POST | `/session/{id}/prompt_async` | 异步发送（fire-and-forget） |
| POST | `/session/{id}/init` | 初始化 AGENTS.md |

### 10. Message（6 个）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/session/{id}/message` | 消息列表（非标准分页 Header） |
| GET | `/session/{id}/message/{mid}` | 消息详情 |
| DELETE | `/session/{id}/message/{mid}` | 删除消息（检查忙碌，不撤销文件） |
| DELETE | `/session/{id}/message/{mid}/part/{pid}` | 删除 Part（不检查忙碌） |
| PATCH | `/session/{id}/message/{mid}/part/{pid}` | 更新 Part（三重 ID 校验） |

### 11. Permission（3 个）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/permission` | 待处理权限 |
| POST | `/permission/{id}/reply` | 回复权限 |
| POST | `/session/{id}/permissions/{pid}` | **已废弃** |

### 12. Question（3 个）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/question` | 待处理问题 |
| POST | `/question/{id}/reply` | 回复问题 |
| POST | `/question/{id}/reject` | 拒绝问题 |

### 13. PTY（8 个）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/pty/shells` | 可用 Shell（fish/nu 不可接受） |
| GET | `/pty` | PTY 会话列表 |
| POST | `/pty` | 创建 PTY |
| GET | `/pty/{id}` | PTY 详情 |
| PUT | `/pty/{id}` | 更新 PTY |
| DELETE | `/pty/{id}` | 删除 PTY |
| POST | `/pty/{id}/connect-token` | WebSocket 票据（x-opencode-ticket:1 + Origin） |
| WS | `/pty/{id}/connect` | WebSocket（ticket + cursor） |

### 14. TUI（13 个）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/tui/append-prompt` | 追加提示 |
| POST | `/tui/open-help` | 打开帮助 |
| POST | `/tui/open-sessions` | 打开会话列表 |
| POST | `/tui/open-themes` | 打开主题（⚠️ 源码 bug：同 open-sessions） |
| POST | `/tui/open-models` | 打开模型选择 |
| POST | `/tui/submit-prompt` | 提交输入 |
| POST | `/tui/clear-prompt` | 清空输入 |
| POST | `/tui/execute-command` | 执行命令（遗留别名） |
| POST | `/tui/show-toast` | 显示 Toast |
| POST | `/tui/publish` | **通用 TUI 事件发布**（4 种联合） |
| POST | `/tui/select-session` | 选择会话 |
| GET | `/tui/control/next` | 获取下一个请求（通道 B，阻塞） |
| POST | `/tui/control/response` | 提交响应（通道 B） |

### 15. 控制平面基础（3 个，无认证）

| 方法 | 路径 | 说明 |
|------|------|------|
| PUT | `/auth/{id}` | 设置认证凭据（OAuth/API/WellKnown 联合） |
| DELETE | `/auth/{id}` | 删除认证凭据 |
| POST | `/log` | 写入服务端日志 |

### 16. Sync（4 个）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/sync/start` | 启动工作区同步 |
| POST | `/sync/replay` | 回放事件（aggregateID 驼峰） |
| POST | `/sync/steal` | 窃取会话到当前工作区 |
| POST | `/sync/history` | 增量查询（aggregate_id 蛇形） |

### 17. Experimental（12 个）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/experimental/console` | Console 组织状态 |
| GET | `/experimental/console/orgs` | 可切换组织 |
| POST | `/experimental/console/switch` | 切换组织 |
| GET | `/experimental/tool` | 工具列表（含参数 schema） |
| GET | `/experimental/tool/ids` | 工具 ID |
| GET | `/experimental/worktree` | 工作树列表 |
| POST | `/experimental/worktree` | 创建工作树（允许空 body） |
| DELETE | `/experimental/worktree` | 删除工作树 |
| POST | `/experimental/worktree/reset` | 重置工作树 |
| GET | `/experimental/session` | 全局会话列表（x-next-cursor 分页） |
| POST | `/experimental/session/{id}/background` | 后台化子代理（需 feature flag） |
| GET | `/experimental/resource` | MCP 资源 |

### 18. Instance / VCS / 元信息（12 个）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/instance/dispose` | 销毁实例 |
| GET | `/path` | 路径信息 |
| GET | `/vcs` | VCS 分支信息 |
| GET | `/vcs/status` | 变更文件列表 |
| GET | `/vcs/diff` | diff（mode: git/branch） |
| GET | `/vcs/diff/raw` | 原始 patch 文本 |
| POST | `/vcs/apply` | 应用 patch |
| GET | `/agent` | Agent 列表 |
| GET | `/command` | 命令列表 |
| GET | `/skill` | Skill 列表 |
| GET | `/lsp` | LSP 状态 |
| GET | `/formatter` | 格式化器状态 |

### 19. 跨项目控制平面（1 个）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/experimental/control-plane/move-session` | 跨目录迁移会话（可选转移变更） |

### 20. File / Find（6 个）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/find` | 文本搜索（ripgrep，硬编码 limit 10） |
| GET | `/find/file` | 模糊文件搜索（fff + ripgrep 降级） |
| GET | `/find/symbol` | **桩实现**，恒返回 [] |
| GET | `/file` | 目录列表 |
| GET | `/file/content` | 文件内容（逃逸 500，不存在返回空） |
| GET | `/file/status` | **桩实现**，恒返回 [] |

---

## SSE 事件总览

> 共 **89 种 SSE 事件类型**

| 分类 | 数量 | type 前缀 |
|------|------|----------|
| 系统与服务 | 4 | `server.*` / `global.disposed` |
| Session v1 | 7 | `session.created/updated/deleted` / `message.*` |
| Message v1 流式 | 1 | `message.part.delta` |
| Session.next v2 | 31 | `session.next.*` |
| Session 状态 | 5 | `session.status/idle/diff/error/compacted` |
| Todo | 1 | `todo.updated` |
| Permission | 4 | `permission.asked/replied` / `permission.v2.*` |
| Question | 6 | `question.*` / `question.v2.*` |
| PTY | 4 | `pty.*` |
| MCP | 2 | `mcp.*` |
| Project/VCS | 3 | `project.*` / `vcs.branch.updated` |
| LSP/IDE/Command | 3 | `lsp.updated` / `ide.installed` / `command.executed` |
| Account/Catalog/Plugin | 5 | `account.*` / `catalog.*` / `plugin.added` |
| Filesystem | 2 | `file.edited` / `file.watcher.updated` |
| Installation | 2 | `installation.*` |
| Workspace/Worktree | 5 | `workspace.*` / `worktree.*` |
| TUI | 4 | `tui.*` |

**4 个瞬时事件**（不持久化，断线丢失）: `message.part.delta`、`session.next.text.delta`、`session.next.reasoning.delta`、`session.next.tool.input.delta`、`session.next.compaction.delta`（实际 5 个，其中 session.next.* 的 4 个 delta 是核心瞬时事件）

**关键事件订阅建议**:
- 实时 token/cost: `session.next.step.ended`（携带 tokens + cost）
- 实时文本流: `session.next.text.started/delta/ended`
- 实时工具调用: `session.next.tool.*` 系列
- 状态变更: `session.status` + `session.error`
- 权限/问题: 优先 v2（`permission.v2.*` / `question.v2.*`）
