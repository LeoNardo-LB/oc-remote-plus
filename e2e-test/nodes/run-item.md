---
id: run-item
label: "执行测试项"
mode: full
goal: "派发执行 SA 执行当前测试项，写回结果，收集证据"
gates: [checklist_complete, summary_non_empty]
---

## 目标
执行 next-item 选定的单个测试项，按步骤操作目标应用，收集截图证据，判定通过/失败并写回 test-plan.md。

## 约束
- 必须真实操作目标应用，不可虚拟结果
- 截图先于操作：每步操作前截 before，操作后截 after
- 成功标准必须是用户可观察的效果，禁止代码层断言
- 测试数据通过前端操作创建，禁止直接操作数据库
- 同一测试项最多尝试 3 次，超过标记 ❌-blocked
- 所有代码修复必须留痕（文件+内容+验证结果）

## 输入
- 当前测试项完整内容（来自 next-item 的任务总结）
- tool_manifest（来自 tool-detect）
- 前序测试项执行结果（来自前序交接）

## 输出
- 更新 test-plan.md 中当前测试项的状态段
- {target_dir}/evidence/ 下的截图和日志证据

## 质量标准
- 状态标记正确使用：✅ / ❌-test-error / ❌-expect-error / ❌-code-bug / ⚠️-env-issue / ❌-blocked
- 证据文件命名规范：T{编号}-{步骤描述}-before/after.png
- 失败项有详细原因和建议修复方向
- 代码修复有完整记录

## 指令
1. 读取前序交接中 next-item 提供的当前测试项内容
2. 确认前置条件已满足（依赖的测试项已通过）
3. 按 test-plan.md 中该测试项的步骤逐步执行：
   a. 每步操作前截图保存为 before
   b. 执行操作
   c. 每步操作后截图保存为 after
   d. 收集控制台错误、网络请求等辅助证据
4. 对比实际结果与成功标准，判定通过或失败
5. 在 test-plan.md 中更新该测试项的状态段：
   - ✅ PASS：实际结果 + 证据列表
   - ❌ 失败：标记类型 + 失败原因 + 证据 + 建议修复方向
6. 如果是 ❌-code-bug 且可以修复：
   - 尝试修复代码
   - 记录修复内容（文件、行号、修改）
   - 重新执行该测试项验证修复效果
7. 如果是 ❌-test-error：在 test-plan.md 中修正步骤描述
8. 证据保存到 {target_dir}/evidence/
9. Prompt 细节参照 references/execute-sa-prompt.md

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
