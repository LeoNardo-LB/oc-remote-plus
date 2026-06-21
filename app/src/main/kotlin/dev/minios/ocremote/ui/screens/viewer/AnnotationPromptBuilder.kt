package dev.minios.ocremote.ui.screens.viewer

import dev.minios.ocremote.domain.model.Annotation

/**
 * Builds structured prompt text for submitting annotations (spec §7.5).
 *
 * Format:
 *   修改意见：<overallNote or "无">
 *
 *   文件名: <absolutePath>
 *   1. <line>:<col> - <line>:<col> <note>
 *   2. ...
 *
 * Numbering follows creation order ([Annotation.index]), not file position.
 */
object AnnotationPromptBuilder {

    fun build(
        annotations: List<Annotation>,
        overallNote: String,
        filePath: String,
        directory: String
    ): String {
        require(annotations.isNotEmpty()) { "Cannot submit empty annotation list" }

        val resolvedPath = resolvePath(filePath, directory)
        val noteText = overallNote.ifBlank { "无" }

        val sb = StringBuilder()
        sb.append("修改意见：").append(noteText).append("\n\n")
        sb.append("文件名: ").append(resolvedPath).append("\n")
        annotations.sortedBy { it.index }.forEach { ann ->
            sb.append("${ann.index + 1}. ")
              .append("${ann.startLine}:${ann.startCol} - ${ann.endLine}:${ann.endCol} ")
              .append(ann.note).append("\n")
        }
        return sb.toString().trimEnd()
    }

    /**
     * Resolve [filePath] against [directory].
     * - Absolute Unix path (leading '/') and Windows drive letter (e.g. "D:/") used as-is.
     * - Relative path joined with '/' separator (forward-slash, cross-platform safe).
     */
    private fun resolvePath(filePath: String, directory: String): String {
        if (filePath.isBlank()) return directory
        if (filePath.startsWith("/")) return filePath
        if (filePath.length >= 2 && filePath[1] == ':') return filePath
        if (directory.isBlank()) return filePath
        val sep = if (directory.endsWith("/")) "" else "/"
        return "$directory$sep$filePath"
    }
}
