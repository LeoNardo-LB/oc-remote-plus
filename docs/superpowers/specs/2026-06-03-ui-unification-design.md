# UI 统一设计文档

> 创建时间: 2026-06-03
> 关联任务: #8 弹窗统一、#10 设置页样式统一、#6 弹窗间距（随迁移解决）、#7 ServerCard 间距优化
> 状态: 待审核

---

## 一、目标

统一项目中 28 个弹窗的容器、AMOLED 适配、按钮布局，以及 3 个服务器设置页的列表样式，消除 4 套弹窗风格和 2 套列表风格并存的碎片化问题。

---

## 二、当前现状

### 弹窗（28 个，4 种容器）

| 容器类型 | 数量 | AMOLED 处理方式 |
|----------|------|-----------------|
| `AppDialog`（自定义，基于 `Dialog`） | 14 | `AmoledSurface` 内置 |
| `AlertDialog`（M3 原生） | 7 | 部分无适配，部分用 `amoledDialogModifier()` |
| `BasicAlertDialog` + `Surface`（M3 原生） | 6 | 每个弹窗手动 `if/else` |
| `Dialog` + `Surface`（全屏） | 1 | 默认 surface |

### 列表（2 种风格）

| 风格 | 页面 | 组件 |
|------|------|------|
| 扁平列表 | SettingsScreen | `ListItem` + `HorizontalDivider` + `SectionHeader` |
| 卡片式 | ServerSettings / ServerProviders / ServerModelFilter | `AmoledCard` + `spacedBy(12dp)` |

---

## 三、设计决策

### 3.1 弹窗容器：全部统一为 BasicAlertDialog + Surface

**不使用** 原生 `AlertDialog` 的 `title`/`text`/`confirmButton`/`dismissButton` 语义化槽位。所有弹窗统一使用 `BasicAlertDialog` + `Surface` 自定义布局，内部结构为：

```
BasicAlertDialog
└── Surface(color, shape, border, tonalElevation)
    └── Column
        ├── Title 区（可选）
        ├── Content 区
        └── DialogButtons 区（统一按钮组件）
```

**理由**：
- 原生 `AlertDialog` 的 2 按钮槽位无法覆盖 3+ 按钮场景
- 自定义布局可以统一所有弹窗的标题样式、间距、分隔线
- AMOLED 适配只需处理 `Surface` 参数，逻辑一致

### 3.2 统一按钮区组件：DialogButtons

新建 `DialogButtons` Composable，取代原生 `confirmButton`/`dismissButton` 和旧 `AppDialogButtons`。

**布局规则**：
- 1 个按钮：单行，右对齐
- 2 个按钮：`Row` 横排，右对齐
- 3+ 个按钮：`Column` 纵排，全宽

**按钮样式**：

| 角色 | 组件 | 说明 |
|------|------|------|
| 主操作（确认/保存/创建） | `FilledTonalButton` | primary 色调 |
| 次要/取消 | `TextButton` | 默认色 |
| 危险操作（删除/撤回） | `TextButton` | error 色 |

**接口设计**：

```kotlin
enum class DialogButtonRole { Primary, Secondary, Danger }

@Composable
fun DialogButtons(
    buttons: List<Triple<String, DialogButtonRole, () -> Unit>>
)
```

### 3.3 AMOLED 统一适配：AmoledDialogParams

新建数据类 + 工厂函数，取代当前 4 种 AMOLED 处理方式。

```kotlin
data class AmoledDialogParams(
    val containerColor: Color,
    val tonalElevation: Dp,
    val border: BorderStroke?,
    val shape: Shape
)

@Composable
fun amoledDialogParams(
    normalColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    normalElevation: Dp = 6.dp,
    shape: Shape = ShapeTokens.extraLarge
): AmoledDialogParams
```

**行为**：
- AMOLED 模式：`Color.Black` + `0.dp` 色调高度 + `1dp outlineVariant/HIGH 边框` + 指定 shape
- 普通模式：指定 color + 指定 elevation + 无边框 + 指定 shape

**位置**：`ui/components/AmoledDialogParams.kt`

**替代关系**：
- 替代 `amoledDialogModifier()` + `amoledDialogContainerColor()`（SettingsDisplayNames.kt）
- 替代每个 BasicAlertDialog 弹窗中散落的 `if (isAmoled) Color.Black + border + 0.dp`
- 替代 AppDialog 中的 `AmoledSurface` 用法

### 3.4 组件提取

| 组件 | 来源 | 目标位置 | 说明 |
|------|------|---------|------|
| `AppPickerList` | AppDialog.kt 内部 | `ui/components/AppPickerList.kt` | LazyColumn 选择列表，8 个 Picker 弹窗共用 |
| `DetailRow` | SessionRow.kt / DirectoryTreeNode.kt 的 private 重复定义 | `ui/components/DetailRow.kt` | label 30% + value 70% 的展示行 |
| 撤回确认弹窗 | MessageCard.kt / ChatMessageList.kt 的重复代码 | `ui/components/ConfirmDialog.kt` | 通用确认弹窗，支持自定义标题/消息/按钮角色 |

### 3.5 设置页样式统一（#10）

3 个服务器设置页全部改为 SettingsScreen 的扁平列表风格：

| 页面 | 改动 |
|------|------|
| `ServerSettingsScreen.kt` | `AmoledCard` + `spacedBy(12dp)` → `ListItem` + `HorizontalDivider` |
| `ServerProvidersScreen.kt`（列表部分） | `ProviderRow(Card)` + `spacedBy(12dp)` → `ListItem` + `HorizontalDivider` + `SectionHeader` |
| `ServerModelFilterScreen.kt` | `AmoledCard` 分组 → `ListItem`(Switch 在 trailingContent) + `HorizontalDivider` + `SectionHeader` |

### 3.6 ServerCard 间距优化（#7）

| 位置 | 当前 | 目标 |
|------|------|------|
| 内部垂直间距 | `spacedBy(12.dp)` | `spacedBy(8.dp)` |
| 按钮水平间距 | `spacedBy(8.dp)` | `spacedBy(6.dp)` |

### 3.7 Bug 修复

`LocalServerLaunchOptionsDialog.kt` 的超大表单（15+ 组件）text 区缺少 `verticalScroll`，补上 `Column(modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(max = 屏幕高度 * 0.7f))`。

---

## 四、全量弹窗迁移清单

### 第一组：已是 AlertDialog → 改为 BasicAlertDialog（7 个）

| # | 弹窗 | 文件 | 类型 | 改动要点 |
|---|------|------|------|---------|
| 1 | Token 详情 | ChatTopBar.kt:157 | 展示型 | AlertDialog→BasicAlertDialog, 补 AMOLED |
| 2 | 重命名会话（聊天页） | ChatScreenDialogs.kt:22 | 表单型 | 同上 + OutlinedTextField |
| 3 | 发送确认 | ChatScreenDialogs.kt:64 | 确认型 | 同上 + 简单 Text |
| 4 | 恢复压缩确认 | ChatScreenDialogs.kt:92 | 确认型 | 同上 + error 色按钮 |
| 5 | 撤回确认（MessageCard） | MessageCard.kt:301 | 确认型 | 改用共享 ConfirmDialog |
| 6 | 撤回确认（ChatMessageList） | ChatMessageList.kt:250 | 确认型 | 改用共享 ConfirmDialog，删除重复代码 |
| 7 | 字号选择 | TerminalFontSizeDialog.kt | 选择型 | AlertDialog→BasicAlertDialog, Slider 内容 |

### 第二组：AppDialog → 改为 BasicAlertDialog（14 个）

#### 简单（标准 title + 内容 + 1~2 按钮）

| # | 弹窗 | 文件 | 类型 | 内容组件 |
|---|------|------|------|---------|
| 8 | 删除确认 | SessionListScreen.kt:334 | 确认型 | Text |
| 9 | 重命名会话 | SessionListScreen.kt:306 | 表单型 | OutlinedTextField |
| 10 | 创建文件夹 | OpenProjectDialog.kt:231 | 表单型 | OutlinedTextField + error |
| 11 | 字体大小选择 | FontSizePickerDialog.kt | 选择型 | AppPickerList(3项) |
| 12 | 消息数量选择 | MessageCountPickerDialog.kt | 选择型 | AppPickerList(4项) |
| 13 | 重连模式选择 | ReconnectModePickerDialog.kt | 选择型 | AppPickerList(3项) |
| 14 | 图片压缩-最大边 | ImageCompressionDialog.kt | 选择型 | AppPickerList |
| 15 | 图片压缩-质量 | ImageCompressionDialog.kt | 选择型 | AppPickerList |

#### 中等（需要特殊处理）

| # | 弹窗 | 文件 | 类型 | 处理要点 |
|---|------|------|------|---------|
| 16 | 目录详情 | DirectoryTreeNode.kt:129 | 展示型 | SelectionContainer + 共享 DetailRow |
| 17 | 主题选择 | ThemePickerDialog.kt | 选择型 | AppPickerList + heightIn(480dp) |
| 18 | 语言选择 | LanguagePickerDialog.kt | 选择型 | AppPickerList(16项) + heightIn(520dp) |
| 19 | 超时选择 | LocalLaunchOptionsDialog.kt:312 | 选择型 | 改用 AppPickerList 替代手动 LazyColumn |

#### 较难（多按钮/复杂布局）

| # | 弹窗 | 文件 | 类型 | 处理要点 |
|---|------|------|------|---------|
| 20 | 会话详情 | SessionRow.kt:181 | 展示型 | 3 按钮（复制/重命名/删除）放 text 区底部，用 DetailRow |
| 21 | 目录选择器 | OpenProjectDialog.kt:57 | 混合型 | LazyColumn + 路径栏 + heightIn 约束 |
| 22 | 启动选项 | LocalServerLaunchOptionsDialog.kt | 表单型 | 超大表单，补 verticalScroll + heightIn |

### 第三组：已是 BasicAlertDialog，统一 AMOLED 处理（6 个）

| # | 弹窗 | 文件 | 类型 | 改动要点 |
|---|------|------|------|---------|
| 23 | 编辑服务器 | ServerDialog.kt | 表单型 | 手动 AMOLED→amoledDialogParams() |
| 24 | 连接方法选择 | ServerProvidersScreen.kt:149 | 选择型 | 同上 |
| 25 | API Key 输入 | ServerProvidersScreen.kt:201 | 表单型 | 同上 |
| 26 | OAuth 认证 | ServerProvidersScreen.kt:254 | 混合型 | 同上 |
| 27 | 模型选择 | ModelPickerDialog.kt | 选择型 | 同上 + 补 border/elevation 一致性 |
| 28 | 图片预览 | ImagePreviewDialog.kt | 展示型 | 同上 |

### 第四组：全屏 Dialog，不改动（1 个）

| # | 弹窗 | 文件 | 说明 |
|---|------|------|------|
| 29 | Markdown 预览 | MarkdownPreviewDialog.kt | 全屏 Dialog + TopAppBar，非弹窗结构，保持不变 |

---

## 五、新增/修改文件清单

### 新增文件

| 文件 | 内容 |
|------|------|
| `ui/components/AmoledDialogParams.kt` | `AmoledDialogParams` 数据类 + `amoledDialogParams()` 工厂函数 |
| `ui/components/DialogButtons.kt` | 统一按钮区组件（`DialogButtons` + `DialogButtonRole`） |
| `ui/components/AppPickerList.kt` | 从 AppDialog.kt 提取的独立选择列表组件 |
| `ui/components/DetailRow.kt` | 从 SessionRow/DirectoryTreeNode 提取的共享展示行 |
| `ui/components/ConfirmDialog.kt` | 通用确认弹窗（撤回/删除等场景复用） |

### 删除文件

| 文件 | 原因 |
|------|------|
| `ui/components/AppDialog.kt` | 被 BasicAlertDialog + 统一组件替代 |

### 修改文件（弹窗迁移）

| 文件 | 改动概要 |
|------|---------|
| `ui/screens/chat/components/ChatTopBar.kt` | AlertDialog→BasicAlertDialog + AMOLED |
| `ui/screens/chat/dialog/ChatScreenDialogs.kt` | 3 个弹窗改容器 + AMOLED |
| `ui/screens/chat/components/MessageCard.kt` | 撤回弹窗改用 ConfirmDialog |
| `ui/screens/chat/components/ChatMessageList.kt` | 撤回弹窗改用 ConfirmDialog，删重复 |
| `ui/screens/settings/components/TerminalFontSizeDialog.kt` | AlertDialog→BasicAlertDialog |
| `ui/screens/settings/components/LocalServerLaunchOptionsDialog.kt` | 补滚动 + AMOLED 统一 |
| `ui/screens/settings/components/ThemePickerDialog.kt` | AppDialog→BasicAlertDialog |
| `ui/screens/settings/components/LanguagePickerDialog.kt` | 同上 |
| `ui/screens/settings/components/FontSizePickerDialog.kt` | 同上 |
| `ui/screens/settings/components/MessageCountPickerDialog.kt` | 同上 |
| `ui/screens/settings/components/ReconnectModePickerDialog.kt` | 同上 |
| `ui/screens/settings/components/ImageCompressionDialog.kt` | 同上（2 个弹窗） |
| `ui/screens/sessions/SessionListScreen.kt` | 2 个 AppDialog→BasicAlertDialog |
| `ui/screens/sessions/components/SessionRow.kt` | 会话详情弹窗改容器，删 private DetailRow |
| `ui/screens/sessions/components/DirectoryTreeNode.kt` | 目录详情弹窗改容器，删 private DetailRow |
| `ui/screens/sessions/components/OpenProjectDialog.kt` | 2 个 AppDialog→BasicAlertDialog |
| `ui/screens/home/components/ServerDialog.kt` | AMOLED 手动→amoledDialogParams() |
| `ui/screens/home/components/LocalLaunchOptionsDialog.kt` | 超时选择改用 AppPickerList |
| `ui/screens/server/ServerProvidersScreen.kt` | 3 个弹窗 AMOLED 统一 + 列表样式改 ListItem |
| `ui/screens/chat/dialog/ModelPickerDialog.kt` | AMOLED 统一 + 补 border/elevation |
| `ui/screens/chat/dialog/ImagePreviewDialog.kt` | AMOLED 统一 |

### 修改文件（列表样式统一 #10）

| 文件 | 改动概要 |
|------|---------|
| `ui/screens/server/ServerSettingsScreen.kt` | AmoledCard→ListItem + HorizontalDivider |
| `ui/screens/server/ServerProvidersScreen.kt`（列表部分） | Card→ListItem + SectionHeader |
| `ui/screens/server/ServerModelFilterScreen.kt` | AmoledCard 分组→ListItem + SectionHeader |

### 修改文件（间距 #7 + 清理）

| 文件 | 改动概要 |
|------|---------|
| `ui/screens/home/ServerCard.kt` | spacedBy(12→8dp)、(8→6dp) |
| `ui/screens/settings/components/SettingsDisplayNames.kt` | 删除旧的 `amoledDialogModifier()` 和 `amoledDialogContainerColor()` |

---

## 六、不涉及的文件

| 文件 | 原因 |
|------|------|
| `MarkdownPreviewDialog.kt` | 全屏 Dialog + TopAppBar，非弹窗结构，不在统一范围内 |
| `AmoledSurface.kt` | 仍被非弹窗场景使用，保留 |
| `AmoledCard.kt` | 不在本次统一范围内（ServerCard 等非列表场景仍使用） |

---

## 七、组件依赖关系

所有组件均为 Material 3 原生或项目自定义，**不引入任何第三方依赖**。

```
BasicAlertDialog (M3 原生)
├── Surface (M3 原生)
│   └── AmoledDialogParams (新增)
├── Title 区 (自定义: Text headlineSmall → titleMedium)
├── Content 区
│   ├── Text (M3)
│   ├── OutlinedTextField (M3)
│   ├── Slider (M3)
│   ├── AppPickerList (提取)
│   ├── DetailRow (提取)
│   ├── SelectionContainer (Compose 原生)
│   └── LazyColumn (Compose 原生)
└── DialogButtons (新增)
    ├── FilledTonalButton (M3)
    └── TextButton (M3)
```

---

## 八、验证标准

每阶段改动后执行 `.\gradlew :app:compileDevDebugKotlin` 确认编译通过。

视觉验证：Light / Dark / AMOLED 三种主题下截图对比，确认：
- 弹窗标题、内容、按钮布局一致
- AMOLED 模式下弹窗背景纯黑 + 边框可见
- 列表样式与 SettingsScreen 一致
- ServerCard 按钮间距缩小后视觉协调
