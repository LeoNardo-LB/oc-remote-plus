# Phase 2: Chat 模块重构 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 ChatScreen.kt (8236行) 拆分为职责单一的模块化组件

**Architecture:** 从 ChatScreen.kt 逐步提取子系统到独立文件，每步编译验证，最终 ChatScreen.kt 仅保留布局骨架

**Tech Stack:** Jetpack Compose, Material3, Hilt, Kotlin Coroutines

**Prerequisites:** Phase 0 (测试基础设施修复 + Characterization Tests) 和 Phase 1 (Domain 层接口定义 + UseCase 抽象) 已完成

---

## 前置知识：ChatScreen.kt 函数清单

| 行号范围 | 函数名 | 类型 | 目标提取位置 |
|----------|--------|------|-------------|
| 205–229 | LocalChatFontSize … LocalOnToggleToolExpanded (9个 CompositionLocal) | CompositionLocal | 保留在 ChatScreen.kt 或提取到 `util/ChatCompositionLocals.kt` |
| 232–236 | `isAmoledTheme()` | @Composable util | `util/ChatColors.kt` |
| 238–250 | `toolOutputContainerColor(isAmoled)` | @Composable util | `util/ChatColors.kt` |
| 252–275 | `performHaptic(view, enabled)` | private fun util | `util/ChatModifiers.kt` |
| 267–279 | agentColorCycle / QueuedBadgeColor / QueuedBadgeTextColor | private val 常量 | `util/ChatColors.kt` |
| 281–292 | `agentColor(agentName, agents)` | private fun util | `util/ChatColors.kt` |
| 294–307 | `Modifier.codeHorizontalScroll()` | @Composable Modifier | `util/ChatModifiers.kt` |
| 309–346 | `Modifier.consumeBoundaryFling(scrollState)` | @Composable Modifier | `util/ChatModifiers.kt` |
| 348–362 | `clientCommands()` | @Composable fun | `input/SlashCommandMenu.kt` |
| 364–428 | `PulsingDotsIndicator()` | @Composable component | `components/PulsingDotsIndicator.kt` |
| 430–472 | `BreathingCircleIndicator()` | @Composable component | `components/BreathingCircleIndicator.kt` |
| 474–480 | `formatTokenCount(count)` | private fun util | `util/ChatFormatters.kt` |
| 482–491 | `formatAssistantErrorMessage(error)` | private fun util | `util/ChatFormatters.kt` |
| 493–644 | `ErrorPayloadContent(...)` | @Composable component | `components/ErrorPayloadContent.kt` |
| 645–740 | `buildPromptParts(text, confirmedPaths, sessionDirectory)` | private fun input | `input/ChatInputBar.kt` 或 `util/ChatFormatters.kt` |
| 741–750 | `decodeDataUrlBytes(dataUrl)` | private fun media | `util/MediaUtils.kt` |
| 751–761 | `decodePartFileBytes(file)` | private fun media | `util/MediaUtils.kt` |
| 762–771 | `extensionForMime(mime)` | private fun media | `util/MediaUtils.kt` |
| 772–797 | `imageThumbnailModel(attachment)` | private fun media | `util/MediaUtils.kt` |
| 798–802 | `estimateVisionTokens(width, height)` | private fun media | `util/MediaUtils.kt` |
| 803–810 | `formatFileSize(bytes)` | private fun util | `util/ChatFormatters.kt` |
| 812–918 | `buildAttachmentFromUri(...)` | private suspend fun media | `util/MediaUtils.kt` |
| 921–3006 | `ChatScreen(...)` — 主入口 | @Composable | 保留在 `ChatScreen.kt` (逐步瘦身) |
| 3009–3122 | `ModelPickerDialog(...)` | @Composable dialog | `dialog/ModelPickerDialog.kt` |
| 3124–3633 | `SessionTerminalInline(...)` | @Composable terminal | `terminal/SessionTerminalInline.kt` |
| 3634–3710 | `TerminalKeyboardOverlay(...)` | @Composable terminal | `terminal/TerminalKeyboardOverlay.kt` |
| 3711–3752 | `TerminalKeyRow(keys)` | @Composable terminal | `terminal/TerminalKeyboardOverlay.kt` |
| 3754–3770 | `applyTerminalModifiers(input, ctrl, alt)` | private fun terminal | `terminal/TerminalKeys.kt` |
| 3772–3831 | `applyTermuxFnBindings(input, cursorApp)` | private fun terminal | `terminal/TerminalKeys.kt` |
| 3833–3850 | `ctrlTransform(ch)` | private fun terminal | `terminal/TerminalKeys.kt` |
| 3851–3896 | `resolveStepsStatus(stepParts)` | @Composable util | `util/ChatFormatters.kt` |
| 3897–4159 | `ChatMessageBubble(...)` | @Composable component | `components/ChatMessageBubble.kt` |
| 4161–4172 | `formatDuration(ms)` | private fun util | `util/ChatFormatters.kt` |
| 4174–4361 | `AssistantMessageCard(...)` | @Composable component | `components/AssistantMessageCard.kt` |
| 4363–4532 | `AssistantTurnBubble(...)` | @Composable component | `components/AssistantTurnBubble.kt` |
| 4533–4580 | `resolveUserCommandLabel(parts)` | @Composable util | `util/ChatFormatters.kt` |
| 4582–4625 | `RevertBanner(onRedo)` | @Composable component | `components/RevertBanner.kt` |
| 4627–4800 | `PartContent(...)` | @Composable component | `components/PartContent.kt` |
| 4806–5018 | `MarkdownContent(...)` | @Composable markdown | `markdown/MarkdownContent.kt` |
| 5019–5193 | `SimpleMarkdownTable(...)` | @Composable markdown | `markdown/MarkdownTable.kt` |
| 5194–5199 | `looksLikeHtmlPayload(text)` | private fun markdown | `markdown/MarkdownContent.kt` |
| 5200–5232 | `normalizeHtmlForEmbeddedPreview(html)` | private fun markdown | `markdown/MarkdownContent.kt` |
| 5233–5247 | `preserveRawHtmlPayload(markdown)` | private fun markdown | `markdown/MarkdownContent.kt` |
| 5248–5381 | `ReasoningBlock(...)` | @Composable component | `components/ReasoningBlock.kt` |
| 5383–5563 | `ToolCallCard(...)` | @Composable tool | `tools/ToolCardRenderer.kt` |
| 5564–5685 | `resolveToolDisplay(...)` | @Composable tool | `tools/ToolCardRegistry.kt` |
| 5686–5693 | `extractToolInput(tool)` | private fun tool | `tools/ToolCardRenderer.kt` |
| 5695–5705 | `extractToolOutput(tool)` | private fun tool | `tools/ToolCardRenderer.kt` |
| 5707–5838 | `EditToolCard(...)` | @Composable tool | `tools/cards/EditToolCard.kt` |
| 5840–5862 | `DiffChangesInline(...)` | @Composable component | `components/DiffChangesInline.kt` |
| 5864–5927 | `DiffView(...)` | @Composable component | `components/DiffChangesInline.kt` |
| 5928–5978 | `computeSimpleDiff(...)` | private fun component | `components/DiffChangesInline.kt` |
| 5980–6079 | `WriteToolCard(...)` | @Composable tool | `tools/cards/WriteToolCard.kt` |
| 6080–6212 | `BashToolCard(...)` | @Composable tool | `tools/cards/BashToolCard.kt` |
| 6213–6344 | `ReadToolCard(...)` | @Composable tool | `tools/cards/ReadToolCard.kt` |
| 6345–6456 | `SearchToolCard(...)` | @Composable tool | `tools/cards/SearchToolCard.kt` |
| 6457–6593 | `TaskToolCard(...)` | @Composable tool | `tools/cards/TaskToolCard.kt` |
| 6594–6711 | `TodoListCard(...)` | @Composable tool | `tools/cards/TodoListCard.kt` |
| 6712–6751 | `TodoItemRow(todo)` | @Composable tool | `tools/cards/TodoListCard.kt` |
| 6752–6832 | `PatchCard(...)` | @Composable tool | `tools/cards/PatchCard.kt` |
| 6833–6908 | `ImageThumbnailRow(...)` | @Composable component | `components/ImageThumbnailRow.kt` 或 `dialog/ImagePreviewDialog.kt` |
| 6910–6986 | `ImagePreviewDialog(...)` | @Composable dialog | `dialog/ImagePreviewDialog.kt` |
| 6988–6993 | `FileCard(file)` | @Composable component | `components/FileCard.kt` |
| 6994–7042 | `FileCardFallback(file)` | @Composable component | `components/FileCard.kt` |
| 7044–7174 | `PermissionCard(...)` | @Composable dialog | `dialog/PermissionRequestCard.kt` |
| 7167–7176 | placeholderHintResIds | private val input | `input/ChatInputBar.kt` |
| 7177–7880 | `ChatInputBar(...)` | @Composable input | `input/ChatInputBar.kt` |
| 7889–8236 | `QuestionCard(...)` | @Composable dialog | `dialog/QuestionCard.kt` |

> **行号回退策略**：本表格中的行号基于原始 ChatScreen.kt。如果在 Phase 0/1 执行期间文件有变动导致行号偏移，请使用函数名搜索定位：
> ```powershell
> # PowerShell
> Select-String -Path "app\src\main\kotlin\dev\minios\ocremote\ui\screens\chat\ChatScreen.kt" -Pattern "fun SessionTerminalInline" | Select-Object LineNumber
> ```
> 每个 Task 的 Step 1 应先确认函数位置再执行提取。

---

## File Structure

### 新建目录

```
ui/screens/chat/
├── terminal/          # Task 1
│   ├── SessionTerminalInline.kt
│   ├── TerminalKeyboardOverlay.kt   (含 TerminalKeyRow)
│   └── TerminalKeys.kt              (applyTerminalModifiers/applyTermuxFnBindings/ctrlTransform)
├── util/              # Task 2
│   ├── ChatColors.kt               (isAmoledTheme, toolOutputContainerColor, agentColor, 颜色常量)
│   ├── ChatFormatters.kt           (formatTokenCount, formatAssistantErrorMessage, formatDuration, formatFileSize, resolveStepsStatus, resolveUserCommandLabel)
│   ├── ChatModifiers.kt            (codeHorizontalScroll, consumeBoundaryFling, performHaptic)
│   ├── MediaUtils.kt               (decodeDataUrlBytes, decodePartFileBytes, extensionForMime, imageThumbnailModel, estimateVisionTokens, buildAttachmentFromUri)
│   └── ChatCompositionLocals.kt    (9个 CompositionLocal)
├── markdown/          # Task 3
│   ├── MarkdownContent.kt          (含 looksLikeHtmlPayload, normalizeHtmlForEmbeddedPreview, preserveRawHtmlPayload)
│   └── MarkdownTable.kt            (SimpleMarkdownTable)
│   # 注：MarkdownContent 在 Phase 2 仅在 chat/markdown/ 内部使用，Phase 5 决定是否提升到 ui/components/
├── tools/             # Task 4
│   ├── ToolCardRegistry.kt         (object ToolCardRegistry + ToolDisplay data class)
│   ├── ToolCardRenderer.kt         (ToolCallCard + extractToolInput/extractToolOutput)
│   └── cards/
│       ├── BashToolCard.kt
│       ├── ReadToolCard.kt
│       ├── WriteToolCard.kt
│       ├── EditToolCard.kt
│       ├── SearchToolCard.kt
│       ├── TaskToolCard.kt
│       ├── TodoListCard.kt         (含 TodoItemRow)
│       └── PatchCard.kt
├── dialog/            # Task 5
│   ├── ModelPickerDialog.kt
│   ├── ImagePreviewDialog.kt       (含 ImageThumbnailRow)
│   ├── QuestionCard.kt
│   └── PermissionRequestCard.kt    (PermissionCard)
├── input/             # Task 6
│   ├── ChatInputBar.kt             (含 clientCommands, buildPromptParts, placeholderHintResIds)
│   ├── SlashCommandMenu.kt         (从 ChatInputBar 中提取斜杠命令菜单)
│   └── AttachmentPreview.kt        (从 ChatInputBar 中提取附件预览)
│   # 注：ChatInputBar 在 Phase 2 仅在 chat/input/ 内部使用，Phase 5 决定是否提升到 ui/components/
├── components/        # Task 7
│   ├── ChatMessageBubble.kt
│   ├── AssistantMessageCard.kt
│   ├── AssistantTurnBubble.kt
│   ├── UserMessageBubble.kt        (ChatMessageBubble 中 user 分支的独立文件)
│   ├── ReasoningBlock.kt
│   ├── PartContent.kt
│   ├── DiffChangesInline.kt        (含 DiffView, computeSimpleDiff)
│   ├── RevertBanner.kt
│   ├── ErrorPayloadContent.kt
│   ├── FileCard.kt                 (含 FileCardFallback)
│   └── ImageThumbnailRow.kt
```

### 修改的现有文件

| 文件 | 变更 |
|------|------|
| `ChatScreen.kt` | 逐步删除已提取的函数，仅保留 ChatScreen composable 骨架 |
| `ChatViewModel.kt` | Task 8 中逐步替换直接 API 调用为 UseCase 委托 |
| `ChatParts.kt` | 不变（已在独立文件中） |
| `TerminalEmulator.kt` | 不变（已在独立文件中） |
| `ServerTerminalWorkspace.kt` | 不变（已在独立文件中） |

### 新建路由文件（Task 9）

```
ui/screens/chat/
├── ChatRoute.kt      # Navigation Route 封装
```

---

## Task 1: 提取 terminal/ 子系统

**预计提取行数:** ~1200 行
**ChatScreen.kt 剩余:** ~7000 行

### Step 1.1: 定位要提取的函数

在 ChatScreen.kt 中定位以下函数：

| 行号 | 函数 | 提取目标 |
|------|------|----------|
| 3124–3633 | `SessionTerminalInline(...)` | `terminal/SessionTerminalInline.kt` |
| 3634–3710 | `TerminalKeyboardOverlay(...)` | `terminal/TerminalKeyboardOverlay.kt` |
| 3711–3752 | `TerminalKeyRow(keys)` | `terminal/TerminalKeyboardOverlay.kt` (同一文件) |
| 3754–3770 | `applyTerminalModifiers(...)` | `terminal/TerminalKeys.kt` |
| 3772–3831 | `applyTermuxFnBindings(...)` | `terminal/TerminalKeys.kt` |
| 3833–3850 | `ctrlTransform(ch)` | `terminal/TerminalKeys.kt` |

### Step 1.2: 创建 `terminal/TerminalKeys.kt`

**路径:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/terminal/TerminalKeys.kt`

**提取内容:**
- `applyTerminalModifiers(input: String, ctrl: Boolean, alt: Boolean): String`
- `applyTermuxFnBindings(input: String, cursorApp: Boolean): FnBindingResult`
- `ctrlTransform(ch: Char): Char`

**参数/状态依赖:**
- 输入均为原始参数，无外部依赖
- `FnBindingResult` 类型需确认定义位置（可能在 TerminalEmulator.kt 中）

**可见性:** `internal`（同 package 可访问）

**需要的 import:**
- 确认 `FnBindingResult` 的 package 位置

### Step 1.3: 创建 `terminal/TerminalKeyboardOverlay.kt`

**路径:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/terminal/TerminalKeyboardOverlay.kt`

**提取内容:**
- `TerminalKeyboardOverlay(...)` @Composable（行 3634–3710）
- `TerminalKeyRow(keys: List<TerminalKey>)` @Composable（行 3711–3752）

**参数/状态依赖:**
- `TerminalKey` 类型（需确认定义位置，可能在 TerminalEmulator.kt 中）
- `applyTerminalModifiers` / `applyTermuxFnBindings` / `ctrlTransform` — 从同目录 `TerminalKeys.kt` 导入

**可见性:** `internal`

**需要的 import:**
- `dev.minios.ocremote.ui.screens.chat.terminal.TerminalKeys.*` (如果 internal 可直接同包访问)
- Compose UI 相关: `Modifier`, `Row`, `Button`, `Text` 等
- `TerminalEmulator` (可能在 `dev.minios.ocremote.ui.screens.chat` 中)

### Step 1.4: 创建 `terminal/SessionTerminalInline.kt`

**路径:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/terminal/SessionTerminalInline.kt`

**提取内容:**
- `SessionTerminalInline(...)` @Composable（行 3124–3633）

**函数签名:**
```kotlin
@Composable
internal fun SessionTerminalInline(
    emulator: TerminalEmulator,
    terminalVersion: Long,
    connected: Boolean,
    focusRequester: FocusRequester,
    onSendInput: (String) -> Unit,
    onPaste: () -> Unit,
    onResize: (cols: Int, rows: Int) -> Unit,
    fontSizeSp: Float,
    onFontSizeChange: (Float) -> Unit,
    contentBottomPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
)
```

**参数/状态依赖:**
- `TerminalEmulator` — 从 `dev.minios.ocremote.ui.screens.chat` 导入
- `TerminalKeyboardOverlay` — 从同目录导入
- `isAmoledTheme()` — 暂时在此文件内复制一份，或提取为 internal fun（Task 2 会统一处理）
- Compose UI: `LocalSoftwareKeyboardController`, `TextFieldValue`, `ScrollState`, `Canvas` 等

**注意:** `SessionTerminalInline` 内部会调用 `TerminalKeyboardOverlay`，确保引用正确。

### Step 1.5: 在 ChatScreen.kt 中引用新文件

- 删除 ChatScreen.kt 中被提取的函数（行 3124–3850）
- 在 ChatScreen.kt 中添加 import:
  ```kotlin
  import dev.minios.ocremote.ui.screens.chat.terminal.SessionTerminalInline
  ```
- `SessionTerminalInline` 在 ChatScreen.kt 中被 `ChatScreen(...)` 调用，确认调用点参数匹配

### Step 1.6: 编译验证

```bash
cd D:\Develop\code\app\oc-remote
./gradlew assembleDebug
```

### Step 1.7: Commit

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/terminal/
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt
git commit -m "refactor(chat): extract terminal/ subsystem from ChatScreen.kt

Extract SessionTerminalInline, TerminalKeyboardOverlay, and TerminalKeys
into ui/screens/chat/terminal/ package.

Phase 2 Task 1/9 — no behavior changes."
```

---

## Task 2: 提取 util/ 辅助函数

**预计提取行数:** ~400 行
**ChatScreen.kt 剩余:** ~6600 行

### Step 2.1: 定位要提取的函数

| 行号 | 函数 | 提取目标 |
|------|------|----------|
| 205–229 | 9个 CompositionLocal | `util/ChatCompositionLocals.kt` |
| 232–236 | `isAmoledTheme()` | `util/ChatColors.kt` |
| 238–250 | `toolOutputContainerColor(isAmoled)` | `util/ChatColors.kt` |
| 252–275 | `performHaptic(view, enabled)` | `util/ChatModifiers.kt` |
| 267–279 | agentColorCycle, QueuedBadgeColor, QueuedBadgeTextColor | `util/ChatColors.kt` |
| 281–292 | `agentColor(agentName, agents)` | `util/ChatColors.kt` |
| 294–307 | `Modifier.codeHorizontalScroll()` | `util/ChatModifiers.kt` |
| 309–346 | `Modifier.consumeBoundaryFling(scrollState)` | `util/ChatModifiers.kt` |
| 474–480 | `formatTokenCount(count)` | `util/ChatFormatters.kt` |
| 482–491 | `formatAssistantErrorMessage(error)` | `util/ChatFormatters.kt` |
| 803–810 | `formatFileSize(bytes)` | `util/ChatFormatters.kt` |
| 741–750 | `decodeDataUrlBytes(dataUrl)` | `util/MediaUtils.kt` |
| 751–761 | `decodePartFileBytes(file)` | `util/MediaUtils.kt` |
| 762–771 | `extensionForMime(mime)` | `util/MediaUtils.kt` |
| 772–797 | `imageThumbnailModel(attachment)` | `util/MediaUtils.kt` |
| 798–802 | `estimateVisionTokens(width, height)` | `util/MediaUtils.kt` |
| 812–918 | `buildAttachmentFromUri(...)` | `util/MediaUtils.kt` |
| 4161–4172 | `formatDuration(ms)` | `util/ChatFormatters.kt` |
| 3851–3896 | `resolveStepsStatus(stepParts)` | `util/ChatFormatters.kt` |
| 4533–4580 | `resolveUserCommandLabel(parts)` | `util/ChatFormatters.kt` |

### Step 2.2: 创建 `util/ChatCompositionLocals.kt`

**路径:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/util/ChatCompositionLocals.kt`

**提取内容:**
```kotlin
package dev.minios.ocremote.ui.screens.chat.util

import androidx.compose.runtime.compositionLocalOf

val LocalChatFontSize = compositionLocalOf { "medium" }
val LocalCodeWordWrap = compositionLocalOf { true }
val LocalCompactMessages = compositionLocalOf { false }
val LocalCollapseTools = compositionLocalOf { false }
val LocalExpandReasoning = compositionLocalOf { false }
val LocalHapticFeedbackEnabled = compositionLocalOf { true }
val LocalImageSaveRequest = compositionLocalOf<(ByteArray, String, String?) -> Unit> { { _, _, _ -> } }
val LocalToolExpandedStates = compositionLocalOf<Map<String, Boolean>> { emptyMap() }
val LocalOnToggleToolExpanded = compositionLocalOf<(String) -> Unit> { } }
```

**可见性:** `val`（public，供其他子模块通过 import 访问）

### Step 2.3: 创建 `util/ChatColors.kt`

**路径:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/util/ChatColors.kt`

**提取内容:**
- `isAmoledTheme(): Boolean` — 改为 `internal`
- `toolOutputContainerColor(isAmoled: Boolean): Color` — 改为 `internal`
- `agentColor(agentName: String, agents: List<AgentInfo>): Color` — 改为 `internal`
- `agentColorCycle: List<Color>` — `internal val`
- `QueuedBadgeColor: Color` — `internal val`
- `QueuedBadgeTextColor: Color` — `internal val`

**参数/状态依赖:**
- `AgentInfo` — 从 `dev.minios.ocremote.data.api.AgentInfo` 导入
- `MaterialTheme`, `Color` — Compose UI

### Step 2.4: 创建 `util/ChatModifiers.kt`

**路径:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/util/ChatModifiers.kt`

**提取内容:**
- `performHaptic(view: android.view.View, enabled: Boolean)` — `internal fun`
- `Modifier.codeHorizontalScroll(): Modifier` — `internal fun Modifier`
- `Modifier.consumeBoundaryFling(scrollState: ScrollState): Modifier` — `internal fun Modifier`

**参数/状态依赖:**
- `ScrollState` — Compose Foundation
- `Velocity` — Compose UI Unit
- `LocalView` — Compose UI Platform

### Step 2.5: 创建 `util/ChatFormatters.kt`

**路径:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/util/ChatFormatters.kt`

**提取内容:**
- `formatTokenCount(count: Int): String`
- `formatAssistantErrorMessage(error: Message.Assistant.ErrorInfo?): String?`
- `formatFileSize(bytes: Int): String`
- `formatDuration(ms: Long): String`
- `resolveStepsStatus(stepParts: List<Part>): String` — 注意此函数有 `@Composable` 注解
- `resolveUserCommandLabel(parts: List<Part>): String?` — 注意此函数有 `@Composable` 注解

**参数/状态依赖:**
- `Message.Assistant.ErrorInfo` — 从 `dev.minios.ocremote.domain.model` 导入
- `Part` — 从 `dev.minios.ocremote.domain.model` 导入
- `MaterialTheme`, `stringResource` (resolveStepsStatus 可能用了 R.string)

### Step 2.6: 创建 `util/MediaUtils.kt`

**路径:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/util/MediaUtils.kt`

**提取内容:**
- `decodeDataUrlBytes(dataUrl: String): ByteArray?`
- `decodePartFileBytes(file: Part.File): ByteArray?`
- `extensionForMime(mime: String): String`
- `imageThumbnailModel(attachment: ImageAttachment): Any`
- `estimateVisionTokens(width: Int, height: Int): Int`
- `buildAttachmentFromUri(contentResolver, uri, compressImages, maxLongSidePx, webpQuality): PreparedAttachment?`

**参数/状态依赖:**
- `Part.File` — 从 `dev.minios.ocremote.domain.model` 导入
- `ImageAttachment`, `PreparedAttachment` — 需确认定义位置（可能在 ChatViewModel.kt 或 domain model 中）
- `android.content.ContentResolver`, `android.net.Uri`, `android.graphics.BitmapFactory`
- `kotlinx.coroutines.Dispatchers`, `withContext`

### Step 2.7: 更新所有引用

在 ChatScreen.kt 和之前 Task 1 创建的 terminal/ 文件中：
- 将 `isAmoledTheme()` 调用替换为从 `util.ChatColors` 导入
- 将 CompositionLocal 引用替换为从 `util.ChatCompositionLocals` 导入
- 删除 ChatScreen.kt 中已提取的函数定义

同时，也需要更新 Task 1 中 `terminal/SessionTerminalInline.kt` 中对 `isAmoledTheme()` 的引用。

### Step 2.8: 编译验证

```bash
./gradlew assembleDebug
```

### Step 2.9: Commit

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/util/
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/terminal/
git commit -m "refactor(chat): extract util/ helpers from ChatScreen.kt

Extract ChatCompositionLocals, ChatColors, ChatModifiers, ChatFormatters,
and MediaUtils into ui/screens/chat/util/ package.

Phase 2 Task 2/9 — no behavior changes."
```

---

## Task 3: 提取 markdown/ 渲染组件

**预计提取行数:** ~300 行
**ChatScreen.kt 剩余:** ~6300 行

### Step 3.1: 定位要提取的函数

| 行号 | 函数 | 提取目标 |
|------|------|----------|
| 4806–5018 | `MarkdownContent(markdown, textColor, isUser, customFontSize)` | `markdown/MarkdownContent.kt` |
| 5019–5193 | `SimpleMarkdownTable(...)` | `markdown/MarkdownTable.kt` |
| 5194–5199 | `looksLikeHtmlPayload(text)` | `markdown/MarkdownContent.kt` (private) |
| 5200–5232 | `normalizeHtmlForEmbeddedPreview(html)` | `markdown/MarkdownContent.kt` (private) |
| 5233–5247 | `preserveRawHtmlPayload(markdown)` | `markdown/MarkdownContent.kt` (private) |

### Step 3.2: 创建 `markdown/MarkdownContent.kt`

**路径:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/markdown/MarkdownContent.kt`

**提取内容:**
- `MarkdownContent(...)` @Composable — `internal`
- `looksLikeHtmlPayload(text)` — `private`
- `normalizeHtmlForEmbeddedPreview(html)` — `private`
- `preserveRawHtmlPayload(markdown)` — `private`
- `HtmlDocumentHintRegex`, `HtmlTagRegex` — `private val`

**函数签名:**
```kotlin
@Composable
internal fun MarkdownContent(
    markdown: String,
    textColor: Color,
    isUser: Boolean,
    customFontSize: String? = null
)
```

**参数/状态依赖:**
- `LocalChatFontSize`, `LocalCodeWordWrap` — 从 `util.ChatCompositionLocals` 导入
- `isAmoledTheme()` — 从 `util.ChatColors` 导入
- mikepenz markdown 库相关 import (Markdown, rememberMarkdownState, markdownColor, markdownTypography 等)
- `CodeTypography` — 从 `dev.minios.ocremote.ui.theme` 导入
- `SimpleMarkdownTable` — 从同目录 `MarkdownTable.kt` 导入
- Compose UI: `MaterialTheme`, `remember`, `Color`, `TextStyle` 等
- `WebView` 相关 (嵌入式 HTML 预览)
- `highlightedCodeBlock`, `highlightedCodeFence` — mikepenz 库

**注意事项:**
- `MarkdownContent` 内部使用大量 mikepenz markdown 库的自定义组件注册
- `WebView` 用于 HTML payload 的嵌入式预览

### Step 3.3: 创建 `markdown/MarkdownTable.kt`

**路径:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/markdown/MarkdownTable.kt`

**提取内容:**
- `SimpleMarkdownTable(...)` @Composable — `internal`

**参数/状态依赖:**
- AST Node 类型: `ASTNode`, `getTextInNode`
- GFM 类型: `GFMHeader`, `GFMRow`, `GFMCell`, `GFMTableSeparator`
- `LocalDensity`, `SubcomposeLayout`, `Layout` — Compose UI
- `isAmoledTheme()` — 从 `util.ChatColors` 导入

### Step 3.4: 更新 ChatScreen.kt 引用

- 删除 ChatScreen.kt 中行 4806–5247（MarkdownContent + 辅助函数 + SimpleMarkdownTable）
- 添加 import:
  ```kotlin
  import dev.minios.ocremote.ui.screens.chat.markdown.MarkdownContent
  ```
- `MarkdownContent` 被 `PartContent(...)` 调用

### Step 3.5: 编译验证

```bash
./gradlew assembleDebug
```

### Step 3.6: Commit

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/markdown/
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt
git commit -m "refactor(chat): extract markdown/ rendering components from ChatScreen.kt

Extract MarkdownContent, SimpleMarkdownTable, and HTML preview helpers
into ui/screens/chat/markdown/ package.

Phase 2 Task 3/9 — no behavior changes."
```

---

## Task 4: 提取 tools/ 工具卡片 + ToolCardRegistry

**预计提取行数:** ~2000 行
**ChatScreen.kt 剩余:** ~4300 行

### Step 4.1: 定位要提取的函数

| 行号 | 函数 | 提取目标 |
|------|------|----------|
| 5383–5563 | `ToolCallCard(tool, isExpanded, onToggleExpand)` | `tools/ToolCardRenderer.kt` |
| 5564–5685 | `resolveToolDisplay(toolName, state, input)` | `tools/ToolCardRegistry.kt` |
| 5686–5693 | `extractToolInput(tool)` | `tools/ToolCardRenderer.kt` |
| 5695–5705 | `extractToolOutput(tool)` | `tools/ToolCardRenderer.kt` |
| 5707–5838 | `EditToolCard(...)` | `tools/cards/EditToolCard.kt` |
| 5980–6079 | `WriteToolCard(...)` | `tools/cards/WriteToolCard.kt` |
| 6080–6212 | `BashToolCard(...)` | `tools/cards/BashToolCard.kt` |
| 6213–6344 | `ReadToolCard(...)` | `tools/cards/ReadToolCard.kt` |
| 6345–6456 | `SearchToolCard(...)` | `tools/cards/SearchToolCard.kt` |
| 6457–6593 | `TaskToolCard(...)` | `tools/cards/TaskToolCard.kt` |
| 6594–6711 | `TodoListCard(...)` | `tools/cards/TodoListCard.kt` |
| 6712–6751 | `TodoItemRow(todo)` | `tools/cards/TodoListCard.kt` |
| 6752–6832 | `PatchCard(...)` | `tools/cards/PatchCard.kt` |

### Step 4.2: 创建 `tools/ToolCardRegistry.kt`

**路径:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/tools/ToolCardRegistry.kt`

**提取内容:**
- `resolveToolDisplay(...)` @Composable — 提取其中的数据模型
- `ToolCardRegistry` object — 新建工厂模式注册表

**设计:**
```kotlin
package dev.minios.ocremote.ui.screens.chat.tools

import androidx.compose.runtime.Composable
import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.domain.model.ToolState

/** Display metadata resolved from a tool name and its state. */
data class ToolDisplay(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String,
    val description: String,
    val stateLabel: String
)

object ToolCardRegistry {
    private val builders = mutableMapOf<String, @Composable (Part.Tool) -> Unit>()

    fun register(toolName: String, builder: @Composable (Part.Tool) -> Unit) {
        builders[toolName] = builder
    }

    fun resolve(toolName: String): (@Composable (Part.Tool) -> Unit)? = builders[toolName]

    fun registerDefaults() {
        register("edit") { EditToolCard(it) }
        register("write") { WriteToolCard(it) }
        register("bash") { BashToolCard(it) }
        register("read") { ReadToolCard(it) }
        register("search") { SearchToolCard(it) }
        register("task") { TaskToolCard(it) }
        register("todowrite") { TodoListCard(it) }
        register("patch") { PatchCard(it) }
    }
}

@Composable
internal fun resolveToolDisplay(
    toolName: String,
    state: ToolState,
    input: kotlinx.serialization.json.JsonElement?
): ToolDisplay
```

**参数/状态依赖:**
- `Part.Tool` — 从 `dev.minios.ocremote.domain.model` 导入
- `ToolState` — 从 `dev.minios.ocremote.domain.model` 导入
- `kotlinx.serialization.json.JsonElement`
- Material Icons (各种工具图标)

### Step 4.3: 创建 `tools/ToolCardRenderer.kt`

**路径:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/tools/ToolCardRenderer.kt`

**提取内容:**
- `ToolCallCard(...)` @Composable — `internal`
- `extractToolInput(tool)` — `internal`
- `extractToolOutput(tool)` — `internal`

**函数签名:**
```kotlin
@Composable
internal fun ToolCallCard(
    tool: Part.Tool,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit
)
```

**参数/状态依赖:**
- `resolveToolDisplay` — 从同目录 `ToolCardRegistry.kt` 导入
- `ToolCardRegistry.resolve()` — 从同目录导入
- `isAmoledTheme()` — 从 `util.ChatColors` 导入
- `performHaptic` — 从 `util.ChatModifiers` 导入
- 各工具卡片: `EditToolCard`, `WriteToolCard` 等 — 从 `cards/` 导入
- `Part.Tool`, `ToolState`, `Part.Patch` — domain model

### Step 4.4: 创建各工具卡片文件

每个卡片独立文件，所有路径在 `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/tools/cards/` 下：

| 文件 | 函数 | 行号 | 参数签名概要 |
|------|------|------|-------------|
| `BashToolCard.kt` | `BashToolCard(tool: Part.Tool)` | 6080–6212 | 接收 Part.Tool，内部调用 extractToolInput/extractToolOutput |
| `ReadToolCard.kt` | `ReadToolCard(tool: Part.Tool)` | 6213–6344 | 同上 |
| `WriteToolCard.kt` | `WriteToolCard(tool: Part.Tool)` | 5980–6079 | 同上 |
| `EditToolCard.kt` | `EditToolCard(tool: Part.Tool)` | 5707–5838 | 同上 |
| `SearchToolCard.kt` | `SearchToolCard(tool: Part.Tool)` | 6345–6456 | 同上 |
| `TaskToolCard.kt` | `TaskToolCard(tool: Part.Tool)` | 6457–6593 | 同上 |
| `TodoListCard.kt` | `TodoListCard(tool: Part.Tool)` + `TodoItemRow(todo)` | 6594–6751 | tool + 内部 TodoItemRow |
| `PatchCard.kt` | `PatchCard(tool: Part.Tool)` | 6752–6832 | 接收 Part.Tool |

**每个卡片的通用依赖:**
- `Part.Tool` — domain model
- `isAmoledTheme()` — `util.ChatColors`
- `extractToolInput`, `extractToolOutput` — 从 `tools/ToolCardRenderer.kt` 导入
- Compose UI 相关: `Surface`, `Column`, `Row`, `Text`, `Modifier`, `Color` 等
- `kotlinx.serialization.json.*`

**可见性:** `internal`（供 ToolCardRegistry 注册使用）

### Step 4.5: 更新 PartContent 中的工具卡片分发逻辑

`PartContent(...)` (在 ChatScreen.kt 中，行 4627–4800) 当前通过 when 分发到各工具卡片。
更新为使用 `ToolCardRegistry.resolve(tool.tool)` 模式：

```kotlin
// 在 PartContent 中，替换 when 分发为:
is Part.Tool -> {
    val cardBuilder = ToolCardRegistry.resolve(part.tool)
    if (cardBuilder != null) {
        cardBuilder(part)
    } else {
        // fallback: ToolCallCard(part, expanded, toggle)
        ToolCallCard(part, toolExpandedStates[part.id] ?: true, { onToggleToolExpanded(part.id) })
    }
}
```

**注意:** 这步改动需要谨慎，确保 `todowrite` 和 `todoread` 的特殊处理逻辑保留。

### Step 4.6: 编译验证 + UI 验证

```bash
./gradlew assembleDebug
```

**UI 验证（手动）：**
- [ ] 发送消息后工具卡片正常显示
- [ ] 各工具卡片展开/折叠正常
- [ ] Bash/Read/Write/Edit/Search/Task/Patch/TodoList 卡片内容正确

### Step 4.7: Commit

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/tools/
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt
git commit -m "refactor(chat): extract tools/ with ToolCardRegistry from ChatScreen.kt

Extract ToolCallCard, resolveToolDisplay, and 8 tool card implementations
into ui/screens/chat/tools/ with factory pattern (ToolCardRegistry).

Phase 2 Task 4/9 — no behavior changes."
```

---

## Task 5: 提取 dialog/ 对话框

**预计提取行数:** ~500 行
**ChatScreen.kt 剩余:** ~3800 行

### Step 5.1: 定位要提取的函数

| 行号 | 函数 | 提取目标 |
|------|------|----------|
| 3009–3122 | `ModelPickerDialog(...)` | `dialog/ModelPickerDialog.kt` |
| 6833–6908 | `ImageThumbnailRow(...)` | `dialog/ImagePreviewDialog.kt` |
| 6910–6986 | `ImagePreviewDialog(...)` | `dialog/ImagePreviewDialog.kt` |
| 7044–7174 | `PermissionCard(...)` | `dialog/PermissionRequestCard.kt` |
| 7889–8236 | `QuestionCard(...)` | `dialog/QuestionCard.kt` |

### Step 5.2: 创建 `dialog/ModelPickerDialog.kt`

**路径:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/dialog/ModelPickerDialog.kt`

**函数签名:**
```kotlin
@Composable
internal fun ModelPickerDialog(
    providers: List<ProviderInfo>,
    selectedProviderId: String?,
    selectedModelId: String?,
    onSelect: (providerId: String, modelId: String) -> Unit,
    onDismiss: () -> Unit
)
```

**参数/状态依赖:**
- `ProviderInfo`, `ProviderModel` — 从 `dev.minios.ocremote.data.api` 导入
- `isAmoledTheme()` — 从 `util.ChatColors` 导入
- `ProviderIcon` — 从 `dev.minios.ocremote.ui.components` 导入
- Material3: `BasicAlertDialog`, `Surface`, `TabRow`, `Tab` 等

### Step 5.3: 创建 `dialog/ImagePreviewDialog.kt`

**路径:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/dialog/ImagePreviewDialog.kt`

**提取内容:**
- `ImageThumbnailRow(...)` @Composable — `internal`
- `ImagePreviewDialog(...)` @Composable — `internal`

**函数签名:**
```kotlin
@Composable
internal fun ImageThumbnailRow(
    images: List<Pair<String, ByteArray?>>,
    thumbnailSize: Dp = 64.dp,
    onClick: (Int) -> Unit
)

@Composable
internal fun ImagePreviewDialog(
    images: List<Pair<String, ByteArray?>>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    onSave: (ByteArray, String, String?) -> Unit
)
```

**参数/状态依赖:**
- `LocalImageSaveRequest` — 从 `util.ChatCompositionLocals` 导入
- `decodePartFileBytes`, `decodeDataUrlBytes` — 从 `util.MediaUtils` 导入
- `extensionForMime` — 从 `util.MediaUtils` 导入
- Compose UI: `Dialog`, `AsyncImage`, `Image`, `pinch-to-zoom`

### Step 5.4: 创建 `dialog/PermissionRequestCard.kt`

**路径:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/dialog/PermissionRequestCard.kt`

**提取内容:**
- `PermissionCard(...)` — 重命名为 `PermissionRequestCard` 以保持一致性

**函数签名:**
```kotlin
@Composable
internal fun PermissionCard(
    permission: SseEvent.PermissionAsked,
    onReply: (requestId: String, reply: String) -> Unit
)
```

**参数/状态依赖:**
- `SseEvent.PermissionAsked` — 从 `dev.minios.ocremote.domain.model` 导入
- `isAmoledTheme()` — 从 `util.ChatColors` 导入
- `performHaptic` — 从 `util.ChatModifiers` 导入

### Step 5.5: 创建 `dialog/QuestionCard.kt`

**路径:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/dialog/QuestionCard.kt`

**函数签名:**
```kotlin
@Composable
internal fun QuestionCard(
    question: SseEvent.QuestionAsked,
    onSubmit: (answers: List<List<String>>) -> Unit,
    onReject: () -> Unit
)
```

**参数/状态依赖:**
- `SseEvent.QuestionAsked` — 从 `dev.minios.ocremote.domain.model` 导入
- `isAmoledTheme()` — 从 `util.ChatColors` 导入
- `performHaptic` — 从 `util.ChatModifiers` 导入

### Step 5.6: 编译验证

```bash
./gradlew assembleDebug
```

### Step 5.7: Commit

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/dialog/
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt
git commit -m "refactor(chat): extract dialog/ components from ChatScreen.kt

Extract ModelPickerDialog, ImagePreviewDialog, PermissionRequestCard,
and QuestionCard into ui/screens/chat/dialog/ package.

Phase 2 Task 5/9 — no behavior changes."
```

---

## Task 6: 提取 input/ 输入区

**预计提取行数:** ~800 行
**ChatScreen.kt 剩余:** ~3000 行

### Step 6.1: 定位要提取的函数

| 行号 | 函数 | 提取目标 |
|------|------|----------|
| 7167–7176 | `placeholderHintResIds` | `input/ChatInputBar.kt` |
| 348–362 | `clientCommands()` | `input/SlashCommandMenu.kt` |
| 645–740 | `buildPromptParts(...)` | `input/ChatInputBar.kt` |
| 7177–7880 | `ChatInputBar(...)` | `input/ChatInputBar.kt` |

### Step 6.2: 创建 `input/ChatInputBar.kt`

**路径:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/input/ChatInputBar.kt`

**提取内容:**
- `placeholderHintResIds` — `private val`
- `ChatInputBar(...)` — `internal` @Composable
- `buildPromptParts(...)` — `internal fun` (从 ChatScreen.kt 行 645–740)

**函数签名:**
```kotlin
@Composable
internal fun ChatInputBar(
    textFieldValue: TextFieldValue,
    onTextFieldValueChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    isSending: Boolean,
    isBusy: Boolean = false,
    messages: List<ChatMessage> = emptyList(),
    attachments: List<ImageAttachment> = emptyList(),
    onAttach: () -> Unit = {},
    onRemoveAttachment: (Int) -> Unit = {},
    onSaveAttachment: (bytes: ByteArray, mime: String, filename: String?) -> Unit = { _, _, _ -> },
    modelLabel: String = "",
    selectedProviderId: String? = null,
    onModelClick: () -> Unit = {},
    agents: List<AgentInfo> = emptyList(),
    selectedAgent: String = "build",
    onAgentSelect: (String) -> Unit = {},
    variantNames: List<String> = emptyList(),
    selectedVariant: String? = null,
    onCycleVariant: () -> Unit = {},
    commands: List<CommandInfo> = emptyList(),
    fileSearchResults: List<String> = emptyList(),
    confirmedFilePaths: Set<String> = emptySet(),
    onFileSelected: (String) -> Unit = {},
    onSlashCommand: (SlashCommand) -> Unit = {},
    inputMode: ChatInputMode = ChatInputMode.NORMAL,
    onInputModeChange: (ChatInputMode) -> Unit = {},
    contextWindow: Int = 0,
    lastContextTokens: Int = 0,
    onStop: () -> Unit = {}
)
```

**参数/状态依赖:**
- `ChatMessage` — 从 `dev.minios.ocremote.ui.screens.chat` (ChatViewModel.kt 中定义)
- `ImageAttachment` — 确认定义位置
- `AgentInfo`, `CommandInfo` — 从 `dev.minios.ocremote.data.api` 导入
- `SlashCommand` — 确认定义位置（可能在本文件内部或单独定义）
- `ChatInputMode` — 确认定义位置
- `isAmoledTheme()` — `util.ChatColors`
- `performHaptic` — `util.ChatModifiers`
- `formatTokenCount`, `formatFileSize` — `util.ChatFormatters`
- `estimateVisionTokens` — `util.MediaUtils`
- `clientCommands()` — 从同目录 `SlashCommandMenu.kt` 导入
- `FileMentionTransformer` (行 556–638) — 可能也在 ChatScreen.kt 中，需一并提取
- Compose UI 大量组件

**注意:** `ChatInputBar` 是最复杂的组件之一（行 7177–7880，约 700 行），包含：
- 文件 @mention 自动补全弹窗
- 斜杠命令菜单
- 附件预览行
- Shell 模式切换
- Agent 选择
- Model 标签
- 上下文窗口 token 计数显示

**内含的子组件（可选进一步拆分）：**
- 斜杠命令菜单部分 → `input/SlashCommandMenu.kt`
- 附件预览部分 → `input/AttachmentPreview.kt`

### Step 6.3: 创建 `input/SlashCommandMenu.kt`

**路径:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/input/SlashCommandMenu.kt`

**提取内容:**
- `clientCommands()` — `internal fun`
- 从 `ChatInputBar` 内部提取斜杠命令显示/过滤逻辑

**参数/状态依赖:**
- `SlashCommand` — 需确认定义位置
- `CommandInfo` — `dev.minios.ocremote.data.api`

### Step 6.4: 创建 `input/AttachmentPreview.kt`

**路径:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/input/AttachmentPreview.kt`

**提取内容:**
- 从 `ChatInputBar` 中提取附件缩略图预览行
- 包含删除、保存等操作

**参数/状态依赖:**
- `ImageAttachment` — 确认定义位置
- `imageThumbnailModel` — `util.MediaUtils`
- `formatFileSize`, `estimateVisionTokens` — `util.ChatFormatters`

### Step 6.5: 编译验证

```bash
./gradlew assembleDebug
```

### Step 6.6: Commit

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/input/
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt
git commit -m "refactor(chat): extract input/ components from ChatScreen.kt

Extract ChatInputBar, SlashCommandMenu, AttachmentPreview, and
buildPromptParts into ui/screens/chat/input/ package.

Phase 2 Task 6/9 — no behavior changes."
```

---

## Task 7: 提取 components/ 消息组件

**预计提取行数:** ~1500 行
**ChatScreen.kt 剩余:** ~1500 行

### Step 7.1: 定位要提取的函数

| 行号 | 函数 | 提取目标 |
|------|------|----------|
| 364–428 | `PulsingDotsIndicator()` | **不提取** — 保留在 ChatScreen.kt，由 Phase 5 统一提取到 `ui/components/` |
| 430–472 | `BreathingCircleIndicator()` | **不提取** — 保留在 ChatScreen.kt，由 Phase 5 统一提取到 `ui/components/` |
| 493–644 | `ErrorPayloadContent(...)` | `components/ErrorPayloadContent.kt` |
| 3897–4159 | `ChatMessageBubble(...)` | `components/ChatMessageBubble.kt` |
| 4174–4361 | `AssistantMessageCard(...)` | `components/AssistantMessageCard.kt` |
| 4363–4532 | `AssistantTurnBubble(...)` | `components/AssistantTurnBubble.kt` |
| 4582–4625 | `RevertBanner(onRedo)` | `components/RevertBanner.kt` |
| 4627–4800 | `PartContent(...)` | `components/PartContent.kt` |
| 5248–5381 | `ReasoningBlock(...)` | `components/ReasoningBlock.kt` |
| 5840–5862 | `DiffChangesInline(...)` | `components/DiffChangesInline.kt` |
| 5864–5927 | `DiffView(...)` | `components/DiffChangesInline.kt` |
| 5928–5978 | `computeSimpleDiff(...)` | `components/DiffChangesInline.kt` |
| 6988–6993 | `FileCard(file)` | `components/FileCard.kt` |
| 6994–7042 | `FileCardFallback(file)` | `components/FileCard.kt` |

### Step 7.2: 创建各组件文件

**注意顺序：** 先提取无外部依赖的叶子组件，再提取依赖叶子的中间组件。

#### 叶子组件（无内部依赖）

| 文件 | 函数 | 依赖 |
|------|------|------|
| ~~`components/PulsingDotsIndicator.kt`~~ | ~~`PulsingDotsIndicator()`~~ | ~~仅 Compose 基础~~ — **由 Phase 5 统一提取** |
| ~~`components/BreathingCircleIndicator.kt`~~ | ~~`BreathingCircleIndicator()`~~ | ~~仅 Compose 基础~~ — **由 Phase 5 统一提取** |
| `components/RevertBanner.kt` | `RevertBanner(onRedo)` | Compose UI + Material Icons |
| `components/DiffChangesInline.kt` | `DiffChangesInline(...)` + `DiffView(...)` + `computeSimpleDiff(...)` | Compose UI |
| `components/ReasoningBlock.kt` | `ReasoningBlock(...)` | Compose UI + Markdown 渲染 |
| `components/FileCard.kt` | `FileCard(...)` + `FileCardFallback(...)` | `util.MediaUtils` |
| `components/ErrorPayloadContent.kt` | `ErrorPayloadContent(...)` | Compose UI + WebView |

#### 中间组件（依赖叶子组件）

| 文件 | 函数 | 依赖 |
|------|------|------|
| `components/PartContent.kt` | `PartContent(part, textColor, isUser, onViewSubSession, turnAgentName)` | MarkdownContent, ReasoningBlock, ToolCallCard, FileCard, MarkdownTable, DiffChangesInline |
| `components/AssistantMessageCard.kt` | `AssistantMessageCard(chatMessage, isContinuation, ...)` | PartContent, PulsingDotsIndicator, util.*
| `components/AssistantTurnBubble.kt` | `AssistantTurnBubble(messages, ...)` | AssistantMessageCard |
| `components/ChatMessageBubble.kt` | `ChatMessageBubble(chatMessage, ...)` | AssistantMessageCard, AssistantTurnBubble, PartContent, RevertBanner, util.* |

### Step 7.3: 关键组件签名

#### `components/PartContent.kt`
```kotlin
@Composable
internal fun PartContent(
    part: Part,
    textColor: Color,
    isUser: Boolean = false,
    onViewSubSession: ((String) -> Unit)? = null,
    turnAgentName: String? = null
)
```
**依赖:** MarkdownContent, ReasoningBlock, ToolCallCard, ToolCardRegistry, FileCard, LocalToolExpandedStates, LocalOnToggleToolExpanded, LocalExpandReasoning

#### `components/AssistantMessageCard.kt`
```kotlin
@Composable
internal fun AssistantMessageCard(
    chatMessage: ChatMessage,
    isContinuation: Boolean,
    onViewSubSession: ((String) -> Unit)? = null,
    onCopyText: (() -> Unit)? = null
)
```
**依赖:** PartContent, PulsingDotsIndicator, filterRenderableParts, formatAssistantErrorMessage, LocalCompactMessages, agentColor, performHaptic

#### `components/ChatMessageBubble.kt`
```kotlin
@Composable
internal fun ChatMessageBubble(
    chatMessage: ChatMessage,
    isQueued: Boolean = false,
    onViewSubSession: ((String) -> Unit)? = null,
    onRevert: (() -> Unit)? = null,
    onCopyText: (() -> Unit)? = null
)
```
**依赖:** AssistantMessageCard, AssistantTurnBubble, RevertBanner, resolveUserCommandLabel, isBubbleRenderablePart, performHaptic

### Step 7.4: 更新 ChatScreen.kt 引用

- 删除所有已提取的组件函数
- 添加 import 指向新的组件文件
- ChatScreen.kt 中的 `ChatScreen(...)` 函数仅保留 LazyColumn 布局 + 对 ChatMessageBubble 和 ChatInputBar 的调用

### Step 7.5: 编译验证 + UI 验证

```bash
./gradlew assembleDebug
```

**UI 验证（手动）：**
- [ ] 消息列表正确显示（user + assistant）
- [ ] Markdown 渲染正常（代码块、列表、表格）
- [ ] 工具卡片展开/折叠
- [ ] 推理块展开/折叠
- [ ] Revert Banner 显示
- [ ] 错误消息显示
- [ ] 流式消息实时更新

### Step 7.6: Commit

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt
git commit -m "refactor(chat): extract components/ from ChatScreen.kt

Extract ChatMessageBubble, AssistantMessageCard, PartContent,
ReasoningBlock, DiffChangesInline, indicators, and more into
ui/screens/chat/components/ package.

Phase 2 Task 7/9 — no behavior changes."
```

---

## Task 8: ChatViewModel 委托 UseCase

**预计修改行数:** ~200 行改动
**ChatViewModel.kt:** 从 1435 行逐步瘦身

> **⚠️ D5 FIX — 前置条件变更：** Phase 1 只创建了 5 个 UseCase（SendMessage, CreateSession, DeleteSession, GetServerList, UpdateSettings），但 Task 8 需要 9 个 UseCase。以下 8 个 UseCase 需在执行 Task 8 前创建：
>
> | 缺失 UseCase | 对应 Phase | 创建位置 |
> |---|---|---|
> | ManageSessionUseCase | Phase 2 | **Step 8.0-A (本 Task)** |
> | ManagePermissionUseCase | Phase 2 | **Step 8.0-B (本 Task)** |
> | SelectModelUseCase | Phase 2 | **Step 8.0-C (本 Task)** |
> | ManageAgentUseCase | Phase 2 | **Step 8.0-D (本 Task)** |
> | ManageTerminalUseCase | Phase 2 | **Step 8.0-E (本 Task)** |
> | DraftUseCase | Phase 2 | **Step 8.0-F (本 Task)** |
> | ShareExportUseCase | Phase 2 | **Step 8.0-G (本 Task)** |
> | UndoRedoUseCase | Phase 2 | **Step 8.0-H (本 Task)** |
>
> 这些 UseCase 在创建时只有**空壳实现**（委托给现有 data 层），不改变行为。完整实现和测试在 Phase 4 中补充。

### Step 8.0: 创建缺失的 UseCase 桩文件

> **D5 FIX:** 每个 UseCase 先创建空壳实现，委托给 ChatViewModel 当前使用的 data 层 API。签名基于 ChatViewModel 中对应方法分析得出。每个文件创建后运行 `.\gradlew compileDebugKotlin` 验证。

- [ ] **Step 8.0-A: ManageSessionUseCase**

```kotlin
// domain/usecase/ManageSessionUseCase.kt
package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.api.ServerConnection
import dev.minios.ocremote.domain.model.Message
import dev.minios.ocremote.domain.model.Session
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Use case: manage session lifecycle (load, refresh, create, fork, rename).
 * Temporary shell — delegates to OpenCodeApi. Full impl with tests in Phase 4.
 */
class ManageSessionUseCase @Inject constructor(
    private val api: OpenCodeApi
) {
    // TODO: Phase 4 — replace api calls with ChatRepository/SessionRepository methods
    suspend fun loadMessages(conn: ServerConnection, sessionId: String, limit: Int): Result<List<Message>> =
        runCatching { api.getMessages(conn, sessionId, limit) }

    suspend fun refreshSession(conn: ServerConnection, sessionId: String): Result<Unit> =
        runCatching { api.refreshSession(conn, sessionId) }

    suspend fun loadOlderMessages(conn: ServerConnection, sessionId: String, before: String, limit: Int): Result<List<Message>> =
        runCatching { api.getMessagesBefore(conn, sessionId, before, limit) }

    suspend fun createNewSession(conn: ServerConnection, directory: String?): Result<Session> =
        runCatching { api.createSession(conn, directory) }

    suspend fun forkSession(conn: ServerConnection, sessionId: String): Result<Session> =
        runCatching { api.forkSession(conn, sessionId) }

    suspend fun renameSession(conn: ServerConnection, sessionId: String, title: String): Result<Unit> =
        runCatching { api.renameSession(conn, sessionId, title) }
}
```

- [ ] **Step 8.0-B: ManagePermissionUseCase**

```kotlin
// domain/usecase/ManagePermissionUseCase.kt
package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.api.ServerConnection
import javax.inject.Inject

class ManagePermissionUseCase @Inject constructor(
    private val api: OpenCodeApi
) {
    suspend fun replyToPermission(conn: ServerConnection, sessionId: String, requestId: String, allowed: Boolean): Result<Unit> =
        runCatching { api.replyPermission(conn, sessionId, requestId, allowed) }

    suspend fun replyToQuestion(conn: ServerConnection, sessionId: String, requestId: String, answers: List<String>): Result<Unit> =
        runCatching { api.replyQuestion(conn, sessionId, requestId, answers) }

    suspend fun rejectQuestion(conn: ServerConnection, sessionId: String, requestId: String): Result<Unit> =
        runCatching { api.rejectQuestion(conn, sessionId, requestId) }
}
```

- [ ] **Step 8.0-C: SelectModelUseCase**

```kotlin
// domain/usecase/SelectModelUseCase.kt
package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.api.ServerConnection
import javax.inject.Inject

class SelectModelUseCase @Inject constructor(
    private val api: OpenCodeApi
) {
    suspend fun selectModel(conn: ServerConnection, sessionId: String, modelId: String): Result<Unit> =
        runCatching { api.selectModel(conn, sessionId, modelId) }

    suspend fun loadProviders(conn: ServerConnection): Result<List<dev.minios.ocremote.domain.model.ProviderInfo>> =
        runCatching { api.getProviders(conn).map { dev.minios.ocremote.domain.model.ProviderInfo(id = it.id, name = it.name) } }
}
```

- [ ] **Step 8.0-D: ManageAgentUseCase**

```kotlin
// domain/usecase/ManageAgentUseCase.kt
package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.api.ServerConnection
import javax.inject.Inject

class ManageAgentUseCase @Inject constructor(
    private val api: OpenCodeApi
) {
    suspend fun selectAgent(conn: ServerConnection, sessionId: String, agent: String): Result<Unit> =
        runCatching { api.selectAgent(conn, sessionId, agent) }

    suspend fun loadAgents(conn: ServerConnection): Result<List<String>> =
        runCatching { api.getAgents(conn) }

    suspend fun cycleVariant(conn: ServerConnection, sessionId: String): Result<Unit> =
        runCatching { api.cycleVariant(conn, sessionId) }

    suspend fun loadCommands(conn: ServerConnection): Result<List<String>> =
        runCatching { api.getCommands(conn) }

    suspend fun searchFilesForMention(conn: ServerConnection, query: String): Result<List<String>> =
        runCatching { api.searchFiles(conn, query) }
}
```

- [ ] **Step 8.0-E: ManageTerminalUseCase**

```kotlin
// domain/usecase/ManageTerminalUseCase.kt
package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.api.ServerConnection
import javax.inject.Inject

class ManageTerminalUseCase @Inject constructor(
    private val api: OpenCodeApi
) {
    suspend fun createTerminal(conn: ServerConnection, title: String, command: String, cwd: String): Result<String> =
        runCatching { api.createPty(conn, title, command, cwd) }

    suspend fun sendTerminalInput(conn: ServerConnection, ptyId: String, input: String): Result<Unit> =
        runCatching { api.sendPtyInput(conn, ptyId, input) }

    suspend fun resizeTerminal(conn: ServerConnection, ptyId: String, cols: Int, rows: Int): Result<Unit> =
        runCatching { api.resizePty(conn, ptyId, cols, rows) }

    suspend fun closeTerminal(conn: ServerConnection, ptyId: String): Result<Unit> =
        runCatching { api.closePty(conn, ptyId) }

    suspend fun executeCommand(conn: ServerConnection, sessionId: String, command: String): Result<Unit> =
        runCatching { api.executeCommand(conn, sessionId, command) }
}
```

- [ ] **Step 8.0-F: DraftUseCase**

```kotlin
// domain/usecase/DraftUseCase.kt
package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.data.repository.DraftRepository
import javax.inject.Inject

class DraftUseCase @Inject constructor(
    private val draftRepository: DraftRepository
) {
    fun getDraft(sessionId: String): String = draftRepository.getDraft(sessionId)
    suspend fun updateDraftText(sessionId: String, text: String) = draftRepository.saveDraft(sessionId, text)
    suspend fun clearDraft(sessionId: String) = draftRepository.clearDraft(sessionId)
}
```

- [ ] **Step 8.0-G: ShareExportUseCase**

```kotlin
// domain/usecase/ShareExportUseCase.kt
package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.api.ServerConnection
import javax.inject.Inject

class ShareExportUseCase @Inject constructor(
    private val api: OpenCodeApi
) {
    suspend fun shareSession(conn: ServerConnection, sessionId: String): Result<String> =
        runCatching { api.shareSession(conn, sessionId) }

    suspend fun unshareSession(conn: ServerConnection, sessionId: String): Result<Unit> =
        runCatching { api.unshareSession(conn, sessionId) }

    suspend fun exportSession(conn: ServerConnection, sessionId: String, format: String): Result<String> =
        runCatching { api.exportSession(conn, sessionId, format) }

    suspend fun compactSession(conn: ServerConnection, sessionId: String): Result<Unit> =
        runCatching { api.compactSession(conn, sessionId) }
}
```

- [ ] **Step 8.0-H: UndoRedoUseCase**

```kotlin
// domain/usecase/UndoRedoUseCase.kt
package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.api.ServerConnection
import javax.inject.Inject

class UndoRedoUseCase @Inject constructor(
    private val api: OpenCodeApi
) {
    suspend fun undoMessage(conn: ServerConnection, sessionId: String): Result<Unit> =
        runCatching { api.undoMessage(conn, sessionId) }

    suspend fun revertMessage(conn: ServerConnection, sessionId: String, messageId: String): Result<Unit> =
        runCatching { api.revertMessage(conn, sessionId, messageId) }

    suspend fun redoMessage(conn: ServerConnection, sessionId: String): Result<Unit> =
        runCatching { api.redoMessage(conn, sessionId) }
}
```

- [ ] **Step 8.0 验证: 编译确认所有桩文件**

Run: `.\gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

---

以下 9 个 UseCase 应已就绪（Phase 1 创建 `SendMessageUseCase` + Step 8.0 创建其余 8 个桩文件）。确认实际文件存在：

> **注意：** `SendMessageUseCase` 已在 Phase 1 中定义。Step 8.0 中创建的 8 个 UseCase 桩文件（ManageSessionUseCase、ManagePermissionUseCase、SelectModelUseCase、ManageAgentUseCase、ManageTerminalUseCase、DraftUseCase、ShareExportUseCase、UndoRedoUseCase）也委托给现有 data 层，完整实现和测试在 Phase 4 中补充。引用名称必须与 Phase 1 定义的 `SendMessageUseCase` 一致。

| UseCase | 职责 | 替换的 ViewModel 方法 |
|---------|------|---------------------|
| `SendMessageUseCase` | 发送消息 + 附件 | `sendMessage()`, `sendParts()` |
| `ManageSessionUseCase` | 会话 CRUD + 历史 | `loadMessages()`, `refreshSession()`, `loadOlderMessages()`, `createNewSession()`, `forkSession()`, `renameSession()` |
| `ManagePermissionUseCase` | 权限/问题回复 | `replyToPermission()`, `replyToQuestion()`, `rejectQuestion()` |
| `SelectModelUseCase` | 模型/Provider 选择 | `selectModel()`, `loadProviders()`, `applyProviderFilter()` |
| `ManageAgentUseCase` | Agent/Variant/Command | `selectAgent()`, `loadAgents()`, `cycleVariant()`, `loadCommands()`, `searchFilesForMention()` |
| `ManageTerminalUseCase` | 终端操作 | 所有 `terminal*` 方法 |
| `DraftUseCase` | 草稿持久化 | `updateDraftText()`, `addDraftAttachment()`, `removeDraftAttachment()`, `clearDraft()`, `saveDraft()` |
| `ShareExportUseCase` | 分享/导出 | `shareSession()`, `unshareSession()`, `exportSession()`, `compactSession()` |
| `UndoRedoUseCase` | 撤销/重做 | `undoMessage()`, `revertMessage()`, `redoMessage()` |

### Step 8.2: 逐步替换 ViewModel 构造函数

**当前构造函数:**
```kotlin
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val eventReducer: EventReducer,
    private val api: OpenCodeApi,
    private val draftRepository: DraftRepository,
    private val settingsRepository: SettingsRepository
)
```

**目标构造函数:**
```kotlin
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val eventReducer: EventReducer,       // Phase 3 会进一步替换
    private val sendMessageUseCase: SendMessageUseCase,
    private val manageSessionUseCase: ManageSessionUseCase,
    private val managePermissionUseCase: ManagePermissionUseCase,
    private val selectModelUseCase: SelectModelUseCase,
    private val manageAgentUseCase: ManageAgentUseCase,
    private val manageTerminalUseCase: ManageTerminalUseCase,
    private val draftUseCase: DraftUseCase,
    private val shareExportUseCase: ShareExportUseCase,
    private val undoRedoUseCase: UndoRedoUseCase,
    private val settingsRepository: SettingsRepository
)
```

**注意:** `eventReducer` 保留到 Phase 3 (基础设施层重构) 再替换。`api` (OpenCodeApi) 应在此 Task 中消除直接依赖。

### Step 8.3: 逐步替换方法（每次 1-2 个方法）

每个方法替换模式：
1. 找到 ViewModel 中直接调用 `api.xxx()` 的方法
2. 替换为 `xxxUseCase.xxx()`
3. 编译验证
4. Commit

**替换顺序（按依赖简单度排序）：**

1. **DraftUseCase** — 最简单，替换 `draftRepository` 直接调用
   - `updateDraftText`, `addDraftAttachment`, `removeDraftAttachment`, `clearDraft`, `saveDraft`
   
2. **ShareExportUseCase** — 独立功能
   - `shareSession`, `unshareSession`, `exportSession`, `compactSession`

3. **UndoRedoUseCase** — 独立功能
   - `undoMessage`, `revertMessage`, `redoMessage`

4. **ManagePermissionUseCase** — 独立功能
   - `replyToPermission`, `replyToQuestion`, `rejectQuestion`

5. **SelectModelUseCase** — 替换 `api` + 内部状态管理
   - `selectModel`, `loadProviders`, `applyProviderFilter`

6. **ManageAgentUseCase** — 替换 `api` + 内部状态管理
   - `selectAgent`, `loadAgents`, `cycleVariant`, `loadCommands`, `searchFilesForMention`

7. **SendMessageUseCase** — 核心功能
   - `sendMessage`, `sendParts`

8. **ManageSessionUseCase** — 核心功能
   - `loadMessages`, `refreshSession`, `loadOlderMessages`, `createNewSession`, `forkSession`, `renameSession`

9. **ManageTerminalUseCase** — 终端相关
   - 所有 `terminal*` / `runShellCommand` / `executeCommand` 方法

### Step 8.4: 更新 DI Module

确认 Hilt Module 绑定已更新（Phase 1 应已创建）：
- 每个 UseCase 的 `@Inject` 构造函数
- 如有 Module 需要更新 `@Binds` 或 `@Provides`

### Step 8.5: 每步编译验证

```bash
# 每替换 1-2 个 UseCase 后
./gradlew assembleDebug
```

### Step 8.6: Commit（可在全部替换完成后一次提交，或每批一次）

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt
git commit -m "refactor(chat): delegate ChatViewModel to UseCases

Replace direct OpenCodeApi/Repository calls with UseCase delegation.
ChatViewModel no longer directly depends on data layer concrete classes.

Phase 2 Task 8/9 — no behavior changes."
```

---

## Task 9: ChatScreen.kt 骨架化 + ChatRoute + 全量验证

**预计 ChatScreen.kt 最终行数:** 200-400 行

### Step 9.1: 审查 ChatScreen.kt 残余内容

经过 Task 1-7 的提取，ChatScreen.kt 应仅包含：

1. `ChatScreen(...)` 主 composable（行 921–3006）— 但已去除所有被提取的子函数
2. 可能残留的:
   - `ChatScreen` 内部的 `LazyColumn` 布局
   - 状态收集和 ViewModel 交互
   - `CompositionLocalProvider` 包裹层
   - `FileMentionTransformer` 类（如果未被 Task 6 提取）

**验证:** 确认所有子 composable 都已被提取到独立文件，ChatScreen.kt 只保留布局编排代码。

### Step 9.2: 创建 `ChatRoute.kt`

**路径:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatRoute.kt`

**目的:** 封装 Navigation Route 定义和参数传递

```kotlin
package dev.minios.ocremote.ui.screens.chat

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.NavType

object ChatRoute {
    const val ROUTE_PATTERN = "chat?serverUrl={serverUrl}&username={username}&password={password}&serverName={serverName}&serverId={serverId}&sessionId={sessionId}"

    fun createRoute(
        serverUrl: String,
        username: String,
        password: String,
        serverName: String,
        serverId: String,
        sessionId: String
    ): String { ... }

    fun register(
        navGraphBuilder: NavGraphBuilder,
        onNavigateBack: () -> Unit,
        onNavigateToSession: (String) -> Unit,
        onNavigateToChildSession: (String) -> Unit,
        // ... shared image handling
    ) { ... }
}
```

**注意:** 当前 `NavGraph.kt` 中已有 ChatScreen 的路由定义（行 493 附近）。ChatRoute 封装后需要更新 `NavGraph.kt` 中的引用。

### Step 9.3: 最终 ChatScreen.kt 结构审查

最终 `ChatScreen.kt` 应为：
```kotlin
package dev.minios.ocremote.ui.screens.chat

// imports from sub-packages
import dev.minios.ocremote.ui.screens.chat.util.*
import dev.minios.ocremote.ui.screens.chat.components.*
import dev.minios.ocremote.ui.screens.chat.input.*
import dev.minios.ocremote.ui.screens.chat.dialog.*
import dev.minios.ocremote.ui.screens.chat.terminal.*
import dev.minios.ocremote.ui.screens.chat.markdown.*
import dev.minios.ocremote.ui.screens.chat.tools.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    onNavigateBack: () -> Unit,
    onNavigateToSession: (sessionId: String) -> Unit = {},
    onNavigateToChildSession: (String) -> Unit = {},
    onOpenInWebView: () -> Unit = {},
    initialSharedImages: List<Uri> = emptyList(),
    onSharedImagesConsumed: () -> Unit = {},
    startInTerminalMode: Boolean = false,
    viewModel: ChatViewModel = hiltViewModel()
) {
    // 1. 状态收集 (~30行)
    // 2. Auto-scroll 逻辑 (~40行)
    // 3. Scaffold + TopBar (~60行)
    // 4. LazyColumn 消息列表 (~80行)
    //    - 调用 ChatMessageBubble, AssistantMessageCard
    // 5. Terminal overlay (~20行)
    //    - 调用 SessionTerminalInline
    // 6. ChatInputBar (~10行)
    // 7. Dialogs (~20行)
    //    - ModelPickerDialog, ImagePreviewDialog
    // 8. CompositionLocalProvider 包裹 (~30行)
    // Total: ~300行
}
```

### Step 9.4: 全量编译验证

```bash
./gradlew assembleDebug
```

### Step 9.5: 运行全部测试

```bash
./gradlew test
```

### Step 9.6: 全量 UI 验证 Checklist

**核心功能验证（参考 spec 第 9 节）：**
- [ ] 发送文本消息 → 消息显示正确
- [ ] 工具卡片展开/折叠正常
- [ ] 终端输出实时滚动
- [ ] 权限请求弹窗出现且回复生效
- [ ] 问题弹窗出现且回答生效
- [ ] Markdown 渲染（代码块/表格/列表）
- [ ] 图片附件预览
- [ ] Model Picker 切换
- [ ] 会话切换/创建/删除
- [ ] 服务器连接/断开
- [ ] 草稿恢复（跨导航）
- [ ] 撤销/重做消息
- [ ] 斜杠命令菜单
- [ ] @file 提及补全
- [ ] Agent 切换
- [ ] 多终端标签
- [ ] 分享/导出会话

### Step 9.7: 最终 Commit

```bash
git add .
git commit -m "refactor(chat): complete Phase 2 Chat module refactoring

ChatScreen.kt skeletonized to ~300 lines with all components extracted
to dedicated sub-packages: terminal/, util/, markdown/, tools/, dialog/,
input/, components/. ChatViewModel delegates to UseCases.

Phase 2 Task 9/9 — no behavior changes."
```

---

## 验证与回退策略

### 每个 Task 的验证流程

```
提取代码 → 编译验证 → (可选) UI 验证 → Commit
     ↓失败
   修复 import/可见性
     ↓仍失败
   git checkout -- . 回退到上一个 commit
```

### 编译中断策略（来自 spec）

> 采用"提取一个类→编译验证→提交"微循环，禁止提交编译失败的代码。

### ToolCardRegistry fallback（来自 spec）

> `resolve()` 返回 null 时渲染 UnknownToolCard（显示 tool name + 原始 JSON），确保不白屏。

---

## 目录结构完成对照表

完成后运行以下命令验证目录结构：

```bash
find app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat -name "*.kt" | sort
```

预期输出：
```
app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatParts.kt
app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatRoute.kt
app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt
app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt
app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/TerminalEmulator.kt
app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ServerTerminalWorkspace.kt
app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/AssistantMessageCard.kt
app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/AssistantTurnBubble.kt
app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/BreathingCircleIndicator.kt
app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/ChatMessageBubble.kt
app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/DiffChangesInline.kt
app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/ErrorPayloadContent.kt
app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/FileCard.kt
app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/PartContent.kt
app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/PulsingDotsIndicator.kt
app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/ReasoningBlock.kt
app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/RevertBanner.kt
app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/dialog/ImagePreviewDialog.kt
app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/dialog/ModelPickerDialog.kt
app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/dialog/PermissionRequestCard.kt
app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/dialog/QuestionCard.kt
app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/input/AttachmentPreview.kt
app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/input/ChatInputBar.kt
app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/input/SlashCommandMenu.kt
app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/markdown/MarkdownContent.kt
app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/markdown/MarkdownTable.kt
app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/terminal/SessionTerminalInline.kt
app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/terminal/TerminalKeyboardOverlay.kt
app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/terminal/TerminalKeys.kt
app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/tools/ToolCardRegistry.kt
app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/tools/ToolCardRenderer.kt
app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/tools/cards/BashToolCard.kt
app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/tools/cards/EditToolCard.kt
app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/tools/cards/PatchCard.kt
app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/tools/cards/ReadToolCard.kt
app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/tools/cards/SearchToolCard.kt
app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/tools/cards/TaskToolCard.kt
app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/tools/cards/TodoListCard.kt
app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/tools/cards/WriteToolCard.kt
app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/util/ChatColors.kt
app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/util/ChatCompositionLocals.kt
app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/util/ChatFormatters.kt
app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/util/ChatModifiers.kt
app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/util/MediaUtils.kt
```

---

## 风险与缓解

| 风险 | 缓解措施 |
|------|----------|
| 提取函数后 import 链断裂 | 每步编译验证，逐步修复 |
| `private` 可见性改为 `internal` 后暴露范围过大 | 限制为同 package `dev.minios.ocremote.ui.screens.chat.*` |
| CompositionLocal 引用跨 package | 在 `util/ChatCompositionLocals.kt` 中声明为 public val |
| ToolCardRegistry 初始化时机 | 在 `ChatScreen` 或 Application 中调用 `registerDefaults()` |
| `FnBindingResult` / `TerminalKey` / `SlashCommand` 等类型定义位置不确定 | 提取前先用 grep 确认定义位置，必要时在同一包内创建 typealias |
| `FileMentionTransformer`（行 556–638）的归属 | 优先放入 `input/ChatInputBar.kt`，因为它是输入处理的一部分 |
| Phase 1 UseCase 接口尚未完成 | Task 8 可推迟到 Phase 1 完成后执行；Task 1-7 不依赖 UseCase |
| `ImageAttachment` / `PreparedAttachment` / `ChatInputMode` 类型定义位置不确定 | 提取前确认位置，必要时保留在 ChatViewModel.kt 中或移到 domain model |
