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
import dev.minios.ocremote.ui.theme.CodeTypography
import dev.minios.ocremote.ui.theme.SpacingTokens
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes

private const val ALPHA_MASK = 0xFF000000.toInt()

@Composable
fun CodeSourceView(
    content: String,
    filePath: String,
    modifier: Modifier = Modifier,
    lazyListState: LazyListState = rememberLazyListState()
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

    LazyColumn(
        state = lazyListState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = SpacingTokens.SM.dp)
    ) {
        items(
            count = lineCount,
            key = { it }
        ) { index ->
            val start = lineOffsets[index]
            val endExclusive = if (index + 1 < lineOffsets.size)
                lineOffsets[index + 1] - 1
            else
                content.length
            val lineAnnotated = annotated.subSequence(start, endExclusive)

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
