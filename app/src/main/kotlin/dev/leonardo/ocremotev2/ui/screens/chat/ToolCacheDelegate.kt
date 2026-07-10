package dev.leonardo.ocremotev2.ui.screens.chat

import dev.leonardo.ocremotev2.domain.model.Part
import dev.leonardo.ocremotev2.domain.model.ToolState
import dev.leonardo.ocremotev2.domain.repository.ToolSnapshotCache
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * Extracts and caches file snapshots from Tool parts for the file viewer.
 *
 * Handles read/write/edit tool output parsing, including stripping Read tool
 * XML wrappers and embedded line-number prefixes.
 */
class ToolCacheDelegate @Inject constructor(
    private val toolSnapshotCache: ToolSnapshotCache,
) {
    fun cacheToolPart(part: Part.Tool) {
        val state = part.state
        val input = when (state) {
            is ToolState.Completed -> state.input
            is ToolState.Running -> state.input
            is ToolState.Pending -> state.input
            is ToolState.Error -> state.input
        }
        val filePath = input["filePath"]?.jsonPrimitive?.contentOrNull
            ?: input["path"]?.jsonPrimitive?.contentOrNull ?: return
        val metadata = (state as? ToolState.Completed)?.metadata
        val filediff = metadata?.get("filediff") as? JsonObject
        val before = filediff?.get("before")?.jsonPrimitive?.contentOrNull
            ?: input["oldString"]?.jsonPrimitive?.contentOrNull
        val after = filediff?.get("after")?.jsonPrimitive?.contentOrNull
            ?: input["newString"]?.jsonPrimitive?.contentOrNull
        val content = when (part.tool.lowercase()) {
            "read" -> {
                val raw = (state as? ToolState.Completed)?.output ?: ""
                cleanReadToolOutput(raw)
            }
            "write" -> input["content"]?.jsonPrimitive?.contentOrNull
            "edit" -> after
            else -> null
        }
        toolSnapshotCache.put(
            part.id,
            ToolSnapshotCache.Snapshot(
                filePath = filePath, content = content, before = before, after = after, toolName = part.tool
            )
        )
    }

    /**
     * Strip Read tool output wrappers (<path>, <content> tags) and embedded
     * line-number prefixes ("291: text" → "text") to avoid double line numbers
     * in the file viewer (which adds its own gutter).
     */
    private fun cleanReadToolOutput(raw: String): String {
        var result = raw
        val contentMatch = Regex("<content>(?:\\r?\\n)?(.*?)(?:\\r?\\n)?</content>", RegexOption.DOT_MATCHES_ALL).find(result)
        result = if (contentMatch != null) {
            contentMatch.groupValues[1]
        } else {
            result.lines().filter { line ->
                !line.startsWith("<path>") && !line.startsWith("</path>") &&
                !line.startsWith("<type>") && !line.startsWith("</type>") &&
                !line.startsWith("<content>") && !line.startsWith("</content>")
            }.joinToString("\n")
        }
        result = result.replace(Regex("(?m)^\\s*\\d+:\\s"), "")
        return result.trim()
    }
}
