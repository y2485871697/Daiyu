package app.daiyu.android.data.repository

import app.daiyu.android.domain.usage.RecordedUsageSummary
import app.daiyu.android.domain.usage.ReportedTokenSubtotal
import app.daiyu.android.model.usage.AttributedUsageStats

/** Read-only presentation of retained provider observations. Never promotes scan completeness
 * to accounting completeness, or resolves a historical model from today's configuration. */
internal data class RecordedUsageProjection(val attributed: AttributedUsageStats) {
    val main: RecordedUsageSummary get() = attributed.summary.main
    val child: RecordedUsageSummary get() = attributed.summary.child
    val combined: RecordedUsageSummary get() = attributed.summary.combined
    // Neither checkpoint usage nor child receipts persist provider/model/time attribution.
    val historicalModelAvailable: Boolean get() = false
    val historicalTimeAvailable: Boolean get() = false
    val coverageDescription: String get() {
        val p = attributed.parentCoverage
        val c = attributed.childCoverage
        return "主回复：检查 ${p.inspectedAssistantMessages}/${p.totalAssistantMessages}，" +
            "缺 checkpoint ${p.messagesWithoutCheckpoint}，无 Usage ${p.messagesWithoutUsage}，" +
            "坏行 ${p.unreadableCheckpoints}，预算延后 ${p.deferredCheckpoints}；" +
            "子任务：检查 ${c.inspectedTasks}/${c.totalTasks}，无 Usage ${c.tasksWithoutReportedUsage}，" +
            "未启动 ${c.notStartedTasks}，坏行 ${c.unreadableTasks}，预算延后 ${c.deferredTasks}。" +
            if (attributed.scanComplete) "扫描完成不代表用量完整。" else "扫描不完整；仅显示已读取的报告小计。"
    }
}

/** A reported zero is visible; no reports is unknown, never a synthetic zero. Even when a
 * subtotal is non-null it may cover only some requests; display that qualification inline. */
internal fun formatReportedUsage(value: ReportedTokenSubtotal): String {
    val tokens = value.reportedTokens?.let(::formatTokenCount) ?: "—"
    return "$tokens（已报告 ${value.reportedRequestCount}，未知 ${value.unknownRequestCount}）"
}
