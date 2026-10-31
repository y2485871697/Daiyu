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
    fun testPreparingSendUsesOnlyComposerCancelEvenWhileCompressionBlocksSending() {
        for (phase in ComposerPhase.entries) for (compressing in listOf(false, true)) {
            var cancellations = 0; var sends = 0; var supplements = 0; var controls = 0; var notices = 0
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    ChatComposer(ComposerUi(text = "frozen draft", phase = phase, enabled = false,
                        supplementAvailable = true, mainContextCompressing = compressing,
                        preparingSendKey = "ticket-${phase.name}-$compressing"),
                        ChatScreenActions(onCancelSend = { cancellations++ }, onSend = { sends++ },
                            onSupplement = { supplements++ }, onPrimaryTap = { controls++ },
                            onPrimaryLongPress = { controls++ }, onControlPress = { controls++ },
                            onControlRelease = { controls++ }, onCompressionSendBlocked = { notices++ }))
                }
            } }
            ui.waitForIdle()
            node("chat-draft").assertIsNotEnabled()
            node("chat-send").assertDoesNotExist()
            node("chat-control").assertDoesNotExist()
            node("text-send-cancel").assertIsEnabled().assertContentDescriptionEquals("取消发送")
                .performTouchInput { click(center) }
            assertEquals(1, cancellations)
            node("text-send-cancel").performTouchInput { longClick(center) }
            assertEquals(2, cancellations)
            assertEquals(0, sends); assertEquals(0, supplements); assertEquals(0, controls); assertEquals(0, notices)
            ui.onNodeWithText("取消发送准备").assertDoesNotExist()
        }
    }

    fun testAcceptedPreviewHasNoPreparationCaptionWhileRunControlIsAlreadyVisible() {
        var controls = 0; var cancellations = 0
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                DaiyuChatScreen(ChatScreenState(title = "accepted send",
                    turns = listOf(ChatTurnUi.Assistant("accepted-answer")),
                    composer = ComposerUi(phase = ComposerPhase.RUNNING),
                    submissionPreview = ChatSubmissionPreview("ticket", "accepted text",
                        settledUserId = "accepted-user", settledAssistantId = "accepted-answer")),
                    ChatScreenActions(onPrimaryTap = { controls++ }, onCancelSend = { cancellations++ }))
            }
        } }
        ui.waitForIdle()
        node("chat-send-preview").assertIsDisplayed()
        ui.onNodeWithText("accepted text").assertIsDisplayed()
        ui.onNodeWithText("正在准备发送…").assertDoesNotExist()
        ui.onNodeWithText("正在准备上下文并核实发送结果").assertDoesNotExist()
        node("text-send-cancel").assertDoesNotExist()
        node("chat-control").assertIsEnabled().assertContentDescriptionEquals("暂停")
            .performTouchInput { click(center) }
        assertEquals(1, controls); assertEquals(0, cancellations)
    }

}
