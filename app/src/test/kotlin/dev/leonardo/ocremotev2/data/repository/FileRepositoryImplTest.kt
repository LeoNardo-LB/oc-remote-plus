package dev.leonardo.ocremotev2.data.repository

import dev.leonardo.ocremotev2.data.api.OpenCodeApi
import dev.leonardo.ocremotev2.domain.model.ServerConnection
import dev.leonardo.ocremotev2.data.dto.response.FileContentDto
import dev.leonardo.ocremotev2.data.dto.response.FileNodeDto
import dev.leonardo.ocremotev2.domain.model.FileContent
import dev.leonardo.ocremotev2.domain.model.FileNode
import dev.leonardo.ocremotev2.domain.repository.FileRepository
import dev.leonardo.ocremotev2.domain.repository.ServerRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FileRepositoryImplTest {

    private val api: OpenCodeApi = mockk()
    private val serverRepository: ServerRepository = mockk()
    private lateinit var sut: FileRepository

    private val testConn = ServerConnection.from("http://localhost:4096", "opencode", "password")
    private val serverId = "server-1"

    @Before
    fun setup() {
        coEvery { serverRepository.resolveConnection(serverId) } returns testConn
        sut = FileRepositoryImpl(api, serverRepository)
    }

    @Test
    fun `listDirectory success maps DTOs and passes directory`() = runTest {
        val dtos = listOf(
            FileNodeDto(
                name = "OpenCodeApi.kt",
                path = "data/api/OpenCodeApi.kt",
                type = "file",
                absolute = "/abs/path/OpenCodeApi.kt",
                ignored = false
            ),
            FileNodeDto(
                name = "dto",
                path = "data/dto",
                type = "directory",
                absolute = "/abs/path/dto",
                ignored = false
            )
        )
        coEvery { api.listDirectory(testConn, "src", "project") } returns dtos

        val result = sut.listDirectory(serverId, "project", "src")

        assertTrue(result.isSuccess)
        val nodes = result.getOrThrow()
        assertEquals(2, nodes.size)
        assertEquals("OpenCodeApi.kt", nodes[0].name)
        assertEquals("data/api/OpenCodeApi.kt", nodes[0].path)
        assertEquals("dto", nodes[1].name)
    }

    @Test
    fun `listDirectory wraps exception as failure`() = runTest {
        coEvery { api.listDirectory(any(), any(), any()) } throws RuntimeException("network error")

        val result = sut.listDirectory(serverId, "project", "src")

        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("network error"))
    }

    @Test
    fun `getFileContent success injects path`() = runTest {
        val dto = FileContentDto(
            type = "text",
            content = "package dev.leonardo.ocremotev2...",
            encoding = "utf-8"
        )
        coEvery { api.readFile(testConn, "data/api/OpenCodeApi.kt", "project") } returns dto

        val result = sut.getFileContent(serverId, "project", "data/api/OpenCodeApi.kt")

        assertTrue(result.isSuccess)
        val content: FileContent = result.getOrThrow()
        assertEquals("data/api/OpenCodeApi.kt", content.path)
        assertEquals("package dev.leonardo.ocremotev2...", content.content)
    }

    @Test
    fun `getFileContent wraps exception as failure`() = runTest {
        coEvery { api.readFile(any(), any(), any()) } throws RuntimeException("read failed")

        val result = sut.getFileContent(serverId, "project", "data/api/OpenCodeApi.kt")

        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("read failed"))
    }
}
