package dev.minios.ocremote.ui.screens.chat.tools

/**
 * Request to view a tool's file snapshot in the FileViewer (spec §5.1-5.4).
 *
 * Created by Read/Write/Edit tool cards when the user taps ↗.
 * [source] determines whether the viewer shows content (TOOL_SNAPSHOT)
 * or cumulative diff (TOOL_SNAPSHOT_DIFF).
 */
data class ViewToolRequest(
    val filePath: String,
    val source: String,
    val toolPartIds: List<String> = emptyList()
)
