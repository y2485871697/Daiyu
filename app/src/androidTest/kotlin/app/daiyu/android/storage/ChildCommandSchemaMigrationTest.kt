package app.daiyu.android.storage

import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.domain.delegation.ChildTaskStatus
import app.daiyu.android.testContext
import java.util.UUID

@Suppress("DEPRECATION")
class ChildCommandSchemaMigrationTest : InstrumentationTestCase() {
    private val contexts = mutableListOf<android.content.Context>()
    private val helpers = mutableListOf<ConversationDatabase>()
    private fun fixture(): Pair<android.content.Context, ConversationDatabase> {
        val context = testContext(instrumentation.targetContext, "child-schema-${UUID.randomUUID()}-")
        contexts.add(context)
        return context to ConversationDatabase(context).also { helpers.add(it) }
    }
    override fun tearDown() {
        try { helpers.forEach { it.close() }; contexts.forEach { it.deleteDatabase("conversations.db") } }
        finally { super.tearDown() }
    }
    private fun columns(db: SQLiteDatabase) = db.rawQuery("PRAGMA table_info(child_tasks)", null).use { c ->
        buildList { while (c.moveToNext()) add(c.getString(1)) }
    }
    private fun witnesses(db: SQLiteDatabase) = db.rawQuery("SELECT * FROM message_video_witnesses ORDER BY message_id", null).use { c ->
        buildList { while (c.moveToNext()) add((0 until c.columnCount).map { if (c.isNull(it)) null else c.getString(it) }) }
    }
    private fun messages(db: SQLiteDatabase) = db.rawQuery("SELECT * FROM messages ORDER BY seq", null).use { c ->
        buildList { while (c.moveToNext()) add((0 until c.columnCount).map { if (c.isNull(it)) null else c.getString(it) }) }
    }
    private fun assertCommandSchema(db: SQLiteDatabase) {
        val info = db.rawQuery("PRAGMA table_info(child_tasks)", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(1), listOf(c.getString(2), c.getInt(3).toString(), if (c.isNull(4)) null else c.getString(4))) }
        }
        assertEquals(listOf("INTEGER", "1", "0"), info["control_revision"])
        assertEquals(listOf("TEXT", "0", null), info["source_task_id"])
        assertEquals(listOf("TEXT", "0", null), info["command_kind"])
        db.rawQuery("SELECT typeof(control_revision),control_revision FROM child_tasks WHERE task_id='old-child'", null).use {
            assertTrue(it.moveToFirst()); assertEquals("integer", it.getString(0)); assertEquals(0L, it.getLong(1))
        }
        val receiptTypes = db.rawQuery("PRAGMA table_info(child_task_commands)", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(1), c.getString(2) to c.getInt(3)) }
        }
        assertEquals(mapOf("call_id" to ("TEXT" to 1), "fingerprint" to ("TEXT" to 1),
            "source_id" to ("TEXT" to 1), "successor_id" to ("TEXT" to 1),
            "expected_revision" to ("INTEGER" to 1), "acknowledged" to ("INTEGER" to 1)), receiptTypes)
        val foreignKeys = db.rawQuery("PRAGMA foreign_key_list(child_task_commands)", null).use { c ->
            buildSet { while (c.moveToNext()) add(listOf(c.getString(2), c.getString(3), c.getString(4), c.getString(6))) }
        }
        assertEquals(setOf(listOf("child_tasks", "source_id", "task_id", "CASCADE"),
            listOf("child_tasks", "successor_id", "task_id", "CASCADE")), foreignKeys)
    }
    private fun legacy(context: android.content.Context, db: ConversationDatabase, conflictingColumn: Boolean = false, video: Boolean = false): ConversationId {
        val conversation = db.create("default")
        val user = Message(MessageId("legacy-user"), conversation.id, RunId("legacy-run"), "user",
            listOf(MessageBlock.Text("body", "unchanged input")), RunStatus.COMPLETED)
        val owner = app.daiyu.android.media.video.MessageVideoOwner(conversation.id.value, user.id.value)
        val inputs = if (!video) emptyList() else listOf(app.daiyu.android.model.videos.MessageVideoInput(owner,
            listOf(app.daiyu.android.media.video.MessageVideoReference(owner, "11111111-1111-1111-1111-111111111111", "video/mp4", 4L,
                app.daiyu.android.context.PrivateBlobs.digest(byteArrayOf(8, 7, 6, 5)), 1000L, 32, 24))))
        db.send(user, user.copy(id = MessageId("legacy-assistant"), role = "assistant", blocks = emptyList(), status = RunStatus.RUNNING),
            videoInputs = inputs)
        db.close()
        context.openOrCreateDatabase("conversations.db", 0, null).use { raw ->
            raw.execSQL("DROP TABLE child_task_commands")
            raw.execSQL("DROP TABLE child_tasks")
            val conflict = if (conflictingColumn) ",Source_Task_ID TEXT" else ""
            raw.execSQL("CREATE TABLE child_tasks(seq INTEGER PRIMARY KEY AUTOINCREMENT,task_id TEXT UNIQUE NOT NULL," +
                "conversation_id TEXT NOT NULL,parent_run_id TEXT NOT NULL,parent_message_id TEXT NOT NULL REFERENCES messages(id) ON DELETE CASCADE," +
                "parent_owner_id TEXT NOT NULL,task_group_id TEXT NOT NULL,generation INTEGER NOT NULL,task_index INTEGER NOT NULL," +
                "assistant_message_id TEXT NOT NULL,profile_id TEXT NOT NULL,prompt TEXT NOT NULL,status TEXT NOT NULL," +
                "text TEXT NOT NULL,detail TEXT$conflict,UNIQUE(task_group_id,task_index))")
            raw.execSQL("CREATE INDEX child_tasks_conversation ON child_tasks(conversation_id,seq)")
            raw.execSQL("INSERT INTO child_tasks(task_id,conversation_id,parent_run_id,parent_message_id,parent_owner_id," +
                "task_group_id,generation,task_index,assistant_message_id,profile_id,prompt,status,text,detail) " +
                "VALUES('old-child',?,'legacy-run','legacy-assistant','old-owner','old-group',1,0,'old-answer','review'," +
                "'old prompt','COMPLETED','old result',NULL)", arrayOf<Any>(conversation.id.value))
            raw.version = 13
        }
        return conversation.id
    }
    fun testVersionThirteenUpgradePreservesTasksMessagesAndVideoWitnesses() {
        val (context, helper) = fixture()
        val conversation = legacy(context, helper)
        val before = context.openOrCreateDatabase("conversations.db", 0, null).use { raw -> witnesses(raw) to messages(raw) }
        val sql = helper.readableDatabase
        assertEquals(18, sql.version)
        val rows = SQLiteChildTaskStore(helper).list(conversation.value)
        assertEquals(1, rows.size)
        val old = rows.single()
        assertEquals("old-child", old.owner.childRunId)
        assertEquals("old prompt", old.prompt); assertEquals("old result", old.text)
        assertEquals(ChildTaskStatus.COMPLETED, old.status)
        assertEquals(0L, old.revision); assertNull(old.sourceTaskId); assertNull(old.commandKind)
        assertTrue(columns(sql).containsAll(listOf("control_revision", "source_task_id", "command_kind")))
        assertCommandSchema(sql)
        assertEquals(before.first, witnesses(sql))
        assertEquals(before.second, messages(sql))
        assertEquals(0L, sql.rawQuery("SELECT count(*) FROM child_task_commands", null).use { it.moveToFirst(); it.getLong(0) })
        val beforeMessages = helper.messages(conversation)
        helper.close()
        assertEquals(beforeMessages, helper.messages(conversation))
        assertEquals(rows, SQLiteChildTaskStore(helper).list(conversation.value))
        assertEquals(before.first, witnesses(helper.readableDatabase))
        assertEquals(before.second, messages(helper.readableDatabase))
    }
    fun testVersionThirteenUpgradeRetainsNonEmptyVideoSidecarAndWitness() {
        val (context, helper) = fixture()
        val conversation = legacy(context, helper, video = true)
        fun sidecars(db: SQLiteDatabase) = db.rawQuery("SELECT * FROM message_video_inputs ORDER BY rowid", null).use { c ->
            buildList { while (c.moveToNext()) add((0 until c.columnCount).map { if (c.isNull(it)) null else c.getString(it) }) }
        }
        val before = context.openOrCreateDatabase("conversations.db", 0, null).use { raw ->
            Triple(messages(raw), witnesses(raw), sidecars(raw))
        }
        assertFalse(before.third.isEmpty())
        context.openOrCreateDatabase("conversations.db", 0, null).use { raw ->
            assertEquals(1L, raw.rawQuery("SELECT reference_count FROM message_video_witnesses WHERE message_id='legacy-user'", null).use {
                assertTrue(it.moveToFirst()); it.getLong(0)
            })
        }
        val sql = helper.readableDatabase
        assertEquals(18, sql.version)
        assertEquals(before.first, messages(sql)); assertEquals(before.second, witnesses(sql))
        assertEquals(before.third, sidecars(sql))
        val history = helper.messages(conversation)
        helper.close()
        assertEquals(history, helper.messages(conversation))
        assertEquals(before.first, messages(helper.readableDatabase))
        assertEquals(before.second, witnesses(helper.readableDatabase))
        assertEquals(before.third, sidecars(helper.readableDatabase))
    }
    fun testFailedUpgradeRollsBackAddedColumnsAndVersionWithoutTouchingHistory() {
        val (context, helper) = fixture()
        val conversation = legacy(context, helper, conflictingColumn = true)
        val before = context.openOrCreateDatabase("conversations.db", 0, null).use { raw ->
            Triple(columns(raw), witnesses(raw), messages(raw))
        }
        try { helper.writableDatabase; fail("case-insensitive duplicate column must reject migration") }
        catch (_: android.database.sqlite.SQLiteException) { }
        context.openOrCreateDatabase("conversations.db", 0, null).use { raw ->
            assertEquals(13, raw.version)
            assertEquals(before.first, columns(raw))
            assertEquals(before.second, witnesses(raw))
            assertEquals(before.third, messages(raw))
            assertEquals(2L, raw.rawQuery("SELECT count(*) FROM messages WHERE conversation_id=?", arrayOf(conversation.value)).use {
                it.moveToFirst(); it.getLong(0)
            })
            assertEquals("old result", raw.rawQuery("SELECT text FROM child_tasks WHERE task_id='old-child'", null).use {
                it.moveToFirst(); it.getString(0)
            })
            assertFalse(raw.rawQuery("SELECT 1 FROM sqlite_master WHERE name='child_task_commands'", null).use { it.moveToFirst() })
        }
    }
}
