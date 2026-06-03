package dev.minios.ocremote.ui.screens.chat.tools.cards

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.minios.ocremote.R
import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.domain.model.ToolState
import dev.minios.ocremote.ui.screens.chat.tools.DiffView
import dev.minios.ocremote.ui.screens.chat.tools.extractFileName
import dev.minios.ocremote.ui.screens.chat.tools.extractToolInput
import dev.minios.ocremote.ui.screens.chat.tools.extractToolOutput
import dev.minios.ocremote.ui.screens.chat.util.isAmoledTheme
import dev.minios.ocremote.ui.theme.AlphaTokens
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * ApplyPatch tool card — shows file path + diff preview.
 * Uses the existing [DiffView] component for rendering.
 */
@Composable
internal fun ApplyPatchToolCard(
    tool: Part.Tool,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit
) {
    val isAmoled = isAmoledTheme()
    val input = extractToolInput(tool)
    val output = extractToolOutput(tool)
    val isRunning = tool.state is ToolState.Running

    // Extract diff content from metadata or input
    val diffContent = remember(tool.state) {
        val completed = tool.state as? ToolState.Completed
        val meta = completed?.metadata
        meta?.get("patch")?.jsonPrimitive?.contentOrNull
            ?: input["patch"]?.jsonPrimitive?.contentOrNull
            ?: output
    }

    val filePath = input["filePath"]?.jsonPrimitive?.contentOrNull
        ?: input["path"]?.jsonPrimitive?.contentOrNull ?: ""

    val title = if (filePath.isNotBlank()) {
        "${stringResource(R.string.tool_apply_patch)} · ${extractFileName(filePath)}"
    } else {
        stringResource(R.string.tool_apply_patch)
    }

    ToolCardScaffold(
        icon = Icons.Default.Build,
        iconTint = MaterialTheme.colorScheme.primary,
        title = title,
        copyText = diffContent,
        isExpanded = isExpanded,
        isRunning = isRunning,
        hasContent = diffContent.isNotBlank(),
        isAmoled = isAmoled,
        onToggleExpand = onToggleExpand
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (diffContent.isNotBlank()) {
                DiffView(
                    before = "",
                    after = diffContent
                )
            }
        }
    }
}
