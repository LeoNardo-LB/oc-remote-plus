package dev.leonardo.ocremotev2.ui.screens.chat

import android.util.Log
import dev.leonardo.ocremotev2.BuildConfig
import dev.leonardo.ocremotev2.data.repository.SessionStatusManager
import dev.leonardo.ocremotev2.domain.model.Message
import dev.leonardo.ocremotev2.domain.model.Part
import dev.leonardo.ocremotev2.domain.model.Session
import dev.leonardo.ocremotev2.domain.model.SessionStatus
import dev.leonardo.ocremotev2.domain.model.SseEvent
import dev.leonardo.ocremotev2.domain.repository.ChatRepository
import dev.leonardo.ocremotev2.domain.repository.SessionRepository
import dev.leonardo.ocremotev2.domain.repository.SettingsRepository
import dev.leonardo.ocremotev2.domain.usecase.ManagePermissionUseCase
import dev.leonardo.ocremotev2.domain.usecase.ManageSessionUseCase
import dev.leonardo.ocremotev2.domain.usecase.MessagePaginationUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "MessageDataDelegate"

/**
 * Owns message SSE observation, loading, pagination, send-state, and tool-expand
 * state previously inlined in [ChatViewModel].
 *
 * Extracted in Phase 3 Task 5 (B cluster).
 *
 * [messageListState] and [interactionState] are the two large `combine` pipelines
 * keyed by [sessionIdFlow] that this delegate owns. They were migrated wholesale
 * from ChatViewModel and must NOT be split apart.
 *
 * **Send lifecycle** is exposed as intent methods ([onSendStarted] / [onSendSuccess]
 * / [onSendError]) because [ChatViewModel.sendParts] is a coordinator that stays in
 * the ViewModel — it must not write this delegate's private state directly.
 *
 * **SSE observer management** is exposed via [cancelSseJob] / [startObservingMessages]
 * because [ChatViewModel.abortSession] / [revertMessage] need to halt and restart the
 * SSE observer while keeping the rest of their coordination logic in the ViewModel.
 *
 * NOTE: Intentionally NOT `@Singleton`/`@Inject`. It holds per-ChatViewModel runtime
 * context (the ViewModel's coroutine scope, the session-id flow from
 * [SessionLifecycleDelegate], and the session-directory provider) that Hilt cannot
 * supply. ChatViewModel constructs it directly and re-exposes every member as a
 * facade, so UI files are unchanged.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class MessageDataDelegate(
    private val manageSessionUseCase: ManageSessionUseCase,
    private val managePermissionUseCase: ManagePermissionUseCase,
    private val chatRepository: ChatRepository,
    private val messagePaging: MessagePaginationUseCase,
    private val sessionStatusManager: SessionStatusManager,
    private val sessionRepository: SessionRepository,
    private val settingsRepository: SettingsRepository,
    private val serverId: String,
    private val sessionIdFlow: StateFlow<String>,
    private val sessionDirectoryProvider: () -> String?,
    private val scope: CoroutineScope,
) {
    // ============ Loading & Error State ============
    private val _isLoading = MutableStateFlow(true)
    private val _isRefreshing = MutableStateFlow(false)  // Background refresh — no UI wipe
    private val _error = MutableStateFlow<String?>(null)
    private val _isSending = MutableStateFlow(false)

    // ============ V1 Message State ============
    private val _messagesList = MutableStateFlow<List<Message>>(emptyList())
    /** Raw (unfiltered) messages — used for hasIncompleteMessage check to avoid
     *  the window where a new assistant message has no parts yet. */
    private val _rawMessagesList = MutableStateFlow<List<Message>>(emptyList())
    private val _partsList = MutableStateFlow<List<Part>>(emptyList())
    private var sseJob: Job? = null

    // ============ Optimistic Send ============
    /** Locally-generated IDs for optimistic messages. Used to distinguish from server-confirmed. */
    private val _pendingMessageIds = MutableStateFlow<Set<String>>(emptySet())

    // ============ Tool Expand State ============
    private val _toolExpandedStates = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val toolExpandedStates: StateFlow<Map<String, Boolean>> = _toolExpandedStates

    // ============ Pagination ============
    /**
     * Number of messages to load per page. Doubles each "load older" click.
     * Refreshed from the user's initialMessageCount setting at the start of [loadMessagesForSession].
     */
    private var currentMessageLimit = 30
    /** Whether there are more messages on the server beyond the current limit. */
    private val _hasOlderMessages = MutableStateFlow(false)
    /** Whether a "load older" request is in flight. */
    private val _isLoadingOlder = MutableStateFlow(false)

    /**
     * Snapshot of the filtered message list — consumed by [ChatViewModel]'s init
     * block to feed [dev.leonardo.ocremotev2.domain.tracker.TokenStatsTracker]
     * (token aggregation is a token-cluster concern, so the tracker is NOT injected here).
     */
    val messagesList: StateFlow<List<Message>> = _messagesList

    // ============ Split State Flows ============

    /**
     * Message list state — derived from V1 chatRepository flows.
     * Combines messages, parts, and tool expand states. Keyed by [sessionIdFlow].
     */
    val messageListState: StateFlow<MessageListState> = sessionIdFlow.flatMapLatest { sid ->
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
            @Suppress("UNCHECKED_CAST")
            val sessionMessages = args[1] as List<Message>
            @Suppress("UNCHECKED_CAST")
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
        scope,
        SharingStarted.WhileSubscribed(5000),
        MessageListState()
    )

    /**
     * Interaction state — loading, sending, error derived from V1 sources,
     * pending permission/question cards from V1 chatRepository.
     */
    val interactionState: StateFlow<InteractionState> = combine(
        sessionIdFlow,
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
        scope,
        SharingStarted.WhileSubscribed(5000),
        InteractionState()
    )

    // ============ Tool Expand ============

    fun toggleToolExpanded(toolId: String, defaultExpanded: Boolean = false) {
        _toolExpandedStates.update { it + (toolId to !(it[toolId] ?: defaultExpanded)) }
    }

    fun isToolExpanded(toolId: String, autoExpand: Boolean): Boolean {
        return _toolExpandedStates.value[toolId] ?: autoExpand
    }

    // ============ Loading & Observation (called from ChatViewModel + SessionLifecycleDelegate) ============

    /**
     * Load messages via V1 API for the current session.
     * Called back from [SessionLifecycleDelegate.loadSession] (cross-cluster
     * callback) so the C-cluster delegate owns the full load orchestration
     * while this retains the MessageData-cluster concerns (pagination limit +
     * list/set).
     */
    suspend fun loadMessagesForSession() {
        // Apply user-configured initial message count as the pagination starting point
        currentMessageLimit = settingsRepository.getSettingsFlow().first().initialMessageCount
        val sid = sessionIdFlow.value
        try {
            val messages = manageSessionUseCase.listMessages(serverId, sid, limit = currentMessageLimit)
            chatRepository.setMessages(sid, messages)
            _hasOlderMessages.value = messages.size >= currentMessageLimit
            if (BuildConfig.DEBUG) Log.d(TAG, "V1 loaded ${messages.size} messages for session $sid (limit=$currentMessageLimit, hasOlder=${_hasOlderMessages.value})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load messages", e)
        }
    }

    /**
     * Observe V1 chatRepository flows (driven by SSE EventDispatcher).
     * Messages and parts are updated automatically as SSE events arrive.
     */
    fun startObservingMessages() {
        sseJob?.cancel()
        val sid = sessionIdFlow.value
        sseJob = scope.launch {
            combine(
                chatRepository.getMessagesFlow(sid),
                chatRepository.getParts(sid),
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
        val sid = sessionIdFlow.value
        scope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val messages = manageSessionUseCase.listMessages(serverId, sid, limit = currentMessageLimit)
                chatRepository.setMessages(sid, messages)
                _hasOlderMessages.value = messages.size >= currentMessageLimit

                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Loaded ${messages.size} messages for session $sid (limit=$currentMessageLimit, hasOlder=${_hasOlderMessages.value})")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to load messages", e)
                if (e is OutOfMemoryError || (e.cause is OutOfMemoryError)) {
                    Log.w(TAG, "OOM loading messages, retrying with smaller limit")
                    currentMessageLimit = (currentMessageLimit / 2).coerceAtLeast(10)
                    try {
                        val messages = manageSessionUseCase.listMessages(serverId, sid, limit = currentMessageLimit)
                        chatRepository.mergeMessages(sid, messages)
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
     * Refresh messages via V1 API.
     */
    suspend fun refreshMessages() {
        val sid = sessionIdFlow.value
        _isRefreshing.value = true
        try {
            val messages = manageSessionUseCase.listMessages(serverId, sid, limit = currentMessageLimit)
            chatRepository.setMessages(sid, messages)
            _hasOlderMessages.value = messages.size >= currentMessageLimit
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to refresh messages", e)
        } finally {
            _isRefreshing.value = false
        }
    }

    /**
     * Fix messages with time.completed == null when the server confirms the session is idle.
     * This handles the server-restart scenario: after restart, all sessions are idle in-memory,
     * but the database preserves interrupted messages with finished_at = NULL.
     * We must NOT call this during periodic polling — only on explicit user actions
     * (entering session, aborting) to avoid breaking premature-idle protection.
     */
    fun fixIncompleteMessagesIfIdle(sid: String) {
        val messages = _rawMessagesList.value
        val hasIncomplete = messages.any { it is Message.Assistant && it.time.completed == null }
        if (hasIncomplete) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Fixing incomplete messages for session $sid (server confirmed idle)")
            sessionRepository.markSessionIdle(sid)
        }
    }

    fun loadOlderMessages() {
        val sid = sessionIdFlow.value
        scope.launch {
            _isLoadingOlder.value = true
            currentMessageLimit = currentMessageLimit * 2
            try {
                val messages = manageSessionUseCase.listMessages(serverId, sid, limit = currentMessageLimit)
                chatRepository.mergeMessages(sid, messages)
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
    suspend fun loadPendingQuestions() {
        val sid = sessionIdFlow.value
        val directory = sessionDirectoryProvider()
        try {
            val allQuestions = managePermissionUseCase.listPendingQuestions(serverId, directory = directory)
            if (BuildConfig.DEBUG) Log.d(TAG, "loadPendingQuestions: ${allQuestions.size} total pending (directory=$directory), filtering for session $sid")

            // Include questions from child sessions
            val childSessionIds = chatRepository.getSessionsSnapshot()
                .filter { it.parentId == sid }
                .map { it.id }
                .toSet()

            val sessionQuestions = allQuestions
                .filter { it.sessionId == sid || it.sessionId in childSessionIds }
                .map { req ->
                    val isChild = req.sessionId != sid
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
                val existingSseQs = chatRepository.getQuestionsSnapshot()[sid] ?: emptyList()
                val existingIds = existingSseQs.map { it.id }.toSet()
                val newQs = sessionQuestions.filter { it.id !in existingIds }
                if (newQs.isNotEmpty()) {
                    chatRepository.setQuestions(sid, existingSseQs + newQs)
                    if (BuildConfig.DEBUG) Log.d(TAG, "Merged ${newQs.size} new + ${existingSseQs.size} existing questions for session $sid")
                } else {
                    if (BuildConfig.DEBUG) Log.d(TAG, "All ${sessionQuestions.size} REST questions already present via SSE for session $sid")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load pending questions: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    /** Load pending permissions from the server REST API on session open (REST recovery). */
    suspend fun loadPendingPermissions() {
        val sid = sessionIdFlow.value
        val directory = sessionDirectoryProvider()
        try {
            val allPermissions = managePermissionUseCase.listPendingPermissions(serverId, directory = directory)
            if (BuildConfig.DEBUG) Log.d(TAG, "loadPendingPermissions: ${allPermissions.size} total pending (directory=$directory), filtering for session $sid")

            // Include permissions from child sessions
            val childSessionIds = chatRepository.getSessionsSnapshot()
                .filter { it.parentId == sid }
                .map { it.id }
                .toSet()

            val sessionPermissions = allPermissions
                .filter { it.sessionId == sid || it.sessionId in childSessionIds }
                .map { req ->
                    val isChild = req.sessionId != sid
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

    // ============ Send Lifecycle (intent methods for ChatViewModel.sendParts) ============

    /**
     * Mark the start of an optimistic send: flip [_isSending] and register the
     * [pendingId] so the UI can render the pending message bubble.
     */
    fun onSendStarted(pendingId: String) {
        _isSending.value = true
        _pendingMessageIds.update { it + pendingId }
    }

    /** Mark a successful send: clear [_isSending] and deregister the [pendingId]. */
    fun onSendSuccess(pendingId: String) {
        _isSending.value = false
        _pendingMessageIds.update { it - pendingId }
    }

    /**
     * Mark a failed send: clear [_isSending], set [_error], and deregister the
     * [pendingId].
     */
    fun onSendError(message: String, pendingId: String) {
        _isSending.value = false
        _error.value = message
        _pendingMessageIds.update { it - pendingId }
    }

    // ============ SSE Observer Management (for ChatViewModel.abort/revert) ============

    /** Cancel the in-flight SSE observer job, if any. */
    fun cancelSseJob() {
        sseJob?.cancel()
        sseJob = null
    }

    // ============ New-Session Loading Marker ============

    /**
     * Mark loading as complete for a brand-new session that has no messages to
     * load. Called from [ChatViewModel]'s init block for the new-session branch.
     */
    fun markLoaded() {
        _isLoading.value = false
    }
}
