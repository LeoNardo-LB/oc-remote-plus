---
id: tool-detect
label: "工具检测"
mode: fast
goal: "检测可用工具（CDP/ADB/curl/源码），生成 tool_manifest"
gates: [checklist_complete, summary_non_empty]
---

## 目标
检测当前环境可用的测试工具，生成 tool_manifest 供后续所有节点使用。

## 约束
- 不执行任何测试操作，只检测和记录工具可用性
- 检测结果写入任务总结，供 explore 及后续节点读取

## 输入
- 平台信息：{platform}（web/android/ios/desktop）
- 访问方式：{access_info}
- 源码路径：{source_dir}
- 产出目录：{target_dir}

## 输出
- tool_manifest（工具能力清单字符串）

## 质量标准
- manifest 列出了所有可用工具及其能力
- 每类工具明确标注可用/不可用

## 指令
1. 检测 chrome-devtools_* 工具是否可用（尝试 list_pages）
   → 可用：添加 CDP 工具集（navigate/fill/click/screenshot/evaluate/press_key/snapshot）
2. 检测 replicant_adb-device 工具是否可用（尝试 list devices）
   → 可用：添加 ADB 工具集（ui-action/ui-capture/ui-query/adb-shell/adb-logcat）
3. 检查 {source_dir} 目录是否可访问
   → 可用：添加源码分析能力（read/grep/glob）
4. 检查 bash 是否可用
   → 可用：添加 HTTP/CLI 能力（curl/httpie）
5. 将 tool_manifest 写入任务总结，格式如下：
   ```
   ## 可用工具清单
   ### 浏览器操作（CDP）
   - navigate_page(url) — 导航
   - take_screenshot() — 截图
   ...
   ### 移动设备操作（ADB）
   ...
   ### HTTP/API 工具
   ...
   ### 源码分析
   ...
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
