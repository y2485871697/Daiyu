package app.daiyu.android.data.repository

import app.daiyu.android.domain.ModelEvent
import app.daiyu.android.domain.runtime.RequestCheckpoint
import app.daiyu.android.domain.runtime.RequestOperation
import app.daiyu.android.domain.usage.*
import app.daiyu.android.model.usage.AttributedUsageStats
import app.daiyu.android.model.usage.ChildUsageCoverage
import org.junit.Assert.*
import org.junit.Test

class RecordedUsageProjectionTest {
    private fun projection(values: List<AttributedRequestUsage>, missing: Int = 0, bad: Int = 0,
        deferred: Int = 0): RecordedUsageProjection {
        val summary = aggregateAttributedUsage(values)
        return RecordedUsageProjection(AttributedUsageStats(summary, values,
            RecordedUsageStats(summary.main, 4, 3, missing, 1, bad, deferred),
            ChildUsageCoverage(5, 4, 1, 1, 1, 1)))
    }
    private fun row(child: Boolean, value: ModelEvent.Usage): AttributedRequestUsage = AttributedRequestUsage(
        "conversation", "parent", "parent-message", if (child) "child" else "parent",
        if (child) "child-message" else "parent-message",
        if (child) UsageOrigin.Child("child", "review-profile-not-model", "group") else UsageOrigin.Main,
        RequestCheckpoint("same-wire-request-id", "latest-attempt", if (child) "child-owner" else "parent-owner", RequestOperation.RESUME), value)

    @Test fun consumesExistingAttributionWithoutDoubleCountingCacheOrGuessingHistoricalModels() {
        val value = projection(listOf(row(false, ModelEvent.Usage(100, 20, 40)), row(true, ModelEvent.Usage(10, 3, 2))))
        assertEquals(100L, value.main.input.reportedTokens)
        assertEquals(10L, value.child.input.reportedTokens)
        assertEquals(110L, value.combined.input.reportedTokens)
        assertEquals(42L, value.combined.cached.reportedTokens)
        assertEquals(2L, value.combined.requestSnapshotCount)
        assertFalse(value.historicalModelAvailable); assertFalse(value.historicalTimeAvailable)
        val snapshot = UsageStatsSnapshot(attributedUsage = value.attributed)
        assertEquals(value, snapshot.recordedProjection)
        assertTrue(snapshot.modelUsage.providers.isEmpty())
        assertFalse(snapshot.tokenAccountingAvailable); assertFalse(snapshot.lifetimeAccountingAvailable)
    }

    @Test fun partialFieldsRemainQualifiedAndReportedZeroIsNotMissing() {
        val value = projection(listOf(row(false, ModelEvent.Usage(null, 0, null)), row(true, ModelEvent.Usage(7, null, 0))))
        assertEquals("7（已报告 1，未知 1）", formatReportedUsage(value.combined.input))
        assertEquals("0（已报告 1，未知 1）", formatReportedUsage(value.combined.output))
        assertEquals("0（已报告 1，未知 1）", formatReportedUsage(value.combined.cached))
        assertEquals("—（已报告 0，未知 0）", formatReportedUsage(projection(emptyList()).combined.input))
        assertEquals("—（已报告 0，未知 1）", formatReportedUsage(value.main.input))
    }

    @Test fun badMissingAndDeferredRowsAreVisibleCoverageNotSyntheticRequests() {
        val value = projection(emptyList(), missing = 2, bad = 1, deferred = 1)
        assertEquals(0L, value.combined.requestSnapshotCount)
        assertNull(value.combined.input.reportedTokens)
        assertTrue(value.coverageDescription.contains("检查 3/4"))
        assertTrue(value.coverageDescription.contains("缺 checkpoint 2"))
        assertTrue(value.coverageDescription.contains("坏行 1"))
        assertTrue(value.coverageDescription.contains("预算延后 1"))
        assertTrue(value.coverageDescription.contains("扫描不完整"))
    }
}
