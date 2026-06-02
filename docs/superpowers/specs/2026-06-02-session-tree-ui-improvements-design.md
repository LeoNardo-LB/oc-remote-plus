# Session Tree UI Improvements — Design Spec

**Date**: 2026-06-02  
**Status**: Approved  
**Scope**: 方案 B — 统一弹窗系统 + 8 项 UI 修复

---

## 1. 改动总览

| # | 问题 | 改动位置 | 类别 |
|---|------|----------|------|
| P1 | AppDialog / SettingsPickerDialog 双轨弹窗 | `AppDialog.kt` 重构, 删除 `SettingsPickerDialog.kt`, 6 个 Picker 迁移 | **核心重构** |
| P2 | 弹窗按钮纯文字无层级 | `AppDialog.kt` `DialogButton()` / `AppDialogButtons()` | 样式 |
| P3 | KV 纵向排布 → 横向表格 + 长按复制 | `SessionRow.kt`, `DirectoryTreeNode.kt` 的 `DetailRow` | 交互 |
| P4 | 弹窗边距过大 | `AppDialog.kt` 内容区/头部 padding | 样式 |
| P5 | FAB 弹窗文件夹图标不一致 | `DirectoryRow.kt` | 样式 |
| P6 | 顶栏单行 → 双行 | `SessionListScreen.kt` `TopAppBar` | 布局 |
| P7 | 会话图标 Busy/Idle 区分度低 | `SessionRow.kt` 图标 + 颜色 | 视觉 |
| P8 | 目录 item 显示活跃会话数 | `TreeNode.kt` 模型 + `DirectoryTreeNode.kt` UI | 功能 |

**涉及文件**: 13 个（1 删除, 1 核心重构, 11 适配）

---

## 2. P1 — 统一弹窗系统

### 2.1 当前状态

项目有两套互不兼容的弹窗：

| 属性 | AppDialog | SettingsPickerDialog |
|------|-----------|---------------------|
| 容器 | `Dialog` + `AmoledSurface` | `BasicAlertDialog` + `Surface` |
| 圆角 | `ShapeTokens.large` (16dp) | `ShapeTokens.largeMedium` (20dp) |
| 关闭按钮 | ✅ | ❌ |
| 分割线 | ✅ (2 条 `HorizontalDivider`) | ❌ |
| 内容 padding | h=24dp, v=16dp | h=16dp, v=14dp (inner) |
| 滚动 | ❌ | ✅ (LazyColumn + maxHeight) |
| 宽度 | fillMaxWidth(0.92f) | fillMaxWidth() |
| 按钮区 | AppDialogButtons (Row/Column) | 单个 Cancel TextButton |

### 2.2 目标

统一为单一 `AppDialog`，通过可选参数覆盖所有场景。

### 2.3 新 AppDialog API

```kotlin
@Composable
fun AppDialog(
    onDismiss: () -> Unit,
    title: String,
    isAmoled: Boolean = isAmoledTheme(),
    showClose: Boolean = true,          // 新增: Picker 类隐藏
    showDividers: Boolean = true,        // 新增: Picker 类隐藏
    maxBodyHeight: Dp? = null,          // 新增: 列表类限制高度
    scrollable: Boolean = false,         // 新增: 内容区用 LazyColumn
    content: @Composable ColumnScope.() -> Unit,
    buttons: @Composable ColumnScope.() -> Unit,
)
```

**行为规则**:
- `showClose = false` → 隐藏 Close 图标
- `showDividers = false` → 移除两条分割线
- `scrollable = true` → 内容区改用 `LazyColumn` 包裹，配合 `maxBodyHeight`
- 不传 `scrollable` / `maxBodyHeight` 时行为与旧版一致

### 2.4 样式统一

| 区域 | 旧值 | 新值 |
|------|------|------|
| 圆角 | large/largeMedium | **large (16dp)** 统一 |
| 头部 padding | top=20, bottom=12 | **top=16, bottom=8** |
| 内容 padding | h=24, v=16 | **h=16, v=12** |
| 按钮 padding | h=24, v=16 | **h=16, v=12** |
| 分割线 | outlineVariant FAINT | 不变 |

### 2.5 Picker 迁移

6 个文件从 `SettingsPickerDialog` 迁移为 `AppDialog(scrollable=true, showClose=false, showDividers=false, maxBodyHeight=480.dp)`:

| 文件 | 改动摘要 |
|------|----------|
| `ThemePickerDialog.kt` | `SettingsPickerDialog(...)` → `AppDialog(scrollable=true, ...)` |
| `ReconnectModePickerDialog.kt` | 同上 |
| `MessageCountPickerDialog.kt` | 同上 |
| `LanguagePickerDialog.kt` | 同上 |
| `ImageCompressionDialog.kt` | 同上 (含第二处调用) |
| `FontSizePickerDialog.kt` | 同上 |
| `SettingsPickerDialog.kt` | **删除** |

每个 Picker 文件需内联原 `SettingsPickerDialog` 的列表项逻辑（`Row` + `LazyColumn` 选择项），因为 `AppDialog` 不再内含选择逻辑。

---

## 3. P2 — 按钮层级

### 3.1 当前

`ButtonStyle.Primary` 和 `ButtonStyle.Secondary` 都渲染为 `TextButton`，视觉无区分。`ButtonStyle.Danger` 为红色 `TextButton`。

### 3.2 改为 Material 3 三级按钮

```kotlin
ButtonStyle.Primary   → FilledTonalButton (实色背景，用于主操作如 "Create Session")
ButtonStyle.Secondary → OutlinedButton  (描边，用于辅助操作如 "Copy"、"Rename")  
ButtonStyle.Danger    → OutlinedButton(contentColor = error) (描边+红色，用于 "Delete")
```

`DialogButton` 函数改为 `switch` 不同按钮组件。`AppDialogButtons` 布局逻辑不变（≤2 个 Row, ≥3 个 Column）。

---

## 4. P3 — 详情表格 + 长按复制

### 4.1 当前

`SessionRow.kt` 和 `DirectoryTreeNode.kt` 中 `DetailRow` 使用 `Column` (label 上, value 下)，不支持文本选择和复制。

### 4.2 改为 SelectionContainer 表格

```kotlin
@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = labelSmall, color = onSurfaceVariant, modifier = Modifier.weight(0.3f))
        Spacer(Modifier.width(8.dp))
        Text(value, style = bodySmall, color = onSurface, modifier = Modifier.weight(0.7f))
    }
}
```

外层包裹 `SelectionContainer { Column { ... } }`，用户长按即可框选复制任意行。**复用 Compose 原生能力，零新增依赖。**

---

## 5. P4 — 边距缩小

见 §2.4 表格。AppDialog 内容区 padding 从 `horizontal=24, vertical=16` 统一为 `horizontal=16, vertical=12`，头部 top padding 从 20 → 16。

---

## 6. P5 — FAB 弹窗文件夹图标

`DirectoryRow.kt` L59 当前：

```kotlin
tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
```

改为与 `DirectoryTreeNode.kt` 一致：

```kotlin
tint = MaterialTheme.colorScheme.primary
```

---

## 7. P6 — 顶栏双行

### 7.1 当前

`TopAppBar` title 为单行 `Row`（servername + ▾ 箭头）。

### 7.2 改为两行 Column

```
←  My Server                    ←  row1: navigationIcon + serverName (titleMedium bold)
   D:/Develop/projects  ▾        ←  row2: baseDirectory path (labelSmall) + ▾ 箭头
```

- 第一行: `Text(serverName, style=titleMedium)` 保持可点
- 第二行: `Row(Text(baseDir, style=labelSmall, color=onSurfaceVariant), Icon(ArrowDropDown))` 保持可点
- 整行 `Column` 包裹在 `clickable` 中触发展开 baseDir 选择器

---

## 8. P7 — 会话图标三态区分

### 8.1 图标方案

| 状态 | 图标 | 颜色 |
|------|------|------|
| Busy | `Icons.Filled.ChatBubble` | `tertiary` (青色) |
| Idle | `Icons.Outlined.ChatBubbleOutline` | `onSurfaceVariant.copy(alpha = FAINT(0.35))` |
| Retry | `Icons.Filled.ErrorOutline` | `error` (红色) |

- Busy 用 **Filled** 变体 (实心)，视觉重量最大
- Idle 用 **Outlined** 变体 (描边) + **FAINT** alpha，视觉最轻
- Retry 改用 **ErrorOutline** 图标，不再与 Idle/Busy 共用 ChatBubble 外形

### 8.2 对比度变化

| 对比对 | 旧方案 | 新方案 |
|--------|--------|--------|
| Busy ↔ Idle | Outlined(tertiary) vs Outlined(MUTED) | **Filled(tertiary) vs Outlined(FAINT)** |
| Retry ↔ Busy | Outlined(error) vs Outlined(tertiary) | **ErrorOutline(error) vs Filled(tertiary)** |

新方案依靠 **图标形状** (Filled/Outlined/ErrorOutline) + **透明度** (FAINT) 双重区分。

---

## 9. P8 — 目录活跃会话数

### 9.1 数据模型

`TreeNode.Directory` 新增字段：

```kotlin
data class Directory(
    override val id: String,
    val path: String,
    val displayName: String,
    val sessionCount: Int,
    val activeSessionCount: Int,  // ← 新增
    val isExpanded: Boolean,
) : TreeNode
```

`buildTreeNodes()` 中计算: 遍历 `statuses` 统计 `is Busy` 的 session 数量。

### 9.2 UI 显示

```kotlin
// DirectoryTreeNode.kt
if (node.activeSessionCount > 0) {
    Row {
        Text("${node.activeSessionCount}", color = primary, fontWeight = Medium)  // 高亮
        Text("/${node.sessionCount} sessions active", color = MUTED)
    }
} else {
    Text("${node.sessionCount} sessions", color = MUTED)  // 不变
}
```

需要新增 string resource: `directory_session_count_active` = `"%1$d/%2$d sessions active"`

---

## 10. 实现顺序

建议按依赖关系和风险排序：

| 顺序 | 任务 | 依赖 |
|------|------|------|
| 1 | P1 统一 AppDialog（核心重构） | 无 |
| 2 | P2 按钮层级 + P4 边距（属 AppDialog 内部） | P1 |
| 3 | P1 的 Picker 迁移 (6 files) | P1 |
| 4 | P3 DetailRow → SelectionContainer | 无 |
| 5 | P5 文件夹图标 | 无 |
| 6 | P6 顶栏双行 | 无 |
| 7 | P7 会话图标 + P8 活跃会话数 (SessionRow/DirectoryTreeNode 相关) | P3 |
| 8 | P8 TreeNode 模型字段 + buildTreeNodes | 无 |

P1–P3 完成后需 `compileDevDebugKotlin` 验证编译通过。

---

## 11. 验证标准

| 验证项 | 方法 |
|--------|------|
| 编译 | `.\gradlew :app:compileDevDebugKotlin` |
| 弹窗外观 | 目视检查 6 个 Picker + 4 个 Alert 弹窗样式一致 |
| 按钮层级 | 确认 Primary=填充, Secondary=描边, Danger=描边红 |
| 长按复制 | 弹窗内长按文本 → 出现系统选择手柄 → 可复制 |
| 图标对比 | Busy(实心青) vs Idle(描边灰) 明显可区分 |
| 活跃计数 | 目录项显示 "3/5 sessions active" 格式 |

---

## 12. 不动范围

- `AppDialogButtons` 的布局逻辑 (≤2 Row, ≥3 Column) 不变
- `ButtonStyle` enum 保留，不改命名
- `SettingsPickerDialog` 的列表项高亮 + check 图标逻辑内联到各 Picker
- `SessionListViewModel` 不修改（P8 所需 activeSessionCount 在 buildTreeNodes 中计算）
- 不影响聊天页、设置页、Home 页的其他 UI
