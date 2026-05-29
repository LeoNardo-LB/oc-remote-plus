package dev.minios.ocremote.ui.screens.sessions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import dev.minios.ocremote.R
import dev.minios.ocremote.ui.components.indicators.PulsingDotsIndicator
import dev.minios.ocremote.ui.screens.sessions.components.SessionRow
import dev.minios.ocremote.ui.screens.sessions.components.isAmoledTheme
import dev.minios.ocremote.ui.screens.sessions.components.OpenProjectDialog
import dev.minios.ocremote.ui.screens.sessions.components.NewSessionQuickDialog
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Session List Screen - shows all sessions for a connected server,
 * grouped by project. Tapping a session navigates to the chat screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    viewModel: SessionListViewModel,
    onNavigateToChat: (sessionId: String, openTerminal: Boolean) -> Unit,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val isAmoled = isAmoledTheme()
    // Navigate to newly created session
    LaunchedEffect(viewModel) {
        viewModel.navigateToSession
            .onEach { sessionId ->
                onNavigateToChat(sessionId, false)
            }
            .launchIn(this)
    }

    // Rename dialog state
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameSessionId by remember { mutableStateOf("") }
    var renameText by remember { mutableStateOf("") }

    // Delete confirmation dialog state
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deleteSessionId by remember { mutableStateOf("") }
    var deleteSessionTitle by remember { mutableStateOf("") }
    var showDeleteSelectedDialog by remember { mutableStateOf(false) }

    // Project picker dialog state
    var showOpenProject by remember { mutableStateOf(false) }
    var showQuickNewSession by remember { mutableStateOf(false) }

    BackHandler(enabled = uiState.isSelectionMode) {
        viewModel.clearSelection()
    }

    Scaffold(
        topBar = {
            if (uiState.isSelectionMode) {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.sessions_selected_count, uiState.selectedIds.size),
                            style = MaterialTheme.typography.titleMedium
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                        }
                    },
                    actions = {
                        TextButton(onClick = { viewModel.selectAll() }) {
                            Text(stringResource(R.string.sessions_select_all))
                        }
                        IconButton(onClick = { showDeleteSelectedDialog = true }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.sessions_delete_selected),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = if (isAmoledTheme()) Color.Black else MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = uiState.serverName.ifEmpty { stringResource(R.string.sessions_title) },
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    },
                    actions = {}
                )
            }
        },
        floatingActionButton = {
            if (!uiState.isSelectionMode) {
                FloatingActionButton(
                    onClick = {
                        // If there are known projects, show the quick dialog first;
                        // otherwise go straight to the full directory browser.
                        if (uiState.sessionGroups.isNotEmpty()) {
                            showQuickNewSession = true
                        } else {
                            showOpenProject = true
                        }
                    },
                    containerColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.primaryContainer,
                    contentColor = if (isAmoled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimaryContainer,
                    elevation = if (isAmoled) {
                        FloatingActionButtonDefaults.elevation(
                            defaultElevation = 0.dp,
                            pressedElevation = 0.dp,
                            focusedElevation = 0.dp,
                            hoveredElevation = 0.dp
                        )
                    } else {
                        FloatingActionButtonDefaults.elevation()
                    },
                    modifier = if (isAmoled) {
                        Modifier.border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                            shape = FloatingActionButtonDefaults.shape
                        )
                    } else {
                        Modifier
                    }
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.sessions_new))
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val allSessions = uiState.sessionGroups.flatMap { it.sessions }
            when {
                uiState.isLoading && allSessions.isEmpty() -> {
                    PulsingDotsIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        dotSize = 12.dp,
                        dotSpacing = 8.dp
                    )
                }
                uiState.error != null && allSessions.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = uiState.error ?: stringResource(R.string.session_unknown_error),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                        Button(onClick = { viewModel.loadSessions() }) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
                allSessions.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Chat,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        )
                        Text(
                            text = stringResource(R.string.sessions_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Text(
                            text = stringResource(R.string.sessions_tap_plus),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        for (group in uiState.sessionGroups) {
                            items(group.sessions, key = { it.session.id }) { item ->
                                val untitledLabel = stringResource(R.string.session_untitled)
                                val dirLabel = group.sessionDirLabels[item.session.id]
                                    ?: group.directory.ifEmpty { group.projectName }
                                SessionRow(
                                    item = item,
                                    projectName = dirLabel,
                                    isSelectionMode = uiState.isSelectionMode,
                                    isSelected = item.session.id in uiState.selectedIds,
                                    onClick = {
                                        if (uiState.isSelectionMode) {
                                            viewModel.toggleSelection(item.session.id)
                                        } else {
                                            onNavigateToChat(item.session.id, false)
                                        }
                                    },
                                    onLongClick = { viewModel.toggleSelection(item.session.id) },
                                    onRename = {
                                        renameSessionId = item.session.id
                                        renameText = item.session.title ?: ""
                                        showRenameDialog = true
                                    },
                                    onDelete = {
                                        deleteSessionId = item.session.id
                                        deleteSessionTitle = item.session.title ?: untitledLabel
                                        showDeleteDialog = true
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Quick new session dialog (recent projects)
    if (showQuickNewSession) {
        val allSessions = uiState.sessionGroups.flatMap { it.sessions }
        NewSessionQuickDialog(
            sessions = allSessions,
            onSelectDirectory = { directory ->
                showQuickNewSession = false
                viewModel.createNewSession(directory = directory)
            },
            onBrowse = {
                showQuickNewSession = false
                showOpenProject = true
            },
            onDismiss = { showQuickNewSession = false }
        )
    }

    // Open Project directory browser dialog
    if (showOpenProject) {
        OpenProjectDialog(
            viewModel = viewModel,
            projects = uiState.projects,
            onSelect = { directory ->
                showOpenProject = false
                viewModel.createNewSession(directory = directory)
            },
            onDismiss = { showOpenProject = false }
        )
    }

    if (showDeleteSelectedDialog) {
        BasicAlertDialog(onDismissRequest = { showDeleteSelectedDialog = false }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
                border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
                tonalElevation = if (isAmoled) 0.dp else 6.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.sessions_delete_selected),
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text(stringResource(R.string.sessions_delete_selected_confirm, uiState.selectedIds.size))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showDeleteSelectedDialog = false }) {
                            Text(stringResource(R.string.cancel))
                        }
                        TextButton(
                            onClick = {
                                viewModel.deleteSelected()
                                showDeleteSelectedDialog = false
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text(stringResource(R.string.delete))
                        }
                    }
                }
            }
        }
    }

    // Rename dialog
    if (showRenameDialog) {
        BasicAlertDialog(onDismissRequest = { showRenameDialog = false }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
                border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
                tonalElevation = if (isAmoled) 0.dp else 6.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.session_rename),
                        style = MaterialTheme.typography.headlineSmall
                    )
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        label = { Text(stringResource(R.string.session_rename_title)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showRenameDialog = false }) {
                            Text(stringResource(R.string.cancel))
                        }
                        TextButton(
                            onClick = {
                                viewModel.renameSession(renameSessionId, renameText)
                                showRenameDialog = false
                            },
                            enabled = renameText.isNotBlank()
                        ) {
                            Text(stringResource(R.string.session_rename_button))
                        }
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        BasicAlertDialog(onDismissRequest = { showDeleteDialog = false }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
                border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
                tonalElevation = if (isAmoled) 0.dp else 6.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.session_delete),
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text(stringResource(R.string.session_delete_confirm, deleteSessionTitle))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showDeleteDialog = false }) {
                            Text(stringResource(R.string.cancel))
                        }
                        TextButton(
                            onClick = {
                                viewModel.deleteSession(deleteSessionId)
                                showDeleteDialog = false
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text(stringResource(R.string.delete))
                        }
                    }
                }
            }
        }
    }
}
