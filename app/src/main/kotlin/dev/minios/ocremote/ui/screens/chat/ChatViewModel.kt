package dev.minios.ocremote.ui.screens.chat

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.minios.ocremote.BuildConfig
import dev.minios.ocremote.R
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.minios.ocremote.domain.model.AgentInfo
import dev.minios.ocremote.domain.model.CommandInfo
import dev.minios.ocremote.domain.model.ModelSelection
import dev.minios.ocremote.domain.model.PromptPart
import dev.minios.ocremote.domain.model.ProviderCatalog
import dev.minios.ocremote.data.repository.ServerTerminalRegistry
import dev.minios.ocremote.ui.navigation.routes.ChatNav
import dev.minios.ocremote.ui.screens.chat.tools.ToolCardResolver
import dev.minios.ocremote.domain.model.Draft
import dev.minios.ocremote.domain.repository.DraftRepository
import dev.minios.ocremote.domain.model.*
import dev.minios.ocremote.domain.repository.ChatRepository
import dev.minios.ocremote.domain.repository.SessionRepository
import dev.minios.ocremote.domain.repository.SettingsRepository
import dev.minios.ocremote.domain.tracker.TokenStatsTracker
import dev.minios.ocremote.domain.usecase.*
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
    val isSending: Boolean = false,
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
) : ViewModel() {

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
    private val serverId: String = URLDecoder.decode(
        savedStateHandle.get<String>("serverId") ?: "", "UTF-8"
    )
    private val directoryParam: String = URLDecoder.decode(
        savedStateHandle.get<String>(ChatNav.PARAM_DIRECTORY) ?: "", "UTF-8"
    )
    private val _sessionId = MutableStateFlow(URLDecoder.decode(
        savedStateHandle.get<String>("sessionId") ?: "", "UTF-8"
    ))
    val sessionId: String get() = _sessionId.value

    init {
    }

    private val _isLoading = MutableStateFlow(true)
    private val _isRefreshing = MutableStateFlow(false)  // Background refresh — no UI wipe
    private val _error = MutableStateFlow<String?>(null)
    private val _isSending = MutableStateFlow(false)
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
    val terminalEmulator: TerminalEmulator get() = terminalWorkspace.activeEmulator()

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

    // ============ Scroll Position Save/Restore ============
    /** Saved scroll position for restoring after sub-session navigation. */
    var savedMessageId by mutableStateOf<String?>(null)
        private set
    var savedScrollOffset by mutableStateOf(0)
        private set

    /**
     * Incremented each time [saveScrollPosition] is called.
     * ChatScreen observes this via LaunchedEffect to reliably restore scroll position
     * after sub-session navigation. Using rememberLazyListState(initial...) is unreliable
     * because `remember` caches the initial state on first composition and ignores
     * new values on recomposition, causing scroll to sometimes restore and sometimes not.
     */
    var scrollRestoreVersion by mutableStateOf(0)
        private set

    fun saveScrollPosition(messageId: String?, offset: Int) {
        savedMessageId = messageId
        savedScrollOffset = offset
        scrollRestoreVersion++
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
    // ============ Pagination ============
    /** Number of messages to load per page. Doubles each "load older" click. */
    private var currentMessageLimit = 20
    /** Whether there are more messages on the server beyond the current limit. */
    private val _hasOlderMessages = MutableStateFlow(false)
    /** Whether a "load older" request is in flight. */
    private val _isLoadingOlder = MutableStateFlow(false)

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
     * Message list state — changes on every message/part/update.
     * Independent from other combines to limit recomposition scope.
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

            val session = allSessions.find { it.id == sid }
            val revertState = session?.revert

            val chatMessages = if (loading && sessionMessages.isEmpty()) {
                emptyList()
            } else {
                val sorted = sessionMessages.sortedBy { it.time.created }
                val visible = if (revertState != null) {
                    sorted.filter { it.id < revertState.messageId }
                } else {
                    sorted
                }
                visible.map { msg ->
                    ChatMessage(
                        message = msg,
                        parts = allParts[msg.id] ?: emptyList()
                    )
                }
            }

            val pendingAssistantIndex = chatMessages.indexOfLast {
                it.message is Message.Assistant && it.message.time.completed == null
            }
            val queuedMessageIds = if (pendingAssistantIndex >= 0) {
                chatMessages.drop(pendingAssistantIndex + 1)
                    .filter { it.isUser }
                    .map { it.message.id }
                    .toSet()
            } else {
                emptySet<String>()
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
     */
    val sessionMetaState: StateFlow<SessionMetaState> = combine(
        _sessionId,
        sessionRepository.getSessionsFlow(serverId),
        sessionRepository.getSessionStatusesFlow(serverId),
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

        SessionMetaState(
            sessionTitle = session?.title ?: "",
            serverName = serverName,
            sessionStatus = statuses[sid] ?: SessionStatus.Idle,
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
     * Interaction state — loading, sending, error, and pending permission/question cards.
     * Includes [_sessionId] as a source so lazy-session creation triggers immediate recomputation.
     */
    val interactionState: StateFlow<InteractionState> = combine(
        _sessionId,
        _isLoading,
        _error,
        _isSending,
        sessionRepository.getSessionsFlow(serverId),
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
            isSending = inter.isSending,
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

        // Observe messages and update token stats tracker (computed outside combine for performance)
        viewModelScope.launch {
            _sessionId.flatMapLatest { sid -> messagePaging.observeMessages(sid) }
                .collect { messages ->
                val assistantMessages = messages.filterIsInstance<Message.Assistant>()
                val totalCost = assistantMessages.sumOf { it.cost ?: 0.0 }
                val totalInputTokens = assistantMessages.sumOf { it.tokens?.input ?: 0 }
                val totalOutputTokens = assistantMessages.sumOf { it.tokens?.output ?: 0 }
                val totalReasoningTokens = assistantMessages.sumOf { it.tokens?.reasoning ?: 0 }
                val totalCacheReadTokens = assistantMessages.sumOf { it.tokens?.cache?.read ?: 0 }
                val totalCacheWriteTokens = assistantMessages.sumOf { it.tokens?.cache?.write ?: 0 }
                val lastAssistantWithTokens = assistantMessages.lastOrNull { (it.tokens?.output ?: 0) > 0 }
                val lastContextTokens = lastAssistantWithTokens?.tokens?.let { t ->
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
                        lastContextTokens = lastContextTokens
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
                currentMessageLimit = settingsRepository.getSettingsFlow().map { it.initialMessageCount }.first()
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
            _isLoading.value = false
            if (!sessionLoaded.isCompleted) {
                sessionLoaded.complete(Unit)
            }
        }
        loadProviders()
        loadAgents()
        loadCommands()
    }

    /** Load the session info to get its directory for correct project context. */
    private suspend fun loadSession() {
        try {
            val session = manageSessionUseCase.getSession(serverId, sessionId)
            if (session.directory.isNotBlank()) {
                sessionDirectory = session.directory
                if (BuildConfig.DEBUG) Log.d(TAG, "Session directory: ${session.directory}")
            }
            // Inject the REST-loaded session into the reducer so that title, parentId,
            // cost, tokens etc. are immediately available even before SSE delivers them.
            // This is critical for sub-sessions which may not be in the SSE session list.
            sessionRepository.setSessions(serverId, listOf(session))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load session info", e)
        } finally {
            if (!sessionLoaded.isCompleted) {
                sessionLoaded.complete(Unit)
            }
        }
    }

    fun loadMessages() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                // Load messages with current limit
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
     * Refresh session data when returning from background (lock screen / app switch).
     * Uses [_isRefreshing] instead of [_isLoading] to avoid clearing the message list.
     */
    fun refreshSession() {
        viewModelScope.launch {
            loadSession()
            refreshMessages()
            loadPendingQuestions()
            loadPendingPermissions()
        }
    }

    /**
     * Refresh messages without triggering the loading-UI state.
     * Unlike [loadMessages], this does NOT set [_isLoading] to true,
     * so [messageListState] won't clear existing messages during the refresh.
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
                }
            }
        }
    }

    /**
     * Load older messages by doubling the current limit.
     */
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
        val pendingId = "pending-${java.util.UUID.randomUUID()}"
        _pendingMessageIds.update { it + pendingId }
        viewModelScope.launch {
            _isSending.value = true
            try {
                val currentSessionId = ensureSession()
                val model = if (_selectedProviderId.value != null && _selectedModelId.value != null) {
                    ModelSelection(
                        providerId = _selectedProviderId.value!!,
                        modelId = _selectedModelId.value!!
                    )
                } else null

                sendMessageUseCase.sendPrompt(
                    serverId = serverId,
                    sessionId = currentSessionId,
                    parts = parts,
                    model = model,
                    agent = modelConfigState.value.selectedAgent,
                    variant = _selectedVariant.value,
                    directory = sessionDirectory
                )
                if (BuildConfig.DEBUG) Log.d(TAG, "Sent prompt to session $currentSessionId (${parts.size} parts)")
                // Schedule a delayed session refresh to pick up the auto-generated title.
                // The server generates a title asynchronously after the first message (step===1),
                // typically within 5-15 seconds. SSE session.updated should deliver it,
                // but this REST fallback ensures the title appears even if SSE misses it.
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

    fun savePermissionRule(event: dev.minios.ocremote.domain.model.SseEvent.PermissionAsked, directory: String) {
        viewModelScope.launch {
            val rule = dev.minios.ocremote.domain.model.AutoApproveRule(
                toolName = event.permission,
                sessionId = null,
                directoryPattern = directory
            )
            chatRepository.addPermissionAutoApproveRule(rule)
        }
    }

    fun abortSession() {
        viewModelScope.launch {
            try {
                manageSessionUseCase.abortSession(serverId, sessionId, directory = sessionDirectory)
                if (BuildConfig.DEBUG) Log.d(TAG, "Aborted session $sessionId")
                // Optimistically update session status to Idle so UI reflects change immediately
                sessionRepository.updateSessionStatus(sessionId, SessionStatus.Idle)
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
                undoRedoUseCase.revertSession(serverId, sessionId, messageId)
                if (BuildConfig.DEBUG) Log.d(TAG, "Reverted session $sessionId to message $messageId")
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
                if (!sessionLoaded.isCompleted) {
                    sessionLoaded.await()
                }
                if (sessionDirectory.isNullOrBlank()) {
                    loadSession()
                }

                val normalizedCommand = command.removePrefix("/").trim()
                val effectiveDirectory = sessionDirectory
                    ?: chatRepository.getSessionsSnapshot()
                        .firstOrNull { it.id == sessionId }
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
                    sessionId = sessionId,
                    command = normalizedCommand,
                    arguments = effectiveArguments,
                    directory = effectiveDirectory,
                )
                if (BuildConfig.DEBUG) {
                    Log.d(
                        TAG,
                        "Executed command /$normalizedCommand in session $sessionId: $ok (directory=$effectiveDirectory, arguments=$effectiveArguments)"
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
                val model = if (_selectedProviderId.value != null && _selectedModelId.value != null) {
                    ModelSelection(
                        providerId = _selectedProviderId.value!!,
                        modelId = _selectedModelId.value!!
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
