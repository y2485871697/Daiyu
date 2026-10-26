package app.daiyu.android.model.usage

import android.content.ContentValues
import android.test.InstrumentationTestCase
import app.daiyu.android.context.DatabaseTransactions
import app.daiyu.android.domain.*
import app.daiyu.android.domain.delegation.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.storage.*
import app.daiyu.android.testContext
import java.util.UUID

@Suppress("DEPRECATION")
class AttributedUsageDatabaseTest : InstrumentationTestCase() {
    private lateinit var context: android.content.Context
    private lateinit var database: ConversationDatabase
    private lateinit var runtime: SQLiteRuntimeStore
    override fun setUp() {
        super.setUp()
        context = testContext(instrumentation.targetContext, "attributed-usage-${UUID.randomUUID()}-")
        database = ConversationDatabase(context)
        runtime = SQLiteRuntimeStore.acquire(database).also { it.recoverPending() }
    }
    override fun tearDown() {
        try { runtime.close(); database.close(); context.deleteDatabase("conversations.db") }
        finally { super.tearDown() }
    }
    private fun parent(): RunExecutionOwner {
        val conversation = database.create("default"); val run = RunId(UUID.randomUUID().toString())
        val user = Message(MessageId(UUID.randomUUID().toString()), conversation.id, run, "user", emptyList(), RunStatus.COMPLETED)
        val assistant = user.copy(id = MessageId(UUID.randomUUID().toString()), role = "assistant", status = RunStatus.RUNNING)
        database.send(user, assistant)
        var result: RunExecutionOwner? = null
        val receiver = object : ReceiptBatchToolExecutor {
            override fun bindOwner(owner: RunExecutionOwner) { result = owner }
            override fun execute(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean) = error("no tools")
        }
        val runner = ChatRunner(ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {}
            override fun cancel() {}
        } }, object : ConversationStore { override fun save(message: Message) {} }, BatteryTool { error("no tools") },
            RunnerOptions(receiptBatchExecutor = receiver)) {}
        assertEquals(RunStatus.COMPLETED, runner.run(assistant, emptyList()).status)
        return checkNotNull(result)
    }
    private fun task(parent: RunExecutionOwner, started: Boolean = true): ChildTaskRecord {
        val task = ChildTaskRecord(ChildRunOwner(parent.conversationId.value, parent.runId.value,
            UUID.randomUUID().toString(), 1, UUID.randomUUID().toString(), UUID.randomUUID().toString()),
            parent.assistantMessageId.value, parent.ownerId, 0, "review", "question")
        runtime.childTasks.enqueue(parent, listOf(task)); if (started) runtime.childTasks.started(task)
        return task
    }
    private fun checkpoint(task: ChildTaskRecord, revision: Long = 1, value: ModelEvent.Usage = ModelEvent.Usage(10, 20, 5)): RunCheckpoint {
        val request = RequestCheckpoint("request", "attempt", "child-owner", RequestOperation.NEXT)
        return RunCheckpoint(Message(MessageId(task.owner.assistantMessageId), ConversationId(task.owner.conversationId),
            RunId(task.owner.childRunId), "assistant", emptyList(), RunStatus.RUNNING), emptyList(), ExecutionPhase.STREAMING,
            owner = request.owner, revision = revision, request = request, usage = listOf(RequestUsageCheckpoint(request, value)))
    }
    private fun read(conversation: ConversationId? = null, budget: RecordedUsageBudget = RecordedUsageBudget()) =
        DatabaseTransactions { database.readableDatabase }.run { RecordedUsageReader.readAttributed(it, conversation, budget) }
    private fun rejects(work: () -> Unit) { try { work(); fail("must reject") } catch (_: Exception) {} }

    fun testCacheCreationSurvivesReopening() {
        val owner = parent(); val child = task(owner)
        val first = checkpoint(child, value = ModelEvent.Usage(17, 8, 5, 2))
        runtime.childUsage.record(child, first)
        assertEquals(2L, read(owner.conversationId).summary.combined.creation.reportedTokens)
        runtime.close(); database.close()
        database = ConversationDatabase(context)
        runtime = SQLiteRuntimeStore.acquire(database).also { it.recoverPending() }
        assertEquals(2L, read(owner.conversationId).requests.single().snapshot.cacheCreationTokens)
    }
    fun testV22UpgradeAddsUnknownCreationWithoutChangingExistingUsage() {
        val owner = parent(); val child = task(owner)
        runtime.childUsage.record(child, checkpoint(child))
        val before = read(owner.conversationId).summary.combined
        runtime.close(); database.close()
        val tables = listOf("child_request_usage", "runtime_preparations", "context_usage", "recorded_usage_requests")
        android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath("conversations.db").path,
            null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.beginTransaction()
            try {
                tables.forEach { table ->
                    val sql = db.rawQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table))
                        .use { check(it.moveToFirst()); it.getString(0) }
                    val indexes = db.rawQuery("SELECT sql FROM sqlite_master WHERE type='index' AND tbl_name=? AND sql IS NOT NULL",
                        arrayOf(table)).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
                    val columns = db.rawQuery("PRAGMA table_info($table)", null).use { c -> buildList {
                        while (c.moveToNext()) if (c.getString(1) != "cache_creation_tokens") add(c.getString(1))
                    } }.joinToString(",")
                    db.execSQL(sql.replace(table, "old_$table").replace(",cache_creation_tokens INTEGER", ""))
                    db.execSQL("INSERT INTO old_$table($columns) SELECT $columns FROM $table")
                    db.execSQL("DROP TABLE $table")
                    db.execSQL("ALTER TABLE old_$table RENAME TO $table")
                    indexes.forEach(db::execSQL)
                }
                db.version = 22
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        database = ConversationDatabase(context)
        runtime = SQLiteRuntimeStore.acquire(database).also { it.recoverPending() }
        assertEquals(28, database.readableDatabase.version)
        tables.forEach { table ->
            database.readableDatabase.rawQuery("SELECT cache_creation_tokens FROM $table", null).use { c ->
                while (c.moveToNext()) assertTrue(c.isNull(0))
            }
        }
        val after = read(owner.conversationId).summary.combined
        assertEquals(before.input, after.input); assertEquals(before.output, after.output)
        assertEquals(before.cached, after.cached); assertNull(after.creation.reportedTokens)
    }

    fun testMainAndChildQueriesShareOneTransactionAndKeepRequestAttribution() {
        val parent = parent(); val child = task(parent)
        runtime.childUsage.record(child, checkpoint(child))
        val assistant = database.messages(parent.conversationId).map { it.second }.single { it.role == "assistant" }
        val request = RequestCheckpoint("request", "attempt", parent.ownerId, RequestOperation.NEXT)
        runtime.commitCheckpoint(RunCheckpoint(assistant, emptyList(), ExecutionPhase.STREAMING, owner = parent.ownerId,
            revision = 1, request = request, usage = listOf(RequestUsageCheckpoint(request, ModelEvent.Usage(100, 40, 25)))))
        val first = read(parent.conversationId)
        assertEquals(100L, first.summary.main.input.reportedTokens); assertEquals(10L, first.summary.child.input.reportedTokens)
        assertEquals(110L, first.summary.combined.input.reportedTokens); assertEquals(30L, first.summary.combined.cached.reportedTokens)
        assertEquals(setOf(parent.runId.value, child.owner.childRunId), first.requests.map { it.runId }.toSet())
        assertEquals(2L, first.summary.combined.requestSnapshotCount)
        assertEquals(0L, read(ConversationId("missing")).summary.combined.requestSnapshotCount)
        rejects { RecordedUsageReader.readAttributed(database.readableDatabase) }
    }
    fun testResumeReplacesNullAndLowerCountsWhileDifferentRequestsStaySeparate() {
        val owner = parent(); val child = task(owner); val first = checkpoint(child)
        runtime.childUsage.record(child, first)
        val resumed = first.request!!.copy(attemptId = "resumed", operation = RequestOperation.RESUME)
        val second = first.copy(revision = 2, request = resumed,
            usage = listOf(RequestUsageCheckpoint(resumed, ModelEvent.Usage(null, 0, 0))))
        runtime.childUsage.record(child, second)
        assertNull(read().summary.child.input.reportedTokens); assertEquals(0L, read().summary.child.output.reportedTokens)
        val next = resumed.copy(requestId = "next", attemptId = "next", operation = RequestOperation.NEXT)
        runtime.childUsage.record(child, second.copy(revision = 3, request = next,
            usage = second.usage + RequestUsageCheckpoint(next, ModelEvent.Usage(10, 20, 5))))
        assertEquals(2L, read().summary.child.requestSnapshotCount)
        assertEquals(10L, read().summary.child.input.reportedTokens); assertEquals(1L, read().summary.child.input.unknownRequestCount)
        rejects { runtime.childUsage.record(child, first) }
    }
    fun testChildSummaryPurposeIsExplicitDurableAndCannotBeRelabelledByRequestPrefix() {
        val owner = parent(); val child = task(owner)
        val base = checkpoint(child)
        val summary = base.copy(usage = base.usage.map { it.copy(source = UsageRequestSource.SUMMARY) })
        runtime.childUsage.record(child, summary)
        val row = read(owner.conversationId).requests.single()
        assertEquals(UsageRequestSource.SUMMARY, row.source)
        assertEquals(owner.runId.value, row.parentRunId)
        assertTrue(row.origin is app.daiyu.android.domain.usage.UsageOrigin.Child)
        rejects { runtime.childUsage.record(child, base.copy(revision = 2)) }
        assertEquals(UsageRequestSource.SUMMARY, read().requests.single().source)
    }
    fun testWholeSnapshotRollsBackOnSecondRequestWriteFailure() {
        val child = task(parent()); val first = checkpoint(child); val second = first.request!!.copy(requestId = "second")
        database.writableDatabase.execSQL("CREATE TRIGGER usage_insert_failure BEFORE INSERT ON child_request_usage" +
            " WHEN NEW.request_id='second' BEGIN SELECT RAISE(ABORT,'fixture'); END")
        rejects { runtime.childUsage.record(child, first.copy(usage = first.usage + RequestUsageCheckpoint(second, ModelEvent.Usage(1, 2)))) }
        assertEquals(0L, read().summary.child.requestSnapshotCount)
        assertEquals(1, read().childCoverage.tasksWithoutReportedUsage)
    }
    fun testForeignOwnersLateWritesAndDroppedRequestsAreRejected() {
        val child = task(parent()); val first = checkpoint(child)
        runtime.childUsage.record(child, first)
        rejects { runtime.childUsage.record(child.copy(parentOwnerId = "foreign"), first.copy(revision = 2)) }
        val foreign = first.request!!.copy(owner = "other-owner")
        rejects { runtime.childUsage.record(child, first.copy(owner = "other-owner", revision = 2,
            request = foreign, usage = listOf(RequestUsageCheckpoint(foreign, ModelEvent.Usage(1, 1))))) }
        val other = first.request!!.copy(requestId = "other-request")
        rejects { runtime.childUsage.record(child, first.copy(revision = 2, request = other,
            usage = listOf(RequestUsageCheckpoint(other, ModelEvent.Usage(1, 1))))) }
        runtime.childTasks.finished(child, ChildTaskStatus.UNKNOWN, detail = "network")
        rejects { runtime.childUsage.record(child, first.copy(revision = 2)) }
        assertEquals(10L, read().summary.child.input.reportedTokens)
    }
    fun testReopenPreservesUsageFencesOldLeaseAndDeletionCascades() {
        val owner = parent(); val child = task(owner); runtime.childUsage.record(child, checkpoint(child))
        val old = runtime; old.close(); database.close()
        runtime = SQLiteRuntimeStore.acquire(database).also { it.recoverPending() }
        rejects { old.childUsage.record(child, checkpoint(child, 2)) }
        assertEquals(ChildTaskStatus.UNKNOWN, runtime.childTasks.list(owner.conversationId.value).single().status)
        assertEquals(10L, read().summary.child.input.reportedTokens)
        database.delete(owner.conversationId)
        assertEquals(0, read().childCoverage.totalTasks); assertTrue(read().requests.isEmpty())
    }
    fun testMissingQueuedCorruptAndBudgetLimitedTasksAreNotKnownZero() {
        val owner = parent(); task(owner, started = false); task(owner)
        val good = task(owner); runtime.childUsage.record(good, checkpoint(good))
        val bad = task(owner); runtime.childUsage.record(bad, checkpoint(bad))
        database.writableDatabase.update("child_request_usage", ContentValues().apply { put("input_tokens", "invalid") },
            "task_id=?", arrayOf(bad.owner.childRunId))
        val value = read()
        assertEquals(1, value.childCoverage.notStartedTasks); assertEquals(1, value.childCoverage.tasksWithoutReportedUsage)
        assertEquals(1, value.childCoverage.unreadableTasks); assertEquals(10L, value.summary.child.input.reportedTokens)
        assertFalse(value.scanComplete)
        val limited = read(budget = RecordedUsageBudget(maxMessages = 1))
        assertEquals(4, limited.childCoverage.totalTasks); assertEquals(1, limited.childCoverage.inspectedTasks)
        assertFalse(limited.scanComplete)
        DatabaseTransactions { database.readableDatabase }.run {
            val (rows, coverage) = SQLiteChildUsageStore.read(it, null, 500, 0)
            assertTrue(rows.isEmpty()); assertEquals(2, coverage.deferredTasks)
            assertEquals(0, coverage.unreadableTasks)
        }
    }
    fun testVersionEightUpgradeKeepsTaskRowsAndReportsOldUsageAsMissing() {
        val owner = parent(); task(owner)
        runtime.close(); database.writableDatabase.execSQL("DROP TABLE child_request_usage")
        app.daiyu.android.removeAssistantSchemaForLegacyFixture(database.writableDatabase)
        database.writableDatabase.version = 8; database.close()
        runtime = SQLiteRuntimeStore.acquire(database).also { it.recoverPending() }
        assertEquals(28, database.readableDatabase.version)
        assertEquals(1, read().childCoverage.tasksWithoutReportedUsage)
        assertNull(read().summary.child.input.reportedTokens)
    }
}
