package app.daiyu.android.domain.usage

import app.daiyu.android.domain.ModelEvent
import org.junit.Assert.*
import org.junit.Test

class RecordedUsageSummaryTest {
    private fun aggregate(vararg values: ModelEvent.Usage) = aggregateRecordedUsage(values.asSequence())
    @Test fun noSnapshotsDoNotInventKnownZero() {
        val value = aggregate()
        assertEquals(0L, value.requestSnapshotCount)
        assertEquals(ReportedTokenSubtotal(null, 0, 0), value.input)
        assertEquals(value.input, value.output); assertEquals(value.input, value.cached)
    }
    @Test fun allUnknownStillCountsOneReportedRequestSnapshot() {
        val value = aggregate(ModelEvent.Usage(null, null))
        assertEquals(1L, value.requestSnapshotCount)
        assertEquals(ReportedTokenSubtotal(null, 0, 1), value.input)
        assertEquals(value.input, value.output); assertEquals(value.input, value.cached)
    }
    @Test fun knownZeroIsNotMissing() {
        val value = aggregate(ModelEvent.Usage(0, 0, 0))
        assertEquals(ReportedTokenSubtotal(0, 1, 0), value.input)
        assertEquals(value.input, value.output); assertEquals(value.input, value.cached)
    }
    @Test fun subtotalsKeepIndependentCoverageAndDoNotAddCacheToInput() {
        val value = aggregate(ModelEvent.Usage(100, 20, 30), ModelEvent.Usage(5, 0))
        assertEquals(2L, value.requestSnapshotCount)
        assertEquals(ReportedTokenSubtotal(105, 2, 0), value.input)
        assertEquals(ReportedTokenSubtotal(20, 2, 0), value.output)
        assertEquals(ReportedTokenSubtotal(30, 1, 1), value.cached)
    }
    @Test fun cacheOnlyDoesNotInferTotalInput() {
        val value = aggregate(ModelEvent.Usage(null, null, 7))
        assertNull(value.input.reportedTokens); assertNull(value.output.reportedTokens)
        assertEquals(ReportedTokenSubtotal(7, 1, 0), value.cached)
    }
    @Test fun equalValuesFromDifferentRequestsAreNotDeduplicated() {
        val value = aggregate(ModelEvent.Usage(10, 20, 5), ModelEvent.Usage(10, 20, 5))
        assertEquals(2L, value.requestSnapshotCount); assertEquals(20L, value.input.reportedTokens)
        assertEquals(40L, value.output.reportedTokens); assertEquals(10L, value.cached.reportedTokens)
    }
    @Test fun mixedCoverageDoesNotInferMissingFields() {
        val value = aggregate(ModelEvent.Usage(null, 0), ModelEvent.Usage(9, null, 2))
        for (subtotal in listOf(value.input, value.output, value.cached)) {
            assertEquals(1L, subtotal.reportedRequestCount); assertEquals(1L, subtotal.unknownRequestCount)
        }
        assertEquals(9L, value.input.reportedTokens); assertEquals(0L, value.output.reportedTokens)
    }
    @Test fun overflowingAnySubtotalFailsInsteadOfWrapping() {
        for (values in listOf(
            arrayOf(ModelEvent.Usage(Long.MAX_VALUE, null), ModelEvent.Usage(1, null)),
            arrayOf(ModelEvent.Usage(null, Long.MAX_VALUE), ModelEvent.Usage(null, 1)),
            arrayOf(ModelEvent.Usage(null, null, Long.MAX_VALUE), ModelEvent.Usage(null, null, 1)))) {
            try { aggregate(*values); fail("overflow accepted") } catch (_: ArithmeticException) {}
        }
    }
}
