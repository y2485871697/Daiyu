package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.data.repository.ModelUsageEvent
import app.daiyu.android.data.repository.ModelUsageModelUi
import app.daiyu.android.data.repository.ModelUsageProviderUi
import app.daiyu.android.data.repository.ModelUsageSnapshot
import app.daiyu.android.data.repository.RecordedUsageStats
import app.daiyu.android.data.repository.UsageStatsSnapshot
import app.daiyu.android.domain.ModelEvent
import app.daiyu.android.domain.runtime.RequestCheckpoint
import app.daiyu.android.domain.runtime.RequestOperation
import app.daiyu.android.domain.usage.AttributedRequestUsage
import app.daiyu.android.domain.usage.UsageOrigin
import app.daiyu.android.domain.usage.aggregateAttributedUsage
import app.daiyu.android.model.usage.AttributedUsageStats
import app.daiyu.android.model.usage.ChildUsageCoverage
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.stats.UsageStatsScreen
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicInteger
import java.time.LocalDate
import java.time.ZoneId

class UsagePresentationActivityTest : WiredActivityTestCase() {
    private fun usagePage() = node("page:${activity.getString(R.string.stats_page_title)}")
    private fun empty(incomplete: Boolean = false): UsageStatsSnapshot {
        val summary = aggregateAttributedUsage(emptyList())
        return UsageStatsSnapshot(attributedUsage = AttributedUsageStats(summary, emptyList(),
            RecordedUsageStats(summary.main, if (incomplete) 1 else 0, 0, 0, 0, 0, if (incomplete) 1 else 0),
            ChildUsageCoverage(0, 0, 0, 0, 0, 0)))
    }
    private fun reported(): UsageStatsSnapshot {
        val row = AttributedRequestUsage("conversation", "parent", "message", "parent", "message", UsageOrigin.Main,
            RequestCheckpoint("request", "attempt", "owner", RequestOperation.RESUME), ModelEvent.Usage(250, 70, 60, 40))
        val summary = aggregateAttributedUsage(listOf(row))
        return UsageStatsSnapshot(attributedUsage = AttributedUsageStats(summary, listOf(row),
            RecordedUsageStats(summary.main, 1, 1, 0, 0, 0, 0), ChildUsageCoverage(0, 0, 0, 0, 0, 0)))
    }
    private fun show(
        dark: Boolean = false,
        scale: Float = 1f,
        fontScale: Float = .9f,
        onBack: () -> Unit = {},
        load: suspend () -> UsageStatsSnapshot,
    ) {
        mainThread { activity.setContent {
            val platform = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(platform.density, fontScale)) {
                AgentAppTheme(AppearanceSettings(interfaceScale = scale,
                    themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                    applyInterfaceScale = true) {
                    UsageStatsScreen(onBack = onBack, load = load)
                }
            }
        } }
        ui.waitForIdle()
    }
    private fun tab(label: Int) = "usage-tab:${activity.getString(label)}"
    private fun selectTab(label: Int) {
        val tag = tab(label)
        usagePage().scrollToDescendant(hasTestTag(tag))
        node(tag).performClick().assertIsSelected()
    }
    private fun assertMetric(label: Int, value: String) = assertMetric(activity.getString(label), value)
    private fun assertMetric(label: String, value: String) {
        val tag = "usage-stat:$label"
        usagePage().scrollToDescendant(hasTestTag(tag))
        ui.onNode(hasText(value) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
            .assertIsDisplayed()
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
        usagePage().scrollToDescendant(hasText("暂无已报告用量"))
        ui.onNodeWithText("暂无已报告用量").assertExists()
        assertEquals(0, fixture.opens.get())
    }
    fun testIncompleteScanHasShortCoverageNoticeWithoutRawDiagnostics() {
        show { empty(incomplete = true) }
        usagePage().scrollToDescendant(hasText("部分记录尚未读取，仅显示已读取的用量。"))
        ui.onNodeWithText("部分记录尚未读取，仅显示已读取的用量。").assertExists()
        ui.onNodeWithText("缺 checkpoint", substring = true).assertDoesNotExist()
        ui.onNodeWithText("预算延后", substring = true).assertDoesNotExist()
        assertEquals(0, fixture.opens.get())
    }

    fun testOriginalOverviewKeepsAllMetricsOnFullPageAcrossThemesAndScales() {
        val stats = reported().copy(
            heatmapAvailable = true, currentDataAvailable = true,
            tokenAccountingAvailable = true, lifetimeAccountingAvailable = true,
            currentConversations = 12, lifetimeConversations = 27,
            currentMessages = 35, lifetimeMessages = 91,
            currentInputTokens = 250, lifetimeInputTokens = 600,
            currentOutputTokens = 70, lifetimeOutputTokens = 180,
            currentCachedTokens = 60, lifetimeCachedTokens = 100,
            currentCacheCreationTokens = 40, lifetimeCacheCreationTokens = 80,
            launchCount = 8,
        )
        listOf(false, true).forEach { dark -> listOf(1f, .8f).forEach { scale ->
            show(dark = dark, scale = scale) { stats }
            val page = node("usage-stats-screen").fetchSemanticsNode().boundsInRoot
            val root = ui.onRoot().fetchSemanticsNode().boundsInRoot
            assertTrue("Usage statistics must occupy the page, not a capped dialog", page.height >= root.height * .95f)
            node("usage-heatmap").assertIsDisplayed()
            node(tab(R.string.stats_tab_overview)).assertIsSelected()
            val tabNode = node(tab(R.string.stats_tab_models)).fetchSemanticsNode()
            assertTrue(tabNode.boundsInRoot.height / tabNode.layoutInfo.density.density >= 43.5f)
            ui.onNodeWithText("关闭").assertDoesNotExist()
            assertMetric(R.string.stats_page_total_conversations, "12 / 27")
            assertMetric(R.string.stats_page_total_messages, "35 / 91")
            assertMetric("输入（含缓存）", "250")
            assertMetric(R.string.stats_page_token_total, "320")
            assertMetric(R.string.stats_page_output_tokens, "70")
            assertMetric(R.string.stats_page_cached_tokens, "60")
            assertMetric(R.string.stats_page_cache_creation_tokens, "40")
            assertMetric(R.string.stats_page_launch_count, "8")
            ui.onNodeWithText("已记录请求").assertDoesNotExist()
            selectTab(R.string.stats_tab_models)
            node("usage-model-pane").assertExists()
            selectTab(R.string.stats_tab_overview)
            node("usage-heatmap").assertExists()
            node("usage-overview-grid").assertExists()
        } }
        assertEquals(0, fixture.opens.get())
    }

    fun testMissingAccountingKeepsOriginalStructureWithoutInventedZeroValues() {
        show { empty() }
        node("usage-heatmap").assertExists()
        listOf(R.string.stats_page_total_conversations, R.string.stats_page_total_messages,
            R.string.stats_page_token_total, R.string.stats_page_output_tokens,
            R.string.stats_page_cached_tokens, R.string.stats_page_cache_creation_tokens,
            R.string.stats_page_launch_count).forEach { assertMetric(it, "—") }
        assertMetric("输入（含缓存）", "—")
        selectTab(R.string.stats_tab_models)
        node("usage-model-filter").assertExists()
        ui.onAllNodesWithText("0%").assertCountEquals(0)
        ui.onAllNodesWithText("0.0%").assertCountEquals(0)
        assertEquals(0, fixture.opens.get())
    }

    fun testOriginalModelFilterAndExpansionRemainUsableWithLargerText() {
        val today = LocalDate.now()
        fun at(date: LocalDate) = date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val usage = ModelUsageSnapshot(listOf(ModelUsageProviderUi("visual-provider", "Visual provider", listOf(
            ModelUsageModelUi("visual-model", "Visual model", 300, 100, 2, 2,
                cachedTokens = 60, cacheCreationTokens = 30,
                events = listOf(
                    ModelUsageEvent(at(today), 100, 40, 20, 10, "recent"),
                    ModelUsageEvent(at(today.minusDays(10)), 200, 60, 40, 20, "older"),
                )),
        ))))
        show(dark = true, scale = .8f, fontScale = 1.3f) {
            empty().copy(tokenAccountingAvailable = true, modelUsage = usage)
        }
        selectTab(R.string.stats_tab_models)
        usagePage().scrollToDescendant(hasText("Visual model"))
        ui.onNode(hasText("Visual model") and hasClickAction()).performClick()
        val activeDays = activity.getString(R.string.stats_model_active_days)
        usagePage().scrollToDescendant(hasText(activeDays))
        ui.onNodeWithText(activeDays).assertIsDisplayed()
        val filterTitle = activity.getString(R.string.stats_model_filter_title)
        usagePage().scrollToDescendant(hasText(filterTitle))
        ui.onNode(hasText(filterTitle) and hasClickAction()).performClick()
        val preset = activity.getString(R.string.stats_model_filter_preset_today)
        usagePage().scrollToDescendant(hasText(preset) and hasClickAction())
        ui.onNode(hasText(preset) and hasClickAction()).performClick()
        usagePage().scrollToDescendant(hasText("140"))
        ui.onAllNodesWithText("140").onFirst().assertIsDisplayed()
        ui.onAllNodesWithText("400").assertCountEquals(0)
        val clear = activity.getString(R.string.stats_model_filter_clear)
        usagePage().scrollToDescendant(hasText(clear))
        ui.onNodeWithText(clear).performClick()
        usagePage().scrollToDescendant(hasText("400"))
        ui.onAllNodesWithText("400").onFirst().assertIsDisplayed()
        assertEquals(0, fixture.opens.get())
    }

    fun testReportedUsageLivesInMainCardsAndUnknownModelBucketRespectsTimeFilter() {
        show { reported() }
        assertMetric(R.string.stats_page_token_total, "320")
        assertMetric("输入（含缓存）", "250")
        ui.onNodeWithText("已记录请求").assertDoesNotExist()
        ui.onNodeWithText("已报告", substring = true).assertDoesNotExist()
        selectTab(R.string.stats_tab_models)
        usagePage().scrollToDescendant(hasTestTag("usage-model-unattributed"))
        node("usage-model-unattributed").assertIsDisplayed()
        val filter = activity.getString(R.string.stats_model_filter_title)
        usagePage().scrollToDescendant(hasText(filter))
        ui.onNode(hasText(filter) and hasClickAction()).performClick()
        val today = activity.getString(R.string.stats_model_filter_preset_today)
        usagePage().scrollToDescendant(hasText(today) and hasClickAction())
        ui.onNode(hasText(today) and hasClickAction()).performClick()
        node("usage-model-unattributed").assertDoesNotExist()
        usagePage().scrollToDescendant(hasText("历史用量缺少时间信息，已排除在时间筛选结果之外。"))
        val clear = activity.getString(R.string.stats_model_filter_clear)
        usagePage().scrollToDescendant(hasText(clear))
        ui.onNodeWithText(clear).performClick()
        usagePage().scrollToDescendant(hasTestTag("usage-model-unattributed"))
        node("usage-model-unattributed").assertIsDisplayed()
        assertEquals(0, fixture.opens.get())
    }

    fun testTopBarAndSystemBackEachReturnOneLevel() {
        val returned = AtomicInteger()
        show(onBack = { returned.incrementAndGet() }) { empty() }
        ui.onNodeWithContentDescription(activity.getString(R.string.action_back)).performClick()
        assertEquals(1, returned.get())
        mainThread { activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(2, returned.get())
        assertEquals(0, fixture.opens.get())
    }
}
