package dev.minios.ocremote.ui.screens.chat.tools.cards

import dev.minios.ocremote.ui.screens.chat.tools.extractFileName
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.minios.ocremote.R
import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.domain.model.ToolState
import dev.minios.ocremote.ui.components.AmoledDefaultBorder
import dev.minios.ocremote.ui.screens.chat.tools.extractToolInput
import dev.minios.ocremote.ui.screens.chat.util.codeHorizontalScroll
import dev.minios.ocremote.ui.screens.chat.util.halfScreenHeight
import dev.minios.ocremote.ui.screens.chat.util.isAmoledTheme
import dev.minios.ocremote.ui.screens.chat.util.toolOutputContainerColor
import dev.minios.ocremote.ui.theme.CodeTypography
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import dev.minios.ocremote.ui.theme.ShapeTokens
import dev.minios.ocremote.ui.theme.AlphaTokens

/**
 * Write tool card — shows file path + code content.
 * Like WebUI: trigger = "Write" + filename, content = code view.
 */
@Composable
internal fun WriteToolCard(
    tool: Part.Tool,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit
) {
    val isAmoled = isAmoledTheme()
    val input = extractToolInput(tool)
    val filePath = input["filePath"]?.jsonPrimitive?.contentOrNull
        ?: input["path"]?.jsonPrimitive?.contentOrNull ?: ""
    val shortPath = extractFileName(filePath)
    val content = input["content"]?.jsonPrimitive?.contentOrNull ?: ""

    val isRunning = tool.state is ToolState.Running
    val isError = tool.state is ToolState.Error
    val hasContent = content.isNotBlank()
    val title = if (shortPath.isNotBlank()) {
        "${stringResource(R.string.chat_write_label)} · $shortPath"
    } else {
        stringResource(R.string.chat_write_label)
    }
    val copyText = if (filePath.isNotBlank()) "Write: $filePath" else "Write"

    ToolCardScaffold(
        icon = if (isError) Icons.Default.Error else Icons.Default.EditNote,
        iconTint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        title = title,
        copyText = copyText,
        isExpanded = isExpanded,
        isRunning = isRunning,
        hasContent = hasContent,
        isAmoled = isAmoled,
        onToggleExpand = onToggleExpand
    ) {
        val halfScreenHeight = halfScreenHeight()
        val scrollState = rememberScrollState()
        Surface(
            shape = ShapeTokens.extraSmall,
            color = toolOutputContainerColor(),
            border = if (isAmoled) AmoledDefaultBorder else null,            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 3.dp)
                .heightIn(max = halfScreenHeight)
                .verticalScroll(scrollState)
        ) {
            SelectionContainer {
                Text(
                    text = content.take(5000),
                    style = CodeTypography.copy(color = if (isAmoled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.AMOLED) else MaterialTheme.colorScheme.onSecondaryContainer),
                    modifier = Modifier
                        .padding(4.dp)
                        .codeHorizontalScroll()
                )
            }
        }
    }
}
