package app.daiyu.android

import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import androidx.compose.runtime.State
import app.daiyu.android.domain.*
import app.daiyu.android.ui.screens.chat.conversation.ChatMenuAction
import app.daiyu.android.ui.screens.chat.conversation.ChatScreenState

/** Inject a late IO snapshot into the real Activity consumer, after real window/final updates. */
class AcceptedHistoryActivityTest : WiredActivityTestCase() {
    private fun field(name: String): Any? = MainActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)
    private fun call(name: String, vararg args: Any?) = MainActivity::class.java.declaredMethods
        .single { it.name == name && it.parameterCount == args.size }.apply { isAccessible = true }
        .invoke(activity, *args)
    @Suppress("UNCHECKED_CAST")
    private fun shown() = (field("shown") as List<Pair<Long, Message>>).toList()
    private fun contentRevision() = ((field("chatState\$delegate") as State<*>).value as ChatScreenState).contentRevision

    private fun setField(name: String, value: Any?) = MainActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.set(activity, value)

    private fun awaitShown(predicate: (List<Pair<Long, Message>>) -> Boolean) {
        ui.waitUntil(timeoutMillis = 5_000) {
            var matches = false
            mainThread { matches = predicate(shown()) }
            matches
        }
    }

    private fun seedHistory(prefix: String, count: Int): Conversation {
        val conversation = fixture.database.create("default")
        repeat(count) { index ->
            val run = RunId("$prefix-$index")
            fixture.database.send(
                Message(MessageId("$prefix-user-$index"), conversation.id, run, Message.USER_ROLE,
                    listOf(MessageBlock.Text("body", "user $index")), RunStatus.COMPLETED),
                Message(MessageId("$prefix-answer-$index"), conversation.id, run, Message.ASSISTANT_ROLE,
                    listOf(MessageBlock.Text("body", "answer $index")), RunStatus.RUNNING))
        }
        return conversation
    }

    fun testControllerBurstIsCoalescedAndRemovedObserverIsNotDelivered() {
        val calls = AtomicInteger()
        val retiredCalls = AtomicInteger()
        val observer: () -> Unit = { calls.incrementAndGet(); Unit }
        val retired: () -> Unit = { retiredCalls.incrementAndGet(); Unit }
        val notify = ChatController::class.java.getDeclaredMethod("notifyChanged").apply { isAccessible = true }
        try {
            mainThread {
                fixture.controller.observe(observer)
                fixture.controller.observe(retired)
                repeat(100) { notify.invoke(fixture.controller) }
                assertEquals(0, calls.get())
                fixture.controller.unobserve(retired)
            }
            awaitControl("coalesced notification") { calls.get() > 0 }
            assertEquals(1, calls.get())
            assertEquals(0, retiredCalls.get())
        } finally {
            mainThread { fixture.controller.unobserve(observer); fixture.controller.unobserve(retired) }
        }
    }

    fun testStreamingDeltasDoNotReloadHistoryAndFinalTextStillReconciles() {
        val session = fixture.session(" final", holdSeconds = 120)
        setDraft("stream refresh regression")
        mainThread { retainedControls(activity).onSend("stream refresh regression") }
        awaitTextSendResult(activity)
        assertTrue(session.entered.await(5, TimeUnit.SECONDS))
        val owner = checkNotNull(fixture.controller.active)
        awaitShown { rows -> rows.any { it.second.id == owner.id } }
        ui.waitUntil(5_000) {
            var idle = false
            mainThread { idle = field("refreshHistoryLoading") == false && field("refreshHistoryPending") == false }
            idle
        }
        @Suppress("UNCHECKED_CAST")
        val original = field("loadedWindowReader") as (ConversationId, Long, Int) -> List<Pair<Long, Message>>
        val calls = AtomicInteger()
        val reader: (ConversationId, Long, Int) -> List<Pair<Long, Message>> = { id, before, count ->
            calls.incrementAndGet(); original(id, before, count)
        }
        try {
            mainThread { setField("loadedWindowReader", reader) }
            repeat(12) { index ->
                checkNotNull(session.sink.get())(ModelEvent.TextDelta("body", "delta$index "))
                awaitShown { rows -> rows.any { it.second.id == owner.id &&
                    it.second.blocks.filterIsInstance<MessageBlock.Text>().any { block -> block.text.contains("delta$index ") } } }
            }
            assertEquals("live deltas must not decode the whole history window", 0, calls.get())
            session.release.countDown()
            awaitControl("stream completed") { fixture.controller.active == null }
            awaitShown { rows -> rows.any { it.second.id == owner.id && it.second.status == RunStatus.COMPLETED &&
                it.second.blocks.filterIsInstance<MessageBlock.Text>().any { block -> block.text.endsWith(" final") } } }
            assertTrue("terminal state must reconcile stored history", calls.get() > 0)
        } finally {
            session.release.countDown()
            mainThread { setField("loadedWindowReader", original) }
        }
    }

    fun testRefreshReadsOffMainSingleFlightAndRetainsExpandedWindow() {
        val conversation = seedHistory("refresh-window", 70)
        mainThread { activity.select(conversation) }
        @Suppress("UNCHECKED_CAST")
        val original = field("loadedWindowReader") as (ConversationId, Long, Int) -> List<Pair<Long, Message>>
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val intercept = AtomicBoolean(true)
        val onMain = AtomicBoolean(false)
        val calls = AtomicInteger()
        val reader: (ConversationId, Long, Int) -> List<Pair<Long, Message>> = { id, before, count ->
            calls.incrementAndGet()
            val snapshot = original(id, before, count)
            if (intercept.compareAndSet(true, false)) {
                onMain.set(Looper.myLooper() == Looper.getMainLooper())
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            snapshot
        }
        try {
            mainThread { setField("loadedWindowReader", reader); call("refresh") }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(onMain.get())
            var expanded = emptyList<MessageId>()
            mainThread {
                repeat(12) { call("refresh") }
                retainedControls(activity).onMenuAction(ChatMenuAction.LOAD_EARLIER)
                expanded = shown().map { it.second.id }
            }
            assertEquals("pending refreshes must not launch parallel readers", 1, calls.get())
            val tail = fixture.database.messages(conversation.id).last().second
            fixture.database.save(tail.copy(status = RunStatus.COMPLETED,
                blocks = listOf(MessageBlock.Text("body", "refresh final"))))
            release.countDown()
            awaitShown { rows -> rows.map { it.second.id } == expanded && rows.last().second.status == RunStatus.COMPLETED &&
                rows.last().second.blocks.filterIsInstance<MessageBlock.Text>().single().text == "refresh final" }
        } finally {
            release.countDown()
            mainThread { setField("loadedWindowReader", original) }
        }
    }

    fun testLateRefreshCannotOverwriteReselectedOrDifferentEditor() {
        for (sameConversation in listOf(false, true)) {
            val old = seedHistory("refresh-old-$sameConversation", 2)
            val current = if (sameConversation) old else seedHistory("refresh-new", 2)
            mainThread { activity.select(old) }
            @Suppress("UNCHECKED_CAST")
            val original = field("loadedWindowReader") as (ConversationId, Long, Int) -> List<Pair<Long, Message>>
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val intercept = AtomicBoolean(true)
            val reader: (ConversationId, Long, Int) -> List<Pair<Long, Message>> = { id, before, count ->
                val snapshot = original(id, before, count)
                if (intercept.compareAndSet(true, false)) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                snapshot
            }
            try {
                mainThread { setField("loadedWindowReader", reader); call("refresh") }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                mainThread {
                    activity.select(current)
                    retainedControls(activity).onTextChange("new editor text")
                    call("refresh")
                }
                val finalText = "reselected final $sameConversation"
                val tail = fixture.database.messages(current.id).last().second
                fixture.database.save(tail.copy(status = RunStatus.COMPLETED,
                    blocks = listOf(MessageBlock.Text("body", finalText))))
                release.countDown()
                awaitShown { rows -> rows.isNotEmpty() && rows.all { it.second.conversationId == current.id } &&
                    rows.last().second.blocks.filterIsInstance<MessageBlock.Text>().single().text == finalText }
                ui.waitUntil(timeoutMillis = 5_000) {
                    var idle = false
                    mainThread { idle = field("refreshHistoryLoading") == false && field("refreshHistoryPending") == false }
                    idle
                }
                assertDraft("new editor text")
                assertEquals(current.id.value, fixture.controller.preferences.getString("selected", null))
            } finally { release.countDown(); mainThread { setField("loadedWindowReader", original) } }
        }
    }

    fun testSelectionOnlyChangeRetainsTurnsAndContentRevision() {
        val conversation = fixture.database.create("default")
        val run = RunId("selection-only-run")
        val answer = Message(MessageId("selection-only-answer"), conversation.id, run,
            Message.ASSISTANT_ROLE, listOf(MessageBlock.Text("body", "retained answer")), RunStatus.RUNNING)
        fixture.database.send(Message(MessageId("selection-only-user"), conversation.id, run,
            Message.USER_ROLE, listOf(MessageBlock.Text("body", "retained question")), RunStatus.COMPLETED), answer)
        fixture.database.save(answer.copy(status = RunStatus.COMPLETED))
        mainThread { activity.select(conversation) }
        setDraft("selection-only text")
        ui.waitForIdle()
        lateinit var before: ChatScreenState
        mainThread {
            before = (field("chatState\$delegate") as State<*>).value as ChatScreenState
            assertTrue(before.turns.isNotEmpty())
        }
        setSelection(2, 7)
        mainThread {
            fun state() = (field("chatState\$delegate") as State<*>).value as ChatScreenState
            val after = state()
            assertSame(before.turns, after.turns)
            assertEquals(before.contentRevision, after.contentRevision)
            assertSame(before.scrollRequest, after.scrollRequest)
            assertEquals(2, after.composer.selectionStart)
            assertEquals(7, after.composer.selectionEnd)
            retainedControls(activity).onSelectionChange(2, 7)
            assertSame(after, state())
        }
        assertSelection(2, 7)
        assertDraft("selection-only text")
    }

    fun testTextOnlyChangeRetainsTurnsAndContentRevisionAndPersistsDraft() {
        val conversation = fixture.database.create("default")
        val run = RunId("text-only-run")
        val answer = Message(MessageId("text-only-answer"), conversation.id, run,
            Message.ASSISTANT_ROLE, listOf(MessageBlock.Text("body", "retained answer")), RunStatus.RUNNING)
        fixture.database.send(Message(MessageId("text-only-user"), conversation.id, run,
            Message.USER_ROLE, listOf(MessageBlock.Text("body", "retained question")), RunStatus.COMPLETED), answer)
        fixture.database.save(answer.copy(status = RunStatus.COMPLETED))
        mainThread { activity.select(conversation) }
        ui.waitForIdle()
        mainThread {
            val before = (field("chatState\$delegate") as State<*>).value as ChatScreenState
            assertTrue(before.turns.isNotEmpty())
            retainedControls(activity).onTextChange("new draft without remapping history")
            val after = (field("chatState\$delegate") as State<*>).value as ChatScreenState
            assertSame(before.turns, after.turns)
            assertEquals(before.contentRevision, after.contentRevision)
            assertSame(before.scrollRequest, after.scrollRequest)
            assertEquals(before.composer.modelLabel, after.composer.modelLabel)
            assertEquals(before.composer.assistantLabel, after.composer.assistantLabel)
            assertEquals("new draft without remapping history", after.composer.text)
            assertEquals(after.composer.text, fixture.database.conversations().single { it.id == conversation.id }.draft)
        }
        assertDraft("new draft without remapping history")
    }

    fun testLateAcceptedHistoryCannotOverwriteExpandedWindowOrNewFinalText() {
        val conversation = fixture.database.create("default")
        repeat(70) { index ->
            val run = RunId("late-history-$index")
            fixture.database.send(
                Message(MessageId("late-user-$index"), conversation.id, run, Message.USER_ROLE,
                    listOf(MessageBlock.Text("body", "user $index")), RunStatus.COMPLETED),
                Message(MessageId("late-assistant-$index"), conversation.id, run, Message.ASSISTANT_ROLE,
                    listOf(MessageBlock.Text("body", "old answer $index")), RunStatus.RUNNING))
        }
        mainThread { activity.select(conversation) }
        lateinit var snapshot: List<Pair<Long, Message>>
        var generation = 0L; var request = 0L; var content = 0L
        mainThread {
            snapshot = shown()
            generation = field("editorGeneration") as Long
            request = field("historyRequestSerial") as Long
            content = contentRevision()
            retainedControls(activity).onMenuAction(ChatMenuAction.LOAD_EARLIER)
        }
        var expanded = emptyList<MessageId>()
        mainThread { expanded = shown().map { it.second.id } }
        assertTrue(expanded.size > snapshot.size)
        val tail = fixture.database.messages(conversation.id).last().second
        fixture.database.save(tail.copy(status = RunStatus.COMPLETED,
            blocks = listOf(MessageBlock.Text("body", "new final answer"))))
        mainThread {
            call("refresh")
            call("applyAcceptedHistory", conversation, generation, request, content, snapshot)
        }
        awaitShown { rows -> rows.map { it.second.id } == expanded && rows.last().second.status == RunStatus.COMPLETED &&
            rows.last().second.blocks.filterIsInstance<MessageBlock.Text>().single().text == "new final answer" }
    }

    fun testLateHistoryForAnotherEditorCannotReplaceCurrentConversation() {
        val old = fixture.database.create()
        val current = fixture.database.create()
        mainThread { activity.select(old) }
        val generation = field("editorGeneration") as Long
        val request = field("historyRequestSerial") as Long
        val content = contentRevision()
        mainThread {
            activity.select(current)
            retainedControls(activity).onTextChange("current text")
            call("applyAcceptedHistory", old, generation, request, content, emptyList<Pair<Long, Message>>())
        }
        assertDraft("current text")
        assertEquals(current.id.value, fixture.controller.preferences.getString("selected", null))
    }
}
