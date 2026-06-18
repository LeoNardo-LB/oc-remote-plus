# OpenCode 文件 / 项目 / 工作区 / 引用接口深度分析

> 调研日期：2026-06-18
> 源码版本：opencode `packages/opencode` + `packages/core`
> 端点总数：**22 个**（5 个功能组）
> 源码根：`packages/opencode/src/server/routes/instance/httpapi/`

## 目录

- [概览](#概览)
- [3.1 File 路由组（6 个）](#31-file-路由组6-个)
- [3.2 Project 路由组（5 个）](#32-project-路由组5-个)
- [3.3 ProjectCopy 路由组（3 个）](#33-projectcopy-路由组3-个)
- [3.4 Workspace 路由组（7 个）](#34-workspace-路由组7-个)
- [3.5 Reference 路由组（1 个）](#35-reference-路由组1-个)
- [附录 A：核心 Schema 定义](#附录-a核心-schema-定义)
- [附录 B：错误类型速查](#附录-b错误类型速查)
- [关键发现](#关键发现)

---

## 概览

| 功能组 | 路由文件 | 端点数 | 路径前缀 | 中间件栈 |
|--------|----------|--------|----------|----------|
| File | groups/file.ts | 6 | `/find`, `/file` | Instance → Workspace → Authorization |
| Project | groups/project.ts | 5 | `/project` | Instance → Workspace → Authorization |
| ProjectCopy | groups/project-copy.ts | 3 | `/experimental/project/:projectID/copy` | Instance → Workspace → Authorization |
| Workspace | groups/workspace.ts | 7 | `/experimental/workspace` | Instance → Workspace → Authorization |
| Reference | groups/reference.ts | 1 | `/reference` | Instance → Workspace → Authorization |

### 三大核心机制

1. **文件搜索三层引擎** — ripgrep（文本搜索）+ fff（frecency 模糊文件搜索）+ LSP（符号搜索），按可用性自动降级
2. **项目实例化机制** — 项目通过 `Project.Service` 管理元数据，`initGit` 触发实例 reload（因 vcs/worktree 改变需要重新初始化）
3. **Workspace 跨目录协作** — 工作区是项目的"分支视图"，通过 adapter 机制支持远程/本地工作区，session warp 用于在工作区间迁移会话

---

## 3.1 File 路由组（6 个）

> 路由：`groups/file.ts` · Handler：`handlers/file.ts`
> 核心：`@opencode-ai/core/filesystem` + `ripgrep` + `Search` + `LSP`
> 路径常量：`FilePaths`（`groups/file.ts:82-89`）

### 端点详解

---

#### 1. `GET /find` — 文本搜索（ripgrep）

| 项目 | 内容 |
|------|------|
| **用途** | 使用 ripgrep 在项目文件中搜索文本模式 |
| **Query** | `FindTextQuery` |
| **返回** | `Ripgrep.SearchMatch[]`（最多 10 条） |
| **原理** | `ripgrep.search({ cwd, pattern, limit: 10 })` 调用本地 ripgrep 二进制 |

**Query 参数**（`groups/file.ts:21-24`）：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `pattern` | `string` | 是 | ripgrep 兼容的正则/字面量模式 |
| `directory`/`workspace` | 公共参数 | 否 | 工作区路由 |

**关键行为**：
- **硬编码 limit: 10**（`handlers/file.ts:27`）—— 客户端无法调整结果数量
- 使用 `Effect.orDie` —— 搜索失败转化为 defect（500 错误，非可恢复错误）

---

#### 2. `GET /find/file` — 模糊文件搜索

| 项目 | 内容 |
|------|------|
| **用途** | 通过文件名/路径模糊匹配查找文件 |
| **Query** | `FindFileQuery` |
| **返回** | `string[]`（文件路径列表） |
| **原理** | 优先使用 fff（frecency + 模糊排序），不可用时降级到 ripgrep 的 `FileSystem.find` |

**Query 参数**（`groups/file.ts:26-34`）：

| 参数 | 类型 | 必填 | 默认 | 说明 |
|------|------|------|------|------|
| `query` | `string` | 是 | — | 搜索关键词 |
| `dirs` | `"true" \| "false"` | 否 | — | 是否包含目录（`"false"` 等价于 `type=file`） |
| `type` | `"file" \| "directory"` | 否 | — | 限定类型（覆盖 `dirs`） |
| `limit` | `integer 1-200` | 否 | `10` | 结果数量上限 |
| `directory`/`workspace` | 公共参数 | 否 | — | 工作区路由 |

**`kind` 解析逻辑**（`handlers/file.ts:36`）：
- `type` 优先；否则 `dirs === "false"` → `"file"`；否则 `"all"`

**降级策略**（`handlers/file.ts:40-71`）：
1. `search.file({ cwd, query, limit, kind })` —— fff 引擎
2. fff 返回 `undefined`（不可用）→ 调用 `FileSystem.find` 作为 fallback
3. 两者都记录日志（`engine`, `results`, `duration`）

---

#### 3. `GET /find/symbol` — 符号搜索（LSP）

| 项目 | 内容 |
|------|------|
| **用途** | 通过 LSP workspace symbols 查找函数/类/变量 |
| **Query** | `FindSymbolQuery`（`{ query }` + 公共参数） |
| **返回** | `LSP.Symbol[]` |
| **原理** | LSP workspace/symbol 请求 |

**⚠️ 当前实现为空数组**（`handlers/file.ts:74-76`）：

```typescript
const findSymbol = Effect.fn(function* () {
  return []   // 未实现
})
```

**建议**：暂不依赖此端点；如需符号搜索，直接调用 LSP 或通过 PTY 使用 ctags 等。

---

#### 4. `GET /file` — 列出目录内容

| 项目 | 内容 |
|------|------|
| **用途** | 列出指定路径下的文件和目录 |
| **Query** | `FileQuery` |
| **返回** | `LegacyEntry[]`（注意：命名为 `FileNode`，identifier: `"FileNode"`） |

**Query 参数**（`groups/file.ts:16-19`）：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `path` | `string` | 是 | 相对路径（相对于实例 directory） |
| `directory`/`workspace` | 公共参数 | 否 | 工作区路由 |

**返回字段**（`LegacyEntry`，`groups/file.ts:41-47`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `name` | `string` | 文件/目录名（basename） |
| `path` | `string` | 相对路径 |
| `absolute` | `string` | 绝对路径（`directory + path`） |
| `type` | `"file" \| "directory"` | 类型 |
| `ignored` | `boolean` | 是否被 gitignore 忽略 |

**原理**：`fs.list({ path: RelativePath })` 后映射每个条目，`fs.isIgnored(path, type)` 判断忽略状态。

---

#### 5. `GET /file/content` — 读取文件内容

| 项目 | 内容 |
|------|------|
| **用途** | 读取文件内容（文本或 base64 二进制） |
| **Query** | `FileQuery`（同 `/file`） |
| **返回** | `LegacyContent`（identifier: `"FileContent"`） |

**返回字段**（`LegacyContent`，`groups/file.ts:49-73`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `type` | `"text" \| "binary"` | 内容类型 |
| `content` | `string` | 文本内容 或 base64 编码的二进制 |
| `diff?` | `string` | git diff（可选） |
| `patch?` | `Patch` | 结构化 patch（含 hunks） |
| `encoding?` | `"base64"` | 仅 binary 时出现 |
| `mimeType?` | `string` | 仅 binary 时出现 |

**安全行为**（`handlers/file.ts:97-110`）：
1. `path.resolve(directory, ctx.query.path)` 解析为绝对路径
2. **路径逃逸检查**：`FSUtil.contains(directory, file)` —— 若解析后的路径在 directory 之外 → `Effect.die(new Error("Path escapes the location"))`（500 错误）
3. **存在性检查**：`fs.existsSafe(file)` —— 不存在 → 返回 `{ type: "text", content: "" }`（空字符串，**非 404**）
4. 文本内容 `item.content.trim()`（去除首尾空白）

---

#### 6. `GET /file/status` — 获取文件变更状态

| 项目 | 内容 |
|------|------|
| **用途** | 获取项目中所有文件的 git 变更状态 |
| **Query** | `WorkspaceRoutingQuery`（无 `path` 参数） |
| **返回** | `LegacyStatus[]`（identifier: `"File"`） |

**⚠️ 当前实现为空数组**（`handlers/file.ts:113-115`）：

```typescript
const status = Effect.fn(function* () {
  return []   // 未实现
})
```

**建议**：使用 `GET /vcs/status`（见 4-terminal-control.md §4.6）替代，那里有完整实现。

---

## 3.2 Project 路由组（5 个）

> 路由：`groups/project.ts` · Handler：`handlers/project.ts`
> 核心：`@/project/project` 的 `Project.Service` + `ProjectV2.Service`

### Project 核心数据模型

**`Project.Info`** —— 项目主对象，关键字段（具体定义在 `@/project/project.ts`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | `ProjectV2.ID` | 项目 ID（`prj_` 前缀） |
| `name` | `string` | 项目显示名 |
| `icon?` | `string` | 图标（emoji 或 URL） |
| `directory` | `string` | 主目录绝对路径 |
| `worktree` | `string` | worktree 目录 |
| `vcs?` | `VcsInfo` | VCS 信息（branch, default_branch） |
| `commands?` | `Record<string, string>` | 自定义命令 |

### 端点详解

---

#### 1. `GET /project` — 列出所有项目

| 项目 | 内容 |
|------|------|
| **用途** | 获取所有曾经用 OpenCode 打开过的项目 |
| **Query** | `WorkspaceRoutingQuery` |
| **返回** | `Project.Info[]` |
| **原理** | `svc.list()` 从项目元数据数据库查询 |

---

#### 2. `GET /project/current` — 获取当前项目

| 项目 | 内容 |
|------|------|
| **用途** | 获取当前实例对应的项目信息 |
| **Query** | `WorkspaceRoutingQuery` |
| **返回** | `Project.Info` |
| **原理** | `(yield* InstanceState.context).project` —— 从实例上下文直接获取，无需查询 |

---

#### 3. `POST /project/git/init` — 初始化 Git 仓库

| 项目 | 内容 |
|------|------|
| **用途** | 为当前项目创建 git 仓库 |
| **Query** | `WorkspaceRoutingQuery` |
| **返回** | `Project.Info`（初始化后的项目信息，含 vcs 字段） |
| **原理** | `svc.initGit({ directory, project })` 执行 `git init` |

**关键行为**（`handlers/project.ts:23-34`）：
1. 执行 `initGit` 返回新的 `Project.Info`
2. **检测是否需要 reload**：比较 `id`、`vcs`、`worktree` 三个字段
3. 若任一变化 → `markInstanceForReload(ctx, { directory, worktree, project })`
4. 若无变化 → 直接返回（不 reload）

> **reload vs disposal**：`markInstanceForReload` 保留当前实例，更新其上下文；`markInstanceForDisposal` 销毁实例。initGit 后通常需要 reload 因为 worktree 信息变了。

---

#### 4. `PATCH /project/:projectID` — 更新项目

| 项目 | 内容 |
|------|------|
| **用途** | 更新项目元数据（名称、图标、命令） |
| **Params** | `projectID: ProjectV2.ID` |
| **Query** | `WorkspaceRoutingQuery` |
| **Payload** | `UpdatePayload`（所有字段可选） |
| **返回** | `Project.Info`（更新后） |
| **错误** | `400 BadRequest`、`ProjectNotFoundError`（404） |

**Payload 字段**（`groups/project.ts:12-16`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `name?` | `string` | 新名称 |
| `icon?` | `Project.Info.fields.icon` | 新图标 |
| `commands?` | `Project.Info.fields.commands` | 新命令（**全量替换**） |

**错误处理**：`Project.NotFoundError` 映射为 `ProjectNotFoundError`（404，含 `projectID` + `message`）。

---

#### 5. `GET /project/:projectID/directories` — 列出项目目录

| 项目 | 内容 |
|------|------|
| **用途** | 列出指定项目在本地已知的所有目录（含 worktree） |
| **Params** | `projectID` |
| **Query** | `WorkspaceRoutingQuery` |
| **返回** | `ProjectV2.Directories` |
| **原理** | `project.directories({ projectID })` 查询项目目录列表 |

---

## 3.3 ProjectCopy 路由组（3 个）

> 路由：`groups/project-copy.ts` · Handler：`handlers/project-copy.ts`
> 核心：`@opencode-ai/core/project/copy` 的 `ProjectCopy.Service`
> **实验性**：路径前缀 `/experimental/project/:projectID/copy`

### ProjectCopy 核心概念

**项目副本**是项目的物理拷贝，用于在不影响原项目的情况下进行实验/分支工作。通过 **strategy**（策略）机制支持不同的复制方式（如 git worktree、文件复制等）。

### 端点详解

---

#### 1. `POST /experimental/project/:projectID/copy` — 创建项目副本

| 项目 | 内容 |
|------|------|
| **用途** | 使用指定策略创建项目副本 |
| **Params** | `projectID: ProjectV2.ID` |
| **Query** | `CopyQuery`（仅 `workspace`） |
| **Payload** | `CreatePayload` |
| **返回** | `ProjectCopy.Copy`（创建的副本信息） |
| **错误** | `ApiProjectCopyError`（400，含 `forceRequired?`） |

**Payload 字段**（`groups/project-copy.ts:19-24`）：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `strategy` | `ProjectCopy.StrategyID` | 是 | 策略 ID（如 `git-worktree`） |
| `directory` | `string` | 是 | 目标目录绝对路径 |
| `name?` | `string` | 否 | 副本名称（缺省由 LLM 生成） |
| `context?` | `string` | 否 | 任务描述（用于 AI 生成名称） |

**AI 名称生成**（`handlers/project-copy.ts:48-95`）：
- 若 `name` 缺省但 `context` 提供：调用 `title` agent 使用 small model 生成 3-4 词名称
- LLM 调用失败或无可用模型 → fallback 到 `Slug.create()`（随机短 ID）
- 名称 slugify：转小写 + 非字母数字替换为 `-`

**错误子类型**（`handlers/project-copy.ts:147-156`）：

| Error 类 | message 模板 |
|----------|-------------|
| `SourceDirectoryNotFoundError` | `Project copy source not found: ${directory}` |
| `DestinationExistsError` | `Project copy destination already exists: ${directory}` |
| `DirectoryUnavailableError` | `Project copy directory unavailable: ${directory}` |
| `StrategyNotFoundError` | `Project copy strategy not found for: ${directory}` |

**`forceRequired` 字段**：当错误是 `Git.WorktreeError` 且 `forceRequired` 为 true 时，客户端可以传 `force: true` 强制删除（用于 remove 端点）。

---

#### 2. `DELETE /experimental/project/:projectID/copy` — 移除项目副本

| 项目 | 内容 |
|------|------|
| **用途** | 移除指定的项目副本 |
| **Params** | `projectID` |
| **Query** | `CopyQuery` |
| **Payload** | `RemovePayload`（**DELETE 带 body**） |
| **返回** | `HttpApiSchema.NoContent`（HTTP 204） |
| **错误** | `ApiProjectCopyError` |

**Payload 字段**（`groups/project-copy.ts:25-28`）：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `directory` | `string` | 是 | 待移除副本的目录 |
| `force?` | `boolean` | 否 | 强制移除（覆盖未提交变更） |

> **注意**：DELETE 请求带 body 在某些 HTTP 客户端中不常见。fetch API 支持，但某些代理可能剥离 body。

---

#### 3. `POST /experimental/project/:projectID/copy/refresh` — 刷新副本列表

| 项目 | 内容 |
|------|------|
| **用途** | 扫描本地，发现并注册未被系统知晓的项目副本 |
| **Params** | `projectID` |
| **Query** | `WorkspaceRoutingQuery` |
| **Payload** | `HttpApiSchema.NoContent`（**空 body 必需**） |
| **返回** | `HttpApiSchema.NoContent`（204） |
| **错误** | `ApiProjectCopyError` |
| **原理** | `service.refresh({ projectID })` 遍历所有策略，发现遗漏的副本 |

---

## 3.4 Workspace 路由组（7 个）

> 路由：`groups/workspace.ts` · Handler：`handlers/workspace.ts`
> 核心：`@/control-plane/workspace` 的 `Workspace.Service` + `listAdapters`
> **实验性**：路径前缀 `/experimental/workspace`
> 路径常量：`WorkspacePaths`（`groups/workspace.ts:40-47`）

### Workspace 核心概念

**工作区（Workspace）** 是项目下的并行工作单元，可以理解为项目的"分支"。一个项目可以有多个工作区，每个工作区有自己的目录、连接状态和会话历史。工作区通过 **adapter**（适配器）机制支持不同类型（本地目录、远程 SSH、Docker 等）。

### 端点详解

---

#### 1. `GET /experimental/workspace/adapter` — 列出工作区适配器

| 项目 | 内容 |
|------|------|
| **用途** | 列出当前项目可用的所有工作区适配器类型 |
| **Query** | `WorkspaceRoutingQuery` |
| **返回** | `WorkspaceAdapterEntry[]` |
| **原理** | `listAdapters(instance.project.id)` 同步函数 |

---

#### 2. `GET /experimental/workspace` — 列出工作区

| 项目 | 内容 |
|------|------|
| **用途** | 列出当前项目的所有工作区 |
| **Query** | `WorkspaceRoutingQuery` |
| **返回** | `Workspace.Info[]` |
| **原理** | `workspace.list(instance.project)` |

---

#### 3. `POST /experimental/workspace` — 创建工作区

| 项目 | 内容 |
|------|------|
| **用途** | 为当前项目创建新工作区 |
| **Query** | `WorkspaceRoutingQuery` |
| **Payload** | `CreatePayload` |
| **返回** | `Workspace.Info` |
| **错误** | `ApiWorkspaceCreateError`（400）、`HttpApiError.BadRequest` |

**Payload**（`groups/workspace.ts:13`）：`Workspace.CreateInput` 去除 `projectID`（自动从实例上下文获取）。

**错误处理**（`handlers/workspace.ts:33-48`）：
- **缺陷穿透**：插件错误以 defect 形式抛出（绕过 `Effect.mapError`），handler 通过 `Effect.catchCause` + 遍历 cause 找到真实 error 消息
- 失败 → `ApiWorkspaceCreateError`（含 `message`）

---

#### 4. `POST /experimental/workspace/sync-list` — 同步工作区列表

| 项目 | 内容 |
|------|------|
| **用途** | 注册 adapter 中存在但本地数据库中缺失的工作区 |
| **Query** | `WorkspaceRoutingQuery` |
| **返回** | `HttpApiSchema.NoContent`（204） |
| **原理** | `workspace.syncList(instance.project)` 遍历所有 adapter，注册遗漏的工作区 |

---

#### 5. `GET /experimental/workspace/status` — 获取工作区连接状态

| 项目 | 内容 |
|------|------|
| **用途** | 获取当前项目所有工作区的连接状态 |
| **Query** | `WorkspaceRoutingQuery` |
| **返回** | `Workspace.ConnectionStatus[]` |
| **原理** | `workspace.status()` 过滤为当前项目的工作区 |

**过滤逻辑**（`handlers/workspace.ts:55-58`）：
1. `workspace.list(project)` 获取当前项目的 workspace ID 集合
2. `workspace.status()` 返回所有 workspace 状态
3. 过滤：`ids.has(item.workspaceID)` —— 只返回当前项目的状态

---

#### 6. `DELETE /experimental/workspace/:id` — 移除工作区

| 项目 | 内容 |
|------|------|
| **用途** | 移除一个工作区 |
| **Params** | `id: Workspace.Info["id"]` |
| **Query** | `WorkspaceRoutingQuery` |
| **返回** | `Workspace.Info \| undefined`（被移除的工作区信息） |
| **错误** | `HttpApiError.BadRequest` |
| **原理** | `workspace.remove(id)` |

---

#### 7. `POST /experimental/workspace/warp` — 迁移会话到工作区

| 项目 | 内容 |
|------|------|
| **用途** | 将会话的同步历史迁移到目标工作区，或从工作区脱离到本地项目 |
| **Query** | `WorkspaceRoutingQuery` |
| **Payload** | `WarpPayload` |
| **返回** | `HttpApiSchema.NoContent`（204） |
| **错误** | `ApiWorkspaceWarpError`、`ApiVcsApplyError`、`ApiNotFoundError` |

**Payload 字段**（`groups/workspace.ts:14-18`）：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `id` | `WorkspaceID \| null` | 是 | 目标工作区 ID（`null` 表示脱离到本地项目） |
| `sessionID` | `SessionID` | 是 | 待迁移的会话 ID |
| `copyChanges` | `boolean` | 是 | 是否复制本地未提交变更（通过 git patch） |

**错误映射**（`handlers/workspace.ts:71-90`）：

| 内部错误 | API 错误 | HTTP |
|---------|---------|------|
| `Workspace.WorkspaceNotFoundError` | `ApiNotFoundError` | 404 |
| `Vcs.PatchApplyError` | `ApiVcsApplyError` | 400（含 `reason`） |
| 其他 | `ApiWorkspaceWarpError` | 400 |

---

## 3.5 Reference 路由组（1 个）

> 路由：`groups/reference.ts` · Handler：`handlers/reference.ts`
> 核心：`@/reference/reference` 的 `Reference.Service`

### Reference 核心概念

**引用（Reference）** 是用户在配置中定义的命名快捷方式，可以是本地路径或 git 仓库。在 OpenCode 中通过 `@alias` 或 `@alias/path` 引用，让 AI 快速访问常用代码/文档。

### 端点详解

---

#### 1. `GET /reference` — 列出配置的引用

| 项目 | 内容 |
|------|------|
| **用途** | 列出当前工作区已解析的配置引用 |
| **Query** | `WorkspaceRoutingQuery` |
| **返回** | `ReferenceDescriptor[]` |
| **原理** | `reference.list()` 解析配置中的 `reference` 字段 |

**返回字段**（`ReferenceDescriptor` 联合类型，按 `kind` 判别，`groups/reference.ts:8-27`）：

**Local 引用**（`kind: "local"`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `name` | `string` | 引用别名 |
| `kind` | `"local"` | 类型判别 |
| `path` | `string` | 本地绝对路径 |

**Git 引用**（`kind: "git"`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `name` | `string` | 引用别名 |
| `kind` | `"git"` | 类型判别 |
| `repository` | `string` | git 仓库 URL |
| `path` | `string` | 仓库内路径 |
| `branch?` | `string` | 分支（仅当配置或默认非 main 时出现） |

**Invalid 引用**（`kind: "invalid"`，解析失败）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `name` | `string` | 引用别名 |
| `kind` | `"invalid"` | 类型判别 |
| `repository?` | `string` | 仓库 URL（若可解析） |
| `message` | `string` | 错误信息（如"仓库不存在"、"路径无效"） |

**handler 特殊处理**（`handlers/reference.ts:13-22`）：
- 仅当 `branch !== undefined` 时序列化 `branch` 字段（避免输出 `branch: null`）
- 非 git 引用原样返回

---

## 附录 A：核心 Schema 定义

### Ripgrep.SearchMatch

来自 `@opencode-ai/core/filesystem/ripgrep`。典型结构：

```typescript
{
  path: { text: string },        // 文件路径（对象形式以兼容二进制路径）
  lines: { text: string },       // 匹配行内容
  line_number: number,
  absolute_offset: number,
  submatches: [
    { match: { text: string }, start: number, end: number }
  ]
}
```

### LSP.Symbol

来自 `@/lsp/lsp`。典型 LSP workspace symbol 结构：

```typescript
{
  name: string,
  kind: number,                  // LSP SymbolKind 枚举值
  location: {
    uri: string,
    range: { start: {...}, end: {...} }
  },
  containerName?: string
}
```

### Project.Info（部分字段）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | `ProjectV2.ID` | 项目 ID（`prj_` 前缀） |
| `name` | `string` | 显示名 |
| `icon?` | `string` | 图标 |
| `directory` | `string` | 主目录 |
| `worktree` | `string` | worktree 目录 |
| `vcs?` | `{ branch?, default_branch? }` | VCS 信息 |
| `commands?` | `Record<string, string>` | 自定义命令 |

### Workspace.Info

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | `WorkspaceID` | 工作区 ID |
| `projectID` | `ProjectV2.ID` | 所属项目 ID |
| `name` | `string` | 显示名 |
| `directory` | `string` | 工作区目录 |
| `adapter?` | `string` | 适配器类型 |
| `extra?` | `unknown` | 适配器特定数据 |

### Workspace.ConnectionStatus

| 字段 | 类型 | 说明 |
|------|------|------|
| `workspaceID` | `WorkspaceID` | 工作区 ID |
| `connected` | `boolean` | 是否已连接 |
| `error?` | `string` | 连接错误（如失败） |

### WorkspaceAdapterEntry

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | `string` | 适配器 ID（如 `local`、`ssh`、`docker`） |
| `name` | `string` | 显示名 |
| `available` | `boolean` | 当前是否可用 |

### ProjectCopy.Copy

| 字段 | 类型 | 说明 |
|------|------|------|
| `projectID` | `ProjectV2.ID` | 父项目 ID |
| `strategy` | `ProjectCopy.StrategyID` | 使用的策略 |
| `directory` | `string` | 副本目录 |
| `name` | `string` | 副本名 |
| `time.created` | `number` | 创建时间戳 |

---

## 附录 B：错误类型速查

### File 组

无专用错误类。失败转化为 defect（500）或返回空数组/空字符串。

### Project 组

| 错误类 | HTTP | 关键字段 | 来源 |
|--------|------|---------|------|
| `ProjectNotFoundError` | 404 | `projectID`, `message` | errors.ts |
| `HttpApiError.BadRequest` | 400 | — | 配置/参数错误 |

### ProjectCopy 组

`ApiProjectCopyError`（HTTP 400，统一类）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `message` | `string` | 错误描述（根据子类型动态生成） |
| `forceRequired?` | `boolean` | 是否可通过 `force: true` 覆盖（仅 `Git.WorktreeError` 时为 true） |

### Workspace 组

| 错误类 | HTTP | 关键字段 |
|--------|------|---------|
| `ApiWorkspaceCreateError` | 400 | `message` |
| `ApiWorkspaceWarpError` | 400 | `message` |
| `ApiVcsApplyError` | 400 | `message`, `reason: "non-git" \| "not-clean"` |
| `ApiNotFoundError` | 404 | — |
| `HttpApiError.BadRequest` | 400 | — |

### Reference 组

无专用错误类。

---

## 关键发现

### 🔴 发现 1：`/find/symbol` 和 `/file/status` 是桩实现

```typescript
const findSymbol = Effect.fn(function* () {
  return []   // 永远返回空数组
})
const status = Effect.fn(function* () {
  return []   // 永远返回空数组
})
```

这两个端点**没有实际实现**，永远返回空数组。客户端不应依赖它们的功能。替代方案：
- 符号搜索：直接调用 LSP 或通过 PTY 运行 ctags/grep
- 文件状态：使用 `GET /vcs/status`（见 4-terminal-control.md §4.6）

---

### 🔴 发现 2：`/file/content` 的路径逃逸保护是 500 而非 400

```typescript
if (!FSUtil.contains(directory, file))
  return yield* Effect.die(new Error("Path escapes the location"))
```

`Effect.die` 抛出 defect，映射为 HTTP 500（非 400）。客户端如果尝试读取 `../../etc/passwd` 会收到 500 而非 400，这可能误导错误处理逻辑。

**建议**：客户端应自行验证路径，避免触发服务端逃逸检查。

---

### 🟡 发现 3：`/find/file` 的双引擎策略和日志

fff（frecency）不可用时降级到 ripgrep 的 `FileSystem.find`。降级时**结果顺序和排名算法不同**：
- fff：基于访问频率 + 模糊匹配评分
- ripgrep：文件名字面匹配

客户端不应假设两次相同查询返回相同顺序（取决于 fff 是否可用）。日志中的 `engine` 字段可用于诊断。

---

### 🟡 发现 4：`POST /project/git/init` 触发实例 reload 而非 disposal

```typescript
if (next.id === ctx.project.id && next.vcs === ctx.project.vcs && next.worktree === ctx.project.worktree)
  return next   // 无变化，直接返回
yield* markInstanceForReload(ctx, {...})   // 有变化，reload
```

与 `PATCH /config` 的 disposal 不同，initGit 使用 **reload**：保留实例进程但更新上下文。这意味着：
- 进行中的会话**不会丢失**
- 但 LSP、文件监听等可能需要重新初始化

---

### 🟡 发现 5：ProjectCopy 的 AI 名称生成可能静默失败

```typescript
const name = ctx.payload.name ??
  (yield* generateName(ctx.payload.context).pipe(Effect.catch(() => Effect.succeed(Slug.create()))))
```

LLM 生成名称失败时静默 fallback 到随机 slug，**客户端无法感知**是否使用了 AI 名称。如果名称可读性重要，客户端应该：
1. 自行生成名称并显式传入 `name` 字段
2. 或检查返回的 `name` 是否像 slug（短随机字符串）来判断是否为 fallback

---

### 🟡 发现 6：DELETE `/experimental/project/:projectID/copy` 带 body

HTTP DELETE 请求通常不带 body，但此端点要求 `RemovePayload`（`directory` + `force?`）。这可能导致：
- 某些 HTTP 代理剥离 DELETE body → 服务端收到空 body → 400
- fetch API 支持 DELETE body，但需要显式设置

**客户端实现建议**：
```javascript
fetch(url, {
  method: "DELETE",
  body: JSON.stringify({ directory: "/path", force: false }),
  headers: { "Content-Type": "application/json" }
})
```

---

### 🟡 发现 7：Workspace warp 的 `id: null` 语义

```typescript
const WarpPayload = Schema.Struct({
  id: Schema.NullOr(Workspace.Info.fields.id),   // 允许 null
  sessionID: ...,
  copyChanges: ...,
})
```

`id: null` 不是错误，而是**显式的"脱离工作区"语义**：将 session 从任何工作区迁出，回归到本地项目。这种"nullable 而非 optional"的设计是为了区分"未提供"和"显式脱离"。

---

### 🟢 发现 8：Reference 的 git 引用 `branch` 字段是条件性序列化的

```typescript
...(item.branch !== undefined ? { branch: item.branch } : {}),
```

handler 显式地**仅当 branch 有值时**才输出该字段。这与 schema 定义（`Schema.optional`）一致，但意味着客户端不能用 `branch === undefined` 来区分"主分支"和"未指定"——两者都不出现 `branch` 字段。

---

*本文档基于 opencode 源码深度调研生成，涵盖 22 个端点的完整规格。如需查阅特定 schema 的完整定义，请参考各章节标注的源码路径。*
