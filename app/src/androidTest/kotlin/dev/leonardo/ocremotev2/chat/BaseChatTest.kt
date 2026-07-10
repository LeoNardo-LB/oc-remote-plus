package dev.leonardo.ocremotev2.chat

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.leonardo.ocremotev2.HiltComponentActivity
import dev.leonardo.ocremotev2.builder.aSession
import dev.leonardo.ocremotev2.domain.model.SessionStatus
import dev.leonardo.ocremotev2.domain.repository.ChatRepository
import dev.leonardo.ocremotev2.domain.repository.SessionRepository
import dev.leonardo.ocremotev2.domain.repository.SettingsRepository
import dev.leonardo.ocremotev2.fakes.FakeChatRepository
import dev.leonardo.ocremotev2.fakes.FakeSessionRepository
import dev.leonardo.ocremotev2.fakes.FakeSettingsRepository
import dev.leonardo.ocremotev2.ui.screens.chat.ChatScreen
import dev.leonardo.ocremotev2.ui.theme.OpenCodeTheme
import org.junit.Before
import org.junit.Rule
import javax.inject.Inject

/**
 * Base class for ChatScreen integration tests.
 *
 * Provides the standard Hilt + Compose setup pattern verified by ChatSmokeTest.
 * Subclasses get pre-injected fakes and a [renderChatScreen] helper.
 */
@HiltAndroidTest
abstract class BaseChatTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltComponentActivity>()

    @Inject lateinit var chatRepo: ChatRepository
    @Inject lateinit var sessionRepo: SessionRepository
    @Inject lateinit var settingsRepo: SettingsRepository

    protected val fakeChat get() = chatRepo as FakeChatRepository
    protected val fakeSession get() = sessionRepo as FakeSessionRepository
    protected val fakeSettings get() = settingsRepo as FakeSettingsRepository

    protected companion object {
        const val TEST_SERVER = "test-server"
        const val TEST_SESSION = "test-session"
    }

    @Before
    open fun setup() {
        hiltRule.inject()
        // Seed a basic session so ChatViewModel doesn't crash on empty state
        fakeSession.sessionsState.value = listOf(
            aSession(id = TEST_SESSION, title = "Test", status = SessionStatus.Idle)
        )
    }

    /**
     * Render ChatScreen with theme wrapper. Call after configuring fake state.
     */
    protected fun renderChatScreen(
        serverId: String = TEST_SERVER,
        sessionId: String = TEST_SESSION
    ) {
        composeRule.setContent {
            OpenCodeTheme {
                ChatScreen(
                    serverId = serverId,
                    sessionId = sessionId,
                    onNavigateBack = {}
                )
            }
        }
        composeRule.waitForIdle()
    }
}
