package dev.minios.ocremote.data.repository.handler

import dev.minios.ocremote.domain.model.*
import dev.minios.ocremote.domain.model.SseEvent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MessageEventHandlerTest {

    private lateinit var handler: MessageEventHandler

    @Before
    fun setup() {
        handler = MessageEventHandler()
    }

    private fun testUserMessage(id: String, sessionId: String) = Message.User(
        id = id,
        sessionId = sessionId,
        time = TimeInfo(created = System.currentTimeMillis())
    )

    private fun testAssistantMessage(id: String, sessionId: String) = Message.Assistant(
        id = id,
        sessionId = sessionId,
        parentId = "parent-$id",
        time = TimeInfo(created = System.currentTimeMillis())
    )

    @Test
    fun `handles MessageUpdated - add new`() {
        val msg = testUserMessage("m1", "s1")
        handler.handle(SseEvent.MessageUpdated(msg), "server1")

        assertEquals(listOf(msg), handler.messages.value["s1"])
    }

    @Test
    fun `handles MessageUpdated - update existing`() {
        val msg = testUserMessage("m1", "s1")
        handler.handle(SseEvent.MessageUpdated(msg), "server1")

        val updated = msg.copy(time = TimeInfo(created = msg.time.created + 1000))
        handler.handle(SseEvent.MessageUpdated(updated), "server1")

        assertEquals(1, handler.messages.value["s1"]!!.size)
        assertEquals(updated, handler.messages.value["s1"]!![0])
    }

    @Test
    fun `handles MessageUpdated - sorts by created descending`() {
        val old = testUserMessage("m1", "s1").copy(time = TimeInfo(created = 1000L))
        val recent = testUserMessage("m2", "s1").copy(time = TimeInfo(created = 3000L))
        val mid = testUserMessage("m3", "s1").copy(time = TimeInfo(created = 2000L))

        handler.handle(SseEvent.MessageUpdated(old), "server1")
        handler.handle(SseEvent.MessageUpdated(recent), "server1")
        handler.handle(SseEvent.MessageUpdated(mid), "server1")

        val msgs = handler.messages.value["s1"]!!
        assertEquals("m2", msgs[0].id)
        assertEquals("m3", msgs[1].id)
        assertEquals("m1", msgs[2].id)
    }

    @Test
    fun `handles MessageRemoved`() {
        handler.handle(SseEvent.MessageUpdated(testUserMessage("m1", "s1")), "server1")
        handler.handle(SseEvent.MessageUpdated(testUserMessage("m2", "s1")), "server1")

        handler.handle(SseEvent.MessageRemoved(sessionId = "s1", messageId = "m1"), "server1")

        assertEquals(1, handler.messages.value["s1"]!!.size)
        assertEquals("m2", handler.messages.value["s1"]!![0].id)
    }

    @Test
    fun `handles MessageRemoved also removes parts`() {
        val part = Part.Text(id = "p1", sessionId = "s1", messageId = "m1")
        handler.handle(SseEvent.MessagePartUpdated(part), "server1")

        handler.handle(SseEvent.MessageRemoved(sessionId = "s1", messageId = "m1"), "server1")

        assertFalse(handler.parts.value.containsKey("m1"))
    }

    @Test
    fun `handles MessagePartUpdated - add new part`() {
        val part = Part.Text(id = "part1", sessionId = "s1", messageId = "m1", text = "Hello")
        handler.handle(SseEvent.MessagePartUpdated(part), "server1")

        assertEquals(listOf(part), handler.parts.value["m1"])
    }

    @Test
    fun `handles MessagePartUpdated - replace existing part`() {
        val original = Part.Text(id = "p1", sessionId = "s1", messageId = "m1", text = "Hello")
        val updated = Part.Text(id = "p1", sessionId = "s1", messageId = "m1", text = "Hello World")
        handler.handle(SseEvent.MessagePartUpdated(original), "server1")
        handler.handle(SseEvent.MessagePartUpdated(updated), "server1")

        assertEquals(1, handler.parts.value["m1"]!!.size)
        assertEquals("Hello World", (handler.parts.value["m1"]!![0] as Part.Text).text)
    }

    @Test
    fun `handles MessagePartDelta - appends text`() {
        val part = Part.Text(id = "part1", sessionId = "s1", messageId = "m1", text = "Hello")
        handler.handle(SseEvent.MessagePartUpdated(part), "server1")

        handler.handle(SseEvent.MessagePartDelta(
            sessionId = "s1", messageId = "m1", partId = "part1",
            field = "text", delta = " World"
        ), "server1")

        assertEquals("Hello World", (handler.parts.value["m1"]!![0] as Part.Text).text)
    }

    @Test
    fun `handles MessagePartDelta - appends to reasoning`() {
        val part = Part.Reasoning(id = "part1", sessionId = "s1", messageId = "m1", text = "Thinking")
        handler.handle(SseEvent.MessagePartUpdated(part), "server1")

        handler.handle(SseEvent.MessagePartDelta(
            sessionId = "s1", messageId = "m1", partId = "part1",
            field = "text", delta = " more"
        ), "server1")

        assertEquals("Thinking more", (handler.parts.value["m1"]!![0] as Part.Reasoning).text)
    }

    @Test
    fun `handles MessagePartDelta does nothing for unknown partId`() {
        handler.handle(SseEvent.MessagePartDelta(
            sessionId = "s1", messageId = "m1", partId = "nonexistent",
            field = "text", delta = "ignored"
        ), "server1")

        assertTrue(handler.parts.value.isEmpty())
    }

    @Test
    fun `handles MessagePartDelta does nothing for non-text part types`() {
        val toolPart = Part.Tool(id = "p1", sessionId = "s1", messageId = "m1", callId = "c1", tool = "bash", state = ToolState.Pending())
        handler.handle(SseEvent.MessagePartUpdated(toolPart), "server1")

        handler.handle(SseEvent.MessagePartDelta(
            sessionId = "s1", messageId = "m1", partId = "p1",
            field = "text", delta = "ignored"
        ), "server1")

        assertEquals(1, handler.parts.value["m1"]!!.size)
        assertTrue(handler.parts.value["m1"]!![0] is Part.Tool)
    }

    @Test
    fun `handles MessagePartRemoved`() {
        val part1 = Part.Text(id = "p1", sessionId = "s1", messageId = "m1")
        val part2 = Part.Text(id = "p2", sessionId = "s1", messageId = "m1")
        handler.handle(SseEvent.MessagePartUpdated(part1), "server1")
        handler.handle(SseEvent.MessagePartUpdated(part2), "server1")

        handler.handle(SseEvent.MessagePartRemoved(sessionId = "s1", messageId = "m1", partId = "p1"), "server1")

        assertEquals(1, handler.parts.value["m1"]!!.size)
        assertEquals("p2", handler.parts.value["m1"]!![0].id)
    }

    @Test
    fun `returns false for non-message events`() {
        val handled = handler.handle(SseEvent.SessionCreated(
            Session(id = "s1", title = "Test", time = Session.Time(created = 1000L, updated = 2000L))
        ), "server1")
        assertFalse(handled)
    }

    @Test
    fun `clearForServer removes messages for sessions`() {
        handler.handle(SseEvent.MessageUpdated(testUserMessage("m1", "s1")), "server1")
        handler.handle(SseEvent.MessageUpdated(testUserMessage("m2", "s2")), "server1")

        handler.clearForServer(setOf("s1"))

        assertNull(handler.messages.value["s1"])
        assertNotNull(handler.messages.value["s2"])
    }

    @Test
    fun `clearForSession removes messages for single session`() {
        handler.handle(SseEvent.MessageUpdated(testUserMessage("m1", "s1")), "server1")
        handler.handle(SseEvent.MessageUpdated(testUserMessage("m2", "s2")), "server1")

        handler.clearForSession("s1")

        assertNull(handler.messages.value["s1"])
        assertNotNull(handler.messages.value["s2"])
    }

    @Test
    fun `setMessages replaces completely`() {
        val msg1 = testUserMessage("m1", "s1")
        val msg2 = testUserMessage("m2", "s1")
        val part1 = Part.Text(id = "p1", sessionId = "s1", messageId = "m1")

        handler.setMessages("s1", listOf(
            MessageWithParts(info = msg1, parts = listOf(part1)),
            MessageWithParts(info = msg2, parts = emptyList())
        ))

        assertEquals(2, handler.messages.value["s1"]!!.size)
        assertEquals(listOf(part1), handler.parts.value["m1"])
    }

    @Test
    fun `mergeMessages preserves SSE-fresh parts`() {
        val existingPart = Part.Text(id = "p1", sessionId = "s1", messageId = "m1", text = "from SSE")
        handler.handle(SseEvent.MessagePartUpdated(existingPart), "server1")

        val msg = testUserMessage("m1", "s1")
        val stalePart = Part.Text(id = "p1", sessionId = "s1", messageId = "m1", text = "from REST")
        handler.mergeMessages("s1", listOf(MessageWithParts(msg, listOf(stalePart))))

        assertEquals("from SSE", (handler.parts.value["m1"]!![0] as Part.Text).text)
    }

    @Test
    fun `clearAll resets everything`() {
        handler.handle(SseEvent.MessageUpdated(testUserMessage("m1", "s1")), "server1")
        handler.clearAll()
        assertTrue(handler.messages.value.isEmpty())
        assertTrue(handler.parts.value.isEmpty())
    }
}
