package dev.minios.ocremote.ui.screens.chat

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import dev.minios.ocremote.data.repository.ServerTerminalRegistry
import io.ktor.client.HttpClient
import dev.minios.ocremote.data.api.SseClient
import dev.minios.ocremote.domain.model.AppSettings
import dev.minios.ocremote.domain.model.ProvidersResponse
import dev.minios.ocremote.domain.repository.ChatRepository
import dev.minios.ocremote.domain.repository.SessionRepository
import dev.minios.ocremote.domain.repository.SettingsRepository
import dev.minios.ocremote.domain.usecase.*
import dev.minios.ocremote.domain.tracker.TokenStatsTracker
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelSendTest {

    private val terminalRegistry: ServerTerminalRegistry = mockk(relaxed = true)
    private val settingsRepository: SettingsRepository = mockk()
    private val testDispatcher = UnconfinedTestDispatcher()

    private val sendMessageUseCase: SendMessageUseCase = mockk()
    private val manageSessionUseCase: ManageSessionUseCase = mockk(relaxed = true)
    private val managePermissionUseCase: ManagePermissionUseCase = mockk(relaxed = true)
    private val selectModelUseCase: SelectModelUseCase = mockk(relaxed = true)
    private val manageAgentUseCase: ManageAgentUseCase = mockk(relaxed = true)
    private val manageTerminalUseCase: ManageTerminalUseCase = mockk(relaxed = true)
    private val draftUseCase: DraftUseCase = mockk(relaxed = true)
    private val shareExportUseCase: ShareExportUseCase = mockk(relaxed = true)
    private val undoRedoUseCase: UndoRedoUseCase = mockk(relaxed = true)
    private val messagePaging: MessagePaginationUseCase = mockk(relaxed = true)
    private val tokenStatsTracker = TokenStatsTracker()

    @After
    fun tearDown() {
    }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0

        every { draftUseCase.getDraft(any()) } returns null

        every { settingsRepository.hiddenModels(any()) } returns flowOf(emptySet())
        every { settingsRepository.getSettingsFlow() } returns flowOf(
            AppSettings(
                terminalFontSize = 13f,
                initialMessageCount = 50,
                chatFontSize = "medium",
                codeWordWrap = false,
                confirmBeforeSend = false,
                compactMessages = false,
                collapseTools = false,
                expandReasoning = false,
                hapticFeedback = true,
                keepScreenOn = false,
                compressImageAttachments = true,
                imageAttachmentMaxLongSide = 1440,
                imageAttachmentWebpQuality = 60,
            )
        )

        coEvery { manageSessionUseCase.getSession(any(), any()) } returns createTestSession()
        coEvery { manageSessionUseCase.listMessages(any(), any(), any()) } returns emptyList()
        coEvery { managePermissionUseCase.listPendingQuestions(any(), any()) } returns emptyList()
        coEvery { managePermissionUseCase.listPendingPermissions(any(), any()) } returns emptyList()
        coEvery { selectModelUseCase.loadProviders(any()) } returns ProvidersResponse(emptyList())
        coEvery { manageAgentUseCase.loadAgents(any()) } returns emptyList()
        coEvery { manageAgentUseCase.loadCommands(any()) } returns emptyList()

        // Wire messagePaging.observeMessages to return empty messages
        every { messagePaging.observeMessages(any()) } returns flowOf(emptyList())
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createTestSession() = dev.minios.ocremote.domain.model.Session(
        id = "test-session",
        title = "Test Session",
        directory = "/test",
        time = dev.minios.ocremote.domain.model.Session.Time(created = 1000L, updated = 2000L)
    )

    private fun createViewModel(): ChatViewModel {
        val savedState = SavedStateHandle(mapOf(
            "serverUrl"  to "http://localhost:8080",
            "username"   to "testuser",
            "password"   to "testpass",
            "serverName" to "TestServer",
            "serverId"   to "test-server",
            "sessionId"  to "test-session"
        ))
        return ChatViewModel(
            savedStateHandle = savedState,
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
            terminalRegistry = terminalRegistry,
            toolCardResolver = dev.minios.ocremote.ui.screens.chat.tools.DefaultToolCardResolver(),
            chatRepository = mockk<ChatRepository>(relaxed = true).also {
                every { it.getAllPartsMap() } returns kotlinx.coroutines.flow.MutableStateFlow(emptyMap<String, List<dev.minios.ocremote.domain.model.Part>>())
            },
            sessionRepository = mockk<SessionRepository>(relaxed = true).also {
                every { it.getSessionsFlow(any()) } returns flowOf(emptyList())
                every { it.getSessionStatusesFlow(any()) } returns flowOf(emptyMap())
                every { it.getCurrentAgentFlow(any()) } returns flowOf(emptyMap())
                every { it.getCurrentModelFlow(any()) } returns flowOf(emptyMap())
            },
            messagePaging = messagePaging,
            tokenStatsTracker = tokenStatsTracker,
            httpClient = mockk(relaxed = true),
            sseClient = mockk(relaxed = true)
        )
    }

    /**
     * uiState is backed by stateIn which needs an active subscriber to emit updates.
     * Without a subscriber, uiState.value returns the initial ChatUiState().
     */
    private fun kotlinx.coroutines.test.TestScope.subscribeToState(vm: ChatViewModel): Job {
        return backgroundScope.launch {
            vm.uiState.collect { /* keep subscription alive */ }
        }
    }

    @Test
    fun `optimistic message appears in state before API returns`() = runTest {
        // Given: sendMessageUseCase will suspend indefinitely
        coEvery { sendMessageUseCase.sendPrompt(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            delay(10_000)
        }

        val viewModel = createViewModel()
        val collectJob = subscribeToState(viewModel)
        advanceUntilIdle()

        // When
        viewModel.sendMessage("Hello world")
        advanceUntilIdle()

        // Then: pending message should appear in state
        val state = viewModel.uiState.value
        assertTrue(
            "Pending message should be in state, got: ${state.pendingMessageIds}",
            state.pendingMessageIds.isNotEmpty()
        )
        collectJob.cancel()
    }

    @Test
    fun `optimistic message removed on failure`() = runTest {
        coEvery { sendMessageUseCase.sendPrompt(any(), any(), any(), any(), any(), any(), any()) } throws
            java.io.IOException("Network error")

        val viewModel = createViewModel()
        val collectJob = subscribeToState(viewModel)
        advanceUntilIdle()

        viewModel.sendMessage("Hello world")
        advanceUntilIdle()

        // Pending should be cleared after failure
        val state = viewModel.uiState.value
        assertTrue(
            "Pending message should be removed on failure, got: ${state.pendingMessageIds}",
            state.pendingMessageIds.isEmpty()
        )
        collectJob.cancel()
    }

    @Test
    fun `restoredDraft is set on send failure`() = runTest {
        coEvery { sendMessageUseCase.sendPrompt(any(), any(), any(), any(), any(), any(), any()) } throws
            java.io.IOException("Network error")

        val viewModel = createViewModel()
        val collectJob = subscribeToState(viewModel)
        advanceUntilIdle()

        viewModel.sendMessage("Hello world")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNotNull(
            "restoredDraft should be set on failure",
            state.restoredDraft
        )
        assertEquals("Hello world", state.restoredDraft?.text)
        collectJob.cancel()
    }

    @Test
    fun `consumeRestoredDraft clears the value`() = runTest {
        coEvery { sendMessageUseCase.sendPrompt(any(), any(), any(), any(), any(), any(), any()) } throws
            java.io.IOException("Network error")

        val viewModel = createViewModel()
        val collectJob = subscribeToState(viewModel)
        advanceUntilIdle()

        viewModel.sendMessage("Hello world")
        advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.restoredDraft)

        viewModel.consumeRestoredDraft()
        advanceUntilIdle()

        assertNull(
            "restoredDraft should be null after consume",
            viewModel.uiState.value.restoredDraft
        )
        collectJob.cancel()
    }
}
