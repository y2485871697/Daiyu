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
    @Test fun sidebarDateAndTodayClassificationStaySeparateFromTheLastUpdate() {
        val owner = database.create("default").id
        val zone = java.time.ZoneId.systemDefault()
        val old = java.time.LocalDate.now(zone).minusDays(5).atStartOfDay(zone).toInstant().toEpochMilli()
        val updated = System.currentTimeMillis()
        database.writableDatabase.execSQL("UPDATE conversations SET sidebar_date=?,updated=? WHERE id=?",
            arrayOf<Any>(old, updated, owner.value))
        val first = data.summaries(null).single()
        assertEquals(old, first.dateGroupMillis)
        assertEquals(updated, first.updatedAtMillis)
        assertFalse(first.isToday)
        database.rename(owner, "renamed")
        database.organize(owner, "folder", true)
        val second = data.paneSnapshot(owner).conversations.single()
        assertEquals(old, second.dateGroupMillis)
        assertFalse(second.isToday)
        assertTrue(second.isPinned)
        assertTrue(second.isActiveRun)
        assertEquals("folder", second.folderId)
    }

    private fun timed(userAt: Long?, replyAt: Long?, status: RunStatus): Pair<ConversationId, Message> {
        val id = database.create("default").id
        val user = Message(MessageId("u-${id.value}"), id, RunId("r-${id.value}"), Message.USER_ROLE,
            listOf(MessageBlock.Text("q", "question")), RunStatus.COMPLETED, createdAt = userAt)
        val reply = user.copy(id = MessageId("a-${id.value}"), role = Message.ASSISTANT_ROLE,
            status = RunStatus.RUNNING, createdAt = replyAt)
        database.send(user, reply)
        if (status != RunStatus.RUNNING) database.save(reply.copy(status = status))
        return id to reply
    }

    @Test fun outputTimeSkipsOnlyRunningAndUsesLastSequenceNotLargestTimestamp() {
        val running = timed(500L, 900L, RunStatus.RUNNING).first
        val terminal = listOf(RunStatus.COMPLETED, RunStatus.STOPPED, RunStatus.FAILED, RunStatus.INTERRUPTED)
            .associateWith { timed(900L, 100L, it).first }
        val rows = data.summaries(null).associateBy { it.id }
        assertEquals(500L, rows.getValue(running.value).lastMessageAtMillis)
        for (owner in terminal.values) assertEquals(100L, rows.getValue(owner.value).lastMessageAtMillis)
        val first = terminal.getValue(RunStatus.COMPLETED)
        database.rename(first, "renamed")
        database.organize(first, "folder", true)
        assertEquals(100L, data.summaries(null).single { it.id == first.value }.lastMessageAtMillis)
    }

    @Test fun outputTimeKeepsUnknownTailUnknownAndZeroValidAndRefreshesAfterDeletion() {
        val unknown = timed(500L, null, RunStatus.COMPLETED).first
        val (zero, reply) = timed(500L, 0L, RunStatus.COMPLETED)
        val empty = database.create("default").id
        var rows = data.summaries(null).associateBy { it.id }
        assertNull(rows.getValue(unknown.value).lastMessageAtMillis)
        assertNull(rows.getValue(empty.value).lastMessageAtMillis)
        assertEquals(0L, rows.getValue(zero.value).lastMessageAtMillis)
        database.writableDatabase.delete("messages", "id=?", arrayOf(reply.id.value))
        rows = data.summaries(null).associateBy { it.id }
        assertEquals(500L, rows.getValue(zero.value).lastMessageAtMillis)
        database.close()
        val context = RuntimeEnvironment.getApplication()
        database = ConversationDatabase(context)
        data = RetainedConversationData(database, ContextService(database, PrivateBlobs(context)))
        assertEquals(500L, data.summaries(null).single { it.id == zero.value }.lastMessageAtMillis)
        assertNull(data.summaries(null).single { it.id == unknown.value }.lastMessageAtMillis)
    }

    @Test fun appendedUserDoesNotReplaceTheTimeOfTheFinalVisibleAssistantSegment() {
        val (id, reply) = timed(50L, 100L, RunStatus.RUNNING)
        val supplement = Message(MessageId("extra-${id.value}"), id, reply.runId, Message.USER_ROLE,
            listOf(MessageBlock.Text("extra", "more")), RunStatus.COMPLETED, createdAt = 300L)
        val db = database.writableDatabase
        db.beginTransaction()
        try { database.insertRuntimeSupplement(db, reply, supplement); db.setTransactionSuccessful() }
        finally { db.endTransaction() }
        assertEquals(300L, data.summaries(null).single().lastMessageAtMillis)
        val segments = listOf(
            MessageHistorySegment(reply.id, Message.ASSISTANT_ROLE, reply.blocks, createdAt = 100L),
            MessageHistorySegment(supplement.id, Message.USER_ROLE, supplement.blocks, createdAt = 300L),
            MessageHistorySegment(reply.id, Message.ASSISTANT_ROLE, listOf(MessageBlock.Text("final", "final")), createdAt = 100L))
        database.save(reply.copy(historySegments = segments.dropLast(1)))
        assertEquals(300L, data.summaries(null).single().lastMessageAtMillis)
        database.save(reply.copy(status = RunStatus.COMPLETED, historySegments = segments))
        assertEquals(100L, data.summaries(null).single().lastMessageAtMillis)
        assertEquals(100L, MessageHistory.expand(database.messages(id).map { it.second }).last().createdAt)
        val cached = db.rawQuery("SELECT created_at FROM conversation_output_times WHERE conversation_id=?", arrayOf(id.value)).use {
            assertTrue(it.moveToFirst()); it.getLong(0)
        }
        assertEquals(100L, cached)
        db.beginTransaction()
        try {
            db.delete("messages", "id=?", arrayOf(reply.id.value))
            assertEquals(300L, data.summaries(null).single().lastMessageAtMillis)
        } finally { db.endTransaction() }
        assertEquals(100L, data.summaries(null).single().lastMessageAtMillis)
    }

    @Test fun embeddedUserTailUsesItsOwnUnknownTimeAndOldDatabaseRebuildsProjectionWithoutChangingMessages() {
        val (id, reply) = timed(50L, 100L, RunStatus.RUNNING)
        val last = MessageHistorySegment(MessageId("embedded-extra"), Message.USER_ROLE,
            listOf(MessageBlock.Text("extra", "extra")))
        database.save(reply.copy(status = RunStatus.COMPLETED, historySegments = listOf(
            MessageHistorySegment(reply.id, Message.ASSISTANT_ROLE, reply.blocks, createdAt = 100L), last)))
        assertNull(data.summaries(null).single().lastMessageAtMillis)
        val db = database.writableDatabase
        val bytes = app.daiyu.android.storage.SQLiteText.read(db, "messages", "history_segments", "id", reply.id.value)
        for (suffix in listOf("insert", "update", "delete")) db.execSQL("DROP TRIGGER conversation_output_$suffix")
        db.execSQL("DROP TABLE conversation_output_times")
        database.close()
        val context = RuntimeEnvironment.getApplication()
        database = ConversationDatabase(context)
        data = RetainedConversationData(database, ContextService(database, PrivateBlobs(context)))
        assertEquals(35, database.readableDatabase.version)
        assertNull(data.summaries(null).single { it.id == id.value }.lastMessageAtMillis)
        assertEquals(bytes, app.daiyu.android.storage.SQLiteText.read(database.readableDatabase, "messages", "history_segments", "id", reply.id.value))
    }

    @Test fun independentLaterRunStillWinsWhenThePhysicalTailWasAbsorbedByAnEarlierOwner() {
        val (id, owner) = timed(50L, 100L, RunStatus.RUNNING)
        val later = owner.copy(id = MessageId("later-run"), runId = RunId("later-run"),
            blocks = listOf(MessageBlock.Text("later", "later")), createdAt = 200L, status = RunStatus.COMPLETED)
        val extra = owner.copy(id = MessageId("interleaved-extra"), role = Message.USER_ROLE,
            blocks = listOf(MessageBlock.Text("extra", "extra")), createdAt = 300L, status = RunStatus.COMPLETED)
        val db = database.writableDatabase
        // Exercise a valid imported row order that runtime sends do not normally produce.
        db.execSQL("INSERT INTO messages(id,conversation_id,run_id,role,blocks,status,protocol,created_at) " +
            "SELECT ?,conversation_id,?,'assistant',blocks,'COMPLETED','[]',? FROM messages WHERE id=?",
            arrayOf<Any>(later.id.value, later.runId.value, 200L, owner.id.value))
        db.beginTransaction()
        try { database.insertRuntimeSupplement(db, owner, extra); db.setTransactionSuccessful() }
        finally { db.endTransaction() }
        database.save(owner.copy(status = RunStatus.COMPLETED, historySegments = listOf(
            MessageHistorySegment(owner.id, Message.ASSISTANT_ROLE, owner.blocks, createdAt = 100L),
            MessageHistorySegment(extra.id, Message.USER_ROLE, extra.blocks, createdAt = 300L))))
        assertEquals(200L, MessageHistory.expand(database.messages(id).map { it.second }).last().createdAt)
        assertEquals(200L, data.summaries(null).single().lastMessageAtMillis)
    }

}
