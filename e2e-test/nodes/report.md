---
id: report
label: "生成报告"
mode: full
goal: "派发报告 SA，汇总所有测试结果，生成 test-report.md"
gates: [checklist_complete, summary_non_empty, files_exist]
---

## 目标
汇总 test-plan.md 中所有测试项的执行结果，生成结构化的 test-report.md。

## 约束
- 所有数据必须来自 test-plan.md 的实际执行结果，不可编造
- 代码修复记录必须与 test-plan.md 中的修复信息一致
- 通过率计算：(通过数 / 总数 - 跳过数) × 100%

## 输入
- {target_dir}/test-plan.md（所有测试项已完成）

## 输出
- {target_dir}/test-report.md

## 质量标准
- 报告包含完整段落：概览、模块结果、失败详情、Bug 清单、代码修复记录、覆盖度评估
- 通过率计算正确
- Bug 严重程度分类合理（P0 阻塞/P1 功能/P2 体验）
- 修复记录完整（文件+内容+验证结果）

## 指令
1. 读取 {target_dir}/test-plan.md
2. 统计结果：总项数、通过数、各类型失败数、跳过数
3. 按模块分组统计
4. 提取所有失败项详情（编号、名称、失败类型、原因）
5. 提取所有 Bug（❌-code-bug 项），按严重程度排序
6. 提取代码修复记录（文件、修改内容、关联测试项、验证结果）
7. 对照 references/coverage-checklist.md 评估实际覆盖度
8. 按 references/report-sa-prompt.md 的报告格式生成 test-report.md
9. 保存到 {target_dir}/test-report.md

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
