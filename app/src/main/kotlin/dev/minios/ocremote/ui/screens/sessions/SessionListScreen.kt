package dev.minios.ocremote.ui.screens.sessions

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.activity.compose.BackHandler
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material.icons.filled.Search
import dev.minios.ocremote.ui.components.amoledOutlinedTextFieldColors
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import dev.minios.ocremote.ui.components.amoledDialogParams
import dev.minios.ocremote.ui.components.DialogButtons
import dev.minios.ocremote.ui.components.DialogButtonRole
import dev.minios.ocremote.ui.components.indicators.PulsingDotsIndicator
import dev.minios.ocremote.ui.theme.ButtonTokens
import dev.minios.ocremote.ui.screens.sessions.components.DirectoryTreeNode
import dev.minios.ocremote.ui.screens.sessions.components.SessionRow
import dev.minios.ocremote.ui.screens.sessions.components.TreeNode
import dev.minios.ocremote.ui.screens.sessions.components.isAmoledTheme
import dev.minios.ocremote.ui.screens.sessions.components.OpenProjectDialog
import dev.minios.ocremote.ui.theme.AlphaTokens
import dev.minios.ocremote.ui.theme.ShapeTokens
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    viewModel: SessionListViewModel,
    onNavigateToChat: (sessionId: String, openTerminal: Boolean) -> Unit,
    onNavigateToNewChat: (directory: String) -> Unit,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isAmoled = isAmoledTheme()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Dialog states
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameSessionId by remember { mutableStateOf("") }
    var renameText by remember { mutableStateOf("") }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var deleteSessionId by remember { mutableStateOf("") }
    var deleteSessionTitle by remember { mutableStateOf("") }
    var showOpenProject by remember { mutableStateOf(false) }
    var showBaseDirDialog by remember { mutableStateOf(false) }

    val pagerState = rememberPagerState(pageCount = { 2 })
    val currentViewMode by viewModel.viewMode.collectAsStateWithLifecycle()

    // Preload MCP servers on screen entry — no loading delay when user swipes
    // to the MCP tab. Also caches error handling for the entire lifetime.
    var mcpLoadedOnce by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.loadMcpServers()
        mcpLoadedOnce = true
    }

    LaunchedEffect(Unit) {
        viewModel.mcpError.collect { errorMessage ->
            snackbarHostState.showSnackbar(errorMessage)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column(modifier = Modifier.clickable { showBaseDirDialog = true }) {
                        Text(
                            text = uiState.serverName.ifEmpty { stringResource(R.string.sessions_title) },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        AnimatedVisibility(visible = uiState.baseDirectory != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = (uiState.baseDirectory ?: "").replace('\\', '/').trimEnd('/'),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = stringResource(R.string.a11y_icon_navigate_forward),
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    // View mode toggle: Folders ↔ Recent
                    TextButton(onClick = { viewModel.toggleViewMode() }) {
                        Text(
                            text = if (currentViewMode == SessionViewMode.FOLDER)
                                stringResource(R.string.sessions_view_folders)
                            else
                                stringResource(R.string.sessions_view_recent),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            AnimatedVisibility(visible = pagerState.currentPage == 0) {
                FloatingActionButton(
                    onClick = { showOpenProject = true },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
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
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = pagerState.currentPage == 0,
                    onClick = {
                        scope.launch { pagerState.scrollToPage(0) }
                    },
                    icon = {
                        Icon(
                            Icons.AutoMirrored.Filled.List,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    label = {
                        Text(
                            stringResource(R.string.sessions_title),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 1,
                    onClick = {
                        scope.launch { pagerState.scrollToPage(1) }
                    },
                    icon = {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    label = {
                        Text(
                            stringResource(R.string.settings_title),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                )
            }
        }
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.padding(padding),
            flingBehavior = PagerDefaults.flingBehavior(
                state = pagerState,
                pagerSnapDistance = PagerSnapDistance.atMost(0)
            ),
        ) { page ->
            when (page) {
                0 -> {
                    PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.refreshSessions() },
            modifier = Modifier.fillMaxSize()
        ) {
            // Search bar — always visible, independent of list state
            var searchInput by rememberSaveable { mutableStateOf("") }
            val searchJob = remember { mutableStateOf<Job?>(null) }

            Column(modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
            ) {
            AnimatedVisibility(visible = pagerState.currentPage == 0) {
                    OutlinedTextField(
                    value = searchInput,
                    onValueChange = { newQuery ->
                        searchInput = newQuery
                        searchJob.value?.cancel()
                        searchJob.value = scope.launch {
                            delay(300)
                            viewModel.setSearchQuery(newQuery)
                            viewModel.loadSessions()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = stringResource(R.string.a11y_icon_search))
                    },
                    trailingIcon = {
                        if (searchInput.isNotEmpty()) {
                            IconButton(onClick = {
                                searchInput = ""
                                viewModel.clearSearchQuery()
                                viewModel.loadSessions()
                            }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.sessions_clear_search))
                            }
                        }
                    },
                    placeholder = { Text(stringResource(R.string.search_sessions)) },
                    singleLine = true,
                    colors = if (isAmoled) {
                        amoledOutlinedTextFieldColors()
                    } else {
                        OutlinedTextFieldDefaults.colors()
                    }
                    )
                }

                when {
                    uiState.isLoading && uiState.treeNodes.isEmpty() && uiState.searchQuery.isNullOrBlank() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            PulsingDotsIndicator(dotSize = 12.dp, dotSpacing = 8.dp)
                        }
                    }
                    uiState.error != null && uiState.treeNodes.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                modifier = Modifier.padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = stringResource(R.string.a11y_icon_warning),
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
                    val listState = rememberLazyListState()
                    val shouldLoadMore by remember {
                        derivedStateOf {
                            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                            val totalItems = listState.layoutInfo.totalItemsCount
                            lastVisibleIndex >= totalItems - 3 && totalItems > 0
                        }
                    }

                    LaunchedEffect(shouldLoadMore) {
                        if (shouldLoadMore && viewModel.hasMorePages && !viewModel.isLoadingMore) {
                            viewModel.loadMore()
                        }
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 4.dp)
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
                                        onNewSession = { directory ->
                                            onNavigateToNewChat(directory)
                                        },
                                    )
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(
                                            alpha = AlphaTokens.FAINT
                                        )
                                    )
                                }
                                is TreeNode.Session -> {
                                    val isRecentMode = currentViewMode == SessionViewMode.RECENT
                                    SessionRow(
                                        item = node.session,
                                        showDirectory = isRecentMode,
                                        onClick = {
                                            onNavigateToChat(node.id, false)
                                        },
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
                                            alpha = AlphaTokens.FAINT
                                        )
                                    )
                                }
                            }
                        }

                        // Load more indicator at the bottom
                        if (viewModel.isLoadingMore) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp
                                    )
                                }
                            }
                        }
                    }
                }
            }
            }
        }
                }
                1 -> {
                    ServerSettingsContent(
                        mcpServers = viewModel.mcpServers.collectAsStateWithLifecycle().value,
                        mcpLoading = viewModel.mcpLoading.collectAsStateWithLifecycle().value,
                        mcpInitialLoading = viewModel.mcpInitialLoading.collectAsStateWithLifecycle().value,
                        onToggleMcp = viewModel::toggleMcpServer,
                    )
                }
            }
        }
    }

    // Open Project dialog
    if (showOpenProject) {
        OpenProjectDialog(
            viewModel = viewModel,
            projects = emptyList(),
            initialDirectory = uiState.prefillDirectory,
            onSelect = { directory ->
                showOpenProject = false
                onNavigateToNewChat(directory)
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

    // Rename dialog
    if (showRenameDialog) {
        val renameParams = amoledDialogParams()
        BasicAlertDialog(
            onDismissRequest = { showRenameDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.92f),
                color = renameParams.containerColor,
                tonalElevation = renameParams.tonalElevation,
                border = renameParams.border,
                shape = renameParams.shape,
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = stringResource(R.string.session_rename),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        label = { Text(stringResource(R.string.session_rename_title)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(16.dp))
                    DialogButtons(
                        buttons = listOf(
                            Triple(stringResource(R.string.cancel), DialogButtonRole.Secondary) { showRenameDialog = false },
                            Triple(stringResource(R.string.session_rename_button), DialogButtonRole.Primary) {
                                viewModel.renameSession(renameSessionId, renameText)
                                showRenameDialog = false
                            },
                        )
                    )
                }
            }
        }
    }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        val deleteParams = amoledDialogParams()
        BasicAlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.92f),
                color = deleteParams.containerColor,
                tonalElevation = deleteParams.tonalElevation,
                border = deleteParams.border,
                shape = deleteParams.shape,
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = stringResource(R.string.session_delete),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.session_delete_confirm, deleteSessionTitle),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(16.dp))
                    DialogButtons(
                        buttons = listOf(
                            Triple(stringResource(R.string.cancel), DialogButtonRole.Secondary) { showDeleteDialog = false },
                            Triple(stringResource(R.string.delete), DialogButtonRole.Danger) {
                                viewModel.deleteSession(deleteSessionId)
                                showDeleteDialog = false
                            },
                        )
                    )
                }
            }
        }
    }
}
