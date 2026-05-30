package dev.minios.ocremote.ui.screens.chat.tools.cards

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Error
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.minios.ocremote.R
import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.domain.model.ToolState
import dev.minios.ocremote.ui.components.indicators.PulsingDotsIndicator
import dev.minios.ocremote.ui.screens.chat.tools.extractToolInput
import dev.minios.ocremote.ui.screens.chat.util.LocalHapticFeedbackEnabled
import dev.minios.ocremote.ui.screens.chat.util.codeHorizontalScroll
import dev.minios.ocremote.ui.screens.chat.util.halfScreenHeight
import dev.minios.ocremote.ui.screens.chat.util.isAmoledTheme
import dev.minios.ocremote.ui.screens.chat.util.performHaptic
import dev.minios.ocremote.ui.screens.chat.util.toolOutputContainerColor
import dev.minios.ocremote.ui.theme.CodeTypography
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Read tool card — shows "读取" title, file name subtitle, expandable for details.
 */
@Composable
internal fun ReadToolCard(
    tool: Part.Tool,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit
) {
    val isAmoled = isAmoledTheme()
    val input = extractToolInput(tool)
    val filePath = input["filePath"]?.jsonPrimitive?.contentOrNull
        ?: input["path"]?.jsonPrimitive?.contentOrNull ?: ""
    val shortPath = filePath.substringAfterLast('/')
    val offset = input["offset"]?.jsonPrimitive?.contentOrNull
    val limit = input["limit"]?.jsonPrimitive?.contentOrNull

    val isRunning = tool.state is ToolState.Running
    val isError = tool.state is ToolState.Error

    // Build args string like WebUI: [offset=N, limit=N]
    val args = buildList {
        offset?.let { add("offset=$it") }
        limit?.let { add("limit=$it") }
    }.takeIf { it.isNotEmpty() }?.joinToString(", ", "[", "]")

    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current
    val expanded = isExpanded
    val isCompleted = tool.state is ToolState.Completed

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
        border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
        tonalElevation = if (isAmoled) 0.dp else 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(4.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { performHaptic(hapticView, hapticOn); onToggleExpand() },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (isError) Icons.Default.Error else Icons.Default.Description,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                    val displayText = if (shortPath.isNotBlank()) {
                        "${stringResource(R.string.tool_read)} · $shortPath"
                    } else {
                        stringResource(R.string.tool_read)
                    }
                    Text(
                        text = displayText,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (isRunning) {
                    PulsingDotsIndicator(dotSize = 5.dp, dotSpacing = 3.dp, color = MaterialTheme.colorScheme.tertiary)
                } else {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded,
            ) {
                val halfScreenHeight = halfScreenHeight()
                val scrollState = rememberScrollState()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = halfScreenHeight)
                        .verticalScroll(scrollState)
                ) {
                    Column(
                        modifier = Modifier.padding(top = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        if (filePath.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = toolOutputContainerColor(isAmoled),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = filePath,
                                    style = CodeTypography.copy(
                                        fontSize = 11.sp,
                                        color = if (isAmoled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                                    ),
                                    modifier = Modifier
                                        .padding(4.dp)
                                        .codeHorizontalScroll()
                                )
                            }
                        }
                        if (args != null) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = toolOutputContainerColor(isAmoled),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = args,
                                    style = CodeTypography.copy(
                                        fontSize = 11.sp,
                                        color = if (isAmoled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                                    ),
                                    modifier = Modifier
                                        .padding(4.dp)
                                        .codeHorizontalScroll()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
