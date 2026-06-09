---
id: explore
label: "探索"
mode: full
goal: "派发探索 SA，分析目标应用，生成 test-plan.md"
gates: [checklist_complete, summary_non_empty, files_exist]
---

## 目标
分析目标应用的源码和可访问界面，全面理解应用结构、业务流程、交互元素，生成结构化的 test-plan.md。

## 约束
- 探索阶段不执行任何测试，只生成计划
- 所有测试步骤必须基于实际观察（源码分析或界面操作），不可凭空臆测
- 成功标准必须是用户可观察的效果，禁止代码层断言
- 测试项之间通过 ID 建立依赖关系
- 每个测试项必须有明确的前置条件

## 输入
- tool_manifest（来自 tool-detect 的任务总结）
- 目标信息：{platform}、{access_info}、{source_dir}、{target_dir}

## 输出
- {target_dir}/test-plan.md

## 质量标准
- 覆盖所有用户可达页面和业务流程
- 每个 UI 交互元素都有对应测试项
- 所有条件渲染（v-if/th:if/ngIf）的 true/false 分支都覆盖
- 所有按钮点击的后端请求覆盖正常/异常/无响应
- 格式严格遵循 references/test-doc-format.md
- 覆盖要求参照 references/coverage-checklist.md

## 指令
1. 读取前序交接中 tool-detect 提供的 tool_manifest
2. 如果有源码访问能力：
   - 分析路由配置，找出所有页面
   - 分析 API 接口，找出所有后端请求
   - 分析组件，找出所有条件渲染分支
3. 如果有浏览器/设备访问能力：
   - 实际浏览应用界面
   - 记录每个页面的交互元素
   - 验证源码分析结果
4. 识别所有业务流程（端到端链路）
5. 按 references/test-doc-format.md 格式生成 test-plan.md
6. 确保 references/coverage-checklist.md 中的"必须"覆盖项全部满足
7. Prompt 细节参照 references/explore-sa-prompt.md

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
