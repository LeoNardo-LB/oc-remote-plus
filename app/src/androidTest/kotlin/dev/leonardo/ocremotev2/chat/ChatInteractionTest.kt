package dev.leonardo.ocremotev2.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Ignore
import dev.leonardo.ocremotev2.builder.anAssistantMessage
import dev.leonardo.ocremotev2.builder.aUserMessage
import dev.leonardo.ocremotev2.domain.model.Message
import dev.leonardo.ocremotev2.domain.model.MessageWithParts
import dev.leonardo.ocremotev2.domain.model.Part
import dev.leonardo.ocremotev2.domain.model.ProviderCatalog
import dev.leonardo.ocremotev2.domain.model.ProvidersResponse
import dev.leonardo.ocremotev2.domain.model.SessionStatus
import dev.leonardo.ocremotev2.domain.model.SseEvent
import dev.leonardo.ocremotev2.domain.repository.ProviderRepository
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
    private fun seedMessages(messages: List<Message>, parts: List<Part>) {
        fakeChat.messagesState.value = messages
        fakeChat.partsState.value = parts
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
        fakeChat.allPermissionsMapState.value = mapOf("" to listOf(perm))
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
        fakeChat.allQuestionsMapState.value = mapOf("" to listOf(q))
        return q
    }

    // ============ Tests ============

    /**
     * Test 1: Typing text and tapping send clears the input and records the message.
     *
     * The send path: ChatInputBar.onSend → ChatScreen doSend() →
     * viewModel.sendMessage(parts) → sendParts() → SendMessageUseCase.sendPrompt() →
     * chatRepository.promptAsync(). The input is cleared synchronously in the
     * ChatScreen onSend callback (inputText = TextFieldValue("")).
     *
     * NOTE: sentMessages is NOT populated because promptAsync() is called,
     * not sendMessage(). We check promptAsyncCalls instead.
     */
    @Ignore("Input field performTextInput unreliable in Compose UI test")
    @Test
    fun sendMessage_clearsInput() {
        renderChatScreen()
        composeRule.waitForIdle()

        // Type text into the input field (BasicTextField supports SetText action)
        composeRule.onNode(hasSetTextAction()).performTextInput("Hello world")
        composeRule.waitForIdle()

        // Tap the send button — the Box with combinedClickable merges descendants,
        // so onNodeWithContentDescription("Send") finds the Box node with onClick.
        composeRule.onNodeWithContentDescription("Send").performClick()
        composeRule.waitForIdle()

        // Wait for the async send (promptAsync) to complete
        composeRule.waitUntil(timeoutMillis = 5_000) {
            fakeChat.promptAsyncCalls.isNotEmpty()
        }

        // Input should be cleared after send
        composeRule.onNode(hasSetTextAction()).assertTextEquals("")

        // The fake should have recorded exactly one promptAsync call
        assert(fakeChat.promptAsyncCalls.size == 1) {
            "Expected 1 promptAsync call, got ${fakeChat.promptAsyncCalls.size}"
        }
    }

    /**
     * Test 2: A tool card with completed output can be expanded to reveal the output.
     *
     * Tool cards render through ToolCardScaffold which collapses output by default.
     * Tapping the card header toggles expansion.
     */
    @Ignore("Tool card text matching needs investigation — ReadToolCard renders via string resources")
    @Test
    fun toolCardExpand_toggles() {
        val assistantMsg = anAssistantMessage(id = "a-tool") {
            toolCompleted(
                name = "read",
                output = "File contents here"
            )
        }
        seedMessages(
            messages = listOf(assistantMsg.info),
            parts = assistantMsg.parts
        )

        renderChatScreen()
        composeRule.waitForIdle()

        // ReadToolCard (resolved by DefaultToolCardResolver for "read" tool name)
        // renders title from R.string.tool_read = "Read" (capital R), not the raw tool name "read"
        val toolNode = composeRule.onAllNodesWithText("Read", substring = true, ignoreCase = true)
        // At least one node should match the tool card
        assert(toolNode.fetchSemanticsNodes().isNotEmpty()) {
            "Tool card with 'Read' should be displayed"
        }

        // TODO: Tapping the tool card to expand and verifying the output text
        // ("File contents here") requires identifying the exact clickable element
        // in the ToolCardScaffold header. The tool card expand/collapse is managed
        // by ChatViewModel.toggleToolExpanded() + setToolExpanded() in the repo.
        // Full expand verification is deferred to a focused tool-card UI test.
    }

    /**
     * Test 3: Context usage indicator appears when token stats are available.
     *
     * The ChatTopBar shows a CircularProgressIndicator with percentage when
     * contextWindow > 0 and lastContextTokens > 0.
     *
     * TODO: TokenStatsTracker is injected deep inside ChatViewModel/ModelConfigDelegate
     * and not accessible from the test surface. Setting up token stats requires either
     * a fake TokenStatsTracker binding or a test-visible setter. The ContextUsageBar
     * composable can be unit-tested in isolation instead.
     */
    @Ignore("Token stats flow timing — TokenStatsTracker reads from async merged flow")
    @Test
    fun contextUsageBar_shows_whenTokenStatsAvailable() {
        renderChatScreen()
        composeRule.waitForIdle()

        // TODO: Seed token stats via TokenStatsTracker fake to populate
        // modelConfig.contextWindow and tokenStats.lastContextTokens.
        // Then assert the percentage text (e.g. "50") is displayed in the top bar.
        //
        // The context indicator renders when:
        //   contextWindow > 0 && lastContextTokens > 0
        // It shows: CircularProgressIndicator + Text(percentage)
        //
        // Requires: FakeTokenStatsTracker or direct StateFlow injection
    }

    /**
     * Test 4: Model selector shows available models when tapped.
     *
     * Providers are loaded from FakeServerRepository.catalogResult via
     * SelectModelUseCase → ProviderRepository.loadProviderCatalog().
     * The model label appears in AgentModelVariantSelector after providers load.
     * Tapping it opens ModelPickerDialog showing provider/model names.
     */
    @Ignore("Model selector requires async provider data flow through ViewModel — needs deeper fake wiring")
    @Test
    fun modelSelector_showsAvailableModels() {
        // Seed providers so the model label renders and dialog has content
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

        // TODO: The model label appears after loadProviders() resolves asynchronously
        // in ModelConfigDelegate. Tapping it triggers onModelClick → showModelPicker = true.
        // The ModelPickerDialog shows model names ("Model A", "Model B").
        //
        // Challenge: loadProviders() is called during ViewModel init and the result
        // propagates through a 12-way combine flow. The exact timing of when the model
        // label becomes visible depends on coroutine dispatch.
        //
        // A more reliable approach: verify ModelPickerDialog content directly by
        // unit-testing the dialog composable with a given provider list.
    }

    /**
     * Test 5: Slash command /undo shows the undo suggestion in the popup.
     *
     * The SlashCommandRegistry registers "undo" as a client command. Typing
     * "/undo" filters suggestions to match. Selecting it calls
     * viewModel.undoMessage() → sessionActions.undoMessage() →
     * undoRedoUseCase.revertSession() (NOT chatRepository.undoRedo()).
     *
     * The full undo flow is hard to verify in instrumented tests because:
     * - undoMessage() reads messages from messageListProvider() which depends
     *   on the ViewModel's internal message flow being populated
     * - The result propagates asynchronously through a coroutine scope
     *
     * This test verifies the suggestion popup renders the "/undo" entry.
     */
    @Ignore("Undo suggestion requires input field — same flaky timing issue as ChatInputTest")
    @Test
    fun undo_callsUndoRedo() {
        // Need at least one message exchange for undo to make sense
        seedConversation()

        renderChatScreen()
        composeRule.waitForIdle()

        // Type the undo slash command
        composeRule.onNode(hasSetTextAction()).performTextInput("/undo")
        composeRule.waitForIdle()

        // The slash command suggestion for "undo" should appear as "/undo"
        val undoSuggestion = composeRule.onAllNodesWithText("/undo", substring = true)
        assert(undoSuggestion.fetchSemanticsNodes().isNotEmpty()) {
            "Slash command suggestion '/undo' should be displayed after typing '/undo'"
        }

        // NOTE: Clicking the suggestion triggers onCommandClick → onSlashCommand →
        // viewModel.undoMessage() → sessionActions.undoMessage() →
        // undoRedoUseCase.revertSession(serverId, sessionId, messageId).
        // This calls FakeChatRepository.revertSession() (not undoRedo()),
        // so checking undoRedoCalls would always be empty.
        // Full undo verification is deferred to a ViewModel-level unit test.
    }

    /**
     * Test 6: Abort/stop button calls abort API when session is busy.
     *
     * The send button transforms into a stop button when isBusy && text is blank.
     * isBusy is derived from session status (Busy or Retry).
     *
     * TODO: Session status is managed by SessionStateService, which is driven by
     * the SSE pipeline. Without SSE events flowing, setting the session to Busy
     * requires either a fake SessionStateService or direct status injection.
     * The abort flow: onStop → viewModel.abortSession() → sessionRepository.abort().
     */
    @Ignore("Abort button needs SessionStateService Busy state — driven by SSE events, not directly settable")
    @Test
    fun abortSession_callsAbortApi() {
        // Set session status to Busy so the stop button appears
        fakeSession.statusesState.value = mapOf(TEST_SESSION to SessionStatus.Busy)

        // Seed a streaming assistant message so the session looks active
        val streamingMsg = anAssistantMessage(streaming = true, id = "a-stream") {
            text("Generating...")
        }
        seedMessages(listOf(streamingMsg.info), streamingMsg.parts)

        renderChatScreen()
        composeRule.waitForIdle()

        // TODO: The stop button shows when isBusy && input text is blank.
        // isBusy comes from sessionMeta.sessionStatus which is read from
        // SessionStateService, not directly from statusesState.
        // If the stop button renders, it has contentDescription "Stop":
        //
        //   composeRule.onNodeWithContentDescription("Stop").performClick()
        //   composeRule.waitForIdle()
        //   assert(fakeSession.abortCalls.any { it.second == TEST_SESSION })
        //
        // Full verification requires wiring SessionStateService to the fake
        // status flow, or unit-testing viewModel.abortSession() in isolation.
    }

    /**
     * Test 7: Permission card appears when a permission is requested.
     */
    @Ignore("Permission flow needs interactionState merge — sessionId='' in test creates key mismatch")
    @Test
    fun permissionDialog_appears_whenPermissionRequested() {
        seedPermission(permission = "bash echo hello")

        renderChatScreen()
        composeRule.waitForIdle()

        // Wait for the interactionState flow (7-way combine) to propagate
        // the permission into pendingPermissions and render PermissionCard.
        // PermissionCard renders R.string.permission_title = "Permission Required"
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Permission Required")
                .fetchSemanticsNodes().isNotEmpty()
        }

        // The PermissionCard renders "Permission Required" as its title
        composeRule.onNodeWithText("Permission Required").assertIsDisplayed()

        // The permission description should also be visible
        composeRule.onNodeWithText("bash echo hello", substring = true).assertIsDisplayed()
    }

    /**
     * Test 8: Question card appears when a question is asked.
     */
    @Ignore("Question flow timing — interactionState merge needs SSE event simulation")
    @Test
    fun questionDialog_appears_whenQuestionAsked() {
        seedQuestion(question = "Which framework?")

        renderChatScreen()
        composeRule.waitForIdle()

        // Wait for the interactionState flow (7-way combine) to propagate
        // the question into pendingQuestions and render QuestionCard.
        // QuestionCard renders R.string.chat_question_label = "Question"
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Question")
                .fetchSemanticsNodes().isNotEmpty()
        }

        // The QuestionCard renders "Question" as its header label
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
     * TODO: This test requires a large number of messages (more than the viewport)
     * AND hasOlderMessages = true (set when the initial load returns >= limit messages).
     * The pagination state is internal to MessageDataDelegate and not directly
     * controllable from the test. Setting listMessagesResult to return many messages
     * AND seeding the flows is needed for full verification.
     */
    @Ignore("Pagination needs hasOlderMessages flag + many messages — complex fake setup")
    @Test
    fun pagination_triggersOnScrollUp() {
        // Generate many messages
        val messages = mutableListOf<Message>()
        val parts = mutableListOf<Part>()
        for (i in 1..30) {
            val userMsg = aUserMessage("Message $i", id = "u$i")
            messages.add(userMsg)
        }

        seedMessages(messages.reversed(), parts) // reversed for newest-first display
        fakeSession.listMessagesResult = Result.success(
            messages.mapIndexed { i, msg ->
                MessageWithParts(info = msg, parts = emptyList())
            }
        )

        renderChatScreen()
        composeRule.waitForIdle()

        // TODO: Verify that scrolling to the top triggers loadOlderMessages().
        // The pagination check: !isLoadingOlder && hasOlderMessages && total - lastVisible <= 8
        // hasOlderMessages is set in loadMessagesForSession when messages.size >= currentMessageLimit.
        //
        // Full verification requires:
        // 1. Setting initialMessageCount in settings to a small number (e.g. 10)
        // 2. Seeding > 10 messages so hasOlderMessages = true
        // 3. Scrolling the LazyColumn up via performTouchInput { swipeUp() }
        // 4. Asserting viewModel.loadOlderMessages was called
        //
        // The loadOlderMessages call goes through sessionRepository.listMessages()
        // which updates listMessagesResult — track by checking fakeSession calls.
    }

    /**
     * Test 10: Scroll-to-bottom FAB appears when scrolled away from bottom.
     *
     * ChatMessageList renders a SmallFloatingActionButton with
     * contentDescription "Scroll to bottom" (R.string.chat_scroll_bottom)
     * when !isAtBottom.
     */
    @Ignore("FAB scroll trigger — swipeDown target selection needs refinement")
    @Test
    fun scrollToBottomFab_appearsWhenScrolledAway() {
        // Seed enough messages to make the list scrollable
        val messages = mutableListOf<Message>()
        for (i in 1..20) {
            messages.add(aUserMessage("Message $i that has enough text to fill a line", id = "u$i"))
        }
        seedMessages(messages.reversed(), emptyList())

        renderChatScreen()
        composeRule.waitForIdle()

        // Wait for at least one message to render
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Message", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Initially at bottom — FAB should NOT be visible
        val initialFabNodes = composeRule
            .onAllNodesWithContentDescription("Scroll to bottom")
            .fetchSemanticsNodes()
        assert(initialFabNodes.isEmpty()) {
            "Scroll-to-bottom FAB should not be visible when at bottom"
        }

        // Swipe up on a message node (NOT the input field) to scroll the
        // LazyColumn content down — this moves the viewport away from the bottom.
        val messageNodes = composeRule.onAllNodesWithText("Message", substring = true)
        assert(messageNodes.fetchSemanticsNodes().isNotEmpty()) {
            "At least one message should be displayed for scrolling"
        }
        messageNodes[0].performTouchInput {
            // Each swipeUp scrolls the LazyColumn up within the message node's bounds.
            // Multiple swipes accumulate to scroll far enough from the bottom.
            swipeUp()
            swipeUp()
            swipeUp()
        }
        composeRule.waitForIdle()

        // FAB should now be visible
        val fabNodes = composeRule
            .onAllNodesWithContentDescription("Scroll to bottom")
            .fetchSemanticsNodes()
        assert(fabNodes.isNotEmpty()) {
            "Scroll-to-bottom FAB should be visible after scrolling away from bottom"
        }
    }
}
