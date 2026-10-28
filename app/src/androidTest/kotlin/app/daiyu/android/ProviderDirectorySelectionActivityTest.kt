package app.daiyu.android

import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.daiyu.android.storage.ProviderRegistry
import app.daiyu.android.storage.ProviderRegistrySnapshot

class ProviderDirectorySelectionActivityTest : WiredActivityTestCase() {
    private fun addRows(): ProviderRegistrySnapshot {
        val repo = fixture.controller.providers
        val first = repo.register(repo.snapshot().revision, "Selection First")
        return repo.register(first.revision, "Selection Second")
    }
    private fun row(slot: String) = reveal("provider-open-$slot", "模型提供商")
    private fun select(slot: String) { row(slot).performTouchInput { longClick() } }

    fun testLongPressSelectsInlineAndCancelBackAndPauseNeverWrite() {
        val before = addRows()
        val settings = fixture.settings.load()
        openProviderManagement()
        select(ProviderRegistry.PRIMARY)
        node("provider-open-primary").assertIsOn()
        node("provider-selection-actions").assertIsDisplayed()
        val page = node("page:模型提供商").fetchSemanticsNode().boundsInRoot
        val actions = node("provider-selection-actions").fetchSemanticsNode().boundsInRoot
        assertTrue("selection retains a visible list", page.height > actions.height)
        assertTrue("footer is below the list, not over it", actions.top >= page.bottom - 1f)
        val delete = node("provider-delete-selected").fetchSemanticsNode().boundsInRoot
        val cancel = node("provider-cancel-selection").fetchSemanticsNode().boundsInRoot
        assertTrue("cancel precedes delete without overlap", cancel.right <= delete.left)
        assertEquals(delete.width, cancel.width, 1f)
        ui.onNodeWithText("配置与模型").assertDoesNotExist()
        node("dialog-confirm").assertDoesNotExist()
        row(before.providers[1].slotId).performClick()
        node("provider-open-${before.providers[1].slotId}").assertIsOn()
        node("page:模型提供商").assertExists()
        node("provider-cancel-selection").performClick()
        node("provider-selection-actions").assertDoesNotExist()
        select(ProviderRegistry.PRIMARY)
        mainThread { activity.onBackPressedDispatcher.onBackPressed() }
        node("provider-selection-actions").assertDoesNotExist()
        node("page:模型提供商").assertExists()
        select(ProviderRegistry.PRIMARY)
        realPauseResume(activity)
        node("provider-selection-actions").assertDoesNotExist()
        assertEquals(before, fixture.controller.providers.snapshot())
        assertEquals(settings, fixture.settings.load())
        assertEquals(0, fixture.opens.get())
        row(ProviderRegistry.PRIMARY).performClick()
        awaitUniqueTag("page:主模型服务商")
    }

    fun testSelectedProviderAndConfirmationShareVividRedWithoutDeletingOnCancel() {
        val before = addRows()
        openProviderManagement()
        select(ProviderRegistry.PRIMARY)
        fun assertVividRed(tag: String) {
            val pixels = captureScreenNode(node(tag).assertIsEnabled()).toPixelMap()
            val expected = Color(0xFFE60012)
            var red = 0
            var white = 0
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                val pixel = pixels[x, y]
                if (kotlin.math.abs(pixel.red - expected.red) < .025f && pixel.green < .025f &&
                    kotlin.math.abs(pixel.blue - expected.blue) < .025f) red++
                if (pixel.red > .97f && pixel.green > .97f && pixel.blue > .97f) white++
            }
            assertTrue("opaque vivid-red fill: $tag", red > pixels.width * pixels.height * .35f)
            assertTrue("white label: $tag", white > 8)
        }
        assertVividRed("provider-delete-selected")
        node("provider-delete-selected").performClick()
        awaitUniqueTag("dialog-confirm")
        assertVividRed("dialog-confirm")
        node("dialog-cancel").performClick()
        assertEquals(before, fixture.controller.providers.snapshot())
        assertEquals(0, fixture.opens.get())
    }

    private fun measuredTextLayout(result: TextLayoutResult, size: IntSize = result.size): TextLayoutResult {
        val input = result.layoutInput
        return TextMeasurer(input.fontFamilyResolver, input.density, input.layoutDirection, cacheSize = 0).measure(
            text = input.text, style = input.style, overflow = input.overflow, softWrap = input.softWrap,
            maxLines = input.maxLines, placeholders = input.placeholders,
            constraints = Constraints.fixed(size.width, size.height))
    }

    private fun actualFirstBaseline(target: SemanticsNodeInteraction): Float {
        val layoutInfo = target.fetchSemanticsNode().layoutInfo
        return ui.runOnIdle {
            var coordinates = layoutInfo.coordinates
            assertTrue(coordinates.isAttached)
            val parentLayout = coordinates.parentLayoutCoordinates
            var baseline = coordinates[FirstBaseline]
            while (baseline == AlignmentLine.Unspecified) {
                val parent = coordinates.parentCoordinates
                assertTrue("Text must expose FirstBaseline within its own layout modifiers",
                    parent != null && parent !== parentLayout)
                coordinates = checkNotNull(parent)
                assertTrue(coordinates.isAttached)
                baseline = coordinates[FirstBaseline]
            }
            coordinates.positionInRoot().y + baseline
        }
    }

    private fun assertCountAndSelectAllTypography() {
        val title = reveal("provider-count-title", "模型提供商", "provider-count")
        val selectAll = reveal("provider-select-all", "模型提供商", "provider-count")
        val titleLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        val actionLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        title.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(titleLayouts) }
        selectAll.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(actionLayouts) }
        val titleLayout = titleLayouts.single()
        val actionLayout = actionLayouts.single()
        assertEquals(titleLayout.layoutInput.style.fontSize, actionLayout.layoutInput.style.fontSize)
        assertEquals(titleLayout.layoutInput.style.fontWeight, actionLayout.layoutInput.style.fontWeight)
        fun assertFits(target: SemanticsNodeInteraction, raw: TextLayoutResult) {
            val node = target.fetchSemanticsNode()
            assertEquals(node.config[SemanticsProperties.Text].single(), raw.layoutInput.text)
            val verticalPadding = with(raw.layoutInput.density) { 8.dp.roundToPx() }
            assertEquals(node.size.width, raw.size.width)
            assertEquals(node.size.height, raw.size.height + 2 * verticalPadding)
            val measured = measuredTextLayout(raw)
            assertFalse("${raw.layoutInput.text.text}: box=${raw.size} paragraph=${raw.multiParagraph.width}x${raw.multiParagraph.height}",
                measured.hasVisualOverflow)
            assertTrue(measuredTextLayout(raw, IntSize(1, raw.size.height)).hasVisualOverflow)
        }
        assertFits(title, titleLayout)
        assertFits(selectAll, actionLayout)
        val invert = reveal("provider-invert-selection", "模型提供商", "provider-count")
        val invertLayouts = mutableListOf<TextLayoutResult>()
        invert.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(invertLayouts) }
        val invertLayout = invertLayouts.single()
        assertEquals(titleLayout.layoutInput.style.fontSize, invertLayout.layoutInput.style.fontSize)
        assertEquals(titleLayout.layoutInput.style.fontWeight, invertLayout.layoutInput.style.fontWeight)
        assertFits(invert, invertLayout)
        assertEquals(actualFirstBaseline(title), actualFirstBaseline(invert), 1f)
        assertEquals(actualFirstBaseline(title), actualFirstBaseline(selectAll), 1f)
    }

    fun testSelectAllMatchesCountTypographyAndBaselineInBothStates() {
        val before = addRows()
        openProviderManagement()
        select(ProviderRegistry.PRIMARY)
        node("provider-select-all").assertTextEquals("全选")
        assertCountAndSelectAllTypography()
        node("provider-select-all").performClick()
        node("provider-select-all").assertTextEquals("取消全选")
        assertCountAndSelectAllTypography()
        node("provider-select-all").performClick()
        node("provider-delete-selected").assertIsNotEnabled()
        assertEquals(before, fixture.controller.providers.snapshot())
        node("provider-cancel-selection").performClick()
        assertEquals(0, fixture.opens.get())
    }

    fun testFilteredSelectAllAndEmptySelectionDoNotSelectHiddenProviders() {
        val before = addRows()
        openProviderManagement()
        select(ProviderRegistry.PRIMARY)
        reveal("provider-search", "模型提供商").performTextReplacement("Selection First")
        reveal("provider-select-all", "模型提供商", "provider-count").performClick()
        row(before.providers[1].slotId).assertIsOn()
        reveal("provider-search", "模型提供商").performTextReplacement("")
        row(ProviderRegistry.PRIMARY).assertIsOn()
        row(before.providers[2].slotId).assertIsOff()
        row(ProviderRegistry.PRIMARY).performClick()
        row(before.providers[1].slotId).performClick()
        node("provider-selection-actions").assertIsDisplayed()
        node("provider-delete-selected").assertIsNotEnabled()
        node("provider-cancel-selection").performClick()
        assertEquals(before, fixture.controller.providers.snapshot())
        assertEquals(0, fixture.opens.get())
    }

    fun testFilteredInvertPreservesHiddenSelectedProviderAndCanSelectNone() {
        val before = addRows()
        openProviderManagement(); select(ProviderRegistry.PRIMARY)
        reveal("provider-search", "模型提供商").performTextReplacement("Selection First")
        reveal("provider-invert-selection", "模型提供商", "provider-count").performClick()
        row(before.providers[1].slotId).assertIsOn()
        reveal("provider-invert-selection", "模型提供商", "provider-count").performClick()
        row(before.providers[1].slotId).assertIsOff()
        reveal("provider-search", "模型提供商").performTextClearance()
        row(ProviderRegistry.PRIMARY).assertIsOn(); row(before.providers[2].slotId).assertIsOff()
        row(ProviderRegistry.PRIMARY).performClick()
        node("provider-delete-selected").assertIsNotEnabled(); node("provider-selection-actions").assertIsDisplayed()
        reveal("provider-search", "模型提供商").performTextReplacement("not-a-provider")
        reveal("provider-invert-selection", "模型提供商", "provider-count").assertIsNotEnabled()
        assertEquals(before, fixture.controller.providers.snapshot()); assertEquals(0, fixture.opens.get())
    }

    fun testConfirmedBatchDeletionUsesOneRevisionAndDoesNotSwitchCurrentProvider() {
        val before = addRows()
        val settings = fixture.settings.load()
        openProviderManagement()
        select(ProviderRegistry.PRIMARY)
        row(before.providers[1].slotId).performClick()
        node("provider-delete-selected").performClick()
        awaitUniqueTag("dialog-confirm")
        node("dialog-cancel").performClick()
        assertEquals(before, fixture.controller.providers.snapshot())
        node("provider-delete-selected").performClick()
        awaitUniqueTag("dialog-confirm")
        awaitNodeWindowFocus(node("dialog-confirm"))
        awaitVisualIdle()
        node("dialog-confirm").performClick()
        awaitControl("selected providers removed") { fixture.controller.providers.snapshot().providers.size == 1 }
        val after = fixture.controller.providers.snapshot()
        assertEquals(before.revision + 1, after.revision)
        assertEquals(before.providers.drop(2), after.providers)
        assertNull(after.activeSlotId)
        node("provider-selection-actions").assertDoesNotExist()
        node("page:模型提供商").assertExists()
        assertEquals(settings, fixture.settings.load())
        assertEquals(0, fixture.opens.get())
    }
}
