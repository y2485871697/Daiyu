package app.daiyu.android.context

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteException
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.context.UsageRecord
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.testContext
import java.io.File
import java.util.UUID

/** Real helper/service transactions; triggers deterministically simulate failed SQLite writes. */
@Suppress("DEPRECATION")
class ContextServiceConsistencyTest : InstrumentationTestCase() {
    private lateinit var context: Context
    private lateinit var db: ConversationDatabase
    private lateinit var service: ContextService

    override fun setUp() {
        super.setUp()
        val base = testContext(instrumentation.targetContext, "context-consistency-${UUID.randomUUID()}-")
        val files = File(base.cacheDir, "context-consistency-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(base) { override fun getFilesDir(): File = files }
        db = ConversationDatabase(context)
        service = ContextService(context, db)
    }

    override fun tearDown() {
        db.close()
        context.deleteDatabase("conversations.db")
        context.filesDir.deleteRecursively()
        super.tearDown()
    }

    private fun receipt(): UsageRecord = UsageRecord("receipt", "summary-task", db.create().id.value, 100, 20, 30, 4)
    private fun rows(): Long = db.readableDatabase.rawQuery("SELECT count(*) FROM context_usage", null).use {
        it.moveToFirst(); it.getLong(0)
    }
    private fun legacyRows(): List<UsageRecord> = db.readableDatabase.rawQuery(
        "SELECT request_id,run_id,branch_id,input_tokens,output_tokens,cached_tokens,reasoning_tokens FROM context_usage ORDER BY rowid", null).use { c ->
        buildList { while (c.moveToNext()) {
            fun n(i: Int): Long? = if (c.isNull(i)) null else c.getLong(i)
            add(UsageRecord(c.getString(0), c.getString(1), c.getString(2), n(3), n(4), n(5), n(6)))
        } }
    }
    private fun reopen() {
        db.close()
        db = ConversationDatabase(context)
        service = ContextService(context, db)
    }
    private fun reject(block: () -> Unit): Exception {
        try { block() } catch (e: Exception) { return e }
        fail("A failed or conflicting receipt must not return successfully")
        throw AssertionError("unreachable")
    }
    private fun failInserts(mode: String) {
        require(mode in setOf("ABORT", "IGNORE"))
        val failure = if (mode == "IGNORE") "RAISE(IGNORE)" else "RAISE(ABORT, 'injected usage write failure')"
        db.writableDatabase.execSQL("CREATE TRIGGER reject_usage BEFORE INSERT ON context_usage BEGIN SELECT $failure; END")
    }

    fun testWriteFailureIsSurfacedAndSuccessfulRetryPersistsOnceAfterReopen() {
        val row = receipt()
        failInserts("ABORT")
        assertTrue(reject { service.recordUsage(row) } is SQLiteException)
        assertEquals(0L, rows())
        assertEquals(0L, service.usage(row.branchId).requests)
        db.writableDatabase.execSQL("DROP TRIGGER reject_usage")
        assertTrue(service.recordUsage(row) > 0L)
        assertEquals(-1L, service.recordUsage(row))
        reopen()
        assertEquals(1L, rows())
        // Receipt-only runs are no longer production ownership evidence.
        assertTrue(reject { service.usage(row.branchId, row.runId) } is IllegalArgumentException)
        val totals = service.usage(row.branchId)
        assertEquals(0L, totals.requests)
        assertEquals(0L, totals.reportedRequests)
        assertNull(totals.totalTokens); assertNull(totals.cachedTokens); assertNull(totals.reasoningTokens)
        assertEquals(row, legacyRows().single())
    }

    fun testWriteFailureRollsBackEnclosingOwnerTransaction() {
        val row = receipt()
        val owner = ConversationId(row.branchId)
        db.draft(owner, "original draft")
        failInserts("ABORT")
        assertTrue(reject {
            DatabaseTransactions { db.writableDatabase }.run {
                db.draft(owner, "must roll back")
                service.recordUsage(row)
            }
        } is SQLiteException)
        reopen()
        assertEquals("original draft", db.conversations().single { it.id == owner }.draft)
        assertEquals(0L, rows())
    }

    fun testIgnoredInsertWithoutReceiptIsNotAcknowledgedOrCommitted() {
        val row = receipt()
        val owner = ConversationId(row.branchId)
        db.draft(owner, "original draft")
        failInserts("IGNORE")
        assertTrue(reject {
            DatabaseTransactions { db.writableDatabase }.run {
                db.draft(owner, "must roll back")
                service.recordUsage(row)
            }
        } is IllegalStateException)
        reopen()
        assertEquals("original draft", db.conversations().single { it.id == owner }.draft)
        assertEquals(0L, rows())
        assertEquals(0L, service.usage(row.branchId).requests)
    }

    fun testExactReplayDoesNotAttemptAnotherInsertAndKeepsUnknownUsageUnknown() {
        val known = receipt()
        val unknown = known.copy(requestId = "unknown-receipt", inputTokens = null, outputTokens = null,
            cachedTokens = null, reasoningTokens = null)
        service.recordUsage(known)
        service.recordUsage(unknown)
        reopen()
        db.writableDatabase.execSQL("CREATE TABLE usage_insert_probe (attempts INTEGER NOT NULL)")
        db.writableDatabase.execSQL("INSERT INTO usage_insert_probe VALUES (0)")
        db.writableDatabase.execSQL("CREATE TRIGGER count_usage_insert BEFORE INSERT ON context_usage " +
            "BEGIN UPDATE usage_insert_probe SET attempts = attempts + 1; SELECT RAISE(IGNORE); END")
        repeat(3) {
            service.recordUsage(known)
            service.recordUsage(unknown)
        }
        val attempts = db.readableDatabase.rawQuery("SELECT attempts FROM usage_insert_probe", null).use {
            it.moveToFirst(); it.getLong(0)
        }
        assertEquals(0L, attempts)
        val totals = service.usage(known.branchId)
        assertEquals(2L, rows()); assertEquals(listOf(known, unknown), legacyRows())
        assertEquals(0L, totals.requests); assertEquals(0L, totals.reportedRequests)
        assertNull(totals.totalTokens); assertTrue(totals.incomplete)
        assertTrue(reject { service.usage(known.branchId, known.runId) } is IllegalArgumentException)
        assertTrue(reject { service.usage(known.branchId, "unrelated-task") } is IllegalArgumentException)
    }

    fun testReplayIdentityOrValueConflictNeverChangesPersistedReceipt() {
        val row = receipt()
        val other = db.create().id
        service.recordUsage(row)
        val before = service.usage(row.branchId)
        listOf(row.copy(branchId = other.value), row.copy(runId = "other-task"),
            row.copy(inputTokens = 101), row.copy(outputTokens = 21), row.copy(cachedTokens = 31),
            row.copy(reasoningTokens = 5), row.copy(cachedTokens = null)).forEach { conflict ->
            assertTrue(reject { service.recordUsage(conflict) } is IllegalArgumentException)
            assertEquals(before, service.usage(row.branchId))
        }
        reopen()
        assertEquals(1L, rows())
        assertEquals(before, service.usage(row.branchId))
        assertEquals(0L, service.usage(other.value).requests)
    }
}
