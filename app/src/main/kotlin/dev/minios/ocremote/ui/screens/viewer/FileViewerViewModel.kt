package dev.minios.ocremote.ui.screens.viewer

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.minios.ocremote.R
import dev.minios.ocremote.domain.model.ContentType
import dev.minios.ocremote.domain.model.VcsDiffMode
import dev.minios.ocremote.domain.model.VcsFileDiff
import dev.minios.ocremote.domain.repository.ToolSnapshotCache
import dev.minios.ocremote.domain.usecase.GetFileContentUseCase
import dev.minios.ocremote.domain.usecase.GetFileDiffUseCase
import dev.minios.ocremote.domain.usecase.SubmitAnnotationsUseCase
import dev.minios.ocremote.ui.navigation.routes.FileViewerNav
import dev.minios.ocremote.ui.navigation.routes.ServerRouteParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URLDecoder
import javax.inject.Inject

@HiltViewModel
class FileViewerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getFileContent: GetFileContentUseCase,
    private val getFileDiff: GetFileDiffUseCase,
    private val toolSnapshotCache: ToolSnapshotCache,
    private val submitAnnotationsUseCase: SubmitAnnotationsUseCase
) : ViewModel() {
    private val serverId = savedStateHandle.get<String>(ServerRouteParams.PARAM_SERVER_ID).orEmpty()
    private val directory = URLDecoder.decode(savedStateHandle.get<String>(FileViewerNav.PARAM_DIRECTORY).orEmpty(), "UTF-8")
    private val filePath = URLDecoder.decode(savedStateHandle.get<String>(FileViewerNav.PARAM_FILE_PATH).orEmpty(), "UTF-8")
    private val source = savedStateHandle.get<String>(FileViewerNav.PARAM_SOURCE) ?: FileViewerNav.Source.LIVE
    private val sessionId = URLDecoder.decode(savedStateHandle.get<String>(FileViewerNav.PARAM_SESSION_ID).orEmpty(), "UTF-8")
    private val toolPartIds = URLDecoder.decode(
        savedStateHandle.get<String>(FileViewerNav.PARAM_TOOL_PART_IDS).orEmpty(), "UTF-8"
    ).split(",").filter { it.isNotBlank() }
    private val _uiState = MutableStateFlow(FileViewerUiState(filePath = filePath))
    val uiState: StateFlow<FileViewerUiState> = _uiState.asStateFlow()
    private val diffParser = DiffParser()
    private var annotationManager: AnnotationManager? = null

    init {
        when (source) {
            FileViewerNav.Source.LIVE -> loadLive()
            FileViewerNav.Source.GIT_DIFF -> loadGitDiff()
            FileViewerNav.Source.TOOL_SNAPSHOT -> loadToolSnapshot()
            FileViewerNav.Source.TOOL_SNAPSHOT_DIFF -> loadToolSnapshotDiff()
        }
    }

    private fun loadLive() {
        viewModelScope.launch {
            getFileContent(serverId, directory, filePath)
                .onSuccess { c ->
                    if (c.type == ContentType.BINARY) _uiState.update { it.copy(isLoading = false, isBinary = true, mimeType = c.mimeType) }
                    else {
                        val lines = c.content.split('\n')
                        val truncated = lines.size > 5000
                        val visible = if (truncated) lines.take(5000).joinToString("\n") else c.content
                        annotationManager = AnnotationManager(visible)
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                content = visible,
                                isEmpty = visible.isBlank(),
                                isTruncated = truncated,
                                isMarkdown = isMarkdownFile(filePath),
                                renderMode = FileViewerRenderMode.SOURCE
                            )
                        }
                    }
                }
                .onFailure { e -> _uiState.update { it.copy(isLoading = false, error = R.string.workspace_error_load_failed) } }
        }
    }

    private fun loadGitDiff() {
        viewModelScope.launch {
            getFileDiff(serverId, directory, VcsDiffMode.GIT)
                .onSuccess { diffs ->
                    val target = diffs.find { it.file == filePath || it.file.endsWith(filePath) }
                    val hunks = target?.patch?.let { diffParser.parseUnifiedDiff(it) } ?: emptyList()
                    _uiState.update { it.copy(isLoading = false, mode = FileViewerMode.DIFF, diff = target, hunks = hunks,
                        currentHunkIndex = 0, isEmpty = hunks.isEmpty()) }
                }
                .onFailure { e -> _uiState.update { it.copy(isLoading = false, error = R.string.workspace_error_load_failed) } }
        }
    }

    fun nextHunk() { _uiState.update { it.copy(currentHunkIndex = (it.currentHunkIndex + 1).coerceAtMost(it.hunks.size - 1)) } }
    fun prevHunk() { _uiState.update { it.copy(currentHunkIndex = (it.currentHunkIndex - 1).coerceAtLeast(0)) } }

    // ============ Phase 3: Annotation Management ============

    fun addAnnotation(selectedText: String, startChar: Int, endChar: Int, note: String) {
        val manager = annotationManager ?: return
        if (_uiState.value.mode != FileViewerMode.SOURCE) return
        manager.add(selectedText, startChar, endChar, note)
        _uiState.update { it.copy(annotations = manager.getAll()) }
    }

    fun deleteAnnotation(id: String) {
        val manager = annotationManager ?: return
        manager.delete(id)
        _uiState.update { it.copy(annotations = manager.getAll()) }
    }

    fun updateAnnotation(id: String, note: String) {
        val manager = annotationManager ?: return
        manager.update(id, note)
        _uiState.update { it.copy(annotations = manager.getAll()) }
    }

    suspend fun submitAnnotations(overallNote: String): Result<Unit> {
        val manager = annotationManager ?: return Result.failure(IllegalStateException("No annotation manager"))
        val anns = _uiState.value.annotations
        if (anns.isEmpty()) return Result.failure(IllegalStateException("No annotations to submit"))
        val result = submitAnnotationsUseCase(serverId, sessionId, anns, overallNote, filePath, directory)
        if (result.isSuccess) {
            manager.clear()
            _uiState.update { it.copy(annotations = emptyList()) }
        }
        return result
    }

    // ============ Phase 2: Markdown render toggle ============

    fun toggleRenderMode() {
        val current = _uiState.value
        if (!current.isMarkdown || current.mode == FileViewerMode.DIFF) return
        _uiState.update {
            it.copy(
                renderMode = if (it.renderMode == FileViewerRenderMode.SOURCE) FileViewerRenderMode.RENDER_PREVIEW
                else FileViewerRenderMode.SOURCE
            )
        }
    }

    private fun isMarkdownFile(filePath: String): Boolean {
        val ext = filePath.substringAfterLast('.', "").lowercase()
        return ext == "md" || ext == "markdown" || ext == "mdx"
    }

    // ============ Phase 2 Task 9: Tool snapshot ============

    private fun loadToolSnapshot() {
        if (toolPartIds.isEmpty()) {
            _uiState.update { it.copy(isLoading = false, error = R.string.fileviewer_error_tool_snapshot_missing) }
            return
        }
        val snapshots = toolSnapshotCache.getAll(toolPartIds)
        if (snapshots.isEmpty()) {
            _uiState.update { it.copy(isLoading = false, error = R.string.fileviewer_error_tool_snapshot_missing) }
            return
        }
        val first = snapshots.first()
        val content = first.content ?: first.after ?: ""
        _uiState.update {
            it.copy(
                isLoading = false,
                content = content,
                isEmpty = content.isBlank(),
                isMarkdown = isMarkdownFile(filePath),
                renderMode = FileViewerRenderMode.SOURCE,
                isToolSnapshot = true,
                toolSnapshotContent = first.content,
                toolSnapshotBefore = first.before,
                toolSnapshotAfter = first.after
            )
        }
    }

    private fun loadToolSnapshotDiff() {
        if (toolPartIds.isEmpty()) {
            _uiState.update { it.copy(isLoading = false, error = R.string.fileviewer_error_tool_snapshot_missing) }
            return
        }
        val snapshots = toolSnapshotCache.getAll(toolPartIds)
        if (snapshots.isEmpty()) {
            _uiState.update { it.copy(isLoading = false, error = R.string.fileviewer_error_tool_snapshot_missing) }
            return
        }
        val cumulativeBefore = snapshots.first().before ?: ""
        val cumulativeAfter = snapshots.last().after ?: snapshots.last().content ?: ""
        val patch = computeUnifiedDiff(cumulativeBefore, cumulativeAfter)
        val hunks = diffParser.parseUnifiedDiff(patch)

        _uiState.update {
            it.copy(
                isLoading = false,
                mode = FileViewerMode.DIFF,
                diff = VcsFileDiff(file = filePath, patch = patch, additions = 0, deletions = 0, status = null),
                hunks = hunks,
                currentHunkIndex = 0,
                isEmpty = hunks.isEmpty(),
                isToolSnapshot = true,
                toolSnapshotBefore = cumulativeBefore,
                toolSnapshotAfter = cumulativeAfter
            )
        }
    }

    /**
     * Simple line-level unified diff via common prefix/suffix.
     * Independent from chat module's internal computeSimpleDiff to avoid cross-module coupling.
     */
    private fun computeUnifiedDiff(before: String, after: String): String {
        val beforeLines = before.lines()
        val afterLines = after.lines()
        if (beforeLines == afterLines) return ""
        val prefixLen = (0 until minOf(beforeLines.size, afterLines.size))
            .takeWhile { beforeLines[it] == afterLines[it] }
            .size
        val maxSuffix = minOf(beforeLines.size - prefixLen, afterLines.size - prefixLen)
        val suffixLen = (0 until maxSuffix)
            .takeWhile { beforeLines[beforeLines.size - 1 - it] == afterLines[afterLines.size - 1 - it] }
            .size
        return buildString {
            append("@@ -1,${beforeLines.size} +1,${afterLines.size} @@\n")
            beforeLines.take(prefixLen).forEach { append(" ").append(it).append("\n") }
            beforeLines.drop(prefixLen).dropLast(suffixLen).forEach { append("-").append(it).append("\n") }
            afterLines.drop(prefixLen).dropLast(suffixLen).forEach { append("+").append(it).append("\n") }
            beforeLines.takeLast(suffixLen).forEach { append(" ").append(it).append("\n") }
        }
    }

    fun cleanupToolSnapshots() {
        if (toolPartIds.isNotEmpty()) toolSnapshotCache.clear(toolPartIds)
    }

    override fun onCleared() {
        super.onCleared()
        cleanupToolSnapshots()
    }
}
