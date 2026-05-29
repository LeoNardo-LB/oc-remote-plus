package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.data.dto.common.ModelSelection
import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.dto.request.PromptPart
import dev.minios.ocremote.data.api.ServerConnection
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SendMessageUseCaseTest {

    private val api: OpenCodeApi = mockk()
    private val useCase = SendMessageUseCase(api)
    private val conn = ServerConnection.from("http://localhost:8080")

    @Test
    fun `sendPrompt delegates to api`() = runTest {
        val parts = listOf(PromptPart(type = "text", text = "Hello"))
        coEvery { api.promptAsync(any(), any(), any(), any(), any(), any(), any()) } returns Unit

        useCase.sendPrompt(
            conn = conn,
            sessionId = "s1",
            parts = parts,
            model = null,
            agent = "build",
            variant = null,
            directory = null
        )

        coVerify { api.promptAsync(any(), "s1", parts, null, "build", null, null) }
    }

    @Test
    fun `sendPrompt propagates exception`() = runTest {
        val parts = listOf(PromptPart(type = "text", text = "Hello"))
        coEvery { api.promptAsync(any(), any(), any(), any(), any(), any(), any()) } throws RuntimeException("Network error")

        var caught = false
        try {
            useCase.sendPrompt(
                conn = conn,
                sessionId = "s1",
                parts = parts,
                model = ModelSelection("p1", "m1"),
                agent = "build",
                variant = null,
                directory = null
            )
        } catch (e: RuntimeException) {
            caught = true
        }
        assert(caught)
    }
}
