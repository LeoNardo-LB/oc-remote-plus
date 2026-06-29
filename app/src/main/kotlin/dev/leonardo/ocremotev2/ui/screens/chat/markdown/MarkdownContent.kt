package dev.leonardo.ocremotev2.ui.screens.chat.markdown

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.material.MarkdownBasicText
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.findChildOfType
import dev.leonardo.ocremotev2.domain.model.LinkClassifier
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

    // Explicit link handler — captures LocalUriHandler at THIS scope, ensuring
    // the custom UriHandler (provided by ChatScreen) is used for link clicks
    // even inside SelectionContainer.
    val uriHandler = LocalUriHandler.current
    val linkListener = remember(uriHandler) {
        LinkInteractionListener { link ->
            val url = (link as? LinkAnnotation.Url)?.url
            if (url != null) uriHandler.openUri(url)
        }
    }

    val components = remember(density, isUser, linkListener) {
        markdownComponents(
            paragraph = { model ->
                val settings = annotatorSettings(linkInteractionListener = linkListener)
                val rawAnnotated = model.content.buildMarkdownAnnotatedString(
                    textNode = model.node,
                    style = model.typography.text,
                    annotatorSettings = settings,
                )
                val items = remember(model.content, model.node) {
                    extractClickableItems(model.content, model.node)
                }
                val codePaths = items.filterIsInstance<ClickableItem.CodePath>()
                val annotated = if (codePaths.isEmpty()) rawAnnotated else {
                    buildAnnotatedString {
                        append(rawAnnotated.text)
                        rawAnnotated.spanStyles.forEach { range ->
                            addStyle(range.item, range.start, range.end)
                        }
                        var searchFrom = 0
                        for (cp in codePaths) {
                            val idx = rawAnnotated.text.indexOf(cp.text, searchFrom)
                            if (idx >= 0) {
                                addStyle(
                                    SpanStyle(
                                        textDecoration = TextDecoration.Underline,
                                        color = linkColor,
                                    ),
                                    idx, idx + cp.text.length,
                                )
                                searchFrom = idx + cp.text.length
                            }
                        }
                    }
                }
                var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
                MarkdownBasicText(
                    text = annotated,
                    style = model.typography.text,
                    onTextLayout = { layoutResult = it },
                    modifier = Modifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { }
                        .pointerInput(annotated) {
                            detectTapGestures { pos ->
                                val layout = layoutResult ?: return@detectTapGestures
                                val offset = layout.getOffsetForPosition(pos)
                                val text = annotated.text
                                var searchFrom = 0
                                for (item in items) {
                                    val idx = text.indexOf(item.text, searchFrom)
                                    if (idx >= 0 && offset >= idx && offset < idx + item.text.length) {
                                        when (item) {
                                            is ClickableItem.Link -> uriHandler.openUri(item.url)
                                            is ClickableItem.CodePath -> uriHandler.openUri(item.text)
                                        }
                                        return@detectTapGestures
                                    }
                                    if (idx >= 0) searchFrom = idx + item.text.length
                                }
                            }
                        },
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

    val markdownState = rememberMarkdownState(
        content = normalizedMarkdown,
        retainState = true,
    )

    Markdown(
        markdownState = markdownState,
        colors = colors,
        typography = typography,
        components = components,
        padding = padding,
        animations = markdownAnimations(animateTextSize = { this }),
        imageTransformer = Coil3ImageTransformerImpl,
        modifier = Modifier.fillMaxWidth(),
    )
}

private sealed interface ClickableItem {
    val text: String
    data class Link(override val text: String, val url: String) : ClickableItem
    data class CodePath(override val text: String) : ClickableItem
}

/**
 * Extract clickable items from a markdown AST paragraph node:
 * - [text](url) markdown links → ClickableItem.Link
 * - `code` inline code that looks like a path → ClickableItem.CodePath
 */
private fun extractClickableItems(content: String, node: org.intellij.markdown.ast.ASTNode): List<ClickableItem> {
    val items = mutableListOf<ClickableItem>()
    fun walk(n: org.intellij.markdown.ast.ASTNode) {
        if (n.type == MarkdownElementTypes.INLINE_LINK) {
            val dest = n.findChildOfType(MarkdownElementTypes.LINK_DESTINATION)
            val textNode = n.findChildOfType(MarkdownElementTypes.LINK_TEXT)
            if (dest != null && textNode != null) {
                val url = dest.getUnescapedTextInNode(content).toString()
                val rawText = textNode.getUnescapedTextInNode(content).toString()
                val linkText = rawText.removeSurrounding("[", "]")
                if (linkText.isNotEmpty() && url.isNotEmpty()) {
                    items.add(ClickableItem.Link(linkText, url))
                }
            }
        } else if (n.type == MarkdownElementTypes.CODE_SPAN) {
            val raw = n.getUnescapedTextInNode(content).toString()
            val codeText = raw.trim('`').trim()
            if (codeText.isNotEmpty() && LinkClassifier.isLikelyFilePath(codeText)) {
                items.add(ClickableItem.CodePath(codeText))
            }
        }
        n.children.forEach { walk(it) }
    }
    walk(node)
    return items
}

