package app.daiyu.android

import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.domain.*
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.app.resolveAppearanceActions
import app.daiyu.android.ui.components.DaiyuConfirmDialog
import java.util.UUID

class MessageConfirmationActivityTest : WiredActivityTestCase() {
    private fun seed(): Pair<Conversation, List<Message>> {
        val conversation = fixture.database.create("default")
        val suffix = UUID.randomUUID().toString()
        val messages = (0..1).flatMap { index ->
            val user = Message(MessageId("user-$index-$suffix"), conversation.id, RunId("run-$index-$suffix"),
                Message.USER_ROLE, listOf(MessageBlock.Text("body", "question $index")), RunStatus.COMPLETED)
            val answer = user.copy(id = MessageId("answer-$index-$suffix"), role = Message.ASSISTANT_ROLE,
                blocks = listOf(MessageBlock.Text("body", "answer $index")), status = RunStatus.RUNNING)
            fixture.database.send(user, answer)
            val completed = answer.copy(status = RunStatus.COMPLETED)
            fixture.database.save(completed)
            listOf(user, completed)
        }
        mainThread { activity.select(conversation) }
        val snapshot = MainActivity::class.java.getDeclaredField("messageActionSnapshot").apply { isAccessible = true }
        awaitControl("message snapshot ready") {
            !fixture.controller.dataBusy && snapshot.get(activity) != null
        }
        return conversation to messages
    }

    private fun open(regenerate: Boolean, messages: List<Message>) {
        mainThread {
            if (regenerate) retainedControls(activity).onRegenerate(messages[1].id.value)
            else retainedControls(activity).onDeleteTurn(messages[2].id.value)
        }
        awaitUniqueTag("chat-selection-sheet")
        awaitNodeWindowFocus(node("chat-selection-sheet"))
        ui.onNodeWithText(if (regenerate) "重新生成将删除此回复及后面的消息" else "删除这条消息及后面的内容？")
            .assertIsDisplayed()
        assertActions()
    }

    private fun assertActions() {
        ui.onAllNodesWithText("取消").assertCountEquals(1)
        ui.onAllNodesWithText("确定").assertCountEquals(1)
        ui.onAllNodesWithContentDescription("关闭").assertCountEquals(0)
        ui.onAllNodesWithTag("prompt-choice:0").assertCountEquals(0)
        ui.onAllNodesWithTag("prompt-choice:1").assertCountEquals(0)
        node("dialog-cancel").assertIsDisplayed().assertHeightIsAtLeast(44.dp)
        node("dialog-confirm").assertIsDisplayed().assertHeightIsAtLeast(44.dp)
        val cancel = node("dialog-cancel").fetchSemanticsNode().boundsInRoot
        val confirm = node("dialog-confirm").fetchSemanticsNode().boundsInRoot
        assertEquals(cancel.width, confirm.width, 1f)
        assertEquals(cancel.top, confirm.top, 1f)
        assertTrue(cancel.right < confirm.left)
    }

    private fun awaitClosed() {
        ui.waitUntil(5_000) {
            ui.mainClock.advanceTimeByFrame()
            ui.onAllNodesWithTag("chat-selection-sheet").fetchSemanticsNodes().isEmpty()
        }
    }

    fun testBothConfirmationsCancelBackAndOutsideLeaveHistoryUnchanged() {
        val (_, messages) = seed()
        val before = fixture.database.exportBackup()
        for (regenerate in listOf(false, true)) for (action in listOf("cancel", "back", "outside")) {
            open(regenerate, messages)
            when (action) {
                "cancel" -> node("dialog-cancel").performTouchInput { click() }
                "back" -> instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                "outside" -> node("preview-dialog-viewport").performTouchInput { click(Offset(2f, 2f)) }
            }
            awaitClosed()
            assertEquals(before, fixture.database.exportBackup())
            assertEquals(0, fixture.opens.get())
        }
    }

    fun testDeleteConfirmKeepsEarlierMessagesAndCommitsOnce() {
        val (conversation, messages) = seed()
        open(false, messages)
        val oldConfirm = node("dialog-confirm").fetchSemanticsNode().config[SemanticsActions.OnClick].action
        node("dialog-confirm").performTouchInput { click() }
        mainThread { oldConfirm?.invoke() }
        awaitClosed()
        awaitControl("delete completed") {
            !fixture.controller.dataBusy && fixture.database.messages(conversation.id).size == 2
        }
        assertEquals(messages.take(2), fixture.database.messages(conversation.id).map { it.second })
        assertEquals(0, fixture.opens.get())
    }

    fun testRegenerateConfirmMakesOneRequestAndTruncatesLaterMessages() {
        val (conversation, messages) = seed()
        fixture.session("confirmed replacement", held = false)
        open(true, messages)
        assertEquals(0, fixture.opens.get())
        val oldConfirm = node("dialog-confirm").fetchSemanticsNode().config[SemanticsActions.OnClick].action
        node("dialog-confirm").performTouchInput { click() }
        mainThread { oldConfirm?.invoke() }
        awaitClosed()
        awaitControl("regeneration completed") {
            !fixture.controller.dataBusy && fixture.controller.active == null && fixture.opens.get() == 1
        }
        val stored = fixture.database.messages(conversation.id).map { it.second }
        assertEquals(2, stored.size)
        assertFalse(messages.first().runId == stored.first().runId)
        assertEquals(messages.first().copy(runId = stored.first().runId), stored.first())
        assertTrue(stored.last().blocks.filterIsInstance<MessageBlock.Text>().any { it.text == "confirmed replacement" })
        assertEquals(1, fixture.opens.get())
    }

    fun testThemePrimaryAndSecondaryMatchThePreviewInBothThemes() {
        for (dark in listOf(false, true)) {
            val appearance = AppearanceSettings(
                themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT,
                paletteSeedColor = 0xFF29445B.toInt(), primaryAccentColor = 0xFF3167A3.toInt(),
                secondaryAccentColor = 0xFF946922.toInt(),
            )
            mainThread { activity.setContent {
                AgentAppTheme(appearance, applyInterfaceScale = false) {
                    DaiyuConfirmDialog("确认操作", "", "确定", destructive = false,
                        onConfirm = {}, onDismiss = {}, preview = true,
                        showCloseButton = false, secondaryCancel = true)
                }
            } }
            awaitUniqueTag("chat-selection-sheet")
            awaitNodeWindowFocus(node("chat-selection-sheet"))
            assertActions()
            val actions = resolveAppearanceActions(appearance, dark)
            assertButtonColors("dialog-confirm", actions.primary, actions.onPrimary)
            assertButtonColors("dialog-cancel", actions.secondaryContainer, actions.onSecondary)
        }
        assertEquals(0, fixture.opens.get())
    }

    private fun assertButtonColors(tag: String, background: Color, foreground: Color) {
        val pixels = captureScreenNode(node(tag)).toPixelMap()
        fun near(actual: Color, expected: Color) =
            kotlin.math.abs(actual.red - expected.red) < .025f &&
                kotlin.math.abs(actual.green - expected.green) < .025f &&
                kotlin.math.abs(actual.blue - expected.blue) < .025f
        var fill = 0
        var text = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            if (near(pixels[x, y], background)) fill++
            if (near(pixels[x, y], foreground)) text++
        }
        assertTrue("$tag uses its theme background", fill > pixels.width * pixels.height * .35f)
        assertTrue("$tag uses its paired theme foreground", text > 8)
    }
}
