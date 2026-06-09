---
id: review-plan
label: "规划审查"
mode: full
goal: "主 Agent 审查 test-plan.md，覆盖自查，补充遗漏项"
gates: [checklist_complete, summary_non_empty]
---

## 目标
审查探索阶段生成的 test-plan.md，用覆盖自查清单逐项核对，补充遗漏的测试项，调整依赖顺序。

## 约束
- 不执行任何测试，只审查和补充计划
- 补充的测试项必须遵循相同的格式规范
- 不删除已有的测试项，只补充和调整顺序

## 输入
- {target_dir}/test-plan.md

## 输出
- 更新后的 {target_dir}/test-plan.md

## 质量标准
- references/coverage-checklist.md 的 15 项全部覆盖（或标注原因）
- 无循环依赖
- 每个测试项的步骤和成功标准都具体可验证
- 所有"必须"覆盖维度均有对应测试项

## 指令
1. 完整性检查：
   - 所有用户可达页面都有对应测试项？
   - 所有业务流程的 Happy Path 都覆盖？
   - 每个交互元素都有测试操作？
2. 可执行性检查：
   - 每个测试项的步骤是否够具体？
   - 成功标准是否可验证（用户可观察）？
   - 前置条件是否可实现？
3. 依赖关系检查：
   - 测试项排序是否满足数据依赖？
   - 是否有循环依赖？
4. 覆盖自查：用 references/coverage-checklist.md 逐项核对 15 个维度
   - 每个未覆盖的维度，补充测试项或标注原因
   - 特别关注：后端请求覆盖、条件分支覆盖、输入边界
5. 补充与调整：
   - 补充遗漏的测试项
   - 合并冗余项
   - 调整依赖顺序确保数据链完整
6. 详细操作参照 references/plan-phase-guide.md

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
