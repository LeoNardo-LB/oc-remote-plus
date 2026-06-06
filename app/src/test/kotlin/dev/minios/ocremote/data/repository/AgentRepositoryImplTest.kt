package dev.minios.ocremote.data.repository

import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.domain.repository.AgentRepository
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Test

class AgentRepositoryImplTest {
    private val api: OpenCodeApi = mockk(relaxed = true)
    private val serverRepo: ServerDataStore = mockk(relaxed = true)

    @Test
    fun `impl creates successfully`() = runTest {
        val repo: AgentRepository = AgentRepositoryImpl(api, serverRepo)
        assertNotNull(repo)
    }
}
