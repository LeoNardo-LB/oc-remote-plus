# P9 UI 修复与优化批次设计规格

> 日期: 2026-06-03
> 状态: 待批准
> 版本: 1.1

## 概述

本批次包含 **7 项** UI 修复与优化。#6（弹窗间距）、#7（主页按钮间距）、#8（弹窗统一）、#10（设置样式统一）推迟至后续批次 → 见 `docs/deferred-ui-unification.md`。

---

## #1 目录选择器根路径改为 "/"

### 问题
`OpenProjectDialog` 初始化时使用 `getHomeDirectory()`（`GET /path` 返回的 `home`），用户无法看到完整文件系统结构。

### 方案
- 初始路径改为 `"/"`，不再默认使用 `homeDir`
- 保留 `homeDir` 用于 `tildeReplace` 显示（如 `/home/user/projects` → `~/projects`）
- 返回按钮可导航到 `/`，到达 `/` 时 disabled

### 涉及文件
- `ui/screens/sessions/components/OpenProjectDialog.kt`:
  - 行 90: `val startDir = initialDirectory ?: home` → `val startDir = initialDirectory ?: "/"`
  - 行 117: 条件门控改为 `currentDir != "/" && currentDir != null`
  - 行 122: `parent.ifEmpty { homeDir }` → `parent.ifEmpty { "/" }`

### 验证
- 打开目录选择器，应显示根目录内容
- 返回箭头可导航到 `/`，到达 `/` 后箭头 disabled

---

## #2 面包屑返回按钮 bug + 高度跳动

### 问题
1. **SessionListScreen TopAppBar**: 自定义 `Column` 模拟双行标题，`baseDirectory` null→有值时高度跳变
2. **OpenProjectDialog 路径栏**: 返回箭头 `IconButton(32.dp)` 条件显隐导致 Row 高度跳动

### 方案

**SessionListScreen TopAppBar**:
- 使用 `TopAppBar` 原生 `subtitle` 参数（`ExperimentalMaterial3ExpressiveApi`）替代自定义 `Column`
- `title` = serverName，`subtitle` = baseDirectory 路径 + ArrowDropDown
- 原生 subtitle 不存在时自动隐藏，高度稳定
- 保持 title 区域 clickable 打开目录选择器

**OpenProjectDialog 路径栏**:
- 去掉返回箭头的条件门控，始终显示
- 缩小图标：去掉 `IconButton(32.dp)` 包裹，改为 `Icon(16.dp)` 内联在 Row 中
- 到达 `/` 时箭头灰显（alpha = MUTED）且不响应点击

### 涉及文件
- `ui/screens/sessions/SessionListScreen.kt` (行 108-155): TopAppBar 改用 subtitle
- `ui/screens/sessions/components/OpenProjectDialog.kt` (行 110-143): 路径栏重构

### 验证
- SessionListScreen: 切换 baseDirectory 时 TopAppBar 高度不跳变
- OpenProjectDialog: 箭头始终显示，高度稳定，导航到根目录后箭头灰显

---

## #3 上下文 Token 进度条数据修复

### 问题
`lastContextTokens` 取的是最后一条 assistant 消息的单轮 token 总和，而非累计上下文占用。

### 方案
- **不使用 `Session.tokens`**（ChatViewModel 行 403-404 注释明确说明 backend 是 overwrite 而非累计）
- 使用 ViewModel 中已有的 `totalInputTokens` + `totalOutputTokens` + `totalReasoningTokens` + `totalCacheReadTokens` + `totalCacheWriteTokens`（行 407-411 已从所有 assistant 消息累计计算）
- `lastContextTokens` = 以上五项之和（表示会话总 token 消耗）
- 删除 `ChatUiState` 中冗余的 `contextXxxTokens` 字段，弹窗直接引用 `totalXxxTokens` 字段
- 更新 `ChatTopBar` 函数签名和 `ChatScreen` 调用点（参数名 contextXxx → totalXxx）
- `contextWindow` 保持不变（从 Provider catalog 获取）

### 涉及文件
- `ui/screens/chat/ChatViewModel.kt` (行 412-422, 500-504): token 计算和 state 赋值
- `ui/screens/chat/components/ChatTopBar.kt` (行 67-71 函数签名, 126-229 显示逻辑)
- `ui/screens/chat/ChatScreen.kt` (行 537-541 传参): 调用点参数重命名

### 验证
- 多轮对话后进度条百分比应持续增长
- 点击进度条弹窗，各分项为累计值（Input/Output/Reasoning/Cache Read/Cache Write）
- 编译通过 + 单元测试通过

---

## #4 工具调用卡片标题显示文件名

### 问题
部分工具卡片标题显示完整路径而非文件名。

### 方案
- 确认专用卡片（Read/Edit/Write）已正确截取
- 修复 `ToolCardRegistry.kt` 通用渲染器：
  - `else` 分支: `substringAfterLast('/')` → `java.io.File(filePath).name`（兼容 Windows/Unix）
  - `list` 分支: subtitle 也做截取

### 涉及文件
- `ui/screens/chat/tools/ToolCardRegistry.kt` (行 41, 69, 123)

### 验证
- 所有工具卡片标题只显示文件名

---

## #5 活动会话数 x/y 中 x 着重样式

### 问题
`DirectoryTreeNode.kt` 中 `"%1$d/%2$d sessions active"` 使用统一样式，x 没有视觉突出。

### 方案
- 使用 `buildAnnotatedString` + `withStyle` 对 x 应用 `fontWeight = FontWeight.Bold` + `color = primary`
- y 和 "/ sessions active" 保持 `labelSmall` + `onSurface.copy(alpha = MUTED)`

### 涉及文件
- `ui/screens/sessions/components/DirectoryTreeNode.kt` (行 82-91)

### 验证
- 活动目录显示 `3/5 sessions active`，其中 `3` 为粗体+主题色

---

## #9 长按弹窗标题优化（目录详情/会话详情）

### 问题
长按弹窗标题显示完整路径或会话标题，过长时不美观。

### 方案
- 目录长按弹窗: 标题改为 `"目录详情"`，内部 key-value 显示 `Path: ~/xxx`、`Sessions: 12`
- 会话长按弹窗: 标题改为 `"会话详情"`，内部 key-value 显示 `Name: xxx`、`ID: abc123`、`Created: ...`
- 使用现有 `DetailRow` 组件

### 涉及文件
- `ui/screens/sessions/components/SessionRow.kt` (行 180-235)
- `ui/screens/sessions/components/DirectoryTreeNode.kt`

### 验证
- 目录长按: 标题 "目录详情"，内容 Path 行
- 会话长按: 标题 "会话详情"，内容 Name 行

---

## #11 设置页 Item 大小调为中档

### 问题
设置页 `ListItem` 使用默认大小，视觉偏大。Material 3 没有内置尺寸档位。

### 方案
- 在 `ui/theme/` 下新增 `ListItemTokens.kt`，定义三档 `ContentPadding`：
  ```kotlin
  object ListItemTokens {
      val ContentPaddingSmall = PaddingValues(horizontal = 16.dp, vertical = 2.dp)
      val ContentPaddingMedium = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
      val ContentPaddingLarge = PaddingValues(horizontal = 16.dp, vertical = 8.dp)  // M3 默认
  }
  ```
- 设置页所有 ListItem 引用 `ListItemTokens.ContentPaddingMedium`
- 符合项目已有的 Token 系统风格（AlphaTokens、ShapeTokens、MotionTokens）

### 涉及文件
- 新增 `ui/theme/ListItemTokens.kt`
- `ui/screens/settings/SettingsScreen.kt`: 所有 ListItem 加 `contentPadding = ListItemTokens.ContentPaddingMedium`

### 验证
- 设置页列表项更紧凑但仍可点击
