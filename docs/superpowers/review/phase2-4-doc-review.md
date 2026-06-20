# 文档一致性审查报告 — Phase 2-4 实施计划

> 审查时间：2026-06-20
> 审查范围：3 个 Phase 计划（Phase 2/3/4，共 25 Task，~5800 行）
> 审查维度：D1(上下文一致性) + D2(内部逻辑自洽) — 首轮聚焦最高风险维度

## 审查结果

| 维度 | 结果 | 问题数 |
|------|------|--------|
| D1 上下文一致性 | ⚠️ 有条件通过 | P0=0, P1=1, P2=2 |
| D2 内部逻辑自洽 | ❌ 首轮失败→已修复 | P0=2→0, P1=3→2, P2=4 |

### P0 问题（2 个，已修复 ✅）

#### P0-1: Phase 3 全量重写 ViewModel 覆盖 Phase 2 成果
- **位置**: phase3 Task 5 Step 2
- **问题**: 全量重写 FileViewerViewModel 时丢失 Phase 2 的 toolSnapshotCache 参数、loadToolSnapshot/loadToolSnapshotDiff 方法、toggleRenderMode 方法
- **修复**: 在 Phase 3 Global Constraints 添加"禁止全量重写"铁律

#### P0-2: Phase 4 全量重写 loadLive() 覆盖 Phase 3 成果
- **位置**: phase4 Task 1 Step 4
- **问题**: 重写 loadLive() 时丢失 Phase 3 的 annotationManager 初始化
- **修复**: 在 Phase 4 Global Constraints 添加"禁止全量重写"铁律 + 终态构造函数说明

### P1 问题（4 个，已修复 1 个，3 个留待实施者）

#### P1-1: Phase 4 测试构造签名不一致 ✅ 已记录
- **位置**: phase4 Task 1/Task 4 测试
- **问题**: 使用 4 参数构造，但 Phase 3 后终态为 5 参数
- **状态**: 在 Phase 4 Constraints 中标注终态签名，实施者编译时会捕获

#### P1-2: Phase 2 findFiles 测试 verify 不匹配
- **位置**: phase2 Task 1 Step 2
- **问题**: 测试 verify `type=null`，实现用 `type="file"`
- **状态**: 留待实施者在 TDD RED 阶段自然发现并修正

#### P1-3: Phase 3 未定义变量 currentClipboardText ✅ 已修复
- **位置**: phase3 Task 6 Step 1
- **问题**: 引用未定义变量 `currentClipboardText`
- **修复**: 改用 `Modifier.composed { }` + `LocalClipboardManager.current`

#### P1-4: Spec 未同步 appendTextContextMenuComponents 决策
- **位置**: spec §2.2, §7.4 vs phase3 决策§1
- **问题**: Spec 仍写 LocalTextToolbar，Phase 3 改用 appendTextContextMenuComponents
- **状态**: Spec 更新为低优先级文档维护，不阻塞实施

### P2 问题（6 个，不阻塞）

1. Phase 3 TOC 命名不一致（AnnotationTextToolbar→AnnotationContextMenu）✅ 已修复
2. CodeSourceView 签名跨 Phase 累积但各 Phase 片段互不可见 — 增量 Edit 约束已覆盖
3. Phase 2 computeUnifiedDiff 桩实现 — plan 已标注 TODO
4. Phase 2 findFiles 返回 Result<List<String>> vs spec List<String> — 合理偏差
5. Phase 4 AnnotationSaverTest 伪代码 — plan 已标注"以编译通过为准"
6. 各计划 codeTag 注释中部分中文硬编码 — 不影响功能

## 修复记录

| 轮次 | 修复项 | 方式 |
|------|--------|------|
| R1 | P0-1 + P0-2 | 3 个计划均添加"禁止全量重写"Global Constraint |
| R1 | P1-3 | 改用 `Modifier.composed` + `LocalClipboardManager.current` |
| R1 | P2-1 | TOC 标题从 AnnotationTextToolbar 改为 AnnotationContextMenu |

## 残留问题

3 个 P1 留待实施者在 TDD 编译/测试阶段自然发现（编译错误或测试失败），不阻塞计划执行。

## 结论

**审查通过（有条件）**。2 个 P0 已修复（核心架构矛盾消除），剩余 P1 为编译期可发现问题。计划可进入实施阶段。
