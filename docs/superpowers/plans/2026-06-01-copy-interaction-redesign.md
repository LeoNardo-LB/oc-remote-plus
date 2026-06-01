# 复制交互重新设计 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 改进 OC Remote 的复制交互——统计栏直接复制取代弹窗、工具标题行长按复制、工具输出内容长按文本选择。

**Architecture:** 在现有工具卡片组件内部独立添加长按复制能力。统计栏复制回调改为直接调用 clipboardManager + Snackbar。所有卡片标题行从 `.clickable` 迁移到 `.combinedClickable`。输出文本区域包裹 `SelectionContainer`。

**Tech Stack:** Kotlin + Jetpack Compose + Material 3, `ExperimentalFoundationApi.combinedClickable`, `SelectionContainer`, `ClipboardManager`, `Toast`

**Spec:** `docs/superpowers/specs/2026-06-01-copy-interaction-redesign.md`

**Project Root:** `D:\Develop\code\app\oc-remote`

---

## File Structure

| File | Action | Responsibility |
|------|--------|---------------|
| `ui/screens/chat/components/ChatMessageList.kt` | Modify | onCopyText 回调从弹窗改为直接复制 |
| `ui/screens/chat/tools/cards/BashToolCard.kt` | Modify | 标题行 combinedClickable + 复制按钮 Toast 反馈 |
| `ui/screens/chat/tools/cards/EditToolCard.kt` | Modify | 标题行 combinedClickable + 输出 SelectionContainer |
| `ui/screens/chat/tools/cards/WriteToolCard.kt` | Modify | 标题行 combinedClickable + 输出 SelectionContainer |
| `ui/screens/chat/tools/cards/ReadToolCard.kt` | Modify | 标题行 combinedClickable + 输出 SelectionContainer |
| `ui/screens/chat/tools/cards/SearchToolCard.kt` | Modify | 标题行 combinedClickable |
| `ui/screens/chat/tools/cards/TaskToolCard.kt` | Modify | 标题行 combinedClickable（条件分支） |
| `ui/screens/chat/tools/ToolCardRenderer.kt` | Modify | 标题行 combinedClickable + 输出 SelectionContainer |

---

### Task 1: 统计栏复制按钮 — 从弹窗改为直接复制

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/ChatMessageList.kt:185-192`

**Context:** 当前 `onCopyText` 调用 `onMarkdownPreviewText(text)` 弹出全屏 Dialog。改为直接复制到剪贴板 + Snackbar 反馈。这个回调同时需要在组件树中获取 `clipboardManager`、`coroutineScope`、`snackbarHostState`、`context`——这些在 `ChatMessageList.kt` 的调用点已经存在（用户消息的 `onCopyText` 在 L282-294 已使用相同模式）。

- [ ] **Step 1: 定位 onCopyText 回调**

读取 `ChatMessageList.kt` 第 185-192 行，确认当前代码：

```kotlin
onCopyText = if (isTurnLast) {
    {
        val messages = turnMessagesForMsg
        val text = messages.flatMap { m ->
            m.parts.filterIsInstance<Part.Text>().map { it.text }
        }.joinToString("\n\n")
        if (text.isNotBlank()) { onMarkdownPreviewText(text) }
    }
} else null
```

- [ ] **Step 2: 替换为直接复制逻辑**

将 `onMarkdownPreviewText(text)` 替换为 `clipboardManager.setText()` + Snackbar。参考同文件 L282-294 用户消息的 onCopyText 实现模式：

```kotlin
onCopyText = if (isTurnLast) {
    {
        val messages = turnMessagesForMsg
        val text = messages.flatMap { m ->
            m.parts.filterIsInstance<Part.Text>().map { it.text }
        }.joinToString("\n\n")
        if (text.isNotBlank()) {
            clipboardManager.setText(AnnotatedString(text))
            coroutineScope.launch {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.chat_copied_clipboard)
                )
            }
        }
    }
} else null
```

确认 `clipboardManager`、`coroutineScope`、`snackbarHostState`、`context` 变量在作用域内已定义。如缺少 import 需补充 `import androidx.compose.ui.text.AnnotatedString`。

- [ ] **Step 3: 构建，验证编译通过**

Run: `.\gradlew assembleBetaDebug 2>&1 | Select-Object -Last 5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```
feat(copy): replace copy dialog with direct clipboard copy in stats bar
```

---

### Task 2: BashToolCard — 标题行长按复制 + 复制按钮反馈修复

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/tools/cards/BashToolCard.kt`

**Context:** BashToolCard 是唯一已有复制按钮的工具卡片。需要：1) 标题行添加长按复制 $command；2) 已有复制按钮补充 Toast 反馈。

- [ ] **Step 1: 添加 import 语句**

在文件头部添加：

```kotlin
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.platform.LocalContext
```

- [ ] **Step 2: 添加 context 和 haptic 变量**

在 `val expanded = isExpanded` 之后添加：

```kotlin
val context = LocalContext.current
```

- [ ] **Step 3: 修改标题行 Row 的 clickable 为 combinedClickable**

将第 104-107 行的：

```kotlin
Row(
    modifier = Modifier
        .fillMaxWidth()
        .clickable { performHaptic(hapticView, hapticOn); onToggleExpand() },
```

替换为（注意添加 `@OptIn` 注解或文件级注解）：

```kotlin
Row(
    modifier = Modifier
        .fillMaxWidth()
        .combinedClickable(
            onClick = { performHaptic(hapticView, hapticOn); onToggleExpand() },
            onLongClick = {
                if (command.isNotBlank()) {
                    clipboardManager.setText(AnnotatedString("$ $command"))
                    Toast.makeText(context, context.getString(R.string.chat_copied_clipboard), Toast.LENGTH_SHORT).show()
                }
            }
        ),
```

注意：`onLongClick` 的 lambda 不需要 `performHaptic`，长按反馈由系统处理。

- [ ] **Step 4: 修复复制按钮的 Toast 反馈**

将第 138-141 行的：

```kotlin
IconButton(
    onClick = {
        clipboardManager.setText(AnnotatedString(displayText))
    },
```

替换为：

```kotlin
IconButton(
    onClick = {
        clipboardManager.setText(AnnotatedString(displayText))
        Toast.makeText(context, context.getString(R.string.chat_copied_clipboard), Toast.LENGTH_SHORT).show()
    },
```

- [ ] **Step 5: 构建，验证编译通过**

Run: `.\gradlew assembleBetaDebug 2>&1 | Select-Object -Last 5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```
feat(copy): add long-press copy to BashToolCard header + fix copy button feedback
```

---

### Task 3: EditToolCard — 标题行长按复制 + 输出 SelectionContainer

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/tools/cards/EditToolCard.kt`

- [ ] **Step 1: 添加 import 语句**

```kotlin
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
```

- [ ] **Step 2: 添加 clipboardManager 和 context 变量**

在 `val expanded = isExpanded` 之后添加：

```kotlin
val clipboardManager = LocalClipboardManager.current
val context = LocalContext.current
```

- [ ] **Step 3: 修改标题行 Row 的 clickable 为 combinedClickable**

将第 107-110 行的：

```kotlin
Row(
    modifier = Modifier
        .fillMaxWidth()
        .clickable { performHaptic(hapticView, hapticOn); onToggleExpand() },
```

替换为：

```kotlin
Row(
    modifier = Modifier
        .fillMaxWidth()
        .combinedClickable(
            onClick = { performHaptic(hapticView, hapticOn); onToggleExpand() },
            onLongClick = {
                val copyText = if (filePath.isNotBlank()) "Edit: $filePath" else "Edit"
                clipboardManager.setText(AnnotatedString(copyText))
                Toast.makeText(context, context.getString(R.string.chat_copied_clipboard), Toast.LENGTH_SHORT).show()
            }
        ),
```

- [ ] **Step 4: 在 DiffView 外包裹 SelectionContainer**

将第 192 行的：

```kotlin
DiffView(before = diffBefore, after = diffAfter)
```

替换为：

```kotlin
SelectionContainer {
    DiffView(before = diffBefore, after = diffAfter)
}
```

注意：仅包裹 `DiffView` 调用，不包裹 error 分支的 `ErrorPayloadContent`（错误内容已有自己的 SelectionContainer 或不需要）。

- [ ] **Step 5: 构建，验证编译通过**

Run: `.\gradlew assembleBetaDebug 2>&1 | Select-Object -Last 5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```
feat(copy): add long-press copy + SelectionContainer to EditToolCard
```

---

### Task 4: WriteToolCard — 标题行长按复制 + 输出 SelectionContainer

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/tools/cards/WriteToolCard.kt`

- [ ] **Step 1: 添加 import 语句**

```kotlin
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
```

- [ ] **Step 2: 添加 clipboardManager 和 context 变量**

在 `val expanded = isExpanded` 之后添加：

```kotlin
val clipboardManager = LocalClipboardManager.current
val context = LocalContext.current
```

- [ ] **Step 3: 修改标题行 Row 的 clickable 为 combinedClickable**

将第 83-86 行的：

```kotlin
Row(
    modifier = Modifier
        .fillMaxWidth()
        .clickable { performHaptic(hapticView, hapticOn); onToggleExpand() },
```

替换为：

```kotlin
Row(
    modifier = Modifier
        .fillMaxWidth()
        .combinedClickable(
            onClick = { performHaptic(hapticView, hapticOn); onToggleExpand() },
            onLongClick = {
                val copyText = if (filePath.isNotBlank()) "Write: $filePath" else "Write"
                clipboardManager.setText(AnnotatedString(copyText))
                Toast.makeText(context, context.getString(R.string.chat_copied_clipboard), Toast.LENGTH_SHORT).show()
            }
        ),
```

- [ ] **Step 4: 在 Text 外包裹 SelectionContainer**

将第 143 行的：

```kotlin
Text(
    text = content.take(5000),
```

以及对应的闭合括号之后，用 `SelectionContainer { ... }` 包裹：

```kotlin
SelectionContainer {
    Text(
        text = content.take(5000),
        style = CodeTypography.copy(fontSize = 12.sp, color = if (isAmoled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f) else MaterialTheme.colorScheme.onSecondaryContainer),
        modifier = Modifier
            .padding(4.dp)
            .codeHorizontalScroll()
    )
}
```

- [ ] **Step 5: 构建，验证编译通过**

Run: `.\gradlew assembleBetaDebug 2>&1 | Select-Object -Last 5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```
feat(copy): add long-press copy + SelectionContainer to WriteToolCard
```

---

### Task 5: ReadToolCard — 标题行长按复制 + 输出 SelectionContainer

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/tools/cards/ReadToolCard.kt`

- [ ] **Step 1: 添加 import 语句**

```kotlin
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
```

- [ ] **Step 2: 添加 clipboardManager 和 context 变量**

在 `val expanded = isExpanded` 之后添加：

```kotlin
val clipboardManager = LocalClipboardManager.current
val context = LocalContext.current
```

- [ ] **Step 3: 修改标题行 Row 的 clickable 为 combinedClickable**

将第 91-94 行的：

```kotlin
Row(
    modifier = Modifier
        .fillMaxWidth()
        .clickable { performHaptic(hapticView, hapticOn); onToggleExpand() },
```

替换为：

```kotlin
Row(
    modifier = Modifier
        .fillMaxWidth()
        .combinedClickable(
            onClick = { performHaptic(hapticView, hapticOn); onToggleExpand() },
            onLongClick = {
                val copyText = if (filePath.isNotBlank()) "Read: $filePath" else "Read"
                clipboardManager.setText(AnnotatedString(copyText))
                Toast.makeText(context, context.getString(R.string.chat_copied_clipboard), Toast.LENGTH_SHORT).show()
            }
        ),
```

- [ ] **Step 4: 在展开区域 Column 外包裹 SelectionContainer**

将第 147 行的 `Column(` 及其内容到第 187 行的闭合 `}` 用 `SelectionContainer { ... }` 包裹：

找到 AnimatedVisibility 内部的 `Column(modifier = Modifier.padding(top = 2.dp), ...)` 及其全部子元素，在 Column 外层添加 `SelectionContainer { ... }`：

```kotlin
SelectionContainer {
    Column(
        modifier = Modifier.padding(top = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // ... 保持原有 filePath Surface 和 args Surface 不变
    }
}
```

- [ ] **Step 5: 构建，验证编译通过**

Run: `.\gradlew assembleBetaDebug 2>&1 | Select-Object -Last 5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```
feat(copy): add long-press copy + SelectionContainer to ReadToolCard
```

---

### Task 6: SearchToolCard — 标题行长按复制

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/tools/cards/SearchToolCard.kt`

**Context:** SearchToolCard 输出区域使用 MarkdownContent（内部已有 SelectionContainer），不需要额外包裹。只需添加标题行长按。

- [ ] **Step 1: 添加 import 语句**

```kotlin
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
```

- [ ] **Step 2: 添加 clipboardManager 和 context 变量**

在 `val expanded = isExpanded` 之后添加：

```kotlin
val clipboardManager = LocalClipboardManager.current
val context = LocalContext.current
```

- [ ] **Step 3: 修改标题行 Row 的 clickable 为 combinedClickable**

将第 97-100 行的：

```kotlin
Row(
    modifier = Modifier
        .fillMaxWidth()
        .clickable { performHaptic(hapticView, hapticOn); onToggleExpand() },
```

替换为：

```kotlin
Row(
    modifier = Modifier
        .fillMaxWidth()
        .combinedClickable(
            onClick = { performHaptic(hapticView, hapticOn); onToggleExpand() },
            onLongClick = {
                clipboardManager.setText(AnnotatedString(title))
                Toast.makeText(context, context.getString(R.string.chat_copied_clipboard), Toast.LENGTH_SHORT).show()
            }
        ),
```

注意：`title` 变量在第 81 行已定义为 `if (patternShort != null) "$baseTitle · $patternShort" else baseTitle`。

- [ ] **Step 4: 构建，验证编译通过**

Run: `.\gradlew assembleBetaDebug 2>&1 | Select-Object -Last 5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```
feat(copy): add long-press copy to SearchToolCard header
```

---

### Task 7: TaskToolCard — 标题行长按复制（条件分支）

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/tools/cards/TaskToolCard.kt`

**Context:** TaskToolCard 的标题行有两种 onClick 行为（有 subSessionId 时跳转子会话，否则展开折叠）。需要在两个分支中都添加 onLongClick。输出区域使用 MarkdownContent（内部已有 SelectionContainer）。

- [ ] **Step 1: 添加 import 语句**

```kotlin
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
```

- [ ] **Step 2: 添加 clipboardManager 和 context 变量**

在 `val expanded = isExpanded` 之后添加：

```kotlin
val clipboardManager = LocalClipboardManager.current
val context = LocalContext.current
```

- [ ] **Step 3: 构建长按复制文本**

在 `val context = LocalContext.current` 之后添加：

```kotlin
val longPressCopyText = description
    ?: agentType?.let { "$it Agent" }
    ?: serverTitle?.takeIf { it.isNotBlank() }
    ?: stringResource(R.string.tool_sub_agent)
```

- [ ] **Step 4: 修改标题行 Row 的条件 clickable 为条件 combinedClickable**

将第 104-113 行的：

```kotlin
Row(
    modifier = Modifier
        .fillMaxWidth()
        .let { mod ->
            when {
                subSessionId != null && onViewSubSession != null ->
                    mod.clickable { performHaptic(hapticView, hapticOn); onViewSubSession(subSessionId) }
                else ->
                    mod.clickable { performHaptic(hapticView, hapticOn); onToggleExpand() }
            }
        },
```

替换为：

```kotlin
Row(
    modifier = Modifier
        .fillMaxWidth()
        .let { mod ->
            when {
                subSessionId != null && onViewSubSession != null ->
                    mod.combinedClickable(
                        onClick = { performHaptic(hapticView, hapticOn); onViewSubSession(subSessionId) },
                        onLongClick = {
                            clipboardManager.setText(AnnotatedString(longPressCopyText))
                            Toast.makeText(context, context.getString(R.string.chat_copied_clipboard), Toast.LENGTH_SHORT).show()
                        }
                    )
                else ->
                    mod.combinedClickable(
                        onClick = { performHaptic(hapticView, hapticOn); onToggleExpand() },
                        onLongClick = {
                            clipboardManager.setText(AnnotatedString(longPressCopyText))
                            Toast.makeText(context, context.getString(R.string.chat_copied_clipboard), Toast.LENGTH_SHORT).show()
                        }
                    )
            }
        },
```

注意两个分支的 onLongClick 逻辑完全相同，可以提取为局部变量减少重复：

```kotlin
val onLongPressCopy: () -> Unit = {
    clipboardManager.setText(AnnotatedString(longPressCopyText))
    Toast.makeText(context, context.getString(R.string.chat_copied_clipboard), Toast.LENGTH_SHORT).show()
}
```

然后在 `combinedClickable(onLongClick = onLongPressCopy)` 中引用。

- [ ] **Step 5: 构建，验证编译通过**

Run: `.\gradlew assembleBetaDebug 2>&1 | Select-Object -Last 5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```
feat(copy): add long-press copy to TaskToolCard header
```

---

### Task 8: ToolCallCard — 标题行长按复制 + 输出 SelectionContainer

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/tools/ToolCardRenderer.kt`

**Context:** ToolCallCard 是通用工具卡片，标题文本由 `resolveToolDisplay` 生成。输出区域包含 input 文本和 output/error 文本。

- [ ] **Step 1: 添加 import 语句**

```kotlin
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
```

- [ ] **Step 2: 添加 clipboardManager 和 context 变量**

在 `val expanded = isExpanded` 之后添加：

```kotlin
val clipboardManager = LocalClipboardManager.current
val context = LocalContext.current
```

- [ ] **Step 3: 构建长按复制文本**

在 `val context = LocalContext.current` 之后添加：

```kotlin
val longPressCopyText = if (toolDisplay.subtitle != null && toolDisplay.subtitle != toolDisplay.title) {
    "${toolDisplay.title} · ${toolDisplay.subtitle}"
} else {
    toolDisplay.title
}
```

- [ ] **Step 4: 修改标题行 Row 的 clickable 为 combinedClickable**

将第 89-92 行的：

```kotlin
Row(
    modifier = Modifier
        .fillMaxWidth()
        .clickable { performHaptic(hapticView, hapticOn); onToggleExpand() },
```

替换为：

```kotlin
Row(
    modifier = Modifier
        .fillMaxWidth()
        .combinedClickable(
            onClick = { performHaptic(hapticView, hapticOn); onToggleExpand() },
            onLongClick = {
                clipboardManager.setText(AnnotatedString(longPressCopyText))
                Toast.makeText(context, context.getString(R.string.chat_copied_clipboard), Toast.LENGTH_SHORT).show()
            }
        ),
```

- [ ] **Step 5: 在输出区域 Column 外包裹 SelectionContainer**

找到 AnimatedVisibility 内部（第 170-214 行）的输出区域 Column。将整个 Column 的内容用 `SelectionContainer { ... }` 包裹：

```kotlin
SelectionContainer {
    Column(
        modifier = Modifier.padding(top = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // input Surface ...（保持不变）
        // output Surface ...（保持不变）
    }
}
```

注意：`SelectionContainer` 放在 `Box` 内部、`Column` 外部。即替换结构为：

```kotlin
Box(...) {
    SelectionContainer {
        Column(...) {
            // input text Surface
            // output text Surface
        }
    }
}
```

- [ ] **Step 6: 构建，验证编译通过**

Run: `.\gradlew assembleBetaDebug 2>&1 | Select-Object -Last 5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```
feat(copy): add long-press copy + SelectionContainer to ToolCallCard
```

---

### Task 9: 清理 MarkdownPreviewDialog 引用

**Files:**
- Inspect: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/dialog/MarkdownPreviewDialog.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt` (如果需要)

**Context:** Task 1 已将统计栏的 onCopyText 从弹窗改为直接复制。需确认 MarkdownPreviewDialog 是否还有其他调用方。

- [ ] **Step 1: 搜索所有 MarkdownPreviewDialog 引用**

```bash
rg "MarkdownPreviewDialog" --include="*.kt" -n
```

预期结果：
- `MarkdownPreviewDialog.kt` — 自身定义
- `ChatScreen.kt` — import + `markdownPreviewText` 状态变量 + Dialog 调用

- [ ] **Step 2: 评估是否移除**

如果 ChatScreen.kt 中 `markdownPreviewText` 状态变量和 `MarkdownPreviewDialog` 调用不再有任何地方触发（Task 1 移除了唯一的调用源 `onMarkdownPreviewText`），则可以清理：

在 ChatScreen.kt 中：
1. 移除 `import ...MarkdownPreviewDialog`
2. 移除 `var markdownPreviewText by rememberSaveable { mutableStateOf("") }` 状态声明
3. 移除 `if (markdownPreviewText.isNotBlank()) { MarkdownPreviewDialog(...) }` 调用
4. 移除 `onMarkdownPreviewText = { markdownPreviewText = it }` 参数传递
5. 从 `ChatMessageList` 的参数列表中移除 `onMarkdownPreviewText: (String) -> Unit`

保留 `MarkdownPreviewDialog.kt` 文件本体备用。

- [ ] **Step 3: 构建，验证编译通过**

Run: `.\gradlew assembleBetaDebug 2>&1 | Select-Object -Last 5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```
refactor(copy): remove MarkdownPreviewDialog references after direct copy migration
```

---

### Task 10: 全量构建 + 手动验证清单

**Files:** 无新改动，验证所有 Task 的结果

- [ ] **Step 1: 全量构建**

Run: `.\gradlew assembleBetaDebug 2>&1 | Select-Object -Last 5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 手动验证清单**

安装 Beta Debug APK 到设备后，逐一验证：

**统计栏复制（Part 1）：**
- [ ] 点击助手消息统计栏右侧复制按钮 → 直接复制正文到剪贴板 → Snackbar 显示"已复制到剪贴板"
- [ ] 无 MarkdownPreviewDialog 弹窗出现
- [ ] 复制内容仅包含 Part.Text 文本，不包含工具调用结果

**标题行长按复制（Part 2）：**
- [ ] BashToolCard 标题行长按 → Toast 提示 → 粘贴得到 `$ command`
- [ ] BashToolCard 标题行短按 → 展开/折叠（行为不变）
- [ ] BashToolCard 复制按钮短按 → Toast 提示 → 粘贴得到完整输出
- [ ] EditToolCard 标题行长按 → Toast → 粘贴得到 `Edit: filePath`
- [ ] WriteToolCard 标题行长按 → Toast → 粘贴得到 `Write: filePath`
- [ ] ReadToolCard 标题行长按 → Toast → 粘贴得到 `Read: filePath`
- [ ] SearchToolCard 标题行长按 → Toast → 粘贴得到搜索标题
- [ ] TaskToolCard 标题行长按（有子会话）→ Toast → 粘贴得到 description
- [ ] TaskToolCard 标题行长按（无子会话）→ Toast → 粘贴得到描述
- [ ] TaskToolCard 标题行短按（有子会话）→ 跳转子会话（行为不变）
- [ ] ToolCallCard 标题行长按 → Toast → 粘贴得到标题

**输出内容长按选择（Part 3）：**
- [ ] BashToolCard 展开后长按输出文本 → 系统选择手柄出现（已有功能，回归）
- [ ] EditToolCard 展开后长按 DiffView → 系统选择手柄出现
- [ ] WriteToolCard 展开后长按代码内容 → 系统选择手柄出现
- [ ] ReadToolCard 展开后长按文件路径/参数 → 系统选择手柄出现
- [ ] SearchToolCard 展开后长按 Markdown 内容 → 选择正常（已有 SelectionContainer）
- [ ] TaskToolCard 展开后长按 Markdown 内容 → 选择正常（已有 SelectionContainer）
- [ ] ToolCallCard 展开后长按 input/output → 系统选择手柄出现

**竞态条件（RC）：**
- [ ] BashToolCard 复制按钮长按 → 触发的是父 Row 的 onLongClick（复制 $command），非按钮 onClick（可接受）
- [ ] 快速连续长按标题行 → Toast 可能堆叠（已知 P2，可接受）
- [ ] SelectionContainer + scrollable 组合 → 文本选择拖动可能触发滚动（已知 Compose 限制）
