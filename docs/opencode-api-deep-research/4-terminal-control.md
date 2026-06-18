# OpenCode API 深度调研 #4：终端、控制、同步与实验性接口

> 调研日期：2026-06-18
> 源码版本：opencode `packages/opencode` + `packages/core`
> 端点总数：**53 个**（7 个功能组）
> 源码根：`packages/opencode/src/server/routes/instance/httpapi/`

## 目录

- [概览](#概览)
- [4.1 PTY 终端接口（8 个）](#41-pty-终端接口8-个)
- [4.2 TUI 控制接口（13 个）](#42-tui-控制接口13-个)
- [4.3 控制平面基础接口（3 个）](#43-控制平面基础接口3-个)
- [4.4 同步接口（4 个）](#44-同步接口4-个)
- [4.5 实验性接口（12 个）](#45-实验性接口12-个)
- [4.6 实例接口（12 个）](#46-实例接口12-个)
- [4.7 跨项目控制平面（1 个）](#47-跨项目控制平面1-个)
- [附录 A：通用查询参数](#附录-a通用查询参数)
- [附录 B：错误类型速查](#附录-b错误类型速查)

---

## 概览

| 功能组 | 路由文件 | 端点数 | 路径前缀 | 中间件栈 |
|--------|----------|--------|----------|----------|
| PTY 终端 | groups/pty.ts | 8 | `/pty` | Instance → Workspace → Authorization |
| PTY WebSocket | groups/pty.ts (PtyConnectApi) | (含在上) | `/pty/:id/connect` | Instance → Workspace → **PtyConnectAuthorization** |
| TUI 控制 | groups/tui.ts | 13 | `/tui` | Instance → Workspace → Authorization |
| 控制平面基础 | groups/control.ts | 3 | `/auth/:providerID`, `/log` | **无**（注册在 RootHttpApi，不走实例中间件） |
| 同步 | groups/sync.ts | 4 | `/sync` | Instance → Workspace → Authorization |
| 实验性 | groups/experimental.ts | 12 | `/experimental/*` | Instance → Workspace → Authorization |
| 实例 | groups/instance.ts | 12 | `/path`, `/vcs`, `/command` 等 | Instance → Workspace → Authorization |
| 跨项目控制 | groups/control-plane.ts | 1 | `/experimental/control-plane/move-session` | **无**（注册在 RootHttpApi） |

### 三大核心机制

1. **PTY WebSocket 票据认证** — 浏览器 WebSocket 无法携带 Basic Auth 头，通过短时票据桥接认证
2. **TUI 双通道控制** — EventV2 事件总线（单向推送）+ AsyncQueue 请求-响应队列（双向同步）
3. **Sync 事件溯源** — 基于 EventV2 的增量同步、回放与"窃取"系统，支撑多工作区协作

---

## 4.1 PTY 终端接口（8 个）

> 路由：`groups/pty.ts` · Handler：`handlers/pty.ts`
> 核心：`packages/core/src/pty.ts`（Service/Schema）+ `packages/core/src/pty/ticket.ts`（票据）+ `server/shared/pty-ticket.ts`（路径识别）

### 4.1.1 核心机制：WebSocket 票据认证（三步流程）

```
客户端                                服务端
  │                                     │
  │── 1. POST /pty/:id/connect-token ──→│  需 Basic Auth + Origin 校验 + x-opencode-ticket:1 头
  │                                     │  tickets.issue() → crypto.randomUUID(), 60s TTL
  │←────── { ticket, expires_in:60 } ───│
  │                                     │
  │── 2. ws://.../pty/:id/connect ──────→│  URL 带 ?ticket=<uuid> → 跳过 Basic Auth
  │         ?ticket=<uuid>&cursor=N      │  tickets.consume() → 原子校验+删除
  │←────── HTTP 101 Switching ──────────│
  │                                     │
  │←── 3a. 历史缓冲回放 (≤64KB/帧) ──────│
  │←── 3b. 控制帧 [0x00 + JSON{cursor}] │
  │←──→ 4. 双向 PTY 数据流 ─────────────│
```

**票据生命周期**（来源：`core/pty/ticket.ts:40-54`）：

| 属性 | 值 | 说明 |
|------|-----|------|
| 格式 | `crypto.randomUUID()` | UUID v4 |
| 默认 TTL | **60 秒** | `DEFAULT_TTL = Duration.seconds(60)` (ticket.ts:8) |
| 容量上限 | **10,000** | `CAPACITY = 10_000` (ticket.ts:9)，基于 effect `Cache` 的 LRU |
| 绑定维度 | `(ptyID, directory, workspaceID)` | 消费时三元组必须完全匹配 (ticket.ts:29-33) |
| 消费模式 | **一次性** | `Cache.invalidateWhen` 原子删除，匹配后立即失效 |
| 过期 | 自动 | effect Cache 内置 TTL 过期清理 |

**为什么需要票据**：WebSocket 升级请求由浏览器发起，`Authorization` 头无法在 `new WebSocket(url)` 中设置。OpenCode 让客户端先用普通 HTTP 获取一次性票据，再把票据放在 WebSocket URL 的 query string 中完成认证（`server/shared/pty-ticket.ts:13-15` 的 `hasPtyConnectTicketURL` 识别此模式，Auth 中间件据此跳过 Basic 校验）。

**额外的 connect-token 请求头**：`POST /connect-token` 除了 Basic Auth 外，还要求：
- 请求头 `x-opencode-ticket: 1`（`PTY_CONNECT_TOKEN_HEADER`，pty-ticket.ts:2）
- 通过 CORS Origin 校验（`validOrigin()`，handlers/pty.ts:129）

两者缺一不可，否则返回 `403 PtyForbiddenError`。

### 4.1.2 PTY 数据模型

**`Pty.Info`**（来源：`core/pty.ts:45-54`）— 所有返回 PTY 信息的端点共用：

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | `PtyID`（`"pty"` 前缀字符串，branded） | 会话 ID，格式如 `pty_01HZ...` |
| `title` | `string` | 显示标题，默认 `Terminal <id后4位>` |
| `command` | `string` | 实际执行的命令（如 `/bin/bash`） |
| `args` | `string[]` | 命令参数数组 |
| `cwd` | `string` | 工作目录 |
| `status` | `"running" \| "exited"` | 进程状态 |
| `pid` | `NonNegativeInt` | 子进程 PID。Windows ConPTY 异步分配，spawn 时可能为 `0` |

**`Pty.CreateInput`**（`core/pty.ts:58-64`）— 所有字段可选：

| 字段 | 类型 | 说明 |
|------|------|------|
| `command` | `string?` | 命令，缺省取 `Shell.preferred(config.shell)` |
| `args` | `string[]?` | 参数，若 shell 支持 login 模式自动追加 `-l` |
| `cwd` | `string?` | 工作目录，缺省取实例目录 |
| `title` | `string?` | 显示标题 |
| `env` | `Record<string,string>?` | 额外环境变量（与 `process.env` + 插件 `shell.env` 合并） |

**`Pty.UpdateInput`**（`core/pty.ts:76-84`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `title` | `string?` | 新标题 |
| `size` | `{ rows: PositiveInt, cols: PositiveInt }?` | 终端尺寸，触发 `process.resize(cols, rows)` |

### 4.1.3 PTY WebSocket 协议详解

**连接后服务端先发送的内容**（`core/pty.ts:262-303` 的 `connect` 实现）：

1. **历史缓冲回放**（如果 `cursor` 有效）：
   - 每个 PTY 会话维护 **2MB 环形缓冲区**（`BUFFER_LIMIT = 1024 * 1024 * 2`，pty.ts:11）
   - 缓冲区按 **64KB 分块**发送（`BUFFER_CHUNK = 64 * 1024`，pty.ts:12），避免单帧过大
   - `cursor` 语义：
     - `cursor` 缺省或 `0`：从缓冲区起始位置回放全部历史
     - `cursor` 为正整数：从该字节偏移开始回放（`Math.max(0, cursor - start)`）
     - `cursor = -1`：**跳过历史**，只接收新数据（`from = end`）

2. **控制帧（meta frame）**（pty.ts:36-43）：
   - 格式：`[0x00, ...UTF-8 JSON bytes]`
   - 内容：`{ "cursor": <当前总字节数> }`
   - 用途：告知客户端当前游标位置，用于断线重连时传入新的 `cursor` 参数
   - **客户端据此判断历史回放结束**，后续都是实时 PTY 输出

3. **双向数据流**：
   - 服务端 → 客户端：PTY 子进程的 stdout/stderr 原始字节流（字符串 chunk）
   - 客户端 → 服务端：键盘输入，支持 **string 或 ArrayBuffer/Uint8Array**（`input.ts:5-23`）
     - string：直接转发 `process.write(message)`
     - binary：UTF-8 解码后转发，解码失败静默丢弃（非致命）

**连接生命周期事件**（`handlers/pty.ts:171-256`）：

| 事件 | 行为 |
|------|------|
| PTY 不存在 | 返回 `404`（升级前检查，pty.ts:177-181） |
| cursor query 非法 | 返回 `400`（pty.ts:183-184） |
| 票据无效/Origin 不匹配 | 返回 `403`（pty.ts:186-190） |
| 服务端正在关闭 | 发送 `CloseEvent(1001, "server closing")` 后断开（websocket-tracker.ts:4） |
| PTY 会话在连接后消失 | 发送 `CloseEvent(4404, "session not found")`（pty.ts:234-236） |
| 客户端断开 | `closed = true`，调用 `handler.onClose()` 清理订阅 |
| 进程退出 | 自动触发 `Event.Exited`，清理会话，关闭所有订阅者 WebSocket |

**服务端优雅关闭**（`websocket-tracker.ts`）：
- `WebSocketTracker` 维护所有活跃 WebSocket 连接的 `Set`
- `closeAll()` 在服务端关闭时标记 `closing = true`，新连接注册返回 `false` → 立即关闭
- 关闭时并发向所有连接发送 `1001`，每连接超时 1 秒

### 4.1.4 端点详解

---

#### 1. `GET /pty/shells` — 列出可用 shell

| 项目 | 内容 |
|------|------|
| **用途** | 获取系统上可用的 shell 列表，供用户选择创建 PTY 时使用 |
| **Query** | `WorkspaceRoutingQuery`（`directory?`, `workspace?`） |
| **返回** | `ShellItem[]` |
| **认证** | Basic Auth（通过 Authorization 中间件） |

**`ShellItem` 字段**（groups/pty.ts:23-27）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `path` | `string` | shell 完整路径（Windows 可能是 git-bash 路径） |
| `name` | `string` | shell 基名小写（如 `bash`, `zsh`, `pwsh`） |
| `acceptable` | `boolean` | 是否可接受。`fish`/`nu` 被标记为 `deny: true`（shell.ts:10-20） |

**原理**（`shell/shell.ts:210-213`）：
- Windows：检测 `pwsh` → `powershell` → `git-bash` → `cmd.exe`
- Unix：读取 `/etc/shells`，失败则回退 `["/bin/bash", "/bin/zsh", "/bin/sh"]`
- 每个候选经过 `resolve()` 验证文件存在

---

#### 2. `GET /pty` — 列出所有 PTY 会话

| 项目 | 内容 |
|------|------|
| **用途** | 获取当前实例管理的所有活跃 PTY 会话信息 |
| **Query** | `WorkspaceRoutingQuery` |
| **返回** | `Pty.Info[]` |
| **原理** | `Pty.Service.list()` 遍历内存中 `sessions: Map<PtyID, Active>` 返回所有 `info` |

---

#### 3. `POST /pty` — 创建 PTY 会话

| 项目 | 内容 |
|------|------|
| **用途** | 创建新的伪终端会话，启动 shell 进程 |
| **Query** | `WorkspaceRoutingQuery` |
| **Payload** | `Pty.CreateInput`（所有字段可选） |
| **返回** | `Pty.Info`（创建后的会话信息，含生成的 `id` 和 `pid`） |
| **错误** | `400 BadRequest` |

**创建流程**（`handlers/pty.ts:62-75` + `pty-preparation.ts`）：
1. `PtyPreparation.prepareCreate()` 准备参数：
   - `command`：取 `input.command` 或配置的 shell 或系统默认
   - `args`：若 shell 支持 login（`META[shell].login === true`），自动追加 `-l`
   - `cwd`：取 `input.cwd` 或实例目录
   - 触发插件钩子 `"shell.env"` 获取额外环境变量
   - 合并 env：`process.env` + `input.env` + 插件 env + `TERM=xterm-256color` + `OPENCODE_TERMINAL=1`
   - Windows 额外设置 `LC_ALL`/`LC_CTYPE`/`LANG = "C.UTF-8"`
2. `Pty.Service.create()`：
   - 生成 `PtyID.ascending()`（前缀 `pty_` 的升序 ULID）
   - 调用底层 `spawn()`（Bun 用 `bun-pty`，Node 用 `@lydell/node-pty`）
   - 注册 `onData` → 广播给订阅者 + 追加到缓冲区
   - 注册 `onExit` → 标记 `status: "exited"` → 发布 `Event.Exited` → 自动移除会话
   - 发布 `Event.Created`

---

#### 4. `GET /pty/:ptyID` — 获取 PTY 会话信息

| 项目 | 内容 |
|------|------|
| **用途** | 查询单个 PTY 会话的当前状态 |
| **Params** | `ptyID: PtyID` |
| **返回** | `Pty.Info` |
| **错误** | `404 PtyNotFoundError`（`{ ptyID, message }`） |

---

#### 5. `PUT /pty/:ptyID` — 更新 PTY 会话

| 项目 | 内容 |
|------|------|
| **用途** | 更新标题或调整终端尺寸 |
| **Params** | `ptyID: PtyID` |
| **Payload** | `Pty.UpdateInput` |
| **返回** | `Pty.Info`（更新后的信息） |
| **错误** | `404 PtyNotFoundError` / `400 BadRequest` |

**行为**（`core/pty.ts:244-250`）：
- `title` 非空 → 更新 `info.title`
- `size` 提供 → 调用 `process.resize(cols, rows)`
- 发布 `Event.Updated`

---

#### 6. `DELETE /pty/:ptyID` — 删除 PTY 会话

| 项目 | 内容 |
|------|------|
| **用途** | 终止并移除 PTY 会话 |
| **Params** | `ptyID: PtyID` |
| **返回** | `boolean`（恒为 `true`） |
| **错误** | `404 PtyNotFoundError` |

**行为**（`core/pty.ts:155-168`）：
- `teardown()`：dispose 所有监听器 → `process.kill()` → 关闭所有 WebSocket 订阅者
- 发布 `Event.Deleted`

---

#### 7. `POST /pty/:ptyID/connect-token` — 获取 WebSocket 连接票据

| 项目 | 内容 |
|------|------|
| **用途** | 为后续 WebSocket 连接申请一次性票据 |
| **Params** | `ptyID: PtyID` |
| **Headers** | **必需** `x-opencode-ticket: 1` + Basic Auth + 合法 Origin |
| **返回** | `ConnectToken { ticket: string, expires_in: number }` |
| **错误** | `403 PtyForbiddenError` / `404 PtyNotFoundError` |

**`ConnectToken` 字段**（`core/pty/ticket.ts:11-14`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `ticket` | `string` | UUID v4 票据 |
| `expires_in` | `PositiveInt` | 过期秒数（默认 60），由 TTL 计算得出：`Math.max(1, round(TTL秒))` |

**建议用法**：
```typescript
// 1. 获取票据
const { ticket } = await fetch(`/pty/${ptyID}/connect-token`, {
  method: "POST",
  headers: { "x-opencode-ticket": "1", "Authorization": "Basic ..." }
}).then(r => r.json())

// 2. 建立 WebSocket（无需 Auth 头）
const ws = new WebSocket(`/pty/${ptyID}/connect?ticket=${ticket}&cursor=-1`)
```

---

#### 8. `GET /pty/:ptyID/connect` — WebSocket 连接（升级端点）

| 项目 | 内容 |
|------|------|
| **用途** | 建立 WebSocket 连接，实时交互 PTY 输入输出 |
| **Params** | `ptyID: PtyID` |
| **Query** | `directory?`, `workspace?`, `cursor?`, `ticket?` |
| **认证** | **特殊**：`PtyConnectAuthorization` 中间件 — 有 ticket 跳过 Basic Auth，无 ticket 则需 Basic Auth |
| **返回** | HTTP 101（WebSocket 升级），无 JSON body |

**Query 参数详解**：

| 参数 | 类型 | 说明 |
|------|------|------|
| `ticket` | `string?` | connect-token 获取的票据。提供时跳过 Basic Auth（但 Origin 仍需校验） |
| `cursor` | `string?`（解析为整数） | 历史回放起始字节偏移。`-1` = 跳过历史。非整数或 `< -1` → `400` |
| `directory` | `string?` | 工作区路由（见附录 A） |
| `workspace` | `string?` | 工作区 ID 路由 |

**特殊设计**（`handlers/pty.ts:171-256`）：
- 此端点用 `handleRaw` 绕过 Effect HttpApi 的编解码，手动处理 WebSocket 升级
- **存在性检查在票据校验之前**（pty.ts:177-184）：不存在 → `404`；cursor 非法 → `400`；票据非法 → `403`。这个顺序是刻意的（注释 pty.ts:142-143 提到 "preserving the established empty-404 response ordering"）
- 用 `EffectBridge` 管理异步写入，避免 socket 写竞态

**客户端实现要点**：
1. 收到的第一类数据是**历史回放**（0 或多个 binary/text 帧）
2. 收到的 `[0x00 + JSON]` 帧是**控制帧**，解析 `cursor` 值保存
3. 之后的所有数据都是**实时 PTY 输出**
4. 发送数据时用 `string` 或 `ArrayBuffer`（UTF-8 编码）

---

## 4.2 TUI 控制接口（13 个）

> 路由：`groups/tui.ts` · Handler：`handlers/tui.ts`
> 核心：`server/tui-event.ts`（事件定义）+ `server/shared/tui-control.ts`（请求-响应队列）

### 4.2.1 核心机制：双通道控制架构

OpenCode 的 TUI（Terminal User Interface）控制有**两条独立通道**：

```
┌─────────────────────────────┐         ┌──────────────────┐
│  外部进程（如 oc-remote App）│         │   OpenCode TUI   │
└──────────────┬──────────────┘         └────────┬─────────┘
               │                                 │
    ┌──────────┴──────────┐          ┌───────────┴────────────┐
    │ 通道 A：事件推送     │          │ 通道 B：请求-响应队列   │
    │ （单向，fire-and-    │          │ （双向，同步等待）      │
    │  forget）            │          │                        │
    │                      │          │                        │
    │ POST /tui/append-    │          │ GET  /tui/control/next │
    │   prompt             │  EventV2 │   ← TUI 消费请求        │
    │ POST /tui/open-help  │──Bus──→  │                        │
    │ POST /tui/show-toast │          │ POST /tui/control/     │
    │ POST /tui/publish    │          │   response             │
    │ POST /tui/select-    │          │   → 外部提交响应        │
    │   session            │          │                        │
    │ ... (10 个端点)      │          │ AsyncQueue<TuiRequest> │
    └─────────────────────┘          └────────────────────────┘
```

**通道 A：事件推送**（`handlers/tui.ts:27-105`）
- 通过 `EventV2Bridge.Service.publish()` 发布事件到内部事件总线
- TUI 订阅这些事件并执行对应 UI 操作
- **fire-and-forget**：不等待 TUI 响应，立即返回 `true`
- 事件类型定义在 `server/tui-event.ts`：`PromptAppend`、`CommandExecute`、`ToastShow`、`SessionSelect`

**通道 B：请求-响应队列**（`server/shared/tui-control.ts`）
- 两个全局 `AsyncQueue`：`request`（外部→TUI）和 `response`（TUI→外部）
- `GET /tui/control/next`：TUI 端长轮询，阻塞等待外部发来的请求
- `POST /tui/control/response`：TUI 处理完请求后提交响应
- **用途**：让 TUI 作为"执行器"，处理外部进程通过 HTTP 发来的命令，并返回结果

### 4.2.2 TuiEvent 事件定义

所有事件通过 `EventV2.define()` 定义（`server/tui-event.ts`）：

| 事件类型 | type 字符串 | data schema |
|----------|-------------|-------------|
| `PromptAppend` | `tui.prompt.append` | `{ text: string }` |
| `CommandExecute` | `tui.command.execute` | `{ command: string \| known-literal }` |
| `ToastShow` | `tui.toast.show` | `{ title?: string, message: string, variant: "info"\|"success"\|"warning"\|"error", duration: PositiveInt }` |
| `SessionSelect` | `tui.session.select` | `{ sessionID: SessionID }` |

**`CommandExecute` 的已知命令字面量**（tui-event.ts:13-31）：
```
session.list, session.new, session.share, session.interrupt, session.compact,
session.page.up, session.page.down, session.line.up, session.line.down,
session.half.page.up, session.half.page.down, session.first, session.last,
prompt.clear, prompt.submit, agent.cycle
```

### 4.2.3 端点详解

---

#### 1. `POST /tui/append-prompt` — 追加提示文本

| 项目 | 内容 |
|------|------|
| **Payload** | `{ text: string }` |
| **返回** | `boolean`（恒 `true`） |
| **错误** | `400 BadRequest` |
| **原理** | 发布 `TuiEvent.PromptAppend`，TUI 收到后将 `text` 追加到输入框 |
| **注意** | 是**追加**不是**替换**，不会自动提交 |

---

#### 2-6. 对话框控制端点组（无 Payload）

这 5 个端点都是 `POST`、无 payload、返回 `boolean`，通过发布 `CommandExecute` 事件驱动 TUI：

| # | 端点 | 发布的 command | 用途 |
|---|------|---------------|------|
| 2 | `POST /tui/open-help` | `help.show` | 打开帮助对话框 |
| 3 | `POST /tui/open-sessions` | `session.list` | 打开会话列表对话框 |
| 4 | `POST /tui/open-themes` | `session.list` | 打开主题对话框（**注意：与 open-sessions 相同的 command，疑似源码笔误**） |
| 5 | `POST /tui/open-models` | `model.list` | 打开模型选择对话框 |
| 6 | `POST /tui/submit-prompt` | `prompt.submit` | 提交当前输入框内容 |

> ⚠️ **发现**：`openThemes` 发布的是 `session.list` 而非 `theme.list`（`handlers/tui.ts:51-54`），这可能是源码 bug——主题对话框和会话对话框会触发相同行为。

---

#### 7. `POST /tui/clear-prompt` — 清空提示输入

| 项目 | 内容 |
|------|------|
| **返回** | `boolean` |
| **原理** | 发布 `CommandExecute { command: "prompt.clear" }` |

---

#### 8. `POST /tui/execute-command` — 执行 TUI 命令（遗留别名）

| 项目 | 内容 |
|------|------|
| **Payload** | `{ command: string }` |
| **返回** | `boolean` |
| **错误** | `400 BadRequest` |

**重要**：此端点使用**遗留别名映射**（`handlers/tui.ts:11-25`），只识别以下旧式命令名，未知命令会被映射为 `undefined`（静默失败）：

| 输入（旧名） | 映射到（新名） |
|-------------|---------------|
| `session_new` | `session.new` |
| `session_share` | `session.share` |
| `session_interrupt` | `session.interrupt` |
| `session_compact` | `session.compact` |
| `messages_page_up` | `session.page.up` |
| `messages_page_down` | `session.page.down` |
| `messages_line_up` | `session.line.up` |
| `messages_line_down` | `session.line.down` |
| `messages_half_page_up` | `session.half.page.up` |
| `messages_half_page_down` | `session.half.page.down` |
| `messages_first` | `session.first` |
| `messages_last` | `session.last` |
| `agent_cycle` | `agent.cycle` |

**建议**：新代码应直接使用 `/tui/publish` 端点发送 `CommandExecute` 事件，避免别名层的限制。

---

#### 9. `POST /tui/show-toast` — 显示 Toast 通知

| 项目 | 内容 |
|------|------|
| **Payload** | `ToastShow.data` |
| **返回** | `boolean` |

**Payload 字段**：

| 字段 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `title` | `string?` | — | 标题（可选） |
| `message` | `string` | 必填 | 正文 |
| `variant` | `"info"\|"success"\|"warning"\|"error"` | 必填 | 样式变体 |
| `duration` | `PositiveInt` | **5000** | 显示毫秒数 |

---

#### 10. `POST /tui/publish` — 发布通用 TUI 事件

| 项目 | 内容 |
|------|------|
| **Payload** | `TuiPublishPayload`（ discriminated union） |
| **返回** | `boolean` |
| **错误** | `400 BadRequest` |

**Payload 是 4 种事件的联合类型**（`groups/tui.ts:29-34`），通过 `type` 字段区分：

```jsonc
// 类型 1：追加提示
{ "type": "tui.prompt.append", "properties": { "text": "..." } }

// 类型 2：执行命令（支持任意 string 命令，不受别名限制）
{ "type": "tui.command.execute", "properties": { "command": "session.new" } }

// 类型 3：显示 Toast
{ "type": "tui.toast.show", "properties": { "message": "...", "variant": "info" } }

// 类型 4：选择会话
{ "type": "tui.session.select", "properties": { "sessionID": "ses_..." } }
```

**这是最通用的 TUI 控制端点**，一个请求可以触发任何类型的 TUI 事件。

---

#### 11. `POST /tui/select-session` — 导航到指定会话

| 项目 | 内容 |
|------|------|
| **Payload** | `{ sessionID: SessionID }` |
| **返回** | `boolean` |
| **错误** | `400 BadRequest`（sessionID 不以 `"ses"` 开头）/ `404 NotFound`（会话不存在） |

**校验逻辑**（`handlers/tui.ts:98-105`）：
1. `sessionID` 必须以 `"ses"` 开头（SessionID 前缀）
2. 通过 `Session.Service.get()` 验证会话存在
3. 发布 `TuiEvent.SessionSelect`，TUI 导航到该会话

---

#### 12. `GET /tui/control/next` — 获取下一个 TUI 请求（通道 B）

| 项目 | 内容 |
|------|------|
| **用途** | TUI 端长轮询，阻塞等待外部进程通过队列发来的请求 |
| **返回** | `TuiRequest`（阻塞直到有请求） |
| **行为** | 调用 `nextTuiRequest()` → `request.next()`（AsyncQueue 出队） |

**`TuiRequest` 结构**（`tui-control.ts:4-7`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `path` | `string` | 请求路径（如 `/sync/replay`） |
| `body` | `unknown` | 请求体（任意 JSON） |

**典型用法**：TUI 进程在事件循环中反复调用此端点，获取外部通过通道 B 发来的操作请求，本地执行后通过 `/tui/control/response` 返回结果。

---

#### 13. `POST /tui/control/response` — 提交 TUI 响应（通道 B）

| 项目 | 内容 |
|------|------|
| **Payload** | `Schema.Unknown`（任意 JSON） |
| **返回** | `boolean`（恒 `true`） |
| **行为** | 调用 `submitTuiResponse(payload)` → `response.push(payload)`（入队） |

**配对使用**：外部进程发请求 → TUI 通过 `next` 取出 → 本地处理 → 通过 `response` 提交结果 → 外部进程从 `response` 队列取出结果。

> **注意**：当前实现中，`request` 和 `response` 是两个独立的**全局单例** AsyncQueue（`tui-control.ts:11-12`），没有请求 ID 关联。这意味着多个并发请求-响应对可能交叉错配。适合单请求-单响应的串行场景。

---

## 4.3 控制平面基础接口（3 个）

> 路由：`groups/control.ts` · Handler：`handlers/control.ts`
> **注意**：这组端点注册在 `RootHttpApi`（非实例级 InstanceHttpApi），**不经过** Instance/Workspace/Authorization 中间件。

### 端点详解

---

#### 1. `PUT /auth/:providerID` — 设置认证凭据

| 项目 | 内容 |
|------|------|
| **Params** | `providerID: ProviderV2.ID` |
| **Payload** | `Auth.Info`（OAuth/API Key/WellKnown 联合类型） |
| **返回** | `boolean` |
| **错误** | `400 BadRequest` |
| **原理** | `Auth.Service.set(providerID, credentials)` 持久化到本地认证存储 |

**`Auth.Info`** 是三种认证方式的联合类型（`auth/index.ts:34`）：

| 变体 | type | 关键字段 |
|------|------|----------|
| OAuth | `"oauth"` | `client_id`, `client_secret?`, `authorization_url`, `token_url`, ... |
| API Key | `"api_key"` | `api_key`, `headers?` |
| WellKnown | `"well_known"` | `url`（OpenID Connect well-known 配置端点） |

---

#### 2. `DELETE /auth/:providerID` — 移除认证凭据

| 项目 | 内容 |
|------|------|
| **Params** | `providerID: ProviderV2.ID` |
| **返回** | `boolean` |
| **错误** | `400 BadRequest` |
| **原理** | `Auth.Service.remove(providerID)` 从本地存储删除 |

---

#### 3. `POST /log` — 写入服务端日志

| 项目 | 内容 |
|------|------|
| **Query** | `LogQuery { directory?: string, workspace?: string }` |
| **Payload** | `LogInput` |
| **返回** | `boolean` |

**`LogInput` 字段**（`groups/control.ts:17-29`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `service` | `string` | 服务名称（标注用） |
| `level` | `"debug"\|"info"\|"warn"\|"error"` | 日志级别 |
| `message` | `string` | 日志消息 |
| `extra` | `Record<string, unknown>?` | 额外元数据（作为 Effect 日志标注） |

**原理**（`handlers/control.ts:28-39`）：映射到 Effect 的日志函数（`Effect.logDebug/logInfo/logWarning/logError`），`extra` 通过 `Effect.annotateLogs()` 附加为结构化标注。**用途**：让客户端（如 SDK、IDE 插件）把自身日志写入 OpenCode 服务端统一日志流，便于调试。

---

## 4.4 同步接口（4 个）

> 路由：`groups/sync.ts` · Handler：`handlers/sync.ts`
> 核心：`EventV2` 事件系统 + `EventTable`（SQLite 持久化）

### 4.4.1 核心机制：EventV2 事件溯源同步

OpenCode 的多工作区协作基于**事件溯源（Event Sourcing）**模式：

```
工作区 A（本地）                     工作区 B（远程）
    │                                    │
    │  用户操作产生 EventV2 事件          │
    │  → 写入本地 SQLite EventTable       │
    │  → 通过 sync 连接转发给 B           │
    │                                    │
    │                    ┌───────────────┤
    │                    │ /sync/replay   │ ← B 接收事件序列
    │                    │ 验证+回放到本地 │
    │                    │ EventTable     │
    │                    └───────────────┤
    │                                    │
    │  /sync/history ← B 查询增量事件     │
    │  /sync/start  ← B 启动持续同步      │
    │  /sync/steal  ← B 将会话纳入本工作区 │
```

**EventV2 结构**（`core/event`）：每个事件有 `id`（唯一）、`aggregateID`（聚合根，如 session ID）、`seq`（单调递增序列号）、`type`（事件类型字符串）、`data`（事件负载）。

**同步语义**：
- **增量同步**：客户端记录每个 aggregate 的最后已知 `seq`，只请求 `seq > 已知值` 的事件
- **全量回放**：`/sync/replay` 接收完整事件序列，验证后应用到本地
- **所有权校验**：replay 时设置 `strictOwner: true`，确保事件归属正确的工作区

### 4.4.2 端点详解

---

#### 1. `POST /sync/start` — 启动工作区同步

| 项目 | 内容 |
|------|------|
| **用途** | 为当前项目中所有有活跃会话的工作区启动同步循环 |
| **Query** | `WorkspaceRoutingQuery` |
| **返回** | `boolean`（恒 `true`） |
| **原理** | `Workspace.Service.startWorkspaceSyncing(projectID)` — fork 一个后台 Effect 持续运行同步循环 |

**注意**（`handlers/sync.ts:27-32`）：使用 `Effect.ignore` + `Effect.forkIn(scope)` 在请求作用域内 fork 后台任务，即使同步循环失败也不影响 HTTP 响应。同步循环的生命周期绑定到实例 scope。

---

#### 2. `POST /sync/replay` — 回放同步事件

| 项目 | 内容 |
|------|------|
| **用途** | 验证并回放完整的事件历史到本地 EventTable |
| **Query** | `WorkspaceRoutingQuery` |
| **Payload** | `ReplayPayload` |
| **返回** | `ReplayResponse { sessionID: string }` |
| **错误** | `400 BadRequest` |

**`ReplayPayload` 字段**（`groups/sync.ts:19-22`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `directory` | `string` | 源工作区目录 |
| `events` | `NonEmptyArray<ReplayEvent>` | 至少 1 个事件，按 seq 升序 |

**`ReplayEvent` 字段**（`groups/sync.ts:12-18`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | `EventV2.ID` | 事件唯一 ID |
| `aggregateID` | `string` | 聚合根 ID |
| `seq` | `NonNegativeInt` | 序列号 |
| `type` | `string` | 事件类型 |
| `data` | `Record<string, unknown>` | 事件负载 |

**处理流程**（`handlers/sync.ts:34-59`）：
1. 提取 `source = events[0].aggregateID`（以第一个事件的 aggregateID 作为会话标识）
2. 获取当前工作区的 `ownerID`
3. 调用 `events.replayAll(payload, { ownerID, strictOwner: true })` — 逐个验证并写入
4. 返回 `{ sessionID: source }`

**日志**：请求开始和完成时各记录一条 `logInfo`，包含事件数、首尾 seq、目录。

---

#### 3. `POST /sync/steal` — 将会话"窃取"到当前工作区

| 项目 | 内容 |
|------|------|
| **用途** | 更新会话归属，使其属于当前工作区（通过事件系统） |
| **Query** | `WorkspaceRoutingQuery` |
| **Payload** | `SessionPayload { sessionID: SessionID }` |
| **返回** | `SessionPayload { sessionID }`（原样返回） |
| **错误** | `400 BadRequest`（无 workspaceID 时） |

**原理**（`handlers/sync.ts:61-70`）：
- 获取当前工作区的 `workspaceID`
- 调用 `Session.Service.setWorkspace({ sessionID, workspaceID })` 更新会话的工作区归属
- 这会通过事件系统传播，让其他工作区感知到归属变更

**"窃取"语义**：当一个会话需要在多个工作区间转移时，目标工作区调用此端点声明所有权。配合 `/sync/replay` 可以迁移完整会话历史。

---

#### 4. `POST /sync/history` — 查询同步事件历史

| 项目 | 内容 |
|------|------|
| **用途** | 增量查询事件历史，获取客户端未知的新事件 |
| **Query** | `WorkspaceRoutingQuery` |
| **Payload** | `HistoryPayload`（`Record<aggregateID, lastKnownSeq>`） |
| **返回** | `HistoryEvent[]`（按 seq 升序） |
| **错误** | `400 BadRequest` |

**`HistoryPayload`**（`groups/sync.ts:29`）：`Record<string, NonNegativeInt>`
- **key**：aggregateID（客户端已知）
- **value**：该 aggregate 客户端最后已知的 seq

**查询逻辑**（`handlers/sync.ts:72-85`）：
- 排除所有 `(aggregate_id = key AND seq <= value)` 的事件
- 返回所有其他事件（包括未在 payload 中列出的 aggregate 的全部历史）
- SQL 等价：`WHERE NOT (aggregate_id IN (...) AND seq <= ...)`，`ORDER BY seq ASC`

**`HistoryEvent` 字段**（`groups/sync.ts:30-36`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | `EventV2.ID` | 事件 ID |
| `aggregate_id` | `string` | 聚合根 ID（**注意蛇形命名**，与 ReplayEvent 的 `aggregateID` 不同） |
| `seq` | `NonNegativeInt` | 序列号 |
| `type` | `string` | 事件类型 |
| `data` | `Record<string, unknown>` | 负载 |

> ⚠️ **命名不一致**：`ReplayEvent` 用 `aggregateID`（驼峰），`HistoryEvent` 用 `aggregate_id`（蛇形）。前者是 API 输入 schema，后者映射自 SQLite `EventTable` 列名。客户端需注意区分。

**建议用法**（增量同步循环）：
```typescript
// 1. 维护本地已知状态：{ [aggregateID]: lastSeq }
// 2. 轮询获取增量
const newEvents = await POST("/sync/history", localKnownState)
// 3. 应用 newEvents 到本地，更新 localKnownState
for (const ev of newEvents) {
  applyEvent(ev)
  localKnownState[ev.aggregate_id] = Math.max(localKnownState[ev.aggregate_id] ?? 0, ev.seq)
}
```

---

## 4.5 实验性接口（12 个）

> 路由：`groups/experimental.ts` · Handler：`handlers/experimental.ts`
> 路径前缀：`/experimental`

### 端点详解

---

#### 1. `GET /experimental/console` — 获取 Console 组织状态

| 项目 | 内容 |
|------|------|
| **用途** | 获取当前活跃的 Console 组织名及其管理的 provider 集合 |
| **Query** | `WorkspaceRoutingQuery` |
| **返回** | `ConsoleStateResponse` |
| **错误** | `500 InternalServerError`（获取组织列表失败时） |

**返回字段**（`groups/experimental.ts:22-26`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `consoleManagedProviders` | `string[]` | 当前 Console 组织管理的 provider ID 列表 |
| `activeOrgName` | `string?` | 活跃组织名（可选，无活跃组织时不出现） |
| `switchableOrgCount` | `NonNegativeInt` | 可切换的组织总数（所有 account 的 org 数之和） |

---

#### 2. `GET /experimental/console/orgs` — 列出可切换的 Console 组织

| 项目 | 内容 |
|------|------|
| **返回** | `ConsoleOrgList { orgs: ConsoleOrgOption[] }` |
| **错误** | `500 InternalServerError` |

**`ConsoleOrgOption` 字段**（`groups/experimental.ts:28-35`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `accountID` | `string` | 账户 ID |
| `accountEmail` | `string` | 账户邮箱 |
| `accountUrl` | `string` | 账户 URL |
| `orgID` | `string` | 组织 ID |
| `orgName` | `string` | 组织名 |
| `active` | `boolean` | 是否为当前活跃组织 |

---

#### 3. `POST /experimental/console/switch` — 切换活跃 Console 组织

| 项目 | 内容 |
|------|------|
| **Payload** | `ConsoleSwitchPayload { accountID, orgID }` |
| **返回** | `boolean` |
| **错误** | `400 BadRequest`（切换失败时） |
| **原理** | `Account.Service.use(accountID, Some(orgID))` 持久化新的活跃选择 |

---

#### 4. `GET /experimental/tool` — 列出工具（含参数 schema）

| 项目 | 内容 |
|------|------|
| **Query** | `ToolListQuery` |
| **返回** | `ToolListItem[]` |
| **错误** | `400 BadRequest` |

**Query 参数**（`groups/experimental.ts:53-57`）：

| 参数 | 类型 | 说明 |
|------|------|------|
| `provider` | `ProviderV2.ID` | **必填** provider ID |
| `model` | `ModelV2.ID` | **必填** model ID |
| `directory?`, `workspace?` | — | 工作区路由 |

**返回字段**（`groups/experimental.ts:47-51`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | `string` | 工具 ID |
| `description` | `string` | 工具描述 |
| `parameters` | `unknown` | JSON Schema 格式的参数定义（`ToolJsonSchema.fromTool()`） |

**原理**：根据 provider + model 组合获取可用工具列表（不同模型可能暴露不同工具集）。

---

#### 5. `GET /experimental/tool/ids` — 列出工具 ID

| 项目 | 内容 |
|------|------|
| **Query** | `WorkspaceRoutingQuery` |
| **返回** | `string[]` |
| **原理** | `ToolRegistry.Service.ids()` — 返回所有已注册工具的 ID（内置 + 动态注册） |

---

#### 6-9. Worktree（工作树沙箱）端点组

| # | 方法+路径 | 用途 | Payload/Query |
|---|----------|------|---------------|
| 6 | `GET /experimental/worktree` | 列出当前项目的所有工作树目录 | Query: WorkspaceRoutingQuery → `string[]` |
| 7 | `POST /experimental/worktree` | 创建 git worktree 并运行启动脚本 | Payload: `Worktree.CreateInput?`（可空，`disableCodecodes: true`）→ `Worktree.Info` |
| 8 | `DELETE /experimental/worktree` | 删除 git worktree 及其分支 | Payload: `Worktree.RemoveInput` → `boolean` |
| 9 | `POST /experimental/worktree/reset` | 重置 worktree 分支到主分支 | Payload: `Worktree.ResetInput` → `boolean` |

**`Worktree.Info`**（`worktree/index.ts:37-41`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `name` | `string` | 工作树名称 |
| `branch` | `string?` | 分支名 |
| `directory` | `string` | 目录路径 |

**`Worktree.CreateInput`**（`worktree/index.ts:44-49`）：`{ name?: string, startCommand?: string }`

**`Worktree.RemoveInput` / `ResetInput`**（`worktree/index.ts:52-59`）：`{ directory: string }`

**错误类型**：`WorktreeApiError`（`groups/experimental.ts:69-75`），HTTP 400，`name` 为以下之一：
`WorktreeNotGitError`、`WorktreeNameGenerationFailedError`、`WorktreeCreateFailedError`、`WorktreeStartCommandFailedError`、`WorktreeRemoveFailedError`、`WorktreeResetFailedError`、`WorktreeListFailedError`

**端点 7 特殊设计**（`groups/experimental.ts:172-184`）：`disableCodecs: true` + payload 联合 `[NoContent, Worktree.CreateInput]`，允许客户端发空 body 创建默认工作树。

**端点 8 附加行为**（`handlers/experimental.ts:118-125`）：删除后还调用 `project.removeSandbox(projectID, directory)` 清理项目元数据。

---

#### 10. `GET /experimental/session` — 全局会话列表

| 项目 | 内容 |
|------|------|
| **用途** | 跨项目列出所有 OpenCode 会话，按更新时间排序 |
| **Query** | `SessionListQuery` |
| **返回** | `Session.GlobalInfo[]` + 可选 `x-next-cursor` 响应头 |

**`SessionListQuery`**（`groups/experimental.ts:76-84`）：

| 参数 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `roots` | `boolean?` | — | 只返回根会话 |
| `start` | `number?` | — | 起始偏移 |
| `cursor` | `number?` | — | 分页游标（时间戳） |
| `search` | `string?` | — | 搜索关键词 |
| `limit` | `number?` | **100** | 每页数量（实际请求 `limit+1` 判断是否有更多） |
| `archived` | `boolean?` | — | 是否包含归档会话（默认排除） |
| `directory?`, `workspace?` | — | — | 工作区路由 |

**分页机制**（`handlers/experimental.ts:134-152`）：请求 `limit + 1` 条，如果返回超过 `limit` 条则截断并通过 `x-next-cursor` 响应头返回下一页游标（最后一条的 `time.updated` 值）。

---

#### 11. `POST /experimental/session/:sessionID/background` — 后台化子代理

| 项目 | 内容 |
|------|------|
| **用途** | 将阻塞当前会话的同步子代理转为后台运行 |
| **Params** | `sessionID: SessionID` |
| **返回** | `boolean`（是否有任务被提升为后台） |
| **错误** | `400 BadRequest` |
| **门控** | 需运行时标志 `experimentalBackgroundSubagents` 开启，否则恒返回 `false` |

**原理**（`handlers/experimental.ts:154-167`）：
1. 过滤 `BackgroundJob` 列表：`type === "task"` && `status === "running"` && `parentSessionId === sessionID` && `background !== true`
2. 对每个匹配任务调用 `background.promote(jobID)` 提升为后台
3. 返回是否有至少一个任务被成功提升

---

#### 12. `GET /experimental/resource` — 获取 MCP 资源

| 项目 | 内容 |
|------|------|
| **用途** | 获取所有已连接 MCP 服务器的资源列表 |
| **Query** | `WorkspaceRoutingQuery` |
| **返回** | `Record<string, MCP.Resource>`（key 为资源名） |
| **原理** | `MCP.Service.resources()` 汇总所有 MCP 服务器的资源 |

---

## 4.6 实例接口（12 个）

> 路由：`groups/instance.ts` · Handler：`handlers/instance.ts`
> 这组端点提供实例元信息、VCS 操作和工具链状态查询

### 端点详解

---

#### 1. `POST /instance/dispose` — 销毁实例

| 项目 | 内容 |
|------|------|
| **用途** | 标记当前实例为待销毁，释放所有资源 |
| **返回** | `boolean` |
| **原理** | `markInstanceForDisposal(context)` — 非阻塞标记，实际清理由生命周期管理器异步执行 |

---

#### 2. `GET /path` — 获取路径信息

| 项目 | 内容 |
|------|------|
| **返回** | `PathInfo` |

**字段**（`groups/instance.ts:18-24`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `home` | `string` | 用户主目录（`Global.Path.home`） |
| `state` | `string` | 状态目录（`Global.Path.state`，存储 DB/日志） |
| `config` | `string` | 配置目录（`Global.Path.config`） |
| `worktree` | `string` | 当前 worktree 目录 |
| `directory` | `string` | 当前工作目录 |

---

#### 3-7. VCS（版本控制）端点组

| # | 方法+路径 | 用途 | 返回 |
|---|----------|------|------|
| 3 | `GET /vcs` | 获取分支信息 | `Vcs.Info { branch?, default_branch? }` |
| 4 | `GET /vcs/status` | 获取变更文件列表（不含 patch） | `Vcs.FileStatus[]` |
| 5 | `GET /vcs/diff` | 获取 diff（含 patch） | `Vcs.FileDiff[]` |
| 6 | `GET /vcs/diff/raw` | 获取原始 patch 文本 | `text/x-diff` 文本 |
| 7 | `POST /vcs/apply` | 应用 patch | `Vcs.ApplyResult { applied: boolean }` |

**端点 5 Query**（`VcsDiffQuery`，`groups/instance.ts:26-30`）：

| 参数 | 类型 | 说明 |
|------|------|------|
| `mode` | `"git"\|"branch"` | **必填**。`git` = 工作树差异；`branch` = 与默认分支的差异 |
| `context` | `number?` | diff 上下文行数（≥0） |

**`Vcs.FileStatus`**（`project/vcs.ts:263-268`）：`{ file, additions, deletions, status: "added"\|"deleted"\|"modified" }`

**`Vcs.FileDiff`**（`project/vcs.ts:251-260`）：`{ file, patch?, additions, deletions, status? }`
> `patch` 在当前实现中总是填充，但 schema 标记为 optional 以兼容未来可能省略 patch 的代码路径（vcs.ts:253-255 注释）。

**端点 7 错误**：`ApiVcsApplyError`（HTTP 400），`{ message, reason: "non-git"\|"not-clean" }`

---

#### 8-12. 元信息查询端点组（无参数，GET）

| # | 路径 | 用途 | 返回 |
|---|------|------|------|
| 8 | `GET /command` | 列出所有可用命令 | `Command.Info[]` |
| 9 | `GET /agent` | 列出所有可用 AI agent | `Agent.Info[]` |
| 10 | `GET /skill` | 列出所有可用 skill | `Skill.Info[]` |
| 11 | `GET /lsp` | 获取 LSP 服务器状态 | `LSP.Status[]` |
| 12 | `GET /formatter` | 获取格式化器状态 | `Format.Status[]` |

这 5 个端点都是只读查询，直接委托给对应 Service 的 `list()` / `status()` / `all()` 方法。

---

## 4.7 跨项目控制平面（1 个）

> 路由：`groups/control-plane.ts` · Handler：`handlers/control-plane.ts`
> **注意**：注册在 `RootHttpApi`，无实例中间件

### `POST /experimental/control-plane/move-session` — 跨目录迁移会话

| 项目 | 内容 |
|------|------|
| **用途** | 将会话迁移到另一个项目目录，可选转移本地代码变更 |
| **Payload** | `MoveSessionPayload` |
| **返回** | `NoContent`（HTTP 204） |
| **错误** | `ApiMoveSessionError`（HTTP 400） |

**`MoveSession.Input`**（`core/control-plane/move-session.ts:20-24`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `sessionID` | `SessionID` | 待迁移的会话 ID |
| `destination` | `{ directory: AbsolutePath }` | 目标目录（绝对路径） |
| `moveChanges` | `boolean?` | 是否转移本地未提交变更（通过 git patch） |

**迁移流程**（`core/control-plane/move-session.ts:76-118`）：
1. 获取会话当前位置，若与目标相同则直接返回
2. 解析源/目标目录的项目，**校验属于同一项目**（`projectID` 必须匹配，否则 `DestinationProjectMismatchError`）
3. 若 `moveChanges` 且目录不同：
   - `git.patch(sourceDir)` 捕获源目录变更 → `CaptureChangesError`（失败时）
   - `git.applyPatch({ directory: destDir, patch })` 应用到目标 → `ApplyChangesError`（失败时）
4. 发布 `SessionEvent.Moved` 事件（含新 location 和 subdirectory）
5. 若有 patch：`git.softResetChanges(sourceDir)` 清理源目录 → `ResetSourceError`（失败时，含 cause）

**错误消息映射**（`handlers/control-plane.ts:30-36`）：

| 错误类型 | 消息 |
|----------|------|
| `SessionV2.NotFoundError` | `Session not found: <sessionID>` |
| `DestinationProjectMismatchError` | `Destination directory belongs to another project` |
| `ApplyChangesError` | `Unable to apply your changes in the destination directory. The files may conflict with existing changes.` |
| 其他 | `error.message` |

---

## 附录 A：通用查询参数

### WorkspaceRoutingQuery

几乎所有实例级端点都接受这两个可选 query 参数（`middleware/workspace-routing.ts:22-25`）：

| 参数 | 类型 | 说明 |
|------|------|------|
| `directory` | `string?` | 工作目录。缺省取 `x-opencode-directory` 头或 `process.cwd()` |
| `workspace` | `string?` | 工作区 ID。用于路由到远程工作区（通过 sync 连接代理） |

**工作区路由逻辑**（`workspace-routing.ts:148-186`）：
1. 若 `workspace` 指定了远程工作区 → 请求被**代理转发**到远程实例
2. 若 `workspace` 指定了本地工作区 → 使用该工作区的 `directory`
3. 若未指定 → 使用 session 的 directory 或 `directory` 参数

### 认证方式

所有受 `Authorization` 中间件保护的端点支持两种认证（`middleware/authorization.ts:73-83`）：

| 方式 | 格式 | 优先级 |
|------|------|--------|
| Query 参数 | `?auth_token=base64(user:pass)` | 高 |
| HTTP 头 | `Authorization: Basic base64(user:pass)` | 低 |

**PTY WebSocket 端点特殊**：使用 `PtyConnectAuthorization`，当 URL 含 `?ticket=` 参数时跳过 Basic Auth（由 PTY handler 自行验证票据）。

---

## 附录 B：错误类型速查

### PTY 专用错误

| 错误类 | HTTP | 关键字段 | 来源 |
|--------|------|----------|------|
| `PtyNotFoundError` | 404 | `ptyID`, `message` | errors.ts:152-159 |
| `PtyForbiddenError` | 403 | `message` | errors.ts:161-167 |

### 通用错误

| 错误类 | HTTP | 场景 |
|--------|------|------|
| `InvalidRequestError` | 400 | 请求参数校验失败 |
| `UnauthorizedError` | 401 | 认证失败 |
| `ForbiddenError` | 403 | 无权限 |
| `ConflictError` | 409 | 资源冲突（如 SessionBusy） |
| `ApiNotFoundError` | 404 | 通用未找到 |
| `UpstreamError` | 502 | 上游服务错误 |
| `ServiceUnavailableError` | 503 | 服务不可用（如 sync 断开） |
| `TimeoutError` | 504 | 操作超时 |
| `UnknownError` | 500 | 未知内部错误 |

### 功能组特定错误

| 错误类 | HTTP | 场景 |
|--------|------|------|
| `WorktreeApiError` | 400 | worktree 操作失败（7 种子类型） |
| `ApiVcsApplyError` | 400 | patch 应用失败（`non-git` / `not-clean`） |
| `ApiMoveSessionError` | 400 | 会话迁移失败 |

---

## 关键发现总结

1. **PTY WebSocket 票据的三重安全设计**：connect-token 端点要求 `x-opencode-ticket: 1` 头 + Basic Auth + CORS Origin 校验三重防护；票据绑定 `(ptyID, directory, workspaceID)` 三元组且 60 秒一次性消费，有效防止了票据被盗用或跨会话重放。

2. **TUI 控制的双通道架构揭示了设计演进**：10 个"快捷"端点（append-prompt、open-help 等）本质是 `publish` 端点的语法糖，都走 EventV2 事件总线（fire-and-forget）；而 `control/next` + `control/response` 构成的请求-响应队列是更晚加入的同步控制通道，但全局单例 AsyncQueue 缺少请求 ID 关联，在并发场景下存在响应错配风险。

3. **Sync 系统的事件溯源命名不一致**：`ReplayEvent.aggregateID`（驼峰，API 输入）与 `HistoryEvent.aggregate_id`（蛇形，SQLite 列映射）命名风格不统一，客户端需要做字段名映射。此外 `openThemes` 端点发布的 command 实际是 `session.list`（与 `openSessions` 相同），疑似源码遗留 bug。

---

*本文档基于 opencode 源码深度调研生成，涵盖 53 个端点的完整规格。如需查阅特定 schema 的完整定义，请参考各章节标注的源码路径。*
