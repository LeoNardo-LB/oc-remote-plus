package dev.minios.ocremote.ui.screens.chat

import android.util.Log
import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.dto.response.ProvidersResponse
import dev.minios.ocremote.data.repository.EventDispatcher
import dev.minios.ocremote.data.repository.PermissionAutoApprover
import dev.minios.ocremote.data.repository.handler.*
import dev.minios.ocremote.data.repository.SettingsRepository
import dev.minios.ocremote.domain.model.*
import dev.minios.ocremote.domain.usecase.*
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import androidx.lifecycle.SavedStateHandle

/**
 * Comprehensive tests for 4 features:
 * A. QUEUED badge — queuedMessageIds computation
 * B. Sub-session identification — sessionParentId
 * C. subSessionId extraction logic from tool metadata
 * D. Part.Agent source extraction logic
 * E. Integration scenarios combining multiple features
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelQueuedTest {

    // === Mock and infrastructure ===

    private lateinit var eventDispatcher: EventDispatcher
    private val api: OpenCodeApi = mockk(relaxed = true)
    private val settingsRepository: SettingsRepository = mockk()
    private val testDispatcher = UnconfinedTestDispatcher()

    // UseCase mocks
    private val sendMessageUseCase: SendMessageUseCase = mockk(relaxed = true)
    private val manageSessionUseCase: ManageSessionUseCase = mockk(relaxed = true)
    private val managePermissionUseCase: ManagePermissionUseCase = mockk(relaxed = true)
    private val selectModelUseCase: SelectModelUseCase = mockk(relaxed = true)
    private val manageAgentUseCase: ManageAgentUseCase = mockk(relaxed = true)
    private val manageTerminalUseCase: ManageTerminalUseCase = mockk(relaxed = true)
    private val draftUseCase: DraftUseCase = mockk(relaxed = true)
    private val shareExportUseCase: ShareExportUseCase = mockk(relaxed = true)
    private val undoRedoUseCase: UndoRedoUseCase = mockk(relaxed = true)

    private val testSessionId = "test-session-1"
    private val testServerId = "test-server-1"
    private val testDirectory = "/home/test"

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        eventDispatcher = EventDispatcher(
            sessionHandler = SessionEventHandler(),
            messageHandler = MessageEventHandler(),
            permissionHandler = PermissionEventHandler(),
            questionHandler = QuestionEventHandler(),
            miscHandler = MiscEventHandler(),
            sessionNextHandler = SessionNextEventHandler()
        )

        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0

        // Draft stubs
        every { draftUseCase.getDraft(any()) } returns null

        // Settings stubs (must stub all Flow properties)
        every { settingsRepository.hiddenModels(any()) } returns flowOf(emptySet())
        every { settingsRepository.terminalFontSize } returns flowOf(13f)
        every { settingsRepository.initialMessageCount } returns flowOf(50)
        every { settingsRepository.chatFontSize } returns flowOf("medium")
        every { settingsRepository.codeWordWrap } returns flowOf(false)
        every { settingsRepository.confirmBeforeSend } returns flowOf(false)
        every { settingsRepository.compactMessages } returns flowOf(false)
        every { settingsRepository.collapseTools } returns flowOf(false)
        every { settingsRepository.expandReasoning } returns flowOf(false)
        every { settingsRepository.hapticFeedback } returns flowOf(true)
        every { settingsRepository.keepScreenOn } returns flowOf(false)
        every { settingsRepository.compressImageAttachments } returns flowOf(true)
        every { settingsRepository.imageAttachmentMaxLongSide } returns flowOf(1440)
        every { settingsRepository.imageAttachmentWebpQuality } returns flowOf(60)

        // UseCase stubs — defaults
        coEvery { manageSessionUseCase.getSession(any(), any()) } returns createTestSession()
        coEvery { manageSessionUseCase.listMessages(any(), any(), any()) } returns emptyList()
        coEvery { managePermissionUseCase.listPendingQuestions(any(), any()) } returns emptyList()
        coEvery { managePermissionUseCase.listPendingPermissions(any(), any()) } returns emptyList()
        coEvery { selectModelUseCase.loadProviders(any()) } returns ProvidersResponse(emptyList())
        coEvery { manageAgentUseCase.loadAgents(any()) } returns emptyList()
        coEvery { manageAgentUseCase.loadCommands(any()) } returns emptyList()
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    // === Helper methods ===

    private fun createTestSession(
        id: String = testSessionId,
        parentId: String? = null
    ): Session = Session(
        id = id,
        title = "Test Session",
        directory = testDirectory,
        parentId = parentId,
        time = Session.Time(created = 1000L, updated = 2000L)
    )

    private fun createUserMessage(
        id: String,
        sessionId: String = testSessionId,
        created: Long = System.currentTimeMillis()
    ): Message.User = Message.User(
        id = id,
        sessionId = sessionId,
        time = TimeInfo(created = created)
    )

    private fun createAssistantMessage(
        id: String,
        sessionId: String = testSessionId,
        completed: Long? = null,
        created: Long = System.currentTimeMillis()
    ): Message.Assistant = Message.Assistant(
        id = id,
        sessionId = sessionId,
        time = TimeInfo(created = created, completed = completed),
        parentId = ""
    )

    private fun createToolPart(
        id: String = "tool-1",
        toolName: String = "task",
        state: ToolState = ToolState.Running(),
        metadata: Map<String, JsonElement>? = null
    ): Part.Tool = Part.Tool(
        id = id,
        sessionId = testSessionId,
        messageId = "msg-2",
        callId = "call-$id",
        tool = toolName,
        state = state,
        metadata = metadata
    )

    private fun createViewModel(
        sessionId: String = testSessionId
    ): ChatViewModel {
        val savedState = SavedStateHandle(mapOf(
            "serverUrl"  to "http://localhost:8080",
            "username"   to "testuser",
            "password"   to "testpass",
            "serverName" to "TestServer",
            "serverId"   to testServerId,
            "sessionId"  to sessionId
        ))
        return ChatViewModel(
            savedStateHandle = savedState,
            eventDispatcher = eventDispatcher,
            sendMessageUseCase = sendMessageUseCase,
            manageSessionUseCase = manageSessionUseCase,
            managePermissionUseCase = managePermissionUseCase,
            selectModelUseCase = selectModelUseCase,
            manageAgentUseCase = manageAgentUseCase,
            manageTerminalUseCase = manageTerminalUseCase,
            draftUseCase = draftUseCase,
            shareExportUseCase = shareExportUseCase,
            undoRedoUseCase = undoRedoUseCase,
            settingsRepository = settingsRepository,
            api = api,
            permissionAutoApprover = mockk<PermissionAutoApprover>(relaxed = true),
            toolCardResolver = dev.minios.ocremote.ui.screens.chat.tools.DefaultToolCardResolver()
        )
    }

    /**
     * Push messages into EventDispatcher after VM is created.
     * This simulates SSE updates arriving after initial load.
     */
    private fun pushMessages(messages: List<Pair<Message, List<Part>>>) {
        val messageWithParts = messages.map { (msg, parts) ->
            MessageWithParts(info = msg, parts = parts)
        }
        eventDispatcher.setMessages(testSessionId, messageWithParts)
    }

    /** Set session into EventDispatcher. */
    private fun setSession(session: Session) {
        eventDispatcher.setSessions(testServerId, listOf(session))
    }

    /**
     * Configure manageSessionUseCase.listMessages to return the given messages as MessageWithParts,
     * so that the VM's init loadMessages() will populate them into EventDispatcher.
     */
    private fun stubMessages(vararg messages: Pair<Message, List<Part>>) {
        val messageWithParts = messages.map { (msg, parts) ->
            MessageWithParts(info = msg, parts = parts)
        }
        coEvery { manageSessionUseCase.listMessages(any(), any(), any()) } returns messageWithParts
    }

    /**
     * Subscribe to uiState to activate the SharingStarted.WhileSubscribed upstream.
     * Without a subscriber, uiState.value returns the initial ChatUiState().
     */
    private fun kotlinx.coroutines.test.TestScope.subscribeToState(vm: ChatViewModel): Job {
        return backgroundScope.launch {
            vm.uiState.collect { /* keep subscription alive */ }
        }
    }

    // ==========================================
    // A. QUEUED badge — queuedMessageIds computation
    // ==========================================

    @Test
    fun queuedMessageIds_empty_whenNoMessages() = runTest {
        val vm = createViewModel()
        val collectJob = subscribeToState(vm)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.queuedMessageIds.isEmpty())
        collectJob.cancel()
    }

    @Test
    fun queuedMessageIds_empty_whenNoPendingAssistant() = runTest {
        // All assistant messages are completed
        stubMessages(
            createUserMessage("u1", created = 1000L) to emptyList(),
            createAssistantMessage("a1", completed = 2000L, created = 1500L) to emptyList(),
            createUserMessage("u2", created = 3000L) to emptyList(),
        )

        val vm = createViewModel()
        val collectJob = subscribeToState(vm)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.queuedMessageIds.isEmpty())
        collectJob.cancel()
    }

    @Test
    fun queuedMessageIds_containsUserMessages_afterPendingAssistant() = runTest {
        // Assistant not completed — user messages after it should be marked
        stubMessages(
            createUserMessage("u1", created = 1000L) to emptyList(),
            createAssistantMessage("a1", completed = null, created = 1500L) to emptyList(),
            createUserMessage("u2", created = 2000L) to emptyList(),
            createUserMessage("u3", created = 2500L) to emptyList(),
        )

        val vm = createViewModel()
        val collectJob = subscribeToState(vm)
        advanceUntilIdle()

        assertEquals(setOf("u2", "u3"), vm.uiState.value.queuedMessageIds)
        collectJob.cancel()
    }

    @Test
    fun queuedMessageIds_excludesMessages_beforePendingAssistant() = runTest {
        // u1 is before pending assistant, should NOT be marked
        stubMessages(
            createUserMessage("u1", created = 1000L) to emptyList(),
            createUserMessage("u2", created = 1200L) to emptyList(),
            createAssistantMessage("a1", completed = null, created = 1500L) to emptyList(),
            createUserMessage("u3", created = 2000L) to emptyList(),
        )

        val vm = createViewModel()
        val collectJob = subscribeToState(vm)
        advanceUntilIdle()

        assertEquals(setOf("u3"), vm.uiState.value.queuedMessageIds)
        collectJob.cancel()
    }

    @Test
    fun queuedMessageIds_empty_whenNoUserAfterPendingAssistant() = runTest {
        // Pending assistant but no user messages after it
        stubMessages(
            createUserMessage("u1", created = 1000L) to emptyList(),
            createAssistantMessage("a1", completed = null, created = 1500L) to emptyList(),
        )

        val vm = createViewModel()
        val collectJob = subscribeToState(vm)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.queuedMessageIds.isEmpty())
        collectJob.cancel()
    }

    @Test
    fun queuedMessageIds_usesLastPendingAssistant() = runTest {
        // indexOfLast finds the LAST pending assistant = a2 (index 2)
        // Only user messages after a2 are collected
        stubMessages(
            createAssistantMessage("a1", completed = null, created = 1500L) to emptyList(),
            createUserMessage("u1", created = 2000L) to emptyList(),
            createAssistantMessage("a2", completed = null, created = 2500L) to emptyList(),
            createUserMessage("u2", created = 3000L) to emptyList(),
        )

        val vm = createViewModel()
        val collectJob = subscribeToState(vm)
        advanceUntilIdle()

        assertEquals(setOf("u2"), vm.uiState.value.queuedMessageIds)
        collectJob.cancel()
    }

    @Test
    fun queuedMessageIds_cleared_whenAssistantCompletes() = runTest {
        // Initial load: assistant pending
        stubMessages(
            createAssistantMessage("a1", completed = null, created = 1500L) to emptyList(),
            createUserMessage("u1", created = 2000L) to emptyList(),
        )

        val vm = createViewModel()
        val collectJob = subscribeToState(vm)
        advanceUntilIdle()

        // Verify queued initially
        assertEquals(setOf("u1"), vm.uiState.value.queuedMessageIds)

        // Simulate SSE update: assistant completes
        pushMessages(listOf(
            createAssistantMessage("a1", completed = 3000L, created = 1500L) to emptyList(),
            createUserMessage("u1", created = 2000L) to emptyList(),
        ))
        advanceUntilIdle()

        // Queued should be cleared
        assertTrue(vm.uiState.value.queuedMessageIds.isEmpty())
        collectJob.cancel()
    }

    // ==========================================
    // B. Sub-session identification — sessionParentId
    // ==========================================

    @Test
    fun sessionParentId_null_whenSessionHasNoParent() = runTest {
        coEvery { manageSessionUseCase.getSession(any(), any()) } returns createTestSession(parentId = null)
        setSession(createTestSession(parentId = null))

        val vm = createViewModel()
        val collectJob = subscribeToState(vm)
        advanceUntilIdle()

        assertNull(vm.uiState.value.sessionParentId)
        collectJob.cancel()
    }

    @Test
    fun sessionParentId_set_whenSessionHasParent() = runTest {
        coEvery { manageSessionUseCase.getSession(any(), any()) } returns createTestSession(parentId = "parent-session-1")
        setSession(createTestSession(parentId = "parent-session-1"))

        val vm = createViewModel()
        val collectJob = subscribeToState(vm)
        advanceUntilIdle()

        assertEquals("parent-session-1", vm.uiState.value.sessionParentId)
        collectJob.cancel()
    }

    // ==========================================
    // C. subSessionId extraction logic
    // ==========================================

    /**
     * Extract subSessionId from a completed tool's metadata.
     * Mirrors the logic used in ChatScreen for sub-agent navigation.
     */
    private fun extractSubSessionId(tool: Part.Tool): String? {
        val state = tool.state
        if (state !is ToolState.Completed) return null
        val metadata = state.metadata ?: return null
        val element = metadata["sessionId"] ?: return null
        val value = runCatching { element.jsonPrimitive.contentOrNull }.getOrNull()
        return value?.takeIf { it.isNotBlank() }
    }

    @Test
    fun subSessionId_null_whenStateIsRunning() {
        val tool = createToolPart(state = ToolState.Running())
        assertNull(extractSubSessionId(tool))
    }

    @Test
    fun subSessionId_null_whenNoMetadata() {
        val tool = createToolPart(
            state = ToolState.Completed(output = "done", metadata = null)
        )
        assertNull(extractSubSessionId(tool))
    }

    @Test
    fun subSessionId_null_whenNoSessionIdInMetadata() {
        val tool = createToolPart(
            state = ToolState.Completed(
                output = "done",
                metadata = mapOf("otherKey" to JsonPrimitive("value"))
            )
        )
        assertNull(extractSubSessionId(tool))
    }

    @Test
    fun subSessionId_returnsValue_whenPresent() {
        val tool = createToolPart(
            state = ToolState.Completed(
                output = "done",
                metadata = mapOf("sessionId" to JsonPrimitive("child-session-1"))
            )
        )
        assertEquals("child-session-1", extractSubSessionId(tool))
    }

    @Test
    fun subSessionId_null_whenBlankValue() {
        val tool = createToolPart(
            state = ToolState.Completed(
                output = "done",
                metadata = mapOf("sessionId" to JsonPrimitive(""))
            )
        )
        assertNull(extractSubSessionId(tool))
    }

    @Test
    fun subSessionId_null_whenValueIsNotPrimitive() {
        val tool = createToolPart(
            state = ToolState.Completed(
                output = "done",
                metadata = mapOf("sessionId" to buildJsonObject { put("nested", JsonPrimitive("value")) })
            )
        )
        // jsonPrimitive would throw, runCatching catches it
        assertNull(extractSubSessionId(tool))
    }

    // ==========================================
    // D. Part.Agent source extraction logic
    // ==========================================

    /**
     * Extract the source string from a Part.Agent's source JsonElement.
     * Mirrors the logic used in ChatScreen for agent part rendering.
     */
    private fun extractAgentSource(source: JsonElement?): String {
        return runCatching { source?.jsonPrimitive?.contentOrNull }.getOrNull() ?: ""
    }

    @Test
    fun agentSource_empty_whenNull() {
        assertEquals("", extractAgentSource(null))
    }

    @Test
    fun agentSource_returnsValue_whenPrimitive() {
        assertEquals("mcp-server", extractAgentSource(JsonPrimitive("mcp-server")))
    }

    @Test
    fun agentSource_empty_whenNotPrimitive() {
        assertEquals("", extractAgentSource(buildJsonObject { put("key", JsonPrimitive("val")) }))
    }

    // ==========================================
    // E. Integration scenarios — multi-feature verification
    // ==========================================

    @Test
    fun queuedAndParentId_workTogether_inSubSession() = runTest {
        // Sub-session scenario: session has parentId, pending assistant + queued messages
        val session = createTestSession(parentId = "parent-1")
        coEvery { manageSessionUseCase.getSession(any(), any()) } returns session
        setSession(session)

        stubMessages(
            createUserMessage("u1", created = 1000L) to emptyList(),
            createAssistantMessage("a1", completed = null, created = 1500L) to listOf(
                createToolPart(
                    id = "tool-1",
                    state = ToolState.Completed(
                        output = "Task completed",
                        metadata = mapOf("sessionId" to JsonPrimitive("grandchild-1"))
                    )
                )
            ),
            createUserMessage("u2", created = 2000L) to emptyList(),
        )

        val vm = createViewModel()
        val collectJob = subscribeToState(vm)
        advanceUntilIdle()

        val state = vm.uiState.value

        // sessionParentId correct
        assertEquals("parent-1", state.sessionParentId)

        // queuedMessageIds correct (u2 after pending assistant)
        assertEquals(setOf("u2"), state.queuedMessageIds)

        // Messages not empty
        assertTrue(state.messages.isNotEmpty())
        collectJob.cancel()
    }

    @Test
    fun queuedMessageIds_withMultipleRapidUserMessages() = runTest {
        // Simulate user rapidly sending 3 messages
        stubMessages(
            createAssistantMessage("a1", completed = null, created = 1500L) to emptyList(),
            createUserMessage("u1", created = 2000L) to emptyList(),
            createUserMessage("u2", created = 2100L) to emptyList(),
            createUserMessage("u3", created = 2200L) to emptyList(),
        )

        val vm = createViewModel()
        val collectJob = subscribeToState(vm)
        advanceUntilIdle()

        assertEquals(setOf("u1", "u2", "u3"), vm.uiState.value.queuedMessageIds)
        collectJob.cancel()
    }
}
