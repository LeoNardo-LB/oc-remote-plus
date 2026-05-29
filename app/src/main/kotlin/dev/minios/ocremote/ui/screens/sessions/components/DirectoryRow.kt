package dev.minios.ocremote.ui.screens.sessions.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.R

/**
 * A single directory row in the browser.
 * Tap to select. Has a chevron to navigate into the directory.
 */
@Composable
internal fun DirectoryRow(
    displayPath: String,
    onClick: () -> Unit,
    onNavigate: (() -> Unit)? = null
) {
    // Split into parent + leaf for styling
    val trimmed = displayPath.trimEnd('/')
    val lastSlash = trimmed.lastIndexOf('/')
    val parent = if (lastSlash > 0) trimmed.substring(0, lastSlash + 1) else ""
    val leaf = if (lastSlash >= 0) trimmed.substring(lastSlash + 1) else trimmed
    val trailing = if (displayPath.endsWith("/")) "/" else ""

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            Icons.Default.Folder,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        Text(
            text = buildAnnotatedString {
                if (parent.isNotEmpty()) {
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))) {
                        append(parent)
                    }
                }
                withStyle(SpanStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium
                )) {
                    append(leaf)
                }
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))) {
                    append(trailing)
                }
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
            if (onNavigate != null) {
                IconButton(
                    onClick = onNavigate,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = stringResource(R.string.open),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                }
            }
    }
}
