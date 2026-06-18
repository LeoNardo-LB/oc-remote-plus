# OpenCode 会话与消息 REST API 深度分析

> 源码版本：2026-06-09  
> 分析范围：`packages/opencode/src/server/routes/instance/httpapi/` 下的 `session`、`permission`、`question` 三个路由组  
> 端点总数：**32 个**（Session 27 + Permission 2 + Question 3）

---

## 目录

- [通用 Schema 定义](#通用-schema-定义)
- [通用 Query 参数](#通用-query-参数)
- [错误码体系](#错误码体系)
- [Session 路由组（27 个端点）](#session-路由组)
- [Permission 路由组（2 个端点）](#permission-路由组)
- [Question 路由组（3 个端点）](#question-路由组)
- [关键发现](#关键发现)

---

## 通用 Schema 定义

### Session.Info（会话主对象）

定义位置：`packages/opencode/src/session/session.ts:212-232`  
这是所有会话接口的核心返回类型。注意区分 `Session.Info`（opencode 本地，带 `optionalOmitUndefined`）与 `SessionV1.SessionInfo`（core 包，schema 更严格）。

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `id` | `SessionID`（`ses_` 前缀字符串） | 是 | 会话唯一 ID |
| `slug` | `string` | 是 | URL 友好的短标识 |
| `projectID` | `ProjectV2.ID` | 是 | 所属项目 ID |
| `workspaceID` | `WorkspaceV2.ID \| undefined` | 否 | 所属工作区 ID |
| `directory` | `string` | 是 | 会话工作目录绝对路径 |
| `path` | `string \| undefined` | 否 | 相对 worktree 的路径 |
| `parentID` | `SessionID \| undefined` | 否 | 父会话 ID（fork 时有值） |
| `summary` | `Summary \| undefined` | 否 | 文件变更摘要（additions/deletions/files/diffs） |
| `cost` | `number \| undefined` | 否 | 累计花费（美元，Finite） |
| `tokens` | `Tokens \| undefined` | 否 | 累计 token 用量 |
| `share` | `{ url: string } \| undefined` | 否 | 分享链接信息 |
| `title` | `string` | 是 | 会话标题 |
| `agent` | `string \| undefined` | 否 | 默认 agent 名称 |
| `model` | `Model \| undefined` | 否 | 默认模型 `{ id, providerID, variant? }` |
| `version` | `string` | 是 | 创建时的 opencode 版本 |
| `metadata` | `Record<string, any> \| undefined` | 否 | 自定义元数据 |
| `time` | `Time` | 是 | `{ created, updated, compacting?, archived? }` |
| `permission` | `PermissionV1.Ruleset \| undefined` | 否 | 权限规则集 |
| `revert` | `Revert \| undefined` | 否 | 回滚点信息 `{ messageID, partID?, snapshot?, diff? }` |

**Tokens 结构**：`{ input, output, reasoning, cache: { read, write } }`，均为 `Finite`（有限数）。  
**Summary 结构**：`{ additions, deletions, files, diffs?: FileDiff[] }`。  
**Time.archived**：`ArchivedTimestamp = Schema.Finite`（注释说明 legacy HTTP 接受负值，故保持宽松）。

### SessionV1.WithParts（带 Part 的消息对象）

定义位置：`packages/core/src/v1/session.ts:491-498`

```typescript
{
  info: Info,       // User | Assistant（按 role 判别联合）
  parts: Part[],    // Part 联合类型数组
}
```

### SessionV1.Info（消息 info，User | Assistant 判别联合）

**User 消息**（`role: "user"`）：
- `id: MessageID`、`sessionID`、`time.created: Timestamp`
- `agent: string`、`model: { providerID, modelID, variant? }`
- `format?`（输出格式：text / json_schema）、`summary?`、`system?`、`tools?`

**Assistant 消息**（`role: "assistant"`）：
- `parentID: MessageID`（对应的 user 消息 ID）
- `modelID`、`providerID`、`mode: string`、`agent: string`
- `path: { cwd, root }`、`cost: Finite`
- `tokens`（同 Session 的 Tokens 结构）
- `time: { created, completed? }`、`error?`（7 种错误类型联合）
- `structured?`、`variant?`、`finish?`、`summary?`

### SessionV1.Part（Part 联合类型，12 种）

通过 `type` 字段判别：

| type | 说明 | 关键字段 |
|------|------|---------|
| `text` | 文本内容 | `text`, `synthetic?`, `ignored?`, `time?`, `metadata?` |
| `reasoning` | 推理过程 | `text`, `metadata?`, `time: { start, end? }` |
| `file` | 文件附件 | `mime`, `url`, `source?`, `filename?` |
| `tool` | 工具调用 | `callID`, `tool`, `state: ToolState`, `metadata?` |
| `step-start` | 步骤开始 | `snapshot?` |
| `step-finish` | 步骤结束 | `reason`, `cost`, `tokens`, `snapshot?` |
| `snapshot` | 快照 | `snapshot: string` |
| `patch` | 补丁 | `hash`, `files: string[]` |
| `agent` | 子 agent | `name`, `source?` |
| `subtask` | 子任务 | `prompt`, `description`, `agent`, `model?`, `command?` |
| `retry` | 重试 | `attempt`, `error: APIError`, `time.created` |
| `compaction` | 压缩 | `auto`, `overflow?`, `tail_start_id?` |

**ToolState**（4 种状态，按 `status` 判别）：
- `pending`：`{ input, raw }`
- `running`：`{ input, title?, metadata?, time.start }`
- `completed`：`{ input, output, title, metadata, time: { start, end, compacted? }, attachments? }`
- `error`：`{ input, error, metadata?, time: { start, end } }`

### Snapshot.FileDiff

定义位置：`packages/opencode/src/snapshot/index.ts:18-28`

| 字段 | 类型 | 说明 |
|------|------|------|
| `file` | `string?` | 文件路径（可选，legacy 数据可能缺失） |
| `patch` | `string?` | diff 补丁文本（可选） |
| `additions` | `Finite` | 新增行数 |
| `deletions` | `Finite` | 删除行数 |
| `status` | `"added" \| "deleted" \| "modified"?` | 变更类型 |

### SessionStatus.Info

定义位置：`packages/opencode/src/session/status.ts:8-31`，3 种状态判别联合：

| type | 字段 |
|------|------|
| `idle` | （无额外字段） |
| `busy` | （无额外字段） |
| `retry` | `attempt`, `message`, `action?`（含 reason/provider/title/message/label/link?）, `next` |

### Todo.Info

| 字段 | 类型 | 说明 |
|------|------|------|
| `content` | `string` | 任务描述 |
| `status` | `string` | `pending` / `in_progress` / `completed` / `cancelled` |
| `priority` | `string` | `high` / `medium` / `low` |

### Question 相关 Schema

**QuestionID**：`que_` 前缀字符串（Newtype）。  
**Question.Info**（单个问题）：`{ question, header, options: Option[], multiple?, custom? }`  
**Question.Option**：`{ label, description }`  
**Question.Request**：`{ id, sessionID, questions: Info[], tool? }`  
**Question.Answer**：`string[]`（选中的 label 数组）  
**Question.Reply**：`{ answers: Answer[] }`（按问题顺序，每个 answer 是 label 数组）

### Permission 相关 Schema

**PermissionV1.ID**：`per_` 前缀字符串。  
**PermissionV1.Request**：`{ id, sessionID, permission, patterns, metadata, always, tool? }`  
**PermissionV1.Reply**：`"once" | "always" | "reject"`  
**PermissionV1.Rule**：`{ permission, pattern, action: "allow"|"deny"|"ask" }`  
**PermissionV1.Ruleset**：`Rule[]`

---

## 通用 Query 参数

所有端点都经过三层中间件：`InstanceContextMiddleware` → `WorkspaceRoutingMiddleware` → `Authorization`。

### WorkspaceRoutingQueryFields（所有端点的公共 query 参数）

定义位置：`workspace-routing.ts:22-25`

| 参数 | 类型 | 说明 |
|------|------|------|
| `directory` | `string?` | 工作目录，默认 `process.cwd()` 或 header `x-opencode-directory` |
| `workspace` | `string?` | 工作区 ID，用于远程工作区路由代理 |

> **重要**：这两个参数虽然不在各端点的业务 schema 中声明，但中间件会读取它们。如果不将 `WorkspaceRoutingQueryFields` 展开到端点 schema 中，HttpApi 会拒绝携带这些参数的请求（返回 400）。

---

## 错误码体系

定义位置：`packages/opencode/src/server/routes/instance/httpapi/errors.ts`

| 错误类 | HTTP 状态码 | 触发场景 |
|--------|------------|---------|
| `InvalidRequestError` | 400 | 参数校验失败 |
| `UnauthorizedError` | 401 | 未认证 |
| `ForbiddenError` | 403 | 无权限 |
| `ApiNotFoundError` / `SessionNotFoundError` / `MessageNotFoundError` | 404 | 资源不存在 |
| `SessionBusyError` | 409 | 会话忙碌（shell/revert/unrevert/deleteMessage） |
| `ConflictError` | 409 | 资源冲突 |
| `UpstreamError` | 502 | 上游服务错误 |
| `ServiceUnavailableError` | 503 | 服务不可用 |
| `TimeoutError` | 504 | 超时 |
| `UnknownError` | 500 | 未知错误 |
| `PermissionNotFoundError` / `QuestionNotFoundError` | 404 | 权限/问题请求不存在 |
| `HttpApiError.BadRequest` | 400 | Effect HttpApi 内置坏请求 |
| `HttpApiError.InternalServerError` | 500 | Effect HttpApi 内置服务器错误 |

---

## Session 路由组

### 1. `GET /session` — 列出会话

**用途**：获取所有 OpenCode 会话列表，按最近更新排序。

**Query 参数**（`ListQuery`）：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `directory` | `string?` | 否 | 工作目录（公共参数） |
| `workspace` | `string?` | 否 | 工作区 ID（公共参数） |
| `scope` | `"project"?` | 否 | 限定为当前 project scope（传 `project` 时 directory 传 undefined） |
| `path` | `string?` | 否 | 路径过滤 |
| `roots` | `boolean?`（`"true"/"false"` 字符串） | 否 | 是否只返回根会话 |
| `start` | `number?`（从字符串解析） | 否 | 起始时间戳过滤 |
| `search` | `string?` | 否 | 搜索关键词 |
| `limit` | `number?`（从字符串解析） | 否 | 数量限制 |

**返回**：`Session.Info[]`

**数据来源**：`Session.Service.list()` → 数据库查询。  
**注意**：`roots` 参数使用自定义 `QueryBoolean`（接受 `"true"`/`"false"` 字符串），而非原生 boolean。

---

### 2. `GET /session/status` — 获取所有会话状态

**用途**：获取所有会话的当前状态（idle/busy/retry）。

**Query 参数**：仅 `WorkspaceRoutingQuery`（directory + workspace）。

**返回**：`Record<string, SessionStatus.Info>` —— 键为 sessionID，值为状态对象。

**数据来源**：`SessionStatus.Service.list()` → 内存中的 `InstanceState`（Map）。  
**原理**：状态不持久化，纯内存维护。idle 状态的会话不会出现在 map 中（handler 用 `Object.fromEntries` 转换，缺失的会话默认为 idle）。

---

### 3. `GET /session/:sessionID` — 获取单个会话

**Path 参数**：`sessionID: SessionID`  
**Query 参数**：`WorkspaceRoutingQuery`  
**返回**：`Session.Info`  
**错误**：400（参数错误）、404（`ApiNotFoundError`，会话不存在）

**数据来源**：`Session.Service.get(sessionID)` → 数据库。  
**原理**：handler 通过 `mapStorageNotFound` 将底层 `NotFoundError` 映射为 `ApiNotFoundError`（404）。

---

### 4. `GET /session/:sessionID/children` — 获取子会话

**用途**：获取从指定会话 fork 出来的所有子会话。

**Path 参数**：`sessionID`  
**返回**：`Session.Info[]`  
**错误**：400、404（父会话不存在）  
**注意**：先 `requireSession` 验证父会话存在，再调用 `session.children()`。

---

### 5. `GET /session/:sessionID/todo` — 获取 Todo 列表

**Path 参数**：`sessionID`  
**返回**：`Todo.Info[]`  
**数据来源**：`Todo.Service.get()` → 数据库 `TodoTable` 查询（drizzle ORM）。

---

### 6. `GET /session/:sessionID/diff` — 获取消息 diff

**用途**：获取指定消息产生的文件变更。

**Path 参数**：`sessionID`  
**Query 参数**（`DiffQuery`）：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `directory` / `workspace` | 公共参数 | 否 | |
| `messageID` | `MessageID?` | 否 | 指定消息 ID，不传则计算整个会话的 diff |

**返回**：`Snapshot.FileDiff[]`  
**数据来源**：`SessionSummary.Service.diff()` → git diff 计算。  
**注意**：此端点**不验证 sessionID 是否存在**（无 `requireSession` 调用），直接传给 summary 服务。

---

### 7. `GET /session/:sessionID/message` — 获取消息列表

**用途**：获取会话中的所有消息（含 user 和 assistant）。

**Path 参数**：`sessionID`  
**Query 参数**（`MessagesQuery`）：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `directory` / `workspace` | 公共参数 | 否 | |
| `limit` | `integer ≥ 0?` | 否 | 分页大小 |
| `before` | `string?` | 否 | 游标（base64url 编码的 `{id, time}`） |

**返回**：`SessionV1.WithParts[]`

**⚠️ 分页行为（关键隐藏行为）**：

1. 如果传了 `before` 但没传 `limit` → **400 BadRequest**
2. 如果传了 `before` → 先尝试 `MessageV2.cursor.decode(before)`，解码失败 → **400**
3. 如果 `limit` 未传或为 0 → 返回全部消息（无分页），普通 JSON 响应
4. 如果 `limit > 0` → 分页模式：
   - 查询 `limit + 1` 条，按 `time_created DESC, id DESC` 排序
   - 结果 `reverse()` 恢复为时间正序
   - 如果有更多数据（`rows.length > limit`），响应包含额外 header：
     - `Link: <完整URL>; rel="next"`（URL 带 `limit` 和 `before=cursor` 参数）
     - `X-Next-Cursor: <cursor>`（base64url 编码）
     - `Access-Control-Expose-Headers: Link, X-Next-Cursor`
   - 如果无更多数据 → 普通 JSON 响应（无额外 header）
5. sessionID 不存在 → `NotFoundError` 映射为 404

**游标格式**：`base64url(JSON.stringify({ id: MessageID, time: number }))`  
**排序逻辑**：`older(cursor)` = `time < cursor.time OR (time == cursor.time AND id < cursor.id)`

---

### 8. `GET /session/:sessionID/message/:messageID` — 获取单条消息

**Path 参数**：`sessionID`、`messageID`  
**返回**：`SessionV1.WithParts`  
**错误**：400、404（消息或会话不存在）  
**数据来源**：`MessageV2.get()` → 数据库，WHERE `id = messageID AND session_id = sessionID`。

---

### 9. `POST /session` — 创建会话

**用途**：创建新的 OpenCode 会话。

**请求体**（`Session.CreateInput`，可选 —— **空 body 也能创建**）：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `parentID` | `SessionID?` | 否 | 父会话 ID |
| `title` | `string?` | 否 | 标题 |
| `agent` | `string?` | 否 | 默认 agent |
| `model` | `Model?` | 否 | 默认模型 `{ id, providerID, variant? }` |
| `metadata` | `Record<string, any>?` | 否 | 元数据 |
| `permission` | `PermissionV1.Ruleset?` | 否 | 权限规则（数组） |
| `workspaceID` | `WorkspaceV2.ID?` | 否 | 工作区 ID |

**返回**：`Session.Info`

**⚠️ Raw handler 行为**：此端点使用 `handleRaw`（`createRaw`），手动解析 body：
- 空 body → `create({})`（使用全部默认值）
- 非 JSON body → 400
- JSON body → `Schema.decodeUnknownEffect` 解码，失败 → 400
- **特殊处理**：`permission` 字段会被 `[...decoded.permission]` 展开为数组（兼容单对象传入？）

**数据来源**：`SessionShare.Service.create()`（注意：是 share 服务，非直接 Session.create）。

---

### 10. `DELETE /session/:sessionID` — 删除会话

**返回**：`boolean`（true）  
**行为**：永久删除会话及所有关联数据（消息、历史）。  
**注意**：不检查会话是否忙碌。

---

### 11. `PATCH /session/:sessionID` — 更新会话

**请求体**（`UpdatePayload`，所有字段可选）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `title` | `string?` | 新标题 |
| `metadata` | `Record<string, any>?` | 新元数据（**替换**） |
| `permission` | `PermissionV1.Ruleset?` | 权限规则（**合并**，非替换） |
| `time.archived` | `ArchivedTimestamp?` | 归档时间戳 |

**返回**：更新后的 `Session.Info`

**⚠️ 关键行为**：
- `metadata`：直接调用 `setMetadata`，**完全替换**
- `permission`：调用 `Permission.merge(current.permission ?? [], newPermission)` —— **合并而非替换**！这是隐藏行为，客户端如果期望替换会踩坑
- 执行顺序：title → metadata → permission → archived，最后重新查询返回

---

### 12. `POST /session/:sessionID/fork` — Fork 会话

**用途**：在指定消息处分叉出新会话。

**请求体**（`ForkPayload`，可选 —— 空 body 也能 fork）：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `messageID` | `MessageID?` | 否 | fork 点消息 ID（不传则在最新消息处 fork） |

**返回**：新的 `Session.Info`  
**行为**：Raw handler（`forkRaw`），空 body 等同不传 messageID。  
**标题规则**：fork 出的会话标题为 `原标题 (fork #N)`，N 递增。

---

### 13. `POST /session/:sessionID/abort` — 中止会话

**用途**：中止正在进行的 AI 处理。  
**返回**：`boolean`（true）  
**实现**：`promptSvc.cancel(sessionID)`。  
**注意**：不检查会话是否存在，直接调用 cancel（cancel 可能是幂等的）。

---

### 14. `POST /session/:sessionID/init` — 初始化 AGENTS.md

**用途**：分析当前应用并创建 AGENTS.md 文件。

**请求体**（`InitPayload`，必填）：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `modelID` | `ModelV2.ID` | 是 | 模型 ID |
| `providerID` | `ProviderV2.ID` | 是 | 提供商 ID |
| `messageID` | `MessageID` | 是 | 消息 ID |

**返回**：`boolean`（true）  
**原理**：内部调用 `promptSvc.command()`，command 为 `Command.Default.INIT`（值为 `"init"`），使用 `initialize.txt` 模板。任何错误映射为 400。

---

### 15. `POST /session/:sessionID/share` — 分享会话

**返回**：`Session.Info`（含 `share.url`）  
**错误**：500（`InternalServerError`）、404  
**原理**：`SessionShare.Service.share()`，失败映射为 **500**（非 400），注释说明这是因为 share 失败可能是存储/网络问题。

---

### 16. `DELETE /session/:sessionID/share` — 取消分享

**返回**：`Session.Info`  
**错误**：500、404  
**原理**：`SessionShare.Service.unshare()`，同样映射为 500。

---

### 17. `POST /session/:sessionID/summarize` — 压缩总结

**用途**：使用 AI 压缩保留关键信息，生成简洁摘要。

**请求体**（`SummarizePayload`，必填）：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `providerID` | `ProviderV2.ID` | 是 | 提供商 |
| `modelID` | `ModelV2.ID` | 是 | 模型 |
| `auto` | `boolean?` | 否 | 是否自动压缩（默认 `false`） |

**返回**：`boolean`（true）  
**原理**：
1. `revertSvc.cleanup()` 清理回滚状态
2. 获取所有消息
3. **agent 选择逻辑**：找最后一条 `role === "user"` 的消息的 `info.agent`，找不到用 `defaultAgent`
4. `compactSvc.create()` 创建压缩任务
5. `promptSvc.loop()` 执行压缩循环
6. 阻塞直到完成

---

### 18. `POST /session/:sessionID/message` — 发送消息（同步）

> ⚠️ **路径冲突警告**：此端点（POST）与 [消息列表](#7-get-sessionsessionidmessage--获取消息列表)（GET）共享 `/session/:sessionID/message` 路径，仅 HTTP 方法不同。

**用途**：创建并发送新消息，**阻塞等待 AI 响应完成**后返回。

**请求体**（`PromptPayload` = `SessionPrompt.PromptInput` 去除 `sessionID`）：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `messageID` | `MessageID?` | 否 | 预生成的消息 ID |
| `model` | `{ providerID, modelID }?` | 否 | 覆盖模型 |
| `agent` | `string?` | 否 | 覆盖 agent |
| `noReply` | `boolean?` | 否 | 不生成回复 |
| `tools` | `Record<string, boolean>?` | 否 | **@deprecated** 工具开关（已合并到 permissions） |
| `format` | `Format?` | 否 | 输出格式（text / json_schema） |
| `system` | `string?` | 否 | 系统 prompt 覆盖 |
| `variant` | `string?` | 否 | 模型变体 |
| `parts` | `PartInput[]` | 是 | 消息内容（text/file/agent/subtask） |

**parts 元素类型**（4 种联合，按 `type` 判别）：
- `text`：`{ id?, type: "text", text, synthetic?, ignored?, time?, metadata? }`
- `file`：`{ id?, type: "file", mime, url, filename?, source? }`
- `agent`：`{ id?, type: "agent", name, source? }`
- `subtask`：`{ id?, type: "subtask", prompt, description, agent, model?, command? }`

**返回**：`SessionV1.WithParts`（`application/json`，包装在 Stream 中但**单次输出**）

**⚠️ 关键行为**：
- handler 先 `requireSession` 验证会话存在
- 调用 `promptSvc.prompt()` —— **阻塞直到整个 AI 响应循环完成**
- 返回值用 `HttpServerResponse.stream(Stream.make(JSON.stringify(message)))` 包装
- 虽然是 stream 响应，但实际只推送**一个 JSON chunk**（最终消息），**不是流式响应**
- 真正的实时流式响应通过 **SSE 事件**（`session.*` / `message.*` 事件）实现
- 任何 prompt 错误映射为 **400 BadRequest**

---

### 19. `POST /session/:sessionID/prompt_async` — 异步发送消息

**用途**：异步发送消息，立即返回，AI 处理在后台进行。

**请求体**：同 `PromptPayload`  
**返回**：`204 No Content`

**⚠️ 关键行为**：
- 调用 `promptSvc.prompt()` 并用 `Effect.forkIn(scope, { startImmediately: true })` 在后台 fork
- **错误处理**：fork 的 effect 用 `Effect.catchCause` 捕获所有错误：
  - 记录日志 `Effect.logError`
  - 发布 `Session.Event.Error` 事件（type: `"session.error"`），error 为 `NamedError.Unknown`
  - **HTTP 响应始终是 204**，即使后台任务最终失败
- 客户端必须通过 SSE 监听 `session.error` 事件来感知异步失败

---

### 20. `POST /session/:sessionID/command` — 发送命令

**用途**：发送预定义命令（如 init/review）给 AI。

**请求体**（`CommandPayload` = `SessionPrompt.CommandInput` 去除 `sessionID`）：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `messageID` | `MessageID?` | 否 | 消息 ID |
| `agent` | `string?` | 否 | agent |
| `model` | `string?` | 否 | 模型（格式：`providerID/modelID`） |
| `arguments` | `string` | 是 | 命令参数 |
| `command` | `string` | 是 | 命令名（如 `"init"`、`"review"`） |
| `variant` | `string?` | 否 | 变体 |
| `parts` | `FilePartInput[]?` | 否 | 文件附件（仅 file 类型） |

**返回**：`SessionV1.WithParts`  
**注意**：`model` 是字符串格式（`"provider/model"`），与 PromptInput 的 `{ providerID, modelID }` 对象格式不同！

---

### 21. `POST /session/:sessionID/shell` — 执行 Shell 命令

**请求体**（`ShellPayload` = `SessionPrompt.ShellInput` 去除 `sessionID`）：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `messageID` | `MessageID?` | 否 | 消息 ID |
| `agent` | `string` | **是** | agent（必填，注意与 prompt 的可选不同） |
| `model` | `{ providerID, modelID }?` | 否 | 模型 |
| `command` | `string` | 是 | shell 命令 |

**返回**：`SessionV1.WithParts`  
**错误**：400、404、**409（`SessionBusyError`）** —— shell 会检查会话是否忙碌。

---

### 22. `POST /session/:sessionID/revert` — 回滚消息

**用途**：回滚指定消息，**撤销文件变更**并恢复先前状态。

**请求体**（`RevertPayload` = `SessionRevert.RevertInput` 去除 `sessionID`）：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `messageID` | `MessageID` | 是 | 回滚到此消息 |
| `partID` | `PartID?` | 否 | 精确到 part 级别 |

**返回**：`Session.Info`  
**错误**：400、404、**409（忙碌）**

---

### 23. `POST /session/:sessionID/unrevert` — 恢复回滚

**用途**：恢复所有之前被回滚的消息。  
**无请求体**  
**返回**：`Session.Info`  
**错误**：400、404、**409（忙碌）**

---

### 24. `POST /session/:sessionID/permissions/:permissionID` — 响应权限请求（已废弃）

> ⚠️ **DEPRECATED**：OpenAPI 标注 `deprecated: true`。新接口为 [`POST /permission/:requestID/reply`](#29-post-permissionrequestidreply--回复权限请求)。

**Path 参数**：`sessionID`、`permissionID`  
**请求体**：`{ response: PermissionV1.Reply }`（`"once" | "always" | "reject"`）  
**返回**：`boolean`  
**错误**：400、404（会话或权限不存在）、`PermissionNotFoundError`

---

### 25. `DELETE /session/:sessionID/message/:messageID` — 删除消息

**用途**：永久删除消息及其所有 part，**不撤销文件变更**。

**返回**：`boolean`  
**错误**：400、404、**409（忙碌）**  
**原理**：先 `requireSession`，再 `runState.assertNotBusy`（忙碌 → 409），再 `session.removeMessage()`。  
**与 revert 的区别**：deleteMessage 不回滚文件，revert 会回滚。

---

### 26. `DELETE /session/:sessionID/message/:messageID/part/:partID` — 删除 Part

**返回**：`boolean`  
**错误**：400、404  
**注意**：**不检查忙碌状态**（与 deleteMessage 不同）。

---

### 27. `PATCH /session/:sessionID/message/:messageID/part/:partID` — 更新 Part

**请求体**：`SessionV1.Part`（完整的 Part 对象）  
**返回**：`SessionV1.Part`

**⚠️ ID 一致性校验**：handler 验证三重 ID 匹配，任一不符返回 400：
- `payload.id === params.partID`
- `payload.messageID === params.messageID`
- `payload.sessionID === params.sessionID`

---

## Permission 路由组

### 28. `GET /permission` — 列出待处理权限请求

**用途**：获取**所有会话**的待处理权限请求。  
**Query 参数**：`WorkspaceRoutingQuery`  
**返回**：`PermissionV1.Request[]`  
**数据来源**：`Permission.Service.list()`（内存/状态管理）。

---

### 29. `POST /permission/:requestID/reply` — 回复权限请求

**Path 参数**：`requestID: PermissionV1.ID`（`per_` 前缀）  
**请求体**（`ReplyPayload`）：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `reply` | `"once" \| "always" \| "reject"` | 是 | 回复类型 |
| `message` | `string?` | 否 | 附加消息（如拒绝理由） |

**返回**：`boolean`  
**错误**：400、`PermissionNotFoundError`（404）  
**原理**：`Permission.Service.reply({ requestID, reply, message })`。

---

## Question 路由组

### 30. `GET /question` — 列出待处理问题

**用途**：获取**所有会话**的待处理问题请求。  
**返回**：`Question.Request[]`

---

### 31. `POST /question/:requestID/reply` — 回复问题

**Path 参数**：`requestID: QuestionID`（`que_` 前缀）  
**请求体**（`ReplyPayload`）：

```typescript
{
  answers: string[][]  // 按问题顺序，每个是选中 label 的数组
}
```

**返回**：`boolean`  
**错误**：400、`QuestionNotFoundError`（404）

---

### 32. `POST /question/:requestID/reject` — 拒绝问题

**Path 参数**：`requestID`  
**无请求体**  
**返回**：`boolean`  
**错误**：400、404

---

## 关键发现

### 🔴 发现 1：`POST /session/:id/message`（prompt）不是流式响应

OpenAPI 描述为 "streaming the AI response"，但实际 handler 代码：

```typescript
const message = yield* promptSvc.prompt({...})  // 阻塞等待完成
return HttpServerResponse.stream(
  Stream.make(JSON.stringify(message)),          // 单 chunk
  { contentType: "application/json" }
)
```

`promptSvc.prompt()` 会阻塞直到整个 AI 响应循环结束，然后返回最终消息。`HttpServerResponse.stream` 只是技术上的流式包装（单次 chunk 推送完整 JSON）。**真正的实时流式必须通过 SSE 事件监听**（`session.status`、`message.part.updated` 等事件）。

**客户端建议**：对于需要实时反馈的场景，应使用 `prompt_async` + SSE 监听，而非依赖 `prompt` 的返回。

---

### 🔴 发现 2：`PATCH /session/:id` 的 permission 是合并而非替换

```typescript
yield* session.setPermission({
  sessionID,
  permission: Permission.merge(current.permission ?? [], ctx.payload.permission)
})
```

客户端如果发送 `permission: [...]` 期望完全替换权限规则，实际效果是**与现有规则合并**。而 `metadata` 字段是**完全替换**（`setMetadata`）。这种不一致是隐藏陷阱。

---

### 🟡 发现 3：消息分页使用非标准 Header

`GET /session/:id/message?limit=N` 的分页不使用标准的 `next_cursor` body 字段，而是通过自定义 HTTP header：
- `Link: <URL>; rel="next"`
- `X-Next-Cursor: <cursor>`

客户端需要手动解析这些 header。游标格式为 `base64url(JSON({id, time}))`。且 `before` 参数必须配合 `limit` 使用，否则 400。

---

### 🟡 发现 4：prompt_async 的错误是"静默"的

`prompt_async` 返回 204 后，后台任务可能失败。错误通过 `session.error` SSE 事件通知。如果客户端不监听 SSE，将完全无法感知异步失败。

---

### 🟡 发现 5：command 的 model 是字符串格式，prompt 的 model 是对象格式

- `PromptInput.model`：`{ providerID: ProviderV2.ID, modelID: ModelV2.ID }`（对象）
- `CommandInput.model`：`string`（格式 `"providerID/modelID"`）

这种不一致容易导致客户端序列化错误。

---

### 🟢 发现 6：多个端点对 sessionID 存在性的检查不一致

- `diff` 端点**不检查** sessionID 是否存在（直接传给 summary 服务）
- `abort` 端点**不检查** sessionID 是否存在（直接调用 cancel）
- 其他大多数端点通过 `requireSession` 检查

---

### 🟢 发现 7：create 和 fork 使用 Raw Handler

这两个端点使用 `handleRaw` 手动解析 body，支持**空 body**（创建默认会话 / 在最新消息处 fork）。这是因为它们的 payload schema 用了 `Schema.optional` 或允许 undefined，raw handler 能正确处理空 body 情况，而标准 handler 可能对空 body 报错。
