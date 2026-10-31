package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.Alignment
import app.daiyu.android.dismissDesktopEducationTooltip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme

class ToolCardReadabilityTest : WiredActivityTestCase() {
    private data class DisplayRestoreStep(val command: String, val size: String, val density: String)
    private val displayRestore = ArrayDeque<DisplayRestoreStep>()

    private fun displayState(command: String): String =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }

    private fun effectiveSize(sizes: String): String =
        checkNotNull(Regex("(?:Override|Physical) size: (\\d+x\\d+)").findAll(sizes).lastOrNull()) { sizes }.groupValues[1]

    private fun effectiveDensity(densities: String): String =
        checkNotNull(Regex("(?:Override|Physical) density: (\\d+)").findAll(densities).lastOrNull()) { densities }.groupValues[1]

    private fun verifyDisplay(size: String, density: String) {
        val actualSize = displayState("wm size")
        val actualDensity = displayState("wm density")
        check(effectiveSize(actualSize) == size && effectiveDensity(actualDensity) == density) {
            "Requested display $size@$density; actual: $actualSize $actualDensity"
        }
        android.util.Log.i("DaiyuToolCardDisplay", "verified size=$size density=$density")
    }

    private fun changeDisplay(command: String, size: String, density: String) {
        val sizes = displayState("wm size")
        val densities = displayState("wm density")
        val undo = if (command.startsWith("wm size ")) {
            "wm size " + (Regex("Override size: (\\d+x\\d+)").find(sizes)?.groupValues?.get(1) ?: "reset")
        } else {
            "wm density " + (Regex("Override density: (\\d+)").find(densities)?.groupValues?.get(1) ?: "reset")
        }
        displayRestore.addFirst(DisplayRestoreStep(undo, effectiveSize(sizes), effectiveDensity(densities)))
        check(displayState(command).isBlank())
        verifyDisplay(size, density)
    }

    override fun setUp() {
        verifyDisplay("320x640", "160")
        // Every intermediate configuration stays between 320dp and 560dp wide.
        changeDisplay("wm size 560x1120", "560x1120", "160")
        changeDisplay("wm density 280", "560x1120", "280")
        changeDisplay("wm size 980x1960", "980x1960", "280")
        changeDisplay("wm density 490", "980x1960", "490")
        changeDisplay("wm size 1216x2432", "1216x2432", "490")
        changeDisplay("wm density 525", "1216x2432", "525")
        instrumentation.waitForIdleSync()
        super.setUp()
        ui.waitUntil(5_000) {
            var ready = false
            instrumentation.runOnMainSync {
                ready = activity.window.decorView.width == 1216 && activity.resources.displayMetrics.densityDpi == 525
            }
            ready
        }
    }

    override fun cleanupAfterLegacy() {
        try { super.cleanupAfterLegacy() }
        finally {
            try {
                while (displayRestore.isNotEmpty()) {
                    val step = displayRestore.first()
                    check(displayState(step.command).isBlank())
                    verifyDisplay(step.size, step.density)
                    displayRestore.removeFirst()
                }
            } finally {
                instrumentation.waitForIdleSync()
            }
        }
    }

    private val phoneDensity = 525f / 160f
    private val prose = "已收到设备快照"
    private val title = "查询设备状态和当前系统内存使用情况"
    private val output = "first line\nsecond line\nthird line\nfourth line"
    private var actionCount = 0

    private fun show(dark: Boolean, scale: Float, fontScale: Float, narrow: Boolean = false, compact: Boolean = false, terminal: Boolean = false) {
        val step = WorkStepUi("readability", if (terminal) "执行命令 · Android • user" else if (compact) "查询电池" else title, "", if (compact) WorkStepState.OK else WorkStepState.UNKNOWN,
            owner = WorkStepOwnerUi("fixture", "run", "message"), category = if (terminal) "terminal" else "device_read",
            executionTargetLabel = if (terminal) "Android • user" else null,
            activity = if (compact) ToolActivityUi.SUCCESS else ToolActivityUi.INTERRUPTED,
            categoryDetails = ToolCategoryDetailsUi("设备", notice = "", rich = ToolRichDetailsUi(
                prose = ToolDisclosureUi("", prose),
                technical = listOf(ToolDisclosureUi("调用参数", "{}", true)))))
        mainThread { activity.setContent {
            CompositionLocalProvider(LocalDensity provides Density(phoneDensity, fontScale)) {
                key(dark, scale, fontScale, narrow, compact, terminal) {
                    AgentAppTheme(AppearanceSettings(
                        themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT,
                        interfaceScale = scale), applyInterfaceScale = true) {
                        Box(Modifier.fillMaxSize().statusBarsPadding()) {
                            Column(Modifier.width(((if (narrow) 320f else 1216f / phoneDensity) / scale - 28f).dp)
                                .verticalScroll(rememberScrollState()).testTag("readability-list")) {
                                WorkProcessCard(WorkProcessUi(listOf(WorkSegmentUi(steps = listOf(step))), if (compact) "已完成" else "已中断"),
                                    ownerKey = "readability")
                                ToolDocument(ToolDisclosureUi("标准输出", output, true))
                                ToolMiniAction("保存原件", Modifier.testTag("readability-action")) { actionCount++ }
                            }
                            Box(Modifier.align(Alignment.CenterEnd).width(8.dp).fillMaxHeight()
                                .testTag("readability-education-blank").pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                                }
                            })
                        }
                    }
                }
            }
        } }
        ui.waitForIdle()
        dismissDesktopEducationTooltip(instrumentation, ui, node("readability-education-blank"))
        val expectedWidth = ((if (narrow) 320f else 1216f / phoneDensity) / scale - 28f) * phoneDensity * scale
        val actual = node("readability-list").fetchSemanticsNode().boundsInRoot
        assertEquals("Fixture must provide the requested phone-density canvas", expectedWidth, actual.width, 1f)
    }

    private fun layout(target: SemanticsNodeInteraction): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        target.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        return layouts.single()
    }

    private fun assertCenteredUniformDots(target: SemanticsNodeInteraction, case: String): Float {
        val measured = layout(target)
        val textBounds = target.fetchSemanticsNode().boundsInRoot
        val positions = measured.layoutInput.text.text.indices.filter {
            measured.layoutInput.text.text[it] == '·' || measured.layoutInput.text.text[it] == '•'
        }
        assertEquals(positions.size, measured.placeholderRects.size)
        // Inline placeholders use half of the resolved header font, not separately scaled 6.5sp.
        val headerFontPx = with(measured.layoutInput.density) { measured.layoutInput.style.fontSize.toPx() }
        val expectedPlaceholderSize = ceil(headerFontPx * .5f)
        val circles = ui.onAllNodesWithTag(ToolHeaderSeparatorId, useUnmergedTree = true).fetchSemanticsNodes()
        val paintedDiameters = mutableListOf<Float>()
        measured.placeholderRects.forEachIndexed { index, nullableRect ->
            val rect = checkNotNull(nullableRect) { "Visible separator must not be ellipsized" }
            val line = measured.getLineForOffset(positions[index])
            assertEquals("Both dot characters must preserve their spacing; $case fontPx=$headerFontPx rect=$rect", expectedPlaceholderSize, rect.width, 1f)
            assertEquals("Bullet placeholder must stay square", rect.width, rect.height, 1f)
            assertEquals("Bullet must be vertically centered in its own text line",
                (measured.getLineTop(line) + measured.getLineBottom(line)) / 2f, rect.center.y, 1f)
            val bounds = rect.translate(textBounds.topLeft)
            val circleIndex = circles.indices.single { kotlin.math.abs(circles[it].boundsInRoot.center.x - bounds.center.x) <= 1f &&
                kotlin.math.abs(circles[it].boundsInRoot.center.y - bounds.center.y) <= 1f }
            val circle = circles[circleIndex].boundsInRoot
            assertEquals("Canvas must preserve the placeholder bounds", bounds.width, circle.width, 1f)
            assertEquals("Canvas must preserve the placeholder bounds", bounds.height, circle.height, 1f)
            val image = captureScreenNode(
                ui.onAllNodesWithTag(ToolHeaderSeparatorId, useUnmergedTree = true)[circleIndex]
            )
            val pixels = image.toPixelMap()
            val background = pixels[0, 0]
            fun distance(color: Color): Float = kotlin.math.abs(color.red - background.red) +
                kotlin.math.abs(color.green - background.green) + kotlin.math.abs(color.blue - background.blue)
            val contrast = distance(pixels[pixels.width / 2, pixels.height / 2])
            val rasterDiagnostic = "$case fontPx=$headerFontPx placeholder=$rect textBounds=$textBounds " +
                "expectedBounds=$bounds circle=$circle crop=${pixels.width}x${pixels.height} " +
                "corner=$background center=${pixels[pixels.width / 2, pixels.height / 2]} contrast=$contrast"
            if (contrast <= .2f) {
                val directory = java.io.File(activity.getExternalFilesDir(null), "visual-diagnostics").apply { mkdirs() }
                java.io.File(directory, "tool-dot-${android.os.SystemClock.elapsedRealtimeNanos()}.png").outputStream().use {
                    check(image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                }
                android.util.Log.e("DaiyuToolHeaderRaster", rasterDiagnostic)
            }
            assertTrue("Bullet ink must be visible in both themes; $rasterDiagnostic", contrast > .2f)
            val ink = buildList {
                for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                    if (distance(pixels[x, y]) > contrast * .25f) add(x to y)
                }
            }
            assertTrue("Bullet must have actual painted pixels", ink.isNotEmpty())
            val left = ink.minOf { it.first }; val right = ink.maxOf { it.first }
            val top = ink.minOf { it.second }; val bottom = ink.maxOf { it.second }
            val expectedInkDiameter = minOf(rect.width, rect.height) * .56f
            val inkDiameter = (right - left + 1).toFloat()
            assertEquals("Visible bullet width must be 56 percent of the placeholder", expectedInkDiameter, inkDiameter, 2f)
            assertEquals("Visible bullet height must be 56 percent of the placeholder", expectedInkDiameter, (bottom - top + 1).toFloat(), 2f)
            paintedDiameters += inkDiameter
            assertEquals("Visible bullet must be round", (right - left).toFloat(), (bottom - top).toFloat(), 1f)
            assertEquals("Painted ink must be horizontally centered", (pixels.width - 1) / 2f, (left + right) / 2f, 1f)
            assertEquals("Painted ink must be vertically centered", (pixels.height - 1) / 2f, (top + bottom) / 2f, 1f)
        }
        return paintedDiameters.first()
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
            assertTarget("work-process-header", scale, minimumHeight = 44f)
            val heading = layout(ui.onNodeWithText("工作流程", useUnmergedTree = true))
            assertEquals(13.sp, heading.layoutInput.style.fontSize)
            assertEquals(phoneDensity * scale, heading.layoutInput.density.density, .001f)
            assertEquals(fontScale, heading.layoutInput.density.fontScale, .001f)
            val icon = ui.onNodeWithTag("work-process-icon", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals(18f * phoneDensity * scale, icon.width, 1f)
            ui.onNodeWithTag("work-process-header").performClick()
            assertTarget("tool-step-header:readability", scale, minimumHeight = 44f)
            val titleNode = ui.onNodeWithText(title, useUnmergedTree = true).assertIsDisplayed()
            assertEquals(13.sp, layout(titleNode).layoutInput.style.fontSize)
            val stateNode = ui.onNodeWithText("所属运行已中断", useUnmergedTree = true).assertIsDisplayed()
            val titleBounds = titleNode.fetchSemanticsNode().boundsInRoot
            val stateBounds = stateNode.fetchSemanticsNode().boundsInRoot
            assertTrue("Long title must not overlap state", titleBounds.right <= stateBounds.left || titleBounds.bottom <= stateBounds.top)
            val stateLayout = layout(stateNode)
            assertFalse("dark=$dark scale=$scale large=$large narrow=$narrow " +
                "constraints=${stateLayout.layoutInput.constraints} size=${stateLayout.size} " +
                "paragraph=${stateLayout.multiParagraph.width}x${stateLayout.multiParagraph.height} " +
                "lines=${stateLayout.lineCount} overflowWidth=${stateLayout.didOverflowWidth} " +
                "overflowHeight=${stateLayout.didOverflowHeight}", stateLayout.hasVisualOverflow)
            for (line in 0 until stateLayout.lineCount) {
                assertFalse("State must not be ellipsized", stateLayout.isLineEllipsized(line))
                assertTrue("State line must fit the measured text width",
                    stateLayout.getLineLeft(line) >= -1f && stateLayout.getLineRight(line) <= stateLayout.size.width + 1f)
            }
            assertEquals("State must render the whole string", stateLayout.layoutInput.text.length,
                stateLayout.getLineEnd(stateLayout.lineCount - 1, visibleEnd = true))
            val headerBounds = ui.onNodeWithTag("tool-step-header:readability").fetchSemanticsNode().boundsInRoot
            assertTrue(titleBounds.left >= headerBounds.left && stateBounds.right <= headerBounds.right)
            ui.onNodeWithTag("tool-step-header:readability").performClick()
            ui.onNodeWithContentDescription("复制安全摘要").assertDoesNotExist()
            val body = layout(ui.onNodeWithText(prose, useUnmergedTree = true).scrollToVisible().assertIsDisplayed())
            assertEquals(14.sp, body.layoutInput.style.fontSize)
            assertEquals(21.sp, body.layoutInput.style.lineHeight)
            assertFalse(body.hasVisualOverflow)
            ui.onNodeWithTag("tool-step-technical:readability").assertDoesNotExist()
            ui.onNodeWithText("技术详情").assertDoesNotExist()
            ui.onNodeWithText("调用参数").assertDoesNotExist()
            ui.onNodeWithText("原始回执").assertDoesNotExist()
            ui.onNodeWithText("执行命令").assertDoesNotExist()
            ui.onNodeWithText("终端输出").assertDoesNotExist()
            assertTarget("readability-action", scale, minimumWidth = true)
        }
        assertEquals("Disclosure must not copy or run an action", 0, actionCount)
    }


    fun testCompactHeadersKeepPhoneAndNarrowCardsWithinHeightBudget() {
        for (dark in listOf(false, true)) for (narrow in listOf(false, true)) {
            show(dark = dark, scale = 1f, fontScale = .9f, narrow = narrow, compact = true)
            val header = node("work-process-header").fetchSemanticsNode().boundsInRoot
            assertEquals("Short collapsed header must stay compact", 44f * phoneDensity, header.height, 1f)
            node("work-process-header").performClick()
            val step = node("tool-step-header:readability").fetchSemanticsNode().boundsInRoot
            assertEquals("Short tool row must stay compact", 44f * phoneDensity, step.height, 1f)
            val surface = ui.onNodeWithTag("work-process-surface", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("One disclosed step must not restore the old padding", surface.height <= 98f * phoneDensity + 1f)
            assertTrue(step.left >= surface.left && step.right <= surface.right)
            node("tool-step-header:readability").performClick()
            node("tool-step-header:readability").assert(SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "成功 · 已展开"))
            node("tool-step-header:readability").performClick()
            ui.onNodeWithText(prose, useUnmergedTree = true).assertDoesNotExist()
        }
        assertEquals("Disclosures must never dispatch an action", 0, actionCount)
    }

    fun testEmphasizedTerminalDotsKeepNarrowFontScaleHeadersReadable() {
        val target = "Android • user"
        val stepTitle = "执行命令 · $target"
        for (dark in listOf(false, true)) for (scale in listOf(1f, .8f)) for (fontScale in listOf(.9f, 1.3f)) {
            show(dark = dark, scale = scale, fontScale = fontScale, narrow = true, compact = true, terminal = true)
            val heading = layout(ui.onNodeWithText("工作流程", useUnmergedTree = true))
            assertEquals(13.sp, heading.layoutInput.style.fontSize)
            assertEquals("工作流程", heading.layoutInput.text.text)
            assertTrue(heading.placeholderRects.isEmpty())
            assertFalse(heading.hasVisualOverflow)
            val case = "dark=$dark scale=$scale fontScale=$fontScale"
            node("work-process-header").performClick()
            val titleNode = ui.onNodeWithText(stepTitle, useUnmergedTree = true).assertIsDisplayed()
            val measured = layout(titleNode)
            assertEquals(13.sp, measured.layoutInput.style.fontSize)
            assertEquals(toolHeaderText(stepTitle, true), measured.layoutInput.text)
            assertCenteredUniformDots(titleNode, "$case step")
            assertFalse(measured.hasVisualOverflow)
            for (line in 0 until measured.lineCount) assertFalse(measured.isLineEllipsized(line))
            assertEquals(stepTitle.length, measured.getLineEnd(measured.lineCount - 1, visibleEnd = true))
            val state = ui.onNodeWithText("成功", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val bounds = titleNode.fetchSemanticsNode().boundsInRoot
            val header = node("tool-step-header:readability").fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.right <= state.left || bounds.bottom <= state.top)
            assertTrue(bounds.left >= header.left && bounds.right <= header.right &&
                bounds.top >= header.top && bounds.bottom <= header.bottom)
            assertTrue(state.top >= header.top && state.bottom <= header.bottom)
            if (fontScale == .9f) {
                val expectedHeight = with(measured.layoutInput.density) {
                    maxOf(44.dp.roundToPx().toFloat(), bounds.height + state.height + 2 * 5.dp.roundToPx())
                }
                assertEquals(expectedHeight, header.height, 1f)
                val surface = ui.onNodeWithTag("work-process-surface", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                assertTrue(surface.height <= 98f * phoneDensity * scale + 1f)
            }
        }
        assertEquals(0, actionCount)
    }

    fun testMiniActionIsClickableAndDispatchesOnce() {
        show(dark = true, scale = .8f, fontScale = 1.3f, narrow = true)
        assertTarget("readability-action", .8f, minimumWidth = true)
        ui.onNodeWithTag("readability-action").performClick()
        assertEquals(1, actionCount)
    }

    fun testOutputPreviewHasNoHintAndFullOutputStillOpens() {
        for (dark in listOf(false, true)) for (fontScale in listOf(.9f, 1.3f)) {
            show(dark = dark, scale = 1f, fontScale = fontScale, narrow = true)
            val preview = ui.onNodeWithTag("tool-output-preview:标准输出", useUnmergedTree = true).scrollToVisible()
            val previewLayout = layout(preview)
            assertEquals(13.sp, previewLayout.layoutInput.style.fontSize)
            assertEquals(3, previewLayout.lineCount)
            ui.onNodeWithText("点按查看完整输出", useUnmergedTree = true).assertDoesNotExist()
            ui.onNodeWithTag("tool-output-hint:标准输出", useUnmergedTree = true).assertDoesNotExist()
            ui.onNodeWithTag("tool-document:标准输出").performClick()
            val full = ui.onNodeWithTag("tool-output-text", useUnmergedTree = true).assertTextEquals(output)
            assertEquals(13.sp, layout(full).layoutInput.style.fontSize)
            ui.onNodeWithTag("tool-output-close", useUnmergedTree = true).assertDoesNotExist()
            assertTarget("tool-output-copy", 1f, minimumWidth = true, reveal = false)
            ui.onNodeWithTag("tool-output-content", useUnmergedTree = true).performClick()
            ui.onNodeWithTag("tool-output-dialog").assertExists()
            ui.onNodeWithTag("preview-dialog-scrim", useUnmergedTree = true).performTouchInput {
                click(topLeft + androidx.compose.ui.geometry.Offset(2f, 2f))
            }
            ui.waitForIdle()
            ui.onNodeWithTag("tool-output-dialog").assertDoesNotExist()
        }
        assertEquals(0, actionCount)
    }

    fun testFailedAndUnconfirmedHeaderCountsNeverEllipsize() {
        val states = listOf(WorkStepState.OK, WorkStepState.OK, WorkStepState.OK,
            WorkStepState.FAILED, WorkStepState.UNKNOWN)
        val work = WorkProcessUi(listOf(WorkSegmentUi(steps = states.mapIndexed { index, state ->
            WorkStepUi("outcome-$index", "operation", "", state)
        })), "已暂停", running = true, controlActivity = ToolActivityUi.PAUSED)
        val expected = "已完成3步 · 1步失败 · 1步待确认 · 已暂停"
        for (width in listOf(272, 300, 360)) for (fontScale in listOf(1f, 1.3f)) {
            mainThread { activity.setContent {
                CompositionLocalProvider(LocalDensity provides Density(phoneDensity, fontScale)) {
                    AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                        Box(Modifier.width(width.dp)) { WorkProcessCard(work, ownerKey = "outcome-header") }
                    }
                }
            } }
            val node = ui.onNodeWithText(expected, useUnmergedTree = true).assertIsDisplayed()
            val measured = layout(node)
            assertFalse("width=$width fontScale=$fontScale", measured.hasVisualOverflow)
            for (line in 0 until measured.lineCount) assertFalse(measured.isLineEllipsized(line))
            assertEquals(expected.length, measured.getLineEnd(measured.lineCount - 1, visibleEnd = true))
            val titleBounds = ui.onNodeWithText("工作流程", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue(node.fetchSemanticsNode().boundsInRoot.top >= titleBounds.bottom - 1f)
        }
    }
}
