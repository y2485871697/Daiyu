package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.chat.conversation.*

class ChatCompressionSendActivityTest : WiredActivityTestCase() {
    fun testBlockedSendOnlyShowsNoticeAndRestoresAfterCompression() {
        for (phase in ComposerPhase.entries) for (globallyEnabled in listOf(true, false)) {
            var notices = 0; var sends = 0; var supplements = 0; var controls = 0
            fun show(compressing: Boolean, text: String = "retained draft") {
                mainThread { activity.setContent {
                    AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                        ChatComposer(ComposerUi(text = text, phase = phase, enabled = globallyEnabled,
                            supplementAvailable = true, mainContextCompressing = compressing),
                            ChatScreenActions(onCompressionSendBlocked = { notices++ }, onSend = { sends++ },
                                onSupplement = { supplements++ }, onControlPress = { controls++ }, onPrimaryTap = { controls++ }))
                    }
                } }
                ui.waitForIdle()
            }
            show(true)
            node("chat-send").performTouchInput { click(center) }
            assertEquals(1, notices); assertEquals(0, sends); assertEquals(0, supplements); assertEquals(0, controls)
            ui.onNodeWithText("retained draft", useUnmergedTree = true).assertExists()
            show(false)
            if (globallyEnabled) {
                node("chat-send").performTouchInput { click(center) }
                assertEquals(if (phase == ComposerPhase.IDLE) 1 else 0, sends)
                assertEquals(if (phase == ComposerPhase.IDLE) 0 else 1, supplements)
            } else node("chat-send").assertIsNotEnabled()
            if (globallyEnabled && phase != ComposerPhase.IDLE) {
                show(true, "")
                node("chat-control").assertIsEnabled().performTouchInput { click(center) }
                assertTrue(controls > 0)
            }
        }
    }
}
