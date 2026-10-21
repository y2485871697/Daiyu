package app.daiyu.android

import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import app.daiyu.android.ui.screens.settings.ModelCatalogEntryUi
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

    fun testModelPickerHasNoCrossOrFooterAndSystemBackStillDismisses() {
        show { dismiss ->
            ChatModelPicker(ChatModelPickerState("", emptyList()), {}, dismiss)
        }
        for (text in listOf("模型设置", "刷新", "取消", "当前供应商 · 本地目录", "暂无可选模型")) {
            ui.onNodeWithText(text).assertDoesNotExist()
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        ui.waitForIdle()
        node("chat-model-picker").assertDoesNotExist()
        assertEquals(1, closes)
    }

    fun testModelPickerKeepsCatalogSearchAndSelectionWhenModelsExist() {
        var selected: String? = null
        show { dismiss ->
            ChatModelPicker(ChatModelPickerState("first", listOf(
                ModelCatalogEntryUi("first", "First model"), ModelCatalogEntryUi("second", "Second model"))),
                { selected = it }, dismiss)
        }
        node("chat-model-provider:current").assertIsDisplayed()
        node("chat-model-search").performSemanticsAction(SemanticsActions.SetText) {
            assertTrue(it(AnnotatedString("missing")))
        }
        ui.onNodeWithText("没有找到匹配的模型").assertIsDisplayed()
        node("chat-model-search").performSemanticsAction(SemanticsActions.SetText) {
            assertTrue(it(AnnotatedString("second")))
        }
        node("chat-model-option:first").assertDoesNotExist()
        node("chat-model-option:second").performClick()
        assertEquals("second", selected)
    }

    fun testEmptyModelPickerKeepsImportGuidanceWithoutDuplicateEmptyLabels() {
        val message = "本地目录为空，请到模型设置导入"
        show { dismiss -> ChatModelPicker(ChatModelPickerState("", emptyList(), message = message), {}, dismiss) }
        ui.onNodeWithText(message).assertIsDisplayed()
        ui.onNodeWithText("当前供应商 · 本地目录").assertDoesNotExist()
        ui.onNodeWithText("暂无可选模型").assertDoesNotExist()
        node("chat-model-refresh").assertDoesNotExist()
    }

    fun testSubAgentStatusHasNoCrossOrFooterAndSystemBackStillDismisses() {
        show { dismiss -> SubAgentStatusDialog(emptyList(), false, false, dismiss) }
        node("subagent-status-close").assertDoesNotExist()
        ui.onNodeWithText("当前会话").assertDoesNotExist()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        ui.waitForIdle()
        node("subagent-status").assertDoesNotExist()
        assertEquals(1, closes)
    }

    fun testImagePickerEntryDismissesAttachmentSheetAndRoutesOnlyItsAction() {
        var action: app.daiyu.android.ui.screens.chat.conversation.ChatMenuAction? = null
        show { dismiss -> ChatAttachmentSheet(dismiss) { action = it } }
        node("image-host-authorize").assertDoesNotExist()
        node("chat-attachment-path-toggle").assertDoesNotExist()
        ui.onNodeWithText("声明图片能力").assertDoesNotExist()
        ui.onNodeWithText("输入文件路径").assertDoesNotExist()
        node("chat-attachment:ATTACH_IMAGE").performClick()
        assertEquals(app.daiyu.android.ui.screens.chat.conversation.ChatMenuAction.ATTACH_IMAGE, action)
        assertEquals(1, closes)
        node("chat-attachment:ATTACH_IMAGE").assertDoesNotExist()
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
