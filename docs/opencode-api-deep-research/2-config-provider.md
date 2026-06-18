# OpenCode 配置 / Provider / 认证 / MCP / 全局接口深度分析

> 调研日期：2026-06-18
> 源码版本：opencode `packages/opencode` + `packages/core`
> 端点总数：**21 个**（4 个功能组）
> 源码根：`packages/opencode/src/server/routes/instance/httpapi/`

## 目录

- [概览](#概览)
- [2.1 Config 路由组（3 个）](#21-config-路由组3-个)
- [2.2 Provider 路由组（4 个）](#22-provider-路由组4-个)
- [2.3 MCP 路由组（8 个）](#23-mcp-路由组8-个)
- [2.4 Global 路由组（6 个）](#24-global-路由组6-个)
- [附录 A：核心 Schema 定义](#附录-a核心-schema-定义)
- [附录 B：错误类型速查](#附录-b错误类型速查)
- [关键发现](#关键发现)

---

## 概览

| 功能组 | 路由文件 | 端点数 | 路径前缀 | 中间件栈 |
|--------|----------|--------|----------|----------|
| Config | groups/config.ts | 3 | `/config` | Instance → Workspace → Authorization |
| Provider | groups/provider.ts | 4 | `/provider` | Instance → Workspace → Authorization |
| MCP | groups/mcp.ts | 8 | `/mcp` | Instance → Workspace → Authorization |
| Global | groups/global.ts | 6 | `/global` | **无**（注册在 RootHttpApi，不走实例中间件） |

### 三大核心机制

1. **配置两层结构** — 实例级 `/config`（针对单个项目目录）+ 全局 `/global/config`（跨项目共享），更新后均触发实例销毁以重新加载
2. **Provider 双来源合并** — 来自 `models.dev` 的内置 provider 与本地已认证 provider 合并，通过 `disabled_providers`/`enabled_providers` 过滤
3. **MCP OAuth 动态客户端注册** — 远程 MCP 支持 OAuth，包含动态注册、回调完成认证、票据缓存等完整流程

---

## 2.1 Config 路由组（3 个）

> 路由：`groups/config.ts` · Handler：`handlers/config.ts`
> 核心：`@/config/config` 的 `Config.Service`

### 端点详解

---

#### 1. `GET /config` — 获取实例配置

| 项目 | 内容 |
|------|------|
| **用途** | 获取当前实例（项目目录）的 OpenCode 配置 |
| **Query** | `WorkspaceRoutingQuery`（`directory?`, `workspace?`） |
| **返回** | `ConfigV1.Info`（完整配置对象） |
| **认证** | Basic Auth（通过 Authorization 中间件） |
| **原理** | `configSvc.get()` 返回当前实例的配置（合并了全局配置 + 项目级 `opencode.json`） |

---

#### 2. `PATCH /config` — 更新实例配置

| 项目 | 内容 |
|------|------|
| **用途** | 更新当前实例的配置 |
| **Query** | `WorkspaceRoutingQuery` |
| **Payload** | `ConfigV1.Info`（完整配置对象） |
| **返回** | `ConfigV1.Info`（原样返回 payload） |
| **错误** | `400 BadRequest`（配置无效） |

**关键行为**（`handlers/config.ts:18-22`）：
1. `configSvc.update(ctx.payload)` 写入项目级配置文件（`opencode.json`）
2. **`markInstanceForDisposal()` 标记当前实例待销毁** —— 配置变更需要重启实例才生效
3. 直接返回 `ctx.payload`（**不重新读取磁盘**，可能不反映文件中的格式化/规范化结果）

> **注意**：此端点采用**全量替换**语义，未提供的字段会被清除。客户端必须先 GET 当前配置，修改后再 PATCH。

---

#### 3. `GET /config/providers` — 列出配置中的 Provider

| 项目 | 内容 |
|------|------|
| **用途** | 列出配置中定义的 provider（仅来自配置，不含 models.dev 内置） |
| **Query** | `WorkspaceRoutingQuery` |
| **返回** | `Provider.ConfigProvidersResult` |

**返回结构**（`provider.ts:1041-1045`）：

```typescript
{
  providers: Provider.Info[],          // 配置中定义的 provider 列表
  default: Record<string, string>      // providerID → 默认 modelID 映射
}
```

**与 `/provider` 端点的区别**：
- 此端点（`/config/providers`）只返回**配置文件中显式定义**的 provider，经过 `Provider.toPublicInfo()` 序列化
- `/provider`（见 2.2）返回**所有可用 provider**，合并 models.dev 数据 + 已连接 provider，并标记连接状态

---

## 2.2 Provider 路由组（4 个）

> 路由：`groups/provider.ts` · Handler：`handlers/provider.ts`
> 核心：`@/provider/provider` 的 `Provider.Service` + `@/provider/auth` 的 `ProviderAuth.Service`

### 端点详解

---

#### 1. `GET /provider` — 列出所有 Provider（含连接状态）

| 项目 | 内容 |
|------|------|
| **用途** | 获取所有可用的 AI provider，包括 models.dev 内置和已连接的 |
| **Query** | `WorkspaceRoutingQuery` |
| **返回** | `Provider.ListResult` |

**返回结构**（`provider.ts:1034-1039`）：

```typescript
{
  all: Provider.Info[],                // 所有可用 provider（内置 + 已连接）
  default: Record<string, string>,     // 每个 provider 的默认模型 ID
  connected: string[]                  // 已连接（已认证）的 provider ID 列表
}
```

**数据来源与过滤逻辑**（`handlers/provider.ts:40-58`）：
1. `cfg.get()` 获取配置，读取 `disabled_providers` 和 `enabled_providers`
2. `ModelsDev.Service.use((s) => s.get())` 获取 models.dev 全量 provider
3. **过滤**：
   - 若 `enabled_providers` 存在：仅保留 `enabled_providers` 中的 provider
   - 排除 `disabled_providers` 中的 provider
4. `provider.list()` 获取本地已认证 provider
5. 合并：`mapValues(filtered, fromModelsDev)` + `connected`（已连接覆盖同名）
6. 转换为 `Provider.toPublicInfo()` 后返回

---

#### 2. `GET /provider/auth` — 获取所有 Provider 的认证方法

| 项目 | 内容 |
|------|------|
| **用途** | 查询每个 provider 支持的认证方式（OAuth/API Key） |
| **Query** | `WorkspaceRoutingQuery` |
| **返回** | `ProviderAuth.Methods`（`Record<providerID, Method[]>`） |
| **原理** | `svc.methods()` 收集所有 provider 插件声明的认证方法 |

**`ProviderAuth.Method` 结构**（`auth.ts:40-44`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `type` | `"oauth" \| "api"` | 认证类型 |
| `label` | `string` | 显示标签 |
| `prompts?` | `Prompt[]` | 用户需要填写的字段（文本/选择） |

**Prompt 结构**（联合类型，按 `type` 判别）：
- **TextPrompt**：`{ type: "text", key, message, placeholder?, when? }`
- **SelectPrompt**：`{ type: "select", key, message, options: SelectOption[], when? }`

**条件显示**（`When`）：`{ key, op: "eq"|"neq", value }` —— 根据 provider 已有状态决定是否显示该 prompt

---

#### 3. `POST /provider/:providerID/oauth/authorize` — 启动 OAuth 认证

| 项目 | 内容 |
|------|------|
| **用途** | 启动指定 provider 的 OAuth 认证流程 |
| **Params** | `providerID: ProviderV2.ID` |
| **Query** | `WorkspaceRoutingQuery` |
| **Payload** | `ProviderAuth.AuthorizeInput` |
| **返回** | `ProviderAuth.Authorization \| undefined`（认证 URL 和方法） |
| **错误** | `ProviderAuthApiError`（400） |

**Payload 字段**（`auth.ts:55-59`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `method` | `Finite`（整数） | 认证方法的索引（来自 `Methods[providerID][index]`） |
| `inputs?` | `Record<string, string>` | 用户填写的 prompt 答案 |

**返回结构**（`Authorization` 类，`auth.ts:49-53`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `url` | `string` | 浏览器需要访问的认证 URL |
| `method` | `"auto" \| "code"` | 认证方式（auto=自动回调；code=手动粘贴授权码） |
| `instructions` | `string` | 给用户的指引文本 |

**⚠️ Raw Handler 行为**（`handlers/provider.ts:78-91`）：
- 使用 `handleRaw` 手动解析 body，便于处理空结果
- 当 `authorize()` 解析为 `undefined`（如无需进一步重定向），返回 JSON `null`（非空 body），保证客户端可 `.json()` 解析
- 错误映射到 `ProviderAuthApiError`（400）

---

#### 4. `POST /provider/:providerID/oauth/callback` — 完成 OAuth 认证

| 项目 | 内容 |
|------|------|
| **用途** | 处理 OAuth 回调，使用授权码换取 token |
| **Params** | `providerID` |
| **Query** | `WorkspaceRoutingQuery` |
| **Payload** | `ProviderAuth.CallbackInput` |
| **返回** | `boolean`（恒 `true`） |
| **错误** | `ProviderAuthApiError`（400） |

**Payload 字段**（`auth.ts:61-65`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `method` | `Finite` | 认证方法索引（必须与 authorize 时一致） |
| `code?` | `string` | OAuth 授权码（`code` method 时必填；`auto` method 时缺省） |

**错误子类型**（`groups/provider.ts:14-19`）：

| name | 触发场景 |
|------|---------|
| `ProviderAuthOauthMissing` | provider 不支持 OAuth |
| `ProviderAuthOauthCodeMissing` | `code` method 但未提供 code |
| `ProviderAuthOauthCallbackFailed` | 回调交换 token 失败 |
| `ProviderAuthValidationFailed` | 输入字段校验失败（含 `field` + `message`） |
| `BadRequest` | 兜底错误 |

---

## 2.3 MCP 路由组（8 个）

> 路由：`groups/mcp.ts` · Handler：`handlers/mcp.ts`
> 核心：`@/mcp` 的 `MCP.Service`
> 路径常量：`McpPaths`（`groups/mcp.ts:32-39`）

### MCP 核心数据模型

**`MCP.Status`**（`mcp/index.ts:75-99`）— 联合类型，5 种状态判别：

| status | 额外字段 | 说明 |
|--------|---------|------|
| `connected` | — | 已连接成功 |
| `disabled` | — | 配置中禁用 |
| `failed` | `error: string` | 连接失败 |
| `needs_auth` | — | 需要完成 OAuth 认证 |
| `needs_client_registration` | `error: string` | 需要动态客户端注册（RFC 7591） |

**`AddPayload`**（`groups/mcp.ts:11-14`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `name` | `string` | MCP 服务器名称 |
| `config` | `ConfigMCPV1.Info` | 配置（local/remote 联合，见附录 A） |

### 端点详解

---

#### 1. `GET /mcp` — 获取 MCP 服务器状态

| 项目 | 内容 |
|------|------|
| **用途** | 查询所有 MCP 服务器的当前状态 |
| **Query** | `WorkspaceRoutingQuery` |
| **返回** | `Record<string, MCP.Status>` —— key 为 MCP 名称 |
| **原理** | `mcp.status()` 返回所有已注册 MCP 服务器的实时状态 |

---

#### 2. `POST /mcp` — 添加 MCP 服务器

| 项目 | 内容 |
|------|------|
| **用途** | 动态添加新的 MCP 服务器到系统 |
| **Query** | `WorkspaceRoutingQuery` |
| **Payload** | `AddPayload` |
| **返回** | `StatusMap`（`Record<string, MCP.Status>`，仅含新添加的服务器） |
| **错误** | `400 BadRequest`（payload 校验失败或状态解码失败） |

**关键行为**（`handlers/mcp.ts:16-21`）：
1. `mcp.add(name, config)` 返回 `{ status }` 结果
2. **特殊处理**：若 `result` 自身就是 status 对象（含 `status` 字段），包装为 `{ [name]: result }`；否则假设 result 已是 map，直接使用
3. 通过 `Schema.decodeUnknownEffect(StatusMap)` 解码，失败映射到 `400 BadRequest`

---

#### 3. `POST /mcp/:name/auth` — 启动 MCP OAuth 认证

| 项目 | 内容 |
|------|------|
| **用途** | 启动指定 MCP 服务器的 OAuth 认证流程 |
| **Params** | `name: string` |
| **Query** | `WorkspaceRoutingQuery` |
| **返回** | `AuthStartResponse` |
| **错误** | `UnsupportedOAuthError`（400）、`McpServerNotFoundError`（404） |

**返回字段**（`groups/mcp.ts:17-20`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `authorizationUrl` | `string` | 浏览器需要访问的授权 URL |
| `oauthState` | `string` | OAuth state 参数，用于回调验证 |

**前置校验**（`handlers/mcp.ts:23-34`）：
1. `mcp.supportsOAuth(name)` 检查服务器是否支持 OAuth，不支持 → `UnsupportedOAuthError`
2. `mcp.startAuth(name)` 启动认证流程
3. `MCP.NotFoundError` 映射为 `McpServerNotFoundError`

---

#### 4. `POST /mcp/:name/auth/callback` — 完成 MCP OAuth 认证

| 项目 | 内容 |
|------|------|
| **用途** | 使用授权码完成 MCP OAuth 认证 |
| **Params** | `name` |
| **Query** | `WorkspaceRoutingQuery` |
| **Payload** | `AuthCallbackPayload` |
| **返回** | `MCP.Status`（认证完成后的新状态，通常为 `connected`） |
| **错误** | `400 BadRequest`、`McpServerNotFoundError`（404） |

**Payload**（`groups/mcp.ts:21-23`）：`{ code: string }` —— OAuth 授权码

**原理**：`mcp.finishAuth(name, code)` 使用持久化的 transport（`pendingOAuthTransports` Map）完成 token 交换。

---

#### 5. `POST /mcp/:name/auth/authenticate` — 一键完成 MCP OAuth 认证

| 项目 | 内容 |
|------|------|
| **用途** | 启动 OAuth 流程并**阻塞等待回调**完成（自动打开浏览器） |
| **Params** | `name` |
| **Query** | `WorkspaceRoutingQuery` |
| **返回** | `MCP.Status`（认证完成后的状态） |
| **错误** | `UnsupportedOAuthError`、`McpServerNotFoundError` |

**与 `/auth` 的区别**：
- `/auth`：返回 `authorizationUrl`，客户端**自行**引导用户访问 URL，拿到 code 后调用 `/auth/callback`
- `/auth/authenticate`：服务端**自动打开浏览器**，并**阻塞**直到 OAuth 回调完成，直接返回最终状态

**适用场景**：本地 TUI/桌面客户端，希望用户无需手动复制 code；不适用于无头/远程场景（无法打开浏览器）。

---

#### 6. `DELETE /mcp/:name/auth` — 移除 MCP OAuth 凭据

| 项目 | 内容 |
|------|------|
| **用途** | 删除指定 MCP 服务器的 OAuth 凭据 |
| **Params** | `name` |
| **Query** | `WorkspaceRoutingQuery` |
| **返回** | `AuthRemoveResponse`（`{ success: true }`） |
| **错误** | `McpServerNotFoundError`（404） |

**前置校验**（`handlers/mcp.ts:64-73`）：
- 先 `mcp.status()` 检查 `name` 是否存在，不存在 → `McpServerNotFoundError`
- 然后 `mcp.removeAuth(name)` 删除凭据

---

#### 7. `POST /mcp/:name/connect` — 连接 MCP 服务器

| 项目 | 内容 |
|------|------|
| **用途** | 显式触发指定 MCP 服务器的连接 |
| **Params** | `name` |
| **Query** | `WorkspaceRoutingQuery` |
| **返回** | `boolean`（恒 `true`） |
| **错误** | `McpServerNotFoundError` |
| **原理** | `mcp.connect(name)` 启动连接流程；通常配置启用时会自动连接，此端点用于手动重连 |

---

#### 8. `POST /mcp/:name/disconnect` — 断开 MCP 服务器

| 项目 | 内容 |
|------|------|
| **用途** | 显式断开指定 MCP 服务器的连接 |
| **Params** | `name` |
| **Query** | `WorkspaceRoutingQuery` |
| **返回** | `boolean`（恒 `true`） |
| **错误** | `McpServerNotFoundError` |
| **原理** | `mcp.disconnect(name)` 关闭 transport 并清理状态 |

---

## 2.4 Global 路由组（6 个）

> 路由：`groups/global.ts` · Handler：`handlers/global.ts`
> **注意**：注册在 `RootHttpApi`（非实例级 InstanceHttpApi），**不经过** Instance/Workspace/Authorization 中间件
> 路径常量：`GlobalPaths`（`groups/global.ts:67-73`）

### 端点详解

---

#### 1. `GET /global/health` — 健康检查

| 项目 | 内容 |
|------|------|
| **用途** | 健康探针，用于负载均衡器/k8s 健康检查 |
| **返回** | `GlobalHealth` |
| **认证** | **无**（公开端点） |

**返回字段**（`groups/global.ts:11-14`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `healthy` | `true`（字面量） | 恒为 `true`（ unhealthy 时服务本身已不可达） |
| `version` | `string` | OpenCode 版本（`InstallationVersion` 常量） |

---

#### 2. `GET /global/event` — 全局事件 SSE 流

| 项目 | 内容 |
|------|------|
| **用途** | 订阅**跨实例/跨项目**的全局事件流（SSE） |
| **返回** | `text/event-stream` —— 长连接，事件格式见 [5-sse-events.md](./5-sse-events.md) |
| **认证** | **无**（公开端点） |
| **行为** | 使用 `handleRaw` 手动构造 SSE 流 |

**事件流内容**（`handlers/global.ts:33-66`）：
1. **首个事件**：`{ type: "server.connected", properties: {} }`
2. **后续事件**：所有发布到 `GlobalBus` 的事件（实例级事件会通过 `EventV2Bridge` 桥接到 GlobalBus）
3. **心跳**：每 10 秒发送 `{ type: "server.heartbeat", properties: {} }`

**SSE 编码**：
- Content-Type: `text/event-stream`
- Cache-Control: `no-cache, no-transform`
- X-Accel-Buffering: `no`（禁用 nginx 缓冲）
- X-Content-Type-Options: `nosniff`

**事件结构**（`GlobalEventSchema`，`groups/global.ts:36-50`）：

```typescript
{
  directory: string,                  // 实例目录
  project?: string,                   // 项目 ID
  workspace?: string,                 // 工作区 ID
  payload: {
    id: EventV2.ID,
    type: string,                     // 事件类型
    properties: unknown               // 事件数据
  }
}
```

**payload 特殊形式 — sync 事件**：
- 当事件类型在 registry 中标记为 `sync: true` 时，会额外发布一个 `payload.type = "sync"` 的事件，包裹原始事件和 `seq`/`aggregateID` 信息（用于跨工作区事件溯源同步）

---

#### 3. `GET /global/config` — 获取全局配置

| 项目 | 内容 |
|------|------|
| **用途** | 获取全局配置（位于 `~/.config/opencode/opencode.json`，跨所有项目） |
| **返回** | `ConfigV1.Info` |
| **认证** | **无** |
| **原理** | `config.getGlobal()` |

---

#### 4. `PATCH /global/config` — 更新全局配置

| 项目 | 内容 |
|------|------|
| **用途** | 更新全局配置 |
| **Payload** | `ConfigV1.Info` |
| **返回** | `ConfigV1.Info`（更新后的配置） |
| **错误** | `400 BadRequest` |
| **认证** | **无** |

**关键行为**（`handlers/global.ts:86-90`）：
1. `config.updateGlobal(ctx.payload)` 写入全局配置文件
2. 若 `result.changed === true`：通过 `EffectBridge` 异步触发 `disposeAllInstancesAndEmitGlobalDisposed({ swallowErrors: true })` —— **销毁所有实例**让配置生效
3. 返回 `result.info`

> **重要**：此端点会**销毁所有运行中的实例**，包括进行中的会话！客户端需要监听 `global.disposed` 事件并重新建立连接。

---

#### 5. `POST /global/dispose` — 销毁所有实例

| 项目 | 内容 |
|------|------|
| **用途** | 清理并销毁所有 OpenCode 实例，释放资源 |
| **返回** | `boolean`（恒 `true`） |
| **认证** | **无** |
| **原理** | 同步执行 `disposeAllInstancesAndEmitGlobalDisposed()`，阻塞直到完成 |

---

#### 6. `POST /global/upgrade` — 升级 OpenCode

| 项目 | 内容 |
|------|------|
| **用途** | 升级 OpenCode 到指定或最新版本 |
| **Payload** | `[NoContent, GlobalUpgradeInput]` —— **允许空 body** |
| **返回** | `GlobalUpgradeResult` |
| **错误** | `400 BadRequest` |
| **认证** | **无** |

**Payload**（`GlobalUpgradeInput`，`groups/global.ts:52-54`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `target?` | `string` | 目标版本号，缺省取最新 |

**返回结构**（`GlobalUpgradeResult` 联合）：

| 变体 | 字段 |
|------|------|
| 成功 | `{ success: true, version: string }` |
| 失败 | `{ success: false, error: string }` |

**⚠️ Raw Handler 行为**（`handlers/global.ts:129-146`）：
- 使用 `handleRaw` 手动解析 body，支持空 body（升级到最新版）
- 空 body 或非法 JSON → `{ success: false, error: "Invalid request body" }`（400）
- 升级成功后**发布全局事件** `installation.updated`（携带新版本号）

**失败场景**：
- `installation.method() === "unknown"`：无法识别的安装方式（如手动编译），返回 `{ success: false, error: "Unknown installation method" }`
- 升级过程抛错：错误消息回传到 `error` 字段

---

## 附录 A：核心 Schema 定义

### ConfigV1.Info（配置对象）

定义位置：`packages/core/src/v1/config/config.ts:32-184`

完整配置对象，所有字段**可选**。关键字段：

| 字段 | 类型 | 说明 |
|------|------|------|
| `$schema` | `string?` | JSON schema 引用 |
| `shell` | `string?` | 默认 shell |
| `logLevel` | `"DEBUG"|"INFO"|"WARN"|"ERROR"?` | 日志级别 |
| `server` | `ConfigServerV1.Server?` | 服务器配置 |
| `model` | `string?` | 默认模型（`provider/model` 格式） |
| `small_model` | `string?` | 小模型（标题生成等） |
| `default_agent` | `string?` | 默认 agent（缺省 `build`） |
| `username` | `string?` | 显示用户名 |
| `agent` | `StructWithRest?` | agent 配置（`build`/`plan`/`general`/`explore`/`title`/`summary`/`compaction` + 自定义） |
| `provider` | `Record<string, ConfigProviderV1.Info>?` | provider 配置覆盖 |
| `mcp` | `Record<string, ConfigMCPV1.Info \| { enabled: boolean }>?` | MCP 服务器配置 |
| `disabled_providers` | `string[]?` | 禁用的 provider |
| `enabled_providers` | `string[]?` | 仅启用的 provider（白名单） |
| `lsp` | `ConfigLSPV1.Info?` | LSP 配置 |
| `formatter` | `ConfigFormatterV1.Info?` | 格式化器配置 |
| `permission` | `ConfigPermissionV1.Info?` | 权限规则 |
| `instructions` | `string[]?` | 额外指令文件 |
| `compaction` | `{ auto?, prune?, tail_turns?, preserve_recent_tokens?, reserved? }?` | 压缩配置 |
| `experimental` | `{ ... }?` | 实验性选项（`batch_tool`、`openTelemetry`、`mcp_timeout` 等） |

### ConfigMCPV1.Info（MCP 配置）

定义位置：`packages/core/src/v1/config/mcp.ts:6-60`

联合类型，按 `type` 判别：

**Local MCP**（`type: "local"`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `type` | `"local"` | 类型判别 |
| `command` | `string[]` | 命令 + 参数 |
| `environment?` | `Record<string, string>` | 环境变量 |
| `enabled?` | `boolean` | 启用/禁用 |
| `timeout?` | `PositiveInt` | 请求超时（ms，默认 5000） |

**Remote MCP**（`type: "remote"`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `type` | `"remote"` | 类型判别 |
| `url` | `string` | 远程 MCP 服务器 URL |
| `enabled?` | `boolean` | 启用/禁用 |
| `headers?` | `Record<string, string>` | 请求头 |
| `oauth?` | `OAuth \| false` | OAuth 配置（false 禁用自动检测） |
| `timeout?` | `PositiveInt` | 请求超时（ms） |

**McpOAuthConfig**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `clientId?` | `string` | 客户端 ID（缺省触发动态注册 RFC 7591） |
| `clientSecret?` | `string` | 客户端密钥 |
| `scope?` | `string` | OAuth scope |
| `callbackPort?` | `integer 1-65535` | 回调端口（默认 19876） |
| `redirectUri?` | `string` | 完整回调 URI（覆盖 callbackPort） |

### Provider.Info（Provider 信息）

定义位置：`packages/opencode/src/provider/provider.ts:1021-1030`

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | `ProviderV2.ID` | provider ID |
| `name` | `string` | 显示名 |
| `source` | `"env" \| "config" \| "custom" \| "api"` | 来源 |
| `env` | `string[]` | 需要的环境变量（如 `ANTHROPIC_API_KEY`） |
| `key?` | `string` | API key（已脱敏后的占位） |
| `options` | `Record<string, any>` | provider 选项 |
| `models` | `Record<string, Model>` | 模型映射 |

### Provider.Model（模型信息）

定义位置：`packages/opencode/src/provider/provider.ts`（约 1000-1019 行）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | `string` | 模型 ID |
| `name` | `string` | 显示名 |
| `toolCallType` | `string` | 工具调用类型 |
| `attachment` | `boolean` | 是否支持附件 |
| `reasoning` | `boolean` | 是否支持 reasoning |
| `cost` | `ProviderCost` | 成本信息 |
| `limit` | `ProviderLimit` | 上下文/输出限制 |
| `status` | `ModelStatus` | 状态（`stable`/`beta`/`deprecated`） |
| `release_date` | `string` | 发布日期 |

---

## 附录 B：错误类型速查

### Config 组

| 错误类 | HTTP | 场景 |
|--------|------|------|
| `HttpApiError.BadRequest` | 400 | 配置无效 |

### Provider 组

`ProviderAuthApiError`（统一类，HTTP 400），通过 `name` 区分子类型（`groups/provider.ts:14-32`）：

| name | data 关键字段 | 场景 |
|------|--------------|------|
| `ProviderAuthOauthMissing` | `providerID` | provider 不支持 OAuth |
| `ProviderAuthOauthCodeMissing` | `providerID` | 缺少授权码 |
| `ProviderAuthOauthCallbackFailed` | — | token 交换失败 |
| `ProviderAuthValidationFailed` | `field`, `message` | 输入校验失败 |
| `BadRequest` | — | 兜底错误 |

### MCP 组

| 错误类 | HTTP | 场景 |
|--------|------|------|
| `UnsupportedOAuthError` | 400 | MCP 不支持 OAuth（含 `error` 字段） |
| `McpServerNotFoundError` | 404 | MCP 服务器不存在（含 `name`, `message`） |
| `HttpApiError.BadRequest` | 400 | payload 校验失败或状态解码失败 |

### Global 组

| 错误类 | HTTP | 场景 |
|--------|------|------|
| `HttpApiError.BadRequest` | 400 | 配置无效 / 升级失败 |

---

## 关键发现

### 🔴 发现 1：配置更新必然销毁实例

`PATCH /config` 和 `PATCH /global/config` 都会触发实例销毁（前者销毁当前实例，后者销毁所有实例）。客户端必须：
1. 监听 `server.instance.disposed` / `global.disposed` 事件
2. 销毁后重新建立连接（可能需要重新创建实例）

**反模式**：在用户编辑配置时频繁 PATCH。应该让用户在 UI 中编辑，确认后再一次性 PATCH。

---

### 🔴 发现 2：`PATCH /config` 不重新读取磁盘

```typescript
const update = Effect.fn(function* (ctx) {
  yield* configSvc.update(ctx.payload)   // 写入文件
  yield* markInstanceForDisposal(...)    // 标记销毁
  return ctx.payload                     // ⚠️ 返回的是输入，不是磁盘内容
})
```

返回的是用户传入的 payload，而非文件实际写入后的内容（可能经过 JSON 规范化、字段补全等）。如果客户端依赖返回值显示配置，可能与下次 GET 不一致。

---

### 🟡 发现 3：`/config/providers` 与 `/provider` 的语义差异

| 端点 | 数据来源 | 用途 |
|------|---------|------|
| `GET /config/providers` | 仅配置文件 + 通过 `toPublicInfo` 序列化 | 查看用户自定义的 provider 配置 |
| `GET /provider` | models.dev + 已连接 provider，经过 disabled/enabled 过滤 | 选择可用 provider 创建会话 |

**客户端建议**：UI 显示 provider 列表用 `/provider`；编辑 provider 配置用 `/config/providers`（或直接读 `/config` 中的 `provider` 字段）。

---

### 🟡 发现 4：OAuth `authorize` 返回 `null` 而非空对象

```typescript
return HttpServerResponse.jsonUnsafe(result ?? null)
```

当 `authorize()` 返回 `undefined`（如已经认证、或无需进一步重定向），HTTP 响应体是 JSON `null`。客户端如果直接访问 `result.url` 会抛错。**应该先检查 `result === null`**。

---

### 🟡 发现 5：MCP `POST /mcp` 的返回结构存在两种形式

```typescript
"status" in result ? { [ctx.payload.name]: result } : result
```

`mcp.add()` 的返回可能是单个 status 对象（含 `status` 字段）或已经是 status map。这种"鸭子类型"判断让客户端难以预测返回结构。**实际上由于 schema 强制解码为 `StatusMap`（`Record<string, Status>`），最终返回始终是 map**，但内部代码暗示 add 实现可能返回非预期结构。

---

### 🟡 发现 6：Global 组端点全部无认证

`GlobalApi` 注册在 `RootHttpApi`，**不经过 Authorization 中间件**。意味着：
- `POST /global/dispose` 可被任意客户端调用（销毁所有实例）
- `POST /global/upgrade` 可触发版本升级
- `PATCH /global/config` 可修改全局配置

**安全建议**：生产环境应通过反向代理添加认证层，或限制监听地址（仅 localhost）。

---

### 🟢 发现 7：MCP OAuth 的双模式认证流程

| 端点 | 模式 | 适用场景 |
|------|------|---------|
| `/auth` + `/auth/callback` | 两步式（先获取 URL，再交回 code） | Web/远程客户端，用户在另一设备完成认证 |
| `/auth/authenticate` | 一键式（服务端自动开浏览器并等待） | 本地 TUI/桌面客户端 |

注意 `/auth/authenticate` **阻塞**等待回调，若浏览器无法打开（如远程服务器）会一直挂起。

---

*本文档基于 opencode 源码深度调研生成，涵盖 21 个端点的完整规格。如需查阅特定 schema 的完整定义，请参考各章节标注的源码路径。*
