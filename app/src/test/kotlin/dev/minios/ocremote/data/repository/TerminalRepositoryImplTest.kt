package dev.minios.ocremote.data.repository

import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.domain.repository.TerminalRepository
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Test

class TerminalRepositoryImplTest {
    private val api: OpenCodeApi = mockk(relaxed = true)
    private val serverRepo: ServerDataStore = mockk(relaxed = true)

    @Test
    fun `impl creates successfully`() = runTest {
        val repo: TerminalRepository = TerminalRepositoryImpl(api, serverRepo)
        assertNotNull(repo)
    }
}
