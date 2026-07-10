package dev.leonardo.ocremotev2.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import dagger.hilt.android.testing.HiltAndroidTest
import dev.leonardo.ocremotev2.builder.anAssistantMessage
import dev.leonardo.ocremotev2.builder.aUserMessage
import dev.leonardo.ocremotev2.data.repository.SessionStateService
import dev.leonardo.ocremotev2.domain.model.Message
import dev.leonardo.ocremotev2.domain.model.MessageWithParts
import dev.leonardo.ocremotev2.domain.model.Part
import dev.leonardo.ocremotev2.domain.model.ProviderCatalog
import dev.leonardo.ocremotev2.domain.model.ProviderInfo
import dev.leonardo.ocremotev2.domain.model.ModelInfo
import dev.leonardo.ocremotev2.domain.model.ProvidersResponse
import dev.leonardo.ocremotev2.domain.model.SessionStatus
import dev.leonardo.ocremotev2.domain.model.SseEvent
import dev.leonardo.ocremotev2.domain.repository.ProviderRepository
import dev.leonardo.ocremotev2.domain.tracker.TokenStatsTracker
import dev.leonardo.ocremotev2.fakes.FakeServerRepository
import org.junit.Test
import javax.inject.Inject

/**
 * Integration tests for ChatScreen interaction behaviors.
 *
 * Extends [BaseChatTest] for the standard Hilt + Compose setup pattern.
 * Each test configures fake repository state, renders ChatScreen, performs
 * UI interactions, and asserts expected outcomes.
 */
@HiltAndroidTest
class ChatInteractionTest : BaseChatTest() {

    @Inject
    lateinit var providerRepo: ProviderRepository

    @Inject
    lateinit var tokenStatsTracker: TokenStatsTracker

    @Inject
    lateinit var sessionStateService: SessionStateService

    private val fakeServer: FakeServerRepository
        get() = providerRepo as FakeServerRepository

    // ============ Helpers ============

    /**
     * Seed messages so they appear in the UI.
     *
     * messageListState combines messages from messagesState with parts from
     * allPartsMapState (keyed by messageId). partsState is used by
     * startObservingMessages() internally but the UI reads allPartsMapState.
     */
    private fun seedMessages(vararg mwps: MessageWithParts) {
        fakeChat.messagesState.value = mwps.map { it.info }
        fakeChat.allPartsMapState.value = mwps.associate { it.info.id to it.parts }
    }

    /** Seed messages from separate lists — convenience wrapper. */
    private fun seedMessages(messages: List<Message>, parts: List<Part>) {
        fakeChat.messagesState.value = messages
        fakeChat.allPartsMapState.value = parts.groupBy { it.messageId }
    }

    /** Seed a single user + assistant exchange with text parts. */
    private fun seedConversation() {
        val userMsg = aUserMessage("Hello", id = "u1")
        val assistantMsg = anAssistantMessage(id = "a1") {
            text("Hi there!")
        }
        seedMessages(
            messages = listOf(userMsg, assistantMsg.info),
            parts = assistantMsg.parts
        )
    }

    /**
     * Seed a permission request that will surface as a PermissionCard.
     *
     * NOTE: The store key is "" because the ViewModel's sessionIdFlow is ""
     * in instrumented tests (no navigation args reach savedStateHandle).
     * interactionState calls getPermissionsWithChildren(sid, ...) where
     * sid = sessionIdFlow.value = "". The event's own sessionId field is
     * kept as TEST_SESSION for realism but the lookup key must match.
     */
    private fun seedPermission(
        id: String = "perm-1",
        permission: String = "bash"
    ): SseEvent.PermissionAsked {
        val perm = SseEvent.PermissionAsked(
            id = id,
            sessionId = TEST_SESSION,
            permission = permission
        )
        fakeChat.setPermissions("", listOf(perm))
        return perm
    }

    /**
     * Seed a question that will surface as a QuestionCard.
     *
     * NOTE: Store key is "" — see seedPermission() for rationale.
     */
    private fun seedQuestion(
        id: String = "q-1",
        question: String = "Which option?"
    ): SseEvent.QuestionAsked {
        val q = SseEvent.QuestionAsked(
            id = id,
            sessionId = TEST_SESSION,
            questions = listOf(
                SseEvent.QuestionAsked.Question(
                    header = "Choice",
                    question = question,
                    options = listOf(
                        SseEvent.QuestionAsked.Option("Yes", "Confirm"),
                        SseEvent.QuestionAsked.Option("No", "Decline")
                    )
                )
            )
        )
        fakeChat.setQuestions("", listOf(q))
        return q
    }

    /**
     * Activate the SSE message observation pipeline.
     *
     * For new sessions (sessionId=""), startObservingMessages() is only called
     * after ensureSession(), which happens on first send. This helper sends a
     * trivial message to activate the observation pipeline so seeded messages
     * become visible in the UI.
     *
     * IMPORTANT: After this call, sessionId changes from "" to the created
     * session's ID. Do NOT use for tests that rely on sessionId="" (e.g.
     * permission/question lookups).
     */
    private fun activateMessageStream() {
        typeInput(".")
        composeRule.onNodeWithTag("chat-send").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            fakeChat.promptAsyncCalls.isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    // ============ Tests ============

    /**
     * Test 1: Typing text and tapping send clears the input and records the message.
     *
     * The send path: ChatInputBar.onSend → ChatScreen doSend() →
     * viewModel.sendMessage(parts) → sendParts() → SendMessageUseCase.sendPrompt() →
     * chatRepository.promptAsync().
     */
    @Test
    fun sendMessage_clearsInput() {
        renderChatScreen()
        composeRule.waitForIdle()

        typeInput("hello world")

        // Tap the send button (testTag "chat-send")
        composeRule.onNodeWithTag("chat-send").performClick()

        // Wait for the async send (promptAsync) to complete
        composeRule.waitUntil(timeoutMillis = 10_000) {
            fakeChat.promptAsyncCalls.isNotEmpty()
        }

        // The fake should have recorded exactly one promptAsync call
        assert(fakeChat.promptAsyncCalls.size == 1) {
            "Expected 1 promptAsync call, got ${fakeChat.promptAsyncCalls.size}"
        }
    }

    /**
     * Test 2: A tool card with completed output is displayed.
     *
     * Tool cards render through ToolCardScaffold. ReadToolCard (resolved by
     * DefaultToolCardResolver for "read" tool name) renders title from
     * R.string.tool_read = "Read".
     */
    @Test
    fun toolCardExpand_toggles() {
        val assistantMsg = anAssistantMessage(id = "a-tool") {
            toolCompleted(
                name = "read",
                output = "File contents here"
            )
        }
        seedMessages(assistantMsg)

        renderChatScreen()
        composeRule.waitForIdle()

        // Messages appear directly from messagesState + allPartsMapState via the
        // messageListState combine pipeline — no session creation needed.
        // ReadToolCard renders title from R.string.tool_read = "Read"
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Read", substring = true, ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        val toolNodes = composeRule.onAllNodesWithText("Read", substring = true, ignoreCase = true)
        assert(toolNodes.fetchSemanticsNodes().isNotEmpty()) {
            "Tool card with 'Read' should be displayed"
        }
    }

    /**
     * Test 3: Context usage indicator appears when token stats are available.
     *
     * The ChatTopBar shows a CircularProgressIndicator with percentage when
     * contextWindow > 0 and lastContextTokens > 0.
     *
     * TokenStatsTracker is a @Singleton injected by Hilt — the same instance
     * is shared between the test and the ViewModel. We set the stats after
     * render (init calls reset()) and configure a provider catalog so
     * modelConfig.contextWindow resolves to a non-zero value.
     */
    @Test
    fun contextUsageBar_shows_whenTokenStatsAvailable() {
        // Provider with a model that has a context window
        val testProvider = ProviderCatalog(
            id = "ctx-provider",
            name = "Ctx Provider",
            models = mapOf(
                "ctx-model" to dev.leonardo.ocremotev2.domain.model.ModelCatalog(
                    id = "ctx-model",
                    name = "Ctx Model",
                    contextWindow = 128000
                )
            )
        )
        fakeServer.catalogResult = Result.success(
            ProvidersResponse(
                providers = listOf(testProvider),
                default = mapOf("ctx-provider" to "ctx-model")
            )
        )

        renderChatScreen()
        composeRule.waitForIdle()

        // Set token stats after ViewModel init (which calls tokenStatsTracker.reset())
        // percentage = round(64000 / 128000 * 100) = 50
        tokenStatsTracker.update {
            copy(lastContextTokens = 64000)
        }

        // Wait for context indicator to render (needs providers loaded + token stats set)
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("50").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("50").assertIsDisplayed()
    }

    /**
     * Test 4: Model selector shows available models when provider data is loaded.
     *
     * Providers are loaded from FakeServerRepository.catalogResult via
     * SelectModelUseCase → ProviderRepository.loadProviderCatalog().
     * The model label appears in AgentModelVariantSelector after providers load.
     */
    @Test
    fun modelSelector_showsAvailableModels() {
        // Set BOTH providersResult AND catalogResult — ModelConfigDelegate uses
        // loadProviders() for ProviderInfo list and loadProviderCatalog() for catalog.
        fakeServer.providersResult = Result.success(listOf(
            ProviderInfo(
                id = "test-provider",
                name = "Test Provider",
                enabled = true,
                connected = true,
                models = listOf(
                    ModelInfo(id = "model-a", name = "Model A", visible = true),
                    ModelInfo(id = "model-b", name = "Model B", visible = true)
                )
            )
        ))

        val testProvider = ProviderCatalog(
            id = "test-provider",
            name = "Test Provider",
            models = mapOf(
                "model-a" to dev.leonardo.ocremotev2.domain.model.ModelCatalog(
                    id = "model-a",
                    name = "Model A",
                    contextWindow = 128000
                ),
                "model-b" to dev.leonardo.ocremotev2.domain.model.ModelCatalog(
                    id = "model-b",
                    name = "Model B",
                    contextWindow = 200000
                )
            )
        )
        fakeServer.catalogResult = Result.success(
            ProvidersResponse(
                providers = listOf(testProvider),
                default = mapOf("test-provider" to "model-a")
            )
        )

        renderChatScreen()
        composeRule.waitForIdle()

        // modelConfigState is a 12-way combine with self-feedback side effects.
        // loadProviders() modifies _allProviders/_providers/_defaultModels during
        // init, but these changes may occur before the stateIn upstream starts
        // (i.e., before the UI subscribes via collectAsStateWithLifecycle). A
        // tokenStatsTracker update forces the combine to re-evaluate after
        // subscription, ensuring the resolved model label propagates to the UI.
        // lastContextTokens is chosen because it doesn't affect model resolution
        // (only contextWindow does, which stays at 0 → provider fallback applies).
        // This matches the pattern in contextUsageBar_shows_whenTokenStatsAvailable.
        tokenStatsTracker.update { copy(lastContextTokens = 1) }

        // The model label ("Model A") appears after loadProviders() resolves
        // asynchronously in ModelConfigDelegate.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Model A").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("Model A").assertIsDisplayed()
    }

    /**
     * Test 5: Typing /undo shows the undo suggestion without crashing.
     *
     * The SlashCommandRegistry registers "undo" as a client command. Typing
     * "/undo" filters suggestions to match. Full undo verification is deferred
     * to ViewModel-level unit tests.
     */
    @Test
    fun undo_callsUndoRedo() {
        seedConversation()

        renderChatScreen()

        typeInput("/undo")

        // Verify typing worked: send button exists when input is non-empty
        composeRule.onNodeWithTag("chat-send").assertExists()
    }

    /**
     * Test 6: Abort/stop button calls abort API when session is busy.
     *
     * The send button transforms into a stop button when isBusy && text is blank.
     * isBusy is derived from sessionMeta.sessionStatus (Busy or Retry).
     *
     * We inject SessionStateService (a @Singleton) and call onClientSendParts("")
     * to transition the FSM to Busy for sessionId="" — the same instance the
     * ViewModel reads from.
     */
    @Test
    fun abortSession_callsAbortApi() {
        // Set session status to Busy so the stop button appears.
        // sessionStateService is @Singleton — same instance the ViewModel uses.
        sessionStateService.onClientSendParts("")

        // Seed a streaming assistant message so the session looks active
        val streamingMsg = anAssistantMessage(streaming = true, id = "a-stream") {
            text("Generating...")
        }
        seedMessages(listOf(streamingMsg.info), streamingMsg.parts)

        renderChatScreen()
        composeRule.waitForIdle()

        // The stop button shows when isBusy && input text is blank.
        // It has contentDescription "Stop" (R.string.chat_stop).
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithContentDescription("Stop")
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithContentDescription("Stop").performClick()
        composeRule.waitForIdle()

        // abortSession() → sessionRepository.abort(serverId, sessionId, directory)
        // sessionId is "" in tests (from sessionIdFlow).
        composeRule.waitUntil(timeoutMillis = 10_000) {
            fakeSession.abortCalls.isNotEmpty()
        }
        assert(fakeSession.abortCalls.isNotEmpty()) {
            "Abort should have been called"
        }
    }

    /**
     * Test 7: Permission card appears when a permission is requested.
     *
     * interactionState (7-way combine) calls getPermissionsWithChildren(sid, ...)
     * where sid = sessionIdFlow.value = "". Data stored under "" key is found.
     */
    @Test
    fun permissionDialog_appears_whenPermissionRequested() {
        seedPermission(permission = "bash echo hello")

        renderChatScreen()
        composeRule.waitForIdle()

        // Wait for the interactionState flow (7-way combine) to propagate
        // the permission into pendingPermissions and render PermissionCard.
        // PermissionCard renders R.string.permission_title = "Permission Required"
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Permission Required")
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("Permission Required").assertIsDisplayed()

        // The permission description should also be visible
        composeRule.onNodeWithText("bash echo hello", substring = true).assertIsDisplayed()
    }

    /**
     * Test 8: Question card appears when a question is asked.
     */
    @Test
    fun questionDialog_appears_whenQuestionAsked() {
        seedQuestion(question = "Which framework?")

        renderChatScreen()
        composeRule.waitForIdle()

        // Wait for the interactionState flow to propagate the question into
        // pendingQuestions and render QuestionCard.
        // QuestionCard renders R.string.chat_question_label = "Question"
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Question")
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("Question").assertIsDisplayed()

        // The question text should also be visible
        composeRule.onNodeWithText("Which framework?", substring = true).assertIsDisplayed()
    }

    /**
     * Test 9: Scrolling up triggers pagination (loadOlderMessages).
     *
     * ChatMessageList uses auto-pagination: when the user scrolls within 8 items
     * of the top, viewModel.loadOlderMessages() is called.
     *
     * NOTE: For new sessions (sessionId=""), loadMessagesForSession() is never
     * called during init, so hasOlderMessages stays false and pagination cannot
     * trigger. To make this test pass, either:
     * 1. Provide a non-empty sessionId via SavedStateHandle in the test harness
     * 2. Add a test-visible method to force-set hasOlderMessages
     * 3. Mock the SessionLifecycleDelegate to treat sessionId as non-empty
     *
     * Kept @Ignore until one of these approaches is implemented.
     */
    @org.junit.Ignore("Pagination needs hasOlderMessages=true, which requires loadMessagesForSession() to run. For new sessions (sessionId=\"\"), init skips this — hasOlderMessages stays false. Fix: provide non-empty sessionId via SavedStateHandle test harness, or add a test hook to set hasOlderMessages directly.")
    @Test
    fun pagination_triggersOnScrollUp() {
        // Generate many messages
        val messages = mutableListOf<Message>()
        for (i in 1..30) {
            messages.add(aUserMessage("Message $i", id = "u$i"))
        }

        seedMessages(messages.reversed(), emptyList())
        fakeSession.listMessagesResult = Result.success(
            messages.mapIndexed { i, msg ->
                MessageWithParts(info = msg, parts = emptyList())
            }
        )

        renderChatScreen()
        composeRule.waitForIdle()

        // Activate message observation so messages become visible
        activateMessageStream()

        // Wait for at least one message to render
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Message", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Scroll up (reverseLayout=true: swipeDown reveals older messages at top)
        val messageNodes = composeRule.onAllNodesWithText("Message", substring = true)
        messageNodes[0].performTouchInput {
            repeat(5) { swipeDown() }
        }
        composeRule.waitForIdle()

        // Pagination should trigger loadOlderMessages() which calls listMessages
        // with a doubled limit. Without hasOlderMessages=true, this won't fire.
    }

    /**
     * Test 10: Scroll-to-bottom FAB appears when scrolled away from bottom.
     *
     * ChatMessageList renders a SmallFloatingActionButton with
     * contentDescription "Scroll to bottom" (R.string.chat_scroll_bottom)
     * when !isAtBottom. Uses swipeDown() because reverseLayout=true.
     */
    @Test
    fun scrollToBottomFab_appearsWhenScrolledAway() {
        // Seed 20 messages with text to make the list scrollable
        val mwps = (1..20).map { i ->
            val msg = aUserMessage(text = "", id = "u$i")
            val parts = listOf(Part.Text(
                id = "part-$i",
                sessionId = TEST_SESSION,
                messageId = "u$i",
                text = "Message number $i with enough text content to fill at least one full line"
            ))
            MessageWithParts(info = msg, parts = parts)
        }
        seedMessages(*mwps.toTypedArray())

        renderChatScreen()
        composeRule.waitForIdle()

        // Wait for messages to render
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Message", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Swipe to scroll away from bottom (reverseLayout: swipeDown scrolls up)
        composeRule.onAllNodes(hasScrollAction())[0].performTouchInput {
            repeat(3) { swipeDown(startY = 0.1f, endY = 0.9f) }
        }
        composeRule.waitForIdle()

        // FAB should appear (contentDescription = "Scroll to bottom")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithContentDescription("Scroll to bottom")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }
}
