package app.daiyu.android.storage

import android.content.ContentValues
import android.test.InstrumentationTestCase
import app.daiyu.android.context.DatabaseTransactions
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.testContext
import org.json.JSONObject
import java.util.UUID

@Suppress("DEPRECATION")
class RecordedUsageReaderDatabaseTest : InstrumentationTestCase() {
    private lateinit var context: android.content.Context
    private lateinit var database: ConversationDatabase
    private lateinit var runtime: SQLiteRuntimeStore
    override fun setUp() {
        super.setUp()
        context = testContext(instrumentation.targetContext, "recorded-usage-${UUID.randomUUID()}-")
        database = ConversationDatabase(context)
        runtime = SQLiteRuntimeStore.acquire(database).also { it.recoverPending() }
    }
    override fun tearDown() {
        try { runtime.close(); database.close(); context.deleteDatabase("conversations.db") } finally { super.tearDown() }
    }
    private fun seed(usage: ModelEvent.Usage? = ModelEvent.Usage(10, 20, 5),
        conversation: ConversationId = database.create("default").id): RunCheckpoint {
        val id = UUID.randomUUID().toString()
        val user = Message(MessageId("u-$id"), conversation, RunId(id), "user", emptyList(), RunStatus.COMPLETED)
        val assistant = user.copy(id = MessageId("a-$id"), role = "assistant", status = RunStatus.RUNNING)
        database.send(user, assistant)
        val request = RequestCheckpoint(id, "attempt", "owner-$id", RequestOperation.NEXT)
        return RunCheckpoint(assistant, listOf(user), ExecutionPhase.STREAMING, owner = request.owner,
            revision = 1, request = request, usage = usage?.let { listOf(RequestUsageCheckpoint(request, it)) }.orEmpty())
    }
    private fun read(conversation: ConversationId? = null, budget: RecordedUsageBudget = RecordedUsageBudget()) =
        DatabaseTransactions { database.readableDatabase }.run { RecordedUsageReader.read(it, conversation, budget) }
    private fun writePayload(checkpoint: RunCheckpoint, payload: String) {
        assertEquals(1, database.writableDatabase.update("runtime_checkpoints", ContentValues().apply {
            put("payload", payload)
        }, "message_id=?", arrayOf(checkpoint.message.id.value)))
    }
    fun testLatestResumeSnapshotAndScopedQueriesNeverAcquireOrRecoverLiveRun() {
        val first = seed(); runtime.commitCheckpoint(first)
        val nextRequest = first.request!!.copy(attemptId = "resume", operation = RequestOperation.RESUME)
        val replaced = first.copy(revision = 2, request = nextRequest,
            usage = listOf(RequestUsageCheckpoint(nextRequest, ModelEvent.Usage(null, 0, 0))))
        runtime.commitCheckpoint(replaced)
        val second = seed(); runtime.commitCheckpoint(second)
        val scoped = read(first.message.conversationId)
        assertEquals(1, scoped.totalAssistantMessages); assertTrue(scoped.scanComplete)
        assertEquals(1L, scoped.summary.requestSnapshotCount); assertNull(scoped.summary.input.reportedTokens)
        assertEquals(0L, scoped.summary.output.reportedTokens); assertEquals(0L, scoped.summary.cached.reportedTokens)
        assertEquals(replaced, runtime.readCommittedCheckpoint(replaced))
        assertEquals(2L, read().summary.requestSnapshotCount)
        assertEquals(0, read(ConversationId("absent")).totalAssistantMessages)
    }
    fun testMissingLegacyAndCorruptRowsStayDistinctWhileGoodUsageSurvives() {
        val missing = seed(); val legacy = seed(null); val corrupt = seed(); val good = seed()
        runtime.commitCheckpoint(legacy); runtime.commitCheckpoint(corrupt); runtime.commitCheckpoint(good)
        val v3 = JSONObject(RunCheckpointCodec.encode(legacy)).put("version", 3)
        v3.remove("usage"); v3.remove("usage_requests")
        writePayload(legacy, v3.toString()); writePayload(corrupt, "{}")
        val value = read()
        assertEquals(4, value.totalAssistantMessages); assertEquals(4, value.inspectedAssistantMessages)
        assertEquals(1, value.messagesWithoutCheckpoint); assertEquals(1, value.messagesWithoutUsage)
        assertEquals(1, value.unreadableCheckpoints); assertEquals(0, value.deferredCheckpoints)
        assertFalse(value.scanComplete); assertEquals(1L, value.summary.requestSnapshotCount)
        assertEquals(10L, value.summary.input.reportedTokens)
        assertEquals(1, read(missing.message.conversationId).messagesWithoutCheckpoint)
    }
    fun testMismatchedMessageIdentityIsNotCountedAsUsage() {
        val first = seed(); val second = seed()
        runtime.commitCheckpoint(first); runtime.commitCheckpoint(second)
        writePayload(second, RunCheckpointCodec.encode(first))
        val value = read()
        assertEquals(1, value.unreadableCheckpoints); assertEquals(1L, value.summary.requestSnapshotCount)
        assertFalse(value.scanComplete)
    }
    fun testRowPayloadTotalBytesAndRecordBudgetsAreExplicit() {
        val first = seed(); runtime.commitCheckpoint(first)
        val second = seed(); runtime.commitCheckpoint(second)
        val limited = read(budget = RecordedUsageBudget(maxMessages = 1))
        assertEquals(2, limited.totalAssistantMessages); assertEquals(1, limited.inspectedAssistantMessages)
        assertFalse(limited.scanComplete)
        val tiny = read(budget = RecordedUsageBudget(maxPayloadBytes = 1))
        assertEquals(2, tiny.deferredCheckpoints); assertEquals(0, tiny.unreadableCheckpoints)
        assertNull(tiny.summary.input.reportedTokens)
        val sizes = database.readableDatabase.rawQuery(
            "SELECT length(CAST(payload AS BLOB)) FROM runtime_checkpoints ORDER BY rowid DESC", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0)) }
        }
        val bytes = read(budget = RecordedUsageBudget(maxTotalPayloadBytes = sizes.first()))
        assertEquals(1, bytes.deferredCheckpoints); assertEquals(1L, bytes.summary.requestSnapshotCount)
        assertFalse(bytes.scanComplete)
        val records = read(budget = RecordedUsageBudget(maxUsageRecords = 1))
        assertEquals(1, records.deferredCheckpoints); assertEquals(1L, records.summary.requestSnapshotCount)
    }
    fun testLargeValidPayloadIsDeferredByDefaultAndReadLosslesslyBeyondCursorWindow() {
        val first = seed()
        val large = first.copy(message = first.message.copy(blocks = listOf(MessageBlock.Text("large", "x".repeat(2_200_000) + "末尾😀"))))
        runtime.commitCheckpoint(large)
        val deferred = read()
        assertEquals(1, deferred.deferredCheckpoints); assertEquals(0, deferred.unreadableCheckpoints)
        val included = read(budget = RecordedUsageBudget(maxPayloadBytes = 4L * 1024 * 1024))
        assertTrue(included.scanComplete); assertEquals(10L, included.summary.input.reportedTokens)
        assertEquals(large, runtime.readCommittedCheckpoint(large))
    }
    fun testFailedCommitShowsOnlyLastDurableUsageAndDeleteRemovesIt() {
        val first = seed(); runtime.commitCheckpoint(first)
        database.writableDatabase.execSQL("CREATE TRIGGER fail_usage BEFORE UPDATE ON runtime_checkpoints BEGIN SELECT RAISE(ABORT,'fixture'); END")
        try {
            runtime.commitCheckpoint(first.copy(revision = 2, usage = listOf(RequestUsageCheckpoint(first.request!!, ModelEvent.Usage(99, 99)))))
            fail("write failure accepted")
        } catch (_: android.database.sqlite.SQLiteException) {}
        assertEquals(10L, read().summary.input.reportedTokens)
        database.writableDatabase.execSQL("DROP TRIGGER fail_usage")
        database.delete(first.message.conversationId)
        val empty = read(); assertTrue(empty.scanComplete)
        assertEquals(0, empty.totalAssistantMessages); assertNull(empty.summary.input.reportedTokens)
    }
    fun testRecoveryPreservesHistoricalRequestOwnerAndReadDoesNotChangeTerminalRow() {
        val first = seed(); runtime.commitCheckpoint(first); runtime.close()
        runtime = SQLiteRuntimeStore.acquire(database)
        val recovered = runtime.reconcileCheckpoint(first); runtime.recoverPending()
        val before = RunCheckpointCodec.encode(recovered)
        assertTrue(first.owner != recovered.owner)
        assertEquals(first.usage, recovered.usage)
        assertEquals(10L, read().summary.input.reportedTokens)
        assertEquals(before, RunCheckpointCodec.encode(runtime.readCommittedCheckpoint(recovered)!!))
    }
}
