package app.daiyu.android

import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import app.daiyu.android.assistants.FileAssistantAssets
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.assistants.AssistantPickerDialog
import app.daiyu.android.ui.screens.chat.conversation.ChatAttachmentSheet
import app.daiyu.android.ui.screens.chat.conversation.ChatModelPicker
import app.daiyu.android.ui.screens.chat.conversation.ChatModelPickerState
import app.daiyu.android.ui.screens.chat.conversation.SubAgentStatusDialog

class DialogCloseAffordanceTest : WiredActivityTestCase() {
    private var closes = 0

    private fun show(content: @Composable (() -> Unit) -> Unit) {
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    var open by remember { mutableStateOf(true) }
                    if (open) content { closes++; open = false }
                }
            }
        }
        ui.waitForIdle()
        ui.onNodeWithContentDescription("关闭", useUnmergedTree = true).assertDoesNotExist()
    }

    fun testAssistantPickerHasNoCrossAndDoneStillDismisses() {
        val assets = FileAssistantAssets(instrumentation.targetContext)
        show { dismiss ->
            AssistantPickerDialog(true, emptyList(), null, {}, {}, dismiss,
                onCopy = {}, onDelete = {}, assets = assets)
        }
        node("assistant-done").assertIsDisplayed().performClick()
        node("assistant-sheet").assertDoesNotExist()
        assertEquals(1, closes)
    }

    fun testModelPickerHasNoCrossAndCancelStillDismisses() {
        show { dismiss ->
            ChatModelPicker(ChatModelPickerState("", emptyList()), {}, {}, {}, dismiss)
        }
        ui.onNodeWithText("取消").assertIsDisplayed().performClick()
        node("chat-model-picker").assertDoesNotExist()
        assertEquals(1, closes)
    }

    fun testSubAgentStatusHasNoCrossAndFooterStillDismisses() {
        show { dismiss -> SubAgentStatusDialog(emptyList(), false, false, dismiss) }
        node("subagent-status-close").assertIsDisplayed().performClick()
        node("subagent-status").assertDoesNotExist()
        assertEquals(1, closes)
    }

    fun testAttachmentSheetHasNoCrossAndSystemBackStillDismisses() {
        show { dismiss -> ChatAttachmentSheet(dismiss) { error("Back must not select an attachment") } }
        node("chat-attachment:ATTACH_IMAGE").assertIsDisplayed()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        ui.waitForIdle()
        node("chat-attachment:ATTACH_IMAGE").assertDoesNotExist()
        assertEquals(1, closes)
    }
}
