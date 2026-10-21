package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
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
                                onCopy = {}, onDelete = {}, assets = assets)
                        }
                    }
                }
            }
        }
        ui.waitForIdle()
        dismissIme()
    }

    private fun text(id: Int, vararg args: Any) = instrumentation.targetContext.getString(id, *args)
    private fun bounds(tag: String) = ui.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun dp(px: Float) = px / density.get().density
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

    fun testPickerKeepsReadableSpacingAndOnlyAppliesFontScaleOnce() {
        for (dark in listOf(false, true)) for (scale in listOf(1f, .8f)) {
            show(scale, dark)
            val sheet = bounds("assistant-sheet")
            val search = bounds("assistant-search")
            val list = bounds("assistant-list")
            val first = bounds("assistant-item-visual-a")
            val second = bounds("assistant-item-visual-b")
            val avatar = bounds("assistant-avatar-visual-a")
            val newButton = bounds("assistant-new")
            val done = bounds("assistant-done")
            assertTrue(dp(sheet.height) in 300f..520.5f)
            assertEquals(16f, dp(search.left - sheet.left), .6f)
            assertEquals(16f, dp(sheet.right - search.right), .6f)
            assertEquals(84f, dp(search.top - sheet.top), .8f)
            assertEquals(44f, dp(search.height), dp(1f) + .01f)
            assertEquals(17f + dp(layout(text(R.string.assistant_count, 2)).size.height.toFloat()), dp(list.top - search.bottom), .8f)
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
            assertEquals(FontWeight(650), layout(text(R.string.assistant_picker_title)).layoutInput.style.fontWeight)
            assertType(text(R.string.assistant_search), 14.sp, 20.sp)
            assertType(text(R.string.assistant_count, 2), 11.sp, 16.sp)
            assertType("Visual A", 15.sp, 21.sp)
            assertEquals(FontWeight(550), layout("Visual A").layoutInput.style.fontWeight)
            assertType("First description", 12.sp, 18.sp)
            assertType(text(R.string.assistant_current), 11.sp, 16.sp)
            assertType(text(R.string.assistant_new), 14.sp, 20.sp)
            assertType(text(R.string.assistant_done), 14.sp, 20.sp)
            assertEquals(if (dark) Color(0xFFE0DCE8) else Color(0xFF36333F), layout("Visual A").layoutInput.style.color)
            assertEquals(if (dark) Color(0xFF9C92A8) else Color(0xFF968B9F), layout("First description").layoutInput.style.color)
            assertEquals(if (dark) Color(0xFF261F30) else Color.White, layout(text(R.string.assistant_done)).layoutInput.style.color)
        }
    }

    fun testActionsKeepPreviewGeometryAndEditUsesOriginalId() {
        for (dark in listOf(false, true)) for (scale in listOf(1f, .8f)) {
            show(scale, dark)
            ui.onNodeWithTag("assistant-item-visual-a").performTouchInput { longClick() }
            ui.onNodeWithContentDescription("关闭").assertDoesNotExist()
            ui.onNodeWithContentDescription("返回").assertHasClickAction()
            val copy = bounds("assistant-action-copy")
            val edit = bounds("assistant-action-edit")
            val delete = bounds("assistant-action-delete")
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
            ui.onNodeWithTag("assistant-action-edit").performClick()
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
        assertEquals("visual-b", selected)
        assertTrue(dismissed)
    }
}
