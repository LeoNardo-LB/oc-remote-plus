---
report_name: Session Tree UI Improvements 文档审查报告
review_date: 2026-06-02
document_reviewed: spec (2026-06-02-session-tree-ui-improvements-design.md) + plan (2026-06-02-session-tree-ui-implementation.md)
round_number: 1 / 3
final_verdict: CONDITIONAL PASS
---

# 文档一致性审查报告

## 1. 审查摘要

| 项目 | 内容 |
|------|------|
| **审查对象** | spec + plan 两份文档 |
| **审查轮次** | 第 1 轮 / 共 3 轮 |
| **审查时间** | 2026-06-02 |
| **最终判定** | **CONDITIONAL PASS** |

### 各维度结果速览

| 维度 | 维度名称 | 本轮 |
|------|----------|------|
| D1 | 上下文一致性 | ✅ PASS（1 P2 残留） |
| D2 | 内部逻辑自洽 | ✅ PASS（已修复） |
| D3 | 外部事实一致性 | ✅ PASS（D3-001 属另一 plan） |
| D4 | 技术可行性 | ✅ PASS（2 P2 残留） |
| D5 | 可执行性 | ✅ PASS（已修复） |
| D6 | 前置依赖完备性 | ✅ PASS（3 P2 残留） |
| D7 | 验收标准明确性 | ✅ PASS（已修复） |
| D8 | 边界完备性 | ⚠️ PASS（3 P2 残留） |
| D9 | 结构清晰性 | ✅ PASS（2 P2 残留） |

> 本轮修复了全部 6 个 P1 问题，剩余 11 个 P2 级别改进建议。

## 2. 问题明细

### 已修复（本轮）

| ID | 严重级别 | 问题 | 修复方式 |
|----|----------|------|----------|
| D5-001 | P1 | `Icons.Filled.ErrorOutline` 应为 `Icons.Outlined.ErrorOutline` | Plan Task 7 改为 `Icons.Outlined.ErrorOutline` |
| D2-002 | P1 | P8 字符串资源冲突，UI 文本丢失 "sessions" | 统一为 `directory_session_count_active` 格式化资源 |
| D5-002 | P1 | Task 3 Steps 2-6 用 "Same pattern" 过于模糊 | 展开为完整代码（各 Picker 的 AppDialog + AppPickerList 调用） |
| D4-001 | P1 | AppPickerList 缺少选中项自动滚动 | 添加 `rememberLazyListState` + `LaunchedEffect(scrollToItem)` |
| D4-002 | P1 | LanguagePicker maxBodyHeight 480 vs 520 不一致 | Plan 明确 520dp |
| D7-001 | P1 | P4/P5/P6 在 spec §11 缺失验收标准 | 补充完整验收表（12 行） |
| D5-003 | P2 | 未使用的 `items` 导入 | 移除 |
| D5-004 | P2 | 死字符串资源 `sessions_active` | 删除，改用统一资源 |
| D5-005 | P2 | Task 9 黑盒步骤 | 改为确定性描述 |

### 残留问题（P2，建议择机修复）

| ID | 严重级别 | 问题 | 位置 |
|----|----------|------|------|
| D1-004 | P2 | 文件计数 spec 13 vs plan 16 不一致 | Spec §1 |
| D2-003 | P2 | Picker 迁移方案（内联 vs AppPickerList）spec 未更新 | Spec §2.5 |
| D4-003 | P2 | P8 活跃数不再用 primary 高亮（改用统一的 secondary text 色） | Plan Task 8 |
| D6-001 | P2 | Spec/Plan 未明确声明 JDK 21/SDK 36 构建环境 | Spec §1 |
| D6-002 | P2 | Plan 多处硬编码行号存在漂移风险 | Plan |
| D6-003 | P2 | P8 字符串资源方案分歧（格式化 vs 拼接）已在修复中解决 | — |
| D8-003 | P2 | AppPickerList 缺少空列表回退状态 | Plan Task 2 |
| D8-004 | P2 | 编译失败回滚步骤缺失 | Plan Tasks 1-9 |
| D9-001 | P2 | Task 1-4 标题缺少 P-x 标注 | Plan |
| D9-002 | P2 | Spec/Plan 文件计数差异未说明 | Spec §1 / Plan |
| D4-004 | P2 | AppPickerList 交叉层依赖 sessions 包 | Plan Task 2 |
| D4-005 | P2 | 重复 import 声明 | Plan Task 4 |

## 3. 修复记录

| 轮次 | 修复项 | 影响维度 | 本轮结果 |
|------|--------|----------|----------|
| 1 | 修复 6 个 P1 + 3 个 P2（icon 名、字符串资源、验收标准、Picker 详情、自动滚动、maxBodyHeight） | D2, D4, D5, D7 | P1 清零，P2 残留 11 项 |

## 4. 残留问题

> ⚠️ 11 项 P2 级别改进建议，不阻塞实现。建议在实现过程中或实现后择机修复。

### ⚠️ [P2] D1-004: 文件计数不一致（spec 13 vs plan 16）

| 字段 | 内容 |
|------|------|
| **未修复原因** | Spec 与 Plan 的统计粒度不同（Plan 多出 AppPickerList.kt、OpenProjectDialog.kt、strings.xml），差异是合理的实现细节 |
| **对实现的影响** | 无 — 不影响代码正确性 |
| **缓解措施** | 在 Plan File Structure 表底部添加脚注说明差异原因 |

### ⚠️ [P2] D2-003: Picker 迁移方案 spec/plan 不一致

| 字段 | 内容 |
|------|------|
| **未修复原因** | Plan 的 AppPickerList 方案（DRY）优于 Spec 的内联方案，但 Spec 未同步更新 |
| **对实现的影响** | 无 — 按 Plan 执行即可 |
| **缓解措施** | 更新 Spec §2.5 反映 AppPickerList 方案 |

### ⚠️ [P2] 其余 9 项

包括空列表回退、回滚步骤、标题标注、构建环境声明、行号漂移等，均为文档改进建议，不阻塞实现。

## 5. 结论

### 最终判定: **CONDITIONAL PASS**

| 判定 | 含义 |
|------|------|
| **CONDITIONAL PASS** | 存在 P2 级别改进建议，不影响实现，建议择机优化 |

**建议**: 文档质量可进入实现阶段。6 个 P1 问题已修复，Plan 现在包含完整可执行的代码和步骤。剩余 11 个 P2 改进建议可在实现过程中逐步完善（如在首轮编译成功后补充回滚说明、实现 AppPickerList 空列表处理等）。

---

*报告由 doc-consistency-review 技能自动生成 · 生成时间: 2026-06-02*
