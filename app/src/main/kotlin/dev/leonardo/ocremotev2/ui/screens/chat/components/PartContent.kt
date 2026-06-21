package dev.leonardo.ocremotev2.ui.screens.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.leonardo.ocremotev2.ui.theme.AlphaTokens
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.leonardo.ocremotev2.R
import dev.leonardo.ocremotev2.domain.model.Part
import androidx.compose.foundation.text.selection.SelectionContainer
import dev.leonardo.ocremotev2.ui.screens.chat.markdown.MarkdownContent
import dev.leonardo.ocremotev2.ui.screens.chat.tools.ToolCallCard
import dev.leonardo.ocremotev2.ui.screens.chat.tools.cards.PatchCard
import dev.leonardo.ocremotev2.ui.screens.chat.tools.cards.TodoListCard
import dev.leonardo.ocremotev2.ui.screens.chat.util.LocalCollapseTools
import dev.leonardo.ocremotev2.ui.screens.chat.util.LocalToolCardResolver
import dev.leonardo.ocremotev2.ui.screens.chat.util.LocalExpandReasoning
import dev.leonardo.ocremotev2.ui.screens.chat.util.LocalHapticFeedbackEnabled
import dev.leonardo.ocremotev2.ui.screens.chat.util.LocalOnToggleToolExpanded
import dev.leonardo.ocremotev2.ui.screens.chat.util.LocalToolExpandedStates
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Composable
internal fun PartContent(
    part: Part,
    textColor: Color,
    isUser: Boolean = false,
    onViewSubSession: ((String) -> Unit)? = null,
    turnAgentName: String? = null,
    onOpenFile: ((filePath: String) -> Unit)? = null
) {
    when (part) {
        is Part.Text -> {
            // Hide synthetic/ignored text parts (internal system content)
            if (part.text.isNotBlank() && part.synthetic != true && part.ignored != true) {
                SelectionContainer {
                    MarkdownContent(
                        markdown = part.text,
                        textColor = textColor,
                        isUser = isUser,
                        immediate = !isUser  // assistant messages: synchronous to avoid height jump during streaming
                    )
                }
            }
        }
        is Part.Reasoning -> {
            if (part.text.isNotBlank()) {
                val isStreaming = part.time?.end == null
                val startTimeMs = part.time?.start
                val reasoningDuration = part.time?.let { t ->
                    t.end?.let { end -> end - t.start }
                }
                val toolExpandedStates = LocalToolExpandedStates.current
                val onToggleToolExpanded = LocalOnToggleToolExpanded.current
                val expandReasoningDefault = LocalExpandReasoning.current
                ReasoningBlock(
                    text = part.text,
                    isExpanded = toolExpandedStates[part.id] ?: expandReasoningDefault,
                    onToggleExpand = { onToggleToolExpanded(part.id, expandReasoningDefault) },
                    durationMs = reasoningDuration,
                    isStreaming = isStreaming,
                    startTimeMs = startTimeMs
                )
            }
        }
        is Part.Tool -> {
            // todoread parts are filtered out entirely (WebUI convention)
            val toolExpandedStates = LocalToolExpandedStates.current
            val onToggleToolExpanded = LocalOnToggleToolExpanded.current
            if (part.tool == "todoread") {
                // skip
            } else if (part.tool == "todowrite") {
                TodoListCard(
                    tool = part,
                    isExpanded = toolExpandedStates[part.id] ?: true,
                    onToggleExpand = { onToggleToolExpanded(part.id, true) }
                )
            } else {
                // Use the resolver registry
                val autoExpand = LocalCollapseTools.current
                val expanded = toolExpandedStates[part.id] ?: autoExpand
                val toggleExpand = { onToggleToolExpanded(part.id, autoExpand) }

                val resolved = LocalToolCardResolver.current.resolve(
                    tool = part,
                    isExpanded = expanded,
                    onToggleExpand = toggleExpand,
                    onViewSubSession = onViewSubSession,
                    turnAgentName = turnAgentName,
                    onOpenFile = onOpenFile
                )

                if (resolved != null) {
                    resolved()
                } else {
                    // Fallback to generic ToolCallCard
                    ToolCallCard(
                        tool = part,
                        isExpanded = expanded,
                        onToggleExpand = toggleExpand
                    )
                }
            }
        }
        is Part.StepStart -> {
            // Visual separator between steps (hidden - WebUI doesn't show these)
        }
        is Part.StepFinish -> {
            // Token/cost info is aggregated at the bottom of the assistant message
        }
        is Part.Patch -> {
            val autoExpand = LocalCollapseTools.current
            val toolExpandedStates = LocalToolExpandedStates.current
            val onToggleToolExpanded = LocalOnToggleToolExpanded.current
            PatchCard(
                patch = part,
                isExpanded = toolExpandedStates[part.id] ?: autoExpand,
                onToggleExpand = { onToggleToolExpanded(part.id, autoExpand) }
            )
        }
        is Part.File -> {
            FileCard(file = part)
        }
        is Part.Permission -> {
            Text(
                text = stringResource(R.string.chat_permission_label, part.message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
        is Part.Question -> {
            Text(
                text = stringResource(R.string.chat_question_inline, part.question),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
        is Part.Abort -> {
            Text(
                text = stringResource(R.string.chat_aborted, part.reason),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        is Part.Retry -> {
            Text(
                text = stringResource(R.string.chat_retry, part.attempt, part.errorMessage),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        // Ignore less relevant parts
        is Part.Snapshot, is Part.Subtask, is Part.Compaction,
        is Part.SessionTurn, is Part.Unknown -> { /* skip */ }
        is Part.Agent -> {
            val displayName = part.name.ifBlank { "Agent" }
            val displaySource = part.source?.jsonPrimitive?.contentOrNull ?: ""
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Android,
                    contentDescription = stringResource(R.string.a11y_icon_select_provider),
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.MEDIUM)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                if (displaySource.isNotBlank()) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = displaySource,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT)
                    )
                }
            }
        }
    }
}
