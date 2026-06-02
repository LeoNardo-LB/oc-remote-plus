# 延期 UI 统一任务

> 创建时间: 2026-06-02
> 状态: 待排期
> 原因: 涉及面广（~10 个文件），需要专项设计和验证

---

## 一、弹窗统一 (#8)

### 目标
将项目中所有弹窗统一为原生 Material 3 `AlertDialog` 组件，移除自定义 `AppDialog`。

### 现状分析

项目中存在 **4 套弹窗样式**：

| 风格 | 组件 | 使用场景 |
|------|------|---------|
| **Style A** | `AppDialog`（自定义） | 会话树页面：详情/重命名/删除、目录选择器 |
| **Style B** | `AlertDialog` + `amoledDialogModifier` | 设置页：TerminalFontSize、LocalServerLaunchOptions |
| **Style C** | `BasicAlertDialog` + `Surface` | 主页：编辑服务器；服务器设置：提供商/模型选择；图片预览 |
| **Style D** | 原生 `AlertDialog` | 聊天页：上下文详情、重命名、确认发送 |

### 弹窗分类

用户将弹窗分为三类：

1. **展示型** — 详情信息展示（会话详情、目录详情、token 详情）
2. **选择型** — 列表选择（提供商选择、模型选择、目录选择、字号选择、启动选项选择）
3. **表单型** — 输入编辑（编辑服务器名称/地址、重命名会话/目录）

### 需要统一的目标弹窗

| 弹窗 | 当前样式 | 目标样式 | 文件 |
|------|---------|---------|------|
| 会话详情 | AppDialog | 原生 AlertDialog | SessionRow.kt |
| 目录详情 | AppDialog | 原生 AlertDialog | DirectoryTreeNode.kt |
| 重命名 | AppDialog | 原生 AlertDialog | SessionRow.kt / DirectoryTreeNode.kt |
| 删除确认 | AppDialog | 原生 AlertDialog | SessionRow.kt / DirectoryTreeNode.kt |
| 目录选择器 | AppDialog | 原生 AlertDialog | OpenProjectDialog.kt |
| 编辑服务器 | BasicAlertDialog+Surface | 原生 AlertDialog | ServerDialog.kt |
| 提供商选择 | BasicAlertDialog+Surface | 原生 AlertDialog | ServerProvidersScreen.kt |
| 模型选择 | BasicAlertDialog+Surface | 原生 AlertDialog | ModelPickerDialog.kt |
| 上下文详情 | 原生 AlertDialog | 保持原生 | ChatTopBar.kt |
| 字号选择 | AlertDialog+amoledModifier | 原生 AlertDialog | TerminalFontSizeDialog.kt |
| 启动选项 | AlertDialog+amoledModifier | 原生 AlertDialog | LocalServerLaunchOptionsDialog.kt |
| 图片预览 | BasicAlertDialog+Surface | 原生 AlertDialog | ImagePreviewDialog.kt |

### 关键技术问题

- **AMOLED 适配**: 原生 `AlertDialog` 默认不支持纯黑背景，需要通过 `MaterialTheme.colorScheme` 或 `onCustomAvailable` 适配
- **AppDialog.kt 处理**: 统一完成后可删除 `AppDialog.kt` 及相关辅助组件
- **回归验证**: 每个页面改完后需要 AMOLED/Dark/Light 三种主题截图验证

### 实施建议

分阶段逐页面统一，每个页面独立 commit：

1. 设置页弹窗（TerminalFontSize + LocalServerLaunchOptions）— 最简单
2. 服务器设置页弹窗（ServerDialog + ProviderPicker + ModelPicker）
3. 聊天页弹窗（ChatTopBar token 详情）
4. 会话树页弹窗（SessionRow + DirectoryTreeNode + OpenProjectDialog）— 改动最大
5. 删除 AppDialog.kt

---

## 二、设置页与服务器设置页样式统一 (#10)

### 目标
将服务器设置页的列表样式统一为设置页的分块列表样式。

### 现状对比

| 维度 | 设置页 | 服务器设置页 |
|------|--------|-------------|
| 列表组件 | `ListItem`（M3 原生） | `AmoledCard` + `Row`（自定义卡片） |
| 分隔方式 | `HorizontalDivider` + padding | `spacedBy(12.dp)` |
| 分区标题 | `SectionHeader`（labelMedium+primary） | 无分区 |
| 卡片边框 | 无 | `AmoledDefaultBorder` |

### 需要统一的页面

- `ServerSettingsScreen.kt` — 主列表
- `ServerProvidersScreen.kt` — 提供商列表
- `ServerModelFilterScreen.kt` — 模型过滤列表

### 实施建议

在弹窗统一完成后再做，因为弹窗样式也会影响这些页面的交互体验。

---

## 三、弹窗间距优化 (#6)

### 目标
减小长按弹窗标题字体和按钮间距，使其更紧凑。

### 具体改动
- 标题: `headlineSmall` (~20sp) → `titleMedium` (~16sp)
- 按钮 padding: `12dp` → `8dp`
- 按钮间距 (Column spacedBy): `12dp` → `8dp`

### 涉及文件
- `ui/components/AppDialog.kt`

---

## 四、主页卡片按钮间距优化 (#7)

### 目标
减小 ServerCard 中按钮间距。

### 具体改动
- 水平间距: `spacedBy(8.dp)` → `spacedBy(6.dp)`
- 垂直间距: `spacedBy(12.dp)` → `spacedBy(8.dp)`

### 涉及文件
- `ui/screens/home/ServerCard.kt`

---

## 备注

- 弹窗统一后，`AppDialog.kt` 及其辅助组件（`AppDialogButtons`、`AppPickerList` 等）可安全删除
- 如果弹窗统一决定使用原生组件，`amoledDialogModifier()` 辅助函数也需要重新评估
- 建议在实施前先用视觉伴侣做一个原生 AlertDialog 在 AMOLED 下的 mockup 确认效果
