package dev.minios.ocremote.ui.screens.chat.util

import dev.minios.ocremote.domain.model.Message
import dev.minios.ocremote.domain.model.MessageWithParts
import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.domain.model.TimeInfo
import dev.minios.ocremote.domain.model.ToolState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContextStatsTest {

    private fun userMsg(id: String, textLen: Int) = MessageWithParts(
        Message.User(
            id = id,
            sessionId = "s",
            time = TimeInfo(created = 0)
        ),
        listOf(Part.Text("p-$id", "s", id, "x".repeat(textLen)))
    )

    private fun assistantMsg(id: String, textLen: Int = 0, toolOutputLen: Int = 0) = MessageWithParts(
        Message.Assistant(
            id = id,
            sessionId = "s",
            time = TimeInfo(created = 0),
            parentId = "p-$id"
        ),
        buildList {
            if (textLen > 0) add(Part.Text("t-$id", "s", id, "y".repeat(textLen)))
            if (toolOutputLen > 0) add(
                Part.Tool(
                    "tl-$id", "s", id, "c-$id", "read",
                    ToolState.Completed(output = "z".repeat(toolOutputLen))
                )
            )
        }
    )

    @Test
    fun `breakdown estimates tokens as chars divided by 4`() {
        // user text 40 chars -> 10 tokens
        val msgs = listOf(userMsg("u1", 40))
        val b = estimateContextBreakdown(msgs, realInput = 100)
        val userSeg = b.segments.first { it.role == BreakdownRole.USER }
        assertEquals(10, userSeg.estimatedTokens)
    }

    @Test
    fun `other absorbs difference from real input`() {
        val msgs = listOf(userMsg("u1", 40))  // 10 tokens user
        val b = estimateContextBreakdown(msgs, realInput = 100)
        val otherSeg = b.segments.first { it.role == BreakdownRole.OTHER }
        assertEquals(90, otherSeg.estimatedTokens)  // 100 - 10
    }

    @Test
    fun `other is zero when estimate exceeds input`() {
        val msgs = listOf(userMsg("u1", 4000))  // 1000 tokens
        val b = estimateContextBreakdown(msgs, realInput = 100)
        val otherSeg = b.segments.firstOrNull { it.role == BreakdownRole.OTHER }
        // other = max(0, 100 - 1000) = 0 -> filtered out
        assertNull(otherSeg)
    }

    @Test
    fun `percent is tokens over real input`() {
        val msgs = listOf(userMsg("u1", 40))
        val b = estimateContextBreakdown(msgs, realInput = 200)
        val userSeg = b.segments.first { it.role == BreakdownRole.USER }
        assertEquals(0.05f, userSeg.percent, 0.001f)  // 10/200
    }

    @Test
    fun `countMessages splits user and assistant`() {
        val msgs = listOf(userMsg("u1", 1), assistantMsg("a1"), assistantMsg("a2"))
        val c = countMessages(msgs.map { it.info })
        assertEquals(1, c.user)
        assertEquals(2, c.assistant)
    }

    @Test
    fun `cacheHitRate is cacheRead over input`() {
        assertEquals(0.5f, cacheHitRate(cacheRead = 50, input = 100)!!, 0.001f)
    }
}
