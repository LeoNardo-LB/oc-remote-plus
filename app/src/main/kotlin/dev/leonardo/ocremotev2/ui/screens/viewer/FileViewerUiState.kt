package dev.leonardo.ocremotev2.ui.screens.viewer

import dev.leonardo.ocremotev2.domain.model.VcsFileDiff

enum class FileViewerMode { SOURCE, DIFF }

enum class DiffHunkType { ADDED, REMOVED, MODIFIED }

data class DiffHunk(
    val startLine: Int,
    val patchStartLineIndex: Int,
    val type: DiffHunkType,
    val rawPatch: String
)

data class FileViewerUiState(
    val filePath: String = "",
    val mode: FileViewerMode = FileViewerMode.SOURCE,
    val isLoading: Boolean = true,
    val content: String = "",
    val isBinary: Boolean = false,
    val mimeType: String? = null,
    val error: Int? = null,
    val isEmpty: Boolean = false,
    val isTruncated: Boolean = false,
    val diff: VcsFileDiff? = null,
    val hunks: List<DiffHunk> = emptyList(),
    val currentHunkIndex: Int = 0
)
