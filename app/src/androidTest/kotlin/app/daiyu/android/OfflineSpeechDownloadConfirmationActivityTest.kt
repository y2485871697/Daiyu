package app.daiyu.android

import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.assistants.AssistantPreviewPalette
import app.daiyu.android.ui.screens.settings.OfflineSpeechDownloadConfirmation
import java.util.concurrent.atomic.AtomicInteger

/** UI-only confirmation fixture: never invokes the real model downloader or microphone. */
class OfflineSpeechDownloadConfirmationActivityTest : WiredActivityTestCase() {
    private val appearance = mutableStateOf(AppearanceSettings())
    private var density = 1f

    private fun mount(dark: Boolean = false, fontScale: Float = 1f,
        onConfirm: () -> Unit = {}, onDismiss: () -> Unit = {}) {
        val open = mutableStateOf(true)
        mainThread {
            appearance.value = AppearanceSettings(
                themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT)
            activity.setContent {
                AgentAppTheme(appearance.value, applyInterfaceScale = false) {
                    val current = LocalDensity.current
                    density = current.density
                    CompositionLocalProvider(LocalDensity provides Density(current.density, fontScale)) {
                        if (open.value) OfflineSpeechDownloadConfirmation(
                            onConfirm = { onConfirm(); open.value = false },
                            onDismiss = { onDismiss(); open.value = false })
                    }
                }
            }
        }
        awaitUniqueTag("chat-selection-sheet")
        awaitNodeWindowFocus(node("chat-selection-sheet"))
        awaitDialogEnter(node("chat-selection-sheet"))
        ui.waitForIdle()
    }

    fun testExactMessageAndContentSizedEqualButtonsAcrossFontScales() {
        for (dark in listOf(false, true)) for (font in listOf(1f, 1.8f)) {
            mount(dark, font)
            node("offline-speech-download-message").assertTextEquals("将从下载约141 MB的语音模型，可能产生流量。")
                .assertIsDisplayed()
            ui.onAllNodesWithContentDescription("关闭").assertCountEquals(0)
            val cancel = node("dialog-cancel").assertHeightIsAtLeast(48.dp).fetchSemanticsNode().boundsInRoot
            val confirm = node("dialog-confirm").assertHeightIsAtLeast(48.dp).fetchSemanticsNode().boundsInRoot
            val message = node("offline-speech-download-message").fetchSemanticsNode().boundsInRoot
            val sheet = node("chat-selection-sheet").fetchSemanticsNode().boundsInRoot
            assertEquals(cancel.width, confirm.width, 1f)
            assertEquals(cancel.top, confirm.top, 1f)
            assertEquals(cancel.height, confirm.height, 1f)
            assertTrue(cancel.right < confirm.left)
            assertTrue(message.bottom <= cancel.top + 1f)
            assertTrue(sheet.height <= message.height + cancel.height + 110f * density)
        }
    }

    private fun assertColor(expected: Color, actual: Color) {
        assertEquals(expected.red, actual.red, .02f)
        assertEquals(expected.green, actual.green, .02f)
        assertEquals(expected.blue, actual.blue, .02f)
    }

    private data class ButtonRole(val tag: String, val text: String, val background: Color, val foreground: Color)

    private fun assertRoles(dark: Boolean) {
        ui.waitForIdle()
        val expected = AssistantPreviewPalette(dark)
        for ((tag, text, background, foreground) in listOf(
            ButtonRole("dialog-cancel", "取消", expected.secondaryContainer, expected.onSecondary),
            ButtonRole("dialog-confirm", "下载", expected.primary, expected.onPrimary))) {
            val pixels = captureScreenNode(node(tag)).toPixelMap()
            assertColor(background, pixels[(pixels.width * .2f).toInt(), pixels.height / 2])
            val layouts = mutableListOf<TextLayoutResult>()
            ui.onNode(hasText(text) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(1, layouts.size)
            assertColor(foreground, layouts.single().layoutInput.style.color)
        }
    }

    fun testDefaultActionRolesRemainStableWhenPureBlackChangesWhileOpen() {
        for (dark in listOf(false, true)) {
            mount(dark)
            assertRoles(dark)
            mainThread { appearance.value = appearance.value.copy(pureBlackEnabled = !appearance.value.pureBlackEnabled) }
            assertRoles(dark)
        }
    }

    fun testCancelBackOutsideAndConfirmOnlyInvokeTheirOwnCallbackOnce() {
        val confirmations = AtomicInteger()
        val dismissals = AtomicInteger()
        for (action in listOf("cancel", "back", "outside", "confirm")) {
            val confirmationsBefore = confirmations.get()
            val dismissalsBefore = dismissals.get()
            mount(onConfirm = { confirmations.incrementAndGet() }, onDismiss = { dismissals.incrementAndGet() })
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
            assertEquals(if (action == "confirm") 1 else 0, confirmations.get() - confirmationsBefore)
            assertEquals(if (action == "confirm") 0 else 1, dismissals.get() - dismissalsBefore)
        }
        assertEquals(1, confirmations.get())
        assertEquals(3, dismissals.get())
        assertEquals(0, fixture.opens.get())
    }
}
