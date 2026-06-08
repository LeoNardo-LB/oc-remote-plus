# Doc Review Report

**Target:** `docs/superpowers/plans/2026-06-09-chat-state-streaming-fix.md`
**Date:** 2026-06-09
**Reviewer:** Main Agent (doc-consistency-review skill)
**Rounds:** 1 (quick mode)

---

## Round 1 Results

| Dimension | Result | Issues Found |
|-----------|--------|-------------|
| D1 上下文一致性 | ✅ PASS | — 计划忠实反映 9 轮深度分析结论 |
| D2 内部逻辑自洽 | ⚠️ → ✅ | 3 个 P1/P2 修复后通过 |
| D3 外部事实一致性 | ⚠️ → ✅ | 4 个 P0 修复后通过 |
| D4 技术可行性 | ✅ PASS | — 方案可行，Task 1-6 逻辑正确 |
| D5 可执行性 | ⚠️ → ✅ | 5 个 P0/P1 修复后通过 |
| D6 前置依赖完备性 | ✅ PASS | — Task 依赖顺序在 Cross-Task Conflicts 中明确 |
| D7 验收标准明确性 | ✅ PASS | — 每个 Task 有 compile/test 验证步骤 |
| D8 边界完备性 | ✅ PASS | — mergePart 保留更长文本、SSE-only 消息保留 |
| D9 结构清晰性 | ✅ PASS | — 9 个 Task 按优先级排序，依赖关系明确 |

---

## Issues Found & Fixed

### P0 Issues (4/4 Fixed)

| ID | Location | Title | Fix |
|----|----------|-------|-----|
| P0-001 | Task 7/8 全文 | `Message.Time` 不存在，应为 `TimeInfo` | 全局替换 `Message.Time(` → `TimeInfo(` |
| P0-002 | Task 7 `createViewModel()` | SavedStateHandle 缺少 `serverUrl`/`username`/`password`；`isNewSession` 键不存在 | 改为 `SavedStateHandle(mapOf(...))` 并移除 `isNewSession` |
| P0-003 | Task 8 所有 `MessagePartDelta` | 缺少必需的 `field` 参数 | 添加 `field = "text"` |
| P0-004 | Task 7 测试断言 | `vm.interactionState` 属性不存在 | 改为通过 `vm.messageListState.value` 验证 |

### P1 Issues (3/3 Fixed)

| ID | Location | Title | Fix |
|----|----------|-------|-----|
| P1-001 | Task 7 `createViewModel()` | 构造函数多传 `eventDispatcher`，少传其他参数 | 移除 `eventDispatcher`，添加注释指引参考现有测试 |
| P1-002 | Task 7 `setup()` | EventDispatcher 位置参数缺第 6 个 | 改为命名参数形式 |
| P1-003 | Task 8 最后一个测试 | 标题误导（实际测 SSE-to-SSE 而非 SSE-to-REST） | 标题改为 `handleMessagePartUpdated keeps longer existing text...` |

### P2 Issues (2/2 Fixed)

| ID | Location | Title | Fix |
|----|----------|-------|-----|
| P2-001 | Task 1/4/5 | `refreshSession()` 被 3 个 Task 逐步覆盖 | 在 Task 1/4/5 各 Step 顶部添加 ⚠️ VERSION-N (FINAL) 醒目注释，标注覆盖关系 |
| P2-002 | Task 8 最后测试 | 断言消息和注释提及 REST，实际走 SSE handler | 注释改为 "Shorter incoming PartUpdated via SSE → mergePart keeps longer existing text" |

---

## Verification Checklist

- [x] 9 个维度全部 PASS (修复后)
- [x] 所有 P0 问题已修复
- [x] 所有 P1 问题已修复
- [x] 修复过程中未引入新的矛盾
- [x] 残留 P2 问题已记录

## Conclusion

**PASS with notes.** Task 1-6 的核心逻辑（_isLoading 分离、fingerprint 修复、snapToBottom 改进、条件刷新、原子 refresh、流式合并）经审查确认正确。所有 P0/P1 集中在 Task 7/8 的测试代码中（类型名/构造函数参数不匹配），已全部修复。执行时仍需注意：Task 7 的 `createViewModel()` 最终参数应以实际 `ChatViewModel` 的 `@Inject` 构造函数为准，参考 `ChatViewModelQueuedTest.kt` 的 setup 模式。
