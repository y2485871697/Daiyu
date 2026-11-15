package app.daiyu.android.model.usage

import app.daiyu.android.data.repository.RecordedUsageStats
import app.daiyu.android.domain.usage.AttributedRequestUsage
import app.daiyu.android.domain.usage.AttributedUsageSummary

/** Coverage concerns retained task rows, never an inferred number of network requests. */
internal data class ChildUsageCoverage(
    val totalTasks: Int,
    val inspectedTasks: Int,
    val tasksWithoutReportedUsage: Int,
    val notStartedTasks: Int,
    val unreadableTasks: Int,
    val deferredTasks: Int,
) {
    val scanComplete: Boolean get() = totalTasks == inspectedTasks && unreadableTasks == 0 && deferredTasks == 0
}

internal data class ChildRequestMetadata(
    val parentRunId: String,
    val runId: String,
    val request: app.daiyu.android.domain.runtime.RequestCheckpoint,
    val historicalModel: app.daiyu.android.domain.delegation.ChildRequestModel?,
    val requestedAtMs: Long?,
    val requestSequence: Int?,
    val assignedProfileId: String?,
)

internal fun AttributedRequestUsage.toChildRequestMetadata(): ChildRequestMetadata {
    require(origin is app.daiyu.android.domain.usage.UsageOrigin.Child)
    return ChildRequestMetadata(requireNotNull(parentRunId), requireNotNull(runId), request,
        historicalModel, requestedAtMs, requestSequence, assignedProfileId)
}

internal data class ChildUsageReadResult(
    val requests: List<AttributedRequestUsage>,
    val coverage: ChildUsageCoverage,
    val unreportedRequests: List<ChildRequestMetadata>,
)

internal data class AttributedUsageStats(
    val summary: AttributedUsageSummary,
    val requests: List<AttributedRequestUsage>,
    val parentCoverage: RecordedUsageStats,
    val childCoverage: ChildUsageCoverage,
    val unreportedChildRequests: List<ChildRequestMetadata> = emptyList(),
) {
    val scanComplete: Boolean get() = parentCoverage.scanComplete && childCoverage.scanComplete
}
