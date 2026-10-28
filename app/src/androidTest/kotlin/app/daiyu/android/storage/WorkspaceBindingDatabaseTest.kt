package app.daiyu.android.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase
import app.daiyu.android.backup.NeutralBackupService
import app.daiyu.android.context.ContextService
import app.daiyu.android.context.PrivateBlobs
import app.daiyu.android.context.ProductionConversationBranch
import app.daiyu.android.context.DatabaseTransactions
import app.daiyu.android.domain.*
import app.daiyu.android.testContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@Suppress("DEPRECATION")
class WorkspaceBindingDatabaseTest : InstrumentationTestCase() {
    private val contexts = mutableListOf<Context>()
    private val helpers = mutableListOf<ConversationDatabase>()
    private val a = "{\"kind\":\"app\",\"path\":\"a\"}"
    private val b = "{\"kind\":\"app\",\"path\":\"b\"}"
    private fun fresh(): Pair<Context, ConversationDatabase> {
        val context = testContext(instrumentation.targetContext, "workspace-storage-${UUID.randomUUID()}-")
        contexts += context
        return context to open(context)
    }
    private fun open(context: Context) = ConversationDatabase(context).also { helpers += it }
    override fun tearDown() {
        helpers.forEach { runCatching { it.close() } }
        contexts.forEach { it.deleteDatabase("conversations.db") }
        super.tearDown()
    }
    private fun reject(block: () -> Unit) {
        var rejected = false
        try { block() } catch (_: Exception) { rejected = true }
        assertTrue("operation must reject", rejected)
    }
    private fun pair(id: ConversationId): Pair<Message, Message> {
        val run = RunId(UUID.randomUUID().toString())
        return Message(MessageId(UUID.randomUUID().toString()), id, run, "user",
            listOf(MessageBlock.Text("u", "question")), RunStatus.COMPLETED) to
            Message(MessageId(UUID.randomUUID().toString()), id, run, "assistant",
                listOf(MessageBlock.Text("a", "answer")), RunStatus.RUNNING)
    }
    fun testConversationComposerIsolationCasAndReopen() {
        val (context, db) = fresh()
        val one = db.create("default"); val two = db.create("default")
        assertTrue(db.bindWorkspace(one.id, null, a))
        assertTrue(db.bindWorkspace(two.id, null, b))
        assertTrue(db.bindWorkspace(null, null, b))
        assertFalse(db.bindWorkspace(one.id, null, b))
        assertFalse(db.bindWorkspace(ConversationId("missing"), null, a))
        assertEquals(a, db.workspaceBinding(one.id))
        assertEquals(b, db.workspaceBinding(two.id))
        assertEquals(b, db.workspaceBinding(null))
        assertTrue(db.bindWorkspace(one.id, a, null))
        reject { db.bindWorkspace(one.id, null, "  ") }
        reject { db.bindWorkspace(one.id, null, "汉".repeat(1366)) }
        assertTrue(db.bindWorkspace(one.id, null, "x".repeat(4096)))
        db.close()
        val reopened = open(context)
        assertEquals("x".repeat(4096), reopened.workspaceBinding(one.id))
        assertEquals(b, reopened.conversations().single { it.id == two.id }.workspaceBinding)
        assertEquals(b, reopened.workspaceBinding(null))
    }
    fun testCasReadbackFailureRollsBack() {
        val (_, db) = fresh(); val owner = db.create("default").id
        db.writableDatabase.execSQL("CREATE TRIGGER corrupt_binding AFTER UPDATE OF workspace_binding ON conversations BEGIN UPDATE conversations SET workspace_binding='wrong' WHERE id=NEW.id; END")
        reject { db.bindWorkspace(owner, null, a) }
        assertNull(db.workspaceBinding(owner))
    }
    fun testNewSendTransfersComposerAndExistingSendPreservesIndependentComposer() {
        val (_, db) = fresh()
        db.draft("new draft"); assertTrue(db.bindWorkspace(null, null, a))
        val (u, r) = pair(ConversationId(UUID.randomUUID().toString()))
        reject { db.send(u, r, newConversation = true, expectedWorkspaceBinding = b) }
        assertEquals(a, db.workspaceBinding(null)); assertEquals("new draft", db.draft())
        assertTrue(db.conversations().isEmpty())
        val receipt = db.send(u, r, newConversation = true, expectedWorkspaceBinding = a)
        assertEquals(a, receipt.workspaceBinding); assertEquals(a, db.workspaceBinding(u.conversationId))
        assertNull(db.workspaceBinding(null)); assertEquals("", db.draft())
        db.save(r.copy(status = RunStatus.COMPLETED))
        db.draft("other draft"); assertTrue(db.bindWorkspace(null, null, b))
        val (u2, r2) = pair(u.conversationId)
        reject { db.send(u2, r2) }
        assertEquals(a, db.send(u2, r2, expectedWorkspaceBinding = a).workspaceBinding)
        assertEquals(b, db.workspaceBinding(null)); assertEquals("other draft", db.draft())
    }
    fun testFailedNewSendKeepsComposerAndNoConversation() {
        val (_, db) = fresh(); db.draft("keep"); db.bindWorkspace(null, null, a)
        db.writableDatabase.execSQL("CREATE TRIGGER ignore_assistant BEFORE INSERT ON messages WHEN NEW.role='assistant' BEGIN SELECT RAISE(IGNORE); END")
        val (u, r) = pair(ConversationId(UUID.randomUUID().toString()))
        reject { db.send(u, r, newConversation = true, expectedWorkspaceBinding = a) }
        assertEquals(a, db.workspaceBinding(null)); assertEquals("keep", db.draft())
        assertTrue(db.conversations().isEmpty())
        assertTrue(db.messages(u.conversationId).isEmpty())
    }
    fun testVersionsThirteenAndTwentySevenUpgradeWithAndWithoutRetainedColumns() {
        for (version in listOf(13, 27)) for (retained in listOf(false, true)) {
            val (context, helper) = fresh(); val owner = helper.create("default").id
            helper.draft("composer draft")
            if (retained) { helper.bindWorkspace(owner, null, a); helper.bindWorkspace(null, null, b) }
            helper.close()
            SQLiteDatabase.openDatabase(context.getDatabasePath("conversations.db").path, null, SQLiteDatabase.OPEN_READWRITE).use { sql ->
                if (!retained) {
                    // Real old table shapes; keep the rest of the fixture for the >=13 early-return path.
                    sql.execSQL("CREATE TABLE old_conversations(id TEXT PRIMARY KEY,title TEXT NOT NULL,draft TEXT NOT NULL,updated INTEGER NOT NULL,folder TEXT NOT NULL DEFAULT '',pinned INTEGER NOT NULL DEFAULT 0,assistant_id TEXT)")
                    sql.execSQL("INSERT INTO old_conversations SELECT id,title,draft,updated,folder,pinned,assistant_id FROM conversations")
                    sql.execSQL("DROP TABLE conversations"); sql.execSQL("ALTER TABLE old_conversations RENAME TO conversations")
                    sql.execSQL("CREATE TABLE old_composer(id INTEGER PRIMARY KEY CHECK(id=1),draft TEXT NOT NULL)")
                    sql.execSQL("INSERT INTO old_composer SELECT id,draft FROM composer")
                    sql.execSQL("DROP TABLE composer"); sql.execSQL("ALTER TABLE old_composer RENAME TO composer")
                }
                sql.version = version
            }
            val upgraded = open(context)
            assertEquals(30, upgraded.readableDatabase.version)
            assertEquals(if (retained) a else null, upgraded.workspaceBinding(owner))
            assertEquals(if (retained) b else null, upgraded.workspaceBinding(null))
            assertEquals("composer draft", upgraded.draft())
            upgraded.close()
            assertEquals(if (retained) a else null, open(context).workspaceBinding(owner))
        }
    }
    private fun rewrite(text: String, version: Int = 3, edit: (JSONObject) -> Unit): String {
        val envelope = JSONObject(text); val payload = JSONObject(envelope.getString("payload"))
        edit(payload); val raw = payload.toString()
        val hash = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return envelope.put("version", version).put("payload", raw).put("sha256", hash).toString()
    }
    fun testTextV3RoundTripOldVersionsAndStrictBindingValidation() {
        val (_, source) = fresh(); val owner = source.create("default", a)
        val text = source.exportBackup()
        assertEquals(3, JSONObject(text).getInt("version"))
        val (_, target) = fresh(); target.bindWorkspace(null, null, b)
        target.importBackup(text)
        assertEquals(a, target.workspaceBinding(owner.id)); assertEquals(b, target.workspaceBinding(null))
        reject { target.importBackup(text) } // Collision must not replace a binding.
        assertEquals(a, target.workspaceBinding(owner.id))
        for (version in listOf(1, 2)) {
            val old = rewrite(text, version) { payload ->
                val row = payload.getJSONArray("conversations").getJSONObject(0)
                row.remove("workspace_binding"); if (version == 1) row.remove("assistant_id")
            }
            val (_, legacyTarget) = fresh(); legacyTarget.importBackup(old)
            assertNull(legacyTarget.workspaceBinding(owner.id))
        }
        for (bad in listOf<Any>(" ", "汉".repeat(1366), 12, JSONObject())) {
            val corrupted = rewrite(text) { it.getJSONArray("conversations").getJSONObject(0).put("workspace_binding", bad) }
            val (_, clean) = fresh(); reject { clean.importBackup(corrupted) }
            assertTrue(clean.conversations().isEmpty())
        }
        val extra = rewrite(text) { it.getJSONArray("conversations").getJSONObject(0).put("unknown", true) }
        val (_, clean) = fresh(); reject { clean.importBackup(extra) }
    }
    fun testCompletePackagePreservesBindingWithoutOverwritingComposer() {
        val (context, source) = fresh(); val owner = source.create("default", a)
        val text = NeutralBackupService(context, source).exportPackage()
        val (destination, target) = fresh(); target.bindWorkspace(null, null, b)
        val service = NeutralBackupService(destination, target)
        assertFalse(service.importPackage(text).alreadyImported)
        assertEquals(a, target.workspaceBinding(owner.id)); assertEquals(b, target.workspaceBinding(null))
        assertTrue(service.importPackage(text).alreadyImported)
    }
    fun testProductionBranchInheritsBindingAndSourceCasIncludesIt() {
        val (context, db) = fresh(); val owner = db.create("default", a)
        val (u, r) = pair(owner.id); db.send(u, r, expectedWorkspaceBinding = a)
        db.save(r.copy(status = RunStatus.COMPLETED))
        val brancher = ProductionConversationBranch(db, PrivateBlobs(context),
            DatabaseTransactions { db.writableDatabase }, null, null) { false }
        val copy = brancher.branch(owner.id, r.id, {}, { _, mapped -> mapped }).conversationId
        assertEquals(a, db.workspaceBinding(copy))
        assertTrue(db.bindWorkspace(copy, a, b)); assertEquals(a, db.workspaceBinding(owner.id))
        val before = db.conversations().map { it.id }.toSet()
        reject {
            brancher.branch(owner.id, r.id, {}, { _, mapped ->
                assertTrue(db.bindWorkspace(owner.id, a, b)); mapped
            })
        }
        assertEquals(before, db.conversations().map { it.id }.toSet())
        assertEquals(b, db.workspaceBinding(owner.id))
    }
    fun testLegacyBranchInheritsThenCanChangeIndependentlyAndDeleteNeverTouchesFiles() {
        val (context, db) = fresh(); val owner = db.create("default", a)
        val (u, r) = pair(owner.id); db.send(u, r, expectedWorkspaceBinding = a)
        db.save(r.copy(status = RunStatus.COMPLETED))
        val branch = ContextService(context, db).branch(owner.id, r.id)
        assertEquals(a, db.workspaceBinding(branch))
        assertTrue(db.bindWorkspace(branch, a, b)); assertEquals(a, db.workspaceBinding(owner.id))
        val marker = File(context.filesDir, "workspace-marker-${UUID.randomUUID()}").apply { writeText("keep") }
        try {
            db.delete(branch); assertEquals("keep", marker.readText())
            assertEquals(a, db.workspaceBinding(owner.id))
            db.delete(owner.id); assertEquals("keep", marker.readText())
        } finally { marker.delete() }
    }
}
