package dev.minios.ocremote.ui.screens.viewer

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.domain.model.Annotation
import dev.minios.ocremote.ui.theme.AlphaTokens
import dev.minios.ocremote.ui.theme.CodeTypography
import dev.minios.ocremote.ui.theme.SpacingTokens
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

private const val ALPHA_MASK = 0xFF000000.toInt()

@Composable
fun CodeSourceView(
    content: String,
    filePath: String,
    annotations: List<Annotation> = emptyList(),
    onAnnotate: ((selectedText: String) -> Unit)? = null,
    onTapAnnotation: ((Annotation) -> Unit)? = null,
    modifier: Modifier = Modifier,
    lazyListState: LazyListState = rememberLazyListState(),
    // Phase 4: pagination — null means render all lines (backward compatible)
    visibleLineCount: Int? = null,
    totalLineCount: Int? = null,
    onLoadMore: (() -> Unit)? = null
) {
    if (content.isEmpty()) return

    val isDark = isSystemInDarkTheme()
    val language = rememberLanguage(filePath)
    val highlights = remember(content, language, isDark) {
        buildHighlights(content, language, isDark)
    }
    val annotated = remember(content, highlights) {
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
    val highlightColor = MaterialTheme.colorScheme.primary
    val lineAnnotations = remember(annotations, content, lineCount) {
        val map = mutableMapOf<Int, MutableList<Triple<Int, Int, Int>>>()
        annotations.forEach { ann ->
            val annStartLine = ann.startLine - 1
            val annEndLine = ann.endLine - 1
            for (lineIdx in annStartLine..annEndLine) {
                if (lineIdx < 0 || lineIdx >= lineCount) continue
                val lineStart = lineOffsets[lineIdx]
                val lineEnd = if (lineIdx + 1 < lineOffsets.size) lineOffsets[lineIdx + 1] - 1 else content.length
                val relStart = (ann.startChar - lineStart).coerceAtLeast(0)
                val relEnd = (ann.endChar - lineStart).coerceAtMost(lineEnd - lineStart)
                if (relStart < relEnd) {
                    map.getOrPut(lineIdx) { mutableListOf() }
                      .add(Triple(relStart, relEnd, ann.index + 1))
                }
            }
        }
        map
    }
    val maxChars = remember(content) {
        var max = 0
        var current = 0
        for (c in content) {
            if (c == '\n') {
                if (current > max) max = current
                current = 0
            } else {
                current++
            }
        }
        if (current > max) max = current
        max
    }
    val gutterColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gutterWidth = remember(lineCount) {
        val digits = maxOf(1, lineCount).toString().length
        (digits * 10 + SpacingTokens.SM).dp
    }
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val maxRowWidth = remember(maxChars, gutterWidth, density) {
        val charWidthPx = textMeasurer.measure("M", CodeTypography).size.width
        val maxCodeWidthPx = charWidthPx * maxChars
        with(density) {
            gutterWidth + maxCodeWidthPx.toDp() + SpacingTokens.SM.dp + SpacingTokens.LG.dp
        }
    }
    val hScroll = rememberScrollState()
    val annotationEnabled = onAnnotate != null

    // Phase 4: pagination — trigger loadMore when user scrolls near the bottom
    val visLines = visibleLineCount
    val totalLines = totalLineCount
    val hasMore = visLines != null && totalLines != null && visLines < totalLines
    if (onLoadMore != null && hasMore) {
        LaunchedEffect(lazyListState, visLines, totalLines) {
            snapshotFlow {
                val lastVisible = lazyListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                lastVisible >= visLines - 50
            }
                .filter { it }
                .distinctUntilChanged()
                .collect { onLoadMore() }
        }
    }

    val lazyContent: @Composable (Modifier) -> Unit = { m ->
        LazyColumn(
            state = lazyListState,
            modifier = m.fillMaxSize(),
            contentPadding = PaddingValues(vertical = SpacingTokens.SM.dp)
        ) {
            items(
                count = visLines ?: lineCount,
                key = { it }
            ) { index ->
                val start = lineOffsets[index]
                val endExclusive = if (index + 1 < lineOffsets.size)
                    lineOffsets[index + 1] - 1
                else
                    content.length
                val baseLine = annotated.subSequence(start, endExclusive)
                val lineAnnotated = if (lineAnnotations.containsKey(index)) {
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
                        text = lineAnnotated,
                        style = CodeTypography,
                        modifier = Modifier.padding(
                            start = SpacingTokens.SM.dp,
                            end = SpacingTokens.LG.dp
                        )
                    )
                }
            }
            // Phase 4: load-more indicator
            if (hasMore) {
                item(key = "load_more") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(SpacingTokens.LG.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp).testTag("viewer_load_more_indicator")
                        )
                    }
                }
            }
        }
    }

    if (annotationEnabled && onAnnotate != null) {
        SelectionContainer {
            lazyContent(modifier.annotationContextMenu(onAnnotate))
        }
    } else {
        lazyContent(modifier)
    }
}

private fun buildHighlights(
    content: String,
    language: SyntaxLanguage,
    isDark: Boolean
): Highlights =
    Highlights.Builder()
        .code(content)
        .language(language)
        .theme(SyntaxThemes.default(isDark))
        .build()

private fun buildAnnotatedStringFromHighlights(
    content: String,
    highlightsResult: Highlights
): AnnotatedString = buildAnnotatedString {
    append(content)
    val maxIndex = content.length
    highlightsResult.getHighlights().forEach { h ->
        val start = h.location.start
        val end = (h.location.end + 1).coerceAtMost(maxIndex)
        if (start >= maxIndex || end <= start) return@forEach
        when (h) {
            is ColorHighlight -> addStyle(
                SpanStyle(color = Color(h.rgb or ALPHA_MASK)),
                start, end
            )
            is BoldHighlight -> addStyle(
                SpanStyle(fontWeight = FontWeight.Bold),
                start, end
            )
        }
    }
}

private fun rememberLanguage(filePath: String): SyntaxLanguage {
    val ext = filePath.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "kt", "kts" -> SyntaxLanguage.KOTLIN
        "java" -> SyntaxLanguage.JAVA
        "js", "mjs", "jsx" -> SyntaxLanguage.JAVASCRIPT
        "ts", "tsx" -> SyntaxLanguage.TYPESCRIPT
        "py" -> SyntaxLanguage.PYTHON
        "go" -> SyntaxLanguage.GO
        "rs" -> SyntaxLanguage.RUST
        "c", "h" -> SyntaxLanguage.C
        "cpp", "cc", "cxx", "hpp", "hxx" -> SyntaxLanguage.CPP
        "cs" -> SyntaxLanguage.CSHARP
        "rb" -> SyntaxLanguage.RUBY
        "dart" -> SyntaxLanguage.DART
        "php" -> SyntaxLanguage.PHP
        "pl", "pm" -> SyntaxLanguage.PERL
        "swift" -> SyntaxLanguage.SWIFT
        "coffee" -> SyntaxLanguage.COFFEESCRIPT
        "sh", "bash", "zsh", "fish" -> SyntaxLanguage.SHELL
        else -> SyntaxLanguage.DEFAULT
    }
}

/**
 * Build a per-line AnnotatedString with annotation highlights overlaid on the base syntax-highlighted line.
 */
private fun buildAnnotatedLineWithAnnotations(
    baseLine: AnnotatedString,
    annotations: List<Triple<Int, Int, Int>>,
    baseColor: Color
): AnnotatedString = buildAnnotatedString {
    append(baseLine)
    annotations.forEach { (relStart, relEnd, _) ->
        addStyle(
            SpanStyle(background = baseColor.copy(alpha = AlphaTokens.SELECTED)),
            relStart.coerceIn(0, length),
            relEnd.coerceIn(0, length)
        )
    }
}
