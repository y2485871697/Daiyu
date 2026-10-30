package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import app.daiyu.android.MarkdownBlock
import app.daiyu.android.MarkdownInline
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme

/** Counts traversal, not frame duration; this fixture needs Android instrumentation. */
class MarkdownRevealTraversalTest : WiredActivityTestCase() {
    private class CountingList<T>(private val items: List<T>) : AbstractList<T>() {
        var reads = 0
        override val size: Int get() = items.size
        override fun get(index: Int): T { reads++; return items[index] }
    }

    fun testRevealDoesNotVisitTheHiddenBlockTail() {
        val blocks = CountingList(List(500) { MarkdownBlock.Paragraph(listOf(MarkdownInline.Text("body $it"))) })
        val visible = mutableStateOf(1)
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                ParsedMessageMarkdown("unused", blocks, visibleCharacters = visible.value)
            }
        } }
        ui.waitForIdle()
        mainThread { blocks.reads = 0; visible.value = 2 }
        ui.waitForIdle()
        assertTrue("Hidden blocks must not be traversed on every reveal frame: ${blocks.reads}", blocks.reads < 20)
        ui.onNodeWithText("bo").assertIsDisplayed()
    }

    fun testRevealDoesNotVisitTheHiddenListItemTail() {
        val items = CountingList(List(500) {
            MarkdownBlock.Item(listOf(MarkdownBlock.Paragraph(listOf(MarkdownInline.Text("body $it")))))
        })
        val blocks = listOf(MarkdownBlock.Items(false, 1, items))
        val visible = mutableStateOf(1)
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                ParsedMessageMarkdown("unused", blocks, visibleCharacters = visible.value)
            }
        } }
        ui.waitForIdle()
        mainThread { items.reads = 0; visible.value = 2 }
        ui.waitForIdle()
        assertTrue("Hidden list items must not be traversed on every reveal frame: ${items.reads}", items.reads < 20)
        ui.onNodeWithText("bo").assertIsDisplayed()
    }
}
