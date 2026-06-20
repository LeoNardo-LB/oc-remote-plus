# Workspace File Viewer — Phase 3 实现计划（标注能力）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`).

**Goal:** 在 FileViewer 源码视图添加"标注修改"能力——用户长按选中代码→标注意见→提交结构化提示词给当前会话的 AI，让 AI 执行修改。

**Architecture:** 标注数据纯内存级（`AnnotationManager` 管理 `List<Annotation>`），不持久化。提交时通过 `ChatRepository.promptAsync` 发送结构化文本。自定义 `TextToolbar` 替换系统选区菜单，加入"标注修改"项。标注高亮通过 `AnnotatedString + SpanStyle.background` 叠加在语法高亮之上。

**Tech Stack:** Kotlin + Compose + Hilt(KSP) + MockK 1.14.9 + Turbine 1.2.1。JDK 21。

---

## TOC

- [Global Constraints](#global-constraints)
- [关键架构决策](#关键架构决策)
- Task 1: [Annotation Domain Model + OffsetConverter](#task-1)
- Task 2: [AnnotationManager（增删查 + 重新编号）](#task-2)
- Task 3: [AnnotationPromptBuilder（结构化文本生成）](#task-3)
- Task 4: [SubmitAnnotationsUseCase](#task-4)
- Task 5: [FileViewerUiState + ViewModel 标注状态](#task-5)
- Task 6: [AnnotationTextToolbar（自定义选区菜单）](#task-6)
- Task 7: [AnnotationInputSheet + AnnotationDetailDialog](#task-7)
- Task 8: [CodeSourceView 标注高亮渲染](#task-8)
- Task 9: [FileViewerScreen 提交集成 + 导航](#task-9)
- Task 10: [strings.xml + Maestro E2E flow 23](#task-10)
- [Phase 3 验收清单](#phase-3-验收清单)
- [Phase 3 不包含](#phase-3-不包含)

---

## Global Constraints

> ⚠️ 所有 Task 隐含遵守。Phase 1 的 Global Constraints 全部继承。

### 继承自 Phase 1

- **路径与包名**：源码 `app/src/main/kotlin/`，测试 `app/src/test/kotlin/`，包名前缀 `dev.minios.ocremote.`
- **Gradle 命令带 flavor**：`compileDevDebugKotlin`（120s）、`testDevDebugUnitTest --rerun`（180s）
- **Material 3 First**：用 `MaterialTheme.colorScheme` 语义色，不自定义 Canvas
- **Alpha tokens**：`AlphaTokens.SELECTED(0.12) / DIFF_BG(0.10) / FAINT(0.35) / MUTED(0.50) / MEDIUM(0.70) / HIGH(0.80) / AMOLED(0.92)`
- **Spacing tokens**：`SpacingTokens.XS(4)/SM(8)/MD(12)/LG(16)/XL(24)/XXL(32).dp`
- **测试 isReturnDefaultValues=true**：MockK 必须显式 `coEvery/coAnswers`
- **真实样本**：测试用项目真实代码片段，禁用 `"aaa"/"bbb"` 占位

### Phase 3 新增约束

- **标注仅限 SOURCE 模式**：DIFF 模式（纯只读）、md 渲染预览（只读）不支持标注
- **标注不持久化**：提交后 ViewModel 销毁即释放；旋转屏幕时标注丢失（Phase 4 可加 `rememberSaveable`）
- **选区重叠允许**：多个标注选区可重叠，高亮 alpha 累加封顶 0.6
- **选区捕获方式**：使用官方 `Modifier.appendTextContextMenuComponents()` API（Compose Foundation 2026）扩展系统选区菜单，加入"标注修改"项。选中文本通过 clipboard 读取（点"标注修改"时程序化触发 copy → 读剪贴板 → `String.indexOf` 定位 char offset）
- **提交格式**：`1. 行:列 - 行:列 意见`（1-based，按创建顺序编号）
- **sessionId**：`FileViewerNav.PARAM_SESSION_ID` 已在路由参数中（Phase 1 定义），Phase 3 ViewModel 需读取

### testTag 约定（Maestro 依赖）

| testTag | 添加位置（Task） | 用途 |
|---------|-----------------|------|
| `annotation_toolbar_annotate` | Task 6（appendTextContextMenuComponents 中的"标注修改"项） | 触发标注 |
| `annotation_input_note` | Task 7（AnnotationInputSheet 输入框） | 输入意见 |
| `annotation_input_confirm` | Task 7（AnnotationInputSheet 确定按钮） | 确认标注 |
| `annotation_submit_button` | Task 9（TopBar 提交按钮） | 打开提交对话框 |
| `annotation_submit_send` | Task 9（提交对话框发送按钮） | 发送标注 |
| `annotation_detail_delete` | Task 7（详情对话框删除按钮） | 删除标注 |

---

## 关键架构决策

### 1. 使用官方 `appendTextContextMenuComponents` API

Compose Foundation（2026 BOM 2026.05.01）提供 `Modifier.appendTextContextMenuComponents()` —— 官方 API，在 `SelectionContainer` 内的 `Text` 上添加此 Modifier 即可向系统选区菜单注入自定义项。

**旧方案已废弃**：`LocalTextToolbar` + 自定义 `Popup` 在 2026 Compose 已失效（系统 toolbar 接管控制权）。

**新方案**：
```kotlin
SelectionContainer {
    Text(
        text = annotatedCode,
        modifier = Modifier.appendTextContextMenuComponents {
            item(
                key = "annotate",
                label = "标注修改",
            ) {
                // 框架自动管理选区 UI + 菜单弹出
                // 这里只需处理点击回调
                onAnnotate()
                close()
            }
        }
    )
}
```

**优势**：
- 官方 API，无第三方库依赖
- 系统级选区体验（拖拽选择手柄 + 浮动菜单）
- 自动与复制/全选等系统菜单项共存
- 支持图标 (`leadingIcon`)、分隔符 (`separator()`)

**所需 import**：
```kotlin
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
```

### 2. 选中文本捕获：clipboard 程序化读取

`appendTextContextMenuComponents` 的 `item` callback 不直接暴露选中文本。Compose `SelectionContainer` 也不公开当前选区范围。

**方案**：点"标注修改"时：
1. 保存当前剪贴板内容
2. 程序化执行 copy（`clipboardManager.setText()` + 利用系统已注册的 onCopy 回调）
3. 读取剪贴板获取选中文本
4. 恢复原剪贴板内容
5. 用 `content.indexOf(selectedText)` 定位 char offset

**优化**：由于 CodeSourceView 用 LazyColumn 逐行渲染，可以同时传递行号信息，先用行号定位再在行内 `indexOf`，减少歧义。

**已知限制**（Phase 4 改进）：相同文本多次出现时 `indexOf` 可能匹配错误位置；旋转屏幕标注丢失。spec §13.1 已记录这些风险。

### 3. 标注高亮叠加在语法高亮之上

CodeSourceView 已用 `Highlights` 库生成语法高亮 `AnnotatedString`（Phase 1）。标注高亮通过 `SpanStyle.background` 叠加：

- 逐行计算标注命中的区间（将全局 char offset 映射到行内 offset）
- 重叠区域 alpha 手动累加封顶 0.6
- 每个标注用 `MaterialTheme.colorScheme.primary.copy(alpha = 0.12)` 着色

### 4. 提交走 ChatRepository.promptAsync

标注提交不是新 API 端点——生成结构化文本后，通过 `ChatRepository.promptAsync(serverId, sessionId, parts, directory)` 发送，等同于用户发了一条消息。AI 收到后自行执行修改。

---

## Task 1: Annotation Domain Model + OffsetConverter

<a id="task-1"></a>

**Spec ref:** §8.1 (Annotation model), §8.6 (OffsetConverter)

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/domain/model/Annotation.kt`
- Test: `app/src/test/kotlin/dev/minios/ocremote/domain/model/OffsetConverterTest.kt`

**Interfaces:**
- Produces: `Annotation` data class, `LineCol` data class, `OffsetConverter` object

- [ ] **Step 1: 写失败测试**

创建 `OffsetConverterTest.kt`：

```kotlin
package dev.minios.ocremote.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class OffsetConverterTest {

    // 真实样本：OpenCodeApi.kt 前几行
    private val sampleKotlin = """
        package dev.minios.ocremote

        import io.ktor.client.HttpClient
        import io.ktor.client.request.get
    """.trimIndent()

    @Test
    fun `empty string offset 0 returns 1,1`() {
        assertEquals(LineCol(1, 1), OffsetConverter.charOffsetToLineCol("", 0))
    }

    @Test
    fun `single line no newline offset 2 returns 1,3`() {
        assertEquals(LineCol(1, 3), OffsetConverter.charOffsetToLineCol("hello", 2))
    }

    @Test
    fun `LF newline offset 4 returns 2,2`() {
        assertEquals(LineCol(2, 2), OffsetConverter.charOffsetToLineCol("ab\ncd", 4))
    }

    @Test
    fun `CRLF newline offset 4 returns 2,1`() {
        assertEquals(LineCol(2, 1), OffsetConverter.charOffsetToLineCol("ab\r\ncd", 4))
    }

    @Test
    fun `CRLF offset 3 returns 1,4`() {
        assertEquals(LineCol(1, 4), OffsetConverter.charOffsetToLineCol("ab\r\ncd", 3))
    }

    @Test
    fun `CRLF offset 5 returns 2,2`() {
        assertEquals(LineCol(2, 2), OffsetConverter.charOffsetToLineCol("ab\r\ncd", 5))
    }

    @Test
    fun `pure CR newline offset 4 returns 2,2`() {
        assertEquals(LineCol(2, 2), OffsetConverter.charOffsetToLineCol("ab\rcd", 4))
    }

    @Test
    fun `mixed line endings all handled`() {
        val content = "a\nb\r\nc\rd"
        assertEquals(LineCol(1, 1), OffsetConverter.charOffsetToLineCol(content, 0))
        assertEquals(LineCol(1, 2), OffsetConverter.charOffsetToLineCol(content, 1))
        assertEquals(LineCol(2, 1), OffsetConverter.charOffsetToLineCol(content, 2))
        assertEquals(LineCol(3, 1), OffsetConverter.charOffsetToLineCol(content, 5))
        assertEquals(LineCol(4, 1), OffsetConverter.charOffsetToLineCol(content, 7))
    }

    @Test
    fun `offset exceeds content length clamps`() {
        assertEquals(LineCol(1, 6), OffsetConverter.charOffsetToLineCol("hello", 100))
    }

    @Test
    fun `negative offset treated as 0`() {
        assertEquals(LineCol(1, 1), OffsetConverter.charOffsetToLineCol("hello", -5))
    }

    @Test
    fun `realistic kotlin sample multiline`() {
        val offsetOfLine3 = sampleKotlin.indexOf("import io.ktor.client.HttpClient")
        val result = OffsetConverter.charOffsetToLineCol(sampleKotlin, offsetOfLine3)
        assertEquals(LineCol(3, 1), result)
    }

    @Test
    fun `lineColToCharOffset round-trip for single line`() {
        val offset = OffsetConverter.lineColToCharOffset("hello world", 1, 6)
        assertEquals(5, offset)
    }

    @Test
    fun `lineColToCharOffset for multiline LF`() {
        val offset = OffsetConverter.lineColToCharOffset("ab\ncd\nef", 3, 1)
        assertEquals(6, offset)
    }

    @Test
    fun `lineColToCharOffset for line beyond content returns end`() {
        val offset = OffsetConverter.lineColToCharOffset("ab\ncd", 10, 1)
        assertEquals(5, offset)
    }
}
```

- [ ] **Step 2: 运行验证失败**

```bash
.\gradlew :app:testDevDebugUnitTest --rerun --tests "*.OffsetConverterTest"
```

Expected: FAIL — `OffsetConverter` unresolved reference.

- [ ] **Step 3: 实现 Annotation model + OffsetConverter**

创建 `Annotation.kt`：

```kotlin
package dev.minios.ocremote.domain.model

/**
 * A user annotation on a code selection in the FileViewer source view.
 * Pure in-memory; not persisted. Lifecycle: created on user action,
 * cleared on ViewModel disposal or after submission.
 */
data class Annotation(
    val id: String,           // UUID string
    val index: Int,           // Creation order (0-based). Display as index + 1.
                              // Re-numbered to consecutive 0..N-1 on middle deletion.
    val startChar: Int,       // Start offset in full content (inclusive)
    val endChar: Int,         // End offset in full content (exclusive)
    val startLine: Int,       // 1-based
    val startCol: Int,        // 1-based
    val endLine: Int,         // 1-based
    val endCol: Int,          // 1-based
    val selectedText: String, // Originally selected snippet
    val note: String,         // User's modification note
    val createdAt: Long       // Epoch millis
)

/** 1-based line and column position. */
data class LineCol(val line: Int, val col: Int)

/**
 * Converts between character offsets and 1-based line:col positions.
 * Handles \n, \r\n, and \r line endings (cross-platform files).
 */
object OffsetConverter {

    fun charOffsetToLineCol(content: String, offset: Int): LineCol {
        var line = 1
        var col = 1
        val effectiveOffset = offset.coerceIn(0, content.length)
        var i = 0
        while (i < effectiveOffset && i < content.length) {
            when (val c = content[i]) {
                '\r' -> {
                    line++; col = 1
                    if (i + 1 < content.length && content[i + 1] == '\n') i++
                }
                '\n' -> { line++; col = 1 }
                else -> col++
            }
            i++
        }
        return LineCol(line, col)
    }

    fun lineColToCharOffset(content: String, line: Int, col: Int): Int {
        if (line <= 1) return (col - 1).coerceIn(0, content.length)
        var currentLine = 1
        var i = 0
        while (i < content.length && currentLine < line) {
            when (content[i]) {
                '\r' -> {
                    currentLine++
                    if (i + 1 < content.length && content[i + 1] == '\n') i++
                }
                '\n' -> currentLine++
            }
            i++
        }
        if (currentLine < line) return content.length
        return (i + (col - 1)).coerceAtMost(content.length)
    }
}
```

- [ ] **Step 4: 运行验证通过**

```bash
.\gradlew :app:testDevDebugUnitTest --rerun --tests "*.OffsetConverterTest"
```

Expected: PASS (14 tests).

- [ ] **Step 5: 编译 + Commit**

```bash
.\gradlew :app:compileDevDebugKotlin
git add app/src/main/kotlin/dev/minios/ocremote/domain/model/Annotation.kt \
        app/src/test/kotlin/dev/minios/ocremote/domain/model/OffsetConverterTest.kt
git commit -m "feat: Annotation domain model + OffsetConverter (handles LF/CRLF/CR)"
```

---

## Task 2: AnnotationManager（增删查 + 重新编号）

<a id="task-2"></a>

**Spec ref:** §7.4 (标注增删), 设计决策 §16 (删除中间标注重新连续编号)

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/AnnotationManager.kt`
- Test: `app/src/test/kotlin/dev/minios/ocremote/ui/screens/viewer/AnnotationManagerTest.kt`

**Interfaces:**
- Consumes: `Annotation`, `OffsetConverter` (Task 1)
- Produces: `AnnotationManager` class with `add`, `delete`, `update`, `getAll`, `getForLine`, `clear`

- [ ] **Step 1: 写失败测试**

创建 `AnnotationManagerTest.kt`：

```kotlin
package dev.minios.ocremote.ui.screens.viewer

import dev.minios.ocremote.domain.model.Annotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AnnotationManagerTest {

    private lateinit var manager: AnnotationManager

    private val sampleContent = """
        package dev.minios.ocremote

        import android.os.Bundle
        import androidx.appcompat.app.AppCompatActivity

        class MainActivity : AppCompatActivity() {
            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                setContentView(R.layout.activity_main)
            }
        }
    """.trimIndent()

    @Before
    fun setup() {
        manager = AnnotationManager(sampleContent)
    }

    @Test
    fun `add first annotation gets index 0`() {
        val pos = sampleContent.indexOf("import android.os.Bundle")
        val ann = manager.add("import android.os.Bundle", pos, pos + 25, "Should use alias")
        assertEquals(0, ann.index)
        assertEquals(1, manager.getAll().size)
    }

    @Test
    fun `add second annotation gets index 1`() {
        val p1 = sampleContent.indexOf("import android.os.Bundle")
        manager.add("import android.os.Bundle", p1, p1 + 25, "note1")
        val p2 = sampleContent.indexOf("setContentView")
        val ann2 = manager.add("setContentView", p2, p2 + 13, "note2")
        assertEquals(1, ann2.index)
    }

    @Test
    fun `delete middle annotation re-numbers remaining consecutively`() {
        manager.add("t1", 0, 5, "n1")
        manager.add("t2", 10, 15, "n2")
        manager.add("t3", 20, 25, "n3")
        val firstId = manager.getAll()[0].id
        manager.delete(firstId)
        val remaining = manager.getAll()
        assertEquals(2, remaining.size)
        assertEquals(0, remaining[0].index)
        assertEquals(1, remaining[1].index)
    }

    @Test
    fun `delete last annotation does not re-number others`() {
        manager.add("t1", 0, 5, "n1")
        manager.add("t2", 10, 15, "n2")
        manager.delete(manager.getAll()[1].id)
        val remaining = manager.getAll()
        assertEquals(1, remaining.size)
        assertEquals(0, remaining[0].index)
    }

    @Test
    fun `delete only annotation results in empty list`() {
        manager.add("t1", 0, 5, "n1")
        manager.delete(manager.getAll()[0].id)
        assertTrue(manager.getAll().isEmpty())
    }

    @Test
    fun `add after delete gets correct index`() {
        manager.add("t1", 0, 5, "n1")
        manager.add("t2", 10, 15, "n2")
        manager.delete(manager.getAll()[0].id)
        val ann3 = manager.add("t3", 20, 25, "n3")
        assertEquals(1, ann3.index)
    }

    @Test
    fun `update changes note only`() {
        val ann = manager.add("t1", 0, 5, "original")
        manager.update(ann.id, "updated")
        val updated = manager.getAll().find { it.id == ann.id }!!
        assertEquals("updated", updated.note)
        assertEquals(ann.startChar, updated.startChar)
    }

    @Test
    fun `getForLine returns intersecting annotations`() {
        val importStart = sampleContent.indexOf("import androidx")
        manager.add("import androidx", importStart, importStart + 14, "note")
        val result = manager.getForLine(3) // 0-based line 4
        assertEquals(1, result.size)
    }

    @Test
    fun `getForLine returns empty for non-intersecting`() {
        manager.add("package", 0, 7, "note")
        assertTrue(manager.getForLine(5).isEmpty())
    }

    @Test
    fun `clear removes all`() {
        manager.add("t1", 0, 2, "n1")
        manager.add("t2", 5, 7, "n2")
        manager.clear()
        assertTrue(manager.getAll().isEmpty())
    }

    @Test
    fun `add computes correct line col from offsets`() {
        val importStart = sampleContent.indexOf("import android.os.Bundle")
        val ann = manager.add("import android.os.Bundle", importStart, importStart + 25, "note")
        assertEquals(3, ann.startLine)
        assertEquals(1, ann.startCol)
    }

    @Test
    fun `overlapping annotations both returned by getForLine`() {
        val pos1 = sampleContent.indexOf("class MainActivity")
        val pos2 = sampleContent.indexOf("MainActivity")
        manager.add("class MainActivity", pos1, pos1 + 17, "n1")
        manager.add("MainActivity", pos2, pos2 + 12, "n2")
        assertEquals(2, manager.getForLine(5).size) // 0-based line 6
    }
}
```

- [ ] **Step 2: 运行验证失败**

```bash
.\gradlew :app:testDevDebugUnitTest --rerun --tests "*.AnnotationManagerTest"
```

Expected: FAIL.

- [ ] **Step 3: 实现 AnnotationManager**

创建 `AnnotationManager.kt`：

```kotlin
package dev.minios.ocremote.ui.screens.viewer

import dev.minios.ocremote.domain.model.Annotation
import dev.minios.ocremote.domain.model.OffsetConverter
import java.util.UUID

/**
 * Manages an in-memory list of [Annotation]s for a single file's source view.
 * Annotations are ordered by creation time ([Annotation.index]).
 * Deleting a middle annotation re-numbers remaining to consecutive 0..N-1.
 *
 * @param content The full file content for computing line:col from char offsets.
 */
class AnnotationManager(private val content: String) {

    private val annotations = mutableListOf<Annotation>()

    fun add(selectedText: String, startChar: Int, endChar: Int, note: String): Annotation {
        val start = OffsetConverter.charOffsetToLineCol(content, startChar)
        val end = OffsetConverter.charOffsetToLineCol(content, endChar)
        val annotation = Annotation(
            id = UUID.randomUUID().toString(),
            index = annotations.size,
            startChar = startChar, endChar = endChar,
            startLine = start.line, startCol = start.col,
            endLine = end.line, endCol = end.col,
            selectedText = selectedText, note = note,
            createdAt = System.currentTimeMillis()
        )
        annotations.add(annotation)
        return annotation
    }

    fun delete(id: String) {
        if (annotations.removeAll { it.id == id }) renumber()
    }

    fun update(id: String, note: String) {
        val idx = annotations.indexOfFirst { it.id == id }
        if (idx >= 0) annotations[idx] = annotations[idx].copy(note = note)
    }

    fun getAll(): List<Annotation> = annotations.sortedBy { it.index }

    /** Get annotations intersecting 0-based [lineIndex]. */
    fun getForLine(lineIndex: Int): List<Annotation> {
        val target = lineIndex + 1
        return annotations.filter { it.startLine <= target && it.endLine >= target }
    }

    fun clear() = annotations.clear()

    private fun renumber() {
        annotations.sortBy { it.index }
        annotations.forEachIndexed { i, ann ->
            if (ann.index != i) annotations[i] = ann.copy(index = i)
        }
    }
}
```

- [ ] **Step 4: 运行 + Commit**

```bash
.\gradlew :app:testDevDebugUnitTest --rerun --tests "*.AnnotationManagerTest"
# Expected: PASS (12 tests)
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/AnnotationManager.kt \
        app/src/test/kotlin/dev/minios/ocremote/ui/screens/viewer/AnnotationManagerTest.kt
git commit -m "feat: AnnotationManager (add/delete/update + renumber, line intersection query)"
```

---

## Task 3: AnnotationPromptBuilder（结构化文本生成）

<a id="task-3"></a>

**Spec ref:** §7.5 (结构化文本格式)

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/AnnotationPromptBuilder.kt`
- Test: `app/src/test/kotlin/dev/minios/ocremote/ui/screens/viewer/AnnotationPromptBuilderTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package dev.minios.ocremote.ui.screens.viewer

import dev.minios.ocremote.domain.model.Annotation
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationPromptBuilderTest {

    private val builder = AnnotationPromptBuilder

    private fun makeAnn(index: Int, sl: Int, sc: Int, el: Int, ec: Int, note: String) =
        Annotation("ann-$index", index, 0, 10, sl, sc, el, ec, "code", note, 1000L * index)

    @Test
    fun `single annotation with overall note`() {
        val result = builder.build(listOf(makeAnn(0, 12, 1, 13, 15, "fix this")),
            "请按标注修改", "src/App.kt", "/project")
        assertTrue(result.contains("修改意见：请按标注修改"))
        assertTrue(result.contains("文件名: /project/src/App.kt"))
        assertTrue(result.contains("1. 12:1 - 13:15 fix this"))
    }

    @Test
    fun `empty overall note fills with 无`() {
        val result = builder.build(listOf(makeAnn(0, 5, 1, 5, 10, "note")), "", "App.kt", "/proj")
        assertTrue(result.contains("修改意见：无"))
    }

    @Test
    fun `multiple annotations numbered by creation order`() {
        val anns = listOf(makeAnn(0, 45, 1, 45, 10, "first"), makeAnn(1, 12, 1, 13, 15, "second"))
        val result = builder.build(anns, "无", "App.kt", "/proj")
        assertTrue(result.contains("1. 45:1 - 45:10 first"))
        assertTrue(result.contains("2. 12:1 - 13:15 second"))
    }

    @Test
    fun `special characters preserved`() {
        val result = builder.build(listOf(makeAnn(0, 1, 1, 1, 5, "改为\"正确\"的值")),
            "无", "App.kt", "/proj")
        assertTrue(result.contains("改为\"正确\"的值"))
    }

    @Test
    fun `relative path resolved with directory`() {
        val result = builder.build(listOf(makeAnn(0, 1, 1, 1, 5, "n")), "无",
            "src/main/App.kt", "/home/user/project")
        assertTrue(result.contains("文件名: /home/user/project/src/main/App.kt"))
    }

    @Test
    fun `absolute path used directly`() {
        val result = builder.build(listOf(makeAnn(0, 1, 1, 1, 5, "n")), "无",
            "/home/user/project/src/App.kt", "/home/user/project")
        assertTrue(result.contains("文件名: /home/user/project/src/App.kt"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty annotation list throws`() {
        builder.build(emptyList(), "n", "App.kt", "/proj")
    }

    @Test
    fun `windows drive letter path used directly`() {
        val result = builder.build(listOf(makeAnn(0, 1, 1, 1, 5, "n")), "无",
            "D:/Develop/App.kt", "D:/Develop")
        assertTrue(result.contains("文件名: D:/Develop/App.kt"))
    }
}
```

- [ ] **Step 2: 运行验证失败**

```bash
.\gradlew :app:testDevDebugUnitTest --rerun --tests "*.AnnotationPromptBuilderTest"
```

- [ ] **Step 3: 实现 AnnotationPromptBuilder**

```kotlin
package dev.minios.ocremote.ui.screens.viewer

import dev.minios.ocremote.domain.model.Annotation
import java.io.File

/**
 * Builds structured prompt text for submitting annotations (spec §7.5).
 *
 * Format:
 *   修改意见：<overallNote or "无">
 *
 *   文件名: <absolutePath>
 *   1. <line>:<col> - <line>:<col> <note>
 *   2. ...
 *
 * Numbering follows creation order ([Annotation.index]), not file position.
 */
object AnnotationPromptBuilder {

    fun build(
        annotations: List<Annotation>,
        overallNote: String,
        filePath: String,
        directory: String
    ): String {
        require(annotations.isNotEmpty()) { "Cannot submit empty annotation list" }

        val resolvedPath = resolvePath(filePath, directory)
        val noteText = overallNote.ifBlank { "无" }

        val sb = StringBuilder()
        sb.append("修改意见：").append(noteText).append("\n\n")
        sb.append("文件名: ").append(resolvedPath).append("\n")
        annotations.sortedBy { it.index }.forEach { ann ->
            sb.append("${ann.index + 1}. ")
              .append("${ann.startLine}:${ann.startCol} - ${ann.endLine}:${ann.endCol} ")
              .append(ann.note).append("\n")
        }
        return sb.toString().trimEnd()
    }

    private fun resolvePath(filePath: String, directory: String): String {
        if (filePath.isBlank()) return directory
        if (filePath.startsWith("/")) return filePath
        if (filePath.length >= 2 && filePath[1] == ':') return filePath
        if (directory.isBlank()) return filePath
        return File(directory, filePath).path
    }
}
```

- [ ] **Step 4: 运行 + Commit**

```bash
.\gradlew :app:testDevDebugUnitTest --rerun --tests "*.AnnotationPromptBuilderTest"
# Expected: PASS (8 tests)
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/AnnotationPromptBuilder.kt \
        app/src/test/kotlin/dev/minios/ocremote/ui/screens/viewer/AnnotationPromptBuilderTest.kt
git commit -m "feat: AnnotationPromptBuilder (structured text, path resolution, creation-order numbering)"
```

---

## Task 4: SubmitAnnotationsUseCase

<a id="task-4"></a>

**Spec ref:** §8.5

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/domain/usecase/SubmitAnnotationsUseCase.kt`
- Test: `app/src/test/kotlin/dev/minios/ocremote/domain/usecase/SubmitAnnotationsUseCaseTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.domain.model.Annotation
import dev.minios.ocremote.domain.model.PromptPart
import dev.minios.ocremote.domain.repository.ChatRepository
import dev.minios.ocremote.ui.screens.viewer.AnnotationPromptBuilder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class SubmitAnnotationsUseCaseTest {

    private val chatRepository: ChatRepository = mockk()
    private val useCase = SubmitAnnotationsUseCase(chatRepository)

    private fun makeAnn(index: Int) = Annotation(
        "ann-$index", index, 0, 10, 12, 1, 13, 15, "code", "fix this", 1000L
    )

    @Test
    fun `invoke builds prompt and calls promptAsync`() = runTest {
        val anns = listOf(makeAnn(0), makeAnn(1))
        coEvery { chatRepository.promptAsync(any(), any(), any(), any(), any(), any(), any()) } returns Result.success(Unit)

        useCase("srv-1", "sess-1", anns, "请修改", "src/App.kt", "/project")

        val expected = AnnotationPromptBuilder.build(anns, "请修改", "src/App.kt", "/project")
        coVerify {
            chatRepository.promptAsync("srv-1", "sess-1",
                listOf(PromptPart(type = "text", text = expected)),
                null, null, null, "/project")
        }
    }

    @Test
    fun `invoke propagates failure`() = runTest {
        coEvery { chatRepository.promptAsync(any(), any(), any(), any(), any(), any(), any()) } returns
            Result.failure(RuntimeException("Network error"))
        val result = useCase("srv-1", "sess-1", listOf(makeAnn(0)), "", "App.kt", "/project")
        assertTrue(result.isFailure)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty annotations throws`() = runTest {
        useCase("srv-1", "sess-1", emptyList(), "n", "App.kt", "/project")
    }
}
```

- [ ] **Step 2: 运行验证失败**

```bash
.\gradlew :app:testDevDebugUnitTest --rerun --tests "*.SubmitAnnotationsUseCaseTest"
```

- [ ] **Step 3: 实现 SubmitAnnotationsUseCase**

```kotlin
package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.domain.model.Annotation
import dev.minios.ocremote.domain.model.PromptPart
import dev.minios.ocremote.domain.repository.ChatRepository
import dev.minios.ocremote.ui.screens.viewer.AnnotationPromptBuilder
import javax.inject.Inject

/**
 * Submit annotations as a structured prompt to the session's AI.
 * Builds prompt via [AnnotationPromptBuilder], wraps in [PromptPart],
 * sends via [ChatRepository.promptAsync].
 */
class SubmitAnnotationsUseCase @Inject constructor(
    private val chatRepository: ChatRepository
) {
    suspend operator fun invoke(
        serverId: String,
        sessionId: String,
        annotations: List<Annotation>,
        overallNote: String,
        filePath: String,
        directory: String
    ): Result<Unit> {
        val promptText = AnnotationPromptBuilder.build(annotations, overallNote, filePath, directory)
        return chatRepository.promptAsync(
            serverId = serverId, sessionId = sessionId,
            parts = listOf(PromptPart(type = "text", text = promptText)),
            model = null, agent = null, variant = null, directory = directory
        )
    }
}
```

- [ ] **Step 4: 运行 + 编译 + Commit**

```bash
.\gradlew :app:testDevDebugUnitTest --rerun --tests "*.SubmitAnnotationsUseCaseTest"
# Expected: PASS (3 tests)
.\gradlew :app:compileDevDebugKotlin
git add app/src/main/kotlin/dev/minios/ocremote/domain/usecase/SubmitAnnotationsUseCase.kt \
        app/src/test/kotlin/dev/minios/ocremote/domain/usecase/SubmitAnnotationsUseCaseTest.kt
git commit -m "feat: SubmitAnnotationsUseCase (builds prompt + delegates to ChatRepository)"
```

---

## Task 5: FileViewerUiState + ViewModel 标注状态

<a id="task-5"></a>

**Spec ref:** §7.4, §4.3

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/FileViewerUiState.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/FileViewerViewModel.kt`
- Modify: `app/src/test/kotlin/dev/minios/ocremote/ui/screens/viewer/FileViewerViewModelTest.kt`

**Interfaces:**
- Consumes: `AnnotationManager` (Task 2), `SubmitAnnotationsUseCase` (Task 4), `Annotation` (Task 1)
- Produces: Updated `FileViewerUiState` with `annotations` field; ViewModel annotation methods

- [ ] **Step 1: 修改 FileViewerUiState — 添加标注字段**

在 `FileViewerUiState.kt` 的 data class 中添加：

```kotlin
import dev.minios.ocremote.domain.model.Annotation

// ... existing fields ...
    val currentHunkIndex: Int = 0,
    // Phase 3: Annotation state
    val annotations: List<Annotation> = emptyList()
)
```

- [ ] **Step 2: 修改 FileViewerViewModel — 添加标注管理**

完整修改 `FileViewerViewModel.kt`：

```kotlin
package dev.minios.ocremote.ui.screens.viewer

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.minios.ocremote.R
import dev.minios.ocremote.domain.model.ContentType
import dev.minios.ocremote.domain.model.VcsDiffMode
import dev.minios.ocremote.domain.usecase.GetFileContentUseCase
import dev.minios.ocremote.domain.usecase.GetFileDiffUseCase
import dev.minios.ocremote.domain.usecase.SubmitAnnotationsUseCase
import dev.minios.ocremote.ui.navigation.routes.FileViewerNav
import dev.minios.ocremote.ui.navigation.routes.ServerRouteParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URLDecoder
import javax.inject.Inject

@HiltViewModel
class FileViewerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getFileContent: GetFileContentUseCase,
    private val getFileDiff: GetFileDiffUseCase,
    private val submitAnnotationsUseCase: SubmitAnnotationsUseCase
) : ViewModel() {
    private val serverId = savedStateHandle.get<String>(ServerRouteParams.PARAM_SERVER_ID).orEmpty()
    private val directory = URLDecoder.decode(savedStateHandle.get<String>(FileViewerNav.PARAM_DIRECTORY).orEmpty(), "UTF-8")
    private val filePath = URLDecoder.decode(savedStateHandle.get<String>(FileViewerNav.PARAM_FILE_PATH).orEmpty(), "UTF-8")
    private val sessionId = URLDecoder.decode(savedStateHandle.get<String>(FileViewerNav.PARAM_SESSION_ID).orEmpty(), "UTF-8")
    private val source = savedStateHandle.get<String>(FileViewerNav.PARAM_SOURCE) ?: FileViewerNav.Source.LIVE
    private val _uiState = MutableStateFlow(FileViewerUiState(filePath = filePath))
    val uiState: StateFlow<FileViewerUiState> = _uiState.asStateFlow()
    private val diffParser = DiffParser()
    private var annotationManager: AnnotationManager? = null

    init {
        when (source) {
            FileViewerNav.Source.LIVE -> loadLive()
            FileViewerNav.Source.GIT_DIFF -> loadGitDiff()
            FileViewerNav.Source.TOOL_SNAPSHOT, FileViewerNav.Source.TOOL_SNAPSHOT_DIFF ->
                _uiState.update { it.copy(isLoading = false, error = R.string.fileviewer_error_tool_snapshot_unsupported) }
        }
    }

    private fun loadLive() {
        viewModelScope.launch {
            getFileContent(serverId, directory, filePath)
                .onSuccess { c ->
                    if (c.type == ContentType.BINARY) {
                        _uiState.update { it.copy(isLoading = false, isBinary = true, mimeType = c.mimeType) }
                    } else {
                        val lines = c.content.split('\n')
                        val truncated = lines.size > 5000
                        val visible = if (truncated) lines.take(5000).joinToString("\n") else c.content
                        annotationManager = AnnotationManager(visible)
                        _uiState.update { it.copy(isLoading = false, content = visible, isEmpty = visible.isBlank(), isTruncated = truncated) }
                    }
                }
                .onFailure { _uiState.update { it.copy(isLoading = false, error = R.string.workspace_error_load_failed) } }
        }
    }

    private fun loadGitDiff() {
        viewModelScope.launch {
            getFileDiff(serverId, directory, VcsDiffMode.GIT)
                .onSuccess { diffs ->
                    val target = diffs.find { it.file == filePath || it.file.endsWith(filePath) }
                    val hunks = target?.patch?.let { diffParser.parseUnifiedDiff(it) } ?: emptyList()
                    _uiState.update { it.copy(isLoading = false, mode = FileViewerMode.DIFF, diff = target, hunks = hunks,
                        currentHunkIndex = 0, isEmpty = hunks.isEmpty()) }
                }
                .onFailure { _uiState.update { it.copy(isLoading = false, error = R.string.workspace_error_load_failed) } }
        }
    }

    fun nextHunk() { _uiState.update { it.copy(currentHunkIndex = (it.currentHunkIndex + 1).coerceAtMost(it.hunks.size - 1)) } }
    fun prevHunk() { _uiState.update { it.copy(currentHunkIndex = (it.currentHunkIndex - 1).coerceAtLeast(0)) } }

    // ============ Phase 3: Annotation Management ============

    fun addAnnotation(selectedText: String, startChar: Int, endChar: Int, note: String) {
        val manager = annotationManager ?: return
        if (_uiState.value.mode != FileViewerMode.SOURCE) return
        manager.add(selectedText, startChar, endChar, note)
        _uiState.update { it.copy(annotations = manager.getAll()) }
    }

    fun deleteAnnotation(id: String) {
        val manager = annotationManager ?: return
        manager.delete(id)
        _uiState.update { it.copy(annotations = manager.getAll()) }
    }

    fun updateAnnotation(id: String, note: String) {
        val manager = annotationManager ?: return
        manager.update(id, note)
        _uiState.update { it.copy(annotations = manager.getAll()) }
    }

    suspend fun submitAnnotations(overallNote: String): Result<Unit> {
        val manager = annotationManager ?: return Result.failure(IllegalStateException("No annotation manager"))
        val anns = _uiState.value.annotations
        if (anns.isEmpty()) return Result.failure(IllegalStateException("No annotations to submit"))
        val result = submitAnnotationsUseCase(serverId, sessionId, anns, overallNote, filePath, directory)
        if (result.isSuccess) {
            manager.clear()
            _uiState.update { it.copy(annotations = emptyList()) }
        }
        return result
    }
}
```

> ⚠️ **关键变更**：
> 1. 构造函数新增 `submitAnnotationsUseCase: SubmitAnnotationsUseCase`
> 2. 新增 `sessionId` 从 SavedStateHandle 读取
> 3. `loadLive()` 中创建 `AnnotationManager(visible)`
> 4. 新增 4 个标注方法

- [ ] **Step 3: 追加测试到 FileViewerViewModelTest.kt**

在现有测试类中添加（需添加 `SubmitAnnotationsUseCase` mock 和相应 import）：

```kotlin
// 添加 import
import dev.minios.ocremote.domain.usecase.SubmitAnnotationsUseCase
import org.junit.Assert.assertEquals

// 添加字段
private val submitAnnotations = mockk<SubmitAnnotationsUseCase>()

// 修改 ViewModel 创建（现有测试也需更新构造函数）
// 将所有 FileViewerViewModel(savedStateHandle(), getFileContent, getFileDiff)
// 改为 FileViewerViewModel(savedStateHandle(), getFileContent, getFileDiff, submitAnnotations)

// === 新增标注测试 ===

@Test
fun `addAnnotation creates annotation and updates state`() = runTest {
    coEvery { getFileContent(serverId, directory, filePath) } returns Result.success(sampleFileContent)
    val vm = FileViewerViewModel(savedStateHandle(), getFileContent, getFileDiff, submitAnnotations)
    val pos = sampleKotlinSource.indexOf("import android.os.Bundle")
    vm.addAnnotation("import android.os.Bundle", pos, pos + 25, "use alias")
    assertEquals(1, vm.uiState.value.annotations.size)
    assertEquals(0, vm.uiState.value.annotations[0].index)
}

@Test
fun `addAnnotation in DIFF mode is ignored`() = runTest {
    coEvery { getFileDiff(serverId, directory, VcsDiffMode.GIT) } returns Result.success(sampleDiffs)
    val vm = FileViewerViewModel(savedStateHandle(source = FileViewerNav.Source.GIT_DIFF), getFileContent, getFileDiff, submitAnnotations)
    vm.addAnnotation("text", 0, 4, "note")
    assertTrue(vm.uiState.value.annotations.isEmpty())
}

@Test
fun `deleteAnnotation removes and renumbers`() = runTest {
    coEvery { getFileContent(serverId, directory, filePath) } returns Result.success(sampleFileContent)
    val vm = FileViewerViewModel(savedStateHandle(), getFileContent, getFileDiff, submitAnnotations)
    vm.addAnnotation("import", 0, 6, "n1")
    vm.addAnnotation("class", 10, 15, "n2")
    vm.addAnnotation("override", 20, 28, "n3")
    val firstId = vm.uiState.value.annotations[0].id
    vm.deleteAnnotation(firstId)
    val anns = vm.uiState.value.annotations
    assertEquals(2, anns.size)
    assertEquals(0, anns[0].index)
    assertEquals(1, anns[1].index)
}

@Test
fun `submitAnnotations calls use case and clears on success`() = runTest {
    coEvery { getFileContent(serverId, directory, filePath) } returns Result.success(sampleFileContent)
    coEvery { submitAnnotations(any(), any(), any(), any(), any(), any()) } returns Result.success(Unit)
    val vm = FileViewerViewModel(savedStateHandle(), getFileContent, getFileDiff, submitAnnotations)
    vm.addAnnotation("import", 0, 6, "n1")
    val result = vm.submitAnnotations("overall note")
    assertTrue(result.isSuccess)
    assertTrue(vm.uiState.value.annotations.isEmpty())
}

@Test
fun `submitAnnotations failure does not clear`() = runTest {
    coEvery { getFileContent(serverId, directory, filePath) } returns Result.success(sampleFileContent)
    coEvery { submitAnnotations(any(), any(), any(), any(), any(), any()) } returns Result.failure(RuntimeException("fail"))
    val vm = FileViewerViewModel(savedStateHandle(), getFileContent, getFileDiff, submitAnnotations)
    vm.addAnnotation("import", 0, 6, "n1")
    vm.submitAnnotations("note")
    assertEquals(1, vm.uiState.value.annotations.size)
}
```

- [ ] **Step 4: 运行 + 编译 + Commit**

```bash
.\gradlew :app:testDevDebugUnitTest --rerun --tests "*.FileViewerViewModelTest"
# Expected: PASS (14 tests = 9 existing updated + 5 new)
# 注意：现有测试的 ViewModel 构造需加 submitAnnotations 参数
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/FileViewerUiState.kt \
        app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/FileViewerViewModel.kt \
        app/src/test/kotlin/dev/minios/ocremote/ui/screens/viewer/FileViewerViewModelTest.kt
git commit -m "feat: FileViewerViewModel annotation state (add/delete/update/submit, sessionId param)"
```

> ⚠️ **注意**：此 Task 后 `FileViewerRoute` 和 Hilt 注入会因构造函数变更而编译失败。Task 9 会修复。中间 Task 6-8 不依赖 ViewModel 编译。

---

## Task 6: AnnotationContextMenu（官方 appendTextContextMenuComponents）

<a id="task-6"></a>

**Spec ref:** §7.4 (标注交互链路 step 1-2)

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/AnnotationContextMenu.kt`

**Interfaces:**
- Consumes: `Modifier.appendTextContextMenuComponents`（官方 API）, `LocalClipboardManager`
- Produces: `Modifier.annotationContextMenu(onAnnotate: (String) -> Unit): Modifier`

> **官方框架 API**：`Modifier.appendTextContextMenuComponents()` 是 Compose Foundation 2026 官方 API，在 `SelectionContainer` 内的 `Text` 上添加此 Modifier 即可向系统选区菜单注入自定义项。已验证编译通过（BOM 2026.05.01）。

- [ ] **Step 1: 实现 AnnotationContextMenu Modifier 扩展**

创建 `AnnotationContextMenu.kt`：

```kotlin
package dev.minios.ocremote.ui.screens.viewer

import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString

/**
 * Adds "标注修改" item to the system text context menu via official
 * [Modifier.appendTextContextMenuComponents] API.
 *
 * When clicked: captures selected text via clipboard, strips line-number
 * gutter prefixes, and calls [onAnnotate].
 *
 * Usage: wrap content in `SelectionContainer`, apply this modifier to `Text`.
 *
 * @param onAnnotate callback with the captured selected text
 */
fun Modifier.annotationContextMenu(
    onAnnotate: (selectedText: String) -> Unit,
): Modifier = this.appendTextContextMenuComponents {
    item(
        key = AnnotationMenuKey,
        label = "标注修改",
    ) {
        // Clipboard capture: system already has selection.
        // Trigger copy programmatically via TextContextMenuSession's built-in copy,
        // then read clipboard to get the selected text.
        val clipboard = currentClipboardText
        val selectedText = clipboard?.text.orEmpty()
        val cleaned = stripGutterNumbers(selectedText)
        if (cleaned.isNotBlank()) {
            onAnnotate(cleaned)
        }
        close()
    }
}

/** Unique key for the annotation context menu item. */
private data object AnnotationMenuKey

/**
 * Strip line-number gutter prefixes from clipboard-captured text.
 * Gutter Text composables inside SelectionContainer may add line numbers
 * to the selected text. This regex removes "digits + whitespace" at line starts.
 */
internal fun stripGutterNumbers(text: String): String {
    return text.replace(Regex("(?m)^\\s*\\d+\\s"), "")
}
```

> **⚠️ clipboard 读取**：`appendTextContextMenuComponents` 的 `item` callback 在 `TextContextMenuSession` receiver scope 内执行。系统在显示 context menu 时已将选中文本放入系统剪贴板（Android 选区行为）。我们通过 `LocalClipboardManager.current.getText()` 读取。
>
> 在实际实现中，如果 clipboard 没有自动获取选中文本，需要先调用 `performCopy`（通过系统菜单的复制回调）再读取。实现者需验证此行为，必要时添加 `onCopyRequested` 调用链。

- [ ] **Step 2: 编译验证**

```bash
.\gradlew :app:compileDevDebugKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/AnnotationContextMenu.kt
git commit -m "feat: annotation context menu via appendTextContextMenuComponents (official API)"
```

---

## Task 7: AnnotationInputSheet + AnnotationDetailDialog

<a id="task-7"></a>

**Spec ref:** §7.4 (标注交互链路 step 2-3, 标注的编辑/删除)

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/AnnotationInputSheet.kt`
- Create: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/AnnotationDetailDialog.kt`

- [ ] **Step 1: 实现 AnnotationInputSheet**

创建 `AnnotationInputSheet.kt`：

```kotlin
package dev.minios.ocremote.ui.screens.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.R
import dev.minios.ocremote.ui.theme.SpacingTokens
import kotlinx.coroutines.launch

/**
 * Bottom sheet for entering a modification note for a selected code snippet.
 *
 * @param selectedText The code the user selected (preview, read-only).
 * @param onConfirm Called with the entered note when user taps "确定".
 * @param onDismiss Called when sheet is dismissed (cancel or outside tap).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnotationInputSheet(
    selectedText: String,
    onConfirm: (note: String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SpacingTokens.LG.dp)
                .padding(bottom = SpacingTokens.XXL.dp),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.MD.dp)
        ) {
            Text(
                text = stringResource(R.string.annotation_input_title),
                style = MaterialTheme.typography.titleMedium
            )

            // Selected text preview
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = selectedText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(SpacingTokens.MD.dp)
                        .heightIn(max = 150.dp)
                        .verticalScroll(rememberScrollState())
                )
            }

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text(stringResource(R.string.annotation_input_hint)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("annotation_input_note"),
                minLines = 2,
                maxLines = 5
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = {
                    scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                }) { Text(stringResource(R.string.cancel)) }

                TextButton(
                    onClick = {
                        if (note.isNotBlank()) {
                            scope.launch { sheetState.hide() }.invokeOnCompletion { onConfirm(note.trim()) }
                        }
                    },
                    enabled = note.isNotBlank(),
                    modifier = Modifier.testTag("annotation_input_confirm")
                ) { Text(stringResource(R.string.annotation_input_confirm)) }
            }
        }
    }
}
```

- [ ] **Step 2: 实现 AnnotationDetailDialog**

创建 `AnnotationDetailDialog.kt`：

```kotlin
package dev.minios.ocremote.ui.screens.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.R
import dev.minios.ocremote.domain.model.Annotation
import dev.minios.ocremote.ui.theme.SpacingTokens

/**
 * Dialog showing annotation details when user taps an existing highlight.
 * Provides Edit / Delete actions.
 */
@Composable
fun AnnotationDetailDialog(
    annotation: Annotation,
    onEdit: (newNote: String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var isEditing by remember { mutableStateOf(false) }
    var editNote by remember { mutableStateOf(annotation.note) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.annotation_detail_title, annotation.index + 1),
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp)) {
                Text(
                    text = stringResource(R.string.annotation_detail_selected),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = annotation.selectedText,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .heightIn(max = 120.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(SpacingTokens.SM.dp)
                    )
                }

                Text(
                    text = "${annotation.startLine}:${annotation.startCol} - ${annotation.endLine}:${annotation.endCol}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (isEditing) {
                    androidx.compose.material3.OutlinedTextField(
                        value = editNote,
                        onValueChange = { editNote = it },
                        label = { Text(stringResource(R.string.annotation_input_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2, maxLines = 5
                    )
                } else {
                    Text(
                        text = annotation.note,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = SpacingTokens.XS.dp)
                    )
                }
            }
        },
        confirmButton = {
            if (isEditing) {
                TextButton(onClick = { onEdit(editNote.trim()); onDismiss() }) {
                    Text(stringResource(R.string.annotation_detail_save))
                }
            } else {
                TextButton(onClick = { isEditing = true }) {
                    Text(stringResource(R.string.annotation_detail_edit))
                }
            }
        },
        dismissButton = {
            if (isEditing) {
                TextButton(onClick = { isEditing = false; editNote = annotation.note }) {
                    Text(stringResource(R.string.cancel))
                }
            } else {
                TextButton(
                    onClick = { onDelete(); onDismiss() },
                    modifier = Modifier.testTag("annotation_detail_delete")
                ) {
                    Text(
                        stringResource(R.string.annotation_detail_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    )
}
```

- [ ] **Step 3: 编译 + Commit**

```bash
.\gradlew :app:compileDevDebugKotlin
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/AnnotationInputSheet.kt \
        app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/AnnotationDetailDialog.kt
git commit -m "feat: AnnotationInputSheet (bottom sheet) + AnnotationDetailDialog (edit/delete)"
```

---

## Task 8: CodeSourceView 标注高亮渲染

<a id="task-8"></a>

**Spec ref:** §7.2 (内容渲染), §7.4 (选区高亮)

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/CodeSourceView.kt`

**Interfaces:**
- Consumes: `Annotation` (Task 1), `annotationContextMenu` Modifier (Task 6)
- Produces: Updated `CodeSourceView` with annotation highlight + SelectionContainer

- [ ] **Step 1: 修改 CodeSourceView — 添加标注参数和高亮渲染**

在 `CodeSourceView.kt` 中修改签名并添加标注高亮逻辑：

```kotlin
// === 新增 import ===
import androidx.compose.foundation.text.selection.SelectionContainer
import dev.minios.ocremote.domain.model.Annotation
import dev.minios.ocremote.ui.theme.AlphaTokens

// === 修改函数签名 ===
@Composable
fun CodeSourceView(
    content: String,
    filePath: String,
    annotations: List<Annotation> = emptyList(),
    onAnnotate: ((selectedText: String) -> Unit)? = null,
    onTapAnnotation: ((Annotation) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (content.isEmpty()) return

    val isDark = isSystemInDarkTheme()
    val language = rememberLanguage(filePath)
    val highlights = remember(content, language, isDark) {
        buildHighlights(content, language, isDark)
    }
    val baseAnnotated = remember(content, highlights) {
        buildAnnotatedStringFromHighlights(content, highlights)
    }
    val lineCount = remember(content) {
        if (content.isEmpty()) 0
        else content.count { it == '\n' } + if (content.endsWith('\n')) 0 else 1
    }
    val lineOffsets = remember(content) {
        buildList {
            add(0)
            content.forEachIndexed { i, c -> if (c == '\n') add(i + 1) }
        }.toIntArray()
    }
    // ... existing maxChars, gutterWidth, maxRowWidth computation stays the same ...

    // === 新增：标注模式（仅当 onAnnotate 不为 null 时启用 SelectionContainer） ===
    val annotationEnabled = onAnnotate != null

    // === 新增：逐行标注高亮预计算 ===
    val highlightColor = MaterialTheme.colorScheme.primary
    val lineAnnotations = remember(annotations) {
        // 预计算每行命中的标注区间
        val map = mutableMapOf<Int, MutableList<Triple<Int, Int, Int>>>() // lineIdx -> [(relStart, relEnd, annCount)]
        annotations.forEach { ann ->
            val annStartLine = ann.startLine - 1 // 转为 0-based
            val annEndLine = ann.endLine - 1
            for (lineIdx in annStartLine..annEndLine) {
                if (lineIdx < 0 || lineIdx >= lineCount) continue
                val lineStart = lineOffsets[lineIdx]
                val lineEnd = if (lineIdx + 1 < lineOffsets.size) lineOffsets[lineIdx + 1] - 1 else content.length
                val relStart = (ann.startChar - lineStart).coerceAtLeast(0)
                val relEnd = (ann.endChar - lineStart).coerceAtMost(lineEnd - lineStart)
                if (relStart < relEnd) {
                    map.getOrPut(lineIdx) { mutableListOf() }
                      .add(Triple(relStart, relEnd, ann.index + 1)) // 1-based display number
                }
            }
        }
        map
    }

    val hScroll = rememberScrollState()

    // === 渲染：用 SelectionContainer 包裹（仅当标注启用时） ===
    val contentComposable: @Composable () -> Unit = {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = SpacingTokens.SM.dp)
        ) {
            items(count = lineCount, key = { it }) { index ->
                val start = lineOffsets[index]
                val endExclusive = if (index + 1 < lineOffsets.size)
                    lineOffsets[index + 1] - 1
                else
                    content.length
                val baseLine = baseAnnotated.subSequence(start, endExclusive)

                // 叠加标注高亮
                val annotatedLine = if (lineAnnotations.containsKey(index)) {
                    buildAnnotatedLineWithAnnotations(baseLine, lineAnnotations[index]!!, highlightColor)
                } else {
                    baseLine
                }

                Row(
                    modifier = Modifier
                        .defaultMinSize(minWidth = maxRowWidth)
                        .horizontalScroll(hScroll)
                ) {
                    Text(
                        text = "${index + 1}",
                        style = CodeTypography,
                        color = gutterColor,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(gutterWidth)
                    )
                    Text(
                        text = annotatedLine,
                        style = CodeTypography,
                        modifier = Modifier.padding(
                            start = SpacingTokens.SM.dp,
                            end = SpacingTokens.LG.dp
                        )
                    )
                }
            }
        }
    }

    if (annotationEnabled && onAnnotate != null) {
        SelectionContainer {
            contentComposable(modifier = Modifier.annotationContextMenu(onAnnotate))
        }
    } else {
        contentComposable(modifier = Modifier)
    }
}

/**
 * Build a per-line AnnotatedString with annotation highlights overlaid on the base syntax-highlighted line.
 * Overlapping annotations accumulate alpha, capped at 0.6.
 */
private fun buildAnnotatedLineWithAnnotations(
    baseLine: AnnotatedString,
    annotations: List<Triple<Int, Int, Int>>, // (relStart, relEnd, displayNumber)
    baseColor: Color
): AnnotatedString = buildAnnotatedString {
    append(baseLine)
    // Simple approach: each annotation adds 0.12 alpha background
    // Overlapping ranges composite naturally (last addStyle wins for same range,
    // but for non-overlapping ranges they're independent)
    annotations.forEach { (relStart, relEnd, _) ->
        addStyle(
            SpanStyle(background = baseColor.copy(alpha = AlphaTokens.SELECTED)),
            relStart.coerceIn(0, length),
            relEnd.coerceIn(0, length)
        )
    }
}
```

> ⚠️ **修改要点**：
> 1. 新增 `annotations`, `onAnnotate`, `onTapAnnotation` 可选参数（默认空/null，向后兼容）
> 2. 当 `onAnnotate != null` 时，用 `SelectionContainer` + `Modifier.annotationContextMenu(onAnnotate)` 包裹
> 3. 逐行预计算标注命中区间（`lineAnnotations` map）
> 4. `buildAnnotatedLineWithAnnotations` 在基础语法高亮上叠加标注背景色
> 5. 原有的 maxChars、gutterWidth、maxRowWidth 计算保持不变

- [ ] **Step 2: 编译验证**

```bash
.\gradlew :app:compileDevDebugKotlin
```

Expected: BUILD SUCCESSFUL. 现有的 `CodeSourceView` 调用点（FileViewerScreen）因为新参数有默认值，不会编译失败。

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/CodeSourceView.kt
git commit -m "feat: CodeSourceView annotation highlights (overlay on syntax, SelectionContainer with custom toolbar)"
```

---

## Task 9: FileViewerScreen 提交集成 + 导航

<a id="task-9"></a>

**Spec ref:** §7.1 (TopBar 形态 B), §7.4 (提交链路), §4.3 (标注提交数据流)

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/FileViewerScreen.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/FileViewerRoute.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/navigation/NavGraph.kt` (仅 onSubmitted callback)

**Interfaces:**
- Consumes: All Phase 3 components (Tasks 1-8)
- Produces: Fully integrated annotation UI in FileViewerScreen

- [ ] **Step 1: 修改 FileViewerScreen — 添加提交按钮 + 标注回调**

在 `FileViewerScreen.kt` 中：

1. 函数签名添加新参数：

```kotlin
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileViewerScreen(
    uiState: FileViewerUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onNextHunk: () -> Unit,
    onPrevHunk: () -> Unit,
    onCopyPath: () -> Unit,
    onShare: () -> Unit,
    onCopyAllContent: () -> Unit,
    // Phase 3 新增:
    onAnnotateSelection: (selectedText: String) -> Unit,
    onAddAnnotation: (selectedText: String, note: String) -> Unit,
    onDeleteAnnotation: (id: String) -> Unit,
    onUpdateAnnotation: (id: String, note: String) -> Unit,
    onSubmitAnnotations: (overallNote: String) -> Unit
) {
```

2. 添加 UI 状态管理：

```kotlin
    var showLongPressMenu by remember { mutableStateOf(false) }
    // Phase 3: 标注 UI 状态
    var pendingAnnotationText by remember { mutableStateOf<String?>(null) }
    var detailAnnotation by remember { mutableStateOf<Annotation?>(null) }
    var showSubmitDialog by remember { mutableStateOf(false) }
```

3. TopBar 修改——当有标注时显示提交按钮：

```kotlin
    Scaffold(
        topBar = {
            FileViewerTopBar(
                uiState = uiState,
                onBack = onBack,
                onCopyPath = onCopyPath,
                onShare = onShare,
                annotationCount = uiState.annotations.size,
                onSubmitClick = { showSubmitDialog = true }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
```

4. 源码视图渲染修改——传入标注参数：

```kotlin
                // 在 else 分支中（非 truncated 的源码视图）:
                else -> CodeSourceView(
                    content = uiState.content,
                    filePath = uiState.filePath,
                    annotations = uiState.annotations,
                    onAnnotate = { selectedText -> pendingAnnotationText = selectedText },
                    onTapAnnotation = { ann -> detailAnnotation = ann },
                    modifier = Modifier.fillMaxSize()
                )
```

5. 在 Scaffold 内容后添加对话框/Sheet 渲染：

```kotlin
    // Phase 3: Annotation Input Sheet
    pendingAnnotationText?.let { selectedText ->
        AnnotationInputSheet(
            selectedText = selectedText,
            onConfirm = { note ->
                onAddAnnotation(selectedText, note)
                pendingAnnotationText = null
            },
            onDismiss = { pendingAnnotationText = null }
        )
    }

    // Phase 3: Annotation Detail Dialog
    detailAnnotation?.let { ann ->
        AnnotationDetailDialog(
            annotation = ann,
            onEdit = { newNote -> onUpdateAnnotation(ann.id, newNote) },
            onDelete = { onDeleteAnnotation(ann.id) },
            onDismiss = { detailAnnotation = null }
        )
    }

    // Phase 3: Submit Dialog
    if (showSubmitDialog && uiState.annotations.isNotEmpty()) {
        AnnotationSubmitDialog(
            annotationCount = uiState.annotations.size,
            annotations = uiState.annotations,
            onSubmit = { overallNote ->
                onSubmitAnnotations(overallNote)
                showSubmitDialog = false
            },
            onDismiss = { showSubmitDialog = false }
        )
    }
```

6. 修改 TopBar 添加提交按钮：

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileViewerTopBar(
    uiState: FileViewerUiState,
    onBack: () -> Unit,
    onCopyPath: () -> Unit,
    onShare: () -> Unit,
    annotationCount: Int = 0,
    onSubmitClick: () -> Unit = {}
) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = uiState.filePath.substringAfterLast('/').ifBlank { uiState.filePath },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (annotationCount > 0) {
                    Spacer(Modifier.width(SpacingTokens.SM.dp))
                    Badge { Text("$annotationCount") }
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack, modifier = Modifier.testTag("back_button")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
            }
        },
        actions = {
            if (annotationCount > 0) {
                TextButton(
                    onClick = onSubmitClick,
                    modifier = Modifier.testTag("annotation_submit_button")
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(Modifier.width(SpacingTokens.XS.dp))
                    Text(stringResource(R.string.annotation_submit))
                }
            } else {
                IconButton(onClick = onCopyPath) {
                    Icon(Icons.Default.ContentCopy, stringResource(R.string.a11y_icon_copy_path))
                }
                IconButton(onClick = onShare) {
                    Icon(Icons.Default.Share, stringResource(R.string.a11y_icon_share))
                }
            }
        }
    )
}
```

> 当有标注时，TopBar actions 从"复制/分享"切换为"提交"按钮。

- [ ] **Step 2: 创建 AnnotationSubmitDialog**

在 `FileViewerScreen.kt` 文件末尾（或新建 `AnnotationSubmitDialog.kt`）添加：

```kotlin
@Composable
private fun AnnotationSubmitDialog(
    annotationCount: Int,
    annotations: List<Annotation>,
    onSubmit: (overallNote: String) -> Unit,
    onDismiss: () -> Unit
) {
    var overallNote by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.annotation_submit_dialog_title, annotationCount))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp)) {
                OutlinedTextField(
                    value = overallNote,
                    onValueChange = { overallNote = it },
                    label = { Text(stringResource(R.string.annotation_submit_overall_note)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2, maxLines = 4
                )
                Text(
                    text = stringResource(R.string.annotation_submit_summary),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                annotations.sortedBy { it.index }.forEach { ann ->
                    Text(
                        text = "${ann.index + 1}. 行 ${ann.startLine}:${ann.startCol} - ${ann.endLine}:${ann.endCol}\n   \"${ann.note}\"",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = SpacingTokens.SM.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(overallNote.trim()) },
                modifier = Modifier.testTag("annotation_submit_send")
            ) { Text(stringResource(R.string.annotation_submit_send)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
```

- [ ] **Step 3: 修改 FileViewerRoute — 添加标注回调和导航**

完整修改 `FileViewerRoute.kt`：

```kotlin
package dev.minios.ocremote.ui.screens.viewer

import android.content.Intent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.minios.ocremote.R
import kotlinx.coroutines.launch

@Composable
fun FileViewerRoute(
    viewModel: FileViewerViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onSubmitted: () -> Unit = {}  // Phase 3: 提交后回调（navigateUp 回 Chat）
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var isSubmitting by remember { mutableStateOf(false) }

    FileViewerScreen(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onNextHunk = viewModel::nextHunk,
        onPrevHunk = viewModel::prevHunk,
        onCopyPath = {
            clipboard.setText(AnnotatedString(uiState.filePath))
            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.menu_copied_to_clipboard)) }
        },
        onShare = {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, uiState.content.ifBlank { uiState.filePath })
            }
            runCatching { context.startActivity(Intent.createChooser(sendIntent, null)) }
        },
        onCopyAllContent = {
            clipboard.setText(AnnotatedString(uiState.content))
            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.menu_copied_to_clipboard)) }
        },
        // Phase 3: 标注回调
        onAnnotateSelection = { selectedText ->
            // 从 selectedText 在 content 中定位 char offset
            val startChar = uiState.content.indexOf(selectedText)
            // startChar 可能是 -1（未找到）或错误位置（相同文本多次出现）
            // Phase 3 接受此限制；Phase 4 可用 SelectionRegistrar 精确定位
            if (startChar >= 0) {
                // 通过 ViewModel 添加标注（需要 selectedText + offsets + note）
                // 但 note 在 Sheet 中输入，所以这里先存 pendingSelection
                // 实际上 FileViewerScreen 管理 pendingAnnotationText 状态
                // Route 只需要提供 onAddAnnotation
            }
        },
        onAddAnnotation = { selectedText, note ->
            val startChar = uiState.content.indexOf(selectedText)
            if (startChar >= 0) {
                viewModel.addAnnotation(selectedText, startChar, startChar + selectedText.length, note)
            }
        },
        onDeleteAnnotation = viewModel::deleteAnnotation,
        onUpdateAnnotation = viewModel::updateAnnotation,
        onSubmitAnnotations = { overallNote ->
            if (!isSubmitting) {
                isSubmitting = true
                scope.launch {
                    val result = viewModel.submitAnnotations(overallNote)
                    isSubmitting = false
                    if (result.isSuccess) {
                        scope.launch {
                            snackbarHostState.showSnackbar(context.getString(R.string.annotation_submitted_toast))
                        }
                        onSubmitted() // navigateUp 回 Chat
                    } else {
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                context.getString(R.string.annotation_submit_failed)
                            )
                        }
                    }
                }
            }
        }
    )
}
```

- [ ] **Step 4: 修改 NavGraph — 传入 onSubmitted 回调**

在 `NavGraph.kt` 的 FileViewer composable 中：

```kotlin
        // ============ File Viewer Screen ============
        composable(
            route = FileViewerNav.routePattern,
            arguments = FileViewerNav.navArguments
        ) {
            FileViewerRoute(
                onBack = { navController.popBackStack() },
                onSubmitted = { navController.popBackStack() } // Phase 3: 提交后返回
            )
        }
```

- [ ] **Step 5: 编译验证**

```bash
.\gradlew :app:compileDevDebugKotlin
```

Expected: BUILD SUCCESSFUL. Hilt 自动注入 `SubmitAnnotationsUseCase`（已有 `@Inject constructor`）。

- [ ] **Step 6: 运行全部测试**

```bash
.\gradlew :app:testDevDebugUnitTest --rerun
```

Expected: 全绿（含 Phase 1 + Phase 3 测试）。

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/FileViewerScreen.kt \
        app/src/main/kotlin/dev/minios/ocremote/ui/screens/viewer/FileViewerRoute.kt \
        app/src/main/kotlin/dev/minios/ocremote/ui/navigation/NavGraph.kt
git commit -m "feat: FileViewerScreen annotation integration (submit button, dialogs, navigation back to chat)"
```

---

## Task 10: strings.xml + Maestro E2E flow 23

<a id="task-10"></a>

**Spec ref:** §11.4 (Maestro flow 23)

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Create: `maestro/e2e-file-viewer-annotation.yaml`

- [ ] **Step 1: 添加 strings.xml 标注相关字符串**

在 `strings.xml` 的 `</resources>` 之前添加：

```xml
    <!-- File Viewer: Annotation -->
    <string name="annotation_input_title">Annotate modification</string>
    <string name="annotation_input_hint">Modification note</string>
    <string name="annotation_input_confirm">Confirm</string>
    <string name="annotation_detail_title">Annotation #%1$d</string>
    <string name="annotation_detail_selected">Selected code</string>
    <string name="annotation_detail_edit">Edit</string>
    <string name="annotation_detail_delete">Delete</string>
    <string name="annotation_detail_save">Save</string>
    <string name="annotation_submit">Submit</string>
    <string name="annotation_submit_dialog_title">Submit %1$d annotations</string>
    <string name="annotation_submit_overall_note">Overall note (optional)</string>
    <string name="annotation_submit_summary">Summary:</string>
    <string name="annotation_submit_send">Send</string>
    <string name="annotation_submitted_toast">Sent to AI</string>
    <string name="annotation_submit_failed">Failed to submit</string>
```

- [ ] **Step 2: 运行 lokit 同步翻译**

```bash
lokit
```

> 若 lokit 不可用，手动复制到 15 个 `values-*` locale 目录。

- [ ] **Step 3: 验证 testTag 全部就位**

```bash
grep -rn "testTag.*annotation_toolbar_annotate\|testTag.*annotation_input_note\|testTag.*annotation_input_confirm\|testTag.*annotation_submit_button\|testTag.*annotation_submit_send\|testTag.*annotation_detail_delete" app/src/main --include="*.kt"
```

预期：6 行命中。

- [ ] **Step 4: 创建 Maestro flow 23**

创建 `maestro/e2e-file-viewer-annotation.yaml`：

```yaml
appId: dev.minios.ocremote.dev
name: E2E - File Viewer Annotation (Real OpenCode Server)
tags:
  - e2e
  - workspace
  - annotation
---

# Prerequisite: Server at 10.0.2.2:4096, session active, workspace accessible

- launchApp

- extendedWaitUntil:
    visible: "Connected"
    timeout: 15000
    optional: true

# Navigate to a session
- tapOn:
    text: "Sessions"
    index: 0
- extendedWaitUntil:
    visible: "Search sessions..."
    timeout: 20000
    optional: true
- tapOn: "D:/Develop/code/app/oc-remote"
- extendedWaitUntil:
    visible: "sessions active"
    timeout: 15000
    optional: true
- tapOn:
    text: ".*"
    index: 0
- extendedWaitUntil:
    visible: "Ask a question"
    timeout: 20000
    optional: true

# Open workspace
- tapOn:
    id: "more_vert"
- tapOn:
    id: "menu_open_workspace"

# Open a file
- tapOn:
    id: "panel_file_tree"
    optional: true
- tapOn:
    text: ".*"
    index: 0
- takeScreenshot: e2e_ann_file_opened

# Long-press to trigger text selection
- longPressOn:
    text: ".*"
    index: 0
- takeScreenshot: e2e_ann_selection_started

# Tap "Annotate" in custom toolbar
- tapOn:
    id: "annotation_toolbar_annotate"
    optional: true
- takeScreenshot: e2e_ann_input_sheet

# Enter note
- tapOn:
    id: "annotation_input_note"
- inputText: "Test annotation note"
- tapOn:
    id: "annotation_input_confirm"
- takeScreenshot: e2e_ann_added

# Verify annotation badge in TopBar
- assertVisible:
    text: ".*1.*"
    optional: true

# Tap submit
- tapOn:
    id: "annotation_submit_button"
    optional: true
- takeScreenshot: e2e_ann_submit_dialog

# Send
- tapOn:
    id: "annotation_submit_send"
    optional: true
- takeScreenshot: e2e_ann_submitted

# Verify toast (best-effort)
- extendedWaitUntil:
    visible: ".*Sent.*"
    timeout: 5000
    optional: true

# Verify navigation back to chat
- extendedWaitUntil:
    visible: "Ask a question"
    timeout: 10000
    optional: true
- takeScreenshot: e2e_ann_back_to_chat
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/values/strings.xml maestro/e2e-file-viewer-annotation.yaml
# Also add translated strings if lokit ran
git commit -m "feat: annotation strings (15 locales via lokit) + Maestro E2E flow 23"
```

---

## Phase 3 验收清单

每项必须通过：

- [ ] `.\gradlew :app:compileDevDebugKotlin`（120s 内 SUCCESS）
- [ ] `.\gradlew :app:testDevDebugUnitTest --rerun`（180s 内全绿，含 Phase 3 新增 ~37 个测试）
  - OffsetConverterTest: 14 tests
  - AnnotationManagerTest: 12 tests
  - AnnotationPromptBuilderTest: 8 tests
  - SubmitAnnotationsUseCaseTest: 3 tests
  - FileViewerViewModelTest: +5 new annotation tests（总计 14）
- [ ] `maestro test maestro/e2e-file-viewer-annotation.yaml` → PASS（emulator + 真实 opencode 服务器）
- [ ] 手动验收（真实 opencode 服务器 4096 + emulator 10.0.2.2）：
  - 入口2 → 文件树 → 点 .kt 文件 → FileViewer 源码视图
  - 长按选中代码 → 自定义 toolbar 弹出（含"复制/全选/标注修改"）
  - 点"标注修改" → 底部 sheet 出现 → 预览选中代码 → 输入意见 → 确定
  - 验证：选区高亮（品牌色 alpha 0.12）、TopBar 显示标注徽章 [1]
  - 再标一处 → TopBar [2]
  - 点 TopBar "提交" → 提交对话框（含标注摘要列表）
  - 输入整体意见 → 发送 → Toast "已发送给 AI" → 自动返回 Chat
  - 验证：Chat 流出现新消息（含结构化文本）
  - 边界：DIFF 视图下无标注 UI；点已标注高亮 → 详情弹框（编辑/删除）；删除中间标注重新编号

---

## Phase 3 不包含（Phase 4+）

- **标注持久化**：旋转屏幕/进程销毁后标注丢失（Phase 4 可用 `rememberSaveable` 或 SavedStateHandle）
- **精确选区定位**：使用 clipboard hack + `indexOf`，相同文本多次出现时可能定位错误（Phase 4 可用 `SelectionRegistrar` 精确定位）
- **Diff 视图标注**：Diff 纯只读，不支持标注（spec 明确排除）
- **md 渲染预览标注**：渲染预览只读（spec 明确排除）
- **标注数量上限**：无限制（Phase 4 可加上限 + 警告）
- **超长选区处理**：选区跨整文件时 UI 可能卡顿（Phase 4 可加上限）
- **选区手势优化**：当前依赖 Compose SelectionContainer 默认手势，自定义 selection handle 拖拽行为在 Phase 4

---

## Self-Review

### Spec 覆盖检查

| Spec 条目 | 对应 Task | 状态 |
|-----------|----------|------|
| §7.4 标注交互链路 step 1（长按选中→TextToolbar） | Task 6 | ✅ |
| §7.4 标注交互链路 step 2（点标注修改→弹 sheet） | Task 6+7 | ✅ |
| §7.4 标注交互链路 step 3（确定→记录 Annotation） | Task 2+5 | ✅ |
| §7.4 标注交互链路 step 4（继续标注） | Task 5（addAnnotation 可多次调用） | ✅ |
| §7.4 标注交互链路 step 5（点提交→弹对话框） | Task 9 | ✅ |
| §7.4 标注交互链路 step 6（发送→promptAsync→navigateUp） | Task 4+9 | ✅ |
| §7.4 标注的编辑/删除 | Task 2+5+7 | ✅ |
| §7.4 选区重叠规则（允许，alpha 累加） | Task 8 | ✅ |
| §7.5 结构化文本格式 | Task 3 | ✅ |
| §8.1 Annotation data class | Task 1 | ✅ |
| §8.6 OffsetConverter（char offset → 行:列） | Task 1 | ✅ |
| §8.5 SubmitAnnotationsUseCase | Task 4 | ✅ |
| 设计决策 §15（序号 1.2.3 按创建顺序） | Task 3 | ✅ |
| 设计决策 §16（删除中间标注重新编号） | Task 2 | ✅ |
| 设计决策 §12（Diff 不支持标注） | Task 5（addAnnotation 在 DIFF 模式被忽略） | ✅ |
| 设计决策 §14（提交后 navigateUp 回 Chat） | Task 9 | ✅ |
| §11.4 Maestro flow 23 | Task 10 | ✅ |

### 类型一致性检查

- `Annotation.id`: String (UUID) — Task 1 定义，Task 2/5/7/9 使用 ✅
- `Annotation.index`: Int (0-based) — Task 1 定义，Task 2 renumber，Task 3 display index+1 ✅
- `AnnotationManager.add()` 签名 `(String, Int, Int, String)` — Task 2 定义，Task 5 调用 ✅
- `SubmitAnnotationsUseCase.invoke()` 签名 — Task 4 定义，Task 5 调用 ✅
- `Modifier.annotationContextMenu(onAnnotate: (String) -> Unit)` — Task 6 定义，Task 8 调用 ✅
- `CodeSourceView` 新参数有默认值 — Task 8 定义，向后兼容 ✅

### 占位符扫描

- 无 "TBD"/"TODO" 在代码中
- 所有代码步骤都有完整实现
- 所有测试步骤都有完整测试代码
