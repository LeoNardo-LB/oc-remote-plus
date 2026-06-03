# Layer 3: Fine-grained Event Processing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Consume all 25 `session.next.*` event types for real-time status tracking of tool execution, reasoning, streaming, and compaction.

**Architecture:** New `SessionNextEvent` sealed class nested in SseEvent, parsed in SseClient, dispatched via new handler in EventDispatcher. Streaming state tracker manages part-level streaming lifecycle. New Compose components display tool progress, step progress, and compaction status.

**Tech Stack:** Kotlin, kotlinx.serialization, kotlinx.coroutines (StateFlow), Jetpack Compose (Material 3), Hilt, MockK, Turbine, coroutines-test

**Depends on:** Layer 1 item 1.9 (SessionRetryCard) for item 3.11 only

**Spec reference:** `docs/superpowers/specs/2026-06-04-gap-fix-design.md` → Layer 3

---

## File Structure

| Action | Path | Responsibility |
|--------|------|----------------|
| CREATE | `app/src/main/kotlin/dev/minios/ocremote/domain/model/SessionNextEvent.kt` | 25 event types sealed class |
| CREATE | `app/src/main/kotlin/dev/minios/ocremote/data/repository/handler/SessionNextEventHandler.kt` | Handler for session.next.* events |
| CREATE | `app/src/main/kotlin/dev/minios/ocremote/domain/tracker/StreamingStateTracker.kt` | Part-level streaming state machine |
| CREATE | `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/ToolProgressCard.kt` | Tool execution progress card |
| CREATE | `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/StepProgressIndicator.kt` | Step lifecycle indicator |
| CREATE | `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/CompactionBanner.kt` | Context compaction banner |
| EDIT | `app/src/main/kotlin/dev/minios/ocremote/data/api/SseClient.kt` | Add `session.next.*` parsing |
| EDIT | `app/src/main/kotlin/dev/minios/ocremote/data/repository/EventDispatcher.kt` | Wire SessionNextEventHandler |
| CREATE | `app/src/test/kotlin/dev/minios/ocremote/domain/model/SessionNextEventTest.kt` | Model + serialization tests |
| CREATE | `app/src/test/kotlin/dev/minios/ocremote/data/repository/handler/SessionNextEventHandlerTest.kt` | Handler unit tests |
| CREATE | `app/src/test/kotlin/dev/minios/ocremote/domain/tracker/StreamingStateTrackerTest.kt` | State machine tests |
| CREATE | `app/src/test/kotlin/dev/minios/ocremote/data/api/SseClientSessionNextTest.kt` | Parsing integration tests |

---

## Tasks

### Task 1: SessionNextEvent Domain Model (P0)

**Create the sealed class with all ~25 event types.**

> TDD: Write serialization tests first → verify fail → implement model → verify pass

#### Step 1.1: Write failing test for SessionNextEvent model

File: `app/src/test/kotlin/dev/minios/ocremote/domain/model/SessionNextEventTest.kt`

```kotlin
package dev.minios.ocremote.domain.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SessionNextEventTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ============ Agent/Model Switching ============

    @Test
    fun `AgentSwitched parses correctly`() {
        val eventJson = """{"type":"session.next.agent.switched","sessionID":"s1","agent":"code"}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        assertTrue(event is SessionNextEvent.AgentSwitched)
        assertEquals("s1", (event as SessionNextEvent.AgentSwitched).sessionId)
        assertEquals("code", event.agent)
    }

    @Test
    fun `ModelSwitched parses correctly`() {
        val eventJson = """{"type":"session.next.model.switched","sessionID":"s1","providerID":"anthropic","modelID":"claude-4-sonnet"}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        assertTrue(event is SessionNextEvent.ModelSwitched)
        assertEquals("anthropic", (event as SessionNextEvent.ModelSwitched).providerId)
        assertEquals("claude-4-sonnet", event.modelId)
    }

    // ============ Text Streaming ============

    @Test
    fun `TextStarted parses correctly`() {
        val eventJson = """{"type":"session.next.text.started","sessionID":"s1","messageID":"m1","partID":"p1"}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        assertTrue(event is SessionNextEvent.TextStarted)
        assertEquals("p1", (event as SessionNextEvent.TextStarted).partId)
    }

    @Test
    fun `TextDelta parses correctly`() {
        val eventJson = """{"type":"session.next.text.delta","sessionID":"s1","messageID":"m1","partID":"p1","delta":"hello"}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        assertTrue(event is SessionNextEvent.TextDelta)
        assertEquals("hello", (event as SessionNextEvent.TextDelta).delta)
    }

    @Test
    fun `TextEnded parses correctly`() {
        val eventJson = """{"type":"session.next.text.ended","sessionID":"s1","messageID":"m1","partID":"p1"}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        assertTrue(event is SessionNextEvent.TextEnded)
        assertEquals("p1", (event as SessionNextEvent.TextEnded).partId)
    }

    // ============ Reasoning Streaming ============

    @Test
    fun `ReasoningStarted parses correctly`() {
        val eventJson = """{"type":"session.next.reasoning.started","sessionID":"s1","messageID":"m1","partID":"p2"}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        assertTrue(event is SessionNextEvent.ReasoningStarted)
        assertEquals("p2", (event as SessionNextEvent.ReasoningStarted).partId)
    }

    @Test
    fun `ReasoningDelta parses correctly`() {
        val eventJson = """{"type":"session.next.reasoning.delta","sessionID":"s1","messageID":"m1","partID":"p2","delta":"thinking..."}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        assertTrue(event is SessionNextEvent.ReasoningDelta)
        assertEquals("thinking...", (event as SessionNextEvent.ReasoningDelta).delta)
    }

    @Test
    fun `ReasoningEnded parses correctly`() {
        val eventJson = """{"type":"session.next.reasoning.ended","sessionID":"s1","messageID":"m1","partID":"p2"}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        assertTrue(event is SessionNextEvent.ReasoningEnded)
        assertEquals("p2", (event as SessionNextEvent.ReasoningEnded).partId)
    }

    // ============ Tool Execution ============

    @Test
    fun `ToolInputStarted parses correctly`() {
        val eventJson = """{"type":"session.next.tool.input.started","sessionID":"s1","messageID":"m1","partID":"p3","callID":"c1","tool":"bash"}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        assertTrue(event is SessionNextEvent.ToolInputStarted)
        assertEquals("bash", (event as SessionNextEvent.ToolInputStarted).tool)
        assertEquals("c1", event.callId)
    }

    @Test
    fun `ToolInputDelta parses correctly`() {
        val eventJson = """{"type":"session.next.tool.input.delta","sessionID":"s1","messageID":"m1","partID":"p3","callID":"c1","delta":"{\"command\":\"ls\"}"}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        assertTrue(event is SessionNextEvent.ToolInputDelta)
        assertEquals("c1", (event as SessionNextEvent.ToolInputDelta).callId)
    }

    @Test
    fun `ToolCalled parses correctly`() {
        val eventJson = """{"type":"session.next.tool.called","sessionID":"s1","messageID":"m1","partID":"p3","callID":"c1","tool":"bash","input":{"command":"ls"}}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        assertTrue(event is SessionNextEvent.ToolCalled)
        assertEquals("bash", (event as SessionNextEvent.ToolCalled).tool)
    }

    @Test
    fun `ToolProgress parses correctly`() {
        val eventJson = """{"type":"session.next.tool.progress","sessionID":"s1","messageID":"m1","partID":"p3","callID":"c1","progress":"50%","title":"Running..."}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        assertTrue(event is SessionNextEvent.ToolProgress)
        assertEquals("50%", (event as SessionNextEvent.ToolProgress).progress)
        assertEquals("Running...", event.title)
    }

    @Test
    fun `ToolSuccess parses correctly`() {
        val eventJson = """{"type":"session.next.tool.success","sessionID":"s1","messageID":"m1","partID":"p3","callID":"c1","output":"done"}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        assertTrue(event is SessionNextEvent.ToolSuccess)
        assertEquals("done", (event as SessionNextEvent.ToolSuccess).output)
    }

    @Test
    fun `ToolFailed parses correctly`() {
        val eventJson = """{"type":"session.next.tool.failed","sessionID":"s1","messageID":"m1","partID":"p3","callID":"c1","error":"crashed"}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        assertTrue(event is SessionNextEvent.ToolFailed)
        assertEquals("crashed", (event as SessionNextEvent.ToolFailed).error)
    }

    // ============ Step Lifecycle ============

    @Test
    fun `StepStarted parses correctly`() {
        val eventJson = """{"type":"session.next.step.started","sessionID":"s1","messageID":"m1","step":1,"agent":"code","model":"claude-4-sonnet"}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        assertTrue(event is SessionNextEvent.StepStarted)
        assertEquals(1, (event as SessionNextEvent.StepStarted).step)
        assertEquals("code", event.agent)
        assertEquals("claude-4-sonnet", event.model)
    }

    @Test
    fun `StepEnded parses correctly`() {
        val eventJson = """{"type":"session.next.step.ended","sessionID":"s1","messageID":"m1","step":1}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        assertTrue(event is SessionNextEvent.StepEnded)
        assertEquals(1, (event as SessionNextEvent.StepEnded).step)
    }

    @Test
    fun `StepFailed parses correctly`() {
        val eventJson = """{"type":"session.next.step.failed","sessionID":"s1","messageID":"m1","step":1,"error":"timeout"}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        assertTrue(event is SessionNextEvent.StepFailed)
        assertEquals("timeout", (event as SessionNextEvent.StepFailed).error)
    }

    // ============ Shell ============

    @Test
    fun `ShellStarted parses correctly`() {
        val eventJson = """{"type":"session.next.shell.started","sessionID":"s1","messageID":"m1","partID":"p4","command":"npm test"}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        assertTrue(event is SessionNextEvent.ShellStarted)
        assertEquals("npm test", (event as SessionNextEvent.ShellStarted).command)
    }

    @Test
    fun `ShellEnded parses correctly`() {
        val eventJson = """{"type":"session.next.shell.ended","sessionID":"s1","messageID":"m1","partID":"p4","exitCode":0}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        assertTrue(event is SessionNextEvent.ShellEnded)
        assertEquals(0, (event as SessionNextEvent.ShellEnded).exitCode)
    }

    // ============ Compaction ============

    @Test
    fun `CompactionStarted parses correctly`() {
        val eventJson = """{"type":"session.next.compaction.started","sessionID":"s1","messageID":"m1","reason":"context full"}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        assertTrue(event is SessionNextEvent.CompactionStarted)
        assertEquals("context full", (event as SessionNextEvent.CompactionStarted).reason)
    }

    @Test
    fun `CompactionDelta parses correctly`() {
        val eventJson = """{"type":"session.next.compaction.delta","sessionID":"s1","messageID":"m1","delta":"compacting..."}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        assertTrue(event is SessionNextEvent.CompactionDelta)
        assertEquals("compacting...", (event as SessionNextEvent.CompactionDelta).delta)
    }

    @Test
    fun `CompactionEnded parses correctly`() {
        val eventJson = """{"type":"session.next.compaction.ended","sessionID":"s1","messageID":"m1"}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        assertTrue(event is SessionNextEvent.CompactionEnded)
        assertEquals("s1", (event as SessionNextEvent.CompactionEnded).sessionId)
    }

    // ============ Other ============

    @Test
    fun `Prompted parses correctly`() {
        val eventJson = """{"type":"session.next.prompted","sessionID":"s1","messageID":"m1"}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        assertTrue(event is SessionNextEvent.Prompted)
    }

    @Test
    fun `Retried parses correctly`() {
        val eventJson = """{"type":"session.next.retried","sessionID":"s1","attempt":2,"error":"rate limited"}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        assertTrue(event is SessionNextEvent.Retried)
        assertEquals(2, (event as SessionNextEvent.Retried).attempt)
        assertEquals("rate limited", event.error)
    }

    @Test
    fun `Synthetic parses correctly`() {
        val eventJson = """{"type":"session.next.synthetic","sessionID":"s1","messageID":"m1"}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        assertTrue(event is SessionNextEvent.Synthetic)
    }

    @Test
    fun `Unknown event preserves raw type`() {
        // Unknown events are created by the parser, not directly deserialized
        val event = SessionNextEvent.Unknown("session.next.foo.bar", "{}")
        assertEquals("session.next.foo.bar", event.rawType)
        assertEquals("{}", event.rawJson)
    }

    // ============ Discriminator ============

    @Test
    fun `type discriminator selects correct variant`() {
        val json1 = """{"type":"session.next.agent.switched","sessionID":"s1","agent":"code"}"""
        val event1 = json.decodeFromString<SessionNextEvent>(json1)
        assertTrue(event1 is SessionNextEvent.AgentSwitched)
    }

    @Test
    fun `sessionId is present on all events`() {
        val variants = listOf(
            """{"type":"session.next.agent.switched","sessionID":"s1","agent":"code"}""",
            """{"type":"session.next.model.switched","sessionID":"s1","providerID":"p","modelID":"m"}""",
            """{"type":"session.next.text.started","sessionID":"s1","messageID":"m1","partID":"p1"}"""
        )
        for (jsonStr in variants) {
            val event = json.decodeFromString<SessionNextEvent>(jsonStr)
            assertEquals("s1", event.sessionId)
        }
    }
}
```

- [ ] **Verify test fails:** `.\gradlew :app:testDevDebugUnitTest --tests "dev.minios.ocremote.domain.model.SessionNextEventTest" --rerun` (180s) — should fail with `Unresolved reference: SessionNextEvent`

#### Step 1.2: Implement SessionNextEvent model

File: `app/src/main/kotlin/dev/minios/ocremote/domain/model/SessionNextEvent.kt`

```kotlin
package dev.minios.ocremote.domain.model

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Discriminator serializer for SessionNextEvent.
 * Uses the "type" field to select the correct variant.
 * Falls back to [SessionNextEvent.Unknown] for unrecognized types.
 */
object SessionNextEventSerializer : JsonContentPolymorphicSerializer<SessionNextEvent>(SessionNextEvent::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<SessionNextEvent> {
        val type = element.jsonObject["type"]?.jsonPrimitive?.content ?: return SessionNextEvent.Unknown.serializer()
        return when (type) {
            "session.next.agent.switched" -> SessionNextEvent.AgentSwitched.serializer()
            "session.next.model.switched" -> SessionNextEvent.ModelSwitched.serializer()
            "session.next.text.started" -> SessionNextEvent.TextStarted.serializer()
            "session.next.text.delta" -> SessionNextEvent.TextDelta.serializer()
            "session.next.text.ended" -> SessionNextEvent.TextEnded.serializer()
            "session.next.reasoning.started" -> SessionNextEvent.ReasoningStarted.serializer()
            "session.next.reasoning.delta" -> SessionNextEvent.ReasoningDelta.serializer()
            "session.next.reasoning.ended" -> SessionNextEvent.ReasoningEnded.serializer()
            "session.next.tool.input.started" -> SessionNextEvent.ToolInputStarted.serializer()
            "session.next.tool.input.delta" -> SessionNextEvent.ToolInputDelta.serializer()
            "session.next.tool.called" -> SessionNextEvent.ToolCalled.serializer()
            "session.next.tool.progress" -> SessionNextEvent.ToolProgress.serializer()
            "session.next.tool.success" -> SessionNextEvent.ToolSuccess.serializer()
            "session.next.tool.failed" -> SessionNextEvent.ToolFailed.serializer()
            "session.next.step.started" -> SessionNextEvent.StepStarted.serializer()
            "session.next.step.ended" -> SessionNextEvent.StepEnded.serializer()
            "session.next.step.failed" -> SessionNextEvent.StepFailed.serializer()
            "session.next.shell.started" -> SessionNextEvent.ShellStarted.serializer()
            "session.next.shell.ended" -> SessionNextEvent.ShellEnded.serializer()
            "session.next.compaction.started" -> SessionNextEvent.CompactionStarted.serializer()
            "session.next.compaction.delta" -> SessionNextEvent.CompactionDelta.serializer()
            "session.next.compaction.ended" -> SessionNextEvent.CompactionEnded.serializer()
            "session.next.prompted" -> SessionNextEvent.Prompted.serializer()
            "session.next.retried" -> SessionNextEvent.Retried.serializer()
            "session.next.synthetic" -> SessionNextEvent.Synthetic.serializer()
            else -> SessionNextEvent.Unknown.serializer()
        }
    }
}

/**
 * Fine-grained session event types for real-time status tracking.
 * Events use the `session.next.{category}.{action}` naming convention.
 * Parsed from SSE stream when type starts with "session.next.".
 */
@Serializable(with = SessionNextEventSerializer::class)
sealed class SessionNextEvent {
    abstract val sessionId: String

    // ============ Agent / Model Switching ============

    /** Agent was switched for this session. */
    @Serializable
    data class AgentSwitched(
        @SerialName("sessionID") override val sessionId: String,
        val agent: String
    ) : SessionNextEvent()

    /** Model was switched for this session. */
    @Serializable
    data class ModelSwitched(
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("providerID") val providerId: String,
        @SerialName("modelID") val modelId: String
    ) : SessionNextEvent()

    // ============ Text Streaming ============

    @Serializable
    data class TextStarted(
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") val messageId: String,
        @SerialName("partID") val partId: String
    ) : SessionNextEvent()

    @Serializable
    data class TextDelta(
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") val messageId: String,
        @SerialName("partID") val partId: String,
        val delta: String
    ) : SessionNextEvent()

    @Serializable
    data class TextEnded(
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") val messageId: String,
        @SerialName("partID") val partId: String
    ) : SessionNextEvent()

    // ============ Reasoning Streaming ============

    @Serializable
    data class ReasoningStarted(
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") val messageId: String,
        @SerialName("partID") val partId: String
    ) : SessionNextEvent()

    @Serializable
    data class ReasoningDelta(
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") val messageId: String,
        @SerialName("partID") val partId: String,
        val delta: String
    ) : SessionNextEvent()

    @Serializable
    data class ReasoningEnded(
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") val messageId: String,
        @SerialName("partID") val partId: String
    ) : SessionNextEvent()

    // ============ Tool Execution ============

    @Serializable
    data class ToolInputStarted(
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") val messageId: String,
        @SerialName("partID") val partId: String,
        @SerialName("callID") val callId: String,
        val tool: String
    ) : SessionNextEvent()

    @Serializable
    data class ToolInputDelta(
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") val messageId: String,
        @SerialName("partID") val partId: String,
        @SerialName("callID") val callId: String,
        val delta: String
    ) : SessionNextEvent()

    @Serializable
    data class ToolCalled(
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") val messageId: String,
        @SerialName("partID") val partId: String,
        @SerialName("callID") val callId: String,
        val tool: String,
        val input: Map<String, JsonElement> = emptyMap()
    ) : SessionNextEvent()

    @Serializable
    data class ToolProgress(
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") val messageId: String,
        @SerialName("partID") val partId: String,
        @SerialName("callID") val callId: String,
        val progress: String? = null,
        val title: String? = null
    ) : SessionNextEvent()

    @Serializable
    data class ToolSuccess(
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") val messageId: String,
        @SerialName("partID") val partId: String,
        @SerialName("callID") val callId: String,
        val output: String = ""
    ) : SessionNextEvent()

    @Serializable
    data class ToolFailed(
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") val messageId: String,
        @SerialName("partID") val partId: String,
        @SerialName("callID") val callId: String,
        val error: String = ""
    ) : SessionNextEvent()

    // ============ Step Lifecycle ============

    @Serializable
    data class StepStarted(
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") val messageId: String,
        val step: Int,
        val agent: String = "",
        val model: String = ""
    ) : SessionNextEvent()

    @Serializable
    data class StepEnded(
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") val messageId: String,
        val step: Int
    ) : SessionNextEvent()

    @Serializable
    data class StepFailed(
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") val messageId: String,
        val step: Int,
        val error: String = ""
    ) : SessionNextEvent()

    // ============ Shell ============

    @Serializable
    data class ShellStarted(
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") val messageId: String,
        @SerialName("partID") val partId: String,
        val command: String = ""
    ) : SessionNextEvent()

    @Serializable
    data class ShellEnded(
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") val messageId: String,
        @SerialName("partID") val partId: String,
        val exitCode: Int = 0
    ) : SessionNextEvent()

    // ============ Compaction ============

    @Serializable
    data class CompactionStarted(
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") val messageId: String,
        val reason: String = ""
    ) : SessionNextEvent()

    @Serializable
    data class CompactionDelta(
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") val messageId: String,
        val delta: String = ""
    ) : SessionNextEvent()

    @Serializable
    data class CompactionEnded(
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") val messageId: String
    ) : SessionNextEvent()

    // ============ Other ============

    @Serializable
    data class Prompted(
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") val messageId: String
    ) : SessionNextEvent()

    @Serializable
    data class Retried(
        @SerialName("sessionID") override val sessionId: String,
        val attempt: Int = 0,
        val error: String = ""
    ) : SessionNextEvent()

    @Serializable
    data class Synthetic(
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") val messageId: String
    ) : SessionNextEvent()

    /** Fallback for unrecognized session.next.* event types. */
    @Serializable
    data class Unknown(
        val rawType: String = "",
        val rawJson: String = ""
    ) : SessionNextEvent() {
        @SerialName("sessionID")
        override val sessionId: String = ""
    }
}
```

- [ ] **Verify test passes:** `.\gradlew :app:testDevDebugUnitTest --tests "dev.minios.ocremote.domain.model.SessionNextEventTest" --rerun` (180s)
- [ ] **Verify compile:** `.\gradlew :app:compileDevDebugKotlin` (120s)
- [ ] **Commit:** `git add -A && git commit -m "feat(layer3): add SessionNextEvent sealed class with 25 event types"`

---

### Task 2: SseClient session.next.* Parsing (P0)

**Add parsing logic to SseClient for session.next.* prefix events.**

> TDD: Write parsing test → verify fail → add parser → verify pass

#### Step 2.1: Write failing test for SseClient session.next.* parsing

File: `app/src/test/kotlin/dev/minios/ocremote/data/api/SseClientSessionNextTest.kt`

```kotlin
package dev.minios.ocremote.data.api

import dev.minios.ocremote.domain.model.SessionNextEvent
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SseClientSessionNextTest {

    private lateinit var json: Json
    private lateinit var sseClient: SseClient

    @Before
    fun setup() {
        json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        sseClient = SseClient(
            httpClient = io.ktor.client.HttpClient(),
            json = json
        )
    }

    // ============ session.next.* type routing ============

    @Test
    fun `parseSessionNextEvent routes agent switched`() {
        val result = sseClient.parseSessionNextEvent("session.next.agent.switched", buildProps(
            "sessionID" to "s1",
            "agent" to "code"
        ))
        assertTrue(result is SessionNextEvent.AgentSwitched)
        assertEquals("s1", (result as SessionNextEvent.AgentSwitched).sessionId)
        assertEquals("code", result.agent)
    }

    @Test
    fun `parseSessionNextEvent routes text delta`() {
        val result = sseClient.parseSessionNextEvent("session.next.text.delta", buildProps(
            "sessionID" to "s1",
            "messageID" to "m1",
            "partID" to "p1",
            "delta" to "hello world"
        ))
        assertTrue(result is SessionNextEvent.TextDelta)
        assertEquals("hello world", (result as SessionNextEvent.TextDelta).delta)
    }

    @Test
    fun `parseSessionNextEvent routes tool progress`() {
        val result = sseClient.parseSessionNextEvent("session.next.tool.progress", buildProps(
            "sessionID" to "s1",
            "messageID" to "m1",
            "partID" to "p1",
            "callID" to "c1",
            "progress" to "50%",
            "title" to "Running..."
        ))
        assertTrue(result is SessionNextEvent.ToolProgress)
        assertEquals("50%", (result as SessionNextEvent.ToolProgress).progress)
    }

    @Test
    fun `parseSessionNextEvent routes step started`() {
        val result = sseClient.parseSessionNextEvent("session.next.step.started", buildProps(
            "sessionID" to "s1",
            "messageID" to "m1",
            "step" to 1,
            "agent" to "code",
            "model" to "claude-4-sonnet"
        ))
        assertTrue(result is SessionNextEvent.StepStarted)
        assertEquals(1, (result as SessionNextEvent.StepStarted).step)
        assertEquals("code", result.agent)
    }

    @Test
    fun `parseSessionNextEvent returns Unknown for unrecognized type`() {
        val result = sseClient.parseSessionNextEvent("session.next.foo.bar", buildProps(
            "sessionID" to "s1"
        ))
        assertTrue(result is SessionNextEvent.Unknown)
        assertEquals("session.next.foo.bar", (result as SessionNextEvent.Unknown).rawType)
    }

    @Test
    fun `parseSessionNextEvent handles shell events`() {
        val started = sseClient.parseSessionNextEvent("session.next.shell.started", buildProps(
            "sessionID" to "s1",
            "messageID" to "m1",
            "partID" to "p4",
            "command" to "npm test"
        ))
        assertTrue(started is SessionNextEvent.ShellStarted)
        assertEquals("npm test", (started as SessionNextEvent.ShellStarted).command)

        val ended = sseClient.parseSessionNextEvent("session.next.shell.ended", buildProps(
            "sessionID" to "s1",
            "messageID" to "m1",
            "partID" to "p4",
            "exitCode" to 0
        ))
        assertTrue(ended is SessionNextEvent.ShellEnded)
        assertEquals(0, (ended as SessionNextEvent.ShellEnded).exitCode)
    }

    @Test
    fun `parseSessionNextEvent handles compaction events`() {
        val started = sseClient.parseSessionNextEvent("session.next.compaction.started", buildProps(
            "sessionID" to "s1",
            "messageID" to "m1",
            "reason" to "context full"
        ))
        assertTrue(started is SessionNextEvent.CompactionStarted)
        assertEquals("context full", (started as SessionNextEvent.CompactionStarted).reason)
    }

    @Test
    fun `parseSessionNextEvent handles retried`() {
        val result = sseClient.parseSessionNextEvent("session.next.retried", buildProps(
            "sessionID" to "s1",
            "attempt" to 2,
            "error" to "rate limited"
        ))
        assertTrue(result is SessionNextEvent.Retried)
        assertEquals(2, (result as SessionNextEvent.Retried).attempt)
    }

    private fun buildProps(vararg pairs: Pair<String, Any>): kotlinx.serialization.json.JsonObject {
        val map = pairs.toMap().mapValues { (_, v) ->
            when (v) {
                is String -> kotlinx.serialization.json.JsonPrimitive(v)
                is Int -> kotlinx.serialization.json.JsonPrimitive(v)
                is Long -> kotlinx.serialization.json.JsonPrimitive(v)
                is Boolean -> kotlinx.serialization.json.JsonPrimitive(v)
                else -> kotlinx.serialization.json.JsonPrimitive(v.toString())
            }
        }
        return kotlinx.serialization.json.JsonObject(map)
    }
}
```

- [ ] **Verify test fails:** `.\gradlew :app:testDevDebugUnitTest --tests "dev.minios.ocremote.data.api.SseClientSessionNextTest" --rerun` (180s) — should fail with `Unresolved reference: parseSessionNextEvent`

#### Step 2.2: Add parseSessionNextEvent to SseClient

Edit `app/src/main/kotlin/dev/minios/ocremote/data/api/SseClient.kt`:

**Add import** at the top (after existing imports):
```kotlin
import dev.minios.ocremote.domain.model.SessionNextEvent
```

**Add method** — insert after the `parseEventByType` method (after the closing `}` of `parseEventByType` at ~line 576), and before the `// ============ Message Parsing ============` comment:

```kotlin
    // ============ Session Next Event Parsing ============

    /**
     * Parse a session.next.* event from type string and properties.
     * Called when the SSE event type starts with "session.next.".
     * Uses kotlinx.serialization Json to decode into the appropriate SessionNextEvent variant.
     */
    fun parseSessionNextEvent(type: String, props: JsonObject): SessionNextEvent {
        return try {
            // Inject the type into props so the discriminator can select the correct variant
            val propsWithType = JsonObject(props + ("type" to JsonPrimitive(type)))
            json.decodeFromString<SessionNextEvent>(propsWithType.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse session.next event: $type — ${e.message}")
            SessionNextEvent.Unknown(rawType = type, rawJson = props.toString())
        }
    }
```

**Update `parseEventByType`** to route `session.next.*` events. Add this case BEFORE the `else` branch in the `when (type)` block (around line 567):

```kotlin
                // Session next events — fine-grained real-time status
                else -> if (type.startsWith("session.next.")) {
                    val nextEvent = parseSessionNextEvent(type, props)
                    SseEvent.SessionNext(nextEvent)
                } else {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Unhandled event: $type")
                    null
                }
```

This replaces the existing `else` block (lines 567-570). The full replacement is:

```kotlin
                else -> if (type.startsWith("session.next.")) {
                    val nextEvent = parseSessionNextEvent(type, props)
                    SseEvent.SessionNext(nextEvent)
                } else {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Unhandled event: $type")
                    null
                }
```

**Add SessionNext wrapper to SseEvent** — edit `app/src/main/kotlin/dev/minios/ocremote/domain/model/SseEvent.kt`. Add BEFORE the closing `}` of the sealed class (before line 238):

```kotlin
    // Session Next events — fine-grained real-time status
    @Serializable
    data class SessionNext(val event: SessionNextEvent) : SseEvent()
```

- [ ] **Verify test passes:** `.\gradlew :app:testDevDebugUnitTest --tests "dev.minios.ocremote.data.api.SseClientSessionNextTest" --rerun` (180s)
- [ ] **Verify compile:** `.\gradlew :app:compileDevDebugKotlin` (120s)
- [ ] **Commit:** `git add -A && git commit -m "feat(layer3): add session.next.* event parsing in SseClient"`

---

### Task 3: StreamingStateTracker (P1)

**Standalone utility for tracking streaming state of text/reasoning parts.**

> TDD: Write state machine tests first → verify fail → implement → verify pass

#### Step 3.1: Write failing tests for StreamingStateTracker

File: `app/src/test/kotlin/dev/minios/ocremote/domain/tracker/StreamingStateTrackerTest.kt`

```kotlin
package dev.minios.ocremote.domain.tracker

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class StreamingStateTrackerTest {

    private lateinit var tracker: StreamingStateTracker

    @Before
    fun setup() {
        tracker = StreamingStateTracker()
    }

    @Test
    fun `initial state is Idle`() {
        assertEquals(StreamingState.Idle, tracker.getState("p1"))
    }

    @Test
    fun `Started transitions from Idle`() {
        tracker.onStarted("p1")
        assertEquals(StreamingState.Started, tracker.getState("p1"))
    }

    @Test
    fun `Streaming transitions from Started`() {
        tracker.onStarted("p1")
        tracker.onDelta("p1", "hello")
        assertEquals(StreamingState.Streaming, tracker.getState("p1"))
    }

    @Test
    fun `Ended transitions from Streaming`() {
        tracker.onStarted("p1")
        tracker.onDelta("p1", "hello")
        tracker.onEnded("p1")
        assertEquals(StreamingState.Ended, tracker.getState("p1"))
    }

    @Test
    fun `full lifecycle Idle-Started-Streaming-Ended`() {
        assertEquals(StreamingState.Idle, tracker.getState("p1"))
        tracker.onStarted("p1")
        assertEquals(StreamingState.Started, tracker.getState("p1"))
        tracker.onDelta("p1", "a")
        assertEquals(StreamingState.Streaming, tracker.getState("p1"))
        tracker.onDelta("p1", "b")
        assertEquals(StreamingState.Streaming, tracker.getState("p1"))
        tracker.onEnded("p1")
        assertEquals(StreamingState.Ended, tracker.getState("p1"))
    }

    @Test
    fun `multiple independent parts tracked separately`() {
        tracker.onStarted("p1")
        tracker.onStarted("p2")
        tracker.onDelta("p1", "hello")

        assertEquals(StreamingState.Streaming, tracker.getState("p1"))
        assertEquals(StreamingState.Started, tracker.getState("p2"))
    }

    @Test
    fun `clear removes specific part`() {
        tracker.onStarted("p1")
        tracker.onStarted("p2")

        tracker.clear("p1")

        assertEquals(StreamingState.Idle, tracker.getState("p1"))
        assertEquals(StreamingState.Started, tracker.getState("p2"))
    }

    @Test
    fun `clearAll removes all parts`() {
        tracker.onStarted("p1")
        tracker.onStarted("p2")

        tracker.clearAll()

        assertEquals(StreamingState.Idle, tracker.getState("p1"))
        assertEquals(StreamingState.Idle, tracker.getState("p2"))
    }

    @Test
    fun `cleanup keeps last 50 entries`() {
        // Add 60 entries
        for (i in 1..60) {
            tracker.onStarted("part-$i")
        }

        tracker.cleanup()

        // First 10 should be cleaned up, last 50 should remain
        assertEquals(StreamingState.Idle, tracker.getState("part-1"))
        assertEquals(StreamingState.Idle, tracker.getState("part-10"))
        assertEquals(StreamingState.Started, tracker.getState("part-11"))
        assertEquals(StreamingState.Started, tracker.getState("part-60"))
    }

    @Test
    fun `ended entries are cleaned up first`() {
        tracker.onStarted("p1")
        tracker.onEnded("p1")
        tracker.onStarted("p2")
        // p1 is Ended, should be cleaned first
        // With only 2 entries, cleanup(1) should remove the ended one
        tracker.cleanup(maxEntries = 1)

        assertEquals(StreamingState.Idle, tracker.getState("p1"))
        assertEquals(StreamingState.Started, tracker.getState("p2"))
    }

    @Test
    fun `clearForSession removes all parts for a session`() {
        tracker.onStarted("p1", sessionId = "s1")
        tracker.onStarted("p2", sessionId = "s1")
        tracker.onStarted("p3", sessionId = "s2")

        tracker.clearForSession("s1")

        assertEquals(StreamingState.Idle, tracker.getState("p1"))
        assertEquals(StreamingState.Idle, tracker.getState("p2"))
        assertEquals(StreamingState.Started, tracker.getState("p3"))
    }
}
```

- [ ] **Verify test fails:** `.\gradlew :app:testDevDebugUnitTest --tests "dev.minios.ocremote.domain.tracker.StreamingStateTrackerTest" --rerun` (180s)

#### Step 3.2: Implement StreamingStateTracker

File: `app/src/main/kotlin/dev/minios/ocremote/domain/tracker/StreamingStateTracker.kt`

```kotlin
package dev.minios.ocremote.domain.tracker

/**
 * Streaming state for a single part (text or reasoning).
 */
enum class StreamingState {
    Idle,
    Started,
    Streaming,
    Ended
}

/**
 * Tracks streaming state per part ID with bounded history.
 *
 * Lifecycle: Idle → Started → Streaming → Ended
 * - Started: first event for this part (TextStarted / ReasoningStarted)
 * - Streaming: at least one delta received
 * - Ended: streaming complete (TextEnded / ReasoningEnded)
 *
 * Thread safety: designed for single-threaded dispatcher use (Main + Dispatchers.Default).
 * For multi-threaded access, external synchronization is required.
 */
class StreamingStateTracker {

    private data class Entry(
        val state: StreamingState,
        val sessionId: String? = null
    )

    private val states = LinkedHashMap<String, Entry>()

    companion object {
        const val DEFAULT_MAX_ENTRIES = 50
    }

    fun getState(partId: String): StreamingState =
        states[partId]?.state ?: StreamingState.Idle

    fun onStarted(partId: String, sessionId: String? = null) {
        states[partId] = Entry(StreamingState.Started, sessionId)
    }

    fun onDelta(partId: String, delta: String) {
        val current = states[partId]
        if (current != null && current.state != StreamingState.Ended) {
            states[partId] = Entry(StreamingState.Streaming, current.sessionId)
        }
    }

    fun onEnded(partId: String) {
        val current = states[partId]
        if (current != null) {
            states[partId] = Entry(StreamingState.Ended, current.sessionId)
        }
    }

    fun clear(partId: String) {
        states.remove(partId)
    }

    fun clearAll() {
        states.clear()
    }

    fun clearForSession(sessionId: String) {
        val toRemove = states.entries
            .filter { it.value.sessionId == sessionId }
            .map { it.key }
        toRemove.forEach { states.remove(it) }
    }

    /**
     * Remove oldest entries beyond [maxEntries].
     * Prioritizes removing Ended entries first, then oldest Started/Streaming.
     */
    fun cleanup(maxEntries: Int = DEFAULT_MAX_ENTRIES) {
        if (states.size <= maxEntries) return

        // Remove Ended entries first (oldest first)
        val endedKeys = states.entries
            .filter { it.value.state == StreamingState.Ended }
            .map { it.key }
        for (key in endedKeys) {
            if (states.size <= maxEntries) return
            states.remove(key)
        }

        // Still too many? Remove oldest entries
        val iterator = states.keys.iterator()
        while (states.size > maxEntries && iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }
    }
}
```

- [ ] **Verify test passes:** `.\gradlew :app:testDevDebugUnitTest --tests "dev.minios.ocremote.domain.tracker.StreamingStateTrackerTest" --rerun` (180s)
- [ ] **Verify compile:** `.\gradlew :app:compileDevDebugKotlin` (120s)
- [ ] **Commit:** `git add -A && git commit -m "feat(layer3): add StreamingStateTracker for part-level streaming lifecycle"`

---

### Task 4: SessionNextEventHandler (P0)

**New handler for session.next.* events, manages streaming state, step progress, and agent/model tracking.**

> TDD: Write handler tests → verify fail → implement handler → verify pass

#### Step 4.1: Write failing tests for SessionNextEventHandler

File: `app/src/test/kotlin/dev/minios/ocremote/data/repository/handler/SessionNextEventHandlerTest.kt`

```kotlin
package dev.minios.ocremote.data.repository.handler

import dev.minios.ocremote.domain.model.SessionNextEvent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SessionNextEventHandlerTest {

    private lateinit var handler: SessionNextEventHandler

    @Before
    fun setup() {
        handler = SessionNextEventHandler()
    }

    // ============ Agent / Model State ============

    @Test
    fun `AgentSwitched updates currentAgent`() {
        handler.handleSessionNextEvent(
            SessionNextEvent.AgentSwitched(sessionId = "s1", agent = "code")
        )
        assertEquals("code", handler.currentAgent.value["s1"])
    }

    @Test
    fun `ModelSwitched updates currentModel`() {
        handler.handleSessionNextEvent(
            SessionNextEvent.ModelSwitched(sessionId = "s1", providerId = "anthropic", modelId = "claude-4-sonnet")
        )
        val model = handler.currentModel.value["s1"]
        assertNotNull(model)
        assertEquals("anthropic", model!!.first)
        assertEquals("claude-4-sonnet", model.second)
    }

    // ============ Tool Progress ============

    @Test
    fun `ToolInputStarted tracks running tool`() {
        handler.handleSessionNextEvent(
            SessionNextEvent.ToolInputStarted(
                sessionId = "s1", messageId = "m1", partId = "p1",
                callId = "c1", tool = "bash"
            )
        )
        val tools = handler.activeToolProgress.value["s1"]
        assertNotNull(tools)
        assertEquals(1, tools!!.size)
        assertEquals("bash", tools[0].tool)
        assertEquals("c1", tools[0].callId)
        assertEquals("started", tools[0].status)
    }

    @Test
    fun `ToolProgress updates running tool`() {
        handler.handleSessionNextEvent(
            SessionNextEvent.ToolInputStarted(
                sessionId = "s1", messageId = "m1", partId = "p1",
                callId = "c1", tool = "bash"
            )
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.ToolProgress(
                sessionId = "s1", messageId = "m1", partId = "p1",
                callId = "c1", progress = "50%", title = "Running ls"
            )
        )
        val tools = handler.activeToolProgress.value["s1"]!!
        assertEquals("50%", tools[0].progress)
        assertEquals("Running ls", tools[0].title)
    }

    @Test
    fun `ToolSuccess removes running tool`() {
        handler.handleSessionNextEvent(
            SessionNextEvent.ToolInputStarted(
                sessionId = "s1", messageId = "m1", partId = "p1",
                callId = "c1", tool = "bash"
            )
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.ToolSuccess(
                sessionId = "s1", messageId = "m1", partId = "p1",
                callId = "c1", output = "done"
            )
        )
        val tools = handler.activeToolProgress.value["s1"]
        assertEquals(0, tools!!.size)
    }

    @Test
    fun `ToolFailed removes running tool`() {
        handler.handleSessionNextEvent(
            SessionNextEvent.ToolInputStarted(
                sessionId = "s1", messageId = "m1", partId = "p1",
                callId = "c1", tool = "bash"
            )
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.ToolFailed(
                sessionId = "s1", messageId = "m1", partId = "p1",
                callId = "c1", error = "crashed"
            )
        )
        val tools = handler.activeToolProgress.value["s1"]
        assertEquals(0, tools!!.size)
    }

    // ============ Step Progress ============

    @Test
    fun `StepStarted tracks current step`() {
        handler.handleSessionNextEvent(
            SessionNextEvent.StepStarted(
                sessionId = "s1", messageId = "m1",
                step = 1, agent = "code", model = "claude-4-sonnet"
            )
        )
        val progress = handler.stepProgress.value["s1"]
        assertNotNull(progress)
        assertEquals(1, progress!!.step)
        assertEquals("code", progress.agent)
        assertEquals("claude-4-sonnet", progress.model)
    }

    @Test
    fun `StepEnded clears step progress`() {
        handler.handleSessionNextEvent(
            SessionNextEvent.StepStarted(
                sessionId = "s1", messageId = "m1",
                step = 1, agent = "code", model = "claude-4-sonnet"
            )
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.StepEnded(sessionId = "s1", messageId = "m1", step = 1)
        )
        assertNull(handler.stepProgress.value["s1"])
    }

    @Test
    fun `StepFailed clears step progress`() {
        handler.handleSessionNextEvent(
            SessionNextEvent.StepStarted(
                sessionId = "s1", messageId = "m1",
                step = 1, agent = "code", model = "claude-4-sonnet"
            )
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.StepFailed(sessionId = "s1", messageId = "m1", step = 1, error = "timeout")
        )
        assertNull(handler.stepProgress.value["s1"])
    }

    // ============ Streaming State ============

    @Test
    fun `TextStarted updates streaming tracker`() {
        handler.handleSessionNextEvent(
            SessionNextEvent.TextStarted(sessionId = "s1", messageId = "m1", partId = "p1")
        )
        assertEquals(
            dev.minios.ocremote.domain.tracker.StreamingState.Started,
            handler.textStreamingState.getState("p1")
        )
    }

    @Test
    fun `ReasoningStarted updates streaming tracker`() {
        handler.handleSessionNextEvent(
            SessionNextEvent.ReasoningStarted(sessionId = "s1", messageId = "m1", partId = "p2")
        )
        assertEquals(
            dev.minios.ocremote.domain.tracker.StreamingState.Started,
            handler.reasoningStreamingState.getState("p2")
        )
    }

    // ============ Compaction State ============

    @Test
    fun `CompactionStarted sets compaction progress`() {
        handler.handleSessionNextEvent(
            SessionNextEvent.CompactionStarted(sessionId = "s1", messageId = "m1", reason = "context full")
        )
        val state = handler.compactionState.value["s1"]
        assertNotNull(state)
        assertTrue(state!!.isActive)
        assertEquals("context full", state.reason)
    }

    @Test
    fun `CompactionEnded clears compaction progress`() {
        handler.handleSessionNextEvent(
            SessionNextEvent.CompactionStarted(sessionId = "s1", messageId = "m1", reason = "context full")
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.CompactionEnded(sessionId = "s1", messageId = "m1")
        )
        assertNull(handler.compactionState.value["s1"])
    }

    // ============ Shell State ============

    @Test
    fun `ShellStarted sets shell state`() {
        handler.handleSessionNextEvent(
            SessionNextEvent.ShellStarted(sessionId = "s1", messageId = "m1", partId = "p4", command = "npm test")
        )
        val state = handler.shellState.value["s1"]
        assertNotNull(state)
        assertEquals("npm test", state!!.command)
    }

    @Test
    fun `ShellEnded clears shell state`() {
        handler.handleSessionNextEvent(
            SessionNextEvent.ShellStarted(sessionId = "s1", messageId = "m1", partId = "p4", command = "npm test")
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.ShellEnded(sessionId = "s1", messageId = "m1", partId = "p4", exitCode = 0)
        )
        assertNull(handler.shellState.value["s1"])
    }

    // ============ SseEventHandler Integration ============

    @Test
    fun `handle returns true for SessionNext event`() {
        val event = dev.minios.ocremote.domain.model.SseEvent.SessionNext(
            SessionNextEvent.AgentSwitched(sessionId = "s1", agent = "code")
        )
        assertTrue(handler.handle(event, "server1"))
    }

    @Test
    fun `handle returns false for non-SessionNext event`() {
        val event = dev.minios.ocremote.domain.model.SseEvent.ServerHeartbeat
        assertFalse(handler.handle(event, "server1"))
    }

    // ============ Cleanup ============

    @Test
    fun `clearForSession removes all session state`() {
        handler.handleSessionNextEvent(
            SessionNextEvent.AgentSwitched(sessionId = "s1", agent = "code")
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.StepStarted(sessionId = "s1", messageId = "m1", step = 1)
        )

        handler.clearForSession("s1")

        assertNull(handler.currentAgent.value["s1"])
        assertNull(handler.stepProgress.value["s1"])
    }

    @Test
    fun `clearAll removes everything`() {
        handler.handleSessionNextEvent(
            SessionNextEvent.AgentSwitched(sessionId = "s1", agent = "code")
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.AgentSwitched(sessionId = "s2", agent = "build")
        )

        handler.clearAll()

        assertTrue(handler.currentAgent.value.isEmpty())
        assertTrue(handler.currentModel.value.isEmpty())
        assertTrue(handler.activeToolProgress.value.isEmpty())
        assertTrue(handler.stepProgress.value.isEmpty())
        assertTrue(handler.compactionState.value.isEmpty())
        assertTrue(handler.shellState.value.isEmpty())
    }
}
```

- [ ] **Verify test fails:** `.\gradlew :app:testDevDebugUnitTest --tests "dev.minios.ocremote.data.repository.handler.SessionNextEventHandlerTest" --rerun` (180s)

#### Step 4.2: Implement SessionNextEventHandler

File: `app/src/main/kotlin/dev/minios/ocremote/data/repository/handler/SessionNextEventHandler.kt`

```kotlin
package dev.minios.ocremote.data.repository.handler

import android.util.Log
import dev.minios.ocremote.BuildConfig
import dev.minios.ocremote.domain.model.SessionNextEvent
import dev.minios.ocremote.domain.model.SseEvent
import dev.minios.ocremote.domain.tracker.StreamingStateTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Current tool execution progress for a single tool call.
 */
data class ToolProgressInfo(
    val callId: String,
    val partId: String,
    val tool: String,
    val status: String,
    val progress: String? = null,
    val title: String? = null
)

/**
 * Current step progress.
 */
data class StepProgressInfo(
    val step: Int,
    val agent: String = "",
    val model: String = ""
)

/**
 * Compaction state for a session.
 */
data class CompactionStateInfo(
    val isActive: Boolean,
    val reason: String = ""
)

/**
 * Shell execution state for a session.
 */
data class ShellStateInfo(
    val command: String
)

/**
 * Handles all session.next.* events for real-time status tracking.
 * Manages: agent/model switching, tool progress, step progress, streaming state,
 * compaction state, and shell state.
 */
@Singleton
class SessionNextEventHandler @Inject constructor() : SseEventHandler {

    companion object {
        private const val TAG = "SessionNextEventHandler"
    }

    // ============ Public State (read-only) ============

    private val _currentAgent = MutableStateFlow<Map<String, String>>(emptyMap())
    val currentAgent: StateFlow<Map<String, String>> = _currentAgent.asStateFlow()

    private val _currentModel = MutableStateFlow<Map<String, Pair<String, String>>>(emptyMap())
    val currentModel: StateFlow<Map<String, Pair<String, String>>> = _currentModel.asStateFlow()

    private val _activeToolProgress = MutableStateFlow<Map<String, List<ToolProgressInfo>>>(emptyMap())
    val activeToolProgress: StateFlow<Map<String, List<ToolProgressInfo>>> = _activeToolProgress.asStateFlow()

    private val _stepProgress = MutableStateFlow<Map<String, StepProgressInfo>>(emptyMap())
    val stepProgress: StateFlow<Map<String, StepProgressInfo>> = _stepProgress.asStateFlow()

    private val _compactionState = MutableStateFlow<Map<String, CompactionStateInfo>>(emptyMap())
    val compactionState: StateFlow<Map<String, CompactionStateInfo>> = _compactionState.asStateFlow()

    private val _shellState = MutableStateFlow<Map<String, ShellStateInfo>>(emptyMap())
    val shellState: StateFlow<Map<String, ShellStateInfo>> = _shellState.asStateFlow()

    /** Streaming state tracker for text parts. */
    val textStreamingState = StreamingStateTracker()

    /** Streaming state tracker for reasoning parts. */
    val reasoningStreamingState = StreamingStateTracker()

    // ============ SseEventHandler ============

    override fun handle(event: SseEvent, serverId: String): Boolean {
        if (event is SseEvent.SessionNext) {
            handleSessionNextEvent(event.event)
            return true
        }
        return false
    }

    // ============ Event Processing ============

    fun handleSessionNextEvent(event: SessionNextEvent) {
        if (BuildConfig.DEBUG) Log.d(TAG, "Processing: ${event::class.simpleName}")
        when (event) {
            is SessionNextEvent.AgentSwitched -> handleAgentSwitched(event)
            is SessionNextEvent.ModelSwitched -> handleModelSwitched(event)

            is SessionNextEvent.TextStarted -> textStreamingState.onStarted(event.partId, event.sessionId)
            is SessionNextEvent.TextDelta -> textStreamingState.onDelta(event.partId, event.delta)
            is SessionNextEvent.TextEnded -> {
                textStreamingState.onEnded(event.partId)
                textStreamingState.cleanup()
            }

            is SessionNextEvent.ReasoningStarted -> reasoningStreamingState.onStarted(event.partId, event.sessionId)
            is SessionNextEvent.ReasoningDelta -> reasoningStreamingState.onDelta(event.partId, event.delta)
            is SessionNextEvent.ReasoningEnded -> {
                reasoningStreamingState.onEnded(event.partId)
                reasoningStreamingState.cleanup()
            }

            is SessionNextEvent.ToolInputStarted -> handleToolInputStarted(event)
            is SessionNextEvent.ToolInputDelta -> { /* delta tracked, no state change */ }
            is SessionNextEvent.ToolCalled -> { /* full input available, no state change */ }
            is SessionNextEvent.ToolProgress -> handleToolProgress(event)
            is SessionNextEvent.ToolSuccess -> handleToolComplete(event.sessionId, event.callId)
            is SessionNextEvent.ToolFailed -> handleToolComplete(event.sessionId, event.callId)

            is SessionNextEvent.StepStarted -> handleStepStarted(event)
            is SessionNextEvent.StepEnded -> handleStepEnded(event.sessionId)
            is SessionNextEvent.StepFailed -> handleStepEnded(event.sessionId)

            is SessionNextEvent.ShellStarted -> handleShellStarted(event)
            is SessionNextEvent.ShellEnded -> handleShellEnded(event.sessionId)

            is SessionNextEvent.CompactionStarted -> handleCompactionStarted(event)
            is SessionNextEvent.CompactionDelta -> { /* delta tracked */ }
            is SessionNextEvent.CompactionEnded -> handleCompactionEnded(event.sessionId)

            is SessionNextEvent.Prompted -> { /* informational */ }
            is SessionNextEvent.Retried -> { /* informational — retry display handled by existing Part.Retry */ }
            is SessionNextEvent.Synthetic -> { /* informational */ }
            is SessionNextEvent.Unknown -> {
                Log.w(TAG, "Unhandled session.next event: ${event.rawType}")
            }
        }
    }

    private fun handleAgentSwitched(event: SessionNextEvent.AgentSwitched) {
        _currentAgent.update { it + (event.sessionId to event.agent) }
    }

    private fun handleModelSwitched(event: SessionNextEvent.ModelSwitched) {
        _currentModel.update { it + (event.sessionId to (event.providerId to event.modelId)) }
    }

    private fun handleToolInputStarted(event: SessionNextEvent.ToolInputStarted) {
        _activeToolProgress.update { current ->
            val sessionTools = current[event.sessionId]?.toMutableList() ?: mutableListOf()
            sessionTools.add(ToolProgressInfo(
                callId = event.callId,
                partId = event.partId,
                tool = event.tool,
                status = "started"
            ))
            current + (event.sessionId to sessionTools)
        }
    }

    private fun handleToolProgress(event: SessionNextEvent.ToolProgress) {
        _activeToolProgress.update { current ->
            val sessionTools = current[event.sessionId] ?: return@update current
            val updated = sessionTools.map { tool ->
                if (tool.callId == event.callId) {
                    tool.copy(
                        status = "running",
                        progress = event.progress,
                        title = event.title
                    )
                } else tool
            }
            current + (event.sessionId to updated)
        }
    }

    private fun handleToolComplete(sessionId: String, callId: String) {
        _activeToolProgress.update { current ->
            val sessionTools = current[sessionId]?.filter { it.callId != callId } ?: emptyList()
            current + (sessionId to sessionTools)
        }
    }

    private fun handleStepStarted(event: SessionNextEvent.StepStarted) {
        _stepProgress.update { it + (event.sessionId to StepProgressInfo(
            step = event.step,
            agent = event.agent,
            model = event.model
        )) }
    }

    private fun handleStepEnded(sessionId: String) {
        _stepProgress.update { it - sessionId }
    }

    private fun handleShellStarted(event: SessionNextEvent.ShellStarted) {
        _shellState.update { it + (event.sessionId to ShellStateInfo(command = event.command)) }
    }

    private fun handleShellEnded(sessionId: String) {
        _shellState.update { it - sessionId }
    }

    private fun handleCompactionStarted(event: SessionNextEvent.CompactionStarted) {
        _compactionState.update { it + (event.sessionId to CompactionStateInfo(
            isActive = true,
            reason = event.reason
        )) }
    }

    private fun handleCompactionEnded(sessionId: String) {
        _compactionState.update { it - sessionId }
    }

    // ============ Cleanup ============

    fun clearForSession(sessionId: String) {
        _currentAgent.update { it - sessionId }
        _currentModel.update { it - sessionId }
        _activeToolProgress.update { it - sessionId }
        _stepProgress.update { it - sessionId }
        _compactionState.update { it - sessionId }
        _shellState.update { it - sessionId }
        textStreamingState.clearForSession(sessionId)
        reasoningStreamingState.clearForSession(sessionId)
    }

    fun clearForServer(sessionIds: Set<String>) {
        for (sessionId in sessionIds) {
            clearForSession(sessionId)
        }
    }

    fun clearAll() {
        _currentAgent.value = emptyMap()
        _currentModel.value = emptyMap()
        _activeToolProgress.value = emptyMap()
        _stepProgress.value = emptyMap()
        _compactionState.value = emptyMap()
        _shellState.value = emptyMap()
        textStreamingState.clearAll()
        reasoningStreamingState.clearAll()
    }
}
```

- [ ] **Verify test passes:** `.\gradlew :app:testDevDebugUnitTest --tests "dev.minios.ocremote.data.repository.handler.SessionNextEventHandlerTest" --rerun` (180s)
- [ ] **Verify compile:** `.\gradlew :app:compileDevDebugKotlin` (120s)
- [ ] **Commit:** `git add -A && git commit -m "feat(layer3): add SessionNextEventHandler with state tracking"`

---

### Task 5: EventDispatcher Integration (P0)

**Wire SessionNextEventHandler into EventDispatcher and add tests.**

#### Step 5.1: Write failing tests for EventDispatcher with SessionNext

Edit `app/src/test/kotlin/dev/minios/ocremote/data/repository/EventDispatcherTest.kt` — **add** the following tests at the end of the class (before the closing `}`):

```kotlin
    // ============ SessionNext Event Integration ============

    @Test
    fun `SessionNext event routed to SessionNextHandler`() = runTest {
        val agentEvent = SessionNextEvent.AgentSwitched(sessionId = "s1", agent = "code")
        dispatcher.processEvent(SseEvent.SessionNext(agentEvent), "server1")

        assertEquals("code", dispatcher.currentAgent.value["s1"])
    }

    @Test
    fun `SessionDeleted cascades cleanup to SessionNextHandler`() = runTest {
        val session = testSession("s1")
        dispatcher.processEvent(SseEvent.SessionCreated(session), "server1")

        val agentEvent = SessionNextEvent.AgentSwitched(sessionId = "s1", agent = "code")
        dispatcher.processEvent(SseEvent.SessionNext(agentEvent), "server1")

        dispatcher.processEvent(SseEvent.SessionDeleted(session), "server1")

        assertNull(dispatcher.currentAgent.value["s1"])
    }

    @Test
    fun `SessionNext tool progress tracked`() = runTest {
        val toolEvent = SessionNextEvent.ToolInputStarted(
            sessionId = "s1", messageId = "m1", partId = "p1",
            callId = "c1", tool = "bash"
        )
        dispatcher.processEvent(SseEvent.SessionNext(toolEvent), "server1")

        val tools = dispatcher.activeToolProgress.value["s1"]
        assertNotNull(tools)
        assertEquals(1, tools!!.size)
        assertEquals("bash", tools[0].tool)
    }

    @Test
    fun `clearAll resets SessionNextHandler`() = runTest {
        val agentEvent = SessionNextEvent.AgentSwitched(sessionId = "s1", agent = "code")
        dispatcher.processEvent(SseEvent.SessionNext(agentEvent), "server1")

        dispatcher.clearAll()

        assertTrue(dispatcher.currentAgent.value.isEmpty())
        assertTrue(dispatcher.activeToolProgress.value.isEmpty())
    }

    @Test
    fun `clearForServer resets SessionNextHandler for server sessions`() = runTest {
        val session = testSession("s1")
        dispatcher.processEvent(SseEvent.SessionCreated(session), "server1")
        val agentEvent = SessionNextEvent.AgentSwitched(sessionId = "s1", agent = "code")
        dispatcher.processEvent(SseEvent.SessionNext(agentEvent), "server1")

        dispatcher.clearForServer("server1")

        assertNull(dispatcher.currentAgent.value["s1"])
    }
```

**Add imports** at the top of the test file:

```kotlin
import dev.minios.ocremote.domain.model.SessionNextEvent
```

- [ ] **Verify test fails:** `.\gradlew :app:testDevDebugUnitTest --tests "dev.minios.ocremote.data.repository.EventDispatcherTest" --rerun` (180s) — should fail: dispatcher has no `currentAgent`, `activeToolProgress` etc.

#### Step 5.2: Update EventDispatcher to include SessionNextEventHandler

Edit `app/src/main/kotlin/dev/minios/ocremote/data/repository/EventDispatcher.kt`:

**1. Add import:**
```kotlin
import dev.minios.ocremote.data.repository.handler.SessionNextEventHandler
```

**2. Add constructor parameter** (after `miscHandler`):
```kotlin
    private val sessionNextHandler: SessionNextEventHandler,
```

**3. Add public state properties** (after `val projectInfo` line):
```kotlin
    // Session Next state
    val currentAgent: StateFlow<Map<String, String>> get() = sessionNextHandler.currentAgent
    val currentModel: StateFlow<Map<String, Pair<String, String>>> get() = sessionNextHandler.currentModel
    val activeToolProgress: StateFlow<Map<String, List<ToolProgressInfo>>> get() = sessionNextHandler.activeToolProgress
    val stepProgress: StateFlow<Map<String, StepProgressInfo>> get() = sessionNextHandler.stepProgress
    val compactionState: StateFlow<Map<String, CompactionStateInfo>> get() = sessionNextHandler.compactionState
    val shellState: StateFlow<Map<String, ShellStateInfo>> get() = sessionNextHandler.shellState
```

**4. Add imports for the new types:**
```kotlin
import dev.minios.ocremote.data.repository.handler.ToolProgressInfo
import dev.minios.ocremote.data.repository.handler.StepProgressInfo
import dev.minios.ocremote.data.repository.handler.CompactionStateInfo
import dev.minios.ocremote.data.repository.handler.ShellStateInfo
```

**5. Add `sessionNextHandler.handle(event, serverId)` call** in `processEvent()` after `miscHandler.handle(event, serverId)`:
```kotlin
        sessionNextHandler.handle(event, serverId)
```

**6. Add cascade cleanup for SessionDeleted** — in the `if (event is SseEvent.SessionDeleted)` block, add:
```kotlin
            sessionNextHandler.clearForSession(sessionId)
```

**7. Add to clearAll():**
```kotlin
        sessionNextHandler.clearAll()
```

**8. Add to clearForServer():**
```kotlin
        sessionNextHandler.clearForServer(sessionIds)
```

**9. Update EventDispatcherTest setup** — edit `app/src/test/kotlin/dev/minios/ocremote/data/repository/EventDispatcherTest.kt`, add a field and update setup:

```kotlin
    private lateinit var sessionNextHandler: SessionNextEventHandler
```

In `setup()`:
```kotlin
        sessionNextHandler = SessionNextEventHandler()
```

In the `EventDispatcher(...)` constructor call, add:
```kotlin
            sessionNextHandler = sessionNextHandler,
```

- [ ] **Verify all EventDispatcher tests pass:** `.\gradlew :app:testDevDebugUnitTest --tests "dev.minios.ocremote.data.repository.EventDispatcherTest" --rerun` (180s)
- [ ] **Verify compile:** `.\gradlew :app:compileDevDebugKotlin` (120s)
- [ ] **Commit:** `git add -A && git commit -m "feat(layer3): wire SessionNextEventHandler into EventDispatcher"`

---

### Task 6: ToolProgressCard Composable (P1)

**UI component showing tool execution progress with status animation.**

> Verify via compile check only (Compose preview).

#### Step 6.1: Create ToolProgressCard composable

File: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/ToolProgressCard.kt`

```kotlin
package dev.minios.ocremote.ui.screens.chat.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.data.repository.handler.ToolProgressInfo
import dev.minios.ocremote.ui.theme.AlphaTokens
import dev.minios.ocremote.ui.theme.AppMotion

/**
 * Card displaying real-time tool execution progress.
 * Shows tool name, status text, and an animated progress indicator.
 */
@Composable
fun ToolProgressCard(
    toolInfo: ToolProgressInfo,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = AlphaTokens.FAINT)
        ),
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Animated icon
            val transition = rememberInfiniteTransition(label = "tool_progress_rotation")
            val rotation by transition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(AppMotion.PULSE_CYCLE, easing = AppMotion.StandardEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "tool_icon_rotation"
            )
            Icon(
                imageVector = if (toolInfo.status == "started") Icons.Default.Sync else Icons.Default.Build,
                contentDescription = null,
                modifier = Modifier
                    .size(16.dp)
                    .rotate(rotation),
                tint = MaterialTheme.colorScheme.tertiary
            )

            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                // Tool name + progress
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = toolInfo.title ?: toolInfo.tool,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1
                    )
                    if (toolInfo.progress != null) {
                        Text(
                            text = toolInfo.progress,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Indeterminate progress bar
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.tertiary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
        }
    }
}
```

- [ ] **Verify compile:** `.\gradlew :app:compileDevDebugKotlin` (120s)
- [ ] **Commit:** `git add -A && git commit -m "feat(layer3): add ToolProgressCard composable"`

---

### Task 7: StepProgressIndicator Composable (P1)

**Shows current step number, agent, and model in a compact indicator.**

> Verify via compile check only.

#### Step 7.1: Create StepProgressIndicator composable

File: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/StepProgressIndicator.kt`

```kotlin
package dev.minios.ocremote.ui.screens.chat.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.data.repository.handler.StepProgressInfo
import dev.minios.ocremote.ui.theme.AlphaTokens

/**
 * Step progress indicator showing current step number, agent, and model.
 * Uses Material3 indeterminate LinearProgressIndicator.
 */
@Composable
fun StepProgressIndicator(
    stepInfo: StepProgressInfo,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Step ${stepInfo.step}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            if (stepInfo.agent.isNotBlank()) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stepInfo.agent,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MEDIUM)
                )
            }
            if (stepInfo.model.isNotBlank()) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stepInfo.model,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT)
                )
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.MEDIUM),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}
```

- [ ] **Verify compile:** `.\gradlew :app:compileDevDebugKotlin` (120s)
- [ ] **Commit:** `git add -A && git commit -m "feat(layer3): add StepProgressIndicator composable"`

---

### Task 8: CompactionBanner Composable (P2)

**Banner showing context compaction progress.**

> Verify via compile check only.

#### Step 8.1: Create CompactionBanner composable

File: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/CompactionBanner.kt`

```kotlin
package dev.minios.ocremote.ui.screens.chat.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.data.repository.handler.CompactionStateInfo
import dev.minios.ocremote.ui.theme.AlphaTokens
import dev.minios.ocremote.ui.theme.AppMotion

/**
 * Banner showing context compaction in progress.
 * Displays "Compressing context..." with an animated indicator and reason.
 */
@Composable
fun CompactionBanner(
    state: CompactionStateInfo,
    modifier: Modifier = Modifier
) {
    if (!state.isActive) return

    val transition = rememberInfiniteTransition(label = "compaction_pulse")
    val alpha by transition.animateFloat(
        initialValue = AlphaTokens.MEDIUM,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(AppMotion.BREATH_CYCLE),
            repeatMode = RepeatMode.Reverse
        ),
        label = "compaction_alpha"
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = AlphaTokens.FAINT)
        ),
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Compress,
                contentDescription = null,
                modifier = Modifier
                    .size(16.dp)
                    .graphicsLayer { this.alpha = alpha },
                tint = MaterialTheme.colorScheme.tertiary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (state.reason.isNotBlank()) "Compressing context: ${state.reason}" else "Compressing context...",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.weight(1f)
            )
        }
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.tertiary.copy(alpha = AlphaTokens.MEDIUM),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}
```

- [ ] **Verify compile:** `.\gradlew :app:compileDevDebugKotlin` (120s)
- [ ] **Commit:** `git add -A && git commit -m "feat(layer3): add CompactionBanner composable"`

---

### Task 9: ChatUiState Integration — Agent/Model Display (P2)

**Add agent/model info to ChatUiState and display in ChatTopBar subtitle.**

#### Step 9.1: Add agent/model fields to ChatUiState

Edit `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt`:

Add fields to `ChatUiState` data class (after `val toolExpandedStates`):
```kotlin
    /** Current agent name from session.next.agent.switched events. */
    val currentAgentName: String? = null,
    /** Current model ID from session.next.model.switched events. */
    val currentModelId: String? = null,
```

**Wire the flows** — in the `combine(...)` block that builds `uiState` (search for `val uiState: StateFlow<ChatUiState>`), add `eventDispatcher.currentAgent` and `eventDispatcher.currentModel` to the combine:

Add two new parameters to the `combine(...)` call. Since `combine` supports up to a certain number of flows, find where it ends and add:

```kotlin
        eventDispatcher.currentAgent,
        eventDispatcher.currentModel,
```

And in the lambda, add parameters and map them:
```kotlin
        currentAgent, currentModel ->
```

Then in the ChatUiState constructor call inside the lambda, add:
```kotlin
            currentAgentName = currentAgent[sessionId],
            currentModelId = currentModel[sessionId]?.second,
```

> **Note:** The exact position depends on the current combine structure. Search for `toolExpandedStates =` in the ChatUiState constructor and add after it.

#### Step 9.2: Display in ChatTopBar

Edit `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/ChatTopBar.kt`:

Add parameters to `ChatTopBar` composable:
```kotlin
    currentAgentName: String? = null,
    currentModelId: String? = null,
```

In the title `Column`, after the subtitle stats block, add:
```kotlin
                if (!currentAgentName.isNullOrBlank() || !currentModelId.isNullOrBlank()) {
                    val agentParts = mutableListOf<String>()
                    if (!currentAgentName.isNullOrBlank()) agentParts.add(currentAgentName)
                    if (!currentModelId.isNullOrBlank()) agentParts.add(currentModelId)
                    Text(
                        text = agentParts.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.MUTED)
                    )
                }
```

- [ ] **Verify compile:** `.\gradlew :app:compileDevDebugKotlin` (120s)
- [ ] **Commit:** `git add -A && git commit -m "feat(layer3): show agent/model in ChatTopBar from session.next events"`

---

### Task 10: Retry Event Display (P1)

**Wire SessionNextEvent.Retried to update the existing retry display.**

> Depends on Layer 1.9 (SessionRetryCard). If not available, the existing `Part.Retry` rendering in `PartContent.kt` already handles retries. This task adds real-time retry event tracking.

#### Step 10.1: Add retry tracking to SessionNextEventHandler

Edit `app/src/main/kotlin/dev/minios/ocremote/data/repository/handler/SessionNextEventHandler.kt`:

Add state flow for retry events:
```kotlin
    private val _retryState = MutableStateFlow<Map<String, Int>>(emptyMap()) // sessionId -> attempt count
    val retryState: StateFlow<Map<String, Int>> = _retryState.asStateFlow()
```

Update the `Retried` handler in `handleSessionNextEvent`:
```kotlin
            is SessionNextEvent.Retried -> {
                _retryState.update { it + (event.sessionId to event.attempt) }
            }
```

Update `clearForSession`:
```kotlin
        _retryState.update { it - sessionId }
```

Update `clearAll`:
```kotlin
        _retryState.value = emptyMap()
```

- [ ] **Verify compile:** `.\gradlew :app:compileDevDebugKotlin` (120s)
- [ ] **Commit:** `git add -A && git commit -m "feat(layer3): track retry events in SessionNextEventHandler"`

---

### Task 11: Shell Event Handling (P2)

**Display shell execution state in the terminal tab area.**

The `SessionNextEventHandler` already tracks shell state (from Task 4). This task wires it to the UI.

#### Step 11.1: Expose shell state via EventDispatcher

Edit `app/src/main/kotlin/dev/minios/ocremote/data/repository/EventDispatcher.kt` — add property:
```kotlin
    val shellState: StateFlow<Map<String, ShellStateInfo>> get() = sessionNextHandler.shellState
```

This is already done in Task 5. Verify it's present.

#### Step 11.2: Use in ChatViewModel for terminal tab title

Edit `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt`:

The shell state is already accessible via `eventDispatcher.shellState`. In the terminal tab management, when a `ShellStarted` event arrives for the current session, update the terminal tab title to "Running: {command}". When `ShellEnded` arrives, reset it.

Since the existing terminal system uses `ServerTerminalRegistry` and `TerminalTabUi`, the integration point is observing the shell state flow:

```kotlin
    // Observe shell state for terminal tab title updates
    init {
        viewModelScope.launch {
            eventDispatcher.shellState.collect { shellStates ->
                val shellInfo = shellStates[sessionId]
                if (shellInfo != null) {
                    terminalWorkspace.updateActiveTitle("Running: ${shellInfo.command}")
                } else {
                    terminalWorkspace.updateActiveTitle(null)
                }
            }
        }
    }
```

> **Note:** `TerminalTabUi` and `ServerTerminalRegistry` may not have `updateActiveTitle`. If not, this can be deferred. The shell state is still tracked and exposed via StateFlow for future use.

- [ ] **Verify compile:** `.\gradlew :app:compileDevDebugKotlin` (120s)
- [ ] **Commit:** `git add -A && git commit -m "feat(layer3): wire shell events to terminal tab state"`

---

### Task 12: Event Sequence Validation (P2)

**Optional: Track event sequence numbers and detect gaps.**

#### Step 12.1: Add sequence tracking to SessionNextEventHandler

Edit `app/src/main/kotlin/dev/minios/ocremote/data/repository/handler/SessionNextEventHandler.kt`:

Add sequence tracking state:
```kotlin
    /** Last event sequence number per session. Null if not tracked. */
    private val _lastEventSeq = MutableStateFlow<Map<String, Long>>(emptyMap())
    val lastEventSeq: StateFlow<Map<String, Long>> = _lastEventSeq.asStateFlow()

    /** Sessions where a sequence gap was detected. */
    private val _gapDetected = MutableStateFlow<Set<String>>(emptySet())
    val gapDetected: StateFlow<Set<String>> = _gapDetected.asStateFlow()
```

Add sequence tracking method:
```kotlin
    /**
     * Update sequence tracking for a session.
     * Call from SseClient when an event has a sequence number.
     * Detects gaps and sets [gapDetected] when events are missed.
     */
    fun trackSequence(sessionId: String, seq: Long) {
        val last = _lastEventSeq.value[sessionId]
        if (last != null && seq > last + 1) {
            Log.w(TAG, "Sequence gap detected for session $sessionId: expected ${last + 1}, got $seq (missed ${seq - last - 1} events)")
            _gapDetected.update { it + sessionId }
        }
        _lastEventSeq.update { it + (sessionId to seq) }
    }

    fun clearGap(sessionId: String) {
        _gapDetected.update { it - sessionId }
    }
```

Update `clearForSession`:
```kotlin
        _lastEventSeq.update { it - sessionId }
        _gapDetected.update { it - sessionId }
```

Update `clearAll`:
```kotlin
        _lastEventSeq.value = emptyMap()
        _gapDetected.value = emptySet()
```

#### Step 12.2: Wire gap detection to REST refresh

Edit `app/src/main/kotlin/dev/minios/ocremote/data/repository/EventDispatcher.kt`:

Add property:
```kotlin
    val gapDetected: StateFlow<Set<String>> get() = sessionNextHandler.gapDetected
```

Add method:
```kotlin
    fun clearGap(sessionId: String) {
        sessionNextHandler.clearGap(sessionId)
    }
```

The actual REST refresh trigger should be observed by the service or ViewModel layer. This is outside the scope of this task — the handler just detects and reports gaps.

- [ ] **Verify compile:** `.\gradlew :app:compileDevDebugKotlin` (120s)
- [ ] **Verify all tests still pass:** `.\gradlew :app:testDevDebugUnitTest --rerun` (180s)
- [ ] **Commit:** `git add -A && git commit -m "feat(layer3): add event sequence validation with gap detection"`

---

### Task 13: Final Integration & Full Test Suite

**Run the complete test suite and verify everything works together.**

#### Step 13.1: Run full test suite

```bash
.\gradlew :app:testDevDebugUnitTest --rerun
```

- [ ] **All tests pass**

#### Step 13.2: Run compile check

```bash
.\gradlew :app:compileDevDebugKotlin
```

- [ ] **Compiles successfully**

#### Step 13.3: Verify no regressions in existing EventDispatcher tests

```bash
.\gradlew :app:testDevDebugUnitTest --tests "dev.minios.ocremote.data.repository.*" --rerun
```

- [ ] **All repository tests pass**

- [ ] **Final commit:** `git add -A && git commit -m "feat(layer3): complete fine-grained event processing implementation"`

---

## Summary

| Task | Priority | New Files | Modified Files | Test Coverage |
|------|----------|-----------|----------------|---------------|
| 1. SessionNextEvent model | P0 | 1 (model) | 0 | Full (25 types) |
| 2. SseClient parsing | P0 | 1 (test) | 1 (SseClient + SseEvent) | Parsing unit tests |
| 3. StreamingStateTracker | P1 | 2 (impl + test) | 0 | State machine tests |
| 4. SessionNextEventHandler | P0 | 2 (handler + test) | 0 | Handler unit tests |
| 5. EventDispatcher wiring | P0 | 0 | 2 (dispatcher + test) | Integration tests |
| 6. ToolProgressCard | P1 | 1 (composable) | 0 | Compile check |
| 7. StepProgressIndicator | P1 | 1 (composable) | 0 | Compile check |
| 8. CompactionBanner | P2 | 1 (composable) | 0 | Compile check |
| 9. Agent/Model display | P2 | 0 | 2 (ViewModel + TopBar) | Compile check |
| 10. Retry event display | P1 | 0 | 1 (handler) | Handler test |
| 11. Shell event handling | P2 | 0 | 2 (dispatcher + VM) | Compile check |
| 12. Sequence validation | P2 | 0 | 1 (handler + dispatcher) | Compile check |
| 13. Final integration | — | 0 | 0 | Full test suite |

**Total new files:** 8 production + 4 test = 12 files
**Total modified files:** ~6 existing files
**Estimated effort:** 13 tasks × ~5 min average = ~65 minutes
