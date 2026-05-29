package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.domain.model.Message
import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.domain.model.TimeInfo
import dev.minios.ocremote.domain.repository.ChatRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SendMessageUseCaseTest {

    private val chatRepository: ChatRepository = mockk()
    private val useCase = SendMessageUseCase(chatRepository)

    @Test
    fun `invoke returns message on success`() = runTest {
        val sessionId = "session-1"
        val parts = listOf(
            Part.Text(
                id = "p1",
                sessionId = sessionId,
                messageId = "m1",
                text = "Hello"
            )
        )
        val expectedMessage = Message.User(
            id = "m1",
            sessionId = sessionId,
            time = TimeInfo(created = 1000L)
        )
        coEvery { chatRepository.sendMessage(sessionId, parts) } returns Result.success(expectedMessage)

        val result = useCase(sessionId, parts)

        assertTrue(result.isSuccess)
        assertEquals(expectedMessage, result.getOrNull())
    }

    @Test
    fun `invoke returns failure when repository fails`() = runTest {
        val sessionId = "session-1"
        val parts = emptyList<Part>()
        val exception = RuntimeException("Network error")
        coEvery { chatRepository.sendMessage(sessionId, parts) } returns Result.failure(exception)

        val result = useCase(sessionId, parts)

        assertTrue(result.isFailure)
        assertEquals("Network error", result.exceptionOrNull()?.message)
    }

    @Test
    fun `invoke propagates repository exception`() = runTest {
        val sessionId = "session-2"
        val parts = emptyList<Part>()
        coEvery { chatRepository.sendMessage(sessionId, parts) } returns Result.failure(
            IllegalStateException("Session not found")
        )

        val result = useCase(sessionId, parts)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }
}
