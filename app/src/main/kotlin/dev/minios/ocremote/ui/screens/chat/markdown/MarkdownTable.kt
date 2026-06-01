package dev.minios.ocremote.ui.screens.chat.markdown

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.layout.Layout
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.compose.elements.material.MarkdownBasicText
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes.HEADER as GFMHeader
import org.intellij.markdown.flavours.gfm.GFMElementTypes.ROW as GFMRow
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.CELL as GFMCell

/** Data class representing a parsed table row from the AST. */
private data class TableRow(
    val isHeader: Boolean,
    val rowIndex: Int,
    val cells: List<ASTNode>,
)

/**
 * Table component — uniform column widths driven by the widest cell content.
 *
 * Uses a custom [Layout] with inline [MeasurePolicy] to measure all cells in a
 * single composition pass.  Column widths are determined via
 * [androidx.compose.ui.layout.Measurable.minIntrinsicWidth] (no probe pass),
 * then a "fill parent" scaling is applied when the natural width is narrower
 * than the container.  Horizontal scroll is enabled when the table exceeds
 * the parent width.
 */
@Composable
internal fun SimpleMarkdownTable(
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
    // annotatorSettings() is @Composable; called directly so Compose tracks its
    // CompositionLocal dependencies.  The result is cheap (no IO / allocation).
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
    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { /* consumed to capture container width for fill strategy below */ }
            .border(border, shape)
            .clip(shape)
    ) {
        var containerWidth by remember { mutableIntStateOf(0) }

        // Inner box: captures container width once, then applies horizontal scroll.
        // onSizeChanged fires only on the first layout pass (0 → actual), producing
        // a single cheap recomposition (cellContent is fully remembered).
        Box(
            modifier = Modifier
                .onSizeChanged { containerWidth = it.width }
                .horizontalScroll(scrollState)
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
            ) { measurables, constraints ->
                if (measurables.isEmpty()) return@Layout layout(0, 0) {}

                // Single pass: determine column widths from intrinsic widths.
                val colWidths = IntArray(columnCount) { 0 }
                for (i in measurables.indices) {
                    val col = i % columnCount
                    val intrinsic = measurables[i].minIntrinsicWidth(0)
                    if (intrinsic > colWidths[col]) colWidths[col] = intrinsic
                }

                // Fill strategy: scale columns to fill the parent width when narrower.
                val naturalWidth = colWidths.sum()
                val parentWidth = containerWidth
                val finalColWidths = if (
                    naturalWidth > 0 &&
                    parentWidth > 0 &&
                    naturalWidth < parentWidth
                ) {
                    val scale = parentWidth.toFloat() / naturalWidth.toFloat()
                    val scaled = IntArray(columnCount) { col ->
                        (colWidths[col] * scale).toInt()
                    }
                    val diff = parentWidth - scaled.sum()
                    for (i in 0 until diff.coerceAtMost(columnCount)) {
                        scaled[i] += 1
                    }
                    scaled
                } else {
                    colWidths
                }

                // Measure each cell with its final column width.
                val placeables = measurables.mapIndexed { index, measurable ->
                    val col = index % columnCount
                    measurable.measure(
                        Constraints(
                            minWidth = finalColWidths[col],
                            maxWidth = finalColWidths[col],
                            minHeight = 0,
                            maxHeight = constraints.maxHeight,
                        )
                    )
                }

                // Compute row heights (max cell height in each row).
                val rowHeights = IntArray(rowCount) { 0 }
                for (i in placeables.indices) {
                    val row = i / columnCount
                    if (placeables[i].height > rowHeights[row]) {
                        rowHeights[row] = placeables[i].height
                    }
                }

                val totalWidth = finalColWidths.sum()
                val totalHeight = rowHeights.sum()

                layout(totalWidth, totalHeight) {
                    var y = 0
                    for (row in 0 until rowCount) {
                        var x = 0
                        for (col in 0 until columnCount) {
                            val idx = row * columnCount + col
                            if (idx < placeables.size) {
                                placeables[idx].placeRelative(x, y)
                            }
                            x += finalColWidths[col]
                        }
                        y += rowHeights[row]
                    }
                }
            }
        }
    }
}
