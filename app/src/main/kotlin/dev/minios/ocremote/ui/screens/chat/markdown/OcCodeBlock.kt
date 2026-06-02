package dev.minios.ocremote.ui.screens.chat.markdown

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeFence
import dev.snipme.highlights.Highlights
import org.intellij.markdown.ast.ASTNode
import dev.minios.ocremote.ui.theme.ShapeTokens
import dev.minios.ocremote.ui.theme.AlphaTokens

/**
 * Custom code block component replacing mikepenz's built-in showHeader.
 *
 * Renders highlighted code with a floating copy button (top-right corner).
 * Copy action triggers a system Toast ("已复制").
 *
 * @param content  Raw code text
 * @param node     Markdown AST node (used by mikepenz for language detection)
 * @param style    TextStyle for code (from markdownTypography)
 * @param highlightsBuilder  Reusable Highlights.Builder for syntax highlighting
 * @param wordWrap Whether to wrap long lines (true) or use horizontal scroll (false)
 * @param isFence  true for fenced code blocks (```), false for indented (4 spaces)
 */
@Composable
internal fun OcCodeBlock(
    content: String,
    node: ASTNode,
    style: TextStyle,
    highlightsBuilder: Highlights.Builder,
    wordWrap: Boolean,
    isFence: Boolean = false
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val maxWidth = LocalConfiguration.current.screenWidthDp.dp

    Box(
        modifier = Modifier
            .clip(ShapeTokens.extraSmall)
            .fillMaxWidth()
            .widthIn(max = maxWidth)
    ) {
        // Syntax-highlighted code (mikepenz engine, no built-in header)
        // immediate=true: synchronous highlight avoids Highlights.Builder race condition
        // widthIn ensures finite constraints reach mikepenz's internal Row + horizontalScroll
        val immediate = true
        if (isFence) {
            MarkdownHighlightedCodeFence(
                content, node, style, highlightsBuilder,
                false,  // showHeader
                immediate
            )
        } else {
            MarkdownHighlightedCodeBlock(
                content, node, style, highlightsBuilder,
                false,  // showHeader
                immediate
            )
        }

        // Floating copy button (top-right, always visible, fixed position)
        IconButton(
            onClick = {
                clipboardManager.setText(AnnotatedString(content))
                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
                .size(24.dp)
        ) {
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = "复制代码",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED)
            )
        }
    }
}
