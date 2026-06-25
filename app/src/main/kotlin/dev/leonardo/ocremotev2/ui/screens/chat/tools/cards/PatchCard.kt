package dev.leonardo.ocremotev2.ui.screens.chat.tools.cards

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.leonardo.ocremotev2.R
import dev.leonardo.ocremotev2.domain.model.Part
import dev.leonardo.ocremotev2.ui.screens.chat.util.isAmoledTheme
import dev.leonardo.ocremotev2.ui.theme.AlphaTokens
import dev.leonardo.ocremotev2.ui.theme.CodeTypography
import dev.leonardo.ocremotev2.util.PathUtils

/**
 * Shows a summary of files changed at the end of an agent turn.
 * Uses [ToolCardScaffold] for consistent styling with other tool cards.
 * Each file in the expanded list is clickable → navigates to FileViewer.
 */
@Composable
internal fun PatchCard(
    patch: Part.Patch,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onOpenFile: ((filePath: String) -> Unit)? = null
) {
    val isAmoled = isAmoledTheme()
    val title = if (patch.files.size == 1)
        stringResource(R.string.chat_files_changed, patch.files.size)
    else
        stringResource(R.string.chat_files_changed_plural, patch.files.size)

    ToolCardScaffold(
        icon = Icons.Default.Code,
        iconTint = MaterialTheme.colorScheme.primary,
        title = title,
        copyText = title,
        isExpanded = isExpanded,
        isRunning = false,
        hasContent = patch.files.isNotEmpty(),
        isAmoled = isAmoled,
        onToggleExpand = onToggleExpand,
    ) {
        Column(
            modifier = Modifier.padding(top = 2.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            for (filePath in patch.files) {
                Surface(
                    onClick = { onOpenFile?.invoke(filePath) },
                    enabled = onOpenFile != null,
                    shape = dev.leonardo.ocremotev2.ui.theme.ShapeTokens.small,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = AlphaTokens.MEDIUM),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.MEDIUM)
                        )
                        Text(
                            text = PathUtils.fileName(filePath),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
