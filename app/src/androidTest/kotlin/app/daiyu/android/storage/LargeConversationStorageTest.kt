package app.daiyu.android.storage

import android.content.Context
import android.content.ContextWrapper
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.testContext
import java.io.File
import java.util.UUID

@Suppress("DEPRECATION")
class LargeConversationStorageTest : InstrumentationTestCase() {
    private val contexts = mutableListOf<Context>()
    private val databases = mutableListOf<ConversationDatabase>()

    private fun fresh(): Context {
        val token = UUID.randomUUID().toString()
        val base = testContext(instrumentation.targetContext, "large-history-$token-")
        val files = File(base.cacheDir, token).apply { check(mkdirs()) }
        return object : ContextWrapper(base) { override fun getFilesDir() = files }.also { contexts += it }
    }
    private fun open(context: Context) = ConversationDatabase(context).also { databases += it }
    override fun tearDown() {
        try {
            databases.forEach { it.close() }
            contexts.forEach { it.deleteDatabase("conversations.db"); it.filesDir.deleteRecursively() }
        } finally { super.tearDown() }
    }
    private fun seed(db: ConversationDatabase): Message {
        val owner = db.create("default").id
        val run = RunId("run-${UUID.randomUUID()}")
        val user = Message(MessageId("user-${UUID.randomUUID()}"), owner, run, "user",
            listOf(MessageBlock.Text("u", "question")), RunStatus.COMPLETED)
        val assistant = Message(MessageId("assistant-${UUID.randomUUID()}"), owner, run, "assistant",
            emptyList(), RunStatus.RUNNING)
        db.send(user, assistant)
        return assistant
    }
    private fun largeText() = "earliest\u0000" + "数🙂".repeat(340_000) + "\u0000latest"
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected a rejected operation") } catch (expected: PersistenceException) { }
    }

    fun testLargeTerminalReceiptIsIdempotentButDifferentLateReceiptStillRejects() {
        val context = fresh(); var db = open(context)
        val pending = seed(db)
        val text = largeText()
        val result = pending.copy(blocks = listOf(MessageBlock.Text("answer", text)),
            status = RunStatus.COMPLETED, error = "error\u0000" + "e".repeat(2_200_000))
        db.save(result)
        db.save(result)
        assertEquals(result, db.messages(pending.conversationId).last().second)
        rejects { db.save(result.copy(error = result.error + "changed")) }
        rejects { db.save(result.copy(blocks = listOf(MessageBlock.Text("answer", text + "changed")))) }
        assertEquals(result, db.messages(pending.conversationId).last().second)
        db.close(); databases.remove(db); db = open(context)
        assertEquals(result, db.messages(pending.conversationId).last().second)
    }

    fun testLargeBlocksAndHistorySegmentsRoundTripThroughBothTextExports() {
        val context = fresh(); var db = open(context)
        val pending = seed(db)
        val blocks = listOf(MessageBlock.Text("answer", largeText()))
        val result = pending.copy(blocks = blocks, status = RunStatus.COMPLETED,
            historySegments = listOf(MessageHistorySegment(pending.id, "assistant", blocks)))
        db.save(result)
        db.close(); databases.remove(db); db = open(context)
        assertEquals(result, db.messages(pending.conversationId).last().second)
        val all = db.exportBackup()
        val selected = db.exportConversationBackup(pending.conversationId)
        for (backup in listOf(all, selected)) {
            val target = open(fresh())
            assertEquals(1 to 2, target.importBackup(backup))
            assertEquals(result, target.messages(pending.conversationId).last().second)
            assertEquals(backup, target.exportBackup())
        }
    }

    fun testLargeConversationAndComposerDraftsExportAndImportWithoutClipping() {
        val db = open(fresh()); val pending = seed(db)
        db.save(pending.copy(status = RunStatus.COMPLETED))
        val draft = largeText()
        db.draft(pending.conversationId, draft)
        db.draft(draft)
        val backup = db.exportBackup()
        val target = open(fresh())
        assertEquals(1 to 2, target.importBackup(backup))
        assertEquals(draft, target.draft())
        assertEquals(draft, SQLiteText.read(target.readableDatabase, "conversations", "draft", "id", pending.conversationId.value))
        assertEquals(backup, target.exportBackup())
    }

    fun testUnrelatedLargeComposerSurvivesSelectedImportAndConflictingImportRollsBack() {
        val source = open(fresh()); val pending = seed(source)
        source.save(pending.copy(status = RunStatus.COMPLETED))
        val selected = source.exportConversationBackup(pending.conversationId)
        val target = open(fresh())
        val draft = largeText()
        target.draft(draft)
        assertEquals(1 to 2, target.importBackup(selected))
        assertEquals(draft, target.draft())
        assertEquals(source.messages(pending.conversationId), target.messages(pending.conversationId))
        val conflict = open(fresh())
        val conflictPending = seed(conflict)
        conflict.save(conflictPending.copy(status = RunStatus.COMPLETED))
        conflict.draft("different composer")
        val before = target.exportBackup()
        var rejected = false
        try { target.importBackup(conflict.exportBackup()) } catch (expected: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
        assertEquals(before, target.exportBackup())
        assertEquals(draft, target.draft())
    }

    fun testDuplicateReceiptPreservesRawJsonAndNullVersusEmptyErrorContract() {
        val db = open(fresh()); val pending = seed(db)
        val result = pending.copy(status = RunStatus.COMPLETED,
            blocks = listOf(MessageBlock.Text("answer", "original")))
        db.save(result)
        val encoded = SQLiteText.read(db.readableDatabase, "messages", "blocks", "id", result.id.value)!!
        db.writableDatabase.execSQL("UPDATE messages SET blocks=? WHERE id=?", arrayOf(" $encoded ", result.id.value))
        rejects { db.save(result) }
        assertEquals(" $encoded ", SQLiteText.read(db.readableDatabase, "messages", "blocks", "id", result.id.value))
        db.writableDatabase.execSQL("UPDATE messages SET blocks=?,error='' WHERE id=?", arrayOf(encoded, result.id.value))
        rejects { db.save(result) }
        assertEquals("", db.messages(pending.conversationId).last().second.error)
        db.save(result.copy(error = ""))
    }

    fun testLargeRawProtocolKeepsOriginalBytesAcrossDuplicateSaveAndBackup() {
        val db = open(fresh()); val pending = seed(db)
        val raw = " { \"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"" +
            "x".repeat(2_200_000) + "\"}]} "
        val result = pending.copy(status = RunStatus.COMPLETED,
            blocks = listOf(MessageBlock.Text("answer", "shown")), protocolItems = listOf(raw))
        db.save(result); db.save(result)
        val backup = db.exportBackup()
        val target = open(fresh())
        assertEquals(1 to 2, target.importBackup(backup))
        assertEquals(raw, target.messages(pending.conversationId).last().second.protocolItems.single())
        assertEquals(backup, target.exportBackup())
    }

    fun testInvalidUtf8AndChunkBudgetRejectInsteadOfReplacingOrClipping() {
        val db = open(fresh()); val pending = seed(db)
        val result = pending.copy(status = RunStatus.COMPLETED,
            blocks = listOf(MessageBlock.Text("answer", "unaltered")))
        db.save(result)
        var bounded = false
        try { SQLiteText.read(db.readableDatabase, "messages", "blocks", "id", result.id.value, 1) }
        catch (expected: IllegalArgumentException) { bounded = true }
        assertTrue(bounded)
        db.writableDatabase.execSQL("UPDATE messages SET error=CAST(X'80' AS TEXT) WHERE id=?", arrayOf(result.id.value))
        var malformed = false
        try { db.exportBackup() } catch (expected: java.nio.charset.CharacterCodingException) { malformed = true }
        assertTrue(malformed)
    }

    fun testBackupKeepsOriginalSixteenMiBBudget() {
        val db = open(fresh()); val pending = seed(db)
        val result = pending.copy(status = RunStatus.COMPLETED,
            blocks = listOf(MessageBlock.Text("answer", "x".repeat(16 * 1024 * 1024))))
        db.save(result)
        var rejected = false
        try { db.exportBackup() } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("16MiB")); rejected = true
        }
        assertTrue(rejected)
        assertEquals(result, db.messages(pending.conversationId).last().second)
    }
}
