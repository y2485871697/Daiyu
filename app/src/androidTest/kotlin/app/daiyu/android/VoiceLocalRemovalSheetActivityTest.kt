package app.daiyu.android

import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.assistants.assistantPreviewPalette
import java.util.concurrent.atomic.AtomicInteger

class VoiceLocalRemovalSheetActivityTest : WiredActivityTestCase() {
    private val confirmed = AtomicInteger()
    private val dismissed = AtomicInteger()
    private var expectedPrimary = Color.Unspecified
    private var density = 1f

    private fun mount(dark: Boolean = false, fontScale: Float = 1f) {
        val visit = Any()
        mainThread { activity.setContent {
            key(visit) {
                AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                    applyInterfaceScale = false) {
                    val local = LocalDensity.current
                    density = local.density
                    expectedPrimary = assistantPreviewPalette().primary
                    CompositionLocalProvider(LocalDensity provides Density(local.density, fontScale)) {
                        var open by remember { mutableStateOf(true) }
                        if (open) VoiceLocalRemovalSheet(
                            onConfirm = { confirmed.incrementAndGet(); open = false },
                            onDismiss = { dismissed.incrementAndGet(); open = false })
                    }
                }
            }
        } }
        awaitUniqueTag("voice-local-removal-sheet")
        awaitNodeWindowFocus(node("voice-local-removal-sheet"))
        ui.waitForIdle()
    }

    private fun awaitClosed() {
        ui.waitUntil(5_000) {
            ui.mainClock.advanceTimeByFrame()
            ui.onAllNodesWithTag("voice-local-removal-sheet").fetchSemanticsNodes().isEmpty()
        }
    }

    fun testContentSizedReferenceSheetAndButtonsInBothThemesAndLargeFonts() {
        val before = fixture.database.exportBackup()
        for (dark in listOf(false, true)) for (fontScale in listOf(1f, 1.3f)) {
            mount(dark, fontScale)
            val sheet = node("voice-local-removal-sheet").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val viewport = node("preview-dialog-viewport").fetchSemanticsNode().boundsInRoot
            assertTrue("confirmation wraps its content", sheet.height < viewport.height * .6f)
            val cancel = node("dialog-cancel").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .fetchSemanticsNode().boundsInRoot
            val confirm = node("dialog-confirm").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .fetchSemanticsNode().boundsInRoot
            assertEquals(cancel.width, confirm.width, 1f)
            assertEquals(cancel.height, confirm.height, 1f)
            assertTrue(cancel.right < confirm.left)
            val title = ui.onNodeWithText("确认本次操作").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals("title aligns with the left action", cancel.left, title.left, 1f)
            ui.onNodeWithText("仅移除本机记录，不删除云端音色或任务，不退款、不释放名额").assertDoesNotExist()
            ui.onAllNodesWithContentDescription("关闭").assertCountEquals(0)
            val beforeConfirm = confirmed.get()
            node("dialog-confirm").performClick()
            awaitClosed()
            assertEquals(beforeConfirm + 1, confirmed.get())
        }
        assertEquals(0, dismissed.get())
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
    }

    fun testPrimaryUsesTheSameAccentAsTheCollaborationDoneButton() {
        for (dark in listOf(false, true)) {
            mount(dark)
            val pixels = node("dialog-confirm").captureToImage().toPixelMap()
            val fill = pixels[(12f * density).toInt(), pixels.height / 2]
            assertEquals(expectedPrimary.red, fill.red, .025f)
            assertEquals(expectedPrimary.green, fill.green, .025f)
            assertEquals(expectedPrimary.blue, fill.blue, .025f)
            node("dialog-cancel").performClick()
            awaitClosed()
        }
        assertEquals(0, confirmed.get())
        assertEquals(2, dismissed.get())
        assertEquals(0, fixture.opens.get())
    }

    fun testCancelBackAndOutsideOnlyDismissWithoutRemovingOrOpeningNetwork() {
        val before = fixture.database.exportBackup()
        mount()
        node("dialog-cancel").performClick()
        awaitClosed()
        assertEquals(1, dismissed.get())
        mount()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitClosed()
        assertEquals(2, dismissed.get())
        mount()
        node("preview-dialog-scrim").performTouchInput { click(Offset(2f, 2f)) }
        awaitClosed()
        assertEquals(3, dismissed.get())
        assertEquals(0, confirmed.get())
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
    }
}
