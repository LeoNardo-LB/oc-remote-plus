=== PHASE 1 FINAL REVIEW PACKAGE ===
Branch: refactor/phase1-data-foundation
Range: a65a390c..63fa149

--- COMMIT LOG ---
63fa1493 milestone: Phase 1 Data Foundation complete
3883a1ba refactor(sse): split MessageEventHandler into 3 focused sub-event handlers
d6adab78 refactor(sse): EventDispatcher when-block dispatch -> registry pattern (OCP)
4a571ba5 refactor(api): migrate 17 consumers to domain interfaces, delete OpenCodeApi
d16e9711 docs: Phase 1 Block 1 report — domain API extraction complete
1cfc52be refactor(di): bind 6 domain API interfaces in Hilt ApiModule
dabd7782 refactor(api): extract SystemApi interface + impl (8 methods)
0bbe20f1 refactor(api): extract FileApi interface + impl (12 methods, File+VCS)
e005a6de refactor(api): extract ProviderApi interface + impl (13 methods, Provider+Config)
ec70afac refactor(api): extract TerminalApi interface + impl (6 methods)
b5431490 refactor(api): extract MessageApi interface + impl (12 methods, Message+Permission+Question)
8e214547 refactor(api): extract SessionApi interface + impl (21 methods)
b07523e7 refactor(api): add ApiClient shared httpClient holder
4636f9cd fix: remove dev.hossain dead code from MarkdownContent

--- DIFF STAT ---
 .../dev/leonardo/ocremotev2/data/api/ApiClient.kt  |   33 +
 .../leonardo/ocremotev2/data/api/OpenCodeApi.kt    | 1095 --------------------
 .../ocremotev2/data/api/RestSessionStatusInfo.kt   |   19 +
 .../leonardo/ocremotev2/data/api/file/FileApi.kt   |  183 ++++
 .../ocremotev2/data/api/message/MessageApi.kt      |  340 ++++++
 .../ocremotev2/data/api/provider/ProviderApi.kt    |  304 ++++++
 .../ocremotev2/data/api/session/SessionApi.kt      |  385 +++++++
 .../ocremotev2/data/api/system/SystemApi.kt        |  123 +++
 .../ocremotev2/data/api/terminal/TerminalApi.kt    |  253 +++++
 .../data/repository/AgentRepositoryImpl.kt         |   12 +-
 .../data/repository/ChatRepositoryImpl.kt          |   46 +-
 .../ocremotev2/data/repository/EventDispatcher.kt  |   87 +-
 .../data/repository/FileRepositoryImpl.kt          |    4 +-
 .../data/repository/McpRepositoryImpl.kt           |   14 +-
 .../ocremotev2/data/repository/ServerDataStore.kt  |    4 +-
 .../data/repository/ServerRepositoryImpl.kt        |    6 +-
 .../data/repository/ServerTerminalRegistry.kt      |    6 +-
 .../data/repository/SessionRepositoryImpl.kt       |   44 +-
 .../data/repository/TerminalRepositoryImpl.kt      |    8 +-
 .../data/repository/VcsRepositoryImpl.kt           |    4 +-
 .../data/repository/handler/MessageEventHandler.kt |   34 +-
 .../data/repository/handler/MessagePartHandler.kt  |   27 +
 .../repository/handler/MessageRemovedHandler.kt    |   24 +
 .../repository/handler/MessageUpdatedHandler.kt    |   25 +
 .../kotlin/dev/leonardo/ocremotev2/di/ApiModule.kt |   47 +
 .../dev/leonardo/ocremotev2/di/NetworkModule.kt    |    2 +-
 .../domain/repository/SessionRepository.kt         |    2 +-
 .../domain/usecase/SelectModelUseCase.kt           |    2 +-
 .../ocremotev2/service/SseConnectionManager.kt     |   18 +-
 .../ui/screens/chat/ServerTerminalWorkspace.kt     |    4 +-
 .../ui/screens/chat/markdown/MarkdownContent.kt    |  126 ---
 .../ui/screens/server/ServerSettingsViewModel.kt   |   48 +-
 .../ui/screens/sessions/SessionListViewModel.kt    |   42 +-
 .../ocremotev2/data/api/OpenCodeApiArchiveTest.kt  |    3 +-
 .../ocremotev2/data/api/OpenCodeApiImportTest.kt   |    3 +-
 .../data/api/OpenCodeApiMessageDeleteTest.kt       |    3 +-
 .../data/api/OpenCodeApiSearchPaginationTest.kt    |    7 +-
 .../ocremotev2/data/api/OpenCodeApiTest.kt         |    6 +-
 .../ocremotev2/data/api/OpenCodeApiVcsTest.kt      |    6 +-
 .../data/repository/AgentRepositoryImplTest.kt     |    8 +-
 .../data/repository/ChatRepositoryImplTest.kt      |   22 +-
 .../repository/EventDispatcherIntegrationTest.kt   |    6 +-
 .../data/repository/EventDispatcherTest.kt         |    3 +
 .../data/repository/FileRepositoryImplTest.kt      |    4 +-
 .../data/repository/SessionRepositoryImplTest.kt   |   20 +-
 .../data/repository/TerminalRepositoryImplTest.kt  |    4 +-
 .../data/repository/VcsRepositoryImplTest.kt       |    4 +-
 .../ui/screens/chat/ChatViewModelDeleteTest.kt     |    6 +-
 .../ui/screens/chat/ChatViewModelPermissionTest.kt |    6 +-
 .../ui/screens/chat/ChatViewModelQueuedTest.kt     |    6 +-
 .../sessions/SessionListViewModelPaginationTest.kt |   15 +-
 .../sessions/SessionListViewModelSearchTest.kt     |   15 +-
 docs/superpowers/reports/phase1-block1-report.md   |  105 ++
 53 files changed, 2222 insertions(+), 1401 deletions(-)

--- KEY FILES DIFF (EventDispatcher + ApiModule + 1 consumer sample) ---
diff --git a/app/src/main/kotlin/dev/leonardo/ocremotev2/di/ApiModule.kt b/app/src/main/kotlin/dev/leonardo/ocremotev2/di/ApiModule.kt
new file mode 100644
index 00000000..452b383b
--- /dev/null
+++ b/app/src/main/kotlin/dev/leonardo/ocremotev2/di/ApiModule.kt
@@ -0,0 +1,47 @@
+package dev.leonardo.ocremotev2.di
+
+import dagger.Binds
+import dagger.Module
+import dagger.hilt.InstallIn
+import dagger.hilt.components.SingletonComponent
+import dev.leonardo.ocremotev2.data.api.file.FileApi
+import dev.leonardo.ocremotev2.data.api.file.FileApiImpl
+import dev.leonardo.ocremotev2.data.api.message.MessageApi
+import dev.leonardo.ocremotev2.data.api.message.MessageApiImpl
+import dev.leonardo.ocremotev2.data.api.provider.ProviderApi
+import dev.leonardo.ocremotev2.data.api.provider.ProviderApiImpl
+import dev.leonardo.ocremotev2.data.api.session.SessionApi
+import dev.leonardo.ocremotev2.data.api.session.SessionApiImpl
+import dev.leonardo.ocremotev2.data.api.system.SystemApi
+import dev.leonardo.ocremotev2.data.api.system.SystemApiImpl
+import dev.leonardo.ocremotev2.data.api.terminal.TerminalApi
+import dev.leonardo.ocremotev2.data.api.terminal.TerminalApiImpl
+
+/**
+ * Hilt bindings for the 6 domain API interfaces.
+ *
+ * Each `*ApiImpl` is `@Singleton` + `@Inject constructor`, so the bindings here are
+ * unscoped aliases — the scope lives on the implementation, matching [DomainModule].
+ */
+@Module
+@InstallIn(SingletonComponent::class)
+abstract class ApiModule {
+
+    @Binds
+    abstract fun bindSessionApi(impl: SessionApiImpl): SessionApi
+
+    @Binds
+    abstract fun bindMessageApi(impl: MessageApiImpl): MessageApi
+
+    @Binds
+    abstract fun bindTerminalApi(impl: TerminalApiImpl): TerminalApi
+
+    @Binds
+    abstract fun bindProviderApi(impl: ProviderApiImpl): ProviderApi
+
+    @Binds
+    abstract fun bindFileApi(impl: FileApiImpl): FileApi
+
+    @Binds
+    abstract fun bindSystemApi(impl: SystemApiImpl): SystemApi
+}
diff --git a/app/src/main/kotlin/dev/leonardo/ocremotev2/di/NetworkModule.kt b/app/src/main/kotlin/dev/leonardo/ocremotev2/di/NetworkModule.kt
index a3ea28e3..243e174b 100644
--- a/app/src/main/kotlin/dev/leonardo/ocremotev2/di/NetworkModule.kt
+++ b/app/src/main/kotlin/dev/leonardo/ocremotev2/di/NetworkModule.kt
@@ -70,7 +70,7 @@ object NetworkModule {
             }
         }
         
-        // Default headers will be set per-request in OpenCodeApi
+        // Default headers will be set per-request in domain Api implementations
     }
     
     @Provides

--- EventDispatcher.kt DIFF ---
diff --git a/app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/EventDispatcher.kt b/app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/EventDispatcher.kt
index b84e818d..7765423e 100644
--- a/app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/EventDispatcher.kt
+++ b/app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/EventDispatcher.kt
@@ -15,6 +15,7 @@ import dev.leonardo.ocremotev2.domain.model.SseEvent
 import kotlinx.coroutines.flow.StateFlow
 import javax.inject.Inject
 import javax.inject.Singleton
+import kotlin.reflect.KClass
 
 private const val TAG = "EventDispatcher"
 
@@ -30,6 +31,9 @@ private const val TAG = "EventDispatcher"
 class EventDispatcher @Inject constructor(
     private val sessionHandler: SessionEventHandler,
     private val messageHandler: MessageEventHandler,
+    private val messagePartHandler: MessagePartHandler,
+    private val messageUpdatedHandler: MessageUpdatedHandler,
+    private val messageRemovedHandler: MessageRemovedHandler,
     private val permissionHandler: PermissionEventHandler,
     private val questionHandler: QuestionEventHandler,
     private val miscHandler: MiscEventHandler,
@@ -46,6 +50,74 @@ class EventDispatcher @Inject constructor(
         }
     }
 
+    // ============ Event Handler Registry (Open/Closed Principle) ============
+    // Maps each SseEvent subclass to its single responsible handler.
+    // To support a new event domain: add a bind() call below. processEvent itself
+    // never changes — it just looks up this map. This replaces the previous
+    // broadcast model where every event was sent to all 6 handlers and each
+    // handler filtered internally via its own `when` block.
+    private val registry: Map<KClass<out SseEvent>, SseEventHandler> = buildRegistry()
+
+    private fun buildRegistry(): Map<KClass<out SseEvent>, SseEventHandler> {
+        val map = mutableMapOf<KClass<out SseEvent>, SseEventHandler>()
+        fun bind(handler: SseEventHandler, vararg events: KClass<out SseEvent>) {
+            for (e in events) map[e] = handler
+        }
+        // Session lifecycle + server connection events → SessionEventHandler
+        bind(
+            sessionHandler,
+            SseEvent.ServerConnected::class, SseEvent.ServerHeartbeat::class,
+            SseEvent.ServerInstanceDisposed::class,
+            SseEvent.SessionCreated::class, SseEvent.SessionUpdated::class,
+            SseEvent.SessionDeleted::class, SseEvent.SessionStatus::class,
+            SseEvent.SessionIdle::class, SseEvent.SessionError::class,
+            SseEvent.SessionDiff::class, SseEvent.SessionCompacted::class,
+            SseEvent.VcsBranchUpdated::class, SseEvent.ProjectUpdated::class
+        )
+        // Messages → per-sub-event handlers. They share the MessageEventHandler
+        // state store (injected) but are registered independently so each
+        // message event type routes to its focused handler.
+        bind(
+            messageUpdatedHandler,
+            SseEvent.MessageUpdated::class
+        )
+        bind(
+            messageRemovedHandler,
+            SseEvent.MessageRemoved::class
+        )
+        bind(
+            messagePartHandler,
+            SseEvent.MessagePartUpdated::class, SseEvent.MessagePartDelta::class,
+            SseEvent.MessagePartRemoved::class
+        )
+        // Permission → PermissionEventHandler
+        bind(
+            permissionHandler,
+            SseEvent.PermissionAsked::class, SseEvent.PermissionReplied::class
+        )
+        // Question → QuestionEventHandler
+        bind(
+            questionHandler,
+            SseEvent.QuestionAsked::class, SseEvent.QuestionReplied::class,
+            SseEvent.QuestionRejected::class
+        )
+        // Misc (todo, command, pty, workspace, file, vcs, install, lsp) → MiscEventHandler
+        bind(
+            miscHandler,
+            SseEvent.TodoUpdated::class, SseEvent.CommandExecuted::class,
+            SseEvent.PtyCreated::class, SseEvent.PtyUpdated::class, SseEvent.PtyDeleted::class,
+            SseEvent.WorkspaceReady::class, SseEvent.WorkspaceFailed::class,
+            SseEvent.FileEdited::class, SseEvent.McpToolsChanged::class,
+            SseEvent.FileWatcherUpdated::class,
+            SseEvent.InstallationUpdated::class, SseEvent.InstallationUpdateAvailable::class,
+            SseEvent.WorktreeReady::class, SseEvent.WorktreeFailed::class,
+            SseEvent.LspUpdated::class
+        )
+        // SessionNext → SessionNextEventHandler
+        bind(sessionNextHandler, SseEvent.SessionNext::class)
+        return map
+    }
+
     // ============ Public State (read-only) ============
 
     val serverSessions: StateFlow<Map<String, Set<String>>> get() = sessionHandler.serverSessions
@@ -80,12 +152,15 @@ class EventDispatcher @Inject constructor(
      * - CommandExecuted: resets session status to Idle
      */
     fun processEvent(event: SseEvent, serverId: String) {
-        sessionHandler.handle(event, serverId)
-        messageHandler.handle(event, serverId)
-        permissionHandler.handle(event, serverId)
-        questionHandler.handle(event, serverId)
-        miscHandler.handle(event, serverId)
-        sessionNextHandler.handle(event, serverId)
+        // Registry dispatch: route event to its single registered handler (O(1) lookup).
+        // Replaces the previous broadcast model where every event was sent to all 6
+        // handlers and each filtered internally via its own `when` block.
+        val handler = registry[event::class]
+        if (handler != null) {
+            handler.handle(event, serverId)
+        } else if (BuildConfig.DEBUG) {
+            Log.w(TAG, "No handler registered for ${event::class.simpleName}")
+        }
         forwardToStatusManager(event)
 
         // Cross-handler: SessionDeleted cascades cleanup to other handlers

--- ChatRepositoryImpl.kt DIFF (consumer sample) ---
diff --git a/app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/ChatRepositoryImpl.kt b/app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/ChatRepositoryImpl.kt
index 5adbfc7b..970fdfff 100644
--- a/app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/ChatRepositoryImpl.kt
+++ b/app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/ChatRepositoryImpl.kt
@@ -1,6 +1,9 @@
 ﻿package dev.leonardo.ocremotev2.data.repository
 
-import dev.leonardo.ocremotev2.data.api.OpenCodeApi
+import dev.leonardo.ocremotev2.data.api.message.MessageApi
+import dev.leonardo.ocremotev2.data.api.provider.ProviderApi
+import dev.leonardo.ocremotev2.data.api.session.SessionApi
+import dev.leonardo.ocremotev2.data.api.terminal.TerminalApi
 import dev.leonardo.ocremotev2.domain.model.ServerConnection
 import dev.leonardo.ocremotev2.data.dto.common.ModelSelection as DataModelSelection
 import dev.leonardo.ocremotev2.data.dto.request.PromptPart as DataPromptPart
@@ -31,14 +34,17 @@ import javax.inject.Singleton
 
 /**
  * Implementation of [ChatRepository].
- * Bridges domain interface with EventDispatcher (state) and OpenCodeApi (network).
+ * Bridges domain interface with EventDispatcher (state) and domain APIs (network).
  *
  * Phase 3: compiled but not yet wired to UseCases. Phase 4 will migrate
- * ViewModel/OpenCodeApi direct calls to go through this repository.
+ * ViewModel direct calls to go through this repository.
  */
 @Singleton
 class ChatRepositoryImpl @Inject constructor(
-    private val api: OpenCodeApi,
+    private val messageApi: MessageApi,
+    private val sessionApi: SessionApi,
+    private val terminalApi: TerminalApi,
+    private val providerApi: ProviderApi,
     private val eventDispatcher: EventDispatcher,
     private val serverRepo: ServerDataStore,
     private val permissionAutoApprover: PermissionAutoApprover
@@ -119,7 +125,7 @@ class ChatRepositoryImpl @Inject constructor(
     override suspend fun sendMessage(sessionId: String, parts: List<Part>): Result<Message> = runCatching {
         val conn = resolveConnectionForSession(sessionId)
         val promptParts = parts.map { it.toDataPromptPart() }
-        api.promptAsync(conn, sessionId, promptParts)
+        messageApi.promptAsync(conn, sessionId, promptParts)
         // The actual message arrives via SSE — return a lightweight placeholder.
         // Callers should observe [getMessagesFlow] for the real Message.
         Message.User(
@@ -133,14 +139,14 @@ class ChatRepositoryImpl @Inject constructor(
         val sessionId = findSessionForPermission(permissionId)
             ?: throw IllegalStateException("Session not found for permission $permissionId")
         val conn = resolveConnectionForSession(sessionId)
-        api.replyToPermission(conn, permissionId, reply)
+        messageApi.replyToPermission(conn, permissionId, reply)
     }
 
     override suspend fun replyQuestion(questionId: String, answer: String): Result<Boolean> = runCatching {
         val sessionId = findSessionForQuestion(questionId)
             ?: throw IllegalStateException("Session not found for question $questionId")
         val conn = resolveConnectionForSession(sessionId)
-        api.replyToQuestion(conn, questionId, listOf(listOf(answer)))
+        messageApi.replyToQuestion(conn, questionId, listOf(listOf(answer)))
     }
 
     override suspend fun promptAsync(
@@ -153,17 +159,17 @@ class ChatRepositoryImpl @Inject constructor(
         directory: String?
     ): Result<Unit> = runCatching {
         val conn = resolveConnection(serverId)
-        api.promptAsync(conn, sessionId, parts.map { it.toData() }, model?.toData(), agent, variant, directory)
+        messageApi.promptAsync(conn, sessionId, parts.map { it.toData() }, model?.toData(), agent, variant, directory)
     }
 
     override suspend fun revertSession(serverId: String, sessionId: String, messageId: String): Result<Unit> = runCatching {
         val conn = resolveConnection(serverId)
-        api.revertSession(conn, sessionId, messageId)
+        sessionApi.revertSession(conn, sessionId, messageId)
     }
 
     override suspend fun unrevertSession(serverId: String, sessionId: String): Result<Unit> = runCatching {
         val conn = resolveConnection(serverId)
-        api.unrevertSession(conn, sessionId)
+        sessionApi.unrevertSession(conn, sessionId)
     }
 
     override suspend fun respondPermission(
@@ -173,26 +179,26 @@ class ChatRepositoryImpl @Inject constructor(
         directory: String?
     ): Result<Boolean> = runCatching {
         val conn = resolveConnection(serverId)
-        api.replyToPermission(conn, permissionId, reply, directory = directory)
+        messageApi.replyToPermission(conn, permissionId, reply, directory = directory)
     }
 
     override suspend fun selectModel(serverId: String, providerId: String, modelId: String): Result<Unit> = runCatching {
         val conn = resolveConnection(serverId)
-        // TODO: Phase 4 — verify if OpenCodeApi has a dedicated updateModel endpoint
+        // TODO: Phase 4 — verify if there is a dedicated updateModel endpoint
         // For now, use config patch as fallback
-        api.updateConfig(conn, dev.leonardo.ocremotev2.data.dto.request.ServerConfigPatch())
+        providerApi.updateConfig(conn, dev.leonardo.ocremotev2.data.dto.request.ServerConfigPatch())
     }
 
     // ============ Pending Queries ============
 
     override suspend fun listPendingPermissions(serverId: String, directory: String?): Result<List<PermissionState>> = runCatching {
         val conn = resolveConnection(serverId)
-        api.listPendingPermissions(conn, directory).map { it.toDomainPermissionState() }
+        messageApi.listPendingPermissions(conn, directory).map { it.toDomainPermissionState() }
     }
 
     override suspend fun listPendingQuestions(serverId: String, directory: String?): Result<List<QuestionState>> = runCatching {
         val conn = resolveConnection(serverId)
-        api.listPendingQuestions(conn, directory).map { it.toDomainQuestionState() }
+        messageApi.listPendingQuestions(conn, directory).map { it.toDomainQuestionState() }
     }
 
     override suspend fun replyToQuestion(
@@ -202,7 +208,7 @@ class ChatRepositoryImpl @Inject constructor(
         directory: String?
     ): Result<Boolean> = runCatching {
         val conn = resolveConnection(serverId)
-        api.replyToQuestion(conn, requestId, answers, directory)
+        messageApi.replyToQuestion(conn, requestId, answers, directory)
     }
 
     override suspend fun rejectQuestion(
@@ -211,7 +217,7 @@ class ChatRepositoryImpl @Inject constructor(
         directory: String?
     ): Result<Boolean> = runCatching {
         val conn = resolveConnection(serverId)
-        api.rejectQuestion(conn, requestId, directory)
+        messageApi.rejectQuestion(conn, requestId, directory)
     }
 
     // ============ Undo/Redo ============
@@ -228,7 +234,7 @@ class ChatRepositoryImpl @Inject constructor(
                 throw UnsupportedOperationException("Use revertSession(serverId, sessionId, messageId) for undo")
             }
             "redo" -> {
-                api.unrevertSession(conn, sessionId)
+                sessionApi.unrevertSession(conn, sessionId)
             }
             else -> throw IllegalArgumentException("Invalid action: $action. Must be 'undo' or 'redo'")
         }
@@ -244,7 +250,7 @@ class ChatRepositoryImpl @Inject constructor(
         directory: String?
     ): Result<Boolean> = runCatching {
         val conn = resolveConnection(serverId)
-        api.executeCommand(conn, sessionId, command, arguments, directory)
+        sessionApi.executeCommand(conn, sessionId, command, arguments, directory)
     }
 
     override suspend fun runShellCommand(
@@ -260,7 +266,7 @@ class ChatRepositoryImpl @Inject constructor(
         val model = if (providerId != null && modelId != null) {
             DataModelSelection(providerId = providerId, modelId = modelId)
         } else null
-        api.runShellCommand(conn, sessionId, command, agent, model, directory)
+        terminalApi.runShellCommand(conn, sessionId, command, agent, model, directory)
     }
 
     override fun getToolExpandedStates(): Map<String, Boolean> = toolExpandedStates
