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
        awaitUniqueTag("delete-confirm-dialog")
        awaitNodeWindowFocus(node("delete-confirm-dialog"))
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
            val icon = bounds("delete-confirm-icon")
            val cancel = bounds("dialog-cancel")
            val confirm = bounds("dialog-confirm")
            val sheet = bounds("delete-confirm-dialog")
            val viewport = bounds("preview-dialog-viewport")
            node("folder-delete-message").assertDoesNotExist()
            ui.onNodeWithText(instrumentation.targetContext.getString(R.string.drawer_delete_folder_message)).assertDoesNotExist()
            node("dialog-cancel").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            node("dialog-confirm").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            assertEquals(sheet.center.x, title.center.x, 1f)
            assertEquals(21f * sheetDensity, title.top - icon.bottom, 2f)
            assertEquals(27f * sheetDensity, cancel.top - title.bottom, 2f)
            assertEquals(cancel.top, confirm.top, 1f)
            assertEquals(cancel.width, confirm.width, 1f)
            assertEquals(23f * sheetDensity, sheet.bottom - maxOf(cancel.bottom, confirm.bottom), 2f)
            assertEquals(viewport.center.x, sheet.center.x, 2f)
            assertTrue("dialog is vertically centered inside safe drawing area",
                kotlin.math.abs(viewport.center.y - sheet.center.y) <= 32f * sheetDensity)
            assertTrue("height follows icon, title and actions", sheet.height <=
                icon.height + title.height + maxOf(cancel.height, confirm.height) + 105f * sheetDensity)
            val layouts = mutableListOf<TextLayoutResult>()
            node("delete-confirm-title").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(1, layouts.size)
            assertEquals(20.sp, layouts.single().layoutInput.style.fontSize)
            assertEquals(28.sp, layouts.single().layoutInput.style.lineHeight)
            assertEquals(TextAlign.Center, layouts.single().layoutInput.style.textAlign)
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
                ui.onAllNodesWithTag("delete-confirm-dialog").fetchSemanticsNodes().isEmpty()
            }
            assertEquals(if (action == "confirm") 1 else 0, confirmations.get())
        }
        assertEquals(1, confirmations.get())
        assertEquals(3, dismissals.get())
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
    }
}
