package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.chat.conversation.*

class ChatComposerReferencesActivityTest : WiredActivityTestCase() {
    private val file = "文件引用（未读取）：content://documents/document/image%3A100"
    private val folder = "目录引用（未读取）：content://documents/tree/primary%3A中文目录"

    fun testReferenceOnlyKeepsOneEnabledSendAndSendsExactPayload() {
        val raw = "$file\n$folder"
        var sent: String? = null
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                ChatMenuLayer { ChatComposer(ComposerUi(text = raw), ChatScreenActions(onSend = { sent = it }),
                    Modifier.requiredWidth(320.dp)) }
            }
        } }
        node("chat-draft").assertEditableText("")
        node("chat-reference:0").assertIsDisplayed()
        ui.onNodeWithText("content://", substring = true, useUnmergedTree = true).assertDoesNotExist()
        node("chat-send").assertIsEnabled().performClick()
        assertEquals(raw, sent)
    }

    fun testBodyEditingAndLastChipRemovalKeepSeparateSelectionAndDisableEmptySend() {
        val state = mutableStateOf(ComposerUi(text = "正文😀\n$file"))
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                ChatMenuLayer { ChatComposer(state.value, ChatScreenActions(
                    onProjectedDraftEdit = { expected, next, start, end ->
                        if (state.value.text != expected) false else {
                            state.value = state.value.copy(text = next, selectionStart = start, selectionEnd = end)
                            true
                        }
                    }), Modifier.requiredWidth(320.dp)) }
            }
        } }
        node("chat-draft").assertEditableText("正文😀")
        node("chat-draft").performSemanticsAction(SemanticsActions.SetText) { assertTrue(it(AnnotatedString(""))) }
        node("chat-draft").assertEditableText("")
        assertEquals(file, state.value.text)
        node("chat-send").assertIsEnabled()
        node("chat-reference-remove:0").performClick()
        node("chat-reference-chips").assertDoesNotExist()
        assertEquals("", state.value.text)
        node("chat-send").assertIsNotEnabled()
    }

    fun testLongNameStaysWithinComposerAndRemovalDoesNotOverlapMainActions() {
        val raw = "路径引用（未读取）：/workspace/" + "中文文件名".repeat(80) + ".txt"
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                ChatMenuLayer { ChatComposer(ComposerUi(text = raw), ChatScreenActions(), Modifier.requiredWidth(320.dp)) }
            }
        } }
        val surface = node("chat-composer-surface").fetchSemanticsNode().boundsInRoot
        val chip = node("chat-reference:0").fetchSemanticsNode().boundsInRoot
        val remove = node("chat-reference-remove:0").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val send = node("chat-send").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(chip.left >= surface.left && chip.right <= surface.right)
        assertTrue(remove.bottom <= send.top)
        node("chat-draft").assertEditableText("")
    }
}
