package app.daiyu.android

import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.*
import app.daiyu.android.domain.*
import app.daiyu.android.ui.screens.chat.conversation.ChatMenuAction
import app.daiyu.android.ui.screens.chat.conversation.ChatScreenState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MessageActionScrollActivityTest : WiredActivityTestCase() {
    private fun field(name: String): Any? = MainActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)
    private fun setField(name: String, value: Any?) = MainActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.set(activity, value)
    private fun call(name: String) = MainActivity::class.java.declaredMethods
        .single { it.name == name && it.parameterCount == 0 }.apply { isAccessible = true }.invoke(activity)
    private fun state() = call("getChatState") as ChatScreenState
    @Suppress("UNCHECKED_CAST")
    private fun shown() = (field("shown") as List<Pair<Long, Message>>).toList()
    private fun top(id: String) = ui.onNodeWithTag("chat-turn:$id").fetchSemanticsNode()
        .layoutInfo.coordinates.positionInRoot().y

    private fun append(conversation: Conversation, index: Int): Message {
        val user = Message(MessageId("scroll-user-$index"), conversation.id, RunId("scroll-run-$index"),
            Message.USER_ROLE, listOf(MessageBlock.Text("body", "history question $index")), RunStatus.COMPLETED)
        val answer = user.copy(id = MessageId("scroll-answer-$index"), role = Message.ASSISTANT_ROLE,
            blocks = listOf(MessageBlock.Text("body", "history answer $index")), status = RunStatus.RUNNING)
        fixture.database.send(user, answer)
        fixture.database.save(answer.copy(status = RunStatus.COMPLETED))
        return user
    }

    private fun mountHistory(): Conversation {
        val conversation = fixture.database.create("default")
        repeat(40) { append(conversation, it) }
        mainThread { activity.select(conversation) }
        awaitControl("initial snapshot") { field("messageActionSnapshot") != null && !fixture.controller.dataBusy }
        mainThread { retainedControls(activity).onMenuAction(ChatMenuAction.LOAD_EARLIER) }
        awaitControl("expanded snapshot") {
            shown().size == 80 && field("messageActionSnapshot") != null && field("refreshHistoryLoading") == false
        }
        ui.onNodeWithTag("chat-messages").performTouchInput { swipeDown() }
        ui.waitForIdle()
        ui.onNodeWithTag("chat-messages").scrollToDescendant(hasTestTag("chat-turn:scroll-user-8"))
        ui.waitForIdle()
        ui.onNodeWithTag("chat-turn:scroll-user-8").assertIsDisplayed()
        return conversation
    }

    fun testDeleteRetainsExpandedHistoryAndAnchorWhileReloadIsBlocked() {
        val conversation = mountHistory()
        lateinit var before: ChatScreenState
        val beforeTop = top("scroll-user-8")
        var first = 0L
        @Suppress("UNCHECKED_CAST")
        val original = field("loadedWindowReader") as (ConversationId, Long, Int) -> List<Pair<Long, Message>>
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val intercept = AtomicBoolean(true)
        val reader: (ConversationId, Long, Int) -> List<Pair<Long, Message>> = { id, end, count ->
            if (intercept.compareAndSet(true, false)) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
            original(id, end, count)
        }
        try {
            mainThread {
                before = state(); first = shown().first().first
                setField("loadedWindowReader", reader)
                retainedControls(activity).onDeleteTurn("scroll-user-30")
            }
            awaitUniqueTag("chat-selection-sheet")
            awaitNodeWindowFocus(node("chat-selection-sheet"))
            node("dialog-confirm").performTouchInput { click() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            ui.waitForIdle()
            mainThread {
                assertTrue("reload must not publish an empty list", state().turns.isNotEmpty())
                assertEquals(first, shown().first().first)
                assertEquals(before.scrollRequest, state().scrollRequest)
            }
            assertEquals(beforeTop, top("scroll-user-8"), 1f)
            release.countDown()
            awaitControl("deleted history applied") {
                shown().size == 60 && shown().none { it.second.id.value == "scroll-user-30" } &&
                    field("refreshHistoryLoading") == false
            }
            ui.waitForIdle()
            mainThread {
                assertEquals(first, shown().first().first)
                assertEquals(before.scrollRequest, state().scrollRequest)
            }
            assertEquals(beforeTop, top("scroll-user-8"), 1f)
            val persisted = fixture.database.messages(conversation.id, limit = 200)
            assertEquals(60, persisted.size)
            assertFalse(persisted.any { it.second.id.value == "scroll-user-30" })
            assertEquals(0, fixture.opens.get())
        } finally {
            release.countDown()
            mainThread { setField("loadedWindowReader", original) }
        }
    }

    fun testUncertainRefreshIncludesNewTerminalTailWithoutMovingAnchor() {
        val conversation = mountHistory()
        lateinit var before: ChatScreenState
        var first = 0L
        val beforeTop = top("scroll-user-8")
        mainThread { before = state(); first = shown().first().first }
        append(conversation, 40)
        assertNull(fixture.controller.active)
        mainThread {
            call("refreshMessageActionHistory")
            assertTrue(state().turns.isNotEmpty())
            assertEquals(before.scrollRequest, state().scrollRequest)
        }
        awaitControl("new terminal tail reconciled") {
            shown().size == 82 && shown().last().second.id.value == "scroll-answer-40" &&
                field("refreshHistoryLoading") == false
        }
        ui.waitForIdle()
        mainThread {
            assertEquals(first, shown().first().first)
            assertEquals(before.scrollRequest, state().scrollRequest)
            assertNull(field("refreshHistoryTailConversation"))
        }
        assertEquals(beforeTop, top("scroll-user-8"), 1f)
        assertEquals(0, fixture.opens.get())
    }
}
