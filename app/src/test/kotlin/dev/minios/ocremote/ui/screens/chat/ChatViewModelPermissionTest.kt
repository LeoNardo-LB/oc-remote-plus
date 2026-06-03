package dev.minios.ocremote.ui.screens.chat

import android.util.Log
import app.cash.turbine.test
import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.dto.response.PermissionRequest
import dev.minios.ocremote.data.dto.response.ProvidersResponse
import dev.minios.ocremote.data.repository.DraftRepository
import dev.minios.ocremote.data.repository.EventDispatcher
import dev.minios.ocremote.data.repository.handler.*
import dev.minios.ocremote.data.repository.SettingsRepository
import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.domain.model.SseEvent
import dev.minios.ocremote.domain.model.ToolRef
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import androidx.lifecycle.SavedStateHandle

/**
 * Pure-JVM unit tests for ChatViewModel's permission-related logic.
 *
 * Uses [UnconfinedTestDispatcher] so viewModelScope coroutines execute eagerly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelPermissionTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var eventDispatcher: EventDispatcher
    private lateinit var api: OpenCodeApi
    private lateinit var settingsRepository: SettingsRepository
    // UseCase mocks
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
    private val testDirectory = "/home/user/project"

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

        // Create fresh mocks per test to avoid stub ordering issues
        api = mockk(relaxed = true)
        settingsRepository = mockk()

        // Create UseCase mocks (all relaxed so unimportant methods don't need stubs)
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

        // Init block stubs — defaults that tests can override
        coEvery { manageSessionUseCase.getSession(any(), any()) } returns createTestSession()
        coEvery { manageSessionUseCase.listMessages(any(), any(), any()) } returns emptyList()
        coEvery { managePermissionUseCase.listPendingQuestions(any(), any()) } returns emptyList()
        coEvery { selectModelUseCase.loadProviders(any()) } returns ProvidersResponse(emptyList())
        coEvery { manageAgentUseCase.loadAgents(any()) } returns emptyList()
        coEvery { manageAgentUseCase.loadCommands(any()) } returns emptyList()
        // NOTE: listPendingPermissions is NOT set here — each test sets its own stub
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createViewModel(
        sessionId: String = testSessionId,
        serverId: String = testServerId
    ): ChatViewModel {
        val savedState = SavedStateHandle(mapOf(
            "serverUrl"  to "http://localhost:8080",
            "username"   to "testuser",
            "password"   to "testpass",
            "serverName" to "TestServer",
            "serverId"   to serverId,
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
        directory: String = testDirectory
    ): Session = Session(
        id = id,
        title = "Test Session",
        directory = directory,
        time = Session.Time(created = 1000L, updated = 2000L)
    )

    private fun createTestPermissionRequest(
        id: String = "perm-1",
        sessionId: String = testSessionId,
        permission: String = "bash",
        patterns: List<String> = listOf("/home/user/project"),
        metadata: Map<String, JsonPrimitive>? = null,
        always: JsonElement? = null,
        tool: ToolRef? = null
    ): PermissionRequest = PermissionRequest(
        id = id,
        sessionId = sessionId,
        permission = permission,
        patterns = patterns,
        metadata = metadata,
        always = always,
        tool = tool
    )

    // ============================================================
    // Sanity checks: verify init block coroutines execute
    // ============================================================

    @Test
    fun `init block executes — getSession API is called`() = runTest {
        coEvery { managePermissionUseCase.listPendingPermissions(any(), any()) } returns emptyList()
        createViewModel()
        coVerify { manageSessionUseCase.getSession(any(), testSessionId) }
    }

    @Test
    fun `init block executes — permissions API is called`() = runTest {
        coEvery { managePermissionUseCase.listPendingPermissions(any(), any()) } returns emptyList()
        createViewModel()
        coVerify { managePermissionUseCase.listPendingPermissions(any(), any()) }
    }

    @Test
    fun `EventDispatcher setPermissions works directly`() = runTest {
        val perm = SseEvent.PermissionAsked(id = "p1", sessionId = testSessionId, permission = "bash")
        eventDispatcher.setPermissions(testSessionId, listOf(perm))
        assertEquals(1, eventDispatcher.permissions.value[testSessionId]?.size)
        assertEquals("p1", eventDispatcher.permissions.value[testSessionId]?.firstOrNull()?.id)
    }

    // ============================================================
    // Tests: loadPendingPermissions
    // ============================================================

    @Test
    fun `loadPendingPermissions maps and stores permission`() = runTest {
        val permRequest = createTestPermissionRequest(
            id = "perm-1",
            sessionId = testSessionId,
            permission = "bash",
            patterns = listOf("/home/user"),
            metadata = mapOf("key" to JsonPrimitive("value")),
            always = JsonArray(listOf(JsonPrimitive("pattern1")))
        )
        coEvery { managePermissionUseCase.listPendingPermissions(any(), any()) } returns listOf(permRequest)

        val vm = createViewModel()

        // Check EventDispatcher directly (source of truth)
        val reducerPerms = eventDispatcher.permissions.value
        assertEquals("EventDispatcher should have 1 permission for session, got: ${reducerPerms}",
            1, reducerPerms[testSessionId]?.size)
        assertEquals("perm-1", reducerPerms[testSessionId]?.firstOrNull()?.id)
        assertEquals("bash", reducerPerms[testSessionId]?.firstOrNull()?.permission)
        assertEquals(true, reducerPerms[testSessionId]?.firstOrNull()?.always)
        assertEquals(mapOf("key" to "value"), reducerPerms[testSessionId]?.firstOrNull()?.metadata)
    }

    @Test
    fun `loadPendingPermissions filters by session ID`() = runTest {
        val perm1 = createTestPermissionRequest(id = "p1", sessionId = testSessionId)
        val perm2 = createTestPermissionRequest(id = "p2", sessionId = "other-session")
        coEvery { managePermissionUseCase.listPendingPermissions(any(), any()) } returns listOf(perm1, perm2)

        val vm = createViewModel()

        val reducerPerms = eventDispatcher.permissions.value
        assertEquals(1, reducerPerms[testSessionId]?.size)
        assertEquals("p1", reducerPerms[testSessionId]?.firstOrNull()?.id)
        assertTrue(reducerPerms["other-session"].isNullOrEmpty())
    }

    @Test
    fun `loadPendingPermissions empty result — no permissions stored`() = runTest {
        coEvery { managePermissionUseCase.listPendingPermissions(any(), any()) } returns emptyList()

        val vm = createViewModel()

        assertTrue(eventDispatcher.permissions.value.isEmpty())
    }

    @Test
    fun `loadPendingPermissions maps metadata`() = runTest {
        coEvery { managePermissionUseCase.listPendingPermissions(any(), any()) } returns listOf(
            createTestPermissionRequest(
                id = "pm",
                metadata = mapOf(
                    "str" to JsonPrimitive("hello"),
                    "num" to JsonPrimitive(42),
                    "bool" to JsonPrimitive(true)
                )
            )
        )

        createViewModel()

        val perm = eventDispatcher.permissions.value[testSessionId]?.firstOrNull()
        assertNotNull(perm)
        assertEquals("hello", perm?.metadata?.get("str"))
        assertEquals("42", perm?.metadata?.get("num"))
        assertEquals("true", perm?.metadata?.get("bool"))
    }

    @Test
    fun `loadPendingPermissions maps always field`() = runTest {
        coEvery { managePermissionUseCase.listPendingPermissions(any(), any()) } returns listOf(
            createTestPermissionRequest(id = "p-no", always = null),
            createTestPermissionRequest(id = "p-yes", always = JsonArray(listOf(JsonPrimitive("pattern"))))
        )

        createViewModel()

        val perms = eventDispatcher.permissions.value[testSessionId]
        assertEquals(2, perms?.size)
        assertFalse(perms?.first { it.id == "p-no" }?.always ?: true)
        assertTrue(perms?.first { it.id == "p-yes" }?.always ?: false)
    }

    @Test
    fun `loadPendingPermissions API exception does not crash`() = runTest {
        coEvery { managePermissionUseCase.listPendingPermissions(any(), any()) } throws RuntimeException("err")

        createViewModel() // Should not throw

        assertTrue(eventDispatcher.permissions.value.isEmpty())
    }

    @Test
    fun `loadPendingPermissions maps tool ref`() = runTest {
        coEvery { managePermissionUseCase.listPendingPermissions(any(), any()) } returns listOf(
            createTestPermissionRequest(id = "pt", tool = ToolRef(messageId = "m1", callId = "c1"))
        )

        createViewModel()

        val perm = eventDispatcher.permissions.value[testSessionId]?.firstOrNull()
        assertNotNull(perm)
        assertEquals("m1", perm?.tool?.messageId)
        assertEquals("c1", perm?.tool?.callId)
    }

    // ============================================================
    // Tests: replyToPermission
    // ============================================================

    @Test
    fun `replyToPermission calls API and removes permission`() = runTest {
        coEvery { managePermissionUseCase.listPendingPermissions(any(), any()) } returns listOf(
            createTestPermissionRequest(id = "perm-reply")
        )
        coEvery { managePermissionUseCase.replyToPermission(any(), any(), any(), any()) } returns true

        val vm = createViewModel()
        assertEquals("Precondition: 1 permission loaded",
            1, eventDispatcher.permissions.value[testSessionId]?.size)

        vm.replyToPermission("perm-reply", "once")

        coVerify { managePermissionUseCase.replyToPermission(any(), "perm-reply", "once", any()) }
        assertTrue(eventDispatcher.permissions.value[testSessionId].isNullOrEmpty())
    }

    @Test
    fun `replyToPermission with reply=always`() = runTest {
        coEvery { managePermissionUseCase.listPendingPermissions(any(), any()) } returns listOf(
            createTestPermissionRequest(id = "pa")
        )
        coEvery { managePermissionUseCase.replyToPermission(any(), any(), any(), any()) } returns true

        val vm = createViewModel()

        vm.replyToPermission("pa", "always")

        coVerify { managePermissionUseCase.replyToPermission(any(), "pa", "always", any()) }
        assertTrue(eventDispatcher.permissions.value[testSessionId].isNullOrEmpty())
    }

    @Test
    fun `replyToPermission with reply=reject`() = runTest {
        coEvery { managePermissionUseCase.listPendingPermissions(any(), any()) } returns listOf(
            createTestPermissionRequest(id = "pr")
        )
        coEvery { managePermissionUseCase.replyToPermission(any(), any(), any(), any()) } returns true

        val vm = createViewModel()

        vm.replyToPermission("pr", "reject")

        coVerify { managePermissionUseCase.replyToPermission(any(), "pr", "reject", any()) }
        assertTrue(eventDispatcher.permissions.value[testSessionId].isNullOrEmpty())
    }

    @Test
    fun `replyToPermission does NOT remove when API returns false`() = runTest {
        coEvery { managePermissionUseCase.listPendingPermissions(any(), any()) } returns listOf(
            createTestPermissionRequest(id = "pf")
        )
        coEvery { managePermissionUseCase.replyToPermission(any(), any(), any(), any()) } returns false

        val vm = createViewModel()

        vm.replyToPermission("pf", "once")

        assertEquals(1, eventDispatcher.permissions.value[testSessionId]?.size)
    }

    @Test
    fun `replyToPermission spares other permissions`() = runTest {
        coEvery { managePermissionUseCase.listPendingPermissions(any(), any()) } returns listOf(
            createTestPermissionRequest(id = "p1", permission = "bash"),
            createTestPermissionRequest(id = "p2", permission = "write")
        )
        coEvery { managePermissionUseCase.replyToPermission(any(), "p1", any(), any()) } returns true

        val vm = createViewModel()

        vm.replyToPermission("p1", "once")

        val perms = eventDispatcher.permissions.value[testSessionId]
        assertEquals(1, perms?.size)
        assertEquals("p2", perms?.firstOrNull()?.id)
        assertEquals("write", perms?.firstOrNull()?.permission)
    }

    @Test
    fun `replyToPermission API exception keeps permission`() = runTest {
        coEvery { managePermissionUseCase.listPendingPermissions(any(), any()) } returns listOf(
            createTestPermissionRequest(id = "pe")
        )
        coEvery { managePermissionUseCase.replyToPermission(any(), any(), any(), any()) } throws RuntimeException("err")

        val vm = createViewModel()

        vm.replyToPermission("pe", "once")

        assertEquals(1, eventDispatcher.permissions.value[testSessionId]?.size)
    }

    // ============================================================
    // Tests: multi-session
    // ============================================================

    @Test
    fun `multi-session — only current session permissions loaded into EventDispatcher`() = runTest {
        coEvery { managePermissionUseCase.listPendingPermissions(any(), any()) } returns listOf(
            createTestPermissionRequest(id = "p1", sessionId = testSessionId),
            createTestPermissionRequest(id = "p2", sessionId = "session-456")
        )

        createViewModel()

        // Only current session's permissions are stored (filter is by sessionId)
        assertEquals(1, eventDispatcher.permissions.value[testSessionId]?.size)
        assertEquals("p1", eventDispatcher.permissions.value[testSessionId]?.firstOrNull()?.id)
        // session-456 is NOT loaded because loadPendingPermissions only stores
        // permissions matching the ViewModel's own sessionId
        assertTrue(eventDispatcher.permissions.value["session-456"].isNullOrEmpty())
    }
}
