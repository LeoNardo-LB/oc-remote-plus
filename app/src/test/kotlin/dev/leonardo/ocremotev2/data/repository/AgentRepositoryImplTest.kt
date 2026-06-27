package dev.leonardo.ocremotev2.data.repository

import dev.leonardo.ocremotev2.data.api.file.FileApi
import dev.leonardo.ocremotev2.data.api.system.SystemApi
import dev.leonardo.ocremotev2.domain.repository.AgentRepository
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Test

class AgentRepositoryImplTest {
    private val systemApi: SystemApi = mockk(relaxed = true)
    private val fileApi: FileApi = mockk(relaxed = true)
    private val serverRepo: ServerDataStore = mockk(relaxed = true)

    @Test
    fun `impl creates successfully`() = runTest {
        val repo: AgentRepository = AgentRepositoryImpl(systemApi, fileApi, serverRepo)
        assertNotNull(repo)
    }
}
