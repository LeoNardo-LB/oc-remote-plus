package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.dto.response.PermissionRequest
import dev.minios.ocremote.data.api.ServerConnection
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagePermissionUseCaseTest {

    private val api: OpenCodeApi = mockk()
    private val useCase = ManagePermissionUseCase(api)
    private val conn = ServerConnection.from("http://localhost:8080")

    @Test
    fun `replyToPermission delegates to api and returns true`() = runTest {
        coEvery { api.replyToPermission(any(), "p1", "allow", any()) } returns true

        val result = useCase.replyToPermission(conn, "p1", "allow", null)

        assertTrue(result)
    }

    @Test
    fun `replyToPermission delegates to api and returns false`() = runTest {
        coEvery { api.replyToPermission(any(), "p1", "deny", any()) } returns false

        val result = useCase.replyToPermission(conn, "p1", "deny", null)

        assertEquals(false, result)
    }
}
