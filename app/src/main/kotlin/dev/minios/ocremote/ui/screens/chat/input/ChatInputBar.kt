package dev.minios.ocremote.ui.screens.chat.input

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.minios.ocremote.R
import dev.minios.ocremote.data.dto.response.AgentInfo
import dev.minios.ocremote.data.dto.response.CommandInfo
import dev.minios.ocremote.data.dto.request.PromptPart
import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.domain.model.ToolState
import dev.minios.ocremote.ui.components.ProviderIcon
import dev.minios.ocremote.ui.screens.chat.ChatMessage
import dev.minios.ocremote.ui.screens.chat.components.BreathingCircleIndicator
import dev.minios.ocremote.ui.screens.chat.components.PulsingDotsIndicator
import dev.minios.ocremote.ui.screens.chat.dialog.ImagePreviewDialog
import dev.minios.ocremote.ui.screens.chat.util.ImageAttachment
import dev.minios.ocremote.ui.screens.chat.util.agentColor
import dev.minios.ocremote.ui.screens.chat.util.decodeDataUrlBytes
import dev.minios.ocremote.ui.screens.chat.util.imageThumbnailModel
import dev.minios.ocremote.ui.screens.chat.util.isAmoledTheme
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Slash command definition for the suggestion popup.
 * @param name Command name without the "/" prefix
 * @param description Human-readable description
 * @param type "server" commands are sent via API, "client" commands trigger local actions
 */
internal data class SlashCommand(
    val name: String,
    val description: String?,
    val type: String // "server" or "client"
)

internal enum class ChatInputMode {
    NORMAL,
    SHELL
}

/** Client-side slash commands that mirror the original opencode TUI. */
@Composable
internal fun clientCommands(): List<SlashCommand> {
    return listOf(
        SlashCommand("new", stringResource(R.string.cmd_new), "client"),
        SlashCommand("compact", stringResource(R.string.cmd_compact), "client"),
        SlashCommand("fork", stringResource(R.string.cmd_fork), "client"),
        SlashCommand("share", stringResource(R.string.cmd_share), "client"),
        SlashCommand("unshare", stringResource(R.string.cmd_unshare), "client"),
        SlashCommand("undo", stringResource(R.string.cmd_undo), "client"),
        SlashCommand("redo", stringResource(R.string.cmd_redo), "client"),
        SlashCommand("rename", stringResource(R.string.cmd_rename), "client"),
        SlashCommand("shell", stringResource(R.string.cmd_shell_mode), "client"),
    )
}

// BreathingCircleIndicator moved to components/BreathingCircleIndicator.kt

/**
 * Visual transformation that highlights confirmed @file mentions in the input field.
 * Confirmed paths get a colored background + bold style.
 * Unconfirmed @query mentions (still searching) and plain text
 * remain unstyled so the user can see they haven't been selected yet.
 */
private class FileMentionVisualTransformation(
    private val confirmedFilePaths: Set<String>,
    private val highlightColor: Color,
    private val bgColor: Color
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (confirmedFilePaths.isEmpty()) {
            return TransformedText(text, OffsetMapping.Identity)
        }
        val raw = text.text
        val annotated = buildAnnotatedString {
            append(raw)
            // For each confirmed path, find all occurrences of @path in the text
            for (path in confirmedFilePaths) {
                val needle = "@$path"
                var searchFrom = 0
                while (true) {
                    val idx = raw.indexOf(needle, searchFrom)
                    if (idx == -1) break
                    // Ensure the match is not part of a longer token:
                    // next char after needle should be whitespace, end-of-string, or another @
                    val endIdx = idx + needle.length
                    if (endIdx < raw.length) {
                        val next = raw[endIdx]
                        if (!next.isWhitespace() && next != '@') {
                            searchFrom = endIdx
                            continue
                        }
                    }
                    addStyle(
                        SpanStyle(
                            color = highlightColor,
                            background = bgColor,
                            fontWeight = FontWeight.SemiBold
                        ),
                        start = idx,
                        end = endIdx
                    )
                    searchFrom = endIdx
                }
            }
        }
        return TransformedText(annotated, OffsetMapping.Identity)
    }
}

/**
 * Splits raw input text into a list of [PromptPart] objects.
 * Text around confirmed @file mentions becomes type="text" parts,
 * and each @file mention becomes a type="file" part with a file:// URL.
 */
internal fun buildPromptParts(
    text: String,
    confirmedPaths: Set<String>,
    sessionDirectory: String?
): List<PromptPart> {
    if (confirmedPaths.isEmpty()) {
        val trimmed = text.trim()
        return if (trimmed.isEmpty()) emptyList()
        else listOf(PromptPart(type = "text", text = trimmed))
    }

    // Find all confirmed @path mentions with their positions
    data class Mention(val start: Int, val end: Int, val path: String)
    val mentions = mutableListOf<Mention>()

    for (path in confirmedPaths) {
        val needle = "@$path"
        var searchFrom = 0
        while (true) {
            val idx = text.indexOf(needle, searchFrom)
            if (idx == -1) break
            val endIdx = idx + needle.length
            // Boundary check: next char must be whitespace, end-of-string, or @
            if (endIdx < text.length) {
                val next = text[endIdx]
                if (!next.isWhitespace() && next != '@') {
                    searchFrom = endIdx
                    continue
                }
            }
            mentions.add(Mention(idx, endIdx, path))
            searchFrom = endIdx
        }
    }

    if (mentions.isEmpty()) {
        val trimmed = text.trim()
        return if (trimmed.isEmpty()) emptyList()
        else listOf(PromptPart(type = "text", text = trimmed))
    }

    // Sort by position
    mentions.sortBy { it.start }

    val parts = mutableListOf<PromptPart>()
    var cursor = 0

    for (mention in mentions) {
        // Add text before this mention
        if (mention.start > cursor) {
            val segment = text.substring(cursor, mention.start).trim()
            if (segment.isNotEmpty()) {
                parts.add(PromptPart(type = "text", text = segment))
            }
        }
        // Add file part
        val isDir = mention.path.endsWith("/")
        val absPath = if (sessionDirectory != null) "$sessionDirectory/${mention.path}" else mention.path
        val displayName = mention.path.trimEnd('/').substringAfterLast('/')
        parts.add(
            PromptPart(
                type = "file",
                path = mention.path,
                mime = if (isDir) "application/x-directory" else "text/plain",
                url = "file:///$absPath",
                filename = displayName
            )
        )
        cursor = mention.end
    }

    // Trailing text
    if (cursor < text.length) {
        val segment = text.substring(cursor).trim()
        if (segment.isNotEmpty()) {
            parts.add(PromptPart(type = "text", text = segment))
        }
    }

    return parts
}

/** Rotating placeholder hints for the input bar, similar to the WebUI prompt input. */
private val placeholderHintResIds = listOf(
    R.string.chat_hint_ask,
    R.string.chat_hint_fix,
    R.string.chat_hint_refactor,
    R.string.chat_hint_tests,
    R.string.chat_hint_explain,
    R.string.chat_hint_help,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatInputBar(
    textFieldValue: TextFieldValue,
    onTextFieldValueChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    isSending: Boolean,
    isBusy: Boolean = false,
    messages: List<ChatMessage> = emptyList(),
    attachments: List<ImageAttachment> = emptyList(),
    onAttach: () -> Unit = {},
    onRemoveAttachment: (Int) -> Unit = {},
    onSaveAttachment: (bytes: ByteArray, mime: String, filename: String?) -> Unit = { _, _, _ -> },
    modelLabel: String = "",
    selectedProviderId: String? = null,
    onModelClick: () -> Unit = {},
    agents: List<AgentInfo> = emptyList(),
    selectedAgent: String = "build",
    onAgentSelect: (String) -> Unit = {},
    variantNames: List<String> = emptyList(),
    selectedVariant: String? = null,
    onCycleVariant: () -> Unit = {},
    commands: List<CommandInfo> = emptyList(),
    fileSearchResults: List<String> = emptyList(),
    confirmedFilePaths: Set<String> = emptySet(),
    onFileSelected: (String) -> Unit = {},
    onSlashCommand: (SlashCommand) -> Unit = {},
    inputMode: ChatInputMode = ChatInputMode.NORMAL,
    onInputModeChange: (ChatInputMode) -> Unit = {},
    contextWindow: Int = 0,
    lastContextTokens: Int = 0,
    onStop: () -> Unit = {}
) {
    val isAmoled = isAmoledTheme()
    val isShellMode = inputMode == ChatInputMode.SHELL
    // Rotate placeholder hint every 4 seconds
    val hintIndex = remember { mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(4000)
            hintIndex.intValue = (hintIndex.intValue + 1) % placeholderHintResIds.size
        }
    }
    val placeholder = if (isShellMode) {
        stringResource(R.string.chat_shell_placeholder)
    } else {
        stringResource(placeholderHintResIds[hintIndex.intValue])
    }

    val text = textFieldValue.text
    val canSend = (text.isNotBlank() || attachments.isNotEmpty()) && !isSending && (!isShellMode || !isBusy)
    var previewAttachmentIndex by remember { mutableStateOf(-1) }

    // Build merged slash commands: client commands + server commands + skills (deduplicated)
    val clientCmds = clientCommands()
    val allCommands = remember(commands, clientCmds) {
        val clientNames = clientCmds.map { it.name }.toSet()
        val serverSlash = commands
            .filter { it.name !in clientNames }
            .map { SlashCommand(it.name, it.description, it.source ?: "server") }
        clientCmds + serverSlash
    }

    // Slash command suggestions
    val showSlashSuggestions = !isShellMode && text.startsWith("/") && !text.contains(" ")
    val slashQuery = if (showSlashSuggestions) text.removePrefix("/").lowercase() else ""
    val filteredCommands = if (showSlashSuggestions) {
        allCommands.filter { cmd ->
            slashQuery.isEmpty() || cmd.name.lowercase().contains(slashQuery)
        }
    } else emptyList()

    Column(
        modifier = Modifier
            .fillMaxWidth()
    ) {
        // Thin divider
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            thickness = 0.5.dp
        )

        // Slash command suggestions popup (scrollable, max 40% screen height)
        AnimatedVisibility(
            visible = showSlashSuggestions && filteredCommands.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            val configuration = LocalConfiguration.current
            val maxHeight = (configuration.screenHeightDp * 0.4f).dp

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .background(if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(vertical = 4.dp)
            ) {
                items(filteredCommands, key = { it.name }) { cmd ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (cmd.type == "skill") {
                                    // Put skill command into input field for user to add additional input
                                    val skillText = "/${cmd.name} "
                                    onTextFieldValueChange(TextFieldValue(skillText, TextRange(skillText.length)))
                                } else {
                                    onTextFieldValueChange(TextFieldValue(""))
                                    onSlashCommand(cmd)
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "/${cmd.name}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (cmd.type == "skill") MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                            fontFamily = FontFamily.Monospace
                        )
                        if (cmd.type == "skill") {
                            Text(
                                text = "skill",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f),
                                modifier = Modifier.padding(end = 4.dp)
                            )
                        }
                        if (cmd.description != null) {
                            Text(
                                text = cmd.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        // @ file mention suggestions popup
        AnimatedVisibility(
            visible = !isShellMode && fileSearchResults.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            val configuration = LocalConfiguration.current
            val maxHeight = (configuration.screenHeightDp * 0.4f).dp

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .background(if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(vertical = 4.dp)
            ) {
                items(
                    fileSearchResults.take(10),
                    key = { it }
                ) { path ->
                    val isDir = path.endsWith("/")
                    // Split into directory part + filename for display
                    val displayPath = if (isDir) path.trimEnd('/') else path
                    val lastSlash = displayPath.lastIndexOf('/')
                    val dirPart = if (lastSlash >= 0) displayPath.substring(0, lastSlash + 1) else ""
                    val namePart = if (lastSlash >= 0) displayPath.substring(lastSlash + 1) else displayPath

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onFileSelected(path) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (isDir) Icons.Default.Folder else Icons.Default.Description,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (isDir)
                                MaterialTheme.colorScheme.tertiary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Text(
                            text = buildAnnotatedString {
                                if (dirPart.isNotEmpty()) {
                                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))) {
                                        append(dirPart)
                                    }
                                }
                                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface)) {
                                    append(namePart)
                                }
                                if (isDir) {
                                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))) {
                                        append("/")
                                    }
                                }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // Status row: working status (left) + context usage (right)
        val showContext = contextWindow > 0 && lastContextTokens > 0
        if (isBusy || showContext) {
            val lastRunningTool = if (isBusy) {
                messages.firstOrNull()?.parts
                    ?.filterIsInstance<Part.Tool>()
                    ?.lastOrNull { it.state is ToolState.Running }
            } else null

            val statusText = if (isBusy) {
                if (lastRunningTool != null) {
                    val title = (lastRunningTool.state as ToolState.Running).title
                    when (lastRunningTool.tool) {
                        "read" -> title ?: stringResource(R.string.chat_tool_reading_file)
                        "write" -> title ?: stringResource(R.string.chat_tool_writing_file)
                        "edit" -> title ?: stringResource(R.string.chat_tool_editing_file)
                        "bash" -> title ?: stringResource(R.string.chat_tool_running_command)
                        "glob", "list" -> title ?: stringResource(R.string.chat_tool_searching_files)
                        "grep" -> title ?: stringResource(R.string.chat_tool_searching_code)
                        "webfetch" -> title ?: stringResource(R.string.chat_tool_fetching_url)
                        "task" -> title ?: stringResource(R.string.chat_tool_running_subagent)
                        "todowrite" -> title ?: stringResource(R.string.chat_tool_updating_tasks)
                        else -> title ?: stringResource(R.string.chat_tool_running_tool, lastRunningTool.tool)
                    }
                } else {
                    stringResource(R.string.chat_tool_thinking)
                }
            } else null

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 2.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: working status
                if (isBusy && statusText != null) {
                    Row(
                        modifier = Modifier.weight(1f, fill = false),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PulsingDotsIndicator(
                            dotSize = 4.dp,
                            dotSpacing = 3.dp,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.width(0.dp))
                }
                // Right: context usage (percentage)
                if (showContext) {
                    val percentage = Math.round(lastContextTokens.toDouble() / contextWindow * 100).toInt()
                    val contextColor = when {
                        percentage >= 90 -> MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                        percentage >= 70 -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    }
                    Text(
                        text = stringResource(
                            R.string.chat_context_format,
                            percentage
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = contextColor
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // Agent + Model + Variant + Attach selector row — small, subtle
            if ((modelLabel.isNotEmpty() || agents.size > 1)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Scrollable area for agent/model/variant so paperclip always stays visible
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Agent selector — single button, tap to cycle
                        // Fixed width: all agent names rendered invisible to reserve max width
                        if (agents.size > 1) {
                            val agentColor = agentColor(selectedAgent, agents)
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(agentColor.copy(alpha = 0.18f))
                                    .clickable {
                                        val currentIndex = agents.indexOfFirst { it.name == selectedAgent }
                                        val nextIndex = (currentIndex + 1) % agents.size
                                        onAgentSelect(agents[nextIndex].name)
                                    }
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                            ) {
                                // Invisible ghost texts for all agent names — fixes width to the widest
                                agents.forEach { agent ->
                                    Text(
                                        text = agent.name.replaceFirstChar { it.uppercase() },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.Transparent
                                    )
                                }
                                // Visible label with accent color
                                Text(
                                    text = selectedAgent.replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = agentColor
                                )
                            }
                        }

                        // Model selector — SECOND
                        if (modelLabel.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { onModelClick() }
                                    .padding(horizontal = 3.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                if (selectedProviderId != null) {
                                    ProviderIcon(
                                        providerId = selectedProviderId,
                                        size = 13.dp,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                                Text(
                                    text = modelLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                                Icon(
                                    Icons.Default.UnfoldMore,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                        }

                        // Variant cycle button (thinking effort) — THIRD
                        if (variantNames.isNotEmpty()) {
                            Text(
                                text = selectedVariant?.replaceFirstChar { it.uppercase() } ?: stringResource(R.string.chat_default_variant),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selectedVariant != null) {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                },
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { onCycleVariant() }
                                    .padding(horizontal = 3.dp, vertical = 3.dp)
                            )
                        }

                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        // Attach button (paperclip) — always visible, pinned right, aligned with Send button
                        IconButton(
                            onClick = onAttach,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.AttachFile,
                                contentDescription = stringResource(R.string.chat_attach),
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }

            // Image attachment thumbnails
            if (attachments.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(attachments.size) { index ->
                        val attachment = attachments[index]
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(10.dp))
                        ) {
                            AsyncImage(
                                model = imageThumbnailModel(attachment),
                                contentDescription = attachment.filename,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clickable { previewAttachmentIndex = index },
                                contentScale = ContentScale.Crop
                            )
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(2.dp)
                                    .size(18.dp)
                                    .clickable { onRemoveAttachment(index) },
                                shape = RoundedCornerShape(9.dp),
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.9f)
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = stringResource(R.string.chat_remove),
                                        modifier = Modifier.size(12.dp),
                                        tint = MaterialTheme.colorScheme.onError
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (previewAttachmentIndex >= 0 && previewAttachmentIndex < attachments.size) {
                val attachment = attachments[previewAttachmentIndex]
                val imageBytes = remember(attachment.dataUrl) { decodeDataUrlBytes(attachment.dataUrl) }
                val bitmap = remember(imageBytes) {
                    imageBytes?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                }

                if (bitmap != null) {
                    ImagePreviewDialog(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = attachment.filename,
                        onDismiss = { previewAttachmentIndex = -1 },
                        onSave = {
                            if (imageBytes != null) {
                                onSaveAttachment(imageBytes, attachment.mime, attachment.filename)
                            }
                        },
                    )
                }
            }

            AnimatedVisibility(
                visible = isShellMode,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isAmoled) {
                                Color.Black
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            }
                        )
                        .then(
                            if (isAmoled) {
                                Modifier.border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                    shape = RoundedCornerShape(10.dp),
                                )
                            } else {
                                Modifier
                            }
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.chat_shell_mode_hold_send_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Input row
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Text field — minimal style, no heavy outline
                val mentionHighlightColor = MaterialTheme.colorScheme.primary
                val mentionBgColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                val visualTransformation = remember(confirmedFilePaths, mentionHighlightColor, mentionBgColor) {
                    if (isShellMode) {
                        VisualTransformation.None
                    } else {
                        FileMentionVisualTransformation(confirmedFilePaths, mentionHighlightColor, mentionBgColor)
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(22.dp))
                        .background(
                            if (isAmoled) {
                                Color.Black
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            }
                        )
                        .then(
                            when {
                                isShellMode -> Modifier.border(
                                    width = if (isAmoled) 1.5.dp else 1.dp,
                                    color = if (isAmoled) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                                    } else {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                                    },
                                    shape = RoundedCornerShape(22.dp)
                                )
                                isAmoled -> Modifier.border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                                    shape = RoundedCornerShape(22.dp)
                                )
                                else -> Modifier
                            }
                        )
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    BasicTextField(
                        value = textFieldValue,
                        onValueChange = onTextFieldValueChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 24.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = if (isShellMode) FontFamily.Monospace else FontFamily.Default
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                        maxLines = 5,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        visualTransformation = visualTransformation,
                        decorationBox = { innerTextField ->
                            if (text.isEmpty()) {
                                Text(
                                    text = placeholder,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                            innerTextField()
                        }
                    )
                }

                // Send / Stop button — tap to send or stop, long-press toggles shell mode
                val showStop = isBusy && text.isBlank()
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(
                            if (showStop) {
                                MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                            } else if (isShellMode && !isSending) {
                                if (isAmoled) {
                                    Color.Black
                                } else {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                }
                            } else if (isAmoled) {
                                Color.Black
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            }
                        )
                        .then(
                            if (showStop) {
                                Modifier.border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                                    shape = RoundedCornerShape(22.dp),
                                )
                            } else if (isShellMode && !isSending) {
                                Modifier.border(
                                    width = if (isAmoled) 1.2.dp else 1.dp,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = if (isAmoled) 0.88f else 0.75f),
                                    shape = RoundedCornerShape(22.dp),
                                )
                            } else if (isAmoled) {
                                Modifier.border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(22.dp),
                                )
                            } else {
                                Modifier.border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                    shape = RoundedCornerShape(22.dp),
                                )
                            }
                        )
                        .combinedClickable(
                            onClick = {
                                if (showStop) {
                                    onStop()
                                } else if (canSend) {
                                    onSend()
                                }
                            },
                            onLongClick = {
                                if (!showStop) {
                                    onInputModeChange(
                                        if (isShellMode) ChatInputMode.NORMAL else ChatInputMode.SHELL
                                    )
                                }
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (showStop) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = stringResource(R.string.chat_stop),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    } else if (isSending) {
                        BreathingCircleIndicator(
                            size = 20.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = if (isShellMode) {
                                stringResource(R.string.chat_send_shell)
                            } else {
                                stringResource(R.string.chat_send)
                            },
                            modifier = Modifier.size(20.dp),
                            tint = if (canSend) {
                                MaterialTheme.colorScheme.primary
                            } else if (isShellMode && isAmoled && !isSending) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                            }
                        )

                    }
                }
            }
        }
    }
}
