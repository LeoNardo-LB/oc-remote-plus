package dev.minios.ocremote.ui.screens.chat

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.dto.response.ProvidersResponse
import dev.minios.ocremote.data.repository.EventDispatcher
import dev.minios.ocremote.data.repository.SettingsRepository
import dev.minios.ocremote.data.repository.handler.*
import dev.minios.ocremote.domain.usecase.*
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
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

    private lateinit var eventDispatcher: EventDispatcher
    private val api: OpenCodeApi = mockk(relaxed = true)
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

        every { draftUseCase.getDraft(any()) } returns null

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
            toolCardResolver = dev.minios.ocremote.ui.screens.chat.tools.DefaultToolCardResolver()
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
