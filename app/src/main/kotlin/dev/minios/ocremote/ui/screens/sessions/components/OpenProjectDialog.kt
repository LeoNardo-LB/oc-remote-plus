package dev.minios.ocremote.ui.screens.sessions.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.minios.ocremote.R
import dev.minios.ocremote.data.dto.response.FileNode
import dev.minios.ocremote.domain.model.Project
import dev.minios.ocremote.ui.components.AmoledSurface
import dev.minios.ocremote.ui.components.AmoledDefaultBorder
import dev.minios.ocremote.ui.components.indicators.PulsingDotsIndicator
import dev.minios.ocremote.ui.screens.sessions.SessionListViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import dev.minios.ocremote.ui.theme.AlphaTokens
import dev.minios.ocremote.ui.theme.ShapeTokens

/**
 * Directory browser dialog for opening a project.
 * Shows: known projects at top, then browsable server filesystem.
 * Supports search and tap-to-navigate into subdirectories.
 */
@Composable
internal fun OpenProjectDialog(
    viewModel: SessionListViewModel,
    projects: List<Project>,
    initialDirectory: String? = null,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val isAmoled = isAmoledTheme()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var currentDir by remember { mutableStateOf<String?>(null) }
    var homeDir by remember { mutableStateOf<String?>(null) }
    var directories by remember { mutableStateOf<List<FileNode>>(emptyList()) }
    var searchResults by remember { mutableStateOf<List<String>>(emptyList()) }
    var pathNavigatedDirs by remember { mutableStateOf<List<FileNode>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var isCreatingFolder by remember { mutableStateOf(false) }
    var createFolderError by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }

    val isSearching = searchQuery.isNotBlank()

    fun isPathLike(input: String): Boolean {
        val trimmed = input.trim()
        return trimmed.startsWith("/") ||
               trimmed.startsWith("~") ||
               trimmed.matches(Regex("[A-Za-z]:.*"))
    }

    fun resolvePath(input: String, home: String?): Pair<String, String?> {
        val trimmed = input.trim()
        val expanded = if (trimmed.startsWith("~")) {
            (home ?: "") + trimmed.removePrefix("~")
        } else {
            trimmed
        }

        val normalized = expanded.replace('\\', '/')

        return if (normalized.endsWith("/")) {
            expanded to null
        } else {
            val lastSlash = normalized.lastIndexOf('/')
            if (lastSlash > 0) {
                var parent = expanded.substring(0, lastSlash)
                // Windows 盘符修复：D: → D:/
                if (parent.length == 2 && parent[1] == ':') {
                    parent = "$parent/"
                }
                parent = parent.replace('/', java.io.File.separatorChar)
                val fragment = normalized.substring(lastSlash + 1)
                parent to fragment
            } else {
                expanded to null
            }
        }
    }

    // Load home directory and initial listing
    LaunchedEffect(Unit) {
        val home = viewModel.getHomeDirectory()
        homeDir = home
        val startDir = initialDirectory ?: home
        currentDir = startDir
        isLoading = true
        directories = viewModel.listDirectories(startDir)
        isLoading = false
    }

    // Re-list when currentDir changes
    LaunchedEffect(currentDir) {
        val dir = currentDir ?: return@LaunchedEffect
        if (searchQuery.isBlank()) {
            isLoading = true
            directories = viewModel.listDirectories(dir)
            isLoading = false
        }
    }

    // Search debounce
    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank()) {
            searchResults = emptyList()
            pathNavigatedDirs = emptyList()
            currentDir?.let {
                isLoading = true
                directories = viewModel.listDirectories(it)
                isLoading = false
            }
            return@LaunchedEffect
        }
        delay(300)
        isLoading = true

        if (isPathLike(searchQuery)) {
            // 路径导航模式
            val (resolvedPath, fragment) = resolvePath(searchQuery, homeDir)
            val allDirs = viewModel.listDirectories(resolvedPath)
            pathNavigatedDirs = if (fragment != null) {
                allDirs.filter { it.name.startsWith(fragment, ignoreCase = true) }
            } else {
                allDirs
            }
            searchResults = emptyList()
        } else {
            // 文件名模糊搜索模式
            val baseDir = homeDir ?: "/"
            searchResults = viewModel.searchDirectories(searchQuery, baseDir)
            pathNavigatedDirs = emptyList()
        }
        isLoading = false
    }

    // Focus the search field
    LaunchedEffect(Unit) {
        delay(200)
        try { focusRequester.requestFocus() } catch (_: Exception) {}
    }

    /** Shorten an absolute path by replacing home prefix with ~ */
    fun tildeReplace(path: String): String {
        val home = homeDir ?: return path
        return if (path.startsWith(home)) "~" + path.removePrefix(home) else path
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        AmoledSurface(
            isAmoledDark = isAmoled,
            shape = ShapeTokens.large,
            normalTonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.75f),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.sessions_open_project),
                        style = MaterialTheme.typography.titleMedium
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                    }
                }

                // Search field
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(ShapeTokens.small)
                        .background(
                            if (isAmoled) {
                                Color.Black
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = AlphaTokens.MUTED)
                            }
                        )
                        .then(
                            if (isAmoled) {
                                Modifier.border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.MEDIUM),
                                    shape = ShapeTokens.small
                                )
                            } else {
                                Modifier
                            }
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
                    )
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { innerTextField ->
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.sessions_search_folders),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.FAINT)
                                )
                            }
                            innerTextField()
                        }
                    )
                        if (searchQuery.isNotEmpty()) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.chat_clear),
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable { searchQuery = "" },
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
                            )
                        }
                }

                // Breadcrumb / current path (when not searching)
                if (!isSearching && currentDir != null) {
                    val canGoUp = currentDir != "/" && currentDir != homeDir
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp)
                            .then(
                                if (canGoUp) Modifier
                                    .clip(ShapeTokens.extraSmall)
                                    .clickable {
                                        // Navigate up
                                        val parent = currentDir!!.trimEnd('/').substringBeforeLast('/')
                                        currentDir = parent.ifEmpty { "/" }
                                    } else Modifier
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (canGoUp) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.MEDIUM)
                            )
                        }
                        Text(
                            text = tildeReplace(currentDir ?: "/"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MEDIUM),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT)
                )

                Box(modifier = Modifier.fillMaxSize()) {
                    // Content
                    when {
                        isLoading -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                PulsingDotsIndicator(dotSize = 10.dp, dotSpacing = 6.dp)
                            }
                        }
                        isSearching -> {
                            val displayDirs = if (pathNavigatedDirs.isNotEmpty()) pathNavigatedDirs else emptyList()
                            val displayPaths = if (searchResults.isNotEmpty()) searchResults else emptyList()

                            if (displayDirs.isEmpty() && displayPaths.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stringResource(R.string.sessions_no_folders),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
                                    )
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(vertical = 4.dp)
                                ) {
                                    // 路径导航结果（FileNode 列表）
                                    items(displayDirs, key = { it.path }) { node ->
                                        val absPath = node.absolute ?: node.path
                                        DirectoryRow(
                                            displayPath = tildeReplace(absPath) + "/",
                                            onClick = { onSelect(absPath) },
                                            onNavigate = {
                                                searchQuery = ""
                                                currentDir = absPath
                                            }
                                        )
                                    }
                                    // 模糊搜索结果（路径字符串列表）
                                    items(displayPaths) { path ->
                                        val base = (homeDir ?: "").trimEnd('/')
                                        val rel = path.trimStart('/').trimEnd('/')
                                        val absolutePath = "$base/$rel"
                                        DirectoryRow(
                                            displayPath = tildeReplace(absolutePath) + "/",
                                            onClick = { onSelect(absolutePath) },
                                            onNavigate = {
                                                searchQuery = ""
                                                currentDir = absolutePath
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        else -> {
                            // Directory listing
                            val showKnownProjects = currentDir == homeDir && projects.isNotEmpty()

                            if (directories.isEmpty() && !showKnownProjects) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stringResource(R.string.sessions_empty_directory),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
                                    )
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(vertical = 4.dp)
                                ) {
                                    items(directories, key = { it.name }) { node ->
                                        val absPath = node.absolute ?: "${currentDir?.trimEnd('/')}/${node.name}"
                                        DirectoryRow(
                                            displayPath = tildeReplace(absPath) + "/",
                                            onNavigate = {
                                                // Navigate into this directory
                                                currentDir = absPath
                                            },
                                            onClick = { onSelect(absPath) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (isAmoled) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .navigationBarsPadding()
                                .imePadding()
                                .padding(16.dp)
                                .size(56.dp)
                                .clickable {
                                    showCreateFolderDialog = true
                                    createFolderError = null
                                    if (newFolderName.isBlank()) newFolderName = ""
                                },
                            shape = CircleShape,
                            color = Color.Black,
                            border = AmoledDefaultBorder,
                            tonalElevation = 0.dp,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.CreateNewFolder,
                                    contentDescription = stringResource(R.string.sessions_create_folder),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    } else {
                        FloatingActionButton(
                            onClick = {
                                showCreateFolderDialog = true
                                createFolderError = null
                                if (newFolderName.isBlank()) newFolderName = ""
                            },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .navigationBarsPadding()
                                .imePadding()
                                .padding(16.dp),
                        ) {
                            Icon(Icons.Default.CreateNewFolder, contentDescription = stringResource(R.string.sessions_create_folder))
                        }
                    }
                }
            }
        }
    }

    if (showCreateFolderDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!isCreatingFolder) showCreateFolderDialog = false
            },
            title = { Text(stringResource(R.string.sessions_create_folder_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newFolderName,
                        onValueChange = {
                            newFolderName = it
                            createFolderError = null
                        },
                        singleLine = true,
                        enabled = !isCreatingFolder,
                        label = { Text(stringResource(R.string.sessions_create_folder_name_label)) },
                        placeholder = { Text(stringResource(R.string.sessions_create_folder_name_placeholder)) },
                        isError = createFolderError != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (createFolderError != null) {
                        Text(
                            text = createFolderError ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val parent = currentDir ?: homeDir ?: "/"
                        val name = newFolderName.trim()
                        if (name.isBlank()) {
                            createFolderError = context.getString(R.string.sessions_create_folder_invalid_name)
                            return@TextButton
                        }

                        isCreatingFolder = true
                        scope.launch {
                            val result = viewModel.createDirectory(parent, name)
                            isCreatingFolder = false
                            result.onSuccess { createdPath ->
                                showCreateFolderDialog = false
                                newFolderName = ""
                                createFolderError = null
                                searchQuery = ""
                                currentDir = parent
                                directories = viewModel.listDirectories(parent)
                                Toast
                                    .makeText(
                                        context,
                                        context.getString(R.string.sessions_create_folder_success, tildeReplace(createdPath)),
                                        Toast.LENGTH_SHORT,
                                    )
                                    .show()
                            }.onFailure { error ->
                                createFolderError = error.message ?: context.getString(R.string.sessions_create_folder_failed)
                            }
                        }
                    },
                    enabled = !isCreatingFolder,
                ) {
                    if (isCreatingFolder) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.sessions_create_folder_create))
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showCreateFolderDialog = false },
                    enabled = !isCreatingFolder,
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
