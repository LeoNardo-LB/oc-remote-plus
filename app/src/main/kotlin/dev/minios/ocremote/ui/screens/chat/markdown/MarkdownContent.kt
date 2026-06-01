package dev.minios.ocremote.ui.screens.chat.markdown

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import dev.snipme.highlights.Highlights
import com.mikepenz.markdown.model.markdownDimens
import dev.minios.ocremote.ui.screens.chat.util.isAmoledTheme
import dev.minios.ocremote.ui.screens.chat.util.LocalChatFontSize
import dev.minios.ocremote.ui.screens.chat.util.LocalCodeWordWrap
import dev.minios.ocremote.ui.theme.CodeTypography
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontStyle

private val HtmlDocumentHintRegex = Regex("(?is)<!doctype\\s+html\\b|<\\s*html\\b")
private val HtmlTagRegex = Regex("(?is)<\\s*/?\\s*[a-z][^>]*>")

internal fun looksLikeHtmlPayload(text: String): Boolean {
    if (text.isBlank()) return false
    if (HtmlDocumentHintRegex.containsMatchIn(text)) return true
    return HtmlTagRegex.findAll(text).take(12).count() >= 6
}

internal fun normalizeHtmlForEmbeddedPreview(html: String): String {
    if (html.isBlank()) return html
    val overrideCss = """
        html, body {
          margin: 0 !important;
          padding: 8px !important;
          min-height: auto !important;
          height: auto !important;
        }
        body {
          display: block !important;
          align-items: flex-start !important;
          justify-content: flex-start !important;
          overflow: auto !important;
        }
        .container {
          align-items: flex-start !important;
          justify-content: flex-start !important;
          height: auto !important;
          min-height: auto !important;
          width: 100% !important;
          margin: 0 !important;
        }
    """.trimIndent()

    val styleBlock = "<style>$overrideCss</style>"
    return if (html.contains("</head>", ignoreCase = true)) {
        html.replaceFirst(Regex("(?i)</head>"), "$styleBlock</head>")
    } else {
        "<head>$styleBlock</head>$html"
    }
}

internal fun preserveRawHtmlPayload(markdown: String): String {
    if (markdown.isBlank()) return markdown
    if ("```" in markdown) return markdown

    val looksLikeHtmlDocument = HtmlDocumentHintRegex.containsMatchIn(markdown)
    val htmlTagCount = HtmlTagRegex.findAll(markdown).take(16).count()
    if (!looksLikeHtmlDocument && htmlTagCount < 8) return markdown

    return buildString(markdown.length + 16) {
        append("```text\n")
        append(markdown.trimEnd())
        append("\n```")
    }
}

@Composable
internal fun MarkdownContent(
    markdown: String,
    textColor: Color,
    isUser: Boolean,
    customFontSize: String? = null  // null = use global setting; "small"/"medium"/"large" = override
) {
    val normalizedMarkdown = remember(markdown, isUser) {
        val base = preserveRawHtmlPayload(markdown)
        if (isUser) {
            // User messages: single \n doesn't break in Markdown (soft break).
            // Convert standalone \n to \n\n for paragraph breaks.
            base.replace(Regex("(?<!\n)\n(?!\n)"), "\n\n")
        } else {
            base
        }
    }

    val isAmoled = isAmoledTheme()

    // Inline code: keep text styling, but no opaque background so selection remains visible.
    val inlineCodeFg = when {
        isAmoled -> MaterialTheme.colorScheme.onSurface
        isUser -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.primary
    }
    // Code blocks: distinct background
    val codeBlockBg = when {
        isAmoled -> Color(0xFF2A2A36)  // Matches AMOLED surfaceContainerHighest
        isUser -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    val codeBlockFg = when {
        isAmoled -> MaterialTheme.colorScheme.onSurface
        isUser -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }

    // Font size from settings: small=13sp, medium=14sp (default), large=16sp
    val fontSizeSetting = customFontSize ?: LocalChatFontSize.current
    val (bodyFontSize, bodyLineHeight) = when (fontSizeSetting) {
        "small" -> 13.sp to 18.sp
        "large" -> 16.sp to 26.sp
        else -> 14.sp to 22.sp // medium
    }
    val (codeFontSize, codeLineHeight) = when (fontSizeSetting) {
        "small" -> 11.sp to 16.sp
        "large" -> 15.sp to 22.sp
        else -> 13.sp to 20.sp // medium
    }

    // Balanced text style with better line-height for readability
    val bodyStyle = MaterialTheme.typography.bodyMedium.copy(
        color = textColor,
        fontSize = bodyFontSize,
        lineHeight = bodyLineHeight
    )

    val linkColor = when {
        isAmoled -> MaterialTheme.colorScheme.primary
        isUser -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.primary
    }

    val inlineCodeBg = when {
        isAmoled -> Color(0xFF353540)
        isUser -> MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.1f)
        else -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    }

    val colors = markdownColor(
        text = textColor,
        codeBackground = codeBlockBg,
        inlineCodeBackground = inlineCodeBg,
        dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        tableBackground = MaterialTheme.colorScheme.surfaceContainerLow
    )

    val typography = markdownTypography(
        h1 = MaterialTheme.typography.titleLarge.copy(
            color = textColor,
            fontWeight = FontWeight.Bold,
            lineHeight = 32.sp
        ),
        h2 = MaterialTheme.typography.titleMedium.copy(
            color = textColor,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 28.sp
        ),
        h3 = MaterialTheme.typography.titleSmall.copy(
            color = textColor,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 24.sp
        ),
        h4 = MaterialTheme.typography.bodyLarge.copy(
            color = textColor,
            fontWeight = FontWeight.SemiBold
        ),
        h5 = MaterialTheme.typography.bodyMedium.copy(
            color = textColor,
            fontWeight = FontWeight.SemiBold
        ),
        h6 = MaterialTheme.typography.bodyMedium.copy(
            color = textColor.copy(alpha = 0.8f),
            fontWeight = FontWeight.Medium
        ),
        text = bodyStyle,
        code = CodeTypography.copy(color = codeBlockFg, fontSize = codeFontSize, lineHeight = codeLineHeight),
        inlineCode = CodeTypography.copy(
            color = inlineCodeFg,
            fontSize = codeFontSize,
            fontWeight = FontWeight.Medium
        ),
        quote = bodyStyle.copy(
            color = textColor.copy(alpha = 0.65f),
            fontStyle = FontStyle.Italic
        ),
        paragraph = bodyStyle,
        ordered = bodyStyle,
        bullet = bodyStyle,
        list = bodyStyle,
        table = bodyStyle,
        textLink = TextLinkStyles(
            style = bodyStyle.copy(
                color = linkColor,
                fontWeight = FontWeight.Medium
            ).toSpanStyle()
        )
    )

    val wordWrap = LocalCodeWordWrap.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp.dp

    val highlightsBuilder = remember { Highlights.Builder() }

    val components = remember(wordWrap) {
        markdownComponents(
            codeBlock = { model ->
                OcCodeBlock(
                    content = model.content,
                    node = model.node,
                    style = typography.code,
                    highlightsBuilder = highlightsBuilder,
                    wordWrap = wordWrap,
                    isFence = false
                )
            },
            codeFence = { model ->
                OcCodeBlock(
                    content = model.content,
                    node = model.node,
                    style = typography.code,
                    highlightsBuilder = highlightsBuilder,
                    wordWrap = wordWrap,
                    isFence = true
                )
            },
            table = { model ->
                SimpleMarkdownTable(model.content, model.node, model.typography.table)
            }
        )
    }

    val dimens = markdownDimens(
        tableCellPadding = 6.dp,
        tableCellWidth = Dp.Unspecified,
        tableCornerSize = 4.dp,
        tableMaxWidth = screenWidthDp * 1.5f
    )

    val markdownState = rememberMarkdownState(
        content = normalizedMarkdown,
        retainState = true
    )
    Markdown(
        markdownState = markdownState,
        colors = colors,
        typography = typography,
        components = components,
        dimens = dimens,
        imageTransformer = Coil3ImageTransformerImpl,
        modifier = Modifier.fillMaxWidth()
    )
}
