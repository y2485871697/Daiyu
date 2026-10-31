package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.domain.*
import app.daiyu.android.timeline.*
import app.daiyu.android.ui.app.AgentAppTheme

class ReconnectBoundaryPlacementTest : WiredActivityTestCase() {
    private val anchor = TimelineAnchor("c", "r", "a", 0)
    private fun message(text: String) = Message(MessageId("a"), ConversationId("c"), RunId("r"),
        Message.ASSISTANT_ROLE, listOf(MessageBlock.Text("body", text)), RunStatus.COMPLETED)
    private fun event(offset: Long, id: String = "one") = ChatTimelineEntry.Reconnect(id, anchor,
        1L, "running", 10L, "network", 0, "model", ReconnectPosition(offset))

    fun testAppendingAndSameIdStatusChangesKeepOneDividerBetweenPrefixAndSuffixWithOneAvatarAndOwnerActions() {
        var text by mutableStateOf("beforeafter")
        var entry by mutableStateOf(event(6))
        var copied: String? = null
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            ChatMessageList(ChatTurnMapper.map(listOf(message(text)), reconnects = listOf(entry)),
                ChatScreenActions(onCopy = { copied = it }), timeline = listOf(entry))
        } } }
        fun assertBetween(suffix: String) {
            ui.waitUntil(timeoutMillis = 5_000) {
                ui.onAllNodesWithText("before").fetchSemanticsNodes().size == 1 &&
                    ui.onAllNodesWithText(suffix).fetchSemanticsNodes().size == 1
            }
            val before = ui.onNodeWithText("before").fetchSemanticsNode().boundsInRoot
            val line = ui.onNodeWithTag("timeline:one").fetchSemanticsNode().boundsInRoot
            val after = ui.onNodeWithText(suffix).fetchSemanticsNode().boundsInRoot
            assertTrue(line.top >= before.bottom)
            assertTrue(line.bottom <= after.top)
            ui.onAllNodesWithTag("timeline:one").assertCountEquals(1)
            ui.onAllNodesWithText("助手").assertCountEquals(1)
            ui.onAllNodesWithContentDescription("复制回答").assertCountEquals(1)
        }
        assertBetween("after")
        for (state in listOf("stopped", "running", "succeeded")) {
            mainThread { entry = entry.copy(status = state) }
            assertBetween("after")
        }
        mainThread { text = "beforeafter appended" }
        assertBetween("after appended")
        ui.onNodeWithContentDescription("复制回答").performClick()
        assertEquals(anchor.listKey, copied)
    }

    fun testUnplacedOutOfRangeAndLegacyEventsAreStillRenderedOnce() {
        val lines = listOf(event(999), event(0, "legacy").copy(position = null))
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            ChatMessageList(ChatTurnMapper.map(listOf(message("short")), reconnects = lines),
                ChatScreenActions(), timeline = lines)
        } } }
        awaitUniqueTag("timeline:one")
        awaitUniqueTag("timeline:legacy")
        ui.onNodeWithTag("timeline:one").assertIsDisplayed()
        ui.onNodeWithTag("timeline:legacy").assertIsDisplayed()
    }
}
