---
id: validate
label: "验证"
mode: full
goal: "最终验证：报告完整性、覆盖度、修复留痕"
gates: [checklist_complete, summary_non_empty, route_valid]
route:
  - {target: archive, condition: pass}
  - {target: report, condition: retry-report}
---

## 目标
验证 test-report.md 的完整性和质量，确保所有产出物符合规范。通过则归档，不通过则回到 report 节点重新生成。

## 约束
- 不做额外测试，只验证已有产出的质量
- 最多重试 2 次（max_retry: 2）

## 输入
- {target_dir}/test-plan.md
- {target_dir}/test-report.md

## 输出
- 验证结果
- 路由决策：pass 或 retry-report

## 质量标准
- test-report.md 格式完整（所有必需段落存在且非空）
- test-plan.md 中所有测试项都有终态（无 ⬜）
- 代码修复记录完整（有文件名、修改内容、验证结果）
- 覆盖度评估合理（覆盖了主要维度）

## 指令
1. 检查 test-report.md 格式完整性：
   - 概览段（总项数、通过数、失败数、通过率）
   - 模块结果段
   - 失败项详情段
   - Bug 清单段
   - 代码修复记录段
   - 覆盖度评估段
2. 检查 test-plan.md 终态完整性：
   - 无 ⬜ 待执行项
   - 所有 ❌ 项都有原因描述
3. 验证代码修复记录：
   - 每个修复有明确的文件路径和修改内容
   - 修复后验证结果已记录
4. 检查覆盖度评估：
   - 是否覆盖了 coverage-checklist.md 中的主要维度
   - 未覆盖维度是否有原因说明
5. 全部通过 → 路由 pass
6. 存在不通过项 → 在 Issues 中记录问题，路由 retry-report

## 前序交接
（引擎自动注入）

## 综合分析
<!-- Planner SA 填写 -->

## 任务清单
<!-- Planner SA 填写 -->

## 任务总结
<!-- Executor SA 填写 -->

## 路由

## 创建的文件
<!-- Executor SA 填写 -->

## 修改的文件

## Issues

## 历史
<!-- 引擎自动管理 -->
