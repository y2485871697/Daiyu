package app.daiyu.android.storage

import android.content.Context
import android.content.ContextWrapper
import android.test.InstrumentationTestCase
import app.daiyu.android.backup.NeutralBackupService
import app.daiyu.android.context.ContextService
import app.daiyu.android.domain.*
import app.daiyu.android.domain.context.UsageRecord
import app.daiyu.android.testContext
import java.io.File
import java.util.UUID

@Suppress("DEPRECATION")
class ConversationSchema4Test : InstrumentationTestCase() {
    private val contexts = mutableListOf<Context>()
    private val databases = mutableListOf<ConversationDatabase>()

    private fun fresh(): Context {
        val token = UUID.randomUUID().toString()
        val base = testContext(instrumentation.targetContext, "schema4-$token-")
        val files = File(base.cacheDir, "schema4-$token").apply { check(mkdirs()) }
        return object : ContextWrapper(base) {
            override fun getFilesDir(): File = files
        }.also { contexts.add(it) }
    }
    private fun open(context: Context) = ConversationDatabase(context).also { databases.add(it) }
    override fun tearDown() {
        try {
            databases.forEach { it.close() }
            contexts.forEach { it.deleteDatabase("conversations.db"); it.filesDir.deleteRecursively() }
        } finally { super.tearDown() }
    }
    private fun count(db: ConversationDatabase, table: String): Int =
        db.readableDatabase.rawQuery("SELECT count(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) }
    private fun assertSchema(db: ConversationDatabase) {
        assertEquals(8, db.readableDatabase.version)
        db.readableDatabase.rawQuery("SELECT history_segments FROM messages LIMIT 0",null).use { assertEquals(1,it.columnCount) }
        for (table in listOf("context_archives", "context_branches", "context_usage", "neutral_assets", "neutral_config", "neutral_imports")) {
            db.readableDatabase.rawQuery("SELECT * FROM $table LIMIT 0", null).use { assertTrue(it.columnCount > 0) }
        }
        assertEquals(1, count(db, "composer"))
    }
    private fun turn(db: ConversationDatabase, owner: ConversationId): Message {
        val token = UUID.randomUUID().toString()
        val run = RunId("run-$token")
        val user = Message(MessageId("user-$token"), owner, run, "user", listOf(MessageBlock.Text("text", "历史原文")), RunStatus.COMPLETED)
        val assistant = Message(MessageId("assistant-$token"), owner, run, "assistant", listOf(MessageBlock.Text("text", "已完成")), RunStatus.RUNNING)
        db.send(user, assistant); db.save(assistant.copy(status = RunStatus.COMPLETED))
        return user
    }
    fun testNewDatabaseInstallsSchemaAndCompleteBackupWithoutManualSetup() {
        val context = fresh(); val db = open(context)
        assertSchema(db)
        val service = NeutralBackupService(context, db)
        val inventory = service.preflight(service.exportPackage())
        assertEquals(0, inventory.conversations); assertEquals(0, inventory.attachments)
        db.close(); assertSchema(open(context))
    }
    fun testRealVersionThreeUpgradePreservesHistoryComposerAndOrganization() {
        val context = fresh()
        context.openOrCreateDatabase("conversations.db", 0, null).use { old ->
            old.execSQL("CREATE TABLE conversations(id TEXT PRIMARY KEY,title TEXT NOT NULL,draft TEXT NOT NULL,updated INTEGER NOT NULL,folder TEXT NOT NULL DEFAULT '',pinned INTEGER NOT NULL DEFAULT 0)")
            old.execSQL("CREATE TABLE messages(seq INTEGER PRIMARY KEY AUTOINCREMENT,id TEXT UNIQUE NOT NULL,conversation_id TEXT NOT NULL,run_id TEXT NOT NULL,role TEXT NOT NULL,blocks TEXT NOT NULL,status TEXT NOT NULL,error TEXT,protocol TEXT NOT NULL)")
            old.execSQL("CREATE INDEX messages_tail ON messages(conversation_id,seq)")
            old.execSQL("CREATE TABLE composer(id INTEGER PRIMARY KEY CHECK(id=1),draft TEXT NOT NULL)")
            old.execSQL("INSERT INTO composer VALUES(1,'独立草稿')")
            old.execSQL("INSERT INTO conversations VALUES('legacy','标题','会话草稿',123,'文件夹',1)")
            old.execSQL("INSERT INTO messages(seq,id,conversation_id,run_id,role,blocks,status,error,protocol) VALUES(9,'message','legacy','run','assistant','[]','INTERRUPTED','中断','[]')")
            old.version = 3
        }
        var db = open(context); assertSchema(db)
        assertEquals("独立草稿", db.draft())
        val conversation = db.conversations().single()
        assertEquals("标题", conversation.title); assertEquals("会话草稿", conversation.draft)
        assertEquals("文件夹", conversation.folder); assertTrue(conversation.pinned)
        val message = db.messages(conversation.id).single()
        assertEquals(9L, message.first); assertEquals(MessageId("message"), message.second.id)
        assertEquals(RunStatus.INTERRUPTED, message.second.status); assertEquals("中断", message.second.error)
        val backup = NeutralBackupService(context, db)
        assertEquals(1, backup.preflight(backup.exportPackage()).messages)
        val before = db.exportBackup(); db.close(); db = open(context)
        assertSchema(db); assertEquals(before, db.exportBackup())
    }
    fun testDeleteClearsOwnedReferencesButKeepsMaterializedBranchAndSharedBlob() {
        val context = fresh(); val db = open(context)
        val parent = db.create().id; val other = db.create().id
        val message = turn(db, parent); turn(db, other)
        val backup = NeutralBackupService(context, db)
        backup.attach("parent-asset", message.id.value, "application/octet-stream", byteArrayOf(1, 2, 3))
        val service = ContextService(context, db)
        val child = service.branch(parent, message.id)
        service.recordUsage(UsageRecord("parent-usage", message.runId.value, parent.value, 1L, 2L, null, null))
        service.recordUsage(UsageRecord("child-usage", "child-run", child.value, 3L, 4L, null, null))
        db.writableDatabase.execSQL("INSERT INTO context_archives VALUES('parent-archive',?,1,'revision','summary','hash',1,0)", arrayOf(parent.value))
        val childMessages = db.messages(child)
        val asset = db.readableDatabase.rawQuery("SELECT id FROM neutral_assets WHERE id!='parent-asset'", null).use { it.moveToFirst(); it.getString(0) }
        db.delete(parent)
        assertFalse(db.conversations().any { it.id == parent })
        assertEquals(childMessages, db.messages(child)); assertEquals(2, db.messages(other).size)
        assertEquals(0, count(db, "context_archives")); assertEquals(1, count(db, "context_usage"))
        assertEquals(1, count(db, "neutral_assets")); assertEquals(1, count(db, "context_branches"))
        assertTrue(byteArrayOf(1, 2, 3).contentEquals(backup.attachment(asset)))
        assertEquals(2, backup.preflight(backup.exportPackage()).conversations)
        db.delete(child)
        assertEquals(0, count(db, "context_branches")); assertEquals(0, count(db, "context_usage")); assertEquals(0, count(db, "neutral_assets"))
        assertEquals(1, db.conversations().size)
    }
    fun testDeleteFailureRollsBackReferencesAndMessagesAfterReopen() {
        val context = fresh(); var db = open(context)
        val owner = db.create().id; val message = turn(db, owner)
        val backup = NeutralBackupService(context, db)
        backup.attach("asset", message.id.value, "application/octet-stream", byteArrayOf(5))
        ContextService(context, db).recordUsage(UsageRecord("usage", message.runId.value, owner.value, 1L, 2L, null, null))
        val before = backup.exportPackage()
        db.writableDatabase.execSQL("CREATE TRIGGER fail_delete BEFORE DELETE ON messages BEGIN SELECT RAISE(ABORT,'synthetic delete failure'); END")
        try { db.delete(owner); fail("delete must fail") } catch (_: android.database.sqlite.SQLiteException) {}
        assertEquals(before, backup.exportPackage())
        db.close(); db = open(context)
        assertEquals(before, NeutralBackupService(context, db).exportPackage())
        db.writableDatabase.execSQL("DROP TRIGGER fail_delete")
        db.delete(owner)
        assertEquals(0, count(db, "neutral_assets")); assertEquals(0, count(db, "context_usage"))
    }
}
