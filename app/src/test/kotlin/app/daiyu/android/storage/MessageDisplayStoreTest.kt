package app.daiyu.android.storage

import app.daiyu.android.domain.*
import app.daiyu.android.ui.screens.chat.conversation.ChatTurnMapper
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
class MessageDisplayStoreTest {
    private lateinit var database: ConversationDatabase
    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("conversations.db")
        database = ConversationDatabase(context)
    }
    @After fun cleanup() {
        database.close()
        RuntimeEnvironment.getApplication().deleteDatabase("conversations.db")
    }
    private fun seed(): Pair<Message, Message> {
        val owner = database.create("default").id
        val user = Message(MessageId("user"), owner, RunId("run"), Message.USER_ROLE,
            listOf(MessageBlock.Text("q", "question")), RunStatus.COMPLETED)
        val answer = user.copy(id = MessageId("answer"), role = Message.ASSISTANT_ROLE,
            blocks = listOf(MessageBlock.Text("body", "first")), status = RunStatus.RUNNING)
        database.send(user, answer)
        database.displayMessages(owner)
        return user to answer
    }
    private fun count() = database.readableDatabase.rawQuery("SELECT count(*) FROM message_display", null).use {
        check(it.moveToFirst()); it.getInt(0)
    }
    @Test fun writesAndReopenKeepProtocolFreeDisplayAndExactTurnKeys() {
        val (user, initial) = seed()
        val supplement = user.copy(id = MessageId("supplement"), blocks = listOf(MessageBlock.Text("extra", "extra")))
        val owner = initial.copy(status = RunStatus.STOPPED,
            historySegments = listOf(MessageHistorySegment(initial.id, Message.ASSISTANT_ROLE, initial.blocks,
                listOf("x".repeat(999_999))), MessageHistorySegment(supplement.id, Message.USER_ROLE, supplement.blocks),
                MessageHistorySegment(initial.id, Message.ASSISTANT_ROLE, listOf(MessageBlock.Text("end", "last")))))
        database.save(owner)
        val expected = ChatTurnMapper.map(database.messages(user.conversationId).map { it.second })
        val display = database.displayMessages(user.conversationId)
        assertEquals(expected, ChatTurnMapper.mapDisplay(display))
        assertEquals(2, count())
        database.readableDatabase.rawQuery("SELECT payload FROM message_display WHERE message_id='answer'", null).use {
            assertTrue(it.moveToFirst()); assertFalse(it.getString(0).contains("x".repeat(100)))
        }
        database.close()
        database = ConversationDatabase(RuntimeEnvironment.getApplication())
        assertEquals(display, database.displayMessages(user.conversationId))
    }
    @Test fun legacyMissSkipsProtocolAndNeverChangesCanonicalBytes() {
        val (user, answer) = seed()
        val db = database.writableDatabase
        db.execSQL("UPDATE messages SET protocol='not-json',history_segments=? WHERE id=?", arrayOf(
            """[{"message_id":"answer","role":"assistant","blocks":[{"type":"text","item":"body","text":"legacy"}],"protocol":["${"z".repeat(2_100_000)}"]}]""", answer.id.value))
        assertEquals(1, count())
        val raw = SQLiteText.read(db, "messages", "history_segments", "id", answer.id.value)
        val display = database.displayMessages(user.conversationId)
        assertEquals("legacy", (display.rows.last().second.segments.single().blocks.single() as MessageBlock.Text).text)
        assertEquals(raw, SQLiteText.read(db, "messages", "history_segments", "id", answer.id.value))
        assertTrue(runCatching { database.messages(user.conversationId) }.isFailure)
        assertEquals(2, count())
        assertEquals(display, database.displayMessages(user.conversationId))
    }
    @Test fun directUpdatesRollbackDeleteAndIdentityReuseInvalidateSameTransaction() {
        val (user, answer) = seed()
        val db = database.writableDatabase
        db.beginTransaction()
        try { db.execSQL("UPDATE messages SET status='STOPPED' WHERE id=?", arrayOf(answer.id.value)); assertEquals(1, count()) }
        finally { db.endTransaction() }
        assertEquals(2, count())
        db.execSQL("UPDATE messages SET status='STOPPED',protocol='not-json' WHERE id=?", arrayOf(answer.id.value))
        assertEquals(1, count())
        assertEquals(RunStatus.STOPPED, database.displayMessages(user.conversationId).rows.last().second.status)
        db.delete("messages", "id=?", arrayOf(answer.id.value))
        assertEquals(1, count())
        assertEquals(listOf(user.id), database.displayMessages(user.conversationId).rows.map { it.second.id })
    }
    @Test fun schema31UpgradeInstallsEmptyCacheWithoutRewritingHistory() {
        val (user, answer) = seed()
        val db = database.writableDatabase
        val original = SQLiteText.read(db, "messages", "blocks", "id", answer.id.value)
        listOf("insert", "update", "delete").forEach { db.execSQL("DROP TRIGGER message_display_$it") }
        db.execSQL("DROP TABLE message_display")
        db.version = 31
        database.close()
        database = ConversationDatabase(RuntimeEnvironment.getApplication())
        assertEquals(0, count())
        assertEquals(original, SQLiteText.read(database.readableDatabase, "messages", "blocks", "id", answer.id.value))
        assertEquals(2, database.displayMessages(user.conversationId).rows.size)
        assertEquals(32, database.readableDatabase.version)
    }
    @Test fun damagedAndWrongOwnerCacheRebuildFromCanonicalOnce() {
        val (user, answer) = seed()
        val db = database.writableDatabase
        db.execSQL("UPDATE message_display SET payload='broken' WHERE message_id=?", arrayOf(answer.id.value))
        assertEquals("first", (database.displayMessages(user.conversationId).rows.last().second.blocks.single() as MessageBlock.Text).text)
        db.execSQL("UPDATE message_display SET payload=(SELECT payload FROM message_display WHERE message_id=?) WHERE message_id=?",
            arrayOf(user.id.value, answer.id.value))
        assertEquals("first", (database.displayMessages(user.conversationId).rows.last().second.blocks.single() as MessageBlock.Text).text)
    }

    @Test fun legalExternalTriggerRewriteCannotBeReplacedByCallerCache() {
        val (user, answer) = seed()
        val db = database.writableDatabase
        db.execSQL("""CREATE TRIGGER rewrite_display_test AFTER UPDATE ON messages WHEN NEW.id='answer' BEGIN
            UPDATE messages SET blocks='[{"type":"text","item":"body","text":"trigger"}]' WHERE id=NEW.id;
            END""")
        database.save(answer.copy(status = RunStatus.STOPPED))
        assertEquals("trigger", (database.displayMessages(user.conversationId).rows.last().second.blocks.single() as MessageBlock.Text).text)
        assertEquals("trigger", (database.messages(user.conversationId).last().second.blocks.single() as MessageBlock.Text).text)
    }

}
