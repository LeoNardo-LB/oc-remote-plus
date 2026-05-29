package dev.minios.ocremote.ui.screens.chat.tools.cards

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import dev.minios.ocremote.ui.screens.chat.markdown.MarkdownContent
import dev.minios.ocremote.ui.screens.chat.tools.extractToolInput
import dev.minios.ocremote.ui.screens.chat.tools.extractToolOutput
import dev.minios.ocremote.ui.screens.chat.util.LocalHapticFeedbackEnabled
import dev.minios.ocremote.ui.screens.chat.util.consumeBoundaryFling
import dev.minios.ocremote.ui.screens.chat.util.isAmoledTheme
import dev.minios.ocremote.ui.screens.chat.util.performHaptic
import dev.minios.ocremote.ui.screens.chat.util.toolOutputContainerColor
import dev.minios.ocremote.ui.theme.CodeTypography
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Task (sub-agent) tool card — shows description + child info.
 * Like WebUI: trigger = "Agent (task)" + description, content = child tool list.
 */
@Composable
internal fun TaskToolCard(
    tool: Part.Tool,
    onViewSubSession: ((String) -> Unit)? = null,
    turnAgentName: String? = null,
    isExpanded: Boolean = false,
    onToggleExpand: () -> Unit = {}
) {
    val isAmoled = isAmoledTheme()
    val input = extractToolInput(tool)
    val description = input["description"]?.jsonPrimitive?.contentOrNull
    val inputAgentType = input["subagent_type"]?.jsonPrimitive?.contentOrNull?.replaceFirstChar { it.uppercase() }
    val metadataAgentName = when (val s = tool.state) {
        is ToolState.Completed -> s.metadata?.get("agent")?.jsonPrimitive?.contentOrNull
        is ToolState.Running -> s.metadata?.get("agent")?.jsonPrimitive?.contentOrNull
        else -> null
    }
    val agentType = inputAgentType
        ?: metadataAgentName?.replaceFirstChar { it.uppercase() }
        ?: turnAgentName?.replaceFirstChar { it.uppercase() }
    val output = extractToolOutput(tool)

    val serverTitle = when (val s = tool.state) {
        is ToolState.Running -> s.title
        is ToolState.Completed -> s.title
        else -> null
    }

    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current
    val expanded = isExpanded
    val isRunning = tool.state is ToolState.Running
    val hasOutput = output.isNotBlank()
    val subSessionId = when (val state = tool.state) {
            is ToolState.Completed -> state.metadata?.get("sessionId")
            is ToolState.Running -> state.metadata?.get("sessionId")
            else -> null
        }?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            ?.takeIf { it?.isNotBlank() == true }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
        border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
        tonalElevation = if (isAmoled) 0.dp else 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .let { mod ->
                        when {
                            subSessionId != null && onViewSubSession != null ->
                                mod.clickable { performHaptic(hapticView, hapticOn); onViewSubSession(subSessionId) }
                            else ->
                                mod.clickable { performHaptic(hapticView, hapticOn); onToggleExpand() }
                        }
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.AccountTree,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = agentType?.let { "$it Agent" }
                                ?: serverTitle?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.tool_sub_agent),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1
                        )
                        if (description != null) {
                            Text(
                                text = description,
                                style = CodeTypography.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                if (isRunning) {
                    PulsingDotsIndicator(dotSize = 5.dp, dotSpacing = 3.dp, color = MaterialTheme.colorScheme.tertiary)
                } else if (subSessionId != null && onViewSubSession != null) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                } else if (hasOutput) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded && hasOutput,
            ) {
                val halfScreenHeight = LocalConfiguration.current.screenHeightDp.dp / 2
                val scrollState = rememberScrollState()
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = toolOutputContainerColor(isAmoled),
                    border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .heightIn(max = halfScreenHeight)
                        .consumeBoundaryFling(scrollState)
                        .verticalScroll(scrollState)
                ) {
                    MarkdownContent(
                        markdown = output,
                        textColor = if (isAmoled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f) else MaterialTheme.colorScheme.onSecondaryContainer,
                        isUser = false
                    )
                }
            }
        }
    }
}
