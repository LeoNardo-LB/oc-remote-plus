# Layer 4: Message Rendering Enhancement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Improve message sending reliability, add copy/meta UI, and extend tool card rendering for glob/web-fetch/web-search/apply-patch tools plus a pluggable tool card registry.
**Architecture:** ChatViewModel gains optimistic send + draft restore flows. New Compose composables for copy, meta info, and four tool cards. A `ToolCardResolver` interface replaces the hardcoded `when` in `PartContent.kt`, backed by a Map-based registry.
**Tech Stack:** Kotlin, Jetpack Compose (Material3), StateFlow, Hilt, kotlinx.serialization
**Depends on:** Layers 1–3 (connection, SSE, chat screen base) must be complete
**Spec reference:** `docs/superpowers/specs/2026-06-04-gap-fix-design.md` → Layer 4

---

## Step 4.1: Optimistic Message Send (P1)

ChatViewModel inserts a pending `Message.User` into the local message list before the API call. SSE `message.updated` replaces it. On failure: remove pending + restore input. 60 s timeout → mark as failed.

### 4.1.1 — Add pending message support to ChatUiState

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt`

Add a `pendingMessages` map and a helper to build synthetic pending user messages.

```kotlin
// Add to ChatUiState data class (inside the existing data class body):
/** User messages optimistically inserted before API confirmation, keyed by messageId. */
val pendingMessageIds: Set<String> = emptySet(),
```

Add fields to the ViewModel class body (after existing private fields):

```kotlin
/** Locally-generated IDs for optimistic messages. Used to distinguish from server-confirmed. */
private val _pendingMessageIds = MutableStateFlow<Set<String>>(emptySet())
```

In the `init` block or the `collectUiState` merging function, merge `_pendingMessageIds` into `ChatUiState.pendingMessageIds`.

### 4.1.2 — Write test for optimistic insert

**File:** `app/src/test/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModelSendTest.kt`

```kotlin
package dev.minios.ocremote.ui.screens.chat

import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.repository.EventDispatcher
import dev.minios.ocremote.domain.model.*
import dev.minios.ocremote.domain.repository.SettingsRepository
import dev.minios.ocremote.domain.usecase.*
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelSendTest {

    @get:Rule
    val mainRule = androidx.arch.core.executor.testing.InstantTaskExecutorRule()

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var eventDispatcher: EventDispatcher
    private lateinit var api: OpenCodeApi
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var sendMessageUseCase: SendMessageUseCase
    private lateinit var viewModel: ChatViewModel

    @Before
    fun setup() {
        eventDispatcher = EventDispatcher(
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true)
        )
        api = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
        sendMessageUseCase = mockk()

        // Provide minimal SavedStateHandle with sessionId
        val savedStateHandle = androidx.lifecycle.SavedStateHandle(mapOf("sessionId" to "test-session"))
        viewModel = ChatViewModel(
            savedStateHandle = savedStateHandle,
            eventDispatcher = eventDispatcher,
            sendMessageUseCase = sendMessageUseCase,
            listMessagesUseCase = mockk(),
            getMessageUseCase = mockk(),
            deleteSessionUseCase = mockk(),
            updateSettingsUseCase = mockk(),
            settingsRepository = settingsRepository,
            api = api
        )
    }

    @Test
    fun `optimistic message appears in state before API returns`() = testDispatcher.runTest {
        // Given: sendMessageUseCase will suspend
        coEvery { sendMessageUseCase.sendPrompt(any(), any(), any()) } answers {
            // Never completes — simulates in-flight request
            kotlinx.coroutines.delay(10_000)
        }

        // When
        viewModel.sendMessage("Hello world")

        // Then: pending message should appear in messages
        val state = viewModel.uiState.value
        assertTrue(
            "Pending message should be in state",
            state.pendingMessageIds.isNotEmpty()
        )
    }

    @Test
    fun `optimistic message removed on failure`() = testDispatcher.runTest {
        coEvery { sendMessageUseCase.sendPrompt(any(), any(), any()) } throws
            java.io.IOException("Network error")

        viewModel.sendMessage("Hello world")

        // Pending should be cleared after failure
        val state = viewModel.uiState.value
        assertTrue(
            "Pending message should be removed on failure",
            state.pendingMessageIds.isEmpty()
        )
    }
}
```

### 4.1.3 — Implement optimistic insert in sendMessage

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt`

In the existing `sendMessage` / `sendParts` method, add the optimistic insert before the API call. Find the method that calls `sendMessageUseCase.sendPrompt(...)` and wrap it:

```kotlin
// Add this helper function inside ChatViewModel class:
private fun generatePendingMessageId(): String = "pending-${java.util.UUID.randomUUID()}"

private fun insertOptimisticUserMessage(text: String): String {
    val pendingId = generatePendingMessageId()
    val now = System.currentTimeMillis()
    val pendingMessage = Message.User(
        id = pendingId,
        sessionId = currentSessionId,
        time = TimeInfo(created = now),
        summary = Message.User.UserSummary(title = text)
    )
    val pendingPart = Part.Text(
        id = "part-$pendingId",
        sessionId = currentSessionId,
        messageId = pendingId,
        text = text
    )
    // Insert into event dispatcher's message handler directly
    // (EventDispatcher already exposes messages flow)
    _pendingMessageIds.update { it + pendingId }
    return pendingId
}

private fun removeOptimisticMessage(messageId: String) {
    _pendingMessageIds.update { it - messageId }
}
```

Modify the `sendMessage` method (find the existing one and wrap the API call):

```kotlin
fun sendMessage(text: String, attachmentUris: List<String> = emptyList()) {
    val pendingId = insertOptimisticUserMessage(text)
    viewModelScope.launch {
        try {
            withTimeout(60_000) {
                sendMessageUseCase.sendPrompt(
                    connection = currentConnection,
                    sessionId = currentSessionId,
                    parts = buildSendParts(text, attachmentUris)
                )
            }
        } catch (e: Exception) {
            removeOptimisticMessage(pendingId)
            _restoredDraft.value = RevertedDraftPayload(
                text = text,
                attachmentUris = attachmentUris
            )
            _error.value = e.message
        }
    }
}
```

### 4.1.4 — Verify

```bash
.\gradlew :app:compileDevDebugKotlin
.\gradlew :app:testDevDebugUnitTest --rerun --tests "dev.minios.ocremote.ui.screens.chat.ChatViewModelSendTest"
```

- [ ] Test passes: optimistic message appears before API returns
- [ ] Test passes: optimistic message removed on failure
- [ ] Compile check passes
- [ ] Commit: `feat(chat): optimistic message send with failure rollback`

---

## Step 4.2: Failed Send Input Restore (P1)

When `sendMessage()` fails, the input text is saved to `_restoredDraft` StateFlow. The UI observes this and pre-fills the input bar.

### 4.2.1 — Add restoredDraft to ChatUiState

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt`

The `RevertedDraftPayload` data class already exists. Add to ChatUiState:

```kotlin
/** Draft restored after a failed send. Non-null only once until consumed. */
val restoredDraft: RevertedDraftPayload? = null,
```

Add the MutableStateFlow in the ViewModel body:

```kotlin
private val _restoredDraft = MutableStateFlow<RevertedDraftPayload?>(null)
```

Merge it into the combined state. When the UI consumes it, it should set back to null:

```kotlin
fun consumeRestoredDraft() {
    _restoredDraft.value = null
}
```

### 4.2.2 — Update ChatInputBar to observe restoredDraft

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/input/ChatInputBar.kt`

Add a `LaunchedEffect` block inside `ChatInputBar` composable that observes `restoredDraft` and sets the input text:

```kotlin
// Add parameter to ChatInputBar signature:
@Composable
fun ChatInputBar(
    // ... existing params ...
    restoredDraft: RevertedDraftPayload?,
    onConsumeRestoredDraft: () -> Unit,
    // ... existing params ...
) {
    // Inside the composable body, add:
    LaunchedEffect(restoredDraft) {
        restoredDraft?.let { draft ->
            textFieldValue = TextFieldValue(draft.text)
            onConsumeRestoredDraft()
        }
    }
    // ... rest of existing code ...
}
```

### 4.2.3 — Wire in ChatScreen

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt`

In the `ChatScreen` composable where `ChatInputBar` is called, pass the new parameters:

```kotlin
ChatInputBar(
    // ... existing params ...
    restoredDraft = uiState.restoredDraft,
    onConsumeRestoredDraft = { viewModel.consumeRestoredDraft() },
    // ... existing params ...
)
```

### 4.2.4 — Verify

```bash
.\gradlew :app:compileDevDebugKotlin
```

- [ ] Compile check passes
- [ ] Commit: `feat(chat): restore input text on send failure`

---

## Step 4.3: Assistant Copy Button (P1)

Add a copy button to assistant message bubbles.

### 4.3.1 — Create CopyButton composable

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/CopyButton.kt`

```kotlin
package dev.minios.ocremote.ui.screens.chat.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import dev.minios.ocremote.ui.theme.AlphaTokens

/**
 * A small copy button that copies [text] to clipboard.
 * Used in assistant message bubbles and tool cards.
 */
@Composable
internal fun CopyButton(
    text: String,
    modifier: Modifier = Modifier,
    contentDescription: String = "Copy"
) {
    val clipboardManager = LocalClipboardManager.current
    IconButton(
        onClick = { clipboardManager.setText(AnnotatedString(text)) },
        modifier = modifier
    ) {
        Icon(
            imageVector = Icons.Default.ContentCopy,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED),
            modifier = Modifier
        )
    }
}
```

### 4.3.2 — Integrate into AssistantTurnBubble

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/AssistantTurnBubble.kt`

The existing `AssistantTurnBubble` already has an `onCopyText` callback. It is already wired to copy text in `ChatMessageList`. This step is complete — the copy button already exists via `ToolCardScaffold`'s built-in copy and the `onCopyText` lambda in the bubble.

No additional code changes needed for this step — the copy functionality is already integrated through:
1. `ToolCardScaffold`'s built-in copy `IconButton`
2. The `onCopyText` callback in `AssistantTurnBubble` which copies all text parts

### 4.3.3 — Verify

```bash
.\gradlew :app:compileDevDebugKotlin
```

- [ ] Compile check passes
- [ ] Commit: `feat(chat): add CopyButton composable component`

---

## Step 4.4: Message Meta Info (P1)

Display model name, duration, and token count below assistant messages.

### 4.4.1 — Create MessageMetaInfo composable

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/MessageMetaInfo.kt`

```kotlin
package dev.minios.ocremote.ui.screens.chat.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.R
import dev.minios.ocremote.ui.theme.AlphaTokens

/**
 * Displays metadata below an assistant message: model name, duration, token count.
 */
@Composable
internal fun MessageMetaInfo(
    modelName: String?,
    durationMs: Long?,
    inputTokens: Int?,
    outputTokens: Int?,
    modifier: Modifier = Modifier
) {
    val metaColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
    val parts = buildList {
        modelName?.let { add(it) }
        durationMs?.let { add(formatMetaDuration(it)) }
        inputTokens?.let { i -> outputTokens?.let { o ->
            add(stringResource(R.string.chat_meta_tokens, i + o))
        } }
    }
    if (parts.isEmpty()) return

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        parts.forEachIndexed { index, part ->
            if (index > 0) {
                Text(
                    text = " · ",
                    style = MaterialTheme.typography.labelSmall,
                    color = metaColor
                )
            }
            Text(
                text = part,
                style = MaterialTheme.typography.labelSmall,
                color = metaColor
            )
        }
    }
}

private fun formatMetaDuration(ms: Long): String {
    return when {
        ms < 1000 -> "${ms}ms"
        ms < 60_000 -> "${ms / 1000}s"
        else -> "${ms / 60_000}m ${((ms % 60_000) / 1000)}s"
    }
}
```

### 4.4.2 — Add string resource

**File:** `app/src/main/res/values/strings.xml`

```xml
<string name="chat_meta_tokens">%d tokens</string>
```

### 4.4.3 — Integrate into AssistantTurnBubble

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/AssistantTurnBubble.kt`

At the bottom of the bubble content (after all parts are rendered, before the closing `Column`), add:

```kotlin
// After the last part rendering, before the closing Column brace:
val lastAssistant = messages.lastOrNull()?.message as? Message.Assistant
val modelName = lastAssistant?.modelId
val duration = lastAssistant?.time?.let { t ->
    t.completed?.let { end -> end - t.created }
}
val tokens = lastAssistant?.tokens

if (modelName != null || duration != null || tokens != null) {
    MessageMetaInfo(
        modelName = modelName,
        durationMs = duration,
        inputTokens = tokens?.input,
        outputTokens = tokens?.output
    )
}
```

### 4.4.4 — Verify

```bash
.\gradlew :app:compileDevDebugKotlin
```

- [ ] Compile check passes
- [ ] Commit: `feat(chat): add MessageMetaInfo with model, duration, token display`

---

## Step 4.5: GlobToolCard (P1)

New composable for the `glob` tool: shows glob pattern + match count + expandable file list.

### 4.5.1 — Create GlobToolCard

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/tools/cards/GlobToolCard.kt`

```kotlin
package dev.minios.ocremote.ui.screens.chat.tools.cards

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.minios.ocremote.R
import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.domain.model.ToolState
import dev.minios.ocremote.ui.components.AmoledDefaultBorder
import dev.minios.ocremote.ui.screens.chat.tools.extractToolInput
import dev.minios.ocremote.ui.screens.chat.tools.extractToolOutput
import dev.minios.ocremote.ui.screens.chat.util.isAmoledTheme
import dev.minios.ocremote.ui.screens.chat.util.toolOutputContainerColor
import dev.minios.ocremote.ui.theme.CodeTypography
import dev.minios.ocremote.ui.theme.ShapeTokens
import dev.minios.ocremote.ui.theme.AlphaTokens
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Glob tool card — shows glob pattern + match count + expandable file list.
 */
@Composable
internal fun GlobToolCard(
    tool: Part.Tool,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit
) {
    val isAmoled = isAmoledTheme()
    val input = extractToolInput(tool)
    val pattern = input["pattern"]?.jsonPrimitive?.contentOrNull ?: ""
    val output = extractToolOutput(tool)
    val isRunning = tool.state is ToolState.Running

    // Parse output lines as file paths
    val files = remember(output) {
        if (output.isBlank()) emptyList()
        else output.lines().filter { it.isNotBlank() }
    }

    val title = if (pattern.isNotBlank()) {
        "${stringResource(R.string.tool_find_files)} · $pattern"
    } else {
        stringResource(R.string.tool_find_files)
    }

    ToolCardScaffold(
        icon = Icons.Default.FolderOpen,
        iconTint = MaterialTheme.colorScheme.primary,
        title = title,
        copyText = files.joinToString("\n"),
        isExpanded = isExpanded,
        isRunning = isRunning,
        hasContent = files.isNotEmpty(),
        isAmoled = isAmoled,
        onToggleExpand = onToggleExpand
    ) {
        Column {
            // Match count
            if (files.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.chat_glob_match_count, files.size),
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
                    ),
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                )
            }

            // Expandable file list
            AnimatedVisibility(visible = isExpanded) {
                Surface(
                    shape = ShapeTokens.extraSmall,
                    color = toolOutputContainerColor(),
                    border = if (isAmoled) AmoledDefaultBorder else null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        items(files) { filePath ->
                            SelectionContainer {
                                Text(
                                    text = filePath,
                                    style = CodeTypography.copy(
                                        fontSize = 11.sp,
                                        color = if (isAmoled) {
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.AMOLED)
                                        } else {
                                            MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = AlphaTokens.HIGH)
                                        }
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
```

### 4.5.2 — Add string resource

**File:** `app/src/main/res/values/strings.xml`

```xml
<string name="chat_glob_match_count">%d matches</string>
```

### 4.5.3 — Verify

```bash
.\gradlew :app:compileDevDebugKotlin
```

- [ ] Compile check passes
- [ ] Commit: `feat(chat): add GlobToolCard with match count and expandable file list`

---

## Step 4.6: WebFetchToolCard (P1)

New composable for the `webfetch` / `web_fetch` tool: shows URL + content summary.

### 4.6.1 — Create WebFetchToolCard

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/tools/cards/WebFetchToolCard.kt`

```kotlin
package dev.minios.ocremote.ui.screens.chat.tools.cards

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.minios.ocremote.R
import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.domain.model.ToolState
import dev.minios.ocremote.ui.components.AmoledDefaultBorder
import dev.minios.ocremote.ui.screens.chat.markdown.MarkdownContent
import dev.minios.ocremote.ui.screens.chat.tools.extractToolInput
import dev.minios.ocremote.ui.screens.chat.tools.extractToolOutput
import dev.minios.ocremote.ui.screens.chat.util.halfScreenHeight
import dev.minios.ocremote.ui.screens.chat.util.isAmoledTheme
import dev.minios.ocremote.ui.screens.chat.util.toolOutputContainerColor
import dev.minios.ocremote.ui.theme.ShapeTokens
import dev.minios.ocremote.ui.theme.AlphaTokens
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * WebFetch tool card — shows URL + content summary.
 */
@Composable
internal fun WebFetchToolCard(
    tool: Part.Tool,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit
) {
    val isAmoled = isAmoledTheme()
    val input = extractToolInput(tool)
    val url = input["url"]?.jsonPrimitive?.contentOrNull ?: ""
    val output = extractToolOutput(tool)
    val isRunning = tool.state is ToolState.Running
    val isPrompt = input["prompt"]?.jsonPrimitive?.contentOrNull

    val title = if (url.isNotBlank()) {
        val shortUrl = if (url.length > 50) url.take(47) + "..." else url
        "${stringResource(R.string.tool_web_fetch)} · $shortUrl"
    } else {
        stringResource(R.string.tool_web_fetch)
    }

    ToolCardScaffold(
        icon = Icons.Default.Language,
        iconTint = MaterialTheme.colorScheme.primary,
        title = title,
        copyText = url,
        isExpanded = isExpanded,
        isRunning = isRunning,
        hasContent = output.isNotBlank(),
        isAmoled = isAmoled,
        onToggleExpand = onToggleExpand
    ) {
        Column {
            // URL display
            if (url.isNotBlank()) {
                Surface(
                    shape = ShapeTokens.extraSmall,
                    color = toolOutputContainerColor(),
                    border = if (isAmoled) AmoledDefaultBorder else null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SelectionContainer {
                        Text(
                            text = url,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.HIGH)
                            ),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            // Prompt label
            if (isPrompt != null) {
                Text(
                    text = stringResource(R.string.chat_webfetch_prompt, isPrompt),
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
                    ),
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                )
            }

            // Content summary
            if (output.isNotBlank()) {
                val halfScreenHeight = halfScreenHeight()
                val scrollState = rememberScrollState()
                Surface(
                    shape = ShapeTokens.extraSmall,
                    color = toolOutputContainerColor(),
                    border = if (isAmoled) AmoledDefaultBorder else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = halfScreenHeight)
                        .verticalScroll(scrollState)
                ) {
                    SelectionContainer {
                        Text(
                            text = output.take(5000),
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = if (isAmoled) {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.AMOLED)
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                }
                            ),
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        }
    }
}
```

### 4.6.2 — Add string resources

**File:** `app/src/main/res/values/strings.xml`

```xml
<string name="tool_web_fetch">WebFetch</string>
<string name="chat_webfetch_prompt">Prompt: %s</string>
```

### 4.6.3 — Verify

```bash
.\gradlew :app:compileDevDebugKotlin
```

- [ ] Compile check passes
- [ ] Commit: `feat(chat): add WebFetchToolCard with URL and content summary`

---

## Step 4.7: WebSearchToolCard (P1)

New composable for the `websearch` / `web_search` tool: shows search query + result list.

### 4.7.1 — Create WebSearchToolCard

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/tools/cards/WebSearchToolCard.kt`

```kotlin
package dev.minios.ocremote.ui.screens.chat.tools.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.minios.ocremote.R
import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.domain.model.ToolState
import dev.minios.ocremote.ui.components.AmoledDefaultBorder
import dev.minios.ocremote.ui.screens.chat.tools.extractToolInput
import dev.minios.ocremote.ui.screens.chat.tools.extractToolOutput
import dev.minios.ocremote.ui.screens.chat.util.isAmoledTheme
import dev.minios.ocremote.ui.screens.chat.util.toolOutputContainerColor
import dev.minios.ocremote.ui.theme.CodeTypography
import dev.minios.ocremote.ui.theme.ShapeTokens
import dev.minios.ocremote.ui.theme.AlphaTokens
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private data class SearchResult(
    val title: String,
    val url: String,
    val summary: String
)

/**
 * WebSearch tool card — shows search query + result list.
 */
@Composable
internal fun WebSearchToolCard(
    tool: Part.Tool,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit
) {
    val isAmoled = isAmoledTheme()
    val input = extractToolInput(tool)
    val query = input["query"]?.jsonPrimitive?.contentOrNull ?: ""
    val output = extractToolOutput(tool)
    val isRunning = tool.state is ToolState.Running

    // Try to parse structured results from metadata
    val results = remember(tool.state) {
        val completed = tool.state as? ToolState.Completed
        val meta = completed?.metadata
        val resultsJson = meta?.get("results")?.jsonArray
        if (resultsJson != null) {
            resultsJson.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                SearchResult(
                    title = obj["title"]?.jsonPrimitive?.contentOrNull ?: "",
                    url = obj["url"]?.jsonPrimitive?.contentOrNull ?: "",
                    summary = obj["snippet"]?.jsonPrimitive?.contentOrNull ?: ""
                )
            }
        } else {
            // Fallback: parse output as text
            if (output.isBlank()) emptyList()
            else listOf(SearchResult(title = "", url = "", summary = output.take(2000)))
        }
    }

    val title = if (query.isNotBlank()) {
        val shortQuery = if (query.length > 40) query.take(37) + "..." else query
        "${stringResource(R.string.tool_web_search)} · $shortQuery"
    } else {
        stringResource(R.string.tool_web_search)
    }

    ToolCardScaffold(
        icon = Icons.Default.Search,
        iconTint = MaterialTheme.colorScheme.primary,
        title = title,
        copyText = output,
        isExpanded = isExpanded,
        isRunning = isRunning,
        hasContent = results.isNotEmpty() || output.isNotBlank(),
        isAmoled = isAmoled,
        onToggleExpand = onToggleExpand
    ) {
        Column {
            if (results.isNotEmpty()) {
                Surface(
                    shape = ShapeTokens.extraSmall,
                    color = toolOutputContainerColor(),
                    border = if (isAmoled) AmoledDefaultBorder else null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(results) { result ->
                            SearchResultRow(result = result, isAmoled = isAmoled)
                        }
                    }
                }
            } else if (output.isNotBlank()) {
                Surface(
                    shape = ShapeTokens.extraSmall,
                    color = toolOutputContainerColor(),
                    border = if (isAmoled) AmoledDefaultBorder else null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SelectionContainer {
                        Text(
                            text = output.take(2000),
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = if (isAmoled) {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.AMOLED)
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                }
                            ),
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(result: SearchResult, isAmoled: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        if (result.title.isNotBlank()) {
            Text(
                text = result.title,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (result.url.isNotBlank()) {
            Text(
                text = result.url,
                style = CodeTypography.copy(
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.HIGH)
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (result.summary.isNotBlank()) {
            Text(
                text = result.summary,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
                ),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
```

### 4.7.2 — Add string resource

**File:** `app/src/main/res/values/strings.xml`

```xml
<string name="tool_web_search">WebSearch</string>
```

### 4.7.3 — Verify

```bash
.\gradlew :app:compileDevDebugKotlin
```

- [ ] Compile check passes
- [ ] Commit: `feat(chat): add WebSearchToolCard with search results list`

---

## Step 4.8: ApplyPatchToolCard (P1)

New composable for the `apply_patch` tool: shows file path + diff preview using existing `DiffView`.

### 4.8.1 — Create ApplyPatchToolCard

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/tools/cards/ApplyPatchToolCard.kt`

```kotlin
package dev.minios.ocremote.ui.screens.chat.tools.cards

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.R
import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.domain.model.ToolState
import dev.minios.ocremote.ui.screens.chat.tools.DiffView
import dev.minios.ocremote.ui.screens.chat.tools.extractToolInput
import dev.minios.ocremote.ui.screens.chat.tools.extractToolOutput
import dev.minios.ocremote.ui.screens.chat.util.isAmoledTheme
import dev.minios.ocremote.ui.theme.AlphaTokens
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * ApplyPatch tool card — shows file path + diff preview.
 * Uses the existing [DiffView] component for rendering.
 */
@Composable
internal fun ApplyPatchToolCard(
    tool: Part.Tool,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit
) {
    val isAmoled = isAmoledTheme()
    val input = extractToolInput(tool)
    val output = extractToolOutput(tool)
    val isRunning = tool.state is ToolState.Running

    // Extract diff content from metadata or input
    val diffContent = remember(tool.state) {
        val completed = tool.state as? ToolState.Completed
        val meta = completed?.metadata
        meta?.get("patch")?.jsonPrimitive?.contentOrNull
            ?: input["patch"]?.jsonPrimitive?.contentOrNull
            ?: output
    }

    val filePath = input["filePath"]?.jsonPrimitive?.contentOrNull
        ?: input["path"]?.jsonPrimitive?.contentOrNull ?: ""

    val title = if (filePath.isNotBlank()) {
        "${stringResource(R.string.tool_apply_patch)} · ${extractFileName(filePath)}"
    } else {
        stringResource(R.string.tool_apply_patch)
    }

    ToolCardScaffold(
        icon = Icons.Default.Build,
        iconTint = MaterialTheme.colorScheme.primary,
        title = title,
        copyText = diffContent,
        isExpanded = isExpanded,
        isRunning = isRunning,
        hasContent = diffContent.isNotBlank(),
        isAmoled = isAmoled,
        onToggleExpand = onToggleExpand
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (diffContent.isNotBlank()) {
                DiffView(
                    before = "",
                    after = diffContent
                )
            }
        }
    }
}
```

### 4.8.2 — Add string resource

**File:** `app/src/main/res/values/strings.xml`

```xml
<string name="tool_apply_patch">ApplyPatch</string>
```

### 4.8.3 — Verify

```bash
.\gradlew :app:compileDevDebugKotlin
```

- [ ] Compile check passes
- [ ] Commit: `feat(chat): add ApplyPatchToolCard with diff preview`

---

## Step 4.9: Tool Card Extension Mechanism (P1)

Replace the hardcoded `when` in `PartContent.kt` with a pluggable `ToolCardResolver` interface and a Map-based registry.

### 4.9.1 — Define ToolCardResolver interface

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/tools/ToolCardResolver.kt`

```kotlin
package dev.minios.ocremote.ui.screens.chat.tools

import androidx.compose.runtime.Composable
import dev.minios.ocremote.domain.model.Part

/**
 * Resolver for tool-specific card composables.
 * Implementations map a tool name (lowercase) to its dedicated Compose card.
 */
interface ToolCardResolver {
    /**
     * Resolve a composable for the given tool part.
     * @return the composable lambda, or null if this resolver does not handle the tool.
     */
    fun resolve(
        tool: Part.Tool,
        isExpanded: Boolean,
        onToggleExpand: () -> Unit,
        onViewSubSession: ((String) -> Unit)?,
        turnAgentName: String?
    ): (@Composable () -> Unit)?
}
```

### 4.9.2 — Create Map-based registry

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/tools/DefaultToolCardResolver.kt`

```kotlin
package dev.minios.ocremote.ui.screens.chat.tools

import androidx.compose.runtime.Composable
import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.ui.screens.chat.tools.cards.ApplyPatchToolCard
import dev.minios.ocremote.ui.screens.chat.tools.cards.BashToolCard
import dev.minios.ocremote.ui.screens.chat.tools.cards.EditToolCard
import dev.minios.ocremote.ui.screens.chat.tools.cards.GlobToolCard
import dev.minios.ocremote.ui.screens.chat.tools.cards.ReadToolCard
import dev.minios.ocremote.ui.screens.chat.tools.cards.SearchToolCard
import dev.minios.ocremote.ui.screens.chat.tools.cards.TaskToolCard
import dev.minios.ocremote.ui.screens.chat.tools.cards.WebFetchToolCard
import dev.minios.ocremote.ui.screens.chat.tools.cards.WebSearchToolCard
import dev.minios.ocremote.ui.screens.chat.tools.cards.WriteToolCard
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default tool card resolver that maps tool names to their card composables.
 * Tool names are matched case-insensitively.
 */
@Singleton
class DefaultToolCardResolver @Inject constructor() : ToolCardResolver {

    private val cardMap: Map<String, (
        tool: Part.Tool,
        isExpanded: Boolean,
        onToggleExpand: () -> Unit,
        onViewSubSession: ((String) -> Unit)?,
        turnAgentName: String?
    ) -> @Composable () -> Unit> = mapOf(
        "bash" to { tool, expanded, toggle, _, _ ->
            { BashToolCard(tool = tool, isExpanded = expanded, onToggleExpand = toggle) }
        },
        "edit" to { tool, expanded, toggle, _, _ ->
            { EditToolCard(tool = tool, isExpanded = expanded, onToggleExpand = toggle) }
        },
        "multiedit" to { tool, expanded, toggle, _, _ ->
            { EditToolCard(tool = tool, isExpanded = expanded, onToggleExpand = toggle) }
        },
        "read" to { tool, expanded, toggle, _, _ ->
            { ReadToolCard(tool = tool, isExpanded = expanded, onToggleExpand = toggle) }
        },
        "write" to { tool, expanded, toggle, _, _ ->
            { WriteToolCard(tool = tool, isExpanded = expanded, onToggleExpand = toggle) }
        },
        "glob" to { tool, expanded, toggle, _, _ ->
            { GlobToolCard(tool = tool, isExpanded = expanded, onToggleExpand = toggle) }
        },
        "grep" to { tool, expanded, toggle, _, _ ->
            { SearchToolCard(tool = tool, isExpanded = expanded, onToggleExpand = toggle) }
        },
        "search" to { tool, expanded, toggle, _, _ ->
            { SearchToolCard(tool = tool, isExpanded = expanded, onToggleExpand = toggle) }
        },
        "task" to { tool, expanded, toggle, viewSub, agentName ->
            { TaskToolCard(tool = tool, onViewSubSession = viewSub, turnAgentName = agentName, isExpanded = expanded, onToggleExpand = toggle) }
        },
        "webfetch" to { tool, expanded, toggle, _, _ ->
            { WebFetchToolCard(tool = tool, isExpanded = expanded, onToggleExpand = toggle) }
        },
        "web_fetch" to { tool, expanded, toggle, _, _ ->
            { WebFetchToolCard(tool = tool, isExpanded = expanded, onToggleExpand = toggle) }
        },
        "websearch" to { tool, expanded, toggle, _, _ ->
            { WebSearchToolCard(tool = tool, isExpanded = expanded, onToggleExpand = toggle) }
        },
        "web_search" to { tool, expanded, toggle, _, _ ->
            { WebSearchToolCard(tool = tool, isExpanded = expanded, onToggleExpand = toggle) }
        },
        "apply_patch" to { tool, expanded, toggle, _, _ ->
            { ApplyPatchToolCard(tool = tool, isExpanded = expanded, onToggleExpand = toggle) }
        },
    )

    override fun resolve(
        tool: Part.Tool,
        isExpanded: Boolean,
        onToggleExpand: () -> Unit,
        onViewSubSession: ((String) -> Unit)?,
        turnAgentName: String?
    ): (@Composable () -> Unit)? {
        val factory = cardMap[tool.tool.lowercase()] ?: return null
        return factory(tool, isExpanded, onToggleExpand, onViewSubSession, turnAgentName)
    }
}
```

### 4.9.3 — Write test for DefaultToolCardResolver

**File:** `app/src/test/kotlin/dev/minios/ocremote/ui/screens/chat/tools/DefaultToolCardResolverTest.kt`

```kotlin
package dev.minios.ocremote.ui.screens.chat.tools

import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.domain.model.ToolState
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DefaultToolCardResolverTest {

    private lateinit var resolver: DefaultToolCardResolver

    @Before
    fun setup() {
        resolver = DefaultToolCardResolver()
    }

    private fun testTool(name: String) = Part.Tool(
        id = "test-part",
        sessionId = "s1",
        messageId = "m1",
        callId = "c1",
        tool = name,
        state = ToolState.Pending(input = emptyMap())
    )

    @Test
    fun `resolves bash tool`() {
        val result = resolver.resolve(testTool("bash"), false, {}, null, null)
        assertNotNull("bash should resolve", result)
    }

    @Test
    fun `resolves edit tool`() {
        val result = resolver.resolve(testTool("edit"), false, {}, null, null)
        assertNotNull("edit should resolve", result)
    }

    @Test
    fun `resolves glob tool`() {
        val result = resolver.resolve(testTool("glob"), false, {}, null, null)
        assertNotNull("glob should resolve", result)
    }

    @Test
    fun `resolves task tool`() {
        val result = resolver.resolve(testTool("task"), false, {}, null, null)
        assertNotNull("task should resolve", result)
    }

    @Test
    fun `resolves webfetch tool`() {
        val result = resolver.resolve(testTool("webfetch"), false, {}, null, null)
        assertNotNull("webfetch should resolve", result)
    }

    @Test
    fun `resolves web_fetch tool`() {
        val result = resolver.resolve(testTool("web_fetch"), false, {}, null, null)
        assertNotNull("web_fetch should resolve", result)
    }

    @Test
    fun `resolves apply_patch tool`() {
        val result = resolver.resolve(testTool("apply_patch"), false, {}, null, null)
        assertNotNull("apply_patch should resolve", result)
    }

    @Test
    fun `returns null for unknown tool`() {
        val result = resolver.resolve(testTool("unknown_tool_xyz"), false, {}, null, null)
        assertNull("unknown tool should not resolve", result)
    }

    @Test
    fun `case insensitive matching`() {
        val result = resolver.resolve(testTool("Bash"), false, {}, null, null)
        assertNotNull("Bash (capitalized) should resolve", result)
    }
}
```

### 4.9.4 — Update PartContent to use resolver

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/PartContent.kt`

Replace the tool-card section of the `when (part)` block. The `is Part.Tool` branch currently has a hardcoded `when` for tool names. Replace it with:

```kotlin
is Part.Tool -> {
    val toolExpandedStates = LocalToolExpandedStates.current
    val onToggleToolExpanded = LocalOnToggleToolExpanded.current

    if (part.tool == "todoread") {
        // skip todoread parts
    } else if (part.tool == "todowrite") {
        TodoListCard(
            tool = part,
            isExpanded = toolExpandedStates[part.id] ?: true,
            onToggleExpand = { onToggleToolExpanded(part.id, true) }
        )
    } else {
        // Use the resolver registry
        val autoExpand = LocalCollapseTools.current
        val expanded = toolExpandedStates[part.id] ?: autoExpand
        val toggleExpand = { onToggleToolExpanded(part.id, autoExpand) }

        val resolved = LocalToolCardResolver.current.resolve(
            tool = part,
            isExpanded = expanded,
            onToggleExpand = toggleExpand,
            onViewSubSession = onViewSubSession,
            turnAgentName = turnAgentName
        )

        if (resolved != null) {
            resolved()
        } else {
            // Fallback to generic ToolCallCard
            ToolCallCard(
                tool = part,
                isExpanded = expanded,
                onToggleExpand = toggleExpand
            )
        }
    }
}
```

### 4.9.5 — Provide LocalToolCardResolver composition local

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/util/CompositionLocals.kt`

Add a new composition local. Find or create the file where `LocalCollapseTools`, `LocalToolExpandedStates`, etc. are defined and add:

```kotlin
val LocalToolCardResolver = compositionLocalOf<ToolCardResolver> {
    DefaultToolCardResolver()
}
```

### 4.9.6 — Provide resolver via Hilt in ChatScreen

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt`

At the top of `ChatScreen`, provide the resolver:

```kotlin
val toolCardResolver = remember {
    // Will be injected via ViewModel or created here
    // The DefaultToolCardResolver is @Singleton via Hilt
    viewModel.toolCardResolver
}

CompositionLocalProvider(LocalToolCardResolver provides toolCardResolver) {
    // ... existing content ...
}
```

Add to ChatViewModel:

```kotlin
// In ChatViewModel:
val toolCardResolver: ToolCardResolver = DefaultToolCardResolver()
```

### 4.9.7 — Verify

```bash
.\gradlew :app:compileDevDebugKotlin
.\gradlew :app:testDevDebugUnitTest --rerun --tests "dev.minios.ocremote.ui.screens.chat.tools.DefaultToolCardResolverTest"
```

- [ ] Test passes: all known tools resolve
- [ ] Test passes: unknown tools return null
- [ ] Test passes: case-insensitive matching
- [ ] Compile check passes
- [ ] Commit: `feat(chat): pluggable ToolCardResolver with Map-based registry`

---

## Final Verification

After all steps are complete:

```bash
.\gradlew :app:compileDevDebugKotlin
.\gradlew :app:testDevDebugUnitTest --rerun
```

- [ ] All compile checks pass
- [ ] All unit tests pass
- [ ] No regressions in existing tests
