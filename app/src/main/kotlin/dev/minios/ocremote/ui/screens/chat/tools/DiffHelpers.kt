package dev.minios.ocremote.ui.screens.chat.tools

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.minios.ocremote.ui.screens.chat.util.codeHorizontalScroll
import dev.minios.ocremote.ui.screens.chat.util.isAmoledTheme
import dev.minios.ocremote.ui.theme.CodeTypography

/**
 * Inline diff change counts: +N -N with colors.
 */
@Composable
internal fun DiffChangesInline(additions: Int, deletions: Int) {
    val addColor = Color(0xFF4CAF50)
    val delColor = Color(0xFFE53935)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (additions > 0) {
            Text(
                text = "+$additions",
                style = CodeTypography.copy(fontSize = 11.sp, color = addColor)
            )
        }
        if (deletions > 0) {
            Text(
                text = "-$deletions",
                style = CodeTypography.copy(fontSize = 11.sp, color = delColor)
            )
        }
    }
}

private data class DiffLineStyle(val prefix: String, val text: String, val bgColor: Color, val fgColor: Color)

private enum class DiffLineType { REMOVED, ADDED, UNCHANGED }
private data class DiffLine(val type: DiffLineType, val text: String)

/**
 * Unified diff view — shows old lines in red, new lines in green.
 * Simple approach: compute line-level diff between before and after.
 */
@Composable
internal fun DiffView(before: String, after: String) {
    val isAmoled = isAmoledTheme()
    val addColor = Color(0xFF4CAF50)
    val delColor = Color(0xFFE53935)
    val addBg = Color(0xFF4CAF50).copy(alpha = 0.1f)
    val delBg = Color(0xFFE53935).copy(alpha = 0.1f)

    // Simple diff: show removed lines, then added lines
    // For a proper diff we'd need a diff library, but line-level comparison works for edit tools
    val beforeLines = if (before.isBlank()) emptyList() else before.lines()
    val afterLines = if (after.isBlank()) emptyList() else after.lines()

    // Compute simple LCS-based diff
    val diffLines = remember(before, after) { computeSimpleDiff(beforeLines, afterLines) }

    Surface(
        shape = RoundedCornerShape(4.dp),
        color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f),
        border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 400.dp)
    ) {
        Column(
            modifier = Modifier
                .codeHorizontalScroll()
                .padding(4.dp)
        ) {
            for (line in diffLines) {
                val (prefix, text, bgColor, fgColor) = when (line.type) {
                    DiffLineType.REMOVED -> DiffLineStyle("-", line.text, delBg, delColor)
                    DiffLineType.ADDED -> DiffLineStyle("+", line.text, addBg, addColor)
                    DiffLineType.UNCHANGED -> DiffLineStyle(" ", line.text, Color.Transparent, if (isAmoled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(bgColor)
                ) {
                    Text(
                        text = "$prefix ",
                        style = CodeTypography.copy(fontSize = 13.sp, color = fgColor),
                        modifier = Modifier.padding(start = 4.dp)
                    )
                    Text(
                        text = text,
                        style = CodeTypography.copy(fontSize = 13.sp, color = fgColor)
                    )
                }
            }
        }
    }
}

/**
 * Simple diff algorithm: find common prefix/suffix lines, show removed and added lines in between.
 * Not a full LCS but good enough for typical edit tool changes.
 */
private fun computeSimpleDiff(before: List<String>, after: List<String>): List<DiffLine> {
    if (before.isEmpty() && after.isEmpty()) return emptyList()
    if (before.isEmpty()) return after.map { DiffLine(DiffLineType.ADDED, it) }
    if (after.isEmpty()) return before.map { DiffLine(DiffLineType.REMOVED, it) }

    // Find common prefix
    var commonPrefixLen = 0
    while (commonPrefixLen < before.size && commonPrefixLen < after.size &&
        before[commonPrefixLen] == after[commonPrefixLen]) {
        commonPrefixLen++
    }

    // Find common suffix (after prefix)
    var commonSuffixLen = 0
    while (commonSuffixLen < (before.size - commonPrefixLen) &&
        commonSuffixLen < (after.size - commonPrefixLen) &&
        before[before.size - 1 - commonSuffixLen] == after[after.size - 1 - commonSuffixLen]) {
        commonSuffixLen++
    }

    val result = mutableListOf<DiffLine>()

    // Show a few context lines from prefix (max 3)
    val contextLines = 3
    val prefixStart = (commonPrefixLen - contextLines).coerceAtLeast(0)
    for (i in prefixStart until commonPrefixLen) {
        result.add(DiffLine(DiffLineType.UNCHANGED, before[i]))
    }

    // Removed lines (from before, between prefix and suffix)
    for (i in commonPrefixLen until (before.size - commonSuffixLen)) {
        result.add(DiffLine(DiffLineType.REMOVED, before[i]))
    }

    // Added lines (from after, between prefix and suffix)
    for (i in commonPrefixLen until (after.size - commonSuffixLen)) {
        result.add(DiffLine(DiffLineType.ADDED, after[i]))
    }

    // Show a few context lines from suffix (max 3)
    val suffixEnd = commonSuffixLen.coerceAtMost(contextLines)
    for (i in 0 until suffixEnd) {
        result.add(DiffLine(DiffLineType.UNCHANGED, before[before.size - commonSuffixLen + i]))
    }

    return result
}
