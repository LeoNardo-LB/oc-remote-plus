package dev.leonardo.ocremotev2.ui.screens.chat.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import dev.leonardo.ocremotev2.ui.theme.AlphaTokens

/**
 * A small copy button that copies [text] to clipboard.
 * Used in assistant message bubbles and tool cards.
 */
@Composable
fun CopyButton(
    text: String,
    modifier: Modifier = Modifier,
    contentDescription: String = "Copy"
) {
    val clipboardManager = LocalClipboardManager.current
    IconButton(
        onClick = { clipboardManager.setText(AnnotatedString(text)) },
        modifier = modifier
    ) {
        Icon(
            imageVector = Icons.Default.ContentCopy,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED),
            modifier = Modifier
        )
    }
}
