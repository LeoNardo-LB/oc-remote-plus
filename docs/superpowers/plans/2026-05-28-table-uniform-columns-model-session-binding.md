# 表格统一列宽 + 模型会话绑定修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 Markdown 表格的列宽统一问题（每列宽度=该列最宽单元格），修复模型选择器在会话切换时总是回到默认值的 Bug。

**Architecture:** 表格使用 Custom `Layout` composable（`MeasurePolicy`）实现单遍测量：在 `measure()` 中一次性测完所有子元素，按列分组取最大宽度，然后 placement。模型绑定修复在 `combine` 块中添加 effective→StateFlow 的同步逻辑，与 agent 已有的同步模式一致。

**Tech Stack:** Jetpack Compose (Layout/MeasurePolicy), Kotlin StateFlow, mikepenz multiplatform-markdown-renderer

---

## File Structure

| File | Action | Responsibility |
|------|--------|---------------|
| `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt` | Modify | 替换 `SimpleMarkdownTable` 为基于 `Layout` 的实现 |
| `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt` | Modify | 在 combine 块中添加模型同步逻辑 + 加载消息后写缓存 |
| `app/build.gradle.kts` | Modify | 版本号 beta.40 → beta.41 |

---

## Task 1: 模型会话绑定修复（ChatViewModel.kt）

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt:293-354`（combine 块模型解析区域）
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt:705-711`（selectModel 方法）
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt:1348-1354`（companion object 缓存）

### Bug 根因

`combine` 块从历史消息解析出 `effectiveProviderId`/`effectiveModelId` 后，只赋给了 `ChatUiState` 的字段显示在 UI 上，但**没有写回到 `_selectedProviderId`/`_selectedModelId` StateFlow**。

对比 agent 的同步代码（第 324-327 行）：
```kotlin
// agent 有同步 ✅
if (effectiveAgent != selectedAgent && !isAgentExplicitlySelected) {
    _selectedAgent.value = effectiveAgent to false
}
// model 没有等价逻辑 ❌
```

这导致：
1. `_selectedProviderId`/`_selectedModelId` 始终为 null（当非显式选择时）
2. `sendParts()` 读 StateFlow → 发送时模型为 null → 服务端用默认模型
3. `sessionModelCache` 只在 `selectModel()` 中写入 → 从未被填充 → 切换会话后缓存未命中

- [ ] **Step 1: 在 combine 块中添加模型同步逻辑（在 agent 同步代码之后）**

在第 327 行（agent 同步）之后，第 329 行（`val assistantMessages = ...`）之前，添加：

```kotlin
        // Keep model StateFlows in sync with the effective model,
        // mirroring the agent sync logic above.
        if ((effectiveProviderId != selProviderId || effectiveModelId != selModelId) && !isModelExplicitlySelected) {
            _selectedProviderId.value = effectiveProviderId
            _selectedModelId.value = effectiveModelId
        }
```

这段代码的作用：当 combine 块解析出的 effective 模型与 StateFlow 中存储的值不同，且用户没有显式选择模型时，将 effective 值同步回 StateFlow。这确保了 `sendParts()` 读取的 StateFlow 始终与 UI 显示的一致。

- [ ] **Step 2: 在 combine 块末尾，将最终确定的模型写入缓存**

在第 354 行（`val availableVariants = ...`）之后，第 357 行（`val pendingAssistantIndex = ...`）之前，添加：

```kotlin
        // Persist the resolved model to the in-memory cache so it survives
        // session switching (ViewModel recreation).  This runs on every
        // combine emission but is cheap (Map.put).
        if (effectiveProviderId != null && effectiveModelId != null) {
            sessionModelCache[sessionId] = effectiveProviderId to effectiveModelId
        }
```

这确保了即使从历史消息解析出的模型（非用户显式选择）也会被缓存，切换会话后能立即恢复。

- [ ] **Step 3: 验证修改**

检查修改后的代码段（第 293-360 行区域）逻辑流程：
1. `selProviderId`/`selModelId` 从 StateFlow 取值 → 可能为 null
2. 如果非显式选择，从历史消息解析 effective 值 → 赋值给局部变量
3. **新增**：同步 effective 值回 StateFlow（与 agent 模式一致）
4. 如果 effective 对应的 model 不在 providers 中 → fallback 到第一个可用 model
5. **新增**：将最终 effective 值写入 sessionModelCache
6. 用 effective 值构造 ChatUiState

确认没有循环依赖风险：
- `_selectedProviderId.value = effectiveProviderId` 触发 combine 重新求值
- 第二次求值时 `selProviderId == effectiveProviderId` → 同步条件不满足 → 不再写入 → 稳定

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt
git commit -m "fix: sync resolved model back to StateFlows and cache

The combine block resolved effectiveProviderId/effectiveModelId from
history but never wrote them back to the StateFlows, causing:
- sendParts() reading null from StateFlows → server using default model
- sessionModelCache never populated → model resets on session switch

Mirrors the existing agent sync pattern (line 324-327)."
```

---

## Task 2: 表格统一列宽（ChatScreen.kt）

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt:4681-4796`（SimpleMarkdownTable 函数）

### 设计说明

用 Custom `Layout` composable（`MeasurePolicy`）替换当前的 `Column + Row + Box` 结构。

**核心原理**：`Layout` 的 `measure()` 方法在同一帧内：
1. 用 `measurable.measure(looseConstraints)` 测完所有单元格 → 得到自然宽高
2. 按 `(index % columnCount)` 分组 → 取每列最大宽度
3. 按 `(index / columnCount)` 分组 → 取每行最大高度
4. `placeRelative()` 放好位置 → 每个单元格在所属列的宽度空间内对齐

这不是"两遍渲染"——是 Compose 的标准自定义布局机制，等同于 CSS `table-layout: auto`。

**视觉细节**：
- 背景色、分割线、padding 通过 `Modifier.drawBehind` / `Modifier.background` / `Modifier.padding` 在每个单元格上实现
- 行背景色（奇偶交替）和列分割线在 `drawBehind` 中绘制
- 外层圆角边框通过 `Modifier.border + clip` 实现
- `horizontalScroll` 加在 Layout 外层的 `Box` 上

- [ ] **Step 1: 替换 SimpleMarkdownTable 函数**

将 `SimpleMarkdownTable` 函数（第 4681-4796 行）整体替换为以下代码：

```kotlin
/**
 * Table component — uniform column widths driven by the widest cell content.
 *
 * Uses a custom [Layout] with [MeasurePolicy] to measure all cells in a
 * single pass, compute per-column max widths, and place them on a uniform
 * grid.  Horizontal scroll is enabled when the table exceeds the parent width.
 */
@Composable
private fun SimpleMarkdownTable(
    content: String,
    tableNode: ASTNode,
    style: TextStyle,
) {
    val headerBg = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
    val rowBgOdd = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f)
    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    val pad = 10.dp
    val shape = RoundedCornerShape(6.dp)
    val border = BorderStroke(1.dp, dividerColor)
    val annotator = annotatorSettings()

    val columnCount = remember(tableNode) {
        tableNode.children.maxOfOrNull { child ->
            when (child.type) {
                GFMHeader, GFMRow -> child.children.count { it.type == GFMCell }
                else -> 0
            }
        } ?: 0
    }
    if (columnCount == 0) return

    // Collect structured row data from AST
    val rows = remember(tableNode, content) {
        val list = mutableListOf<TableRow>()
        var rowIdx = 0
        tableNode.children.forEach { child ->
            when (child.type) {
                GFMHeader -> {
                    val cells = child.children.filter { it.type == GFMCell }
                    list.add(TableRow(isHeader = true, rowIndex = -1, cells = cells))
                }
                GFMRow -> {
                    val cells = child.children.filter { it.type == GFMCell }
                    list.add(TableRow(isHeader = false, rowIndex = rowIdx, cells = cells))
                    rowIdx++
                }
            }
        }
        list
    }

    val rowCount = rows.size
    val totalCells = rows.sumOf { minOf(it.cells.size, columnCount) }
    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .border(border, shape)
            .clip(shape)
    ) {
        Layout(
            content = {
                rows.forEachIndexed { rowIdx, row ->
                    val cellCount = minOf(row.cells.size, columnCount)
                    repeat(cellCount) { colIdx ->
                        val cell = row.cells[colIdx]
                        val isLastCol = colIdx == cellCount - 1
                        val cellStyle = if (row.isHeader) {
                            style.copy(fontWeight = FontWeight.SemiBold)
                        } else {
                            style
                        }
                        Box(
                            modifier = Modifier
                                .background(
                                    when {
                                        row.isHeader -> headerBg
                                        row.rowIndex % 2 == 1 -> rowBgOdd
                                        else -> Color.Transparent
                                    }
                                )
                                .then(
                                    if (!isLastCol) Modifier.drawBehind {
                                        drawLine(
                                            dividerColor,
                                            Offset(size.width, 0f),
                                            Offset(size.width, size.height),
                                            strokeWidth = 1f
                                        )
                                    } else Modifier
                                )
                                .padding(horizontal = pad, vertical = if (row.isHeader) 8.dp else 6.dp)
                        ) {
                            MarkdownBasicText(
                                text = content.buildMarkdownAnnotatedString(
                                    textNode = cell,
                                    style = cellStyle,
                                    annotatorSettings = annotator,
                                ),
                                style = cellStyle,
                            )
                        }
                    }
                }
            },
            measurePolicy = remember(columnCount, rowCount) {
                UniformColumnMeasurePolicy(columnCount, rowCount, pad)
            }
        )
    }

    // Horizontal divider between header and body
    if (rows.any { it.isHeader } && rows.any { !it.isHeader }) {
        HorizontalDivider(thickness = 1.5.dp, color = dividerColor)
    }
}

/** Data class representing a parsed table row from the AST. */
private data class TableRow(
    val isHeader: Boolean,
    val rowIndex: Int,
    val cells: List<ASTNode>,
)
```

- [ ] **Step 2: 添加 UniformColumnMeasurePolicy**

在 `SimpleMarkdownTable` 函数之后（原函数结束位置），添加：

```kotlin
/**
 * [MeasurePolicy] that lays out children in a grid with uniform column widths.
 * Each column's width equals the widest cell in that column.
 * Rows are separated by a thin divider drawn in [drawBehind].
 */
private class UniformColumnMeasurePolicy(
    private val columnCount: Int,
    private val rowCount: Int,
    private val cellPadding: Dp,
) : MeasurePolicy {

    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        if (measurables.isEmpty()) return layout(0, 0) {}

        val density = this
        val padPx = with(density) { cellPadding.roundToPx() }

        // 1. Measure all cells with loose width (minWidth=0) to get natural sizes
        val looseConstraints = constraints.copy(minWidth = 0)
        val placeables = measurables.map { it.measure(looseConstraints) }

        // 2. Compute per-column max width
        val colWidths = IntArray(columnCount) { 0 }
        placeables.forEachIndexed { index, placeable ->
            val col = index % columnCount
            colWidths[col] = maxOf(colWidths[col], placeable.width)
        }

        // 3. Compute per-row max height
        val rowHeights = IntArray(rowCount) { 0 }
        placeables.forEachIndexed { index, placeable ->
            val row = index / columnCount
            rowHeights[row] = maxOf(rowHeights[row], placeable.height)
        }

        // 4. Total dimensions
        val totalWidth = colWidths.sum()
        val totalHeight = rowHeights.sum()

        // 5. Place in grid
        layout(totalWidth, totalHeight) {
            var y = 0
            for (row in 0 until rowCount) {
                var x = 0
                for (col in 0 until columnCount) {
                    val idx = row * columnCount + col
                    if (idx < placeables.size) {
                        val p = placeables[idx]
                        // Center vertically within the row height
                        val dy = (rowHeights[row] - p.height) / 2
                        p.placeRelative(x, y + dy)
                    }
                    x += colWidths[col]
                }
                y += rowHeights[row]
            }
        }
    }

    override fun minIntrinsicWidth(measurables: List<Measurable>, height: Int): Int {
        val colMax = IntArray(columnCount) { 0 }
        measurables.forEachIndexed { i, m ->
            colMax[i % columnCount] = maxOf(colMax[i % columnCount], m.minIntrinsicWidth(height))
        }
        return colMax.sum()
    }

    override fun maxIntrinsicWidth(measurables: List<Measurable>, height: Int): Int =
        minIntrinsicWidth(measurables, height)

    override fun minIntrinsicHeight(measurables: List<Measurable>, width: Int): Int {
        if (measurables.isEmpty()) return 0
        val rowHeights = IntArray(rowCount) { 0 }
        measurables.forEachIndexed { i, m ->
            val row = i / columnCount
            rowHeights[row] = maxOf(rowHeights[row], m.minIntrinsicHeight(width))
        }
        return rowHeights.sum()
    }

    override fun maxIntrinsicHeight(measurables: List<Measurable>, width: Int): Int =
        minIntrinsicHeight(measurables, width)
}
```

- [ ] **Step 3: 验证编译**

运行：
```bash
cd D:\Develop\code\app\oc-remote && .\gradlew.bat :app:compileDebugKotlin 2>&1 | Select-Object -Last 20
```

预期：编译成功，无错误。

如有未解析引用，检查：
- `TableRow` 是否在 `SimpleMarkdownTable` 之外可见（`private data class` 在文件级别定义即可）
- `remember` 的 import：`import androidx.compose.runtime.remember`
- `Dp` / `Offset` 的 import：`import androidx.compose.ui.unit.Dp` / `import androidx.compose.ui.geometry.Offset`

- [ ] **Step 4: 修复 header/body 分割线位置**

注意 Step 1 代码末尾的 `HorizontalDivider` 放在了 `Box` 外面——这意味着它不会随表格水平滚动。这不对。

修改：将 header/body 分割线移到 Layout content 的 header 和 body 之间，用 `drawBehind` 在 header 行底部画线。

替换 Step 1 末尾的 `HorizontalDivider` 代码块为：直接删除它。分割线已通过 header 行的 `background(headerBg)` 视觉区分（header 有半透明蓝色背景）。

如果需要更明显的分隔线，在 Step 1 的 header 行 `Box` modifier 中追加：
```kotlin
.then(
    if (row.isHeader) Modifier.drawBehind {
        // Draw a thicker line at the bottom of the header row
        drawLine(
            dividerColor,
            Offset(0f, size.height),
            Offset(size.width, size.height),
            strokeWidth = 2f
        )
    } else Modifier
)
```

但先简单实现，测试后看视觉效果再决定是否添加。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt
git commit -m "feat: uniform table column widths via custom Layout

Replace SimpleMarkdownTable's Column+Row+Box structure with a custom
Layout composable (MeasurePolicy). All cells are measured in a single
pass, per-column max widths computed, and cells placed on a uniform
grid. Horizontal scroll works correctly outside the Layout.

No two-pass rendering — this is standard Compose custom layout,
equivalent to CSS table-layout: auto."
```

---

## Task 3: 版本升级

**Files:**
- Modify: `app/build.gradle.kts:20-21`

- [ ] **Step 1: 更新版本号**

将 `app/build.gradle.kts` 中的：
```kotlin
versionCode = 240
versionName = "2.0.0-beta.40"
```
改为：
```kotlin
versionCode = 241
versionName = "2.0.0-beta.41"
```

- [ ] **Step 2: Commit**

```bash
git add app/build.gradle.kts
git commit -m "chore: bump version to 2.0.0-beta.41"
```

---

## Task 4: 构建验证 + 发布

- [ ] **Step 1: 全量编译**

```bash
cd D:\Develop\code\app\oc-remote && .\gradlew.bat assembleRelease 2>&1 | Select-Object -Last 30
```

预期：`BUILD SUCCESSFUL`。

- [ ] **Step 2: 推送到远端**

```bash
git push origin HEAD
```

- [ ] **Step 3: 创建 GitHub Release**

```bash
gh release create v2.0.0-beta.41 --title "v2.0.0-beta.41" --notes "## Changes

### Bug Fixes
- **模型会话绑定修复**: 切换会话时模型不再回到默认值。从历史消息解析出的模型现在会正确同步到 StateFlow 并缓存。

### Improvements
- **表格列宽统一**: Markdown 表格每列宽度现在由该列最宽单元格决定，所有行的同列宽度一致。基于 Compose Custom Layout 单遍测量实现。

### Full Changelog
https://github.com/niuyi/openremote/compare/v2.0.0-beta.40...v2.0.0-beta.41" app/build/outputs/apk/release/app-release.apk
```

注意：`gh release create` 的 APK 路径可能需要根据实际 build 输出调整。如果 build 变体不同，路径可能是 `app/build/outputs/apk/debug/app-debug.apk`。

---

## Self-Review

### Spec Coverage
- ✅ 表格统一列宽：Task 2 完整实现
- ✅ 模型会话绑定：Task 1 修复所有 4 个 Bug
- ✅ 版本升级和发布：Task 3 + Task 4

### Placeholder Scan
- ✅ 所有步骤包含完整代码
- ✅ 无 TBD/TODO/placeholder
- ✅ 所有 git commit message 包含完整内容

### Type Consistency
- ✅ `UniformColumnMeasurePolicy` 构造参数 `(columnCount: Int, rowCount: Int, cellPadding: Dp)` 与 `remember(columnCount, rowCount)` 调用一致
- ✅ `TableRow` data class 的字段在 `SimpleMarkdownTable` 和 `Layout content` 中一致使用
- ✅ `_selectedProviderId`/`_selectedModelId` 的类型是 `String?`，写入值来自 `effectiveProviderId`/`effectiveModelId`（也是 `String?`）— 类型一致
