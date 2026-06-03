package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.api.ServerConnection
import dev.minios.ocremote.domain.model.Session
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ManageSessionUseCaseExtendedTest {

    private val api: OpenCodeApi = mockk()
    private lateinit var useCase: ManageSessionUseCase
    private val conn = ServerConnection.from("http://localhost:8080")
    private val baseSession = Session(
        id = "s1",
        title = "Test",
        time = Session.Time(created = 1000L, updated = 1000L)
    )

    @Before
    fun setup() {
        useCase = ManageSessionUseCase(api)
    }

    @Test
    fun `deleteMessage delegates to api`() = runTest {
        coEvery { api.deleteMessage(conn, "s1", "m1") } returns true

        val result = useCase.deleteMessage(conn, "s1", "m1")

        assertTrue(result)
    }

    @Test
    fun `deleteMessage returns false on failure`() = runTest {
        coEvery { api.deleteMessage(conn, "s1", "m1") } returns false

        val result = useCase.deleteMessage(conn, "s1", "m1")

        assertFalse(result)
    }

    @Test
    fun `deleteMessagePart delegates to api`() = runTest {
        coEvery { api.deleteMessagePart(conn, "s1", "m1", 0) } returns true

        val result = useCase.deleteMessagePart(conn, "s1", "m1", 0)

        assertTrue(result)
    }

    @Test
    fun `deleteMessagePart returns false on failure`() = runTest {
        coEvery { api.deleteMessagePart(conn, "s1", "m1", 2) } returns false

        val result = useCase.deleteMessagePart(conn, "s1", "m1", 2)

        assertFalse(result)
    }

    @Test
    fun `archiveSession calls updateSessionFields with archived true`() = runTest {
        val archived = baseSession.copy(
            time = baseSession.time.copy(archived = 2000L)
        )
        coEvery { api.updateSessionFields(conn, "s1", mapOf("archived" to true)) } returns archived

        val result = useCase.archiveSession(conn, "s1")

        assertEquals(2000L, result.time.archived)
    }

    @Test
    fun `unarchiveSession calls updateSessionFields with archived false`() = runTest {
        coEvery { api.updateSessionFields(conn, "s1", mapOf("archived" to false)) } returns baseSession

        val result = useCase.unarchiveSession(conn, "s1")

        assertEquals(null, result.time.archived)
    }

    @Test
    fun `importSession delegates to api`() = runTest {
        val imported = Session(
            id = "imported-1",
            title = "Imported",
            time = Session.Time(created = 3000L, updated = 3000L)
        )
        coEvery { api.importSession(conn, "https://share.example.com/s/abc") } returns imported

        val result = useCase.importSession(conn, "https://share.example.com/s/abc")

        assertEquals("imported-1", result.id)
        assertEquals("Imported", result.title)
    }
}
