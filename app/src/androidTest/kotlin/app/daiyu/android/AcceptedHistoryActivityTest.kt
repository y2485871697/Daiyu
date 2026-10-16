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
