package dev.leonardo.ocremotev2.ui.screens.chat.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.leonardo.ocremotev2.R
import dev.leonardo.ocremotev2.domain.model.Part
import dev.leonardo.ocremotev2.domain.model.ToolState
import dev.leonardo.ocremotev2.ui.screens.chat.tools.cards.ToolCardScaffold
import dev.leonardo.ocremotev2.ui.screens.chat.util.isAmoledTheme
import dev.leonardo.ocremotev2.ui.theme.AlphaTokens
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Composable
fun ContextToolGroupCard(
    parts: List<Part.Tool>,
    onOpenFile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val pending = remember(parts) {
        parts.any { it.state is ToolState.Running || it.state is ToolState.Pending }
    }
    val summary = remember(parts) { contextToolSummary(parts) }
    val isAmoled = isAmoledTheme()

    val statusText = stringResource(
        if (pending) R.string.context_exploring else R.string.context_explored
    )
    val title = buildString {
        append(statusText)
        if (summary.read > 0) {
            append(" · ")
            append(stringResource(R.string.context_read_count, summary.read))
        }
        if (summary.search > 0) {
            append(" · ")
            append(stringResource(R.string.context_search_count, summary.search))
        }
    }

    ToolCardScaffold(
        icon = if (pending) Icons.Filled.Search else Icons.Filled.CheckCircle,
        iconTint = if (pending) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurfaceVariant,
        title = title,
        copyText = title,
        isExpanded = expanded,
        isRunning = pending,
        hasContent = true,
        isAmoled = isAmoled,
        onToggleExpand = { expanded = !expanded },
        modifier = modifier,
    ) {
        Column {
            parts.forEachIndexed { idx, part ->
                if (idx > 0) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(
                            alpha = AlphaTokens.FAINT
                        )
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { handleToolClick(part, onOpenFile) }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = toolIcon(part.tool),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = toolLabel(part.tool),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    val subtitle = toolSubtitle(part)
                    if (subtitle.isNotEmpty()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

private fun handleToolClick(part: Part.Tool, onOpenFile: (String) -> Unit) {
    if (part.tool.lowercase() != "read") return
    val input = extractToolInput(part)
    val filePath = input["filePath"]?.jsonPrimitive?.contentOrNull
        ?: input["path"]?.jsonPrimitive?.contentOrNull
    if (filePath != null) onOpenFile(filePath)
}

private fun toolIcon(toolName: String) = when (toolName.lowercase()) {
    "read" -> Icons.Filled.Description
    "glob" -> Icons.Filled.FindInPage
    "grep" -> Icons.Filled.Search
    else -> Icons.Filled.Description
}

@Composable
private fun toolLabel(toolName: String): String = when (toolName.lowercase()) {
    "read" -> stringResource(R.string.tool_read)
    "glob" -> stringResource(R.string.tool_glob)
    "grep" -> stringResource(R.string.tool_grep)
    else -> toolName
}

private fun toolSubtitle(part: Part.Tool): String {
    val input = extractToolInput(part)
    return when (part.tool.lowercase()) {
        "read" -> {
            val path = input["filePath"]?.jsonPrimitive?.contentOrNull
                ?: input["path"]?.jsonPrimitive?.contentOrNull ?: ""
            extractFileName(path)
        }
        "glob", "grep" -> input["pattern"]?.jsonPrimitive?.contentOrNull ?: ""
        else -> ""
    }
}
