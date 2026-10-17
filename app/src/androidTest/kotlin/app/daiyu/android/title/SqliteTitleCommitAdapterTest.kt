package app.daiyu.android.title

import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*

/** Synthetic SQLite only: no provider client, credentials, or paid call. */
@Suppress("DEPRECATION")
class SqliteTitleCommitAdapterTest : InstrumentationTestCase() {
    private lateinit var db: SQLiteDatabase
    private val a = ConversationId("a")
    private val b = ConversationId("b")
    private val run = RunId("parent-a")
    private var receipts = 0

    override fun setUp() {
        super.setUp()
        db = SQLiteDatabase.create(null)
        db.setForeignKeyConstraintsEnabled(true)
        db.execSQL("CREATE TABLE conversations(id TEXT PRIMARY KEY,title TEXT NOT NULL)")
        db.execSQL("CREATE TABLE messages(seq INTEGER PRIMARY KEY AUTOINCREMENT,id TEXT UNIQUE,conversation_id TEXT,run_id TEXT,role TEXT,status TEXT,blocks TEXT NOT NULL DEFAULT '[]',history_segments TEXT NOT NULL DEFAULT '[]')")
        db.execSQL("CREATE TABLE existing_parent_usage(request_id TEXT PRIMARY KEY,owner TEXT,input_tokens INTEGER)")
        db.execSQL("INSERT INTO conversations VALUES('a','新会话'),('b','新会话')")
        db.execSQL("INSERT INTO messages(id,conversation_id,run_id,role,status) VALUES('u','a','parent-a','user','COMPLETED'),('ma','a','parent-a','assistant','COMPLETED')")
        db.beginTransaction()
        try { SqliteTitleCommitAdapter.installSchema(db); db.setTransactionSuccessful() }
        finally { db.endTransaction() }
        receipts = 0
    }
    override fun tearDown() { db.close(); super.tearDown() }
    private fun adapter(failUsage: Boolean = false) = SqliteTitleCommitAdapter({ db }, ParentTitleUsagePort { sql, receipt ->
        assertSame(db, sql)
        assertTrue(sql.inTransaction())
        assertEquals("生成标题", title(a))
        sql.execSQL("INSERT INTO existing_parent_usage VALUES(?,?,?)", arrayOf(receipt.requestId, receipt.conversationId.value, receipt.usage?.inputTokens))
        receipts++
        if (failUsage) error("synthetic receipt failure")
    })
    private fun receipt(owner: ConversationId = a) = ParentTitleUsage("request-a", owner, run, ModelEvent.Usage(7, 2, 1))
    private fun title(owner: ConversationId): String = db.rawQuery("SELECT title FROM conversations WHERE id=?", arrayOf(owner.value)).use { it.moveToFirst(); it.getString(0) }
    private fun count(): Int = db.rawQuery("SELECT count(*) FROM existing_parent_usage", null).use { it.moveToFirst(); it.getInt(0) }

    fun testExactOwnerCasAndUsageShareCommitAndReplayDoesNothing() {
        val adapter = adapter(); val expected = adapter.capture(a)!!
        assertTrue(adapter.commit(expected, "生成标题", receipt()))
        assertEquals("生成标题", title(a)); assertEquals("新会话", title(b)); assertEquals(1, count())
        assertFalse(adapter.commit(expected, "生成标题", receipt()))
        assertEquals(1, receipts)
    }
    fun testManualRenameAndSameTextRenameBothFencePendingResult() {
        for (manual in listOf("手动标题", "新会话")) {
            // A fresh untouched witness is essential: UPDATE OF title already revokes it,
            // even for identical text, so that precondition would make this test vacuous.
            db.execSQL("DELETE FROM conversations WHERE id='a'")
            db.execSQL("INSERT INTO conversations VALUES('a','新会话')")
            val adapter = adapter(); val expected = adapter.capture(a)!!
            assertFalse(expected.titleTouched)
            assertTrue(adapter.isCurrent(expected))
            db.execSQL("UPDATE conversations SET title=? WHERE id='a'", arrayOf(manual))
            assertFalse(adapter.commit(expected, "生成标题", receipt()))
            assertEquals(manual, title(a)); assertEquals(0, count())
        }
    }
    fun testRenameAwayAndBackAndDeleteRecreateCannotPassAba() {
        val adapter = adapter(); val expected = adapter.capture(a)!!
        db.execSQL("UPDATE conversations SET title='手动' WHERE id='a'")
        db.execSQL("UPDATE conversations SET title='新会话' WHERE id='a'")
        assertFalse(adapter.isCurrent(expected))
        db.execSQL("DELETE FROM conversations WHERE id='a'")
        db.execSQL("INSERT INTO conversations VALUES('a','新会话')")
        val beforeDelete = adapter.capture(a)!!
        assertFalse(beforeDelete.titleTouched)
        assertTrue(adapter.isCurrent(beforeDelete))
        db.execSQL("DELETE FROM conversations WHERE id='a'")
        assertFalse(adapter.commit(beforeDelete, "生成标题", receipt()))
        db.execSQL("INSERT INTO conversations VALUES('a','新会话')")
        assertFalse(adapter.commit(beforeDelete, "生成标题", receipt()))
        assertEquals(0, receipts)
    }
    fun testEditedFirstMessageIsRejectedEvenIfTitleAndTitleRevisionUnchanged() {
        val adapter = adapter(); val expected = adapter.capture(a)!!
        db.execSQL("UPDATE messages SET blocks='[{\"type\":\"text\",\"item\":\"u\",\"text\":\"edited\"}]' WHERE id='u'")
        assertFalse(adapter.isCurrent(expected))
        assertFalse(adapter.commit(expected, "生成标题", receipt()))
        assertEquals("新会话", title(a)); assertEquals(0, count())
    }
    fun testWrongParentRunAndWrongReceiptOwnerAreRejected() {
        val adapter = adapter(); val expected = adapter.capture(a)!!
        assertFalse(adapter.commit(expected, "生成标题", receipt().copy(parentRunId = RunId("parent-b"))))
        try { adapter.commit(expected, "生成标题", receipt(b)); fail() } catch (_: IllegalArgumentException) { }
        assertEquals("新会话", title(a)); assertEquals(0, count())
    }
    fun testUsageFailureRollsBackTitleAndRevision() {
        val adapter = adapter(true); val expected = adapter.capture(a)!!
        try { adapter.commit(expected, "生成标题", receipt()); fail() } catch (_: IllegalStateException) { }
        assertEquals(expected, adapter.capture(a)); assertEquals("新会话", title(a)); assertEquals(0, count())
    }
    fun testNoUseBeforeSchemaOrInsideUncommittedParentTransaction() {
        db.beginTransaction()
        try { try { adapter().capture(a); fail() } catch (_: IllegalStateException) { } }
        finally { db.endTransaction() }
        db.execSQL("DROP TABLE conversation_title_revisions")
        try { adapter().capture(a); fail() } catch (_: android.database.sqlite.SQLiteException) { }
    }
    fun testIgnoredTitleUpdateCannotEmitUsage() {
        val adapter = adapter(); val expected = adapter.capture(a)!!
        db.execSQL("CREATE TRIGGER ignore_title BEFORE UPDATE OF title ON conversations BEGIN SELECT RAISE(IGNORE); END")
        assertFalse(adapter.commit(expected, "生成标题", receipt()))
        assertEquals(0, count()); assertEquals(expected, adapter.capture(a))
    }
}
