---
id: next-item
label: "下一测试项"
mode: fast
goal: "读取 test-plan.md，找到下一个待执行测试项。有则路由到 run-item，无则路由到 report"
gates: [checklist_complete, summary_non_empty, route_valid]
route:
  - {target: run-item, condition: has-next}
  - {target: report, condition: all-done}
---

## 目标
读取 test-plan.md，定位下一个状态为 ⬜ 待执行的测试项。决定是继续执行还是进入报告阶段。

## 约束
- 不执行任何测试，只做路由决策
- 必须正确识别第一个 ⬜ 状态的测试项
- 必须在任务总结中完整输出当前测试项内容（供 run-item 使用）

## 输入
- {target_dir}/test-plan.md

## 输出
- 任务总结中包含当前测试项完整内容
- 路由决策：has-next 或 all-done

## 质量标准
- 正确识别第一个 ⬜ 状态的测试项
- 所有项都有终态（✅/❌/⚠️）时路由 all-done
- 任务总结包含完整测试项内容（编号、名称、步骤、成功标准、依赖）

## 指令
1. 读取 {target_dir}/test-plan.md
2. 统计所有测试项状态：
   - 统计 ⬜ 待执行、✅ 通过、❌ 各类失败、⚠️ 跳过 的数量
   - 记录进度：已完成 X/总数 Y
3. 查找第一个状态为 ⬜ 的测试项
4. 如果找到：
   - 将完整测试项内容写入任务总结（包括编号、名称、前置条件、步骤、成功标准、依赖）
   - 路由：has-next
5. 如果没有 ⬜ 项：
   - 任务总结写"所有测试项已完成"
   - 路由：all-done
6. 在任务总结开头写明进度：`进度：X/Y（通过 N，失败 M，跳过 K）`

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
