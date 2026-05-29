package dev.minios.ocremote.ui.screens.chat.tools

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.minios.ocremote.R
import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.domain.model.ToolState
import dev.minios.ocremote.ui.screens.chat.PulsingDotsIndicator
import dev.minios.ocremote.ui.screens.chat.util.LocalHapticFeedbackEnabled
import dev.minios.ocremote.ui.screens.chat.util.consumeBoundaryFling
import dev.minios.ocremote.ui.screens.chat.util.isAmoledTheme
import dev.minios.ocremote.ui.screens.chat.util.performHaptic
import dev.minios.ocremote.ui.screens.chat.util.toolOutputContainerColor
import dev.minios.ocremote.ui.screens.chat.util.codeHorizontalScroll
import dev.minios.ocremote.ui.theme.CodeTypography
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Composable
internal fun ToolCallCard(
    tool: Part.Tool,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit
) {
    val isAmoled = isAmoledTheme()
    val stateColor = when (tool.state) {
        is ToolState.Pending -> MaterialTheme.colorScheme.outline
        is ToolState.Running -> MaterialTheme.colorScheme.tertiary
        is ToolState.Completed -> MaterialTheme.colorScheme.primary
        is ToolState.Error -> MaterialTheme.colorScheme.error
    }

    // Extract input args for context-specific display
    val input = when (val state = tool.state) {
        is ToolState.Pending -> state.input
        is ToolState.Running -> state.input
        is ToolState.Completed -> state.input
        is ToolState.Error -> state.input
    }

    // Resolve display info based on tool type
    val toolDisplay = resolveToolDisplay(tool.tool, tool.state, input)

    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current
    val expanded = isExpanded

    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
        border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
        tonalElevation = if (isAmoled) 0.dp else 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // Header row — always clickable to allow expand/collapse in any state
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { performHaptic(hapticView, hapticOn); onToggleExpand() },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = when (tool.state) {
                            is ToolState.Running -> Icons.Default.Sync
                            is ToolState.Completed -> toolDisplay.icon
                            is ToolState.Error -> Icons.Default.Error
                            else -> Icons.Default.PlayArrow
                        },
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (tool.state is ToolState.Error) stateColor else toolDisplay.iconTint ?: stateColor
                    )
                    if (tool.tool == "task") {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = toolDisplay.title,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (toolDisplay.subtitle != null) {
                                Text(
                                    text = toolDisplay.subtitle,
                                    style = CodeTypography.copy(fontSize = 11.sp),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    } else {
                        val displayText = if (toolDisplay.subtitle != null && toolDisplay.subtitle != toolDisplay.title) {
                            "${toolDisplay.title} · ${toolDisplay.subtitle}"
                        } else {
                            toolDisplay.title
                        }
                        Text(
                            text = displayText,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                if (tool.state is ToolState.Running) {
                    PulsingDotsIndicator(dotSize = 5.dp, dotSpacing = 3.dp, color = stateColor)
                } else {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) stringResource(R.string.chat_collapse) else stringResource(R.string.chat_expand),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                val halfScreenHeight = LocalConfiguration.current.screenHeightDp.dp / 2
                val toolCardScrollState = rememberScrollState()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = halfScreenHeight)
                        .consumeBoundaryFling(toolCardScrollState)
                        .verticalScroll(toolCardScrollState)
                ) {
                    Column(
                        modifier = Modifier.padding(top = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (input.isNotEmpty()) {
                            val inputText = input.entries
                                .filter { (_, v) -> v.toString().length <= 500 }
                                .joinToString("\n") { (k, v) ->
                                    val value = (v as? JsonPrimitive)?.contentOrNull ?: v.toString().take(200)
                                    "$k: $value"
                                }
                            if (inputText.isNotBlank()) {
                                Surface(
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                                    color = toolOutputContainerColor(isAmoled),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = inputText.take(2000),
                                        style = CodeTypography.copy(fontSize = 11.sp, color = if (isAmoled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)),
                                        modifier = Modifier.padding(8.dp).codeHorizontalScroll()
                                    )
                                }
                            }
                        }
                        val output = when (val s = tool.state) {
                            is ToolState.Completed -> s.output
                            is ToolState.Error -> s.error
                            else -> ""
                        }
                        if (output.isNotBlank()) {
                            Surface(
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                                color = toolOutputContainerColor(isAmoled),
                                border = if (isAmoled) BorderStroke(1.dp, stateColor.copy(alpha = 0.6f)) else null,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = output.take(3000),
                                    style = CodeTypography.copy(fontSize = 11.sp, color = if (isAmoled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f) else MaterialTheme.colorScheme.onSecondaryContainer),
                                    modifier = Modifier.padding(8.dp).codeHorizontalScroll()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Extract common tool input values.
 */
internal fun extractToolInput(tool: Part.Tool): Map<String, kotlinx.serialization.json.JsonElement> {
    return when (val state = tool.state) {
        is ToolState.Pending -> state.input
        is ToolState.Running -> state.input
        is ToolState.Completed -> state.input
        is ToolState.Error -> state.input
    }
}

internal fun extractToolOutput(tool: Part.Tool): String {
    return when (val s = tool.state) {
        is ToolState.Completed -> s.output
        is ToolState.Error -> s.error
        else -> ""
    }
}
