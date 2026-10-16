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

internal data class AttributedUsageStats(
    val summary: AttributedUsageSummary,
    val requests: List<AttributedRequestUsage>,
    val parentCoverage: RecordedUsageStats,
    val childCoverage: ChildUsageCoverage,
) {
    val scanComplete: Boolean get() = parentCoverage.scanComplete && childCoverage.scanComplete
}
