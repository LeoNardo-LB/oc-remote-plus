# 消息操作栏与统计栏统一实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 删除 AssistantMessageCard 头部多余复制按钮，去除用户消息滑动撤回改为操作栏，新增用户消息统计栏（时间 + duration + Undo + Copy）。

**Architecture:** 在 ChatMessageBubble 中删除 SwipeToDismissBox，改为直接渲染气泡内容 + 底部统计栏 Row。统计栏包含时间、duration、Undo/Copy 操作按钮。在 AssistantMessageCard 中仅删除头部复制按钮。

**Tech Stack:** Kotlin, Jetpack Compose, Material3

---

### Task 1: 删除 AssistantMessageCard 头部复制按钮

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/AssistantMessageCard.kt:125-134`

- [ ] **Step 1: 删除头部复制按钮代码块**

在 `AssistantMessageCard.kt` 中删除 L125-134 的整个 `if (onCopyText != null) { Icon(...) }` 块。这是头部标题行中 15dp 的 ContentCopy 图标。注意：统计栏底部的 14dp 复制按钮（L228-241, L245-263）保持不变。

- [ ] **Step 2: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/AssistantMessageCard.kt
git commit -m "refactor: remove redundant copy button from AssistantMessageCard header"
```

---

### Task 2: 删除 ChatMessageBubble 的 SwipeToDismissBox 并新增统计栏

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/ChatMessageBubble.kt`
- Reference: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/util/ChatFormatters.kt` (formatDuration)

- [ ] **Step 1: 删除 SwipeToDismissBox 相关代码**

需要删除以下代码块：

1. **删除 L215 的 `if (onRevert != null) {`** 到 **L305 的 `}`**（整个 SwipeToDismissBox 分支）
2. **删除 L306-316 的 `else` 分支**（`pointerInput(Unit) {}` 隔离块）

删除后，需要在原来 `bubbleContent()` 之后、函数结束前，添加新的统计栏 + 保留的 AlertDialog。

- [ ] **Step 2: 添加 import**

在文件头部 import 区域添加：
```kotlin
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.ContentCopy
import dev.minios.ocremote.ui.screens.chat.util.formatDuration
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
```

- [ ] **Step 3: 添加统计栏和 AlertDialog**

#### 统计栏位置（关键！）

统计栏必须放在 `bubbleContent()` **内部的 Column 末尾**（即 Surface → Column 的最后一个子项），而不是 `bubbleContent()` 之后。原因：外层 Column 是 `fillMaxWidth()` + `Alignment.End`，bubbleContent() 内的 Surface 也是 `fillMaxWidth()`。如果统计栏放在外层 Column 中使用 `fillMaxWidth`，会通栏占满屏幕宽度。

#### 改动方式

1. 在 `bubbleContent()` lambda 内的 Column 末尾（L207 `}` 之前），添加统计栏 Row
2. `showRevertConfirmation` state 声明提到 `bubbleContent()` 之前（Composable scope 内）
3. AlertDialog 放在外层 Column 中（`bubbleContent()` 之后），因为它需要在气泡外弹出

#### 代码结构

```kotlin
// === 在 bubbleContent() 之前声明 ===
var showRevertConfirmation by remember { mutableStateOf(false) }

val bubbleContent: @Composable () -> Unit = {
    Surface(...) {
        val compact = LocalCompactMessages.current
        Column(...) {
            // ... 现有内容（QUEUED badge, parts, fallback text 等）...

            // === 新增：统计栏（Column 的最后一个子项）===
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (compact) 4.dp else 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧：时间
                val timeText = remember(chatMessage.message.time.created) {
                    SimpleDateFormat("HH:mm", Locale.getDefault())
                        .format(Date(chatMessage.message.time.created))
                }
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                )

                // 左侧：耗时
                val completed = chatMessage.message.time.completed
                if (completed != null) {
                    val durationMs = completed - chatMessage.message.time.created
                    if (durationMs > 0) {
                        Text(
                            text = formatDuration(durationMs),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                        )
                    }
                }

                // 弹性空白
                Spacer(modifier = Modifier.weight(1f))

                // Undo 按钮（仅主会话，onRevert != null 时显示）
                if (onRevert != null) {
                    Icon(
                        Icons.AutoMirrored.Filled.Undo,
                        contentDescription = stringResource(R.string.chat_revert),
                        modifier = Modifier
                            .size(14.dp)
                            .clickable {
                                performHaptic(hapticView, hapticOn)
                                showRevertConfirmation = true
                            },
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                    )
                }

                // Copy 按钮（最右侧）
                if (onCopyText != null) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.chat_copy),
                        modifier = Modifier
                            .size(14.dp)
                            .clickable {
                                performHaptic(hapticView, hapticOn)
                                onCopyText()
                            },
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                    )
                }
            }
        }
    }
}

// === 在 bubbleContent() 调用之后（外层 Column 中）===
// 只放 AlertDialog，不放统计栏

// 撤回确认对话框
if (showRevertConfirmation && onRevert != null) {
    AlertDialog(
        onDismissRequest = { showRevertConfirmation = false },
        title = { Text(stringResource(R.string.chat_revert)) },
        text = { Text(stringResource(R.string.chat_revert_confirm)) },
        confirmButton = {
            TextButton(onClick = {
                showRevertConfirmation = false
                onRevert()
            }) {
                Text(stringResource(R.string.chat_revert))
            }
        },
        dismissButton = {
            TextButton(onClick = { showRevertConfirmation = false }) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}
```

**关键注意事项：**
- `hapticView` 和 `hapticOn` 已在 L86-87 定义（`LocalView.current` 和 `LocalHapticFeedbackEnabled.current`），直接复用
- `performHaptic` 已在 L55 导入
- `chatMessage` 是函数参数
- `formatDuration` 来自 `ChatFormatters.kt`（`chat.util` 包），需要 import：`import dev.minios.ocremote.ui.screens.chat.util.formatDuration`
- `Icons.AutoMirrored.Filled.Undo` — 项目已引入 `material-icons-extended`（build.gradle.kts L116），且 `Icons.AutoMirrored` 已在 ChatMessageBubble.kt L289 使用
- `stringResource(R.string.chat_revert)` 和 `stringResource(R.string.chat_revert_confirm)` 已在原 SwipeToDismissBox 中使用，无需新增字符串资源
- `onRevert` 和 `onCopyText` 是现有函数参数（L64-65）
- 子会话传入 `onRevert=null`，Undo 按钮自动不显示
- `chatMessage.message.time.completed` 是 `Long?`（可空），`null` 时 duration 不显示
- AlertDialog 中 `onRevert()` 直接调用（此时在 `onRevert != null` 条件内，非空断言安全）

- [ ] **Step 4: 清理无用 import**

删除不再需要的 import：
- `SwipeToDismissBox` 相关的 import（如果不再被其他代码使用）
- `DismissValue` / `DismissDirection` 相关 import
- `rememberSwipeToDismissBoxState` import

- [ ] **Step 5: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 运行测试**

Run: `.\gradlew :app:testDevDebugUnitTest`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/ChatMessageBubble.kt
git commit -m "feat: replace SwipeToDismiss with footer stats bar on user messages"
```

---

### Task 3: 编译完整验证 + APK 构建 + 发版

**Files:** 无改动

- [ ] **Step 1: 完整编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 运行测试**

Run: `.\gradlew :app:testDevDebugUnitTest`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 构建 Beta APK**

Run: `.\gradlew :app:assembleBetaRelease`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 推送 + 打 tag + 发版**

```bash
git push fork master
git tag v2.0.0-beta.72
git push fork v2.0.0-beta.72
gh release create v2.0.0-beta.72 --repo LeoNardo-LB/oc-remote-v2 \
  --title "v2.0.0-beta.72 Message Actions Footer" \
  --notes "## 修复内容

- 删除 AssistantMessageCard 头部多余复制按钮
- 去除用户消息滑动撤回，改为统计栏操作按钮（Undo + Copy）
- 新增用户消息统计栏：时间 + 耗时 + Undo + Copy
- 统计栏风格与 Assistant 统计栏统一" \
  app\build\outputs\apk\beta\release\app-beta-release.apk
```

Expected: Release URL returned
