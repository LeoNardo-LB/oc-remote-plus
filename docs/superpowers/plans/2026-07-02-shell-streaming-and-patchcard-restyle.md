# Shell 输出流式显示 + PatchCard 复用聚合样式 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Shell 命令运行期间持续显示 stdout/stderr 输出；让 "N 个文件变更" 卡片复用聚合卡片的轻量行样式并保留 +N -N 行数统计。

**Architecture:** 问题1打通断裂的数据通道——解析 `tool.progress` 的 content，通过 pure function 注入到 `Part.Tool.Running.output`，ViewModel combine 管道接入，所有工具卡片经 `extractToolOutput` 自动受益。问题2抽取聚合卡片的行列表为共享 `ToolGroupList` 组件，PatchCard 改用。

**Tech Stack:** Kotlin, Jetpack Compose, kotlinx.serialization, kotlinx.coroutines Flow, JUnit4 + MockK

## Global Constraints

- JDK 21（`jvmToolchain(21)`），编译命令带 flavor：`.\gradlew :app:compileDevDebugKotlin`（120s 超时）
- 单元测试：`.\gradlew :app:testDevDebugUnitTest --rerun`（180s 超时）
- 路径处理跨平台：用 `PathUtils`（`util/PathUtils.kt`），不用 `substringAfterLast('/')`
- 主题 token：间距用 `SpacingTokens`，透明度用 `AlphaTokens`，形状用 `ShapeTokens`
- `Part.Tool.callId` 字段：`@SerialName("callID") val callId: String`（`Part.kt:79`）
- 不自动 commit/push，除非用户明确要求（AGENTS.md 规则；计划中的 commit 步骤供执行流程在获批后使用）
- ChatScreen.kt 编辑协议：Read before Edit，每次编辑后 `compileDevDebugKotlin`
- 参考 spec：`docs/superpowers/specs/2026-07-02-shell-streaming-and-patchcard-restyle-design.md`

---

## 问题 1：Shell 命令输出持续显示

### Task 1: 模型层 — Running.output 字段 + ToolProgress.content 解析

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/domain/model/ToolState.kt:38-47`
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/domain/model/SessionNextEvent.kt:153-162`
- Test: `app/src/test/kotlin/dev/leonardo/ocremotev2/domain/model/SessionNextEventTest.kt`

**Interfaces:**
- Produces: `ToolState.Running.output: String`（默认 ""，反序列化兼容）
- Produces: `SessionNextEvent.ToolProgress.content: List<ToolOutputContent>`（默认 emptyList）
- Produces: `ToolOutputContent(type: String, text: String)` data class

**⚠️ content 结构说明：** OpenCode 源码未提供 `ToolOutput.Content` 的精确字段定义。本任务按行业惯例（OpenAI content block 语义）推测为 `{type, text}`。Step 2 的测试用带 content 的 JSON 验证；若实测字段名不同（如 `data` 而非 `text`），修正 `ToolOutputContent` 定义后重跑——这是 TDD 正常循环。

- [ ] **Step 1: 在 SessionNextEvent.kt 顶部（ToolProgress 之前）新增 ToolOutputContent 类型**

在 `SessionNextEvent.kt` 中 `class ToolProgress` 定义之前插入：

```kotlin
/** OpenCode ToolOutput.Content 元素 —— 工具输出内容块。 */
@Serializable
data class ToolOutputContent(
    val type: String = "text",
    val text: String = ""
)
```

- [ ] **Step 2: 写失败测试 — ToolProgress 带 content 反序列化**

在 `SessionNextEventTest.kt` 现有 `ToolProgress parses correctly` 测试附近新增：

```kotlin
@Test
fun `ToolProgress parses with content`() {
    val eventJson = """{"type":"session.next.tool.progress","sessionID":"s1","messageID":"m1","partID":"p3","callID":"c1","progress":"50%","title":"Running...","content":[{"type":"text","text":"line1\n"}]}"""
    val event = json.decodeFromString<SessionNextEvent>(eventJson)
    assertTrue(event is SessionNextEvent.ToolProgress)
    val progress = event as SessionNextEvent.ToolProgress
    assertEquals("c1", progress.callId)
    assertEquals(1, progress.content.size)
    assertEquals("text", progress.content[0].type)
    assertEquals("line1\n", progress.content[0].text)
}

@Test
fun `ToolProgress without content defaults to empty`() {
    val eventJson = """{"type":"session.next.tool.progress","sessionID":"s1","messageID":"m1","partID":"p3","callID":"c1","progress":"50%"}"""
    val event = json.decodeFromString<SessionNextEvent>(eventJson)
    val progress = event as SessionNextEvent.ToolProgress
    assertTrue(progress.content.isEmpty())
}
```

- [ ] **Step 3: 运行测试验证失败**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "*SessionNextEventTest*ToolProgress*" --rerun`
Expected: 编译失败（`content` 字段不存在）

- [ ] **Step 4: 给 ToolProgress 加 content 字段**

`SessionNextEvent.kt` 的 `ToolProgress` data class（约 L153-162）改为：

```kotlin
@Serializable
data class ToolProgress(
    @SerialName("sessionID") override val sessionId: String,
    @SerialName("messageID") val messageId: String,
    @SerialName("partID") val partId: String,
    @SerialName("callID") val callId: String,
    val progress: String? = null,
    val title: String? = null,
    val content: List<ToolOutputContent> = emptyList()
) : SessionNextEvent()
```

- [ ] **Step 5: 运行测试验证通过**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "*SessionNextEventTest*ToolProgress*" --rerun`
Expected: PASS

- [ ] **Step 6: 给 ToolState.Running 加 output 字段**

`ToolState.kt` 的 `Running`（约 L38-47）改为：

```kotlin
@Serializable
data class Running(
    val input: Map<String, JsonElement> = emptyMap(),
    val output: String = "",
    val title: String? = null,
    val metadata: Map<String, JsonElement>? = null,
    val time: Time? = null
) : ToolState() {
    @Serializable
    data class Time(val start: Long)
}
```

- [ ] **Step 7: 全量编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL（output 带默认值，向后兼容）

- [ ] **Step 8: 全量模型测试回归**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "*SerializationTest*" --tests "*SessionNextEventTest*" --rerun`
Expected: PASS（确认 Running 加字段未破坏现有序列化测试）

- [ ] **Step 9: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocremotev2/domain/model/ToolState.kt app/src/main/kotlin/dev/leonardo/ocremotev2/domain/model/SessionNextEvent.kt app/src/test/kotlin/dev/leonardo/ocremotev2/domain/model/SessionNextEventTest.kt
git commit -m "feat(model): add Running.output and ToolProgress.content for shell streaming"
```

---

### Task 2: Handler 层 — ToolProgressInfo.output 累积

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/handler/SessionNextEventHandler.kt:17-25`（ToolProgressInfo）和 `:169-181`（handleToolProgress）
- Test: `app/src/test/kotlin/dev/leonardo/ocremotev2/data/repository/handler/SessionNextEventHandlerTest.kt`

**Interfaces:**
- Consumes: `SessionNextEvent.ToolProgress.content: List<ToolOutputContent>`（Task 1）
- Produces: `ToolProgressInfo.output: String`（累积的输出文本）

- [ ] **Step 1: 写失败测试 — ToolProgress content 累积到 output**

在 `SessionNextEventHandlerTest.kt` 的 `ToolProgress updates running tool` 测试后新增：

```kotlin
@Test
fun `ToolProgress accumulates content into output`() {
    // 先 start 一个 tool
    handler.handleSessionNextEvent(
        SessionNextEvent.ToolInputStarted(
            sessionId = "s1", messageId = "m1", partId = "p1",
            callId = "c1", tool = "bash"
        )
    )
    // 第一次 progress：content 有文本
    handler.handleSessionNextEvent(
        SessionNextEvent.ToolProgress(
            sessionId = "s1", messageId = "m1", partId = "p1", callId = "c1",
            content = listOf(ToolOutputContent(text = "line1\n"))
        )
    )
    // 第二次 progress：更多文本（增量）
    handler.handleSessionNextEvent(
        SessionNextEvent.ToolProgress(
            sessionId = "s1", messageId = "m1", partId = "p1", callId = "c1",
            content = listOf(ToolOutputContent(text = "line2\n"))
        )
    )
    val tool = handler.activeToolProgress.value["s1"]!![0]
    assertEquals("line1\nline2\n", tool.output)
}
```

需在测试文件顶部加 import：`import dev.leonardo.ocremotev2.domain.model.ToolOutputContent`

- [ ] **Step 2: 运行测试验证失败**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "*SessionNextEventHandlerTest*accumulates*" --rerun`
Expected: 编译失败（`ToolProgressInfo.output` 不存在 / `ToolOutputContent` 未 import）

- [ ] **Step 3: 给 ToolProgressInfo 加 output 字段**

`SessionNextEventHandler.kt` 的 `ToolProgressInfo`（约 L17-25）改为：

```kotlin
data class ToolProgressInfo(
    val callId: String,
    val partId: String,
    val tool: String,
    val status: String,
    val progress: String? = null,
    val title: String? = null,
    val output: String = ""
)
```

- [ ] **Step 4: 修改 handleToolProgress 累积 content**

`SessionNextEventHandler.kt` 的 `handleToolProgress`（约 L169-181）改为：

```kotlin
private fun handleToolProgress(event: SessionNextEvent.ToolProgress) {
    _activeToolProgress.update { current ->
        val sessionTools = current[event.sessionId] ?: return@update current
        val outputDelta = event.content.joinToString("") { it.text }
        val updated = sessionTools.map { tool ->
            if (tool.callId == event.callId) {
                tool.copy(
                    status = "running",
                    progress = event.progress,
                    title = event.title,
                    output = tool.output + outputDelta
                )
            } else tool
        }
        current + (event.sessionId to updated)
    }
}
```

**累积策略说明：** 上面按"增量追加"实现（`tool.output + outputDelta`）。若实施时抓取真实 SSE 发现 content 是全量快照（每次含全部输出），改为 `output = outputDelta`（直接替换）。验证方法见 Task 4 Step 8 实机测试。

- [ ] **Step 5: 运行测试验证通过**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "*SessionNextEventHandlerTest*accumulates*" --rerun`
Expected: PASS

- [ ] **Step 6: handler 全量回归**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "*SessionNextEventHandler*" --rerun`
Expected: PASS（现有 progress 测试不应受影响——它们不传 content，outputDelta 为 ""，累积结果为 ""）

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/handler/SessionNextEventHandler.kt app/src/test/kotlin/dev/leonardo/ocremotev2/data/repository/handler/SessionNextEventHandlerTest.kt
git commit -m "feat(handler): accumulate tool.progress content into ToolProgressInfo.output"
```

---

### Task 3: Pure function — ToolProgressOutputInjector

**Files:**
- Create: `app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/tools/ToolProgressOutputInjector.kt`
- Test: `app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/tools/ToolProgressOutputInjectorTest.kt`

**Interfaces:**
- Consumes: `Part.Tool`（`Part.kt:75`，data class，`copy` 可用）、`ToolState.Running.output`（Task 1）、`ToolProgressInfo.output`（Task 2）
- Produces: `ToolProgressOutputInjector.inject(parts, progressOutputs): List<Part>` — pure function

- [ ] **Step 1: 写失败测试**

创建 `ToolProgressOutputInjectorTest.kt`：

```kotlin
package dev.leonardo.ocremotev2.ui.screens.chat.tools

import dev.leonardo.ocremotev2.domain.model.Part
import dev.leonardo.ocremotev2.domain.model.ToolState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ToolProgressOutputInjectorTest {

    private fun tool(callId: String, state: ToolState) = Part.Tool(
        id = "p1", sessionId = "s1", messageId = "m1",
        callId = callId, tool = "bash", state = state
    )

    @Test
    fun `empty progress map returns parts unchanged`() {
        val parts = listOf(tool("c1", ToolState.Running()))
        val result = ToolProgressOutputInjector.inject(parts, emptyMap())
        assertSame(parts, result)
    }

    @Test
    fun `injects output into Running tool by callId`() {
        val parts = listOf(tool("c1", ToolState.Running()))
        val result = ToolProgressOutputInjector.inject(parts, mapOf("c1" to "stdout line"))
        val running = (result[0] as Part.Tool).state as ToolState.Running
        assertEquals("stdout line", running.output)
    }

    @Test
    fun `does not touch Completed tools`() {
        val parts = listOf(tool("c1", ToolState.Completed(output = "done")))
        val result = ToolProgressOutputInjector.inject(parts, mapOf("c1" to "stdout"))
        val completed = (result[0] as Part.Tool).state as ToolState.Completed
        assertEquals("done", completed.output)
    }

    @Test
    fun `skips Running tools with no matching callId`() {
        val parts = listOf(tool("c1", ToolState.Running()))
        val result = ToolProgressOutputInjector.inject(parts, mapOf("c2" to "stdout"))
        val running = (result[0] as Part.Tool).state as ToolState.Running
        assertEquals("", running.output)
    }

    @Test
    fun `empty output string does not replace existing`() {
        val parts = listOf(tool("c1", ToolState.Running(output = "existing")))
        val result = ToolProgressOutputInjector.inject(parts, mapOf("c1" to ""))
        val running = (result[0] as Part.Tool).state as ToolState.Running
        assertEquals("existing", running.output)
    }

    @Test
    fun `preserves non-Tool parts`() {
        val textPart = Part.Text(id = "p0", sessionId = "s1", messageId = "m1", text = "hello")
        val parts = listOf(textPart, tool("c1", ToolState.Running()))
        val result = ToolProgressOutputInjector.inject(parts, mapOf("c1" to "out"))
        assertEquals(textPart, result[0])
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "*ToolProgressOutputInjectorTest*" --rerun`
Expected: 编译失败（`ToolProgressOutputInjector` 不存在）

- [ ] **Step 3: 实现 ToolProgressOutputInjector**

创建 `ToolProgressOutputInjector.kt`：

```kotlin
package dev.leonardo.ocremotev2.ui.screens.chat.tools

import dev.leonardo.ocremotev2.domain.model.Part
import dev.leonardo.ocremotev2.domain.model.ToolState

/**
 * 把 tool.progress 累积的 output 注入到对应 callID 的 Part.Tool（仅 Running 态）。
 *
 * Pure function —— 在 MessageDataDelegate 的 combine 管道中调用，使所有读取
 * `Part.Tool.state` 的 UI 自动获得运行期输出，无需各卡片单独查询 progress 流。
 *
 * 设计依据：`docs/superpowers/specs/2026-07-02-shell-streaming-and-patchcard-restyle-design.md` §2.5
 * —— Running.output 为本地增强，tool.success 时 Completed.output（服务器权威）经 message
 * 通道自然覆盖，无冲突。
 */
object ToolProgressOutputInjector {

    /**
     * @param parts 当前消息 parts 列表
     * @param progressOutputs callID → 累积的 progress 输出文本
     * @return 注入后的 parts 列表（无匹配时原样返回，避免无谓重组）
     */
    fun inject(
        parts: List<Part>,
        progressOutputs: Map<String, String>
    ): List<Part> {
        if (progressOutputs.isEmpty()) return parts
        return parts.map { part ->
            if (part is Part.Tool && part.state is ToolState.Running) {
                val output = progressOutputs[part.callId]
                if (!output.isNullOrEmpty()) {
                    part.copy(state = part.state.copy(output = output))
                } else {
                    part
                }
            } else {
                part
            }
        }
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "*ToolProgressOutputInjectorTest*" --rerun`
Expected: PASS（全部 6 个测试）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/tools/ToolProgressOutputInjector.kt app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/tools/ToolProgressOutputInjectorTest.kt
git commit -m "feat(tools): add ToolProgressOutputInjector pure function"
```

---

### Task 4: 接入层 — MessageDataDelegate combine 注入 + extractToolOutput

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/MessageDataDelegate.kt`（构造参数 + messageListState combine，约 L65-200）
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModel.kt`（构造 messageData 时传 activeToolProgress）
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/tools/ToolCardRenderer.kt:234-240`（extractToolOutput）

**Interfaces:**
- Consumes: `ToolProgressOutputInjector.inject`（Task 3）、`eventDispatcher.activeToolProgress`（`EventDispatcher.kt:145`）
- Produces: `Part.Tool.Running.output` 经 combine 管道自动填充；`extractToolOutput` 返回 Running.output

**⚠️ 实施前必读：** 先 `read` `MessageDataDelegate.kt:60-200`，确认 `messageListState` 的 combine source 列表与 transform lambda 结构（该文件 561 行，combine 管道在 L115 起）。下面的代码框架按"combine 增加 activeToolProgress source，在处理 parts 时调用 inject"设计，具体行号以实际 read 为准。

- [ ] **Step 1: extractToolOutput 加 Running 分支**

`ToolCardRenderer.kt` 的 `extractToolOutput`（L234-240）改为：

```kotlin
internal fun extractToolOutput(tool: Part.Tool): String {
    return when (val s = tool.state) {
        is ToolState.Completed -> s.output
        is ToolState.Error -> s.error
        is ToolState.Running -> s.output
        else -> ""
    }
}
```

同时检查 `ToolCardRenderer.kt` 内 `ToolCallCard`（约 L197-201）从 state 取 output 的逻辑：

```kotlin
val output = when (val s = tool.state) {
    is ToolState.Completed -> s.output
    is ToolState.Error -> s.error
    else -> ""
}
```
改为也包含 Running：

```kotlin
val output = when (val s = tool.state) {
    is ToolState.Completed -> s.output
    is ToolState.Running -> s.output
    is ToolState.Error -> s.error
    else -> ""
}
```

- [ ] **Step 2: 编译验证 extractToolOutput 改动**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Read MessageDataDelegate 确认 combine 结构**

Run（用 read 工具）: `MessageDataDelegate.kt` offset=60 limit=160
确认：
- 构造参数列表（找出在哪里加 `activeToolProgress` 参数）
- `messageListState` 的 `combine(...)` source 列表
- transform lambda 中 `_partsList` 如何被消费（找到注入点）

- [ ] **Step 4: MessageDataDelegate 构造加 activeToolProgress 参数**

在 `MessageDataDelegate` 构造参数中（class MessageDataDelegate 主构造，约 L60-95 区域）新增：

```kotlin
private val activeToolProgress: StateFlow<Map<String, List<ToolProgressInfo>>,
```

需在文件顶部加 import：
```kotlin
import dev.leonardo.ocremotev2.data.repository.handler.ToolProgressInfo
import dev.leonardo.ocremotev2.ui.screens.chat.tools.ToolProgressOutputInjector
```

- [ ] **Step 5: 在 messageListState combine 管道注入**

`activeToolProgress` **必须作为 combine 的 source**（不能在 transform 内用 `.value` 读取——那样 progress 变化不会触发重组）。

在 `messageListState`（L115）的 combine source 列表中加入 `activeToolProgress`，transform lambda 对应增加一个参数（如 `progressMap`）。在 transform 内、消费 `_partsList` 之处调用注入：

```kotlin
// combine(..., _partsList, activeToolProgress, ...) { ..., parts, progressMap, ... ->
//     val sid = <当前 flatMapLatest 作用域的 sessionId>
//     val progressList = progressMap[sid].orEmpty()
//     val progressOutputs = progressList.associate { it.callId to it.output }
//     val injectedParts = ToolProgressOutputInjector.inject(parts, progressOutputs)
//     // 后续构建 messages 时用 injectedParts 替代 parts
// }
```

**关键正确性要求：**
1. `activeToolProgress` 作为 combine source，使其变化触发重组（Running 期间每个 progress 事件 → output 累积 → combine 重算 → UI 刷新）。
2. 若 combine 在 `flatMapLatest { sid -> combine(...) }` 内，`sid` 已在作用域，用 `progressMap[sid]` 过滤当前会话的 progress。
3. `callId` 全局唯一，跨会话匹配也安全；若 combine 无 sessionId 上下文，可用 `progressMap.values.flatten().associate { it.callId to it.output }`。

**具体改法以 Step 3 read 结果为准**：现有 combine 的 source 数量与 transform 参数须对应。若 combine source 过多（超过 Flow.combine 的重载上限 5 个），可能需先 `combine(a, b)` 得中间流再与其它 combine，或将部分 source 分组。保持现有 combine 的分组结构，仅插入 activeToolProgress。

- [ ] **Step 6: ChatViewModel 传 activeToolProgress 给 MessageDataDelegate**

`ChatViewModel.kt` 中构造 `messageData` 处（搜索 `MessageDataDelegate(`），加参数：

```kotlin
activeToolProgress = eventDispatcher.activeToolProgress,
```

- [ ] **Step 7: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: 全量单元测试回归**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun`
Expected: PASS（特别关注 EventDispatcherIntegrationTest、ChatViewModel 相关测试；若 combine 改动破坏现有测试，根据失败信息修正）

- [ ] **Step 9: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/MessageDataDelegate.kt app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModel.kt app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/tools/ToolCardRenderer.kt
git commit -m "feat(chat): wire tool.progress output into Running tools via combine pipeline"
```

---

## 问题 2：PatchCard 复用聚合卡片样式

### Task 5: 抽取 ToolGroupList 共享组件 + ContextToolGroupCard 重构

**Files:**
- Create: `app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/tools/ToolGroupList.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/tools/ContextToolGroupCard.kt:80-119`

**Interfaces:**
- Produces: `ToolGroupListItem(icon, label, subtitle?, trailing?)` data class
- Produces: `ToolGroupList(items, onItemClick?, modifier)` composable —— 布局迁移自 ContextToolGroupCard L80-119

- [ ] **Step 1: 创建 ToolGroupList.kt**

```kotlin
package dev.leonardo.ocremotev2.ui.screens.chat.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.leonardo.ocremotev2.ui.theme.AlphaTokens

/** 聚合卡片通用行数据。 */
data class ToolGroupListItem(
    val icon: ImageVector,
    val label: String,
    val subtitle: String? = null,
    val trailing: @Composable (() -> Unit)? = null,
)

/**
 * 聚合卡片通用行列表。布局迁移自 ContextToolGroupCard（重构前 L80-119）：
 * Column { forEach { if(idx>0) HorizontalDivider; Row(icon, label, subtitle weight:1f, trailing) } }
 *
 * 行样式：图标 16dp(onSurfaceVariant) + 标签(labelMedium) + 副标题(labelMedium,
 * onSurfaceVariant, weight:1f 省略号) + 可选 trailing。
 */
@Composable
fun ToolGroupList(
    items: List<ToolGroupListItem>,
    onItemClick: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        items.forEachIndexed { idx, item ->
            if (idx > 0) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT)
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .let { m -> if (onItemClick != null) m.clickable { onItemClick(idx) } else m }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.labelMedium,
                )
                if (!item.subtitle.isNullOrEmpty()) {
                    Text(
                        text = item.subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                item.trailing?.invoke()
            }
        }
    }
}
```

- [ ] **Step 2: 编译验证新组件**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 重构 ContextToolGroupCard 改用 ToolGroupList**

`ContextToolGroupCard.kt` 中，把 `ToolCardScaffold { ... }` 内的 Column（L80-119）替换为 ToolGroupList 调用。先 `read` 该文件 L40-122 确认当前结构，然后替换内容 lambda：

将原 L80-119 的 `Column { parts.forEachIndexed { ... } }` 替换为：

```kotlin
// 预解析标签（@Composable，须在 Composable 体内、map 之前调用）
val readLabel = stringResource(R.string.tool_read)
val globLabel = stringResource(R.string.tool_glob)
val grepLabel = stringResource(R.string.tool_grep)

ToolGroupList(
    items = parts.map { part ->
        val label = when (part.tool.lowercase()) {
            "read" -> readLabel
            "glob" -> globLabel
            "grep" -> grepLabel
            else -> part.tool
        }
        ToolGroupListItem(
            icon = toolIcon(part.tool),
            label = label,
            subtitle = toolSubtitle(part).ifEmpty { null },
        )
    },
    onItemClick = { idx -> handleToolClick(parts[idx], onOpenFile) },
)
```

**为什么预解析：** `toolLabel` 原为 `@Composable` 函数（内部用 `stringResource`）。`parts.map { }` 的 lambda 不是 `@Composable` 上下文（map 非 inline），在其中调用 @Composable 函数会编译失败。因此须在 `ToolGroupList` 调用前（@Composable 体内）用 `stringResource` 预解析三个标签，map 内用普通 String 变量。

保留文件中现有的 `handleToolClick`、`toolIcon`、`toolSubtitle` 私有函数（逻辑不变）。**删除** `toolLabel` 函数（已被预解析替代）。移除不再需要的 import（Column / Row / HorizontalDivider / Icon / Text / size / padding / Arrangement / Alignment / TextOverflow / clickable —— 凡仅被旧 Column 块或 toolLabel 使用的；保留仍被 `toolIcon` 等用到的，如 `Icons`、`Description`、`FindInPage`、`Search`）。

- [ ] **Step 4: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 视觉行为回归（手动）**

启动 app，触发一次 Read + Glob 聚合，确认 ContextToolGroupCard 的行布局、图标、标签、副标题、分隔线与重构前一致（纯重构，像素级不变）。

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/tools/ToolGroupList.kt app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/tools/ContextToolGroupCard.kt
git commit -m "refactor(tools): extract ToolGroupList shared component, ContextToolGroupCard reuses it"
```

---

### Task 6: PatchCard 改用 ToolGroupList

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/tools/cards/PatchCard.kt:64-103`

**Interfaces:**
- Consumes: `ToolGroupList`（Task 5）、`DiffChangesInline`（`DiffHelpers.kt:37`）、`LocalSessionDiffs`（`util/ChatColors.kt`）、`PathUtils.fileName`

- [ ] **Step 1: Read PatchCard.kt 确认当前结构**

Run（read 工具）: `PatchCard.kt` 全文（105 行）。确认 L64-103 的 Column+Surface+Row 结构。

- [ ] **Step 2: 替换 PatchCard 内容 lambda 为 ToolGroupList**

`PatchCard.kt` 的 `ToolCardScaffold { ... }` 内容（L63-104）替换为：

```kotlin
) {
    ToolGroupList(
        items = patch.files.map { filePath ->
            val fileDiff = sessionDiffs?.find { it.file == filePath }
            ToolGroupListItem(
                icon = Icons.Default.Description,
                label = PathUtils.fileName(filePath),
                subtitle = PathUtils.parentDir(filePath).ifEmpty { null },
                trailing = {
                    DiffChangesInline(
                        additions = fileDiff?.additions ?: 0,
                        deletions = fileDiff?.deletions ?: 0
                    )
                },
            )
        },
        onItemClick = if (onOpenFile != null) { idx -> onOpenFile(patch.files[idx]) } else null,
    )
}
```

同时更新 import：
- 移除不再直接使用的：`Column`、`Row`、`fillMaxWidth`（若仅旧布局用）、`padding`、`size`、`Surface`、`clickable`、`Arrangement`、`Alignment`、`TextOverflow`、`MaterialTheme`（若不再用）、`Text`（若不再用）
- 新增：`import dev.leonardo.ocremotev2.ui.screens.chat.tools.ToolGroupList`、`import dev.leonardo.ocremotev2.ui.screens.chat.tools.ToolGroupListItem`
- 保留：`DiffChangesInline`、`PathUtils`、`Icons`、`Description`、`isAmoledTheme`、`ShapeTokens`/`AlphaTokens`（按实际使用）

**subtitle 说明：** 用 `PathUtils.parentDir(filePath)` 显示目录（与 EditToolCard 一致的路径风格）。若 parentDir 为空（根目录文件），subtitle 传 null，ToolGroupList 不渲染副标题。

- [ ] **Step 3: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 全量单元测试回归**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun`
Expected: PASS

- [ ] **Step 5: 视觉验证（手动）**

触发一次文件编辑（edit/write），确认：
1. turn 结束的 "N 个文件变更" 卡片样式与 "读取了 N 个文件" 聚合卡片一致（轻量行 + 分隔线）
2. 每行右侧显示 `+N -N` 行数
3. 点击文件行能打开 FileViewer

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/tools/cards/PatchCard.kt
git commit -m "refactor(tools): PatchCard reuses ToolGroupList with per-file diff counts"
```

---

## 完成验证（全量）

- [ ] **编译**: `.\gradlew :app:compileDevDebugKotlin` → BUILD SUCCESSFUL
- [ ] **单元测试**: `.\gradlew :app:testDevDebugUnitTest --rerun` → 全部 PASS
- [ ] **实机 Shell 流式**: 运行耗时命令（如 `find /system`），观察 BashToolCard 在 Running 期间持续刷新输出，而非只显示入参
- [ ] **实机 content 结构确认**: 若 Running 期间无输出，抓取该时段 SSE（adb logcat 或服务端日志），确认 `content` 字段名；若非 `text`，回 Task 1 Step 1 修正 `ToolOutputContent` 字段名，回 Task 2 Step 4 确认累积逻辑
- [ ] **实机 PatchCard**: 文件编辑后，PatchCard 行样式与聚合卡片一致且带行数

## 风险与回退

- **content 结构不符**：Task 1 的 `ToolOutputContent(type, text)` 是推测。实机验证若失败，按"完成验证"第4项修正字段名。这是预期内的 TDD 循环，非计划缺陷。
- **MessageDataDelegate combine 改动破坏现有测试**：Task 4 Step 8 全量回归；若失败，根据断言修正 combine 注入逻辑（pure function `ToolProgressOutputInjector` 本身已独立测试通过，风险仅在接入点）。
- **content 全量 vs 增量**：若实机发现输出重复（如 "line1" 变 "line1line1"），说明 content 是全量，回 Task 2 Step 4 改 `output = outputDelta`（删去 `tool.output +`）。
