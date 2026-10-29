package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.ReportedReasoning
import org.junit.Assert.*
import org.junit.Test

class ReasoningHandoffTest {
    @Test fun historyIsReadyButLiveReasoningWaitsForItsVisualHandoff() {
        val history = ReportedReasoning("old", "history")
        val live = ReportedReasoning("live", "thinking", elapsedMillis = 0L, activeSinceNanos = 1L)
        val handoff = ReasoningHandoff(listOf(history, live))
        assertTrue(handoff.ready(history)); assertFalse(handoff.ready(live))
        val sealed = live.copy(activeSinceNanos = null, text = "thinking finished")
        assertFalse(handoff.ready(sealed))
        handoff.complete(sealed)
        assertTrue(handoff.ready(listOf(history, sealed)))
        handoff.complete(sealed)
        assertTrue(handoff.ready(sealed))
    }
    @Test fun laterItemsWaitAndCompletingOneDoesNotReleaseAnother() {
        val handoff = ReasoningHandoff(emptyList())
        val first = ReportedReasoning("first", "one")
        val second = ReportedReasoning("second", "two")
        assertFalse(handoff.ready(first)); assertFalse(handoff.ready(second))
        handoff.complete(first)
        assertFalse(handoff.ready(listOf(first, second)))
        handoff.complete(second)
        assertTrue(handoff.ready(listOf(first, second)))
    }
}
