package app.daiyu.android.context

import android.database.sqlite.SQLiteDatabase
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.usage.RecordedUsageSummary
import app.daiyu.android.domain.usage.ReportedTokenSubtotal
import app.daiyu.android.domain.usage.aggregateRecordedUsage
import app.daiyu.android.model.usage.AttributedUsageStats
import app.daiyu.android.storage.RecordedUsageReader
import org.json.JSONObject

/** Public query result, not a receipt/ledger. Nullable counters are reported subtotals.
 * Requests counts persisted Usage snapshots only, never inferred network attempts. */
class ContextUsageSnapshot internal constructor(
    internal val attributed: AttributedUsageStats,
    private val runId: String?,
) {
    override fun equals(other: Any?): Boolean = other is ContextUsageSnapshot &&
        attributed == other.attributed && runId == other.runId
    override fun hashCode(): Int = 31 * attributed.hashCode() + (runId?.hashCode() ?: 0)

    private val selected = attributed.requests.filter { runId == null || it.parentRunId == runId || it.runId == runId }
    internal val summary: RecordedUsageSummary = aggregateRecordedUsage(selected.asSequence().map { it.snapshot })
    val requests: Long get() = summary.requestSnapshotCount
    val reportedRequests: Long get() = selected.count { it.snapshot.inputTokens != null && it.snapshot.outputTokens != null }.toLong()
    val inputTokens: Long? get() = summary.input.reportedTokens
    val outputTokens: Long? get() = summary.output.reportedTokens
    val cachedTokens: Long? get() = summary.cached.reportedTokens
    val reasoningTokens: Long? get() = null // Not persisted by the authoritative usage contract.
    // Independent partial input/output sets must not be combined into an apparent total.
    val totalTokens: Long? get() = if (requests > 0 && reportedRequests == requests)
        Math.addExact(requireNotNull(inputTokens), requireNotNull(outputTokens)) else null
    val incomplete: Boolean get() = runId != null || !attributed.scanComplete || requests == 0L ||
        reportedRequests != requests || summary.cached.unknownRequestCount > 0 ||
        attributed.parentCoverage.messagesWithoutCheckpoint > 0 || attributed.parentCoverage.messagesWithoutUsage > 0 ||
        attributed.childCoverage.tasksWithoutReportedUsage > 0 || attributed.parentCoverage.preparationsWithoutUsage > 0 ||
        attributed.parentCoverage.mainRequestsWithoutUsage > 0 || attributed.parentCoverage.auxiliaryRequestsWithoutUsage > 0 ||
        attributed.parentCoverage.titleRequestsWithoutUsage > 0 || attributed.parentCoverage.manualSummaryWithoutUsage > 0

    internal fun toJson(): JSONObject = JSONObject().put("requests", requests)
        .put("request_count_kind", "persisted_usage_snapshots")
        .put("reported_requests", reportedRequests)
        .put("request_sources", JSONObject()
            .put("main", selected.count { it.origin == app.daiyu.android.domain.usage.UsageOrigin.Main && it.source == app.daiyu.android.domain.runtime.UsageRequestSource.MAIN })
            .put("child", selected.count { it.origin is app.daiyu.android.domain.usage.UsageOrigin.Child && it.source == app.daiyu.android.domain.runtime.UsageRequestSource.MAIN })
            .put("summary", selected.count { it.source == app.daiyu.android.domain.runtime.UsageRequestSource.SUMMARY })
            .put("auxiliary_vision", selected.count { it.source == app.daiyu.android.domain.runtime.UsageRequestSource.AUXILIARY_VISION })
            .put("title", selected.count { it.source == app.daiyu.android.domain.runtime.UsageRequestSource.TITLE })
            .put("manual_summary", selected.count { it.source == app.daiyu.android.domain.runtime.UsageRequestSource.MANUAL_SUMMARY }))
        .put("input_tokens", inputTokens ?: JSONObject.NULL)
        .put("output_tokens", outputTokens ?: JSONObject.NULL)
        .put("cached_tokens", cachedTokens ?: JSONObject.NULL)
        .put("reasoning_tokens", JSONObject.NULL)
        .put("total_tokens", totalTokens ?: JSONObject.NULL)
        .put("field_coverage", JSONObject().put("input", fieldCoverage(summary.input))
            .put("output", fieldCoverage(summary.output)).put("cached", fieldCoverage(summary.cached)))
        .put("coverage", coverageJson())
        .put("historical_model", JSONObject.NULL)
        .put("incomplete", incomplete)

    private fun fieldCoverage(value: ReportedTokenSubtotal) = JSONObject()
        .put("reported_requests", value.reportedRequestCount).put("unknown_requests", value.unknownRequestCount)

    private fun coverageJson(): JSONObject {
        // Reader budgets are conversation-wide. Do not expose another run's coverage metadata to
        // a run-only grant, or pretend a post-filtered bounded scan exhausts the selected run.
        if (runId != null) return JSONObject().put("scope", "bounded_conversation_scan_filtered_to_run")
            .put("scan_complete", JSONObject.NULL).put("counts_available", false)
            .put("note", "Run-filtered reported subtotal only; conversation-wide budget and coverage counts are not run coverage.")
        val p = attributed.parentCoverage
        val c = attributed.childCoverage
        return JSONObject().put("scope", "retained_conversation").put("scan_complete", attributed.scanComplete)
            .put("counts_available", true)
            .put("parent", JSONObject().put("total_messages", p.totalAssistantMessages)
                .put("inspected_messages", p.inspectedAssistantMessages)
                .put("without_checkpoint", p.messagesWithoutCheckpoint).put("without_usage", p.messagesWithoutUsage)
                .put("unreadable", p.unreadableCheckpoints).put("deferred", p.deferredCheckpoints)
                .put("requests_without_usage", p.mainRequestsWithoutUsage))
            .put("preparation", JSONObject().put("total", p.totalPreparations)
                .put("inspected", p.inspectedPreparations).put("without_usage", p.preparationsWithoutUsage)
                .put("unreadable", p.unreadablePreparations).put("deferred", p.totalPreparations - p.inspectedPreparations))
            .put("auxiliary_vision", JSONObject().put("total", p.totalAuxiliaryRequests)
                .put("inspected", p.inspectedAuxiliaryRequests).put("without_usage", p.auxiliaryRequestsWithoutUsage)
                .put("unreadable", p.unreadableAuxiliaryRequests).put("deferred", p.totalAuxiliaryRequests - p.inspectedAuxiliaryRequests))
            .put("title", JSONObject().put("total", p.totalTitleRequests)
                .put("inspected", p.inspectedTitleRequests).put("without_usage", p.titleRequestsWithoutUsage)
                .put("unreadable", p.unreadableTitleRequests).put("deferred", p.totalTitleRequests - p.inspectedTitleRequests))
            .put("manual_summary", JSONObject().put("total", p.totalManualSummaryRequests)
                .put("inspected", p.inspectedManualSummaryRequests).put("without_usage", p.manualSummaryWithoutUsage)
                .put("unreadable", p.unreadableManualSummaryRequests)
                .put("deferred", p.totalManualSummaryRequests - p.inspectedManualSummaryRequests))
            .put("child", JSONObject().put("total_tasks", c.totalTasks).put("inspected_tasks", c.inspectedTasks)
                .put("without_usage", c.tasksWithoutReportedUsage).put("not_started", c.notStartedTasks)
                .put("unreadable", c.unreadableTasks).put("deferred", c.deferredTasks))
    }
}

/** Explicit read-only bridge to the SAME committed checkpoint/child reader as the stats UI.
 * Only explicitly typed context_usage admissions join the authority; legacy receipts remain excluded. */
internal object RecordedContextUsageAdapter {
    fun read(db: SQLiteDatabase, branchId: String, runId: String?): ContextUsageSnapshot {
        check(db.inTransaction()) { "context_usage_transaction_required" }
        require(isOwner(db, branchId, runId)) { "context_usage_owner_unavailable" }
        return ContextUsageSnapshot(RecordedUsageReader.readAttributed(db, ConversationId(branchId)), runId)
    }

    fun isOwner(db: SQLiteDatabase, branchId: String, runId: String?): Boolean {
        check(db.inTransaction()) { "context_usage_transaction_required" }
        val exists = db.rawQuery("SELECT id FROM conversations WHERE id=?", arrayOf(branchId)).use { it.moveToFirst() }
        if (!exists || runId == null) return exists
        // Legacy context_usage receipts cannot grant authority to otherwise unknown runs.
        return db.rawQuery("SELECT conversation_id FROM messages WHERE run_id=? UNION " +
            "SELECT conversation_id FROM runtime_preparations WHERE run_id=? UNION " +
            "SELECT t.conversation_id FROM child_tasks t JOIN messages m ON m.id=t.parent_message_id" +
            " AND m.conversation_id=t.conversation_id AND m.run_id=t.parent_run_id AND m.role='assistant' WHERE t.task_id=?",
            arrayOf(runId, runId, runId)).use { c -> c.moveToFirst() && c.getString(0) == branchId && !c.moveToNext() }
    }
}
