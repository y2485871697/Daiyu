package app.daiyu.android.model.usage

import android.content.Context
import android.test.InstrumentationTestCase
import app.daiyu.android.context.ContextPreparationCancellation
import app.daiyu.android.context.DatabaseTransactions
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.storage.*
import app.daiyu.android.testContext
import app.daiyu.android.title.ParentTitleUsage
import app.daiyu.android.title.ParentTitleUsagePort
import app.daiyu.android.title.SqliteTitleCommitAdapter
import java.util.UUID

/** Real helper, lease, Runner, title CAS and reader fixtures. No real network or billing data.
 * Source-only until the parent runs these on Android; do not treat their existence as a pass. */
@Suppress("DEPRECATION")
class P21RequestCoverageDatabaseTest : InstrumentationTestCase() {
    private lateinit var context: Context
    private lateinit var database: ConversationDatabase
    private lateinit var runtime: SQLiteRuntimeStore
    override fun setUp() {
        super.setUp()
        context = testContext(instrumentation.targetContext, "p21-coverage-${UUID.randomUUID()}-")
        database = ConversationDatabase(context)
        runtime = SQLiteRuntimeStore.acquire(database).also { it.recoverPending() }
    }
    override fun tearDown() {
        try { runtime.close(); database.close(); context.deleteDatabase("conversations.db") }
        finally { super.tearDown() }
    }
    private fun candidate(): Pair<Message, Message> {
        val conversation = database.create("default").id
        val run = RunId(UUID.randomUUID().toString())
        val user = Message(MessageId("u-${run.value}"), conversation, run, "user",
            listOf(MessageBlock.Text("q", "fixture question")), RunStatus.COMPLETED)
        return user to user.copy(id = MessageId("a-${run.value}"), role = "assistant", blocks = emptyList(), status = RunStatus.RUNNING)
    }
    private fun read(owner: ConversationId, budget: RecordedUsageBudget = RecordedUsageBudget()) =
        DatabaseTransactions { database.writableDatabase }.run { RecordedUsageReader.readAttributed(it, owner, budget) }
    private fun checkpoint(message: Message) = DatabaseTransactions { database.writableDatabase }.run {
        requireNotNull(SQLiteRuntimeStore.readCheckpointSnapshot(it, message.id.value))
    }
    private fun reject(block: () -> Unit) {
        try { block(); fail("stale or replayed usage admitted") } catch (_: IllegalStateException) { }
    }
    private fun run(assistant: Message, usage: ModelEvent.Usage? = null): Message {
        val model = ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                usage?.let(sink); sink(ModelEvent.TextFinal("answer", "done"))
            }
            override fun cancel() {}
        } }
        return ChatRunner(model, database, BatteryTool { error("no tools") }, RunnerOptions(runtimeStore = runtime)) {}.run(assistant, emptyList())
    }

    fun testIgnoredTypedAdmissionFailsAndDoesNotCreateARequest() {
        val (user, assistant) = candidate(); database.send(user, assistant)
        val db = database.writableDatabase
        db.execSQL("CREATE TRIGGER ignore_typed_usage BEFORE INSERT ON context_usage BEGIN SELECT RAISE(IGNORE); END")
        try {
            for (source in listOf(UsageRequestSource.TITLE, UsageRequestSource.AUXILIARY_VISION)) {
                val request = RequestCheckpoint(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "fixture", RequestOperation.NEXT)
                reject {
                    DatabaseTransactions { db }.run {
                        SQLiteAdditionalUsageStore.admit(it, assistant.conversationId.value, assistant.runId.value,
                            assistant.id.value, request, source, "fixture-process")
                    }
                }
            }
            db.rawQuery("SELECT COUNT(*) FROM context_usage", null).use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        } finally { db.execSQL("DROP TRIGGER ignore_typed_usage") }
    }

    fun testDeletingParentRemovesOnlyItsTypedUsageAndRollsBackAtomically() {
        val (user, first) = candidate(); database.send(user, first); database.save(first.copy(status = RunStatus.COMPLETED))
        val secondRun = RunId(UUID.randomUUID().toString())
        val secondUser = user.copy(id = MessageId("u-${secondRun.value}"), runId = secondRun)
        val second = first.copy(id = MessageId("a-${secondRun.value}"), runId = secondRun)
        database.send(secondUser, second); database.save(second.copy(status = RunStatus.COMPLETED))
        val db = database.writableDatabase
        DatabaseTransactions { db }.run {
            for (parent in listOf(first, second)) for (source in listOf(UsageRequestSource.TITLE, UsageRequestSource.AUXILIARY_VISION)) {
                val request = RequestCheckpoint(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "fixture", RequestOperation.NEXT)
                SQLiteAdditionalUsageStore.admit(it, parent.conversationId.value, parent.runId.value,
                    parent.id.value, request, source, "fixture-process")
            }
            it.execSQL("INSERT INTO context_usage(request_id,run_id,branch_id,input_tokens) VALUES(?,?,?,?)",
                arrayOf<Any?>("legacy-delete-fixture", first.runId.value, first.conversationId.value, 7L))
            val manual = RequestCheckpoint(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "fixture", RequestOperation.NEXT)
            SQLiteAdditionalUsageStore.admit(it, first.conversationId.value, null, null, manual,
                UsageRequestSource.MANUAL_SUMMARY, "fixture-process", "manual-delete-fixture")
        }
        fun count(where: String) = db.rawQuery("SELECT COUNT(*) FROM context_usage WHERE $where", null).use {
            assertTrue(it.moveToFirst()); it.getInt(0)
        }
        db.beginTransaction()
        try {
            assertEquals(1, db.delete("messages", "id=?", arrayOf(first.id.value)))
            assertEquals(2, count("usage_source IN ('TITLE','AUXILIARY_VISION')"))
        } finally { db.endTransaction() }
        assertEquals(4, count("usage_source IN ('TITLE','AUXILIARY_VISION')"))
        DatabaseTransactions { db }.run { assertEquals(1, it.delete("messages", "id=?", arrayOf(first.id.value))) }
        assertEquals(2, count("usage_source IN ('TITLE','AUXILIARY_VISION')"))
        assertEquals(1, count("usage_source='LEGACY'")); assertEquals(1, count("usage_source='MANUAL_SUMMARY'"))
        DatabaseTransactions { db }.run {
            for (source in listOf(UsageRequestSource.TITLE, UsageRequestSource.AUXILIARY_VISION)) {
                val value = SQLiteAdditionalUsageStore.read(it, first.conversationId, source, 20)
                assertEquals(1, value.total); assertEquals(0, value.unreadable)
            }
        }
    }

    fun testSummaryMissingSurvivesHandoffWhenMainReportsAndReverseIsAlsoUnknown() {
        for (summaryReports in listOf(false, true)) {
            val (user, assistant) = candidate()
            val prep = runtime.summaryPreparation(assistant.conversationId, assistant.runId, assistant.id, ContextPreparationCancellation())
            prep.usagePort.claim()
            if (summaryReports) prep.usagePort.observe(ModelEvent.Usage(10, 2, 0))
            prep.seal(); database.send(user, assistant)
            assertEquals(RunStatus.COMPLETED, run(assistant, if (summaryReports) null else ModelEvent.Usage(20, 4, 0)).status)
            prep.close()
            val value = read(assistant.conversationId)
            assertEquals(1L, value.summary.combined.requestSnapshotCount)
            assertEquals(if (summaryReports) 10L else 20L, value.summary.combined.input.reportedTokens)
            assertEquals(if (summaryReports) 0 else 1, value.parentCoverage.preparationsWithoutUsage)
            assertEquals(if (summaryReports) 1 else 0, value.parentCoverage.mainRequestsWithoutUsage)
            assertEquals(if (summaryReports) 1 else 0, value.parentCoverage.messagesWithoutUsage)
            assertTrue(checkpoint(assistant).usageRequests.size == 1)
            assertEquals(if (summaryReports) UsageRequestSource.SUMMARY else UsageRequestSource.MAIN, value.requests.single().source)
            // Snapshot count does not include a fabricated all-null/zero request.
            assertEquals(0L, value.summary.combined.input.unknownRequestCount)
            DatabaseTransactions { database.writableDatabase }.run { db ->
                val json = app.daiyu.android.context.RecordedContextUsageAdapter.read(db, assistant.conversationId.value, null).toJson()
                assertTrue(json.getBoolean("incomplete"))
                val coverage = json.getJSONObject("coverage")
                assertEquals(if (summaryReports) 0 else 1, coverage.getJSONObject("preparation").getInt("without_usage"))
                assertEquals(if (summaryReports) 1 else 0, coverage.getJSONObject("parent").getInt("requests_without_usage"))
            }
        }
    }

    fun testAuxiliaryNullableReplacementAttemptFenceBudgetAndReopenDoNotReplay() {
        val (user, assistant) = candidate(); database.send(user, assistant)
        val attempt = runtime.auxiliaryUsage(assistant)
        assertEquals(1, read(assistant.conversationId).parentCoverage.auxiliaryRequestsWithoutUsage)
        attempt.observe(ModelEvent.Usage(10, 5, 2))
        attempt.observe(ModelEvent.Usage(null, 0, null))
        attempt.observe(ModelEvent.Usage(null, 0, null)) // idempotent same snapshot
        assertNull(read(assistant.conversationId).summary.combined.input.reportedTokens)
        assertEquals(0L, read(assistant.conversationId).summary.combined.output.reportedTokens)
        attempt.close(); reject { attempt.observe(ModelEvent.Usage(99, 99, 0)) }
        val unknown = runtime.auxiliaryUsage(assistant)
        unknown.close()
        val value = read(assistant.conversationId)
        assertEquals(2, value.parentCoverage.totalAuxiliaryRequests)
        assertEquals(1, value.parentCoverage.auxiliaryRequestsWithoutUsage)
        assertEquals(1L, value.summary.combined.requestSnapshotCount)
        assertEquals(UsageRequestSource.AUXILIARY_VISION, value.requests.single().source)
        assertEquals(assistant.runId.value, value.requests.single().parentRunId)
        assertEquals(assistant.id.value, value.requests.single().parentMessageId)
        val bounded = read(assistant.conversationId, RecordedUsageBudget(maxMessages = 1))
        assertEquals(1, bounded.parentCoverage.inspectedAuxiliaryRequests)
        assertFalse(bounded.scanComplete)
        val abandoned = runtime.auxiliaryUsage(assistant)
        runtime.close(); database.close()
        database = ConversationDatabase(context)
        runtime = SQLiteRuntimeStore.acquire(database).also { it.recoverPending() }
        reject { abandoned.observe(ModelEvent.Usage(500, 500, 0)) }
        assertEquals(3, read(assistant.conversationId).parentCoverage.totalAuxiliaryRequests)
        assertEquals(2, read(assistant.conversationId).parentCoverage.auxiliaryRequestsWithoutUsage)
        assertEquals(1L, read(assistant.conversationId).summary.combined.requestSnapshotCount)
        database.delete(assistant.conversationId)
        assertEquals(0, DatabaseTransactions { database.writableDatabase }.run { RecordedUsageReader.readAttributed(it).parentCoverage.totalAuxiliaryRequests })
    }

    fun testTitleAfterTerminalIsAtomicCountedOnceAndNeverRewritesTerminalCheckpoint() {
        val (user, assistant) = candidate(); database.send(user, assistant)
        assertEquals(RunStatus.COMPLETED, run(assistant, ModelEvent.Usage(30, 5, 0)).status)
        val terminal = checkpoint(assistant)
        val commits = SqliteTitleCommitAdapter({ database.writableDatabase }, object : ParentTitleUsagePort {
            override fun beginRequest(db: android.database.sqlite.SQLiteDatabase, receipt: ParentTitleUsage) = SQLiteAdditionalUsageStore.beginTitle(db, receipt)
            override fun observeRequest(db: android.database.sqlite.SQLiteDatabase, receipt: ParentTitleUsage) = SQLiteAdditionalUsageStore.observeTitle(db, receipt)
            override fun recordAfterTitleSaved(db: android.database.sqlite.SQLiteDatabase, receipt: ParentTitleUsage) = SQLiteAdditionalUsageStore.finishTitle(db, receipt)
        })
        val revision = requireNotNull(commits.capture(assistant.conversationId))
        val request = RequestCheckpoint("title-${UUID.randomUUID()}", "attempt", "title-owner", RequestOperation.NEXT)
        val missing = ParentTitleUsage(request.requestId, assistant.conversationId, assistant.runId, null, request)
        commits.beginRequestUsage(missing)
        assertEquals(1, read(assistant.conversationId).parentCoverage.titleRequestsWithoutUsage)
        val reported = missing.copy(usage = ModelEvent.Usage(7, 2, 0))
        commits.observeRequestUsage(reported)
        assertEquals(37L, read(assistant.conversationId).summary.combined.input.reportedTokens)
        // A failed title/receipt transaction cannot erase previously durable observations.
        database.writableDatabase.execSQL("CREATE TRIGGER fail_p21_title BEFORE UPDATE ON context_usage WHEN NEW.usage_state='TERMINAL' BEGIN SELECT RAISE(ABORT,'fixture'); END")
        try { commits.commit(revision, "实际标题", reported); fail("failed title write acknowledged") }
        catch (_: app.daiyu.android.title.TitleUsagePersistenceException) {}
        assertEquals(revision, commits.capture(assistant.conversationId))
        assertEquals(37L, read(assistant.conversationId).summary.combined.input.reportedTokens)
        database.writableDatabase.execSQL("DROP TRIGGER fail_p21_title")
        assertTrue(commits.commit(revision, "实际标题", reported))
        commits.recordRequestUsage(reported) // receipt replay is idempotent, NOT another request
        val after = read(assistant.conversationId)
        assertEquals(2L, after.summary.combined.requestSnapshotCount)
        assertEquals(37L, after.summary.combined.input.reportedTokens)
        assertEquals(1, after.requests.count { it.source == UsageRequestSource.TITLE })
        assertEquals(terminal, checkpoint(assistant))
        val failedRequest = RequestCheckpoint("title-${UUID.randomUUID()}", "attempt-2", "title-owner-2", RequestOperation.NEXT)
        val failed = missing.copy(requestId = failedRequest.requestId, request = failedRequest)
        commits.beginRequestUsage(failed); commits.recordRequestUsage(failed)
        assertEquals(1, read(assistant.conversationId).parentCoverage.titleRequestsWithoutUsage)
        assertEquals(2L, read(assistant.conversationId).summary.combined.requestSnapshotCount)
        assertEquals(terminal, checkpoint(assistant))
    }

    fun testMalformedPreparationParentIsIsolatedEvenWhenItsUsageIsMissing() {
        val (_, assistant) = candidate()
        val prep = runtime.summaryPreparation(assistant.conversationId, assistant.runId, assistant.id, ContextPreparationCancellation())
        prep.usagePort.claim(); prep.usagePort.observe(ModelEvent.Usage(7, 1, 0)); prep.close()
        val db = database.writableDatabase
        for ((index, hasUsage) in listOf(0, 1).withIndex()) {
            db.execSQL("INSERT INTO runtime_preparations(message_id,conversation_id,run_id,owner,process_owner,revision,state,request_id,request_owner,attempt_id,has_usage,input_tokens,output_tokens,cached_tokens) VALUES(?,?,'','owner','process',1,'TERMINAL',?,'owner','attempt',?,9,1,0)",
                arrayOf<Any?>("bad-parent-$index", assistant.conversationId.value, "bad-request-$index", hasUsage))
        }
        // Corrupt identity and INTEGER storage classes independently; getLong() must not
        // normalize REAL 1.5/0.5 into an apparently valid revision or report-presence bit.
        for (id in listOf("bad-message", "bad-revision", "bad-has-usage")) {
            db.execSQL("INSERT INTO runtime_preparations SELECT ?,conversation_id,run_id,owner,process_owner,revision,state,?,request_owner,attempt_id,has_usage,input_tokens,output_tokens,cached_tokens FROM runtime_preparations WHERE message_id=?",
                arrayOf(id, "request-$id", assistant.id.value))
        }
        db.execSQL("UPDATE runtime_preparations SET message_id='' WHERE message_id='bad-message'")
        db.execSQL("UPDATE runtime_preparations SET revision=1.5 WHERE message_id='bad-revision'")
        db.execSQL("UPDATE runtime_preparations SET has_usage=0.5 WHERE message_id='bad-has-usage'")
        val value = read(assistant.conversationId)
        assertEquals(6, value.parentCoverage.inspectedPreparations)
        assertEquals(5, value.parentCoverage.unreadablePreparations)
        assertEquals(0, value.parentCoverage.preparationsWithoutUsage)
        assertEquals(1L, value.summary.combined.requestSnapshotCount)
        assertEquals(7L, value.summary.combined.input.reportedTokens)
        assertFalse(value.scanComplete)
    }

    fun testV16HelperUpgradeInstallsBeforeEarlyReturnAndRetainsLegacyNullAndZero() {
        runtime.close()
        val db = database.writableDatabase
        db.execSQL("DROP TABLE runtime_preparations")
        db.execSQL("DROP TABLE child_request_usage")
        db.execSQL("CREATE TABLE child_request_usage(task_id TEXT NOT NULL,request_id TEXT NOT NULL,request_owner TEXT NOT NULL,attempt_id TEXT NOT NULL,operation TEXT NOT NULL,checkpoint_revision INTEGER NOT NULL,input_tokens INTEGER,output_tokens INTEGER,cached_tokens INTEGER,PRIMARY KEY(task_id,request_id))")
        db.execSQL("INSERT INTO child_request_usage VALUES('legacy-child','opaque','owner','attempt','NEXT',1,NULL,0,NULL)")
        db.execSQL("DROP TABLE context_usage")
        db.execSQL("CREATE TABLE context_usage(request_id TEXT PRIMARY KEY,run_id TEXT NOT NULL,branch_id TEXT NOT NULL,input_tokens INTEGER,output_tokens INTEGER,cached_tokens INTEGER,reasoning_tokens INTEGER)")
        db.execSQL("INSERT INTO context_usage(request_id,run_id,branch_id,input_tokens,output_tokens) VALUES('title:legacy','old','old',NULL,0)")
        db.version = 16
        database.close(); database = ConversationDatabase(context)
        val upgraded = database.writableDatabase
        assertEquals(22, upgraded.version)
        upgraded.rawQuery("SELECT usage_source,input_tokens,output_tokens,cached_tokens FROM child_request_usage", null).use {
            assertTrue(it.moveToFirst()); assertEquals("MAIN", it.getString(0)); assertTrue(it.isNull(1)); assertEquals(0L, it.getLong(2)); assertTrue(it.isNull(3))
        }
        upgraded.rawQuery("SELECT usage_source,input_tokens,output_tokens FROM context_usage WHERE request_id='title:legacy'", null).use {
            assertTrue(it.moveToFirst()); assertEquals("LEGACY", it.getString(0)); assertTrue(it.isNull(1)); assertEquals(0L, it.getLong(2))
        }
        upgraded.rawQuery("SELECT COUNT(*) FROM runtime_preparations", null).use { it.moveToFirst(); assertEquals(0L, it.getLong(0)) }
        runtime = SQLiteRuntimeStore.acquire(database).also { it.recoverPending() }
    }
}
