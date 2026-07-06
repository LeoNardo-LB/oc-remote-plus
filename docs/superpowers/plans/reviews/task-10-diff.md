## Task 10 Diff: 49411fff..668384e3
### Commits
668384e3 refactor(chat): streaming UI reads activityFlow (A1, FSM+part.time combined)
### Stat
 .../leonardo/ocremotev2/ui/screens/chat/ChatScreen.kt  |  2 ++
 .../ocremotev2/ui/screens/chat/ChatViewModel.kt        |  7 +++++++
 .../ui/screens/chat/components/ChatMessageList.kt      | 18 +++++++++++++-----
 .../ui/screens/chat/components/PartContent.kt          |  3 ++-
 .../ui/screens/chat/util/ChatCompositionLocals.kt      |  8 ++++++++
 5 files changed, 32 insertions(+), 6 deletions(-)
### Full Diff
diff --git a/app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatScreen.kt b/app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatScreen.kt
index 0e8cc27c..e4ba5666 100644
--- a/app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatScreen.kt
+++ b/app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatScreen.kt
@@ -190,16 +190,17 @@ import dev.leonardo.ocremotev2.ui.screens.chat.util.codeHorizontalScroll
 import dev.leonardo.ocremotev2.ui.theme.ChatDensity
 import dev.leonardo.ocremotev2.ui.theme.LocalChatDensity
 import dev.leonardo.ocremotev2.ui.screens.chat.util.LocalCollapseTools
 import dev.leonardo.ocremotev2.ui.screens.chat.util.LocalExpandReasoning
 import dev.leonardo.ocremotev2.ui.screens.chat.util.LocalShowTurnDividers
 import dev.leonardo.ocremotev2.ui.screens.chat.util.LocalHapticFeedbackEnabled
 import dev.leonardo.ocremotev2.ui.screens.chat.util.LocalImageSaveRequest
 import dev.leonardo.ocremotev2.ui.screens.chat.util.LocalSessionDiffs
+import dev.leonardo.ocremotev2.ui.screens.chat.util.LocalSessionStreaming
 import dev.leonardo.ocremotev2.ui.screens.chat.util.LocalToolExpandedStates
 import dev.leonardo.ocremotev2.ui.screens.chat.util.LocalOnToggleToolExpanded
 import dev.leonardo.ocremotev2.ui.screens.chat.util.LocalToolCardResolver
 import dev.leonardo.ocremotev2.ui.screens.chat.util.ImageAttachment
 import dev.leonardo.ocremotev2.ui.screens.chat.util.PreparedAttachment
 import dev.leonardo.ocremotev2.ui.screens.chat.util.decodeDataUrlBytes
 import dev.leonardo.ocremotev2.ui.screens.chat.util.decodePartFileBytes
 import dev.leonardo.ocremotev2.ui.screens.chat.util.imageThumbnailModel
@@ -516,16 +517,17 @@ fun ChatScreen(
         LocalHapticFeedbackEnabled provides hapticEnabled,
         LocalImageSaveRequest provides attachmentHandler.requestSaveImage,
         LocalToolExpandedStates provides messageState.toolExpandedStates,
         LocalOnToggleToolExpanded provides onToggleToolExpandedLambda,
         LocalToolCardResolver provides viewModel.toolCardResolver,
         LocalSessionDiffs provides sessionDiffsMap,
         LocalUriHandler provides linkUriHandler,
         LocalOnViewTool provides onViewToolLambda,
+        LocalSessionStreaming provides sessionMeta.isStreaming,
     ) {
     Scaffold(
         snackbarHost = {
             SnackbarHost(snackbarHostState) { data ->
                 Snackbar(
                     modifier = Modifier.padding(horizontal = SpacingTokens.LG.dp),
                     containerColor = MaterialTheme.colorScheme.surfaceVariant,
                     contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
diff --git a/app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModel.kt b/app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModel.kt
index 1351b617..bcfa916d 100644
--- a/app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModel.kt
+++ b/app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModel.kt
@@ -73,16 +73,18 @@ data class SessionMetaState(
     val serverName: String = "",
     val sessionStatus: SessionStatus = SessionStatus.Idle,
     val revert: Session.Revert? = null,
     val sessionParentId: String? = null,
     val sessionAgent: String? = null,
     val currentAgentName: String? = null,
     val currentModelId: String? = null,
     val shareUrl: String? = null,
+    /** True when FSM activity for this session is Streaming (gates reasoning timer + streamingMsgId). */
+    val isStreaming: Boolean = false,
 )
 
 /**
  * Split state: user interaction state.
  * Changes on loading/sending/error and pending permissions/questions.
  */
 @Immutable
 data class InteractionState(
@@ -539,40 +541,45 @@ class ChatViewModel @Inject constructor(
      * the single source of truth for busy/idle/activity state.
      */
     val sessionMetaState: StateFlow<SessionMetaState> = combine(
         sessionLifecycle.sessionIdFlow,
         sessionRepository.getSessionsFlow(serverId),
         sessionStateService.statusFlow,
         sessionRepository.getCurrentAgentFlow(serverId),
         sessionRepository.getCurrentModelFlow(serverId),
+        sessionStateService.activityFlow,
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
+        @Suppress("UNCHECKED_CAST")
+        val activities = args[5] as Map<String, SessionActivity?>
 
         val session = allSessions.find { it.id == sid }
         val sessionStatus = statuses[sid] ?: SessionStatus.Idle
+        val isStreaming = activities[sid] is SessionActivity.Streaming
 
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
+            isStreaming = isStreaming,
         )
     }.stateIn(
         viewModelScope,
         SharingStarted.WhileSubscribed(5000),
         SessionMetaState()
     )
 
     // interactionState — migrated to MessageDataDelegate (Phase 3 Task 5 — B cluster).
diff --git a/app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/components/ChatMessageList.kt b/app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/components/ChatMessageList.kt
index e5ca9659..4af68abc 100644
--- a/app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/components/ChatMessageList.kt
+++ b/app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/components/ChatMessageList.kt
@@ -137,17 +137,17 @@ fun ChatMessageList(
             )
         }
     }
 
     val streamingMsgId = remember(rawMessages) {
         rawMessages.lastOrNull {
             it.isAssistant && it.message.time.completed == null
         }?.message?.id
-    }
+    }?.takeIf { sessionMeta.isStreaming }
 
     // Key on streamingMsgId so state resets when streaming message changes (new message
     // or completion). This is simpler and more correct than heightMap + session-scope clear.
     val compensateState = remember(streamingMsgId) { CompensateState() }
     val toolCompensateState = remember(streamingMsgId) { CompensateState() }
 
     // Track whether user has scrolled away from bottom.
     // Key is ONLY isScrollInProgress — NOT isAtBottom — so SSE layout changes
@@ -229,23 +229,31 @@ fun ChatMessageList(
 
             // Auto-pagination: trigger load when user is within 8 items of the top.
             // Replaces PullToRefreshBox — seamless, no manual gesture needed.
             val shouldPaginate by remember {
                 derivedStateOf {
                     val layoutInfo = listState.layoutInfo
                     val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                     val total = layoutInfo.totalItemsCount
-                    !messageState.isLoadingOlder &&
-                    messageState.hasOlderMessages &&
-                    total - lastVisible <= 8
+                    val dist = total - lastVisible
+                    val result = !messageState.isLoadingOlder &&
+                        messageState.hasOlderMessages &&
+                        dist <= 8
+                    if (dist <= 12) {
+                        android.util.Log.d("AutoPag", "last=$lastVisible total=$total dist=$dist hasOlder=${messageState.hasOlderMessages} load=${messageState.isLoadingOlder} → $result")
+                    }
+                    result
                 }
             }
             LaunchedEffect(shouldPaginate) {
-                if (shouldPaginate) viewModel.loadOlderMessages()
+                if (shouldPaginate) {
+                    android.util.Log.d("AutoPag", "→ loadOlderMessages()")
+                    viewModel.loadOlderMessages()
+                }
             }
 
                 LazyColumn(
                     state = listState,
                     flingBehavior = safeFlingBehavior,
                     modifier = Modifier.fillMaxSize()
                         .pointerInput(Unit) { detectTapGestures(onTap = { keyboardController?.hide() }) },
                     contentPadding = PaddingValues(
diff --git a/app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/components/PartContent.kt b/app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/components/PartContent.kt
index 100f06c0..9d0d6018 100644
--- a/app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/components/PartContent.kt
+++ b/app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/components/PartContent.kt
@@ -73,16 +73,17 @@ import dev.leonardo.ocremotev2.ui.screens.chat.util.LocalOnViewTool
 import dev.leonardo.ocremotev2.ui.screens.viewer.FileViewerSource
 import dev.leonardo.ocremotev2.ui.screens.chat.tools.cards.PatchCard
 import dev.leonardo.ocremotev2.ui.screens.chat.tools.cards.TodoListCard
 import dev.leonardo.ocremotev2.ui.screens.chat.util.LocalCollapseTools
 import dev.leonardo.ocremotev2.ui.screens.chat.util.LocalToolCardResolver
 import dev.leonardo.ocremotev2.ui.screens.chat.util.LocalExpandReasoning
 import dev.leonardo.ocremotev2.ui.screens.chat.util.LocalHapticFeedbackEnabled
 import dev.leonardo.ocremotev2.ui.screens.chat.util.LocalOnToggleToolExpanded
+import dev.leonardo.ocremotev2.ui.screens.chat.util.LocalSessionStreaming
 import dev.leonardo.ocremotev2.ui.screens.chat.util.LocalToolExpandedStates
 import kotlinx.serialization.json.contentOrNull
 import kotlinx.serialization.json.jsonPrimitive
 
 @Composable
 internal fun PartContent(
     part: Part,
     textColor: Color,
@@ -109,17 +110,17 @@ internal fun PartContent(
                             immediate = !isUser
                         )
                     }
                 }
             }
         }
         is Part.Reasoning -> {
             if (part.text.isNotBlank()) {
-                val isStreaming = part.time?.end == null
+                val isStreaming = LocalSessionStreaming.current && (part.time?.end == null)
                 val startTimeMs = part.time?.start
                 val reasoningDuration = part.time?.let { t ->
                     t.end?.let { end -> end - t.start }
                 }
                 val toolExpandedStates = LocalToolExpandedStates.current
                 val onToggleToolExpanded = LocalOnToggleToolExpanded.current
                 val expandReasoningDefault = LocalExpandReasoning.current
                 ReasoningBlock(
diff --git a/app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/util/ChatCompositionLocals.kt b/app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/util/ChatCompositionLocals.kt
index 6c2825d6..38515a6e 100644
--- a/app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/util/ChatCompositionLocals.kt
+++ b/app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/util/ChatCompositionLocals.kt
@@ -1,11 +1,12 @@
 ﻿package dev.leonardo.ocremotev2.ui.screens.chat.util
 
 import androidx.compose.runtime.compositionLocalOf
+import androidx.compose.runtime.staticCompositionLocalOf
 import dev.leonardo.ocremotev2.domain.model.FileDiff
 import dev.leonardo.ocremotev2.ui.screens.chat.tools.DefaultToolCardResolver
 import dev.leonardo.ocremotev2.ui.screens.chat.tools.ToolCardResolver
 
 // ============ Chat Settings via CompositionLocal ============
 
 /** Whether tool cards are collapsed by default. */
 val LocalCollapseTools = compositionLocalOf { false }
@@ -14,16 +15,23 @@ val LocalCollapseTools = compositionLocalOf { false }
 val LocalExpandReasoning = compositionLocalOf { false }
 
 /** Whether to show dividers between messages in the same turn. */
 val LocalShowTurnDividers = compositionLocalOf { true }
 
 /** Whether haptic feedback is enabled. */
 val LocalHapticFeedbackEnabled = compositionLocalOf { true }
 
+/**
+ * Whether the current session is actively streaming (FSM activity = Streaming).
+ * Authoritative gate for the reasoning timer; combined with per-part `time.end == null`
+ * so only the current reasoning part shows the timer (approach B).
+ */
+val LocalSessionStreaming = staticCompositionLocalOf { false }
+
 /** Image save request callback available to image preview composables. */
 val LocalImageSaveRequest = compositionLocalOf<(ByteArray, String, String?) -> Unit> { { _, _, _ -> } }
 
 /** Persisted expand/collapse state for tool cards, keyed by Part.Tool.id or Part.Patch.id. */
 val LocalToolExpandedStates = compositionLocalOf<Map<String, Boolean>> { emptyMap() }
 
 /** Callback to toggle a tool card's expanded state by its part id. */
 val LocalOnToggleToolExpanded = compositionLocalOf<(String, Boolean) -> Unit> { { _, _ -> } }
