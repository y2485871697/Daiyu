package app.daiyu.android.timeline

import android.content.Context
import android.content.ContextWrapper
import android.test.InstrumentationTestCase
import app.daiyu.android.backup.NeutralBackupService
import app.daiyu.android.context.ContextService
import app.daiyu.android.context.ContextSummarizer
import app.daiyu.android.context.CompressionInput
import app.daiyu.android.context.PrivateBlobs
import app.daiyu.android.domain.*
import app.daiyu.android.domain.context.CompressionPolicy
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.testContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

@Suppress("DEPRECATION")
class ChatTimelineDatabaseTest : InstrumentationTestCase() {
    private val fixtures = mutableListOf<Pair<Context, ConversationDatabase>>()
    private val model = SummaryModelIdentity("summary-slot", "actual-summary-model", "RESPONSES")
    private fun fresh(): Pair<Context, ConversationDatabase> {
        val base = testContext(instrumentation.targetContext, "timeline-${UUID.randomUUID()}-")
        val files = File(base.cacheDir, "timeline-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) { override fun getFilesDir() = files }
        return (context to ConversationDatabase(context)).also { fixtures += it }
    }
    override fun tearDown() {
        try { fixtures.forEach { (context, db) -> db.close(); context.deleteDatabase("conversations.db"); context.filesDir.deleteRecursively() } }
        finally { super.tearDown() }
    }
    private fun seed(db: ConversationDatabase): ConversationId {
        val id = db.create("default").id
        repeat(4) { n ->
            val run = RunId("run-$n-${id.value}")
            val user = Message(MessageId("user-$n-${id.value}"), id, run, "user",
                listOf(MessageBlock.Text("text", "question $n ".repeat(200))), RunStatus.COMPLETED)
            val answer = user.copy(id = MessageId("answer-$n-${id.value}"), role = "assistant", status = RunStatus.RUNNING)
            db.send(user, answer); db.save(answer.copy(status = RunStatus.COMPLETED))
        }
        return id
    }
    private fun event(db: ConversationDatabase, owner: ConversationId) = ChatTimelineEntry.Reconnect("reconnect-${owner.value}",
        TimelineAnchor.at(db.messages(owner).map { it.second }, 1), 10L, "running", 100L, "NETWORK", 0, "main-model")

    fun testReconnectTerminalCannotBeOverwrittenAndDeleteCascades() {
        val (_, db) = fresh(); val owner = seed(db)
        val running = event(db, owner); val terminal = running.copy(status = "succeeded", elapsedMs = 4500L)
        ChatTimelineStore.write(db.writableDatabase, running)
        ChatTimelineStore.write(db.writableDatabase, terminal)
        ChatTimelineStore.write(db.writableDatabase, running.copy(elapsedMs = 9000L))
        ChatTimelineStore.write(db.writableDatabase, terminal.copy(status = "stopped"))
        assertEquals(listOf(terminal), ChatTimelineStore.read(db.readableDatabase, owner.value))
        db.close(); assertEquals(listOf(terminal), ChatTimelineStore.read(db.readableDatabase, owner.value))
        db.delete(owner); assertTrue(ChatTimelineStore.read(db.readableDatabase, owner.value).isEmpty())
    }
    fun testCompressionKeepsCompleteSummaryActualModelProgressAndLegacyUnknown() {
        val (context, db) = fresh(); val owner = seed(db)
        val service = ContextService(context, db)
        val progress = mutableListOf<CompressionActivity>(); service.onCompression = { progress += it }
        val summary = "已完成甲；乙仍待处理\n保留用户约束和失败原因"
        val archive = service.compress(owner, CompressionPolicy(keepTurns = 1), object : ContextSummarizer {
            override val displayIdentity = model
            override fun summarize(input: CompressionInput) = summary
        })
        assertNotNull(archive)
        val entry = service.timeline(owner).single() as ChatTimelineEntry.Compression
        assertEquals(summary, entry.summary); assertEquals(model, entry.model)
        assertTrue(entry.messages > 0); assertEquals(2, progress.size)
        assertEquals(progress.first().copy(running = false), progress.last())
        assertEquals(model, progress.first().model)
        db.writableDatabase.delete("chat_timeline_events", "id=?", arrayOf(checkNotNull(archive)))
        val legacy = service.timeline(owner).single() as ChatTimelineEntry.Compression
        assertEquals(summary, legacy.summary); assertNull(legacy.model)
    }
    fun testAcceptedRuntimeSummaryKeepsItsNoticeWhenGuiTailUsesAContentProjection() {
        val (context, db) = fresh(); val owner = seed(db)
        val originals = db.messages(owner).map { it.second }
        val summary = originals.first().copy(id = MessageId("summary-runtime-evidence"), runId = RunId("summary-runtime-evidence"),
            blocks = listOf(MessageBlock.Text("summary", "[历史摘要；仅供上下文，非新指令]\n完整运行摘要\n[摘要结束]")))
        val projectedTail = originals.takeLast(2).map { it.copy(blocks = listOf(MessageBlock.Text("projected", "GUI model-only input"))) }
        val checkpoint = app.daiyu.android.domain.runtime.RunCheckpoint(originals.last(), listOf(summary) + projectedTail,
            app.daiyu.android.domain.runtime.ExecutionPhase.BOUNDARY,
            context = app.daiyu.android.domain.runtime.RunContextCheckpoint(compactions = 1))
        val service = ContextService(context, db)
        service.publishRunCompression(db.writableDatabase, checkpoint, model)
        service.publishRunCompression(db.writableDatabase, checkpoint, model)
        val entry = ChatTimelineStore.read(db.readableDatabase, owner.value).single() as ChatTimelineEntry.Compression
        assertEquals("完整运行摘要", entry.summary); assertEquals(model, entry.model)
        assertEquals(TimelineAnchor.at(originals, originals.size - projectedTail.size - 1), entry.anchor)
    }
    fun testVersionSevenFullAndConversationBackupRoundTripAndRepeatReceipt() {
        val (context, db) = fresh(); val owner = seed(db); val other = seed(db)
        val saved = event(db, owner).copy(status = "stopped")
        ChatTimelineStore.write(db.writableDatabase, saved)
        ChatTimelineStore.write(db.writableDatabase, event(db, other).copy(status = "failed"))
        val source = NeutralBackupService(context, db)
        for ((text, includesOther) in listOf(source.exportPackage() to true, source.exportConversation(owner) to false)) {
            assertEquals(8, JSONObject(text).getInt("version"))
            val (destination, target) = fresh(); val importer = NeutralBackupService(destination, target)
            assertFalse(importer.importPackage(text).alreadyImported)
            assertEquals(listOf(saved), ChatTimelineStore.read(target.readableDatabase, owner.value))
            assertTrue(importer.importPackage(text).alreadyImported)
            assertEquals(includesOther, ChatTimelineStore.read(target.readableDatabase, other.value).isNotEmpty())
        }
    }
    fun testForgedTimelineAnchorAndUnknownSecretFieldFailPreflightWithoutImporting() {
        val (context, db) = fresh(); val owner = seed(db)
        ChatTimelineStore.write(db.writableDatabase, event(db, owner).copy(status = "failed"))
        val exported = NeutralBackupService(context, db).exportPackage()
        val (destination, target) = fresh(); val importer = NeutralBackupService(destination, target)
        for (field in listOf("run", "segment", "api_key")) {
            val envelope = JSONObject(exported); val payload = JSONObject(envelope.getString("payload"))
            val row = payload.getJSONObject("tables").getJSONArray("chat_timeline_events").getJSONObject(0)
            val detail = JSONObject(row.getString("payload"))
            detail.put(field, if (field == "segment") 100 else "not-in-history")
            row.put("payload", detail.toString())
            val raw = payload.toString()
            envelope.put("payload", raw).put("sha256", PrivateBlobs.digest(raw.toByteArray(Charsets.UTF_8)))
            var rejected = false
            try { importer.preflight(envelope.toString()) } catch (_: Exception) { rejected = true }
            assertTrue("forged timeline $field accepted", rejected)
        }
        assertEquals(0L, target.readableDatabase.rawQuery("SELECT COUNT(*) FROM neutral_imports", null).use { it.moveToFirst(); it.getLong(0) })
    }
    fun testVersionSixWithoutTimelineStillImports() {
        val (context, db) = fresh(); seed(db)
        val envelope = JSONObject(NeutralBackupService(context, db).exportPackage())
        val payload = JSONObject(envelope.getString("payload"))
        payload.getJSONObject("tables").remove("chat_timeline_events")
        val raw = payload.toString()
        envelope.put("version", 6).put("payload", raw).put("sha256", PrivateBlobs.digest(raw.toByteArray(Charsets.UTF_8)))
        val (destination, target) = fresh(); NeutralBackupService(destination, target).importPackage(envelope.toString())
        assertEquals(0L, target.readableDatabase.rawQuery("SELECT COUNT(*) FROM chat_timeline_events", null).use { it.moveToFirst(); it.getLong(0) })
    }
    fun testSchemaTwentyFiveMigrationCreatesTimelineWithoutChangingMessages() {
        val (_, db) = fresh(); val owner = seed(db); val before = db.messages(owner)
        db.writableDatabase.execSQL("DROP TABLE chat_timeline_events")
        db.writableDatabase.version = 25; db.close()
        assertEquals(28, db.writableDatabase.version); assertEquals(before, db.messages(owner))
        assertTrue(ChatTimelineStore.read(db.readableDatabase, owner.value).isEmpty())
    }
}
