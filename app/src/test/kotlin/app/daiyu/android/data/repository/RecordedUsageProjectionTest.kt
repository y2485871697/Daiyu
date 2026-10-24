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

    @Test fun settingsMetricsKeepReportedSubtotalsAndDoNotCountCacheTwice() {
        val value = projection(listOf(row(false, ModelEvent.Usage(100, 20, 40, 10))))
        assertEquals(UsageTokenMetrics(100, 20, 40, 10, 120), value.tokens)
        val snapshot = UsageStatsSnapshot(attributedUsage = value.attributed)
        assertEquals(value.tokens, snapshot.overviewTokens)
        assertEquals(UsageTokenMetrics(), snapshot.modelTokens(ModelUsageSnapshot()))
        val attributedOnly = snapshot.copy(tokenAccountingAvailable = true)
        assertEquals(UsageTokenMetrics(), attributedOnly.modelTokens(ModelUsageSnapshot()))
        assertEquals(UsageTokenMetrics(), UsageStatsSnapshot(tokenAccountingAvailable = true,
            currentInputTokens = 900).overviewTokens)
        val partial = projection(listOf(row(false, ModelEvent.Usage(null, 0, null))))
        assertNull(partial.tokens.input)
        assertEquals(0L, partial.tokens.output)
        assertNull(partial.tokens.total)
        val zero = projection(listOf(row(false, ModelEvent.Usage(0, 0, 0, 0))))
        assertEquals(UsageTokenMetrics(0, 0, 0, 0, 0), zero.tokens)
        val missing = projection(listOf(row(false, ModelEvent.Usage(7, null)), row(true, ModelEvent.Usage(null, 8))))
        assertEquals(7L, missing.tokens.input); assertEquals(8L, missing.tokens.output)
        assertNull(missing.tokens.total)
    }

    @Test fun metricOverflowRemainsUnknownWithoutCrashingTheScreen() {
        val max = UsageTokenMetrics(Long.MAX_VALUE, 1, 0, 0, Long.MAX_VALUE)
        val sum = max + UsageTokenMetrics(1, 2, 0, 0, 1)
        assertNull(sum.input)
        assertNull(sum.total)
        assertEquals(3L, sum.output)
        val value = projection(listOf(row(false, ModelEvent.Usage(Long.MAX_VALUE, 1))))
        assertNull(value.tokens.total)
    }

    @Test fun compactConversationProjectionKeepsKnownFieldsWhenOtherRequestsAreMissing() {
        val records = listOf(row(false, ModelEvent.Usage(17, 8, 5, 2)))
        val summary = aggregateAttributedUsage(records)
        val value = RecordedUsageProjection(AttributedUsageStats(summary, records,
            RecordedUsageStats(summary.main, 1, 1, 0, 0, 0, 0), ChildUsageCoverage(0, 0, 0, 0, 0, 0)))
        assertEquals(25L, value.conversationUsage.totalTokens)
        assertEquals(10L, value.conversationUsage.freshInputTokens)
        assertEquals(2L, value.conversationUsage.cacheCreationTokens)
        val partial = value.copy(attributed = value.attributed.copy(parentCoverage = value.attributed.parentCoverage.copy(mainRequestsWithoutUsage = 1)))
        assertTrue(partial.conversationUsage.hasUsage)
        assertEquals(25L, partial.conversationUsage.totalTokens)
        assertEquals(5L, partial.conversationUsage.cachedTokens)
    }
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
        assertEquals(value.tokens, snapshot.overviewTokens)
        assertEquals(UsageTokenMetrics(), snapshot.modelTokens(snapshot.modelUsage))
        val modelUsage = ModelUsageSnapshot(listOf(ModelUsageProviderUi("provider", "Provider", listOf(
            ModelUsageModelUi("model", "Model", 100, 20, 1, 1, cachedTokens = 40, cacheCreationTokens = 10),
        ))))
        val withModelAccounting = snapshot.copy(tokenAccountingAvailable = true, modelUsage = modelUsage)
        assertEquals(UsageTokenMetrics(100, 20, 40, 10, 120), withModelAccounting.modelTokens(modelUsage))
        assertEquals(value.tokens, withModelAccounting.overviewTokens)
    }

    @Test fun partialFieldsRemainQualifiedAndReportedZeroIsNotMissing() {
        val value = projection(listOf(row(false, ModelEvent.Usage(null, 0, null)), row(true, ModelEvent.Usage(7, null, 0))))
        assertEquals("7（已报告 1，未知 1）", formatReportedUsage(value.combined.input))
        assertEquals("0（已报告 1，未知 1）", formatReportedUsage(value.combined.output))
        assertEquals("0（已报告 1，未知 1）", formatReportedUsage(value.combined.cached))
        assertEquals("—（已报告 0，未知 0）", formatReportedUsage(projection(emptyList()).combined.input))
        assertEquals("—（已报告 0，未知 1）", formatReportedUsage(value.main.input))
    }

    @Test fun preparationAndPurposeMissingReportsAndBudgetsAreVisibleWithoutTokenZeros() {
        val base = projection(emptyList()).attributed
        val value = RecordedUsageProjection(base.copy(parentCoverage = base.parentCoverage.copy(
            totalPreparations = 4, inspectedPreparations = 2, preparationsWithoutUsage = 1, unreadablePreparations = 1,
            mainRequestsWithoutUsage = 2, totalAuxiliaryRequests = 3, inspectedAuxiliaryRequests = 1,
            auxiliaryRequestsWithoutUsage = 1, totalTitleRequests = 2, inspectedTitleRequests = 1, titleRequestsWithoutUsage = 1)))
        assertTrue(value.coverageDescription.contains("摘要准备：检查 2/4，缺 Usage 1，坏行 1，预算延后 2"))
        assertTrue(value.coverageDescription.contains("主请求缺报 2"))
        assertTrue(value.coverageDescription.contains("辅助视觉：检查 1/3，缺 Usage 1"))
        assertTrue(value.coverageDescription.contains("标题：检查 1/2，缺 Usage 1"))
        assertNull(value.combined.input.reportedTokens)
        assertEquals(0L, value.combined.requestSnapshotCount)
        assertFalse(value.attributed.scanComplete)
    }

    @Test fun manualOperationCoverageIsSeparateAndNeverInventsReportedTokenZeros() {
        val base = projection(emptyList()).attributed
        val value = RecordedUsageProjection(base.copy(parentCoverage = base.parentCoverage.copy(
            totalManualSummaryRequests = 4, inspectedManualSummaryRequests = 2,
            manualSummaryWithoutUsage = 1, unreadableManualSummaryRequests = 1)))
        assertTrue(value.coverageDescription.contains("手动摘要：检查 2/4，缺 Usage 1，坏行 1，预算延后 2"))
        assertNull(value.manualSummary.input.reportedTokens)
        assertEquals(0L, value.combined.requestSnapshotCount)
        assertFalse(value.attributed.scanComplete)
        val manual = AttributedRequestUsage("conversation", null, null, null, null,
            UsageOrigin.ConversationPreparation("conversation", "operation"),
            RequestCheckpoint("manual", "attempt", "owner", RequestOperation.NEXT), ModelEvent.Usage(0, null, 0),
            app.daiyu.android.domain.runtime.UsageRequestSource.MANUAL_SUMMARY)
        val reported = projection(listOf(manual))
        assertEquals(0L, reported.main.requestSnapshotCount)
        assertEquals(0L, reported.child.requestSnapshotCount)
        assertEquals(1L, reported.manualSummary.requestSnapshotCount)
        assertEquals("0（已报告 1，未知 0）", formatReportedUsage(reported.manualSummary.input))
        assertEquals(1L, reported.combined.requestSnapshotCount)
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
