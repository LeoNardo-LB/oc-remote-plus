# 快速定位（Quick Navigate）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 ChatTopBar 溢出菜单加「快速定位」项，点击后弹出 ModalBottomSheet 列出所有用户提问，点击跳转到对应消息。

**Architecture:** 纯 UI 层功能。新增 1 个纯函数 util（`JumpTargetExtractor`）+ 1 个 UI 组件（`QuickNavigateSheet`），改动 4 个现有文件。不动 ViewModel/Domain/Data。当前提问识别基于 `LazyListState.layoutInfo.visibleItemsInfo` + 消息 key 反查。

**Tech Stack:** Jetpack Compose (Material 3 `ModalBottomSheet`)、Kotlin、JUnit4 + MockK（单测）。

## Global Constraints

（来自 spec 第 6 节，每个 task 隐式遵守）

- **ChatScreen.kt 编辑协议**：串行编辑、先 Read 再 Edit、每次改完跑 `compileDevDebugKotlin`、编译失败先 `git checkout -- <file>` 再重试。详见 `docs/chatscreen-editing-protocol.md`。
- **JDK 21**，`jvmToolchain(21)`。
- **Material 3 First**：用原生 `ModalBottomSheet`/`HorizontalDivider`/`DropdownMenuItem`，不自定义 Canvas。
- **Theme Token System**：高亮背景用 `AlphaTokens.SELECTED`（0.12），间距用 `SpacingTokens`，不硬编码 alpha/spacing。
- **reverseLayout**：`ChatMessageList` 的 LazyColumn 用 `reverseLayout=true`，index 0 在底部。所有 index 推理必须考虑此点。
- **不破坏 SSE 滚动稳定性**：跳转用现成的 `LazyListReflection.requestScrollToItemNoCancel`（不取消进行中的滚动），不触碰 `scheduleFlush`/`layout{}` 补偿/`autoScrollEnabled`。
- **编译命令**：`.\gradlew :app:compileDevDebugKotlin`（超时 120s）；`.\gradlew :app:testDevDebugUnitTest --tests "*.XxxTest" --rerun`（超时 180s）。Windows 上 Gradle daemon 已禁用，卡住时跑 `.\gradlew --stop`。
- **代理警告**：`gradle.properties` 硬编码 `127.0.0.1:7897` 代理，无代理时注释掉 4 行 `systemProp.*`。

**Spec**: `docs/superpowers/specs/2026-07-06-quick-navigate-design.md`

---

## File Structure

| 文件 | 操作 | 职责 |
|------|------|------|
| `app/src/main/kotlin/.../chat/util/JumpTargetExtractor.kt` | 新增 | 纯函数：提取提问 + 识别当前提问 |
| `app/src/test/kotlin/.../chat/util/JumpTargetExtractorTest.kt` | 新增 | 纯函数单测 |
| `app/src/main/kotlin/.../chat/components/QuickNavigateSheet.kt` | 新增 | ModalBottomSheet UI 组件 |
| `app/src/main/kotlin/.../chat/components/ChatTopBar.kt` | 改 | 加菜单项 |
| `app/src/main/kotlin/.../chat/components/ChatMessageList.kt` | 改 | 集成 Sheet + 跳转逻辑 |
| `app/src/main/kotlin/.../chat/ChatScreen.kt` | 改 | 串联状态 |
| `app/src/main/res/values/strings.xml` + 14 个 locale | 改 | 新增字符串 |

---

### Task 1: JumpTargetExtractor 纯函数 + 单元测试（TDD）

**Files:**
- Create: `app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/util/JumpTargetExtractor.kt`
- Test: `app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/util/JumpTargetExtractorTest.kt`

**Interfaces:**
- Consumes: `ChatMessage`（`ChatViewModel.kt:197`，含 `isUser`/`message`/`parts`）、`Message.time.created`、`Part.Text`
- Produces: `JumpTarget`（data class）、`extractJumpTargets(List<ChatMessage>): List<JumpTarget>`、`findNearestUserIndexBefore(List<ChatMessage>, Int): Int?`、`findCurrentQuestionRawIndex(LazyListState, List<ChatMessage>): Int?`

- [ ] **Step 1: 写失败的测试**

创建 `app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/util/JumpTargetExtractorTest.kt`：

```kotlin
package dev.leonardo.ocremotev2.ui.screens.chat.util

import dev.leonardo.ocremotev2.domain.model.Message
import dev.leonardo.ocremotev2.domain.model.Part
import dev.leonardo.ocremotev2.domain.model.TimeInfo
import dev.leonardo.ocremotev2.ui.screens.chat.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JumpTargetExtractorTest {

    private fun userMsg(id: String, text: String, created: Long = 0L) = ChatMessage(
        message = Message.User(
            id = id,
            sessionId = "s1",
            role = "user",
            time = TimeInfo(created = created)
        ),
        parts = listOf(Part.Text(id = "p_$id", sessionId = "s1", messageId = id, text = text))
    )

    private fun assistantMsg(id: String, created: Long = 0L) = ChatMessage(
        message = Message.Assistant(
            id = id,
            sessionId = "s1",
            role = "assistant",
            time = TimeInfo(created = created),
            parentId = "parent"
        ),
        parts = listOf(Part.Text(id = "p_$id", sessionId = "s1", messageId = id, text = "response"))
    )

    @Test
    fun `extractJumpTargets returns only user messages with sequential Q labels`() {
        val msgs = listOf(
            userMsg("u1", "hello", 1000),
            assistantMsg("a1", 2000),
            userMsg("u2", "world", 3000),
            assistantMsg("a2", 4000)
        )
        val targets = extractJumpTargets(msgs)
        assertEquals(2, targets.size)
        assertEquals("Q1", targets[0].label)
        assertEquals("Q2", targets[1].label)
        assertEquals("hello", targets[0].preview)
        assertEquals("world", targets[1].preview)
        assertEquals(1000L, targets[0].timestampMs)
        assertEquals("u1", targets[0].msgId)
        assertEquals(0, targets[0].rawIndex)
        assertEquals(2, targets[1].rawIndex)
    }

    @Test
    fun `extractJumpTargets uses placeholder when text is blank`() {
        val msgs = listOf(userMsg("u1", "   ", 1000))
        val targets = extractJumpTargets(msgs)
        assertEquals(1, targets.size)
        assertEquals("(无文本)", targets[0].preview)
    }

    @Test
    fun `extractJumpTargets empty when no user messages`() {
        val msgs = listOf(assistantMsg("a1"), assistantMsg("a2"))
        assertEquals(emptyList<JumpTarget>(), extractJumpTargets(msgs))
    }

    @Test
    fun `findNearestUserIndexBefore returns self when input is user`() {
        val msgs = listOf(userMsg("u1", "q1"), assistantMsg("a1"), userMsg("u2", "q2"))
        assertEquals(0, findNearestUserIndexBefore(msgs, 0))
        assertEquals(2, findNearestUserIndexBefore(msgs, 2))
    }

    @Test
    fun `findNearestUserIndexBefore walks back to nearest user`() {
        val msgs = listOf(
            userMsg("u1", "q1"),      // 0
            assistantMsg("a1"),        // 1
            assistantMsg("a2"),        // 2
            userMsg("u2", "q2"),      // 3
            assistantMsg("a3")         // 4
        )
        assertEquals(0, findNearestUserIndexBefore(msgs, 1))
        assertEquals(0, findNearestUserIndexBefore(msgs, 2))
        assertEquals(3, findNearestUserIndexBefore(msgs, 3))
        assertEquals(3, findNearestUserIndexBefore(msgs, 4))
    }

    @Test
    fun `findNearestUserIndexBefore null when no user at or before`() {
        val msgs = listOf(assistantMsg("a1"), assistantMsg("a2"))
        assertNull(findNearestUserIndexBefore(msgs, 0))
        assertNull(findNearestUserIndexBefore(msgs, 1))
    }

    @Test
    fun `findNearestUserIndexBefore null for out of bounds`() {
        val msgs = listOf(userMsg("u1", "q1"))
        assertNull(findNearestUserIndexBefore(msgs, -1))
        assertNull(findNearestUserIndexBefore(msgs, 5))
    }
}
```

- [ ] **Step 2: 跑测试确认失败（函数未定义）**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "*.JumpTargetExtractorTest" --rerun`
Expected: 编译失败，`unresolved reference: extractJumpTargets`（函数还没创建）。

- [ ] **Step 3: 写实现**

创建 `app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/util/JumpTargetExtractor.kt`：

```kotlin
package dev.leonardo.ocremotev2.ui.screens.chat.util

import androidx.compose.foundation.lazy.LazyListState
import dev.leonardo.ocremotev2.domain.model.Part
import dev.leonardo.ocremotev2.ui.screens.chat.ChatMessage

/** A user question that can be jumped to from the Quick Navigate sheet. */
data class JumpTarget(
    val label: String,        // "Q1", "Q2" ...
    val timestampMs: Long,    // message.time.created (epoch millis)
    val preview: String,      // first Part.Text content, or placeholder
    val rawIndex: Int,        // index in rawMessages
    val msgId: String         // message.id, for jump lookup
)

/**
 * Extract all user questions from the raw message list, in order.
 * Pure function — no Android/Compose dependencies.
 */
fun extractJumpTargets(rawMessages: List<ChatMessage>): List<JumpTarget> {
    var q = 0
    return rawMessages.mapIndexedNotNull { i, cm ->
        if (!cm.isUser) null
        else {
            q++
            JumpTarget(
                label = "Q$q",
                timestampMs = cm.message.time.created,
                preview = cm.parts.firstOrNull { it is Part.Text }
                    ?.let { (it as Part.Text).text }
                    ?.takeIf { it.isNotBlank() }
                    ?: "(无文本)",
                rawIndex = i,
                msgId = cm.message.id
            )
        }
    }
}

/**
 * Given a rawIndex (any message), find the nearest user message at or before it.
 * Returns its rawIndex, or null if none exists. Pure function.
 */
fun findNearestUserIndexBefore(rawMessages: List<ChatMessage>, rawIdx: Int): Int? {
    if (rawIdx < 0 || rawIdx >= rawMessages.size) return null
    return (rawIdx downTo 0).firstOrNull { rawMessages[it].isUser }
}

/**
 * Identify which user question corresponds to the currently-visible top message.
 *
 * Uses listState.layoutInfo.visibleItemsInfo + message key format
 * ("u_<id>" for user, "t_<id>" for assistant — see ChatMessageList.kt:391-392).
 * reverseLayout=true: visually topmost visible message = smallest offset.
 *
 * Returns the rawIndex of the current user question, or null if indeterminate.
 */
fun findCurrentQuestionRawIndex(
    listState: LazyListState,
    rawMessages: List<ChatMessage>
): Int? {
    val visibleMsgs = listState.layoutInfo.visibleItemsInfo.filter { info ->
        (info.key as? String)?.let { it.startsWith("u_") || it.startsWith("t_") } == true
    }
    val topMsg = visibleMsgs.minByOrNull { it.offset } ?: return null
    val key = topMsg.key as String
    val msgId = key.removePrefix("u_").removePrefix("t_")
    val rawIdx = rawMessages.indexOfFirst { it.message.id == msgId }
    if (rawIdx < 0) return null
    return findNearestUserIndexBefore(rawMessages, rawIdx)
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "*.JumpTargetExtractorTest" --rerun`
Expected: 7 个测试全部 PASSED。

- [ ] **Step 5: 跑编译确认无其他破坏**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/util/JumpTargetExtractor.kt app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/util/JumpTargetExtractorTest.kt
git commit -m "feat(chat): add JumpTargetExtractor util + tests for quick navigate"
```

---

### Task 2: 字符串资源

**Files:**
- Modify: `app/src/main/res/values/strings.xml`（在 `chat_menu_open_workspace` 附近）
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`（中文）

**Interfaces:**
- Produces: `R.string.menu_quick_navigate`

- [ ] **Step 1: 在默认 values/strings.xml 加字符串**

在 `app/src/main/res/values/strings.xml` 的 `<string name="chat_menu_open_workspace">View Workspace</string>`（line 551）后面加一行：

```xml
    <string name="menu_quick_navigate">Quick navigate</string>
```

- [ ] **Step 2: 在中文 values-zh-rCN/strings.xml 加字符串**

```xml
    <string name="menu_quick_navigate">快速定位</string>
```

> 其余 13 个 locale 用 `lokit` 同步生成机翻初稿，或后续手工补齐。先保证默认 + 中文可用。

- [ ] **Step 3: 跑 lokit 同步（可选）**

Run: `lokit`（项目根目录）
Expected: 其余 locale 的 `menu_quick_navigate` 自动生成。

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/values*/strings.xml
git commit -m "i18n: add menu_quick_navigate string"
```

---

### Task 3: ChatTopBar 加菜单项

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/components/ChatTopBar.kt`

**Interfaces:**
- Consumes: `R.string.menu_quick_navigate`（Task 2）
- Produces: `ChatTopBar(..., onQuickNavigate: () -> Unit)` 新参数

- [ ] **Step 1: 加 import**

在 `ChatTopBar.kt` 顶部 import 区（line 8-21 的 `import androidx.compose.material.icons.filled.*` 之后）加：

```kotlin
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
```

- [ ] **Step 2: 加参数**

在 `ChatTopBar` 函数签名（line 50-71）的 `onOpenWorkspace: () -> Unit,`（line 69）之后加：

```kotlin
    onQuickNavigate: () -> Unit,
```

- [ ] **Step 3: 加菜单项（第 2 位）**

在 `DropdownMenu` 内（line 151-272），在「打开工作区」项（line 157-167）的 `)` 之后、「终端」项（line 168）之前，插入：

```kotlin
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_quick_navigate)) },
                            onClick = {
                                showMenu = false
                                onQuickNavigate()
                            },
                            leadingIcon = {
                                Icon(Icons.AutoMirrored.Filled.FormatListBulleted, contentDescription = null)
                            }
                        )
```

- [ ] **Step 4: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL。

> 注意：此时 `ChatScreen.kt` 调用 `ChatTopBar` 还没传 `onQuickNavigate`，会编译失败。**先不编译**，等 Task 6 改完 ChatScreen 再一起编译。或者临时在 ChatScreen line 552 的 ChatTopBar 调用里加 `onQuickNavigate = {}` 占位先通过编译，Task 6 再改真。推荐：本 task 只改 ChatTopBar，编译留到 Task 6。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/components/ChatTopBar.kt
git commit -m "feat(chat): add Quick Navigate menu item to ChatTopBar"
```

---

### Task 4: QuickNavigateSheet UI 组件

**Files:**
- Create: `app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/components/QuickNavigateSheet.kt`

**Interfaces:**
- Consumes: `JumpTarget`、`JumpTarget.label/timestampMs/preview/rawIndex/msgId`（Task 1）
- Produces: `QuickNavigateSheet(show, jumpTargets, currentRawIndex, onJump, onDismiss)` composable

- [ ] **Step 1: 创建组件文件**

创建 `app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/components/QuickNavigateSheet.kt`：

```kotlin
package dev.leonardo.ocremotev2.ui.screens.chat.components

import androidx.compose.foundation.border
import androidx.compose.foundation.drawBehind
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.leonardo.ocremotev2.ui.screens.chat.util.JumpTarget
import dev.leonardo.ocremotev2.ui.theme.AlphaTokens
import dev.leonardo.ocremotev2.ui.theme.ShapeTokens
import dev.leonardo.ocremotev2.ui.theme.SpacingTokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ModalBottomSheet listing all user questions for quick navigation.
 *
 * @param show whether the sheet is visible
 * @param jumpTargets extracted user questions (see JumpTargetExtractor)
 * @param currentRawIndex rawIndex of the currently-visible question, for highlight; null = none
 * @param onJump invoked with msgId when user taps a question
 * @param onDismiss invoked when sheet should close (dismiss tap, close button, after jump)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickNavigateSheet(
    show: Boolean,
    jumpTargets: List<JumpTarget>,
    currentRawIndex: Int?,
    onJump: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!show) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SpacingTokens.LG.dp, vertical = SpacingTokens.SM.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "快速定位",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "关闭")
            }
        }

        if (jumpTargets.isEmpty()) {
            Text(
                text = "暂无提问",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(SpacingTokens.XXL.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(SpacingTokens.XXL.dp))
            return@ModalBottomSheet
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                bottom = SpacingTokens.XXL.dp,
            ),
        ) {
            items(jumpTargets, key = { it.msgId }) { target ->
                JumpTargetRow(
                    target = target,
                    isCurrent = target.rawIndex == currentRawIndex,
                    onClick = { onJump(target.msgId) },
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT),
                )
            }
        }
    }
}

@Composable
private fun JumpTargetRow(
    target: JumpTarget,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val timeText = remember(target.timestampMs) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(target.timestampMs))
    }
    val highlightBg = if (isCurrent) {
        MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.SELECTED)
    } else {
        Color.Transparent
    }
    val accent = MaterialTheme.colorScheme.primary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(highlightBg)
            .then(
                if (isCurrent) Modifier.border(
                    width = 3.dp,
                    color = accent,
                    shape = ShapeTokens.small,
                ) else Modifier
            )
            .clip(ShapeTokens.small)
            .androidx.compose.foundation.clickable(onClick = onClick)
            .padding(horizontal = SpacingTokens.LG.dp, vertical = SpacingTokens.MD.dp),
    ) {
        // Left accent bar for current
        Spacer(
            modifier = Modifier
                .then(if (isCurrent) Modifier.width(0.dp) else Modifier.width(0.dp)),
        )
        Column(modifier = Modifier.fillMaxWidth()) {
            // Row 1: Q label + timestamp
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = target.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isCurrent) accent
                        else MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.HIGH),
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                )
                Spacer(Modifier.width(SpacingTokens.SM.dp))
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
                )
            }
            Spacer(Modifier.height(SpacingTokens.XS.dp))
            // Row 2: preview, horizontally scrollable (not truncated)
            Text(
                text = target.preview,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.HIGH),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
        }
    }
}
```

> 注意：上面 `JumpTargetRow` 里的 `Modifier` 链有一处 `.androidx.compose.foundation.clickable(...)` 是占位笔误——正确写法是先 `import androidx.compose.foundation.clickable`，再用 `Modifier.clickable(onClick = onClick)`。实现时修正：在文件顶部加 `import androidx.compose.foundation.clickable` 和 `import androidx.compose.foundation.background`，把那行改成 `.clickable(onClick = onClick)`。同时去掉无用的 `Spacer`（左 accent bar 改用 `drawBehind` 画竖条更干净）。修正后的 `JumpTargetRow` 见 Step 2。

- [ ] **Step 2: 修正 JumpTargetRow（最终版）**

在文件顶部 import 区补两个 import：

```kotlin
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
```

把 `JumpTargetRow` 整个替换为：

```kotlin
@Composable
private fun JumpTargetRow(
    target: JumpTarget,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val timeText = remember(target.timestampMs) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(target.timestampMs))
    }
    val highlightBg = if (isCurrent) {
        MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.SELECTED)
    } else {
        Color.Transparent
    }
    val accent = MaterialTheme.colorScheme.primary

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isCurrent) Modifier.drawBehind {
                    val w = 4.dp.toPx()
                    drawRect(
                        color = accent,
                        topLeft = androidx.compose.ui.geometry.Offset(0f, 0f),
                        size = androidx.compose.ui.geometry.Size(w, size.height),
                    )
                } else Modifier
            )
            .background(highlightBg)
            .clickable(onClick = onClick)
            .padding(
                start = if (isCurrent) SpacingTokens.MD.dp else SpacingTokens.LG.dp,
                end = SpacingTokens.LG.dp,
                top = SpacingTokens.MD.dp,
                bottom = SpacingTokens.MD.dp,
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = target.label,
                style = MaterialTheme.typography.labelMedium,
                color = if (isCurrent) accent
                    else MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.HIGH),
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            )
            Spacer(Modifier.width(SpacingTokens.SM.dp))
            Text(
                text = timeText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
            )
        }
        Spacer(Modifier.height(SpacingTokens.XS.dp))
        Text(
            text = target.preview,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.HIGH),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        )
    }
}
```

补 import（drawBehind 需要）：

```kotlin
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
```

- [ ] **Step 3: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL。

> 若报 `SpacingTokens`/`ShapeTokens`/`AlphaTokens` 未找到，确认 import 路径为 `dev.leonardo.ocremotev2.ui.theme.*`（这些 token 已存在，见 `ui/theme/Spacing.kt`、`Shape.kt`、`Alpha.kt`）。若 `SpacingTokens.XS` 不存在，用 `4.dp`；`ShapeTokens.small` 不存在则用 `MaterialTheme.shapes.small`。

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/components/QuickNavigateSheet.kt
git commit -m "feat(chat): add QuickNavigateSheet bottom sheet component"
```

---

### Task 5: ChatMessageList 集成（Sheet + 高亮 + 跳转）

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/components/ChatMessageList.kt`

**Interfaces:**
- Consumes: `QuickNavigateSheet`（Task 4）、`extractJumpTargets`/`findCurrentQuestionRawIndex`（Task 1）、`LazyListReflection.requestScrollToItemNoCancel`（已存在 line 643）
- Produces: `ChatMessageList(..., showQuickNavigate: Boolean, onQuickNavigateDismiss: () -> Unit)` 新参数

> ⚠️ 本 task 改 ChatMessageList.kt，遵守 ChatScreen 编辑协议：先 Read 再 Edit，改完编译，失败先 `git checkout`。

- [ ] **Step 1: Read 当前文件**

Read `ChatMessageList.kt` 全文（或 line 95-130 签名区 + line 270-385 banner 区 + line 532-553 FAB 区 + line 640-651 工具函数区），确认行号。

- [ ] **Step 2: 加 import**

在 `ChatMessageList.kt` 顶部 import 区加：

```kotlin
import androidx.compose.runtime.derivedStateOf
import dev.leonardo.ocremotev2.ui.screens.chat.util.JumpTarget
import dev.leonardo.ocremotev2.ui.screens.chat.util.extractJumpTargets
import dev.leonardo.ocremotev2.ui.screens.chat.util.findCurrentQuestionRawIndex
```

- [ ] **Step 3: 加函数参数**

在 `ChatMessageList` 签名（line 99-121）的 `agents: List<...> = emptyList(),`（line 119）之前加：

```kotlin
    showQuickNavigate: Boolean,
    onQuickNavigateDismiss: () -> Unit,
```

- [ ] **Step 4: 计算 jumpTargets + currentRawIndex + bannerCount**

在 `ChatMessageList` 函数体里，`val turnGroups = ...`（line 122）之后加：

```kotlin
    val jumpTargets = remember(rawMessages) { extractJumpTargets(rawMessages) }

    val currentQuestionRawIndex by remember {
        derivedStateOf { findCurrentQuestionRawIndex(listState, rawMessages) }
    }

    // Number of non-message items rendered before itemsIndexed in the LazyColumn.
    // Must mirror the conditional `item { ... }` blocks below (line 272-380).
    val bannerCount = remember(
        sessionMeta.revert,
        currentCompaction,
        sessionMeta.sessionStatus,
        activeTools,
        currentStep,
        interaction.pendingQuestions,
        interaction.pendingPermissions,
    ) {
        (if (sessionMeta.revert != null) 1 else 0) +
        (if (currentCompaction != null && currentCompaction.isActive) 1 else 0) +
        (if (sessionMeta.sessionStatus is SessionStatus.Retry) 1 else 0) +
        (if (activeTools.isNotEmpty()) 1 else 0) +
        (if (currentStep != null) 1 else 0) +
        (if (interaction.pendingQuestions.isNotEmpty()) 1 else 0) +
        (if (interaction.pendingPermissions.isNotEmpty()) 1 else 0)
    }
```

- [ ] **Step 5: 实现 jumpToMessage**

在 Step 4 的代码之后加跳转函数：

```kotlin
    fun jumpToMessage(msgId: String) {
        val displayItemIndex = displayItems.indexOfFirst { it.second.message.id == msgId }
        if (displayItemIndex < 0) return
        val lazyIndex = bannerCount + displayItemIndex
        coroutineScope.launch {
            LazyListReflection.requestScrollToItemNoCancel(listState, lazyIndex, 0)
        }
        onQuickNavigateDismiss()
    }
```

- [ ] **Step 6: 渲染 QuickNavigateSheet**

在 `ChatMessageList` 的 `Box(weight)` 内部（line 179 的 Box），在「Scroll-to-bottom FAB」（line 532-553）之后、「Always-allow confirmation dialog」（line 555）之前，插入：

```kotlin
            // Quick navigate bottom sheet
            QuickNavigateSheet(
                show = showQuickNavigate,
                jumpTargets = jumpTargets,
                currentRawIndex = currentQuestionRawIndex,
                onJump = { msgId -> jumpToMessage(msgId) },
                onDismiss = onQuickNavigateDismiss,
            )
```

- [ ] **Step 7: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL。

> 若失败：`git checkout -- app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/components/ChatMessageList.kt`，重新 Read，修正后重试。

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/components/ChatMessageList.kt
git commit -m "feat(chat): integrate QuickNavigateSheet into ChatMessageList"
```

---

### Task 6: ChatScreen 串联状态

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatScreen.kt`

**Interfaces:**
- Consumes: `ChatTopBar.onQuickNavigate`（Task 3）、`ChatMessageList.showQuickNavigate/onQuickNavigateDismiss`（Task 5）

- [ ] **Step 1: Read 当前文件相关段**

Read `ChatScreen.kt` line 540-600（ChatTopBar 调用区）、line 1025-1080（ChatMessageList 调用区）。

- [ ] **Step 2: 加状态变量**

在 `ChatScreen` 函数体内（`topBar = {` 之前，即 line 549 之前）加：

```kotlin
        var showQuickNavigate by remember { mutableStateOf(false) }
```

（确认 `mutableStateOf` 和 `remember`/`getValue`/`setValue` 已 import；ChatScreen 通常已 import 这些。）

- [ ] **Step 3: 给 ChatTopBar 传回调**

在 `ChatTopBar(...)` 调用（line 552 起）的参数里，在 `onOpenWorkspace = onOpenWorkspace,` 附近（或在任一已有 onXxx 参数后）加：

```kotlin
                        onQuickNavigate = { showQuickNavigate = true },
```

- [ ] **Step 4: 给 parent 分支的 ChatMessageList 传参**

在 `ChatMessageList(...)` 调用的 **parent 分支**（line 1029 起，`if (sessionMeta.sessionParentId == null)` 内），加：

```kotlin
                                showQuickNavigate = showQuickNavigate,
                                onQuickNavigateDismiss = { showQuickNavigate = false },
```

- [ ] **Step 5: 给 child 分支的 ChatMessageList 传占位**

child 分支（line 1054 起，`else` 内）不会显示菜单（菜单仅 parent），但函数签名要求传参。加：

```kotlin
                                showQuickNavigate = false,
                                onQuickNavigateDismiss = {},
```

- [ ] **Step 6: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL。

> 若失败：`git checkout -- app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatScreen.kt`，重新 Read，修正后重试。

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatScreen.kt
git commit -m "feat(chat): wire Quick Navigate state in ChatScreen"
```

---

### Task 7: 完整验证

**Files:** 无新增改动，仅验证。

- [ ] **Step 1: 完整 Kotlin 编译**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 2: 单元测试**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun`
Expected: BUILD SUCCESSFUL，JumpTargetExtractorTest 7 个用例 PASSED，其他既有测试不回归。

- [ ] **Step 3: 构建 debug APK（确认整体可用）**

Run: `.\gradlew :app:assembleDevDebug`
Expected: BUILD SUCCESSFUL。APK 在 `app/build/outputs/apk/dev/debug/app-dev-debug.apk`。

- [ ] **Step 4: 手动验证清单**

安装到设备/模拟器后，逐项验证（对照 spec 第 7 节）：

- [ ] parent session 顶栏 ⋮ 菜单出现「快速定位」项（第 2 位，打开工作区之后）
- [ ] 子会话（sessionParentId != null）菜单不出现「快速定位」
- [ ] 点击「快速定位」→ ModalBottomSheet 从底部弹起，半屏
- [ ] Sheet 列出所有用户提问，每项两行：Q$n + HH:mm / 提问原文
- [ ] 提问原文超长时可左右滑动
- [ ] 滚动聊天区，Sheet 中对应提问自动高亮（左竖条 + 淡背景）
- [ ] 点某项 → Sheet 关闭 + 平滑滚到对应消息
- [ ] 空会话点「快速定位」→ Sheet 显示「暂无提问」
- [ ] 流式输出中点跳转，SSE 滚动不抖动/不跳变
- [ ] 出现 revert banner / question / permission 时跳转目标位置正确

- [ ] **Step 5: 最终 commit（如有 fixup）**

```bash
# 仅当 Step 4 发现问题并修复后才 commit
git add -A
git commit -m "fix(chat): quick navigate manual-test fixes"
```

---

## Self-Review 结论

**Spec coverage**（对照 spec 各节）：
- §3 决策摘要：Task 2（文字）/ Task 3（图标+位置）/ Task 4（两行+横滑+高亮）/ Task 4（半屏 skipPartiallyExpanded=false）✅
- §4.1 入口菜单项：Task 3 ✅
- §4.2 BottomSheet 内容：Task 4 ✅
- §4.3 实现落点 + 数据流：Task 5 + Task 6 ✅
- §5.1 JumpTarget 结构：Task 1 ✅
- §5.2 extractJumpTargets：Task 1 ✅
- §5.3 findCurrentQuestionRawIndex：Task 1 ✅
- §5.4 跳转 + bannerCount：Task 5 Step 4-5 ✅
- §5.5 时间戳格式化：Task 4 JumpTargetRow ✅
- §6 关键约束：Global Constraints 段 ✅
- §7 验证标准：Task 7 ✅
- §8 边界情况（空/1个/纯附件/流式中/分页/子会话）：Task 1 测试 + Task 4 空状态 + Task 7 手动验证 ✅

**Placeholder scan**：Task 4 Step 1 主动标注了一处笔误并在 Step 2 给出修正版（非占位符，是显式修正指引）。其余步骤代码完整。

**Type consistency**：`JumpTarget.msgId: String`（Task 1）→ `onJump: (String) -> Unit`（Task 4）→ `jumpToMessage(msgId: String)`（Task 5）→ `displayItems.indexOfFirst { it.second.message.id == msgId }`（Task 5）类型链一致。`currentRawIndex: Int?`（Task 4 参数）← `findCurrentQuestionRawIndex: Int?`（Task 1）一致。

**已知风险**（实现时关注）：
1. `LazyListItemInfo.key` 在 Compose BOM 2026.05.01 必然可用（1.x 起就有）。
2. reverseLayout 下 `visibleItemsInfo.offset` 语义：最小 offset = 视觉最顶部。若实测发现 offset 语义相反，调整 `minByOrNull` 为 `maxByOrNull`。
3. `SpacingTokens.XS`/`ShapeTokens.small` 若项目里命名不同，按实际 token 文件调整（`ui/theme/Spacing.kt`、`Shape.kt`）。
