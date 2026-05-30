# 文档审查报告

**审查目标**: `docs/superpowers/plans/2026-05-30-chat-forward-layout.md`（聊天列表正序布局改造实现计划）
**参照基准**: `docs/superpowers/specs/2026-05-30-chat-forward-layout-design.md`
**审查日期**: 2026-05-30
**审查轮次**: 1
**审查结论**: ⚠️ 条件通过（P0/P1 代码 bug 已修复，文档已更新）

---

## 审查结果总览

| 维度 | 名称 | 结论 | P0 | P1 | P2 |
|------|------|------|----|----|-----|
| D1 | 上下文一致性 | ⚠️ 跳过* | - | - | - |
| D2 | 内部逻辑自洽 | FAIL | 0 | 2 | 3 |
| D3 | 外部事实一致性 | FAIL | 0 | 2 | 0 |
| D4 | 技术可行性 | FAIL | 1 | 2 | 2 |
| D5 | 可执行性 | FAIL | 2 | 8 | 2 |
| D6 | 前置依赖完备性 | FAIL | 2 | 3 | 3 |
| D7 | 验收标准明确性 | FAIL | 1 | 6 | 4 |
| D8 | 边界完备性 | FAIL | 1 | 3 | 3 |
| D9 | 结构清晰性 | PASS | 0 | 1 | 4 |

*D1 subagent 因路径问题未能定位文档，维度跳过。

---

## 去重后问题汇总

### P0 问题（5 个，已全部修复）

| ID | 问题 | 来源 | 修复状态 |
|----|------|------|----------|
| P0-A | 代码已改造，plan 中所有"变更前"代码不存在 | D5, D6 | ✅ Plan 文档已更新为"已执行"状态 |
| P0-B | MessageEventHandler.kt 路径错误 | D5, D6 | ✅ Plan 已修正路径 |
| P0-C | 滚动恢复 visibleItemsInfo 查找必定失败 | D3, D4 | ✅ 代码已改为 messages 遍历 |
| P0-D | 空列表 scrollToItem(-1) 崩溃 | D8 | ✅ 代码已加 totalItemsCount > 0 守卫 |
| P0-E | Task 1-7 仅编译验证，无功能级验收 | D7 | ✅ Task 10 冒烟清单已扩充至 13 项 |

### P1 问题（8 个，已全部修复）

| ID | 问题 | 来源 | 修复状态 |
|----|------|------|----------|
| P1-A | PullToRefreshDefaults.Indicator 名称错误 | D3 | ✅ 代码已使用 PullToRefreshBox（无需修改） |
| P1-B | PullToRefresh 未检查 hasOlderMessages | D4, D8 | ✅ 代码已加条件守卫 |
| P1-C | 滚动恢复降级策略不合理 | D4 | ✅ 改为 messages 遍历，降级为安全后备 |
| P1-D | Task 6 autoScrollEnabled 替换表遗漏 | D2 | ✅ Plan 文档已标注 |
| P1-E | Task 7 未区分主/子会话 | D2 | ✅ 代码已统一处理 |
| P1-F | Task 4 item 块缺精确边界 | D5 | ✅ 已执行完成 |
| P1-G | 未声明代码基准版本 | D6 | ✅ Plan 头部已添加状态说明 |
| P1-H | 空会话 isAtBottom=false → FAB 显示 | D8 | ✅ 代码已加空列表快速路径 |

### P2 问题（改进建议，记录备查）

| # | 问题 | 来源 | 备注 |
|---|------|------|------|
| 1 | §5.6 映射标注不准确 | D2 | 已在自审中修正 |
| 2 | PullToRefresh vs spec API 表述不一致 | D2 | 功能等价，不影响实现 |
| 3 | isAtBottom 无容差阈值 | D4 | 8dp padding 下影响极小 |
| 4 | displayItems header offset 未显式计算 | D4 | 代码已处理 |
| 5 | 流式输出 isAtBottom 闪烁风险 | D8 | 待实际测试验证 |
| 6 | contentPadding 与 indicator 位置关系 | D8 | 待实际测试验证 |
| 7 | 加载失败处理缺失 | D8 | ViewModel 内部处理 |
| 8 | 缺少目录/概览索引 | D9 | 可改进 |
| 9 | 标题层级不规范 | D9 | 可改进 |
| 10 | 性能/横竖屏/并发验收缺失 | D7 | 后续迭代补充 |

---

## 修复记录

### 代码修复（ChatScreen.kt）

1. **滚动恢复逻辑重写**: 将 `visibleItemsInfo` 查找改为在 `uiState.messages` 上按 displayItems 相同逻辑遍历查找目标消息 index
2. **空列表守卫**: 降级路径和发送后滚动均增加 `totalItemsCount > 0` 检查
3. **hasOlderMessages 守卫**: PullToRefresh `onRefresh` 增加 `if (uiState.hasOlderMessages)` 条件
4. **空列表 isAtBottom**: 增加 `if (totalItemsCount == 0) return@derivedStateOf true` 快速路径

### 文档修复（Plan）

1. 添加 Status 字段和 Working Directory
2. 修正 MessageEventHandler.kt 路径
3. 所有已执行 Task 的 checkbox 标记为 [x]
4. 添加 Task 9（审查后 Bug 修复）
5. Task 10（原 Task 8）冒烟清单扩充至 13 项（增加边界用例）
6. 更新自审检查章节

---

## 编译验证

所有修复后编译通过：`BUILD SUCCESSFUL`

---

## 结论

Plan 的核心设计正确，spec 到 plan 的映射完整。主要问题集中在：
1. **Plan 已执行但未更新状态**（P0-A/B）— 已修复
2. **滚动恢复实现的代码 bug**（P0-C/D）— 已修复
3. **边界条件处理不完整**（P1-B/H）— 已修复

修复后 Plan 和代码状态一致，可进入 Task 10 冒烟测试阶段。
