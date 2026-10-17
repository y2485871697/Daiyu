package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.repository.RecordedUsageStats
import app.daiyu.android.data.repository.UsageStatsSnapshot
import app.daiyu.android.domain.usage.aggregateAttributedUsage
import app.daiyu.android.model.usage.AttributedUsageStats
import app.daiyu.android.model.usage.ChildUsageCoverage
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.stats.UsageStatsScreen
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicInteger

class UsagePresentationActivityTest : WiredActivityTestCase() {
    private fun usagePage() = node("page:${activity.getString(R.string.stats_page_title)}")
    private fun empty(incomplete: Boolean = false): UsageStatsSnapshot {
        val summary = aggregateAttributedUsage(emptyList())
        return UsageStatsSnapshot(attributedUsage = AttributedUsageStats(summary, emptyList(),
            RecordedUsageStats(summary.main, if (incomplete) 1 else 0, 0, 0, 0, 0, if (incomplete) 1 else 0),
            ChildUsageCoverage(0, 0, 0, 0, 0, 0)))
    }
    private fun show(load: suspend () -> UsageStatsSnapshot) {
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = true) {
            UsageStatsScreen(onBack = {}, load = load)
        } } }
        ui.waitForIdle()
    }
    fun testLoadingErrorAndRetryAreDistinctAndDoNotDisplayExceptionContents() {
        val pending = CompletableDeferred<UsageStatsSnapshot>()
        val calls = AtomicInteger()
        show { if (calls.incrementAndGet() == 1) pending.await() else empty() }
        node("usage-loading").assertExists()
        node("usage-error").assertDoesNotExist()
        pending.completeExceptionally(IllegalStateException("private synthetic error details"))
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("usage-error").fetchSemanticsNodes().size == 1 }
        node("usage-loading").assertDoesNotExist()
        ui.onNodeWithText("暂无已报告用量").assertDoesNotExist()
        ui.onNodeWithText("private synthetic error details").assertDoesNotExist()
        node("usage-retry").performClick()
        ui.waitUntil(5_000) { calls.get() == 2 && ui.onAllNodesWithTag("usage-error").fetchSemanticsNodes().isEmpty() }
        usagePage().performScrollToNode(hasText("暂无已报告用量"))
        ui.onNodeWithText("暂无已报告用量").assertExists()
        assertEquals(0, fixture.opens.get())
    }
    fun testIncompleteScanHasShortCoverageNoticeWithoutRawDiagnostics() {
        show { empty(incomplete = true) }
        usagePage().performScrollToNode(hasText("部分记录尚未读取，仅显示已读取的用量。"))
        ui.onNodeWithText("部分记录尚未读取，仅显示已读取的用量。").assertExists()
        ui.onNodeWithText("缺 checkpoint", substring = true).assertDoesNotExist()
        ui.onNodeWithText("预算延后", substring = true).assertDoesNotExist()
        assertEquals(0, fixture.opens.get())
    }
}
