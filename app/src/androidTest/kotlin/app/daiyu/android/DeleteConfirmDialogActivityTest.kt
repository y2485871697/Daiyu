package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.components.DaiyuConfirmDialog
import app.daiyu.android.ui.components.DaiyuDeleteConfirmDialog
import app.daiyu.android.ui.components.PreviewBackdropHost
import java.util.concurrent.atomic.AtomicInteger

class DeleteConfirmDialogActivityTest : WiredActivityTestCase() {
    private val confirmed = AtomicInteger()
    private val dismissed = AtomicInteger()

    private fun mount(dark: Boolean, enabled: Boolean = true, preview: Boolean? = null, title: String = "删除这个文件？") {
        val visit = Any()
        mainThread { activity.setContent {
            key(visit) {
                AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                    applyInterfaceScale = false) {
                    PreviewBackdropHost {
                        var open by remember { mutableStateOf(true) }
                        val confirm = { confirmed.incrementAndGet(); open = false }
                        val dismiss = { dismissed.incrementAndGet(); open = false }
                        if (open) {
                            if (preview == null) DaiyuDeleteConfirmDialog(title, confirm, dismiss, confirmEnabled = enabled)
                            else DaiyuConfirmDialog(title, "不应显示的删除说明段落", "删除", true,
                                onConfirm = confirm, onDismiss = dismiss, preview = preview, deletionAppearance = true)
                        }
                    }
                }
            }
        } }
        awaitUniqueTag("delete-confirm-dialog")
        awaitNodeWindowFocus(node("delete-confirm-dialog"))
        awaitNodeTouchReady(node("delete-confirm-dialog"))
        ui.waitForIdle()
    }

    fun testSharedConfirmationUsesCenteredGlassWithoutDescriptionInEitherRenderingMode() {
        val before = fixture.database.exportBackup()
        for (dark in listOf(false, true)) for (preview in listOf(false, true)) {
            mount(dark, preview = preview)
            node("delete-confirm-icon").assertIsDisplayed()
            node("delete-confirm-title").assertTextEquals("删除这个文件？")
            ui.onNodeWithText("不应显示的删除说明段落").assertDoesNotExist()
            node("chat-selection-sheet").assertDoesNotExist()
            ui.onAllNodesWithContentDescription("关闭").assertCountEquals(0)
            val card = node("delete-confirm-dialog").fetchSemanticsNode().boundsInRoot
            val viewport = node("preview-dialog-viewport").fetchSemanticsNode().boundsInRoot
            assertEquals(viewport.center.x, card.center.x, 2f)
            assertTrue("compact centered confirmation", card.height < viewport.height * .6f)
            node("dialog-cancel").performClick()
            ui.waitForIdle()
        }
        assertEquals(0, confirmed.get())
        assertEquals(4, dismissed.get())
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
    }

    fun testSoftRedAndDisabledConfirmationKeepCallbacksIntact() {
        val before = fixture.database.exportBackup()
        for (dark in listOf(false, true)) {
            mount(dark)
            val pixels = captureScreenNode(node("dialog-confirm")).toPixelMap()
            val expected = Color(if (dark) 0xFFCB6B70 else 0xFFBB5358)
            val fill = pixels[pixels.width / 5, pixels.height / 2]
            assertEquals(expected.red, fill.red, .025f)
            assertEquals(expected.green, fill.green, .025f)
            assertEquals(expected.blue, fill.blue, .025f)
            node("dialog-confirm").assertHeightIsAtLeast(48.dp).performClick()
            ui.waitForIdle()
            mount(dark, enabled = false)
            node("dialog-confirm").assertIsNotEnabled().performTouchInput { click() }
            node("delete-confirm-dialog").assertIsDisplayed()
            node("dialog-cancel").performClick()
            ui.waitForIdle()
        }
        assertEquals(2, confirmed.get())
        assertEquals(2, dismissed.get())
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
    }

    fun testConcreteObjectAndBatchTitlesDoNotCreateDescriptionContent() {
        for (title in listOf("删除这个会话？", "删除这个文件？", "删除这个文件夹？", "删除这个助手？",
            "删除这个子代理？", "删除这个子代理组？", "删除这个技能？", "删除选中的 3 个会话？")) {
            mount(false, title = title)
            node("delete-confirm-title").assertTextEquals(title)
            node("dialog-cancel").performClick()
            ui.waitForIdle()
        }
        assertEquals(0, confirmed.get())
        assertEquals(8, dismissed.get())
        assertEquals(0, fixture.opens.get())
    }
}
