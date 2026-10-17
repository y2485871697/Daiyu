package app.daiyu.android

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
        val expanded = shown().map { it.second.id }
        assertTrue(expanded.size > snapshot.size)
        val tail = fixture.database.messages(conversation.id).last().second
        fixture.database.save(tail.copy(status = RunStatus.COMPLETED,
            blocks = listOf(MessageBlock.Text("body", "new final answer"))))
        mainThread {
            call("refresh")
            call("applyAcceptedHistory", conversation, generation, request, content, snapshot)
        }
        assertEquals(expanded, shown().map { it.second.id })
        assertEquals(RunStatus.COMPLETED, shown().last().second.status)
        assertEquals("new final answer", shown().last().second.blocks.filterIsInstance<MessageBlock.Text>().single().text)
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
