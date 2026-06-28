package dev.leonardo.ocremotev2.ui.screens.viewer

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.rememberMarkdownState
import dev.leonardo.ocremotev2.ui.theme.AlphaTokens
import dev.leonardo.ocremotev2.ui.theme.CodeTypography
import dev.leonardo.ocremotev2.ui.theme.SpacingTokens

/**
 * Read-only Markdown preview for the FileViewer (spec §7.3).
 *
 * Distinct from chat module's `internal MarkdownContent`:
 * - No user/agent message styling, no AMOLED switching
 * - Single theme: surfaceContainer background, onSurface text
 *
 * Uses mikepenz multiplatform-markdown-renderer-m3 (project dependency).
 * Rendered as multiple independent Text composables — selection across
 * components is not supported (spec §2.2). Hence read-only preview.
 *
 * @param scrollState injectable for fraction-anchor scroll (Task 7).
 */
@Composable
fun MarkdownPreview(
    markdown: String,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState()
) {
    val textColor = MaterialTheme.colorScheme.onSurface
    val isDark = isSystemInDarkTheme()
    val codeBlockBg = if (isDark) MaterialTheme.colorScheme.surfaceContainerHighest
    else MaterialTheme.colorScheme.surfaceContainer
    val inlineCodeBg = MaterialTheme.colorScheme.primaryContainer.copy(alpha = AlphaTokens.FAINT)
    val inlineCodeFg = MaterialTheme.colorScheme.primary

    val colors = markdownColor(
        text = textColor,
        codeBackground = codeBlockBg,
        inlineCodeBackground = inlineCodeBg,
        dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT),
        tableBackground = MaterialTheme.colorScheme.surfaceContainerLow
    )

    val typography = markdownTypography(
        h1 = MaterialTheme.typography.titleLarge.copy(color = textColor, fontWeight = FontWeight.Bold),
        h2 = MaterialTheme.typography.titleMedium.copy(color = textColor, fontWeight = FontWeight.SemiBold),
        h3 = MaterialTheme.typography.titleSmall.copy(color = textColor, fontWeight = FontWeight.SemiBold),
        h4 = MaterialTheme.typography.bodyLarge.copy(color = textColor, fontWeight = FontWeight.SemiBold),
        text = MaterialTheme.typography.bodyMedium.copy(color = textColor),
        code = CodeTypography.copy(color = MaterialTheme.colorScheme.onSurface),
        inlineCode = CodeTypography.copy(color = inlineCodeFg, fontWeight = FontWeight.Medium),
        quote = MaterialTheme.typography.bodyMedium.copy(
            color = textColor.copy(alpha = AlphaTokens.MEDIUM),
            fontStyle = FontStyle.Italic
        ),
        paragraph = MaterialTheme.typography.bodyMedium.copy(color = textColor),
        ordered = MaterialTheme.typography.bodyMedium.copy(color = textColor),
        bullet = MaterialTheme.typography.bodyMedium.copy(color = textColor),
        list = MaterialTheme.typography.bodyMedium.copy(color = textColor)
    )

    val dimens = markdownDimens()
    val components = markdownComponents()
    val markdownState = rememberMarkdownState(content = markdown)

    Markdown(
        markdownState = markdownState,
        colors = colors,
        typography = typography,
        components = components,
        dimens = dimens,
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(scrollState)
            .padding(horizontal = SpacingTokens.LG.dp, vertical = SpacingTokens.MD.dp)
    )
}
