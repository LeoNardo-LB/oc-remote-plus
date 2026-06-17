package dev.minios.ocremote.ui.screens.chat.util

import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.domain.model.ToolState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PartGroupingTest {

    private fun tool(id: String, name: String) = Part.Tool(
        id = id, sessionId = "s", messageId = "m", callId = id, tool = name,
        state = ToolState.Completed(output = "")
    )

    private fun text(id: String) = Part.Text(id, "s", "m", "hello")

    @Test fun `empty list returns empty`() {
        assertTrue(groupContextTools(emptyList()).isEmpty())
    }

    @Test fun `single context tool forms a group`() {
        val result = groupContextTools(listOf(tool("t1", "read")))
        assertEquals(1, result.size)
        assertTrue(result[0] is PartRenderUnit.ContextGroup)
    }

    @Test fun `consecutive context tools merge into one group`() {
        val parts = listOf(tool("t1", "read"), tool("t2", "glob"), tool("t3", "grep"))
        val result = groupContextTools(parts)
        assertEquals(1, result.size)
        val group = result[0] as PartRenderUnit.ContextGroup
        assertEquals(3, group.tools.size)
    }

    @Test fun `non-context tool breaks the group`() {
        val parts = listOf(
            tool("t1", "read"), tool("t2", "bash"), tool("t3", "read")
        )
        val result = groupContextTools(parts)
        assertEquals(3, result.size)  // group(t1) + single(bash) + group(t3)
        assertTrue(result[0] is PartRenderUnit.ContextGroup)
        assertTrue(result[1] is PartRenderUnit.Single)
        assertTrue(result[2] is PartRenderUnit.ContextGroup)
    }

    @Test fun `all 4 context tool types recognized`() {
        val parts = listOf(
            tool("a", "read"), tool("b", "glob"), tool("c", "grep"), tool("d", "list")
        )
        val result = groupContextTools(parts)
        assertEquals(1, result.size)
        assertEquals(4, (result[0] as PartRenderUnit.ContextGroup).tools.size)
    }

    @Test fun `non-context tools are singles`() {
        val parts = listOf(tool("t1", "bash"), tool("t2", "edit"))
        val result = groupContextTools(parts)
        assertEquals(2, result.size)
        assertTrue(result.all { it is PartRenderUnit.Single })
    }

    @Test fun `summary counts read_search_list_write correctly`() {
        val tools = listOf(
            tool("a", "read"), tool("b", "read"),
            tool("c", "glob"), tool("d", "grep"),
            tool("e", "list"), tool("f", "write")
        )
        val s = contextToolSummary(tools)
        assertEquals(2, s.read)
        assertEquals(2, s.search)   // glob + grep
        assertEquals(1, s.list)
        assertEquals(1, s.write)
    }

    @Test fun `listDirectory is grouped as context tool`() {
        val parts = listOf(tool("t1", "listDirectory"), tool("t2", "read"))
        val result = groupContextTools(parts)
        assertEquals(1, result.size)
        assertTrue(result[0] is PartRenderUnit.ContextGroup)
    }

    @Test fun `write is grouped as context tool`() {
        val parts = listOf(tool("t1", "read"), tool("t2", "write"))
        val result = groupContextTools(parts)
        assertEquals(1, result.size)
        assertTrue(result[0] is PartRenderUnit.ContextGroup)
    }
}
