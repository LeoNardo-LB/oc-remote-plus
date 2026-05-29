package dev.minios.ocremote.domain.usecase

import app.cash.turbine.test
import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.domain.repository.SessionRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManageSessionUseCaseTest {

    private val sessionRepository: SessionRepository = mockk()
    private val useCase = ManageSessionUseCase(sessionRepository)

    @Test
    fun `getSessionsFlow emits session list`() = runTest {
        val sessions = listOf(Session(id = "s1", title = "Chat", time = Session.Time(created = 1000L, updated = 1000L)))
        every { sessionRepository.getSessionsFlow("server-1") } returns flowOf(sessions)

        useCase.getSessionsFlow("server-1").test {
            assertEquals(sessions, awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `switchSession returns success`() = runTest {
        coEvery { sessionRepository.switchSession("s1") } returns Result.success(Unit)

        val result = useCase.switchSession("s1")

        assertTrue(result.isSuccess)
    }
}
