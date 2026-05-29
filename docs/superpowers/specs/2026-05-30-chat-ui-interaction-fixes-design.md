# Chat UI 交互修复设计文档

> 日期: 2026-05-30
> 范围: 对话界面滚动竞态、复制偏差、卡片间距、统计时机、事件生命周期文档
> 影响文件: 14 Kotlin 源文件（12 修改 + 2 新建）
> 环境要求: Kotlin 1.9.x / Compose UI 1.7.x / AGP 8.7.x / minSdk 26 / compileSdk 35

---

## 目录

- [问题清单](#问题清单)
- [设计 A：移除透明 SelectionContainer + 复制弹窗](#设计-a移除透明-selectioncontainer--复制弹窗)
  - [A1. 移除 MarkdownContent 透明覆盖层](#a1-移除-markdowncontent-透明覆盖层)
  - [A2. 新增 MarkdownPreviewDialog](#a2-新增-markdownpreviewdialog)
  - [A3. 复制按钮触发逻辑变更](#a3-复制按钮触发逻辑变更)
  - [A4. AssistantMessageCard 中按钮行为变更](#a4-assistantmessagecard-中-clipboard-按钮的行为变更)
  - [A5-A6. 保留的 SelectionContainer](#a5-bashtoolcard-内的-selectioncontainer-保留)
- [设计 B：嵌套滚动边界消费升级](#设计-b嵌套滚动边界消费升级)
- [设计 C：工具卡片间距紧凑化](#设计-c工具卡片间距紧凑化)
- [设计 D：统计信息时机修正](#设计-d统计信息时机修正)
- [设计 E：事件生命周期文档](#设计-e事件生命周期文档)
- [改动文件汇总](#改动文件汇总)
- [测试要点](#测试要点)

---

## 问题清单

| # | 问题 | 根因 | 严重度 |
|---|------|------|--------|
| 1 | 工具卡片展开后滚动不稳定（有时能上下滑，有时不能，有时带动外层） | 嵌套滚动手势竞争：MarkdownContent 透明 SelectionContainer 覆盖层与 verticalScroll/LazyColumn 在垂直方向竞争 pointer events | 高 |
| 2 | 复制时框选高亮位置与实际文字有偏差 | 透明 SelectionContainer 使用单一字体的 Text，与 mikepenz Markdown 渲染层的多字体/多行高/多 padding 布局不对齐 | 中 |
| 3 | 工具卡片上下左右边距过大 | 各卡片 padding 值统一偏大 | 低 |
| 4 | 统计信息逐条显示，应在 agent 整次回答结束后只在最后一条汇总 | AssistantMessageCard 对每条 Assistant 消息独立聚合 stepFinishes 显示 Footer | 中 |

---

## 设计 A：移除透明 SelectionContainer + 复制弹窗

### A1. 移除 MarkdownContent 透明覆盖层

**文件**: `chat/markdown/MarkdownContent.kt`（使用 mikepenz/multiplatform-markdown-renderer v0.30.0，MarkdownContent 签名: `(markdown: String, textColor: Color, isUser: Boolean, customFontSize: String?)`）

**当前代码** (L268-L299):

```kotlin
// Layer 1: 可见 Markdown 渲染
Markdown(markdownState, colors, typography, ...)

// Layer 2: 透明文本选择覆盖层（移除此块）
if (!isUser) {
    val selectionColors = TextSelectionColors(...)
    CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
        SelectionContainer(modifier = Modifier.matchParentSize()) {
            Text(text = normalizedMarkdown, color = Color.Transparent, ...)
        }
    }
}
```

**改动**:
- 删除 L280-L298 整个 `if (!isUser) { ... }` 块
- 保留 L270-L278 的 Markdown 渲染层不变
- Box 可简化为直接返回 Markdown 组件（无需两层 Box 嵌套）

**效果**: 对话流中不再有 SelectionContainer，彻底消除其 pointerInput 与 LazyColumn/verticalScroll 的手势竞争。

**副作用**: 对话流中无法长按选择文字。此功能由 A2 的弹窗替代。

### A2. 新增 MarkdownPreviewDialog

**新建文件**: `chat/components/MarkdownPreviewDialog.kt`

**功能**: 独立弹窗，展示单条助手消息的完整文本内容，支持两种视图切换和文本选择。

**函数签名**:

```kotlin
@Composable
internal fun MarkdownPreviewDialog(
    markdown: String,
    onDismiss: () -> Unit,
    onCopyAll: () -> Unit
)
```

**UI 结构**:

```
┌─────────────────────────────────────────────────┐
│  ┌─ TopAppBar ─────────────────────────────────┐ │
│  │  [← 返回]  Agent 回答  [源码/渲染]  [复制全部]│ │
│  └─────────────────────────────────────────────┘ │
│                                                  │
│  源码视图（默认）:                                 │
│  ┌─────────────────────────────────────────────┐ │
│  │  SelectionContainer {                        │ │
│  │    ScrollableColumn {                        │ │
│  │      Text(markdown, monospace, selectable)   │ │  ← 安全：
│  │    }                                         │ │     弹窗内无嵌套滚动
│  │  }                                           │ │     无手势竞争
│  └─────────────────────────────────────────────┘ │
│                                                  │
│  渲染视图:                                        │
│  ┌─────────────────────────────────────────────┐ │
│  │  ScrollableColumn {                          │ │
│  │    MarkdownContent(markdown, isUser=false)   │ │  ← 复用现有组件
│  │  }                                           │ │     但不在 LazyColumn 内
│  └─────────────────────────────────────────────┘ │
│                                                  │
│  底部 snackbar 区（复制成功提示）                    │
└─────────────────────────────────────────────────┘
```

**实现细节**:

1. 使用 `Dialog` composable 全屏展示
2. 视图切换：`enum class PreviewMode { SOURCE, RENDERED }`，默认 `SOURCE`
3. 源码视图：
   - 外层 `SelectionContainer` 包裹整个内容
   - 内层 `Column(modifier = Modifier.verticalScroll(scrollState).padding(16.dp))`
   - `Text` 使用 monospace 字体（`FontFamily.Monospace`）、12.sp 字号
   - 用户可长按选择任意范围的文字
4. 渲染视图：
   - `Column(modifier = Modifier.verticalScroll(scrollState).padding(16.dp))`
   - `MarkdownContent(markdown, textColor, isUser = false)`
   - 无 SelectionContainer（渲染视图仅用于查看格式，不需要选择）
5. "复制全部"按钮：
   - 点击后调用 `clipboardManager.setText(AnnotatedString(markdown))`
   - 显示 snackbar "已复制到剪贴板"
   - 同时触发 `performHaptic` 触觉反馈
6. 动画：弹窗使用 `fadeIn() + expandVertically()` 进入，`fadeOut() + shrinkVertically()` 退出
7. 防御性检查：如果 `markdown.isBlank()`，调用 `onDismiss()` 直接关闭弹窗（理论上不会发生，因 A3 的 onCopyText 已过滤空白文本）

**状态管理**:

```kotlin
var previewMode by rememberSaveable { mutableStateOf(PreviewMode.SOURCE) }
val scrollState = rememberScrollState()
val snackbarHostState = remember { SnackbarHostState() }
```

### A3. 复制按钮触发逻辑变更

**文件**: `ChatScreen.kt` (L122-L131, L1837-L1849, L2082-L2100)

**当前行为**: 📋 图标点击后直接复制 `Part.Text` 拼接文本到剪贴板。

**改动后**: 📋 图标点击后打开 `MarkdownPreviewDialog`。

**具体改动** (以主会话 L1837-L1849 为例):

```kotlin
// 改前
onCopyText = {
    val text = msg.parts.filterIsInstance<Part.Text>()
        .joinToString("\n") { it.text }
    if (text.isNotBlank()) {
        clipboardManager.setText(AnnotatedString(text))
        coroutineScope.launch { snackbarHostState.showSnackbar(...) }
    }
}

// 改后
onCopyText = {
    val text = msg.parts.filterIsInstance<Part.Text>()
        .joinToString("\n\n") { it.text }
    if (text.isNotBlank()) {
        markdownPreviewText = text  // 触发 dialog 显示
    }
}
```

**Dialog 状态提升到 ChatScreen**:

```kotlin
// ChatScreen 中新增状态
var markdownPreviewText by remember { mutableStateOf<String?>(null) }

// Dialog 组件（在 Scaffold 外层）
markdownPreviewText?.let { text ->
    MarkdownPreviewDialog(
        markdown = text,
        onDismiss = { markdownPreviewText = null },
        onCopyAll = {
            clipboardManager.setText(AnnotatedString(text))
            coroutineScope.launch { snackbarHostState.showSnackbar(context.getString(R.string.chat_copied_clipboard)) }
        }
    )
}
```

### A4. AssistantMessageCard 中 📋 按钮的行为变更

**文件**: `AssistantMessageCard.kt` L122-L131

当前 📋 按钮只出现在 `!isContinuation` 的 header 区域。设计 D 修改后，非 continuation 的消息 header 区域保留 📋 按钮（打开弹窗，展示单条消息内容）。Turn 最后一条消息的 Footer 区域有 📋 按钮（打开弹窗，展示整个 turn 的拼接内容）。具体位置见设计 D3。

### A5. BashToolCard 内的 SelectionContainer 保留

**文件**: `BashToolCard.kt` L169

BashToolCard 展开后用 `SelectionContainer` 包裹命令输出。这个 SelectionContainer 在工具卡片内部（不在 LazyColumn 外层），且不在 `MarkdownContent` 覆盖层内（移除 A1 后），不会与外层滚动竞争。

**结论**: BashToolCard 的 `SelectionContainer` 保留不动。

### A6. ErrorPayloadContent 内的 SelectionContainer 保留

**文件**: `ErrorPayloadContent.kt` L49-L56

同理，在工具卡片内部，保留不动。

---

## 设计 B：嵌套滚动边界消费升级

### B1. consumeBoundaryFling → consumeBoundaryScroll

**文件**: `chat/util/ChatModifiers.kt` L49-L68

**当前代码** — 只消费 `onPostFling`:

```kotlin
@Composable
internal fun Modifier.consumeBoundaryFling(scrollState: ScrollState): Modifier {
    val connection = remember(scrollState) {
        object : NestedScrollConnection {
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                val atBottom = !scrollState.canScrollForward
                val atTop = !scrollState.canScrollBackward
                return if ((atBottom && available.y < 0f) || (atTop && available.y > 0f)) {
                    available
                } else {
                    Velocity.Zero
                }
            }
        }
    }
    return this.nestedScroll(connection)
}
```

**改动后** — 同时消费 `onPostScroll` 和 `onPostFling`:

```kotlin
@Composable
internal fun Modifier.consumeBoundaryScroll(scrollState: ScrollState): Modifier {
    val connection = remember(scrollState) {
        object : NestedScrollConnection {
            /** 手指拖动到达边界后，消费剩余 delta，阻止传播到外层 */
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                val atTop = !scrollState.canScrollBackward
                val atBottom = !scrollState.canScrollForward
                return when {
                    atTop && available.y < 0f -> available    // 在顶部，向上拖动的剩余量
                    atBottom && available.y > 0f -> available  // 在底部，向下拖动的剩余量
                    else -> Offset.Zero
                }
            }

            /** 惯性 fling 到达边界后，消费剩余速度，阻止传播到外层 */
            override suspend fun onPostFling(
                consumed: Velocity,
                available: Velocity
            ): Velocity {
                val atTop = !scrollState.canScrollBackward
                val atBottom = !scrollState.canScrollForward
                return when {
                    atTop && available.y < 0f -> available
                    atBottom && available.y > 0f -> available
                    else -> Velocity.Zero
                }
            }
        }
    }
    return this.nestedScroll(connection)
}
```

**关键变化**:
- 新增 `onPostScroll` 回调：当子组件 `verticalScroll` 到达边界后，手指仍在拖动的剩余 delta 会被消费（不再传播到外层 LazyColumn）
- `onPostFling` 保留同样的逻辑（消费边界处的剩余 fling velocity）
- 函数名从 `consumeBoundaryFling` 改为 `consumeBoundaryScroll`
- 所有调用处需要更新函数名

### B2. 现有调用处迁移

将所有 `consumeBoundaryFling` 调用替换为 `consumeBoundaryScroll`（逻辑不变，仅名称更新）:

| 文件 | 行号 | 当前 | 改后 |
|------|------|------|------|
| `ToolCardRenderer.kt` | L163 | `.consumeBoundaryFling(scrollState)` | `.consumeBoundaryScroll(scrollState)` |
| `ReasoningBlock.kt` | L169 | `.consumeBoundaryFling(reasoningScrollState)` | `.consumeBoundaryScroll(reasoningScrollState)` |
| `SearchToolCard.kt` | L147 | `.consumeBoundaryFling(scrollState)` | `.consumeBoundaryScroll(scrollState)` |
| `TaskToolCard.kt` | L179 | `.consumeBoundaryFling(scrollState)` | `.consumeBoundaryScroll(scrollState)` |

**注意**: `consumeBoundaryScroll` 修饰符必须放在 `verticalScroll` **之前**（modifier 链从外到内），确保 `NestedScrollConnection` 先于 `ScrollState` 处理事件。当前代码的顺序 `consumeBoundaryFling → verticalScroll` 是正确的，保持不变。

### B3. 给缺少 verticalScroll 的工具卡片补上滚动

以下卡片展开后内容区域没有 `verticalScroll`，需要补充：

#### B3a. BashToolCard

**文件**: `BashToolCard.kt` L157-L179

**当前**:
```kotlin
AnimatedVisibility(visible = expanded && hasContent) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).heightIn(max = 400.dp)
    ) {
        SelectionContainer {
            Text(
                text = displayText,
                modifier = Modifier.padding(8.dp).codeHorizontalScroll()
            )
        }
    }
}
```

**改后**:
```kotlin
AnimatedVisibility(visible = expanded && hasContent) {
    val halfScreenHeight = maxOf(LocalConfiguration.current.screenHeightDp.dp / 2, 200.dp)
    // scrollState 提升到 AnimatedVisibility 外层以保持展开/折叠时的滚动位置
    val scrollState = rememberScrollState()
    Surface(
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 3.dp)                       // 6→3 (设计 C)
            .heightIn(max = halfScreenHeight)           // 400.dp → halfScreenHeight
            .consumeBoundaryScroll(scrollState)          // 新增
            .verticalScroll(scrollState)                 // 新增
    ) {
        SelectionContainer {
            Text(
                text = displayText,
                modifier = Modifier.padding(4.dp)        // 8→4 (设计 C)
                    .codeHorizontalScroll()
            )
        }
    }
}
```

**变更要点**:
- `heightIn(max = 400.dp)` → `halfScreenHeight`（与其他卡片统一）
- 新增 `consumeBoundaryScroll(scrollState)` + `verticalScroll(scrollState)`
- `SelectionContainer` 保留（在卡片内部，不影响外层）
- padding 值缩减（设计 C）

**注意**: BashToolCard 内部 SelectionContainer 与新增的 verticalScroll 存在潜在手势竞争。
SelectionContainer 需要长按手势启动选择模式，而 verticalScroll 响应拖动手势。
Compose 的手势仲裁机制会根据初始手势方向决定获胜者：
- 垂直拖动 → verticalScroll 获胜（滚动内容）
- 长按 → SelectionContainer 获胜（启动文字选择）
这与 BashToolCard 在改动前的行为一致（原有 SelectionContainer 在无 verticalScroll 的 Surface 内也能正常工作），
新增 verticalScroll 不会影响长按选择功能。需在实现后进行手动验证。

#### B3b. WriteToolCard

**文件**: `WriteToolCard.kt` L121-L141

**当前**:
```kotlin
AnimatedVisibility(visible = expanded && hasContent) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).heightIn(max = 400.dp)
    ) {
        Text(
            text = content.take(5000),
            modifier = Modifier.padding(8.dp).codeHorizontalScroll()
        )
    }
}
```

**改后**:
```kotlin
AnimatedVisibility(visible = expanded && hasContent) {
    val halfScreenHeight = maxOf(LocalConfiguration.current.screenHeightDp.dp / 2, 200.dp)
    // scrollState 提升到 AnimatedVisibility 外层以保持展开/折叠时的滚动位置
    val scrollState = rememberScrollState()
    Surface(
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 3.dp)                       // 6→3
            .heightIn(max = halfScreenHeight)           // 400.dp → halfScreenHeight
            .consumeBoundaryScroll(scrollState)          // 新增
            .verticalScroll(scrollState)                 // 新增
    ) {
        Text(
            text = content.take(5000),
            modifier = Modifier.padding(4.dp)            // 8→4
                .codeHorizontalScroll()
        )
    }
}
```

#### B3c. ReadToolCard

**文件**: `ReadToolCard.kt` L127-L171

**当前**: 展开后是 `Column(padding(top=4.dp), spacedBy(4.dp))` 内两个 Surface，无 `heightIn`、无 `verticalScroll`。

**改后**: 给整个 Column 外层包一个 `Box` 带 `heightIn(max=halfScreenHeight)` + `verticalScroll` + `consumeBoundaryScroll`:

```kotlin
AnimatedVisibility(visible = expanded) {
    val halfScreenHeight = maxOf(LocalConfiguration.current.screenHeightDp.dp / 2, 200.dp)
    // scrollState 提升到 AnimatedVisibility 外层以保持展开/折叠时的滚动位置
    val scrollState = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = halfScreenHeight)           // 新增
            .consumeBoundaryScroll(scrollState)          // 新增
            .verticalScroll(scrollState)                 // 新增
    ) {
        Column(
            modifier = Modifier.padding(top = 2.dp),     // 4→2
            verticalArrangement = Arrangement.spacedBy(2.dp)  // 4→2
        ) {
            // ... filePath Surface、args Surface（内容不变）
        }
    }
}
```

#### B3d. EditToolCard

**文件**: `EditToolCard.kt` L152-L178

**当前**: 展开后是 `Column(padding(top=6.dp))`，内容为 ErrorPayloadContent 或 DiffView。无 `heightIn`、无 `verticalScroll`。

**改后**: 同 ReadToolCard 模式，外层包 Box:

```kotlin
AnimatedVisibility(visible = expanded && hasContent) {
    val halfScreenHeight = maxOf(LocalConfiguration.current.screenHeightDp.dp / 2, 200.dp)
    // scrollState 提升到 AnimatedVisibility 外层以保持展开/折叠时的滚动位置
    val scrollState = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = halfScreenHeight)
            .consumeBoundaryScroll(scrollState)
            .verticalScroll(scrollState)
    ) {
        Column(modifier = Modifier.padding(top = 3.dp)) {  // 6→3
            // ... ErrorPayloadContent 或 DiffView（内容不变）
        }
    }
}
```

### B4. 滚动事件传播链（修复后）

```
手指在工具卡片内容区垂直拖动：

Step 1: onPreScroll (LazyColumn 的 nestedScroll)
  → 返回 Offset.Zero（LazyColumn 不截胡，全部传给子组件）
  → delta 向下传播

Step 2: 子组件 verticalScroll 消费
  → 情况 A：未到边界 → delta 被完全消费，无剩余
  → 情况 B：到达边界 → 消费部分，剩余 available 非零

Step 3: onPostScroll (consumeBoundaryScroll 的 nestedScroll)
  → 检查 scrollState.canScrollForward / canScrollBackward
  → 到边界 → 返回 available（消费掉，不传给 LazyColumn）
  → 未到边界 → 返回 Offset.Zero（放行）

Step 4: (如果 Step 3 放行了) LazyColumn 的 onPostScroll
  → 接收剩余 delta → 滚动对话列表

结果:
  ✓ 工具卡片内容区正常上下滚动
  ✓ 到达边界后手指继续拖动不会带动对话列表
  ✓ 惯性 fling 到达边界后速度被消费
  ✓ SelectionContainer 已被移除（设计 A），无手势竞争
```

---

## 设计 C：工具卡片间距紧凑化

所有工具卡片的 padding/spacing 值缩减为当前的 1/2。

### C1. 通用 ToolCallCard (ToolCardRenderer.kt)

| 位置 | 当前值 | 改后值 | 行号 |
|------|--------|--------|------|
| 外层 Column padding | `8.dp` | `4.dp` | L86 |
| Header Row spacing | `6.dp` | `3.dp` | L96 |
| 展开区域 Column top padding | `4.dp` | `2.dp` | L167 |
| 展开区域 Column spacing | `4.dp` | `2.dp` | L168 |
| Input/Output Text padding | `8.dp` | `4.dp` | L186, L206 |
| Surface 圆角 | `8.dp` | `6.dp` | L80 |

### C2. BashToolCard (BashToolCard.kt)

| 位置 | 当前值 | 改后值 | 行号 |
|------|--------|--------|------|
| 外层 Column padding | `8.dp` | `4.dp` | L98 |
| Header Row spacing | `6.dp` | `3.dp` | L114 |
| 展开区域 Surface top padding | `6.dp` | `3.dp` | L166 |
| 输出 Text padding | `8.dp` | `4.dp` | L174 |
| Surface 圆角 | `8.dp` | `6.dp` | L81 |

### C3. ReadToolCard (ReadToolCard.kt)

| 位置 | 当前值 | 改后值 | 行号 |
|------|--------|--------|------|
| 外层 Column padding | `8.dp` | `4.dp` | L83 |
| Header Row spacing | `6.dp` | `3.dp` | L92 |
| 展开区域 Column top | `4.dp` | `2.dp` | L131 |
| 展开区域 Column spacing | `4.dp` | `2.dp` | L132 |
| FilePath/Args Text padding | `8.dp` | `4.dp` | L147, L165 |
| Surface 圆角 | `8.dp` | `6.dp` | L77 |

### C4. WriteToolCard (WriteToolCard.kt)

| 位置 | 当前值 | 改后值 | 行号 |
|------|--------|--------|------|
| 外层 Column padding | `8.dp` | `4.dp` | L84 |
| Header Row spacing | `6.dp` | `3.dp` | L100 |
| 展开区域 Surface top padding | `6.dp` | `3.dp` | L130 |
| 内容 Text padding | `8.dp` | `4.dp` | L137 |
| Surface 圆角 | `8.dp` | `6.dp` | L80 |

### C5. EditToolCard (EditToolCard.kt)

| 位置 | 当前值 | 改后值 | 行号 |
|------|--------|--------|------|
| 外层 Column padding | `8.dp` | `4.dp` | L98 |
| Header Row spacing | `6.dp` | `3.dp` | L108 |
| 展开区域 Column top | `6.dp` | `3.dp` | L155 |
| Error text padding | `8.dp` | `4.dp` | L171 |
| Surface 圆角 | `8.dp` | `6.dp` | L92 |

### C6. SearchToolCard (SearchToolCard.kt)

| 位置 | 当前值 | 改后值 | 行号 |
|------|--------|--------|------|
| 外层 Column padding | `8.dp` | `4.dp` | L85 |
| Header Row spacing | `6.dp` | `3.dp` | L101 |
| 展开区域 Surface top padding | `6.dp` | `3.dp` | L145 |
| MarkdownContent 内容 padding | — | `4.dp`（通过 Column 包裹） | L150 |
| Surface 圆角 | `8.dp` | `6.dp` | L80 |

### C7. TaskToolCard (TaskToolCard.kt)

| 位置 | 当前值 | 改后值 | 行号 |
|------|--------|--------|------|
| 外层 Column padding | `8.dp` | `4.dp` | L87 |
| Header Row spacing | `6.dp` | `3.dp` | L103 |
| 展开区域 Surface top padding | `6.dp` | `3.dp` | L177 |
| MarkdownContent 内容 padding | — | `4.dp`（通过 Column 包裹） | L182 |
| Surface 圆角 | `8.dp` | `6.dp` | L80 |

### C8. ReasoningBlock (ReasoningBlock.kt)

| 位置 | 当前值 | 改后值 | 行号 |
|------|--------|--------|------|
| 外层 padding | `start=14, end=12, top=12, bottom=12` | `start=10, end=8, top=8, bottom=8` | L120 |
| 展开 Spacer | `10.dp` | `6.dp` | L162 |

### C9. 卡片间距

**文件**: `ChatScreen.kt` L1744

```kotlin
// 当前
val messageSpacing = if (LocalCompactMessages.current) 4.dp else 12.dp

// 改后
val messageSpacing = if (LocalCompactMessages.current) 4.dp else 6.dp  // 12→6
```

---

## 设计 D：统计信息时机修正

### D1. 定义 "Turn"（一次 agent 回答）

```
Turn = 从一条 User 消息之后，到下一条 User 消息（或消息列表末尾）之间的所有连续 Assistant 消息

判定 "Turn 内最后一条 Assistant 消息"：
  当前消息是 Assistant && (下一条不是 Assistant || 无下一条)
  即 !isAssistantContinuation
精确判定：当前消息是 Assistant && 消息列表中下一条（index + 1）不是 Assistant 或不存在。
注意：由于 reverseLayout=true，index + 1 对应视觉上的上一条消息。
isTurnLast 与 isAssistantContinuation 互补但非简单取反——前者看下一条消息类型，后者看上一条消息类型。
在 D4 中两者组合使用以确保判定正确。
```

### D2. AssistantMessageCard Footer 逻辑变更

**文件**: `AssistantMessageCard.kt` L152-L217

**当前**: 每条 Assistant 消息都检查 `stepFinishes.isNotEmpty()` 并显示 Footer。

**改动**: 新增 `isTurnLast` 参数，只在 `isTurnLast == true` 时显示 Footer 统计。

**函数签名变更**:

```kotlin
@Composable
internal fun AssistantMessageCard(
    chatMessage: ChatMessage,
    isContinuation: Boolean,
    isTurnLast: Boolean,                              // 新增参数
    turnMessages: List<ChatMessage>? = null,           // 新增：整个 turn 的所有 assistant 消息
    onViewSubSession: ((String) -> Unit)? = null,
    onCopyText: (() -> Unit)? = null,
)
```

**Footer 统计聚合逻辑**:

```kotlin
// 当 isTurnLast == true 时，聚合整个 turn 的统计
val stepFinishes = if (isTurnLast && turnMessages != null) {
    // 聚合 turn 内所有 assistant 消息的 stepFinishes
    turnMessages.flatMap { msg ->
        msg.parts.filterIsInstance<Part.StepFinish>()
    }
} else {
    // 非 turn 最后一条消息：不显示统计（但仍可复制）
    emptyList()
}

val totalInput = stepFinishes.sumOf { it.tokens?.input ?: 0 }
val totalOutput = stepFinishes.sumOf { it.tokens?.output ?: 0 }
val totalCost = stepFinishes.sumOf { it.cost ?: 0.0 }

// 耗时：turn 内第一条 assistant 消息的 created → 最后一条的 completed
val durationMs = if (isTurnLast && turnMessages != null) {
    val first = turnMessages.firstOrNull()?.message
    val last = turnMessages.lastOrNull()?.message
    if (first is Message.Assistant && last is Message.Assistant) {
        last.time.completed?.let { end -> end - first.time.created }
    } else null
} else null

// 模型名：取最后一条 assistant 消息的 modelId
val modelId = if (isTurnLast && turnMessages != null) {
    (turnMessages.lastOrNull()?.message as? Message.Assistant)?.modelId
} else null
```

**Footer 渲染条件**:

```kotlin
val hasFooter = isTurnLast && (
    totalInput > 0 || totalOutput > 0 || totalCost > 0.0 ||
    durationMs != null || !modelId.isNullOrBlank()
)

if (hasFooter) {
    // 渲染 Footer 统计栏（与当前布局相同）
    Row(...) {
        ProviderIcon(...)
        Text(modelId, ...)
        Text(formatDuration(durationMs), ...)
        Text("↑$totalInput ↓$totalOutput", ...)
        Text(formatCost(totalCost), ...)
        // 新增：复制按钮（见 D3）
    }
}
```

### D3. Footer 右侧复制按钮

在 Footer Row 的最右边添加 📋 复制图标：

```kotlin
// Footer Row 内部，最右边
if (onCopyText != null) {
    Spacer(modifier = Modifier.width(4.dp))
    Icon(
        Icons.Default.ContentCopy,
        contentDescription = "Copy",
        modifier = Modifier
            .size(14.dp)
            .clickable {
                performHaptic(hapticView, hapticOn)
                onCopyText()
            },
        tint = textColor.copy(alpha = 0.3f)
    )
}
```

**复制内容**: 整个 turn 所有 Assistant 消息的 `Part.Text` 拼接。

### D4. ChatScreen 调用处变更

**文件**: `ChatScreen.kt` L1831-L1849 / L2082-L2100

需要在 ChatScreen 层计算每个 turn 的消息分组，并传递给 `AssistantMessageCard`。

**Turn 分组计算**:

```kotlin
// 在 itemsIndexed 之前计算 turn 分组
val turnGroups = remember(uiState.messages) {
    val groups = mutableListOf<Pair<IntRange, List<ChatMessage>>>()
    var currentStart = -1
    val currentGroup = mutableListOf<ChatMessage>()

    for ((index, msg) in uiState.messages.withIndex()) {
        if (msg.isAssistant) {
            if (currentStart == -1) currentStart = index
            currentGroup.add(msg)
        } else {
            if (currentGroup.isNotEmpty()) {
                groups.add((currentStart until index) to currentGroup.toList())
                currentGroup.clear()
                currentStart = -1
            }
        }
    }
    if (currentGroup.isNotEmpty()) {
        groups.add((currentStart until uiState.messages.size) to currentGroup.toList())
    }

    // 建立消息 index → turn group 的映射
    val indexToGroup = mutableMapOf<Int, List<ChatMessage>>()
    for ((range, group) in groups) {
        for (i in range) {
            indexToGroup[i] = group
        }
    }
    indexToGroup
}
```

// 空会话或纯 User 消息时 turnGroups 为空 Map，
// turnGroups[index] 返回 null → turnMessages 为 null → isTurnLast 的 hasFooter 判断为 false → 不显示 Footer
// 此为预期行为，无需额外处理

**调用处变更**:

```kotlin
msg.isAssistant -> {
    val isContinuation = isAssistantContinuation.getOrElse(index) { false }
    val isTurnLast = !isAssistantContinuation.getOrElse(index) { false } &&
                     uiState.messages.getOrNull(index)?.isAssistant == true &&
                     uiState.messages.getOrNull(index + 1)?.isAssistant != true
    val turnMessages = turnGroups[index]

    AssistantMessageCard(
        chatMessage = msg,
        isContinuation = isContinuation,
        isTurnLast = isTurnLast,
        turnMessages = turnMessages,
        onViewSubSession = navigateToChildSessionWithSave,
        onCopyText = {
            val messages = turnMessages ?: listOf(msg)
            val text = messages.flatMap { m ->
                m.parts.filterIsInstance<Part.Text>().map { it.text }
            }.joinToString("\n\n")
            if (text.isNotBlank()) {
                markdownPreviewText = text  // 打开弹窗（设计 A）
            }
        }
    )
}
```

### D5. isAssistantContinuation 计算修正

**文件**: `ChatScreen.kt` L1749-L1754

**当前问题**: `remember(uiState.messages.size)` 当消息数量不变但内容变化时不会重新计算。

**修正**:

```kotlin
// 改前
val isAssistantContinuation = remember(uiState.messages.size) { ... }

// 改后：使用消息 ID 列表作为 key
val isAssistantContinuation = remember(uiState.messages.map { it.message.id }) { ... }
```

这确保了当消息流式更新完成时（状态从 Running → Completed），continuation 标记能正确更新。

---

## 设计 E：事件生命周期文档

**输出文件**: `docs/chat-ui-event-lifecycle.md`

**内容结构**:

### E1. 触摸事件传播链

```
手指 Down → Move → Up 的完整路径
包括：
- Compose pointer event 的分发顺序（父→子）
- hitTest 的判定逻辑
- 事件消费（consume）的传播
- 多指触控的处理
```

### E2. 嵌套滚动生命周期

```
onPreScroll → 子消费 → onPostScroll → onPreFling → 子 fling → onPostFling

包括：
- 每个回调的触发条件、参数、返回值语义
- reverseLayout=true 对方向的影响
- verticalScroll / horizontalScroll 的方向判断
- consumeBoundaryScroll 的介入节点
```

### E3. SSE 流式更新 → UI 重组

```
SSE MessagePartDelta → EventHandler → StateFlow → combine → ChatUiState → collectAsState → Recomposition

包括：
- 事件去重和合并策略
- retainState=true 的作用
- recomposition 的范围（哪些组件会重组）
```

### E4. 消息状态机

```
Queued (用户消息等待发送)
  → Sending (请求中)
  → Streaming (SSE 流式输出)
  → Complete (输出完成)

包括：
- 每个状态的 UI 表现差异
- 状态转换的触发条件
- 流式中途断开的处理
```

### E5. 竞态条件清单

```
1. REST 加载 vs SSE 事件 (ChatViewModel L328-348)
2. scrollRestoreVersion 恢复 vs messageCount 自动滚动 (ChatScreen L299-336)
3. isAssistantContinuation 的 key 过时 (ChatScreen L1749)
4. consumeBoundaryScroll 的方向在 reverseLayout 场景下的正确性
5. Dialog 显示/隐藏与 Snackbar 的时序

每个竞态条件包含：
- 触发条件
- 现象
- 缓解策略
- 测试方法
```

### E6. 实现方式

每个章节的内容来源和编写方法：
- **E1 触摸事件传播链**：从 Compose 源码和官方文档提取事件分发流程，结合 ChatScreen.kt 中 pointerInput/detectTapGestures 的使用位置画图
- **E2 嵌套滚动生命周期**：基于 consumeBoundaryScroll（ChatModifiers.kt）的实现，画出 onPreScroll→子消费→onPostScroll→onPreFling→子fling→onPostFling 的完整流程图
- **E3 SSE→UI 重组**：从 EventDispatcher.kt → MessageEventHandler.kt → ChatViewModel.kt 的 combine 链路追踪
- **E4 消息状态机**：从 Message.kt 和 SseEvent.kt 提取状态定义，从 ChatViewModel.kt 提取状态转换条件
- **E5 竞态条件清单**：逐一分析 ChatViewModel.kt 和 ChatScreen.kt 中的 LaunchedEffect/DisposableEffect 交互

每个章节需包含：触发条件、传播路径、代码位置引用、框架 API 介入节点。

---

## 改动文件汇总

| 文件 | 改动类型 | 涉及设计 |
|------|----------|----------|
| `chat/markdown/MarkdownContent.kt` | 删除透明覆盖层 | A1 |
| `chat/components/MarkdownPreviewDialog.kt` | **新建** | A2 |
| `chat/util/ChatModifiers.kt` | 升级 consumeBoundaryFling → consumeBoundaryScroll | B1 |
| `chat/tools/ToolCardRenderer.kt` | 更新调用名 + padding 调整 | B2, C1 |
| `chat/tools/cards/BashToolCard.kt` | 补 verticalScroll + padding 调整 | B3a, C2 |
| `chat/tools/cards/WriteToolCard.kt` | 补 verticalScroll + padding 调整 | B3b, C4 |
| `chat/tools/cards/ReadToolCard.kt` | 补 verticalScroll + padding 调整 | B3c, C3 |
| `chat/tools/cards/EditToolCard.kt` | 补 verticalScroll + padding 调整 | B3d, C5 |
| `chat/tools/cards/SearchToolCard.kt` | 更新调用名 + padding 调整 | B2, C6 |
| `chat/tools/cards/TaskToolCard.kt` | 更新调用名 + padding 调整 | B2, C7 |
| `chat/components/AssistantMessageCard.kt` | 新增参数 + Footer 逻辑变更 | D2, D3 |
| `chat/components/ReasoningBlock.kt` | 更新调用名 + padding 调整 | B2, C8 |
| `chat/ChatScreen.kt` | Dialog 状态 + turn 分组 + 调用变更 | A3, D4, D5, C9 |
| `docs/chat-ui-event-lifecycle.md` | **新建** | E |

---

## 测试要点

### 手动测试场景

1. **滚动稳定性**: 展开各类型工具卡片（Bash/Read/Write/Search/Task/Edit），上下拖动内容区域
   - 验证：内容区上下滚动时无明显卡顿，手指拖动响应即时
   - 验证：到达边界后不带动外层对话列表
   - 验证：惯性 fling 到达边界后停止（不继续带动外层）

2. **思考卡片滚动**: 展开思考卡片，上下拖动
   - 验证：内容区可以流畅上下滚动
   - 验证：到达边界后不带动外层对话列表

3. **复制弹窗**: 点击 📋 图标
   - 验证：弹窗打开，显示 markdown 源码
   - 验证：可以长按选择文字
   - 验证：切换到渲染视图正常显示
   - 验证：点击"复制全部"成功复制

4. **统计信息**: 触发一次多消息的 agent 回答
   - 验证：中间的 Assistant 消息无 Footer 统计
   - 验证：最后一条 Assistant 消息的 Footer 显示汇总
   - 验证：汇总的 token/cost/duration 是所有消息的总和

5. **间距**: 视觉检查工具卡片展开/折叠态的间距
   - 验证：各卡片的 padding/spacing 值与设计 C1-C8 中的精确数值表一致（如 ToolCallCard 外层 Column padding 应为 4.dp）

### 单元测试补充

1. `ChatModifiersTest`:
   - `test_onPostScroll_atTop_scrollUp_consumesAvailable`: scrollState.value=0, available.y<0 → 返回 available
   - `test_onPostScroll_atBottom_scrollDown_consumesAvailable`: canScrollForward=false, available.y>0 → 返回 available
   - `test_onPostScroll_notAtBoundary_returnsZero`: 非边界状态 → 返回 Offset.Zero
   - `test_onPostFling_atTop_flingUp_consumesVelocity`: 同 onPostScroll 逻辑但测试 Velocity

2. `TurnGroupTest`:
   - `test_emptyMessages_returnsEmptyMap`: 空列表 → 空 Map
   - `test_singleAssistant_turnGroupsWithOneEntry`: 单条 Assistant → Map 含 1 个 entry
   - `test_multipleAssistants_groupedAsOneTurn`: 3 条连续 Assistant → 同一 group
   - `test_mixedUserAssistant_correctGrouping`: User-Asst-Asst-User-Asst → 两组 turn
   - `test_onlyUserMessages_returnsEmptyMap`: 纯 User 消息 → 空 Map

3. `AssistantMessageCardTest`:
   - `test_isTurnLast_true_withStepFinishes_showsFooter`: isTurnLast=true, stepFinishes 有数据 → Footer 可见
   - `test_isTurnLast_false_hidesFooter`: isTurnLast=false → Footer 不可见
   - `test_isTurnLast_true_noStepFinishes_hidesFooter`: isTurnLast=true 但无统计数据 → Footer 不可见
   - `test_turnAggregation_sumsAllStepFinishes`: 多条消息的 stepFinishes 正确聚合

### 回归测试

确保已有 692 个测试全部通过（`testDevDebugUnitTest`）。
