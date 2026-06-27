package dev.leonardo.ocremotev2.ui.screens.chat

import android.util.Log
import androidx.compose.runtime.Immutable
import dev.leonardo.ocremotev2.BuildConfig
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
import dev.leonardo.ocremotev2.ui.screens.chat.tools.ToolCardResolver
import dev.leonardo.ocremotev2.ui.screens.chat.util.ContextBreakdown
import dev.leonardo.ocremotev2.ui.screens.chat.util.ContextDetailState
import dev.leonardo.ocremotev2.ui.screens.chat.util.MessageCount
import dev.leonardo.ocremotev2.ui.screens.chat.util.ProviderModel
import dev.leonardo.ocremotev2.ui.screens.chat.util.SessionTimestamps
import dev.leonardo.ocremotev2.ui.screens.chat.util.cacheHitRate
import dev.leonardo.ocremotev2.ui.screens.chat.util.countMessages
import dev.leonardo.ocremotev2.ui.screens.chat.util.estimateContextBreakdown
import dev.leonardo.ocremotev2.domain.model.*
import dev.leonardo.ocremotev2.domain.repository.ChatRepository
import dev.leonardo.ocremotev2.domain.repository.SessionRepository
import dev.leonardo.ocremotev2.domain.repository.SettingsRepository
import dev.leonardo.ocremotev2.domain.tracker.TokenStatsTracker
import dev.leonardo.ocremotev2.domain.usecase.*
import dev.leonardo.ocremotev2.data.api.SseClient
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

    // (lastRefreshTimeMs migrated to SessionActionsDelegate — Phase 3 Task 6 — G cluster.)
    // (_isLoading / _isRefreshing / _error / _isSending / _messagesList / _rawMessagesList /
    //  _partsList / sseJob) migrated to MessageDataDelegate (Phase 3 Task 5 — B cluster).

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
    // ============ Session Lifecycle Delegate (Phase 3 Task 3 — C cluster) ============
    // Owns session identity/directory/lazy-creation — the spine of the delegate layer.
    // sessionIdFlow feeds 6 combine pipelines; sessionDirectory/sessionLoaded feed
    // TerminalDelegate & DraftInputDelegate via their constructor providers.
    private val sessionLifecycle = SessionLifecycleDelegate(
        manageSessionUseCase = manageSessionUseCase,
        sessionRepository = sessionRepository,
        serverId = serverId,
        savedStateHandle = savedStateHandle,
        scope = viewModelScope,
        // Forwarding via VM methods (not direct messageData refs) avoids a
        // property-init circular dependency: messageData needs sessionLifecycle
        // .sessionIdFlow while sessionLifecycle's lambdas would reference messageData.
        // Method calls resolve lazily at invocation time (well after both are init).
        onMessagesNeedLoading = { loadMessagesForSession() },
        onStartObservingMessages = { startObservingMessages() },
    )
    /** Current session id — facade over [sessionLifecycle]. */
    val sessionId: String get() = sessionLifecycle.sessionId

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

    // ============ Model Config Delegate (Phase 3 Task 4 — A cluster) ============
    // Owns provider/agent/model/variant/command selection and the modelConfigState
    // resolution pipeline (with self-feedback side effects). Consumes sessionIdFlow
    // from sessionLifecycle; exposes selectedAgentValue/selectedVariantValue for
    // DraftInputDelegate draft persistence and sendParts().
    private val modelConfig = ModelConfigDelegate(
        selectModelUseCase = selectModelUseCase,
        manageAgentUseCase = manageAgentUseCase,
        settingsRepository = settingsRepository,
        sessionRepository = sessionRepository,
        messagePaging = messagePaging,
        tokenStatsTracker = tokenStatsTracker,
        serverId = serverId,
        sessionIdFlow = sessionLifecycle.sessionIdFlow,
        scope = viewModelScope,
    )
    val modelConfigState: StateFlow<ModelConfigState> get() = modelConfig.modelConfigState
    fun selectAgent(name: String) = modelConfig.selectAgent(name)
    fun cycleVariant() = modelConfig.cycleVariant()
    fun selectModel(providerId: String, modelId: String) = modelConfig.selectModel(providerId, modelId)

    // ============ Message Data Delegate (Phase 3 Task 5 — B cluster) ============
    // Owns message SSE observation, loading, pagination, send-state, tool-expand,
    // and the messageListState/interactionState combine pipelines. Consumes
    // sessionIdFlow from sessionLifecycle; exposes intent methods (onSendStarted/
    // onSendSuccess/onSendError) and sseJob management (cancelSseJob/
    // startObservingMessages) so sendParts/abort/revert coordinators stay in the
    // ViewModel but never touch this delegate's private state directly.
    private val messageData: MessageDataDelegate = MessageDataDelegate(
        manageSessionUseCase = manageSessionUseCase,
        managePermissionUseCase = managePermissionUseCase,
        chatRepository = chatRepository,
        messagePaging = messagePaging,
        sessionStatusManager = sessionStatusManager,
        sessionRepository = sessionRepository,
        settingsRepository = settingsRepository,
        serverId = serverId,
        sessionIdFlow = sessionLifecycle.sessionIdFlow,
        sessionDirectoryProvider = { sessionLifecycle.sessionDirectory },
        scope = viewModelScope,
    )
    /** Message list state — facade over [messageData]. */
    val messageListState: StateFlow<MessageListState> get() = messageData.messageListState
    /** Interaction state — facade over [messageData]. */
    val interactionState: StateFlow<InteractionState> get() = messageData.interactionState

    private val terminalDelegate = TerminalDelegate(
        terminalRegistry = terminalRegistry,
        settingsRepository = settingsRepository,
        serverId = serverId,
        serverUrl = serverUrl,
        username = username,
        password = password.ifEmpty { null },
        scope = viewModelScope,
        sessionDirectoryProvider = { sessionLifecycle.sessionDirectory },
        sessionLoaded = sessionLifecycle.sessionLoaded,
    )
    val terminalTabs: StateFlow<List<TerminalTabUi>> get() = terminalDelegate.terminalTabs
    val activeTerminalTabId: StateFlow<String?> get() = terminalDelegate.activeTerminalTabId
    /** Incremented on active terminal tab updates — observe to trigger recomposition. */
    val terminalVersion: StateFlow<Long> get() = terminalDelegate.terminalVersion
    val terminalConnected: StateFlow<Boolean> get() = terminalDelegate.terminalConnected
    val terminalFontSizeSp: StateFlow<Float> get() = terminalDelegate.terminalFontSizeSp
    val terminalEmulator: org.connectbot.terminal.TerminalEmulator get() = terminalDelegate.terminalEmulator
    val terminalCursorKeysAppMode: Boolean get() = terminalDelegate.terminalCursorKeysAppMode

    // ============ Draft Input Delegate (Phase 3 Task 2 — D cluster) ============
    private val draftDelegate = DraftInputDelegate(
        draftUseCase = draftUseCase,
        manageAgentUseCase = manageAgentUseCase,
        scope = viewModelScope,
        serverId = serverId,
        sessionIdProvider = { sessionLifecycle.sessionId },
        sessionDirectoryProvider = { sessionLifecycle.sessionDirectory },
        selectedAgentProvider = { modelConfig.selectedAgentValue },
        selectedVariantProvider = { modelConfig.selectedVariantValue },
    )
    val draftText: StateFlow<String> get() = draftDelegate.draftText
    val revertedDraftEvent: SharedFlow<RevertedDraftPayload> get() = draftDelegate.revertedDraftEvent
    val draftAttachmentUris: StateFlow<List<String>> get() = draftDelegate.draftAttachmentUris
    val confirmedFilePaths: StateFlow<Set<String>> get() = draftDelegate.confirmedFilePaths

    // ============ Session Actions Delegate (Phase 3 Task 6 — G cluster) ============
    // Owns 24 stateless REST operations — no private StateFlow. Reads other delegates'
    // state via providers and delegates to UseCases/Repositories. Cross-delegate
    // coordinators (sendParts, revertMessage, abortSession) stay in this ViewModel.
    private val sessionActions = SessionActionsDelegate(
        shareExportUseCase = shareExportUseCase,
        undoRedoUseCase = undoRedoUseCase,
        manageSessionUseCase = manageSessionUseCase,
        managePermissionUseCase = managePermissionUseCase,
        manageTerminalUseCase = manageTerminalUseCase,
        sessionRepository = sessionRepository,
        chatRepository = chatRepository,
        serverId = serverId,
        scope = viewModelScope,
        sessionIdProvider = { sessionLifecycle.sessionId },
        sessionDirectoryProvider = { sessionLifecycle.sessionDirectory },
        modelConfigProvider = { modelConfigState.value },
        messageListProvider = { messageListState.value.messages },
        ensureSession = { sessionLifecycle.ensureSession() },
        loadSessionInfo = { sessionLifecycle.loadSession() },
        awaitSessionLoaded = { sessionLifecycle.sessionLoaded.await() },
        refreshMessages = { messageData.refreshMessages() },
        fixIncompleteMessagesIfIdle = { messageData.fixIncompleteMessagesIfIdle(it) },
        loadPendingQuestions = { messageData.loadPendingQuestions() },
        loadPendingPermissions = { messageData.loadPendingPermissions() },
        restoreRevertedDraft = { draftDelegate.restoreRevertedDraft(it) },
    )

    // ============ Settings (exposed for ChatScreen) ============
    val chatFontSize = settingsRepository.getSettingsFlow().map { it.chatFontSize }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), "medium"
    )
    val chatDensity = settingsRepository.getSettingsFlow().map { it.chatDensity }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), "normal"
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
    // ============ Tool Expand / Pagination (delegated — Phase 3 Task 5) ============
    val toolExpandedStates: StateFlow<Map<String, Boolean>> get() = messageData.toolExpandedStates

    fun toggleToolExpanded(toolId: String, defaultExpanded: Boolean = false) =
        messageData.toggleToolExpanded(toolId, defaultExpanded)

    fun isToolExpanded(toolId: String, autoExpand: Boolean): Boolean =
        messageData.isToolExpanded(toolId, autoExpand)

    // ============ Scroll Position Save/Restore ============
    private val scrollPositionDelegate = ScrollPositionDelegate()

    /** Saved scroll position for restoring after sub-session navigation. */
    val savedMessageId: String? get() = scrollPositionDelegate.savedMessageId
    /** Raw LazyColumn index at save time — used for direct restoration without index arithmetic. */
    val savedLazyIndex: Int get() = scrollPositionDelegate.savedLazyIndex
    val savedScrollOffset: Int get() = scrollPositionDelegate.savedScrollOffset
    val scrollRestoreVersion: Int get() = scrollPositionDelegate.scrollRestoreVersion

    /** Public read-only flag: true when scroll position needs to be restored on return. */
    val pendingScrollRestore: Boolean get() = scrollPositionDelegate.pendingScrollRestore

    fun clearPendingScrollRestore() = scrollPositionDelegate.clearPendingScrollRestore()

    fun saveScrollPosition(lazyIndex: Int, offset: Int) =
        scrollPositionDelegate.saveScrollPosition(lazyIndex, offset)

    /**
     * Re-triggers scroll position restoration on ON_RESUME, but only when a save is pending
     * (i.e. the user is returning from FileViewer or a sub-session). Plain background→foreground
     * transitions are ignored so the user's current browsing position is not disturbed.
     */
    fun bumpScrollRestoreIfPending() = scrollPositionDelegate.bumpScrollRestoreIfPending()
    val expandReasoning = settingsRepository.getSettingsFlow().map { it.expandReasoning }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), false
    )
    val showTurnDividers = settingsRepository.getSettingsFlow().map { it.showTurnDividers }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), true
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
    /** Expose restored draft as StateFlow for ChatScreen consumption. */
    val restoredDraftState: StateFlow<RevertedDraftPayload?> get() = draftDelegate.restoredDraftState

    // NOTE: Legacy uiState is declared after the 5 split StateFlows below (needs forward references).

    // ============ Split State Flows (independent combines for fine-grained recomposition) ============

    // messageListState — migrated to MessageDataDelegate (Phase 3 Task 5 — B cluster).

    /**
     * Session metadata — changes when session info is updated (title, status, agent).
     * Includes [SessionLifecycleDelegate.sessionIdFlow] as a source so lazy-session creation triggers immediate recomputation.
     * Session status is sourced from [SessionStatusManager.statusFlow] (FSM-driven),
     * the single source of truth for busy/idle/activity state.
     */
    val sessionMetaState: StateFlow<SessionMetaState> = combine(
        sessionLifecycle.sessionIdFlow,
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

    // interactionState — migrated to MessageDataDelegate (Phase 3 Task 5 — B cluster).

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
    val directoryState: StateFlow<String> = sessionLifecycle.sessionIdFlow.flatMapLatest { sid ->
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
    val contextDetailState: StateFlow<ContextDetailState> = sessionLifecycle.sessionIdFlow.flatMapLatest { sid ->
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
        draftDelegate.restoredDraftState,
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
            messageData.messagesList.collect { messages ->
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

        // Restore draft from disk — apply restored agent/variant to model config
        if (!isNewSession) {
            val draft = draftDelegate.restorePersistedDraft()
            if (draft != null) {
                modelConfig.applyDraftRestore(draft.selectedAgent, draft.selectedVariant)
            }
        }

        // Restore model selection from in-memory cache (survives session switching, cleared on app restart)
        if (!isNewSession) {
            modelConfig.restoreModelFromCache()
        }

        modelConfig.observeHiddenModels()

        // Load initial message count from settings, then load data
        if (!isNewSession) {
            viewModelScope.launch {
                try {
                    sessionLifecycle.loadSession()
                } catch (e: Exception) {
                }
                try {
                    messageData.loadMessages()
                } catch (e: Exception) {
                }
                try {
                    messageData.loadPendingQuestions()
                } catch (e: Exception) {
                }
                try {
                    messageData.loadPendingPermissions()
                } catch (e: Exception) {
                }
            }
        } else {
            // New session: set directory from route param, skip loading
            sessionLifecycle.initForNewSession()
            // New session has nothing to load — mark loading complete immediately
            messageData.markLoaded()
        }
        modelConfig.loadProviders()
        modelConfig.loadAgents()
        modelConfig.loadCommands()
    }

    // loadMessagesForSession / startObservingMessages — migrated to MessageDataDelegate
    // (Phase 3 Task 5 — B cluster). These thin forwarders are invoked via the
    // sessionLifecycle callbacks; they exist as VM methods (rather than inlining
    // messageData refs into the lambdas) to avoid a property-init circular dep.
    private suspend fun loadMessagesForSession() = messageData.loadMessagesForSession()
    private fun startObservingMessages() = messageData.startObservingMessages()

    /**
     * Load messages via V1 API for modelConfigState resolution (model/agent from history).
     * Facade over [messageData].
     */
    fun loadMessages() = messageData.loadMessages()

    /**
     * Refresh session data — facade over [sessionActions].
     */
    fun refreshSession() = sessionActions.refreshSession()

    /**
     * Refresh session only if enough time has passed since last refresh.
     * Facade over [sessionActions].
     */
    fun refreshIfNeeded() = sessionActions.refreshIfNeeded()

    // refreshMessages — migrated to MessageDataDelegate (Phase 3 Task 5 — B cluster).

    /**
     * Query the OpenCode server for actual session statuses and correct
     * any UI state drift caused by missed SSE events. Facade over [sessionActions].
     */
    fun syncSessionStatus() = sessionActions.syncSessionStatus()

    // refreshAndSync — migrated to SessionActionsDelegate (Phase 3 Task 6 — G cluster).

    /** Load older messages — facade over [messageData]. */
    fun loadOlderMessages() = messageData.loadOlderMessages()

    // loadPendingQuestions — migrated to MessageDataDelegate (Phase 3 Task 5 — B cluster).

    // loadPendingPermissions — migrated to MessageDataDelegate (Phase 3 Task 5 — B cluster).

    // Model/agent/provider/variant/command selection + modelConfigState resolution
    // are delegated to ModelConfigDelegate (Phase 3 Task 4 — A cluster).

    // ============ @ File Mention Search (delegated — Phase 3 Task 2) ============
    val fileSearchResults: StateFlow<List<String>> get() = draftDelegate.fileSearchResults

    fun searchFilesForMention(query: String) = draftDelegate.searchFilesForMention(query)
    fun confirmFilePath(path: String) = draftDelegate.confirmFilePath(path)
    fun removeFilePath(path: String) = draftDelegate.removeFilePath(path)
    fun clearFileSearch() = draftDelegate.clearFileSearch()
    fun clearConfirmedPaths() = draftDelegate.clearConfirmedPaths()

    // ============ Draft Management (delegated) ============

    fun updateDraftText(text: String) = draftDelegate.updateDraftText(text)
    fun addDraftAttachment(uri: String) = draftDelegate.addDraftAttachment(uri)
    fun removeDraftAttachment(index: Int) = draftDelegate.removeDraftAttachment(index)
    fun clearDraft() = draftDelegate.clearDraft()
    fun consumeRestoredDraft() = draftDelegate.consumeRestoredDraft()

    override fun onCleared() {
        messageData.cancelSseJob()
        closeTerminalSession()
        super.onCleared()
        draftDelegate.saveDraft()
    }

    /** Get the session directory for building file:// URLs */
    fun getSessionDirectory(): String? = sessionLifecycle.sessionDirectory

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
     * Schedule a delayed REST refresh to fetch the updated session title.
     * Only refreshes if the current title looks like a default placeholder
     * (null, empty, or matches "New session - ..." pattern).
     */
    private fun refreshSessionTitleDelayed(sid: String) {
        viewModelScope.launch {
            kotlinx.coroutines.delay(8_000) // Wait for server async title generation
            try {
                val refreshed = manageSessionUseCase.getSession(serverId, sid)
                val currentSession = chatRepository.getSessionsSnapshot().find { it.id == sid }
                val currentTitle = currentSession?.title
                // Only update if the title actually changed (skip if SSE already delivered it)
                if (refreshed.title != currentTitle) {
                    val msg = "[Title] REST fallback: title updated from '$currentTitle' to '${refreshed.title}'"
                    Log.i(TAG, msg)
                    appendDiagnosticLog(msg)
                    sessionRepository.setSessions(serverId, listOf(refreshed))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to refresh session title for $sid: ${e.message}")
            }
        }
    }

    private fun sendParts(parts: List<PromptPart>) {
        scrollSignal.requestScrollToTop()
        val pendingId = "pending-${java.util.UUID.randomUUID()}"
        messageData.onSendStarted(pendingId)
        viewModelScope.launch {
            try {
                val currentSessionId = sessionLifecycle.ensureSession()
                sessionStatusManager.onSendParts(currentSessionId)
                // P5-5: read from modelConfigState (resolved effective value) instead of
                // raw _selectedProviderId which may be null on new session's first send.
                val modelCfg = modelConfigState.value
                val model = if (modelCfg.selectedProviderId != null && modelCfg.selectedModelId != null) {
                    ModelSelection(
                        providerId = modelCfg.selectedProviderId,
                        modelId = modelCfg.selectedModelId
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
                    variant = modelConfig.selectedVariantValue,
                    directory = sessionLifecycle.sessionDirectory
                )
                messageData.onSendSuccess(pendingId)
                if (BuildConfig.DEBUG) Log.d(TAG, "Sent prompt to session $currentSessionId (${parts.size} parts)")
                refreshSessionTitleDelayed(currentSessionId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message", e)
                // Restore draft from the failed send
                val failedText = parts.filter { it.type == "text" }.mapNotNull { it.text }.joinToString("\n")
                if (failedText.isNotBlank()) {
                    draftDelegate.setRestoredDraft(RevertedDraftPayload(text = failedText))
                }
                messageData.onSendError(e.message ?: "Failed to send message", pendingId)
            }
        }
    }

    /**
     * Reply to a permission request. Facade over [sessionActions].
     * @param requestId The permission request ID
     * @param reply One of: "once", "always", "reject"
     */
    fun replyToPermission(requestId: String, reply: String) =
        sessionActions.replyToPermission(requestId, reply)

    /** Save a permission auto-approve rule. Facade over [sessionActions]. */
    fun savePermissionRule(event: dev.leonardo.ocremotev2.domain.model.SseEvent.PermissionAsked, directory: String) =
        sessionActions.savePermissionRule(event, directory)

    /**
     * Abort the current session — coordinator.
     * Delegates REST abort + markIdle to [sessionActions], then handles
     * SSE job cancel/restart (B↔C↔G orchestration).
     */
    fun abortSession() {
        sessionStatusManager.onAbort(sessionId)
        viewModelScope.launch {
            try {
                messageData.cancelSseJob()
                sessionActions.abortSession()
                if (BuildConfig.DEBUG) Log.d(TAG, "Aborted session $sessionId")
                // P5-2: restart sseJob to avoid _rawMessagesList freeze.
                runCatching { messageData.startObservingMessages() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to abort session", e)
            }
        }
    }

    /**
     * Reply to a question request. Facade over [sessionActions].
     * @param requestId The question request ID
     * @param answers Answers for each question (list of selected labels per question)
     */
    fun replyToQuestion(requestId: String, answers: List<List<String>>) =
        sessionActions.replyToQuestion(requestId, answers)

    /**
     * Reject a question request. Facade over [sessionActions].
     */
    fun rejectQuestion(requestId: String) =
        sessionActions.rejectQuestion(requestId)

    // ============ Slash Command Actions (delegated — Phase 3 Task 6) ============

    /** Share the current session. Facade over [sessionActions]. */
    fun shareSession(onResult: (String?) -> Unit) =
        sessionActions.shareSession(onResult)

    /** Unshare the current session. Facade over [sessionActions]. */
    fun unshareSession(onResult: (Boolean) -> Unit) =
        sessionActions.unshareSession(onResult)

    /** Compact (summarize) the current session. Facade over [sessionActions]. */
    fun compactSession(onResult: (Boolean) -> Unit) =
        sessionActions.compactSession(onResult)

    /**
     * Export the session as JSON to a file URI. Facade over [sessionActions].
     */
    fun exportSession(context: android.content.Context, uri: android.net.Uri, onResult: (Boolean) -> Unit) =
        sessionActions.exportSession(context, uri, onResult)

    /** Undo the last user message in the session, restoring its text to the input field. Facade over [sessionActions]. */
    fun undoMessage(onResult: (Boolean) -> Unit) =
        sessionActions.undoMessage(onResult)

    /** Revert to a specific user message by ID, optionally restoring its text to the input field.
     *  Coordinator (B↔D↔G orchestration): halts busy session, reverts via undoRedoUseCase,
     *  reconnects SSE, restores draft. */
    fun revertMessage(messageId: String, revertedText: String? = null, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                // Halt: if session is busy (AI generating), abort first before reverting.
                // Same pattern as OpenCode WebUI: halt(sessionID).then(() => revert(input))
                val currentStatus = sessionStatusManager.statusFlow.value[sessionId]
                if (currentStatus is SessionStatus.Busy || currentStatus is SessionStatus.Retry) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Revert: halting busy session $sessionId")
                    sessionStatusManager.onAbort(sessionId)
                    messageData.cancelSseJob()
                    runCatching { sessionRepository.abort(serverId, sessionId, sessionLifecycle.sessionDirectory) }
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
                    runCatching { messageData.startObservingMessages() }
                }

                val targetMessage = messageListState.value.messages
                    .firstOrNull { it.message.id == messageId && it.isUser }
                val fallbackPayload = RevertedDraftPayload(text = revertedText.orEmpty())
                draftDelegate.restoreRevertedDraft(
                    targetMessage?.let { sessionActions.extractRevertedDraft(it) } ?: fallbackPayload
                )
                onResult(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to revert to message $messageId", e)
                onResult(false)
            }
        }
    }

    // extractRevertedDraft — migrated to SessionActionsDelegate (Phase 3 Task 6 — G cluster).
    // restoreRevertedDraft — inlined to draftDelegate.restoreRevertedDraft() (Phase 3 Task 6).

    /** Redo the last undone message. Facade over [sessionActions]. */
    fun redoMessage(onResult: (Boolean) -> Unit) =
        sessionActions.redoMessage(onResult)

    /** Delete a message from the current session. Facade over [sessionActions]. */
    fun deleteMessage(messageId: String, onResult: (Boolean) -> Unit) =
        sessionActions.deleteMessage(messageId, onResult)

    /** Delete a specific part from a message by index. Facade over [sessionActions]. */
    fun deleteMessagePart(messageId: String, partIndex: Int, onResult: (Boolean) -> Unit) =
        sessionActions.deleteMessagePart(messageId, partIndex, onResult)

    /**
     * Called when a SessionUpdated SSE event is received. Facade over [sessionActions].
     */
    fun onSessionUpdated(session: Session) =
        sessionActions.onSessionUpdated(session)

    /** Fork the current session. Facade over [sessionActions]. */
    fun forkSession(onResult: (Session?) -> Unit) =
        sessionActions.forkSession(onResult)

    /** Rename the current session. Facade over [sessionActions]. */
    fun renameSession(title: String, onResult: (Boolean) -> Unit) =
        sessionActions.renameSession(title, onResult)

    /** Execute a server-side command. Facade over [sessionActions]. */
    fun executeCommand(command: String, arguments: String = "", onResult: (Boolean) -> Unit) =
        sessionActions.executeCommand(command, arguments, onResult)

    /** Execute shell command in current session. Facade over [sessionActions]. */
    fun runShellCommand(command: String, onResult: (Boolean) -> Unit) =
        sessionActions.runShellCommand(command, onResult)

    fun openTerminalSession(onResult: (Boolean) -> Unit = {}) =
        terminalDelegate.openTerminalSession(onResult)

    fun createTerminalTab(onResult: (Boolean) -> Unit = {}) =
        terminalDelegate.createTerminalTab(onResult)

    fun switchTerminalTab(tabId: String) = terminalDelegate.switchTerminalTab(tabId)

    fun closeTerminalTab(tabId: String) = terminalDelegate.closeTerminalTab(tabId)

    fun reconnectTerminalTab(tabId: String, onResult: (Boolean) -> Unit = {}) =
        terminalDelegate.reconnectTerminalTab(tabId, onResult)

    fun setTerminalFontSize(fontSizeSp: Float) =
        terminalDelegate.setTerminalFontSize(fontSizeSp)

    fun sendTerminalInput(input: String) = terminalDelegate.sendTerminalInput(input)

    fun clearTerminalBuffer() = terminalDelegate.clearTerminalBuffer()

    fun resizeTerminal(cols: Int, rows: Int) = terminalDelegate.resizeTerminal(cols, rows)

    fun closeTerminalSession() = terminalDelegate.closeTerminalSession()

    /** Connection parameters for navigation to other sessions. */
    fun getConnectionParams(): ConnectionParams = ConnectionParams(
        serverUrl = serverUrl,
        username = username,
        password = password,
        serverName = serverName,
        serverId = serverId
    )

    /** Get the last assistant message text for copying. Facade over [sessionActions]. */
    fun getLastAssistantText(): String? = sessionActions.getLastAssistantText()

    /** Append a diagnostic log line for permission/question debugging. */
    private fun appendDiagnosticLog(message: String) {
        // Log to logcat only; file writing requires MediaStore on Android 11+
        Log.i(TAG, message)
    }

    companion object
}

/** Holds server connection info for navigation purposes. */
data class ConnectionParams(
    val serverUrl: String,
    val username: String,
    val password: String,
    val serverName: String,
    val serverId: String
)
