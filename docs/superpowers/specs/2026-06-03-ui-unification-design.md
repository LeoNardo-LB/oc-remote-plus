# UI 统一设计文档

> 创建时间: 2026-06-03
> 关联任务: #8 弹窗统一、#10 设置页样式统一、#6 弹窗间距（随迁移解决）、#7 ServerCard 间距优化
> 状态: 待审核

---

## 一、目标

统一项目中 31 个弹窗的容器、AMOLED 适配、按钮布局，以及 3 个服务器设置页的列表样式，消除 4 套弹窗风格和 2 套列表风格并存的碎片化问题。

---

## 二、当前现状

### 弹窗（31 个，4 种容器）

| 容器类型 | 数量 | AMOLED 处理方式 |
|----------|------|-----------------|
| `AppDialog`（自定义，基于 `Dialog`） | 13 | `AmoledSurface` 内置 |
| `AlertDialog`（M3 原生） | 8 | 部分无适配，部分用 `amoledDialogModifier()` |
| `BasicAlertDialog` + `Surface`（M3 原生） | 7 | 每个弹窗手动 `if/else` |
| `Dialog` + `Surface`（全屏） | 3 | 默认 surface |

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

**注意**：`BasicAlertDialog` 需要 `@OptIn(ExperimentalMaterial3Api::class)` 标注，所有调用处均需添加。

#### 迁移补偿清单

从 `AppDialog` 迁移到 `BasicAlertDialog` 时，AppDialog 内置但 BasicAlertDialog 没有的能力需手动补偿：

| # | 能力 | 补偿方式 |
|---|------|---------|
| 1 | 弹窗宽度约束 | Surface 添加 `Modifier.fillMaxWidth(0.92f)`（与 AppDialog 一致） |
| 2 | 平台默认宽度禁用 | `DialogProperties(usePlatformDefaultWidth = false)`，BasicAlertDialog 也需要此属性 |
| 3 | 弹窗内边距 | Column 统一 `padding(24.dp)` |
| 4 | 标题与内容间距 | 16dp spacer |
| 5 | 内容与按钮区间距 | 16dp spacer |
| 6 | dismiss 行为策略 | 所有弹窗统一允许点击外部关闭（`onDismissRequest = onDismiss`），等同于点击取消按钮 |
| 7 | 标题样式 | 统一使用 `Text(style = titleMedium)`（从 headlineSmall 缩小） |

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

**使用示例**：

```kotlin
// 确认型弹窗：取消 + 确认删除
DialogButtons(
    buttons = listOf(
        Triple("取消", DialogButtonRole.Secondary, onDismiss),
        Triple("删除", DialogButtonRole.Danger, onConfirm)
    )
)

// 三按钮场景：复制 + 重命名 + 删除
DialogButtons(
    buttons = listOf(
        Triple("复制", DialogButtonRole.Secondary, onCopy),
        Triple("重命名", DialogButtonRole.Primary, onRename),
        Triple("删除", DialogButtonRole.Danger, onDelete)
    )
)

// 单按钮：知道了
DialogButtons(
    buttons = listOf(
        Triple("知道了", DialogButtonRole.Primary, onDismiss)
    )
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

**使用示例**：

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyDialog(onDismiss: () -> Unit) {
    val params = amoledDialogParams()
    
    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f),
            color = params.containerColor,
            tonalElevation = params.tonalElevation,
            border = params.border,
            shape = params.shape
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("标题", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                // 内容区
                Spacer(Modifier.height(16.dp))
                DialogButtons(buttons = listOf(...))
            }
        }
    }
}
```

### 3.4 组件提取

| 组件 | 来源 | 目标位置 | 说明 |
|------|------|---------|------|
| `AppPickerList` | AppDialog.kt 内部 | `ui/components/AppPickerList.kt` | 保留现有独立组件（8 个 Picker 弹窗共用），无需额外提取 |
| `DetailRow` | SessionRow.kt / DirectoryTreeNode.kt 的 private 重复定义 | `ui/components/DetailRow.kt` | label 30% + value 70% 的展示行 |
| 撤回确认弹窗 | MessageCard.kt / ChatMessageList.kt 的重复代码 | `ui/components/ConfirmDialog.kt` | 通用确认弹窗，支持自定义标题/消息/按钮角色 |

### 3.5 设置页样式统一（#10）

3 个服务器设置页全部改为 SettingsScreen 的扁平列表风格：

| 页面 | 改动 |
|------|------|
| `ServerSettingsScreen.kt` | `AmoledCard` + `spacedBy(12dp)` → `ListItem` + `HorizontalDivider` |
| `ServerProvidersScreen.kt`（列表部分） | `ProviderRow(Card)` + `spacedBy(12dp)` → `ListItem` + `HorizontalDivider` + `SectionHeader` |
| `ServerModelFilterScreen.kt` | `AmoledCard` 分组 → `ListItem`(Switch 在 trailingContent) + `HorizontalDivider` + `SectionHeader` |

**字段映射规则**：

| 页面 | 原组件字段 | 映射到 ListItem 字段 |
|------|-----------|---------------------|
| ServerSettingsScreen | Card 内 Text(label) | `headlineContent` = label |
| ServerSettingsScreen | Card 内 Text(value) / Switch | `trailingContent` = value/switch |
| ServerProvidersScreen | ProviderRow(Card + icon + name) | `headlineContent` = name, `leadingContent` = icon |
| ServerProvidersScreen | ProviderRow(chevron) | `trailingContent` = Icon(Icons.Default.ChevronRight) |
| ServerModelFilterScreen | Card header Text | `SectionHeader` composable |
| ServerModelFilterScreen | Card 内 model name + Switch | `headlineContent` = model name, `trailingContent` = Switch |

### 3.6 ServerCard 间距优化（#7）

| 位置 | 当前 | 目标 |
|------|------|------|
| 内部垂直间距 | `spacedBy(12.dp)` | `spacedBy(8.dp)` |
| 按钮水平间距 | `spacedBy(8.dp)` | `spacedBy(6.dp)` |

### 3.7 Bug 修复

`LocalServerLaunchOptionsDialog.kt` 的超大表单（15+ 组件）text 区缺少 `verticalScroll`，补上 `Column(modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(max = 屏幕高度 * 0.7f))`。

---

## 四、全量弹窗迁移清单

### 第一组：AlertDialog → BasicAlertDialog（8 个）

| # | 弹窗 | 文件 | 类型 | 改动要点 |
|---|------|------|------|---------|
| 1 | Token 详情 | ChatTopBar.kt:157 | 展示型 | AlertDialog→BasicAlertDialog, 补 AMOLED |
| 2 | 重命名会话（聊天页） | ChatScreenDialogs.kt:22 | 表单型 | 同上 + OutlinedTextField |
| 3 | 发送确认 | ChatScreenDialogs.kt:64 | 确认型 | 同上 + 简单 Text |
| 4 | 恢复压缩确认 | ChatScreenDialogs.kt:92 | 确认型 | 同上 + error 色按钮 |
| 5 | 撤回确认（MessageCard） | MessageCard.kt:301 | 确认型 | 改用共享 ConfirmDialog |
| 6 | 撤回确认（ChatMessageList） | ChatMessageList.kt:250 | 确认型 | 改用共享 ConfirmDialog，删除重复代码 |
| 7 | 字号选择 | TerminalFontSizeDialog.kt | 选择型 | AlertDialog→BasicAlertDialog, Slider 内容 |
| 8 | 启动选项（子弹窗） | LocalServerLaunchOptionsDialog.kt | 选择型 | AlertDialog→BasicAlertDialog, 补 AMOLED |

### 第二组：AppDialog → BasicAlertDialog（13 个）

> **注意**：所有 AppDialog 弹窗迁移后需使用 `amoledDialogParams()` 替代原 `AmoledSurface`，统一 AMOLED 适配。

#### 简单（标准 title + 内容 + 1~2 按钮）

| # | 弹窗 | 文件 | 类型 | 内容组件 |
|---|------|------|------|---------|
| 9 | 删除确认 | SessionListScreen.kt:334 | 确认型 | Text |
| 10 | 重命名会话 | SessionListScreen.kt:306 | 表单型 | OutlinedTextField |
| 11 | 创建文件夹 | OpenProjectDialog.kt:231 | 表单型 | OutlinedTextField + error |
| 12 | 字体大小选择 | FontSizePickerDialog.kt | 选择型 | AppPickerList(3项) |
| 13 | 消息数量选择 | MessageCountPickerDialog.kt | 选择型 | AppPickerList(4项) |
| 14 | 重连模式选择 | ReconnectModePickerDialog.kt | 选择型 | AppPickerList(3项) |
| 15 | 图片压缩-最大边 | ImageCompressionDialog.kt | 选择型 | AppPickerList |
| 16 | 图片压缩-质量 | ImageCompressionDialog.kt | 选择型 | AppPickerList |

#### 中等（需要特殊处理）

| # | 弹窗 | 文件 | 类型 | 处理要点 |
|---|------|------|------|---------|
| 17 | 目录详情 | DirectoryTreeNode.kt:129 | 展示型 | SelectionContainer + 共享 DetailRow |
| 18 | 主题选择 | ThemePickerDialog.kt | 选择型 | AppPickerList + heightIn(480dp) |
| 19 | 语言选择 | LanguagePickerDialog.kt | 选择型 | AppPickerList(16项) + heightIn(520dp) |
| 20 | 超时选择 | LocalLaunchOptionsDialog.kt:312 | 选择型 | 改用 AppPickerList 替代手动 LazyColumn |

#### 较难（多按钮/复杂布局）

| # | 弹窗 | 文件 | 类型 | 处理要点 |
|---|------|------|------|---------|
| 21 | 会话详情 | SessionRow.kt:181 | 展示型 | 3 按钮（复制/重命名/删除）放 text 区底部，用 DetailRow |

### 第三组：已是 BasicAlertDialog，统一 AMOLED 处理（7 个）

> **改动要点**：手动 AMOLED → `amoledDialogParams()`；按钮区改用 `DialogButtons`。

| # | 弹窗 | 文件 | 类型 | 改动要点 |
|---|------|------|------|---------|
| 22 | 编辑服务器 | ServerDialog.kt | 表单型 | 手动 AMOLED→amoledDialogParams()，按钮区改用 DialogButtons |
| 23 | 连接方法选择 | ServerProvidersScreen.kt:149 | 选择型 | 同上 |
| 24 | API Key 输入 | ServerProvidersScreen.kt:201 | 表单型 | 同上 |
| 25 | OAuth 认证 | ServerProvidersScreen.kt:254 | 混合型 | 同上 |
| 26 | 模型选择 | ModelPickerDialog.kt | 选择型 | 同上 + 补 border/elevation 一致性 |
| 27 | 图片预览 | ImagePreviewDialog.kt | 展示型 | 同上 |
| 28 | 超时选择 | LocalLaunchOptionsDialog.kt | 选择型 | 同上 |

### 第四组：全屏 Dialog，不改动（3 个）

| # | 弹窗 | 文件 | 说明 |
|---|------|------|------|
| 29 | Markdown 预览 | MarkdownPreviewDialog.kt | 全屏 Dialog + TopAppBar，非弹窗结构，保持不变 |
| 30 | LocalLaunchOptionsDialog 全屏主弹窗 | LocalServerLaunchOptionsDialog.kt | 全屏表单 Dialog，保持不变 |
| 31 | ShareTargetPickerDialog | ShareTargetPickerDialog.kt | 全屏选择 Dialog，保持不变 |

---

## 五、新增/修改文件清单

### 新增文件

| 文件 | 内容 |
|------|------|
| `ui/components/AmoledDialogParams.kt` | `AmoledDialogParams` 数据类 + `amoledDialogParams()` 工厂函数 |
| `ui/components/DialogButtons.kt` | 统一按钮区组件（`DialogButtons` + `DialogButtonRole`） |
| `ui/components/DetailRow.kt` | 从 SessionRow/DirectoryTreeNode 提取的共享展示行 |
| `ui/components/ConfirmDialog.kt` | 通用确认弹窗（撤回/删除等场景复用） |

> **注意**：`AppPickerList.kt` 为已有独立组件，无需新增。

### 删除文件

| 文件 | 原因 |
|------|------|
| `ui/components/AppDialog.kt` | 被 BasicAlertDialog + 统一组件替代。**前置条件**：第二组全部 13 个 AppDialog 弹窗完成迁移后，确认无引用方可删除 |

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
| `LocalLaunchOptionsDialog.kt`（全屏主弹窗） | 全屏表单 Dialog，保持不变 |
| `ShareTargetPickerDialog.kt` | 全屏选择 Dialog，保持不变 |
| `AmoledSurface.kt` | 仍被非弹窗场景使用，保留 |
| `AmoledCard.kt` | 不在本次统一范围内（ServerCard 等非列表场景仍使用） |
| `AppPickerList.kt` | 已有独立组件，本次无需改动 |

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
│   ├── AppPickerList (已有独立组件)
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

### 视觉验证

Light / Dark / AMOLED 三种主题下截图对比，确认：
- 弹窗标题、内容、按钮布局一致
- AMOLED 模式下弹窗背景纯黑 + 边框可见
- 列表样式与 SettingsScreen 一致
- ServerCard 按钮间距缩小后视觉协调

### 按弹窗类型验收清单

#### 第一组：AlertDialog → BasicAlertDialog（8 个）

- [ ] Token 详情：AMOLED 背景纯黑 + 边框可见
- [ ] 重命名会话：OutlinedTextField 可正常输入，键盘弹出后布局正常
- [ ] 发送确认：Text 内容完整显示，按钮区使用 DialogButtons
- [ ] 恢复压缩确认：error 色按钮在 Light/Dark/AMOLED 下均可见
- [ ] 撤回确认（MessageCard）：使用共享 ConfirmDialog，行为不变
- [ ] 撤回确认（ChatMessageList）：使用共享 ConfirmDialog，删除重复代码
- [ ] 字号选择：Slider 可正常拖动，AMOLED 适配正确
- [ ] 启动选项子弹窗：容器迁移后功能完整

#### 第二组：AppDialog → BasicAlertDialog（13 个）

- [ ] 删除确认：Text 内容 + 取消/删除按钮，Danger 色正确
- [ ] 重命名会话：OutlinedTextField 可正常输入
- [ ] 创建文件夹：OutlinedTextField + error 提示正常
- [ ] 4 个 Picker 弹窗：AppPickerList 滚动正常，选中态高亮
- [ ] 图片压缩（2 个弹窗）：AppPickerList 正常工作
- [ ] 目录详情：DetailRow 布局正确，SelectionContainer 可复制
- [ ] 主题/语言选择：AppPickerList + heightIn 约束生效
- [ ] 超时选择：AppPickerList 替代手动 LazyColumn，行为一致
- [ ] 会话详情：3 按钮使用 DialogButtons，DetailRow 布局正确
- [ ] 所有第二组弹窗 AMOLED 模式使用 amoledDialogParams()

#### 第三组：BasicAlertDialog AMOLED 统一（7 个）

- [ ] 编辑服务器：amoledDialogParams() 替代手动 if/else，表单完整
- [ ] 连接方法/API Key/OAuth：AMOLED 统一，按钮区改用 DialogButtons
- [ ] 模型选择：border/elevation 一致性
- [ ] 图片预览：AMOLED 适配正确
- [ ] 超时选择：AMOLED 适配 + DialogButtons

#### 第四组：全屏 Dialog（3 个，不改动）

- [ ] Markdown 预览：无回归
- [ ] LocalLaunchOptionsDialog 全屏主弹窗：无回归
- [ ] ShareTargetPickerDialog：无回归

---

## 九、实施阶段

推荐按以下顺序实施，每个阶段完成后编译验证：

### 阶段 1：基础设施（无弹窗改动）

1. 新建 `AmoledDialogParams.kt`（数据类 + 工厂函数）
2. 新建 `DialogButtons.kt`（统一按钮组件）
3. 新建 `ConfirmDialog.kt`（通用确认弹窗）
4. 新建 `DetailRow.kt`（共享展示行）
5. **验证**：编译通过

### 阶段 2：第三组 — BasicAlertDialog AMOLED 统一（7 个，风险最低）

这些弹窗已是 BasicAlertDialog，仅统一 AMOLED 处理和按钮组件：
1. 编辑服务器、连接方法、API Key、OAuth、模型选择、图片预览、超时选择
2. **验证**：7 个弹窗在 Light/Dark/AMOLED 下视觉正确

### 阶段 3：第一组 — AlertDialog → BasicAlertDialog（8 个）

1. Token 详情、重命名会话、发送确认、恢复压缩确认
2. 撤回确认（2 处）→ 改用 ConfirmDialog
3. 字号选择、启动选项子弹窗
4. **验证**：8 个弹窗功能不变，AMOLED 适配正确

### 阶段 4：第二组 — AppDialog → BasicAlertDialog（13 个，工作量大）

从简单到复杂：
1. 删除确认、重命名会话、创建文件夹（简单表单/确认）
2. 4 个 Picker 弹窗 + 图片压缩（AppPickerList 复用）
3. 目录详情、主题/语言/超时选择（中等复杂度）
4. 会话详情（多按钮 + DetailRow）
5. **验证**：13 个弹窗迁移后功能完整，AMOLED 使用 amoledDialogParams()

### 阶段 5：列表样式统一 + 间距优化

1. ServerSettingsScreen、ServerProvidersScreen、ServerModelFilterScreen → ListItem 风格
2. ServerCard 间距优化
3. 删除旧的 `amoledDialogModifier()` / `amoledDialogContainerColor()`
4. **验证**：列表视觉一致，间距协调

### 阶段 6：清理

1. 确认 `AppDialog.kt` 无任何引用（全局搜索确认）
2. 删除 `AppDialog.kt`
3. Bug 修复：LocalServerLaunchOptionsDialog 补 verticalScroll
4. **最终验证**：全量编译 + 三主题视觉检查
