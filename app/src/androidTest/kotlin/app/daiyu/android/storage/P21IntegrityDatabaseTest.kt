package app.daiyu.android.storage

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteException
import android.test.InstrumentationTestCase
import app.daiyu.android.context.ContextPreparationCancellation
import app.daiyu.android.context.DatabaseTransactions
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.testContext
import java.util.UUID

/** Real SQLite storage-class corruption, not a permissive codec or synthesized zero Usage.
 * Source fixtures only; execution requires the Android instrumentation runner. */
@Suppress("DEPRECATION")
class P21IntegrityDatabaseTest : InstrumentationTestCase() {
    private lateinit var context: android.content.Context
    private lateinit var database: ConversationDatabase
    private lateinit var runtime: SQLiteRuntimeStore

    override fun setUp() {
        super.setUp()
        context = testContext(instrumentation.targetContext, "p21-integrity-${UUID.randomUUID()}-")
        database = ConversationDatabase(context)
        runtime = SQLiteRuntimeStore.acquire(database).also { it.recoverPending() }
    }
    override fun tearDown() {
        try { runtime.close(); database.close(); context.deleteDatabase("conversations.db") }
        finally { super.tearDown() }
    }

    private fun candidate(conversation: ConversationId = database.create("default").id): Pair<Message, Message> {
        val run = RunId(UUID.randomUUID().toString())
        val user = Message(MessageId("u-${run.value}"), conversation, run, "user", emptyList(), RunStatus.COMPLETED)
        return user to user.copy(id = MessageId("a-${run.value}"), role = "assistant", status = RunStatus.RUNNING)
    }
    private fun checkpoint(user: Message, assistant: Message, summary: List<RequestUsageCheckpoint> = emptyList()): RunCheckpoint {
        val request = RequestCheckpoint("main-${assistant.runId.value}", "attempt", "owner-${assistant.runId.value}", RequestOperation.NEXT)
        return RunCheckpoint(assistant, listOf(user), ExecutionPhase.STREAMING, owner = request.owner,
            revision = 1, request = request,
            usage = summary + RequestUsageCheckpoint(request, ModelEvent.Usage(10, 20, 5)))
    }
    private fun handed(summary: ModelEvent.Usage? = null): RunCheckpoint {
        val (user, assistant) = candidate()
        val prep = runtime.summaryPreparation(assistant.conversationId, assistant.runId, assistant.id, ContextPreparationCancellation())
        prep.usagePort.claim()
        summary?.let { prep.usagePort.observe(it) }
        prep.seal()
        database.send(user, assistant)
        val value = checkpoint(user, assistant, runtime.preparationUsage(assistant))
        runtime.commitCheckpoint(value)
        prep.close()
        database.readableDatabase.rawQuery("SELECT state FROM runtime_preparations WHERE message_id=?",
            arrayOf(assistant.id.value)).use { c -> assertTrue(c.moveToFirst()); assertEquals("HANDED", c.getString(0)) }
        return value
    }
    private fun terminal(): Message {
        val (_, assistant) = candidate()
        val prep = runtime.summaryPreparation(assistant.conversationId, assistant.runId, assistant.id, ContextPreparationCancellation())
        prep.usagePort.claim(); prep.usagePort.observe(ModelEvent.Usage(7, 2, 1)); prep.close()
        return assistant
    }
    private fun read(conversation: ConversationId? = null, budget: RecordedUsageBudget = RecordedUsageBudget()) =
        DatabaseTransactions { database.readableDatabase }.run { RecordedUsageReader.read(it, conversation, budget) }

    private fun corruptPresence(message: Message, presence: Any) {
        database.writableDatabase.execSQL("UPDATE runtime_preparations SET has_usage=? WHERE message_id=?",
            arrayOf(presence, message.id.value))
        // Verify the fixture's actual SQLite storage class/value before exercising the reader.
        database.readableDatabase.rawQuery("SELECT has_usage FROM runtime_preparations WHERE message_id=?",
            arrayOf(message.id.value)).use { c ->
            assertTrue(c.moveToFirst())
            if (presence is Double) {
                assertEquals(Cursor.FIELD_TYPE_FLOAT, c.getType(0)); assertEquals(presence, c.getDouble(0), 0.0)
            } else {
                assertEquals(Cursor.FIELD_TYPE_INTEGER, c.getType(0)); assertEquals(presence as Long, c.getLong(0))
            }
        }
    }

    fun testHandedMalformedPresenceIsUnreadableNotFilteredOrFabricated() {
        for (presence in listOf<Any>(0.5, 2L, 4294967296L, 4294967297L)) {
            val saved = handed()
            val before = read(saved.message.conversationId)
            assertTrue(before.scanComplete); assertEquals(1, before.preparationsWithoutUsage)
            corruptPresence(saved.message, presence)
            val value = read(saved.message.conversationId)
            assertEquals(1, value.totalPreparations); assertEquals(1, value.inspectedPreparations)
            assertEquals(1, value.unreadablePreparations); assertEquals(0, value.preparationsWithoutUsage)
            assertEquals(0, value.unreadableCheckpoints); assertFalse(value.scanComplete)
            // The independently valid MAIN report survives; no missing SUMMARY is invented as zero.
            assertEquals(1L, value.summary.requestSnapshotCount)
            assertEquals(10L, value.summary.input.reportedTokens)
            assertEquals(20L, value.summary.output.reportedTokens)
            assertEquals(saved, runtime.readCommittedCheckpoint(saved))
        }
    }

    fun testTerminalPresenceUsesLongDomainWithoutIntWraparound() {
        for (presence in listOf<Any>(0.5, 2L, 4294967296L, 4294967297L)) {
            val assistant = terminal()
            assertEquals(7L, read(assistant.conversationId).summary.input.reportedTokens)
            corruptPresence(assistant, presence)
            val value = read(assistant.conversationId)
            assertEquals(1, value.totalPreparations); assertEquals(1, value.inspectedPreparations)
            assertEquals(1, value.unreadablePreparations); assertEquals(0, value.preparationsWithoutUsage)
            assertFalse(value.scanComplete); assertEquals(0L, value.summary.requestSnapshotCount)
            assertNull(value.summary.input.reportedTokens)
            assertNull(value.summary.output.reportedTokens)
            assertNull(value.summary.cached.reportedTokens)
        }
    }

    fun testLegalHandedPresencePreservesMissingCoverageAndAvoidsDoubleCountingUnderBudget() {
        val missing = handed()
        val missingValue = read(missing.message.conversationId)
        assertTrue(missingValue.scanComplete); assertEquals(1, missingValue.preparationsWithoutUsage)
        assertEquals(1L, missingValue.summary.requestSnapshotCount)
        val deferred = read(missing.message.conversationId, RecordedUsageBudget(maxMessages = 1, maxUsageRecords = 1))
        assertEquals(1, deferred.totalPreparations); assertEquals(0, deferred.inspectedPreparations)
        assertFalse(deferred.scanComplete)

        val reported = handed(ModelEvent.Usage(7, 2, 1))
        val reportedValue = read(reported.message.conversationId, RecordedUsageBudget(maxMessages = 1, maxUsageRecords = 2))
        assertTrue(reportedValue.scanComplete)
        assertEquals(0, reportedValue.totalPreparations); assertEquals(0, reportedValue.inspectedPreparations)
        assertEquals(0, reportedValue.unreadablePreparations)
        assertEquals(2L, reportedValue.summary.requestSnapshotCount)
        assertEquals(17L, reportedValue.summary.input.reportedTokens)
        assertEquals(22L, reportedValue.summary.output.reportedTokens)
    }

    fun testRealCheckpointRevisionCannotNormalizeToPayloadRevisionAndGoodUsageSurvives() {
        val (user, assistant) = candidate()
        database.send(user, assistant)
        val corrupt = checkpoint(user, assistant); runtime.commitCheckpoint(corrupt)
        val (goodUser, goodAssistant) = candidate()
        database.send(goodUser, goodAssistant)
        val good = checkpoint(goodUser, goodAssistant); runtime.commitCheckpoint(good)
        // Keep the payload at revision 1 while SQLite REAL 1.5 previously truncated to 1.
        assertEquals(1, database.writableDatabase.update("runtime_checkpoints", ContentValues().apply {
            put("revision", 1.5)
        }, "message_id=?", arrayOf(assistant.id.value)))
        database.readableDatabase.rawQuery("SELECT revision FROM runtime_checkpoints WHERE message_id=?",
            arrayOf(assistant.id.value)).use { c ->
            assertTrue(c.moveToFirst()); assertEquals(Cursor.FIELD_TYPE_FLOAT, c.getType(0)); assertEquals(1.5, c.getDouble(0), 0.0)
        }
        try {
            SQLiteRuntimeStore.readCheckpointSnapshot(database.readableDatabase, assistant.id.value)
            fail("REAL revision accepted")
        } catch (_: IllegalArgumentException) { }
        val value = read()
        assertEquals(2, value.inspectedAssistantMessages); assertEquals(1, value.unreadableCheckpoints)
        assertFalse(value.scanComplete); assertEquals(1L, value.summary.requestSnapshotCount)
        assertEquals(10L, value.summary.input.reportedTokens)
        assertEquals(good, runtime.readCommittedCheckpoint(good))
    }

    fun testSQLiteReadFailureIsNotSwallowedAsAnUnreadablePreparation() {
        val assistant = terminal()
        val db = database.writableDatabase
        // Real SQLite failure inside the preparation query, restored even if the assertion fails.
        db.execSQL("ALTER TABLE runtime_preparations RENAME TO p21_integrity_hidden_preparations")
        try {
            try {
                read(assistant.conversationId)
                fail("SQLite failure swallowed")
            } catch (_: SQLiteException) { }
        } finally {
            db.execSQL("ALTER TABLE p21_integrity_hidden_preparations RENAME TO runtime_preparations")
        }
    }
}
