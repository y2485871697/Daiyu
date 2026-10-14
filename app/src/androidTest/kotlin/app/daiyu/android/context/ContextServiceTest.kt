package app.daiyu.android.context

import android.content.Context
import android.content.ContextWrapper
import android.test.InstrumentationTestCase
import app.daiyu.android.testContext
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.domain.*
import app.daiyu.android.domain.context.*
import java.io.File
import java.util.UUID

@Suppress("DEPRECATION")
class ContextServiceTest : InstrumentationTestCase() {
    private lateinit var context: Context
    private lateinit var db: ConversationDatabase
    private lateinit var service: ContextService
    override fun setUp() {
        super.setUp()
        val base = testContext(instrumentation.targetContext, "context-${UUID.randomUUID()}-")
        val files = File(base.cacheDir, "context-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(base) { override fun getFilesDir(): File = files }
        db = ConversationDatabase(context)
        // Explicit install until the owning agent wires onCreate/version4 migration. No service DDL.
        DatabaseTransactions { db.writableDatabase }.run { ContextStorage.installSchema(it) }
        service = ContextService(context, db)
    }
    override fun tearDown() {
        db.close(); context.deleteDatabase("conversations.db"); context.filesDir.deleteRecursively(); super.tearDown()
    }
    private fun seed(turns: Int = 6): ConversationId {
        val owner = db.create().id
        repeat(turns) { n -> val run = RunId("run-$n-${owner.value}")
            val user = Message(MessageId("u-$n-${owner.value}"), owner, run, "user", listOf(MessageBlock.Text("u", "事实$n ".repeat(100))), RunStatus.COMPLETED)
            val reply = Message(MessageId("a-$n-${owner.value}"), owner, run, "assistant", listOf(MessageBlock.Text("a", "已确认$n ".repeat(100))), RunStatus.RUNNING)
            db.send(user, reply); db.save(reply.copy(status = RunStatus.COMPLETED))
        }
        return owner
    }
    private fun reject(block: () -> Unit) { try { block(); fail("must reject") } catch (_: IllegalArgumentException) {} }
    fun testBranchAndPhysicalReviseDriveDisplayAndSendIdentically() {
        val owner = seed(); val old = service.displayHistory(owner); val copy = service.branch(owner, old[3].id)
        assertEquals(old, service.displayHistory(owner)); assertEquals(4, db.messages(copy).size)
        assertEquals(old.take(4).map { it.blocks }, service.prepareSend(copy).messages.map { it.blocks })
        assertTrue(service.displayHistory(copy).none { m -> old.any { it.id == m.id } })
        service.revise(copy, service.displayHistory(copy)[0].id, "更正事实")
        assertEquals(1, db.messages(copy).size); assertEquals(service.displayHistory(copy), service.prepareSend(copy).messages)
        assertEquals("更正事实", (service.prepareSend(copy).messages.single().blocks.single() as MessageBlock.Text).text)
        assertEquals(old, service.displayHistory(owner))
    }
    fun testArchiveIsExactPageableAndMissingArchiveRefusesRevision() {
        val owner = seed(); val old = service.displayHistory(owner)
        val archive = service.compress(owner, CompressionPolicy(keepTurns = 2), ContextSummarizer { "事实0至3已确认，保留后续原文" })!!
        assertEquals(old, service.displayHistory(owner)); assertEquals(8, service.archivePage(archive).total)
        val first = service.archivePage(archive, 0, 3); assertEquals(3, first.nextOffset)
        assertEquals(old.take(3), first.messages)
        assertEquals(old.drop(6).take(2), service.archivePage(archive, 6, 3).messages)
        val prepared = service.prepareSend(owner); assertEquals(archive, prepared.archiveId); assertEquals(5, prepared.messages.size)
        assertEquals(old.takeLast(4), prepared.messages.takeLast(4))
        val hash = db.readableDatabase.rawQuery("SELECT blob_hash FROM context_archives WHERE id=?", arrayOf(archive)).use { it.moveToFirst(); it.getString(0) }
        File(context.filesDir, "neutral-context-blobs/$hash").delete()
        reject { service.revise(owner, old[1].id) }
        assertEquals(old, service.displayHistory(owner))
    }
    fun testFailureNoShrinkAndHistoryRaceNeverApplySummary() {
        val owner = seed(); val old = service.displayHistory(owner)
        reject { service.compress(owner, CompressionPolicy(), ContextSummarizer { "X".repeat(100000) }) }
        assertNull(service.prepareSend(owner).archiveId)
        try { service.compress(owner, CompressionPolicy(), ContextSummarizer { throw IllegalStateException("failure") }); fail() } catch (_: IllegalStateException) {}
        assertEquals(old, service.displayHistory(owner))
        reject { service.compress(owner, CompressionPolicy(), ContextSummarizer {
            service.revise(owner, old.last().id, null)
            // Same cutoff is not a change; now revise earlier to provoke snapshot mismatch.
            service.revise(owner, old[3].id)
            "简短摘要"
        }) }
        assertNull(service.prepareSend(owner).archiveId); assertEquals(4, service.displayHistory(owner).size)
    }
    fun testAutomaticUnknownWindowAndProviderUsageIsolation() {
        val owner = seed(); var calls = 0
        val summarizer = ContextSummarizer { calls++; "已确认事实摘要" }
        service.prepareSend(owner, CompressionPolicy(enabled = true), summarizer); assertEquals(0, calls)
        service.prepareSend(owner, CompressionPolicy(true, 10000, 10, 1000, 2), summarizer); assertEquals(1, calls)
        val other = seed(1)
        val row = UsageRecord("receipt", "task", owner.value, 100, 20, 30, 4)
        service.recordUsage(row); service.recordUsage(row)
        reject { service.recordUsage(row.copy(branchId = other.value)) }
        assertEquals(120L, service.usage(owner.value, "task").totalTokens)
        assertEquals(0L, service.usage(other.value).totalTokens)
        service.recordUsage(UsageRecord("unknown", "task", owner.value, null, null))
        assertTrue(service.usage(owner.value, "task").incomplete)
    }
}
