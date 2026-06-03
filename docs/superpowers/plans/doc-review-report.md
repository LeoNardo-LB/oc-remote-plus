# 文档一致性审查报告

## 1. 审查摘要

| 项目 | 内容 |
|------|------|
| **审查对象** | 6 个 Layer 实施计划 (2026-06-04-layer1~layer6) |
| **审查轮次** | 第 1 轮 / 共 3 轮 |
| **审查时间** | 2026-06-04 |
| **最终判定** | **CONDITIONAL PASS** |

### 各维度结果速览

| 维度 | 维度名称 | 本轮 | 上轮变化 |
|------|----------|------|----------|
| D1 | 上下文一致性 | FAIL→PASS | ↑ |
| D2 | 内部逻辑自洽 | FAIL→PASS | ↑ |
| D3 | 外部事实一致性 | FAIL→PASS | ↑ |
| D4 | 技术可行性 | FAIL→PASS | ↑ |
| D5 | 可执行性 | FAIL→CONDITIONAL | ↑ |
| D6 | 前置依赖完备性 | FAIL→PASS | ↑ |
| D7 | 验收标准明确性 | FAIL→CONDITIONAL | ↑ |
| D8 | 边界完备性 | FAIL→CONDITIONAL | ↑ |
| D9 | 结构清晰性 | PASS | - |

> **图例**: `PASS` 通过 · `FAIL` 未通过 · `-` 无变化 · `↑` 改善 · `↓` 退步 · `NEW` 新增

## 2. 已修复的 P0 问题

> 按 P0 → P1 → P2 排序。P0 全部已修复。

### [P0] D2/D3/D4: throwabl 拼写错误

| 字段 | 内容 |
|------|------|
| **位置** | layer1-network-resilience.md: Step 2.2, line 536 |
| **描述** | `isTransientException` 函数中 `throwabl.isTransient` 缺少字母 `e`，应为 `throwable.isTransient` |
| **修复** | 已修正为 `throwable.isTransient` |
| **状态** | ✅ 已修复 |

### [P0] D2/D3/D4: mapHttpError HttpStatusCode vs Int 类型不匹配

| 字段 | 内容 |
|------|------|
| **位置** | layer1-network-resilience.md: Step 1.1 测试 vs Step 1.2 实现 |
| **描述** | 测试传入 `HttpStatusCode.Unauthorized`，但实现签名是 `fun mapHttpError(statusCode: Int, ...)`。Ktor 的 `HttpStatusCode` 不是 `Int` |
| **修复** | 测试中所有 `HttpStatusCode.XXX` 改为 `HttpStatusCode.XXX.value` |
| **状态** | ✅ 已修复 |

### [P0] D1/D4: AlphaTokens.SELECTED / DIFF_BG 不存在

| 字段 | 内容 |
|------|------|
| **位置** | layer1 (ConnectionErrorScreen, SessionRetryCard), layer3 (ToolProgressCard, CompactionBanner), layer5 (DiffView) |
| **描述** | `AlphaTokens` 仅定义 FAINT/MUTED/MEDIUM/HIGH/AMOLED 五级。`SELECTED` 和 `DIFF_BG` 不存在 |
| **修复** | `SELECTED` → `HIGH` (0.80), `DIFF_BG` → `FAINT` (0.35) |
| **状态** | ✅ 已修复 |

### [P0] D3: SessionNextEvent 测试 JSON 缺少 type 鉴别字段

| 字段 | 内容 |
|------|------|
| **位置** | layer3-event-processing.md: Step 1.1 SessionNextEventTest |
| **描述** | 25 个测试 JSON 全部缺少 `type` 字段，导致 `SessionNextEventSerializer.selectDeserializer()` 全部回退到 `Unknown` 分支 |
| **修复** | 所有测试 JSON 添加 `{"type":"session.next.xxx",...}` |
| **状态** | ✅ 已修复 |

### [P0] D1/D2/D3/D4: StreamingStateTracker L3 vs L5 同名冲突

| 字段 | 内容 |
|------|------|
| **位置** | layer3 Task 3 vs layer5 Step 5.6.1 |
| **描述** | 两个同名类 `StreamingStateTracker`，API 完全不同：L3 有完整状态机 (Idle→Started→Streaming→Ended)，L5 仅是简单的 Set<String> |
| **修复** | L5 删除独立的 StreamingStateTracker，改为复用 L3 的实现。删除 L5 的测试文件 |
| **状态** | ✅ 已修复 |

## 3. 已修复的 P1/P2 问题

| Issue | 级别 | 修复内容 |
|-------|------|----------|
| D2-008: L3 虚假依赖 RetryPolicy | P2 | 移除 `Depends on` 中的 `1.3 (RetryPolicy)` |
| D3-007: NetworkMonitor 导入 callbackFlow 未使用 | P2 | 删除 `import callbackFlow` |
| D3-008: SseClientReadTimeoutTest 导入 ByteReadChannel 未使用 | P2 | 删除 `import ByteReadChannel` |
| D3-009: NetworkMonitor @Inject + @Provides 冗余 | P2 | 删除 `provideNetworkMonitor()` 方法，依赖 @Inject 自动注入 |
| D5-007: ReasoningBlock // ... existing code ... 省略号 | P1 | 重写为具体的行为描述，无省略号 |

## 4. 残留 P1 问题（实施时需注意）

> ⚠️ 以下 P1 问题在计划审查阶段记录，建议在实际实施时根据真实代码调整。

### ⚠️ [P1] ChatViewModel 构造函数参数不匹配

| 字段 | 内容 |
|------|------|
| **位置** | layer2 Task 9, layer4 Step 4.1 |
| **未修复原因** | 需要对照实际代码库中 ChatViewModel.kt 的 13 参数构造函数逐一调整，且每个 Layer 的测试只用到部分参数 |
| **对实现的影响** | 测试代码无法直接编译，需手动调整构造参数 |
| **缓解措施** | 实施时先 `Read` ChatViewModel.kt 获取完整构造签名，用 `mockk(relaxed=true)` 填充所有参数 |

### ⚠️ [P1] EventDispatcher 构造函数 L3 新增第 6 参数

| 字段 | 内容 |
|------|------|
| **位置** | layer4 Step 4.1 测试 vs layer3 Task 5 实现 |
| **未修复原因** | 需在 L3 实施完成后才能确认 EventDispatcher 新构造签名 |
| **对实现的影响** | L4 测试需在 L3 完成后更新 |
| **缓解措施** | L4 实施前先 Read EventDispatcher.kt 确认最新构造参数 |

### ⚠️ [P1] Spec SessionNextEvent 字段名 vs L3 实现不一致

| 字段 | 内容 |
|------|------|
| **位置** | spec line 88-133 vs layer3 Task 1 |
| **未修复原因** | L3 计划的字段名更完整（包含 messageId/partId），可能与实际 SSE payload 更匹配 |
| **对实现的影响** | Spec 与 Plan 不一致可能导致混淆 |
| **缓解措施** | 实施 L3 时以实际 SSE payload 为准，实施完成后回写 Spec |

### ⚠️ [P1] RateLimit UI 倒计时缺失

| 字段 | 内容 |
|------|------|
| **位置** | spec 1.5 → 无对应计划任务 |
| **未修复原因** | Layer 1 Task 1 实现了 RateLimitError 模型但无 UI 任务。需新增一个 UI 任务 |
| **对实现的影响** | Spec 验收标准（429 展示倒计时）无法通过 |
| **缓解措施** | 在 Layer 1 Task 8 (SessionRetryCard) 中扩展，或作为独立小任务补充 |

### ⚠️ [P1] importSession API 端点未验证

| 字段 | 内容 |
|------|------|
| **位置** | layer2 Task 4 |
| **未修复原因** | 服务端 API 可能不存在 `/session/import` 端点，需要运行时验证 |
| **对实现的影响** | 如果端点不存在，Task 4 的测试和实现需要调整 |
| **缓解措施** | 实施前先 `curl` 验证端点，不存在则降级为解析 share URL |

### ⚠️ [P1] SessionListVM combine 12 流超 API 限制

| 字段 | 内容 |
|------|------|
| **位置** | layer2 Task 6 Step 3 |
| **未修复原因** | Kotlin coroutines `combine` 最多 5 参数重载，超过需特殊处理 |
| **对实现的影响** | 代码无法按计划编写 |
| **缓解措施** | 实施时检查现有代码中 combine 的实际用法（可能已有自定义扩展），按相同模式扩展 |

## 5. 修复记录

| 轮次 | 修复项 | 影响维度 | 本轮结果 |
|------|--------|----------|----------|
| 1 | P0: throwabl 拼写错误 | D2,D3,D4 | FIXED |
| 1 | P0: HttpStatusCode vs Int 类型 | D1,D2,D3,D4 | FIXED |
| 1 | P0: AlphaTokens 常量不存在 | D1,D4,D6 | FIXED |
| 1 | P0: SessionNextEvent 测试缺少 type 字段 | D3,D5 | FIXED |
| 1 | P0: StreamingStateTracker 同名冲突 | D1,D2,D3,D4 | FIXED |
| 1 | P2: L3 虚假依赖声明 | D2,D6 | FIXED |
| 1 | P2: 死导入 (callbackFlow, ByteReadChannel) | D3 | FIXED |
| 1 | P2: NetworkMonitor @Provides 冗余 | D3,D4 | FIXED |
| 1 | P1: ReasoningBlock 省略号 | D5 | FIXED |

## 6. 结论

### 最终判定: **CONDITIONAL PASS**

所有 P0 阻断性问题已修复。残留 6 个 P1 问题，均涉及跨层构造函数一致性和未验证的外部 API。这些问题**不会阻止计划开始执行**，但需要在实施过程中：

1. **每个 Layer 实施前**先 Read 真实代码确认构造函数签名
2. **Layer 2 实施时**检查现有 combine 扩展模式
3. **Layer 3 实施后**回写 Spec 中的 SessionNextEvent 字段定义
4. **补充 RateLimit 倒计时 UI 任务**

**建议**: 可以开始执行 Layer 1 实施。残留 P1 问题在实际执行时逐个解决。

---

*报告由 doc-consistency-review 技能自动生成 · 生成时间: 2026-06-04*
