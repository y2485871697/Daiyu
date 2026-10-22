package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme

class ToolCardReadabilityTest : WiredActivityTestCase() {
    private var displayRestore: String? = null
    private var originalEffectiveSize: String? = null

    private fun displaySize(command: String): String =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }

    private fun effectiveSize(sizes: String): String =
        checkNotNull(Regex("(?:Override|Physical) size: (\\d+x\\d+)").findAll(sizes).lastOrNull()) { sizes }.groupValues[1]

    override fun setUp() {
        val sizes = displaySize("wm size")
        val original = Regex("Override size: (\\d+x\\d+)").find(sizes)?.groupValues?.get(1) ?: "reset"
        check(Regex("Physical size: \\d+x\\d+").containsMatchIn(sizes)) { sizes }
        originalEffectiveSize = effectiveSize(sizes)
        displayRestore = original
        check(displaySize("wm size 1216x2432").isBlank())
        val applied = displaySize("wm size")
        check(effectiveSize(applied) == "1216x2432") { "Requested 1216x2432 fixture; actual: $applied" }
        instrumentation.waitForIdleSync()
        super.setUp()
        ui.waitUntil(5_000) {
            var width = 0
            instrumentation.runOnMainSync { width = activity.window.decorView.width }
            width == 1216
        }
    }

    override fun cleanupAfterLegacy() {
        try { super.cleanupAfterLegacy() }
        finally {
            displayRestore?.let { original ->
                check(displaySize("wm size $original").isBlank())
                val restored = displaySize("wm size")
                check(effectiveSize(restored) == originalEffectiveSize) { "Display restore failed: $restored" }
                displayRestore = null
                originalEffectiveSize = null
                instrumentation.waitForIdleSync()
            }
        }
    }

    private val phoneDensity = 525f / 160f
    private val prose = "已收到设备快照，详情见技术详情"
    private val title = "查询设备状态和当前系统内存使用情况"
    private val output = "first line\nsecond line\nthird line\nfourth line"
    private var actionCount = 0

    private fun show(dark: Boolean, scale: Float, fontScale: Float, narrow: Boolean = false) {
        val step = WorkStepUi("readability", title, "", WorkStepState.UNKNOWN,
            owner = WorkStepOwnerUi("fixture", "run", "message"), category = "device_read",
            activity = ToolActivityUi.INTERRUPTED,
            categoryDetails = ToolCategoryDetailsUi("设备", notice = "", rich = ToolRichDetailsUi(
                prose = ToolDisclosureUi("", prose),
                technical = listOf(ToolDisclosureUi("调用参数", "{}", true)))))
        mainThread { activity.setContent {
            CompositionLocalProvider(LocalDensity provides Density(phoneDensity, fontScale)) {
                key(dark, scale, fontScale, narrow) {
                    AgentAppTheme(AppearanceSettings(
                        themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT,
                        interfaceScale = scale), applyInterfaceScale = true) {
                        Column(Modifier.width(((if (narrow) 320f else 1216f / phoneDensity) / scale - 28f).dp)
                            .verticalScroll(rememberScrollState()).testTag("readability-list")) {
                            WorkProcessCard(WorkProcessUi(listOf(WorkSegmentUi(steps = listOf(step))), "已中断"),
                                ownerKey = "readability")
                            ToolDocument(ToolDisclosureUi("标准输出", output, true))
                            ToolMiniAction("保存原件", Modifier.testTag("readability-action")) { actionCount++ }
                        }
                    }
                }
            }
        } }
        ui.waitForIdle()
        val expectedWidth = ((if (narrow) 320f else 1216f / phoneDensity) / scale - 28f) * phoneDensity * scale
        val actual = node("readability-list").fetchSemanticsNode().boundsInRoot
        assertEquals("Fixture must provide the requested phone-density canvas", expectedWidth, actual.width, 1f)
    }

    private fun layout(target: SemanticsNodeInteraction): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        target.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        return layouts.single()
    }

    private fun assertTarget(tag: String, scale: Float, minimumWidth: Boolean = false, reveal: Boolean = true, minimumHeight: Float = 48f) {
        val target = ui.onNodeWithTag(tag).let { if (reveal) it.scrollToVisible() else it }.assertIsDisplayed()
        val bounds = target.fetchSemanticsNode().boundsInRoot
        val minimum = minimumHeight * phoneDensity * scale - 1f
        assertTrue("$tag has insufficient height: $bounds", bounds.height >= minimum)
        if (minimumWidth) assertTrue("$tag has insufficient width: $bounds", bounds.width >= minimum)
    }

    fun testPhoneDensityFontScaleAndNarrowLargeTextRemainReadable() {
        for (dark in listOf(false, true)) for (scale in listOf(1f, .8f)) for (large in listOf(false, true)) for (narrow in listOf(false, true)) {
            val fontScale = if (large) 1.3f else .9f
            show(dark, scale, fontScale, narrow = narrow)
            assertTarget("work-process-header", scale)
            val heading = layout(ui.onNodeWithText("设备查询", useUnmergedTree = true))
            assertEquals(14.sp, heading.layoutInput.style.fontSize)
            assertEquals(phoneDensity * scale, heading.layoutInput.density.density, .001f)
            assertEquals(fontScale, heading.layoutInput.density.fontScale, .001f)
            val icon = ui.onNodeWithTag("work-process-icon", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals(22f * phoneDensity * scale, icon.width, 1f)
            ui.onNodeWithTag("work-process-header").performClick()
            assertTarget("tool-step-header:readability", scale)
            val titleNode = ui.onNodeWithText(title, useUnmergedTree = true).assertIsDisplayed()
            assertEquals(14.sp, layout(titleNode).layoutInput.style.fontSize)
            val stateNode = ui.onNodeWithText("所属运行已中断", useUnmergedTree = true).assertIsDisplayed()
            val titleBounds = titleNode.fetchSemanticsNode().boundsInRoot
            val stateBounds = stateNode.fetchSemanticsNode().boundsInRoot
            assertTrue("Long title must not overlap state", titleBounds.right <= stateBounds.left)
            val stateLayout = layout(stateNode)
            assertFalse("dark=$dark scale=$scale large=$large narrow=$narrow " +
                "constraints=${stateLayout.layoutInput.constraints} size=${stateLayout.size} " +
                "lines=${stateLayout.lineCount} overflowWidth=${stateLayout.didOverflowWidth} " +
                "overflowHeight=${stateLayout.didOverflowHeight}", stateLayout.hasVisualOverflow)
            val headerBounds = ui.onNodeWithTag("tool-step-header:readability").fetchSemanticsNode().boundsInRoot
            assertTrue(titleBounds.left >= headerBounds.left && stateBounds.right <= headerBounds.right)
            ui.onNodeWithTag("tool-step-header:readability").performClick()
            ui.onNodeWithContentDescription("复制安全摘要").assertDoesNotExist()
            val body = layout(ui.onNodeWithText(prose, useUnmergedTree = true).scrollToVisible().assertIsDisplayed())
            assertEquals(14.sp, body.layoutInput.style.fontSize)
            assertEquals(21.sp, body.layoutInput.style.lineHeight)
            assertFalse(body.hasVisualOverflow)
            assertTarget("tool-step-technical:readability", scale, minimumHeight = 32f)
            ui.onNodeWithTag("tool-disclosure-arrow:技术详情", useUnmergedTree = true).assertIsDisplayed()
            ui.onNodeWithTag("tool-step-technical:readability").assert(
                SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "已折叠"))
            val technical = ui.onNodeWithTag("tool-step-technical:readability").fetchSemanticsNode().boundsInRoot
            val surface = ui.onNodeWithTag("work-process-surface", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("Collapsed technical section must not leave a bottom spacer", surface.bottom - technical.bottom <= 12f * phoneDensity * scale + 1f)
            ui.onNodeWithTag("tool-step-technical:readability").performClick()
            ui.onNodeWithText("收起技术详情").assertDoesNotExist()
            ui.onNodeWithTag("tool-step-technical:readability").assert(
                SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "已展开"))
            val args = layout(ui.onNodeWithTag("tool-document:调用参数", useUnmergedTree = true).scrollToVisible().assertIsDisplayed())
            assertEquals(13.sp, args.layoutInput.style.fontSize)
            assertEquals(20.sp, args.layoutInput.style.lineHeight)
            ui.onNodeWithTag("tool-step-technical:readability").scrollToVisible().performClick()
            ui.onNodeWithTag("tool-document:调用参数").assertDoesNotExist()
            assertTarget("readability-action", scale, minimumWidth = true)
        }
        assertEquals("Disclosure must not copy or run an action", 0, actionCount)
    }

    fun testMiniActionIsClickableAndDispatchesOnce() {
        show(dark = true, scale = .8f, fontScale = 1.3f, narrow = true)
        assertTarget("readability-action", .8f, minimumWidth = true)
        ui.onNodeWithTag("readability-action").performClick()
        assertEquals(1, actionCount)
    }

    fun testOutputHintOverlaysPreviewAndFullOutputStillOpens() {
        for (dark in listOf(false, true)) for (fontScale in listOf(.9f, 1.3f)) {
            show(dark = dark, scale = 1f, fontScale = fontScale, narrow = true)
            val preview = ui.onNodeWithTag("tool-output-preview:标准输出", useUnmergedTree = true).scrollToVisible()
            val previewLayout = layout(preview)
            assertEquals(13.sp, previewLayout.layoutInput.style.fontSize)
            assertEquals(3, previewLayout.lineCount)
            val hint = ui.onNodeWithTag("tool-output-hint:标准输出", useUnmergedTree = true).scrollToVisible()
            val previewBounds = preview.fetchSemanticsNode().boundsInRoot
            val hintBounds = hint.fetchSemanticsNode().boundsInRoot
            assertTrue(hintBounds.top < previewBounds.bottom)
            val documentBounds = ui.onNodeWithTag("tool-document:标准输出").fetchSemanticsNode().boundsInRoot
            assertTrue("Hint must stay inside the output surface", hintBounds.bottom <= documentBounds.bottom + 1f)
            assertTrue("The first two preview lines must stay clear",
                hintBounds.top + 1f >= previewBounds.top + previewLayout.getLineBottom(1))
            ui.onNodeWithTag("tool-document:标准输出").performClick()
            val full = ui.onNodeWithTag("tool-output-text", useUnmergedTree = true).assertTextEquals(output)
            assertEquals(13.sp, layout(full).layoutInput.style.fontSize)
            assertTarget("tool-output-close", 1f, minimumWidth = true, reveal = false)
            assertTarget("tool-output-copy", 1f, minimumWidth = true, reveal = false)
            ui.onNodeWithTag("tool-output-close").performClick()
            ui.onNodeWithTag("tool-output-dialog").assertDoesNotExist()
        }
        assertEquals(0, actionCount)
    }
}
