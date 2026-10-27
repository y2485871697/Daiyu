package app.daiyu.android.storage

import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.ui.model.ContextUsageModel
import app.daiyu.android.testContext
import java.util.UUID

@Suppress("DEPRECATION")
class CloudContextUsageStoreTest : InstrumentationTestCase() {
    private val conversation = ConversationId("context-conversation")
    private val model = ContextUsageModel("provider", "model", 1, 100_000)
    private fun checkpoint(request: String = "one", input: Long? = 40_000, compactions: Int = 0,
        earlier: List<RequestUsageCheckpoint> = emptyList()): RunCheckpoint {
        val id = RequestCheckpoint(request, "attempt", "owner", RequestOperation.NEXT)
        return RunCheckpoint(Message(MessageId("assistant"), conversation, RunId("run"), "assistant",
            emptyList(), RunStatus.RUNNING), emptyList(), ExecutionPhase.STREAMING, owner = "owner", revision = 1,
            request = id, usage = earlier + RequestUsageCheckpoint(id, ModelEvent.Usage(input, 10)),
            usageRequests = earlier.map { it.request } + id, context = RunContextCheckpoint(compactions = compactions))
    }
    private fun <T> transaction(db: SQLiteDatabase, block: () -> T): T {
        db.beginTransaction()
        return try { val result = block(); db.setTransactionSuccessful(); result } finally { db.endTransaction() }
    }
    private fun database() = SQLiteDatabase.create(null).apply {
        setForeignKeyConstraintsEnabled(true)
        execSQL("CREATE TABLE conversations(id TEXT PRIMARY KEY)")
        execSQL("INSERT INTO conversations VALUES('context-conversation')")
        execSQL("CREATE TABLE messages(id TEXT PRIMARY KEY,conversation_id TEXT REFERENCES conversations(id) ON DELETE CASCADE)")
        execSQL("INSERT INTO messages VALUES('message','context-conversation')")
        CloudContextUsageStore.installSchema(this); ConversationUsageStore.installSchema(this)
    }
    fun testVersion24UpgradeBackfillsBillingWithoutInventingAnActualOrHistoricalWindow() {
        val context = testContext(instrumentation.targetContext, "context-v25-${UUID.randomUUID()}-")
        var database = ConversationDatabase(context)
        try {
            val owner = database.create().id
            database.writableDatabase.execSQL("INSERT INTO context_usage(request_id,branch_id,usage_source,request_owner," +
                "attempt_id,process_owner,usage_state,usage_revision,operation_id,terminal_reason,has_usage," +
                "input_tokens,output_tokens,cached_tokens,cache_creation_tokens) " +
                "VALUES('legacy-request',?,'MANUAL_SUMMARY','legacy-owner','attempt','process','TERMINAL',1,'operation','SUCCEEDED',1,123,7,20,3)",
                arrayOf(owner.value))
            database.writableDatabase.execSQL("DROP TABLE context_ui_state")
            database.writableDatabase.execSQL("DROP TABLE conversation_usage_receipts")
            database.writableDatabase.version = 24
            database.close()
            database = ConversationDatabase(context)
            assertEquals(29, database.readableDatabase.version)
            val usage = ConversationUsageStore.read(database.readableDatabase, owner.value)!!
            assertEquals(130L, usage.totalTokens); assertEquals(100L, usage.freshInputTokens)
            assertNull(CloudContextUsageStore.read(database.readableDatabase, owner, model).inputTokens)
            database.readableDatabase.rawQuery("SELECT window_tokens FROM conversation_usage_receipts", null).use {
                assertTrue(it.moveToFirst()); assertTrue(it.isNull(0))
            }
        } finally { database.close(); context.deleteDatabase("conversations.db") }
    }
    fun testFileReopenRestoresStableActualWithoutRelaxingLegacyOrInvalidation() {
        val file = java.io.File(instrumentation.targetContext.cacheDir, "context-reopen-${UUID.randomUUID()}.db")
        val scoped = model.copy(configuration = 52, registryRevision = 4, receiptIdentity = "a".repeat(64))
        var db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.execSQL("CREATE TABLE conversations(id TEXT PRIMARY KEY)")
            db.execSQL("INSERT INTO conversations VALUES('context-conversation')")
            CloudContextUsageStore.installSchema(db)
            transaction(db) { CloudContextUsageStore.record(db, checkpoint(input = 8_162), scoped) }
            db.close()
            db = SQLiteDatabase.openOrCreateDatabase(file, null)
            val reopened = scoped.copy(configuration = 82, registryRevision = 9)
            assertEquals(8_162L, CloudContextUsageStore.read(db, conversation, reopened).inputTokens)
            assertNull(CloudContextUsageStore.read(db, conversation, reopened.copy(provider = "other")).inputTokens)
            assertNull(CloudContextUsageStore.read(db, conversation, reopened.copy(model = "other")).inputTokens)
            assertNull(CloudContextUsageStore.read(db, conversation, reopened.copy(receiptIdentity = "b".repeat(64))).inputTokens)
            assertNull(CloudContextUsageStore.read(db, conversation, reopened.copy(receiptIdentity = null)).inputTokens)
            transaction(db) { CloudContextUsageStore.invalidate(db, conversation) }
            assertNull(CloudContextUsageStore.read(db, conversation, reopened).inputTokens)
            transaction(db) { CloudContextUsageStore.record(db, checkpoint(input = 0), scoped) }
            db.close()
            db = SQLiteDatabase.openOrCreateDatabase(file, null)
            assertEquals(0L, CloudContextUsageStore.read(db, conversation, reopened).inputTokens)
            transaction(db) { CloudContextUsageStore.record(db, checkpoint(input = 40_000), model) }
            db.close()
            db = SQLiteDatabase.openOrCreateDatabase(file, null)
            assertEquals(40_000L, CloudContextUsageStore.read(db, conversation, model).inputTokens)
            assertNull(CloudContextUsageStore.read(db, conversation, model.copy(configuration = 2)).inputTokens)
        } finally { db.close(); SQLiteDatabase.deleteDatabase(file) }
    }
    fun testMessagesMissingCreationPersistsAndRestoresNormalizedCloudInput() {
        val body = listOf(
            """{"type":"message_start","message":{"id":"fixture","role":"assistant","content":[],"usage":{"input_tokens":40000,"cache_read_input_tokens":200000,"output_tokens":1}}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":"ok"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":123}}""",
            """{"type":"message_stop"}""",
        ).joinToString("") { "data: $it\n\n" }
        val events = mutableListOf<ModelEvent>()
        app.daiyu.android.model.MessagesStream.read(body.reader().buffered(), { false }, events::add)
        val usage = events.filterIsInstance<ModelEvent.Usage>().single()
        assertEquals(240_000L, usage.inputTokens)
        assertEquals(200_000L, usage.cachedTokens)
        assertNull(usage.cacheCreationTokens)
        val scoped = model.copy(windowTokens = 272_000, receiptIdentity = "c".repeat(64))
        val saved = checkpoint().let { it.copy(usage = listOf(it.usage.single().copy(snapshot = usage))) }
        val file = java.io.File(instrumentation.targetContext.cacheDir, "context-messages-${UUID.randomUUID()}.db")
        var db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.execSQL("CREATE TABLE conversations(id TEXT PRIMARY KEY)")
            db.execSQL("INSERT INTO conversations VALUES('context-conversation')")
            CloudContextUsageStore.installSchema(db)
            transaction(db) { CloudContextUsageStore.record(db, saved, scoped) }
            db.close()
            db = SQLiteDatabase.openOrCreateDatabase(file, null)
            val reopened = scoped.copy(configuration = 2, registryRevision = 3)
            val restored = CloudContextUsageStore.read(db, conversation, reopened)
            assertEquals(240_000L, restored.inputTokens)
            assertEquals(240_000f / 272_000f, restored.fraction!!, .00001f)
            assertEquals("240K/272K", restored.tokensLabel(java.util.Locale.US))
            val summary = RequestCheckpoint("summary", "summary-attempt", "owner", RequestOperation.NEXT)
            transaction(db) {
                CloudContextUsageStore.record(db, saved.copy(usage = saved.usage +
                    RequestUsageCheckpoint(summary, ModelEvent.Usage(270_000, 500), UsageRequestSource.SUMMARY)), scoped)
            }
            assertEquals(240_000L, CloudContextUsageStore.read(db, conversation, reopened).inputTokens)
            assertNull(CloudContextUsageStore.read(db, conversation, scoped.copy(provider = "other")).inputTokens)
            transaction(db) { CloudContextUsageStore.invalidate(db, conversation) }
            assertNull(CloudContextUsageStore.read(db, conversation, reopened).inputTokens)
        } finally { db.close(); SQLiteDatabase.deleteDatabase(file) }
    }

    fun testActualPersistsWhilePartialDecreasesAndModelChangesRemainScoped() = database().use { db ->
        transaction(db) { CloudContextUsageStore.record(db, checkpoint(), model) }
        assertEquals(40_000L, CloudContextUsageStore.read(db, conversation, model).inputTokens)
        transaction(db) { CloudContextUsageStore.record(db, checkpoint(input = null), model) }
        assertEquals(40_000L, CloudContextUsageStore.read(db, conversation, model).inputTokens)
        transaction(db) { CloudContextUsageStore.record(db, checkpoint(input = 20_000), model) }
        assertEquals(20_000L, CloudContextUsageStore.read(db, conversation, model).inputTokens)
        assertNull(CloudContextUsageStore.read(db, conversation, model.copy(configuration = 2)).inputTokens)
        assertNull(CloudContextUsageStore.read(db, conversation, model.copy(registryRevision = 2)).inputTokens)
        assertNull(CloudContextUsageStore.read(db, ConversationId("other"), model).inputTokens)
    }
    fun testReportedZeroPersistsAndIsNotReplacedByAnOutputOnlyPartial() = database().use { db ->
        transaction(db) { CloudContextUsageStore.record(db, checkpoint(), model) }
        assertEquals(40_000L, CloudContextUsageStore.read(db, conversation, model).inputTokens)
        transaction(db) { CloudContextUsageStore.record(db, checkpoint(input = 0), model) }
        val zero = CloudContextUsageStore.read(db, conversation, model)
        assertEquals(0L, zero.inputTokens)
        assertEquals(0f, zero.fraction!!, 0f)
        transaction(db) { CloudContextUsageStore.record(db, checkpoint(input = null), model) }
        assertEquals(0L, CloudContextUsageStore.read(db, conversation, model).inputTokens)
        transaction(db) { CloudContextUsageStore.invalidate(db, conversation) }
        assertNull(CloudContextUsageStore.read(db, conversation, model).inputTokens)
    }
    fun testCompactionRequiresANewAdmittedMainRequestAndRejectsOldUsage() = database().use { db ->
        val first = checkpoint()
        transaction(db) { CloudContextUsageStore.record(db, first, model) }
        transaction(db) { CloudContextUsageStore.record(db, first.copy(context = RunContextCheckpoint(compactions = 1)), model) }
        assertNull(CloudContextUsageStore.read(db, conversation, model).inputTokens)
        transaction(db) { CloudContextUsageStore.record(db, checkpoint(input = 42_000, compactions = 1), model) }
        assertNull(CloudContextUsageStore.read(db, conversation, model).inputTokens)
        transaction(db) { CloudContextUsageStore.record(db, checkpoint("two", 12_000, 1, first.usage), model) }
        assertEquals(12_000L, CloudContextUsageStore.read(db, conversation, model).inputTokens)
        val late = checkpoint("two", null, 1, first.usage).copy(usage = listOf(
            RequestUsageCheckpoint(checkpoint("two").request!!, ModelEvent.Usage(null, 10)), first.usage.single()))
        transaction(db) { CloudContextUsageStore.record(db, late, model) }
        assertEquals(12_000L, CloudContextUsageStore.read(db, conversation, model).inputTokens)
    }
    fun testInvalidationAndRollbackDoNotPublishAnUncommittedActual() = database().use { db ->
        transaction(db) { CloudContextUsageStore.record(db, checkpoint(), model) }
        db.beginTransaction()
        try { CloudContextUsageStore.record(db, checkpoint(input = 70_000), model) } finally { db.endTransaction() }
        assertEquals(40_000L, CloudContextUsageStore.read(db, conversation, model).inputTokens)
        transaction(db) { CloudContextUsageStore.invalidate(db, conversation) }
        assertNull(CloudContextUsageStore.read(db, conversation, model).inputTokens)
    }
    fun testPartialInputCorrectionCannotRollBackActualBecauseOfAnEarlierCacheField() = database().use { db ->
        val first = checkpoint(input = 100).let { it.copy(usage = listOf(
            it.usage.single().copy(snapshot = ModelEvent.Usage(100, 10, 80)))) }
        transaction(db) {
            ConversationUsageStore.record(db, first)
            CloudContextUsageStore.record(db, first, model)
        }
        val corrected = checkpoint(input = 50)
        transaction(db) {
            ConversationUsageStore.record(db, corrected)
            CloudContextUsageStore.record(db, corrected, model)
        }
        assertEquals(50L, CloudContextUsageStore.read(db, conversation, model).inputTokens)
        val ledger = ConversationUsageStore.read(db, conversation.value)!!
        assertEquals(50L, ledger.inputTokens); assertEquals(80L, ledger.cachedTokens)
        assertEquals(0L, ledger.freshInputTokens)
    }
    fun testMenuRejectsImplausibleInputWithoutDroppingReportedOutput() = database().use { db ->
        transaction(db) { ConversationUsageStore.record(db, conversation.value, "owner", "request",
            ModelEvent.Usage(140, 10, 40, 20), window = 100) }
        val usage = ConversationUsageStore.read(db, conversation.value)!!
        assertEquals(0L, usage.inputTokens); assertEquals(10L, usage.outputTokens)
        assertEquals(0L, usage.cachedTokens); assertEquals(0L, usage.cacheCreationTokens)
    }
    fun testConversationLedgerMergesPartialReplacesCorrectionsAndSurvivesMessageDeletion() = database().use { db ->
        fun record(request: String, usage: ModelEvent.Usage) = transaction(db) {
            ConversationUsageStore.record(db, conversation.value, "owner", request, usage)
        }
        record("one", ModelEvent.Usage(100, null, 40))
        record("one", ModelEvent.Usage(null, 20))
        record("one", ModelEvent.Usage(null, 20))
        record("two", ModelEvent.Usage(10, 3, 2))
        val total = ConversationUsageStore.read(db, conversation.value)!!
        assertEquals(133L, total.totalTokens); assertEquals(42L, total.cachedTokens)
        assertEquals(68L, total.freshInputTokens); assertEquals(2L, total.requestCount)
        transaction(db) { db.delete("messages", "id=?", arrayOf("message")) }
        assertEquals(total, ConversationUsageStore.read(db, conversation.value))
        record("one", ModelEvent.Usage(90, 15))
        assertEquals(118L, ConversationUsageStore.read(db, conversation.value)!!.totalTokens)
        assertNull(ConversationUsageStore.read(db, "other"))
        transaction(db) { db.delete("conversations", "id=?", arrayOf(conversation.value)) }
        assertNull(ConversationUsageStore.read(db, conversation.value))
    }
}
