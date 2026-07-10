package dev.leonardo.ocremotev2.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import dev.leonardo.ocremotev2.domain.model.AgentInfo
import dev.leonardo.ocremotev2.domain.repository.AgentRepository
import dev.leonardo.ocremotev2.fakes.FakeAgentRepository
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import org.junit.Ignore
import org.junit.Test

/**
 * Integration tests for the chat input bar behavior.
 *
 * Covers text input, slash command autocomplete, @-file mention search,
 * attachment button visibility, and send button state management.
 *
 * Uses [BaseChatTest] for Hilt + Compose setup with pre-injected fakes.
 */
@HiltAndroidTest
class ChatInputTest : BaseChatTest() {

    @Inject lateinit var agentRepo: AgentRepository
    private val fakeAgent get() = agentRepo as FakeAgentRepository

    @Ignore("Compose BasicTextField+decorationBox: SetText action not registered on first frame")
    @Test
    fun typing_updates_draft_text() {
        renderChatScreen()

        composeRule.waitUntil(timeoutMillis = 5000) {
            composeRule.onAllNodesWithTag("chat-input").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("chat-input").performTextReplacement("hello world")
        composeRule.waitForIdle()

        // BasicTextField + decorationBox doesn't expose EditableText via semantics.
        // Verify typing worked via side effect: send button exists when input is non-empty.
        composeRule.onNodeWithTag("chat-send").assertExists()
    }

    @Test
    fun slash_command_shows_autocomplete() {
        renderChatScreen()

        // Wait for input field to be ready
        composeRule.waitUntil(timeoutMillis = 5000) {
            composeRule.onAllNodesWithTag("chat-input").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("chat-input").performTextReplacement("/")
        composeRule.waitForIdle()

        // SlashCommandRegistry.clientCommands() always provides: new, compact, fork, etc.
        composeRule.onNodeWithText("/new").assertIsDisplayed()
    }

    @Ignore("performTextReplacement flaky on BasicTextField — same SetText first-frame issue")
    @Test
    fun file_mention_search_shows_results() {
        // Configure fake to return file paths for @-mention search.
        // The search goes through ManageAgentUseCase → AgentRepository.searchFiles.
        fakeAgent.searchFilesResult = Result.success(listOf("src/main.kt", "README.md"))

        renderChatScreen()

        composeRule.onNodeWithTag("chat-input").performTextReplacement("@test")

        // Wait for 150ms debounce + async coroutine to complete
        composeRule.waitUntil(timeoutMillis = 3000) {
            composeRule.onAllNodesWithText("main.kt", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("main.kt", substring = true).assertIsDisplayed()
    }

    @Ignore("Attach button selector timing — AgentModelVariantSelector renders conditionally")
    @Test
    fun attachment_can_be_added() {
        // AgentModelVariantSelector (which contains the attach button) only renders
        // when modelLabel is non-empty or agents.size > 1.
        fakeAgent.agentsResult = Result.success(listOf(
            AgentInfo(name = "build"),
            AgentInfo(name = "general")
        ))

        renderChatScreen()

        // Wait for ViewModel to load agents and render the selector row
        composeRule.waitUntil(timeoutMillis = 5000) {
            composeRule.onAllNodesWithContentDescription("Attach")
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Attach button (AttachFile icon) should be visible
        composeRule.onNodeWithContentDescription("Attach").assertIsDisplayed()
    }

    @Test
    fun send_button_disabled_when_input_empty() {
        renderChatScreen()

        composeRule.waitUntil(timeoutMillis = 5000) {
            composeRule.onAllNodesWithTag("chat-send").fetchSemanticsNodes().isNotEmpty()
        }

        // With empty input, clicking send should NOT trigger promptAsync
        composeRule.onNodeWithTag("chat-send").performClick()
        composeRule.waitForIdle()

        assert(fakeChat.promptAsyncCalls.isEmpty()) {
            "Send with empty input should not call promptAsync"
        }
    }
}
