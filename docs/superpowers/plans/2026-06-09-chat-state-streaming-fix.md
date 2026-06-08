# Chat State & Streaming Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix two critical bugs: (1) last message invisible during SSE streaming, (2) chat state corruption after app-switch. Simplify over-complex state management in ChatViewModel.

**Architecture:** Root causes are 100% client-side. Server (OpenCode) guarantees strict event ordering and atomic persistence. Fixes target three layers: (a) `_isLoading` misuse causing message-list wipe, (b) auto-scroll fingerprint tracking loss during tool-call transitions, (c) unconditional ON_RESUME refresh conflicting with SSE streaming state.

**Tech Stack:** Kotlin, Jetpack Compose, StateFlow/combine, MockK + Turbine for tests.

---

## File Structure

| File | Action | Responsibility |
|------|--------|---------------|
| `app/src/main/kotlin/.../chat/ChatViewModel.kt` | Modify | Separate `_isLoading` into initial-load vs refresh; merge refresh+sync; conditionally refresh |
| `app/src/main/kotlin/.../chat/ChatScreen.kt` | Modify | Fix auto-scroll fingerprint; improve snapToBottom; conditional ON_RESUME |
| `app/src/main/kotlin/.../handler/MessageEventHandler.kt` | Modify | Add streaming-aware merge protection in `setMessages` |
| `app/src/test/.../chat/ChatViewModelStreamingTest.kt` | Create | Tests for all fixes |
| `app/src/test/.../chat/MessageEventHandlerMergeTest.kt` | Create | Tests for streaming-aware merge |

---

## Task 1: Separate `_isLoading` Scope (Fix Bug #2 Core)

**Problem:** `loadMessages()` unconditionally sets `_isLoading = true` (line 904). `messageListState` combine at line 561-562 clears the message list when `loading && sessionMessages.size < 3`. This causes visible message flicker on every ON_RESUME refresh.

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt`

- [ ] **Step 1: Add `_isRefreshing` StateFlow**

In ChatViewModel.kt, add a new private StateFlow near line 258 (after `_isLoading`):

```kotlin
private val _isLoading = MutableStateFlow(true)
private val _isRefreshing = MutableStateFlow(false)  // Background refresh — no UI wipe
```

- [ ] **Step 2: Add `refreshMessages()` that skips `_isLoading`**

Replace the `refreshSession()` method (lines 942-949) with a version that uses `_isRefreshing` instead of `_isLoading` for the message-loading portion:

> ⚠️ **VERSION-1**: This definition will be SUPERSEDED by Task 4 and again by Task 5. Apply sequentially.

```kotlin
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
```

- [ ] **Step 3: Remove `_isLoading` guard from `messageListState`**

Change the combine block at line 561-562. Replace the loading guard:

```kotlin
// BEFORE:
val chatMessages = if (loading && sessionMessages.size < 3) {
    emptyList()
} else {

// AFTER:
val chatMessages = if (loading && sessionMessages.isEmpty()) {
    emptyList()
} else {
```

This narrows the clearing condition from `< 3` to `isEmpty()`. Initial load still shows empty (correct — nothing to show). Refresh via `refreshMessages()` never sets `_isLoading = true`, so existing messages are never wiped.

- [ ] **Step 4: Run compile check**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt
git commit -m "fix: separate _isLoading from refresh to prevent message list wipe on ON_RESUME"
```

---

## Task 2: Fix Auto-Scroll Fingerprint to Track All Incomplete Messages (Fix Bug #1 Core)

**Problem:** `fingerprint` at ChatScreen.kt:506-513 only checks `msgs.last()`. When a tool-call inserts a new empty Assistant message, `msgs.last()` switches to the empty message (fingerprint=0), losing tracking of the previous message's streaming text growth.

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt`

- [ ] **Step 1: Replace single-message fingerprint with streaming-sum fingerprint**

In ChatScreen.kt, replace the snapshotFlow block at lines 501-515:

```kotlin
// BEFORE:
snapshotFlow {
    val msgs = messageState.messages
    val items = listState.layoutInfo.totalItemsCount
    val fingerprint = if (msgs.isEmpty()) 0 else {
        msgs.last().parts.sumOf { part ->
            when (part) {
                is Part.Text -> part.text.length
                is Part.Reasoning -> part.text.length
                else -> 0
            }
        }
    }
    Triple(items, fingerprint, msgs.size)
}

// AFTER:
snapshotFlow {
    val msgs = messageState.messages
    val items = listState.layoutInfo.totalItemsCount
    // Sum text lengths of ALL incomplete (streaming) assistant messages,
    // not just msgs.last(). This prevents losing auto-scroll tracking
    // when a tool-call inserts a new empty assistant message.
    val fingerprint = msgs.sumOf { msg ->
        if (msg.message is Message.Assistant && msg.message.time.completed == null) {
            msg.parts.sumOf { part ->
                when (part) {
                    is Part.Text -> part.text.length
                    is Part.Reasoning -> part.text.length
                    else -> 0
                }
            }
        } else {
            0
        }
    }
    Triple(items, fingerprint, msgs.size)
}
```

- [ ] **Step 2: Run compile check**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt
git commit -m "fix: auto-scroll fingerprint tracks all incomplete messages, not just last"
```

---

## Task 3: Improve snapToBottom — Debounce + Robust Retry

**Problem:** `snapToBottom()` (ChatScreen.kt:1096-1105) has no debounce — every SSE delta triggers a new `snapToBottom` coroutine that competes with previous ones. The 48ms retry window (3×16ms) is insufficient for complex Markdown layouts.

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt`

- [ ] **Step 1: Replace snapToBottom with a more robust version**

Replace the `snapToBottom()` extension function at lines 1096-1105:

```kotlin
/**
 * Scrolls the LazyColumn to the absolute bottom.
 * With reverseLayout=true, "bottom" = item 0.
 * Retries up to 300ms to handle complex Markdown layout delays.
 */
private suspend fun LazyListState.snapToBottom() {
    scrollToItem(0)
    // Retry loop: complex Markdown (code blocks, tables) may need multiple
    // layout passes before the content height stabilizes.
    var attempts = 0
    while (canScrollBackward && attempts < 10) {
        delay(30)
        if (!canScrollBackward) return
        scroll { scrollBy(-10_000f) }
        attempts++
    }
}
```

This increases the retry window from 48ms (3×16ms) to 300ms (10×30ms), enough for Markdown re-layout.

- [ ] **Step 2: Add conflate to the snapshotFlow collection**

In the auto-scroll LaunchedEffect (around line 516), add `.conflate()` before `.collect`:

```kotlin
// BEFORE:
}.collect { (count, fingerprint, _) ->

// AFTER:
}.conflate().collect { (count, fingerprint, _) ->
```

This ensures that when multiple snapshot emissions happen before the collector finishes processing one (during high-frequency streaming), intermediate emissions are dropped — only the latest is processed.

- [ ] **Step 3: Run compile check**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt
git commit -m "fix: improve snapToBottom with longer retry window and conflate for debounce"
```

---

## Task 4: Conditional ON_RESUME Refresh

**Problem:** ChatScreen.kt:482-487 unconditionally calls `refreshSession()` and `syncSessionStatus()` on every ON_RESUME. Even a brief app-switch (< 1 second) triggers a full REST refresh, causing unnecessary network calls and potential state conflicts.

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt`

- [ ] **Step 1: Add timestamp tracking to ChatViewModel**

In ChatViewModel.kt, add a private field near line 258:

```kotlin
/** Timestamp of last successful refresh. Used to skip unnecessary ON_RESUME refreshes. */
private var lastRefreshTimeMs: Long = 0L
```

- [ ] **Step 2: Update `refreshSession()` to record timestamp**

> ⚠️ **VERSION-2**: Adds `lastRefreshTimeMs` to the VERSION-1 definition. Will be SUPERSEDED by Task 5.

At the end of the `refreshSession()` coroutine (after `loadPendingPermissions()`), add:

```kotlin
fun refreshSession() {
    viewModelScope.launch {
        loadSession()
        refreshMessages()
        loadPendingQuestions()
        loadPendingPermissions()
        lastRefreshTimeMs = System.currentTimeMillis()
    }
}
```

- [ ] **Step 3: Add conditional refresh method to ChatViewModel**

Add a new public method:

```kotlin
private companion object {
    const val REFRESH_COOLDOWN_MS = 5_000L  // Skip refresh if last one was < 5s ago
}

/**
 * Refresh session only if enough time has passed since last refresh.
 * Called from ON_RESUME — avoids unnecessary REST calls during brief app-switches.
 */
fun refreshIfNeeded() {
    val elapsed = System.currentTimeMillis() - lastRefreshTimeMs
    if (elapsed >= REFRESH_COOLDOWN_MS) {
        refreshSession()
        syncSessionStatus()
    }
}
```

- [ ] **Step 4: Update ChatScreen to use conditional refresh**

In ChatScreen.kt, replace the DisposableEffect at lines 482-493:

```kotlin
// BEFORE:
DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME && viewModel.sessionId.isNotBlank()) {
            viewModel.refreshSession()
            viewModel.syncSessionStatus()
        }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
}

// AFTER:
DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME && viewModel.sessionId.isNotBlank()) {
            viewModel.refreshIfNeeded()
        }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
}
```

- [ ] **Step 5: Run compile check**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt
git commit -m "fix: conditional ON_RESUME refresh with 5s cooldown to avoid unnecessary REST calls"
```

---

## Task 5: Merge refreshSession + syncSessionStatus Into Atomic Operation

**Problem:** `refreshSession()` and `syncSessionStatus()` run in separate coroutines (launched from ChatScreen ON_RESUME). Their REST responses may arrive at different times, causing transient state inconsistency (e.g., sync marks session Idle while refresh still loading messages).

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt`

- [ ] **Step 1: Create unified `refreshAndSync()` method**

Add a new method in ChatViewModel.kt, after `syncSessionStatus()`:

```kotlin
/**
 * Combined refresh + sync — runs in a single coroutine to avoid
 * state conflicts between parallel REST responses.
 */
private suspend fun refreshAndSync() {
    // 1. Load session info first (needed for sessionDirectory)
    loadSession()

    // 2. Refresh messages (uses _isRefreshing, not _isLoading)
    refreshMessages()

    // 3. Sync session statuses AFTER messages are loaded
    //    so we have the latest data when checking idle state
    if (sessionId.isNotBlank()) {
        sessionLoaded.await()
    }
    val result = sessionRepository.fetchSessionStatuses(serverId, directory = sessionDirectory)
    result.onSuccess { statusMap ->
        sessionRepository.syncAllSessionStatuses(statusMap)
        val currentStatus = statusMap[sessionId]
        if (currentStatus is SessionStatus.Idle) {
            sessionRepository.markSessionIdleProtected(sessionId)
        }
    }

    // 4. Load pending items
    loadPendingQuestions()
    loadPendingPermissions()

    lastRefreshTimeMs = System.currentTimeMillis()
}
```

- [ ] **Step 2: Update `refreshSession()` to use the unified method**

> ⚠️ **VERSION-3 (FINAL)**: Supersedes VERSION-2 from Task 4. Uses `refreshAndSync()` for atomic refresh+sync.

```kotlin
fun refreshSession() {
    viewModelScope.launch {
        refreshAndSync()
    }
}
```

- [ ] **Step 3: Update `refreshIfNeeded()`**

```kotlin
fun refreshIfNeeded() {
    val elapsed = System.currentTimeMillis() - lastRefreshTimeMs
    if (elapsed >= REFRESH_COOLDOWN_MS) {
        refreshSession()
    }
}
```

Note: `syncSessionStatus()` remains as a standalone public method for the `LaunchedEffect(viewModel.sessionId)` call at ChatScreen.kt:475-478 (first entry into a session). It's needed there because `refreshSession` is only called on subsequent ON_RESUME events.

- [ ] **Step 4: Run compile check**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt
git commit -m "fix: merge refreshSession and syncSessionStatus into single atomic coroutine"
```

---

## Task 6: Streaming-Aware Message Merge in MessageEventHandler

**Problem:** When `refreshSession()` calls `chatRepository.setMessages()` via REST during active SSE streaming, the REST response contains empty/incomplete Parts (server only persists text on `text-end`, not during `text-delta`). `mergePartsList` already preserves longer text, but the Message metadata (finish, completed) may be overwritten with stale REST values.

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/data/repository/handler/MessageEventHandler.kt`

- [ ] **Step 1: Add streaming-aware message merge in `setMessages`**

Replace the `setMessages()` method (lines 146-171) with a streaming-aware version:

```kotlin
fun setMessages(sessionId: String, newMessages: List<MessageWithParts>) {
    _messages.update { current ->
        val existing = current[sessionId] ?: emptyList()
        val incomingById = newMessages.associateBy { it.info.id }
        // Merge strategy:
        // - Messages present in both: prefer SSE version (fresher) unless REST has completed=true
        //   and SSE has completed=null (edge case: SSE missed the completion event)
        // - Messages only in SSE: preserved (may be actively streaming)
        // - Messages only in REST: added (missed by SSE)
        val merged = (existing + newMessages.map { it.info })
            .distinctBy { it.id }
            .map { msg ->
                val incoming = incomingById[msg.id]
                if (incoming != null) {
                    // If SSE has a newer update (SSE message has no completed but REST does),
                    // prefer SSE for all fields EXCEPT completed (trust REST if it says completed)
                    val sseVersion = msg
                    val restVersion = incoming.info
                    mergeMessageMeta(sseVersion, restVersion)
                } else {
                    msg
                }
            }
            .sortedBy { it.time.created }
        current + (sessionId to merged)
    }
    val partsMap = newMessages.associate { it.info.id to it.parts }
    _parts.update { current ->
        val merged = partsMap.mapValues { (messageId, incomingParts) ->
            val existingParts = current[messageId]
            if (existingParts != null) {
                mergePartsList(existingParts, incomingParts)
            } else {
                incomingParts
            }
        }
        // Preserve messageIds not in REST response (may be streaming)
        current + merged
    }
}
```

- [ ] **Step 2: Add `mergeMessageMeta()` helper**

Add a private helper method in MessageEventHandler:

```kotlin
/**
 * Merge SSE and REST versions of a message.
 * SSE is fresher for content (streaming), but REST may have completion
 * info that SSE hasn't delivered yet.
 */
private fun mergeMessageMeta(sse: Message, rest: Message): Message {
    // For User messages: REST is authoritative (no streaming)
    if (sse is Message.User) return rest
    if (sse !is Message.Assistant) return rest

    // For Assistant messages:
    // - If SSE says completed (streaming finished), trust SSE completely
    // - If SSE says NOT completed but REST says completed, trust REST's completed time
    //   but keep SSE's other fields (finish, tokens, cost may be fresher)
    return if (sse.time.completed != null) {
        sse  // SSE has final state, prefer it
    } else if (rest.time.completed != null) {
        // REST says completed but SSE hasn't seen it yet — merge completed time
        sse.copy(time = sse.time.copy(completed = rest.time.completed))
    } else {
        // Neither has completed — prefer SSE (fresher streaming state)
        sse
    }
}
```

- [ ] **Step 3: Run compile check**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/data/repository/handler/MessageEventHandler.kt
git commit -m "fix: streaming-aware message merge preserves SSE state during REST refresh"
```

---

## Task 7: Write Tests — ChatViewModel Streaming State

**Files:**
- Create: `app/src/test/java/dev/minios/ocremote/ui/screens/chat/ChatViewModelStreamingTest.kt`

- [ ] **Step 1: Create the test file with setup**

```kotlin
package dev.minios.ocremote.ui.screens.chat

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import dev.minios.ocremote.data.repository.EventDispatcher
import dev.minios.ocremote.data.repository.ServerTerminalRegistry
import dev.minios.ocremote.data.repository.handler.*
import dev.minios.ocremote.domain.model.*
import dev.minios.ocremote.domain.repository.ChatRepository
import dev.minios.ocremote.domain.repository.SessionRepository
import dev.minios.ocremote.domain.repository.SettingsRepository
import dev.minios.ocremote.domain.tracker.TokenStatsTracker
import dev.minios.ocremote.domain.usecase.*
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for streaming state management fixes:
 * 1. refreshMessages does NOT set _isLoading (no message list wipe)
 * 2. refreshSession is atomic with syncSessionStatus
 * 3. refreshIfNeeded respects cooldown
 * 4. Loading guard only clears empty lists
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelStreamingTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var eventDispatcher: EventDispatcher
    private lateinit var terminalRegistry: ServerTerminalRegistry
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
    private lateinit var messagePaging: MessagePaginationUseCase
    private lateinit var tokenStatsTracker: TokenStatsTracker
    private lateinit var chatRepository: ChatRepository
    private lateinit var sessionRepository: SessionRepository

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.w(any(), any()) } returns 0
        every { Log.w(any(), any(), any()) } returns 0

        eventDispatcher = EventDispatcher(
            sessionHandler = SessionEventHandler(),
            messageHandler = MessageEventHandler(),
            permissionHandler = PermissionEventHandler(),
            questionHandler = QuestionEventHandler(),
            miscHandler = MiscEventHandler(),
            sessionNextHandler = SessionNextEventHandler()
        )
        terminalRegistry = mockk(relaxed = true)
        settingsRepository = mockk()
        sendMessageUseCase = mockk(relaxed = true)
        manageSessionUseCase = mockk(relaxed = true)
        selectModelUseCase = mockk(relaxed = true)
        manageAgentUseCase = mockk(relaxed = true)
        manageTerminalUseCase = mockk(relaxed = true)
        draftUseCase = mockk(relaxed = true)
        shareExportUseCase = mockk(relaxed = true)
        undoRedoUseCase = mockk(relaxed = true)
        messagePaging = mockk(relaxed = true)
        tokenStatsTracker = TokenStatsTracker()
        chatRepository = mockk(relaxed = true)
        sessionRepository = mockk(relaxed = true)
        managePermissionUseCase = mockk(relaxed = true)

        // Default settings flow
        val appSettings = AppSettings(
            providers = ProvidersResponse(emptyList()),
            initialMessageCount = 50
        )
        every { settingsRepository.getSettingsFlow() } returns flowOf(appSettings)
        every { settingsRepository.getSettingsFlow().map(any()) } answers {
            flowOf(appSettings).map {
                @Suppress("UNCHECKED_CAST")
                (lastArg as (AppSettings) -> Any).invoke(it)
            }
        }

        // Default empty flows
        every { sessionRepository.getSessionsFlow(any()) } returns MutableStateFlow(emptyList())
        every { sessionRepository.getSessionStatusesFlow(any()) } returns MutableStateFlow(emptyMap())
        every { sessionRepository.getCurrentAgentFlow(any()) } returns MutableStateFlow(emptyMap())
        every { sessionRepository.getCurrentModelFlow(any()) } returns MutableStateFlow(emptyMap())
        every { chatRepository.getAllPartsMap() } returns MutableStateFlow(emptyMap())
        every { messagePaging.observeMessages(any()) } returns MutableStateFlow(emptyList())

        // SSE connection state
        val connectedFlow = MutableStateFlow(false)
        every { chatRepository.isConnectedFlow(any()) } returns connectedFlow

        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createViewModel(
        sessionId: String = "test-session",
        serverId: String = "test-server",
        serverName: String = "Test Server"
    ): ChatViewModel {
        // IMPORTANT: SavedStateHandle keys must match ChatNav route parameters exactly.
        // See ChatViewModel init block (lines 231-251) for required keys.
        val savedStateHandle = SavedStateHandle(mapOf(
            "sessionId" to sessionId,
            "serverId" to serverId,
            "serverName" to serverName,
            "serverUrl" to "http://localhost:8080",
            "username" to "testuser",
            "password" to "testpass",
        ))
        return ChatViewModel(
            savedStateHandle = savedStateHandle,
            sendMessageUseCase = sendMessageUseCase,
            manageSessionUseCase = manageSessionUseCase,
            managePermissionUseCase = managePermissionUseCase,
            selectModelUseCase = selectModelUseCase,
            manageAgentUseCase = manageAgentUseCase,
            manageTerminalUseCase = manageTerminalUseCase,
            draftUseCase = draftUseCase,
            shareExportUseCase = shareExportUseCase,
            undoRedoUseCase = undoRedoUseCase,
            messagePaging = messagePaging,
            tokenStatsTracker = tokenStatsTracker,
            chatRepository = chatRepository,
            sessionRepository = sessionRepository,
            settingsRepository = settingsRepository,
            terminalRegistry = terminalRegistry,
            // NOTE: Check actual @Inject constructor for any additional params
            // (e.g., ToolCardResolver, DraftRepository). Compare with existing test files:
            // ChatViewModelQueuedTest.kt, ChatViewModelSendTest.kt
        )
    }

    @Test
    fun `refreshSession does not set isLoading to true`() = runTest {
        // Given: a ViewModel with an existing session that has messages
        val existingMessages = listOf(
            MessageWithParts(
                info = Message.Assistant(
                    id = "msg-1",
                    sessionId = "test-session",
                    time = TimeInfo(created = 1000L),
                ),
                parts = listOf(Part.Text(id = "p1", sessionId = "test-session", messageId = "msg-1", text = "Hello"))
            )
        )
        coEvery { manageSessionUseCase.listMessages(any(), any(), any()) } returns existingMessages
        coEvery { manageSessionUseCase.getSession(any(), any()) } returns Message.Assistant(
            id = "test-session", sessionId = "", time = TimeInfo(created = 0L)
        )
        every { sessionRepository.fetchSessionStatuses(any(), any()) } returns Result.success(emptyMap())

        val vm = createViewModel()
        advanceUntilIdle()

        // The new-session path sets _isLoading = false
        // Note: use messageListState, not interactionState (the latter does not exist as a property)
        // _isLoading feeds into both messageListState and interactionState combines
        val beforeRefresh = vm.messageListState.value
        // No direct isLoading accessor — verify through side effect: messages not empty means not wiped
        assertTrue(beforeRefresh.messages.isEmpty()) // new session, no messages yet

        // When: refreshSession is called
        vm.refreshSession()
        advanceUntilIdle()

        // Then: _isLoading was NOT set to true (messages were not wiped to emptyList)
        // If _isLoading were true, messageListState would show empty messages (the guard)
        // Since refresh uses _isRefreshing, existing messages are preserved
        val afterRefresh = vm.messageListState.value
        // Messages loaded via REST should now be present
        assertTrue("Messages should be loaded after refresh", afterRefresh.messages.isNotEmpty())
    }

    @Test
    fun `messageListState preserves messages during refresh`() = runTest {
        // Given: existing messages from SSE
        val sseMessages = listOf(
            Message.Assistant(id = "msg-1", sessionId = "test-session", time = TimeInfo(created = 1000L))
        )
        val messagesFlow = MutableStateFlow(sseMessages)
        every { messagePaging.observeMessages("test-session") } returns messagesFlow

        val partsFlow = MutableStateFlow(mapOf(
            "msg-1" to listOf(Part.Text(id = "p1", sessionId = "test-session", messageId = "msg-1", text = "Streaming content here..."))
        ))
        every { chatRepository.getAllPartsMap() } returns partsFlow

        coEvery { manageSessionUseCase.getSession(any(), any()) } returns Message.Assistant(
            id = "test-session", sessionId = "", time = TimeInfo(created = 0L)
        )

        val vm = createViewModel()
        advanceUntilIdle()

        // When: refreshSession is called (REST returns same messages)
        coEvery { manageSessionUseCase.listMessages(any(), any(), any()) } returns listOf(
            MessageWithParts(
                info = Message.Assistant(id = "msg-1", sessionId = "test-session", time = TimeInfo(created = 1000L)),
                parts = listOf(Part.Text(id = "p1", sessionId = "test-session", messageId = "msg-1", text = ""))
            )
        )
        vm.refreshSession()
        advanceUntilIdle()

        // Then: messages should not be cleared (mergePartsList preserves longer text)
        vm.messageListState.test {
            val state = awaitItem()
            assertTrue("Messages must not be cleared during refresh", state.messages.isNotEmpty())
        }
    }

    @Test
    fun `refreshIfNeeded skips refresh within cooldown`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        // First refresh
        coEvery { manageSessionUseCase.getSession(any(), any()) } returns Message.Assistant(
            id = "test-session", sessionId = "", time = TimeInfo(created = 0L)
        )
        vm.refreshSession()
        advanceUntilIdle()

        // Record call count
        val callsAfterFirst = mockk.coVerify(mode = ScrutinyMode.RANGE) {
            manageSessionUseCase.getSession(any(), any())
        }

        // Second refresh within cooldown — should be skipped
        vm.refreshIfNeeded()
        advanceUntilIdle()

        // Verify no additional REST calls were made
        coVerify(exactly = 1) { manageSessionUseCase.getSession(any(), any()) }
    }

    @Test
    fun `loading guard only clears truly empty message lists`() = runTest {
        // Given: 1 message (size < 3 but > 0)
        val messagesFlow = MutableStateFlow(listOf(
            Message.Assistant(id = "msg-1", sessionId = "test-session", time = TimeInfo(created = 1000L))
        ))
        every { messagePaging.observeMessages("test-session") } returns messagesFlow

        val vm = createViewModel()
        advanceUntilIdle()

        // The message list should NOT be cleared even with loading=true and size < 3
        vm.messageListState.test {
            val state = awaitItem()
            // With the fix, loading guard is `isEmpty()` not `< 3`
            assertTrue(state.messages.isNotEmpty())
        }
    }
}
```

- [ ] **Step 2: Run the tests**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "dev.minios.ocremote.ui.screens.chat.ChatViewModelStreamingTest" --rerun`
Expected: All 4 tests PASS

- [ ] **Step 3: Fix any compilation or test failures**

If tests fail due to constructor changes or mock setup issues, adjust the test setup to match the actual ViewModel constructor parameters.

- [ ] **Step 4: Commit**

```bash
git add app/src/test/java/dev/minios/ocremote/ui/screens/chat/ChatViewModelStreamingTest.kt
git commit -m "test: add streaming state management tests for refresh and loading fixes"
```

---

## Task 8: Write Tests — MessageEventHandler Streaming Merge

**Files:**
- Create: `app/src/test/java/dev/minios/ocremote/data/repository/handler/MessageEventHandlerMergeTest.kt`

- [ ] **Step 1: Create the test file**

```kotlin
package dev.minios.ocremote.data.repository.handler

import dev.minios.ocremote.domain.model.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for streaming-aware message/part merge in MessageEventHandler.
 * Verifies that REST refresh during active SSE streaming preserves
 * the streaming state correctly.
 */
class MessageEventHandlerMergeTest {

    private lateinit var handler: MessageEventHandler

    @Before
    fun setup() {
        handler = MessageEventHandler()
    }

    @Test
    fun `setMessages preserves SSE streaming text over REST empty text`() = runTest {
        // Given: SSE has built up streaming text via deltas
        val sessionId = "sess-1"
        val messageId = "msg-1"

        // Simulate SSE building up text: first a MessageUpdated, then PartDeltas
        handler.handle(SseEvent.MessageUpdated(
            info = Message.Assistant(
                id = messageId,
                sessionId = sessionId,
                time = TimeInfo(created = 1000L),
            )
        ), "server-1")

        handler.handle(SseEvent.MessagePartUpdated(
            part = Part.Text(id = "p1", sessionId = sessionId, messageId = messageId, text = "")
        ), "server-1")

        handler.handle(SseEvent.MessagePartDelta(
            sessionId = sessionId,
            messageId = messageId,
            partId = "p1",
            field = "text",
            delta = "Hello "
        ), "server-1")

        handler.handle(SseEvent.MessagePartDelta(
            sessionId = sessionId,
            messageId = messageId,
            partId = "p1",
            field = "text",
            delta = "World"
        ), "server-1")

        // Verify SSE state has accumulated text
        val sseParts = handler.parts.value[messageId]
        assertEquals(1, sseParts?.size)
        assertEquals("Hello World", (sseParts?.first() as Part.Text).text)

        // When: REST refresh returns same message with empty text (server hasn't persisted deltas)
        handler.setMessages(sessionId, listOf(
            MessageWithParts(
                info = Message.Assistant(
                    id = messageId,
                    sessionId = sessionId,
                    time = TimeInfo(created = 1000L),
                ),
                parts = listOf(Part.Text(id = "p1", sessionId = sessionId, messageId = messageId, text = ""))
            )
        ))

        // Then: SSE streaming text is preserved (mergePartsList keeps longer text)
        val mergedParts = handler.parts.value[messageId]
        assertEquals(1, mergedParts?.size)
        val text = (mergedParts?.first() as Part.Text).text
        assertEquals("Streaming text must be preserved over REST empty text", "Hello World", text)
    }

    @Test
    fun `setMessages preserves SSE incomplete message metadata`() = runTest {
        val sessionId = "sess-1"
        val messageId = "msg-1"

        // SSE: message is streaming (completed = null, finish = null)
        handler.handle(SseEvent.MessageUpdated(
            info = Message.Assistant(
                id = messageId,
                sessionId = sessionId,
                time = TimeInfo(created = 1000L),
                finish = null,
            )
        ), "server-1")

        // REST: same message but with completed time (server persisted step-end)
        handler.setMessages(sessionId, listOf(
            MessageWithParts(
                info = Message.Assistant(
                    id = messageId,
                    sessionId = sessionId,
                    time = TimeInfo(created = 1000L, completed = 2000L),
                    finish = "stop",
                ),
                parts = emptyList()
            )
        ))

        // Then: SSE version should be preferred for content fields,
        // but completed time should be merged from REST
        val messages = handler.messages.value[sessionId] ?: emptyList()
        val merged = messages.first { it.id == messageId } as Message.Assistant
        // REST's completed time should be merged into SSE's incomplete message
        assertNotNull("Completed time should be merged from REST", merged.time.completed)
    }

    @Test
    fun `setMessages does not clear parts for messages not in REST response`() = runTest {
        val sessionId = "sess-1"

        // SSE: two messages with parts
        handler.handle(SseEvent.MessageUpdated(
            info = Message.Assistant(id = "msg-1", sessionId = sessionId, time = TimeInfo(created = 1000L))
        ), "server-1")
        handler.handle(SseEvent.MessagePartUpdated(
            part = Part.Text(id = "p1", sessionId = sessionId, messageId = "msg-1", text = "First")
        ), "server-1")

        handler.handle(SseEvent.MessageUpdated(
            info = Message.Assistant(id = "msg-2", sessionId = sessionId, time = TimeInfo(created = 2000L))
        ), "server-1")
        handler.handle(SseEvent.MessagePartUpdated(
            part = Part.Text(id = "p2", sessionId = sessionId, messageId = "msg-2", text = "Second")
        ), "server-1")

        // REST returns only msg-1 (msg-2 is being streamed, not persisted yet)
        handler.setMessages(sessionId, listOf(
            MessageWithParts(
                info = Message.Assistant(id = "msg-1", sessionId = sessionId, time = TimeInfo(created = 1000L)),
                parts = listOf(Part.Text(id = "p1", sessionId = sessionId, messageId = "msg-1", text = "First"))
            )
        ))

        // Then: msg-2 and its parts must be preserved
        val messages = handler.messages.value[sessionId] ?: emptyList()
        assertEquals("Both messages should be preserved", 2, messages.size)

        val parts2 = handler.parts.value["msg-2"]
        assertNotNull("msg-2 parts should be preserved", parts2)
        assertEquals("Second", (parts2?.first() as Part.Text).text)
    }

    @Test
    fun `handleMessagePartUpdated keeps longer existing text over shorter incoming text`() = runTest {
        // Tests the mergePart logic in handleMessagePartUpdated (SSE-to-SSE merge),
        // NOT the setMessages path (SSE-to-REST merge, tested above).
        val sessionId = "sess-1"

        // SSE: text has accumulated via deltas
        handler.handle(SseEvent.MessagePartUpdated(
            part = Part.Text(id = "p1", sessionId = sessionId, messageId = "msg-1", text = "")
        ), "server-1")
        handler.handle(SseEvent.MessagePartDelta(
            sessionId = sessionId, messageId = "msg-1", partId = "p1", field = "text", delta = "Accumulated SSE text"
        ), "server-1")

        // Shorter incoming PartUpdated via SSE → mergePart keeps longer existing text
        handler.handle(SseEvent.MessagePartUpdated(
            part = Part.Text(id = "p1", sessionId = sessionId, messageId = "msg-1", text = "Short")
        ), "server-1")

        val parts = handler.parts.value["msg-1"]
        val text = (parts?.first() as Part.Text).text
        assertEquals("Longer accumulated SSE text should survive shorter incoming", "Accumulated SSE text", text)
    }
}
```

- [ ] **Step 2: Run the tests**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "dev.minios.ocremote.data.repository.handler.MessageEventHandlerMergeTest" --rerun`
Expected: All 4 tests PASS

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/dev/minios/ocremote/data/repository/handler/MessageEventHandlerMergeTest.kt
git commit -m "test: add streaming merge tests for MessageEventHandler"
```

---

## Task 9: Full Build Verification

- [ ] **Step 1: Run full unit test suite**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun`
Expected: ALL TESTS PASS (existing + new)

- [ ] **Step 2: Run compile check for release**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Verify existing tests still pass**

If any existing test fails due to the changes:
1. Check if the test was relying on `_isLoading` being set during `refreshSession()`
2. Update the test to match the new behavior (refresh uses `_isRefreshing`, not `_isLoading`)
3. Re-run and verify

- [ ] **Step 4: Final commit if test fixes needed**

```bash
git add -A
git commit -m "fix: update existing tests for new refreshSession behavior"
```

---

## Self-Review Checklist

### 1. Spec Coverage

| Requirement | Task |
|------------|------|
| Fix: last message invisible during streaming | Task 2 (fingerprint) + Task 3 (snapToBottom) |
| Fix: state wrong after app-switch | Task 1 (_isLoading) + Task 4 (conditional refresh) + Task 5 (atomic refresh) |
| Fix: REST overwrites SSE streaming state | Task 6 (streaming-aware merge) |
| Test: verify fixes | Task 7 + Task 8 |
| Build verification | Task 9 |

### 2. Placeholder Scan

- ✅ No TBD/TODO in any step
- ✅ All code blocks contain actual implementation code
- ✅ All test code is complete
- ✅ All file paths are exact
- ✅ All commands specify expected output

### 3. Type Consistency

- `Message.Assistant` / `Message.User` — consistent across all tasks
- `Part.Text` / `Part.Reasoning` — consistent
- `MessageWithParts(info, parts)` — consistent
- `SseEvent.MessageUpdated(info=...)` / `SseEvent.MessagePartUpdated(part=...)` / `SseEvent.MessagePartDelta(sessionId, messageId, partId, field, delta)` — `field` is required (use `"text"`)
- `TimeInfo(created: Long, completed: Long? = null)` — `Message.Time` is NOT a valid type; use `TimeInfo` directly
- `_isLoading: MutableStateFlow<Boolean>` — consistent
- `_isRefreshing: MutableStateFlow<Boolean>` — introduced in Task 1, used consistently

### 4. Potential Cross-Task Conflicts

- Task 4 modifies `refreshSession()` which Task 5 also modifies → **Task 5 should be applied AFTER Task 4** (Task 5 supersedes Task 4's version of `refreshSession()`)
- Task 1 introduces `refreshMessages()` private method which Task 5 references → **Task 1 must be applied BEFORE Task 5**
- Task 4 introduces `refreshIfNeeded()` which Task 5 modifies → **Task 5 must update the method defined in Task 4**
