package dev.leonardo.ocremotev2.ui.screens.viewer

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.leonardo.ocremotev2.R
import dev.leonardo.ocremotev2.domain.model.ContentType
import dev.leonardo.ocremotev2.domain.model.VcsDiffMode
import dev.leonardo.ocremotev2.domain.usecase.GetFileContentUseCase
import dev.leonardo.ocremotev2.domain.usecase.GetFileDiffUseCase
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
    savedStateHandle: SavedStateHandle,
    private val getFileContent: GetFileContentUseCase,
    private val getFileDiff: GetFileDiffUseCase
) : ViewModel() {
    private val serverId = savedStateHandle.get<String>(ServerRouteParams.PARAM_SERVER_ID).orEmpty()
    private val directory = URLDecoder.decode(savedStateHandle.get<String>(FileViewerNav.PARAM_DIRECTORY).orEmpty(), "UTF-8")
    private val filePath = URLDecoder.decode(savedStateHandle.get<String>(FileViewerNav.PARAM_FILE_PATH).orEmpty(), "UTF-8")
    private val source = savedStateHandle.get<String>(FileViewerNav.PARAM_SOURCE) ?: FileViewerNav.Source.LIVE
    private val _uiState = MutableStateFlow(FileViewerUiState(filePath = filePath))
    val uiState: StateFlow<FileViewerUiState> = _uiState.asStateFlow()
    private val diffParser = DiffParser()

    init {
        when (source) {
            FileViewerNav.Source.LIVE -> loadLive()
            FileViewerNav.Source.GIT_DIFF -> loadGitDiff()
            FileViewerNav.Source.TOOL_SNAPSHOT, FileViewerNav.Source.TOOL_SNAPSHOT_DIFF ->
                _uiState.update { it.copy(isLoading = false, error = R.string.fileviewer_error_tool_snapshot_unsupported) }
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
                        _uiState.update { it.copy(isLoading = false, content = visible, isEmpty = visible.isBlank(), isTruncated = truncated) }
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
}
