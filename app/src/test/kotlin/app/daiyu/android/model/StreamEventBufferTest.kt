package app.daiyu.android.model

import app.daiyu.android.domain.ModelEvent
import org.junit.Assert.*
import org.junit.Test

class StreamEventBufferTest {
    @Test fun adjacentTextDeltasWriteOneBoundedUpdate() {
        val events = mutableListOf<ModelEvent>()
        val buffer = StreamEventBuffer(events::add, nanoTime = { 0 })
        repeat(512) { buffer.accept(ModelEvent.TextDelta("text", "x")) }
        assertEquals(listOf(ModelEvent.TextDelta("text", "x".repeat(512))), events)
        buffer.flush()
        assertEquals(1, events.size)
    }

    @Test fun elapsedTimeFlushesAtTheNextReceivedDelta() {
        var now = 0L
        val events = mutableListOf<ModelEvent>()
        val buffer = StreamEventBuffer(events::add, nanoTime = { now })
        buffer.accept(ModelEvent.TextDelta("text", "a"))
        now = 250_000_000L
        buffer.accept(ModelEvent.TextDelta("text", "b"))
        assertEquals(listOf(ModelEvent.TextDelta("text", "ab")), events)
    }

    @Test fun cumulativeReasoningReplacesRatherThanRepeatingThePrefix() {
        val events = mutableListOf<ModelEvent>()
        val buffer = StreamEventBuffer(events::add, nanoTime = { 0 })
        buffer.accept(ModelEvent.ReasoningDelta("reason", "a", cumulative = true))
        buffer.accept(ModelEvent.ReasoningDelta("reason", "abc", cumulative = true))
        buffer.flush()
        assertEquals(listOf(ModelEvent.ReasoningDelta("reason", "abc", cumulative = true)), events)
    }

    @Test fun finalAndProtocolEventsFlushBeforeTheirBoundary() {
        val events = mutableListOf<ModelEvent>()
        val buffer = StreamEventBuffer(events::add, nanoTime = { 0 })
        buffer.accept(ModelEvent.TextDelta("text", "a"))
        buffer.accept(ModelEvent.TextFinal("text", "abc"))
        buffer.accept(ModelEvent.ProtocolOutput(listOf("{}")))
        assertEquals(listOf(ModelEvent.TextDelta("text", "a"), ModelEvent.TextFinal("text", "abc"),
            ModelEvent.ProtocolOutput(listOf("{}"))), events)
    }

    @Test fun differentItemsAndReasoningKindsNeverMerge() {
        val events = mutableListOf<ModelEvent>()
        val buffer = StreamEventBuffer(events::add, nanoTime = { 0 })
        buffer.accept(ModelEvent.ReasoningDelta("one", "r"))
        buffer.accept(ModelEvent.TextDelta("one", "a"))
        buffer.accept(ModelEvent.TextDelta("two", "b"))
        buffer.flush()
        assertEquals(listOf(ModelEvent.ReasoningDelta("one", "r"), ModelEvent.TextDelta("one", "a"),
            ModelEvent.TextDelta("two", "b")), events)
    }

    @Test fun failedConsumerIsNotRetriedByFlushOrTheNextEvent() {
        val failure = IllegalStateException("consumer")
        var calls = 0
        val buffer = StreamEventBuffer({ calls++; throw failure }, nanoTime = { 0 })
        buffer.accept(ModelEvent.TextDelta("text", "a"))
        repeat(2) {
            try { buffer.flush(); fail("expected failure") } catch (actual: IllegalStateException) { assertSame(failure, actual) }
        }
        try { buffer.accept(ModelEvent.TextFinal("text", "ab")); fail("expected failure") }
        catch (actual: IllegalStateException) { assertSame(failure, actual) }
        assertEquals(1, calls)
    }

    @Test fun discardDoesNotDeliverAnExpiredAttempt() {
        val events = mutableListOf<ModelEvent>()
        val buffer = StreamEventBuffer(events::add, nanoTime = { 0 })
        buffer.accept(ModelEvent.TextDelta("text", "unconfirmed"))
        buffer.discard(); buffer.flush()
        assertTrue(events.isEmpty())
    }
}
