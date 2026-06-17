package dev.minios.ocremote.ui.screens.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.R
import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.domain.model.ToolState
import dev.minios.ocremote.ui.screens.chat.util.LocalCollapseTools
import dev.minios.ocremote.ui.screens.chat.util.LocalOnToggleToolExpanded
import dev.minios.ocremote.ui.screens.chat.util.LocalToolCardResolver
import dev.minios.ocremote.ui.screens.chat.util.LocalToolExpandedStates
import dev.minios.ocremote.ui.screens.chat.util.contextToolSummary
import dev.minios.ocremote.ui.theme.AlphaTokens
import dev.minios.ocremote.ui.theme.ShapeTokens
import dev.minios.ocremote.ui.theme.SpacingTokens

@Composable
internal fun ContextToolGroupCard(
    tools: List<Part.Tool>,
    onViewSubSession: ((String) -> Unit)? = null,
    turnAgentName: String? = null
) {
    val groupKey = remember(tools) { "context:${tools.first().id}" }
    val defaultOpen = LocalCollapseTools.current
    val expandedStates = LocalToolExpandedStates.current
    val onToggle = LocalOnToggleToolExpanded.current
    val isExpanded = expandedStates[groupKey] ?: defaultOpen

    val summary = remember(tools) { contextToolSummary(tools) }
    val isRunning = remember(tools) { tools.any { it.state is ToolState.Running } }
    val titleRes = if (isRunning) R.string.chat_context_gathering else R.string.chat_context_gathered

    Surface(
        shape = ShapeTokens.smallMedium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(SpacingTokens.SM.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle(groupKey, defaultOpen) }
                    .padding(vertical = SpacingTokens.XS.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp)
            ) {
                if (isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = stringResource(titleRes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Count summary: skip categories with count == 0, join with " · "
                if (summary.read > 0) {
                    Text(
                        text = stringResource(R.string.chat_context_count_read, summary.read),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED)
                    )
                }
                if (summary.search > 0) {
                    Text(
                        text = stringResource(R.string.chat_context_count_search, summary.search),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED)
                    )
                }
                if (summary.list > 0) {
                    Text(
                        text = stringResource(R.string.chat_context_count_list, summary.list),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED)
                    )
                }
                if (summary.write > 0) {
                    Text(
                        text = stringResource(R.string.chat_context_count_write, summary.write),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED)
                    )
                }
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
                    modifier = Modifier.size(16.dp)
                )
            }
            AnimatedVisibility(visible = isExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp)) {
                    tools.forEach { tool ->
                        key(tool.id) {
                            val autoExpand = LocalCollapseTools.current
                            val toolExpanded = expandedStates[tool.id] ?: autoExpand
                            val resolved = LocalToolCardResolver.current.resolve(
                                tool = tool,
                                isExpanded = toolExpanded,
                                onToggleExpand = { onToggle(tool.id, autoExpand) },
                                onViewSubSession = onViewSubSession,
                                turnAgentName = turnAgentName
                            )
                            resolved?.invoke()
                        }
                    }
                }
            }
        }
    }
}
