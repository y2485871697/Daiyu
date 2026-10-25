package app.daiyu.android.data.repository

import org.junit.Assert.*
import org.junit.Test

class ModelUsageProjectionTest {
    @Test fun requestIdentityIncludesOwnerAndFoldHappensBeforeDateSelection() {
        val events = listOf(
            ModelUsageEvent(1, 5, 1, conversationId = "one", requestId = "same"),
            ModelUsageEvent(2, 7, 2, conversationId = "two", requestId = "same"),
            ModelUsageEvent(3, 9, 3, conversationId = "one", requestId = "same"))
        assertEquals(listOf(9L, 7L), events.collapsedByRound().map { it.inputTokens })
        val model = ModelUsageModelUi("m", "Model", 16, 5, 2, 1, events)
        assertNull(model.filtered(1, 1))
        val filtered = model.filtered(2, 3)!!
        assertEquals(16L, filtered.inputTokens); assertEquals(2, filtered.conversationCount)
    }
    @Test fun filterPreservesUnknownFieldsRatherThanPromotingThemToReportedZero() {
        val event = ModelUsageEvent(100, 0, 2, requestId = "one", inputReported = false, cachedReported = false)
        val model = ModelUsageModelUi("m", "Model", 0, 2, 0, 1, listOf(event), inputComplete = false, cachedComplete = false)
        val filtered = model.filtered(100, 100)!!
        assertFalse(filtered.inputComplete); assertFalse(filtered.cachedComplete); assertTrue(filtered.outputComplete)
        assertEquals(2L, filtered.outputTokens)
    }
    @Test fun cachesAreSubsetsAndNeverCountedAgainInTotal() {
        val model = ModelUsageModelUi("m", "Model", 100, 20, 2, 4, cachedTokens = 40, cacheCreationTokens = 10)
        assertEquals(120L, model.totalTokens); assertEquals(50L, model.freshInputTokens)
        assertEquals(12L, model.dailyAverageTokens); assertEquals(25L, model.conversationAverageTokens)
    }
    @Test fun dateFilterIncludesEveryRequestBeyondTheOldEventWindow() {
        val events = (0..4000).map { ModelUsageEvent(it.toLong(), 1, 0, requestId = "request:$it") }
        val model = ModelUsageModelUi("m", "Model", 4001, 0, 0, 1, events)
        assertEquals(4001L, model.filtered(0, 4000)!!.inputTokens)
        assertEquals(1L, model.filtered(0, 0)!!.inputTokens)
    }
    @Test fun overflowingMetricStaysIncompleteAndDoesNotWrapOrThrow() {
        val events = listOf(ModelUsageEvent(1, Long.MAX_VALUE, 0), ModelUsageEvent(2, 1, 0))
        val model = ModelUsageModelUi("m", "Model", 0, 0, 0, 1, events)
        val filtered = model.filtered(1, 2)!!
        assertEquals(Long.MAX_VALUE, filtered.inputTokens)
        assertFalse(filtered.inputComplete)
        assertEquals(0L, filtered.outputTokens)
        assertTrue(filtered.outputComplete)
        assertEquals(Long.MAX_VALUE, listOf(Long.MAX_VALUE, 1L).safeTokenSum { it })
        assertFalse(listOf(Long.MAX_VALUE, 1L).tokenSumAvailable { it })
    }

}
