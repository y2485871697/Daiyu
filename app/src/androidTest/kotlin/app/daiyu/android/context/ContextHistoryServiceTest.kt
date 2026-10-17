package app.daiyu.android.context

import app.daiyu.android.storage.SQLiteMessageVideoStore
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.domain.context.*
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.storage.MessageHistoryCodec
import app.daiyu.android.testContext
import org.json.JSONArray
import java.io.File
import java.util.UUID

/** Real Android SQLite/JSON/blob fixture. Added for platform execution, not a host JSON mock. */
@Suppress("DEPRECATION")
class ContextHistoryServiceTest : InstrumentationTestCase() {
    private lateinit var context: Context
    private lateinit var db: ConversationDatabase
    private lateinit var service: ContextService
    private lateinit var blobs: PrivateBlobs
    override fun setUp() {
        super.setUp()
        val base = testContext(instrumentation.targetContext, "context-history-${UUID.randomUUID()}-")
        val files = File(base.cacheDir, "context-history-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(base) { override fun getFilesDir(): File = files }
        db = ConversationDatabase(context); blobs = PrivateBlobs(context); service = ContextService(context, db)
    }
    override fun tearDown() {
        db.close(); context.deleteDatabase("conversations.db"); context.filesDir.deleteRecursively(); super.tearDown()
    }
    private fun reopen() {
        db.close(); db = ConversationDatabase(context); service = ContextService(context, db)
    }
    private fun text(value: String) = listOf(MessageBlock.Text("provider-text-item", value))
    private fun insert(m: Message) {
        DatabaseTransactions { db.writableDatabase }.run { sql ->
            MessageHistoryCodec.validate(m)
            sql.insertOrThrow("messages", null, ContentValues().apply {
                put("id", m.id.value); put("conversation_id", m.conversationId.value); put("run_id", m.runId.value)
                put("role", m.role); put("blocks", ArchiveCodec.blocks(m.blocks).toString()); put("status", m.status.name)
                put("error", m.error); put("protocol", JSONArray(m.protocolItems).toString())
                put("history_segments", MessageHistoryCodec.encode(m.historySegments).toString())
            })
            SQLiteMessageVideoStore.insert(sql, m, emptyList())
        }
    }
    private fun seed(): List<Message> {
        val id = db.create("default").id
        fun row(key: String, role: String, run: String, value: String = key) = Message(
            MessageId("${id.value}-$key"), id, RunId("${id.value}-$run"), role, text(value), RunStatus.COMPLETED)
        val user = row("u1", "user", "r1", "question".repeat(100))
        val supplement = row("extra", "user", "r1", "supplement".repeat(100))
        val answer = row("a1", "assistant", "r1", "DISPLAY MUST NOT BE SUMMARIZED")
        val owner = answer.copy(historySegments = listOf(
            MessageHistorySegment(answer.id, "assistant", text("authoritative first".repeat(100)),
                listOf("{\"type\":\"reasoning\",\"id\":\"provider-reasoning\"}")),
            MessageHistorySegment(supplement.id, "user", supplement.blocks),
            MessageHistorySegment(MessageId("${id.value}-segment-only"), "user", text("segment-only question")),
            MessageHistorySegment(answer.id, "assistant", listOf(
                MessageBlock.Tool(ToolInvocation(ToolCallId("provider-call"), "read", "{}"), "tool result"),
                MessageBlock.Text("provider-final", "authoritative final".repeat(100)))
            )
        ))
        val rows = listOf(user, owner, supplement) + (2..3).flatMap { n -> listOf(
            row("u$n", "user", "r$n", "question$n".repeat(100)),
            row("a$n", "assistant", "r$n", "answer$n".repeat(100))) }
        rows.forEach(::insert)
        return rows
    }
    private fun reject(block: () -> Unit) {
        try { block(); fail("must reject unsafe mutation") } catch (_: IllegalArgumentException) { }
    }
    private fun archiveCount(): Int = db.readableDatabase.rawQuery("SELECT count(*) FROM context_archives", null).use {
        it.moveToFirst(); it.getInt(0)
    }

    fun testCompressionUsesExpandedAuthorityButArchivesOriginalPhysicalRowsAfterReopen() {
        val original = seed(); val id = original.first().conversationId
        assertEquals(original, service.displayHistory(id))
        assertEquals(estimateTokens(MessageHistory.expand(original)), service.prepareSend(id).estimatedTokens)
        val archive = service.compress(id, CompressionPolicy(keepTurns = 2), ContextSummarizer { input ->
            assertEquals(MessageHistory.expand(original.take(3)), input.originals)
            assertEquals(ContextRevision.digest(original.take(3)), input.revision)
            assertTrue(input.originals.none { m -> m.blocks.any { it is MessageBlock.Text && it.text.contains("DISPLAY MUST NOT") } })
            "short factual summary"
        })!!
        reopen()
        assertEquals(original, service.displayHistory(id))
        assertEquals(original.take(3), service.archivePage(archive).messages)
        assertEquals(3, service.archivePage(archive).total)
        val prepared = service.prepareSend(id)
        assertEquals(archive, prepared.archiveId)
        assertEquals(original.drop(3), prepared.messages.drop(1))
        assertEquals(estimateTokens(prepared.messages), prepared.estimatedTokens)
    }

    fun testInjectedBoundaryAndPersistedOldArchiveCannotSplitOwnerSupplementOrRun() {
        val original = seed(); val id = original.first().conversationId
        val injected = ContextService(db, blobs, compressionBoundary = { _, _ -> 2 })
        assertNull(injected.compress(id, CompressionPolicy(keepTurns = 1), ContextSummarizer { fail("unsafe model call"); "unused" }))
        val prefix = original.take(2)
        db.writableDatabase.insertOrThrow("context_archives", null, ContentValues().apply {
            put("id", "unsafe-old-archive"); put("conversation_id", id.value); put("prefix_count", 2)
            put("revision", ContextRevision.digest(prefix)); put("summary", "must not apply")
            put("blob_hash", blobs.put(ArchiveCodec.encode(prefix))); put("created", 1); put("active", 1)
        })
        val prepared = service.prepareSend(id)
        assertNull(prepared.archiveId); assertEquals(original, prepared.messages)
    }

    fun testBranchRemapsOwnerStandaloneAndSegmentOnlyIdsWithoutChangingProviderIds() {
        val original = seed(); val id = original.first().conversationId
        val supplement = original[2]
        val hash = blobs.put("attachment".toByteArray())
        db.writableDatabase.insertOrThrow("neutral_assets", null, ContentValues().apply {
            put("id", "asset"); put("message_id", supplement.id.value); put("kind", "attachment")
            put("mime", "text/plain"); put("bytes", 10); put("blob_hash", hash)
        })
        reject { service.branch(id, original[1].id) }
        val copy = service.branch(id, supplement.id)
        reopen()
        val branched = service.displayHistory(copy)
        assertEquals(3, branched.size)
        assertEquals(original.take(3).map { it.blocks }, branched.map { it.blocks })
        assertTrue(branched.all { m -> m.id !in original.map { it.id } && m.runId != original[0].runId })
        val segments = branched[1].historySegments
        assertEquals(branched[1].id, segments[0].messageId)
        assertEquals(branched[2].id, segments[1].messageId)
        assertEquals(branched[1].id, segments[3].messageId)
        assertTrue(segments[2].messageId != original[1].historySegments[2].messageId)
        assertEquals(original[1].historySegments.map { it.protocolItems }, segments.map { it.protocolItems })
        assertEquals(original[1].historySegments.map { it.blocks }, segments.map { it.blocks })
        assertEquals(MessageHistory.expand(original.take(3)).map { it.blocks }, MessageHistory.expand(branched).map { it.blocks })
        assertEquals(hash, db.readableDatabase.rawQuery("SELECT blob_hash FROM neutral_assets WHERE message_id=?", arrayOf(branched[2].id.value)).use {
            assertTrue(it.moveToFirst()); it.getString(0)
        })
        // Both dangerous supplement cut shapes reject before deleting rows or stale owner content.
        reject { service.revise(copy, branched[1].id) }
        reject { service.revise(copy, branched[2].id, "unsafe edit") }
        assertEquals(branched, service.displayHistory(copy))
        service.revise(copy, branched[0].id, "safe correction before owner")
        reopen()
        val revised = service.displayHistory(copy)
        assertEquals(1, revised.size)
        assertEquals("safe correction before owner", (revised.single().blocks.single() as MessageBlock.Text).text)
        assertTrue(revised.single().historySegments.isEmpty())
        assertEquals(revised, service.prepareSend(copy).messages)
        assertEquals(original, service.displayHistory(id))
    }

    fun testSegmentProtocolRaceAndModelFailureNeverApplyArchiveAndIdleStillRequired() {
        val original = seed(); val id = original.first().conversationId
        try { service.compress(id, CompressionPolicy(keepTurns = 2), ContextSummarizer { throw IllegalStateException("model failure") }); fail() }
        catch (_: IllegalStateException) { }
        assertEquals(original, service.displayHistory(id)); assertEquals(0, archiveCount())
        reject { service.compress(id, CompressionPolicy(keepTurns = 2), ContextSummarizer { "x".repeat(100000) }) }
        assertEquals(original, service.displayHistory(id)); assertEquals(0, archiveCount())
        val changed = original[1].historySegments.mapIndexed { index, segment ->
            if (index == 0) segment.copy(protocolItems = listOf("changed-provider-protocol")) else segment
        }
        reject { service.compress(id, CompressionPolicy(keepTurns = 2), ContextSummarizer {
            db.writableDatabase.execSQL("UPDATE messages SET history_segments=? WHERE id=?",
                arrayOf(MessageHistoryCodec.encode(changed).toString(), original[1].id.value))
            "short summary"
        }) }
        assertEquals(0, archiveCount())
        val afterRace = original.toMutableList().apply { this[1] = this[1].copy(historySegments = changed) }
        assertEquals(afterRace, service.displayHistory(id))
        db.writableDatabase.execSQL("UPDATE messages SET status='RUNNING' WHERE id=?", arrayOf(original.last().id.value))
        reject { service.compress(id, CompressionPolicy(keepTurns = 2), ContextSummarizer { fail("live run"); "unused" }) }
        reject { service.branch(id, original[2].id) }
        reject { service.revise(id, original[0].id, "no mutation") }
        assertEquals(0, archiveCount())
    }

    fun testMismatchingStandaloneSupplementIsRejectedRatherThanSilentlyDropped() {
        val original = seed(); val id = original.first().conversationId
        db.writableDatabase.execSQL("UPDATE messages SET blocks=? WHERE id=?",
            arrayOf(ArchiveCodec.blocks(text("stale supplement")).toString(), original[2].id.value))
        reject { service.displayHistory(id) }
        reject { service.prepareSend(id) }
        reject { service.branch(id, original[2].id) }
        reject { service.revise(id, original[0].id, "no mutation") }
    }
}
