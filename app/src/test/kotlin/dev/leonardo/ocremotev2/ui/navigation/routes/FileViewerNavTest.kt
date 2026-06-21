package dev.leonardo.ocremotev2.ui.navigation.routes

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URI

class FileViewerNavTest {

    private val serverParams = ServerRouteParams(
        serverUrl = "http://192.168.1.100:4096",
        username = "opencode",
        password = "secret#123",
        serverName = "Dev Server",
        serverId = "srv-e5f6g7h8"
    )

    /** Build a mock NavBackStackEntry from a route string, so fromEntry can decode params. */
    private fun buildEntry(route: String): androidx.navigation.NavBackStackEntry {
        // Use java.net.URI (available on JVM) instead of android.net.Uri (stubbed in unit tests)
        val uri = URI("http://dummy/$route")
        val query = uri.rawQuery ?: ""
        val paramMap = query.split("&").associate { part ->
            val idx = part.indexOf('=')
            if (idx >= 0) part.substring(0, idx) to part.substring(idx + 1) else part to ""
        }

        val bundle = mockk<android.os.Bundle>(relaxed = true)
        every { bundle.getString(any()) } answers { paramMap[firstArg<String>()] }

        val entry = mockk<androidx.navigation.NavBackStackEntry>(relaxed = true)
        every { entry.arguments } returns bundle
        return entry
    }

    @Test
    fun `createRoute URL-encodes filePath with slashes`() {
        val filePath = "src/main/kotlin/dev/minios/ocremote/Main.kt"

        val route = FileViewerNav.createRoute(
            serverUrl = serverParams.serverUrl,
            username = serverParams.username,
            password = serverParams.password,
            serverName = serverParams.serverName,
            serverId = serverParams.serverId,
            sessionId = "01H2X3YZ9ABCDEF",
            filePath = filePath,
            source = FileViewerNav.Source.LIVE,
            toolPartIds = ""
        )

        // Slashes in filePath must be encoded
        assert(route.contains("filePath=src%2Fmain%2Fkotlin%2Fdev%2Fminios%2Focremote%2FMain.kt")) {
            "filePath should be URL-encoded, got: $route"
        }
    }

    @Test
    fun `routePattern includes all 5 params`() {
        val pattern = FileViewerNav.routePattern

        val expected = "file_viewer?" +
            "serverUrl={serverUrl}&username={username}&password={password}&" +
            "serverName={serverName}&serverId={serverId}&" +
            "sessionId={sessionId}&filePath={filePath}&source={source}&" +
            "toolPartIds={toolPartIds}&directory={directory}"

        assertEquals(expected, pattern)
    }

    @Test
    fun `fromEntry round-trips source and toolPartIds`() {
        val sessionId = "01H2X3YZ9ABCDEF"
        val filePath = "src/main/kotlin/dev/minios/ocremote/Main.kt"
        val source = FileViewerNav.Source.TOOL_SNAPSHOT
        val toolPartIds = "part-11111111-2222-3333-4444-555555555555,part-aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

        val route = FileViewerNav.createRoute(
            serverUrl = serverParams.serverUrl,
            username = serverParams.username,
            password = serverParams.password,
            serverName = serverParams.serverName,
            serverId = serverParams.serverId,
            sessionId = sessionId,
            filePath = filePath,
            source = source,
            toolPartIds = toolPartIds
        )

        val entry = buildEntry(route)
        val params = FileViewerNav.fromEntry(entry)

        assertEquals(sessionId, params.sessionId)
        assertEquals(filePath, params.filePath)
        assertEquals(FileViewerNav.Source.TOOL_SNAPSHOT, params.source)
        assertEquals(toolPartIds, params.toolPartIds)
        assertEquals("", params.directory)
    }
}
