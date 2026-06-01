package dev.minios.ocremote.ui.screens.sessions

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import dev.minios.ocremote.BuildConfig
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.minios.ocremote.data.dto.response.FileNode
import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.api.ServerConnection
import dev.minios.ocremote.data.repository.EventDispatcher
import dev.minios.ocremote.domain.model.Project
import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.domain.model.SessionStatus
import dev.minios.ocremote.domain.usecase.ManageSessionUseCase
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URLDecoder
import javax.inject.Inject

private const val TAG = "SessionListViewModel"

enum class ListMode { PROJECTS, SESSIONS }

data class ProjectGroup(
    val directory: String,
    val displayName: String,
    val sessionCount: Int,
    val lastUpdated: Long?,
)

data class SessionListUiState(
    val mode: ListMode = ListMode.PROJECTS,
    val projectGroups: List<ProjectGroup> = emptyList(),
    val currentProject: ProjectGroup? = null,
    val sessions: List<SessionItem> = emptyList(),
    val serverName: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val selectedIds: Set<String> = emptySet(),
    val isSelectionMode: Boolean = false,
)

/** A group of sessions belonging to a project. */
data class ProjectSessionGroup(
    val projectId: String,
    val projectName: String,
    val directory: String,
    val sessions: List<SessionItem>,
    /** Per-session tilde-path labels (sessionId -> tildePath) for flat display. */
    val sessionDirLabels: Map<String, String> = emptyMap()
)

/** Helper for session directory info. */
private data class SessionDirInfo(val name: String, val tildePath: String)

data class SessionItem(
    val session: Session,
    val status: SessionStatus = SessionStatus.Idle
)

@HiltViewModel
class SessionListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val eventDispatcher: EventDispatcher,
    private val api: OpenCodeApi,
    private val manageSessionUseCase: ManageSessionUseCase
) : ViewModel() {

    val serverUrl: String = URLDecoder.decode(
        savedStateHandle.get<String>("serverUrl") ?: "", "UTF-8"
    )
    private val username: String = URLDecoder.decode(
        savedStateHandle.get<String>("username") ?: "", "UTF-8"
    )
    private val password: String = URLDecoder.decode(
        savedStateHandle.get<String>("password") ?: "", "UTF-8"
    )
    val serverName: String = URLDecoder.decode(
        savedStateHandle.get<String>("serverName") ?: "", "UTF-8"
    )
    val serverId: String = URLDecoder.decode(
        savedStateHandle.get<String>("serverId") ?: "", "UTF-8"
    )

    private val conn = ServerConnection.from(serverUrl, username, password.ifEmpty { null })

    private val _error = MutableStateFlow<String?>(null)
    private val _isLoading = MutableStateFlow(true)
    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    private val _homeDir = MutableStateFlow<String?>(null)
    private val _mode = MutableStateFlow(ListMode.PROJECTS)
    private val _currentProject = MutableStateFlow<ProjectGroup?>(null)
    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    private val _navigateToSession = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val navigateToSession: SharedFlow<String> = _navigateToSession.asSharedFlow()

    @Suppress("UNCHECKED_CAST")
    val uiState: StateFlow<SessionListUiState> = combine(
        _mode,
        _currentProject,
        eventDispatcher.sessions,
        eventDispatcher.sessionStatuses,
        eventDispatcher.serverSessions,
        _isLoading,
        _error,
        _projects,
        _homeDir,
        _selectedIds
    ) { values ->
        val mode = values[0] as ListMode
        val currentProject = values[1] as ProjectGroup?
        val allSessions = values[2] as List<Session>
        val statuses = values[3] as Map<String, SessionStatus>
        val serverSessionMap = values[4] as Map<String, Set<String>>
        val isLoading = values[5] as Boolean
        val error = values[6] as String?
        val projects = values[7] as List<Project>
        val homeDir = values[8] as String?
        val selectedIds = values[9] as Set<String>

        val serverSessionIds = serverSessionMap[serverId].orEmpty()

        val filteredSessions = allSessions
            .filter { it.id in serverSessionIds && !it.isArchived && it.parentId == null }
            .sortedByDescending { it.time.updated }

        val projectGroups = filteredSessions
            .groupBy { it.directory }
            .map { (dir, sessions) ->
                ProjectGroup(
                    directory = dir,
                    displayName = dir.replaceHomePrefix(homeDir ?: ""),
                    sessionCount = sessions.size,
                    lastUpdated = sessions.maxOfOrNull { it.time.updated }
                )
            }
            .sortedByDescending { it.lastUpdated ?: 0L }

        val displaySessions = if (mode == ListMode.SESSIONS && currentProject != null) {
            filteredSessions.filter { it.directory == currentProject.directory }
        } else {
            emptyList()
        }.map { session ->
            SessionItem(
                session = session,
                status = statuses[session.id] ?: SessionStatus.Idle
            )
        }

        SessionListUiState(
            mode = mode,
            projectGroups = projectGroups,
            currentProject = currentProject,
            sessions = displaySessions,
            serverName = serverName,
            isLoading = isLoading,
            error = error,
            selectedIds = selectedIds,
            isSelectionMode = selectedIds.isNotEmpty()
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SessionListUiState())

    init {
        loadHomeDir()
        loadSessions()
    }

    fun loadSessions() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                // Load all projects first
                val projects = api.listProjects(conn)
                _projects.value = projects
                if (BuildConfig.DEBUG) Log.d(TAG, "Loaded ${projects.size} projects for multi-project session fetch")

                if (projects.isEmpty()) {
                    // Fallback: load sessions without directory header (server CWD only)
                    val sessions = api.listSessions(conn)
                    eventDispatcher.setSessions(serverId, sessions)
                    if (BuildConfig.DEBUG) Log.d(TAG, "Loaded ${sessions.size} sessions (no projects)")
                } else {
                    // Load sessions for each project using its worktree as directory
                    var totalSessions = 0
                    for (project in projects) {
                        try {
                            val sessions = api.listSessions(conn, directory = project.worktree)
                            eventDispatcher.setSessions(serverId, sessions)
                            totalSessions += sessions.size
                            if (BuildConfig.DEBUG) Log.d(TAG, "Loaded ${sessions.size} sessions for project ${project.displayName}")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to load sessions for project ${project.displayName}: ${e.message}")
                        }
                    }
                    if (BuildConfig.DEBUG) Log.d(TAG, "Total: loaded $totalSessions sessions across ${projects.size} projects for server $serverId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load sessions", e)
                _error.value = e.message ?: "Failed to load sessions"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun loadProjects() {
        viewModelScope.launch {
            try {
                val projects = api.listProjects(conn)
                _projects.value = projects
                if (BuildConfig.DEBUG) Log.d(TAG, "Loaded ${projects.size} projects")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load projects", e)
            }
        }
    }

    private fun loadHomeDir() {
        viewModelScope.launch {
            getHomeDirectory()
        }
    }

    fun createNewSession(directory: String? = null) {
        viewModelScope.launch {
            try {
                val session = manageSessionUseCase.createSession(conn, directory = directory)
                // The SSE stream should pick up the new session, but also add directly
                eventDispatcher.setSessions(serverId, listOf(session))
                if (BuildConfig.DEBUG) Log.d(TAG, "Created new session: ${session.id}")
                _navigateToSession.tryEmit(session.id)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create session", e)
                _error.value = e.message ?: "Failed to create session"
            }
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            try {
                val success = api.deleteSession(conn, sessionId)
                if (success) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Deleted session $sessionId")
                    loadSessions()
                } else {
                    _error.value = "Failed to delete session"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete session", e)
                _error.value = e.message ?: "Failed to delete session"
            }
        }
    }

    fun toggleSelection(sessionId: String) {
        _selectedIds.update { selected ->
            if (sessionId in selected) selected - sessionId else selected + sessionId
        }
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
    }

    fun selectAll() {
        val currentState = uiState.value
        if (currentState.mode == ListMode.SESSIONS) {
            _selectedIds.value = currentState.sessions.map { it.session.id }.toSet()
        }
    }

    fun deleteSelected() {
        viewModelScope.launch {
            val ids = _selectedIds.value
            if (ids.isEmpty()) return@launch
            try {
                val results = coroutineScope {
                    ids.map { id ->
                        async {
                            id to api.deleteSession(conn, id)
                        }
                    }.awaitAll()
                }
                val failed = results.filterNot { it.second }
                if (failed.isNotEmpty()) {
                    _error.value = "Failed to delete ${failed.size} session(s)"
                }
                clearSelection()
                loadSessions()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete selected sessions", e)
                _error.value = e.message ?: "Failed to delete selected sessions"
            }
        }
    }

    fun renameSession(sessionId: String, newTitle: String) {
        viewModelScope.launch {
            try {
                manageSessionUseCase.renameSession(conn, sessionId, newTitle)
                if (BuildConfig.DEBUG) Log.d(TAG, "Renamed session $sessionId to '$newTitle'")
                loadSessions()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to rename session", e)
                _error.value = e.message ?: "Failed to rename session"
            }
        }
    }

    // ============ Directory browsing for Open Project ============

    private fun String.replaceHomePrefix(homeDir: String): String {
        return if (homeDir.isNotBlank() && this.startsWith(homeDir)) {
            "~" + this.removePrefix(homeDir)
        } else this
    }

    // ============ Two-level navigation ============

    fun selectProject(group: ProjectGroup) {
        _currentProject.value = group
        _mode.value = ListMode.SESSIONS
        _selectedIds.value = emptySet()
    }

    fun navigateBack() {
        _mode.value = ListMode.PROJECTS
        _currentProject.value = null
        _selectedIds.value = emptySet()
    }

    fun createSessionInCurrentProject() {
        val dir = _currentProject.value?.directory ?: return
        viewModelScope.launch {
            createNewSession(dir)
        }
    }

    /** Get the server's home directory (cached). */
    suspend fun getHomeDirectory(): String {
        _homeDir.value?.let { return it }
        return try {
            val paths = api.getServerPaths(conn)
            val home = paths.home
            _homeDir.value = home
            if (BuildConfig.DEBUG) Log.d(TAG, "Server home directory: $home")
            home
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get server paths", e)
            "/"
        }
    }

    /** List directories in a given path on the server. */
    suspend fun listDirectories(directory: String): List<FileNode> {
        return try {
            val nodes = api.listDirectory(conn, path = "", directory = directory)
            nodes.filter { it.type == "directory" }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list directory: $directory", e)
            emptyList()
        }
    }

    /** Search for directories matching a query, scoped to a base directory. */
    suspend fun searchDirectories(query: String, directory: String): List<String> {
        return try {
            api.findFiles(conn, query = query, type = "directory", directory = directory, limit = 50)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to search directories", e)
            emptyList()
        }
    }

    /** Create a directory inside the currently browsed path. */
    suspend fun createDirectory(parentDirectory: String, folderName: String): Result<String> {
        val sanitized = folderName.trim().trim('/').replace(Regex("/+"), "/")
        if (sanitized.isBlank() || sanitized == "." || sanitized == "..") {
            return Result.failure(IllegalArgumentException("Invalid folder name"))
        }

        return runCatching {
            val targetDirectory = if (parentDirectory == "/") {
                "/$sanitized"
            } else {
                "${parentDirectory.trimEnd('/')}/$sanitized"
            }

            val tempSession = api.createSession(
                conn = conn,
                title = "mkdir",
                directory = parentDirectory,
            )

            try {
                val escaped = sanitized.replace("'", "'\"'\"'")
                val command = "mkdir -p -- '$escaped'"

                val runShellOk = runCatching {
                    api.runShellCommand(
                        conn = conn,
                        sessionId = tempSession.id,
                        command = command,
                        agent = "build",
                        directory = parentDirectory,
                    )
                }.getOrElse { false }

                if (!runShellOk) {
                    val executeOk = api.executeCommand(
                        conn = conn,
                        sessionId = tempSession.id,
                        command = "bash",
                        arguments = "-lc \"$command\"",
                        directory = parentDirectory,
                    )
                    if (!executeOk) {
                        throw IllegalStateException("Failed to create directory")
                    }
                }
            } finally {
                runCatching { api.deleteSession(conn, tempSession.id) }
            }

            repeat(6) {
                if (directoryExists(targetDirectory)) {
                    return@runCatching targetDirectory
                }
                delay(200)
            }

            throw IllegalStateException("Directory was not created")
        }
    }

    private suspend fun directoryExists(directory: String): Boolean {
        return try {
            api.listDirectory(conn, path = "", directory = directory)
            true
        } catch (_: Exception) {
            false
        }
    }
}
