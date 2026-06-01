# Bug 修复 — 5项回归问题 (beta.115 引入)

**日期**: 2026-06-01  
**状态**: 设计中  
**关联**: v2.0.0-beta.115 → v2.0.0-beta.116

---

## 背景

beta.115 发布了 UI Polish（代码块复制按钮、表格自适应、搜索文案简化、搜索入参显示），引入了 5 个回归 bug。beta.116 曾尝试修复但被 revert。

## 问题清单

### 1. 整体列表页卡顿 🔴

**现象**: beta.115 后聊天列表滚动和交互明显卡顿。

**根因**: 两个因素叠加
- A. `MarkdownContent.kt` 每次重组创建新的 `markdownComponents` 对象 → mikepenz `Markdown` 内部 `SubcomposeLayout` 重布局所有代码块
- B. `MarkdownHighlightedCodeBlock(showHeader=true)` 在每个代码块顶部渲染标题栏（语言标签 + 复制按钮），是纯文本代码块的 3-5 倍渲染成本

**影响范围**: 所有包含代码块的 AI 回复和工具输出。LazyColumn 中每帧测量/布局开销累积。

### 2. 读取/编辑卡片标题全路径 🟡

**现象**: 卡片标题显示完整路径（如 `C:\Users\...\file.kt`）而非文件名。

**根因**: `EditToolCard.kt:58` / `ReadToolCard.kt:54` 使用 `substringAfterLast('/')` 截取文件名，不处理 Windows 反斜杠 `\`。

**影响范围**: Windows 设备上所有 Read/Edit 工具卡片。

### 3. 子 agent 卡片展开/折叠图标无意义 🟡

**现象**: 可导航到子会话的任务卡片上显示 `ExpandLess`/`ExpandMore` 图标，但点击无反应（点击被导航行为覆盖）。

**根因**: `TaskToolCard.kt:102` `hasContent = if (showNavArrow) true else hasOutput` → 导航卡片在无输出的情况下仍显示展开图标。

**影响范围**: 有 `subSessionId` 但无输出的 Task 卡片。

### 4. 搜索工具展开后入参与输出重叠 🟡

**现象**: 展开搜索工具卡片后，入参区域（pattern/path）和输出区域视觉重叠，入参不可见。

**根因**: `SearchToolCard.kt` 的 `expandedContent` 是唯一返回多个根级 composable 的工具卡片（`Surface` + `Spacer` + `Surface`）。`ToolCardScaffold` 将此 lambda 放在 `AnimatedVisibility` 中，而 `AnimatedVisibility` 对多子元素按堆叠（Box-like）处理，导致入参 Surface 和出参 Surface 叠在一起。

**影响范围**: 所有 glob/grep 工具卡片。

### 5. 代码块复制无反馈提示 🟡

**现象**: 点击代码块复制按钮后无任何视觉/触觉反馈，用户无法确认是否复制成功。

**根因**: mikepenz `MarkdownHighlightedCodeBlock` 内置复制按钮由库内部实现，不暴露 `onCopy` 回调。

**影响范围**: 所有代码块。

---

## 修复方案

### 修复 1+5: 自制代码块组件 `OcCodeBlock`

**替代** mikepenz 的 `MarkdownHighlightedCodeBlock`/`MarkdownHighlightedCodeFence` 的 `showHeader=true` 用法。

**组件结构**:
```
Box(codeBlockBg + clip)
├── MarkdownHighlightedCodeBlock(showHeader=false)  // 语法高亮，无标题栏
└── IconButton(Alignment.TopEnd)                     // 浮动复制按钮
        onClick → clipboardManager.setText() + Toast("已复制")
```

**参数**: `content: String, node: ASTNode, style: TextStyle, highlightsBuilder: Highlights.Builder, wordWrap: Boolean`

**双分支处理**:
- `wordWrap=true`: 代码自动换行，无横向滚动
- `wordWrap=false`: 代码横向可滚动（保留现有 `codeHorizontalScroll` 逻辑）

**文件改动**:
- 新建: `.../chat/markdown/OcCodeBlock.kt` (~60 行)
- 修改: `MarkdownContent.kt` — 两个分支的 `codeBlock`/`codeFence` 替换为 `OcCodeBlock`

**副作用修复**: 同时解决 Issue 5（复制 Toast 提示）。

### 修复 2: 路径截取跨平台兼容

**EditToolCard.kt:58**
```diff
- val shortPath = filePath.substringAfterLast('/')
+ val shortPath = java.io.File(filePath).name
```

**ReadToolCard.kt:54** 同上。

### 修复 3: 子 agent 卡片隐藏展开图标

**ToolCardScaffold.kt** — 新增参数:
```kotlin
showExpandIcon: Boolean = true
```
第 161 行 `Icon(ExpandLess/ExpandMore)` 包一层 `if (showExpandIcon)`。

**TaskToolCard.kt** — 调用处传入:
```kotlin
ToolCardScaffold(
    hasContent = showNavArrow || hasOutput,  // 等价于现有逻辑，不变
    showExpandIcon = !showNavArrow,           // 导航卡片不显示展开图标
    ...
)
```

**说明**: `hasContent` 逻辑保持不变（`showNavArrow || hasOutput`），因为 `rightSideExtras`（箭头）渲染在 `ToolCardScaffold` 的 `else if (hasContent)` 分支内。`showExpandIcon` 为 `false` 时只隐藏图标，不影响箭头和复制按钮的显示。

### 修复 4: 搜索入参区域正确布局

**SearchToolCard.kt** — `expandedContent` 套一层 `Column`:
```diff
  ) {
+     Column {
          if (pattern != null || !dirPath.isNullOrBlank()) {
              Surface { ... }
              Spacer(Modifier.height(4.dp))
          }
          val halfScreenHeight = halfScreenHeight()
          Surface(...) { MarkdownContent(...) }
+     }
  }
```

---

## 不改动的范围

- 不改 `MarkdownTable.kt`（表格自适应不是卡顿根因）
- 不改 `SearchToolCard` 入参块的 key 名（`pattern`/`path` 确认正确）
- 不改其他工具卡片的 `expandedContent` 结构（它们都只返回单个根 composable）

---

## 验证清单

| # | 验证项 | 方法 |
|---|--------|------|
| 1 | 列表滚动流畅 | 聊天页面上下快速滑动，含多个代码块的 AI 回复 |
| 2 | 读取编辑标题 | 查看 Read/Edit 卡片的标题是否只显示文件名 |
| 3 | 子 agent 箭头 | 有子会话链接的 Task 卡片不显示展开/折叠图标 |
| 4 | 搜索入参显示 | 展开 glob/grep 卡片，入参在输出上方独立显示，不重叠 |
| 5 | 复制提示 | 点击代码块复制按钮，系统弹出 Toast "已复制" |

---

## 发版

- 修复合并后构建 `assembleBetaRelease` → tag `v2.0.0-beta.116` → 上传 GitHub Release
