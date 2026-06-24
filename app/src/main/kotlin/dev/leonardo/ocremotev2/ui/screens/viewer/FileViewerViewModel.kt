package dev.leonardo.ocremotev2.ui.screens.viewer

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.leonardo.ocremotev2.R
import dev.leonardo.ocremotev2.domain.model.Annotation
import dev.leonardo.ocremotev2.domain.model.ContentType
import dev.leonardo.ocremotev2.domain.model.VcsDiffMode
import dev.leonardo.ocremotev2.domain.repository.ToolSnapshotCache
import dev.leonardo.ocremotev2.domain.usecase.GetFileContentUseCase
import dev.leonardo.ocremotev2.domain.usecase.GetFileDiffUseCase
import dev.leonardo.ocremotev2.domain.usecase.SubmitAnnotationsUseCase
import dev.leonardo.ocremotev2.ui.navigation.routes.FileViewerNav
import dev.leonardo.ocremotev2.ui.navigation.routes.ServerRouteParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URLDecoder
import javax.inject.Inject

@HiltViewModel
class FileViewerViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
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
    private val _uiState = MutableStateFlow(FileViewerUiState(filePath = filePath, directory = directory))
    val uiState: StateFlow<FileViewerUiState> = _uiState.asStateFlow()
    private val diffParser = DiffParser()
    private var annotationManager: AnnotationManager? = null

    // Phase 4: pagination — cache full content for loadMore slicing
    private var fullContentCache: String = ""

    private companion object {
        const val INITIAL_PAGE_SIZE = 500
        const val PAGE_SIZE = 500
        const val EXTREMELY_LARGE_THRESHOLD = 100_000
        const val EXTREMELY_LARGE_INITIAL = 10_000
    }

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
                        fullContentCache = c.content
                        val totalLines = if (c.content.isEmpty()) 0
                                         else c.content.count { it == '\n' } + if (c.content.endsWith('\n')) 0 else 1
                        val extremelyLarge = totalLines > EXTREMELY_LARGE_THRESHOLD
                        val initialVisible = if (extremelyLarge) EXTREMELY_LARGE_INITIAL
                                             else minOf(totalLines, INITIAL_PAGE_SIZE)
                        val visible = takeFirstLines(c.content, initialVisible)
                        // AnnotationManager uses full content so line numbers stay correct after loadMore
                        annotationManager = AnnotationManager(fullContentCache)
                        // Phase 4: restore annotations from SavedStateHandle (rotation survival)
                        val restored = restoreAnnotationsFromHandle()
                        if (restored.isNotEmpty()) annotationManager?.restore(restored)
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                mode = FileViewerMode.SOURCE,
                                content = visible,
                                isEmpty = visible.isBlank(),
                                isMarkdown = isMarkdownFile(filePath),
                                renderMode = FileViewerRenderMode.SOURCE,
                                totalLineCount = totalLines,
                                visibleLineCount = initialVisible,
                                isFullyLoaded = initialVisible >= totalLines,
                                isExtremelyLarge = extremelyLarge,
                                annotations = annotationManager?.getAll() ?: emptyList()
                            )
                        }
                    }
                }
                .onFailure { e -> _uiState.update { it.copy(isLoading = false, error = R.string.workspace_error_load_failed) } }
        }
    }

    /**
     * Phase 4: Append PAGE_SIZE more lines to the visible content. No-op when fully loaded.
     * Slices from [fullContentCache] — cheap, no network round-trip.
     */
    fun loadMoreLines() {
        val current = _uiState.value
        if (current.isFullyLoaded) return
        val newSize = (current.visibleLineCount + PAGE_SIZE).coerceAtMost(current.totalLineCount)
        val newContent = takeFirstLines(fullContentCache, newSize)
        _uiState.update {
            it.copy(
                content = newContent,
                visibleLineCount = newSize,
                isFullyLoaded = newSize >= it.totalLineCount
            )
        }
    }

    /** Return the first [lineCount] lines of [content] (inclusive of the trailing newline of the last line). */
    private fun takeFirstLines(content: String, lineCount: Int): String {
        if (lineCount <= 0 || content.isEmpty()) return ""
        var seen = 0
        val sb = StringBuilder()
        for (i in content.indices) {
            sb.append(content[i])
            if (content[i] == '\n') {
                seen++
                if (seen >= lineCount) break
            }
        }
        return sb.toString()
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
        val all = manager.getAll()
        _uiState.update { it.copy(annotations = all) }
        saveAnnotationsToHandle(all)
    }

    fun deleteAnnotation(id: String) {
        val manager = annotationManager ?: return
        manager.delete(id)
        val all = manager.getAll()
        _uiState.update { it.copy(annotations = all) }
        saveAnnotationsToHandle(all)
    }

    fun updateAnnotation(id: String, note: String) {
        val manager = annotationManager ?: return
        manager.update(id, note)
        val all = manager.getAll()
        _uiState.update { it.copy(annotations = all) }
        saveAnnotationsToHandle(all)
    }

    suspend fun submitAnnotations(overallNote: String, editedNotes: Map<String, String> = emptyMap()): Result<Unit> {
        val manager = annotationManager ?: return Result.failure(IllegalStateException("No annotation manager"))
        // Apply any edited notes before submitting
        editedNotes.forEach { (id, newNote) -> manager.update(id, newNote) }
        val anns = manager.getAll()
        if (anns.isEmpty()) return Result.failure(IllegalStateException("No annotations to submit"))
        val result = submitAnnotationsUseCase(serverId, sessionId, anns, overallNote, filePath, directory)
        if (result.isSuccess) {
            manager.clear()
            annotationManager = null
            fullContentCache = ""
            _uiState.update { it.copy(annotations = emptyList(), content = "", isEmpty = true) }
            savedStateHandle.remove<Any>("annotations_flat")
        }
        return result
    }

    // ============ Phase 4: Annotation SavedStateHandle persistence ============

    private fun saveAnnotationsToHandle(annotations: List<Annotation>) {
        if (annotations.isEmpty()) {
            savedStateHandle.remove<Any>("annotations_flat")
            return
        }
        val flat = ArrayList<Any>(annotations.size * 11)
        annotations.forEach { ann ->
            flat.add(ann.id); flat.add(ann.index); flat.add(ann.startChar); flat.add(ann.endChar)
            flat.add(ann.startLine); flat.add(ann.startCol); flat.add(ann.endLine); flat.add(ann.endCol)
            flat.add(ann.selectedText); flat.add(ann.note); flat.add(ann.createdAt)
        }
        savedStateHandle["annotations_flat"] = flat
    }

    private fun restoreAnnotationsFromHandle(): List<Annotation> {
        val raw = savedStateHandle.get<ArrayList<*>>("annotations_flat") ?: return emptyList()
        if (raw.isEmpty()) return emptyList()
        return raw.chunked(11).map { items ->
            Annotation(
                id = items[0] as String, index = items[1] as Int,
                startChar = items[2] as Int, endChar = items[3] as Int,
                startLine = items[4] as Int, startCol = items[5] as Int,
                endLine = items[6] as Int, endCol = items[7] as Int,
                selectedText = items[8] as String, note = items[9] as String,
                createdAt = items[10] as Long
            )
        }
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

    /**
     * Switch from DIFF mode to SOURCE mode so users can annotate the code.
     * If source content was never loaded (e.g., entered via GIT_DIFF), fetches it first.
     */
    fun switchToSource() {
        val current = _uiState.value
        if (current.mode == FileViewerMode.SOURCE) return
        if (current.content.isBlank()) {
            // Source content never loaded → fetch now (loadLive sets mode = SOURCE)
            loadLive()
        } else {
            // Content already available → just switch mode
            _uiState.update { it.copy(mode = FileViewerMode.SOURCE) }
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
        setupToolSnapshotSource(content, snapshots)
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
        val lastSnap = snapshots.last()
        // Edit tools only cache the newString fragment — NOT the full file.
        // Fetch the complete file content from the server so the viewer shows
        // the entire file (not just the edited snippet).
        viewModelScope.launch {
            getFileContent(serverId, directory, filePath)
                .onSuccess { c ->
                    if (c.type == ContentType.BINARY) {
                        _uiState.update { it.copy(isLoading = false, isBinary = true, mimeType = c.mimeType) }
                    } else {
                        setupToolSnapshotSource(c.content, snapshots)
                    }
                }
                .onFailure {
                    // Fallback: use the edited fragment (incomplete but better than blank)
                    val fallback = lastSnap.after ?: lastSnap.content ?: lastSnap.before ?: ""
                    setupToolSnapshotSource(fallback, snapshots)
                }
        }
    }

    /**
     * Shared setup for TOOL_SNAPSHOT and TOOL_SNAPSHOT_DIFF: populates UI state
     * with paginated source content + annotation manager + tool metadata.
     */
    private fun setupToolSnapshotSource(content: String, snapshots: List<dev.leonardo.ocremotev2.domain.repository.ToolSnapshotCache.Snapshot>) {
        val first = snapshots.first()
        val last = snapshots.last()
        fullContentCache = content
        val totalLines = if (content.isEmpty()) 0
                         else content.count { it == '\n' } + if (content.endsWith('\n')) 0 else 1
        val initialVisible = minOf(totalLines, INITIAL_PAGE_SIZE)
        val visible = takeFirstLines(content, initialVisible)
        annotationManager = AnnotationManager(content)
        _uiState.update {
            it.copy(
                isLoading = false,
                mode = FileViewerMode.SOURCE,
                content = visible,
                isEmpty = content.isBlank(),
                isMarkdown = isMarkdownFile(filePath),
                renderMode = FileViewerRenderMode.SOURCE,
                isToolSnapshot = true,
                toolSnapshotContent = first.content,
                toolSnapshotBefore = first.before,
                toolSnapshotAfter = last.after ?: last.content,
                totalLineCount = totalLines,
                visibleLineCount = initialVisible,
                isFullyLoaded = initialVisible >= totalLines,
                annotations = annotationManager?.getAll() ?: emptyList()
            )
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
