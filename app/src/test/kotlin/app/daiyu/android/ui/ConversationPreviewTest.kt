package app.daiyu.android.ui

import app.daiyu.android.context.ContextService
import app.daiyu.android.context.PrivateBlobs
import app.daiyu.android.domain.*
import app.daiyu.android.storage.ConversationDatabase
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [26, 30])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ConversationPreviewTest {
    private lateinit var database: ConversationDatabase
    private lateinit var data: RetainedConversationData

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("conversations.db")
        database = ConversationDatabase(context)
        data = RetainedConversationData(database, ContextService(database, PrivateBlobs(context)))
    }

    @After fun cleanup() {
        database.close()
        RuntimeEnvironment.getApplication().deleteDatabase("conversations.db")
    }

    private fun seed(blocks: List<MessageBlock>, complete: Boolean = true): Pair<ConversationId, Message> {
        val owner = database.create("default").id
        val user = Message(MessageId("user-${owner.value}"), owner, RunId("run-${owner.value}"),
            Message.USER_ROLE, listOf(MessageBlock.Text("question", "question")), RunStatus.COMPLETED)
        val reply = user.copy(id = MessageId("reply-${owner.value}"), role = Message.ASSISTANT_ROLE,
            status = RunStatus.RUNNING, blocks = blocks)
        database.send(user, reply)
        val saved = if (complete) reply.copy(status = RunStatus.COMPLETED).also(database::save) else reply
        return owner to saved
    }

    @Test fun paneReadsOnlyDisplayBlocksAndLeavesLargeProtocolAndHistoryUntouched() {
        val (owner, reply) = seed(listOf(MessageBlock.Text("one", "first"),
            MessageBlock.Tool(ToolInvocation(ToolCallId("tool"), "observe_screen", "{}"),
                "x".repeat(2 * 1024 * 1024 + 7)), MessageBlock.Text("two", "second")))
        val db = database.writableDatabase
        val original = app.daiyu.android.storage.SQLiteText.read(db, "messages", "blocks", "id", reply.id.value)
        db.execSQL("UPDATE messages SET protocol='not-json',history_segments='not-json' WHERE id=?", arrayOf(reply.id.value))
        assertEquals("first second", data.paneSnapshot(null).conversations.single { it.id == owner.value }.preview)
        assertEquals("first second", data.summaries(null).single { it.id == owner.value }.preview)
        assertTrue(runCatching { database.messages(owner) }.exceptionOrNull() is org.json.JSONException)
        assertEquals(original, app.daiyu.android.storage.SQLiteText.read(db, "messages", "blocks", "id", reply.id.value))
        db.rawQuery("SELECT protocol,history_segments FROM messages WHERE id=?", arrayOf(reply.id.value)).use {
            assertTrue(it.moveToFirst())
            assertEquals("not-json", it.getString(0)); assertEquals("not-json", it.getString(1))
        }
    }

    @Test fun previewKeepsTextOrderEmptySeparatorsAndTheExisting160CharacterLimit() {
        for (texts in listOf(listOf("", "a".repeat(159), "数🙂end"), listOf("a", "", "b"),
            listOf("a".repeat(159) + "🙂"), listOf("数🙂".repeat(10_000)))) {
            val (owner, _) = seed(texts.mapIndexed { index, text -> MessageBlock.Text("text-$index", text) })
            assertEquals(texts.joinToString(" ").take(160), data.summaries(null).single { it.id == owner.value }.preview)
        }
    }

    @Test fun latestMessageUpdatesAndRemovalDoNotReuseStalePreview() {
        val (owner, reply) = seed(listOf(MessageBlock.Text("text", "before")), complete = false)
        assertEquals("before", data.summaries(null).single { it.id == owner.value }.preview)
        database.save(reply.copy(blocks = listOf(MessageBlock.Text("text", "after"))))
        assertEquals("after", data.summaries(null).single { it.id == owner.value }.preview)
        database.writableDatabase.delete("messages", "conversation_id=?", arrayOf(owner.value))
        assertEquals("", data.summaries(null).single { it.id == owner.value }.preview)
        database.delete(owner)
        assertTrue(data.summaries(null).isEmpty())
    }

    @Test fun toolOnlyAndEmptyConversationsHaveNoInventedPreview() {
        val empty = database.create("default").id
        val (tool, _) = seed(listOf(MessageBlock.Tool(ToolInvocation(ToolCallId("tool"), "command", "{}"), "not preview")))
        val rows = data.summaries(null).associateBy { it.id }
        assertEquals("", rows.getValue(empty.value).preview)
        assertEquals("", rows.getValue(tool.value).preview)
    }
}
