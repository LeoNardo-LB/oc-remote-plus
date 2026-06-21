package dev.leonardo.ocremotev2.domain.tracker

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
