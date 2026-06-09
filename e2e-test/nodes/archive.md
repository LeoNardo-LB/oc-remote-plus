---
id: archive
label: "归档"
mode: single
goal: "归档运行状态，输出完成摘要"
gates: [checklist_complete, summary_non_empty]
---

## 目标
归档运行状态，输出测试完成摘要。

## 约束
- 只读操作，不修改任何产出文件

## 输入
- {target_dir}/test-plan.md
- {target_dir}/test-report.md

## 输出
- 完成摘要（写入任务总结）

## 质量标准
- 摘要包含完整的测试结果统计
- 列出所有未修复的 Bug

## 指令
1. 读取 {target_dir}/test-report.md 概览段
2. 提取关键数据：总项数、通过数、失败数、跳过数、通过率
3. 列出所有未修复的 Bug（P0/P1/P2）
4. 列出已修复的代码问题
5. 输出完成摘要到任务总结，格式：
   ```
   e2e-walker 测试完成
   总计：X 项 | 通过：N | 失败：M | 跳过：K | 通过率：P%
   Bug：P0 x 个，P1 x 个，P2 x 个
   已修复：x 个代码问题
   报告：{target_dir}/test-report.md
   ```

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
