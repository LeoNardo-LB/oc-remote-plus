package dev.leonardo.ocremoteplus.ui.screens.chat.util

import dev.leonardo.ocremoteplus.domain.model.Message
import dev.leonardo.ocremoteplus.domain.model.SessionStatus
import dev.leonardo.ocremoteplus.domain.model.TimeInfo
import dev.leonardo.ocremoteplus.domain.model.UserMsgStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class UserMsgStatusCalculatorTest {

    private fun userMsg(id: String, created: Long = 1000L) = Message.User(
        id = id,
        sessionId = "sess",
        time = TimeInfo(created = created),
    )

    private fun assistantMsg(id: String, completed: Long? = null, created: Long = 2000L) = Message.Assistant(
        id = id,
        sessionId = "sess",
        time = TimeInfo(created = created, completed = completed),
        parentId = "parent",
    )

    @Test
    fun `queued message returns Queued`() {
        val messages = listOf(userMsg("u1"), assistantMsg("a1"), userMsg("u2"))
        val result = calculateUserMsgStatus("u2", setOf("u2"), SessionStatus.Busy, messages)
        assertEquals(UserMsgStatus.Queued, result)
    }

    @Test
    fun `Busy with no next assistant returns Waiting (covers gap)`() {
        val messages = listOf(userMsg("u1"))
        val result = calculateUserMsgStatus("u1", emptySet(), SessionStatus.Busy, messages)
        assertEquals(UserMsgStatus.Waiting, result)
    }

    @Test
    fun `Busy with next assistant completed returns Completed`() {
        val messages = listOf(userMsg("u1"), assistantMsg("a1", completed = 3000L))
        val result = calculateUserMsgStatus("u1", emptySet(), SessionStatus.Busy, messages)
        assertEquals(UserMsgStatus.Completed, result)
    }

    @Test
    fun `Busy with next assistant not completed returns Waiting`() {
        val messages = listOf(userMsg("u1"), assistantMsg("a1", completed = null))
        val result = calculateUserMsgStatus("u1", emptySet(), SessionStatus.Busy, messages)
        assertEquals(UserMsgStatus.Waiting, result)
    }

    @Test
    fun `Idle returns Completed regardless of messages`() {
        val messages = listOf(userMsg("u1"), assistantMsg("a1", completed = null))
        val result = calculateUserMsgStatus("u1", emptySet(), SessionStatus.Idle, messages)
        assertEquals(UserMsgStatus.Completed, result)
    }

    @Test
    fun `Retry returns Completed (independent from circle)`() {
        val messages = listOf(userMsg("u1"), assistantMsg("a1", completed = null))
        val retry = SessionStatus.Retry(attempt = 1, message = "error", next = 9999L)
        val result = calculateUserMsgStatus("u1", emptySet(), retry, messages)
        assertEquals(UserMsgStatus.Completed, result)
    }

    @Test
    fun `message not in list returns Completed`() {
        val messages = listOf(userMsg("u1"))
        val result = calculateUserMsgStatus("unknown", emptySet(), SessionStatus.Busy, messages)
        assertEquals(UserMsgStatus.Completed, result)
    }

    @Test
    fun `calculateAll computes statuses for all user messages`() {
        val messages = listOf(
            userMsg("u1"),
            assistantMsg("a1", completed = 3000L),
            userMsg("u2"),
            assistantMsg("a2", completed = null),
            userMsg("u3"),
        )
        val result = calculateAllUserMsgStatuses(messages, setOf("u3"), SessionStatus.Busy)
        assertEquals(UserMsgStatus.Completed, result["u1"])
        assertEquals(UserMsgStatus.Waiting, result["u2"])
        assertEquals(UserMsgStatus.Queued, result["u3"])
    }
}
