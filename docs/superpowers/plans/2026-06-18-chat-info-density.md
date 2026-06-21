# 聊天界面信息密度增强 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 OC Remote 聊天界面增加工具调用聚合展示、增强上下文用量弹窗，并做顶部栏/气泡的配套调整。

**Architecture:** 三个独立但相关的功能，分三个 Phase 顺序实现。功能 A（聚合）是纯 UI 层展示变形；功能 B（弹窗）走 ViewModel state + 纯函数计算；功能 C（配套）是小幅 UI 调整。复用现有的 turn 分组（`computeTurnGroups`）、工具展开机制（`collapseTools` + `expandedStates`）、token 数据源（`Part.StepFinish.Tokens`）、compact 格式化（`formatTokenCount`）。

**Tech Stack:** Kotlin + Jetpack Compose + Hilt + Material3。测试：JUnit4 + MockK + Turbine + `createComposeRule()`。

## Global Constraints

- **JDK 21**，`jvmToolchain(21)`
- **Material3 First**：优先 `LinearProgressIndicator` / `Surface` / `Text` 等原生组件，禁止引入 Accompanist 等 UI 依赖库
- **Theme Token System**：alpha 用 `AlphaTokens.*`（FAINT/MUTED/MEDIUM/HIGH），间距用 `SpacingTokens.*.dp`，shape 用 `ShapeTokens.*`
- **编译检查**：`.\gradlew :app:compileDevDebugKotlin`（超时 120s）
- **单元测试**：`.\gradlew :app:testDevDebugUnitTest --rerun`（超时 180s）
- **Gradle Daemon**：已设 `org.gradle.daemon=false`；卡住时跑 `.\gradlew --stop`
- **本地化**：15 locales，改完 `strings.xml` 跑 `lokit` 同步翻译
- **代理警告**：`gradle.properties` 硬编码 `127.0.0.1:7897` 代理；无代理时注释掉 4 行 `systemProp.*`
- **设计依据**：`docs/superpowers/specs/2026-06-18-chat-info-density-design.md`
- **参考实现**：opencode webui（`anomalyco/opencode`，TS）的 `message-part.tsx` / `session-context-breakdown.ts`

---

# Phase 1: 工具调用聚合（功能 A）

> 把主对话流里连续的 read/glob/grep/list 工具合并成可展开卡片。对齐 opencode webui 的 `groupParts` + `CONTEXT_GROUP_TOOLS`。

## Task 1: `groupContextTools` 纯函数 + 单元测试

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/util/PartGrouping.kt`
- Test: `app/src/test/kotlin/dev/minios/ocremote/ui/screens/chat/util/PartGroupingTest.kt`

**Interfaces:**
- Consumes: `dev.leonardo.ocremotev2.domain.model.Part`（现有 sealed class，`Part.Tool` 有 `tool: String`）
- Produces: `PartRenderUnit`（sealed）、`groupContextTools(parts): List<PartRenderUnit>`、`ContextToolSummary`、`contextToolSummary(tools): ContextToolSummary`、`CONTEXT_GROUP_TOOLS: Set<String>`

- [ ] **Step 1: 写失败测试**

`app/src/test/kotlin/dev/minios/ocremote/ui/screens/chat/util/PartGroupingTest.kt`:

```kotlin
package dev.leonardo.ocremotev2.ui.screens.chat.util

import dev.leonardo.ocremotev2.domain.model.Part
import dev.leonardo.ocremotev2.domain.model.ToolState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PartGroupingTest {

    private fun tool(id: String, name: String) = Part.Tool(
        id = id, sessionId = "s", messageId = "m", callId = id, tool = name,
        state = ToolState.Completed(output = "")
    )

    private fun text(id: String) = Part.Text(id, "s", "m", "hello")

    @Test fun `empty list returns empty`() {
        assertTrue(groupContextTools(emptyList()).isEmpty())
    }

    @Test fun `single context tool forms a group`() {
        val result = groupContextTools(listOf(tool("t1", "read")))
        assertEquals(1, result.size)
        assertTrue(result[0] is PartRenderUnit.ContextGroup)
    }

    @Test fun `consecutive context tools merge into one group`() {
        val parts = listOf(tool("t1", "read"), tool("t2", "glob"), tool("t3", "grep"))
        val result = groupContextTools(parts)
        assertEquals(1, result.size)
        val group = result[0] as PartRenderUnit.ContextGroup
        assertEquals(3, group.tools.size)
    }

    @Test fun `non-context tool breaks the group`() {
        val parts = listOf(
            tool("t1", "read"), tool("t2", "bash"), tool("t3", "read")
        )
        val result = groupContextTools(parts)
        assertEquals(3, result.size)  // group(t1) + single(bash) + group(t3)
        assertTrue(result[0] is PartRenderUnit.ContextGroup)
        assertTrue(result[1] is PartRenderUnit.Single)
        assertTrue(result[2] is PartRenderUnit.ContextGroup)
    }

    @Test fun `all 4 context tool types recognized`() {
        val parts = listOf(
            tool("a", "read"), tool("b", "glob"), tool("c", "grep"), tool("d", "list")
        )
        val result = groupContextTools(parts)
        assertEquals(1, result.size)
        assertEquals(4, (result[0] as PartRenderUnit.ContextGroup).tools.size)
    }

    @Test fun `non-context tools are singles`() {
        val parts = listOf(tool("t1", "bash"), tool("t2", "edit"))
        val result = groupContextTools(parts)
        assertEquals(2, result.size)
        assertTrue(result.all { it is PartRenderUnit.Single })
    }

    @Test fun `summary counts read_search_list correctly`() {
        val tools = listOf(
            tool("a", "read"), tool("b", "read"),
            tool("c", "glob"), tool("d", "grep"),
            tool("e", "list")
        )
        val s = contextToolSummary(tools)
        assertEquals(2, s.read)
        assertEquals(2, s.search)   // glob + grep
        assertEquals(1, s.list)
    }
}
```

- [ ] **Step 2: 跑测试验证失败**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun --tests "*PartGroupingTest*"`
Expected: FAIL（`groupContextTools` 未定义）

- [ ] **Step 3: 写实现**

`app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/util/PartGrouping.kt`:

```kotlin
package dev.leonardo.ocremotev2.ui.screens.chat.util

import dev.leonardo.ocremotev2.domain.model.Part

/** 探查类工具集合 —— 连续出现时聚合展示。对齐 opencode CONTEXT_GROUP_TOOLS。 */
val CONTEXT_GROUP_TOOLS: Set<String> = setOf("read", "glob", "grep", "list")

/** parts 列表的可渲染单元：单个 part 或一组连续探查工具。 */
sealed class PartRenderUnit {
    data class Single(val part: Part) : PartRenderUnit()
    data class ContextGroup(val tools: List<Part.Tool>) : PartRenderUnit()
}

/**
 * 把 parts 列表分组：连续的探查工具合并成 [PartRenderUnit.ContextGroup]，其余各自成 [PartRenderUnit.Single]。
 * 单个探查工具也成组。对应 opencode 的 groupParts + isContextGroupTool。
 */
fun groupContextTools(parts: List<Part>): List<PartRenderUnit> {
    val result = mutableListOf<PartRenderUnit>()
    var start = -1

    fun flush(end: Int) {
        if (start < 0) return
        val group = parts.subList(start, end + 1).filterIsInstance<Part.Tool>()
        if (group.isNotEmpty()) {
            result.add(PartRenderUnit.ContextGroup(group))
        }
        start = -1
    }

    for (i in parts.indices) {
        val part = parts[i]
        if (part is Part.Tool && part.tool in CONTEXT_GROUP_TOOLS) {
            if (start < 0) start = i
        } else {
            flush(i - 1)
            result.add(PartRenderUnit.Single(part))
        }
    }
    flush(parts.lastIndex)
    return result
}

/** 探查工具分类计数：read / search(glob+grep) / list。 */
data class ContextToolSummary(val read: Int, val search: Int, val list: Int)

/** 计算一组探查工具的分类计数。 */
fun contextToolSummary(tools: List<Part.Tool>): ContextToolSummary {
    var read = 0
    var search = 0
    var list = 0
    for (t in tools) {
        when (t.tool) {
            "read" -> read++
            "glob", "grep" -> search++
            "list" -> list++
        }
    }
    return ContextToolSummary(read, search, list)
}
```

- [ ] **Step 4: 跑测试验证通过**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun --tests "*PartGroupingTest*"`
Expected: PASS（7 tests）

- [ ] **Step 5: 编译检查 + Commit**

```bash
.\gradlew :app:compileDevDebugKotlin
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/util/PartGrouping.kt \
        app/src/test/kotlin/dev/minios/ocremote/ui/screens/chat/util/PartGroupingTest.kt
git commit -m "feat(chat): add groupContextTools pure function for tool aggregation"
```

---

## Task 2: 本地化字符串（聚合卡）

**Files:**
- Modify: `app/src/main/res/values/strings.xml`（+ 后续 `lokit` 同步）

**Interfaces:** Produces 字符串 id：`chat_context_gathering` / `chat_context_gathered` / `chat_context_count_read` / `chat_context_count_search` / `chat_context_count_list`

- [ ] **Step 1: 添加字符串**

在 `strings.xml` 合适位置（chat 相关字符串区）添加：

```xml
<!-- Tool aggregation card -->
<string name="chat_context_gathering">正在收集上下文</string>
<string name="chat_context_gathered">已收集上下文</string>
<string name="chat_context_count_read">%1$d 次读取</string>
<string name="chat_context_count_search">%1$d 次搜索</string>
<string name="chat_context_count_list">%1$d 次列表</string>
```

- [ ] **Step 2: 编译验证 + Commit**

```bash
.\gradlew :app:compileDevDebugKotlin
git add app/src/main/res/values/strings.xml
git commit -m "i18n(chat): add tool aggregation card strings"
```

- [ ] **Step 3: 同步翻译（可延后到 Phase 末尾批量执行）**

Run: `lokit`（项目根目录，按 `lokit.yaml` 同步 15 locales）

---

## Task 3: `ContextToolGroupCard` 组件

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/ContextToolGroupCard.kt`

**Interfaces:**
- Consumes: `PartRenderUnit.ContextGroup`（Task 1）、`contextToolSummary`（Task 1）、`LocalCollapseTools` / `LocalToolExpandedStates` / `LocalOnToggleToolExpanded` / `LocalToolCardResolver`（现有 CompositionLocals）、`ToolState`（现有）
- Produces: `@Composable ContextToolGroupCard(tools: List<Part.Tool>, onViewSubSession: ((String) -> Unit)?, turnAgentName: String?)`

**设计要点：**
- 折叠态：`Surface` + 标题（gathering/gathered 文案）+ 计数摘要（read/search/list，值为 0 的省略）+ 展开箭头
- 展开态：标题 + 计数 + 每个 tool 经 `LocalToolCardResolver.current.resolve(...)` 渲染（复用现有 ReadToolCard/GlobToolCard 等）
- 展开状态：key = `context:${tools.first().id}`，默认值 = `LocalCollapseTools.current`，存入现有 `expandedStates`
- 组内有 `ToolState.Running` → 显示"正在收集上下文" + 加载指示；否则"已收集上下文"
- Material3：用 `Surface`/`Text`/`Icon`；alpha 用 `AlphaTokens`；shape 用 `ShapeTokens`

- [ ] **Step 1: 写组件骨架**

`app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/ContextToolGroupCard.kt`:

```kotlin
package dev.leonardo.ocremotev2.ui.screens.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leonardo.ocremotev2.R
import dev.leonardo.ocremotev2.domain.model.Part
import dev.leonardo.ocremotev2.domain.model.ToolState
import dev.leonardo.ocremotev2.ui.screens.chat.util.LocalCollapseTools
import dev.leonardo.ocremotev2.ui.screens.chat.util.LocalOnToggleToolExpanded
import dev.leonardo.ocremotev2.ui.screens.chat.util.LocalToolCardResolver
import dev.leonardo.ocremotev2.ui.screens.chat.util.LocalToolExpandedStates
import dev.leonardo.ocremotev2.ui.screens.chat.util.contextToolSummary
import dev.leonardo.ocremotev2.ui.theme.AlphaTokens
import dev.leonardo.ocremotev2.ui.theme.ShapeTokens
import dev.leonardo.ocremotev2.ui.theme.SpacingTokens

@Composable
internal fun ContextToolGroupCard(
    tools: List<Part.Tool>,
    onViewSubSession: ((String) -> Unit)? = null,
    turnAgentName: String? = null
) {
    val groupKey = remember(tools) { "context:${tools.first().id}" }
    val defaultOpen = LocalCollapseTools.current
    val expandedStates = LocalToolExpandedStates.current
    val onToggle = LocalOnToggleToolExpanded.current
    val isExpanded = expandedStates[groupKey] ?: defaultOpen

    val summary = remember(tools) { contextToolSummary(tools) }
    val isRunning = remember(tools) { tools.any { it.state is ToolState.Running } }
    val titleRes = if (isRunning) R.string.chat_context_gathering else R.string.chat_context_gathered

    // 计数摘要：值为 0 的省略，用 " · " 串联
    val summaryText = remember(summary) {
        listOfNotNull(
            if (summary.read > 0) stringResourceSafe(R.string.chat_context_count_read, summary.read) else null,
            if (summary.search > 0) stringResourceSafe(R.string.chat_context_count_search, summary.search) else null,
            if (summary.list > 0) stringResourceSafe(R.string.chat_context_count_list, summary.list) else null,
        ).joinToString(" · ")
    }

    Surface(
        shape = ShapeTokens.smallMedium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(SpacingTokens.SM.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle(groupKey, defaultOpen) }
                    .padding(vertical = SpacingTokens.XS.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp)
            ) {
                if (isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = stringResource(titleRes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (summaryText.isNotEmpty()) {
                    Text(
                        text = summaryText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED)
                    )
                }
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
                    modifier = Modifier.size(16.dp)
                )
            }
            AnimatedVisibility(visible = isExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp)) {
                    tools.forEach { tool ->
                        key(tool.id) {
                            val autoExpand = LocalCollapseTools.current
                            val toolExpanded = expandedStates[tool.id] ?: autoExpand
                            val resolved = LocalToolCardResolver.current.resolve(
                                tool = tool,
                                isExpanded = toolExpanded,
                                onToggleExpand = { onToggle(tool.id, autoExpand) },
                                onViewSubSession = onViewSubSession,
                                turnAgentName = turnAgentName
                            )
                            resolved?.invoke()
                        }
                    }
                }
            }
        }
    }
}

// 辅助：在非 @Composable 上下文取字符串的折中——实际实现时把 summaryText 计算移到 @Composable 作用域，
// 或直接在 Row 内用条件 Text 组合（推荐后者，避免此 helper）。
@Composable
private fun stringResourceSafe(id: Int, vararg args: Any): String = stringResource(id, *args)
```

> **实现注意**：上面 `summaryText` 用了 `stringResourceSafe` 在 `remember` 块里调 `stringResource`（Composable）——这在 `remember` lambda 内不合法。**实际实现时改为直接在 Row 里用条件 `Text` 组合**（读 read/search/list 各一个 `if (count>0) Text(...)`），不要用 remember 缓存字符串。修正后的骨架：

```kotlin
// 替换 summaryText 那段，直接在 Row 内渲染：
if (summary.read > 0) Text(stringResource(R.string.chat_context_count_read, summary.read), ...)
if (summary.search > 0) Text(stringResource(R.string.chat_context_count_search, summary.search), ...)
if (summary.list > 0) Text(stringResource(R.string.chat_context_count_list, summary.list), ...)
```

- [ ] **Step 2: 编译检查**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL。若 `ShapeTokens.smallMedium` 不存在，改用现有的 `ShapeTokens.small` 或 `medium`（查 `ui/theme/Shape.kt`）。

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/ContextToolGroupCard.kt
git commit -m "feat(chat): add ContextToolGroupCard composable"
```

---

## Task 4: 接入 AssistantTurnBubble 渲染循环

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/AssistantTurnBubble.kt:168-183`

**Interfaces:**
- Consumes: `groupContextTools` / `PartRenderUnit`（Task 1）、`ContextToolGroupCard`（Task 3）

**当前代码**（L168-183）：
```kotlin
// Render all messages' parts in original order (text, tool, reasoning interleaved)
for ((renderableParts, errorPair) in allContent) {
    val (errorText, assistantMsg) = errorPair
    for (part in renderableParts) {
        key(part.id) {
            PartContent(part = part, textColor = textColor, isUser = false, ...)
        }
    }
    ...error display...
}
```

- [ ] **Step 1: 改内层循环为聚合渲染**

把内层 `for (part in renderableParts)` 替换为遍历 `groupContextTools(renderableParts)`：

```kotlin
// Render all messages' parts in original order (text, tool, reasoning interleaved)
for ((renderableParts, errorPair) in allContent) {
    val (errorText, assistantMsg) = errorPair

    for (unit in groupContextTools(renderableParts)) {
        when (unit) {
            is PartRenderUnit.Single -> {
                key(unit.part.id) {
                    PartContent(
                        part = unit.part,
                        textColor = textColor,
                        isUser = false,
                        onViewSubSession = onViewSubSession,
                        turnAgentName = if (unit.part is Part.Tool && unit.part.tool == "task") taskAgentNames[unit.part.id] else null
                    )
                }
            }
            is PartRenderUnit.ContextGroup -> {
                key(unit.tools.first().id) {
                    ContextToolGroupCard(
                        tools = unit.tools,
                        onViewSubSession = onViewSubSession,
                        turnAgentName = null
                    )
                }
            }
        }
    }

    // Error display（保持不变）
    ...
}
```

- [ ] **Step 2: 添加 import**

`AssistantTurnBubble.kt` 顶部添加：
```kotlin
import dev.leonardo.ocremotev2.ui.screens.chat.util.PartRenderUnit
import dev.leonardo.ocremotev2.ui.screens.chat.util.groupContextTools
```

- [ ] **Step 3: 编译检查**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 手动/Instrumented 验证**

启动 app（dev debug），在一个会产生连续 read/glob/grep 的会话里观察：探查工具应合并成一张可展开卡片。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/AssistantTurnBubble.kt
git commit -m "feat(chat): aggregate consecutive context tools in AssistantTurnBubble"
```

**Phase 1 验收**：`compileDevDebugKotlin` ✅ + `testDevDebugUnitTest` ✅ + 连续探查工具在 UI 合并展示 ✅

---

# Phase 2: 上下文用量弹窗增强（功能 B）

> 增强顶部栏环形进度条点击后的弹窗：加上下文构成、消息计数、provider/model、时间戳、缓存命中率。

## Task 5: `ContextDetailState` + 计算纯函数 + 单元测试

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/util/ContextStats.kt`
- Test: `app/src/test/kotlin/dev/minios/ocremote/ui/screens/chat/util/ContextStatsTest.kt`

**Interfaces:**
- Consumes: `Part`（含 `Part.Text`/`Reasoning`/`Tool`/`StepFinish`/`File`/`Agent`）、`ToolState.Completed.output`、`Message`（role）、`Session.time`
- Produces: `ContextDetailState`、`ContextBreakdown`、`BreakdownRole`、`estimateContextBreakdown(messages, realInput)`、`countMessages(messages)`、`cacheHitRate(cacheRead, input)`

- [ ] **Step 1: 写失败测试**

`app/src/test/kotlin/dev/minios/ocremote/ui/screens/chat/util/ContextStatsTest.kt`:

```kotlin
package dev.leonardo.ocremotev2.ui.screens.chat.util

import dev.leonardo.ocremotev2.domain.model.Message
import dev.leonardo.ocremotev2.domain.model.Part
import dev.leonardo.ocremotev2.domain.model.ToolState
import org.junit.Assert.assertEquals
import org.junit.Test

class ContextStatsTest {

    private fun userMsg(id: String, textLen: Int) = MessageWithParts(
        Message.User(id = id, role = "user", content = emptyList(),
            time = dev.leonardo.ocremotev2.domain.model.Message.User.Time(created = 0)),
        listOf(Part.Text("p-$id", "s", id, "x".repeat(textLen)))
    )

    private fun assistantMsg(id: String, textLen: Int = 0, toolOutputLen: Int = 0) = MessageWithParts(
        // 按 Message.Assistant 实际构造调整；这里示意
        Message.Assistant(id, "assistant"),
        buildList {
            if (textLen > 0) add(Part.Text("t-$id", "s", id, "y".repeat(textLen)))
            if (toolOutputLen > 0) add(Part.Tool("tl-$id", "s", id, "c-$id", "read",
                ToolState.Completed(output = "z".repeat(toolOutputLen))))
        }
    )

    @Test fun `breakdown estimates tokens as chars divided by 4`() {
        // user text 40 chars -> 10 tokens
        val msgs = listOf(userMsg("u1", 40))
        val b = estimateContextBreakdown(msgs, realInput = 100)
        val userSeg = b.segments.first { it.role == BreakdownRole.USER }
        assertEquals(10, userSeg.estimatedTokens)
    }

    @Test fun `other absorbs difference from real input`() {
        val msgs = listOf(userMsg("u1", 40))  // 10 tokens user
        val b = estimateContextBreakdown(msgs, realInput = 100)
        val otherSeg = b.segments.first { it.role == BreakdownRole.OTHER }
        assertEquals(90, otherSeg.estimatedTokens)  // 100 - 10
    }

    @Test fun `other is zero when estimate exceeds input`() {
        val msgs = listOf(userMsg("u1", 4000))  // 1000 tokens
        val b = estimateContextBreakdown(msgs, realInput = 100)
        val otherSeg = b.segments.firstOrNull { it.role == BreakdownRole.OTHER }
        // other = max(0, 100 - 1000) = 0 -> 该段被过滤掉
        assertEquals(null, otherSeg)
    }

    @Test fun `percent is tokens over real input`() {
        val msgs = listOf(userMsg("u1", 40))
        val b = estimateContextBreakdown(msgs, realInput = 200)
        val userSeg = b.segments.first { it.role == BreakdownRole.USER }
        assertEquals(0.05f, userSeg.percent, 0.001f)  // 10/200
    }

    @Test fun `countMessages splits user and assistant`() {
        val msgs = listOf(userMsg("u1", 1), assistantMsg("a1"), assistantMsg("a2"))
        val c = countMessages(msgs.map { it.info })
        assertEquals(1, c.user)
        assertEquals(2, c.assistant)
    }

    @Test fun `cacheHitRate is cacheRead over input`() {
        assertEquals(0.5f, cacheHitRate(cacheRead = 50, input = 100), 0.001f)
    }
}
```

> **注意**：测试里的 `Message.User`/`Message.Assistant` 构造需对照 `domain/model/Message.kt` 实际签名调整（必填字段、Time 等）。实现时先读 Message.kt 确认。

- [ ] **Step 2: 跑测试验证失败**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun --tests "*ContextStatsTest*"`
Expected: FAIL（符号未定义）

- [ ] **Step 3: 写实现**

`app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/util/ContextStats.kt`:

```kotlin
package dev.leonardo.ocremotev2.ui.screens.chat.util

import dev.leonardo.ocremotev2.domain.model.Message
import dev.leonardo.ocremotev2.domain.model.MessageWithParts
import dev.leonardo.ocremotev2.domain.model.Part
import dev.leonardo.ocremotev2.domain.model.ToolState
import kotlin.math.ceil

enum class BreakdownRole { USER, ASSISTANT, TOOL, OTHER }

data class ContextBreakdownSegment(
    val role: BreakdownRole,
    val estimatedTokens: Int,
    val percent: Float
)

data class ContextBreakdown(val segments: List<ContextBreakdownSegment>)

data class MessageCount(val user: Int, val assistant: Int)

data class ProviderModel(val providerId: String?, val modelId: String?)

data class SessionTimestamps(val created: Long, val updated: Long)

data class ContextDetailState(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val reasoningTokens: Int = 0,
    val cacheReadTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
    val totalCost: Double = 0.0,
    val contextWindow: Int = 0,
    val contextTokens: Int = 0,
    val messageCount: MessageCount? = null,
    val providerModel: ProviderModel? = null,
    val timestamps: SessionTimestamps? = null,
    val cacheHitRate: Float? = null,
    val breakdown: ContextBreakdown? = null
)

private fun estimateTokens(chars: Int): Int = if (chars == 0) 0 else ceil(chars / 4.0).toInt()

/** user part 字符数 */
private fun charsFromUserPart(part: Part): Int = when (part) {
    is Part.Text -> part.text.length
    is Part.File -> part.source?.toString()?.length ?: 0
    is Part.Agent -> part.source?.toString()?.length ?: 0
    else -> 0
}

/** assistant part 字符数 -> (assistantChars, toolChars) */
private fun charsFromAssistantPart(part: Part): Pair<Int, Int> = when (part) {
    is Part.Text -> part.text.length to 0
    is Part.Reasoning -> part.text.length to 0
    is Part.Tool -> 0 to ((part.state as? ToolState.Completed)?.output?.length ?: 0)
    else -> 0 to 0
}

/**
 * 估算上下文按角色拆分。对应 opencode estimateSessionContextBreakdown。
 * OC Remote 没有 system prompt，故无 system 桶，差额归 OTHER（含 system 等）。
 *
 * @param realInput 真实 input token（来自最后 StepFinish.Tokens.input）
 */
fun estimateContextBreakdown(messages: List<MessageWithParts>, realInput: Int): ContextBreakdown {
    var userChars = 0
    var assistantChars = 0
    var toolChars = 0

    for (msg in messages) {
        val role = msg.info.role
        for (part in msg.parts) {
            when (role) {
                "user" -> userChars += charsFromUserPart(part)
                "assistant" -> {
                    val (a, t) = charsFromAssistantPart(part)
                    assistantChars += a
                    toolChars += t
                }
            }
        }
    }

    val userTokens = estimateTokens(userChars)
    val assistantTokens = estimateTokens(assistantChars)
    val toolTokens = estimateTokens(toolChars)
    val estimated = userTokens + assistantTokens + toolTokens
    val otherTokens = (realInput - estimated).coerceAtLeast(0)

    fun pct(tokens: Int) = if (realInput <= 0) 0f else tokens.toFloat() / realInput

    val segments = listOf(
        ContextBreakdownSegment(BreakdownRole.USER, userTokens, pct(userTokens)),
        ContextBreakdownSegment(BreakdownRole.ASSISTANT, assistantTokens, pct(assistantTokens)),
        ContextBreakdownSegment(BreakdownRole.TOOL, toolTokens, pct(toolTokens)),
        ContextBreakdownSegment(BreakdownRole.OTHER, otherTokens, pct(otherTokens))
    ).filter { it.estimatedTokens > 0 }

    return ContextBreakdown(segments)
}

/** 统计 user/assistant 消息数 */
fun countMessages(messages: List<Message>): MessageCount {
    var user = 0
    var assistant = 0
    for (m in messages) {
        when (m.role) {
            "user" -> user++
            "assistant" -> assistant++
        }
    }
    return MessageCount(user, assistant)
}

/** 缓存命中率 = cacheRead / input */
fun cacheHitRate(cacheRead: Int, input: Int): Float? =
    if (input <= 0) null else cacheRead.toFloat() / input
```

- [ ] **Step 4: 跑测试验证通过**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun --tests "*ContextStatsTest*"`
Expected: PASS（按 Message 实际构造修正测试后）

- [ ] **Step 5: Commit**

```bash
.\gradlew :app:compileDevDebugKotlin
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/util/ContextStats.kt \
        app/src/test/kotlin/dev/minios/ocremote/ui/screens/chat/util/ContextStatsTest.kt
git commit -m "feat(chat): add ContextDetailState and breakdown estimation"
```

---

## Task 6: 本地化字符串（弹窗）

**Files:**
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: 添加字符串**

```xml
<!-- Context detail dialog -->
<string name="chat_context_composition">上下文构成（估算）</string>
<string name="chat_context_other_note">其余（含 system 等）</string>
<string name="chat_role_user">用户</string>
<string name="chat_role_assistant">助手</string>
<string name="chat_role_tool">工具调用</string>
<string name="chat_role_other">其余</string>
<string name="chat_context_msg_summary">消息 %1$d（用户 %2$d · 助手 %3$d）</string>
<string name="chat_context_cache_hit">缓存命中 %1$d%%</string>
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/res/values/strings.xml
git commit -m "i18n(chat): add context detail dialog strings"
```

---

## Task 7: `ContextDetailDialog` 组件

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/ContextDetailDialog.kt`

**Interfaces:**
- Consumes: `ContextDetailState`（Task 5）、`formatTokenCount`（现有 `ChatFormatters.kt`）、`TokenUsageCard`（现有，保留 token 明细分区）
- Produces: `@Composable ContextDetailDialog(state: ContextDetailState?, onDismiss: () -> Unit)`

**设计要点：**
- `BasicAlertDialog` + `Surface`（复用 `amoledDialogParams()`，参考现有 `ChatTopBar.kt:160-198`）
- 布局分区：① provider/model + 时间戳 ② 进度条（LinearProgressIndicator）③ 消息计数 + 缓存命中率 ④ breakdown 纵向列表（每行：色块+角色+`formatTokenCount(tokens)`+百分比+mini `LinearProgressIndicator`）⑤ Token 明细（复用 `TokenUsageCard`）
- 数据为 null 时优雅省略
- mini 条进度 = `segment.percent`，颜色按角色（user=primary, assistant=secondary, tool=tertiary, other=outline）
- 时间戳用现有日期格式化（参考项目其他地方，如 `SimpleDateFormat` 或 luxon 等价）

- [ ] **Step 1: 写组件**

```kotlin
package dev.leonardo.ocremotev2.ui.screens.chat.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.leonardo.ocremotev2.R
import dev.leonardo.ocremotev2.ui.components.DialogButtonRole
import dev.leonardo.ocremotev2.ui.components.DialogButtons
import dev.leonardo.ocremotev2.ui.components.amoledDialogParams
import dev.leonardo.ocremotev2.ui.screens.chat.util.BreakdownRole
import dev.leonardo.ocremotev2.ui.screens.chat.util.ContextDetailState
import dev.leonardo.ocremotev2.ui.screens.chat.util.formatTokenCount
import dev.leonardo.ocremotev2.ui.theme.AlphaTokens
import dev.leonardo.ocremotev2.ui.theme.SpacingTokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun ContextDetailDialog(state: ContextDetailState?, onDismiss: () -> Unit) {
    if (state == null) return
    val params = amoledDialogParams()
    BasicAlertDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f),
            color = params.containerColor, tonalElevation = params.tonalElevation,
            border = params.border, shape = params.shape
        ) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp)) {
                Text(stringResource(R.string.chat_context_detail_title), style = MaterialTheme.typography.titleMedium)

                // ① provider/model + 时间戳
                state.providerModel?.let { pm ->
                    val label = listOfNotNull(pm.providerId, pm.modelId).joinToString(" · ")
                    if (label.isNotBlank()) Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.timestamps?.let { ts ->
                    val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                    Text("创建 ${fmt.format(Date(ts.created))} · 活动 ${fmt.format(Date(ts.updated))}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED))
                }

                // ② 进度条
                if (state.contextWindow > 0 && state.contextTokens > 0) {
                    val progress = (state.contextTokens.toFloat() / state.contextWindow).coerceIn(0f, 1f)
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(4.dp))
                    Text("${formatTokenCount(state.contextTokens)} / ${formatTokenCount(state.contextWindow)}  (${(progress*100).toInt()}%)",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED))
                }

                // ③ 消息计数 + 缓存命中率
                state.messageCount?.let { mc ->
                    Text(stringResource(R.string.chat_context_msg_summary, mc.user + mc.assistant, mc.user, mc.assistant),
                        style = MaterialTheme.typography.bodySmall)
                }
                state.cacheHitRate?.let { rate ->
                    Text(stringResource(R.string.chat_context_cache_hit, (rate * 100).toInt()),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED))
                }

                // ④ breakdown 纵向列表
                state.breakdown?.let { bd ->
                    if (bd.segments.isNotEmpty()) {
                        Spacer(Modifier.height(SpacingTokens.XS.dp))
                        Text(stringResource(R.string.chat_context_composition), style = MaterialTheme.typography.labelMedium)
                        bd.segments.forEach { seg ->
                            val roleLabel = when (seg.role) {
                                BreakdownRole.USER -> stringResource(R.string.chat_role_user)
                                BreakdownRole.ASSISTANT -> stringResource(R.string.chat_role_assistant)
                                BreakdownRole.TOOL -> stringResource(R.string.chat_role_tool)
                                BreakdownRole.OTHER -> stringResource(R.string.chat_context_other_note)
                            }
                            val barColor = when (seg.role) {
                                BreakdownRole.USER -> MaterialTheme.colorScheme.primary
                                BreakdownRole.ASSISTANT -> MaterialTheme.colorScheme.secondary
                                BreakdownRole.TOOL -> MaterialTheme.colorScheme.tertiary
                                BreakdownRole.OTHER -> MaterialTheme.colorScheme.outline
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text(roleLabel, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                Text(formatTokenCount(seg.estimatedTokens), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(SpacingTokens.SM.dp))
                                Text("${(seg.percent * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED))
                                Spacer(Modifier.width(SpacingTokens.SM.dp))
                                LinearProgressIndicator(
                                    progress = { seg.percent.coerceIn(0f, 1f) },
                                    modifier = Modifier.width(48.dp).height(4.dp),
                                    color = barColor,
                                    trackColor = barColor.copy(alpha = AlphaTokens.FAINT)
                                )
                            }
                        }
                    }
                }

                // ⑤ Token 明细（复用现有）
                Spacer(Modifier.height(SpacingTokens.XS.dp))
                TokenUsageCard(
                    inputTokens = state.inputTokens, outputTokens = state.outputTokens,
                    reasoningTokens = state.reasoningTokens, cacheReadTokens = state.cacheReadTokens,
                    cacheWriteTokens = state.cacheWriteTokens, totalCost = state.totalCost,
                    contextWindow = state.contextWindow, contextTokens = state.contextTokens
                )

                Spacer(Modifier.height(SpacingTokens.SM.dp))
                DialogButtons(buttons = listOf(
                    Triple(stringResource(R.string.close), DialogButtonRole.Primary) { onDismiss() }
                ))
            }
        }
    }
}
```

- [ ] **Step 2: 编译检查 + Commit**

```bash
.\gradlew :app:compileDevDebugKotlin
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/ContextDetailDialog.kt
git commit -m "feat(chat): add ContextDetailDialog with breakdown and metrics"
```

---

## Task 8: ViewModel 暴露 `ContextDetailState` + 接入弹窗

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt`（新增 `contextDetailState`）
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/ChatTopBar.kt`（弹窗改用 `ContextDetailDialog`）
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt`（传参）

**设计要点：**
- `ChatViewModel` 新增 `val contextDetailState: StateFlow<ContextDetailState>` —— 从 `messages` + `tokenStatsTracker` + `session` 合成
- 合成逻辑：取最后带 tokens 的 assistant message → metrics；遍历所有 `MessageWithParts` → breakdown / messageCount；`session.time` → timestamps；最后 assistant msg 的 providerId/modelId → providerModel
- 用 `map { ... }.stateIn(viewModelScope, SharingStarted.Eagerly, ContextDetailState())`，参考现有 `tokenStatsState` 写法（`ChatViewModel.kt:758`）

- [ ] **Step 1: ViewModel 新增 state**

在 `ChatViewModel.kt` 加（参考 `tokenStatsState` 的合成模式）：

```kotlin
val contextDetailState: StateFlow<ContextDetailState> =
    combine(messages, tokenStatsTracker.stats, session) { msgs, stats, sess ->
        buildContextDetailState(msgs, stats, sess)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ContextDetailState())

private fun buildContextDetailState(
    messages: List<ChatMessage>,
    stats: TokenStatsState,
    session: Session?
): ContextDetailState {
    val lastAssistantWithTokens = messages.lastOrNull { it.message.role == "assistant" }
        ?.let { it to (it.parts.filterIsInstance<Part.StepFinish>().lastOrNull()?.tokens) }
    val realInput = lastAssistantWithTokens?.second?.input ?: 0
    val breakdown = if (realInput > 0) estimateContextBreakdown(messages, realInput) else null
    val messageCount = countMessages(messages.map { it.message })
    val providerModel = lastAssistantWithTokens?.first?.message?.let {
        ProviderModel((it as? Message.Assistant)?.providerId, (it as? Message.Assistant)?.modelId)
    }
    val timestamps = session?.time?.let { SessionTimestamps(it.created, it.updated) }
    val cacheHitRate = cacheHitRate(stats.cacheReadTokens, stats.inputTokens)
    return ContextDetailState(
        inputTokens = stats.inputTokens, outputTokens = stats.outputTokens,
        reasoningTokens = stats.reasoningTokens, cacheReadTokens = stats.cacheReadTokens,
        cacheWriteTokens = stats.cacheWriteTokens, totalCost = stats.totalCost,
        contextWindow = stats.contextWindow, contextTokens = realInput,
        messageCount = messageCount, providerModel = providerModel,
        timestamps = timestamps, cacheHitRate = cacheHitRate, breakdown = breakdown
    )
}
```

> 注意：`messages`/`session`/`stats` 的实际流名称与字段需对照 `ChatViewModel.kt` 现有定义调整（`TokenStatsState` 字段名、`ChatMessage` 结构等）。

- [ ] **Step 2: ChatTopBar 弹窗改用 ContextDetailDialog**

`ChatTopBar.kt`：
- 新增参数 `contextDetail: ContextDetailState?`、`directory: String`
- 移除参数 `totalInputTokens / totalOutputTokens / totalReasoningTokens / totalCacheReadTokens / totalCacheWriteTokens / totalCost / messageCount`（这些已进 state）
- 保留 `contextWindow / lastContextTokens`（环形进度条用）
- `showContextDialog` 块内：把现有 `TokenUsageCard(...)` 替换为 `ContextDetailDialog(state = contextDetail, onDismiss = { showContextDialog = false })`
- 副标题 Text 改为渲染 `directory`（空则隐藏）

- [ ] **Step 3: ChatScreen 传参**

`ChatScreen.kt` 调 `ChatTopBar(...)` 处，把 `contextDetail = viewModel.contextDetailState.collectAsStateWithLifecycle().value`、`directory = session.directory` 传入，移除旧的 token 参数透传。

- [ ] **Step 4: 编译检查 + Commit**

```bash
.\gradlew :app:compileDevDebugKotlin
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt \
        app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/ChatTopBar.kt \
        app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt
git commit -m "feat(chat): wire ContextDetailState to context detail dialog"
```

**Phase 2 验收**：`compileDevDebugKotlin` ✅ + `testDevDebugUnitTest`（ContextStatsTest）✅ + 点击环形进度条弹出增强弹窗 ✅

---

# Phase 3: 配套调整（功能 C）

## Task 9: 气泡 token 用 compact 格式

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/MessageCard.kt:494`

- [ ] **Step 1: 改字符串模板**

`MessageCard.kt` L492-498，当前：
```kotlin
if (totalInput > 0 || totalOutput > 0) {
    Text(
        text = "↑$totalInput ↓$totalOutput",
        ...
    )
}
```
改为：
```kotlin
if (totalInput > 0 || totalOutput > 0) {
    Text(
        text = "↑${formatTokenCount(totalInput)} ↓${formatTokenCount(totalOutput)}",
        ...
    )
}
```

- [ ] **Step 2: 添加 import（若未有）**

```kotlin
import dev.leonardo.ocremotev2.ui.screens.chat.util.formatTokenCount
```

- [ ] **Step 3: 编译检查 + Commit**

```bash
.\gradlew :app:compileDevDebugKotlin
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/MessageCard.kt
git commit -m "refactor(chat): use compact token format in message bubble footer"
```

> 注：顶部栏副标题改 directory 已在 Task 8 一并完成（ChatTopBar 参数调整时同步改副标题）。若 Task 8 未覆盖，在此 task 补完。

---

## Task 10: 本地化同步 + 最终验收

**Files:** 所有 `strings.xml`（15 locales）

- [ ] **Step 1: 跑 lokit 同步翻译**

Run: `lokit`（项目根目录）

- [ ] **Step 2: 全量编译 + 测试**

```bash
.\gradlew :app:compileDevDebugKotlin
.\gradlew :app:testDevDebugUnitTest --rerun
```
Expected: BUILD SUCCESSFUL + 全部测试 PASS

- [ ] **Step 3: 手动验收清单**

- [ ] 连续 read/glob/grep/list 合并成可展开卡片，折叠态显示"N次读取·N次搜索"
- [ ] 聚合卡展开后是各 ToolCard，可独立展开
- [ ] 聚合卡展开状态跟随设置里的"折叠工具"开关
- [ ] 顶部栏副标题显示当前对话目录（非 token/消息数）
- [ ] 消息气泡 token 显示 `↑4.3k ↓59`（非 `↑4270`）
- [ ] 点击环形进度条弹窗显示：provider/model、时间戳、进度条、消息计数、缓存命中率、上下文构成图表（含"其余"段）、token 明细

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/
git commit -m "i18n: sync 15 locales for chat info density feature"
```

---

## Self-Review 记录

**Spec coverage**：
- 功能 A 聚合 → Task 1-4 ✅
- 功能 B 弹窗 → Task 5-8 ✅
- 配套（副标题 directory + 气泡 compact）→ Task 8（副标题）+ Task 9（气泡）✅
- 本地化 → Task 2/6/10 ✅
- 测试 → Task 1/5（纯函数）+ Task 10（验收）✅

**已知实现时需核对的点**（非占位符，是实现细节）：
- `Message.User`/`Message.Assistant` 的确切构造签名（Task 5 测试）→ 读 `Message.kt`
- `TokenStatsState` 的字段名、`ChatViewModel` 的流名称（Task 8）→ 读 `ChatViewModel.kt`
- `ShapeTokens` 可用的 shape 名（Task 3）→ 读 `ui/theme/Shape.kt`
- `ChatMessage` 是否等价 `MessageWithParts`（Task 8）→ 读 `ChatViewModel.kt` 的 messages 类型

**类型一致性**：`PartRenderUnit` / `ContextToolSummary` / `ContextDetailState` / `BreakdownRole` 在各 task 间命名一致 ✅

---

## 执行顺序建议

Phase 1（Task 1→2→3→4）→ Phase 2（Task 5→6→7→8）→ Phase 3（Task 9→10）。
每个 Phase 结束可独立验证、可独立提交/发布。Phase 1 和 Phase 2 互不依赖，若并行开发可拆给两个 worktree。
