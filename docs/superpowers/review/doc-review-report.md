# 文档审查报告 — Workspace File Viewer (Phase 1)

> 审查日期：2026-06-18
> 审查对象：
> - Spec: `docs/superpowers/specs/2026-06-18-workspace-file-viewer-design.md`
> - Plan: `docs/superpowers/plans/2026-06-18-workspace-phase1-foundation.md`
> 审查方法：doc-consistency-review 技能（9 维度并行 subagent 审查）
> 当前状态：**第 1 轮完成 — 9 维度全 FAIL，暂停修复待用户决策**

## 整体结论

**❌ 不通过**。9 个维度全部 FAIL，共发现 **93 个问题**（5 P0 + 55 P1 + 33 P2）。

文档存在系统性质量问题——不是局部笔误，而是**架构层面的文档-现实脱节**。核心问题是：plan 的 Critical Corrections 识别了架构错误（serverId 模式、VcsFileDiff、directory 路由），但这些修正**没有回写到对应的 Task 代码块**，导致执行者按 Task 字面实施会编译失败或走错架构。

## 各维度结果（第 1 轮）

| 维度 | 名称 | 结论 | P0 | P1 | P2 | 总计 |
|------|------|------|----|----|----|----|
| D1 | 上下文一致性 | FAIL | 0 | 2 | 2 | 4 |
| D2 | 内部逻辑自洽 | FAIL | 2 | 8 | 3 | 13 |
| D3 | 外部事实一致性 | FAIL | 1 | 6 | 5 | 12 |
| D4 | 技术可行性 | FAIL | 0 | 5 | 5 | 10 |
| D5 | 可执行性 | FAIL | 1 | 7 | 2 | 10 |
| D6 | 前置依赖完备性 | FAIL | 0 | 9 | 4 | 13 |
| D7 | 验收标准明确性 | FAIL | 0 | 8 | 4 | 12 |
| D8 | 边界完备性 | FAIL | 0 | 6 | 4 | 10 |
| D9 | 结构清晰性 | FAIL | 1 | 4 | 4 | 9 |
| **合计** | | **全 FAIL** | **5** | **55** | **33** | **93** |

## P0 阻断性问题（5 个，必须修复）

### P0-1: Corrections 与 Task 代码块全面矛盾（D2-001）
plan 末尾的 Critical Corrections §1 要求 Repository/UseCase/ViewModel 用 `serverId: String`，但 Task 6/7/8/10/11 的代码块**全部仍用 `conn: ServerConnection`**。执行者按 Task 字面实现会违背现有架构。

### P0-2: FileDiff 格式不兼容致编译失败（D2-002）
Critical Corrections §2 要求新建 `VcsFileDiff`（因既有 FileDiff 是 before/after，与 GET /vcs/diff 的 patch 不兼容）。但 Task 3 的 `FileDiffDto.toDomain()` 仍返回 `FileDiff` 并传入 `patch` 参数——既有 FileDiff 无 patch 字段，**照抄会编译失败**。

### P0-3: directory 路由参数全链路缺失（D3-001）
OpenCode 是多项目架构，依赖 `x-opencode-directory` HTTP header 路由到正确实例。plan 的 WorkspaceNav 设计了 directory 参数，但 Task 4/5 的 API 方法签名只有 `(conn, path)`，ViewModel→UseCase→Repository→API 链路中 directory **完全丢失**。后果：多项目服务器上会看到错误项目的文件树。现有 `OpenCodeApi.listDirectory(conn, path, directory)` 已正确传 directory，plan 反而退步。

### P0-4: Task 15 CodeSourceView 三关键函数完全未实现（D5-001）
`buildHighlights`、`buildAnnotatedStringFromHighlights`、`LineNumberGutter` 三个核心函数只有调用、没有任何实现。Correction 4 仅说"需自建辅助函数"，无代码。这是黑盒步骤，执行者会卡住。

### P0-5: Corrections 置于文档末尾且零内联引用（D9-001）
Critical Corrections section 位于 plan line 2352（文档共 2524 行，距末尾 172 行），影响 10/18 个 Task，但 Tasks 区域内对 Correction 1-4 的引用次数为 **0**。配合 plan 推荐的 subagent-driven-development（subagent 只读分配到的 Task），必然导致写出错误代码后才发现需返工。

## 核心问题模式（跨维度相互印证）

### 模式 1: Corrections 与 Task 代码脱节（最严重）
- 涉及：D2-001, D2-002, D5-003, D5-010, D6-007, D9-001, D9-002, D9-003
- Corrections 正确识别了 4 个架构错误，但**仅作为文末附录存在，未回写到 Task 代码块**
- 受影响 Task：2, 3, 4, 5, 6, 7, 8, 10, 11, 15（10/18）

### 模式 2: UI 层 Task 代码不完整（多个黑盒）
- 涉及：D5-001（P0）, D5-002, D5-004, D5-005, D5-007
- Task 11 ViewModel 核心方法 `/* ... */` 占位
- Task 13/14 Panel 实现纯文字描述无代码
- Task 15 三函数未实现
- Task 16 DiffView 代码有语法错误 + 颜色 token 未定义
- Task 12 NavGraph 回调是注释占位

### 模式 3: directory 参数链路断裂
- 涉及：D3-001（P0）
- 多项目服务器路由所需的 `x-opencode-directory` header 在全链路丢失

### 模式 4: 测试与实现脱节 + 真实样本未落实
- 涉及：D2-006, D7-003, D7-004, D7-006, D8-001, D8-002, D8-004, D8-006
- 用户明确强调"测试覆盖度要广，且需要足够真实"，但 plan 测试用 'val x = 1'/'User.kt' 等占位字符串
- Task 11 测试期望 "no API call" 但实现调了 loadLive
- 网络错误未用 Ktor 真实异常类型、并发场景未测

### 模式 5: 前置依赖（环境/工具）系统性缺失
- 涉及：D6-001 到 D6-009
- JDK 21、Maestro CLI 安装、emulator 10.0.2.2 主机回环、opencode 凭证（user/pass/env var）、lokit 工具、gradle daemon 兜底、Highlights 直接依赖声明、testTag 跨 Task 反向依赖——全部未在 plan 说明

### 模式 6: 结构问题
- 涉及：D9-001（P0）, D9-002, D9-003, D9-004
- Corrections 末尾位置、关键架构决策 section 与 Correction 1 矛盾、Global Constraints 矛盾、两份文档合计 3622 行均无 TOC

### 模式 7: 边界处理不全
- 涉及：D8-001 到 D8-006
- 网络错误未分类、DiffParser 缺 malformed/binary/CRLF 测试、Phase 1 大文件无缓解（spec 要求截断但 plan Phase 1 不实现且无降级）、WorkspaceViewModel 无并发测试、标注能力边界（超长意见/数量上限/选区超大）未定义

## 外部事实验证发现（D3，经工具验证）

D3 subagent 用 context7/websearch/项目代码三方交叉验证，额外发现：
- **D3-006**: OpenCodeApi 已有 `listDirectory`/`readFile`/`findFiles`/`searchText` 方法（line 974-1022），plan Task 4 重复造轮子
- **D3-002**: VcsBranchDto 的 `defaultBranch` 缺 `@SerialName("default_branch")`（API 返回 snake_case，会静默解析为 null）
- **D3-004**: `ShapeTokens.XS` 不存在（实际是 `extraSmall`/`small` 等）
- **D3-005**: `DiffAddedBg`/`DiffAddedFg` 等颜色 token 未定义（Color.kt 只有 `DiffAdded`/`DiffRemoved`）
- **D3-009**: Highlights 库 API 描述不精确（实际是 `List<CodeHighlight>`，子类 `ColorHighlight`/`BoldHighlight`，非 `List<Highlight>`）

## 技术可行性发现（D4）

D4 验证了架构整体可行，但发现 5 个 P1 算法/性能问题：
- **D4-001**: Highlights 是 transitive 依赖（经 mikepenz-code 引入），需显式声明直接依赖
- **D4-002**: Highlights 全文同步构建的大文件性能风险（3000-5000 行可能 200-500ms 卡顿）
- **D4-003**: 同 turn 聚合累积 diff 算法对 Write 中插场景失效
- **D4-004**: parseUnifiedDiff hunk type 推断逻辑漏掉 MODIFIED（真实 diff 多为混合 +/-）
- **D4-005**: DiffHunk.startLine 语义混淆（after 文件行号 vs patch 文本行索引）
- **D4-006**（积极发现）: spec §13.1 风险高估——项目内 `SessionTerminalInline.kt:92-118` 已有完整自定义 TextToolbar 实现，Phase 3 标注能力可直接复用

## 修复策略建议

鉴于 60 个必修问题（5 P0 + 55 P1）的规模和性质，逐个 edit 修补不现实——核心问题是 **plan 的 Task 代码块需要系统性重写**（10/18 个 Task 受影响）。

### 推荐策略：基于审查发现重写 plan

最彻底、最高效的修复方式是：**带着审查发现回到 writing-plans，重写 Phase 1 plan**。理由：
1. 核心问题是 Corrections 与 Task 脱节——重写时直接把修正内联到 Task 代码块，消除信息源分裂
2. UI 层 Task（10-16）大量黑盒——重写时补全代码
3. directory 参数链路——重写时从 API 到 ViewModel 全链路贯穿
4. 测试真实性——重写时用项目真实样本
5. 前置依赖——重写时充实 Global Constraints

### 备选策略：逐个 edit 修复

按 P0 → P1 顺序逐个 edit。但：
- 工作量巨大（60 个 issue，多个涉及整段代码块重写）
- 可能需要 3 轮重审（技能上限），每轮 9 个 subagent
- 修复过程中易引入新矛盾

## 残留问题

第 1 轮审查发现的 93 个问题**全部未修复**（暂停在 Phase 3 后，等待用户决策修复策略）。

## 验证检查清单状态

- [ ] 9 个维度全部 PASS — ❌ 全 FAIL
- [x] P0 问题已识别（5 个）
- [x] P1 问题已识别（55 个）
- [ ] P0 问题已修复 — ⏸ 暂停待决策
- [ ] P1 问题已修复 — ⏸ 暂停待决策
- [x] 审查报告已生成（本文件）
- [ ] 修复后未引入新矛盾 — ⏸ 未开始修复
- [x] 残留问题分级列出（见上文）
