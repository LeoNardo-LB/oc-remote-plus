package dev.minios.ocremote.ui.screens.sessions

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.R
import dev.minios.ocremote.ui.components.AmoledSurface
import dev.minios.ocremote.ui.components.indicators.PulsingDotsIndicator
import dev.minios.ocremote.ui.screens.sessions.components.DirectoryTreeNode
import dev.minios.ocremote.ui.screens.sessions.components.SessionRow
import dev.minios.ocremote.ui.screens.sessions.components.TreeNode
import dev.minios.ocremote.ui.screens.sessions.components.isAmoledTheme
import dev.minios.ocremote.ui.screens.sessions.components.OpenProjectDialog
import dev.minios.ocremote.ui.theme.AlphaTokens
import dev.minios.ocremote.ui.theme.ShapeTokens
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    viewModel: SessionListViewModel,
    onNavigateToChat: (sessionId: String, openTerminal: Boolean) -> Unit,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val isAmoled = isAmoledTheme()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(viewModel) {
        viewModel.navigateToSession
            .onEach { sessionId ->
                onNavigateToChat(sessionId, false)
            }
            .launchIn(this)
    }

    // Dialog states
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameSessionId by remember { mutableStateOf("") }
    var renameText by remember { mutableStateOf("") }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var deleteSessionId by remember { mutableStateOf("") }
    var deleteSessionTitle by remember { mutableStateOf("") }
    var showDeleteSelectedDialog by remember { mutableStateOf(false) }

    var showOpenProject by remember { mutableStateOf(false) }
    var showBaseDirDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = uiState.isSelectionMode) {
        viewModel.clearSelection()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                        containerColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            } else {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(ShapeTokens.small)
                                .clickable { showBaseDirDialog = true }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = uiState.baseDirectory?.let { dir ->
                                    dir.replace('\\', '/').trimEnd('/')
                                } ?: uiState.serverName.ifEmpty { stringResource(R.string.sessions_title) },
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!uiState.isSelectionMode) {
                FloatingActionButton(
                    onClick = { showOpenProject = true },
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
                            color = MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.MEDIUM),
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
            when {
                uiState.isLoading && uiState.treeNodes.isEmpty() -> {
                    PulsingDotsIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        dotSize = 12.dp,
                        dotSpacing = 8.dp
                    )
                }
                uiState.error != null && uiState.treeNodes.isEmpty() -> {
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
                uiState.treeNodes.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.sessions_empty_directory),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {
                    val untitledLabel = stringResource(R.string.session_untitled)
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        itemsIndexed(uiState.treeNodes, key = { _, node -> node.id }) { index, node ->
                            when (node) {
                                is TreeNode.Directory -> {
                                    DirectoryTreeNode(
                                        node = node,
                                        onClick = { viewModel.toggleDirectory(node.path) },
                                        onCopyPath = { path ->
                                            viewModel.copyToClipboard(path, context)
                                            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.menu_copied_to_clipboard)) }
                                        },
                                    )
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(
                                            alpha = if (isAmoled) AlphaTokens.FAINT else 0.2f
                                        )
                                    )
                                }
                                is TreeNode.Session -> {
                                    SessionRow(
                                        item = node.session,
                                        isSelectionMode = uiState.isSelectionMode,
                                        isSelected = node.id in uiState.selectedIds,
                                        onClick = {
                                            if (uiState.isSelectionMode) {
                                                viewModel.toggleSelection(node.id)
                                            } else {
                                                onNavigateToChat(node.id, false)
                                            }
                                        },
                                        onLongClick = { viewModel.toggleSelection(node.id) },
                                        onRename = {
                                            renameSessionId = node.id
                                            renameText = node.session.session.title ?: ""
                                            showRenameDialog = true
                                        },
                                        onDelete = {
                                            deleteSessionId = node.id
                                            deleteSessionTitle = node.session.session.title ?: untitledLabel
                                            showDeleteDialog = true
                                        },
                                        onCopyId = { id ->
                                            viewModel.copyToClipboard(id, context)
                                            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.menu_copied_to_clipboard)) }
                                        },
                                    )
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(
                                            alpha = if (isAmoled) AlphaTokens.FAINT else 0.2f
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Open Project dialog
    if (showOpenProject) {
        OpenProjectDialog(
            viewModel = viewModel,
            projects = emptyList(),
            onSelect = { directory ->
                showOpenProject = false
                viewModel.createNewSession(directory = directory)
            },
            onDismiss = { showOpenProject = false }
        )
    }

    // Base directory selector dialog
    if (showBaseDirDialog) {
        OpenProjectDialog(
            viewModel = viewModel,
            projects = emptyList(),
            onSelect = { directory ->
                showBaseDirDialog = false
                viewModel.setBaseDirectory(directory)
            },
            onDismiss = { showBaseDirDialog = false }
        )
    }

    // Delete selected dialog
    if (showDeleteSelectedDialog) {
        BasicAlertDialog(onDismissRequest = { showDeleteSelectedDialog = false }) {
            AmoledSurface(
                isAmoledDark = isAmoled,
                shape = ShapeTokens.largeMedium,
                normalTonalElevation = 6.dp,
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
            AmoledSurface(
                isAmoledDark = isAmoled,
                shape = ShapeTokens.largeMedium,
                normalTonalElevation = 6.dp,
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
            AmoledSurface(
                isAmoledDark = isAmoled,
                shape = ShapeTokens.largeMedium,
                normalTonalElevation = 6.dp,
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
