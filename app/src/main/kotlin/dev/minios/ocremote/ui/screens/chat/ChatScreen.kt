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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil3.compose.AsyncImage
import dev.minios.ocremote.domain.model.*
import dev.minios.ocremote.data.dto.response.AgentInfo
import dev.minios.ocremote.data.dto.response.CommandInfo
import dev.minios.ocremote.data.dto.request.PromptPart
import dev.minios.ocremote.data.dto.response.ProviderInfo
import dev.minios.ocremote.data.dto.response.ProviderModel
import dev.minios.ocremote.MainActivity
import dev.minios.ocremote.ui.theme.CodeTypography
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
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
import dev.minios.ocremote.ui.screens.chat.terminal.SessionTerminalInline
import dev.minios.ocremote.ui.screens.chat.terminal.TerminalKeyboardOverlay
import dev.minios.ocremote.ui.screens.chat.terminal.TerminalKey
import dev.minios.ocremote.ui.screens.chat.terminal.TerminalKeyRow
import dev.minios.ocremote.ui.screens.chat.terminal.applyTerminalModifiers
import dev.minios.ocremote.ui.screens.chat.terminal.applyTermuxFnBindings
import dev.minios.ocremote.ui.screens.chat.terminal.FnBindingResult
import dev.minios.ocremote.ui.screens.chat.terminal.ctrlTransform
import dev.minios.ocremote.ui.screens.chat.util.isAmoledTheme
import dev.minios.ocremote.ui.screens.chat.util.computeTurnGroups
import dev.minios.ocremote.ui.screens.chat.util.toolOutputContainerColor
import dev.minios.ocremote.ui.screens.chat.util.agentColor
import dev.minios.ocremote.ui.screens.chat.util.agentColorCycle
import dev.minios.ocremote.ui.screens.chat.util.QueuedBadgeColor
import dev.minios.ocremote.ui.screens.chat.util.QueuedBadgeTextColor
import dev.minios.ocremote.ui.screens.chat.util.formatTokenCount
import dev.minios.ocremote.ui.screens.chat.util.formatAssistantErrorMessage
import dev.minios.ocremote.ui.screens.chat.util.formatFileSize
import dev.minios.ocremote.ui.screens.chat.util.formatDuration
import dev.minios.ocremote.ui.screens.chat.util.resolveStepsStatus
import dev.minios.ocremote.ui.screens.chat.util.resolveUserCommandLabel
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
import dev.minios.ocremote.ui.screens.chat.util.ImageAttachment
import dev.minios.ocremote.ui.screens.chat.util.PreparedAttachment
import dev.minios.ocremote.ui.screens.chat.util.AttachmentComparison
import dev.minios.ocremote.ui.screens.chat.util.decodeDataUrlBytes
import dev.minios.ocremote.ui.screens.chat.util.decodePartFileBytes
import dev.minios.ocremote.ui.screens.chat.util.extensionForMime
import dev.minios.ocremote.ui.screens.chat.util.imageThumbnailModel
import dev.minios.ocremote.ui.screens.chat.util.estimateVisionTokens
import dev.minios.ocremote.ui.screens.chat.util.buildAttachmentFromUri
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
import dev.minios.ocremote.ui.screens.chat.dialog.MarkdownPreviewDialog
import dev.minios.ocremote.ui.screens.chat.dialog.ImageThumbnailRow
import dev.minios.ocremote.ui.screens.chat.dialog.ImagePreviewDialog
import dev.minios.ocremote.ui.screens.chat.dialog.QuestionCard
import dev.minios.ocremote.ui.screens.chat.dialog.PermissionCard
import dev.minios.ocremote.ui.screens.chat.input.ChatInputBar
import dev.minios.ocremote.ui.screens.chat.input.ChatInputMode
import dev.minios.ocremote.ui.screens.chat.input.SlashCommand
import dev.minios.ocremote.ui.screens.chat.input.buildPromptParts
import dev.minios.ocremote.ui.screens.chat.components.MessageCard
import dev.minios.ocremote.ui.screens.chat.components.MessageCardRole
import dev.minios.ocremote.ui.screens.chat.components.ErrorPayloadContent
import dev.minios.ocremote.ui.components.indicators.PulsingDotsIndicator
import dev.minios.ocremote.ui.screens.chat.components.RevertBanner


/**
 * Chat Screen - conversation view with native markdown rendering.
 * Shows messages with streaming text rendered via mikepenz markdown renderer.
 */

private const val TAG_SCROLL = "ChatScroll"

// jumpToBottom / animateScrollToBottom removed — reverseLayout=true anchors at bottom natively.

private data class ImageSaveRequest(
    val bytes: ByteArray,
    val mime: String,
    val filename: String,
)



@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    val uiState by viewModel.uiState.collectAsState()
    val draftText by viewModel.draftText.collectAsState()
    val draftAttachmentUris by viewModel.draftAttachmentUris.collectAsState()
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

    // Detect if user is truly at the bottom.
    // reverseLayout=true: "at bottom" means newest messages visible = !canScrollBackward.
    val isAtBottom by remember {
        derivedStateOf {
            if (listState.layoutInfo.totalItemsCount == 0) true
            else !listState.canScrollBackward
        }
    }

    // shouldFollow removed — reverseLayout=true handles auto-follow natively.

    // Debug: log isAtBottom changes
    LaunchedEffect(Unit) {
        Log.w("CHAT_DEBUG", "=== ChatScreen LaunchedEffect(Unit) started === viewModel=${viewModel.sessionId} scrollVersion=${viewModel.scrollRestoreVersion}")
        snapshotFlow { isAtBottom }
            .collect { bottom ->
                Log.w("CHAT_DEBUG", "[isAtBottom] changed to=$bottom items=${listState.layoutInfo.totalItemsCount} canBackward=${listState.canScrollBackward}")
            }
    }

    // Restore scroll position when returning from sub-session navigation.
    // Using LaunchedEffect(scrollRestoreVersion) instead of rememberLazyListState(initial...)
    // because `remember` caches the initial state and ignores new values on recomposition,
    // causing unreliable restoration when the composable is recomposed (not recreated).
    LaunchedEffect(viewModel.scrollRestoreVersion) {
        val version = viewModel.scrollRestoreVersion
        Log.w("CHAT_DEBUG", "[scrollRestore] LaunchedEffect fired: version=$version msgs=${uiState.messages.size}")
        if (version > 0) {
            val savedId = viewModel.savedMessageId
            if (savedId != null) {
                // Reconstruct displayItems filtering to find the target's LazyColumn index.
                // With reverseLayout=true + displayItems.reversed(), the adapter index differs.
                val msgs = uiState.messages
                var displayIndex = -1
                var count = 0
                for (i in msgs.indices) {
                    val msg = msgs[i]
                    val include = when {
                        msg.isUser -> true
                        msg.isAssistant -> {
                            val next = msgs.getOrNull(i + 1)
                            next?.isAssistant != true
                        }
                        else -> false
                    }
                    if (include) {
                        if (msg.message.id == savedId) {
                            displayIndex = count
                            break
                        }
                        count++
                    }
                }
                if (displayIndex >= 0) {
                    // displayItems is reversed for reverseLayout; convert original index to reversed index.
                    val totalCount = count // total matched items (approximate; recount for accuracy)
                    // Recount total displayItems to compute reversed index
                    var totalDisplay = 0
                    for (i in msgs.indices) {
                        val m = msgs[i]
                        val inc = when {
                            m.isUser -> true
                            m.isAssistant -> msgs.getOrNull(i + 1)?.isAssistant != true
                            else -> false
                        }
                        if (inc) totalDisplay++
                    }
                    val reversedIndex = totalDisplay - 1 - displayIndex
                    // Account for auxiliary items declared before messages in reverseLayout
                    val auxOffset = uiState.pendingQuestions.size + uiState.pendingPermissions.size + (if (uiState.revert != null) 1 else 0)
                    listState.scrollToItem(reversedIndex + auxOffset, viewModel.savedScrollOffset)
                    Log.w("CHAT_DEBUG", "[scrollRestore] restored to reversedIndex=$reversedIndex auxOffset=$auxOffset")
                } else {
                    // Fallback: scroll to item 0 (newest, at bottom in reverseLayout)
                    listState.scrollToItem(0)
                    Log.w("CHAT_DEBUG", "[scrollRestore] fallback scrollToItem(0)")
                }
            }
        } else {
            // First entry (scrollRestoreVersion == 0): reverseLayout=true anchors at bottom,
            // so no explicit scroll is needed.
            Log.w("CHAT_DEBUG", "[firstEntry] reverseLayout=true anchors at bottom, no scroll needed")
        }
    }

    // Wrapper that saves scroll position before navigating to a sub-session
    val navigateToChildSessionWithSave: (String) -> Unit = { childSessionId ->
        val firstMessageKey = listState.layoutInfo.visibleItemsInfo
            .firstOrNull {
                val key = (it.key as? String) ?: ""
                !key.startsWith("question_") &&
                !key.startsWith("perm_") &&
                key != "revert_banner"
            }
            ?.key as? String
        viewModel.saveScrollPosition(firstMessageKey, listState.firstVisibleItemScrollOffset)
        onNavigateToChildSession(childSessionId)
    }

    var showModelPicker by remember { mutableStateOf(false) }
    var markdownPreviewText by remember { mutableStateOf<String?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var isTerminalMode by rememberSaveable { mutableStateOf(startInTerminalMode) }
    var terminalCtrlLatched by rememberSaveable { mutableStateOf(false) }
    var terminalAltLatched by rememberSaveable { mutableStateOf(false) }
    var terminalVirtualCtrlDown by remember { mutableStateOf(false) }
    var terminalVirtualFnDown by remember { mutableStateOf(false) }
    var suppressFnTildeUntil by remember { mutableStateOf(0L) }
    val terminalFocusRequester = remember { FocusRequester() }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val isAmoled = isAmoledTheme()
    val keyboardController = LocalSoftwareKeyboardController.current
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    val view = LocalView.current
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    // isAtBottomBeforeIme removed — reverseLayout=true handles IME natively.
    var terminalOverlayHeightPx by remember { mutableStateOf(0) }

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

    // @ file mention state
    val fileSearchResults by viewModel.fileSearchResults.collectAsState()
    val confirmedFilePaths by viewModel.confirmedFilePaths.collectAsState()

    // Settings
    val chatFontSize by viewModel.chatFontSize.collectAsState()
    val codeWordWrap by viewModel.codeWordWrap.collectAsState()
    val confirmBeforeSend by viewModel.confirmBeforeSend.collectAsState()
    val compactMessages by viewModel.compactMessages.collectAsState()
    val collapseTools by viewModel.collapseTools.collectAsState()
    val expandReasoning by viewModel.expandReasoning.collectAsState()
    val hapticEnabled by viewModel.hapticFeedback.collectAsState()
    val keepScreenOn by viewModel.keepScreenOn.collectAsState()
    val compressImageAttachments by viewModel.compressImageAttachments.collectAsState()
    val imageAttachmentMaxLongSide by viewModel.imageAttachmentMaxLongSide.collectAsState()
    val imageAttachmentWebpQuality by viewModel.imageAttachmentWebpQuality.collectAsState()
    val terminalVersion by viewModel.terminalVersion.collectAsState()
    val terminalConnected by viewModel.terminalConnected.collectAsState()
    val terminalTabs by viewModel.terminalTabs.collectAsState()
    val activeTerminalTabId by viewModel.activeTerminalTabId.collectAsState()
    val terminalFontSizeSp by viewModel.terminalFontSizeSp.collectAsState()
    if (BuildConfig.DEBUG) {
        LaunchedEffect(terminalFontSizeSp) {
            Log.d("TerminalZoom", "ChatScreen: terminalFontSizeSp CHANGED to $terminalFontSizeSp (flow identity=${System.identityHashCode(viewModel.terminalFontSizeSp)})")
        }
    }
    val terminalDrawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    var showSendConfirmDialog by remember { mutableStateOf(false) }
    // Pending send action: stored so the confirm dialog can trigger it
    var pendingSendAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var inputMode by rememberSaveable { mutableStateOf(ChatInputMode.NORMAL.name) }
    val isShellMode = inputMode == ChatInputMode.SHELL.name

    BackHandler(enabled = isTerminalMode) {
        if (terminalDrawerState.isOpen) {
            coroutineScope.launch { terminalDrawerState.close() }
        } else if (startInTerminalMode) {
            // Opened directly in terminal mode (e.g. from sessions list) —
            // back should navigate away, not show the chat view.
            onNavigateBack()
        } else {
            isTerminalMode = false
        }
    }

    LaunchedEffect(isTerminalMode) {
        if (isTerminalMode) {
            viewModel.openTerminalSession { ok ->
                if (!ok) {
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(context.getString(R.string.chat_terminal_connect_failed))
                    }
                    isTerminalMode = false
                }
            }
        } else {
            terminalCtrlLatched = false
            terminalAltLatched = false
            terminalVirtualCtrlDown = false
            terminalVirtualFnDown = false
        }
    }

    DisposableEffect(isTerminalMode) {
        val activity = context as? MainActivity
        if (isTerminalMode && activity != null) {
            activity.setTerminalKeyInterceptor { event ->
                when (event.keyCode) {
                    android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> {
                        terminalVirtualCtrlDown = event.action == android.view.KeyEvent.ACTION_DOWN
                        true
                    }
                    android.view.KeyEvent.KEYCODE_VOLUME_UP -> {
                        val wasDown = terminalVirtualFnDown
                        terminalVirtualFnDown = event.action == android.view.KeyEvent.ACTION_DOWN
                        if (BuildConfig.DEBUG) {
                            Log.d("TerminalInput", "VOL_UP: action=${if (event.action == android.view.KeyEvent.ACTION_DOWN) "DOWN" else "UP"} wasDown=$wasDown nowDown=$terminalVirtualFnDown")
                        }
                        if (wasDown && !terminalVirtualFnDown) {
                            // FN key released — some IMEs leak a delayed '~' character
                            // from the underlying key (e.g., Shift+` or dead-key residue).
                            // Suppress any standalone '~' arriving shortly after release.
                            suppressFnTildeUntil = SystemClock.elapsedRealtime() + 3_000L
                            if (BuildConfig.DEBUG) {
                                Log.d("TerminalInput", "FN released -> suppressFnTildeUntil set for 3s")
                            }
                        }
                        true
                    }
                    else -> false
                }
            }
        } else {
            activity?.setTerminalKeyInterceptor(null)
        }
        onDispose {
            activity?.setTerminalKeyInterceptor(null)
            terminalVirtualCtrlDown = false
            terminalVirtualFnDown = false
        }
    }

    // Force status bar black while terminal is visible.
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    DisposableEffect(isTerminalMode) {
        val activity = context as? android.app.Activity
        if (isTerminalMode && activity != null) {
            activity.window.statusBarColor = android.graphics.Color.BLACK
            androidx.core.view.WindowCompat.getInsetsController(
                activity.window, activity.window.decorView
            ).isAppearanceLightStatusBars = false
        }
        onDispose {
            val act = context as? android.app.Activity ?: return@onDispose
            act.window.statusBarColor = android.graphics.Color.TRANSPARENT
            androidx.core.view.WindowCompat.getInsetsController(
                act.window, act.window.decorView
            ).isAppearanceLightStatusBars = !isDarkTheme
        }
    }

    LaunchedEffect(isTerminalMode, terminalConnected) {
        if (isTerminalMode && terminalConnected) {
            terminalFocusRequester.requestFocus()
        }
    }

    fun pasteClipboardToTerminal() {
        if (!terminalConnected) return
        val clip = clipboardManager.getText()?.text ?: return
        if (clip.isEmpty()) return
        val cleaned = clip
            .replace(Regex("[\u001B\u0080-\u009F]"), "")
            .replace("\r\n", "\r")
            .replace('\n', '\r')
        if (cleaned.isNotEmpty()) {
            viewModel.sendTerminalInput(cleaned)
        }
    }

    fun sendTerminalChunk(chunk: String) {
        if (BuildConfig.DEBUG) {
            val codes = chunk.map { String.format("%04x", it.code) }
            val remain = suppressFnTildeUntil - SystemClock.elapsedRealtime()
            Log.d("TerminalInput", "sendTerminalChunk: chunk=$codes fnDown=$terminalVirtualFnDown suppressRemain=${remain}ms")
        }
        if (!terminalVirtualFnDown) {
            val now = SystemClock.elapsedRealtime()
            if (now < suppressFnTildeUntil && chunk.contains('~')) {
                // Guard against a leaked '~' after an FN key combo (e.g., Fn+0/F10).
                // The tilde may arrive alone ("~") or bundled with other characters.
                if (BuildConfig.DEBUG) {
                    Log.d("TerminalInput", "SUPPRESSING tilde from chunk='$chunk'")
                }
                val stripped = chunk.replace("~", "")
                suppressFnTildeUntil = 0L
                if (stripped.isEmpty()) return
                // Forward the non-tilde remainder.
                @Suppress("NAME_SHADOWING")
                val chunk = stripped
                // fall through with the cleaned chunk
                val ctrlActive2 = terminalCtrlLatched || terminalVirtualCtrlDown
                val altActive2 = terminalAltLatched
                val processed = applyTerminalModifiers(input = chunk, ctrl = ctrlActive2, alt = altActive2)
                if (processed.isEmpty()) return
                viewModel.sendTerminalInput(processed)
                if (terminalCtrlLatched) terminalCtrlLatched = false
                if (terminalAltLatched) terminalAltLatched = false
                return
            }
            if (chunk.isNotEmpty() && !chunk.contains('~')) {
                // Any other explicit input clears the temporary suppression window.
                suppressFnTildeUntil = 0L
            }
        }

        val ctrlActive = terminalCtrlLatched || terminalVirtualCtrlDown
        val altActive = terminalAltLatched

        // Termux-compatible shortcut: Ctrl+Alt+V pastes clipboard into terminal.
        if (!terminalVirtualFnDown && ctrlActive && altActive && chunk.length == 1 && chunk[0].lowercaseChar() == 'v') {
            pasteClipboardToTerminal()
            if (terminalCtrlLatched) terminalCtrlLatched = false
            if (terminalAltLatched) terminalAltLatched = false
            return
        }

        val processed = if (terminalVirtualFnDown) {
            val fnResult = applyTermuxFnBindings(chunk, viewModel.terminalEmulator.cursorKeysApplicationMode)
            if (fnResult.showVolumeUi) {
                val audio = context.getSystemService(AudioManager::class.java)
                audio?.adjustSuggestedStreamVolume(
                    AudioManager.ADJUST_SAME,
                    AudioManager.USE_DEFAULT_STREAM_TYPE,
                    AudioManager.FLAG_SHOW_UI
                )
            }
            if (fnResult.toggleKeyboard) {
                if (imeVisible) {
                    keyboardController?.hide()
                } else {
                    terminalFocusRequester.requestFocus()
                    keyboardController?.show()
                }
            }
            if (fnResult.output.contains("~")) {
                // Any FN binding that produces '~' in its escape sequence (F5-F12, Insert,
                // Delete, PageUp, PageDown) may cause the IME to leak a standalone '~' after
                // the Volume-Up (FN) key is released.
                suppressFnTildeUntil = SystemClock.elapsedRealtime() + 3_000L
            }
            fnResult.output
        } else {
            applyTerminalModifiers(
                input = chunk,
                ctrl = ctrlActive,
                alt = altActive
            )
        }
        if (processed.isEmpty()) return
        if (BuildConfig.DEBUG && processed.contains('~')) {
            Log.d("TerminalInput", "SENDING to server: '${processed.map { String.format("%04x", it.code) }}' fnDown=$terminalVirtualFnDown")
        }
        viewModel.sendTerminalInput(processed)
        if (terminalCtrlLatched) terminalCtrlLatched = false
        if (terminalAltLatched) terminalAltLatched = false
    }

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

    // Image attachments — backed by ViewModel URIs for draft persistence
    val attachments = remember { mutableStateListOf<ImageAttachment>() }

    // Rebuild attachment objects from persisted draft URIs on first composition
    LaunchedEffect(draftAttachmentUris, compressImageAttachments, imageAttachmentMaxLongSide, imageAttachmentWebpQuality) {
        // Only rebuild if attachments list doesn't match URIs (e.g. on session restore)
        val currentUris = attachments.map { it.uri.toString() }.toSet()
        val draftUriSet = draftAttachmentUris.toSet()
        if (currentUris == draftUriSet) return@LaunchedEffect

        val restored = mutableListOf<ImageAttachment>()
        for (uriStr in draftAttachmentUris) {
            // Skip URIs already present
            if (uriStr in currentUris) {
                val existing = attachments.first { it.uri.toString() == uriStr }
                restored.add(existing)
                continue
            }
            try {
                val uri = android.net.Uri.parse(uriStr)
                if (uriStr.startsWith("data:image/", ignoreCase = true)) {
                    val mime = uriStr.substringAfter("data:").substringBefore(';').ifBlank { "image/png" }
                    val syntheticName = "image.${mime.substringAfter('/', "png")}".lowercase()
                    restored.add(
                        ImageAttachment(
                            uri = uri,
                            mime = mime,
                            filename = syntheticName,
                            dataUrl = uriStr,
                        )
                    )
                    continue
                }
                val prepared = buildAttachmentFromUri(
                    contentResolver = context.contentResolver,
                    uri = uri,
                    compressImages = compressImageAttachments,
                    maxLongSidePx = imageAttachmentMaxLongSide,
                    webpQuality = imageAttachmentWebpQuality
                )
                if (prepared != null) {
                    restored.add(prepared.attachment)
                }
            } catch (e: Exception) {
                Log.w("ChatScreen", "Failed to restore attachment $uriStr: ${e.message}")
                // Remove invalid URI from draft
                viewModel.removeDraftAttachment(draftAttachmentUris.indexOf(uriStr))
            }
        }
        attachments.clear()
        attachments.addAll(restored)
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        coroutineScope.launch {
            val optimizedComparisons = mutableListOf<AttachmentComparison>()
            for (uri in uris) {
                try {
                    // Take persistable URI permission so the URI survives app restarts
                    try {
                        context.contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (e: Exception) {
                        // Not all URIs support persistable permissions — that's OK
                    }

                    val prepared = buildAttachmentFromUri(
                        contentResolver = context.contentResolver,
                        uri = uri,
                        compressImages = compressImageAttachments,
                        maxLongSidePx = imageAttachmentMaxLongSide,
                        webpQuality = imageAttachmentWebpQuality
                    ) ?: continue

                    attachments.add(prepared.attachment)
                    viewModel.addDraftAttachment(uri.toString())
                    prepared.comparison?.let { optimizedComparisons.add(it) }
                } catch (_: Exception) {
                    // Skip files that fail to read
                }
            }
            if (optimizedComparisons.isNotEmpty()) {
                val totalOriginal = optimizedComparisons.sumOf { it.originalBytes }
                val totalOptimized = optimizedComparisons.sumOf { it.optimizedBytes }
                val totalTokensBefore = optimizedComparisons.sumOf { it.originalEstimatedTokens }
                val totalTokensAfter = optimizedComparisons.sumOf { it.optimizedEstimatedTokens }
                snackbarHostState.showSnackbar(
                    context.getString(
                        R.string.chat_images_optimized_summary,
                        optimizedComparisons.size,
                        formatFileSize(totalOriginal),
                        formatFileSize(totalOptimized),
                        totalTokensBefore,
                        totalTokensAfter
                    )
                )
            }
        }
    }

    // Session export via SAF (Storage Access Framework)
    // Flow: menu click → SAF file picker → stream API responses directly to file
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.exportSession(context, uri) { success ->
                coroutineScope.launch {
                    if (success) {
                        snackbarHostState.showSnackbar(context.getString(R.string.chat_session_exported))
                    } else {
                        snackbarHostState.showSnackbar(context.getString(R.string.chat_session_export_failed))
                    }
                }
            }
        }
    }

    var pendingImageSave by remember { mutableStateOf<ImageSaveRequest?>(null) }
    val saveImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("image/*")
    ) { uri: Uri? ->
        val request = pendingImageSave
        pendingImageSave = null
        if (uri == null || request == null) return@rememberLauncherForActivityResult

        coroutineScope.launch {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(request.bytes) }
                    ?: error("Unable to open output stream")
            }.onSuccess {
                snackbarHostState.showSnackbar(context.getString(R.string.chat_image_saved))
            }.onFailure {
                snackbarHostState.showSnackbar(context.getString(R.string.chat_image_save_failed))
            }
        }
    }

    val requestSaveImage: (ByteArray, String, String?) -> Unit = { bytes, mime, filenameHint ->
        val baseName = filenameHint
            ?.substringAfterLast('/')
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
            ?: "image_${System.currentTimeMillis()}"
        val fileName = "$baseName.${extensionForMime(mime)}"
        pendingImageSave = ImageSaveRequest(bytes = bytes, mime = mime, filename = fileName)
        saveImageLauncher.launch(fileName)
    }

    // Consume images shared from other apps via ACTION_SEND (one-shot)
    LaunchedEffect(initialSharedImages) {
        if (initialSharedImages.isEmpty()) return@LaunchedEffect
        val optimizedComparisons = mutableListOf<AttachmentComparison>()
        for (uri in initialSharedImages) {
            try {
                // Take persistable URI permission so the URI survives app restarts
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    // Not all URIs support persistable permissions — that's OK
                }

                val prepared = buildAttachmentFromUri(
                    contentResolver = context.contentResolver,
                    uri = uri,
                    compressImages = compressImageAttachments,
                    maxLongSidePx = imageAttachmentMaxLongSide,
                    webpQuality = imageAttachmentWebpQuality
                ) ?: continue

                attachments.add(prepared.attachment)
                prepared.comparison?.let { optimizedComparisons.add(it) }
                viewModel.addDraftAttachment(uri.toString())
            } catch (e: Exception) {
                Log.w("ChatScreen", "Failed to read shared image: ${e.message}")
            }
        }
        if (optimizedComparisons.isNotEmpty()) {
            val totalOriginal = optimizedComparisons.sumOf { it.originalBytes }
            val totalOptimized = optimizedComparisons.sumOf { it.optimizedBytes }
            val totalTokensBefore = optimizedComparisons.sumOf { it.originalEstimatedTokens }
            val totalTokensAfter = optimizedComparisons.sumOf { it.optimizedEstimatedTokens }
            snackbarHostState.showSnackbar(
                context.getString(
                    R.string.chat_images_optimized_summary,
                    optimizedComparisons.size,
                    formatFileSize(totalOriginal),
                    formatFileSize(totalOptimized),
                    totalTokensBefore,
                    totalTokensAfter
                )
            )
        }
        onSharedImagesConsumed()
    }

    // Show errors as snackbar when messages are already loaded
    LaunchedEffect(uiState.error) {
        val error = uiState.error
        if (error != null && uiState.messages.isNotEmpty()) {
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
    var hasResumedOnce by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (hasResumedOnce) {
                    viewModel.refreshSession()
                    viewModel.syncSessionStatus()
                } else {
                    hasResumedOnce = true
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // When message count increases (new message from send or QUEUE auto-submit),
    // scroll to absolute bottom if user is already at/near bottom.
    LaunchedEffect(Unit) {
        var lastCount = 0
        snapshotFlow { uiState.messages.size }
            .collect { count ->
                if (count > lastCount && lastCount > 0 && isAtBottom) {
                    // New messages appeared while user is at bottom → stay at bottom
                    listState.snapToBottom()
                }
                lastCount = count
            }
    }

    // When SSE streaming ends, the detached streaming message merges back into LazyColumn.
    // If user was at/near bottom, snap to the new bottom.
    LaunchedEffect(Unit) {
        var wasStreaming = false
        snapshotFlow { uiState.sessionStatus is SessionStatus.Busy }
            .collect { streaming ->
                if (wasStreaming && !streaming && isAtBottom) {
                    listState.snapToBottom()
                }
                wasStreaming = streaming
            }
    }

    CompositionLocalProvider(
        LocalChatFontSize provides chatFontSize,
        LocalCodeWordWrap provides codeWordWrap,
        LocalCompactMessages provides compactMessages,
        LocalCollapseTools provides collapseTools,
        LocalExpandReasoning provides expandReasoning,
        LocalHapticFeedbackEnabled provides hapticEnabled,
        LocalImageSaveRequest provides requestSaveImage,
        LocalToolExpandedStates provides uiState.toolExpandedStates,
        LocalOnToggleToolExpanded provides { toolId, defaultExpanded -> viewModel.toggleToolExpanded(toolId, defaultExpanded) },
    ) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (!isTerminalMode) {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = uiState.sessionTitle.ifBlank { stringResource(R.string.chat_title_placeholder) },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        // Subtitle: chat items count, total tokens and cost for the session
                        val totalTokens = uiState.totalInputTokens + uiState.totalOutputTokens
                        val hasStats = uiState.messageCount > 0 || totalTokens > 0 || uiState.totalCost > 0
                        if (hasStats) {
                            val parts = mutableListOf<String>()
                            if (uiState.messageCount > 0) {
                                parts.add(stringResource(R.string.chat_items_count, uiState.messageCount))
                            }
                            if (totalTokens > 0) {
                                parts.add(stringResource(R.string.chat_tokens_summary, formatTokenCount(totalTokens)))
                            }
                            if (uiState.totalCost > 0) {
                                parts.add(stringResource(R.string.chat_cost_format, String.format("%.4f", uiState.totalCost)))
                            }
                            if (parts.isNotEmpty()) {
                                Text(
                                    text = parts.joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
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
                    if (uiState.sessionParentId == null) {
Box {
                        val isAmoled = isAmoledTheme()
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            containerColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
                            border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null
                        ) {
                            if (uiState.sessionParentId == null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.tool_terminal)) },
                                onClick = {
                                    showMenu = false
                                    isTerminalMode = true
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Terminal, contentDescription = null)
                                }
                            )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_open_in_web)) },
                                onClick = {
                                    showMenu = false
                                    onOpenInWebView()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Language, contentDescription = null)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_new_session)) },
                                onClick = {
                                    showMenu = false
                                    viewModel.createNewSession { session ->
                                        if (session != null) {
                                            onNavigateToSession(session.id)
                                        } else {
                                            coroutineScope.launch {
                                                snackbarHostState.showSnackbar(context.getString(R.string.chat_session_create_failed))
                                            }
                                        }
                                    }
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Add, contentDescription = null)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_fork_session)) },
                                onClick = {
                                    showMenu = false
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
                                leadingIcon = {
                                    Icon(Icons.Default.CopyAll, contentDescription = null)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_compact_session)) },
                                onClick = {
                                    showMenu = false
                                    viewModel.compactSession { ok ->
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar(
                                                if (ok) context.getString(R.string.chat_session_compacted) else context.getString(R.string.chat_session_compact_failed)
                                            )
                                        }
                                    }
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Compress, contentDescription = null)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_review_changes)) },
                                onClick = {
                                    showMenu = false
                                    viewModel.executeCommand("review") { ok ->
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar(
                                                if (ok) context.getString(R.string.chat_command_executed, "review") else context.getString(R.string.chat_command_failed, "review")
                                            )
                                        }
                                    }
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.RateReview, contentDescription = null)
                                },
                            )
                            // Show Share or Unshare depending on current share status
                            if (uiState.shareUrl != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.cmd_unshare)) },
                                    onClick = {
                                        showMenu = false
                                        viewModel.unshareSession { ok ->
                                            coroutineScope.launch {
                                                snackbarHostState.showSnackbar(
                                                    if (ok) context.getString(R.string.chat_session_unshared) else context.getString(R.string.chat_session_unshare_failed)
                                                )
                                            }
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.LinkOff, contentDescription = null)
                                    }
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.menu_share_session)) },
                                    onClick = {
                                        showMenu = false
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
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Share, contentDescription = null)
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_rename_session)) },
                                onClick = {
                                    showMenu = false
                                    showRenameDialog = true
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Edit, contentDescription = null)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_export_session)) },
                                onClick = {
                                    showMenu = false
                                    val slug = uiState.sessionTitle
                                        .take(30)
                                        .replace(Regex("[^a-zA-Z0-9_-]"), "_")
                                        .ifBlank { "session" }
                                    exportLauncher.launch("$slug.json")
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.FileDownload, contentDescription = null)
                                }
                            )
                        }
                    }
                    } // end if (sessionParentId == null)
                }
            )
            }
        },
        bottomBar = {
            if (uiState.sessionParentId == null && !isTerminalMode &&
                (uiState.messages.isNotEmpty() || !uiState.isLoading) && uiState.error == null
            ) {
                val modelLabel = if (uiState.selectedModelId != null && uiState.providers.isNotEmpty()) {
                    val provider = uiState.providers.find { it.id == uiState.selectedProviderId }
                    val model = provider?.models?.get(uiState.selectedModelId)
                    model?.name ?: uiState.selectedModelId ?: ""
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
                                attachments.clear()
                                // Scroll to bottom after sending: wait for new item to appear, then scroll
                                coroutineScope.launch {
                                    val currentCount = listState.layoutInfo.totalItemsCount
                                    Log.w("CHAT_DEBUG", "[afterSend] waiting: currentCount=$currentCount canBackward=${listState.canScrollBackward}")
                                    snapshotFlow { listState.layoutInfo.totalItemsCount }
                                        .first { it > currentCount }
                                    Log.w("CHAT_DEBUG", "[afterSend] new items detected: count=${listState.layoutInfo.totalItemsCount} canBackward=${listState.canScrollBackward}")
                                    listState.snapToBottom()
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
                        isSending = uiState.isSending,
                        isBusy = uiState.sessionStatus is SessionStatus.Busy,
                        messages = uiState.messages,
                        attachments = attachments,
                        onAttach = { imagePickerLauncher.launch("image/*") },
                        onRemoveAttachment = { index ->
                            if (index in attachments.indices) {
                                attachments.removeAt(index)
                                viewModel.removeDraftAttachment(index)
                            }
                        },
                        onSaveAttachment = { bytes, mime, filename ->
                            requestSaveImage(bytes, mime, filename)
                        },
                        modelLabel = modelLabel,
                        selectedProviderId = uiState.selectedProviderId,
                        onModelClick = { showModelPicker = true },
                        agents = uiState.agents,
                        selectedAgent = uiState.selectedAgent,
                        onAgentSelect = { viewModel.selectAgent(it) },
                        variantNames = uiState.variantNames,
                        selectedVariant = uiState.selectedVariant,
                        onCycleVariant = { viewModel.cycleVariant() },
                        commands = uiState.commands,
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
                                    viewModel.createNewSession { session ->
                                        if (session != null) {
                                            onNavigateToSession(session.id)
                                        } else {
                                            coroutineScope.launch {
                                                snackbarHostState.showSnackbar(context.getString(R.string.chat_session_create_failed))
                                            }
                                        }
                                    }
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
                        contextWindow = uiState.contextWindow,
                        lastContextTokens = uiState.lastContextTokens,
                        onStop = { viewModel.abortSession() }
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
                    // IME inset relative to content area. Some devices report 0 for
                    // ime.exclude(navigationBars), so keep a robust fallback to raw ime.
                    val imeBottomRaw = WindowInsets.ime.getBottom(density)
                    val navBottom = WindowInsets.navigationBars.getBottom(density)
                    val imeBottomPx = (imeBottomRaw - navBottom).coerceAtLeast(0).let { adjusted ->
                        if (adjusted == 0 && imeBottomRaw > 0) imeBottomRaw else adjusted
                    }
                    val imeBottomDp = with(density) { imeBottomPx.toDp() }
                    val overlayHeightDp = with(density) { terminalOverlayHeightPx.toDp() }

                    ModalNavigationDrawer(
                        drawerState = terminalDrawerState,
                        gesturesEnabled = true,
                        drawerContent = {
                            ModalDrawerSheet(
                                drawerContainerColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
                                drawerContentColor = MaterialTheme.colorScheme.onSurface,
                                drawerTonalElevation = 0.dp,
                                drawerShape = RoundedCornerShape(0.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .widthIn(min = 240.dp, max = 320.dp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .padding(vertical = 8.dp)
                                            .imePadding(),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                    LazyColumn(
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        items(terminalTabs, key = { it.id }) { tab ->
                                            val selected = tab.id == activeTerminalTabId
                                            val drawerItemShape = RoundedCornerShape(12.dp)
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(drawerItemShape)
                                                    .then(
                                                        if (isAmoled && selected) {
                                                            Modifier.border(
                                                                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                                                                drawerItemShape
                                                            )
                                                        } else Modifier
                                                    )
                                            ) {
                                                NavigationDrawerItem(
                                                    label = {
                                                        Row(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                                        ) {
                                                            Column(
                                                                modifier = Modifier.weight(1f),
                                                                verticalArrangement = Arrangement.spacedBy(3.dp)
                                                            ) {
                                                                Text(
                                                                    text = tab.title,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis,
                                                                    style = MaterialTheme.typography.titleMedium,
                                                                    fontWeight = FontWeight.SemiBold
                                                                )
                                                                if (!tab.connected) {
                                                                    Surface(
                                                                        shape = RoundedCornerShape(999.dp),
                                                                        color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                                                                    ) {
                                                                        Row(
                                                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                                                            verticalAlignment = Alignment.CenterVertically,
                                                                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                                                                        ) {
                                                                            Box(
                                                                                modifier = Modifier
                                                                                    .size(6.dp)
                                                                                    .background(MaterialTheme.colorScheme.error, CircleShape)
                                                                            )
                                                                            Text(
                                                                                text = "Offline",
                                                                                style = MaterialTheme.typography.labelSmall,
                                                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                                            )
    }
}
                                                                 }
                                                            }
                                                            if (!tab.connected) {
                                                                IconButton(
                                                                    onClick = {
                                                                        viewModel.reconnectTerminalTab(tab.id) { ok ->
                                                                            if (!ok) {
                                                                                coroutineScope.launch {
                                                                                    snackbarHostState.showSnackbar(context.getString(R.string.chat_terminal_connect_failed))
                                                                                }
                                                                            }
                                                                        }
                                                                    },
                                                                    modifier = Modifier.size(34.dp),
                                                                    colors = IconButtonDefaults.iconButtonColors(
                                                                        containerColor = if (isAmoled) {
                                                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
                                                                        } else {
                                                                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
                                                                        }
                                                                    )
                                                                ) {
                                                                    Icon(Icons.Default.Refresh, contentDescription = "Reconnect tab")
                                                                }
                                                            }
                                                            IconButton(
                                                                onClick = { viewModel.closeTerminalTab(tab.id) },
                                                                modifier = Modifier.size(34.dp),
                                                                colors = IconButtonDefaults.iconButtonColors(
                                                                    containerColor = if (isAmoled) {
                                                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
                                                                    } else {
                                                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                                                    }
                                                                )
                                                            ) {
                                                                Icon(Icons.Default.Close, contentDescription = "Close tab")
                                                            }
                                                        }
                                                    },
                                                    selected = selected,
                                                    shape = drawerItemShape,
                                                    colors = NavigationDrawerItemDefaults.colors(
                                                        selectedContainerColor = if (isAmoled) {
                                                            Color.Black
                                                        } else {
                                                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                                                        },
                                                        unselectedContainerColor = if (isAmoled) Color.Black else Color.Transparent,
                                                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                                        unselectedTextColor = MaterialTheme.colorScheme.onSurface
                                                    ),
                                                    onClick = {
                                                        viewModel.switchTerminalTab(tab.id)
                                                        coroutineScope.launch { terminalDrawerState.close() }
                                                    },
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                        }
                                    }

                                    HorizontalDivider()

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 4.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                viewModel.createTerminalTab { ok ->
                                                    if (!ok) {
                                                        coroutineScope.launch {
                                                            snackbarHostState.showSnackbar(context.getString(R.string.chat_terminal_connect_failed))
                                                        }
                                                    }
                                                }
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(40.dp),
                                            shape = RoundedCornerShape(10.dp),
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
                                            colors = ButtonDefaults.outlinedButtonColors(
                                                containerColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
                                                contentColor = MaterialTheme.colorScheme.onSurface
                                            )
                                        ) {
                                            Icon(Icons.Default.Add, contentDescription = null)
                                            Spacer(Modifier.width(6.dp))
                                            Text("New")
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                keyboardController?.show()
                                                coroutineScope.launch { terminalDrawerState.close() }
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(40.dp),
                                            shape = RoundedCornerShape(10.dp),
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
                                            colors = ButtonDefaults.outlinedButtonColors(
                                                containerColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
                                                contentColor = MaterialTheme.colorScheme.onSurface
                                            )
                                        ) {
                                            Icon(Icons.Default.Keyboard, contentDescription = null)
                                            Spacer(Modifier.width(6.dp))
                                            Text("Keyboard")
                                        }
                                    }

                                    }

                                    if (isAmoled) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.CenterEnd)
                                                .fillMaxHeight()
                                                .width(1.dp)
                                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
                                        )
                                    }
                                 }
                             }
                         }
                     ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            SessionTerminalInline(
                                emulator = viewModel.terminalEmulator,
                                terminalVersion = terminalVersion,
                                connected = terminalConnected,
                                focusRequester = terminalFocusRequester,
                                onSendInput = ::sendTerminalChunk,
                                onPaste = ::pasteClipboardToTerminal,
                                onResize = { cols, rows ->
                                    viewModel.resizeTerminal(cols, rows)
                                },
                                fontSizeSp = terminalFontSizeSp,
                                onFontSizeChange = viewModel::setTerminalFontSize,
                                contentBottomPadding = overlayHeightDp + imeBottomDp,
                                modifier = Modifier.fillMaxSize()
                            )

                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .fillMaxHeight()
                                    .padding(bottom = overlayHeightDp + imeBottomDp)
                                    .width(18.dp)
                                    .zIndex(0f)
                                    .pointerInput(terminalDrawerState) {
                                        detectTapGestures(
                                            onLongPress = {
                                                if (!terminalDrawerState.isOpen) {
                                                    coroutineScope.launch { terminalDrawerState.open() }
                                                }
                                            }
                                        )
                                    }
                                    .pointerInput(terminalDrawerState) {
                                        var dragged = 0f
                                        detectHorizontalDragGestures(
                                            onHorizontalDrag = { _, dragAmount ->
                                                if (terminalDrawerState.isOpen) return@detectHorizontalDragGestures
                                                dragged += dragAmount
                                                if (dragged > 2f) {
                                                    coroutineScope.launch { terminalDrawerState.open() }
                                                    dragged = 0f
                                                }
                                            },
                                            onDragEnd = { dragged = 0f },
                                            onDragCancel = { dragged = 0f }
                                        )
                                    }
                            )

                        TerminalKeyboardOverlay(
                            connected = terminalConnected,
                            ctrlLatched = terminalCtrlLatched,
                            altLatched = terminalAltLatched,
                            cursorApp = viewModel.terminalEmulator.cursorKeysApplicationMode,
                            onToggleDrawer = { coroutineScope.launch { terminalDrawerState.apply { if (isOpen) close() else open() } } },
                            onToggleCtrl = { terminalCtrlLatched = !terminalCtrlLatched },
                            onToggleAlt = { terminalAltLatched = !terminalAltLatched },
                            onSendInput = ::sendTerminalChunk,
                            onCtrlC = { viewModel.sendTerminalInput("\u0003") },
                            onClear = { viewModel.clearTerminalBuffer() },
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .zIndex(1f)
                                    .fillMaxWidth()
                                    .padding(bottom = imeBottomDp)
                                    .onSizeChanged { terminalOverlayHeightPx = it.height }
                            )

                        }
                    }
                }
                uiState.isLoading && uiState.messages.isEmpty() -> {
                    PulsingDotsIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                uiState.error != null && uiState.messages.isEmpty() -> {
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
                        ErrorPayloadContent(
                            text = uiState.error ?: stringResource(R.string.session_unknown_error),
                            textStyle = MaterialTheme.typography.bodyLarge,
                            textColor = MaterialTheme.colorScheme.error,
                        )
                        Button(onClick = { viewModel.loadMessages() }) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
                uiState.messages.isEmpty() && !uiState.isLoading -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.chat_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Text(
                            text = stringResource(R.string.chat_type_message),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }
                else -> {
                     val messageSpacing = if (LocalCompactMessages.current) 2.dp else 8.dp

                        // Use raw messages directly — each Message is one LazyColumn item.
                        val rawMessages = uiState.messages

                        // Compute turn groups every recompose (no remember!).
                        // SSE text-delta updates Part content without changing message IDs,
                        // so a remembered turnGroups would hold stale ChatMessage objects,
                        // breaking streaming (content appears in chunks) and causing flicker.
                        val turnGroups = computeTurnGroups(rawMessages)

                       // Filter: keep user messages + first assistant in each turn group
                       // to avoid zero-height items creating blank gaps from spacedBy
                       val displayItems = rawMessages.mapIndexedNotNull { index, msg ->
                           when {
                               msg.isUser -> index to msg
                               msg.isAssistant -> {
                                   val nextMsg = rawMessages.getOrNull(index + 1)
                                   if (nextMsg?.isAssistant != true) index to msg else null
                               }
                               else -> null
                           }
                       }

                       // SSE streaming detach: when busy, split the last display item out
                       // so LazyColumn only contains static historical messages.
                       val isStreaming = uiState.sessionStatus is SessionStatus.Busy
                       val (historicalItems, streamingItem) = if (isStreaming && displayItems.isNotEmpty()) {
                           displayItems.dropLast(1) to displayItems.last()
                       } else {
                           displayItems to null
                       }

                       if (uiState.sessionParentId == null) {
                           // Main session: Scaffold bottomBar contains ChatInputBar; content is LazyColumn + FAB
                           Column(
                               modifier = Modifier.fillMaxSize()
                           ) {
                               Box(
                                   modifier = Modifier
                                       .weight(1f)
                                       .fillMaxWidth()
                               ) {
                                   @OptIn(ExperimentalMaterial3Api::class)
                                   val pullToRefreshState = rememberPullToRefreshState()
                                    PullToRefreshBox(
                                        isRefreshing = uiState.isLoadingOlder,
                                        onRefresh = {
                                            if (uiState.hasOlderMessages) viewModel.loadOlderMessages()
                                        },
                                        state = pullToRefreshState,
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                    LazyColumn(
                                        state = listState,
                                        modifier = Modifier.fillMaxSize()
                                            .pointerInput(Unit) { detectTapGestures(onTap = { keyboardController?.hide() }) },
                                       contentPadding = PaddingValues(
                                           start = 12.dp,
                                           top = 8.dp,
                                           end = 12.dp,
                                          bottom = 8.dp
                                      ),
                                        reverseLayout = true,
                                        verticalArrangement = Arrangement.spacedBy(messageSpacing)
                        ) {
                            // reverseLayout=true: items declared first render at the BOTTOM.
                            // Visual order (top→bottom): oldest msgs → newest msgs → revert → pending.
                            // Declaration order is bottom-up: pending (bottom) → messages (top).

                            // Pending questions (declared first = bottom-most visually)
                            items(
                                uiState.pendingQuestions.reversed(),
                                key = { "question_${it.id}" }
                            ) { question ->
                                QuestionCard(
                                    question = question,
                                    onSubmit = { answers ->
                                        viewModel.replyToQuestion(question.id, answers)
                                    },
                                    onReject = {
                                        viewModel.rejectQuestion(question.id)
                                    }
                                )
                            }

                            // Pending permissions
                            items(
                                uiState.pendingPermissions.reversed(),
                                key = { "perm_${it.id}" }
                            ) { permission ->
                                PermissionCard(
                                    permission = permission,
                                    onOnce = { viewModel.replyToPermission(permission.id, "once") },
                                    onAlways = { viewModel.replyToPermission(permission.id, "always") },
                                    onReject = { viewModel.replyToPermission(permission.id, "reject") }
                                )
                            }

                           // Revert banner
                           if (uiState.revert != null) {
                               item(key = "revert_banner") {
                                   RevertBanner(onRedo = {
                                       viewModel.redoMessage { ok ->
                                           coroutineScope.launch {
                                               snackbarHostState.showSnackbar(
                                                   if (ok) context.getString(R.string.chat_messages_restored) else context.getString(R.string.chat_message_redo_failed)
                                               )
                                           }
                                       }
                                   })
                               }
                           }

                              // Chat messages: historicalItems.reversed() so newest is at index 0 (bottom in reverseLayout).
                              // Visual result: oldest at top, newest at bottom.
                              // (streaming item is rendered outside LazyColumn when active)
                              itemsIndexed(
                                 historicalItems.reversed(),
                                 key = { _, item -> item.second.message.id },
                                 contentType = { _, item -> if (item.second.isUser) "user" else "assistant" }
                             ) { _, (rawIndex, msg) ->
                                when {
                                    msg.isAssistant -> {
                                        val turnMessagesForMsg = turnGroups[rawIndex] ?: listOf(msg)
                                        // The first assistant in a turn IS the last (newest) message of the turn
                                        val isTurnLast = rawIndex == rawMessages.lastIndex || rawMessages.getOrNull(rawIndex + 1)?.isAssistant != true

                                        MessageCard(
                                            role = MessageCardRole.ASSISTANT,
                                            turnMessages = turnMessagesForMsg,
                                            currentMessage = msg,
                                            onViewSubSession = navigateToChildSessionWithSave,
                                            isAmoled = isAmoled,
                                            isTurnLast = isTurnLast,
                                            onCopyText = if (isTurnLast) {
                                                {
                                                    val messages = turnMessagesForMsg
                                                    val text = messages.flatMap { m ->
                                                        m.parts.filterIsInstance<Part.Text>().map { it.text }
                                                    }.joinToString("\n\n")
                                                    if (text.isNotBlank()) { markdownPreviewText = text }
                                                }
                                            } else null
                                        )
                                    }
                                    msg.isUser -> {
                                        val chatMessage = msg

                                        val isCompactionTrigger = chatMessage.parts.any { it is Part.Compaction }

                                        if (isCompactionTrigger) {
                                            var showRevertDialog by remember { mutableStateOf(false) }

                                            if (showRevertDialog) {
                                                AlertDialog(
                                                    onDismissRequest = { showRevertDialog = false },
                                                    title = { Text(stringResource(R.string.chat_revert_title)) },
                                                    text = { Text(stringResource(R.string.chat_revert_message)) },
                                                    confirmButton = {
                                                        TextButton(
                                                            onClick = {
                                                                showRevertDialog = false
                                                                viewModel.revertMessage(chatMessage.message.id) { ok ->
                                                                    coroutineScope.launch {
                                                                        snackbarHostState.showSnackbar(
                                                                            if (ok) context.getString(R.string.chat_messages_restored) else context.getString(R.string.chat_message_redo_failed)
                                                                        )
                                                                    }
                                                                }
                                                            }
                                                        ) {
                                                            Text(stringResource(R.string.chat_revert), color = MaterialTheme.colorScheme.error)
                                                        }
                                                    },
                                                    dismissButton = {
                                                        TextButton(onClick = { showRevertDialog = false }) {
                                                            Text(stringResource(R.string.cancel))
                                                        }
                                                    }
                                                )
                                            }

                                            @OptIn(ExperimentalFoundationApi::class)
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .combinedClickable(
                                                        onClick = { },
                                                        onLongClick = { showRevertDialog = true }
                                                    )
                                                    .padding(vertical = 4.dp, horizontal = 32.dp),
                                                horizontalArrangement = Arrangement.Center,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                HorizontalDivider(
                                                    modifier = Modifier.weight(1f),
                                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                                )
                                                Text(
                                                    text = stringResource(R.string.chat_summarized),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                                    modifier = Modifier.padding(horizontal = 12.dp)
                                                )
                                                HorizontalDivider(
                                                    modifier = Modifier.weight(1f),
                                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                                )
                                            }
                                            return@itemsIndexed
                                        }

                                        MessageCard(
                                            role = MessageCardRole.USER,
                                            currentMessage = chatMessage,
                                            isQueued = chatMessage.message.id in uiState.queuedMessageIds,
                                            onViewSubSession = navigateToChildSessionWithSave,
                                            onRevert = if (uiState.sessionParentId == null) {{
                                                val revertText = chatMessage.parts
                                                    .filterIsInstance<Part.Text>()
                                                    .joinToString("\n") { it.text }
                                                viewModel.revertMessage(chatMessage.message.id, revertText) { ok ->
                                                    coroutineScope.launch {
                                                        snackbarHostState.showSnackbar(
                                                            if (ok) context.getString(R.string.chat_message_reverted) else context.getString(R.string.chat_message_revert_failed)
                                                        )
                                                    }
                                                }
                                            }} else null,
                                            onCopyText = {
                                                val text = chatMessage.parts
                                                    .filterIsInstance<Part.Text>()
                                                    .joinToString("\n") { it.text }
                                                if (text.isNotBlank()) {
                                                    clipboardManager.setText(
                                                        androidx.compose.ui.text.AnnotatedString(text)
                                                    )
                                                    coroutineScope.launch {
                                                        snackbarHostState.showSnackbar(context.getString(R.string.chat_copied_clipboard))
                                                    }
                                                }
                                            },
                                            isAmoled = isAmoled
                                        )
                                    }
                                }
                            }
                                 }
                                  } // PullToRefreshBox
  
                         // Scroll-to-bottom FAB
                          if (!isAtBottom) {
                                         SmallFloatingActionButton(
                                             onClick = {
                                                 coroutineScope.launch {
                                                     // reverseLayout=true: item 0 = bottom (newest messages)
                                                     listState.snapToBottom()
                                                 }
                                             },
                                  modifier = Modifier
                                      .align(Alignment.BottomCenter)
                                      .padding(bottom = 8.dp),
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurface
                          ) {
                              Icon(
                                  Icons.Default.KeyboardArrowDown,
                                  contentDescription = stringResource(R.string.chat_scroll_bottom),
                                           modifier = Modifier.size(20.dp)
                                      )
                                  }
                               }

                         } // Box(weight) — contains PullToRefreshBox + FAB

                        // Streaming message card: detached from LazyColumn to prevent layout jitter
                        if (streamingItem != null) {
                            val (rawIndex, msg) = streamingItem
                            val turnMessagesForMsg = turnGroups[rawIndex] ?: listOf(msg)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 280.dp)
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                MessageCard(
                                    role = MessageCardRole.ASSISTANT,
                                    turnMessages = turnMessagesForMsg,
                                    currentMessage = msg,
                                    onViewSubSession = navigateToChildSessionWithSave,
                                    isAmoled = isAmoled,
                                    isTurnLast = true,
                                    onCopyText = null  // No copy while streaming
                                )
                            }
                        }
                    } // Column
                        } else {
                              // Sub-session (no input bar): just LazyColumn + FAB
                              Column(modifier = Modifier.fillMaxSize()) {
                                  Box(
                                      modifier = Modifier
                                          .weight(1f)
                                          .fillMaxWidth()
                                  ) {
                                  @OptIn(ExperimentalMaterial3Api::class)
                                  val pullToRefreshState = rememberPullToRefreshState()
                                   PullToRefreshBox(
                                       isRefreshing = uiState.isLoadingOlder,
                                       onRefresh = {
                                           if (uiState.hasOlderMessages) viewModel.loadOlderMessages()
                                       },
                                      state = pullToRefreshState,
                                      modifier = Modifier.fillMaxSize()
                                  ) {
                                  LazyColumn(
                                  state = listState,
                                  modifier = Modifier.fillMaxSize()
                                      .pointerInput(Unit) { detectTapGestures(onTap = { keyboardController?.hide() }) },
                                  contentPadding = PaddingValues(
                                      horizontal = 12.dp,
                                      vertical = 8.dp
                                  ),
                                    reverseLayout = true,
                                    verticalArrangement = Arrangement.spacedBy(messageSpacing)
                                 ) {
                                     // reverseLayout=true: items declared first render at the BOTTOM.
                                     // Visual order (top→bottom): oldest msgs → newest msgs → revert → pending.
                                     // Declaration order is bottom-up: pending (bottom) → messages (top).

                                     // Pending questions (declared first = bottom-most visually)
                                     items(
                                         uiState.pendingQuestions.reversed(),
                                         key = { "question_${it.id}" }
                                     ) { question ->
                                         QuestionCard(
                                             question = question,
                                             onSubmit = { answers ->
                                                 viewModel.replyToQuestion(question.id, answers)
                                             },
                                             onReject = {
                                                 viewModel.rejectQuestion(question.id)
                                             }
                                         )
                                     }

                                     // Pending permissions
                                     items(
                                         uiState.pendingPermissions.reversed(),
                                         key = { "perm_${it.id}" }
                                     ) { permission ->
                                         PermissionCard(
                                             permission = permission,
                                             onOnce = { viewModel.replyToPermission(permission.id, "once") },
                                             onAlways = { viewModel.replyToPermission(permission.id, "always") },
                                             onReject = { viewModel.replyToPermission(permission.id, "reject") }
                                         )
                                     }

                                     // Revert banner
                                     if (uiState.revert != null) {
                                         item(key = "revert_banner") {
                                             RevertBanner(onRedo = {
                                                 viewModel.redoMessage { ok ->
                                                     coroutineScope.launch {
                                                         snackbarHostState.showSnackbar(
                                                             if (ok) context.getString(R.string.chat_messages_restored) else context.getString(R.string.chat_message_redo_failed)
                                                         )
                                                     }
                                                 }
                                             })
                                         }
                                     }

                                      // Chat messages: historicalItems.reversed() so newest is at index 0 (bottom in reverseLayout).
                                      // Visual result: oldest at top, newest at bottom.
                                      // (streaming item is rendered outside LazyColumn when active)
                                      itemsIndexed(
                                        historicalItems.reversed(),
                                       key = { _, item -> item.second.message.id },
                                       contentType = { _, item -> if (item.second.isUser) "user" else "assistant" }
                                   ) { _, (rawIndex, msg) ->
                                      when {
                                            msg.isAssistant -> {
                                                val turnMessagesForMsg = turnGroups[rawIndex] ?: listOf(msg)
                                                val isTurnLast = rawIndex == rawMessages.lastIndex || rawMessages.getOrNull(rawIndex + 1)?.isAssistant != true

                                                MessageCard(
                                                    role = MessageCardRole.ASSISTANT,
                                                    turnMessages = turnMessagesForMsg,
                                                    currentMessage = msg,
                                                    onViewSubSession = navigateToChildSessionWithSave,
                                                    isAmoled = isAmoled,
                                                    isTurnLast = isTurnLast,
                                                    onCopyText = if (isTurnLast) {
                                                        {
                                                            val messages = turnMessagesForMsg
                                                            val text = messages.flatMap { m ->
                                                                m.parts.filterIsInstance<Part.Text>().map { it.text }
                                                            }.joinToString("\n\n")
                                                            if (text.isNotBlank()) { markdownPreviewText = text }
                                                        }
                                                    } else null
                                                )
                                            }
                                            msg.isUser -> {
                                               val chatMessage = msg

                                               // Detect compaction trigger messages (user messages with Part.Compaction)
                                               val isCompactionTrigger = chatMessage.parts.any { it is Part.Compaction }

                                              // Show compact system-style divider for compaction triggers
                                              if (isCompactionTrigger) {
                                                  var showRevertDialog by remember { mutableStateOf(false) }

                                                  if (showRevertDialog) {
                                                      AlertDialog(
                                                          onDismissRequest = { showRevertDialog = false },
                                                          title = { Text(stringResource(R.string.chat_revert_title)) },
                                                          text = { Text(stringResource(R.string.chat_revert_message)) },
                                                          confirmButton = {
                                                              TextButton(
                                                                  onClick = {
                                                                      showRevertDialog = false
                                                                      viewModel.revertMessage(chatMessage.message.id) { ok ->
                                                                          coroutineScope.launch {
                                                                              snackbarHostState.showSnackbar(
                                                                                  if (ok) context.getString(R.string.chat_messages_restored) else context.getString(R.string.chat_message_redo_failed)
                                                                              )
                                                                          }
                                                                      }
                                                                  }
                                                              ) {
                                                                  Text(stringResource(R.string.chat_revert), color = MaterialTheme.colorScheme.error)
                                                              }
                                                          },
                                                          dismissButton = {
                                                              TextButton(onClick = { showRevertDialog = false }) {
                                                                  Text(stringResource(R.string.cancel))
                                                              }
                                                          }
                                                      )
                                                  }

                                                  @OptIn(ExperimentalFoundationApi::class)
                                                  Row(
                                                      modifier = Modifier
                                                          .fillMaxWidth()
                                                          .combinedClickable(
                                                              onClick = { },
                                                              onLongClick = { showRevertDialog = true }
                                                          )
                                                          .padding(vertical = 4.dp, horizontal = 32.dp),
                                                      horizontalArrangement = Arrangement.Center,
                                                      verticalAlignment = Alignment.CenterVertically
                                                  ) {
                                                      HorizontalDivider(
                                                          modifier = Modifier.weight(1f),
                                                          color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                                      )
                                                      Text(
                                                          text = stringResource(R.string.chat_summarized),
                                                          style = MaterialTheme.typography.labelSmall,
                                                          color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                                          modifier = Modifier.padding(horizontal = 12.dp)
                                                      )
                                                      HorizontalDivider(
                                                          modifier = Modifier.weight(1f),
                                                          color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                                      )
                                                  }
                                                  return@itemsIndexed
                                              }

                                               MessageCard(
                                                   role = MessageCardRole.USER,
                                                   currentMessage = chatMessage,
                                                   isQueued = chatMessage.message.id in uiState.queuedMessageIds,
                                                   onViewSubSession = navigateToChildSessionWithSave,
                                                   onRevert = null,
                                                   onCopyText = {
                                                       val text = chatMessage.parts
                                                           .filterIsInstance<Part.Text>()
                                                           .joinToString("\n") { it.text }
                                                       if (text.isNotBlank()) {
                                                           clipboardManager.setText(
                                                               androidx.compose.ui.text.AnnotatedString(text)
                                                           )
                                                           coroutineScope.launch {
                                                               snackbarHostState.showSnackbar(context.getString(R.string.chat_copied_clipboard))
                                                           }
                                                       }
                                                   },
                                                   isAmoled = isAmoled
                                               )
                                          }
                                      }
                                   }
                                 }
                                  } // PullToRefreshBox

                              // Scroll-to-bottom FAB
                                     if (!isAtBottom) {
                                            SmallFloatingActionButton(
                                               onClick = {
                                                    coroutineScope.launch {
                                                        // reverseLayout=true: item 0 = bottom (newest messages)
                                                        listState.snapToBottom()
                                                    }
                                               },
                                           modifier = Modifier
                                               .align(Alignment.BottomCenter)
                                              .padding(bottom = 8.dp),
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        contentColor = MaterialTheme.colorScheme.onSurface
                                  ) {
                                      Icon(
                                          Icons.Default.KeyboardArrowDown,
                                          contentDescription = stringResource(R.string.chat_scroll_bottom),
                                          modifier = Modifier.size(20.dp)
                                      )
                                  }
                              }

                          } // Box(weight)

                          // Streaming message card for sub-session
                          if (streamingItem != null) {
                              val (rawIndex, msg) = streamingItem
                              val turnMessagesForMsg = turnGroups[rawIndex] ?: listOf(msg)
                              Box(
                                  modifier = Modifier
                                      .fillMaxWidth()
                                      .heightIn(max = 280.dp)
                                      .verticalScroll(rememberScrollState())
                                      .padding(horizontal = 12.dp, vertical = 4.dp)
                              ) {
                                  MessageCard(
                                      role = MessageCardRole.ASSISTANT,
                                      turnMessages = turnMessagesForMsg,
                                      currentMessage = msg,
                                      onViewSubSession = navigateToChildSessionWithSave,
                                      isAmoled = isAmoled,
                                      isTurnLast = true,
                                      onCopyText = null  // No copy while streaming
                                  )
                              }
                          }
                          } // Column
                      }
                  }
              }
          }
       }


    // Model picker dialog
    if (showModelPicker) {
        ModelPickerDialog(
            providers = uiState.providers,
            selectedProviderId = uiState.selectedProviderId,
            selectedModelId = uiState.selectedModelId,
            onSelect = { providerId, modelId ->
                viewModel.selectModel(providerId, modelId)
                showModelPicker = false
            },
            onDismiss = { showModelPicker = false }
        )
    }

    // Markdown preview dialog (copy/view assistant message)
    markdownPreviewText?.let { previewText ->
        MarkdownPreviewDialog(
            markdown = previewText,
            onDismiss = { markdownPreviewText = null },
            onCopyAll = {
                clipboardManager.setText(AnnotatedString(previewText))
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(context.getString(R.string.chat_copied_clipboard))
                }
            }
        )
    }

    // Rename dialog
    if (showRenameDialog) {
        var renameText by remember { mutableStateOf(uiState.sessionTitle) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(stringResource(R.string.session_rename)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text(stringResource(R.string.session_rename_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.renameSession(renameText) { ok ->
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    if (ok) context.getString(R.string.chat_session_renamed) else context.getString(R.string.chat_session_rename_failed)
                                )
                            }
                        }
                        showRenameDialog = false
                    },
                    enabled = renameText.isNotBlank()
                ) {
                    Text(stringResource(R.string.session_rename_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Send confirmation dialog
    if (showSendConfirmDialog) {
        AlertDialog(
            onDismissRequest = {
                showSendConfirmDialog = false
                pendingSendAction = null
            },
            title = { Text(stringResource(R.string.settings_confirm_send_title)) },
            text = { Text(stringResource(R.string.settings_confirm_send_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showSendConfirmDialog = false
                    pendingSendAction?.invoke()
                    pendingSendAction = null
                }) {
                    Text(stringResource(R.string.settings_send))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSendConfirmDialog = false
                    pendingSendAction = null
                }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
    } // CompositionLocalProvider
}

/**
 * Scrolls the LazyColumn to the absolute bottom.
 * With reverseLayout=true, "bottom" = item 0.
 * Uses scrollToItem(0) + scrollBy to overcome any remaining offset.
 */
private suspend fun LazyListState.snapToBottom() {
    scrollToItem(0)
    while (canScrollBackward) {
        scroll { scrollBy(-10_000f) }
    }
}
