package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.domain.model.QuestionState
import dev.minios.ocremote.domain.repository.ChatRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class ManageQuestionUseCaseTest {

    private val chatRepository: ChatRepository = mockk()
    private val useCase = ManageQuestionUseCase(chatRepository)

    @Test
    fun `replyQuestion returns true on success`() = runTest {
        coEvery { chatRepository.replyQuestion("q1", "yes") } returns Result.success(true)

        val result = useCase.replyQuestion("q1", "yes")

        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull() == true)
    }

    @Test
    fun `replyQuestion returns failure on error`() = runTest {
        coEvery { chatRepository.replyQuestion("q1", "no") } returns Result.failure(
            RuntimeException("Timeout")
        )

        val result = useCase.replyQuestion("q1", "no")

        assertTrue(result.isFailure)
    }
}
