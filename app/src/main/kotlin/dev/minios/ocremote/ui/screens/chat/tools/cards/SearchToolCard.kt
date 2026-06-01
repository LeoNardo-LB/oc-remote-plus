package dev.minios.ocremote.ui.screens.chat.tools.cards

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.R
import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.domain.model.ToolState
import dev.minios.ocremote.ui.components.indicators.PulsingDotsIndicator
import dev.minios.ocremote.ui.screens.chat.markdown.MarkdownContent
import dev.minios.ocremote.ui.screens.chat.tools.extractToolInput
import dev.minios.ocremote.ui.screens.chat.tools.extractToolOutput
import dev.minios.ocremote.ui.screens.chat.util.LocalHapticFeedbackEnabled
import dev.minios.ocremote.ui.screens.chat.util.halfScreenHeight
import dev.minios.ocremote.ui.screens.chat.util.isAmoledTheme
import dev.minios.ocremote.ui.screens.chat.util.performHaptic
import dev.minios.ocremote.ui.screens.chat.util.toolOutputContainerColor
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Search tool card (glob/grep) — shows pattern + expandable output.
 * Like WebUI: trigger = "Glob"/"Grep" + directory + [pattern=...], content = markdown output.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SearchToolCard(
    tool: Part.Tool,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit
) {
    val isAmoled = isAmoledTheme()
    val input = extractToolInput(tool)
    val pattern = input["pattern"]?.jsonPrimitive?.contentOrNull
    val include = input["include"]?.jsonPrimitive?.contentOrNull
    val dirPath = input["path"]?.jsonPrimitive?.contentOrNull
    val output = extractToolOutput(tool)

    val serverTitle = when (val s = tool.state) {
        is ToolState.Running -> s.title
        is ToolState.Completed -> s.title
        else -> null
    }

    val baseTitle = when (tool.tool) {
        "glob" -> stringResource(R.string.tool_find_files)
        "grep" -> stringResource(R.string.tool_search_code)
        else -> tool.tool.replace("_", " ").replaceFirstChar { it.uppercase() }
    }
    val patternShort = pattern?.let {
        if (it.length > 40) it.take(37) + "..." else it
    }
    val title = if (patternShort != null) "$baseTitle · $patternShort" else baseTitle

    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current
    val expanded = isExpanded
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val isRunning = tool.state is ToolState.Running
    val hasOutput = output.isNotBlank()

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
                    .combinedClickable(
                        onClick = { performHaptic(hapticView, hapticOn); onToggleExpand() },
                        onLongClick = {
                            clipboardManager.setText(AnnotatedString(title))
                            Toast.makeText(context, context.getString(R.string.chat_copied_clipboard), Toast.LENGTH_SHORT).show()
                        }
                    ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (isRunning) {
                    PulsingDotsIndicator(dotSize = 5.dp, dotSpacing = 3.dp, color = MaterialTheme.colorScheme.tertiary)
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
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                val halfScreenHeight = halfScreenHeight()
                val scrollState = rememberScrollState()
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = toolOutputContainerColor(isAmoled),
                    border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 3.dp)
                        .heightIn(max = halfScreenHeight)
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
