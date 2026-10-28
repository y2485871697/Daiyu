package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import app.daiyu.android.assistants.AssistantProfile
import app.daiyu.android.assistants.FileAssistantAssets
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.assistants.AssistantPickerDialog
import java.util.concurrent.atomic.AtomicReference

class AssistantPickerVisualTest : WiredActivityTestCase() {
    private val density = AtomicReference<Density>()
    private var selected: String? = null
    private var dismissed = false
    private var edited: String? = null

    private fun show(scale: Float = 1f, dark: Boolean = false) {
        val fixtureKey = Any()
        val assets = FileAssistantAssets(instrumentation.targetContext)
        val profiles = listOf(
            AssistantProfile("visual-a", "Visual A", "First description", createdAt = 1L),
            AssistantProfile("visual-b", "Visual B", "Second description", createdAt = 2L),
        )
        mainThread {
            selected = null
            dismissed = false
            edited = null
            activity.setContent {
                val platform = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(platform.density, .9f)) {
                    AgentAppTheme(AppearanceSettings(interfaceScale = scale,
                        themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                        applyInterfaceScale = true) {
                        density.set(LocalDensity.current)
                        key(fixtureKey) {
                            AssistantPickerDialog(true, profiles, "visual-a", { selected = it }, { edited = it }, { dismissed = true },
                                onCopy = {}, onDelete = {}, onCreate = {}, assets = assets)
                        }
                    }
                }
            }
        }
        ui.waitForIdle()
        dismissIme()
        awaitDialogEnter(ui.onNodeWithTag("assistant-sheet", useUnmergedTree = true))
    }

    private fun text(id: Int, vararg args: Any) = instrumentation.targetContext.getString(id, *args)
    private fun dp(px: Float) = px / density.get().density
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun actionGeometry(): List<Rect> {
        awaitVisualIdle()
        awaitDialogEnter(ui.onNodeWithTag("assistant-action-edit"))
        val rows = listOf("assistant-action-copy", "assistant-action-edit", "assistant-action-delete")
            .map { ui.onNodeWithTag(it, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode() }
        val root = rows.first().root
        val view = (root as ViewRootForTest).view
        return awaitStableWindowFrame(view) {
            rows.forEach { assertSame(root, it.root) }
            if (rows.any { !it.layoutInfo.isPlaced }) return@awaitStableWindowFrame null
            rows.map { it.boundsInRoot }.takeIf { bounds -> bounds.none { it.isEmpty } }
        }
    }
    private fun layout(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        ui.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
        return results.single()
    }

    private fun assertType(text: String, size: TextUnit, lineHeight: TextUnit) {
        val input = layout(text).layoutInput
        assertEquals(size, input.style.fontSize)
        assertEquals(lineHeight, input.style.lineHeight)
        assertEquals(.9f, input.density.fontScale, .001f)
    }

    private data class TypeFrame(
        val fontSize: TextUnit,
        val lineHeight: TextUnit,
        val fontScale: Float,
        val fontWeight: FontWeight?,
        val color: Color,
        val height: Int,
        val bounds: Rect,
        val firstBaseline: Float,
    )
    private data class PickerFrame(
        val bounds: List<Rect>,
        val types: Map<String, TypeFrame>,
        val density: Float,
    )
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun pickerFrame(): PickerFrame {
        val geometry = listOf("assistant-sheet", "assistant-search", "assistant-list", "assistant-item-visual-a",
            "assistant-item-visual-b", "assistant-avatar-visual-a", "assistant-new", "assistant-done", "assistant-list-meta")
            .map { ui.onNodeWithTag(it, useUnmergedTree = true).fetchSemanticsNode() }
        val texts = listOf(text(R.string.assistant_picker_title), text(R.string.assistant_search),
            text(R.string.assistant_count, 2), text(R.string.assistant_list_hint), "Visual A", "First description", text(R.string.assistant_current),
            text(R.string.assistant_new), text(R.string.assistant_done))
            .associateWith { ui.onNodeWithText(it, useUnmergedTree = true).fetchSemanticsNode() }
        val root = geometry.first().root
        val view = (root as ViewRootForTest).view
        val nodes = geometry + texts.values
        return awaitStableWindowFrame(view) {
            nodes.forEach { assertSame(root, it.root) }
            if (nodes.any { !it.layoutInfo.isPlaced || !it.layoutInfo.coordinates.isAttached })
                return@awaitStableWindowFrame null
            val bounds = geometry.map { it.boundsInRoot }
            if (bounds.any { it.isEmpty }) return@awaitStableWindowFrame null
            val types = texts.mapValues { (_, node) ->
                val results = mutableListOf<TextLayoutResult>()
                assertTrue(checkNotNull(node.config[SemanticsActions.GetTextLayoutResult].action)(results))
                val result = results.single()
                val input = result.layoutInput
                TypeFrame(input.style.fontSize, input.style.lineHeight, input.density.fontScale,
                    input.style.fontWeight, input.style.color, result.size.height, node.boundsInRoot, result.firstBaseline)
            }
            PickerFrame(bounds, types, density.get().density)
        }
    }

    fun testPickerKeepsReadableSpacingAndOnlyAppliesFontScaleOnce() {
        for (dark in listOf(false, true)) for (scale in listOf(1f, .8f)) {
            show(scale, dark)
            val frame = pickerFrame()
            val (sheet, search, list, first, second) = frame.bounds
            val avatar = frame.bounds[5]
            val newButton = frame.bounds[6]
            val done = frame.bounds[7]
            val meta = frame.bounds[8]
            val count = frame.types.getValue(text(R.string.assistant_count, 2))
            val hint = frame.types.getValue(text(R.string.assistant_list_hint))
            fun dp(px: Float) = px / frame.density
            fun assertType(text: String, size: TextUnit, lineHeight: TextUnit) {
                val sample = frame.types.getValue(text)
                assertEquals(size, sample.fontSize)
                assertEquals(lineHeight, sample.lineHeight)
                assertEquals(.9f, sample.fontScale, .001f)
            }
            assertTrue(dp(sheet.height) in 300f..520.5f)
            assertEquals(16f, dp(search.left - sheet.left), .6f)
            assertEquals(16f, dp(sheet.right - search.right), .6f)
            val titleHeight = frame.types.getValue(text(R.string.assistant_picker_title)).height
            val headerInsets = with(Density(frame.density)) {
                1.dp.roundToPx() + 10.dp.roundToPx() + 3.dp.roundToPx() +
                    8.dp.roundToPx() + 6.dp.roundToPx()
            }
            assertEquals((headerInsets + titleHeight).toFloat(), search.top - sheet.top, .51f)
            assertEquals(44f, dp(search.height), dp(1f) + .01f)
            assertEquals(search.bottom, meta.top, .51f)
            assertEquals(meta.bottom, list.top, .51f)
            assertEquals(4f, dp(count.bounds.left - meta.left), .6f)
            assertEquals(4f, dp(meta.right - hint.bounds.right), .6f)
            assertEquals(8f, dp(minOf(count.bounds.top, hint.bounds.top) - meta.top), .6f)
            assertEquals(8f, dp(meta.bottom - maxOf(count.bounds.bottom, hint.bounds.bottom)), .6f)
            assertEquals(count.bounds.top + count.firstBaseline, hint.bounds.top + hint.firstBaseline, .51f)
            assertEquals(2f, dp(first.top - list.top), .6f)
            assertEquals(2f, dp(first.left - list.left), .6f)
            assertEquals(76f, dp(first.height), .6f)
            assertEquals(5f, dp(second.top - first.bottom), .6f)
            assertEquals(40f, dp(avatar.width), .6f)
            assertEquals(40f, dp(avatar.height), .6f)
            assertEquals(11f, dp(avatar.left - first.left), .6f)
            assertEquals(first.center.y, avatar.center.y, .51f)
            assertEquals(44f, dp(newButton.height), .6f)
            assertEquals(44f, dp(done.height), .6f)
            assertEquals(dp(newButton.width), dp(done.width), .6f)
            assertEquals(8f, dp(done.left - newButton.right), .6f)
            assertEquals(17f, dp(sheet.bottom - done.bottom), .6f)
            assertEquals(dp(search.left), dp(newButton.left), .6f)
            assertType(text(R.string.assistant_picker_title), 18.sp, 26.sp)
            assertEquals(FontWeight(650), frame.types.getValue(text(R.string.assistant_picker_title)).fontWeight)
            assertType(text(R.string.assistant_search), 14.sp, 20.sp)
            assertType(text(R.string.assistant_count, 2), 11.sp, 16.sp)
            assertType(text(R.string.assistant_list_hint), 11.sp, 16.sp)
            assertType("Visual A", 15.sp, 21.sp)
            assertEquals(FontWeight(550), frame.types.getValue("Visual A").fontWeight)
            assertType("First description", 12.sp, 18.sp)
            assertType(text(R.string.assistant_current), 11.sp, 16.sp)
            assertType(text(R.string.assistant_new), 14.sp, 20.sp)
            assertType(text(R.string.assistant_done), 14.sp, 20.sp)
            assertEquals(if (dark) Color(0xFFE0DCE8) else Color(0xFF36333F), frame.types.getValue("Visual A").color)
            assertEquals(if (dark) Color(0xFF9C92A8) else Color(0xFF968B9F), frame.types.getValue("First description").color)
            assertEquals(if (dark) Color(0xFF261F30) else Color.White, frame.types.getValue(text(R.string.assistant_done)).color)
        }
    }

    fun testActionsKeepPreviewGeometryAndEditUsesOriginalId() {
        for (dark in listOf(false, true)) for (scale in listOf(1f, .8f)) {
            show(scale, dark)
            ui.onNodeWithTag("assistant-item-visual-a").performTouchInput { longClick() }
            ui.onNodeWithContentDescription("关闭").assertDoesNotExist()
            ui.onNodeWithContentDescription("返回").assertHasClickAction()
            val (copy, edit, delete) = actionGeometry()
            for (row in listOf(copy, edit, delete)) assertEquals(48f, dp(row.height), .6f)
            assertEquals(6f, dp(edit.top - copy.bottom), .6f)
            assertEquals(6f, dp(delete.top - edit.bottom), .6f)
            assertEquals(copy.left, edit.left, .6f)
            assertEquals(copy.right, delete.right, .6f)
            assertType(text(R.string.assistant_actions_title), 18.sp, 26.sp)
            assertType("Visual A", 15.sp, 22.sp)
            assertType("First description", 12.sp, 18.sp)
            assertType(text(R.string.ui_edit_assistant), 14.sp, 20.sp)
            assertEquals(if (dark) Color(0xFFDF9CA9) else Color(0xFFAE687A),
                layout(text(R.string.ui_delete_assistant)).layoutInput.style.color)
            ui.onNodeWithText(text(R.string.assistant_back_to_list)).performClick()
            ui.onNodeWithTag("assistant-item-visual-a").assertIsDisplayed()
            assertNull(selected)
            ui.onNodeWithTag("assistant-item-visual-a").performTouchInput { longClick() }
            actionGeometry()
            ui.onNodeWithTag("assistant-action-edit").performClick()
            awaitVisualIdle()
            ui.waitUntil(5_000) { ui.runOnIdle { edited != null } }
            assertEquals("visual-a", edited)
        }
    }

    fun testSearchStillFiltersRealProfilesAndSelectionUsesOriginalId() {
        show()
        val input = ui.onNodeWithTag("assistant-search-input")
        input.performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("missing")) }
        ui.onNodeWithText(text(R.string.assistant_empty)).assertIsDisplayed()
        assertType(text(R.string.assistant_empty), 13.sp, 20.sp)
        ui.onNodeWithText(text(R.string.assistant_count, 0)).assertIsDisplayed()
        input.performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("Visual B")) }
        ui.onNodeWithTag("assistant-item-visual-a").assertDoesNotExist()
        ui.onNodeWithText(text(R.string.assistant_count, 1)).assertIsDisplayed()
        ui.onNodeWithTag("assistant-item-visual-b").performClick()
        ui.waitUntil(5_000) { ui.runOnIdle { selected != null && dismissed } }
        assertEquals("visual-b", selected)
        assertTrue(dismissed)
    }
}
