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
import dev.minios.ocremote.data.api.AgentInfo
import dev.minios.ocremote.data.api.CommandInfo
import dev.minios.ocremote.data.api.PromptPart
import dev.minios.ocremote.data.api.ProviderInfo
import dev.minios.ocremote.data.api.ProviderModel
import dev.minios.ocremote.MainActivity
import dev.minios.ocremote.ui.theme.CodeTypography
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.drop
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
import dev.minios.ocremote.ui.screens.chat.util.consumeBoundaryFling
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
import dev.minios.ocremote.ui.screens.chat.dialog.ImageThumbnailRow
import dev.minios.ocremote.ui.screens.chat.dialog.ImagePreviewDialog
import dev.minios.ocremote.ui.screens.chat.dialog.QuestionCard
import dev.minios.ocremote.ui.screens.chat.dialog.PermissionCard


/**
 * Chat Screen - conversation view with native markdown rendering.
 * Shows messages with streaming text rendered via mikepenz markdown renderer.
 */

// CompositionLocals moved to util/ChatCompositionLocals.kt


// isAmoledTheme & toolOutputContainerColor moved to util/ChatColors.kt

// performHaptic moved to util/ChatModifiers.kt

// agentColorCycle, QueuedBadgeColor, QueuedBadgeTextColor, agentColor moved to util/ChatColors.kt

// codeHorizontalScroll moved to util/ChatModifiers.kt

// consumeBoundaryFling moved to util/ChatModifiers.kt

/**
 * Slash command definition for the suggestion popup.
 * @param name Command name without the "/" prefix
 * @param description Human-readable description
 * @param type "server" commands are sent via API, "client" commands trigger local actions
 */
private data class SlashCommand(
    val name: String,
    val description: String?,
    val type: String // "server" or "client"
)

private enum class ChatInputMode {
    NORMAL,
    SHELL
}

/** Client-side slash commands that mirror the original opencode TUI. */
@Composable
private fun clientCommands(): List<SlashCommand> {
    return listOf(
        SlashCommand("new", stringResource(R.string.cmd_new), "client"),
        SlashCommand("compact", stringResource(R.string.cmd_compact), "client"),
        SlashCommand("fork", stringResource(R.string.cmd_fork), "client"),
        SlashCommand("share", stringResource(R.string.cmd_share), "client"),
        SlashCommand("unshare", stringResource(R.string.cmd_unshare), "client"),
        SlashCommand("undo", stringResource(R.string.cmd_undo), "client"),
        SlashCommand("redo", stringResource(R.string.cmd_redo), "client"),
        SlashCommand("rename", stringResource(R.string.cmd_rename), "client"),
        SlashCommand("shell", stringResource(R.string.cmd_shell_mode), "client"),
    )
}

/** Pulsing dots loading indicator — 3 dots that scale up/down in sequence. */
@Composable
internal fun PulsingDotsIndicator(
    modifier: Modifier = Modifier,
    dotSize: Dp = 10.dp,
    dotSpacing: Dp = 8.dp,
    color: Color = MaterialTheme.colorScheme.primary
) {
    val transition = rememberInfiniteTransition(label = "pulsing_dots")
    val scales = (0..2).map { index ->
        transition.animateFloat(
            initialValue = 0.4f,
            targetValue = 0.4f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = 1200
                    0.4f at 0
                    1.0f at 300
                    0.4f at 600
                    0.4f at 1200
                },
                repeatMode = RepeatMode.Restart
            ),
            label = "dot_$index"
        )
    }
    // Stagger: shift each dot's time by reading at offset phase
    val phaseShift = 150 // ms between dots
    val scales2 = (0..2).map { index ->
        transition.animateFloat(
            initialValue = 0.4f,
            targetValue = 0.4f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = 1200
                    val offset = index * phaseShift
                    0.4f at 0 + offset
                    1.0f at 300 + offset
                    0.4f at 600 + offset
                    0.4f at 1200
                },
                repeatMode = RepeatMode.Restart
            ),
            label = "dot_scale_$index"
        )
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(dotSpacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        scales2.forEach { scale ->
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .graphicsLayer {
                        scaleX = scale.value
                        scaleY = scale.value
                        alpha = 0.3f + 0.7f * ((scale.value - 0.4f) / 0.6f)
                    }
                    .background(color, CircleShape)
            )
        }
    }
}

/** Breathing circle loading indicator — single circle that pulses smoothly. */
@Composable
private fun BreathingCircleIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    color: Color = MaterialTheme.colorScheme.primary
) {
    val transition = rememberInfiniteTransition(label = "breathing_circle")
    val scale by transition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "circle_scale"
    )
    val alpha by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "circle_alpha"
    )
    
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                }
                .background(color, CircleShape)
        )
    }
}

/** Format a token count to a human-readable string (e.g., 1.2k, 45.3k, 1.2M). */
// formatTokenCount & formatAssistantErrorMessage moved to util/ChatFormatters.kt

private enum class HtmlErrorViewMode {
    Page,
    Code,
}

@Composable
internal fun ErrorPayloadContent(
    text: String,
    textStyle: TextStyle,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    if (!looksLikeHtmlPayload(text)) {
        SelectionContainer {
            Text(
                text = text,
                style = textStyle,
                color = textColor,
                modifier = modifier,
            )
        }
        return
    }

    var mode by rememberSaveable(text) { mutableStateOf(HtmlErrorViewMode.Code) }
    val htmlForPreview = remember(text) { normalizeHtmlForEmbeddedPreview(text) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == HtmlErrorViewMode.Code,
                onClick = { mode = HtmlErrorViewMode.Code },
                label = { Text(stringResource(R.string.chat_error_view_code)) },
            )
            FilterChip(
                selected = mode == HtmlErrorViewMode.Page,
                onClick = { mode = HtmlErrorViewMode.Page },
                label = { Text(stringResource(R.string.chat_error_view_page)) },
            )
        }

        if (mode == HtmlErrorViewMode.Page) {
            val isAmoled = isAmoledTheme()
            val bgColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = false
                        settings.domStorageEnabled = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.setSupportMultipleWindows(false)
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.textZoom = 85
                        settings.builtInZoomControls = false
                        settings.displayZoomControls = false
                        webViewClient = WebViewClient()
                        setOnTouchListener { v, event ->
                            if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) {
                                v.parent?.requestDisallowInterceptTouchEvent(true)
                            }
                            false
                        }
                        setBackgroundColor(bgColor.toArgb())
                    }
                },
                update = { webView ->
                    if (webView.tag != htmlForPreview) {
                        webView.tag = htmlForPreview
                        webView.loadDataWithBaseURL(
                            "https://localhost/",
                            htmlForPreview,
                            "text/html",
                            "UTF-8",
                            null,
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp, max = 360.dp)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(8.dp),
                    )
                    .clip(RoundedCornerShape(8.dp)),
            )
        } else {
            SelectionContainer {
                Text(
                    text = text,
                    style = textStyle,
                    color = textColor,
                )
            }
        }
    }
}

/**
 * VisualTransformation that highlights confirmed @file mentions as colored pills.
 * Only paths present in [confirmedFilePaths] are highlighted; unconfirmed @queries
 * remain unstyled so the user can see they haven't been selected yet.
 */
private class FileMentionVisualTransformation(
    private val confirmedFilePaths: Set<String>,
    private val highlightColor: Color,
    private val bgColor: Color
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (confirmedFilePaths.isEmpty()) {
            return TransformedText(text, OffsetMapping.Identity)
        }
        val raw = text.text
        val annotated = buildAnnotatedString {
            append(raw)
            // For each confirmed path, find all occurrences of @path in the text
            for (path in confirmedFilePaths) {
                val needle = "@$path"
                var searchFrom = 0
                while (true) {
                    val idx = raw.indexOf(needle, searchFrom)
                    if (idx == -1) break
                    // Ensure the match is not part of a longer token:
                    // next char after needle should be whitespace, end-of-string, or another @
                    val endIdx = idx + needle.length
                    if (endIdx < raw.length) {
                        val next = raw[endIdx]
                        if (!next.isWhitespace() && next != '@') {
                            searchFrom = endIdx
                            continue
                        }
                    }
                    addStyle(
                        SpanStyle(
                            color = highlightColor,
                            background = bgColor,
                            fontWeight = FontWeight.SemiBold
                        ),
                        start = idx,
                        end = endIdx
                    )
                    searchFrom = endIdx
                }
            }
        }
        return TransformedText(annotated, OffsetMapping.Identity)
    }
}

/**
 * Splits raw input text into a list of [PromptPart] objects.
 * Text around confirmed @file mentions becomes type="text" parts,
 * and each @file mention becomes a type="file" part with a file:// URL.
 */
private fun buildPromptParts(
    text: String,
    confirmedPaths: Set<String>,
    sessionDirectory: String?
): List<PromptPart> {
    if (confirmedPaths.isEmpty()) {
        val trimmed = text.trim()
        return if (trimmed.isEmpty()) emptyList()
        else listOf(PromptPart(type = "text", text = trimmed))
    }

    // Find all confirmed @path mentions with their positions
    data class Mention(val start: Int, val end: Int, val path: String)
    val mentions = mutableListOf<Mention>()

    for (path in confirmedPaths) {
        val needle = "@$path"
        var searchFrom = 0
        while (true) {
            val idx = text.indexOf(needle, searchFrom)
            if (idx == -1) break
            val endIdx = idx + needle.length
            // Boundary check: next char must be whitespace, end-of-string, or @
            if (endIdx < text.length) {
                val next = text[endIdx]
                if (!next.isWhitespace() && next != '@') {
                    searchFrom = endIdx
                    continue
                }
            }
            mentions.add(Mention(idx, endIdx, path))
            searchFrom = endIdx
        }
    }

    if (mentions.isEmpty()) {
        val trimmed = text.trim()
        return if (trimmed.isEmpty()) emptyList()
        else listOf(PromptPart(type = "text", text = trimmed))
    }

    // Sort by position
    mentions.sortBy { it.start }

    val parts = mutableListOf<PromptPart>()
    var cursor = 0

    for (mention in mentions) {
        // Add text before this mention
        if (mention.start > cursor) {
            val segment = text.substring(cursor, mention.start).trim()
            if (segment.isNotEmpty()) {
                parts.add(PromptPart(type = "text", text = segment))
            }
        }
        // Add file part
        val isDir = mention.path.endsWith("/")
        val absPath = if (sessionDirectory != null) "$sessionDirectory/${mention.path}" else mention.path
        val displayName = mention.path.trimEnd('/').substringAfterLast('/')
        parts.add(
            PromptPart(
                type = "file",
                path = mention.path,
                mime = if (isDir) "application/x-directory" else "text/plain",
                url = "file:///$absPath",
                filename = displayName
            )
        )
        cursor = mention.end
    }

    // Trailing text
    if (cursor < text.length) {
        val segment = text.substring(cursor).trim()
        if (segment.isNotEmpty()) {
            parts.add(PromptPart(type = "text", text = segment))
        }
    }

    return parts
}

// ImageAttachment, ImageSaveRequest, PreparedAttachment, AttachmentComparison,
// decodeDataUrlBytes, decodePartFileBytes, extensionForMime, imageThumbnailModel,
// estimateVisionTokens, formatFileSize, buildAttachmentFromUri
// moved to util/MediaUtils.kt and util/ChatFormatters.kt

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

    // Auto-scroll state must be declared before scroll-restore LaunchedEffect
    var autoScrollEnabled by remember { mutableStateOf(true) }

    // Restore scroll position when returning from sub-session navigation.
    // Using LaunchedEffect(scrollRestoreVersion) instead of rememberLazyListState(initial...)
    // because `remember` caches the initial state and ignores new values on recomposition,
    // causing unreliable restoration when the composable is recomposed (not recreated).
    LaunchedEffect(viewModel.scrollRestoreVersion) {
        if (viewModel.scrollRestoreVersion > 0) {
            autoScrollEnabled = false
            listState.scrollToItem(
                viewModel.savedFirstVisibleItemIndex,
                viewModel.savedFirstVisibleItemScrollOffset
            )
            // scrollToItem 是挂起函数，返回时滚动已完成、listState 已更新
            // 根据恢复后的实际位置正确设置 autoScrollEnabled
            autoScrollEnabled = listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset <= 50
        }
    }

    // Wrapper that saves scroll position before navigating to a sub-session
    val navigateToChildSessionWithSave: (String) -> Unit = { childSessionId ->
        viewModel.saveScrollPosition(
            listState.firstVisibleItemIndex,
            listState.firstVisibleItemScrollOffset
        )
        onNavigateToChildSession(childSessionId)
    }

    var showModelPicker by remember { mutableStateOf(false) }
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
    var terminalOverlayHeightPx by remember { mutableStateOf(0) }

    // Dismiss keyboard on scroll (hide-only, never show)
    var lastScrollIndex by remember { mutableIntStateOf(listState.firstVisibleItemIndex) }
    LaunchedEffect(Unit) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .drop(1)  // skip initial state to avoid false trigger on composition
            .collect { index ->
                if (index != lastScrollIndex) {
                    keyboardController?.hide()
                    lastScrollIndex = index
                }
            }
    }

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

    // Refresh session when returning from background (lock screen / app switch)
    var hasResumedOnce by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (hasResumedOnce) {
                    viewModel.refreshSession()
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

    // (autoScrollEnabled declared earlier, near listState)

    val messageCount = uiState.messages.size

    // Scroll to bottom when new messages arrive
    LaunchedEffect(messageCount) {
        if (messageCount > 0 && autoScrollEnabled) {
            listState.scrollToItem(0)
        }
    }

    // Detect user scrolling → toggle auto-scroll
    LaunchedEffect(Unit) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { (index, offset) ->
            if (index > 0 || offset > 50) {
                autoScrollEnabled = false
            } else {
                autoScrollEnabled = true
            }
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
        LocalOnToggleToolExpanded provides { viewModel.toggleToolExpanded(it) },
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
                    // Terminal moved into the overflow (three-dot) menu below
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
                                // Scroll to bottom after sending
                                coroutineScope.launch {
                                    autoScrollEnabled = true
                                    listState.scrollToItem(0)
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
                     val messageSpacing = if (LocalCompactMessages.current) 4.dp else 12.dp

                      // Tag each assistant message with whether it follows another assistant
                      // in reverseLayout chronological order. Used for visual continuity:
                      // consecutive assistants share reduced spacing, no card separation.
                      val isAssistantContinuation = remember(uiState.messages.size) {
                          uiState.messages.mapIndexed { index, msg ->
                              val prevMsg = uiState.messages.getOrNull(index + 1)
                              msg.isAssistant && prevMsg?.isAssistant == true
                          }
                      }
                      // Use raw messages directly — each Message is one LazyColumn item.
                      val rawMessages = uiState.messages

                      if (uiState.sessionParentId == null) {
                          // Main session: Scaffold bottomBar contains ChatInputBar; content is LazyColumn + FAB
                          Box(
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
                          // reverseLayout=true: items declared first render at the BOTTOM
                          // Visual order (top→bottom): load_older → old msgs → new msgs → revert → pending

                          // Pending questions (bottom-most, declared first = index 0)
                          items(
                              uiState.pendingQuestions,
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
                              uiState.pendingPermissions,
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

                            // Chat messages: newest-first (rawMessages is sorted newest-first).
                            // In reverseLayout=true, newest at index 0 = bottom. Correct.
                            itemsIndexed(
                                rawMessages,
                                key = { _, msg -> msg.message.id },
                                contentType = { _, msg -> if (msg.isUser) "user" else "assistant" }
                            ) { index, msg ->
                                when {
                                    msg.isAssistant -> {
                                        val isContinuation = isAssistantContinuation.getOrElse(index) { false }
                                        AssistantMessageCard(
                                            chatMessage = msg,
                                            isContinuation = isContinuation,
                                            onViewSubSession = navigateToChildSessionWithSave,
                                            onCopyText = {
                                                val text = msg.parts.filterIsInstance<Part.Text>()
                                                    .joinToString("\n") { it.text }
                                                if (text.isNotBlank()) {
                                                    clipboardManager.setText(
                                                        androidx.compose.ui.text.AnnotatedString(text)
                                                    )
                                                    coroutineScope.launch {
                                                        snackbarHostState.showSnackbar(context.getString(R.string.chat_copied_clipboard))
                                                    }
                                                }
                                            }
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

                                        ChatMessageBubble(
                                            chatMessage = chatMessage,
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
                                            }
                                        )
                                    }
                                }
                            }

                          // "Load earlier messages" at the TOP (declared last in reverseLayout = topmost)
                          if (uiState.hasOlderMessages) {
                              item(key = "load_older") {
                                  Box(
                                      modifier = Modifier
                                          .fillMaxWidth()
                                          .padding(vertical = 4.dp),
                                      contentAlignment = Alignment.Center
                                  ) {
                                      if (uiState.isLoadingOlder) {
                                          Row(
                                              horizontalArrangement = Arrangement.spacedBy(8.dp),
                                              verticalAlignment = Alignment.CenterVertically
                                          ) {
                                              PulsingDotsIndicator(
                                                  dotSize = 6.dp,
                                                  dotSpacing = 4.dp,
                                                  color = MaterialTheme.colorScheme.onSurfaceVariant
                                              )
                                              Text(
                                                  text = stringResource(R.string.chat_loading_earlier),
                                                  style = MaterialTheme.typography.bodySmall,
                                                  color = MaterialTheme.colorScheme.onSurfaceVariant
                                              )
                                          }
                                      } else {
                                          TextButton(onClick = {
                                              viewModel.loadOlderMessages()
                                          }) {
                                              Text(stringResource(R.string.chat_load_earlier))
                                          }
                                      }
                                  }
                              }
                          }
                          }
   
                        // Scroll-to-bottom FAB
                         if (!autoScrollEnabled) {
                            SmallFloatingActionButton(
                                onClick = {
                                    coroutineScope.launch {
                                        listState.scrollToItem(0)
                                        autoScrollEnabled = true
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

                           }
                       } else {
                            // Sub-session (no input bar): just LazyColumn + FAB
                            Box(modifier = Modifier.fillMaxSize()) {
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
                                   // reverseLayout=true: items declared first render at the BOTTOM
                                   // Visual order (top→bottom): load_older → old msgs → new msgs → revert → pending

                                  // Pending questions (bottom-most, declared first = index 0)
                                  items(
                                      uiState.pendingQuestions,
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
                                      uiState.pendingPermissions,
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

                                  // Chat messages: newest-first (rawMessages is sorted newest-first).
                                  // In reverseLayout=true, newest at index 0 = bottom. Correct.
                                  itemsIndexed(
                                      rawMessages,
                                      key = { _, msg -> msg.message.id },
                                      contentType = { _, msg -> if (msg.isUser) "user" else "assistant" }
                                  ) { index, msg ->
                                      when {
                                          msg.isAssistant -> {
                                              val isContinuation = isAssistantContinuation.getOrElse(index) { false }
                                              AssistantMessageCard(
                                                  chatMessage = msg,
                                                  isContinuation = isContinuation,
                                                  onViewSubSession = navigateToChildSessionWithSave,
                                                  onCopyText = {
                                                      val text = msg.parts.filterIsInstance<Part.Text>()
                                                          .joinToString("\n") { it.text }
                                                      if (text.isNotBlank()) {
                                                          clipboardManager.setText(
                                                              androidx.compose.ui.text.AnnotatedString(text)
                                                          )
                                                          coroutineScope.launch {
                                                              snackbarHostState.showSnackbar(context.getString(R.string.chat_copied_clipboard))
                                                          }
                                                      }
                                                  }
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

                                              ChatMessageBubble(
                                                  chatMessage = chatMessage,
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
                                                  }
                                              )
                                          }
                                      }
                                  }

                                  // "Load earlier messages" at the TOP (declared last in reverseLayout = topmost)
                                  if (uiState.hasOlderMessages) {
                                      item(key = "load_older") {
                                          Box(
                                              modifier = Modifier
                                                  .fillMaxWidth()
                                                  .padding(vertical = 4.dp),
                                              contentAlignment = Alignment.Center
                                          ) {
                                              if (uiState.isLoadingOlder) {
                                                  Row(
                                                      horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                      verticalAlignment = Alignment.CenterVertically
                                                  ) {
                                                      PulsingDotsIndicator(
                                                          dotSize = 6.dp,
                                                          dotSpacing = 4.dp,
                                                          color = MaterialTheme.colorScheme.onSurfaceVariant
                                                      )
                                                      Text(
                                                          text = stringResource(R.string.chat_loading_earlier),
                                                          style = MaterialTheme.typography.bodySmall,
                                                          color = MaterialTheme.colorScheme.onSurfaceVariant
                                                      )
                                                  }
                                              } else {
                                                  TextButton(onClick = {
                                                      viewModel.loadOlderMessages()
                                                  }) {
                                                      Text(stringResource(R.string.chat_load_earlier))
                                                  }
                                              }
                                          }
                                      }
                                  }
                              }
 
                              // Scroll-to-bottom FAB
                                if (!autoScrollEnabled) {
                                    SmallFloatingActionButton(
                                        onClick = {
                                            coroutineScope.launch {
                                                listState.scrollToItem(0)
                                                autoScrollEnabled = true
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

                         }
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

// ModelPickerDialog moved to dialog/ModelPickerDialog.kt


// resolveStepsStatus moved to util/ChatFormatters.kt

// isBubbleRenderablePart and filterRenderableParts are defined in ChatParts.kt.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatMessageBubble(
    chatMessage: ChatMessage,
    isQueued: Boolean = false,
    onViewSubSession: ((String) -> Unit)? = null,
    onRevert: (() -> Unit)? = null,
    onCopyText: (() -> Unit)? = null
) {
    val isAmoled = isAmoledTheme()
    val backgroundColor = if (isAmoled) {
        Color.Black
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val textColor = if (isAmoled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }
    val bubbleBorder = if (isAmoled) {
        BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        )
    } else {
        null
    }
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current

    // Filter visible parts for user messages
    val visibleParts = chatMessage.parts.filter { part ->
        when (part) {
            is Part.Text -> part.synthetic != true && part.ignored != true && part.text.isNotBlank()
            else -> true
        }
    }

    val userMessage = chatMessage.message as? Message.User
    val userFallbackText = userMessage?.summary?.body?.takeIf { it.isNotBlank() }
        ?: userMessage?.summary?.title?.takeIf { it.isNotBlank() }
    val userCommandLabel = resolveUserCommandLabel(chatMessage.parts)

    val contentParts = visibleParts

    val hasRenderableUserPart = contentParts.any(::isBubbleRenderablePart)
    if (!hasRenderableUserPart && userFallbackText == null && userCommandLabel == null) {
        return
    }

        val bubbleContent: @Composable () -> Unit = {
        Surface(
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 4.dp,
                bottomStart = 18.dp,
                bottomEnd = 18.dp
            ),
            color = backgroundColor,
            border = bubbleBorder,
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            val compact = LocalCompactMessages.current
            Column(
                    modifier = Modifier.padding(
                        horizontal = if (compact) 10.dp else 16.dp,
                        vertical = if (compact) 8.dp else 14.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 10.dp)
                ) {
                    // QUEUED badge for user messages
                    if (isQueued) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = QueuedBadgeColor,
                                modifier = Modifier.padding(end = 8.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.chat_queued),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = QueuedBadgeTextColor
                                    ),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    // Step/tool parts — not applicable for user messages (handled by AssistantTurnBubble)

                    // Content parts (text, reasoning, patches, etc.)
                    // Group image file parts into a compact thumbnail row
                    val imageFiles = contentParts.filterIsInstance<Part.File>()
                        .filter { it.mime.startsWith("image/") && !it.url.isNullOrBlank() }
                    val otherParts = contentParts.filter { part ->
                        !(part is Part.File && part.mime.startsWith("image/") && !part.url.isNullOrBlank())
                    }
                    val renderableOtherParts = otherParts.filter(::isBubbleRenderablePart)

                    // Render image thumbnails as a horizontal row
                    if (imageFiles.isNotEmpty()) {
                        ImageThumbnailRow(imageFiles = imageFiles)
                    }

                    // Render remaining parts
                    for (part in renderableOtherParts) {
                        key(part.id) {
                            PartContent(
                                part = part,
                                textColor = textColor,
                                isUser = true,
                                onViewSubSession = onViewSubSession
                            )
                        }
                    }

                    if (imageFiles.isEmpty() && renderableOtherParts.isEmpty() && userCommandLabel != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.RateReview,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = textColor.copy(alpha = 0.7f)
                            )
                            Text(
                                text = userCommandLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                color = textColor.copy(alpha = 0.85f)
                            )
                        }
                    }

                    // If text parts are absent but server provided a summary, render it.
                    if (visibleParts.isEmpty() && userFallbackText != null) {
                        Text(
                            text = userFallbackText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor.copy(alpha = 0.5f)
                        )
                    }
                }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End
    ) {
        if (onRevert != null) {
            // Swipe-to-revert for user messages with confirmation dialog
            var showRevertConfirmation by remember { mutableStateOf(false) }
            val hapticEnabled = LocalHapticFeedbackEnabled.current
            val bubbleView = LocalView.current

            val dismissState = rememberSwipeToDismissBoxState(
                confirmValueChange = { value ->
                    if (value != SwipeToDismissBoxValue.Settled) {
                        if (hapticEnabled) {
                            @Suppress("DEPRECATION")
                            bubbleView.performHapticFeedback(
                                android.view.HapticFeedbackConstants.LONG_PRESS,
                                android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or
                                        android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                            )
                        }
                        showRevertConfirmation = true
                    }
                    false // don't actually dismiss; wait for dialog confirmation
                }
            )

            if (showRevertConfirmation) {
                AlertDialog(
                    onDismissRequest = { showRevertConfirmation = false },
                    title = { Text(stringResource(R.string.chat_revert_title)) },
                    text = { Text(stringResource(R.string.chat_revert_message)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showRevertConfirmation = false
                                onRevert()
                            }
                        ) {
                            Text(stringResource(R.string.chat_revert), color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showRevertConfirmation = false }) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                )
            }

            SwipeToDismissBox(
                state = dismissState,
                backgroundContent = {
                    val direction = dismissState.dismissDirection
                    val bgColor = MaterialTheme.colorScheme.errorContainer
                    val iconAlignment = if (direction == SwipeToDismissBoxValue.StartToEnd) {
                        Alignment.CenterStart
                    } else {
                        Alignment.CenterEnd
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(
                                topStart = 18.dp,
                                topEnd = 4.dp,
                                bottomStart = 18.dp,
                                bottomEnd = 18.dp
                            ))
                            .background(bgColor)
                            .padding(horizontal = 20.dp),
                        contentAlignment = iconAlignment
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Undo,
                                contentDescription = stringResource(R.string.chat_revert),
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = stringResource(R.string.chat_revert),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                },
                enableDismissFromStartToEnd = true,
                enableDismissFromEndToStart = true
            ) {
                bubbleContent()
            }
        } else {
            // Provide pointer-input isolation boundary to prevent Markdown library's
            // pointerInput handlers (link handling, code background) from leaking
            // into LazyColumn's pointer scope and intercepting clicks on sibling
            // tool cards. In main sessions, SwipeToDismissBox provides this isolation.
            Box(modifier = Modifier.pointerInput(Unit) {
                // Intentionally empty — only creates a pointer-input scope boundary
            }) {
                bubbleContent()
            }
        }
    }
}

/** Format millisecond duration to human-readable string (e.g., "12.3s", "1.5m"). */
// formatDuration moved to util/ChatFormatters.kt

/**
 * Renders a SINGLE assistant message with its parts interleaved.
 * Unlike the old AssistantTurnBubble, this handles exactly one ChatMessage,
 * so streaming text updates only recompose THIS card, not all sibling messages.
 */
@Composable
private fun AssistantMessageCard(
    chatMessage: ChatMessage,
    isContinuation: Boolean,
    onViewSubSession: ((String) -> Unit)? = null,
    onCopyText: (() -> Unit)? = null,
) {
    val isAmoled = isAmoledTheme()
    val textColor = if (isAmoled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface

    val assistantMsg = chatMessage.message as? Message.Assistant
    val errorText = formatAssistantErrorMessage(assistantMsg?.error)
    val renderableParts = filterRenderableParts(chatMessage.parts)

    if (renderableParts.isEmpty() && errorText == null) return

    val compact = LocalCompactMessages.current
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (isContinuation) 0.dp else 0.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 4.dp)
        ) {
                // "Response" header — only on the first of a consecutive sequence
                if (!isContinuation) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            if (assistantMsg?.providerId != null) {
                                ProviderIcon(
                                    providerId = assistantMsg.providerId,
                                    size = 12.dp,
                                    tint = textColor.copy(alpha = 0.4f)
                                )
                            }
                            Text(
                                text = stringResource(R.string.chat_response),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    letterSpacing = 0.8.sp,
                                    fontWeight = FontWeight.Medium
                                ),
                                color = textColor.copy(alpha = 0.4f)
                            )
                            // Local timestamp from message creation time
                            assistantMsg?.time?.created?.let { createdMs ->
                                val timeText = remember(createdMs) {
                                    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                                        .format(java.util.Date(createdMs))
                                }
                                Text(
                                    text = timeText,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.sp
                                    ),
                                    color = textColor.copy(alpha = 0.3f)
                                )
                            }
                        }
                        if (onCopyText != null) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = stringResource(R.string.chat_copy),
                                modifier = Modifier
                                    .size(15.dp)
                                    .clickable { performHaptic(hapticView, hapticOn); onCopyText() },
                                tint = textColor.copy(alpha = 0.3f)
                            )
                        }
                    }
                }

                // Render parts in original order
                for (part in renderableParts) {
                    key(part.id) {
                        PartContent(
                            part = part,
                            textColor = textColor,
                            isUser = false,
                            onViewSubSession = onViewSubSession,
                            turnAgentName = if (part is Part.Tool && part.tool == "task") {
                                val agentParts = chatMessage.parts.filterIsInstance<Part.Agent>()
                                agentParts.firstOrNull()?.name?.takeIf { it.isNotBlank() }
                            } else null
                        )
                    }
                }

                // Token/cost/duration footer from all StepFinish parts in this message
                val stepFinishes = chatMessage.parts.filterIsInstance<Part.StepFinish>()
                if (stepFinishes.isNotEmpty()) {
                    val totalInput = stepFinishes.sumOf { it.tokens?.input ?: 0 }
                    val totalOutput = stepFinishes.sumOf { it.tokens?.output ?: 0 }
                    val totalCost = stepFinishes.sumOf { it.cost ?: 0.0 }
                    val hasTokenStats = totalInput > 0 || totalOutput > 0 || totalCost > 0.0

                    // Duration from message time
                    val durationMs = assistantMsg?.time?.let { t ->
                        t.completed?.let { end -> end - t.created }
                    }
                    val hasFooter = hasTokenStats || durationMs != null || !assistantMsg?.modelId.isNullOrBlank()

                    if (hasFooter) {
                        Spacer(modifier = Modifier.height(if (compact) 4.dp else 8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Provider icon (small, in footer)
                            if (assistantMsg?.providerId != null) {
                                ProviderIcon(
                                    providerId = assistantMsg.providerId,
                                    size = 10.dp,
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                                )
                            }
                            // Model name
                            if (!assistantMsg?.modelId.isNullOrBlank()) {
                                Text(
                                    text = assistantMsg.modelId,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                            }
                            // Duration
                            if (durationMs != null && durationMs > 0) {
                                Text(
                                    text = formatDuration(durationMs),
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                                )
                            }
                            // Tokens
                            if (totalInput > 0 || totalOutput > 0) {
                                Text(
                                    text = stringResource(R.string.chat_tokens_format, totalInput, totalOutput),
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                                )
                            }
                            // Cost
                            if (totalCost > 0.0 && totalCost.isFinite()) {
                                Text(
                                    text = stringResource(R.string.chat_cost_format, String.format("%.4f", totalCost)),
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                                )
                            }
                        }
                    }
                }

                // Error display
                if (errorText != null) {
                    Surface(
                        color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = if (isAmoled) 0.75f else 0.35f)),
                        tonalElevation = 0.dp,
                    ) {
                        ErrorPayloadContent(
                            text = errorText,
                            textStyle = MaterialTheme.typography.bodySmall,
                            textColor = textColor,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                        )
                    }
                }
            }
    }
}

@Composable
private fun AssistantTurnBubble(
    messages: List<ChatMessage>,
    onViewSubSession: ((String) -> Unit)? = null,
    onCopyText: (() -> Unit)? = null
) {
    val isAmoled = isAmoledTheme()
    val backgroundColor = if (isAmoled) {
        Color.Black
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val textColor = if (isAmoled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val bubbleBorder = if (isAmoled) {
        BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f)
        )
    } else {
        null
    }

    // Collect all renderable content from all messages in the turn
    // Keep parts in their original order so tool calls are interleaved with text/reasoning
    val allContent = remember(messages) {
        messages.mapNotNull { msg ->
            val parts = msg.parts
            val assistantMsg = msg.message as? Message.Assistant ?: return@mapNotNull null
            val errorText = formatAssistantErrorMessage(assistantMsg.error)

            val renderableParts = filterRenderableParts(parts)

            if (renderableParts.isEmpty() && errorText == null) {
                null
            } else {
                Pair(renderableParts, errorText to assistantMsg)
            }
        }
    }

    // Extract agent names from Part.Agent in each message's full parts list
    // Map: part.id -> agentName (for task tools to look up sibling agent names)
    val taskAgentNames = remember(messages) {
        val result = mutableMapOf<String, String?>()
        for (msg in messages) {
            val agentParts = msg.parts.filterIsInstance<Part.Agent>()
            val agentName = agentParts.firstOrNull()?.name?.takeIf { it.isNotBlank() }
            // Find all task tool parts in this message and associate with agentName
            msg.parts.filterIsInstance<Part.Tool>().filter { it.tool == "task" }.forEach { taskPart ->
                result[taskPart.id] = agentName
            }
        }
        result
    }

    if (allContent.isEmpty()) return

    // Use the first message's assistant info for the header
    val firstAssistant = messages.firstOrNull()?.message as? Message.Assistant

    val compact = LocalCompactMessages.current
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 4.dp,
                topEnd = 18.dp,
                bottomStart = 18.dp,
                bottomEnd = 18.dp
            ),
            color = backgroundColor,
            border = bubbleBorder,
            tonalElevation = if (isAmoled) 0.dp else 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(
                    horizontal = if (compact) 10.dp else 16.dp,
                    vertical = if (compact) 8.dp else 14.dp
                ),
                verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 10.dp)
            ) {
                // "Response" header with provider icon and copy button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        if (firstAssistant?.providerId != null) {
                            ProviderIcon(
                                providerId = firstAssistant.providerId,
                                size = 12.dp,
                                tint = textColor.copy(alpha = 0.4f)
                            )
                        }
                        Text(
                            text = stringResource(R.string.chat_response),
                            style = MaterialTheme.typography.labelSmall.copy(
                                letterSpacing = 0.8.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            color = textColor.copy(alpha = 0.4f)
                        )
                    }
                    if (onCopyText != null) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.chat_copy),
                            modifier = Modifier
                                .size(15.dp)
                                .clickable { performHaptic(hapticView, hapticOn); onCopyText() },
                            tint = textColor.copy(alpha = 0.3f)
                        )
                    }
                }

                // Render all messages' parts in original order (text, tool, reasoning interleaved)
                for ((renderableParts, errorPair) in allContent) {
                    val (errorText, assistantMsg) = errorPair

                    // Collect image files for thumbnail display while preserving order
                    for (part in renderableParts) {
                        key(part.id) {
                            PartContent(
                                part = part,
                                textColor = textColor,
                                isUser = false,
                                onViewSubSession = onViewSubSession,
                                turnAgentName = if (part is Part.Tool && part.tool == "task") taskAgentNames[part.id] else null
                            )
                        }
                    }

                    // Error display
                    if (errorText != null) {
                        Surface(
                            color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = if (isAmoled) 0.75f else 0.35f)),
                            tonalElevation = 0.dp,
                        ) {
                            ErrorPayloadContent(
                                text = errorText,
                                textStyle = MaterialTheme.typography.bodySmall,
                                textColor = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// isBubbleRenderablePart moved to ChatParts.kt for testability

// resolveUserCommandLabel moved to util/ChatFormatters.kt

/**
 * Banner shown when messages have been reverted.
 * Tapping restores (redo) the reverted messages.
 */
@Composable
private fun RevertBanner(onRedo: () -> Unit) {
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clickable { performHaptic(hapticView, hapticOn); onRedo() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Undo,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.chat_messages_reverted),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Text(
                    text = stringResource(R.string.chat_tap_restore),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                )
            }
            Icon(
                Icons.Default.Restore,
                contentDescription = stringResource(R.string.chat_restore),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

@Composable
private fun PartContent(
    part: Part,
    textColor: Color,
    isUser: Boolean = false,
    onViewSubSession: ((String) -> Unit)? = null,
    turnAgentName: String? = null
) {
    when (part) {
        is Part.Text -> {
            // Hide synthetic/ignored text parts (internal system content)
            if (part.text.isNotBlank() && part.synthetic != true && part.ignored != true) {
                MarkdownContent(
                    markdown = part.text,
                    textColor = textColor,
                    isUser = isUser
                )
            }
        }
        is Part.Reasoning -> {
            if (part.text.isNotBlank()) {
                val reasoningDuration = part.time?.let { t ->
                    t.end?.let { end -> end - t.start }
                }
                val toolExpandedStates = LocalToolExpandedStates.current
                val onToggleToolExpanded = LocalOnToggleToolExpanded.current
                ReasoningBlock(
                    text = part.text,
                    isExpanded = toolExpandedStates[part.id] ?: LocalExpandReasoning.current,
                    onToggleExpand = { onToggleToolExpanded(part.id) },
                    durationMs = reasoningDuration
                )
            }
        }
        is Part.Tool -> {
            // todoread parts are filtered out entirely (WebUI convention)
            val toolExpandedStates = LocalToolExpandedStates.current
            val onToggleToolExpanded = LocalOnToggleToolExpanded.current
            if (part.tool == "todoread") {
                // skip
            } else if (part.tool == "todowrite") {
                TodoListCard(
                    tool = part,
                    isExpanded = toolExpandedStates[part.id] ?: true,
                    onToggleExpand = { onToggleToolExpanded(part.id) }
                )
            } else {
                // Dispatch to tool-specific renderers (like WebUI)
                val autoExpand = LocalCollapseTools.current
                when (part.tool) {
                    "edit", "multiedit" -> EditToolCard(
                        tool = part,
                        isExpanded = toolExpandedStates[part.id] ?: autoExpand,
                        onToggleExpand = { onToggleToolExpanded(part.id) }
                    )
                    "write" -> WriteToolCard(
                        tool = part,
                        isExpanded = toolExpandedStates[part.id] ?: autoExpand,
                        onToggleExpand = { onToggleToolExpanded(part.id) }
                    )
                    "bash" -> BashToolCard(
                        tool = part,
                        isExpanded = toolExpandedStates[part.id] ?: autoExpand,
                        onToggleExpand = { onToggleToolExpanded(part.id) }
                    )
                    "read" -> ReadToolCard(
                        tool = part,
                        isExpanded = toolExpandedStates[part.id] ?: autoExpand,
                        onToggleExpand = { onToggleToolExpanded(part.id) }
                    )
                    "glob", "grep" -> SearchToolCard(
                        tool = part,
                        isExpanded = toolExpandedStates[part.id] ?: autoExpand,
                        onToggleExpand = { onToggleToolExpanded(part.id) }
                    )
                    "task" -> TaskToolCard(
                        tool = part,
                        onViewSubSession = onViewSubSession,
                        turnAgentName = turnAgentName,
                        isExpanded = toolExpandedStates[part.id] ?: autoExpand,
                        onToggleExpand = { onToggleToolExpanded(part.id) }
                    )
                    else -> ToolCallCard(
                        tool = part,
                        isExpanded = toolExpandedStates[part.id] ?: autoExpand,
                        onToggleExpand = { onToggleToolExpanded(part.id) }
                    )
                }
            }
        }
        is Part.StepStart -> {
            // Visual separator between steps (hidden - WebUI doesn't show these)
        }
        is Part.StepFinish -> {
            // Token/cost info is aggregated at the bottom of AssistantMessageCard
        }
        is Part.Patch -> {
            val autoExpand = LocalCollapseTools.current
            val toolExpandedStates = LocalToolExpandedStates.current
            val onToggleToolExpanded = LocalOnToggleToolExpanded.current
            PatchCard(
                patch = part,
                isExpanded = toolExpandedStates[part.id] ?: autoExpand,
                onToggleExpand = { onToggleToolExpanded(part.id) }
            )
        }
        is Part.File -> {
            FileCard(file = part)
        }
        is Part.Permission -> {
            Text(
                text = stringResource(R.string.chat_permission_label, part.message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
        is Part.Question -> {
            Text(
                text = stringResource(R.string.chat_question_inline, part.question),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
        is Part.Abort -> {
            Text(
                text = stringResource(R.string.chat_aborted, part.reason),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        is Part.Retry -> {
            Text(
                text = stringResource(R.string.chat_retry, part.attempt, part.errorMessage),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        // Ignore less relevant parts
        is Part.Snapshot, is Part.Subtask, is Part.Compaction,
        is Part.SessionTurn, is Part.Unknown -> { /* skip */ }
        is Part.Agent -> {
            val displayName = part.name.ifBlank { "Agent" }
            val displaySource = part.source?.jsonPrimitive?.contentOrNull ?: ""
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Android,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                if (displaySource.isNotBlank()) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = displaySource,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }
        }
    }
}@Composable
private fun ReasoningBlock(text: String, isExpanded: Boolean = false, onToggleExpand: () -> Unit = {}, durationMs: Long? = null) {
    val isAmoled = isAmoledTheme()
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current
    val expanded = isExpanded

    val accentColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
    val containerColor = when {
        isAmoled -> Color.Black
        else -> MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.7f)
    }
    val textColor = MaterialTheme.colorScheme.onSurface

    // Pulse animation for the thinking dot (runs only while durationMs == null = still thinking)
    val infiniteTransition = rememberInfiniteTransition(label = "thinkingPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes { durationMillis = 1200; 0.7f at 400; 0.4f at 800 },
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val isComplete = durationMs != null
    val headerText = if (isComplete && durationMs != null) {
        val dur = if (durationMs < 1000) "${durationMs}ms"
            else if (durationMs < 60000) "${"%.1f".format(durationMs / 1000.0)}s"
            else "${"%.1f".format(durationMs / 60000.0)}m"
        stringResource(R.string.chat_thinking_complete, dur)
    } else {
        stringResource(R.string.chat_status_thinking)
    }

    Surface(
        shape = RoundedCornerShape(0.dp),
        color = containerColor,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // Gradient left accent bar
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(end = 0.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(2.5.dp)
                        .fillMaxHeight()
                        .drawBehind {
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        accentColor,
                                        accentColor.copy(alpha = 0.15f)
                                    )
                                )
                            )
                        }
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { performHaptic(hapticView, hapticOn); onToggleExpand() }
                    .padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Animated pulse dot (shows only while thinking)
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .drawBehind {
                                    drawCircle(
                                        color = accentColor.copy(
                                            alpha = if (isComplete) 0.4f else pulseAlpha
                                        )
                                    )
                                }
                        )
                        Spacer(modifier = Modifier.width(7.dp))
                        Text(
                            text = headerText,
                            style = MaterialTheme.typography.labelMedium,
                            color = textColor.copy(alpha = 0.45f)
                        )
                    }

                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded)
                            stringResource(R.string.chat_collapse)
                        else
                            stringResource(R.string.chat_expand),
                        modifier = Modifier.size(18.dp),
                        tint = textColor.copy(alpha = 0.3f)
                    )
                }

                // Expandable content
                AnimatedVisibility(visible = expanded) {
                    Column {
                        Spacer(modifier = Modifier.height(10.dp))
                        val halfScreenHeight = LocalConfiguration.current.screenHeightDp.dp / 2
                        val reasoningScrollState = rememberScrollState()
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = halfScreenHeight)
                                .consumeBoundaryFling(reasoningScrollState)
                                .verticalScroll(reasoningScrollState)
                        ) {
                        MarkdownContent(
                            markdown = text,
                            textColor = textColor.copy(alpha = 0.55f),
                            isUser = false,
                            customFontSize = "small"
                        )
                        }
                    }
                }
            }
        }
    }
}

// ImageThumbnailRow & ImagePreviewDialog moved to dialog/ImagePreviewDialog.kt

@Composable
private fun FileCard(file: Part.File) {
    // Images are handled by ImageThumbnailRow, so FileCard only handles non-image files
    FileCardFallback(file)
}

@Composable
private fun FileCardFallback(file: Part.File) {
    val isAmoled = isAmoledTheme()
    val containerColor = if (isAmoled) {
        Color.Black
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val borderColor = if (isAmoled) {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.9f)
    }
    val contentColor = if (isAmoled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.AttachFile,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = file.filename
                    ?: file.url?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                    ?: file.mime,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// PermissionCard moved to dialog/PermissionRequestCard.kt

/** Rotating placeholder hints for the input bar, similar to the WebUI prompt input. */
private val placeholderHintResIds = listOf(
    R.string.chat_hint_ask,
    R.string.chat_hint_fix,
    R.string.chat_hint_refactor,
    R.string.chat_hint_tests,
    R.string.chat_hint_explain,
    R.string.chat_hint_help,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatInputBar(
    textFieldValue: TextFieldValue,
    onTextFieldValueChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    isSending: Boolean,
    isBusy: Boolean = false,
    messages: List<ChatMessage> = emptyList(),
    attachments: List<ImageAttachment> = emptyList(),
    onAttach: () -> Unit = {},
    onRemoveAttachment: (Int) -> Unit = {},
    onSaveAttachment: (bytes: ByteArray, mime: String, filename: String?) -> Unit = { _, _, _ -> },
    modelLabel: String = "",
    selectedProviderId: String? = null,
    onModelClick: () -> Unit = {},
    agents: List<AgentInfo> = emptyList(),
    selectedAgent: String = "build",
    onAgentSelect: (String) -> Unit = {},
    variantNames: List<String> = emptyList(),
    selectedVariant: String? = null,
    onCycleVariant: () -> Unit = {},
    commands: List<CommandInfo> = emptyList(),
    fileSearchResults: List<String> = emptyList(),
    confirmedFilePaths: Set<String> = emptySet(),
    onFileSelected: (String) -> Unit = {},
    onSlashCommand: (SlashCommand) -> Unit = {},
    inputMode: ChatInputMode = ChatInputMode.NORMAL,
    onInputModeChange: (ChatInputMode) -> Unit = {},
    contextWindow: Int = 0,
    lastContextTokens: Int = 0,
    onStop: () -> Unit = {}
) {
    val isAmoled = isAmoledTheme()
    val isShellMode = inputMode == ChatInputMode.SHELL
    // Rotate placeholder hint every 4 seconds
    val hintIndex = remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(4000)
            hintIndex.intValue = (hintIndex.intValue + 1) % placeholderHintResIds.size
        }
    }
    val placeholder = if (isShellMode) {
        stringResource(R.string.chat_shell_placeholder)
    } else {
        stringResource(placeholderHintResIds[hintIndex.intValue])
    }

    val text = textFieldValue.text
    val canSend = (text.isNotBlank() || attachments.isNotEmpty()) && !isSending && (!isShellMode || !isBusy)
    var previewAttachmentIndex by remember { mutableStateOf(-1) }

    // Build merged slash commands: client commands + server commands + skills (deduplicated)
    val clientCmds = clientCommands()
    val allCommands = remember(commands, clientCmds) {
        val clientNames = clientCmds.map { it.name }.toSet()
        val serverSlash = commands
            .filter { it.name !in clientNames }
            .map { SlashCommand(it.name, it.description, it.source ?: "server") }
        clientCmds + serverSlash
    }

    // Slash command suggestions
    val showSlashSuggestions = !isShellMode && text.startsWith("/") && !text.contains(" ")
    val slashQuery = if (showSlashSuggestions) text.removePrefix("/").lowercase() else ""
    val filteredCommands = if (showSlashSuggestions) {
        allCommands.filter { cmd ->
            slashQuery.isEmpty() || cmd.name.lowercase().contains(slashQuery)
        }
    } else emptyList()

    Column(
        modifier = Modifier
            .fillMaxWidth()
    ) {
        // Thin divider
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            thickness = 0.5.dp
        )

        // Slash command suggestions popup (scrollable, max 40% screen height)
        AnimatedVisibility(
            visible = showSlashSuggestions && filteredCommands.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            val configuration = LocalConfiguration.current
            val maxHeight = (configuration.screenHeightDp * 0.4f).dp

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .background(if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(vertical = 4.dp)
            ) {
                items(filteredCommands, key = { it.name }) { cmd ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (cmd.type == "skill") {
                                    // Put skill command into input field for user to add additional input
                                    val skillText = "/${cmd.name} "
                                    onTextFieldValueChange(TextFieldValue(skillText, TextRange(skillText.length)))
                                } else {
                                    onTextFieldValueChange(TextFieldValue(""))
                                    onSlashCommand(cmd)
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "/${cmd.name}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (cmd.type == "skill") MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                            fontFamily = FontFamily.Monospace
                        )
                        if (cmd.type == "skill") {
                            Text(
                                text = "skill",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f),
                                modifier = Modifier.padding(end = 4.dp)
                            )
                        }
                        if (cmd.description != null) {
                            Text(
                                text = cmd.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        // @ file mention suggestions popup
        AnimatedVisibility(
            visible = !isShellMode && fileSearchResults.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            val configuration = LocalConfiguration.current
            val maxHeight = (configuration.screenHeightDp * 0.4f).dp

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .background(if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(vertical = 4.dp)
            ) {
                items(
                    fileSearchResults.take(10),
                    key = { it }
                ) { path ->
                    val isDir = path.endsWith("/")
                    // Split into directory part + filename for display
                    val displayPath = if (isDir) path.trimEnd('/') else path
                    val lastSlash = displayPath.lastIndexOf('/')
                    val dirPart = if (lastSlash >= 0) displayPath.substring(0, lastSlash + 1) else ""
                    val namePart = if (lastSlash >= 0) displayPath.substring(lastSlash + 1) else displayPath

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onFileSelected(path) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (isDir) Icons.Default.Folder else Icons.Default.Description,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (isDir)
                                MaterialTheme.colorScheme.tertiary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Text(
                            text = buildAnnotatedString {
                                if (dirPart.isNotEmpty()) {
                                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))) {
                                        append(dirPart)
                                    }
                                }
                                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface)) {
                                    append(namePart)
                                }
                                if (isDir) {
                                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))) {
                                        append("/")
                                    }
                                }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // Status row: working status (left) + context usage (right)
        val showContext = contextWindow > 0 && lastContextTokens > 0
        if (isBusy || showContext) {
            val lastRunningTool = if (isBusy) {
                messages.firstOrNull()?.parts
                    ?.filterIsInstance<Part.Tool>()
                    ?.lastOrNull { it.state is ToolState.Running }
            } else null

            val statusText = if (isBusy) {
                if (lastRunningTool != null) {
                    val title = (lastRunningTool.state as ToolState.Running).title
                    when (lastRunningTool.tool) {
                        "read" -> title ?: stringResource(R.string.chat_tool_reading_file)
                        "write" -> title ?: stringResource(R.string.chat_tool_writing_file)
                        "edit" -> title ?: stringResource(R.string.chat_tool_editing_file)
                        "bash" -> title ?: stringResource(R.string.chat_tool_running_command)
                        "glob", "list" -> title ?: stringResource(R.string.chat_tool_searching_files)
                        "grep" -> title ?: stringResource(R.string.chat_tool_searching_code)
                        "webfetch" -> title ?: stringResource(R.string.chat_tool_fetching_url)
                        "task" -> title ?: stringResource(R.string.chat_tool_running_subagent)
                        "todowrite" -> title ?: stringResource(R.string.chat_tool_updating_tasks)
                        else -> title ?: stringResource(R.string.chat_tool_running_tool, lastRunningTool.tool)
                    }
                } else {
                    stringResource(R.string.chat_tool_thinking)
                }
            } else null

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 2.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: working status
                if (isBusy && statusText != null) {
                    Row(
                        modifier = Modifier.weight(1f, fill = false),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PulsingDotsIndicator(
                            dotSize = 4.dp,
                            dotSpacing = 3.dp,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.width(0.dp))
                }
                // Right: context usage (percentage)
                if (showContext) {
                    val percentage = Math.round(lastContextTokens.toDouble() / contextWindow * 100).toInt()
                    val contextColor = when {
                        percentage >= 90 -> MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                        percentage >= 70 -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    }
                    Text(
                        text = stringResource(
                            R.string.chat_context_format,
                            percentage
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = contextColor
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // Agent + Model + Variant + Attach selector row — small, subtle
            if ((modelLabel.isNotEmpty() || agents.size > 1)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Scrollable area for agent/model/variant so paperclip always stays visible
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Agent selector — single button, tap to cycle
                        // Fixed width: all agent names rendered invisible to reserve max width
                        if (agents.size > 1) {
                            val agentColor = agentColor(selectedAgent, agents)
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(agentColor.copy(alpha = 0.18f))
                                    .clickable {
                                        val currentIndex = agents.indexOfFirst { it.name == selectedAgent }
                                        val nextIndex = (currentIndex + 1) % agents.size
                                        onAgentSelect(agents[nextIndex].name)
                                    }
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                            ) {
                                // Invisible ghost texts for all agent names — fixes width to the widest
                                agents.forEach { agent ->
                                    Text(
                                        text = agent.name.replaceFirstChar { it.uppercase() },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.Transparent
                                    )
                                }
                                // Visible label with accent color
                                Text(
                                    text = selectedAgent.replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = agentColor
                                )
                            }
                        }

                        // Model selector — SECOND
                        if (modelLabel.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { onModelClick() }
                                    .padding(horizontal = 3.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                if (selectedProviderId != null) {
                                    ProviderIcon(
                                        providerId = selectedProviderId,
                                        size = 13.dp,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                                Text(
                                    text = modelLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                                Icon(
                                    Icons.Default.UnfoldMore,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                        }

                        // Variant cycle button (thinking effort) — THIRD
                        if (variantNames.isNotEmpty()) {
                            Text(
                                text = selectedVariant?.replaceFirstChar { it.uppercase() } ?: stringResource(R.string.chat_default_variant),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selectedVariant != null) {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                },
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { onCycleVariant() }
                                    .padding(horizontal = 3.dp, vertical = 3.dp)
                            )
                        }

                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        // Attach button (paperclip) — always visible, pinned right, aligned with Send button
                        IconButton(
                            onClick = onAttach,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.AttachFile,
                                contentDescription = stringResource(R.string.chat_attach),
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }

            // Image attachment thumbnails
            if (attachments.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(attachments.size) { index ->
                        val attachment = attachments[index]
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(10.dp))
                        ) {
                            AsyncImage(
                                model = imageThumbnailModel(attachment),
                                contentDescription = attachment.filename,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clickable { previewAttachmentIndex = index },
                                contentScale = ContentScale.Crop
                            )
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(2.dp)
                                    .size(18.dp)
                                    .clickable { onRemoveAttachment(index) },
                                shape = RoundedCornerShape(9.dp),
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.9f)
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = stringResource(R.string.chat_remove),
                                        modifier = Modifier.size(12.dp),
                                        tint = MaterialTheme.colorScheme.onError
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (previewAttachmentIndex >= 0 && previewAttachmentIndex < attachments.size) {
                val attachment = attachments[previewAttachmentIndex]
                val imageBytes = remember(attachment.dataUrl) { decodeDataUrlBytes(attachment.dataUrl) }
                val bitmap = remember(imageBytes) {
                    imageBytes?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                }

                if (bitmap != null) {
                    ImagePreviewDialog(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = attachment.filename,
                        onDismiss = { previewAttachmentIndex = -1 },
                        onSave = {
                            if (imageBytes != null) {
                                onSaveAttachment(imageBytes, attachment.mime, attachment.filename)
                            }
                        },
                    )
                }
            }

            AnimatedVisibility(
                visible = isShellMode,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isAmoled) {
                                Color.Black
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            }
                        )
                        .then(
                            if (isAmoled) {
                                Modifier.border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                    shape = RoundedCornerShape(10.dp),
                                )
                            } else {
                                Modifier
                            }
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.chat_shell_mode_hold_send_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Input row
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Text field — minimal style, no heavy outline
                val mentionHighlightColor = MaterialTheme.colorScheme.primary
                val mentionBgColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                val visualTransformation = remember(confirmedFilePaths, mentionHighlightColor, mentionBgColor) {
                    if (isShellMode) {
                        VisualTransformation.None
                    } else {
                        FileMentionVisualTransformation(confirmedFilePaths, mentionHighlightColor, mentionBgColor)
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(22.dp))
                        .background(
                            if (isAmoled) {
                                Color.Black
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            }
                        )
                        .then(
                            when {
                                isShellMode -> Modifier.border(
                                    width = if (isAmoled) 1.5.dp else 1.dp,
                                    color = if (isAmoled) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                                    } else {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                                    },
                                    shape = RoundedCornerShape(22.dp)
                                )
                                isAmoled -> Modifier.border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                                    shape = RoundedCornerShape(22.dp)
                                )
                                else -> Modifier
                            }
                        )
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    BasicTextField(
                        value = textFieldValue,
                        onValueChange = onTextFieldValueChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 24.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = if (isShellMode) FontFamily.Monospace else FontFamily.Default
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                        maxLines = 5,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        visualTransformation = visualTransformation,
                        decorationBox = { innerTextField ->
                            if (text.isEmpty()) {
                                Text(
                                    text = placeholder,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                            innerTextField()
                        }
                    )
                }

                // Send / Stop button — tap to send or stop, long-press toggles shell mode
                val showStop = isBusy && text.isBlank()
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(
                            if (showStop) {
                                MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                            } else if (isShellMode && !isSending) {
                                if (isAmoled) {
                                    Color.Black
                                } else {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                }
                            } else if (isAmoled) {
                                Color.Black
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            }
                        )
                        .then(
                            if (showStop) {
                                Modifier.border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                                    shape = RoundedCornerShape(22.dp),
                                )
                            } else if (isShellMode && !isSending) {
                                Modifier.border(
                                    width = if (isAmoled) 1.2.dp else 1.dp,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = if (isAmoled) 0.88f else 0.75f),
                                    shape = RoundedCornerShape(22.dp),
                                )
                            } else if (isAmoled) {
                                Modifier.border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(22.dp),
                                )
                            } else {
                                Modifier.border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                    shape = RoundedCornerShape(22.dp),
                                )
                            }
                        )
                        .combinedClickable(
                            onClick = {
                                if (showStop) {
                                    onStop()
                                } else if (canSend) {
                                    onSend()
                                }
                            },
                            onLongClick = {
                                if (!showStop) {
                                    onInputModeChange(
                                        if (isShellMode) ChatInputMode.NORMAL else ChatInputMode.SHELL
                                    )
                                }
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (showStop) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = stringResource(R.string.chat_stop),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    } else if (isSending) {
                        BreathingCircleIndicator(
                            size = 20.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = if (isShellMode) {
                                stringResource(R.string.chat_send_shell)
                            } else {
                                stringResource(R.string.chat_send)
                            },
                            modifier = Modifier.size(20.dp),
                            tint = if (canSend) {
                                MaterialTheme.colorScheme.primary
                            } else if (isShellMode && isAmoled && !isSending) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                            }
                        )

                    }
                }
            }
        }
    }
}

// QuestionCard moved to dialog/QuestionCard.kt
