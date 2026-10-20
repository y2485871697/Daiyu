package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.ModelEvent
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.chat.conversation.ChatUsageDialog
import java.util.concurrent.TimeUnit

class ChatUsageDialogTest : WiredActivityTestCase() {
    private fun recordUsage(input: Long, output: Long): ConversationId {
        val session = fixture.session()
        val conversation = checkNotNull(fixture.controller.send(null, "usage fixture"))
        try {
            assertTrue(session.entered.await(5, TimeUnit.SECONDS))
            checkNotNull(session.sink.get())(ModelEvent.Usage(input, output, 0))
        } finally { session.release.countDown() }
        awaitControl("usage fixture completed") { fixture.controller.active == null && !fixture.controller.dataBusy }
        assertEquals(input, fixture.controller.recordedRequestUsage(conversation.id).summary.combined.input.reportedTokens)
        return conversation.id
    }

    private fun show(id: ConversationId?, mode: AppearanceThemeMode) {
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(themeMode = mode), applyInterfaceScale = false) {
                var open by remember(id, mode) { mutableStateOf(true) }
                if (open) ChatUsageDialog(fixture.controller, id) { open = false }
            }
        } }
        ui.waitForIdle()
        ui.waitUntil(5_000) {
            ui.onAllNodesWithText("正在读取已记录用量…").fetchSemanticsNodes().isEmpty()
        }
        ui.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("chat-usage-dialog"))).assertCountEquals(0)
        for (label in listOf("刷新", "关闭", "统计对象", "统计对象：全部会话", "统计对象：本会话", "详细用量")) {
            ui.onNodeWithText(label).assertDoesNotExist()
        }
    }

    fun testUsageIsConversationOnlyAndUnattachedOrEmptyChatNeverFallsBackToAll() {
        val first = recordUsage(17, 23)
        val second = recordUsage(61, 79)
        val empty = fixture.database.create("default").id
        val before = fixture.database.exportBackup()
        val opens = fixture.opens.get()
        assertEquals(78L, fixture.controller.recordedRequestUsage().summary.combined.input.reportedTokens)
        for (mode in listOf(AppearanceThemeMode.LIGHT, AppearanceThemeMode.DARK)) {
            for ((id, input) in listOf(first to "17", second to "61")) {
                show(id, mode)
                ui.onNodeWithText(input).assertIsDisplayed()
                ui.onNodeWithText("78").assertDoesNotExist()
                ui.onNodeWithText("暂无用量").assertDoesNotExist()
            }
            for (id in listOf(null, empty, ConversationId("missing-usage-conversation"))) {
                show(id, mode)
                val text = ui.onNodeWithText("暂无用量").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val body = node("chat-usage-empty").fetchSemanticsNode().boundsInRoot
                assertEquals(body.center.x, text.center.x, 1f)
                assertEquals(body.center.y, text.center.y, 1f)
                ui.onNodeWithText("输入 Token").assertDoesNotExist()
                ui.onNodeWithText("已报告小计；缺报显示 —").assertDoesNotExist()
            }
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            ui.waitForIdle()
            node("chat-usage-dialog").assertDoesNotExist()
        }
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(opens, fixture.opens.get())
    }
}
