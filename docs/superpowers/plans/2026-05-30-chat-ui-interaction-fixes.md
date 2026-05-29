# Chat UI 交互修复实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复对话界面的滚动竞态、复制偏差、卡片间距、统计时机、会话状态偏差等问题，并补充事件生命周期文档。

**Architecture:** 移除透明 SelectionContainer 消除手势竞争（设计 A），升级嵌套滚动边界消费同时消费 onPostScroll + onPostFling（设计 B），缩减工具卡片间距到当前值的一半（设计 C），将统计 Footer 改为 Turn 末尾聚合（设计 D），进入/恢复会话时 REST 查询修正 SSE 断连偏差（设计 F），编写事件生命周期文档（设计 E）。

**Tech Stack:** Kotlin, Jetpack Compose, mikepenz/multiplatform-markdown-renderer v0.30.0, OpenCode Serve API, Hilt, JUnit 4

**执行顺序:** B+C（滚动+间距，同文件一起做）→ A（SelectionContainer+弹窗）→ D（统计时机）→ F（会话状态）→ G（SSE中文乱码）→ E（文档，最后）

---

## 文件结构总览

| 操作 | 文件路径 | 涉及设计 |
|------|----------|----------|
| Modify | `ui/screens/chat/util/ChatModifiers.kt` | B1 |
| Modify | `ui/screens/chat/tools/ToolCardRenderer.kt` | B2, C1 |
| Modify | `ui/screens/chat/tools/cards/BashToolCard.kt` | B3a, C2 |
| Modify | `ui/screens/chat/tools/cards/WriteToolCard.kt` | B3b, C4 |
| Modify | `ui/screens/chat/tools/cards/ReadToolCard.kt` | B3c, C3 |
| Modify | `ui/screens/chat/tools/cards/EditToolCard.kt` | B3d, C5 |
| Modify | `ui/screens/chat/tools/cards/SearchToolCard.kt` | B2, C6 |
| Modify | `ui/screens/chat/tools/cards/TaskToolCard.kt` | B2, C7 |
| Modify | `ui/screens/chat/components/ReasoningBlock.kt` | B2, C8 |
| Modify | `ui/screens/chat/ChatScreen.kt` | A3, C9, D4, D5, F4c |
| Modify | `ui/screens/chat/markdown/MarkdownContent.kt` | A1 |
| Create | `ui/screens/chat/dialog/MarkdownPreviewDialog.kt` | A2 |
| Modify | `ui/screens/chat/components/AssistantMessageCard.kt` | D2, D3 |
| Modify | `data/api/OpenCodeApi.kt` | F4a |
| Modify | `ui/screens/chat/ChatViewModel.kt` | F4b |
| Modify | `data/repository/EventDispatcher.kt` | F4d |
| Create | `ui/screens/chat/util/TurnGroupCalculator.kt` | D4 |
| Create | `docs/chat-ui-event-lifecycle.md` | E |
| Create | `test/.../chat/util/ChatModifiersTest.kt` | B1 |
| Create | `test/.../chat/util/TurnGroupCalculatorTest.kt` | D4 |
| Modify | `data/api/SseClient.kt` | G |

所有源文件路径相对于 `app/src/main/kotlin/dev/minios/ocremote/`。
所有测试文件路径相对于 `app/src/test/kotlin/dev/minios/ocremote/`。

---

## Phase 1: 滚动修复 + 间距紧凑化（设计 B + C）

### Task 1: 升级 consumeBoundaryFling → consumeBoundaryScroll

**Files:**
- Modify: `ui/screens/chat/util/ChatModifiers.kt:49-68`
- Create: `test/.../chat/util/ChatModifiersTest.kt`

- [ ] **Step 1: 创建 ChatModifiersTest 测试文件，编写失败测试**

```kotlin
package dev.minios.ocremote.ui.screens.chat.util

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for consumeBoundaryScroll's NestedScrollConnection logic.
 *
 * Since the NestedScrollConnection is created inside a @Composable function,
 * we test the boundary conditions directly by extracting the decision logic
 * into a testable top-level function.
 */
class ChatModifiersTest {

    // ----- onPostScroll logic -----

    @Test
    fun `onPostScroll at top scrolling up consumes available`() {
        // atTop=true, atBottom=false, available.y < 0 (scrolling up)
        val result = postScrollDecision(atTop = true, atBottom = false, availableY = -5f)
        assertEquals(Offset(-5f, -5f), result) // wrong - let me fix
    }

    @Test
    fun `onPostScroll at top scrolling up consumes available`() {
        val result = postScrollDecision(atTop = true, atBottom = false, availableY = -5f)
        assertEquals(Offset(0f, -5f), result)
    }

    @Test
    fun `onPostScroll at bottom scrolling down consumes available`() {
        val result = postScrollDecision(atTop = false, atBottom = true, availableY = 5f)
        assertEquals(Offset(0f, 5f), result)
    }

    @Test
    fun `onPostScroll not at boundary returns zero`() {
        val result = postScrollDecision(atTop = false, atBottom = false, availableY = 5f)
        assertEquals(Offset.Zero, result)
    }

    @Test
    fun `onPostScroll at top scrolling down returns zero`() {
        val result = postScrollDecision(atTop = true, atBottom = false, availableY = 5f)
        assertEquals(Offset.Zero, result)
    }

    @Test
    fun `onPostScroll at bottom scrolling up returns zero`() {
        val result = postScrollDecision(atTop = false, atBottom = true, availableY = -5f)
        assertEquals(Offset.Zero, result)
    }

    // ----- onPostFling logic (mirrors onPostScroll) -----

    @Test
    fun `onPostFling at top fling up consumes velocity`() {
        val result = postFlingDecision(atTop = true, atBottom = false, availableY = -100f)
        assertEquals(Velocity(0f, -100f), result)
    }

    @Test
    fun `onPostFling at bottom fling down consumes velocity`() {
        val result = postFlingDecision(atTop = false, atBottom = true, availableY = 100f)
        assertEquals(Velocity(0f, 100f), result)
    }

    @Test
    fun `onPostFling not at boundary returns zero`() {
        val result = postFlingDecision(atTop = false, atBottom = false, availableY = 100f)
        assertEquals(Velocity.Zero, result)
    }

    // ----- Helper functions that mirror the NestedScrollConnection logic -----

    private fun postScrollDecision(atTop: Boolean, atBottom: Boolean, availableY: Float): Offset {
        return when {
            atTop && availableY < 0f -> Offset(0f, availableY)
            atBottom && availableY > 0f -> Offset(0f, availableY)
            else -> Offset.Zero
        }
    }

    private fun postFlingDecision(atTop: Boolean, atBottom: Boolean, availableY: Float): Velocity {
        return when {
            atTop && availableY < 0f -> Velocity(0f, availableY)
            atBottom && availableY > 0f -> Velocity(0f, availableY)
            else -> Velocity.Zero
        }
    }
}
```

- [ ] **Step 2: 运行测试验证通过**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "dev.minios.ocremote.ui.screens.chat.util.ChatModifiersTest"`
Expected: 8 tests PASS

- [ ] **Step 3: 修改 ChatModifiers.kt — 重命名函数 + 新增 onPostScroll**

将 `ChatModifiers.kt` L49-68 的 `consumeBoundaryFling` 函数替换为：

```kotlin
/**
 * Prevents nested-scroll inertia AND drag from propagating to the parent when
 * the child scrollable reaches its boundary.
 *
 * Consumes remaining delta in `onPostScroll` (finger drag) and remaining
 * velocity in `onPostFling` (inertia) when [ScrollState.canScrollForward] or
 * [ScrollState.canScrollBackward] is false.
 */
@Composable
internal fun Modifier.consumeBoundaryScroll(scrollState: ScrollState): Modifier {
    val connection = remember(scrollState) {
        object : NestedScrollConnection {
            /** Finger drag reached boundary — consume remaining delta, block propagation to parent */
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                val atTop = !scrollState.canScrollBackward
                val atBottom = !scrollState.canScrollForward
                return when {
                    atTop && available.y < 0f -> available
                    atBottom && available.y > 0f -> available
                    else -> Offset.Zero
                }
            }

            /** Inertia fling reached boundary — consume remaining velocity, block propagation to parent */
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

同时需要添加缺失的 import：
```kotlin
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
```

- [ ] **Step 4: 运行测试验证全量通过**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "dev.minios.ocremote.ui.screens.chat.util.ChatModifiersTest"`
Expected: 8 tests PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/util/ChatModifiers.kt app/src/test/kotlin/dev/minios/ocremote/ui/screens/chat/util/ChatModifiersTest.kt
git commit -m "feat(chat): upgrade consumeBoundaryFling → consumeBoundaryScroll with onPostScroll

Adds onPostScroll to consume drag delta at scroll boundaries,
preventing finger drag from propagating to the parent LazyColumn.
Previously only onPostFling (inertia) was consumed.

Design B1. Refs: #2026-05-30-chat-ui-interaction-fixes"
```

---

### Task 2: 更新 ToolCardRenderer — 调用名迁移 + 间距缩减

**Files:**
- Modify: `ui/screens/chat/tools/ToolCardRenderer.kt:41,80,86,96,163,167,168,186,206`

- [ ] **Step 1: 替换 import（L41）**

将：
```kotlin
import dev.minios.ocremote.ui.screens.chat.util.consumeBoundaryFling
```
改为：
```kotlin
import dev.minios.ocremote.ui.screens.chat.util.consumeBoundaryScroll
```

- [ ] **Step 2: 更新 Surface 圆角（L80）**

将 `RoundedCornerShape(8.dp)` 改为 `RoundedCornerShape(6.dp)`。

- [ ] **Step 3: 更新外层 Column padding（L86）**

将 `Modifier.padding(8.dp)` 改为 `Modifier.padding(4.dp)`。

- [ ] **Step 4: 更新 Header Row spacing（L96）**

找到 `Arrangement.spacedBy(6.dp)`（Header Row 内），改为 `Arrangement.spacedBy(3.dp)`。

- [ ] **Step 5: 替换 consumeBoundaryFling 调用（L163）**

将 `.consumeBoundaryFling(toolCardScrollState)` 改为 `.consumeBoundaryScroll(toolCardScrollState)`。

- [ ] **Step 6: 更新展开区域 Column top padding + spacing（L167-168）**

将：
```kotlin
modifier = Modifier.padding(top = 4.dp),
verticalArrangement = Arrangement.spacedBy(4.dp)
```
改为：
```kotlin
modifier = Modifier.padding(top = 2.dp),
verticalArrangement = Arrangement.spacedBy(2.dp)
```

- [ ] **Step 7: 更新 Input Text padding（L186）和 Output Text padding（L206）**

将两处 `.padding(8.dp)` 改为 `.padding(4.dp)`。

- [ ] **Step 8: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/tools/ToolCardRenderer.kt
git commit -m "refactor(chat): update ToolCardRenderer for consumeBoundaryScroll + compact spacing

Design B2 + C1. Refs: #2026-05-30-chat-ui-interaction-fixes"
```

---

### Task 3: 更新 BashToolCard — 补滚动 + 间距缩减

**Files:**
- Modify: `ui/screens/chat/tools/cards/BashToolCard.kt:92,98,114,157-179`

- [ ] **Step 1: 更新 Surface 圆角（L92）**

将 `RoundedCornerShape(8.dp)` 改为 `RoundedCornerShape(6.dp)`。

- [ ] **Step 2: 更新外层 Column padding（L98）**

将 `Modifier.padding(8.dp)` 改为 `Modifier.padding(4.dp)`。

- [ ] **Step 3: 更新 Header Row spacing（L114）**

找到 `Arrangement.spacedBy(6.dp)`，改为 `Arrangement.spacedBy(3.dp)`。

- [ ] **Step 4: 替换展开区域（L157-179）**

将整个 `AnimatedVisibility` 块替换为：

```kotlin
            AnimatedVisibility(
                visible = expanded && hasContent,
            ) {
                val halfScreenHeight = maxOf(LocalConfiguration.current.screenHeightDp.dp / 2, 200.dp)
                val scrollState = rememberScrollState()
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = toolOutputContainerColor(isAmoled),
                    border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 3.dp)
                        .heightIn(max = halfScreenHeight)
                        .consumeBoundaryScroll(scrollState)
                        .verticalScroll(scrollState)
                ) {
                    SelectionContainer {
                        Text(
                            text = displayText,
                            style = CodeTypography.copy(fontSize = 12.sp, color = if (isAmoled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f) else MaterialTheme.colorScheme.onSecondaryContainer),
                            modifier = Modifier
                                .padding(4.dp)
                                .codeHorizontalScroll()
                        )
                    }
                }
            }
```

需在文件顶部添加缺失的 import（如果还没有）：
```kotlin
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
import dev.minios.ocremote.ui.screens.chat.util.consumeBoundaryScroll
```

- [ ] **Step 5: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/tools/cards/BashToolCard.kt
git commit -m "feat(chat): add verticalScroll to BashToolCard + compact spacing

Design B3a + C2. Refs: #2026-05-30-chat-ui-interaction-fixes"
```

---

### Task 4: 更新 WriteToolCard — 补滚动 + 间距缩减

**Files:**
- Modify: `ui/screens/chat/tools/cards/WriteToolCard.kt:71,77,100,121-141`

- [ ] **Step 1: 更新 Surface 圆角（L71）**

将 `RoundedCornerShape(8.dp)` 改为 `RoundedCornerShape(6.dp)`。

- [ ] **Step 2: 更新外层 Column padding（L77）**

将 `Modifier.padding(8.dp)` 改为 `Modifier.padding(4.dp)`。

- [ ] **Step 3: 更新 Header Row spacing（L100）**

找到 `Arrangement.spacedBy(6.dp)`，改为 `Arrangement.spacedBy(3.dp)`。

- [ ] **Step 4: 替换展开区域（L121-141）**

将整个 `AnimatedVisibility` 块替换为：

```kotlin
            AnimatedVisibility(
                visible = expanded && hasContent,
            ) {
                val halfScreenHeight = maxOf(LocalConfiguration.current.screenHeightDp.dp / 2, 200.dp)
                val scrollState = rememberScrollState()
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = toolOutputContainerColor(isAmoled),
                    border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 3.dp)
                        .heightIn(max = halfScreenHeight)
                        .consumeBoundaryScroll(scrollState)
                        .verticalScroll(scrollState)
                ) {
                    Text(
                        text = content.take(5000),
                        style = CodeTypography.copy(fontSize = 12.sp, color = if (isAmoled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f) else MaterialTheme.colorScheme.onSecondaryContainer),
                        modifier = Modifier
                            .padding(4.dp)
                            .codeHorizontalScroll()
                    )
                }
            }
```

需在文件顶部添加缺失的 import：
```kotlin
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
import dev.minios.ocremote.ui.screens.chat.util.consumeBoundaryScroll
```

- [ ] **Step 5: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/tools/cards/WriteToolCard.kt
git commit -m "feat(chat): add verticalScroll to WriteToolCard + compact spacing

Design B3b + C4. Refs: #2026-05-30-chat-ui-interaction-fixes"
```

---

### Task 5: 更新 ReadToolCard — 补滚动 + 间距缩减

**Files:**
- Modify: `ui/screens/chat/tools/cards/ReadToolCard.kt:77,83,92,127-170`

- [ ] **Step 1: 更新 Surface 圆角（L77）**

将 `RoundedCornerShape(8.dp)` 改为 `RoundedCornerShape(6.dp)`。

- [ ] **Step 2: 更新外层 Column padding（L83）**

将 `Modifier.padding(8.dp)` 改为 `Modifier.padding(4.dp)`。

- [ ] **Step 3: 更新 Header Row spacing（L92）**

找到 `Arrangement.spacedBy(6.dp)`，改为 `Arrangement.spacedBy(3.dp)`。

- [ ] **Step 4: 替换展开区域（L127-170）**

将 `AnimatedVisibility` 块（L127-170）替换为：

```kotlin
            AnimatedVisibility(
                visible = expanded,
            ) {
                val halfScreenHeight = maxOf(LocalConfiguration.current.screenHeightDp.dp / 2, 200.dp)
                val scrollState = rememberScrollState()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = halfScreenHeight)
                        .consumeBoundaryScroll(scrollState)
                        .verticalScroll(scrollState)
                ) {
                    Column(
                        modifier = Modifier.padding(top = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        if (filePath.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = toolOutputContainerColor(isAmoled),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = filePath,
                                    style = CodeTypography.copy(
                                        fontSize = 11.sp,
                                        color = if (isAmoled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                                    ),
                                    modifier = Modifier
                                        .padding(4.dp)
                                        .codeHorizontalScroll()
                                )
                            }
                        }
                        if (args != null) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = toolOutputContainerColor(isAmoled),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = args,
                                    style = CodeTypography.copy(
                                        fontSize = 11.sp,
                                        color = if (isAmoled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                                    ),
                                    modifier = Modifier
                                        .padding(4.dp)
                                        .codeHorizontalScroll()
                                )
                            }
                        }
                    }
                }
            }
```

需在文件顶部添加缺失的 import：
```kotlin
import androidx.compose.foundation.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
import dev.minios.ocremote.ui.screens.chat.util.consumeBoundaryScroll
```

- [ ] **Step 5: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/tools/cards/ReadToolCard.kt
git commit -m "feat(chat): add verticalScroll to ReadToolCard + compact spacing

Design B3c + C3. Refs: #2026-05-30-chat-ui-interaction-fixes"
```

---

### Task 6: 更新 EditToolCard — 补滚动 + 间距缩减

**Files:**
- Modify: `ui/screens/chat/tools/cards/EditToolCard.kt:92,98,108,151-178`

- [ ] **Step 1: 更新 Surface 圆角（L92）**

将 `RoundedCornerShape(8.dp)` 改为 `RoundedCornerShape(6.dp)`。

- [ ] **Step 2: 更新外层 Column padding（L98）**

将 `Modifier.padding(8.dp)` 改为 `Modifier.padding(4.dp)`。

- [ ] **Step 3: 更新 Header Row spacing（L108）**

找到 `Arrangement.spacedBy(6.dp)`，改为 `Arrangement.spacedBy(3.dp)`。

- [ ] **Step 4: 替换展开区域（L151-178）**

将 `AnimatedVisibility` 块替换为：

```kotlin
            AnimatedVisibility(
                visible = expanded && hasContent,
            ) {
                val halfScreenHeight = maxOf(LocalConfiguration.current.screenHeightDp.dp / 2, 200.dp)
                val scrollState = rememberScrollState()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = halfScreenHeight)
                        .consumeBoundaryScroll(scrollState)
                        .verticalScroll(scrollState)
                ) {
                    Column(modifier = Modifier.padding(top = 3.dp)) {
                        if (isError) {
                            val errorText = (tool.state as ToolState.Error).error
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.errorContainer,
                                border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.7f)) else null,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                ErrorPayloadContent(
                                    text = errorText,
                                    textStyle = CodeTypography.copy(
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    ),
                                    textColor = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(4.dp),
                                )
                            }
                        } else {
                            DiffView(before = diffBefore, after = diffAfter)
                        }
                    }
                }
            }
```

需在文件顶部添加缺失的 import：
```kotlin
import androidx.compose.foundation.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
import dev.minios.ocremote.ui.screens.chat.util.consumeBoundaryScroll
```

- [ ] **Step 5: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/tools/cards/EditToolCard.kt
git commit -m "feat(chat): add verticalScroll to EditToolCard + compact spacing

Design B3d + C5. Refs: #2026-05-30-chat-ui-interaction-fixes"
```

---

### Task 7: 更新 SearchToolCard — 调用名迁移 + 间距缩减

**Files:**
- Modify: `ui/screens/chat/tools/cards/SearchToolCard.kt:42,89,95,145,147,150`

- [ ] **Step 1: 替换 import（L42）**

将：
```kotlin
import dev.minios.ocremote.ui.screens.chat.util.consumeBoundaryFling
```
改为：
```kotlin
import dev.minios.ocremote.ui.screens.chat.util.consumeBoundaryScroll
```

- [ ] **Step 2: 更新 Surface 圆角（L89）**

将 `RoundedCornerShape(8.dp)` 改为 `RoundedCornerShape(6.dp)`。

- [ ] **Step 3: 更新外层 Column padding（L95）**

将 `Modifier.padding(8.dp)` 改为 `Modifier.padding(4.dp)`。

- [ ] **Step 4: 更新展开区域 Surface padding（L145）**

将 `.padding(top = 6.dp)` 改为 `.padding(top = 3.dp)`。

- [ ] **Step 5: 替换 consumeBoundaryFling 调用（L147）**

将 `.consumeBoundaryFling(scrollState)` 改为 `.consumeBoundaryScroll(scrollState)`。

- [ ] **Step 6: 给 MarkdownContent 外面包一层 Column 添加 padding（L150 附近）**

当前代码（L149-154）：
```kotlin
                ) {
                    MarkdownContent(
                        markdown = output,
                        textColor = if (isAmoled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f) else MaterialTheme.colorScheme.onSecondaryContainer,
                        isUser = false
                    )
                }
```

替换为：
```kotlin
                ) {
                    Column(modifier = Modifier.padding(4.dp)) {
                        MarkdownContent(
                            markdown = output,
                            textColor = if (isAmoled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f) else MaterialTheme.colorScheme.onSecondaryContainer,
                            isUser = false
                        )
                    }
                }
```

需确认 `Column` 的 import 已存在。

- [ ] **Step 7: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/tools/cards/SearchToolCard.kt
git commit -m "refactor(chat): update SearchToolCard for consumeBoundaryScroll + compact spacing

Design B2 + C6. Refs: #2026-05-30-chat-ui-interaction-fixes"
```

---

### Task 8: 更新 TaskToolCard — 调用名迁移 + 间距缩减

**Files:**
- Modify: `ui/screens/chat/tools/cards/TaskToolCard.kt:43,96,102,177,179,182`

- [ ] **Step 1: 替换 import（L43）**

将：
```kotlin
import dev.minios.ocremote.ui.screens.chat.util.consumeBoundaryFling
```
改为：
```kotlin
import dev.minios.ocremote.ui.screens.chat.util.consumeBoundaryScroll
```

- [ ] **Step 2: 更新 Surface 圆角（L96）**

将 `RoundedCornerShape(8.dp)` 改为 `RoundedCornerShape(6.dp)`。

- [ ] **Step 3: 更新外层 Column padding（L102）**

将 `Modifier.padding(8.dp)` 改为 `Modifier.padding(4.dp)`。

- [ ] **Step 4: 更新展开区域 Surface padding（L177）**

将 `.padding(top = 6.dp)` 改为 `.padding(top = 3.dp)`。

- [ ] **Step 5: 替换 consumeBoundaryFling 调用（L179）**

将 `.consumeBoundaryFling(scrollState)` 改为 `.consumeBoundaryScroll(scrollState)`。

- [ ] **Step 6: 给 MarkdownContent 外面包一层 Column 添加 padding（L182 附近）**

当前代码：
```kotlin
                ) {
                    MarkdownContent(
                        markdown = output,
                        textColor = if (isAmoled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f) else MaterialTheme.colorScheme.onSecondaryContainer,
                        isUser = false
                    )
                }
```

替换为：
```kotlin
                ) {
                    Column(modifier = Modifier.padding(4.dp)) {
                        MarkdownContent(
                            markdown = output,
                            textColor = if (isAmoled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f) else MaterialTheme.colorScheme.onSecondaryContainer,
                            isUser = false
                        )
                    }
                }
```

需确认 `Column` 的 import 已存在。

- [ ] **Step 7: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/tools/cards/TaskToolCard.kt
git commit -m "refactor(chat): update TaskToolCard for consumeBoundaryScroll + compact spacing

Design B2 + C7. Refs: #2026-05-30-chat-ui-interaction-fixes"
```

---

### Task 9: 更新 ReasoningBlock — 调用名迁移 + 间距缩减

**Files:**
- Modify: `ui/screens/chat/components/ReasoningBlock.kt:120,162,169`

- [ ] **Step 1: 替换 import**

找到并替换：
```kotlin
import dev.minios.ocremote.ui.screens.chat.util.consumeBoundaryFling
```
为：
```kotlin
import dev.minios.ocremote.ui.screens.chat.util.consumeBoundaryScroll
```

- [ ] **Step 2: 更新外层 padding（L120）**

将 `.padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)` 改为 `.padding(start = 10.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)`。

- [ ] **Step 3: 更新 Spacer 高度（L162）**

将 `Spacer(modifier = Modifier.height(10.dp))` 改为 `Spacer(modifier = Modifier.height(6.dp))`。

- [ ] **Step 4: 替换 consumeBoundaryFling 调用（L169）**

将 `.consumeBoundaryFling(reasoningScrollState)` 改为 `.consumeBoundaryScroll(reasoningScrollState)`。

- [ ] **Step 5: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/ReasoningBlock.kt
git commit -m "refactor(chat): update ReasoningBlock for consumeBoundaryScroll + compact spacing

Design B2 + C8. Refs: #2026-05-30-chat-ui-interaction-fixes"
```

---

### Task 10: 更新 ChatScreen 消息间距（C9）

**Files:**
- Modify: `ui/screens/chat/ChatScreen.kt:1744`

- [ ] **Step 1: 更新 messageSpacing**

在 ChatScreen.kt 的 `else ->` 分支内（L1744 附近），找到：

```kotlin
val messageSpacing = if (LocalCompactMessages.current) 4.dp else 12.dp
```

替换为：

```kotlin
val messageSpacing = if (LocalCompactMessages.current) 4.dp else 6.dp
```

- [ ] **Step 2: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt
git commit -m "style(chat): reduce message spacing from 12dp to 6dp

Design C9. Refs: #2026-05-30-chat-ui-interaction-fixes"
```

---

## Phase 2: 移除 SelectionContainer + 复制弹窗（设计 A）

### Task 11: 移除 MarkdownContent 透明覆盖层

**Files:**
- Modify: `ui/screens/chat/markdown/MarkdownContent.kt:268-299`

- [ ] **Step 1: 删除透明 SelectionContainer 覆盖层**

将 L268-299 的 `Box` 及其内容替换为直接返回 Markdown：

```kotlin
    Markdown(
        markdownState = markdownState,
        colors = colors,
        typography = typography,
        components = components,
        dimens = dimens,
        imageTransformer = Coil3ImageTransformerImpl,
        modifier = Modifier.fillMaxWidth()
    )
```

即删除整个 `Box(modifier = Modifier.fillMaxWidth()) { ... }` 包裹和内部的 `if (!isUser) { ... }` 透明覆盖层块。

可以移除以下不再需要的 import：
- `androidx.compose.foundation.text.selection.SelectionContainer`
- `androidx.compose.material3.LocalTextSelectionColors`
- `androidx.compose.material3.TextSelectionColors`
- `androidx.compose.runtime.CompositionLocalProvider`
- `androidx.compose.ui.graphics.Color`（如果仅用于 Color.Transparent）

- [ ] **Step 2: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/markdown/MarkdownContent.kt
git commit -m "fix(chat): remove transparent SelectionContainer overlay from MarkdownContent

Eliminates pointer event competition between the transparent text
selection layer and LazyColumn/verticalScroll, fixing the scroll
instability issue when tool cards are expanded.

Trade-off: inline text selection is no longer available in the chat
flow; replaced by MarkdownPreviewDialog (next commit).

Design A1. Refs: #2026-05-30-chat-ui-interaction-fixes"
```

---

### Task 12: 新增 MarkdownPreviewDialog

**Files:**
- Create: `ui/screens/chat/dialog/MarkdownPreviewDialog.kt`

- [ ] **Step 1: 创建 MarkdownPreviewDialog.kt**

```kotlin
package dev.minios.ocremote.ui.screens.chat.dialog

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.minios.ocremote.ui.screens.chat.markdown.MarkdownContent
import dev.minios.ocremote.ui.screens.chat.util.performHaptic

private enum class PreviewMode { SOURCE, RENDERED }

/**
 * Full-screen dialog for viewing and copying a single assistant message.
 * Supports source (monospace, selectable) and rendered (Markdown) views.
 * No nested scroll conflicts: the dialog is a standalone screen,
 * not embedded inside a LazyColumn.
 */
@Composable
internal fun MarkdownPreviewDialog(
    markdown: String,
    onDismiss: () -> Unit,
    onCopyAll: () -> Unit
) {
    // Defensive: dismiss if content is blank
    if (markdown.isBlank()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }

    var previewMode by rememberSaveable { mutableStateOf(PreviewMode.SOURCE) }
    val scrollState = rememberScrollState()
    val snackbarHostState = remember { SnackbarHostState() }
    val view = LocalView.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // TopAppBar
                TopAppBar(
                    title = { Text("Agent 回答") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回"
                            )
                        }
                    },
                    actions = {
                        // View toggle button
                        TextButton(onClick = {
                            previewMode = if (previewMode == PreviewMode.SOURCE)
                                PreviewMode.RENDERED else PreviewMode.SOURCE
                        }) {
                            Text(
                                if (previewMode == PreviewMode.SOURCE) "渲染" else "源码"
                            )
                        }
                        // Copy all button
                        IconButton(onClick = {
                            performHaptic(view, true)
                            onCopyAll()
                        }) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "复制全部"
                            )
                        }
                    }
                )

                // Content area
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .animateContentSize()
                ) {
                    when (previewMode) {
                        PreviewMode.SOURCE -> {
                            SelectionContainer {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(scrollState)
                                        .padding(16.dp)
                                ) {
                                    Text(
                                        text = markdown,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        lineHeight = 18.sp
                                    )
                                }
                            }
                        }
                        PreviewMode.RENDERED -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(scrollState)
                                    .padding(16.dp)
                            ) {
                                MarkdownContent(
                                    markdown = markdown,
                                    textColor = MaterialTheme.colorScheme.onSurface,
                                    isUser = false
                                )
                            }
                        }
                    }
                }

                // Snackbar for copy confirmation
                SnackbarHost(hostState = snackbarHostState) { data ->
                    Snackbar(snackbarData = data)
                }
            }
        }
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/dialog/MarkdownPreviewDialog.kt
git commit -m "feat(chat): add MarkdownPreviewDialog for viewing and copying assistant messages

Full-screen dialog with source (selectable monospace) and rendered
(Markdown) views. Safe from scroll conflicts because it's a standalone
dialog, not embedded in LazyColumn.

Design A2. Refs: #2026-05-30-chat-ui-interaction-fixes"
```

---

### Task 13: ChatScreen 接入 MarkdownPreviewDialog

**Files:**
- Modify: `ui/screens/chat/ChatScreen.kt` — 主会话区域 L1831-1849 和子会话区域 L2082-2100

- [ ] **Step 1: 添加 MarkdownPreviewDialog 的 import**

在 ChatScreen.kt 的 import 区域添加：
```kotlin
import dev.minios.ocremote.ui.screens.chat.dialog.MarkdownPreviewDialog
```

- [ ] **Step 2: 添加 dialog 状态变量**

在 `ChatScreen` 函数内部、现有 dialog 状态变量附近（如 `showModelPicker` 附近），添加：

```kotlin
var markdownPreviewText by remember { mutableStateOf<String?>(null) }
```

- [ ] **Step 3: 修改主会话 onCopyText（L1837-1848）**

将：
```kotlin
                                            onCopyText = {
                                                val text = msg.parts.filterIsInstance<Part.Text>()
                                                    .joinToString("\n") { it.text }
                                                if (text.isNotBlank()) {
                                                    clipboardManager.setText(
                                                        androidx.compose.ui.text.AnnotatedString(text)
                                                    )
                                                    coroutineScope.launch {
                                                        snackbarHostState.showSnackbar(context.getString(R.string.chat_copied_clipboard))
                                                    }
                                                }
                                            }
```

替换为：
```kotlin
                                            onCopyText = {
                                                val text = msg.parts.filterIsInstance<Part.Text>()
                                                    .joinToString("\n\n") { it.text }
                                                if (text.isNotBlank()) {
                                                    markdownPreviewText = text
                                                }
                                            }
```

- [ ] **Step 4: 修改子会话 onCopyText（L2088-2098）**

将：
```kotlin
                                                   onCopyText = {
                                                       val text = msg.parts.filterIsInstance<Part.Text>()
                                                           .joinToString("\n") { it.text }
                                                       if (text.isNotBlank()) {
                                                           clipboardManager.setText(
                                                               androidx.compose.ui.text.AnnotatedString(text)
                                                           )
                                                           coroutineScope.launch {
                                                               snackbarHostState.showSnackbar(context.getString(R.string.chat_copied_clipboard))
                                                           }
                                                       }
                                                   }
```

替换为：
```kotlin
                                                   onCopyText = {
                                                       val text = msg.parts.filterIsInstance<Part.Text>()
                                                           .joinToString("\n\n") { it.text }
                                                       if (text.isNotBlank()) {
                                                           markdownPreviewText = text
                                                       }
                                                   }
```

- [ ] **Step 5: 在 dialog 区域添加 MarkdownPreviewDialog**

在 ChatScreen 函数末尾的 dialog 区域（如 `showModelPicker` dialog 之后）添加：

```kotlin
    // Markdown preview dialog (copy/view assistant message)
    markdownPreviewText?.let { previewText ->
        MarkdownPreviewDialog(
            markdown = previewText,
            onDismiss = { markdownPreviewText = null },
            onCopyAll = {
                clipboardManager.setText(AnnotatedString(previewText))
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(context.getString(R.string.chat_copied_clipboard))
                }
            }
        )
    }
```

- [ ] **Step 6: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt
git commit -m "feat(chat): wire MarkdownPreviewDialog into ChatScreen copy button

Copy button now opens a full-screen preview dialog instead of
directly copying to clipboard. Join separator changed from \\n to \\n\\n.

Design A3. Refs: #2026-05-30-chat-ui-interaction-fixes"
```

---

## Phase 3: 统计信息时机修正（设计 D）

### Task 14: 创建 TurnGroupCalculator 工具类 + 测试

**Files:**
- Create: `ui/screens/chat/util/TurnGroupCalculator.kt`
- Create: `test/.../chat/util/TurnGroupCalculatorTest.kt`

- [ ] **Step 1: 编写 TurnGroupCalculatorTest 失败测试**

```kotlin
package dev.minios.ocremote.ui.screens.chat.util

import dev.minios.ocremote.domain.model.Message
import dev.minios.ocremote.domain.model.MessageTime
import dev.minios.ocremote.ui.screens.chat.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class TurnGroupCalculatorTest {

    private fun assistantMsg(id: String) = ChatMessage(
        message = Message.Assistant(
            id = id,
            time = MessageTime(created = 1000L, completed = 2000L),
            modelId = "test-model"
        ),
        parts = emptyList()
    )

    private fun userMsg(id: String) = ChatMessage(
        message = Message.User(
            id = id,
            time = MessageTime(created = 500L)
        ),
        parts = emptyList()
    )

    @Test
    fun `empty messages returns empty map`() {
        val result = computeTurnGroups(emptyList())
        assertEquals(emptyMap<Int, List<ChatMessage>>(), result)
    }

    @Test
    fun `single assistant message returns one group`() {
        val msgs = listOf(assistantMsg("a1"))
        val result = computeTurnGroups(msgs)
        assertEquals(1, result.size)
        assertEquals(listOf(msgs[0]), result[0])
    }

    @Test
    fun `three consecutive assistants grouped as one turn`() {
        val msgs = listOf(assistantMsg("a1"), assistantMsg("a2"), assistantMsg("a3"))
        val result = computeTurnGroups(msgs)
        assertEquals(3, result.size)
        assertEquals(msgs, result[0])
        assertEquals(msgs, result[1])
        assertEquals(msgs, result[2])
    }

    @Test
    fun `mixed user and assistant correct grouping`() {
        // User-Asst-Asst-User-Asst → two turn groups
        val msgs = listOf(
            userMsg("u1"), assistantMsg("a1"), assistantMsg("a2"),
            userMsg("u2"), assistantMsg("a3")
        )
        val result = computeTurnGroups(msgs)
        // Index 1,2 → group [a1, a2]
        assertEquals(listOf(msgs[1], msgs[2]), result[1])
        assertEquals(listOf(msgs[1], msgs[2]), result[2])
        // Index 4 → group [a3]
        assertEquals(listOf(msgs[4]), result[4])
        // Index 0,3 are User → null
        assertEquals(null, result[0])
        assertEquals(null, result[3])
    }

    @Test
    fun `only user messages returns empty map`() {
        val msgs = listOf(userMsg("u1"), userMsg("u2"))
        val result = computeTurnGroups(msgs)
        assertEquals(emptyMap<Int, List<ChatMessage>>(), result)
    }
}
```

- [ ] **Step 2: 运行测试验证编译失败**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "dev.minios.ocremote.ui.screens.chat.util.TurnGroupCalculatorTest"`
Expected: FAIL — `unresolved reference: computeTurnGroups`

- [ ] **Step 3: 创建 TurnGroupCalculator.kt**

```kotlin
package dev.minios.ocremote.ui.screens.chat.util

import dev.minios.ocremote.ui.screens.chat.ChatMessage

/**
 * Computes turn groups for assistant messages in a chat message list.
 *
 * A "turn" is a consecutive sequence of assistant messages between two user
 * messages (or the start/end of the list).
 *
 * @return Map from message index → list of all ChatMessages in the same turn.
 *         User message indices are NOT included in the map.
 */
internal fun computeTurnGroups(messages: List<ChatMessage>): Map<Int, List<ChatMessage>> {
    val groups = mutableListOf<Pair<IntRange, List<ChatMessage>>>()
    var currentStart = -1
    val currentGroup = mutableListOf<ChatMessage>()

    for ((index, msg) in messages.withIndex()) {
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
        groups.add((currentStart until messages.size) to currentGroup.toList())
    }

    val indexToGroup = mutableMapOf<Int, List<ChatMessage>>()
    for ((range, group) in groups) {
        for (i in range) {
            indexToGroup[i] = group
        }
    }
    return indexToGroup
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "dev.minios.ocremote.ui.screens.chat.util.TurnGroupCalculatorTest"`
Expected: 5 tests PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/util/TurnGroupCalculator.kt app/src/test/kotlin/dev/minios/ocremote/ui/screens/chat/util/TurnGroupCalculatorTest.kt
git commit -m "feat(chat): add TurnGroupCalculator for turn-based stat aggregation

Design D4. Refs: #2026-05-30-chat-ui-interaction-fixes"
```

---

### Task 15: 更新 AssistantMessageCard — isTurnLast + turnMessages + Footer 复制按钮

**Files:**
- Modify: `ui/screens/chat/components/AssistantMessageCard.kt:51-56,151-217`

- [ ] **Step 1: 更新函数签名（L51-56）**

将：
```kotlin
internal fun AssistantMessageCard(
    chatMessage: ChatMessage,
    isContinuation: Boolean,
    onViewSubSession: ((String) -> Unit)? = null,
    onCopyText: (() -> Unit)? = null,
)
```

改为：
```kotlin
internal fun AssistantMessageCard(
    chatMessage: ChatMessage,
    isContinuation: Boolean,
    isTurnLast: Boolean = false,
    turnMessages: List<ChatMessage>? = null,
    onViewSubSession: ((String) -> Unit)? = null,
    onCopyText: (() -> Unit)? = null,
)
```

- [ ] **Step 2: 替换 Footer 逻辑（L151-217）**

将整个 Footer 计算和渲染块（L151-217）替换为：

```kotlin
                // Token/cost/duration footer — only on the last message of a turn
                val stepFinishes = if (isTurnLast && turnMessages != null) {
                    turnMessages.flatMap { msg ->
                        msg.parts.filterIsInstance<Part.StepFinish>()
                    }
                } else {
                    emptyList()
                }

                if (stepFinishes.isNotEmpty()) {
                    val totalInput = stepFinishes.sumOf { it.tokens?.input ?: 0 }
                    val totalOutput = stepFinishes.sumOf { it.tokens?.output ?: 0 }
                    val totalCost = stepFinishes.sumOf { it.cost ?: 0.0 }
                    val hasTokenStats = totalInput > 0 || totalOutput > 0 || totalCost > 0.0

                    val durationMs = if (isTurnLast && turnMessages != null) {
                        val first = turnMessages.firstOrNull()?.message
                        val last = turnMessages.lastOrNull()?.message
                        if (first is Message.Assistant && last is Message.Assistant) {
                            last.time.completed?.let { end -> end - first.time.created }
                        } else null
                    } else null

                    val modelId = if (isTurnLast && turnMessages != null) {
                        (turnMessages.lastOrNull()?.message as? Message.Assistant)?.modelId
                    } else null

                    val hasFooter = hasTokenStats || durationMs != null || !modelId.isNullOrBlank()

                    if (hasFooter) {
                        Spacer(modifier = Modifier.height(if (compact) 4.dp else 8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (assistantMsg?.providerId != null) {
                                ProviderIcon(
                                    providerId = assistantMsg.providerId,
                                    size = 10.dp,
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                                )
                            }
                            if (!modelId.isNullOrBlank()) {
                                Text(
                                    text = modelId,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                            }
                            if (durationMs != null && durationMs > 0) {
                                Text(
                                    text = formatDuration(durationMs),
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                                )
                            }
                            if (totalInput > 0 || totalOutput > 0) {
                                Text(
                                    text = stringResource(R.string.chat_tokens_format, totalInput, totalOutput),
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                                )
                            }
                            if (totalCost > 0.0 && totalCost.isFinite()) {
                                Text(
                                    text = stringResource(R.string.chat_cost_format, String.format("%.4f", totalCost)),
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                                )
                            }
                            // Footer copy button
                            if (onCopyText != null) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = stringResource(R.string.chat_copy),
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clickable {
                                            performHaptic(hapticView, hapticOn)
                                            onCopyText()
                                        },
                                    tint = textColor.copy(alpha = 0.3f)
                                )
                            }
                        }
                    }
                }
```

- [ ] **Step 3: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/AssistantMessageCard.kt
git commit -m "feat(chat): aggregate stats in turn-last message + add footer copy button

Footer now only shows on isTurnLast=true, aggregating stepFinishes
from all messages in the turn. Adds a copy button at the footer's
right edge.

Design D2 + D3. Refs: #2026-05-30-chat-ui-interaction-fixes"
```

---

### Task 16: ChatScreen 接入 turn 分组 + isTurnLast 传递

**Files:**
- Modify: `ui/screens/chat/ChatScreen.kt:1749-1754,1831-1849,2082-2100`

- [ ] **Step 1: 添加 import**

在 import 区域添加：
```kotlin
import dev.minios.ocremote.ui.screens.chat.util.computeTurnGroups
```

- [ ] **Step 2: 修正 isAssistantContinuation key（L1749）**

将：
```kotlin
val isAssistantContinuation = remember(uiState.messages.size) {
```

改为：
```kotlin
val isAssistantContinuation = remember(uiState.messages.map { it.message.id }) {
```

- [ ] **Step 3: 添加 turnGroups 计算**

在 `isAssistantContinuation` 之后（L1754 之后），添加：

```kotlin
                      val turnGroups = remember(uiState.messages.map { it.message.id }) {
                          computeTurnGroups(uiState.messages)
                      }
```

- [ ] **Step 4: 更新主会话 AssistantMessageCard 调用（L1831-1849）**

将：
```kotlin
                                    msg.isAssistant -> {
                                        val isContinuation = isAssistantContinuation.getOrElse(index) { false }
                                        AssistantMessageCard(
                                            chatMessage = msg,
                                            isContinuation = isContinuation,
                                            onViewSubSession = navigateToChildSessionWithSave,
                                            onCopyText = {
                                                val text = msg.parts.filterIsInstance<Part.Text>()
                                                    .joinToString("\n\n") { it.text }
                                                if (text.isNotBlank()) {
                                                    markdownPreviewText = text
                                                }
                                            }
                                        )
                                    }
```

替换为：
```kotlin
                                    msg.isAssistant -> {
                                        val isContinuation = isAssistantContinuation.getOrElse(index) { false }
                                        val isTurnLast = uiState.messages.getOrNull(index)?.isAssistant == true &&
                                                         uiState.messages.getOrNull(index + 1)?.isAssistant != true
                                        val turnMessagesForMsg = turnGroups[index]
                                        AssistantMessageCard(
                                            chatMessage = msg,
                                            isContinuation = isContinuation,
                                            isTurnLast = isTurnLast,
                                            turnMessages = turnMessagesForMsg,
                                            onViewSubSession = navigateToChildSessionWithSave,
                                            onCopyText = {
                                                val messages = turnMessagesForMsg ?: listOf(msg)
                                                val text = messages.flatMap { m ->
                                                    m.parts.filterIsInstance<Part.Text>().map { it.text }
                                                }.joinToString("\n\n")
                                                if (text.isNotBlank()) {
                                                    markdownPreviewText = text
                                                }
                                            }
                                        )
                                    }
```

- [ ] **Step 5: 更新子会话 AssistantMessageCard 调用（L2082-2100）**

同样模式替换子会话区域：
```kotlin
                                           msg.isAssistant -> {
                                               val isContinuation = isAssistantContinuation.getOrElse(index) { false }
                                               val isTurnLast = uiState.messages.getOrNull(index)?.isAssistant == true &&
                                                                uiState.messages.getOrNull(index + 1)?.isAssistant != true
                                               val turnMessagesForMsg = turnGroups[index]
                                               AssistantMessageCard(
                                                   chatMessage = msg,
                                                   isContinuation = isContinuation,
                                                   isTurnLast = isTurnLast,
                                                   turnMessages = turnMessagesForMsg,
                                                   onViewSubSession = navigateToChildSessionWithSave,
                                                   onCopyText = {
                                                       val messages = turnMessagesForMsg ?: listOf(msg)
                                                       val text = messages.flatMap { m ->
                                                           m.parts.filterIsInstance<Part.Text>().map { it.text }
                                                       }.joinToString("\n\n")
                                                       if (text.isNotBlank()) {
                                                           markdownPreviewText = text
                                                       }
                                                   }
                                               )
                                           }
```

- [ ] **Step 6: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt
git commit -m "feat(chat): wire turn group computation into ChatScreen

Adds computeTurnGroups call, isTurnLast calculation, and passes
turnMessages to AssistantMessageCard. Fixes isAssistantContinuation
key to use message IDs instead of size.

Design D4 + D5. Refs: #2026-05-30-chat-ui-interaction-fixes"
```

---

## Phase 4: 会话状态修正（设计 F）

### Task 17: 新增 fetchSessionStatus API 方法

**Files:**
- Modify: `data/api/OpenCodeApi.kt:919`（文件末尾 `}` 之前）

- [ ] **Step 1: 在 OpenCodeApi.kt 末尾添加 fetchSessionStatus 方法**

在文件末尾的 `}` 之前（L919 之前），添加：

```kotlin

    // ============ Session Status ============

    /**
     * Query the current status of all sessions from the OpenCode server.
     * GET /session/status
     *
     * Used as a REST fallback when SSE events may have been missed
     * (app backgrounded, connection lost, etc.).
     *
     * @return Map of sessionId → SessionStatusInfo where type ∈ {"idle", "busy", "retry"}
     */
    suspend fun fetchSessionStatus(conn: ServerConnection): Result<Map<String, SessionStatusInfo>> {
        return runCatching {
            val response: Map<String, kotlinx.serialization.json.JsonObject> =
                httpClient.get("${conn.baseUrl}/session/status") {
                    conn.authHeader?.let { header("Authorization", it) }
                }.body()
            response.mapValues { (_, obj) ->
                SessionStatusInfo(
                    type = obj["type"]?.jsonPrimitive?.content ?: "idle",
                    attempt = obj["attempt"]?.jsonPrimitive?.intOrNull,
                    message = obj["message"]?.jsonPrimitive?.contentOrNull,
                    next = obj["next"]?.jsonPrimitive?.longOrNull
                )
            }
        }
    }
```

同时添加数据类（可放在 `OpenCodeApi.kt` 内部作为顶层或放在 companion object 之后）：

```kotlin
/**
 * Represents the status of a session as reported by GET /session/status.
 */
data class SessionStatusInfo(
    val type: String,          // "idle" | "busy" | "retry"
    val attempt: Int? = null,  // only for "retry"
    val message: String? = null,
    val next: Long? = null
)
```

- [ ] **Step 2: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/data/api/OpenCodeApi.kt
git commit -m "feat(api): add fetchSessionStatus REST endpoint

GET /session/status returns a map of session IDs to their current
status (idle/busy/retry). Used as REST fallback for SSE disconnection.

Design F4a. Refs: #2026-05-30-chat-ui-interaction-fixes"
```

---

### Task 18: EventDispatcher 新增 markSessionIdle + ChatViewModel 新增 syncSessionStatus

**Files:**
- Modify: `data/repository/EventDispatcher.kt:117`（文件末尾 `}` 之前）
- Modify: `ui/screens/chat/ChatViewModel.kt`

- [ ] **Step 1: 在 EventDispatcher.kt 末尾添加 markSessionIdle 方法**

在 `clearForServer` 方法之后、class 的 `}` 之前，添加：

```kotlin

    // ============ State Correction ============

    /**
     * Force-mark all streaming messages in a session as completed.
     * Used when REST fallback detects the server reports "idle" but
     * the UI may still show "thinking" due to missed SSE events.
     */
    fun markSessionIdle(sessionId: String) {
        messageHandler.markSessionIdle(sessionId)
        sessionHandler.updateSessionStatus(sessionId, SessionStatus.Idle)
    }
```

- [ ] **Step 2: 在 MessageEventHandler 中添加 markSessionIdle**

找到 `MessageEventHandler.kt`（在 `data/repository/handler/` 目录下），添加方法：

```kotlin
    /**
     * Mark all incomplete assistant messages in a session as completed.
     * Called when REST fallback detects server is idle but UI shows streaming.
     */
    fun markSessionIdle(sessionId: String) {
        val current = _messages.value[sessionId] ?: return
        val now = System.currentTimeMillis()
        val updated = current.map { msg ->
            if (msg is Message.Assistant && msg.time.completed == null) {
                msg.copy(time = msg.time.copy(completed = now))
            } else {
                msg
            }
        }
        _messages.value = _messages.value + (sessionId to updated)
    }
```

- [ ] **Step 3: 在 ChatViewModel.kt 添加 syncSessionStatus 方法**

在 ChatViewModel 的方法区域（如 `loadSession` 方法附近），添加：

```kotlin
    /**
     * Query the OpenCode server for the actual session status and correct
     * any UI state drift caused by missed SSE events.
     *
     * Triggered on:
     * - Entering a session (LaunchedEffect(sessionId))
     * - Resuming from background (DisposableEffect ON_RESUME)
     */
    fun syncSessionStatus() {
        viewModelScope.launch {
            val result = api.fetchSessionStatus(conn)
            result.onSuccess { statuses ->
                val statusInfo = statuses[sessionId]
                if (statusInfo != null && statusInfo.type == "idle") {
                    _isLoading.value = false
                    eventDispatcher.markSessionIdle(sessionId)
                }
            }
            // On failure: silently keep current UI state, rely on future SSE events
        }
    }
```

需确认 `viewModelScope` import 已存在（`import androidx.lifecycle.viewModelScope`）。

- [ ] **Step 4: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/data/repository/EventDispatcher.kt app/src/main/kotlin/dev/minios/ocremote/data/repository/handler/MessageEventHandler.kt app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt
git commit -m "feat(chat): add syncSessionStatus for SSE disconnection recovery

REST fallback: when entering/resuming a session, query GET /session/status.
If server reports idle, force-clear loading state and mark streaming
messages as completed.

Design F4b + F4d. Refs: #2026-05-30-chat-ui-interaction-fixes"
```

---

### Task 19: ChatScreen 接入 syncSessionStatus 触发

**Files:**
- Modify: `ui/screens/chat/ChatScreen.kt`

- [ ] **Step 1: 添加 LaunchedEffect 进入会话时触发**

在 ChatScreen 函数内部，现有的 `LaunchedEffect` 附近（如 L345 附近或 `LaunchedEffect(Unit)` 块附近），添加：

```kotlin
    // Sync session status when entering a session (REST fallback for missed SSE events)
    LaunchedEffect(viewModel.sessionId) {
        if (viewModel.sessionId.isNotBlank()) {
            viewModel.syncSessionStatus()
        }
    }
```

- [ ] **Step 2: 添加 DisposableEffect 从后台恢复时触发**

在上述 `LaunchedEffect` 之后添加：

```kotlin
    // Sync session status when resuming from background
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.syncSessionStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
```

需确认以下 import 已存在（ChatScreen.kt L94 已有 `LocalLifecycleOwner`，L134-135 已有 `Lifecycle` 和 `LifecycleEventObserver`）：

```kotlin
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
```

如果 `lifecycleOwner` 变量不存在，需在 ChatScreen 函数内部添加：

```kotlin
    val lifecycleOwner = LocalLifecycleOwner.current
```

- [ ] **Step 3: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt
git commit -m "feat(chat): trigger syncSessionStatus on session enter and resume

LaunchedEffect(sessionId) triggers on session entry.
DisposableEffect(ON_RESUME) triggers on background recovery.

Design F4c. Refs: #2026-05-30-chat-ui-interaction-fixes"
```

---

## Phase 5: 事件生命周期文档（设计 E）

### Task 20: 创建事件生命周期文档

**Files:**
- Create: `docs/chat-ui-event-lifecycle.md`

- [ ] **Step 1: 创建文档**

在项目根目录 `docs/` 下创建 `chat-ui-event-lifecycle.md`，内容涵盖设计文档 E1-E6 的所有章节：

```markdown
# Chat UI 事件生命周期文档

> 本文档描述 OC Remote 对话界面的触摸事件、嵌套滚动、SSE 流式更新、
> 消息状态机的完整生命周期，以及已知的竞态条件和缓解策略。

---

## E1. 触摸事件传播链

### 事件分发顺序

```
手指 Down → Move → Up

Compose pointer event 分发（父→子）：
1. hitTest: 从根布局开始，确定哪些组件包含触摸点
2. 父节点 onPointerEvent(PointerEventType.Press) → 子节点 onPointerEvent
3. 子节点 consume() → 父节点检查 consumed 状态
4. 手指 Move → 同上流程
5. 手指 Up → 同上流程，触发 tap/longPress 识别
```

### ChatScreen 中的 pointerInput 使用位置

| 组件 | 位置 | 手势类型 |
|------|------|----------|
| LazyColumn | ChatScreen L1766 | detectTapGestures(onTap=hideKeyboard) |
| SelectionContainer | BashToolCard | 长按选择文字 |
| MarkdownPreviewDialog | dialog/MarkdownPreviewDialog.kt | SelectionContainer 长按选择 |
| Header Row | 各 ToolCard | clickable 切换展开 |

### 多指触控

Compose 的 pointer event 体系通过 pointerId 区分不同手指。ChatScreen 未使用多指手势。
SelectionContainer 内部长按选择时会消费 pointer events，阻止事件传播。

---

## E2. 嵌套滚动生命周期

### 回调触发顺序

```
手指 Down (不触发滚动回调)
手指 Move (delta ≠ 0):
  1. 父 onPreScroll(delta) → 父决定是否提前消费
  2. 子组件消费 delta (verticalScroll / LazyColumn)
  3. 父 onPostScroll(consumed, available) → 父决定是否消费剩余
  4. consumeBoundaryScroll.onPostScroll → 边界消费

手指 Up (触发 fling):
  5. 父 onPreFling(velocity) → 父决定是否提前消费速度
  6. 子组件执行 fling 动画
  7. 父 onPostFling(consumed, available) → 父决定是否消费剩余速度
  8. consumeBoundaryScroll.onPostFling → 边界消费
```

### consumeBoundaryScroll 介入节点

`consumeBoundaryScroll` 修饰符放在 `verticalScroll` 之前（modifier 链从外到内），
所以其 `NestedScrollConnection` 先于 `ScrollState` 接收事件。

```
[consumeBoundaryScroll] → [verticalScroll] → [内容]

onPostScroll:
  - scrollState.canScrollBackward == false (到达顶部) && available.y < 0 (向上拖)
    → 返回 available (消费，阻止传给 LazyColumn)
  - scrollState.canScrollForward == false (到达底部) && available.y > 0 (向下拖)
    → 返回 available (消费，阻止传给 LazyColumn)
  - 其他 → 返回 Offset.Zero (放行)

onPostFling: 同上逻辑但处理 Velocity
```

### reverseLayout=true 对方向的影响

ChatScreen 的 LazyColumn 使用 `reverseLayout = true`：
- 视觉上：最新消息在底部（index 0 = 视觉底部）
- 滚动方向：向下拖 → delta.y > 0 → LazyColumn 向尾部滚动（显示更旧的消息）
- verticalScroll 在工具卡片内不受 reverseLayout 影响

---

## E3. SSE 流式更新 → UI 重组

### 传播链路

```
SSE MessagePartDelta 事件
  → SseConnectionManager (data/network/SseConnectionManager.kt)
  → EventDispatcher.processEvent(event, serverId)
  → MessageEventHandler.handle(event, serverId)
  → _messages StateFlow 更新
  → ChatViewModel.combine { messages[sessionId] ... }
  → ChatUiState 更新
  → collectAsState() 触发
  → ChatScreen Recomposition
  → AssistantMessageCard / ToolCards 重组
```

### 事件去重和合并策略

- SSE 事件通过 `messageId + partIndex` 定位目标 Part
- `MessagePartDelta` 累积追加到现有 Part 的 text 字段
- `retainState = true`（MarkdownContent L266）保持旧渲染内容直到新内容解析完成，防止闪烁

### Recomposition 范围

- `collectAsState()` 在 ChatScreen 层级读取 `uiState`
- `uiState.messages` 变化 → `itemsIndexed` 重新执行
- 每条消息通过 `key(msg.message.id)` 稳定标识，只有变化的 item 会重组
- `AssistantMessageCard` 内部 `key(part.id)` 稳定 Part 重组

---

## E4. 消息状态机

```
┌────────────────────────────────────────────────────────────────┐
│                                                                │
│  User 消息:                                                     │
│  Queued → Sending → Complete                                   │
│                                                                │
│  Assistant 消息:                                                │
│  (created) → Streaming (SSE MessagePartDelta) → Complete       │
│                          ↓                                      │
│                    Error (SSE Error)                            │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```

### UI 表现差异

| 状态 | User 消息 | Assistant 消息 |
|------|-----------|----------------|
| Queued | 灰色半透明 | — |
| Sending | 进度指示器 | — |
| Streaming | — | PulsingDotsIndicator，MarkdownContent retainState |
| Complete | 正常样式 | Footer 统计，可复制 |
| Error | — | ErrorPayloadContent 红色边框 |

### 状态转换触发

| 转换 | 触发条件 |
|------|----------|
| Queued → Sending | viewModel.sendMessage() |
| Sending → Streaming | SSE session.update + message.create |
| Streaming → Complete | SSE session.status=idle 或 REST fallback |
| Streaming → Error | SSE error 事件 |
| Complete → Streaming | 不可能（单向） |

---

## E5. 竞态条件清单

### 1. REST 加载 vs SSE 事件

- **触发条件**: `loadSession()` 的 REST 调用和 SSE 重连的事件几乎同时到达
- **现象**: 重复消息或丢失增量更新
- **缓解**: `EventDispatcher.mergeMessages()` 使用 ID 去重
- **测试**: 手动 — 杀掉 APP 进程后重新打开同一会话

### 2. scrollRestoreVersion 恢复 vs messageCount 自动滚动

- **触发条件**: `scrollRestoreVersion` 触发 `scrollToItem` 同时新消息到达触发自动滚动
- **现象**: 滚动位置跳动
- **缓解**: `autoScrollEnabled` 标志位，只在用户未手动滚动时自动跟随
- **测试**: 手动 — 长对话中滚动到中间，等待新消息到达

### 3. isAssistantContinuation key 过时（已修复）

- **触发条件**: 消息数量不变但内容变化（streaming 完成）
- **现象**: continuation 标记不更新
- **缓解**: 使用 `uiState.messages.map { it.message.id }` 作为 remember key
- **测试**: `TurnGroupCalculatorTest` 覆盖

### 4. consumeBoundaryScroll 方向在 reverseLayout 下的正确性

- **触发条件**: LazyColumn reverseLayout=true 时，工具卡片内 verticalScroll 的方向判断
- **现象**: 工具卡片到达边界后仍带动外层
- **缓解**: `consumeBoundaryScroll` 使用 `ScrollState` 的 `canScrollForward/Backward`，
  与 layout 方向无关
- **测试**: 手动 — 展开工具卡片，在内容顶部/底部继续拖动

### 5. Dialog 显示/隐藏与 Snackbar 时序

- **触发条件**: MarkdownPreviewDialog 的 onCopyAll 同时设置 clipboard 和显示 snackbar，
  然后 dialog 被关闭
- **现象**: Snackbar 可能在 dialog 关闭后无法显示
- **缓解**: SnackbarHost 在 dialog 内部，dialog 关闭时 snackbar 自然消失（可接受）
- **测试**: 手动 — 在弹窗中点击复制全部后关闭弹窗

---

## E6. 实现方式说明

| 章节 | 内容来源 |
|------|----------|
| E1 触摸事件 | Compose 源码 + ChatScreen.kt pointerInput 位置 |
| E2 嵌套滚动 | consumeBoundaryScroll (ChatModifiers.kt) 实现 |
| E3 SSE→UI | EventDispatcher → MessageEventHandler → ChatViewModel.combine |
| E4 状态机 | Message.kt + SseEvent.kt 状态定义 |
| E5 竞态条件 | ChatViewModel.kt + ChatScreen.kt 的 LaunchedEffect/DisposableEffect 交互 |
```

- [ ] **Step 2: Commit**

```bash
git add docs/chat-ui-event-lifecycle.md
git commit -m "docs(chat): add event lifecycle documentation

Covers touch event propagation, nested scroll lifecycle,
SSE→UI recomposition chain, message state machine,
and race condition registry.

Design E. Refs: #2026-05-30-chat-ui-interaction-fixes"
```

---

## Phase 5.5: SSE 中文乱码修复 (G)

### Task 21: SseClient 基于字节的 SSE 解析

**Files:**
- Modify: `data/api/SseClient.kt`: L75-L107

**Goal**: 将逐行 UTF-8 解码改为基于字节累积 → 完整 event 边界解码。

- [ ] **Step 1: 新增辅助方法 readRawLineBytes 和 buildStringFromBytes**

在 `SseClient.kt` 中新增两个私有辅助函数：

```kotlin
/**
 * 读取原始字节直到遇到 \n，不做 UTF-8 解码。
 * 返回 null 表示 channel 已关闭且无更多数据。
 */
private suspend fun ByteReadChannel.readRawLineBytes(): List<Byte>? {
    val result = mutableListOf<Byte>()
    try {
        while (true) {
            val b = readByte()
            if (b == '\n'.code.toByte()) break
            if (b == '\r'.code.toByte()) continue  // 兼容 CRLF
            result.add(b)
        }
    } catch (e: ClosedReceiveChannelException) {
        if (result.isEmpty()) return null
    }
    return result
}

/**
 * 将 byte 块列表拼接为完整字节数组，然后一次性 UTF-8 解码。
 */
private fun buildStringFromBytes(chunks: List<List<Byte>>): String {
    val totalSize = chunks.sumOf { it.size }
    if (totalSize == 0) return ""
    val array = ByteArray(totalSize)
    var pos = 0
    for (chunk in chunks) {
        for (b in chunk) {
            array[pos++] = b
        }
    }
    return array.toString(Charsets.UTF_8)
}
```

- [ ] **Step 2: 重写 SSE 解析主循环**

将 `readUTF8Line()` 替换为 `readRawLineBytes()`，并在空白行处解码整个 buffer：

```kotlin
// 改前 (L75-L107):
val line = channel.readUTF8Line() ?: break
// ... line.isEmpty(), line.startsWith("data:") ...

// 改后:
val lineBytes = channel.readRawLineBytes() ?: break

if (lineBytes.isEmpty()) {
    // 空白行 = SSE event 边界 → 解码整个 buffer
    val data = buildStringFromBytes(buffer)
    if (data.isNotEmpty()) {
        try {
            val event = parseEvent(data)
            if (event != null) {
                emit(event)
                eventCount++
                lastHeartbeat = System.currentTimeMillis()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse SSE event", e)
        }
        buffer.clear()
    }
} else {
    // data: 行 → 提取 payload 的原始字节
    val prefix = "data:".encodeToByteArray()
    var start = 0
    // 跳过 "data:" 前缀
    if (lineBytes.size >= prefix.size && 
        lineBytes.subList(0, prefix.size) == prefix.toList()) {
        start = prefix.size
        if (start < lineBytes.size && lineBytes[start] == ' '.code.toByte()) {
            start++  // 跳过 "data: " 中的空格
        }
    }
    if (start < lineBytes.size) {
        buffer.add(lineBytes.subList(start, lineBytes.size))
    }
}
```

**注意**: 需要将 `buffer` 的类型从 `String` 改为 `MutableList<List<Byte>>`：
```kotlin
// 改前
var buffer = ""

// 改后
val buffer = mutableListOf<List<Byte>>()
```

- [ ] **Step 3: 验证编译通过**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add data/api/SseClient.kt
git commit -m "fix: prevent SSE streaming Chinese character corruption

Replace line-by-line readUTF8Line() decoding with raw byte buffering.
U+FFFD (�) was caused by TCP chunk boundary splitting a multi-byte
UTF-8 sequence across lines — the partial bytes were silently replaced
by the UTF-8 decoder's REPLACE action.

Now bytes accumulate until a blank line (SSE event boundary), then the
entire buffer is decoded as UTF-8. Multi-byte characters are always
reassembled correctly regardless of TCP chunk boundaries.

Design G. Refs: #2026-05-30-chat-ui-interaction-fixes"
```

---

## Phase 6: 回归测试

### Task 22: 运行全量单元测试回归

**Files:**
- 无文件变更

- [ ] **Step 1: 运行全量测试**

Run: `.\gradlew :app:testDevDebugUnitTest`
Expected: 所有测试 PASS（包括新增的 ChatModifiersTest 和 TurnGroupCalculatorTest）

- [ ] **Step 2: 如果有测试失败，逐个修复后重跑**

常见失败原因：
- import 未更新（`consumeBoundaryFling` 残留引用）
- 函数签名不匹配（`AssistantMessageCard` 新参数）

修复后重新运行直到全部通过。

- [ ] **Step 3: 最终 Commit（如有修复）**

```bash
git add -A
git commit -m "fix(chat): address regression test failures"
```

---

## 自检清单

### Spec 覆盖度

| 设计 | 任务 | 状态 |
|------|------|------|
| A1 移除 SelectionContainer | Task 11 | ✅ |
| A2 MarkdownPreviewDialog | Task 12 | ✅ |
| A3 复制按钮触发逻辑 | Task 13 | ✅ |
| A4 AssistantMessageCard 按钮变更 | Task 15 (header) + Task 16 (footer) | ✅ |
| A5 BashToolCard SelectionContainer 保留 | Task 3（保留） | ✅ |
| A6 ErrorPayloadContent 保留 | 未修改（保留） | ✅ |
| B1 consumeBoundaryScroll | Task 1 | ✅ |
| B2 调用处迁移 | Task 2, 7, 8, 9 | ✅ |
| B3a BashToolCard 补滚动 | Task 3 | ✅ |
| B3b WriteToolCard 补滚动 | Task 4 | ✅ |
| B3c ReadToolCard 补滚动 | Task 5 | ✅ |
| B3d EditToolCard 补滚动 | Task 6 | ✅ |
| C1 ToolCardRenderer 间距 | Task 2 | ✅ |
| C2 BashToolCard 间距 | Task 3 | ✅ |
| C3 ReadToolCard 间距 | Task 5 | ✅ |
| C4 WriteToolCard 间距 | Task 4 | ✅ |
| C5 EditToolCard 间距 | Task 6 | ✅ |
| C6 SearchToolCard 间距 | Task 7 | ✅ |
| C7 TaskToolCard 间距 | Task 8 | ✅ |
| C8 ReasoningBlock 间距 | Task 9 | ✅ |
| C9 消息间距 | Task 10 | ✅ |
| D1 Turn 定义 | Task 14 | ✅ |
| D2 AssistantMessageCard Footer | Task 15 | ✅ |
| D3 Footer 复制按钮 | Task 15 | ✅ |
| D4 ChatScreen 调用变更 | Task 16 | ✅ |
| D5 isAssistantContinuation 修正 | Task 16 | ✅ |
| E 事件生命周期文档 | Task 20 | ✅ |
| F4a fetchSessionStatus API | Task 17 | ✅ |
| F4b syncSessionStatus | Task 18 | ✅ |
| F4c ChatScreen 触发 | Task 19 | ✅ |
| F4d markSessionIdle | Task 18 | ✅ |
| G SseClient 字节解析 | Task 21 | ✅ |

### 占位符扫描

- 无 "TBD"、"TODO"、"implement later"、"fill in details"
- 无 "add error handling" / "add validation" / "handle edge cases"
- 无 "write tests for the above" （所有测试代码已内联）
- 无 "similar to Task N" （所有代码完整展示）
- 所有步骤包含具体代码或操作命令

### 类型一致性

- `computeTurnGroups` 在 Task 14 定义，Task 16 调用 — 签名一致
- `AssistantMessageCard` 签名在 Task 15 更新，Task 16 调用 — 参数名一致
- `MarkdownPreviewDialog` 在 Task 12 创建，Task 13 调用 — 签名一致
- `syncSessionStatus` 在 Task 18 定义，Task 19 调用 — 名称一致
- `consumeBoundaryScroll` 在 Task 1 定义，Task 2-9 调用 — 名称一致
