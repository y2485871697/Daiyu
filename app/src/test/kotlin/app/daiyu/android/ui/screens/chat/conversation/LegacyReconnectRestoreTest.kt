package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.*
import app.daiyu.android.model.ResponsesSearchProtocol
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.storage.SQLiteText
import app.daiyu.android.timeline.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
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
class LegacyReconnectRestoreTest {
    private lateinit var database: ConversationDatabase
    private val plain = "**左😀右** [网站](https://example.test)"
    private val old = " [1](<https://source.test>)\u2060\\*\\*左😀右\\*\\* \\[网站\\]\\(https://example\\.test\\)\n\n来源：\n\n1. [Source](<https://source.test>)"
    private val expected = "$plain\n\n来源：\n\n1. [Source](<https://source.test>)"
    private data class Fixture(val conversation: ConversationId, val answer: Message, val protocol: String)

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("conversations.db")
        database = ConversationDatabase(context)
    }
    @After fun cleanup() {
        database.close()
        RuntimeEnvironment.getApplication().deleteDatabase("conversations.db")
    }
    private fun reopen() {
        database.close()
        database = ConversationDatabase(RuntimeEnvironment.getApplication())
    }
    private fun seed(changedWitness: Boolean = false, tail: Boolean = false): Fixture {
        val conversation = database.create("default").id
        val user = Message(MessageId("q"), conversation, RunId("run"), "user",
            listOf(MessageBlock.Text("q", "question")), RunStatus.COMPLETED)
        val answer = user.copy(id = MessageId("answer"), role = "assistant", status = RunStatus.RUNNING,
            blocks = listOf(MessageBlock.Text("body", "pending")))
        database.send(user, answer)
        database.displayMessages(conversation)
        val item = JSONObject().put("type", "message").put("id", "body").put("role", "assistant")
            .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", plain)
                .put("annotations", JSONArray().put(JSONObject().put("type", "url_citation")
                    .put("start_index", 0).put("end_index", 0).put("url", "https://source.test").put("title", "Source")))))
        assertEquals(old, ResponsesSearchProtocol.legacyDisplayText(item, plain))
        val block = JSONObject().put("type", "text").put("item", "body").put("text", old + if (changedWitness) "!" else "")
        val blocks = JSONArray().put(block)
        val protocol = JSONArray().put(item.toString())
        val history = JSONArray().put(JSONObject().put("message_id", "answer").put("role", "assistant")
            .put("blocks", blocks).put("protocol", protocol))
        if (tail) history.put(JSONObject().put("message_id", "answer").put("role", "assistant")
            .put("blocks", JSONArray().put(JSONObject().put("type", "text").put("item", "tail").put("text", "**tail end**")))
            .put("protocol", JSONArray()))
        database.writableDatabase.execSQL("UPDATE messages SET blocks=?,protocol=?,history_segments=?,status='COMPLETED' WHERE id='answer'",
            arrayOf(blocks.toString(), protocol.toString(), history.toString()))
        return Fixture(conversation, answer, item.toString())
    }
    private fun events(fixture: Fixture, cuts: List<Int>): List<ChatTimelineEntry.Reconnect> = cuts.mapIndexed { index, cut ->
        ChatTimelineEntry.Reconnect("cut-$index", TimelineAnchor(fixture.conversation.value, "run", "answer", 0),
            index.toLong(), "running", 10, "network", 0, "model", ReconnectPosition(cut.toLong())).also {
            ChatTimelineStore.write(database.writableDatabase, it)
        }
    }
    private fun authority(): List<String?> = listOf("blocks", "protocol", "history_segments").map {
        SQLiteText.read(database.readableDatabase, "messages", it, "id", "answer")
    }
    private fun eventBytes(events: List<ChatTimelineEntry.Reconnect>) = events.map {
        database.readableDatabase.rawQuery("SELECT payload FROM chat_timeline_events WHERE id=?", arrayOf(it.id)).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }
    }
    private suspend fun verify(fixture: Fixture, events: List<ChatTimelineEntry.Reconnect>, expected: String): List<ReconnectPart> {
        val loaded = database.messages(fixture.conversation).map { it.second }
        assertEquals(fixture.protocol, loaded.last().protocolItems.single())
        val window = database.displayMessages(fixture.conversation)
        val direct = ChatTurnMapper.map(loaded, reconnects = events)
        val displayed = ChatTurnMapper.mapDisplay(window, reconnects = events)
        assertEquals(direct, displayed)
        val turn = displayed.filterIsInstance<ChatTurnUi.Assistant>().single()
        val parts = reconnectParts(turn, events).parts
        val items = parts.flatMap { it.turn.content }.filterIsInstance<MessageContentUi.Text>()
        assertEquals(expected, items.joinToString("") { it.text })
        assertEquals(events, parts.flatMap { it.after })
        for (item in items) {
            val projection = projectMarkdown(listOf(item.text), windows = listOf(item.markdownWindow))
            assertNotNull(projection.blocksAt(0))
            assertEquals(item.text, item.markdownWindow!!.displaySource())
        }
        return parts
    }

    @Test fun coldSQLiteRestoreAndPersistentCacheHitPreserveEveryRawCut() = runBlocking {
        val fixture = seed()
        val events = events(fixture, listOf(0, 3, old.indexOf("右"), old.indexOf("来源") + 1, old.indexOf("Source") + 2, old.length))
        val original = authority(); val timeline = eventBytes(events)
        reopen()
        assertEquals(events, ChatTimelineStore.read(database.readableDatabase, fixture.conversation.value))
        verify(fixture, events, expected)
        val first = database.displayMessages(fixture.conversation)
        assertEquals(expected, (first.rows.last().second.blocks.single() as MessageBlock.Text).text)
        assertEquals(old, (first.rows.last().second.coordinateBlocks.single() as MessageBlock.Text).text)
        database.writableDatabase.execSQL("CREATE TRIGGER reject_display_rebuild BEFORE INSERT ON message_display BEGIN SELECT RAISE(ABORT,'unexpected rebuild'); END")
        reopen()
        assertEquals(first, database.displayMessages(fixture.conversation))
        verify(fixture, events, expected)
        assertEquals(original, authority()); assertEquals(timeline, eventBytes(events))
    }

    @Test fun oldV2CacheAndDirectUpdateRebuildWithoutEditingAuthority() = runBlocking {
        val fixture = seed()
        database.displayMessages(fixture.conversation)
        val db = database.writableDatabase
        val payload = JSONObject(requireNotNull(SQLiteText.read(db, "message_display", "payload", "message_id", "answer")))
        payload.put("version", 2).remove("projections")
        db.execSQL("UPDATE message_display SET payload=? WHERE message_id='answer'", arrayOf(payload.toString()))
        val events = events(fixture, listOf(old.indexOf("网站")))
        val original = authority(); val timeline = eventBytes(events)
        reopen()
        verify(fixture, events, expected)
        database.writableDatabase.execSQL("UPDATE messages SET blocks=blocks WHERE id='answer'")
        verify(fixture, events, expected)
        assertEquals(original, authority()); assertEquals(timeline, eventBytes(events))
    }

    @Test fun reconnectInsertedAfterProjectionUsesRawLengthsAcrossMergedSegments() = runBlocking {
        val fixture = seed(tail = true)
        database.displayMessages(fixture.conversation)
        val events = events(fixture, listOf(old.length + "**tail ".length))
        val original = authority(); val timeline = eventBytes(events)
        reopen()
        val parts = verify(fixture, events, expected + "**tail end**")
        val tail = parts.last().turn.content.filterIsInstance<MessageContentUi.Text>().last()
        assertEquals("end**", tail.text)
        assertEquals(original, authority()); assertEquals(timeline, eventBytes(events))
    }

    @Test fun unprovableWitnessRetainsItsExactRawDisplayAndCoordinates() = runBlocking {
        val fixture = seed(changedWitness = true)
        val events = events(fixture, listOf(old.indexOf("右")))
        val original = authority(); val timeline = eventBytes(events)
        reopen()
        verify(fixture, events, old + "!")
        assertEquals(original, authority()); assertEquals(timeline, eventBytes(events))
    }
}
