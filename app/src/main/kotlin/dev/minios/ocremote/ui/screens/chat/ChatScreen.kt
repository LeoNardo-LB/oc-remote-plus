package dev.minios.ocremote.ui.screens.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeNestedScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.buildAnnotatedString

import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.times
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.minios.ocremote.domain.model.*
import dev.minios.ocremote.domain.model.AgentInfo
import dev.minios.ocremote.domain.model.CommandInfo
import dev.minios.ocremote.domain.model.ModelCatalog
import dev.minios.ocremote.domain.model.ProviderCatalog
import dev.minios.ocremote.MainActivity
import dev.minios.ocremote.ui.theme.AppMotion
import dev.minios.ocremote.ui.theme.CodeTypography
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

import android.net.Uri
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory

import android.os.Build
import android.util.Base64
import android.util.Log
import android.view.MotionEvent
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.minios.ocremote.BuildConfig
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import dev.minios.ocremote.R
import dev.minios.ocremote.ui.components.ProviderIcon
import dev.minios.ocremote.ui.screens.chat.util.isAmoledTheme
import dev.minios.ocremote.ui.screens.chat.util.toolOutputContainerColor
import dev.minios.ocremote.ui.screens.chat.util.agentColor
import dev.minios.ocremote.ui.screens.chat.util.agentColorCycle
import dev.minios.ocremote.ui.theme.QueuedBadgeColor
import dev.minios.ocremote.ui.theme.QueuedBadgeTextColor
import dev.minios.ocremote.ui.screens.chat.util.formatTokenCount
import dev.minios.ocremote.ui.screens.chat.util.formatAssistantErrorMessage
import dev.minios.ocremote.ui.screens.chat.util.formatDuration
import dev.minios.ocremote.ui.screens.chat.util.resolveUserCommandLabel
import dev.minios.ocremote.ui.screens.chat.util.ScrollPositionChecker
import dev.minios.ocremote.ui.screens.chat.util.performHaptic
import dev.minios.ocremote.ui.screens.chat.util.codeHorizontalScroll
import dev.minios.ocremote.ui.screens.chat.util.LocalChatFontSize
import dev.minios.ocremote.ui.screens.chat.util.LocalCodeWordWrap
import dev.minios.ocremote.ui.screens.chat.util.LocalCompactMessages
import dev.minios.ocremote.ui.screens.chat.util.LocalCollapseTools
import dev.minios.ocremote.ui.screens.chat.util.LocalExpandReasoning
import dev.minios.ocremote.ui.screens.chat.util.LocalHapticFeedbackEnabled
import dev.minios.ocremote.ui.screens.chat.util.LocalImageSaveRequest
import dev.minios.ocremote.ui.screens.chat.util.LocalToolExpandedStates
import dev.minios.ocremote.ui.screens.chat.util.LocalOnToggleToolExpanded
import dev.minios.ocremote.ui.screens.chat.util.LocalToolCardResolver
import dev.minios.ocremote.ui.screens.chat.util.ImageAttachment
import dev.minios.ocremote.ui.screens.chat.util.PreparedAttachment
import dev.minios.ocremote.ui.screens.chat.util.decodeDataUrlBytes
import dev.minios.ocremote.ui.screens.chat.util.decodePartFileBytes
import dev.minios.ocremote.ui.screens.chat.util.imageThumbnailModel
import dev.minios.ocremote.ui.screens.chat.util.estimateVisionTokens
import dev.minios.ocremote.ui.screens.chat.markdown.MarkdownContent
import dev.minios.ocremote.ui.screens.chat.markdown.SimpleMarkdownTable
import dev.minios.ocremote.ui.screens.chat.markdown.looksLikeHtmlPayload
import dev.minios.ocremote.ui.screens.chat.markdown.normalizeHtmlForEmbeddedPreview
import dev.minios.ocremote.ui.screens.chat.tools.ToolCallCard
import dev.minios.ocremote.ui.screens.chat.tools.cards.BashToolCard
import dev.minios.ocremote.ui.screens.chat.tools.cards.EditToolCard
import dev.minios.ocremote.ui.screens.chat.tools.cards.PatchCard
import dev.minios.ocremote.ui.screens.chat.tools.cards.ReadToolCard
import dev.minios.ocremote.ui.screens.chat.tools.cards.SearchToolCard
import dev.minios.ocremote.ui.screens.chat.tools.cards.TaskToolCard
import dev.minios.ocremote.ui.screens.chat.tools.cards.TodoListCard
import dev.minios.ocremote.ui.screens.chat.tools.cards.WriteToolCard
import dev.minios.ocremote.ui.screens.chat.dialog.ModelPickerDialog
import dev.minios.ocremote.ui.screens.chat.dialog.ImageThumbnailRow
import dev.minios.ocremote.ui.screens.chat.dialog.ImagePreviewDialog
import dev.minios.ocremote.ui.screens.chat.dialog.QuestionCard
import dev.minios.ocremote.ui.screens.chat.dialog.PermissionCard
import dev.minios.ocremote.ui.screens.chat.input.ChatInputBar
import dev.minios.ocremote.ui.screens.chat.input.ChatInputMode
import dev.minios.ocremote.ui.screens.chat.input.SlashCommand
import dev.minios.ocremote.ui.screens.chat.input.rememberAttachmentHandler
import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.ui.screens.chat.input.buildPromptParts
import dev.minios.ocremote.ui.screens.chat.components.MessageCard
import dev.minios.ocremote.ui.screens.chat.components.MessageCardRole
import dev.minios.ocremote.ui.screens.chat.components.ChatEmptyState
import dev.minios.ocremote.ui.screens.chat.components.ChatErrorState
import dev.minios.ocremote.ui.screens.chat.components.ChatMessageList
import dev.minios.ocremote.ui.screens.chat.components.ChatTopBar
import dev.minios.ocremote.ui.screens.chat.components.ErrorPayloadContent
import dev.minios.ocremote.ui.components.indicators.PulsingDotsIndicator
import dev.minios.ocremote.ui.screens.chat.components.RevertBanner
import dev.minios.ocremote.ui.screens.chat.terminal.ChatTerminalView
import dev.minios.ocremote.ui.screens.chat.dialog.RenameSessionDialog
import dev.minios.ocremote.ui.screens.chat.dialog.SendConfirmDialog
import dev.minios.ocremote.ui.theme.AlphaTokens


/**
 * Chat Screen - conversation view with native markdown rendering.
 * Shows messages with streaming text rendered via mikepenz markdown renderer.
 */

private const val TAG_SCROLL = "ChatScroll"

// jumpToBottom / animateScrollToBottom removed — reverseLayout=true anchors at bottom natively.




@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    onNavigateBack: () -> Unit,
    onNavigateToSession: (sessionId: String) -> Unit = {},
    onNavigateToChildSession: (String) -> Unit = {},
    onOpenInWebView: () -> Unit = {},
    initialSharedImages: List<Uri> = emptyList(),
    onSharedImagesConsumed: () -> Unit = {},
    startInTerminalMode: Boolean = false,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val messageState by viewModel.messageListState.collectAsStateWithLifecycle()
    val sessionMeta by viewModel.sessionMetaState.collectAsStateWithLifecycle()
    val interaction by viewModel.interactionState.collectAsStateWithLifecycle()
    val tokenStats by viewModel.tokenStatsState.collectAsStateWithLifecycle()
    val modelConfig by viewModel.modelConfigState.collectAsStateWithLifecycle()
    val restoredDraft by viewModel.restoredDraftState.collectAsStateWithLifecycle()
    val draftText by viewModel.draftText.collectAsStateWithLifecycle()
    val draftAttachmentUris by viewModel.draftAttachmentUris.collectAsStateWithLifecycle()
    var inputText by remember { mutableStateOf(TextFieldValue("")) }
    // Sync inputText once from draft on first composition
    var draftTextInitialized by remember { mutableStateOf(false) }
    if (!draftTextInitialized && draftText.isNotEmpty()) {
        inputText = TextFieldValue(draftText, TextRange(draftText.length))
        draftTextInitialized = true
    } else if (!draftTextInitialized) {
        draftTextInitialized = true
    }
    // Listen for revert events that should restore text to the input field
    LaunchedEffect(Unit) {
        viewModel.revertedDraftEvent.collect { payload ->
            inputText = TextFieldValue(payload.text, TextRange(payload.text.length))
        }
    }
    val listState = rememberLazyListState()

    // Detect if user is near the bottom with a tolerance buffer.
    // Using !canScrollBackward is too strict — it can transiently become true during
    // fling deceleration or layout recalculation (e.g., Markdown rendering completes,
    // PullToRefreshBox resizes), triggering spurious snapToBottom() jumps.
    //
    // Instead, check that the first visible item is item 0 (bottom-most in reverseLayout)
    // AND its scroll offset is within a 64px tolerance. This prevents jumps when the user
    // is merely near the bottom due to momentum or layout changes.
    val isAtBottom by remember {
        derivedStateOf {
            ScrollPositionChecker.isAtBottom(
                firstVisibleIndex = listState.firstVisibleItemIndex,
                firstVisibleOffset = listState.firstVisibleItemScrollOffset,
                totalItemsCount = listState.layoutInfo.totalItemsCount
            )
        }
    }

    // shouldFollow removed — reverseLayout=true handles auto-follow natively.




    // Restore scroll position when returning from sub-session navigation.
    // Saves raw LazyColumn index, no fragile index arithmetic needed.
    LaunchedEffect(viewModel.scrollRestoreVersion) {
        val version = viewModel.scrollRestoreVersion
        Log.w("CHAT_DEBUG", "[scrollRestore] LaunchedEffect fired: version=$version msgs=${messageState.messages.size}")
        if (version > 0) {
            // Wait for messages to be loaded — restoration before load is useless
            var retries = 0
            while (messageState.messages.isEmpty() && retries < 20) {
                delay(50L)
                retries++
            }
            // Restore using raw LazyColumn index (saved at navigation time)
            if (messageState.messages.isNotEmpty()) {
                val savedIdx = viewModel.savedLazyIndex
                val totalItems = listState.layoutInfo.totalItemsCount
                // Clamp to valid range (items may have been added/removed during navigation)
                val targetIdx = savedIdx.coerceIn(0, (totalItems - 1).coerceAtLeast(0))
                listState.scrollToItem(targetIdx, viewModel.savedScrollOffset)
                Log.w("CHAT_DEBUG", "[scrollRestore] restored to index=$targetIdx (saved=$savedIdx, total=$totalItems)")
            } else {
                Log.w("CHAT_DEBUG", "[scrollRestore] messages still empty after wait, skipping")
            }
        } else {
            // First entry (scrollRestoreVersion == 0): reverseLayout=true anchors at bottom,
            // so no explicit scroll is needed.
            Log.w("CHAT_DEBUG", "[firstEntry] reverseLayout=true anchors at bottom, no scroll needed")
        }
    }

    // Wrapper that saves scroll position before navigating to a sub-session.
    // Saves raw LazyColumn index + offset for direct restoration (no index arithmetic).
    val navigateToChildSessionWithSave: (String) -> Unit = { childSessionId ->
        viewModel.saveScrollPosition(
            lazyIndex = listState.firstVisibleItemIndex,
            offset = listState.firstVisibleItemScrollOffset
        )
        onNavigateToChildSession(childSessionId)
    }

    var showModelPicker by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var isTerminalMode by rememberSaveable { mutableStateOf(startInTerminalMode) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val isAmoled = isAmoledTheme()
    val keyboardController = LocalSoftwareKeyboardController.current
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    val view = LocalView.current
    val density = LocalDensity.current
    // isAtBottomBeforeIme removed — reverseLayout=true handles IME natively.

    // Dismiss keyboard only on genuine user scroll (isScrollInProgress),
    // not on programmatic scrolls or layout changes.
    var lastScrollIndex by remember { mutableIntStateOf(listState.firstVisibleItemIndex) }
    LaunchedEffect(Unit) {
        snapshotFlow { listState.isScrollInProgress to listState.firstVisibleItemIndex }
            .collect { (scrolling, index) ->
                if (scrolling && index != lastScrollIndex) {
                    keyboardController?.hide()
                    lastScrollIndex = index
                }
            }
    }

    // IME scroll LaunchedEffect removed — reverseLayout=true anchors at bottom,
    // keyboard push content naturally without explicit scroll.

    // Scroll anchor lock: prevent viewport drift during SSE streaming.
    //
    // reverseLayout=true does NOT anchor scroll position when the bottom item
    // (streaming message) grows in height — the growth pushes upper content
    // downward, perceived as "being pulled toward the bottom". This effect
    // locks the visual top item's offset; when non-user layout changes shift
    // it, scrollBy compensates immediately to keep the viewport stable.
    LaunchedEffect(Unit) {
        var anchorKey: Any? = null
        var anchorOffset = 0

        snapshotFlow {
            val info = listState.layoutInfo
            val top = info.visibleItemsInfo.lastOrNull()
            Triple(top?.key, top?.offset ?: 0, listState.isScrollInProgress)
        }.collect { (key, offset, scrolling) ->
            if (key == null) return@collect
            if (scrolling || isAtBottom) {
                anchorKey = key
                anchorOffset = offset
            } else if (key == anchorKey && offset != anchorOffset) {
                listState.scroll { scrollBy((offset - anchorOffset).toFloat()) }
            } else if (key != anchorKey) {
                anchorKey = key
                anchorOffset = offset
            }
        }
    }

    // @ file mention state
    val fileSearchResults by viewModel.fileSearchResults.collectAsStateWithLifecycle()
    val confirmedFilePaths by viewModel.confirmedFilePaths.collectAsStateWithLifecycle()

    // Settings used directly in ChatScreen
    val confirmBeforeSend by viewModel.confirmBeforeSend.collectAsStateWithLifecycle()
    val hapticEnabled by viewModel.hapticFeedback.collectAsStateWithLifecycle()
    val keepScreenOn by viewModel.keepScreenOn.collectAsStateWithLifecycle()
    val compressImageAttachments by viewModel.compressImageAttachments.collectAsStateWithLifecycle()
    val imageAttachmentMaxLongSide by viewModel.imageAttachmentMaxLongSide.collectAsStateWithLifecycle()
    val imageAttachmentWebpQuality by viewModel.imageAttachmentWebpQuality.collectAsStateWithLifecycle()
    var showSendConfirmDialog by remember { mutableStateOf(false) }
    // Pending send action: stored so the confirm dialog can trigger it
    var pendingSendAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var inputMode by rememberSaveable { mutableStateOf(ChatInputMode.NORMAL.name) }
    val isShellMode = inputMode == ChatInputMode.SHELL.name

    // Keep screen on while on chat screen (if enabled in settings)
    DisposableEffect(keepScreenOn) {
        val window = (context as? android.app.Activity)?.window
        if (keepScreenOn) {
            window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Attachment handler (image picker, SAF export, image save, draft restore)
    val attachmentHandler = rememberAttachmentHandler(
        draftAttachmentUris = draftAttachmentUris,
        compressImages = compressImageAttachments,
        imageMaxLongSide = imageAttachmentMaxLongSide,
        imageWebpQuality = imageAttachmentWebpQuality,
        initialSharedImages = initialSharedImages,
        onSharedImagesConsumed = onSharedImagesConsumed,
        onAddDraftAttachment = { viewModel.addDraftAttachment(it) },
        onRemoveDraftAttachment = { viewModel.removeDraftAttachment(it) },
        onExportSession = { ctx, uri, callback -> viewModel.exportSession(ctx, uri, callback) },
        onShowSnackbar = { msg -> snackbarHostState.showSnackbar(msg) },
    )
    val attachments = attachmentHandler.attachments

    // Show errors as snackbar when messages are already loaded
    LaunchedEffect(interaction.error) {
        val error = interaction.error
        if (error != null && messageState.messages.isNotEmpty()) {
            snackbarHostState.showSnackbar(
                message = error,
                duration = SnackbarDuration.Short
            )
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current

    // Sync session status when entering a session (REST fallback for missed SSE events)
    LaunchedEffect(viewModel.sessionId) {
        if (viewModel.sessionId.isNotBlank()) {
            viewModel.syncSessionStatus()
        }
    }

    // Refresh session when returning from background (lock screen / app switch)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && viewModel.sessionId.isNotBlank()) {
                viewModel.refreshIfNeeded()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }



    // CompositionLocalProvider collects settings flows here (sunk from ChatScreen level).
    // ChatScreen itself does NOT read these settings, so setting changes don't trigger
    // ChatScreen recomposition — only this wrapper recomposes.
    ChatSettingsProvider(viewModel = viewModel) {
    CompositionLocalProvider(
        LocalHapticFeedbackEnabled provides hapticEnabled,
        LocalImageSaveRequest provides attachmentHandler.requestSaveImage,
        LocalToolExpandedStates provides messageState.toolExpandedStates,
        LocalOnToggleToolExpanded provides { toolId, defaultExpanded -> viewModel.toggleToolExpanded(toolId, defaultExpanded) },
        LocalToolCardResolver provides viewModel.toolCardResolver,
    ) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (!isTerminalMode) {
                Column {
                    ChatTopBar(
                        sessionTitle = sessionMeta.sessionTitle,
                        messageCount = messageState.messageCount,
                        totalInputTokens = tokenStats.totalInputTokens,
                        totalOutputTokens = tokenStats.totalOutputTokens,
                        totalReasoningTokens = tokenStats.totalReasoningTokens,
                        totalCacheReadTokens = tokenStats.totalCacheReadTokens,
                        totalCacheWriteTokens = tokenStats.totalCacheWriteTokens,
                        totalCost = tokenStats.totalCost,
                        sessionParentId = sessionMeta.sessionParentId,
                        shareUrl = sessionMeta.shareUrl,
                        contextWindow = modelConfig.contextWindow,
                        lastContextTokens = tokenStats.lastContextTokens,
                        onNavigateBack = onNavigateBack,
                        onTerminalMode = { isTerminalMode = true },
                        onOpenInWebView = onOpenInWebView,
                        onNewSession = {
                            onNavigateToSession("")  // Empty sessionId = lazy creation
                        },
                        onForkSession = {
                            viewModel.forkSession { session ->
                                if (session != null) {
                                    onNavigateToSession(session.id)
                                } else {
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar(context.getString(R.string.chat_fork_failed))
                                    }
                                }
                            }
                        },
                        onCompactSession = {
                            viewModel.compactSession { ok ->
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(
                                        if (ok) context.getString(R.string.chat_session_compacted) else context.getString(R.string.chat_session_compact_failed)
                                    )
                                }
                            }
                        },
                        onReviewChanges = {
                            viewModel.executeCommand("review") { ok ->
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(
                                        if (ok) context.getString(R.string.chat_command_executed, "review") else context.getString(R.string.chat_command_failed, "review")
                                    )
                                }
                            }
                        },
                        onShare = {
                            viewModel.shareSession { url ->
                                coroutineScope.launch {
                                    if (url != null) {
                                        clipboardManager.setText(AnnotatedString(url))
                                        snackbarHostState.showSnackbar(context.getString(R.string.chat_share_url_copied))
                                    } else {
                                        snackbarHostState.showSnackbar(context.getString(R.string.chat_share_failed))
                                    }
                                }
                            }
                        },
                        onUnshare = {
                            viewModel.unshareSession { ok ->
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(
                                        if (ok) context.getString(R.string.chat_session_unshared) else context.getString(R.string.chat_session_unshare_failed)
                                    )
                                }
                            }
                        },
                        onRename = { showRenameDialog = true },
                        onExport = {
                            val slug = sessionMeta.sessionTitle
                                .take(30)
                                .replace(Regex("[^a-zA-Z0-9_-]"), "_")
                                .ifBlank { "session" }
                            attachmentHandler.launchExport("$slug.json")
                        },

                    )
                    // Indeterminate progress bar under the top bar when busy
                    val isBusy = sessionMeta.sessionStatus is SessionStatus.Busy || sessionMeta.sessionStatus is SessionStatus.Retry
                    AnimatedVisibility(
                        visible = isBusy,
                        enter = fadeIn(animationSpec = tween(AppMotion.SHORT)),
                        exit = fadeOut(animationSpec = tween(AppMotion.SHORT))
                    ) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        bottomBar = {
            if (sessionMeta.sessionParentId == null && !isTerminalMode &&
                (messageState.messages.isNotEmpty() || !interaction.isLoading) && interaction.error == null
            ) {
                val modelLabel = if (modelConfig.selectedModelId != null && modelConfig.providers.isNotEmpty()) {
                    val provider = modelConfig.providers.find { it.id == modelConfig.selectedProviderId }
                    val model = provider?.models?.get(modelConfig.selectedModelId)
                    model?.name ?: modelConfig.selectedModelId ?: ""
                } else ""
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .navigationBarsPadding()
                        .imePadding()
                ) {
                    ChatInputBar(
                        textFieldValue = inputText,
                        onTextFieldValueChange = { newValue ->
                            val wasEmpty = inputText.text.isEmpty()
                            val shouldAutoShell = !isShellMode && newValue.text.startsWith("!")
                            val normalizedValue = if (shouldAutoShell) {
                                val stripped = newValue.text.drop(1).trimStart()
                                val newCursor = (newValue.selection.start - 1).coerceAtLeast(0)
                                TextFieldValue(
                                    text = stripped,
                                    selection = TextRange(newCursor.coerceAtMost(stripped.length))
                                )
                            } else {
                                newValue
                            }

                            if (shouldAutoShell) {
                                inputMode = ChatInputMode.SHELL.name
                            }

                            inputText = normalizedValue
                            viewModel.updateDraftText(normalizedValue.text)

                            // reverseLayout=true anchors at bottom; no explicit scroll needed when typing.

                            if (isShellMode || shouldAutoShell) {
                                viewModel.clearFileSearch()
                                return@ChatInputBar
                            }
                            // Detect @query before cursor for file mention
                            val cursorPos = normalizedValue.selection.start
                            val textBefore = normalizedValue.text.substring(0, cursorPos)
                            val atMatch = Regex("@(\\S*)$").find(textBefore)
                            if (atMatch != null) {
                                val query = atMatch.groupValues[1]
                                viewModel.searchFilesForMention(query)
                            } else {
                                viewModel.clearFileSearch()
                            }
                        },
                        onSend = {
                            val doSend = doSend@{
                                if (hapticEnabled) {
                                    @Suppress("DEPRECATION")
                                    val flags = android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or
                                            android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                                        view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM, flags)
                                    } else {
                                        view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK, flags)
                                    }
                                }
                                val rawText = inputText.text
                                val shellCommand = when {
                                    isShellMode -> rawText.trim()
                                    rawText.startsWith("!") -> rawText.drop(1).trimStart()
                                    else -> null
                                }
                                if (shellCommand != null) {
                                    if (shellCommand.isBlank()) {
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar(context.getString(R.string.chat_shell_empty))
                                        }
                                        return@doSend
                                    }
                                    if (attachments.isNotEmpty()) {
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar(context.getString(R.string.chat_shell_attachments_unsupported))
                                        }
                                        return@doSend
                                    }
                                    viewModel.runShellCommand(shellCommand) { ok ->
                                        if (!ok) {
                                            coroutineScope.launch {
                                                snackbarHostState.showSnackbar(context.getString(R.string.chat_shell_failed))
                                            }
                                        }
                                    }
                                    inputText = TextFieldValue("")
                                    if (isShellMode) {
                                        inputMode = ChatInputMode.NORMAL.name
                                    }
                                    viewModel.clearConfirmedPaths()
                                    viewModel.clearFileSearch()
                                    viewModel.clearDraft()
                                    return@doSend
                                }
                                // Detect slash commands (e.g., /skillname arguments)
                                if (rawText.startsWith("/") && !rawText.startsWith("/ ") && confirmedFilePaths.isEmpty()) {
                                    val parts = rawText.trim().split("\\s+".toRegex(), 2)
                                    val commandName = parts[0].removePrefix("/")
                                    val commandArgs = parts.getOrElse(1) { "" }
                                    if (commandName.isNotBlank()) {
                                        viewModel.executeCommand(commandName, commandArgs) { ok ->
                                            coroutineScope.launch {
                                                snackbarHostState.showSnackbar(
                                                    if (ok) context.getString(R.string.chat_command_executed, commandName)
                                                    else context.getString(R.string.chat_command_failed, commandName)
                                                )
                                            }
                                        }
                                        inputText = TextFieldValue("")
                                        if (isShellMode) {
                                            inputMode = ChatInputMode.NORMAL.name
                                        }
                                        viewModel.clearConfirmedPaths()
                                        viewModel.clearFileSearch()
                                        viewModel.clearDraft()
                                        return@doSend
                                    }
                                }
                                // Build prompt parts: split text around confirmed @file mentions
                                val allParts = buildPromptParts(rawText, confirmedFilePaths, viewModel.getSessionDirectory())
                                // Add image attachments
                                val attachmentParts = attachments.map { att ->
                                    PromptPart(
                                        type = "file",
                                        mime = att.mime,
                                        url = att.dataUrl,
                                        filename = att.filename
                                    )
                                }
                                viewModel.sendMessage(allParts, attachmentParts)
                                inputText = TextFieldValue("")
                                attachmentHandler.clearAttachments()
                                // Scroll to bottom after sending to follow the response
                                coroutineScope.launch {
                                    val currentCount = listState.layoutInfo.totalItemsCount
                                    Log.w("CHAT_DEBUG", "[afterSend] waiting: currentCount=$currentCount canBackward=${listState.canScrollBackward}")
                                    snapshotFlow { listState.layoutInfo.totalItemsCount }
                                        .first { it > currentCount }
                                    Log.w("CHAT_DEBUG", "[afterSend] new items detected: count=${listState.layoutInfo.totalItemsCount} canBackward=${listState.canScrollBackward}")
                                    listState.smoothScrollToBottom()
                                    Log.w("CHAT_DEBUG", "[afterSend] DONE: canBackward=${listState.canScrollBackward} first=${listState.firstVisibleItemIndex} offset=${listState.firstVisibleItemScrollOffset}")
                                }
                                viewModel.clearConfirmedPaths()
                                viewModel.clearFileSearch()
                                viewModel.clearDraft()
                            }
                            if (confirmBeforeSend) {
                                pendingSendAction = doSend
                                showSendConfirmDialog = true
                            } else {
                                doSend()
                            }
                        },
                        inputMode = if (isShellMode) ChatInputMode.SHELL else ChatInputMode.NORMAL,
                        onInputModeChange = {
                            inputMode = it.name
                            if (it == ChatInputMode.SHELL) {
                                viewModel.clearFileSearch()
                            }
                        },
                        isSending = interaction.isSending,
                        isBusy = sessionMeta.sessionStatus is SessionStatus.Busy || sessionMeta.sessionStatus is SessionStatus.Retry,
                        messages = messageState.messages,
                        attachments = attachments,
                        onAttach = { attachmentHandler.pickImages() },
                        onRemoveAttachment = { index ->
                            if (index in attachments.indices) {
                                attachmentHandler.removeAttachment(index)
                                viewModel.removeDraftAttachment(index)
                            }
                        },
                        onSaveAttachment = { bytes, mime, filename ->
                            attachmentHandler.requestSaveImage(bytes, mime, filename)
                        },
                        modelLabel = modelLabel,
                        selectedProviderId = modelConfig.selectedProviderId,
                        onModelClick = { showModelPicker = true },
                        agents = modelConfig.agents,
                        selectedAgent = modelConfig.selectedAgent,
                        onAgentSelect = { viewModel.selectAgent(it) },
                        variantNames = modelConfig.variantNames,
                        selectedVariant = modelConfig.selectedVariant,
                        onCycleVariant = { viewModel.cycleVariant() },
                        commands = modelConfig.commands,
                        fileSearchResults = fileSearchResults,
                        confirmedFilePaths = confirmedFilePaths,
                        onFileSelected = { path ->
                            // Replace @query with @path in text
                            val cursorPos = inputText.selection.start
                            val textBefore = inputText.text.substring(0, cursorPos)
                            val atMatch = Regex("@(\\S*)$").find(textBefore)
                            if (atMatch != null) {
                                val matchStart = atMatch.range.first
                                val replacement = "@$path "
                                val newText = inputText.text.substring(0, matchStart) + replacement +
                                        inputText.text.substring(cursorPos)
                                val newCursor = matchStart + replacement.length
                                inputText = TextFieldValue(
                                    text = newText,
                                    selection = TextRange(newCursor)
                                )
                            }
                            viewModel.confirmFilePath(path)
                            viewModel.clearFileSearch()
                        },
                        onSlashCommand = { cmd ->
                            when (cmd.name) {
                                "new" -> {
                                    onNavigateToSession("")  // Empty sessionId = lazy creation
                                }
                                "compact" -> {
                                    viewModel.compactSession { ok ->
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar(
                                                if (ok) context.getString(R.string.chat_session_compacted) else context.getString(R.string.chat_session_compact_failed)
                                            )
                                        }
                                    }
                                }
                                "fork" -> {
                                    viewModel.forkSession { session ->
                                        if (session != null) {
                                            onNavigateToSession(session.id)
                                        } else {
                                            coroutineScope.launch {
                                                snackbarHostState.showSnackbar(context.getString(R.string.chat_fork_failed))
                                            }
                                        }
                                    }
                                }
                                "share" -> {
                                    viewModel.shareSession { url ->
                                        coroutineScope.launch {
                                            if (url != null) {
                                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(url))
                                                snackbarHostState.showSnackbar(context.getString(R.string.chat_share_url_copied))
                                            } else {
                                                snackbarHostState.showSnackbar(context.getString(R.string.chat_share_failed))
                                            }
                                        }
                                    }
                                }
                                "unshare" -> {
                                    viewModel.unshareSession { ok ->
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar(
                                                if (ok) context.getString(R.string.chat_session_unshared) else context.getString(R.string.chat_session_unshare_failed)
                                            )
                                        }
                                    }
                                }
                                "undo" -> {
                                    viewModel.undoMessage { ok ->
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar(
                                                if (ok) context.getString(R.string.chat_message_undone) else context.getString(R.string.chat_message_undo_failed)
                                            )
                                        }
                                    }
                                }
                                "redo" -> {
                                    viewModel.redoMessage { ok ->
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar(
                                                if (ok) context.getString(R.string.chat_message_redone) else context.getString(R.string.chat_message_redo_failed)
                                            )
                                        }
                                    }
                                }
                                "rename" -> {
                                    showRenameDialog = true
                                }
                                "shell" -> {
                                    inputMode = ChatInputMode.SHELL.name
                                }
                                "review" -> {
                                    viewModel.executeCommand("review") { ok ->
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar(
                                                if (ok) context.getString(R.string.chat_command_executed, "review") else context.getString(R.string.chat_command_failed, "review")
                                            )
                                        }
                                    }
                                }
                                else -> {
                                    viewModel.executeCommand(cmd.name) { ok ->
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar(
                                                if (ok) context.getString(R.string.chat_command_executed, cmd.name) else context.getString(R.string.chat_command_failed, cmd.name)
                                            )
            }
        }
    }
}


                        },
                        onStop = { viewModel.abortSession() },
                        restoredDraft = restoredDraft,
                        onConsumeRestoredDraft = { viewModel.consumeRestoredDraft() }
                    )
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
        ) {
            when {
                isTerminalMode -> {
                    ChatTerminalView(
                        viewModel = viewModel,
                        isTerminalMode = isTerminalMode,
                        onTerminalModeChanged = { isTerminalMode = it },
                        startInTerminalMode = startInTerminalMode,
                        onNavigateBack = onNavigateBack,
                        snackbarHostState = snackbarHostState,
                    )
                }
                interaction.isLoading && messageState.messages.isEmpty() -> {
                    PulsingDotsIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                interaction.error != null && messageState.messages.isEmpty() -> {
                    ChatErrorState(
                        modifier = Modifier.align(Alignment.Center),
                        error = interaction.error,
                        onRetry = { viewModel.loadMessages() }
                    )
                }
                messageState.messages.isEmpty() && !interaction.isLoading -> {
                    ChatEmptyState(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                else -> {
                     val messageSpacing = if (LocalCompactMessages.current) 2.dp else 8.dp

                        // Use raw messages directly — each Message is one LazyColumn item.
                        // V1 messageListState returns oldest-first; reverse to newest-first
                        // for ChatMessageList's reverseLayout (index 0 = newest at bottom).
                        val rawMessages = remember(messageState.messages) {
                            messageState.messages.reversed()
                        }

                        // Filter: keep user messages + first assistant in each turn group
                       // to avoid zero-height items creating blank gaps from spacedBy
                       val displayItems = remember(rawMessages) {
                           rawMessages.mapIndexedNotNull { index, msg ->
                               when {
                                   msg.isUser -> index to msg
                                   msg.isAssistant -> {
                                       val nextMsg = rawMessages.getOrNull(index + 1)
                                       if (nextMsg?.isAssistant != true) index to msg else null
                                   }
                                   else -> null
                               }
                           }
                       }



                        if (sessionMeta.sessionParentId == null) {
                            ChatMessageList(
                                listState = listState,
                                messageState = messageState,
                                sessionMeta = sessionMeta,
                                interaction = interaction,
                                rawMessages = rawMessages,
                                displayItems = displayItems,
                                isAtBottom = isAtBottom,
                                isAmoled = isAmoled,
                                messageSpacing = messageSpacing,
                                isMainSession = true,
                                coroutineScope = coroutineScope,
                                snackbarHostState = snackbarHostState,
                                context = context,
                                clipboardManager = clipboardManager,
                                keyboardController = keyboardController,
                                viewModel = viewModel,
                                navigateToChildSession = navigateToChildSessionWithSave,
                                agents = modelConfig.agents,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            ChatMessageList(
                                listState = listState,
                                messageState = messageState,
                                sessionMeta = sessionMeta,
                                interaction = interaction,
                                rawMessages = rawMessages,
                                displayItems = displayItems,
                                isAtBottom = isAtBottom,
                                isAmoled = isAmoled,
                                messageSpacing = messageSpacing,
                                isMainSession = false,
                                coroutineScope = coroutineScope,
                                snackbarHostState = snackbarHostState,
                                context = context,
                                clipboardManager = clipboardManager,
                                keyboardController = keyboardController,
                                viewModel = viewModel,
                                navigateToChildSession = navigateToChildSessionWithSave,
                                agents = modelConfig.agents,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                  }
              }
           }
       }


    // Model picker dialog
    if (showModelPicker) {
        ModelPickerDialog(
            providers = modelConfig.providers,
            selectedProviderId = modelConfig.selectedProviderId,
            selectedModelId = modelConfig.selectedModelId,
            onSelect = { providerId, modelId ->
                viewModel.selectModel(providerId, modelId)
            },
            onDismiss = { showModelPicker = false }
        )
    }

    // Rename dialog
    if (showRenameDialog) {
        RenameSessionDialog(
            initialTitle = sessionMeta.sessionTitle,
            onRename = { newTitle ->
                viewModel.renameSession(newTitle) { ok ->
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(
                            if (ok) context.getString(R.string.chat_session_renamed) else context.getString(R.string.chat_session_rename_failed)
                        )
                    }
                }
            },
            onDismiss = { showRenameDialog = false }
        )
    }

    // Send confirmation dialog
    if (showSendConfirmDialog) {
        SendConfirmDialog(
            onConfirm = {
                pendingSendAction?.invoke()
                pendingSendAction = null
            },
            onDismiss = {
                pendingSendAction = null
            }
        )
    }
    } // CompositionLocalProvider
    } // ChatSettingsProvider
}

/**
 * Animated auto-scroll to bottom. Used for after-send follow.
 *
 * With reverseLayout=true, "bottom" = item 0.
 * Retries up to 48ms (3×16ms) to handle complex Markdown layout delays.
 */
internal suspend fun LazyListState.smoothScrollToBottom() {
    animateScrollToItem(0)
    var attempts = 0
    while (canScrollBackward && attempts < 3) {
        delay(16)
        if (!canScrollBackward) return
        scroll { scrollBy(-10_000f) }
        attempts++
    }
}

/**
 * Instant snap to bottom for explicit user actions (FAB click).
 */
internal suspend fun LazyListState.snapToBottom() {
    if (layoutInfo.totalItemsCount == 0) return
    scrollToItem(0)
    var attempts = 0
    while (canScrollBackward && attempts < 3) {
        delay(16)
        if (!canScrollBackward) return
        scroll { scrollBy(-10_000f) }
        attempts++
    }
}

/**
 * Wrapper composable that collects settings flows and provides them via CompositionLocals.
 * Sunk from ChatScreen to prevent settings changes from triggering ChatScreen recomposition.
 * Only this wrapper recomposes when settings change.
 */
@Composable
private fun ChatSettingsProvider(
    viewModel: ChatViewModel,
    content: @Composable () -> Unit,
) {
    val chatFontSize by viewModel.chatFontSize.collectAsStateWithLifecycle()
    val codeWordWrap by viewModel.codeWordWrap.collectAsStateWithLifecycle()
    val compactMessages by viewModel.compactMessages.collectAsStateWithLifecycle()
    val collapseTools by viewModel.collapseTools.collectAsStateWithLifecycle()
    val expandReasoning by viewModel.expandReasoning.collectAsStateWithLifecycle()

    CompositionLocalProvider(
        LocalChatFontSize provides chatFontSize,
        LocalCodeWordWrap provides codeWordWrap,
        LocalCompactMessages provides compactMessages,
        LocalCollapseTools provides collapseTools,
        LocalExpandReasoning provides expandReasoning,
    ) {
        content()
    }
}
