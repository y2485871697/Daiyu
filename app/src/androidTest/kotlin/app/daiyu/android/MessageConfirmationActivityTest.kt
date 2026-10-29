package app.daiyu.android

import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
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

import kotlin.math.ceil
import kotlin.math.floor
import top.yukonga.miuix.kmp.theme.MiuixTheme

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

            )
            mainThread { activity.setContent {
                AgentAppTheme(appearance, applyInterfaceScale = false) {
                    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
                        DaiyuConfirmDialog("确认操作", "", "确定", destructive = false,
                            onConfirm = {}, onDismiss = {}, preview = true,
                            showCloseButton = false, secondaryCancel = true)
                    }
                }
            } }
            awaitUniqueTag("chat-selection-sheet")
            awaitNodeWindowFocus(node("chat-selection-sheet"))
            assertActions()
            assertButtonColors(dark)
        }
        assertEquals(0, fixture.opens.get())
    }

    private fun assertButtonColors(dark: Boolean) {
        val actions = resolveAppearanceActions(dark)
        val sheet = node("chat-selection-sheet")
        val pixels = captureScreenNode(sheet).toPixelMap()
        val sheetBounds = sheet.fetchSemanticsNode().boundsInRoot
        val cancelBounds = node("dialog-cancel").fetchSemanticsNode().boundsInRoot
        val confirmBounds = node("dialog-confirm").fetchSemanticsNode().boundsInRoot
        val originX = floor(sheetBounds.left)
        val originY = floor(sheetBounds.top)
        val leftX = ((sheetBounds.left + cancelBounds.left) * .5f - originX).toInt()
        val rightX = ((confirmBounds.right + sheetBounds.right) * .5f - originX).toInt()
        assertTrue(leftX >= 0 && rightX < pixels.width && leftX < rightX)
        fun near(actual: Color, expected: Color) =
            kotlin.math.abs(actual.red - expected.red) < .025f &&
                kotlin.math.abs(actual.green - expected.green) < .025f &&
                kotlin.math.abs(actual.blue - expected.blue) < .025f
        for ((tag, background, foreground) in listOf(
            Triple("dialog-confirm", actions.primary, actions.onPrimary),
            Triple("dialog-cancel", actions.secondaryContainer, actions.onSecondary))) {
            val bounds = node(tag).fetchSemanticsNode().boundsInRoot
            val left = floor(bounds.left - originX).toInt()
            val top = floor(bounds.top - originY).toInt()
            val right = ceil(bounds.right - originX).toInt()
            val bottom = ceil(bounds.bottom - originY).toInt()
            assertTrue(left > leftX && right <= rightX)
            assertTrue(top >= 0 && bottom <= pixels.height && top < bottom)
            var fill = 0
            var text = 0
            for (y in top until bottom) for (x in left until right) {
                val underlay = lerp(pixels[leftX, y], pixels[rightX, y],
                    (x - leftX).toFloat() / (rightX - leftX))
                if (near(pixels[x, y], background.compositeOver(underlay))) fill++
                if (near(pixels[x, y], foreground)) text++
            }
            val area = (right - left) * (bottom - top)
            assertTrue("$tag uses its theme background; dark=$dark fill=$fill area=$area", fill > area * .35f)
            assertTrue("$tag uses its paired theme foreground; dark=$dark text=$text", text > 8)
        }
    }
}
