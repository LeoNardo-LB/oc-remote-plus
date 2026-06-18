package dev.minios.ocremote.ui.screens.workspace

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.minios.ocremote.domain.model.FileNode
import dev.minios.ocremote.domain.model.isDirectory
import dev.minios.ocremote.domain.usecase.GetVcsStatusUseCase
import dev.minios.ocremote.domain.usecase.ListDirectoryUseCase
import dev.minios.ocremote.ui.navigation.routes.ServerRouteParams
import dev.minios.ocremote.ui.navigation.routes.WorkspaceNav
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URLDecoder
import javax.inject.Inject

@HiltViewModel
class WorkspaceViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val listDirectory: ListDirectoryUseCase,
    private val getVcsStatus: GetVcsStatusUseCase
) : ViewModel() {

    private val serverId = savedStateHandle.get<String>(ServerRouteParams.PARAM_SERVER_ID).orEmpty()
    private val directory = URLDecoder.decode(
        savedStateHandle.get<String>(WorkspaceNav.PARAM_DIRECTORY).orEmpty(), "UTF-8"
    )

    private val _uiState = MutableStateFlow(WorkspaceUiState(directory = directory))
    val uiState: StateFlow<WorkspaceUiState> = _uiState.asStateFlow()

    private val dirCache = mutableMapOf<String, List<FileNode>>()
    private val loadJobs = mutableMapOf<String, Job>()

    private val _dirLoadEvents = MutableSharedFlow<DirectoryLoadResult>()
    val dirLoadEvents: SharedFlow<DirectoryLoadResult> = _dirLoadEvents.asSharedFlow()

    init {
        if (serverId.isBlank()) {
            _uiState.update { it.copy(rootError = "服务器配置缺失", rootLoading = false) }
        } else {
            loadDirectory("")
            prefetchGitCount()
        }
    }

    fun loadDirectory(path: String) {
        if (serverId.isBlank()) return
        dirCache[path]?.let { return }
        loadJobs[path]?.cancel()
        if (path.isEmpty()) _uiState.update { it.copy(rootLoading = true, rootError = null) }
        loadJobs[path] = viewModelScope.launch {
            listDirectory(serverId, directory, path)
                .onSuccess { nodes ->
                    dirCache[path] = nodes
                    if (path.isEmpty()) {
                        _uiState.update { it.copy(rootNodes = nodes.toTreeNodes(), rootLoading = false) }
                    } else {
                        _dirLoadEvents.tryEmit(DirectoryLoadResult(path, nodes, null))
                    }
                }
                .onFailure { e ->
                    val msg = e.message ?: "加载失败"
                    if (path.isEmpty()) {
                        _uiState.update { it.copy(rootLoading = false, rootError = msg) }
                    } else {
                        _dirLoadEvents.tryEmit(DirectoryLoadResult(path, emptyList(), msg))
                    }
                }
        }
    }

    fun refreshRoot() {
        dirCache.clear()
        loadJobs.values.forEach { it.cancel() }
        loadDirectory("")
    }

    fun switchPanel(p: WorkspacePanel) {
        _uiState.update { it.copy(currentPanel = p) }
        if (p == WorkspacePanel.GIT_CHANGES
            && _uiState.value.gitChanges.isEmpty()
            && !_uiState.value.isNonGit
            && !_uiState.value.gitLoading
        ) {
            loadGitChanges()
        }
    }

    fun loadGitChanges() {
        if (serverId.isBlank()) return
        _uiState.update { it.copy(gitLoading = true, gitError = null, isNonGit = false) }
        viewModelScope.launch {
            getVcsStatus(serverId, directory)
                .onSuccess { c ->
                    _uiState.update {
                        it.copy(gitChanges = c, gitLoading = false, gitChangeCount = c.size, isNonGit = false)
                    }
                }
                .onFailure { e ->
                    val msg = e.message.orEmpty()
                    val nonGit = msg.contains("non-git", true) || msg.contains("not a git", true)
                    _uiState.update {
                        it.copy(gitLoading = false, isNonGit = nonGit, gitError = if (nonGit) null else msg)
                    }
                }
        }
    }

    private fun prefetchGitCount() {
        viewModelScope.launch {
            getVcsStatus(serverId, directory)
                .onSuccess { c -> _uiState.update { it.copy(gitChangeCount = c.size) } }
        }
    }

    fun toggleShowIgnored() {
        _uiState.update { it.copy(showIgnored = !it.showIgnored) }
    }

    private fun List<FileNode>.toTreeNodes() =
        sortedWith(compareBy({ !it.isDirectory() }, { it.name.lowercase() }))
            .map { FileTreeNode(it, if (it.isDirectory()) null else emptyList()) }
}
