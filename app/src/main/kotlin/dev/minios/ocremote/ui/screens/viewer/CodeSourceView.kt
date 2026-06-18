package dev.minios.ocremote.ui.screens.viewer

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
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
    modifier: Modifier = Modifier
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
    val lineCount = remember(content) { content.count { it == '\n' } + 1 }

    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()

    Row(
        modifier = modifier
            .fillMaxSize()
            .horizontalScroll(hScroll)
            .verticalScroll(vScroll)
    ) {
        LineNumberGutter(lineCount = lineCount, style = CodeTypography)
        Text(
            text = annotated,
            style = CodeTypography,
            modifier = Modifier.padding(
                start = SpacingTokens.SM.dp,
                end = SpacingTokens.LG.dp,
                top = SpacingTokens.SM.dp,
                bottom = SpacingTokens.SM.dp
            )
        )
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

@Composable
private fun LineNumberGutter(
    lineCount: Int,
    style: TextStyle,
    modifier: Modifier = Modifier
) {
    val gutterColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gutterWidth = remember(lineCount) {
        val digits = maxOf(1, lineCount).toString().length
        (digits * 10 + SpacingTokens.SM).dp
    }
    Column(modifier = modifier.width(gutterWidth)) {
        repeat(lineCount) { i ->
            Text(
                text = "${i + 1}",
                style = style,
                color = gutterColor,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth()
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

