package dev.minios.ocremote.domain.usecase

import app.cash.turbine.test
import dev.minios.ocremote.domain.model.PermissionState
import dev.minios.ocremote.domain.repository.ChatRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagePermissionUseCaseTest {

    private val chatRepository: ChatRepository = mockk()
    private val useCase = ManagePermissionUseCase(chatRepository)

    @Test
    fun `replyPermission returns true on success`() = runTest {
        coEvery { chatRepository.replyPermission("p1", "allow") } returns Result.success(true)

        val result = useCase.replyPermission("p1", "allow")

        assertTrue(result.isSuccess)
        assertEquals(true, result.getOrNull())
    }

    @Test
    fun `replyPermission returns failure on error`() = runTest {
        coEvery { chatRepository.replyPermission("p1", "deny") } returns Result.failure(
            RuntimeException("Connection lost")
        )

        val result = useCase.replyPermission("p1", "deny")

        assertTrue(result.isFailure)
    }
}
