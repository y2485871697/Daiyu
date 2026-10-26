package app.daiyu.android

import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.components.ConversationWorkspaceSheet
import app.daiyu.android.ui.components.WorkspaceSourceOption
import app.daiyu.android.ui.screens.chat.conversation.*

class ConversationWorkspaceActivityTest : WiredActivityTestCase() {
    private fun px(dp: Int): Float = dp * activity.resources.displayMetrics.density

    private var opens = 0
    private var voiceLongPresses = 0
    private var saves = 0
    private var dismisses = 0
    private var visible by mutableStateOf(true)
    private var busy by mutableStateOf(false)
    private var sourceId by mutableStateOf("private")
    private var path by mutableStateOf("/private/workspace")

    private fun showComposer() {
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                ChatMenuLayer(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
                        ChatComposer(ComposerUi(text = "可选择的正文\n第二行\n第三行\n第四行\n第五行\n第六行\n第七行"),
                            ChatScreenActions(onOpenWorkspace = { opens++ }, onVoiceLongPress = { voiceLongPresses++ }))
                    }
                }
            }
        } }
        awaitHostWindowFocus(activity)
    }

    fun testUpwardBlankStripCommitsOnlyOnceOnRelease() {
        showComposer()
        val strip = node("chat-workspace-gesture-area")
        strip.performTouchInput {
            down(center)
            moveBy(Offset(0f, -px(80)))
            advanceEventTime(100)
        }
        ui.runOnIdle { assertEquals(0, opens) }
        strip.performTouchInput {
            moveBy(Offset(0f, -px(20)))
            up()
        }
        ui.runOnIdle { assertEquals(1, opens) }
        strip.performTouchInput { swipe(center, center + Offset(0f, -px(80))) }
        ui.runOnIdle { assertEquals(2, opens) }
    }

    fun testCancelHorizontalDownAndShortDragsDoNotOpen() {
        showComposer()
        val strip = node("chat-workspace-gesture-area")
        strip.performTouchInput {
            down(center); moveBy(Offset(0f, -px(80))); cancel()
        }
        strip.performTouchInput { swipe(center, center + Offset(px(100), 0f)) }
        strip.performTouchInput { swipe(center, center + Offset(0f, px(60))) }
        strip.performTouchInput { swipe(center, center + Offset(0f, -px(12))) }
        strip.performTouchInput {
            down(center)
            moveBy(Offset(px(40), 0f))
            moveBy(Offset(0f, -px(100)))
            up()
        }
        ui.runOnIdle { assertEquals(0, opens) }
    }

    fun testEditorAndVoiceOwnTheirGestures() {
        showComposer()
        node("chat-draft").performTouchInput { swipeUp() }
        node("chat-draft").performTouchInput { longClick() }
        ui.runOnIdle { assertEquals(0, opens) }
        // Remove the text selection toolbar/IME before testing the voice long press.
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitHostWindowFocus(activity)
        node("chat-voice").performTouchInput { longClick() }
        awaitUniqueTag("chat-voice-mode-menu")
        ui.runOnIdle {
            assertEquals(0, opens)
            assertEquals(1, voiceLongPresses)
        }
    }

    private fun showSheet() {
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                ConversationWorkspaceSheet(visible = visible,
                    sources = listOf(
                        WorkspaceSourceOption("private", "应用私有", "/private", "/private/workspace"),
                        WorkspaceSourceOption("rootfs", "rootfs", "/approved/rootfs", "/approved/rootfs/home")),
                    selectedSourceId = sourceId, path = path, busy = busy, error = "路径不可用",
                    onSourceChange = { sourceId = it }, onPathChange = { path = it },
                    onSave = { saves++ }, onDismiss = { dismisses++; visible = false })
            }
        } }
        awaitUniqueTag("conversation-workspace-sheet")
        awaitNodeWindowFocus(node("conversation-workspace-sheet"))
        ui.mainClock.advanceTimeBy(1_000)
        awaitVisualIdle()
    }

    fun testControlledEditsAndDoneDoNotImplicitlyDismiss() {
        showSheet()
        node("workspace-source:private").assertIsSelected()
        node("workspace-source:rootfs").performClick().assertIsSelected()
        // Source selection never invents a draft/default or accesses a filesystem.
        node("workspace-path").assertEditableText("/private/workspace")
        node("workspace-path").performTextReplacement("/approved/rootfs/项目")
        node("workspace-error").assertTextEquals("路径不可用")
        node("workspace-save").performClick()
        ui.runOnIdle {
            assertEquals("rootfs", sourceId)
            assertEquals("/approved/rootfs/项目", path)
            assertEquals(1, saves)
            assertEquals(0, dismisses)
        }
        node("conversation-workspace-sheet").assertExists()
        node("workspace-close").performClick()
        ui.runOnIdle { assertEquals(1, dismisses); assertEquals(1, saves) }
        node("conversation-workspace-sheet").assertDoesNotExist()
    }

    fun testBusyDisablesSaveAndEditsButCloseDoesNotSave() {
        busy = true
        showSheet()
        node("workspace-save").assertIsNotEnabled()
        node("workspace-source:rootfs").assertIsNotEnabled()
        node("workspace-path").assertIsNotEnabled()
        node("workspace-close").performClick()
        ui.runOnIdle { assertEquals(0, saves); assertEquals(1, dismisses) }
    }

    fun testBackAndOutsideDismissNeverSaveEvenWhenBusy() {
        busy = true
        showSheet()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        ui.waitUntil(5_000) { !visible }
        ui.runOnIdle { assertEquals(0, saves); assertEquals(1, dismisses); visible = true }
        awaitUniqueTag("conversation-workspace-sheet")
        awaitNodeWindowFocus(node("conversation-workspace-sheet"))
        ui.mainClock.advanceTimeBy(1_000)
        awaitVisualIdle()
        node("preview-dialog-scrim").performTouchInput { click(Offset(center.x, 2f)) }
        ui.waitUntil(5_000) { !visible }
        ui.runOnIdle { assertEquals(0, saves); assertEquals(2, dismisses) }
    }

    fun testInvisibleOwnerDoesNotRenderOrDispatch() {
        visible = false
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                ConversationWorkspaceSheet(false, emptyList(), "", "", onSourceChange = {}, onPathChange = {},
                    onSave = { saves++ }, onDismiss = { dismisses++ })
            }
        } }
        node("conversation-workspace-sheet").assertDoesNotExist()
        ui.runOnIdle { assertEquals(0, saves); assertEquals(0, dismisses) }
    }
}
