package dev.minios.ocremote.ui.screens.chat

import android.util.Log
import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.dto.response.ProvidersResponse
import dev.minios.ocremote.data.repository.DraftRepository
import dev.minios.ocremote.data.repository.EventDispatcher
import dev.minios.ocremote.data.repository.SettingsRepository
import dev.minios.ocremote.data.repository.handler.*
import dev.minios.ocremote.domain.model.MessageWithParts
import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.domain.usecase.*
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import androidx.lifecycle.SavedStateHandle
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelDeleteTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var eventDispatcher: EventDispatcher
    private lateinit var api: OpenCodeApi
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var sendMessageUseCase: SendMessageUseCase
    private lateinit var manageSessionUseCase: ManageSessionUseCase
    private lateinit var managePermissionUseCase: ManagePermissionUseCase
    private lateinit var selectModelUseCase: SelectModelUseCase
    private lateinit var manageAgentUseCase: ManageAgentUseCase
    private lateinit var manageTerminalUseCase: ManageTerminalUseCase
    private lateinit var draftUseCase: DraftUseCase
    private lateinit var shareExportUseCase: ShareExportUseCase
    private lateinit var undoRedoUseCase: UndoRedoUseCase

    private val testSessionId = "session-123"
    private val testServerId = "server-1"

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

        api = mockk(relaxed = true)
        settingsRepository = mockk()

        sendMessageUseCase = mockk(relaxed = true)
        manageSessionUseCase = mockk(relaxed = true)
        managePermissionUseCase = mockk(relaxed = true)
        selectModelUseCase = mockk(relaxed = true)
        manageAgentUseCase = mockk(relaxed = true)
        manageTerminalUseCase = mockk(relaxed = true)
        draftUseCase = mockk(relaxed = true)
        shareExportUseCase = mockk(relaxed = true)
        undoRedoUseCase = mockk(relaxed = true)

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
        coEvery { selectModelUseCase.loadProviders(any()) } returns ProvidersResponse(emptyList())
        coEvery { manageAgentUseCase.loadAgents(any()) } returns emptyList()
        coEvery { manageAgentUseCase.loadCommands(any()) } returns emptyList()
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `deleteMessage calls api and returns true on success`() = runTest {
        coEvery { manageSessionUseCase.deleteMessage(any(), testSessionId, "m1") } returns true

        val vm = createViewModel()
        var result = false
        vm.deleteMessage("m1") { result = it }

        assertTrue(result)
    }

    @Test
    fun `deleteMessage returns false on failure`() = runTest {
        coEvery { manageSessionUseCase.deleteMessage(any(), testSessionId, "m1") } returns false

        val vm = createViewModel()
        var result = true
        vm.deleteMessage("m1") { result = it }

        assertFalse(result)
    }

    @Test
    fun `deleteMessagePart calls api and returns true on success`() = runTest {
        coEvery { manageSessionUseCase.deleteMessagePart(any(), testSessionId, "m1", 2) } returns true

        val vm = createViewModel()
        var result = false
        vm.deleteMessagePart("m1", 2) { result = it }

        assertTrue(result)
    }

    @Test
    fun `deleteMessagePart returns false on failure`() = runTest {
        coEvery { manageSessionUseCase.deleteMessagePart(any(), testSessionId, "m1", 0) } returns false

        val vm = createViewModel()
        var result = true
        vm.deleteMessagePart("m1", 0) { result = it }

        assertFalse(result)
    }

    private fun createViewModel(sessionId: String = testSessionId): ChatViewModel {
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
            toolCardResolver = dev.minios.ocremote.ui.screens.chat.tools.DefaultToolCardResolver()
        )
    }

    private fun createTestSession(
        id: String = testSessionId,
        directory: String = "/home/user/project"
    ): Session = Session(
        id = id,
        title = "Test Session",
        directory = directory,
        time = Session.Time(created = 1000L, updated = 2000L)
    )

    // --- Task 10: onSessionUpdated tests ---

    @Test
    fun `onSessionUpdated refreshes messages for matching session`() = runTest {
        val messages = listOf(mockk<MessageWithParts>(relaxed = true))
        coEvery { manageSessionUseCase.listMessages(any(), testSessionId, 100) } returns messages

        val vm = createViewModel()
        vm.onSessionUpdated(createTestSession())

        coVerify { manageSessionUseCase.listMessages(any(), testSessionId, 100) }
    }

    @Test
    fun `onSessionUpdated ignores non-matching session`() = runTest {
        coEvery { manageSessionUseCase.listMessages(any(), any(), any()) } returns emptyList()

        val vm = createViewModel()
        // Should not throw for non-matching session
        vm.onSessionUpdated(createTestSession(id = "other-session"))
    }

    @Test
    fun `onSessionUpdated handles exception gracefully`() = runTest {
        coEvery { manageSessionUseCase.listMessages(any(), testSessionId, 100) } throws RuntimeException("network error")

        val vm = createViewModel()
        // Should not throw
        vm.onSessionUpdated(createTestSession())
    }
}
