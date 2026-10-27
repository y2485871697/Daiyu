package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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
import app.daiyu.android.ui.components.UsagePalette
import app.daiyu.android.ui.screens.stats.UsageStatsScreen
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicInteger
import java.time.LocalDate
import java.time.ZoneId
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters

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
        val contentKey = Any()
        mainThread { activity.setContent {
            key(contentKey) {
                val platform = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(platform.density, fontScale)) {
                    AgentAppTheme(AppearanceSettings(interfaceScale = scale,
                        themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                        applyInterfaceScale = true) {
                        UsageStatsScreen(onBack = onBack, load = load)
                    }
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
        ui.onNodeWithText("private synthetic error details").assertDoesNotExist()
        node("usage-retry").performClick()
        ui.waitUntil(5_000) { calls.get() == 2 && ui.onAllNodesWithTag("usage-error").fetchSemanticsNodes().isEmpty() }
        assertEquals(0, fixture.opens.get())
    }
    fun testIncompleteScanHasShortCoverageNoticeWithoutRawDiagnostics() {
        show { empty(incomplete = true) }
        ui.onNodeWithText("部分记录尚未读取，仅显示已读取的用量。").assertDoesNotExist()
        ui.onNodeWithText("输入、输出及缓存为已报告子集", substring = true).assertDoesNotExist()
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
            awaitUniqueTag("usage-heatmap")
            awaitVisualIdle()
            val page = node("usage-stats-screen").fetchSemanticsNode().boundsInRoot
            val root = ui.onRoot().fetchSemanticsNode().boundsInRoot
            assertTrue("Usage statistics must occupy the page, not a capped dialog", page.height >= root.height * .95f)
            usagePage().scrollToDescendant(hasTestTag("usage-heatmap"))
            node("usage-heatmap").assertIsDisplayed()
            node(tab(R.string.stats_tab_overview)).assertIsSelected()
            usagePage().scrollToDescendant(hasTestTag(tab(R.string.stats_tab_models)))
            awaitVisualIdle()
            node(tab(R.string.stats_tab_models)).assertIsDisplayed()
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

    fun testMissingAccountingDisplaysZeroWithoutChangingAvailability() {
        show { empty() }
        node("usage-heatmap").assertExists()
        listOf(R.string.stats_page_total_conversations, R.string.stats_page_total_messages,
            R.string.stats_page_token_total, R.string.stats_page_output_tokens,
            R.string.stats_page_cached_tokens, R.string.stats_page_cache_creation_tokens,
            R.string.stats_page_launch_count).forEach { assertMetric(it, "0") }
        assertMetric("输入（含缓存）", "0")
        assertMetric(R.string.stats_page_fresh_input_tokens, "0")
        selectTab(R.string.stats_tab_models)
        ui.onAllNodes(hasText("0") and hasAnyAncestor(hasTestTag("usage-model-total")),
            useUnmergedTree = true).assertCountEquals(7)
        ui.onAllNodesWithText("—").assertCountEquals(0)
        node("usage-model-filter").assertExists()
        ui.onAllNodesWithText("0%").assertCountEquals(0)
        ui.onAllNodesWithText("0.0%").assertCountEquals(0)
        assertEquals(0, fixture.opens.get())
    }

    fun testIncompleteModelMetricsShowZeroAndKeepReportedValues() {
        val model = ModelUsageModelUi("incomplete", "Incomplete model", 200, 70, 1, 1,
            cachedTokens = 40, cacheCreationTokens = 10,
            cachedComplete = false, creationComplete = false)
        val stats = empty().copy(tokenAccountingAvailable = true,
            modelUsage = ModelUsageSnapshot(listOf(ModelUsageProviderUi("provider", "Provider", listOf(model)))))
        show { stats }
        selectTab(R.string.stats_tab_models)
        usagePage().scrollToDescendant(hasText("Incomplete model"))
        ui.onNode(hasText("Incomplete model") and hasClickAction()).performClick()
        awaitVisualIdle()
        for (label in listOf(R.string.stats_model_daily_avg, R.string.stats_model_conversation_avg)) {
            val metric = hasText("0") and hasAnyAncestor(hasTestTag("usage-model-metric:${activity.getString(label)}"))
            usagePage().scrollToDescendant(metric)
            ui.onNode(metric, useUnmergedTree = true).assertIsDisplayed()
        }
        ui.onAllNodes(hasText("0") and hasAnyAncestor(hasTestTag("usage-model-metric:${activity.getString(R.string.stats_page_cache_creation_tokens)}")),
            useUnmergedTree = true).assertCountEquals(2)
        ui.onAllNodes(hasText("70") and hasAnyAncestor(hasTestTag("usage-model-metric:${activity.getString(R.string.stats_page_output_tokens)}")),
            useUnmergedTree = true).assertCountEquals(2)
        ui.onAllNodesWithText("—").assertCountEquals(0)
        assertFalse(model.cachedComplete)
        assertFalse(model.creationComplete)
        assertEquals(40L, model.cachedTokens)
        assertEquals(10L, model.cacheCreationTokens)
        assertEquals(0, fixture.opens.get())
    }

    fun testZeroInputModelCacheRateDisplaysZero() {
        val model = ModelUsageModelUi("zero-input", "Zero input model", 0, 0, 0, 0)
        show { empty().copy(tokenAccountingAvailable = true,
            modelUsage = ModelUsageSnapshot(listOf(ModelUsageProviderUi("provider", "Provider", listOf(model))))) }
        selectTab(R.string.stats_tab_models)
        usagePage().scrollToDescendant(hasText("Zero input model"))
        ui.onNode(hasText("Zero input model") and hasClickAction()).performClick()
        awaitVisualIdle()
        ui.onAllNodes(hasText("0") and hasAnyAncestor(hasTestTag("usage-model-metric:${activity.getString(R.string.stats_model_cache_hit_rate)}")),
            useUnmergedTree = true).assertCountEquals(2)
        ui.onAllNodesWithText("—").assertCountEquals(0)
        assertEquals(0, fixture.opens.get())
    }

    private fun heatmapCell(date: LocalDate) =
        ui.onNodeWithTag("usage-heatmap-cell:$date", useUnmergedTree = true)

    fun testUnavailableHeatmapKeepsSquareGridInsteadOfRowsOfDashesAcrossThemesAndScales() {
        val today = LocalDate.now()
        val sunday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
        for (dark in listOf(false, true)) for (scale in listOf(1f, .8f)) {
            show(dark = dark, scale = scale) { empty() }
            awaitUniqueTag("usage-heatmap-grid")
            awaitVisualIdle()
            node("usage-heatmap-grid").assert(SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription, activity.getString(R.string.stats_page_heatmap_unavailable)))
            ui.onAllNodes(SemanticsMatcher("dated heatmap cells") {
                it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("usage-heatmap-cell:") == true
            }, useUnmergedTree = true).assertCountEquals(53 * 7)
            ui.onAllNodes(hasText("—") and hasAnyAncestor(hasTestTag("usage-heatmap")),
                useUnmergedTree = true).assertCountEquals(0)
            var previousBottom: Float? = null
            for (day in 0..6) {
                val cell = heatmapCell(sunday.plusDays(day.toLong())).assertIsDisplayed().fetchSemanticsNode()
                val bounds = cell.boundsInRoot
                val density = cell.layoutInfo.density.density
                assertEquals(11f, bounds.width / density, .6f)
                assertEquals(bounds.width, bounds.height, 1f)
                previousBottom?.let { assertEquals(2f, (bounds.top - it) / density, .6f) }
                previousBottom = bounds.bottom
            }
            val pixels = heatmapCell(today).captureToImage().toPixelMap()
            val expected = UsagePalette(dark).bubble
            // A real filled cell, not a text dash on a transparent background.
            for (y in listOf(pixels.height / 3, pixels.height / 2, pixels.height * 2 / 3)) {
                val actual = pixels[pixels.width / 2, y]
                assertEquals(expected.red, actual.red, .015f)
                assertEquals(expected.green, actual.green, .015f)
                assertEquals(expected.blue, actual.blue, .015f)
            }
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testAvailableHeatmapPreservesZeroAndFourHeatLevelsWithoutUnknownState() {
        val today = LocalDate.now()
        val counts = (0..6).associate { today.minusDays((6 - it).toLong()) to it + 1 }
        for (dark in listOf(false, true)) {
            show(dark = dark) { empty().copy(heatmapAvailable = true, conversationsPerDay = counts) }
            awaitUniqueTag("usage-heatmap-grid")
            awaitVisualIdle()
            node("usage-heatmap-grid").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
            val dates = listOf(today.minusDays(8), today.minusDays(6), today.minusDays(4),
                today.minusDays(2), today)
            val colors = dates.map { date ->
                val pixels = heatmapCell(date).assertIsDisplayed().captureToImage().toPixelMap()
                pixels[pixels.width / 2, pixels.height / 2].toArgb()
            }
            assertEquals("Zero and the four heat levels must stay distinct", 5, colors.toSet().size)
            assertEquals(UsagePalette(dark).bubble.toArgb(), colors.first())
            assertEquals(UsagePalette(dark).accent.toArgb(), colors.last())
            val grid = node("usage-heatmap-grid")
            val gridBounds = grid.fetchSemanticsNode().boundsInRoot
            val pixels = grid.captureToImage().toPixelMap()
            for ((date, alpha) in listOf(today.minusDays(6) to .25f,
                today.minusDays(4) to .5f, today.minusDays(2) to .75f)) {
                val cell = heatmapCell(date).fetchSemanticsNode()
                val bounds = cell.boundsInRoot
                val x = (bounds.center.x - gridBounds.left).toInt()
                val y = (bounds.center.y - gridBounds.top).toInt()
                // The 2dp gap above this row exposes the actual card backing.
                val gapY = (bounds.top - gridBounds.top - cell.layoutInfo.density.density).toInt()
                val expected = UsagePalette(dark).accent.copy(alpha = alpha).compositeOver(pixels[x, gapY])
                val actual = pixels[x, y]
                assertEquals(expected.red, actual.red, .015f)
                assertEquals(expected.green, actual.green, .015f)
                assertEquals(expected.blue, actual.blue, .015f)
            }
        }
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
        awaitVisualIdle()
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

    fun testModelUsageDoesNotMixUnattributedHistoricalUsageOrShowExplanatoryCopy() {
        show { reported() }
        assertMetric(R.string.stats_page_token_total, "320")
        assertMetric("输入（含缓存）", "250")
        ui.onNodeWithText("已记录请求").assertDoesNotExist()
        selectTab(R.string.stats_tab_models)
        node("usage-model-unattributed").assertDoesNotExist()
        ui.onNodeWithText("历史记录未包含模型和时间信息。").assertDoesNotExist()
        ui.onNodeWithText("历史用量缺少时间信息，已排除在时间筛选结果之外。").assertDoesNotExist()
        usagePage().scrollToDescendant(hasTestTag("usage-model-total"))
        node("usage-model-total").assertIsDisplayed()
        ui.onAllNodesWithText("320").assertCountEquals(0)
        val filter = activity.getString(R.string.stats_model_filter_title)
        usagePage().scrollToDescendant(hasText(filter))
        ui.onNode(hasText(filter) and hasClickAction()).performClick()
        val today = activity.getString(R.string.stats_model_filter_preset_today)
        usagePage().scrollToDescendant(hasText(today) and hasClickAction())
        ui.onNode(hasText(today) and hasClickAction()).performClick()
        node("usage-model-unattributed").assertDoesNotExist()
        val clear = activity.getString(R.string.stats_model_filter_clear)
        usagePage().scrollToDescendant(hasText(clear))
        ui.onNodeWithText(clear).performClick()
        node("usage-model-unattributed").assertDoesNotExist()
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
