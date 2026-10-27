package app.daiyu.android

import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.components.FolderDeleteConfirmation
import java.util.concurrent.atomic.AtomicInteger

class FolderDeleteConfirmationActivityTest : WiredActivityTestCase() {
    private var sheetDensity = 1f

    private fun mount(dark: Boolean, fontScale: Float, onConfirm: () -> Unit = {}, onDismiss: () -> Unit = {}) {
        val open = mutableStateOf(true)
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                applyInterfaceScale = false) {
                val density = LocalDensity.current
                sheetDensity = density.density
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    if (open.value) FolderDeleteConfirmation(
                        onConfirm = { onConfirm(); open.value = false },
                        onDismiss = { onDismiss(); open.value = false },
                    )
                }
            }
        } }
        awaitUniqueTag("chat-selection-sheet")
        awaitNodeWindowFocus(node("chat-selection-sheet"))
        ui.waitForIdle()
    }

    private fun bounds(tag: String) = node(tag).fetchSemanticsNode().boundsInRoot

    fun testReadableContentAndContentSizedHeight() {
        val before = fixture.database.exportBackup()
        val titleText = instrumentation.targetContext.getString(R.string.drawer_delete_folder_title)
        for (dark in listOf(false, true)) for (fontScale in listOf(1f, 1.3f, 1.8f)) {
            mount(dark, fontScale)
            ui.onAllNodesWithContentDescription("关闭").assertCountEquals(0)
            val title = ui.onNodeWithText(titleText).fetchSemanticsNode().boundsInRoot
            val message = bounds("folder-delete-message")
            val cancel = bounds("dialog-cancel")
            val confirm = bounds("dialog-confirm")
            val sheet = bounds("chat-selection-sheet")
            node("folder-delete-message").assertIsDisplayed()
            node("dialog-cancel").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            node("dialog-confirm").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            assertEquals(title.left, message.left, 1f)
            assertEquals(12f * sheetDensity, message.top - title.bottom, 2f)
            assertEquals(20f * sheetDensity, cancel.top - message.bottom, 2f)
            assertEquals(cancel.top, confirm.top, 1f)
            assertEquals(cancel.width, confirm.width, 1f)
            assertTrue("no overlap or excessive space below buttons", sheet.bottom - cancel.bottom in
                0f..(17f * sheetDensity + 1f))
            assertTrue("height follows text, not a fixed workspace", sheet.height <=
                title.height + message.height + maxOf(cancel.height, confirm.height) + 80f * sheetDensity)
            val layouts = mutableListOf<TextLayoutResult>()
            node("folder-delete-message").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(1, layouts.size)
            assertEquals(15.sp, layouts.single().layoutInput.style.fontSize)
            assertEquals(23.sp, layouts.single().layoutInput.style.lineHeight)
            assertEquals(TextAlign.Start, layouts.single().layoutInput.style.textAlign)
        }
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
    }

    fun testCancelBackAndOutsideDoNotConfirmAndDeleteOnlyConfirmsOnce() {
        val confirmations = AtomicInteger()
        val dismissals = AtomicInteger()
        val before = fixture.database.exportBackup()
        for (action in listOf("cancel", "back", "outside", "confirm")) {
            mount(false, 1f, onConfirm = { confirmations.incrementAndGet() }, onDismiss = { dismissals.incrementAndGet() })
            when (action) {
                "cancel" -> node("dialog-cancel").performTouchInput { click() }
                "back" -> instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                "outside" -> node("preview-dialog-viewport").performTouchInput { click(Offset(2f, 2f)) }
                "confirm" -> node("dialog-confirm").performTouchInput { click() }
            }
            ui.waitUntil(5_000) {
                ui.mainClock.advanceTimeByFrame()
                ui.onAllNodesWithTag("chat-selection-sheet").fetchSemanticsNodes().isEmpty()
            }
            assertEquals(if (action == "confirm") 1 else 0, confirmations.get())
        }
        assertEquals(1, confirmations.get())
        assertEquals(3, dismissals.get())
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
    }
}
