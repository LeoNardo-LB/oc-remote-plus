package dev.minios.ocremote.ui.screens.sessions.components

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.minios.ocremote.R
import dev.minios.ocremote.data.dto.response.FileNode
import dev.minios.ocremote.domain.model.Project
import dev.minios.ocremote.ui.components.amoledDialogParams
import dev.minios.ocremote.ui.components.amoledOutlinedTextFieldColors
import dev.minios.ocremote.ui.components.DialogButtons
import dev.minios.ocremote.ui.components.DialogButtonRole
import dev.minios.ocremote.ui.components.indicators.PulsingDotsIndicator
import dev.minios.ocremote.ui.screens.sessions.SessionListViewModel
import dev.minios.ocremote.ui.theme.AlphaTokens
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Directory browser dialog for opening a project.
 * Standard file-system browser with tap-to-navigate into subdirectories.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OpenProjectDialog(
    viewModel: SessionListViewModel,
    projects: List<Project>,
    initialDirectory: String? = null,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val isAmoled = isAmoledTheme()
    val fieldColors = if (isAmoled) {
        amoledOutlinedTextFieldColors()
    } else {
        OutlinedTextFieldDefaults.colors()
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var currentDir by remember { mutableStateOf<String?>(null) }
    var homeDir by remember { mutableStateOf<String?>(null) }
    var directories by remember { mutableStateOf<List<FileNode>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var isCreatingFolder by remember { mutableStateOf(false) }
    var createFolderError by remember { mutableStateOf<String?>(null) }

    /** Shorten an absolute path by replacing home prefix with ~ */
    fun tildeReplace(path: String): String {
        if (path == SessionListViewModel.WINDOWS_DRIVES_ROOT) return path
        val home = homeDir ?: return path
        return if (path.startsWith(home)) "~" + path.removePrefix(home) else path
    }

    // Load server paths and then list currentDir whenever it changes.
    LaunchedEffect(Unit) {
        val paths = viewModel.getServerPaths()
        homeDir = paths.home
        if (currentDir == null) {
            currentDir = initialDirectory ?: if (viewModel.isWindowsServer) SessionListViewModel.WINDOWS_DRIVES_ROOT else "/"
        }
    }

    // React to currentDir changes via snapshotFlow
    LaunchedEffect(Unit) {
        snapshotFlow { currentDir }.collectLatest { dir ->
            if (dir == null) return@collectLatest
            isLoading = true
            directories = if (dir == SessionListViewModel.WINDOWS_DRIVES_ROOT) {
                viewModel.listWindowsDrives()
            } else {
                viewModel.listDirectories(dir)
            }
            isLoading = false
        }
    }

    val params = amoledDialogParams()

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f),
            color = params.containerColor,
            tonalElevation = params.tonalElevation,
            border = params.border,
            shape = params.shape,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(R.string.sessions_open_project),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(16.dp))

                // Path bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Back arrow - navigate up or to drive list on Windows
                    val dirPath = currentDir ?: "/"
                    val normalizedPath = dirPath.trimEnd('/')
                    val isWindowsDrivesRoot = currentDir == SessionListViewModel.WINDOWS_DRIVES_ROOT
                    val isAtDriveRoot = normalizedPath.matches(Regex("[A-Za-z]:/?"))
                    val isAtRoot = isWindowsDrivesRoot || normalizedPath.isEmpty() || normalizedPath == "/"
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        modifier = Modifier
                            .size(16.dp)
                            .then(
                                if (!isAtRoot) Modifier.clickable {
                                    if (isAtDriveRoot && viewModel.isWindowsServer) {
                                        currentDir = SessionListViewModel.WINDOWS_DRIVES_ROOT
                                    } else {
                                        val parent = java.io.File(normalizedPath).parent
                                        currentDir = parent ?: if (viewModel.isWindowsServer) {
                                            SessionListViewModel.WINDOWS_DRIVES_ROOT
                                        } else {
                                            "/"
                                        }
                                    }
                                } else Modifier
                            ),
                        tint = if (isAtRoot) {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isWindowsDrivesRoot) "Drives" else tildeReplace(currentDir ?: "/"),
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        softWrap = false,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Directory list
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp, max = 400.dp),
                ) {
                    when {
                        isLoading -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                PulsingDotsIndicator(dotSize = 10.dp, dotSpacing = 6.dp)
                            }
                        }
                        directories.isEmpty() -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stringResource(R.string.sessions_empty_directory),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                        alpha = AlphaTokens.MUTED
                                    ),
                                )
                            }
                        }
                        else -> {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(directories, key = { it.name }) { node ->
                                    val absPath = node.absolute
                                        ?: "${currentDir?.trimEnd('/')}/${node.name}"
                                    DirectoryRow(
                                        displayPath = node.name,
                                        onClick = { currentDir = absPath },
                                        onNavigate = { currentDir = absPath },
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                DialogButtons(
                    buttons = listOf(
                        Triple(
                            stringResource(R.string.sessions_create_session),
                            DialogButtonRole.Primary,
                        ) {
                            currentDir?.let { onSelect(it) }
                        },
                        Triple(
                            stringResource(R.string.sessions_create_folder),
                            DialogButtonRole.Secondary,
                        ) {
                            showCreateFolderDialog = true
                            createFolderError = null
                            if (newFolderName.isBlank()) newFolderName = ""
                        },
                    )
                )
            }
        }
    }

    // Create folder dialog
    if (showCreateFolderDialog) {
        val createFolderParams = amoledDialogParams()
        BasicAlertDialog(
            onDismissRequest = {
                if (!isCreatingFolder) showCreateFolderDialog = false
            },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.92f),
                color = createFolderParams.containerColor,
                tonalElevation = createFolderParams.tonalElevation,
                border = createFolderParams.border,
                shape = createFolderParams.shape,
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = stringResource(R.string.sessions_create_folder_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = newFolderName,
                        onValueChange = {
                            newFolderName = it
                            createFolderError = null
                        },
                        singleLine = true,
                        enabled = !isCreatingFolder,
                        label = { Text(stringResource(R.string.sessions_create_folder_name_label)) },
                        placeholder = {
                            Text(stringResource(R.string.sessions_create_folder_name_placeholder))
                        },
                        isError = createFolderError != null,
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (createFolderError != null) {
                        Text(
                            text = createFolderError ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    DialogButtons(
                        buttons = listOf(
                            Triple(stringResource(R.string.cancel), DialogButtonRole.Secondary) {
                                showCreateFolderDialog = false
                            },
                            Triple(
                                stringResource(R.string.sessions_create_folder_create),
                                DialogButtonRole.Primary,
                            ) {
                                val parent = currentDir ?: homeDir ?: "/"
                                val name = newFolderName.trim()
                                if (name.isBlank()) {
                                    createFolderError = context.getString(R.string.sessions_create_folder_invalid_name)
                                    return@Triple
                                }

                                isCreatingFolder = true
                                scope.launch {
                                    val result = viewModel.createDirectory(parent, name)
                                    isCreatingFolder = false
                                    result.onSuccess { createdPath ->
                                        showCreateFolderDialog = false
                                        newFolderName = ""
                                        createFolderError = null
                                        currentDir = parent
                                        directories = viewModel.listDirectories(parent)
                                        Toast
                                            .makeText(
                                                context,
                                                context.getString(
                                                    R.string.sessions_create_folder_success,
                                                    tildeReplace(createdPath)
                                                ),
                                                Toast.LENGTH_SHORT,
                                            )
                                            .show()
                                    }.onFailure { error ->
                                        createFolderError = error.message
                                            ?: context.getString(R.string.sessions_create_folder_failed)
                                    }
                                }
                            },
                        )
                    )
                }
            }
        }
    }
}
