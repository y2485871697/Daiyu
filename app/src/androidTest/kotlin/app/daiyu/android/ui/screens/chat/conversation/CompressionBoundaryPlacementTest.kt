package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.timeline.*
import app.daiyu.android.ui.app.AgentAppTheme

class CompressionBoundaryPlacementTest : WiredActivityTestCase() {
    fun testRunningAndAcceptedNoticeRemainBetweenTheSameRequestsWhileLaterMessagesAppend() {
        val anchor = TimelineAnchor("c", "r", "before", 0)
        val after = TimelineAnchor("c", "r", "after", 0)
        val live = CompressionActivity("compression", "c", 4, null, anchor = anchor)
        var entries by mutableStateOf<List<ChatTimelineEntry>>(emptyList())
        var turns by mutableStateOf<List<ChatTurnUi>>(listOf(
            ChatTurnUi.User("before", "previous request", listKey = anchor.listKey),
            ChatTurnUi.User("after", "next request", listKey = after.listKey)))
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            ChatMessageList(turns, ChatScreenActions(), timeline = entries, compressionActivities = listOf(live))
        } } }
        fun assertBetween(label: String) {
            val before = ui.onNodeWithText("previous request").fetchSemanticsNode().boundsInRoot
            val notice = ui.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
            val next = ui.onNodeWithText("next request").fetchSemanticsNode().boundsInRoot
            assertTrue("notice must follow previous request", notice.top >= before.bottom)
            assertTrue("notice must precede next request", notice.bottom <= next.top)
        }
        assertBetween("主代理 • 正在压缩上下文")
        mainThread { entries = listOf(ChatTimelineEntry.Compression(live.id, anchor, 1, "summary", null, 4, null, null)) }
        ui.onNodeWithText("主代理 · 已压缩 4 条消息").assertIsDisplayed()
        ui.onNodeWithText("主代理 • 正在压缩上下文").assertDoesNotExist()
        assertBetween("主代理 · 已压缩 4 条消息")
        mainThread { turns = turns + ChatTurnUi.User("later", "later request") }
        assertBetween("主代理 · 已压缩 4 条消息")
    }
}
