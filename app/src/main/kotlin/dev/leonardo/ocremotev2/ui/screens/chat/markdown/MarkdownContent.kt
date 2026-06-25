package dev.leonardo.ocremotev2.ui.screens.chat.markdown

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import dev.hossain.highlight.ui.CodeBlockStyle
import dev.hossain.highlight.ui.SyntaxHighlightedCode
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.ast.getTextInNode
import dev.leonardo.ocremotev2.ui.screens.chat.util.isAmoledTheme
import dev.leonardo.ocremotev2.ui.theme.AlphaTokens
import dev.leonardo.ocremotev2.ui.theme.ChatDensity
import dev.leonardo.ocremotev2.ui.theme.CodeTypography
import dev.leonardo.ocremotev2.ui.theme.LocalChatDensity
import dev.leonardo.ocremotev2.ui.theme.ShapeTokens
import dev.leonardo.ocremotev2.ui.theme.spacing
import dev.leonardo.ocremotev2.ui.theme.typography

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

// ============ Markdown Preprocessing ============

/**
 * Minimal Markdown preprocessing — let Mikepenz Handle parsing natively.
 * Only user message newline normalization remains.
 * Custom HTML detection and table format fixing removed to avoid
 * false-positives that broke rendering.
 */
internal fun normalizeMarkdown(raw: String, isUser: Boolean): String {
    // Normalize Windows line endings (\r\n → \n). opencode server on Windows
    // returns \r\n in Markdown text, which can break GFM table parsing
    // (the \r may be treated as cell content instead of line ending).
    var result = raw.replace("\r\n", "\n").replace("\r", "\n")
    if (!isUser) return result
    // User messages: single \n doesn't break in Markdown (soft break).
    return result.replace(Regex("(?<!\n)\n(?!\n)"), "\n\n")
}

@Composable
internal fun MarkdownContent(
    markdown: String,
    textColor: Color,
    isUser: Boolean,
    @Suppress("UNUSED_PARAMETER") customFontSize: String? = null,
    @Suppress("UNUSED_PARAMETER") immediate: Boolean = false,
) {
    // NOTE: customFontSize & immediate are retained for call-site compatibility
    // (PartContent / ReasoningBlock still pass them) but are intentionally unused
    // — typography/density is now driven by LocalChatDensity, and Mikepenz Markdown
    // parses synchronously so the immediate flag has no effect.
    val normalizedMarkdown = remember(markdown, isUser) {
        normalizeMarkdown(markdown, isUser)
    }

    val isAmoled = isAmoledTheme()
    val density = LocalChatDensity.current
    val tokens = density.typography
    val spacing = density.spacing

    // Inline code foreground — keep text legible without opaque background.
    val inlineCodeFg = when {
        isAmoled -> MaterialTheme.colorScheme.onSurface
        isUser -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.primary
    }
    // Code block distinct backgrounds.
    val codeBlockBg = when {
        isAmoled -> MaterialTheme.colorScheme.surfaceContainerHighest
        isUser -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    val codeBlockFg = when {
        isAmoled -> MaterialTheme.colorScheme.onSurface
        isUser -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val inlineCodeBg = when {
        isAmoled -> MaterialTheme.colorScheme.surfaceContainerHighest
        isUser -> MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = AlphaTokens.SELECTED)
        else -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = AlphaTokens.FAINT)
    }
    val linkColor = when {
        isAmoled -> MaterialTheme.colorScheme.primary
        isUser -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.primary
    }

    val colors = markdownColor(
        text = textColor,
        codeBackground = codeBlockBg,
        inlineCodeBackground = inlineCodeBg,
        dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT),
        tableBackground = MaterialTheme.colorScheme.surfaceContainerLow,
    )

    val bodyStyle = MaterialTheme.typography.bodyMedium.copy(
        color = textColor,
        fontSize = tokens.bodyFontSize,
        lineHeight = tokens.bodyLineHeight,
    )

    val typography = markdownTypography(
        h1 = MaterialTheme.typography.titleLarge.copy(
            color = textColor,
            fontSize = tokens.h1.fontSize,
            lineHeight = tokens.h1.lineHeight,
            fontWeight = tokens.h1.fontWeight,
        ),
        h2 = MaterialTheme.typography.titleLarge.copy(
            color = textColor,
            fontSize = tokens.h2.fontSize,
            lineHeight = tokens.h2.lineHeight,
            fontWeight = tokens.h2.fontWeight,
        ),
        h3 = MaterialTheme.typography.titleMedium.copy(
            color = textColor,
            fontSize = tokens.h3.fontSize,
            lineHeight = tokens.h3.lineHeight,
            fontWeight = tokens.h3.fontWeight,
        ),
        h4 = MaterialTheme.typography.titleSmall.copy(
            color = textColor,
            fontSize = tokens.h4.fontSize,
            lineHeight = tokens.h4.lineHeight,
            fontWeight = tokens.h4.fontWeight,
        ),
        h5 = MaterialTheme.typography.bodyMedium.copy(
            color = textColor,
            fontSize = tokens.h5.fontSize,
            lineHeight = tokens.h5.lineHeight,
            fontWeight = tokens.h5.fontWeight,
        ),
        h6 = MaterialTheme.typography.bodyMedium.copy(
            color = textColor.copy(alpha = tokens.h6.alpha),
            fontSize = tokens.h6.fontSize,
            lineHeight = tokens.h6.lineHeight,
            fontWeight = tokens.h6.fontWeight,
        ),
        text = bodyStyle,
        code = CodeTypography.copy(
            color = codeBlockFg,
            fontSize = tokens.codeFontSize,
            lineHeight = tokens.codeLineHeight,
        ),
        inlineCode = CodeTypography.copy(
            color = inlineCodeFg,
            fontSize = tokens.codeFontSize,
            fontWeight = FontWeight.Medium,
        ),
        quote = bodyStyle.copy(
            color = textColor.copy(alpha = AlphaTokens.MEDIUM),
            fontStyle = FontStyle.Italic,
        ),
        paragraph = bodyStyle,
        ordered = bodyStyle,
        bullet = bodyStyle,
        list = bodyStyle,
        table = bodyStyle.copy(
            fontSize = tokens.tableFontSize,
            lineHeight = tokens.codeLineHeight,
        ),
        textLink = TextLinkStyles(
            style = bodyStyle.copy(
                color = linkColor,
                fontWeight = FontWeight.Medium,
            ).toSpanStyle()
        ),
    )

    val context = LocalContext.current
    val components = remember(density, isUser) {
        markdownComponents(
            codeBlock = { model ->
                val (code, lang) = extractCodeAndLanguage(model.content, model.node, isFence = false)
                CodeBlockRenderer(
                    code = code,
                    language = lang,
                    isUser = isUser,
                    density = density,
                    backgroundColor = codeBlockBg,
                    codeBlockFg = codeBlockFg,
                    context = context,
                )
            },
            codeFence = { model ->
                val (code, lang) = extractCodeAndLanguage(model.content, model.node, isFence = true)
                CodeBlockRenderer(
                    code = code,
                    language = lang,
                    isUser = isUser,
                    density = density,
                    backgroundColor = codeBlockBg,
                    codeBlockFg = codeBlockFg,
                    context = context,
                )
            },
            heading1 = { model ->
                // getUnescapedTextInNode returns the raw "# Title" range; strip the
                // leading marker(s) so only the heading copy is rendered.
                Column {
                    Text(
                        text = model.node.getUnescapedTextInNode(model.content)
                            .dropWhile { it == '#' }
                            .trim(),
                        style = typography.h1,
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(top = spacing.block),
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT),
                    )
                }
            },
            table = { model ->
                SimpleMarkdownTable(model.content, model.node, model.typography.table)
            },
        )
    }

    val padding = markdownPadding(
        block = spacing.block,
        list = 0.dp,
        listItemTop = 2.dp,
        listItemBottom = spacing.listItemBottom,
        listIndent = 4.dp,
    )

    Markdown(
        content = normalizedMarkdown,
        colors = colors,
        typography = typography,
        components = components,
        padding = padding,
        imageTransformer = Coil3ImageTransformerImpl,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Extracts pure code text and language from a Mikepenz code AST node.
 *
 * The [content] parameter is the entire markdown source; the [node] offsets
 * delimit the code block. Language detection only applies to fenced blocks
 * (```), indented code blocks have no language.
 */
private fun extractCodeAndLanguage(
    content: String,
    node: ASTNode,
    isFence: Boolean,
): Pair<String, String> {
    val codeText = if (isFence && node.children.size >= 3) {
        val language = node.findChildOfType(MarkdownTokenTypes.FENCE_LANG)
        val start = node.children[2].startOffset
        val minCount = if (language != null && node.children.size > 3) 3 else 2
        val end = node.children[(node.children.size - 2).coerceAtLeast(minCount)].endOffset
        content.subSequence(start, end).toString().replaceIndent()
    } else if (!isFence && node.children.isNotEmpty()) {
        val start = node.children[0].startOffset
        val end = node.children[node.children.size - 1].endOffset
        content.subSequence(start, end).toString().replaceIndent()
    } else {
        content
    }
    val lang = if (isFence) {
        node.findChildOfType(MarkdownTokenTypes.FENCE_LANG)
            ?.getTextInNode(content)?.toString()?.trim()?.lowercase() ?: ""
    } else {
        ""
    }
    return codeText to lang
}

/**
 * Renders a code block.
 *
 * User messages show plain monospaced text (their bubble already conveys context);
 * assistant messages get full Highlight.js syntax coloring via [SyntaxHighlightedCode]
 * with a built-in copy button that surfaces a toast on copy.
 */
@Composable
private fun CodeBlockRenderer(
    code: String,
    language: String,
    isUser: Boolean,
    density: ChatDensity,
    backgroundColor: Color,
    codeBlockFg: Color,
    context: android.content.Context,
) {
    val spacing = density.spacing
    val tokens = density.typography

    Surface(
        shape = ShapeTokens.extraSmall,
        color = backgroundColor,
        tonalElevation = 0.dp,
    ) {
        if (isUser) {
            Text(
                text = code,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = tokens.codeFontSize,
                    lineHeight = tokens.codeLineHeight,
                    color = codeBlockFg,
                ),
                modifier = Modifier.padding(all = spacing.codeBlock),
            )
        } else {
            SyntaxHighlightedCode(
                code = code,
                language = language.ifBlank { "plaintext" },
                style = CodeBlockStyle(
                    shape = RectangleShape,
                    padding = PaddingValues(spacing.codeBlock),
                    textStyle = TextStyle(
                        fontSize = tokens.codeFontSize,
                        lineHeight = tokens.codeLineHeight,
                        fontFamily = FontFamily.Monospace,
                    ),
                    copyButtonSize = 24.dp,
                ),
                onCopyClick = {
                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                },
            )
        }
    }
}
