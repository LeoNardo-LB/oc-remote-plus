package dev.leonardo.ocremotev2.ui.screens.chat

import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.leonardo.ocremotev2.BuildConfig
import dev.leonardo.ocremotev2.R
import androidx.lifecycle.SavedStateHandle
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.leonardo.ocremotev2.domain.model.AgentInfo
import dev.leonardo.ocremotev2.domain.model.CommandInfo
import dev.leonardo.ocremotev2.domain.model.ModelSelection
import dev.leonardo.ocremotev2.domain.model.PromptPart
import dev.leonardo.ocremotev2.domain.model.ProviderCatalog
import dev.leonardo.ocremotev2.data.repository.ServerTerminalRegistry
import dev.leonardo.ocremotev2.data.repository.SessionStatusManager
import dev.leonardo.ocremotev2.ui.navigation.routes.ChatNav
import dev.leonardo.ocremotev2.ui.screens.chat.tools.ToolCardResolver
import dev.leonardo.ocremotev2.ui.screens.chat.util.ContextBreakdown
import dev.leonardo.ocremotev2.ui.screens.chat.util.ContextDetailState
import dev.leonardo.ocremotev2.ui.screens.chat.util.MessageCount
import dev.leonardo.ocremotev2.ui.screens.chat.util.ProviderModel
import dev.leonardo.ocremotev2.ui.screens.chat.util.SessionTimestamps
import dev.leonardo.ocremotev2.ui.screens.chat.util.cacheHitRate
import dev.leonardo.ocremotev2.ui.screens.chat.util.countMessages
import dev.leonardo.ocremotev2.ui.screens.chat.util.estimateContextBreakdown
import dev.leonardo.ocremotev2.domain.model.Draft
import dev.leonardo.ocremotev2.domain.repository.DraftRepository
import dev.leonardo.ocremotev2.domain.model.*
import dev.leonardo.ocremotev2.domain.repository.ChatRepository
import dev.leonardo.ocremotev2.domain.repository.SessionRepository
import dev.leonardo.ocremotev2.domain.repository.SettingsRepository
import dev.leonardo.ocremotev2.domain.tracker.TokenStatsTracker
import dev.leonardo.ocremotev2.domain.usecase.*
import dev.leonardo.ocremotev2.data.api.SseClient
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLDecoder
import javax.inject.Inject

private const val TAG = "ChatViewModel"

/**
 * Split state: message list and pagination data.
 * Changes on every new message/part update — highest frequency.
 */
@Immutable
data class MessageListState(
    val messages: List<ChatMessage> = emptyList(),
    val messageCount: Int = 0,
    val hasOlderMessages: Boolean = false,
    val isLoadingOlder: Boolean = false,
    val toolExpandedStates: Map<String, Boolean> = emptyMap(),
    val queuedMessageIds: Set<String> = emptySet(),
    val pendingMessageIds: Set<String> = emptySet(),
)

/**
 * Split state: session metadata.
 * Changes when session info is updated (title, status, agent).
 */
@Immutable
data class SessionMetaState(
    val sessionTitle: String = "",
    val serverName: String = "",
    val sessionStatus: SessionStatus = SessionStatus.Idle,
    val revert: Session.Revert? = null,
    val sessionParentId: String? = null,
    val sessionAgent: String? = null,
    val currentAgentName: String? = null,
    val currentModelId: String? = null,
    val shareUrl: String? = null,
)

/**
 * Split state: user interaction state.
 * Changes on loading/sending/error and pending permissions/questions.
 */
@Immutable
data class InteractionState(
    val isLoading: Boolean = true,
    val isSending: Boolean = false,
    val error: String? = null,
    val pendingPermissions: List<SseEvent.PermissionAsked> = emptyList(),
    val pendingQuestions: List<SseEvent.QuestionAsked> = emptyList(),
)

/**
 * Split state: token usage statistics.
 * Changes on every streaming token update — high frequency during generation.
 */
@Immutable
data class TokenStatsState(
    val totalCost: Double = 0.0,
    val totalInputTokens: Int = 0,
    val totalOutputTokens: Int = 0,
    val totalReasoningTokens: Int = 0,
    val totalCacheReadTokens: Int = 0,
    val totalCacheWriteTokens: Int = 0,
    val contextWindow: Int = 0,
    val lastContextTokens: Int = 0,
)

/**
 * Split state: model/agent configuration and resolved selections.
 * Changes on provider loads, user selection, or message history updates (for auto-resolution).
 * Contains side-effect logic for model/agent resolution from message history.
 */
@Immutable
data class ModelConfigState(
    val providers: List<ProviderCatalog> = emptyList(),
    val hasServerModelCatalog: Boolean = false,
    val defaultModels: Map<String, String> = emptyMap(),
    val selectedProviderId: String? = null,
    val selectedModelId: String? = null,
    val agents: List<AgentInfo> = emptyList(),
    val selectedAgent: String = "build",
    val variantNames: List<String> = emptyList(),
    val selectedVariant: String? = null,
    val commands: List<CommandInfo> = emptyList(),
    /** Context window size — resolved from token stats with provider fallback. */
    val contextWindow: Int = 0,
)

data class ChatUiState(
    val sessionTitle: String = "",
    val serverName: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val messageCount: Int = 0,
    val revert: Session.Revert? = null,
    val sessionStatus: SessionStatus = SessionStatus.Idle,
    val pendingPermissions: List<SseEvent.PermissionAsked> = emptyList(),
    val pendingQuestions: List<SseEvent.QuestionAsked> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val providers: List<ProviderCatalog> = emptyList(),
    val hasServerModelCatalog: Boolean = false,
    val defaultModels: Map<String, String> = emptyMap(),
    val selectedProviderId: String? = null,
    val selectedModelId: String? = null,
    val totalCost: Double = 0.0,
    /** Session totals computed from all loaded assistant messages (not session.tokens which may be per-call). */
    val totalInputTokens: Int = 0,
    val totalOutputTokens: Int = 0,
    val totalReasoningTokens: Int = 0,
    val totalCacheReadTokens: Int = 0,
    val totalCacheWriteTokens: Int = 0,
    val agents: List<AgentInfo> = emptyList(),
    val selectedAgent: String = "build",
    val variantNames: List<String> = emptyList(),
    val selectedVariant: String? = null,
    val commands: List<CommandInfo> = emptyList(),
    /** True when there are older messages on the server that haven't been loaded yet. */
    val hasOlderMessages: Boolean = false,
    /** True while a "load older" request is in flight. */
    val isLoadingOlder: Boolean = false,
    /** Share URL if session is shared, null otherwise. */
    val shareUrl: String? = null,
    /** Context window size of the current model (0 if unknown). */
    val contextWindow: Int = 0,
    /** Total tokens from the last assistant message with output > 0 (current context usage). */
    val lastContextTokens: Int = 0,
    /** IDs of user messages that are queued (sent while assistant is still generating). */
    val queuedMessageIds: Set<String> = emptySet(),
    /** Parent session ID — non-null when this session is a child/sub-agent session. */
    val sessionParentId: String? = null,
    /** Agent name for this session (e.g. "explore", "general"). Populated for sub-agent sessions. */
    val sessionAgent: String? = null,
    /** Persisted expand/collapse state for tool cards, keyed by Part.Tool.id or Part.Patch.id. */
    val toolExpandedStates: Map<String, Boolean> = emptyMap(),
    val currentAgentName: String? = null,
    val currentModelId: String? = null,
    /** User messages optimistically inserted before API confirmation, keyed by messageId. */
    val pendingMessageIds: Set<String> = emptySet(),
    /** Draft restored after a failed send. Non-null only once until consumed. */
    val restoredDraft: RevertedDraftPayload? = null,
)

data class RevertedDraftPayload(
    val text: String,
    val attachmentUris: List<String> = emptyList(),
)

/**
 * A flattened chat message for the UI.
 * Combines Message info with its parts.
 */
data class ChatMessage(
    val message: Message,
    val parts: List<Part>
) {
    val isUser: Boolean get() = message is Message.User
    val isAssistant: Boolean get() = message is Message.Assistant
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val scrollSignal: dev.leonardo.ocremotev2.ui.screens.sessions.SessionScrollSignal,
    private val sendMessageUseCase: SendMessageUseCase,
    private val manageSessionUseCase: ManageSessionUseCase,
    private val managePermissionUseCase: ManagePermissionUseCase,
    private val selectModelUseCase: SelectModelUseCase,
    private val manageAgentUseCase: ManageAgentUseCase,
    private val manageTerminalUseCase: ManageTerminalUseCase,
    private val draftUseCase: DraftUseCase,
    private val shareExportUseCase: ShareExportUseCase,
    private val undoRedoUseCase: UndoRedoUseCase,
    private val settingsRepository: SettingsRepository,
    private val terminalRegistry: ServerTerminalRegistry,
    val toolCardResolver: ToolCardResolver,
    private val chatRepository: ChatRepository,
    private val sessionRepository: SessionRepository,
    private val messagePaging: MessagePaginationUseCase,
    private val tokenStatsTracker: TokenStatsTracker,
    private val httpClient: io.ktor.client.HttpClient,
    private val sseClient: SseClient,
    private val sessionStatusManager: SessionStatusManager,
    private val sessionFocusHolder: dev.leonardo.ocremotev2.service.SessionFocusHolder,
    private val appNotificationManager: dev.leonardo.ocremotev2.service.AppNotificationManager,
    private val toolSnapshotCache: dev.leonardo.ocremotev2.domain.repository.ToolSnapshotCache,
) : ViewModel() {

    // ============ Phase 2 Task 9: Tool snapshot cache ============

    fun cacheToolPart(part: dev.leonardo.ocremotev2.domain.model.Part.Tool) {
        val state = part.state
        val input = when (state) {
            is dev.leonardo.ocremotev2.domain.model.ToolState.Completed -> state.input
            is dev.leonardo.ocremotev2.domain.model.ToolState.Running -> state.input
            is dev.leonardo.ocremotev2.domain.model.ToolState.Pending -> state.input
            is dev.leonardo.ocremotev2.domain.model.ToolState.Error -> state.input
        }
        val filePath = input["filePath"]?.jsonPrimitive?.contentOrNull
            ?: input["path"]?.jsonPrimitive?.contentOrNull ?: return
        val metadata = (state as? dev.leonardo.ocremotev2.domain.model.ToolState.Completed)?.metadata
        val filediff = metadata?.get("filediff") as? JsonObject
        // Fallback: server may omit filediff — use oldString/newString from tool input
        val before = filediff?.get("before")?.jsonPrimitive?.contentOrNull
            ?: input["oldString"]?.jsonPrimitive?.contentOrNull
        val after = filediff?.get("after")?.jsonPrimitive?.contentOrNull
            ?: input["newString"]?.jsonPrimitive?.contentOrNull
        val content = when (part.tool.lowercase()) {
            "read" -> {
                val raw = (state as? dev.leonardo.ocremotev2.domain.model.ToolState.Completed)?.output ?: ""
                cleanReadToolOutput(raw)
            }
            "write" -> input["content"]?.jsonPrimitive?.contentOrNull
            "edit" -> after
            else -> null
        }
        toolSnapshotCache.put(
            part.id,
            dev.leonardo.ocremotev2.domain.repository.ToolSnapshotCache.Snapshot(
                filePath = filePath, content = content, before = before, after = after, toolName = part.tool
            )
        )
    }

    /**
     * Strip Read tool output wrappers (<path>, <content> tags) and embedded
     * line-number prefixes ("291: text" → "text") to avoid double line numbers
     * in the file viewer (which adds its own gutter).
     */
    private fun cleanReadToolOutput(raw: String): String {
        var result = raw
        // Extract <content>...</content> if present
        val contentMatch = Regex("<content>(?:\\r?\\n)?(.*?)(?:\\r?\\n)?</content>", RegexOption.DOT_MATCHES_ALL).find(result)
        result = if (contentMatch != null) {
            contentMatch.groupValues[1]
        } else {
            // Remove known XML-like wrapper lines
            result.lines().filter { line ->
                !line.startsWith("<path>") && !line.startsWith("</path>") &&
                !line.startsWith("<type>") && !line.startsWith("</type>") &&
                !line.startsWith("<content>") && !line.startsWith("</content>")
            }.joinToString("\n")
        }
        // Strip embedded line numbers: "291: text" → "text"
        result = result.replace(Regex("(?m)^\\s*\\d+:\\s"), "")
        return result.trim()
    }

    /** Snapshot of message IDs at the time of revert. Used to distinguish
     *  old messages (should be hidden) from new messages (should be shown). */
    // Removed: using m.id <= revertState.messageId instead (OpenCode pattern)

    // ============ Loading & Error State ============
    private val _isLoading = MutableStateFlow(true)
    private val _isRefreshing = MutableStateFlow(false)  // Background refresh — no UI wipe
    /** Timestamp of last successful refresh. Used to skip unnecessary ON_RESUME refreshes. */
    private var lastRefreshTimeMs: Long = 0L
    private val _error = MutableStateFlow<String?>(null)
    private val _isSending = MutableStateFlow(false)

    // ============ V1 Message State ============
    private val _messagesList = MutableStateFlow<List<Message>>(emptyList())
    /** Raw (unfiltered) messages — used for hasIncompleteMessage check to avoid
     *  the window where a new assistant message has no parts yet. */
    private val _rawMessagesList = MutableStateFlow<List<Message>>(emptyList())
    private val _partsList = MutableStateFlow<List<Part>>(emptyList())
    private var sseJob: Job? = null

    /** Expose chatRepository for ChatMessageList composable (tool progress, step progress, compaction state). */
    val chatRepositoryExposed: ChatRepository get() = chatRepository

    private val serverUrl: String = URLDecoder.decode(
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
    private val directoryParam: String = URLDecoder.decode(
        savedStateHandle.get<String>(ChatNav.PARAM_DIRECTORY) ?: "", "UTF-8"
    )
    private val _sessionId = MutableStateFlow(URLDecoder.decode(
        savedStateHandle.get<String>("sessionId") ?: "", "UTF-8"
    ))
    val sessionId: String get() = _sessionId.value

    /**
     * Called when ChatScreen enters composition.
     * Cancels existing notifications for this session and registers active focus
     * so future TaskComplete notifications are suppressed.
     */
    fun onSessionFocused(notificationManager: android.app.NotificationManager) {
        appNotificationManager.cancelSessionNotifications(notificationManager, serverId, sessionId)
        sessionFocusHolder.setActiveFocus(serverId, sessionId)
    }

    /**
     * Called when ChatScreen leaves composition.
     * Clears active focus so notifications resume.
     */
    fun onSessionUnfocused() {
        sessionFocusHolder.setActiveFocus(null, null)
    }

    init {
        sessionStatusManager.setServerId(serverId)
    }

    private val _allProviders = MutableStateFlow<List<ProviderCatalog>>(emptyList())
    private val _providers = MutableStateFlow<List<ProviderCatalog>>(emptyList())
    private val _hiddenModels = MutableStateFlow<Set<String>>(emptySet())
    private val _defaultModels = MutableStateFlow<Map<String, String>>(emptyMap())
    private val _selectedProviderId = MutableStateFlow<String?>(null)
    private val _selectedModelId = MutableStateFlow<String?>(null)
    // Track if the model was explicitly selected by the user to avoid overwriting it with defaults/history
    private var isModelExplicitlySelected = false
    /** The directory of this session's project — sent as x-opencode-directory so the server resolves the correct project context. */
    private var sessionDirectory: String? = null
    /** Mutex to prevent concurrent session creation */
    private val sessionCreateMutex = Mutex()
    /** Signals when [loadSession] has finished (successfully or with error), so that terminal
     *  creation can wait for [sessionDirectory] to be populated. */
    private val sessionLoaded = CompletableDeferred<Unit>()
    private val _agents = MutableStateFlow<List<AgentInfo>>(emptyList())
    /** Pair(agentName, explicitlySelected) — using a single flow avoids race between flag and value */
    private val _selectedAgent = MutableStateFlow("build" to false)
    private val _selectedVariant = MutableStateFlow<String?>(null)
    private val _commands = MutableStateFlow<List<CommandInfo>>(emptyList())
    private val terminalWorkspace = terminalRegistry.workspaceFor(
        serverId, serverUrl, username, password.ifEmpty { null }
    ).also {
        if (BuildConfig.DEBUG) {
            Log.d("TerminalZoom", "ChatViewModel init: workspaceId=${System.identityHashCode(it)} flowId=${System.identityHashCode(it.activeFontSizeSp)} serverId=$serverId vmId=${System.identityHashCode(this)}")
        }
    }
    val terminalTabs: StateFlow<List<TerminalTabUi>> = terminalWorkspace.tabList
    val activeTerminalTabId: StateFlow<String?> = terminalWorkspace.activeTabId
    /** Incremented on active terminal tab updates — observe to trigger recomposition. */
    val terminalVersion: StateFlow<Long> = terminalWorkspace.activeVersion
    val terminalConnected: StateFlow<Boolean> = terminalWorkspace.activeConnected
    val terminalFontSizeSp: StateFlow<Float> = terminalWorkspace.activeFontSizeSp
    val terminalEmulator: org.connectbot.terminal.TerminalEmulator get() = terminalWorkspace.activeEmulator()
    val terminalCursorKeysAppMode: Boolean get() = terminalWorkspace.activeAdapter().cursorKeysApplicationMode.value

    // ============ Draft Persistence ============
    /** Draft text for the input field — survives navigation / app restart. */
    private val _draftText = MutableStateFlow("")
    val draftText: StateFlow<String> = _draftText

    /** One-shot event: emits reverted draft payload (text + image attachments) for ChatScreen. */
    private val _revertedDraftEvent = MutableSharedFlow<RevertedDraftPayload>(extraBufferCapacity = 1)
    val revertedDraftEvent: SharedFlow<RevertedDraftPayload> = _revertedDraftEvent

    /** Draft attachment URIs (content:// URIs as strings) — survives navigation / app restart. */
    private val _draftAttachmentUris = MutableStateFlow<List<String>>(emptyList())
    val draftAttachmentUris: StateFlow<List<String>> = _draftAttachmentUris

    /** Set of file paths that have been confirmed by user selection from the popup */
    private val _confirmedFilePaths = MutableStateFlow<Set<String>>(emptySet())
    val confirmedFilePaths: StateFlow<Set<String>> = _confirmedFilePaths

    // ============ Settings (exposed for ChatScreen) ============
    val chatFontSize = settingsRepository.getSettingsFlow().map { it.chatFontSize }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), "medium"
    )
    val codeWordWrap = settingsRepository.getSettingsFlow().map { it.codeWordWrap }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), false
    )
    val confirmBeforeSend = settingsRepository.getSettingsFlow().map { it.confirmBeforeSend }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), false
    )
    val compactMessages = settingsRepository.getSettingsFlow().map { it.compactMessages }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), false
    )
    val collapseTools = settingsRepository.getSettingsFlow().map { it.collapseTools }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), false
    )
    // ============ Optimistic Send ============

    /** Locally-generated IDs for optimistic messages. Used to distinguish from server-confirmed. */
    private val _pendingMessageIds = MutableStateFlow<Set<String>>(emptySet())

    /** Draft restored after a failed send. UI consumes once and sets back to null. */
    private val _restoredDraft = MutableStateFlow<RevertedDraftPayload?>(null)

    // ============ Tool Expand State ============
    private val _toolExpandedStates = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val toolExpandedStates: StateFlow<Map<String, Boolean>> = _toolExpandedStates

    fun toggleToolExpanded(toolId: String, defaultExpanded: Boolean = false) {
        _toolExpandedStates.update { it + (toolId to !(it[toolId] ?: defaultExpanded)) }
    }

    fun isToolExpanded(toolId: String, autoExpand: Boolean): Boolean {
        return _toolExpandedStates.value[toolId] ?: autoExpand
    }

    // ============ Pagination ============
    /**
     * Number of messages to load per page. Doubles each "load older" click.
     * Refreshed from the user's initialMessageCount setting at the start of [loadSession].
     */
    private var currentMessageLimit = 30
    /** Whether there are more messages on the server beyond the current limit. */
    private val _hasOlderMessages = MutableStateFlow(false)
    /** Whether a "load older" request is in flight. */
    private val _isLoadingOlder = MutableStateFlow(false)

    // ============ Scroll Position Save/Restore ============
    /** Saved scroll position for restoring after sub-session navigation. */
    var savedMessageId by mutableStateOf<String?>(null)
        private set
    /** Raw LazyColumn index at save time — used for direct restoration without index arithmetic. */
    var savedLazyIndex by mutableStateOf(0)
        private set
    var savedScrollOffset by mutableStateOf(0)
        private set

    /**
     * Incremented each time [saveScrollPosition] is called, and again by
     * [bumpScrollRestoreIfPending] when ChatScreen resumes with a pending restore.
     * ChatScreen observes this via LaunchedEffect to reliably restore scroll position
     * after FileViewer / sub-session navigation. Using rememberLazyListState(initial...)
     * is unreliable because `remember` caches the initial state on first composition and
     * ignores new values on recomposition, causing scroll to sometimes restore and sometimes not.
     */
    var scrollRestoreVersion by mutableStateOf(0)
        private set

    /**
     * True when a scroll position has been saved (via [saveScrollPosition]) but not yet
     * restored. Used by [bumpScrollRestoreIfPending] to re-trigger restoration ONLY when
     * returning from a navigation that actually saved a position (FileViewer / sub-session),
     * avoiding spurious restores on plain background→foreground transitions that would
     * disturb the user's current browsing position.
     */
    private var hasPendingScrollRestore = false

    fun saveScrollPosition(lazyIndex: Int, offset: Int) {
        savedLazyIndex = lazyIndex
        savedScrollOffset = offset
        scrollRestoreVersion++
        hasPendingScrollRestore = true
    }

    /**
     * Re-triggers scroll position restoration on ON_RESUME, but only when a save is pending
     * (i.e. the user is returning from FileViewer or a sub-session). Plain background→foreground
     * transitions are ignored so the user's current browsing position is not disturbed.
     */
    fun bumpScrollRestoreIfPending() {
        if (hasPendingScrollRestore) {
            scrollRestoreVersion++
        }
    }
    val expandReasoning = settingsRepository.getSettingsFlow().map { it.expandReasoning }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), false
    )
    val hapticFeedback = settingsRepository.getSettingsFlow().map { it.hapticFeedback }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), true
    )
    val keepScreenOn = settingsRepository.getSettingsFlow().map { it.keepScreenOn }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), false
    )
    val compressImageAttachments = settingsRepository.getSettingsFlow().map { it.compressImageAttachments }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), true
    )
    val imageAttachmentMaxLongSide = settingsRepository.getSettingsFlow().map { it.imageAttachmentMaxLongSide }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), 1440
    )
    val imageAttachmentWebpQuality = settingsRepository.getSettingsFlow().map { it.imageAttachmentWebpQuality }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), 60
    )
    // ============ Model/Agent Config State (with resolution side effects) ============

    /**
     * Model and agent configuration — providers, agents, model/agent selection, variants.
     * Performs side effects: model/agent resolution from message history, model caching,
     * and sync-back to raw StateFlows so sendParts()/runShellCommand() use consistent values.
     */
    val modelConfigState: StateFlow<ModelConfigState> = _sessionId.flatMapLatest { sid ->
        combine(
            _allProviders,
            _providers,
            _defaultModels,
            _selectedProviderId,
            _selectedModelId,
            _agents,
            _selectedAgent,
            _selectedVariant,
            _commands,
            messagePaging.observeMessages(sid),
            sessionRepository.getSessionsFlow(serverId),
            tokenStatsTracker.stats,
        ) { args ->
            val allProviders = args[0] as List<ProviderCatalog>
            val providers = args[1] as List<ProviderCatalog>
            val defaultModels = args[2] as Map<String, String>
            val selProviderId = args[3] as String?
            val selModelId = args[4] as String?
            val agents = args[5] as List<AgentInfo>
            @Suppress("UNCHECKED_CAST")
            val agentSelection = args[6] as Pair<String, Boolean>
            val selectedAgent = agentSelection.first
            val isAgentExplicitlySelected = agentSelection.second
            val selectedVariant = args[7] as String?
            val commands = args[8] as List<CommandInfo>
            val sessionMessages = args[9] as List<Message>
            @Suppress("UNCHECKED_CAST")
            val allSessions = args[10] as List<Session>
            val tokenStats = args[11] as TokenStatsTracker.TokenStats

            // Resolve model: explicit selection > last user message's model > provider default
            var effectiveProviderId = selProviderId
            var effectiveModelId = selModelId

            if (!isModelExplicitlySelected) {
                val lastUserWithModel = sessionMessages
                    .filterIsInstance<Message.User>()
                    .lastOrNull { it.model != null }
                if (lastUserWithModel?.model != null) {
                    effectiveProviderId = lastUserWithModel.model.providerId
                    effectiveModelId = lastUserWithModel.model.modelId
                } else if (effectiveModelId == null && defaultModels.isNotEmpty()) {
                    val entry = defaultModels.entries.first()
                    effectiveProviderId = entry.key
                    effectiveModelId = entry.value
                }
            }

            // Resolve agent from last user message if not explicitly changed
            val effectiveAgent = if (!isAgentExplicitlySelected) {
                val lastUserAgent = sessionMessages
                    .filterIsInstance<Message.User>()
                    .lastOrNull { it.agent != null }
                    ?.agent
                lastUserAgent ?: selectedAgent
            } else {
                selectedAgent
            }

            // Keep raw state in sync so sendParts()/runShellCommand() always use the displayed value
            if (effectiveAgent != selectedAgent && !isAgentExplicitlySelected) {
                _selectedAgent.value = effectiveAgent to false
            }

            // Keep model StateFlows in sync with the effective model
            if ((effectiveProviderId != selProviderId || effectiveModelId != selModelId) && !isModelExplicitlySelected) {
                _selectedProviderId.value = effectiveProviderId
                _selectedModelId.value = effectiveModelId
            }

            // Resolve available variants for the currently selected model.
            var currentModel = if (effectiveProviderId != null && effectiveModelId != null) {
                providers.find { it.id == effectiveProviderId }
                    ?.models?.get(effectiveModelId)
            } else null
            if (currentModel == null) {
                val firstProvider = providers.firstOrNull()
                val firstModel = firstProvider?.models?.values?.firstOrNull()
                if (firstProvider != null && firstModel != null) {
                    effectiveProviderId = firstProvider.id
                    effectiveModelId = firstModel.id
                    currentModel = firstModel
                }
            }
            val availableVariants = currentModel?.variantNames?.sorted() ?: emptyList()

            // Persist the resolved model to the in-memory cache
            if (effectiveProviderId != null && effectiveModelId != null) {
                sessionModelCache[sid] = effectiveProviderId to effectiveModelId
            }

            // Resolve context window with fallback to provider model info
            val session = allSessions.find { it.id == sid }
            val contextWindow = tokenStats.contextWindow.let { cw ->
                if (cw > 0) cw else session?.model?.let { sm ->
                    providers.find { it.id == sm.providerId }?.models?.get(sm.id)?.contextWindow
                } ?: currentModel?.contextWindow ?: 0
            }

            ModelConfigState(
                providers = providers,
                hasServerModelCatalog = allProviders.any { it.models.isNotEmpty() },
                defaultModels = defaultModels,
                selectedProviderId = effectiveProviderId,
                selectedModelId = effectiveModelId,
                agents = agents.filter { it.mode != "subagent" && !it.hidden },
                selectedAgent = effectiveAgent,
                variantNames = availableVariants,
                selectedVariant = if (selectedVariant != null && selectedVariant in availableVariants) selectedVariant else null,
                commands = commands,
                contextWindow = contextWindow,
            )
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        ModelConfigState()
    )

    /** Expose restored draft as StateFlow for ChatScreen consumption. */
    val restoredDraftState: StateFlow<RevertedDraftPayload?> = _restoredDraft

    // NOTE: Legacy uiState is declared after the 5 split StateFlows below (needs forward references).

    // ============ Split State Flows (independent combines for fine-grained recomposition) ============

    /**
     * Message list state — derived from V1 chatRepository flows.
     * Combines messages, parts, and tool expand states.
     */
    val messageListState: StateFlow<MessageListState> = _sessionId.flatMapLatest { sid ->
        combine(
            sessionRepository.getSessionsFlow(serverId),
            messagePaging.observeMessages(sid),
            chatRepository.getAllPartsMap(),
            _isLoading,
            _hasOlderMessages,
            _isLoadingOlder,
            _toolExpandedStates,
            _pendingMessageIds,
            sessionStatusManager.statusFlow,
        ) { args ->
            @Suppress("UNCHECKED_CAST")
            val allSessions = args[0] as List<Session>
            val sessionMessages = args[1] as List<Message>
            val allParts = args[2] as Map<String, List<Part>>
            val loading = args[3] as Boolean
            val hasOlderMessages = args[4] as Boolean
            val isLoadingOlder = args[5] as Boolean
            @Suppress("UNCHECKED_CAST")
            val toolExpandedStates = args[6] as Map<String, Boolean>
            @Suppress("UNCHECKED_CAST")
            val pendingMessageIds = args[7] as Set<String>
            @Suppress("UNCHECKED_CAST")
            val statuses = args[8] as Map<String, SessionStatus>

            val session = allSessions.find { it.id == sid }
            val revertState = session?.revert

            val visible: List<Message> = if (loading && sessionMessages.isEmpty()) {
                emptyList()
            } else {
                val sorted = sessionMessages.sortedBy { it.time.created }
                if (revertState != null) {
                    // OpenCode pattern: filter by message ID string comparison.
                    // Message IDs are ULID (monotonically increasing), so
                    // id <= revertId correctly includes the revert point and
                    // everything before it.
                    sorted.filter { it.id < revertState.messageId }
                } else {
                    sorted
                }
            }

            // P5-1: queuedMessageIds derived from FSM status — Idle forces clear.
            // Computed on the full visible list (before P5-3 filtering) so pending
            // assistant detection is not affected by empty-parts filtering.
            val fsmStatus = statuses[sid] ?: SessionStatus.Idle
            val queuedMessageIds: Set<String> = if (fsmStatus is SessionStatus.Idle) {
                emptySet()
            } else {
                val pendingAssistantIndex = visible.indexOfLast {
                    it is Message.Assistant && it.time.completed == null
                }
                if (pendingAssistantIndex >= 0) {
                    visible.drop(pendingAssistantIndex + 1)
                        .filterIsInstance<Message.User>()
                        .map { it.id }
                        .toSet()
                } else {
                    emptySet()
                }
            }

            // P5-3: filter out assistant messages with no parts to prevent
            // empty bubble flash (MessageUpdated arrives before MessagePartUpdated).
            val chatMessages = visible
                .filter { msg ->
                    msg is Message.User || (msg is Message.Assistant && allParts[msg.id]?.isNotEmpty() == true)
                }
                .map { msg ->
                    ChatMessage(
                        message = msg,
                        parts = allParts[msg.id] ?: emptyList()
                    )
                }

            MessageListState(
                messages = chatMessages,
                messageCount = chatMessages.size,
                hasOlderMessages = hasOlderMessages,
                isLoadingOlder = isLoadingOlder,
                toolExpandedStates = toolExpandedStates,
                queuedMessageIds = queuedMessageIds,
                pendingMessageIds = pendingMessageIds,
            )
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        MessageListState()
    )

    /**
     * Session metadata — changes when session info is updated (title, status, agent).
     * Includes [_sessionId] as a source so lazy-session creation triggers immediate recomputation.
     * Session status is sourced from [SessionStatusManager.statusFlow] (FSM-driven),
     * the single source of truth for busy/idle/activity state.
     */
    val sessionMetaState: StateFlow<SessionMetaState> = combine(
        _sessionId,
        sessionRepository.getSessionsFlow(serverId),
        sessionStatusManager.statusFlow,
        sessionRepository.getCurrentAgentFlow(serverId),
        sessionRepository.getCurrentModelFlow(serverId),
    ) { args ->
        val sid = args[0] as String
        @Suppress("UNCHECKED_CAST")
        val allSessions = args[1] as List<Session>
        @Suppress("UNCHECKED_CAST")
        val statuses = args[2] as Map<String, SessionStatus>
        @Suppress("UNCHECKED_CAST")
        val currentAgentMap = args[3] as Map<String, String>
        @Suppress("UNCHECKED_CAST")
        val currentModelMap = args[4] as Map<String, Pair<String, String>>

        val session = allSessions.find { it.id == sid }
        val sessionStatus = statuses[sid] ?: SessionStatus.Idle

        SessionMetaState(
            sessionTitle = session?.title ?: "",
            serverName = serverName,
            sessionStatus = sessionStatus,
            revert = session?.revert,
            sessionParentId = session?.parentId,
            sessionAgent = session?.agent,
            currentAgentName = currentAgentMap[sid],
            currentModelId = currentModelMap[sid]?.second,
            shareUrl = session?.share?.url,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        SessionMetaState()
    )

    /**
     * Interaction state — loading, sending, error derived from V1 sources,
     * pending permission/question cards from V1 chatRepository.
     */
    val interactionState: StateFlow<InteractionState> = combine(
        _sessionId,
        _isLoading,
        _error,
        _isSending,
        sessionRepository.getSessionsFlow(serverId),
        chatRepository.getAllQuestionsFlow(),
        chatRepository.getAllPermissionsFlow(),
    ) { args ->
        val sid = args[0] as String
        val loading = args[1] as Boolean
        val error = args[2] as String?
        val sending = args[3] as Boolean
        @Suppress("UNCHECKED_CAST")
        val allSessions = args[4] as List<Session>

        InteractionState(
            isLoading = loading,
            isSending = sending,
            error = error,
            pendingPermissions = chatRepository.getPermissionsWithChildren(sid, allSessions),
            pendingQuestions = chatRepository.getQuestionsWithChildren(sid, allSessions),
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        InteractionState()
    )

    /**
     * Token usage statistics — changes on every streaming token update.
     * Directly mapped from [TokenStatsTracker.stats].
     */
    val tokenStatsState: StateFlow<TokenStatsState> = tokenStatsTracker.stats.map { stats ->
        TokenStatsState(
            totalCost = stats.totalCost,
            totalInputTokens = stats.totalInputTokens,
            totalOutputTokens = stats.totalOutputTokens,
            totalReasoningTokens = stats.totalReasoningTokens,
            totalCacheReadTokens = stats.totalCacheReadTokens,
            totalCacheWriteTokens = stats.totalCacheWriteTokens,
            contextWindow = stats.contextWindow,
            lastContextTokens = stats.lastContextTokens,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        TokenStatsState()
    )

    /**
     * Session directory — current chat's working directory, used for the top bar subtitle.
     * Empty when session is not yet resolved or has no directory.
     */
    val directoryState: StateFlow<String> = _sessionId.flatMapLatest { sid ->
        sessionRepository.getSessionsFlow(serverId).map { sessions ->
            sessions.find { it.id == sid }?.directory.orEmpty()
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        ""
    )

    /**
     * Aggregated context detail — provider/model, timestamps, message count, breakdown,
     * cache hit rate, and per-call token metrics. Built from the last assistant message
     * (with token-bearing StepFinish) plus session-level stats. Drives [ContextDetailDialog].
     */
    val contextDetailState: StateFlow<ContextDetailState> = _sessionId.flatMapLatest { sid ->
        combine(
            messageListState,
            tokenStatsState,
            sessionRepository.getSessionsFlow(serverId),
            modelConfigState,
        ) { msgList, stats, sessions, modelCfg ->
            val session = sessions.find { it.id == sid }
            buildContextDetailState(msgList.messages, stats, session, modelCfg.contextWindow)
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        ContextDetailState()
    )

    private fun buildContextDetailState(
        messages: List<ChatMessage>,
        stats: TokenStatsState,
        session: Session?,
        contextWindow: Int,
    ): ContextDetailState {
        // Use stats-derived values (single source of truth, avoids re-scanning messages)
        val realInput = stats.totalInputTokens
        // Estimate role-breakdown only when we have a real input to anchor percentages against
        val breakdown: ContextBreakdown? = if (realInput > 0) {
            // ChatMessage uses .message, estimateContextBreakdown expects MessageWithParts (.info)
            val mwp = messages.map { MessageWithParts(it.message, it.parts) }
            estimateContextBreakdown(mwp, realInput)
        } else null
        val messageCount: MessageCount = countMessages(messages.map { it.message })
        // provider/model from last assistant message (not available in stats)
        val providerModel: ProviderModel? =
            (messages.lastOrNull { it.message is Message.Assistant }?.message as? Message.Assistant)
                ?.let { ProviderModel(it.providerId, it.modelId) }
        val timestamps: SessionTimestamps? = session?.time
            ?.let { SessionTimestamps(it.created, it.updated) }
        // Cache hit rate = cacheRead / input (only meaningful when there is real input)
        val cacheHitRateVal: Float? = cacheHitRate(stats.totalCacheReadTokens, stats.totalInputTokens)
        return ContextDetailState(
            inputTokens = stats.totalInputTokens,
            outputTokens = stats.totalOutputTokens,
            reasoningTokens = stats.totalReasoningTokens,
            cacheReadTokens = stats.totalCacheReadTokens,
            cacheWriteTokens = stats.totalCacheWriteTokens,
            totalCost = stats.totalCost,
            contextWindow = contextWindow,
            contextTokens = stats.lastContextTokens,
            messageCount = messageCount,
            providerModel = providerModel,
            timestamps = timestamps,
            cacheHitRate = cacheHitRateVal,
            breakdown = breakdown,
        )
    }

    /**
     * Legacy uiState for backward compatibility (tests).
     * Lightweight assembly from the 5 split StateFlows — no business logic.
     */
    // TODO: Replace positional args[] with a data class or structured combine sources for type safety
    val uiState: StateFlow<ChatUiState> = combine(
        messageListState,
        sessionMetaState,
        interactionState,
        tokenStatsState,
        modelConfigState,
        _restoredDraft,
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val msgList = args[0] as MessageListState
        @Suppress("UNCHECKED_CAST")
        val sessMeta = args[1] as SessionMetaState
        @Suppress("UNCHECKED_CAST")
        val inter = args[2] as InteractionState
        @Suppress("UNCHECKED_CAST")
        val tokens = args[3] as TokenStatsState
        @Suppress("UNCHECKED_CAST")
        val modelCfg = args[4] as ModelConfigState
        val restoredDraft = args[5] as RevertedDraftPayload?
        ChatUiState(
            sessionTitle = sessMeta.sessionTitle,
            serverName = sessMeta.serverName,
            messages = msgList.messages,
            messageCount = msgList.messageCount,
            revert = sessMeta.revert,
            sessionStatus = sessMeta.sessionStatus,
            pendingPermissions = inter.pendingPermissions,
            pendingQuestions = inter.pendingQuestions,
            isLoading = inter.isLoading,
            error = inter.error,
            providers = modelCfg.providers,
            hasServerModelCatalog = modelCfg.hasServerModelCatalog,
            defaultModels = modelCfg.defaultModels,
            selectedProviderId = modelCfg.selectedProviderId,
            selectedModelId = modelCfg.selectedModelId,
            totalCost = tokens.totalCost,
            totalInputTokens = tokens.totalInputTokens,
            totalOutputTokens = tokens.totalOutputTokens,
            totalReasoningTokens = tokens.totalReasoningTokens,
            totalCacheReadTokens = tokens.totalCacheReadTokens,
            totalCacheWriteTokens = tokens.totalCacheWriteTokens,
            agents = modelCfg.agents,
            selectedAgent = modelCfg.selectedAgent,
            variantNames = modelCfg.variantNames,
            selectedVariant = modelCfg.selectedVariant,
            commands = modelCfg.commands,
            hasOlderMessages = msgList.hasOlderMessages,
            isLoadingOlder = msgList.isLoadingOlder,
            shareUrl = sessMeta.shareUrl,
            contextWindow = modelCfg.contextWindow,
            lastContextTokens = tokens.lastContextTokens,
            queuedMessageIds = msgList.queuedMessageIds,
            sessionParentId = sessMeta.sessionParentId,
            sessionAgent = sessMeta.sessionAgent,
            currentAgentName = sessMeta.currentAgentName,
            currentModelId = sessMeta.currentModelId,
            toolExpandedStates = msgList.toolExpandedStates,
            pendingMessageIds = msgList.pendingMessageIds,
            restoredDraft = restoredDraft,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        ChatUiState()
    )

    init {
        val isNewSession = sessionId.isEmpty()

        // Reset token stats from previous session (TokenStatsTracker is @Singleton, shared across sessions)
        tokenStatsTracker.reset()

        // Observe messages and update token stats tracker.
        // Token values use the LAST assistant message's tokens (represents current context size).
        // Cost is cumulative across all API calls.
        viewModelScope.launch {
            _messagesList.collect { messages ->
                val assistantMessages = messages.filterIsInstance<Message.Assistant>()

                // Cost is cumulative across all API calls
                val totalCost = assistantMessages.sumOf { it.cost ?: 0.0 }

                // Token usage = last call's values (not cumulative, matches OpenCode behavior)
                val lastWithTokens = assistantMessages.lastOrNull { (it.tokens?.output ?: 0) > 0 }
                val lastTokens = lastWithTokens?.tokens
                val totalInputTokens = lastTokens?.input ?: 0
                val totalOutputTokens = lastTokens?.output ?: 0
                val totalReasoningTokens = lastTokens?.reasoning ?: 0
                val totalCacheReadTokens = lastTokens?.cache?.read ?: 0
                val totalCacheWriteTokens = lastTokens?.cache?.write ?: 0

                // Context tokens for the circular progress indicator
                val lastContextTokens = lastTokens?.let { t ->
                    t.input + t.output + t.reasoning + t.cache.read + t.cache.write
                } ?: 0

                tokenStatsTracker.update {
                    copy(
                        totalCost = totalCost,
                        totalInputTokens = totalInputTokens,
                        totalOutputTokens = totalOutputTokens,
                        totalReasoningTokens = totalReasoningTokens,
                        totalCacheReadTokens = totalCacheReadTokens,
                        totalCacheWriteTokens = totalCacheWriteTokens,
                        lastContextTokens = lastContextTokens,
                    )
                }
            }
        }

        // Restore draft from disk
        if (!isNewSession) {
            val draft = draftUseCase.getDraft(sessionId)
            if (draft != null) {
                _draftText.value = draft.text
                _draftAttachmentUris.value = draft.imageUris
                if (draft.confirmedFilePaths.isNotEmpty()) {
                    _confirmedFilePaths.value = draft.confirmedFilePaths.toSet()
                }
                if (!draft.selectedAgent.isNullOrBlank()) {
                    _selectedAgent.value = draft.selectedAgent to true
                }
                if (!draft.selectedVariant.isNullOrBlank()) {
                    _selectedVariant.value = draft.selectedVariant
                }
            }
        }

        // Restore model selection from in-memory cache (survives session switching, cleared on app restart)
        if (!isNewSession) {
            sessionModelCache[sessionId]?.let { (providerId, modelId) ->
                _selectedProviderId.value = providerId
                _selectedModelId.value = modelId
                isModelExplicitlySelected = true
            }
        }

        viewModelScope.launch {
            settingsRepository.hiddenModels(serverId).collect { hidden ->
                _hiddenModels.value = hidden
                applyProviderFilter()
            }
        }

        viewModelScope.launch {
            settingsRepository.getSettingsFlow().map { it.terminalFontSize }.collect { size ->
                terminalWorkspace.setDefaultFontSize(size)
            }
        }

        // Load initial message count from settings, then load data
        if (!isNewSession) {
            viewModelScope.launch {
                try {
                    loadSession()
                } catch (e: Exception) {
                }
                try {
                    loadMessages()
                } catch (e: Exception) {
                }
                try {
                    loadPendingQuestions()
                } catch (e: Exception) {
                }
                try {
                    loadPendingPermissions()
                } catch (e: Exception) {
                }
            }
        } else {
            // New session: set directory from route param, skip loading
            if (directoryParam.isNotEmpty()) {
                sessionDirectory = directoryParam
            }
            if (!sessionLoaded.isCompleted) {
                sessionLoaded.complete(Unit)
            }
            // New session has nothing to load — mark loading complete immediately
            _isLoading.value = false
        }
        loadProviders()
        loadAgents()
        loadCommands()
    }

    /**
     * Load session info and messages via V1 API, then observe SSE-driven flows.
     */
    private suspend fun loadSession() {
        // Apply user-configured initial message count as the pagination starting point
        currentMessageLimit = settingsRepository.getSettingsFlow().first().initialMessageCount
        try {
            // 1. Load session info for directory / session metadata
            val session = manageSessionUseCase.getSession(serverId, sessionId)
            if (session.directory.isNotBlank()) {
                sessionDirectory = session.directory
                if (BuildConfig.DEBUG) Log.d(TAG, "Session directory: ${session.directory}")
            }
            sessionRepository.setSessions(serverId, listOf(session))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load session info", e)
        } finally {
            if (!sessionLoaded.isCompleted) {
                sessionLoaded.complete(Unit)
            }
        }

        // 2. Load messages via V1 API
        try {
            val messages = manageSessionUseCase.listMessages(serverId, sessionId, limit = currentMessageLimit)
            chatRepository.setMessages(sessionId, messages)
            if (BuildConfig.DEBUG) Log.d(TAG, "V1 loaded ${messages.size} messages for session $sessionId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load messages", e)
        }

        // 3. Start observing chatRepository flows (driven by SSE EventDispatcher)
        runCatching { startObservingMessages() }
            .onFailure { Log.e(TAG, "Failed to start observing messages", it) }
    }

    /**
     * Observe V1 chatRepository flows (driven by SSE EventDispatcher).
     * Messages and parts are updated automatically as SSE events arrive.
     */
    private fun startObservingMessages() {
        sseJob?.cancel()
        sseJob = viewModelScope.launch {
            combine(
                chatRepository.getMessagesFlow(sessionId),
                chatRepository.getParts(sessionId),
            ) { messages, parts ->
                val grouped = parts.groupBy { it.messageId }
                // 过滤掉还没有 parts 的 assistant 消息：
                // MessageUpdated 可能先于 MessagePartUpdated 到达，此时 assistant 消息存在但没有内容，
                // 导致 UI 上看起来回复没出现。等第一个 part 到达后自然会显示。
                val visibleMessages = messages.filter { msg ->
                    msg is Message.User || (msg is Message.Assistant && grouped[msg.id]?.isNotEmpty() == true)
                }
                val missingParts = messages.size - visibleMessages.size
                Log.d(TAG, "[sseJob] msgs=${messages.size} visible=${visibleMessages.size} parts=${parts.size} active=${sseJob?.isActive} filtered=$missingParts")
                _rawMessagesList.value = messages
                _messagesList.value = visibleMessages
                _partsList.value = parts
            }.collect { }
        }
    }

    /**
     * Load messages via V1 API for modelConfigState resolution (model/agent from history).
     */
    fun loadMessages() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val messages = manageSessionUseCase.listMessages(serverId, sessionId, limit = currentMessageLimit)
                chatRepository.setMessages(sessionId, messages)
                _hasOlderMessages.value = messages.size >= currentMessageLimit

                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Loaded ${messages.size} messages for session $sessionId (limit=$currentMessageLimit, hasOlder=${_hasOlderMessages.value})")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to load messages", e)
                if (e is OutOfMemoryError || (e.cause is OutOfMemoryError)) {
                    Log.w(TAG, "OOM loading messages, retrying with smaller limit")
                    currentMessageLimit = (currentMessageLimit / 2).coerceAtLeast(10)
                    try {
                        val messages = manageSessionUseCase.listMessages(serverId, sessionId, limit = currentMessageLimit)
                        chatRepository.mergeMessages(sessionId, messages)
                        _hasOlderMessages.value = messages.size >= currentMessageLimit
                        if (BuildConfig.DEBUG) Log.d(TAG, "Retry succeeded: loaded ${messages.size} messages (limit=$currentMessageLimit)")
                    } catch (retryEx: Throwable) {
                        Log.e(TAG, "Retry also failed", retryEx)
                        _error.value = retryEx.message ?: "Failed to load messages"
                    }
                } else {
                    _error.value = e.message ?: "Failed to load messages"
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Refresh session data — reloads messages and session status from REST.
     */
    fun refreshSession() {
        viewModelScope.launch {
            refreshAndSync()
        }
    }

    /**
     * Refresh session only if enough time has passed since last refresh.
     * Called from ON_RESUME — avoids unnecessary REST calls during brief app-switches.
     *
     * Only syncs session status and refreshes messages via REST.
     * Does NOT restart sseJob to avoid scroll position reset and data flickering.
     */
    fun refreshIfNeeded() {
        val elapsed = System.currentTimeMillis() - lastRefreshTimeMs
        if (elapsed >= REFRESH_COOLDOWN_MS) {
            refreshSession()
        }
    }

    /**
     * Refresh messages via V1 API.
     */
    private suspend fun refreshMessages() {
        _isRefreshing.value = true
        try {
            val messages = manageSessionUseCase.listMessages(serverId, sessionId, limit = currentMessageLimit)
            chatRepository.setMessages(sessionId, messages)
            _hasOlderMessages.value = messages.size >= currentMessageLimit
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to refresh messages", e)
        } finally {
            _isRefreshing.value = false
        }
    }

    /**
     * Query the OpenCode server for actual session statuses and correct
     * any UI state drift caused by missed SSE events.
     *
     * Writes ALL session statuses from REST response (not just current session).
     * Only marks the current session's messages as completed if it's truly idle.
     *
     * Triggered on:
     * - Entering a session (LaunchedEffect(sessionId))
     * - Resuming from background (DisposableEffect ON_RESUME)
     */
    fun syncSessionStatus() {
        viewModelScope.launch {
            // Wait for loadSession() to complete so sessionDirectory is populated.
            // Without this, REST call may use null directory and return incorrect status.
            if (sessionId.isNotBlank()) {
                sessionLoaded.await()
            }
            val result = sessionRepository.fetchSessionStatuses(serverId, directory = sessionDirectory)
            result.onSuccess { statusMap ->
                // Batch-update ALL session statuses (one REST call, all sessions)
                sessionRepository.syncAllSessionStatuses(statusMap)

                // Only mark current session as idle if REST explicitly confirmed it's idle.
                // null means the server didn't report this session (possibly wrong directory),
                // which must NOT be treated as "confirmed idle" — doing so would incorrectly
                // overwrite a legitimate Busy status from SSE and break ongoing tasks.
                val currentStatus = statusMap[sessionId]
                if (currentStatus is SessionStatus.Idle) {
                    sessionRepository.markSessionIdleProtected(sessionId)
                    // If server says idle but local messages are incomplete (server restart),
                    // fix the messages so the UI shows the correct state.
                    fixIncompleteMessagesIfIdle(sessionId)
                }
            }
        }
    }

    /**
     * Combined refresh + sync — runs in a single coroutine to avoid
     * state conflicts between parallel REST responses.
     */
    private suspend fun refreshAndSync() {
        // 1. Load session info first (needed for sessionDirectory)
        loadSession()

        // 2. Refresh messages (uses _isRefreshing, not _isLoading)
        refreshMessages()

        // 3. Sync session statuses AFTER messages are loaded
        //    so we have the latest data when checking idle state
        if (sessionId.isNotBlank()) {
            sessionLoaded.await()
        }
        val result = sessionRepository.fetchSessionStatuses(serverId, directory = sessionDirectory)
        result.onSuccess { statusMap ->
            sessionRepository.syncAllSessionStatuses(statusMap)
            val currentStatus = statusMap[sessionId]
            if (currentStatus is SessionStatus.Idle) {
                sessionRepository.markSessionIdleProtected(sessionId)
                fixIncompleteMessagesIfIdle(sessionId)
            }
        }

        // 4. Load pending items
        loadPendingQuestions()
        loadPendingPermissions()

        lastRefreshTimeMs = System.currentTimeMillis()
    }

    /**
     * Fix messages with time.completed == null when the server confirms the session is idle.
     * This handles the server-restart scenario: after restart, all sessions are idle in-memory,
     * but the database preserves interrupted messages with finished_at = NULL.
     * We must NOT call this during periodic polling — only on explicit user actions
     * (entering session, aborting) to avoid breaking premature-idle protection.
     */
    private fun fixIncompleteMessagesIfIdle(sid: String) {
        val messages = _rawMessagesList.value
        val hasIncomplete = messages.any { it is Message.Assistant && it.time.completed == null }
        if (hasIncomplete) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Fixing incomplete messages for session $sid (server confirmed idle)")
            sessionRepository.markSessionIdle(sid)
        }
    }

    fun loadOlderMessages() {
        viewModelScope.launch {
            _isLoadingOlder.value = true
            currentMessageLimit = currentMessageLimit * 2
            try {
                val messages = manageSessionUseCase.listMessages(serverId, sessionId, limit = currentMessageLimit)
                chatRepository.mergeMessages(sessionId, messages)
                _hasOlderMessages.value = messages.size >= currentMessageLimit

                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Loaded older: ${messages.size} messages (limit=$currentMessageLimit, hasOlder=${_hasOlderMessages.value})")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load older messages", e)
                currentMessageLimit = currentMessageLimit / 2
            } finally {
                _isLoadingOlder.value = false
            }
        }
    }

    /**
     * Load pending questions from the server REST API.
     * Converts QuestionRequest DTOs to SseEvent.QuestionAsked domain objects.
     * Must be called after loadSession() so sessionDirectory is set.
     */
    private suspend fun loadPendingQuestions() {
        try {
            val allQuestions = managePermissionUseCase.listPendingQuestions(serverId, directory = sessionDirectory)
            if (BuildConfig.DEBUG) Log.d(TAG, "loadPendingQuestions: ${allQuestions.size} total pending (directory=$sessionDirectory), filtering for session $sessionId")

            // Include questions from child sessions
            val childSessionIds = chatRepository.getSessionsSnapshot()
                .filter { it.parentId == sessionId }
                .map { it.id }
                .toSet()

            val sessionQuestions = allQuestions
                .filter { it.sessionId == sessionId || it.sessionId in childSessionIds }
                .map { req ->
                    val isChild = req.sessionId != sessionId
                    SseEvent.QuestionAsked(
                        id = req.id,
                        sessionId = req.sessionId,
                        questions = req.questions.map { q ->
                            SseEvent.QuestionAsked.Question(
                                header = q.header,
                                question = q.question,
                                multiple = q.multiple,
                                custom = q.custom,
                                options = q.options.map { o ->
                                    SseEvent.QuestionAsked.Option(
                                        label = o.label,
                                        description = o.description
                                    )
                                }
                            )
                        },
                        tool = req.tool,
                        sourceSessionTitle = if (isChild) {
                            chatRepository.getSessionsSnapshot().find { it.id == req.sessionId }?.title
                        } else null
                    )
                }
            if (sessionQuestions.isNotEmpty()) {
                // 合并 SSE 已有的问题 + REST 恢复的问题（去重），防止覆盖 SSE 新推送的问题
                val existingSseQs = chatRepository.getQuestionsSnapshot()[sessionId] ?: emptyList()
                val existingIds = existingSseQs.map { it.id }.toSet()
                val newQs = sessionQuestions.filter { it.id !in existingIds }
                if (newQs.isNotEmpty()) {
                    chatRepository.setQuestions(sessionId, existingSseQs + newQs)
                    if (BuildConfig.DEBUG) Log.d(TAG, "Merged ${newQs.size} new + ${existingSseQs.size} existing questions for session $sessionId")
                } else {
                    if (BuildConfig.DEBUG) Log.d(TAG, "All ${sessionQuestions.size} REST questions already present via SSE for session $sessionId")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load pending questions: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    /** Load pending permissions from the server REST API on session open (REST recovery). */
    private suspend fun loadPendingPermissions() {
        try {
            val allPermissions = managePermissionUseCase.listPendingPermissions(serverId, directory = sessionDirectory)
            if (BuildConfig.DEBUG) Log.d(TAG, "loadPendingPermissions: ${allPermissions.size} total pending (directory=$sessionDirectory), filtering for session $sessionId")

            // Include permissions from child sessions
            val childSessionIds = chatRepository.getSessionsSnapshot()
                .filter { it.parentId == sessionId }
                .map { it.id }
                .toSet()

            val sessionPermissions = allPermissions
                .filter { it.sessionId == sessionId || it.sessionId in childSessionIds }
                .map { req ->
                    val isChild = req.sessionId != sessionId
                    SseEvent.PermissionAsked(
                        id = req.id,
                        sessionId = req.sessionId,
                        permission = req.permission,
                        patterns = req.patterns,
                        metadata = req.metadata,
                        always = req.always,
                        tool = req.tool,
                        sourceSessionTitle = if (isChild) {
                            chatRepository.getSessionsSnapshot().find { it.id == req.sessionId }?.title
                        } else null
                    )
                }
            if (sessionPermissions.isNotEmpty()) {
                // Group permissions by their target sessionId to match SSE storage pattern
                // SSE stores child session permissions under childSessionId, REST should do the same
                val permissionsByTarget = sessionPermissions.groupBy { it.sessionId }
                for ((targetSessionId, perms) in permissionsByTarget) {
                    val existingSsePerms = chatRepository.getPermissionsSnapshot()[targetSessionId] ?: emptyList()
                    val existingIds = existingSsePerms.map { it.id }.toSet()
                    val newPerms = perms.filter { it.id !in existingIds }
                    if (newPerms.isNotEmpty()) {
                        chatRepository.setPermissions(targetSessionId, existingSsePerms + newPerms)
                        if (BuildConfig.DEBUG) Log.d(TAG, "Merged ${newPerms.size} new + ${existingSsePerms.size} existing permissions for session $targetSessionId")
                    } else {
                        if (BuildConfig.DEBUG) Log.d(TAG, "All ${perms.size} REST permissions already present via SSE for session $targetSessionId")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load pending permissions: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    // Removed initModelFromMessages as it's handled reactively

    private fun loadProviders() {
        viewModelScope.launch {
            try {
                val response = selectModelUseCase.loadProviders(serverId)
                _allProviders.value = response.providers
                applyProviderFilter()
                _defaultModels.value = response.default
                if (BuildConfig.DEBUG) Log.d(TAG, "Loaded ${response.providers.size} providers, defaults: ${response.default}")
                // No need to set default here, combine block handles fallback
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load providers", e)
            }
        }
    }

    private fun applyProviderFilter() {
        val hidden = _hiddenModels.value
        val filtered = _allProviders.value
            .map { provider ->
                provider.copy(
                    models = provider.models.filterKeys { modelId ->
                        "${provider.id}:$modelId" !in hidden
                    }
                )
            }
            .filter { it.models.isNotEmpty() }
        _providers.value = filtered
    }

    private fun loadAgents() {
        viewModelScope.launch {
            try {
                val agents = manageAgentUseCase.loadAgents(serverId)
                _agents.value = agents
                if (BuildConfig.DEBUG) Log.d(TAG, "Loaded ${agents.size} agents: ${agents.map { it.name }}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load agents", e)
            }
        }
    }

    fun selectAgent(name: String) {
        _selectedAgent.value = name to true
    }

    private fun loadCommands() {
        viewModelScope.launch {
            try {
                val commands = manageAgentUseCase.loadCommands(serverId)
                _commands.value = commands
                if (BuildConfig.DEBUG) Log.d(TAG, "Loaded ${commands.size} commands: ${commands.map { it.name }}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load commands", e)
            }
        }
    }

    /**
     * Cycle through available thinking effort variants for the current model.
     * Cycles: none -> first -> second -> ... -> last -> none (default).
     */
    fun cycleVariant() {
        val variants = modelConfigState.value.variantNames
        if (variants.isEmpty()) return
        val current = _selectedVariant.value
        if (current == null || current !in variants) {
            _selectedVariant.value = variants.first()
        } else {
            val idx = variants.indexOf(current)
            _selectedVariant.value = if (idx == variants.lastIndex) null else variants[idx + 1]
        }
    }

    fun selectModel(providerId: String, modelId: String) {
        // MUST set flag BEFORE modifying StateFlows — on Main.immediate dispatcher,
        // setting a StateFlow value synchronously triggers combine recomputation,
        // which would overwrite our values if the flag isn't set yet.
        isModelExplicitlySelected = true
        _selectedProviderId.value = providerId
        _selectedModelId.value = modelId
        // Remember selection for this session (in-memory, cleared on app restart)
        sessionModelCache[sessionId] = providerId to modelId
    }

    // ============ @ File Mention Search ============

    /** File search results for @-autocomplete */
    private val _fileSearchResults = MutableStateFlow<List<String>>(emptyList())
    val fileSearchResults: StateFlow<List<String>> = _fileSearchResults

    /** Debounce job for file search */
    private var fileSearchJob: Job? = null

    /** Search files and directories for @-mention autocomplete. Debounced by 200ms. */
    fun searchFilesForMention(query: String) {
        fileSearchJob?.cancel()
        if (query.isEmpty()) {
            // Show recent/top files immediately with no debounce
            fileSearchJob = viewModelScope.launch {
                try {
                    val results = manageAgentUseCase.searchFiles(
                        serverId = serverId,
                        query = "",
                        dirs = "true",
                        directory = sessionDirectory,
                        limit = 15
                    )
                    _fileSearchResults.value = results
                } catch (e: Exception) {
                    Log.e(TAG, "File search failed", e)
                    _fileSearchResults.value = emptyList()
                }
            }
            return
        }
        fileSearchJob = viewModelScope.launch {
            delay(150) // debounce
            try {
                val results = manageAgentUseCase.searchFiles(
                    serverId = serverId,
                    query = query,
                    dirs = "true",
                    directory = sessionDirectory,
                    limit = 15
                )
                _fileSearchResults.value = results
            } catch (e: Exception) {
                Log.e(TAG, "File search failed for query '$query'", e)
                _fileSearchResults.value = emptyList()
            }
        }
    }

    /** Add a confirmed file path (user selected it from the popup) */
    fun confirmFilePath(path: String) {
        _confirmedFilePaths.value = _confirmedFilePaths.value + path
    }

    /** Remove a confirmed file path */
    fun removeFilePath(path: String) {
        _confirmedFilePaths.value = _confirmedFilePaths.value - path
    }

    /** Clear file search results (e.g. when popup is closed) */
    fun clearFileSearch() {
        fileSearchJob?.cancel()
        _fileSearchResults.value = emptyList()
    }

    /** Clear confirmed file paths (e.g. after sending a message) */
    fun clearConfirmedPaths() {
        _confirmedFilePaths.value = emptySet()
    }

    // ============ Draft Management ============

    /** Update the draft text (called on every keystroke). */
    fun updateDraftText(text: String) {
        _draftText.value = text
    }

    /** Add an attachment URI to the draft. */
    fun addDraftAttachment(uri: String) {
        _draftAttachmentUris.value = _draftAttachmentUris.value + uri
    }

    /** Remove an attachment URI from the draft by index. */
    fun removeDraftAttachment(index: Int) {
        val current = _draftAttachmentUris.value.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            _draftAttachmentUris.value = current
        }
    }

    /** Clear all draft state (called after sending a message). */
    fun clearDraft() {
        _draftText.value = ""
        _draftAttachmentUris.value = emptyList()
        draftUseCase.clearDraft(sessionId)
    }

    /** Consume the restored draft after UI has read it. */
    fun consumeRestoredDraft() {
        _restoredDraft.value = null
    }

    /** Persist current draft to disk. */
    private fun saveDraft() {
        val agentPair = _selectedAgent.value
        val draft = Draft(
            text = _draftText.value,
            imageUris = _draftAttachmentUris.value,
            confirmedFilePaths = _confirmedFilePaths.value.toList(),
            selectedAgent = agentPair.first.takeIf { agentPair.second },
            selectedVariant = _selectedVariant.value
        )
        draftUseCase.saveDraft(sessionId, draft)
    }

    override fun onCleared() {
        sseJob?.cancel()
        closeTerminalSession()
        super.onCleared()
        saveDraft()
    }

    /** Get the session directory for building file:// URLs */
    fun getSessionDirectory(): String? = sessionDirectory

    fun sendMessage(text: String, attachments: List<PromptPart> = emptyList()) {
        if (text.isBlank() && attachments.isEmpty()) return
        val parts = mutableListOf<PromptPart>()
        if (text.isNotBlank()) {
            parts.add(PromptPart(type = "text", text = text))
        }
        parts.addAll(attachments)
        sendParts(parts)
    }

    /** Send pre-built prompt parts (used when @-file mentions need structured parts). */
    fun sendMessage(promptParts: List<PromptPart>, attachments: List<PromptPart>) {
        val parts = promptParts + attachments
        if (parts.isEmpty()) return
        sendParts(parts)
    }

    /**
     * Ensures a session exists before sending messages.
     * If sessionId is empty (new session), creates one via API.
     * Thread-safe via Mutex to prevent duplicate creation.
     * After creation, starts observing SSE-driven flows so messages appear.
     */
    private suspend fun ensureSession(): String {
        if (sessionId.isNotEmpty()) return sessionId
        return sessionCreateMutex.withLock {
            // Double-check after acquiring lock
            if (sessionId.isNotEmpty()) return sessionId
            val dir = if (directoryParam.isNotEmpty()) directoryParam else sessionDirectory
            val session = manageSessionUseCase.createSession(serverId, directory = dir)
            sessionRepository.setSessions(serverId, listOf(session))
            _sessionId.value = session.id
            sessionDirectory = session.directory.ifBlank { dir }
            if (!sessionLoaded.isCompleted) {
                sessionLoaded.complete(Unit)
            }
            // Start observing SSE-driven message/part flows for the new session.
            // Without this, SSE events arrive at EventDispatcher but ChatViewModel
            // never collects them — messages stay invisible.
            runCatching { startObservingMessages() }
                .onFailure { Log.e(TAG, "Failed to start observing after session creation", it) }
            sessionId
        }
    }

    /**
     * Schedule a delayed REST refresh to fetch the updated session title.
     * Only refreshes if the current title looks like a default placeholder
     * (null, empty, or matches "New session - ..." pattern).
     */
    private fun refreshSessionTitleDelayed(sid: String) {
        viewModelScope.launch {
            kotlinx.coroutines.delay(8_000) // Wait for server async title generation
            try {
                val refreshed = manageSessionUseCase.getSession(serverId, sid)
                if (refreshed != null) {
                    val currentSession = chatRepository.getSessionsSnapshot().find { it.id == sid }
                    val currentTitle = currentSession?.title
                    // Only update if the title actually changed (skip if SSE already delivered it)
                    if (refreshed.title != currentTitle) {
                        val msg = "[Title] REST fallback: title updated from '$currentTitle' to '${refreshed.title}'"
                        Log.i(TAG, msg)
                        appendDiagnosticLog(msg)
                        sessionRepository.setSessions(serverId, listOf(refreshed))
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to refresh session title for $sid: ${e.message}")
            }
        }
    }

    private fun sendParts(parts: List<PromptPart>) {
        scrollSignal.requestScrollToTop()
        val pendingId = "pending-${java.util.UUID.randomUUID()}"
        _pendingMessageIds.update { it + pendingId }
        viewModelScope.launch {
            _isSending.value = true
            try {
                val currentSessionId = ensureSession()
                sessionStatusManager.onSendParts(currentSessionId)
                // P5-5: read from modelConfigState (resolved effective value) instead of
                // raw _selectedProviderId which may be null on new session's first send.
                val modelCfg = modelConfigState.value
                val model = if (modelCfg.selectedProviderId != null && modelCfg.selectedModelId != null) {
                    ModelSelection(
                        providerId = modelCfg.selectedProviderId!!,
                        modelId = modelCfg.selectedModelId!!
                    )
                } else null

                // Clear revert before sending — message.removed SSE events have
                // already cleaned old messages from cache, so no flash.
                chatRepository.clearRevert(currentSessionId)

                sendMessageUseCase.sendPrompt(
                    serverId = serverId,
                    sessionId = currentSessionId,
                    parts = parts,
                    model = model,
                    agent = modelConfigState.value.selectedAgent,
                    variant = _selectedVariant.value,
                    directory = sessionDirectory
                )
                // P5-4: clear pendingId on success path (was only cleared in catch block).
                _pendingMessageIds.update { it - pendingId }
                if (BuildConfig.DEBUG) Log.d(TAG, "Sent prompt to session $currentSessionId (${parts.size} parts)")
                refreshSessionTitleDelayed(currentSessionId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message", e)
                _pendingMessageIds.update { it - pendingId }
                // Restore draft from the failed send
                val failedText = parts.filter { it.type == "text" }.mapNotNull { it.text }.joinToString("\n")
                if (failedText.isNotBlank()) {
                    _restoredDraft.value = RevertedDraftPayload(text = failedText)
                }
                _error.value = e.message ?: "Failed to send message"
            } finally {
                _isSending.value = false
            }
        }
    }

    /**
     * Reply to a permission request.
     * @param requestId The permission request ID
     * @param reply One of: "once", "always", "reject"
     */
    fun replyToPermission(requestId: String, reply: String) {
        viewModelScope.launch {
            val logMsg = "[Permission] replyToPermission: id=$requestId reply=$reply dir=$sessionDirectory"
            Log.i(TAG, logMsg)
            appendDiagnosticLog(logMsg)
            try {
                val success = managePermissionUseCase.replyToPermission(
                    serverId = serverId,
                    requestId = requestId,
                    reply = reply,
                    directory = sessionDirectory
                )
                val resultMsg = "[Permission] replyToPermission result: id=$requestId success=$success"
                Log.i(TAG, resultMsg)
                appendDiagnosticLog(resultMsg)
                if (success) {
                    // Optimistically remove the permission card — SSE event may arrive late or not at all
                    chatRepository.removePermission(requestId)
                } else {
                    // API returned non-2xx (e.g. already replied by another client) — remove card anyway
                    // to prevent permanently stuck permission cards
                    val warnMsg = "[Permission] API returned failure for $requestId, removing card as fallback (likely already replied)"
                    Log.w(TAG, warnMsg)
                    appendDiagnosticLog(warnMsg)
                    chatRepository.removePermission(requestId)
                }
            } catch (e: Exception) {
                val errMsg = "[Permission] Exception replying to $requestId: ${e.javaClass.simpleName}: ${e.message}"
                Log.e(TAG, errMsg, e)
                appendDiagnosticLog(errMsg)
                // Network/timeout error — remove card to prevent stuck state;
                // if the reply didn't reach the server, the server will re-emit the permission event
                chatRepository.removePermission(requestId)
            }
        }
    }

    fun savePermissionRule(event: dev.leonardo.ocremotev2.domain.model.SseEvent.PermissionAsked, directory: String) {
        viewModelScope.launch {
            val rule = dev.leonardo.ocremotev2.domain.model.AutoApproveRule(
                toolName = event.permission,
                sessionId = null,
                directoryPattern = directory
            )
            chatRepository.addPermissionAutoApproveRule(rule)
        }
    }

    fun abortSession() {
        sessionStatusManager.onAbort(sessionId)
        viewModelScope.launch {
            try {
                sseJob?.cancel()
                sseJob = null
                sessionRepository.abort(serverId, sessionId, sessionDirectory)
                if (BuildConfig.DEBUG) Log.d(TAG, "Aborted session $sessionId")
                // Force-complete messages AND set Idle — abort is a terminal action.
                // Must use markSessionIdle (not Protected) because:
                // 1. SSE stream is cancelled above, server's idle event won't reach us
                // 2. markSessionIdleProtected is blocked by SSE freshness window (5s)
                // 3. markSessionIdleProtected doesn't complete messages, so premature-idle
                //    protection would block any future idle events
                sessionRepository.markSessionIdle(sessionId)
                // P5-2: restart sseJob to avoid _rawMessagesList freeze.
                runCatching { startObservingMessages() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to abort session", e)
            }
        }
    }

    /**
     * Reply to a question request.
     * @param requestId The question request ID
     * @param answers Answers for each question (list of selected labels per question)
     */
    fun replyToQuestion(requestId: String, answers: List<List<String>>) {
        viewModelScope.launch {
            val logMsg = "[Question] replyToQuestion: id=$requestId answers=$answers dir=$sessionDirectory"
            Log.i(TAG, logMsg)
            appendDiagnosticLog(logMsg)
            try {
                val success = managePermissionUseCase.replyToQuestion(
                    serverId = serverId,
                    requestId = requestId,
                    answers = answers,
                    directory = sessionDirectory
                )
                val resultMsg = "[Question] replyToQuestion result: id=$requestId success=$success"
                Log.i(TAG, resultMsg)
                appendDiagnosticLog(resultMsg)
                // Always remove the question card regardless of API result.
                chatRepository.removeQuestion(requestId)
            } catch (e: Exception) {
                val errMsg = "[Question] Exception replying to $requestId: ${e.javaClass.simpleName}: ${e.message}"
                Log.e(TAG, errMsg, e)
                appendDiagnosticLog(errMsg)
                // Network/timeout — remove card; server will re-emit if still pending
                chatRepository.removeQuestion(requestId)
            }
        }
    }

    /**
     * Reject a question request.
     */
    fun rejectQuestion(requestId: String) {
        viewModelScope.launch {
            val logMsg = "[Question] rejectQuestion: id=$requestId dir=$sessionDirectory"
            Log.i(TAG, logMsg)
            appendDiagnosticLog(logMsg)
            try {
                val success = managePermissionUseCase.rejectQuestion(serverId = serverId, requestId = requestId, directory = sessionDirectory)
                val resultMsg = "[Question] rejectQuestion result: id=$requestId success=$success"
                Log.i(TAG, resultMsg)
                appendDiagnosticLog(resultMsg)
                // Always remove the question card regardless of API result.
                // If already answered by another client, server returns non-2xx — card should still close.
                chatRepository.removeQuestion(requestId)
            } catch (e: Exception) {
                val errMsg = "[Question] Exception rejecting $requestId: ${e.javaClass.simpleName}: ${e.message}"
                Log.e(TAG, errMsg, e)
                appendDiagnosticLog(errMsg)
                // Network/timeout — remove card; server will re-emit if still pending
                chatRepository.removeQuestion(requestId)
            }
        }
    }

    // ============ Slash Command Actions ============

    /** Share the current session. Returns the share URL or null on failure. */
    fun shareSession(onResult: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                val session = shareExportUseCase.shareSession(serverId, sessionId)
                val url = session.share?.url
                if (BuildConfig.DEBUG) Log.d(TAG, "Shared session $sessionId: $url")
                onResult(url)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to share session", e)
                onResult(null)
            }
        }
    }

    fun unshareSession(onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                shareExportUseCase.unshareSession(serverId, sessionId)
                if (BuildConfig.DEBUG) Log.d(TAG, "Unshared session $sessionId")
                onResult(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unshare session", e)
                onResult(false)
            }
        }
    }

    /** Compact (summarize) the current session. */
    fun compactSession(onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                val config = modelConfigState.value
                val providerId = config.selectedProviderId
                val modelId = config.selectedModelId
                if (providerId == null || modelId == null) {
                    Log.e(TAG, "Cannot compact: no model selected")
                    onResult(false)
                    return@launch
                }
                shareExportUseCase.compactSession(serverId, sessionId, providerId, modelId)
                if (BuildConfig.DEBUG) Log.d(TAG, "Compacted session $sessionId")
                onResult(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to compact session", e)
                onResult(false)
            }
        }
    }

    /**
     * Export the session as JSON directly to a file URI.
     * Streams API responses directly to the output stream to avoid OOM
     * on large sessions (messages can be 80+ MB).
     * Shows a notification with download progress.
     */
    fun exportSession(context: android.content.Context, uri: android.net.Uri, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val notificationManager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val channelId = "opencode_export"
            val notificationId = 9999

            // Create notification channel
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    channelId,
                    context.getString(R.string.menu_export_session),
                    android.app.NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.notification_export_progress)
                    setShowBadge(false)
                }
                notificationManager.createNotificationChannel(channel)
            }

            val builder = androidx.core.app.NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(context.getString(R.string.menu_export_session))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(0, 0, true)

            try {
                Log.d(TAG, "exportSession: streaming to $uri")
                notificationManager.notify(notificationId, builder.build())

                var lastNotifyTime = 0L
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    shareExportUseCase.exportSessionToStream(serverId, sessionId, outputStream) { bytesWritten ->
                        val now = System.currentTimeMillis()
                        if (now - lastNotifyTime > 500) { // throttle to 2 updates/sec
                            lastNotifyTime = now
                            val mb = String.format("%.1f MB", bytesWritten / 1_000_000.0)
                            builder.setContentText(mb)
                            notificationManager.notify(notificationId, builder.build())
                        }
                    }
                }

                Log.d(TAG, "exportSession: done")
                notificationManager.cancel(notificationId)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onResult(true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to export session", e)
                notificationManager.cancel(notificationId)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onResult(false)
                }
            }
        }
    }

    /** Undo the last user message in the session, restoring its text to the input field. */
    fun undoMessage(onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                // Find the last user message (before any existing revert point)
                val messages = messageListState.value.messages
                val lastUser = messages.firstOrNull { it.isUser }
                if (lastUser == null) {
                    onResult(false)
                    return@launch
                }
                undoRedoUseCase.revertSession(serverId, sessionId, lastUser.message.id)
                if (BuildConfig.DEBUG) Log.d(TAG, "Reverted session $sessionId to message ${lastUser.message.id}")
                // Restore the user message text to the input field
                restoreRevertedDraft(extractRevertedDraft(lastUser))
                onResult(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to revert session", e)
                onResult(false)
            }
        }
    }

    /** Revert to a specific user message by ID, optionally restoring its text to the input field. */
    fun revertMessage(messageId: String, revertedText: String? = null, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                // Halt: if session is busy (AI generating), abort first before reverting.
                // Same pattern as OpenCode WebUI: halt(sessionID).then(() => revert(input))
                val currentStatus = sessionStatusManager.statusFlow.value[sessionId]
                if (currentStatus is SessionStatus.Busy || currentStatus is SessionStatus.Retry) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Revert: halting busy session $sessionId")
                    sessionStatusManager.onAbort(sessionId)
                    sseJob?.cancel()
                    sseJob = null
                    runCatching { sessionRepository.abort(serverId, sessionId, sessionDirectory) }
                    sessionRepository.markSessionIdle(sessionId)
                    // NOTE: startObservingMessages deferred to after setRevert below
                }

                undoRedoUseCase.revertSession(serverId, sessionId, messageId)
                if (BuildConfig.DEBUG) Log.d(TAG, "Reverted session $sessionId to message $messageId")

                // Set local revert state BEFORE reconnecting SSE.
                // This ensures the message filter (id < revertState.messageId)
                // is active when the new SSE connection pushes messages.
                chatRepository.setRevert(sessionId, messageId)

                // Reconnect SSE now — old messages will be filtered by revert state.
                if (currentStatus is SessionStatus.Busy || currentStatus is SessionStatus.Retry) {
                    runCatching { startObservingMessages() }
                }

                val targetMessage = messageListState.value.messages
                    .firstOrNull { it.message.id == messageId && it.isUser }
                val fallbackPayload = RevertedDraftPayload(text = revertedText.orEmpty())
                restoreRevertedDraft(targetMessage?.let { extractRevertedDraft(it) } ?: fallbackPayload)
                onResult(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to revert to message $messageId", e)
                onResult(false)
            }
        }
    }

    private fun extractRevertedDraft(message: ChatMessage): RevertedDraftPayload {
        val revertedText = message.parts
            .filterIsInstance<Part.Text>()
            .joinToString("\n") { it.text }

        val imageUris = message.parts
            .filterIsInstance<Part.File>()
            .mapNotNull { part ->
                val mime = part.mime.lowercase()
                if (mime.startsWith("image/") && !part.url.isNullOrBlank()) part.url else null
            }

        return RevertedDraftPayload(
            text = revertedText,
            attachmentUris = imageUris,
        )
    }

    private fun restoreRevertedDraft(payload: RevertedDraftPayload) {
        _draftText.value = payload.text
        _draftAttachmentUris.value = payload.attachmentUris
        _confirmedFilePaths.value = emptySet()
        _revertedDraftEvent.tryEmit(payload)
    }

    /** Redo the last undone message. */
    fun redoMessage(onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                undoRedoUseCase.unrevertSession(serverId, sessionId)
                if (BuildConfig.DEBUG) Log.d(TAG, "Unreverted session $sessionId")
                onResult(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unrevert session", e)
                onResult(false)
            }
        }
    }

    /** Delete a message from the current session. */
    fun deleteMessage(messageId: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                val success = manageSessionUseCase.deleteMessage(serverId, sessionId, messageId)
                if (BuildConfig.DEBUG) Log.d(TAG, "Deleted message $messageId: success=$success")
                onResult(success)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete message $messageId", e)
                onResult(false)
            }
        }
    }

    /** Delete a specific part from a message by index. */
    fun deleteMessagePart(messageId: String, partIndex: Int, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                val success = manageSessionUseCase.deleteMessagePart(serverId, sessionId, messageId, partIndex)
                if (BuildConfig.DEBUG) Log.d(TAG, "Deleted part $partIndex from message $messageId: success=$success")
                onResult(success)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete part $partIndex from message $messageId", e)
                onResult(false)
            }
        }
    }

    /**
     * Called when a SessionUpdated SSE event is received.
     * Refreshes the message list to pick up revert/unrevert changes.
     */
    fun onSessionUpdated(session: Session) {
        if (session.id != sessionId) return
        viewModelScope.launch {
            try {
                val messages = manageSessionUseCase.listMessages(serverId, sessionId, 100)
                chatRepository.replaceMessages(sessionId, messages)
                if (BuildConfig.DEBUG) Log.d(TAG, "Refreshed messages after session update")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh messages after session update", e)
            }
        }
    }

    /** Fork the current session. Returns the new session or null. */
    fun forkSession(onResult: (Session?) -> Unit) {
        viewModelScope.launch {
            try {
                val session = manageSessionUseCase.forkSession(serverId, sessionId)
                if (BuildConfig.DEBUG) Log.d(TAG, "Forked session $sessionId -> ${session.id}")
                onResult(session)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fork session", e)
                onResult(null)
            }
        }
    }

    /** Rename the current session. */
    fun renameSession(title: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                manageSessionUseCase.renameSession(serverId, sessionId, title)
                if (BuildConfig.DEBUG) Log.d(TAG, "Renamed session $sessionId to $title")
                onResult(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to rename session", e)
                onResult(false)
            }
        }
    }

    /** Execute a server-side command (e.g. /init, /review, MCP commands). */
    fun executeCommand(command: String, arguments: String = "", onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                val currentSessionId = ensureSession()
                if (sessionDirectory.isNullOrBlank()) {
                    loadSession()
                }

                val normalizedCommand = command.removePrefix("/").trim()
                val effectiveDirectory = sessionDirectory
                    ?: chatRepository.getSessionsSnapshot()
                        .firstOrNull { it.id == currentSessionId }
                        ?.directory
                        ?.takeIf { it.isNotBlank() }
                // /init: when arguments are omitted, rely on x-opencode-directory only.
                // Passing an explicit path (absolute or ".") can lead to duplicated or
                // malformed path text in the generated init prompt.
                val effectiveArguments = if (
                    normalizedCommand.equals("init", ignoreCase = true) && arguments.isBlank()
                ) {
                    ""
                } else {
                    arguments
                }

                val ok = manageTerminalUseCase.executeCommand(
                    serverId = serverId,
                    sessionId = currentSessionId,
                    command = normalizedCommand,
                    arguments = effectiveArguments,
                    directory = effectiveDirectory,
                )
                if (BuildConfig.DEBUG) {
                    Log.d(
                        TAG,
                        "Executed command /$normalizedCommand in session $currentSessionId: $ok (directory=$effectiveDirectory, arguments=$effectiveArguments)"
                    )
                }
                onResult(ok)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to execute command /$command", e)
                onResult(false)
            }
        }
    }

    /** Execute shell command in current session. */
    fun runShellCommand(command: String, onResult: (Boolean) -> Unit) {
        val trimmed = command.trim()
        if (trimmed.isBlank()) {
            onResult(false)
            return
        }
        viewModelScope.launch {
            try {
                // P5-5: read from modelConfigState for consistency with sendParts.
                val modelCfg = modelConfigState.value
                val model = if (modelCfg.selectedProviderId != null && modelCfg.selectedModelId != null) {
                    ModelSelection(
                        providerId = modelCfg.selectedProviderId!!,
                        modelId = modelCfg.selectedModelId!!
                    )
                } else null
                val ok = manageTerminalUseCase.runShellCommand(
                    serverId = serverId,
                    sessionId = sessionId,
                    command = trimmed,
                    agent = modelConfigState.value.selectedAgent,
                    model = model,
                    directory = sessionDirectory
                )
                if (BuildConfig.DEBUG) Log.d(TAG, "Executed shell command in session $sessionId: $ok")
                onResult(ok)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to execute shell command", e)
                onResult(false)
            }
        }
    }

    fun openTerminalSession(onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            // Wait for loadSession() to finish so sessionDirectory is populated.
            // This prevents the race condition where the PTY is created with directory=null
            // and then resize is attempted with the real directory.
            sessionLoaded.await()
            if (BuildConfig.DEBUG) Log.d(TAG, "openTerminalSession: sessionDirectory=$sessionDirectory")
            terminalWorkspace.ensureActiveTab(cwd = sessionDirectory, directory = sessionDirectory, onResult = onResult)
        }
    }

    fun createTerminalTab(onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            sessionLoaded.await()
            terminalWorkspace.createTab(cwd = sessionDirectory, directory = sessionDirectory, onResult = onResult)
        }
    }

    fun switchTerminalTab(tabId: String) {
        terminalWorkspace.switchTab(tabId)
    }

    fun closeTerminalTab(tabId: String) {
        terminalWorkspace.closeTab(tabId)
    }

    fun reconnectTerminalTab(tabId: String, onResult: (Boolean) -> Unit = {}) {
        terminalWorkspace.reconnectTab(tabId, onResult)
    }

    fun setTerminalFontSize(fontSizeSp: Float) {
        terminalWorkspace.setActiveFontSize(fontSizeSp)
    }

    fun sendTerminalInput(input: String) {
        terminalWorkspace.sendActiveInput(input)
    }

    fun clearTerminalBuffer() {
        terminalWorkspace.clearActiveBuffer()
    }

    fun resizeTerminal(cols: Int, rows: Int) {
        terminalWorkspace.resizeActive(cols, rows)
    }

    fun closeTerminalSession() {
        // Global terminal workspaces are server-scoped and survive chat screen changes.
    }

    /** Connection parameters for navigation to other sessions. */
    fun getConnectionParams(): ConnectionParams = ConnectionParams(
        serverUrl = serverUrl,
        username = username,
        password = password,
        serverName = serverName,
        serverId = serverId
    )

    /** Get the last assistant message text for copying. */
    fun getLastAssistantText(): String? {
        val msgs = messageListState.value.messages
        val last = msgs.firstOrNull { it.isAssistant } ?: return null
        return last.parts
            .filterIsInstance<Part.Text>()
            .joinToString("") { it.text }
            .ifBlank { null }
    }

    /** Append a diagnostic log line for permission/question debugging. */
    private fun appendDiagnosticLog(message: String) {
        // Log to logcat only; file writing requires MediaStore on Android 11+
        Log.i(TAG, message)
    }

    companion object {
        /**
         * In-memory cache mapping sessionId → (providerId, modelId).
         * Survives session switching (ViewModel recreation) but clears on app restart (process death).
         */
        private val sessionModelCache = mutableMapOf<String, Pair<String, String>>()

        const val REFRESH_COOLDOWN_MS = 5_000L  // Skip refresh if last one was < 5s ago
    }
}

/** Holds server connection info for navigation purposes. */
data class ConnectionParams(
    val serverUrl: String,
    val username: String,
    val password: String,
    val serverName: String,
    val serverId: String
)
