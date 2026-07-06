## Task 8+9 Diff: 827eb695..49411fff
### Commits
49411fff refactor(ui): SessionListViewModel + ChatViewModel read statusFlow from SessionStateService
### Stat
 .../kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModel.kt | 6 ++++--
 .../leonardo/ocremotev2/ui/screens/sessions/SessionListViewModel.kt | 4 +++-
 .../leonardo/ocremotev2/ui/screens/chat/ChatViewModelDeleteTest.kt  | 5 +++++
 .../ocremotev2/ui/screens/chat/ChatViewModelPermissionTest.kt       | 4 ++++
 .../leonardo/ocremotev2/ui/screens/chat/ChatViewModelQueuedTest.kt  | 4 ++++
 .../leonardo/ocremotev2/ui/screens/chat/ChatViewModelSendTest.kt    | 4 ++++
 .../ocremotev2/ui/screens/chat/ChatViewModelStreamingTest.kt        | 4 ++++
 .../ui/screens/sessions/SessionListViewModelPaginationTest.kt       | 4 ++++
 .../ui/screens/sessions/SessionListViewModelSearchTest.kt           | 4 ++++
 9 files changed, 36 insertions(+), 3 deletions(-)
### Full Diff
diff --git a/app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModel.kt b/app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModel.kt
index 6207ee58..1351b617 100644
--- a/app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModel.kt
+++ b/app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModel.kt
@@ -13,12 +13,13 @@ import dagger.hilt.android.lifecycle.HiltViewModel
 import dev.leonardo.ocremotev2.domain.model.AgentInfo
 import dev.leonardo.ocremotev2.domain.model.CommandInfo
 import dev.leonardo.ocremotev2.domain.model.ModelSelection
 import dev.leonardo.ocremotev2.domain.model.PromptPart
 import dev.leonardo.ocremotev2.domain.model.ProviderCatalog
 import dev.leonardo.ocremotev2.data.repository.ServerTerminalRegistry
+import dev.leonardo.ocremotev2.data.repository.SessionStateService
 import dev.leonardo.ocremotev2.data.repository.SessionStatusManager
 import dev.leonardo.ocremotev2.ui.screens.chat.tools.ToolCardResolver
 import dev.leonardo.ocremotev2.ui.screens.chat.util.ContextBreakdown
 import dev.leonardo.ocremotev2.ui.screens.chat.util.ContextDetailState
 import dev.leonardo.ocremotev2.ui.screens.chat.util.MessageCount
 import dev.leonardo.ocremotev2.ui.screens.chat.util.ProviderModel
@@ -221,12 +222,13 @@ class ChatViewModel @Inject constructor(
     private val sessionRepository: SessionRepository,
     private val messagePaging: MessagePaginationUseCase,
     private val tokenStatsTracker: TokenStatsTracker,
     private val httpClient: io.ktor.client.HttpClient,
     private val sseClient: SseClient,
     private val sessionStatusManager: SessionStatusManager,
+    private val sessionStateService: SessionStateService,
     private val sessionFocusHolder: dev.leonardo.ocremotev2.service.SessionFocusHolder,
     private val appNotificationManager: dev.leonardo.ocremotev2.service.AppNotificationManager,
     private val toolSnapshotCache: dev.leonardo.ocremotev2.domain.repository.ToolSnapshotCache,
 ) : ViewModel() {
 
     // ============ Phase 2 Task 9: Tool snapshot cache ============
@@ -530,19 +532,19 @@ class ChatViewModel @Inject constructor(
 
     // messageListState — migrated to MessageDataDelegate (Phase 3 Task 5 — B cluster).
 
     /**
      * Session metadata — changes when session info is updated (title, status, agent).
      * Includes [SessionLifecycleDelegate.sessionIdFlow] as a source so lazy-session creation triggers immediate recomputation.
-     * Session status is sourced from [SessionStatusManager.statusFlow] (FSM-driven),
+     * Session status is sourced from [SessionStateService.statusFlow] (FSM-driven),
      * the single source of truth for busy/idle/activity state.
      */
     val sessionMetaState: StateFlow<SessionMetaState> = combine(
         sessionLifecycle.sessionIdFlow,
         sessionRepository.getSessionsFlow(serverId),
-        sessionStatusManager.statusFlow,
+        sessionStateService.statusFlow,
         sessionRepository.getCurrentAgentFlow(serverId),
         sessionRepository.getCurrentModelFlow(serverId),
     ) { args ->
         val sid = args[0] as String
         @Suppress("UNCHECKED_CAST")
         val allSessions = args[1] as List<Session>
diff --git a/app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/sessions/SessionListViewModel.kt b/app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/sessions/SessionListViewModel.kt
index 7b8ceb43..0b38cd20 100644
--- a/app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/sessions/SessionListViewModel.kt
+++ b/app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/sessions/SessionListViewModel.kt
@@ -15,12 +15,13 @@ import dev.leonardo.ocremotev2.data.api.file.FileApi
 import dev.leonardo.ocremotev2.data.api.session.SessionApi
 import dev.leonardo.ocremotev2.data.api.RestSessionStatusInfo
 import dev.leonardo.ocremotev2.data.api.system.SystemApi
 import dev.leonardo.ocremotev2.data.api.terminal.TerminalApi
 import dev.leonardo.ocremotev2.domain.model.ServerConnection
 import dev.leonardo.ocremotev2.data.repository.EventDispatcher
+import dev.leonardo.ocremotev2.data.repository.SessionStateService
 import dev.leonardo.ocremotev2.domain.model.Project
 import dev.leonardo.ocremotev2.domain.model.Session
 import dev.leonardo.ocremotev2.domain.model.SessionStatus
 import dev.leonardo.ocremotev2.domain.model.McpServerStatus
 import dev.leonardo.ocremotev2.domain.repository.DraftRepository
 import dev.leonardo.ocremotev2.domain.repository.McpRepository
@@ -73,12 +74,13 @@ data class SessionItem(
 )
 
 @HiltViewModel
 class SessionListViewModel @Inject constructor(
     private val savedStateHandle: SavedStateHandle,
     private val eventDispatcher: EventDispatcher,
+    private val sessionStateService: SessionStateService,
     private val sessionApi: SessionApi,
     private val fileApi: FileApi,
     private val systemApi: SystemApi,
     private val terminalApi: TerminalApi,
     private val manageSessionUseCase: ManageSessionUseCase,
     private val deleteSessionUseCase: DeleteSessionUseCase,
@@ -146,13 +148,13 @@ class SessionListViewModel @Inject constructor(
     private val _mcpError = MutableSharedFlow<String>()
     val mcpError: SharedFlow<String> = _mcpError.asSharedFlow()
 
     @Suppress("UNCHECKED_CAST")
     val uiState: StateFlow<SessionListUiState> = combine(
         eventDispatcher.sessions,
-        eventDispatcher.sessionStatuses,
+        sessionStateService.statusFlow,
         eventDispatcher.serverSessions,
         eventDispatcher.lastUserMessageTime,
         _isLoading,
         _error,
         _projects,
         _expandedPaths,
diff --git a/app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModelDeleteTest.kt b/app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModelDeleteTest.kt
index 32128700..01ed92b8 100644
--- a/app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModelDeleteTest.kt
+++ b/app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModelDeleteTest.kt
@@ -6,12 +6,13 @@ import io.ktor.client.HttpClient
 import dev.leonardo.ocremotev2.data.api.SseClient
 import dev.leonardo.ocremotev2.domain.model.AppSettings
 import dev.leonardo.ocremotev2.domain.model.ProvidersResponse
 import dev.leonardo.ocremotev2.domain.repository.ChatRepository
 import dev.leonardo.ocremotev2.domain.repository.DraftRepository
 import dev.leonardo.ocremotev2.data.repository.EventDispatcher
+import dev.leonardo.ocremotev2.data.repository.SessionStateService
 import dev.leonardo.ocremotev2.data.repository.SessionStatusManager
 import dev.leonardo.ocremotev2.service.SessionFocusHolder
 import dev.leonardo.ocremotev2.service.AppNotificationManager
 import dev.leonardo.ocremotev2.data.repository.handler.*
 import dev.leonardo.ocremotev2.domain.model.MessageWithParts
 import dev.leonardo.ocremotev2.domain.model.Session
@@ -25,12 +26,13 @@ import io.mockk.coVerify
 import io.mockk.every
 import dev.leonardo.ocremotev2.domain.repository.ToolSnapshotCache
 import io.mockk.mockk
 import io.mockk.mockkStatic
 import io.mockk.unmockkAll
 import kotlinx.coroutines.Dispatchers
+import kotlinx.coroutines.flow.MutableStateFlow
 import kotlinx.coroutines.flow.flowOf
 import kotlinx.coroutines.flow.map
 import kotlinx.coroutines.ExperimentalCoroutinesApi
 import kotlinx.coroutines.test.UnconfinedTestDispatcher
 import kotlinx.coroutines.test.resetMain
 import kotlinx.coroutines.test.runTest
@@ -59,12 +61,13 @@ class ChatViewModelDeleteTest {
     private lateinit var draftUseCase: DraftUseCase
     private lateinit var shareExportUseCase: ShareExportUseCase
     private lateinit var undoRedoUseCase: UndoRedoUseCase
     private lateinit var messagePaging: MessagePaginationUseCase
     private val tokenStatsTracker = TokenStatsTracker()
     private val sessionStatusManager: SessionStatusManager = mockk(relaxed = true)
+    private val sessionStateService: SessionStateService = mockk(relaxed = true)
     private val sessionFocusHolder = mockk<SessionFocusHolder>(relaxed = true)
     private val appNotificationManager = mockk<AppNotificationManager>(relaxed = true)
     private val toolSnapshotCache = ToolSnapshotCache()
 
     private val testSessionId = "session-123"
     private val testServerId = "server-1"
@@ -87,12 +90,13 @@ class ChatViewModelDeleteTest {
             questionHandler = QuestionEventHandler(),
             miscHandler = MiscEventHandler(),
             sessionNextHandler = SessionNextEventHandler(),
             sessionStatusManager = sessionStatusManager
         )
         every { sessionStatusManager.statusFlow } returns eventDispatcher.sessionStatuses
+        every { sessionStateService.statusFlow } returns MutableStateFlow(emptyMap())
 
         mockkStatic(Log::class)
         every { Log.d(any(), any()) } returns 0
         every { Log.e(any(), any()) } returns 0
         every { Log.e(any(), any(), any()) } returns 0
         every { Log.w(any(), any<String>()) } returns 0
@@ -236,12 +240,13 @@ class ChatViewModelDeleteTest {
             },
             messagePaging = messagePaging,
             tokenStatsTracker = tokenStatsTracker,
             httpClient = mockk(relaxed = true),
             sseClient = mockk(relaxed = true),
             sessionStatusManager = sessionStatusManager,
+            sessionStateService = sessionStateService,
             sessionFocusHolder = sessionFocusHolder,
             scrollSignal = SessionScrollSignal(),
             appNotificationManager = appNotificationManager,
             toolSnapshotCache = toolSnapshotCache
         )
     }
diff --git a/app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModelPermissionTest.kt b/app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModelPermissionTest.kt
index be60207a..ecfb0644 100644
--- a/app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModelPermissionTest.kt
+++ b/app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModelPermissionTest.kt
@@ -8,12 +8,13 @@ import dev.leonardo.ocremotev2.data.api.SseClient
 import dev.leonardo.ocremotev2.domain.model.AppSettings
 import dev.leonardo.ocremotev2.domain.model.ProvidersResponse
 import dev.leonardo.ocremotev2.domain.model.PermissionState
 import dev.leonardo.ocremotev2.domain.repository.ChatRepository
 import dev.leonardo.ocremotev2.domain.repository.DraftRepository
 import dev.leonardo.ocremotev2.data.repository.EventDispatcher
+import dev.leonardo.ocremotev2.data.repository.SessionStateService
 import dev.leonardo.ocremotev2.data.repository.SessionStatusManager
 import dev.leonardo.ocremotev2.service.SessionFocusHolder
 import dev.leonardo.ocremotev2.service.AppNotificationManager
 import dev.leonardo.ocremotev2.data.repository.handler.*
 import dev.leonardo.ocremotev2.domain.model.Session
 import dev.leonardo.ocremotev2.domain.model.SseEvent
@@ -71,12 +72,13 @@ class ChatViewModelPermissionTest {
     private lateinit var draftUseCase: DraftUseCase
     private lateinit var shareExportUseCase: ShareExportUseCase
     private lateinit var undoRedoUseCase: UndoRedoUseCase
     private lateinit var messagePaging: MessagePaginationUseCase
     private val tokenStatsTracker = TokenStatsTracker()
     private val sessionStatusManager: SessionStatusManager = mockk(relaxed = true)
+    private val sessionStateService: SessionStateService = mockk(relaxed = true)
     private val sessionFocusHolder = mockk<SessionFocusHolder>(relaxed = true)
     private val appNotificationManager = mockk<AppNotificationManager>(relaxed = true)
     private val toolSnapshotCache = ToolSnapshotCache()
 
     private val testSessionId = "session-123"
     private val testServerId = "server-1"
@@ -100,12 +102,13 @@ class ChatViewModelPermissionTest {
             questionHandler = QuestionEventHandler(),
             miscHandler = MiscEventHandler(),
             sessionNextHandler = SessionNextEventHandler(),
             sessionStatusManager = sessionStatusManager
         )
         every { sessionStatusManager.statusFlow } returns eventDispatcher.sessionStatuses
+        every { sessionStateService.statusFlow } returns MutableStateFlow(emptyMap())
 
         mockkStatic(Log::class)
         every { Log.d(any(), any()) } returns 0
         every { Log.e(any(), any()) } returns 0
         every { Log.e(any(), any(), any()) } returns 0
         every { Log.w(any(), any<String>()) } returns 0
@@ -241,12 +244,13 @@ class ChatViewModelPermissionTest {
             },
             messagePaging = messagePaging,
             tokenStatsTracker = tokenStatsTracker,
             httpClient = mockk(relaxed = true),
             sseClient = mockk(relaxed = true),
             sessionStatusManager = sessionStatusManager,
+            sessionStateService = sessionStateService,
             sessionFocusHolder = sessionFocusHolder,
             scrollSignal = SessionScrollSignal(),
             appNotificationManager = appNotificationManager,
             toolSnapshotCache = toolSnapshotCache
         )
     }
diff --git a/app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModelQueuedTest.kt b/app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModelQueuedTest.kt
index d3dabce9..6742a243 100644
--- a/app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModelQueuedTest.kt
+++ b/app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModelQueuedTest.kt
@@ -5,12 +5,13 @@ import android.util.Log
 import dev.leonardo.ocremotev2.data.repository.ServerTerminalRegistry
 import io.ktor.client.HttpClient
 import dev.leonardo.ocremotev2.data.api.SseClient
 import dev.leonardo.ocremotev2.domain.model.AppSettings
 import dev.leonardo.ocremotev2.domain.model.ProvidersResponse
 import dev.leonardo.ocremotev2.data.repository.EventDispatcher
+import dev.leonardo.ocremotev2.data.repository.SessionStateService
 import dev.leonardo.ocremotev2.data.repository.SessionStatusManager
 import dev.leonardo.ocremotev2.service.SessionFocusHolder
 import dev.leonardo.ocremotev2.service.AppNotificationManager
 import dev.leonardo.ocremotev2.data.repository.handler.*
 import dev.leonardo.ocremotev2.domain.model.*
 import dev.leonardo.ocremotev2.domain.repository.ChatRepository
@@ -73,12 +74,13 @@ class ChatViewModelQueuedTest {
     private val draftUseCase: DraftUseCase = mockk(relaxed = true)
     private val shareExportUseCase: ShareExportUseCase = mockk(relaxed = true)
     private val undoRedoUseCase: UndoRedoUseCase = mockk(relaxed = true)
     private val messagePaging: MessagePaginationUseCase = mockk(relaxed = true)
     private val tokenStatsTracker = TokenStatsTracker()
     private val sessionStatusManager: SessionStatusManager = mockk(relaxed = true)
+    private val sessionStateService: SessionStateService = mockk(relaxed = true)
     private val sessionFocusHolder = mockk<SessionFocusHolder>(relaxed = true)
     private val appNotificationManager = mockk<AppNotificationManager>(relaxed = true)
     private val toolSnapshotCache = ToolSnapshotCache()
 
     private val testSessionId = "test-session-1"
     private val testServerId = "test-server-1"
@@ -108,12 +110,13 @@ class ChatViewModelQueuedTest {
             questionHandler = QuestionEventHandler(),
             miscHandler = MiscEventHandler(),
             sessionNextHandler = SessionNextEventHandler(),
             sessionStatusManager = sessionStatusManager
         )
         every { sessionStatusManager.statusFlow } returns testStatusFlow
+        every { sessionStateService.statusFlow } returns testStatusFlow
 
         mockkStatic(Log::class)
         every { Log.d(any(), any()) } returns 0
         every { Log.e(any(), any()) } returns 0
         every { Log.e(any(), any(), any()) } returns 0
         every { Log.w(any(), any<String>()) } returns 0
@@ -283,12 +286,13 @@ class ChatViewModelQueuedTest {
             },
             messagePaging = messagePaging,
             tokenStatsTracker = tokenStatsTracker,
             httpClient = mockk(relaxed = true),
             sseClient = mockk(relaxed = true),
             sessionStatusManager = sessionStatusManager,
+            sessionStateService = sessionStateService,
             sessionFocusHolder = sessionFocusHolder,
             scrollSignal = SessionScrollSignal(),
             appNotificationManager = appNotificationManager,
             toolSnapshotCache = toolSnapshotCache
         )
     }
diff --git a/app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModelSendTest.kt b/app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModelSendTest.kt
index deab2c58..6cbbc2e7 100644
--- a/app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModelSendTest.kt
+++ b/app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModelSendTest.kt
@@ -1,12 +1,13 @@
 ﻿package dev.leonardo.ocremotev2.ui.screens.chat
 
 import dev.leonardo.ocremotev2.domain.repository.ToolSnapshotCache
 import android.util.Log
 import androidx.lifecycle.SavedStateHandle
 import dev.leonardo.ocremotev2.data.repository.ServerTerminalRegistry
+import dev.leonardo.ocremotev2.data.repository.SessionStateService
 import dev.leonardo.ocremotev2.data.repository.SessionStatusManager
 import dev.leonardo.ocremotev2.service.SessionFocusHolder
 import dev.leonardo.ocremotev2.service.AppNotificationManager
 import io.ktor.client.HttpClient
 import dev.leonardo.ocremotev2.data.api.SseClient
 import dev.leonardo.ocremotev2.domain.model.AppSettings
@@ -52,12 +53,13 @@ class ChatViewModelSendTest {
     private val draftUseCase: DraftUseCase = mockk(relaxed = true)
     private val shareExportUseCase: ShareExportUseCase = mockk(relaxed = true)
     private val undoRedoUseCase: UndoRedoUseCase = mockk(relaxed = true)
     private val messagePaging: MessagePaginationUseCase = mockk(relaxed = true)
     private val tokenStatsTracker = TokenStatsTracker()
     private val sessionStatusManager: SessionStatusManager = mockk(relaxed = true)
+    private val sessionStateService: SessionStateService = mockk(relaxed = true)
     private val sessionFocusHolder = mockk<SessionFocusHolder>(relaxed = true)
     private val appNotificationManager = mockk<AppNotificationManager>(relaxed = true)
     private val toolSnapshotCache = ToolSnapshotCache()
 
     @After
     fun tearDown() {
@@ -128,12 +130,13 @@ class ChatViewModelSendTest {
             "password"   to "testpass",
             "serverName" to "TestServer",
             "serverId"   to "test-server",
             "sessionId"  to "test-session"
         ))
         every { sessionStatusManager.statusFlow } returns MutableStateFlow(emptyMap())
+        every { sessionStateService.statusFlow } returns MutableStateFlow(emptyMap())
         return ChatViewModel(
             savedStateHandle = savedState,
             sendMessageUseCase = sendMessageUseCase,
             manageSessionUseCase = manageSessionUseCase,
             managePermissionUseCase = managePermissionUseCase,
             selectModelUseCase = selectModelUseCase,
@@ -156,12 +159,13 @@ class ChatViewModelSendTest {
             },
             messagePaging = messagePaging,
             tokenStatsTracker = tokenStatsTracker,
             httpClient = mockk(relaxed = true),
             sseClient = mockk(relaxed = true),
             sessionStatusManager = sessionStatusManager,
+            sessionStateService = sessionStateService,
             sessionFocusHolder = sessionFocusHolder,
             scrollSignal = SessionScrollSignal(),
             appNotificationManager = appNotificationManager,
             toolSnapshotCache = toolSnapshotCache
         )
     }
diff --git a/app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModelStreamingTest.kt b/app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModelStreamingTest.kt
index a847ae5f..442cae16 100644
--- a/app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModelStreamingTest.kt
+++ b/app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModelStreamingTest.kt
@@ -1,12 +1,13 @@
 ﻿package dev.leonardo.ocremotev2.ui.screens.chat
 
 import dev.leonardo.ocremotev2.domain.repository.ToolSnapshotCache
 import android.util.Log
 import androidx.lifecycle.SavedStateHandle
 import dev.leonardo.ocremotev2.data.repository.ServerTerminalRegistry
+import dev.leonardo.ocremotev2.data.repository.SessionStateService
 import dev.leonardo.ocremotev2.data.repository.SessionStatusManager
 import dev.leonardo.ocremotev2.service.SessionFocusHolder
 import dev.leonardo.ocremotev2.service.AppNotificationManager
 import io.ktor.client.HttpClient
 import dev.leonardo.ocremotev2.data.api.SseClient
 import dev.leonardo.ocremotev2.domain.model.AppSettings
@@ -55,12 +56,13 @@ class ChatViewModelStreamingTest {
     private val draftUseCase: DraftUseCase = mockk(relaxed = true)
     private val shareExportUseCase: ShareExportUseCase = mockk(relaxed = true)
     private val undoRedoUseCase: UndoRedoUseCase = mockk(relaxed = true)
     private val messagePaging: MessagePaginationUseCase = mockk(relaxed = true)
     private val tokenStatsTracker = TokenStatsTracker()
     private val sessionStatusManager: SessionStatusManager = mockk(relaxed = true)
+    private val sessionStateService: SessionStateService = mockk(relaxed = true)
     private val sessionFocusHolder = mockk<SessionFocusHolder>(relaxed = true)
     private val appNotificationManager = mockk<AppNotificationManager>(relaxed = true)
     private val toolSnapshotCache = ToolSnapshotCache()
 
     private val messagesFlow = MutableStateFlow<List<Message>>(emptyList())
     private val partsFlow = MutableStateFlow<Map<String, List<dev.leonardo.ocremotev2.domain.model.Part>>>(emptyMap())
@@ -146,12 +148,13 @@ class ChatViewModelStreamingTest {
             every { it.getSessionStatusesFlow(any()) } returns flowOf(emptyMap())
             every { it.getCurrentAgentFlow(any()) } returns flowOf(emptyMap())
             every { it.getCurrentModelFlow(any()) } returns flowOf(emptyMap())
             coEvery { it.fetchSessionStatuses(any(), any()) } returns Result.success(emptyMap())
         }
         every { sessionStatusManager.statusFlow } returns MutableStateFlow(emptyMap())
+        every { sessionStateService.statusFlow } returns MutableStateFlow(emptyMap())
     }
 
     @After
     fun teardown() {
         Dispatchers.resetMain()
         unmockkAll()
@@ -212,12 +215,13 @@ class ChatViewModelStreamingTest {
             sessionRepository = sessionRepository,
             messagePaging = messagePaging,
             tokenStatsTracker = tokenStatsTracker,
             httpClient = mockk(relaxed = true),
             sseClient = mockk(relaxed = true),
             sessionStatusManager = sessionStatusManager,
+            sessionStateService = sessionStateService,
             sessionFocusHolder = sessionFocusHolder,
             scrollSignal = SessionScrollSignal(),
             appNotificationManager = appNotificationManager,
             toolSnapshotCache = toolSnapshotCache
         )
     }
diff --git a/app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/sessions/SessionListViewModelPaginationTest.kt b/app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/sessions/SessionListViewModelPaginationTest.kt
index 52fdef35..f23808ee 100644
--- a/app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/sessions/SessionListViewModelPaginationTest.kt
+++ b/app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/sessions/SessionListViewModelPaginationTest.kt
@@ -3,12 +3,13 @@
 import android.util.Log
 import dev.leonardo.ocremotev2.data.api.file.FileApi
 import dev.leonardo.ocremotev2.data.api.session.SessionApi
 import dev.leonardo.ocremotev2.data.api.system.SystemApi
 import dev.leonardo.ocremotev2.data.api.terminal.TerminalApi
 import dev.leonardo.ocremotev2.data.repository.EventDispatcher
+import dev.leonardo.ocremotev2.data.repository.SessionStateService
 import dev.leonardo.ocremotev2.domain.model.Session
 import dev.leonardo.ocremotev2.domain.model.SessionStatus
 import dev.leonardo.ocremotev2.domain.repository.DraftRepository
 import dev.leonardo.ocremotev2.domain.repository.McpRepository
 import dev.leonardo.ocremotev2.domain.usecase.DeleteSessionUseCase
 import dev.leonardo.ocremotev2.domain.usecase.ManageSessionUseCase
@@ -29,12 +30,13 @@ class SessionListViewModelPaginationTest {
 
     private val sessionApi: SessionApi = mockk()
     private val fileApi: FileApi = mockk()
     private val systemApi: SystemApi = mockk()
     private val terminalApi: TerminalApi = mockk()
     private val eventDispatcher: EventDispatcher = mockk(relaxed = true)
+    private val sessionStateService: SessionStateService = mockk(relaxed = true)
     private val manageSessionUseCase: ManageSessionUseCase = mockk()
     private val deleteSessionUseCase: DeleteSessionUseCase = mockk()
 
     @Before
     fun setup() {
         mockkStatic(Log::class)
@@ -44,12 +46,13 @@ class SessionListViewModelPaginationTest {
         every { Log.w(any(), any<String>(), any()) } returns 0
         // Relaxed mock returns default Object for StateFlow<List>.value;
         // set up proper empty collections to avoid ClassCastException
         every { eventDispatcher.sessions.value } returns emptyList()
         every { eventDispatcher.sessionStatuses.value } returns emptyMap<String, SessionStatus>()
         every { eventDispatcher.serverSessions.value } returns emptyMap<String, Set<String>>()
+        every { sessionStateService.statusFlow.value } returns emptyMap()
     }
 
     @After
     fun teardown() {
         unmockkAll()
     }
@@ -84,12 +87,13 @@ class SessionListViewModelPaginationTest {
                 "serverId" to "srv1"
             )
         )
         return SessionListViewModel(
             savedStateHandle = savedStateHandle,
             eventDispatcher = eventDispatcher,
+            sessionStateService = sessionStateService,
             sessionApi = sessionApi,
             fileApi = fileApi,
             systemApi = systemApi,
             terminalApi = terminalApi,
             manageSessionUseCase = manageSessionUseCase,
             deleteSessionUseCase = deleteSessionUseCase,
diff --git a/app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/sessions/SessionListViewModelSearchTest.kt b/app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/sessions/SessionListViewModelSearchTest.kt
index 35708004..c478826a 100644
--- a/app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/sessions/SessionListViewModelSearchTest.kt
+++ b/app/src/test/kotlin/dev/leonardo/ocremotev2/ui/screens/sessions/SessionListViewModelSearchTest.kt
@@ -3,12 +3,13 @@
 import android.util.Log
 import dev.leonardo.ocremotev2.data.api.file.FileApi
 import dev.leonardo.ocremotev2.data.api.session.SessionApi
 import dev.leonardo.ocremotev2.data.api.system.SystemApi
 import dev.leonardo.ocremotev2.data.api.terminal.TerminalApi
 import dev.leonardo.ocremotev2.data.repository.EventDispatcher
+import dev.leonardo.ocremotev2.data.repository.SessionStateService
 import dev.leonardo.ocremotev2.domain.model.Session
 import dev.leonardo.ocremotev2.domain.model.SessionStatus
 import dev.leonardo.ocremotev2.domain.repository.DraftRepository
 import dev.leonardo.ocremotev2.domain.repository.McpRepository
 import dev.leonardo.ocremotev2.domain.usecase.DeleteSessionUseCase
 import dev.leonardo.ocremotev2.domain.usecase.ManageSessionUseCase
@@ -28,12 +29,13 @@ class SessionListViewModelSearchTest {
 
     private val sessionApi: SessionApi = mockk()
     private val fileApi: FileApi = mockk()
     private val systemApi: SystemApi = mockk()
     private val terminalApi: TerminalApi = mockk()
     private val eventDispatcher: EventDispatcher = mockk(relaxed = true)
+    private val sessionStateService: SessionStateService = mockk(relaxed = true)
     private val manageSessionUseCase: ManageSessionUseCase = mockk()
     private val deleteSessionUseCase: DeleteSessionUseCase = mockk()
 
     @Before
     fun setup() {
         mockkStatic(Log::class)
@@ -43,12 +45,13 @@ class SessionListViewModelSearchTest {
         every { Log.w(any(), any<String>(), any()) } returns 0
         // Relaxed mock returns default Object for StateFlow<List>.value;
         // set up proper empty collections to avoid ClassCastException
         every { eventDispatcher.sessions.value } returns emptyList()
         every { eventDispatcher.sessionStatuses.value } returns emptyMap<String, SessionStatus>()
         every { eventDispatcher.serverSessions.value } returns emptyMap<String, Set<String>>()
+        every { sessionStateService.statusFlow.value } returns emptyMap()
     }
 
     @After
     fun teardown() {
         unmockkAll()
     }
@@ -84,12 +87,13 @@ class SessionListViewModelSearchTest {
                 "serverId" to "srv1"
             )
         )
         return SessionListViewModel(
             savedStateHandle = savedStateHandle,
             eventDispatcher = eventDispatcher,
+            sessionStateService = sessionStateService,
             sessionApi = sessionApi,
             fileApi = fileApi,
             systemApi = systemApi,
             terminalApi = terminalApi,
             manageSessionUseCase = manageSessionUseCase,
             deleteSessionUseCase = deleteSessionUseCase,
