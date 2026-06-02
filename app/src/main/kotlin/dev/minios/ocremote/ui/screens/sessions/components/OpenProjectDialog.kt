package dev.minios.ocremote.ui.screens.sessions.components

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.R
import dev.minios.ocremote.data.dto.response.FileNode
import dev.minios.ocremote.domain.model.Project
import dev.minios.ocremote.ui.components.AppDialog
import dev.minios.ocremote.ui.components.AppDialogButtons
import dev.minios.ocremote.ui.components.ButtonStyle
import dev.minios.ocremote.ui.components.indicators.PulsingDotsIndicator
import dev.minios.ocremote.ui.screens.sessions.SessionListViewModel
import dev.minios.ocremote.ui.theme.AlphaTokens
import kotlinx.coroutines.launch

/**
 * Directory browser dialog for opening a project.
 * Standard file-system browser with tap-to-navigate into subdirectories.
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
    val fieldColors = if (isAmoled) {
        OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color.Black,
            unfocusedContainerColor = Color.Black,
            disabledContainerColor = Color.Black,
        )
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
        val home = homeDir ?: return path
        return if (path.startsWith(home)) "~" + path.removePrefix(home) else path
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
        isLoading = true
        directories = viewModel.listDirectories(dir)
        isLoading = false
    }

    AppDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.sessions_open_project),
        isAmoled = isAmoled,
        content = {
            // Path bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (currentDir != homeDir && currentDir != null) {
                    IconButton(
                        onClick = {
                            val path = currentDir?.trimEnd('/') ?: ""
                            val parent = path.substringBeforeLast('/')
                            currentDir = parent.ifEmpty { homeDir }
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = tildeReplace(currentDir ?: "/"),
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
                    .weight(0.7f, fill = false),
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
        },
        buttons = {
            AppDialogButtons(
                listOf(
                    Triple(
                        stringResource(R.string.sessions_create_session),
                        ButtonStyle.Primary,
                    ) {
                        currentDir?.let { onSelect(it) }
                    },
                    Triple(
                        stringResource(R.string.sessions_create_folder),
                        ButtonStyle.Secondary,
                    ) {
                        showCreateFolderDialog = true
                        createFolderError = null
                        if (newFolderName.isBlank()) newFolderName = ""
                    },
                ),
            )
        },
    )

    // Create folder dialog
    if (showCreateFolderDialog) {
        AppDialog(
            onDismiss = {
                if (!isCreatingFolder) showCreateFolderDialog = false
            },
            title = stringResource(R.string.sessions_create_folder_title),
            isAmoled = isAmoled,
            content = {
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
            },
            buttons = {
                AppDialogButtons(
                    listOf(
                        Triple(stringResource(R.string.cancel), ButtonStyle.Secondary) {
                            showCreateFolderDialog = false
                        },
                        Triple(
                            stringResource(R.string.sessions_create_folder_create),
                            ButtonStyle.Primary,
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
                    ),
                )
            },
        )
    }
}
