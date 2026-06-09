package dev.minios.ocremote.data.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventReducerTest {
    private val sessionId = "test-session"
    private val model = ModelRef("provider-x", "model-y")

    // ========================================================================
    // 1. Append Events (prepend new messages)
    // ========================================================================

    @Test
    fun `AgentSwitchedEvent creates AgentSwitchedMessage at head`() {
        val event = AgentSwitchedEvent(sessionID = sessionId, id = "as1", agent = "coder")
        val result = EventReducer.reduce(SessionState.EMPTY, event)

        assertEquals(1, result.messages.size)
        val msg = result.messages[0] as AgentSwitchedMessage
        assertEquals("as1", msg.id)
        assertEquals("coder", msg.agent)
        assertNotNull(msg.time.created)
    }

    @Test
    fun `PromptedEvent creates UserMessage at head with correct text`() {
        val prompt = Prompt(text = "hello world")
        val event = PromptedEvent(sessionID = sessionId, id = "m1", prompt = prompt)
        val result = EventReducer.reduce(SessionState.EMPTY, event)

        assertEquals(1, result.messages.size)
        val msg = result.messages[0] as UserMessage
        assertEquals("m1", msg.id)
        assertEquals("hello world", msg.text)
    }

    @Test
    fun `ShellStartedEvent creates ShellMessage with empty output`() {
        val event = ShellStartedEvent(
            sessionID = sessionId, id = "sh1", callID = "call-1", command = "ls"
        )
        val result = EventReducer.reduce(SessionState.EMPTY, event)

        assertEquals(1, result.messages.size)
        val msg = result.messages[0] as ShellMessage
        assertEquals("sh1", msg.id)
        assertEquals("call-1", msg.callId)
        assertEquals("ls", msg.command)
        assertEquals("", msg.output)
        assertNull(msg.time.completed)
    }

    @Test
    fun `messages are in reverse-chronological order - newest first`() {
        val step1 = StepStartedEvent(sessionID = sessionId, id = "step1", agent = "a", model = model)
        val prompted = PromptedEvent(
            sessionID = sessionId, id = "m1", prompt = Prompt(text = "first")
        )
        // step first, then prompted — prompted should be at head
        val state1 = EventReducer.reduce(SessionState.EMPTY, step1)
        val state2 = EventReducer.reduce(state1, prompted)

        assertEquals(2, state2.messages.size)
        assertTrue(state2.messages[0] is UserMessage)
        assertTrue(state2.messages[1] is AssistantMessage)
    }

    // ========================================================================
    // 2. Update Events
    // ========================================================================

    @Test
    fun `ShellEndedEvent updates ShellMessage output and completed time`() {
        val started = ShellStartedEvent(
            sessionID = sessionId, id = "sh1", callID = "call-1", command = "ls"
        )
        val state1 = EventReducer.reduce(SessionState.EMPTY, started)

        val ended = ShellEndedEvent(
            sessionID = sessionId, id = "sh1", callID = "call-1", output = "file1.txt\nfile2.txt"
        )
        val state2 = EventReducer.reduce(state1, ended)

        assertEquals(1, state2.messages.size)
        val msg = state2.messages[0] as ShellMessage
        assertEquals("file1.txt\nfile2.txt", msg.output)
        assertNotNull(msg.time.completed)
    }

    @Test
    fun `StepStartedEvent closes previous Assistant and creates new one`() {
        val step1 = StepStartedEvent(sessionID = sessionId, id = "s1", agent = "a1", model = model)
        val state1 = EventReducer.reduce(SessionState.EMPTY, step1)

        val step2 = StepStartedEvent(sessionID = sessionId, id = "s2", agent = "a2", model = model)
        val state2 = EventReducer.reduce(state1, step2)

        assertEquals(2, state2.messages.size)
        // New step at head
        val newStep = state2.messages[0] as AssistantMessage
        assertEquals("s2", newStep.id)
        assertNull(newStep.time.completed)

        // Previous step closed
        val prevStep = state2.messages[1] as AssistantMessage
        assertEquals("s1", prevStep.id)
        assertNotNull(prevStep.time.completed)
    }

    @Test
    fun `StepEndedEvent sets finish, cost, tokens on Assistant`() {
        val step = StepStartedEvent(sessionID = sessionId, id = "s1", agent = "a", model = model)
        val state1 = EventReducer.reduce(SessionState.EMPTY, step)

        val tokens = TokenUsage(input = 100, output = 50, reasoning = 10)
        val ended = StepEndedEvent(
            sessionID = sessionId, id = "s1", finish = "stop", cost = 0.05, tokens = tokens
        )
        val state2 = EventReducer.reduce(state1, ended)

        val msg = state2.messages[0] as AssistantMessage
        assertEquals("stop", msg.finish)
        assertEquals(0.05, msg.cost!!, 0.001)
        assertEquals(100, msg.tokens!!.input)
        assertEquals(50, msg.tokens!!.output)
        assertNotNull(msg.time.completed)
    }

    // ========================================================================
    // 3. Streaming Deltas
    // ========================================================================

    @Test
    fun `TextStartedEvent adds empty AssistantText to Assistant content`() {
        val step = StepStartedEvent(sessionID = sessionId, id = "s1", agent = "a", model = model)
        val state1 = EventReducer.reduce(SessionState.EMPTY, step)

        val textStarted = TextStartedEvent(sessionID = sessionId, id = "t1", stepID = "s1")
        val state2 = EventReducer.reduce(state1, textStarted)

        val msg = state2.messages[0] as AssistantMessage
        assertEquals(1, msg.content.size)
        val text = msg.content[0] as AssistantText
        assertEquals("t1", text.id)
        assertEquals("", text.text)
    }

    @Test
    fun `TextDeltaEvent appends delta to existing AssistantText`() {
        val step = StepStartedEvent(sessionID = sessionId, id = "s1", agent = "a", model = model)
        val state1 = EventReducer.reduce(SessionState.EMPTY, step)

        val textStarted = TextStartedEvent(sessionID = sessionId, id = "t1", stepID = "s1")
        val state2 = EventReducer.reduce(state1, textStarted)

        val delta1 = TextDeltaEvent(sessionID = sessionId, id = "t1", stepID = "s1", delta = "Hello ")
        val state3 = EventReducer.reduce(state2, delta1)

        val delta2 = TextDeltaEvent(sessionID = sessionId, id = "t1", stepID = "s1", delta = "World")
        val state4 = EventReducer.reduce(state3, delta2)

        val msg = state4.messages[0] as AssistantMessage
        val text = msg.content[0] as AssistantText
        assertEquals("Hello World", text.text)
    }

    @Test
    fun `ReasoningDeltaEvent appends delta to existing AssistantReasoning`() {
        val step = StepStartedEvent(sessionID = sessionId, id = "s1", agent = "a", model = model)
        val state1 = EventReducer.reduce(SessionState.EMPTY, step)

        val reasoningStarted = ReasoningStartedEvent(sessionID = sessionId, id = "r1", stepID = "s1")
        val state2 = EventReducer.reduce(state1, reasoningStarted)

        val delta = ReasoningDeltaEvent(sessionID = sessionId, id = "r1", stepID = "s1", delta = "thinking...")
        val state3 = EventReducer.reduce(state2, delta)

        val msg = state3.messages[0] as AssistantMessage
        val reasoning = msg.content[0] as AssistantReasoning
        assertEquals("thinking...", reasoning.text)
    }

    // ========================================================================
    // 4. Tool State Machine
    // ========================================================================

    @Test
    fun `ToolInputStartedEvent creates Pending tool, ToolCalledEvent transitions to Running`() {
        val step = StepStartedEvent(sessionID = sessionId, id = "s1", agent = "a", model = model)
        val state1 = EventReducer.reduce(SessionState.EMPTY, step)

        val inputStarted = ToolInputStartedEvent(sessionID = sessionId, id = "tool1", stepID = "s1")
        val state2 = EventReducer.reduce(state1, inputStarted)

        val tool = (state2.messages[0] as AssistantMessage).content[0] as AssistantTool
        assertTrue(tool.state is ToolStatePending)
        assertEquals("", tool.name)

        val toolCalled = ToolCalledEvent(
            sessionID = sessionId, id = "tool1", stepID = "s1",
            name = "bash", input = "ls -la"
        )
        val state3 = EventReducer.reduce(state2, toolCalled)

        val updatedTool = (state3.messages[0] as AssistantMessage).content[0] as AssistantTool
        assertEquals("bash", updatedTool.name)
        assertTrue(updatedTool.state is ToolStateRunning)
        assertEquals("ls -la", (updatedTool.state as ToolStateRunning).input)
    }

    @Test
    fun `ToolSuccessEvent transitions to Completed`() {
        val step = StepStartedEvent(sessionID = sessionId, id = "s1", agent = "a", model = model)
        val state1 = EventReducer.reduce(SessionState.EMPTY, step)

        val inputStarted = ToolInputStartedEvent(sessionID = sessionId, id = "tool1", stepID = "s1")
        val state2 = EventReducer.reduce(state1, inputStarted)

        val toolCalled = ToolCalledEvent(
            sessionID = sessionId, id = "tool1", stepID = "s1",
            name = "bash", input = "echo hi"
        )
        val state3 = EventReducer.reduce(state2, toolCalled)

        val success = ToolSuccessEvent(
            sessionID = sessionId, id = "tool1", stepID = "s1",
            outputPaths = listOf("/tmp/out.txt"), result = "done"
        )
        val state4 = EventReducer.reduce(state3, success)

        val tool = (state4.messages[0] as AssistantMessage).content[0] as AssistantTool
        assertTrue(tool.state is ToolStateCompleted)
        val completed = tool.state as ToolStateCompleted
        assertEquals("echo hi", completed.input)
        assertEquals(listOf("/tmp/out.txt"), completed.outputPaths)
        assertEquals("done", completed.result)
        assertNotNull(tool.time.completed)
    }

    @Test
    fun `ToolFailedEvent transitions to Error`() {
        val step = StepStartedEvent(sessionID = sessionId, id = "s1", agent = "a", model = model)
        val state1 = EventReducer.reduce(SessionState.EMPTY, step)

        val inputStarted = ToolInputStartedEvent(sessionID = sessionId, id = "tool1", stepID = "s1")
        val state2 = EventReducer.reduce(state1, inputStarted)

        val toolCalled = ToolCalledEvent(
            sessionID = sessionId, id = "tool1", stepID = "s1",
            name = "bash", input = "exit 1"
        )
        val state3 = EventReducer.reduce(state2, toolCalled)

        val failed = ToolFailedEvent(
            sessionID = sessionId, id = "tool1", stepID = "s1", error = "exit code 1"
        )
        val state4 = EventReducer.reduce(state3, failed)

        val tool = (state4.messages[0] as AssistantMessage).content[0] as AssistantTool
        assertTrue(tool.state is ToolStateError)
        assertEquals("exit code 1", (tool.state as ToolStateError).error)
        assertNotNull(tool.time.completed)
    }

    @Test
    fun `ToolInputDeltaEvent accumulates input in Pending state`() {
        val step = StepStartedEvent(sessionID = sessionId, id = "s1", agent = "a", model = model)
        val state1 = EventReducer.reduce(SessionState.EMPTY, step)

        val inputStarted = ToolInputStartedEvent(sessionID = sessionId, id = "tool1", stepID = "s1")
        val state2 = EventReducer.reduce(state1, inputStarted)

        val delta1 = ToolInputDeltaEvent(sessionID = sessionId, id = "tool1", stepID = "s1", delta = "{")
        val state3 = EventReducer.reduce(state2, delta1)
        val delta2 = ToolInputDeltaEvent(sessionID = sessionId, id = "tool1", stepID = "s1", delta = "\"key\":")
        val state4 = EventReducer.reduce(state3, delta2)
        val delta3 = ToolInputDeltaEvent(sessionID = sessionId, id = "tool1", stepID = "s1", delta = "\"val\"}")
        val state5 = EventReducer.reduce(state4, delta3)

        val tool = (state5.messages[0] as AssistantMessage).content[0] as AssistantTool
        assertTrue(tool.state is ToolStatePending)
        assertEquals("{\"key\":\"val\"}", (tool.state as ToolStatePending).input)
    }

    // ========================================================================
    // 5. Passive Events
    // ========================================================================

    @Test
    fun `PromptAdmittedEvent leaves state unchanged`() {
        val event = PromptAdmittedEvent(sessionID = sessionId)
        val result = EventReducer.reduce(SessionState.EMPTY, event)
        assertEquals(SessionState.EMPTY, result)
    }

    @Test
    fun `RetriedEvent leaves state unchanged`() {
        val event = RetriedEvent(sessionID = sessionId)
        val result = EventReducer.reduce(SessionState.EMPTY, event)
        assertEquals(SessionState.EMPTY, result)
    }

    @Test
    fun `MovedEvent leaves state unchanged`() {
        val event = MovedEvent(sessionID = sessionId)
        val result = EventReducer.reduce(SessionState.EMPTY, event)
        assertEquals(SessionState.EMPTY, result)
    }

    // ========================================================================
    // 6. Edge Cases
    // ========================================================================

    @Test
    fun `TextEndedEvent leaves state unchanged`() {
        val event = TextEndedEvent(sessionID = sessionId, id = "t1", stepID = "s1")
        val result = EventReducer.reduce(SessionState.EMPTY, event)
        assertEquals(SessionState.EMPTY, result)
    }

    @Test
    fun `ReasoningEndedEvent leaves state unchanged`() {
        val event = ReasoningEndedEvent(sessionID = sessionId, id = "r1", stepID = "s1")
        val result = EventReducer.reduce(SessionState.EMPTY, event)
        assertEquals(SessionState.EMPTY, result)
    }

    @Test
    fun `ToolInputEndedEvent leaves state unchanged`() {
        val event = ToolInputEndedEvent(sessionID = sessionId, id = "t1", stepID = "s1")
        val result = EventReducer.reduce(SessionState.EMPTY, event)
        assertEquals(SessionState.EMPTY, result)
    }

    @Test
    fun `delta targeting non-existent step does not crash`() {
        val state = SessionState(messages = emptyList())
        val delta = TextDeltaEvent(sessionID = sessionId, id = "t1", stepID = "no-such-step", delta = "x")
        // Should not throw — just return unchanged state
        val result = EventReducer.reduce(state, delta)
        assertEquals(state, result)
    }

    @Test
    fun `delta targeting wrong stepID does not affect other steps`() {
        val step1 = StepStartedEvent(sessionID = sessionId, id = "s1", agent = "a", model = model)
        val step2 = StepStartedEvent(sessionID = sessionId, id = "s2", agent = "a", model = model)
        val state1 = EventReducer.reduce(SessionState.EMPTY, step1)
        val state2 = EventReducer.reduce(state1, step2)

        // Add text to step1
        val textStarted = TextStartedEvent(sessionID = sessionId, id = "t1", stepID = "s1")
        val state3 = EventReducer.reduce(state2, textStarted)

        // Delta targets step2, but text belongs to step1 — step1 unchanged
        val delta = TextDeltaEvent(sessionID = sessionId, id = "t1", stepID = "s2", delta = "oops")
        val state4 = EventReducer.reduce(state3, delta)

        // step1's text should still be empty (delta targeted step2 which has no matching content)
        val step1Msg = state4.messages[1] as AssistantMessage // step1 is older, at index 1
        assertEquals(1, step1Msg.content.size)
        val text = step1Msg.content[0] as AssistantText
        assertEquals("", text.text)

        // step2 should have no content
        val step2Msg = state4.messages[0] as AssistantMessage
        assertTrue(step2Msg.content.isEmpty())
    }

    @Test
    fun `multiple AssistantMessages, delta targets correct one by stepID`() {
        // Create two steps
        val step1 = StepStartedEvent(sessionID = sessionId, id = "s1", agent = "a1", model = model)
        val state1 = EventReducer.reduce(SessionState.EMPTY, step1)

        val step2 = StepStartedEvent(sessionID = sessionId, id = "s2", agent = "a2", model = model)
        val state2 = EventReducer.reduce(state1, step2)

        // Add text to step1
        val textStarted1 = TextStartedEvent(sessionID = sessionId, id = "t1", stepID = "s1")
        val state3 = EventReducer.reduce(state2, textStarted1)

        // Add text to step2
        val textStarted2 = TextStartedEvent(sessionID = sessionId, id = "t2", stepID = "s2")
        val state4 = EventReducer.reduce(state3, textStarted2)

        // Delta to step2's text
        val delta = TextDeltaEvent(sessionID = sessionId, id = "t2", stepID = "s2", delta = "step2 text")
        val state5 = EventReducer.reduce(state4, delta)

        // step2 (at index 0) has the delta
        val step2Msg = state5.messages[0] as AssistantMessage
        assertEquals("step2 text", (step2Msg.content[0] as AssistantText).text)

        // step1 (at index 1) is unchanged
        val step1Msg = state5.messages[1] as AssistantMessage
        assertEquals("", (step1Msg.content[0] as AssistantText).text)
    }

    @Test
    fun `StepFailedEvent sets error on Assistant`() {
        val step = StepStartedEvent(sessionID = sessionId, id = "s1", agent = "a", model = model)
        val state1 = EventReducer.reduce(SessionState.EMPTY, step)

        val failed = StepFailedEvent(sessionID = sessionId, id = "s1", error = "timeout")
        val state2 = EventReducer.reduce(state1, failed)

        val msg = state2.messages[0] as AssistantMessage
        assertEquals("timeout", msg.error)
        assertNotNull(msg.time.completed)
    }

    @Test
    fun `CompactionEndedEvent creates CompactionMessage at head`() {
        val event = CompactionEndedEvent(
            sessionID = sessionId, id = "c1",
            reason = "too long", summary = "summarized", recent = "recent msgs"
        )
        val result = EventReducer.reduce(SessionState.EMPTY, event)

        assertEquals(1, result.messages.size)
        val msg = result.messages[0] as CompactionMessage
        assertEquals("c1", msg.id)
        assertEquals("too long", msg.reason)
        assertEquals("summarized", msg.summary)
        assertEquals("recent msgs", msg.recent)
    }

    @Test
    fun `ContextUpdatedEvent creates SystemMessage at head`() {
        val event = ContextUpdatedEvent(sessionID = sessionId, id = "ctx1", text = "system prompt")
        val result = EventReducer.reduce(SessionState.EMPTY, event)

        assertEquals(1, result.messages.size)
        val msg = result.messages[0] as SystemMessage
        assertEquals("system prompt", msg.text)
    }

    @Test
    fun `SyntheticEvent creates SyntheticMessage at head`() {
        val event = SyntheticEvent(sessionID = sessionId, id = "syn1", text = "synthetic content")
        val result = EventReducer.reduce(SessionState.EMPTY, event)

        assertEquals(1, result.messages.size)
        val msg = result.messages[0] as SyntheticMessage
        assertEquals("synthetic content", msg.text)
    }
}
