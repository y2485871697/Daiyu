package app.daiyu.android.context

import app.daiyu.android.data.repository.RecordedUsageStats
import app.daiyu.android.domain.ModelEvent
import app.daiyu.android.domain.runtime.RequestCheckpoint
import app.daiyu.android.domain.runtime.RequestOperation
import app.daiyu.android.domain.usage.*
import app.daiyu.android.model.usage.AttributedUsageStats
import app.daiyu.android.model.usage.ChildUsageCoverage
import org.junit.Assert.*
import org.junit.Test

class RecordedContextUsageAdapterTest {
    private fun row(run: String, child: Boolean, value: ModelEvent.Usage): AttributedRequestUsage {
        val parent = if (child) "parent" else run
        return AttributedRequestUsage("conversation", parent, "$parent-message", run, "$run-message",
            if (child) UsageOrigin.Child(run, "profile", "group") else UsageOrigin.Main,
            RequestCheckpoint("request", "attempt-$run", "owner-$run", RequestOperation.NEXT), value)
    }
    private fun stats(rows: List<AttributedRequestUsage>, missing: Int = 0, corrupt: Int = 0): AttributedUsageStats {
        val summary = aggregateAttributedUsage(rows)
        return AttributedUsageStats(summary, rows, RecordedUsageStats(summary.main, 3, 3, missing, 0, corrupt, 0),
            ChildUsageCoverage(1, 1, 0, 0, 0, 0))
    }

    @Test fun unknownAndZeroSurviveJsonAndIndependentPartialFieldsNeverProduceTotal() {
        val value = ContextUsageSnapshot(stats(listOf(row("parent", false, ModelEvent.Usage(7, null, null)),
            row("child", true, ModelEvent.Usage(null, 0, 0)))), null)
        val json = value.toJson()
        assertEquals(2L, json.getLong("requests")); assertEquals(0L, json.getLong("reported_requests"))
        assertEquals(7L, json.getLong("input_tokens")); assertEquals(0L, json.getLong("output_tokens"))
        assertEquals(0L, json.getLong("cached_tokens")); assertTrue(json.isNull("total_tokens"))
        assertTrue(json.isNull("reasoning_tokens")); assertTrue(json.isNull("historical_model"))
        assertTrue(json.getBoolean("incomplete"))
        assertEquals(1L, json.getJSONObject("field_coverage").getJSONObject("input").getLong("unknown_requests"))
    }

    @Test fun runFiltersIncludeDescendantsButNeverExposeSiblingCoverageOrUsage() {
        val data = stats(listOf(row("parent", false, ModelEvent.Usage(10, 20, 5)),
            row("child", true, ModelEvent.Usage(1, 2, 0)), row("sibling", false, ModelEvent.Usage(900, 800, 700))))
        val parent = ContextUsageSnapshot(data, "parent").toJson()
        assertEquals(11L, parent.getLong("input_tokens")); assertEquals(33L, parent.getLong("total_tokens"))
        assertEquals(2L, parent.getLong("requests")); assertTrue(parent.getBoolean("incomplete"))
        val coverage = parent.getJSONObject("coverage")
        assertFalse(coverage.getBoolean("counts_available")); assertTrue(coverage.isNull("scan_complete"))
        assertFalse(coverage.has("parent")); assertFalse(coverage.has("child"))
        val child = ContextUsageSnapshot(data, "child")
        assertEquals(1L, child.inputTokens); assertEquals(3L, child.totalTokens)
    }

    @Test fun manualOperationIsConversationOwnedAndMissingReportCoverageDoesNotLeakIntoRunGrant() {
        val manual = AttributedRequestUsage("conversation", null, null, null, null,
            UsageOrigin.ConversationPreparation("conversation", "operation"),
            RequestCheckpoint("manual", "attempt", "owner", RequestOperation.NEXT), ModelEvent.Usage(3, 0, 0),
            app.daiyu.android.domain.runtime.UsageRequestSource.MANUAL_SUMMARY)
        val base = stats(listOf(row("parent", false, ModelEvent.Usage(10, 20, 5)), manual))
        val data = base.copy(parentCoverage = base.parentCoverage.copy(totalManualSummaryRequests = 3,
            inspectedManualSummaryRequests = 2, manualSummaryWithoutUsage = 1))
        val conversation = ContextUsageSnapshot(data, null).toJson()
        assertEquals(13L, conversation.getLong("input_tokens"))
        assertEquals(1, conversation.getJSONObject("request_sources").getInt("manual_summary"))
        val coverage = conversation.getJSONObject("coverage").getJSONObject("manual_summary")
        assertEquals(1, coverage.getInt("without_usage")); assertEquals(1, coverage.getInt("deferred"))
        assertTrue(conversation.getBoolean("incomplete"))
        val run = ContextUsageSnapshot(data, "parent").toJson()
        assertEquals(10L, run.getLong("input_tokens"))
        assertEquals(0, run.getJSONObject("request_sources").getInt("manual_summary"))
        assertFalse(run.getJSONObject("coverage").has("manual_summary"))
    }

    @Test fun emptyAndCorruptCoverageDoNotBecomeZeroTokenRequests() {
        val empty = ContextUsageSnapshot(stats(emptyList(), missing = 2, corrupt = 1), null).toJson()
        assertEquals(0L, empty.getLong("requests")); assertTrue(empty.isNull("input_tokens"))
        assertTrue(empty.isNull("total_tokens")); assertTrue(empty.getBoolean("incomplete"))
        val parent = empty.getJSONObject("coverage").getJSONObject("parent")
        assertEquals(2, parent.getInt("without_checkpoint")); assertEquals(1, parent.getInt("unreadable"))
        assertFalse(empty.getJSONObject("coverage").getBoolean("scan_complete"))
    }
}
