package dev.minios.ocremote.domain.model

/**
 * Domain model for current tool execution progress.
 * Counterpart of data.repository.handler.ToolProgressInfo.
 */
data class ToolProgressInfo(
    val callId: String,
    val partId: String,
    val tool: String,
    val status: String,
    val progress: String? = null,
    val title: String? = null
)
