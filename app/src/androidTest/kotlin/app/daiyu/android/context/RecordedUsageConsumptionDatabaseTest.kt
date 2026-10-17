package app.daiyu.android.context

import android.content.ContentValues
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.domain.context.UsageRecord
import app.daiyu.android.domain.delegation.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.storage.*
import app.daiyu.android.testContext
import app.daiyu.android.ui.RetainedConversationData
import org.json.JSONObject
import java.util.UUID

/** Production projections against real SQLite; provider calls are in-process offline fixtures. */
@Suppress("DEPRECATION")
class RecordedUsageConsumptionDatabaseTest : InstrumentationTestCase() {
    private lateinit var context: android.content.Context
    private lateinit var database: ConversationDatabase
    private lateinit var runtime: SQLiteRuntimeStore
    override fun setUp() {
        super.setUp()
        context = testContext(instrumentation.targetContext, "usage-consumption-${UUID.randomUUID()}-")
        database = ConversationDatabase(context)
        runtime = SQLiteRuntimeStore.acquire(database).also { it.recoverPending() }
    }
    override fun tearDown() {
        try { runtime.close(); database.close(); context.deleteDatabase("conversations.db") }
        finally { super.tearDown() }
    }
    private fun service() = ContextService(context, database)
    private fun stats() = RetainedConversationData(database, service()).stats()
    private fun changes(): Long = database.readableDatabase.rawQuery("SELECT total_changes()", null).use {
        it.moveToFirst(); it.getLong(0)
    }
    private fun parent(conversation: ConversationId = database.create("default").id): RunExecutionOwner {
        val run = RunId(UUID.randomUUID().toString())
        val user = Message(MessageId("u-${run.value}"), conversation, run, "user", emptyList(), RunStatus.COMPLETED)
        val assistant = user.copy(id = MessageId("a-${run.value}"), role = "assistant", status = RunStatus.RUNNING)
        database.send(user, assistant)
        var result: RunExecutionOwner? = null
        val receiver = object : ReceiptBatchToolExecutor {
            override fun bindOwner(owner: RunExecutionOwner) { result = owner }
            override fun execute(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean) = error("no tools")
        }
        ChatRunner(ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {}
            override fun cancel() {}
        } }, object : ConversationStore { override fun save(message: Message) {} }, BatteryTool { error("no tools") },
            RunnerOptions(receiptBatchExecutor = receiver)) {}.run(assistant, emptyList())
        return checkNotNull(result)
    }
    private fun saveParent(owner: RunExecutionOwner, value: ModelEvent.Usage): RunCheckpoint {
        val assistant = database.messages(owner.conversationId).map { it.second }.single { it.id == owner.assistantMessageId }
        val request = RequestCheckpoint("parent-request", "attempt", owner.ownerId, RequestOperation.NEXT)
        return RunCheckpoint(assistant, emptyList(), ExecutionPhase.STREAMING, owner = owner.ownerId,
            revision = 1, request = request, usage = listOf(RequestUsageCheckpoint(request, value)))
            .also { runtime.commitCheckpoint(it) }
    }
    private fun child(owner: RunExecutionOwner, value: ModelEvent.Usage): Pair<ChildTaskRecord, RunCheckpoint> {
        val task = ChildTaskRecord(ChildRunOwner(owner.conversationId.value, owner.runId.value,
            UUID.randomUUID().toString(), 1, UUID.randomUUID().toString(), UUID.randomUUID().toString()),
            owner.assistantMessageId.value, owner.ownerId, 0, "review-not-a-model", "question")
        runtime.childTasks.enqueue(owner, listOf(task)); runtime.childTasks.started(task)
        val request = RequestCheckpoint("child-request", "attempt", "child-owner", RequestOperation.NEXT)
        val checkpoint = RunCheckpoint(Message(MessageId(task.owner.assistantMessageId), owner.conversationId,
            RunId(task.owner.childRunId), "assistant", emptyList(), RunStatus.RUNNING), emptyList(), ExecutionPhase.STREAMING,
            owner = request.owner, revision = 1, request = request, usage = listOf(RequestUsageCheckpoint(request, value)))
        runtime.childUsage.record(task, checkpoint)
        return task to checkpoint
    }
    private fun query(owner: RunExecutionOwner, run: String? = null): JSONObject {
        val args = JSONObject().put("branch_id", owner.conversationId.value)
        if (run != null) args.put("run_id", run)
        return ContextRegistry(service()).forModel(ContextRegistryScope(owner.conversationId)).execute("context_usage", args)
    }

    fun testStatsAndContextConsumeSameCommittedParentChildReaderWithoutWritingOrRecovering() {
        val owner = parent(); val checkpoint = saveParent(owner, ModelEvent.Usage(100, 20, 40))
        child(owner, ModelEvent.Usage(10, 3, 2))
        service().recordUsage(UsageRecord("legacy-must-not-be-added", owner.runId.value, owner.conversationId.value, 900, 800, 700))
        val before = changes()
        repeat(2) {
            val snapshot = stats(); val projection = snapshot.recordedProjection!!
            assertEquals(110L, projection.combined.input.reportedTokens)
            assertEquals(42L, projection.combined.cached.reportedTokens)
            assertEquals(2L, projection.combined.requestSnapshotCount)
            assertEquals(snapshot.recordedUsage, snapshot.attributedUsage!!.parentCoverage)
            assertTrue(snapshot.modelUsage.providers.isEmpty()); assertFalse(projection.historicalModelAvailable)
            val json = query(owner)
            assertTrue(json.toString(), json.getBoolean("ok"))
            assertEquals(projection.combined.input.reportedTokens, json.getLong("input_tokens"))
            assertEquals(133L, json.getLong("total_tokens")) // cached tokens are not added again
            assertEquals("provider_reported", json.getString("source"))
            assertEquals("runtime_checkpoints+child_request_usage+runtime_preparations+typed_context_usage", json.getString("record_source"))
            assertTrue(json.isNull("reasoning_tokens"))
            assertEquals(1, json.getJSONObject("coverage").getJSONObject("child").getInt("total_tasks"))
            assertEquals(checkpoint, runtime.readCommittedCheckpoint(checkpoint))
        }
        assertEquals(before, changes())
    }

    fun testResumeNullCorrectionMissingAndCorruptRowsStayUnknownAndQueriesRemainScoped() {
        val owner = parent(); val checkpoint = saveParent(owner, ModelEvent.Usage(100, 20, 5))
        val (task, child) = child(owner, ModelEvent.Usage(10, 20, 5))
        val resumed = child.request!!.copy(attemptId = "resume-attempt", operation = RequestOperation.RESUME)
        runtime.childUsage.record(task, child.copy(revision = 2, request = resumed,
            usage = listOf(RequestUsageCheckpoint(resumed, ModelEvent.Usage(null, 0, 0)))))
        val sibling = parent(owner.conversationId); saveParent(sibling, ModelEvent.Usage(900, 800, 700))
        val selected = query(owner, owner.runId.value)
        assertEquals(100L, selected.getLong("input_tokens")); assertEquals(20L, selected.getLong("output_tokens"))
        assertTrue(selected.isNull("total_tokens")); assertTrue(selected.getBoolean("incomplete"))
        assertFalse(selected.getJSONObject("coverage").getBoolean("counts_available"))
        assertEquals(1L, query(owner, task.owner.childRunId).getLong("requests"))
        // Good child's reported zero survives a corrupt parent; no fake input zero is created.
        database.writableDatabase.update("runtime_checkpoints", ContentValues().apply { put("payload", "{}") },
            "message_id=?", arrayOf(checkpoint.message.id.value))
        parent() // A retained row without checkpoint, in another conversation.
        val value = stats().recordedProjection!!
        assertEquals(1, value.attributed.parentCoverage.unreadableCheckpoints)
        assertEquals(1, value.attributed.parentCoverage.messagesWithoutCheckpoint)
        val scoped = query(owner, owner.runId.value)
        assertTrue(scoped.isNull("input_tokens")); assertEquals(0L, scoped.getLong("output_tokens"))
        assertEquals(0L, scoped.getLong("cached_tokens")); assertTrue(scoped.isNull("total_tokens"))
        val other = database.create("default")
        val denied = ContextRegistry(service()).forModel(ContextRegistryScope(other.id)).execute("context_usage",
            JSONObject().put("branch_id", owner.conversationId.value))
        assertFalse(denied.getBoolean("ok"))
        service().recordUsage(UsageRecord("legacy-only", "unadmitted-run", owner.conversationId.value, 5, 6))
        assertFalse(service().isUsageOwner(owner.conversationId.value, "unadmitted-run"))
    }

    fun testReopenAndDeleteDoNotLeaveAProjectionLedger() {
        val owner = parent(); saveParent(owner, ModelEvent.Usage(null, 0, null)); child(owner, ModelEvent.Usage(7, 9, 2))
        val before = stats().attributedUsage
        runtime.close(); database.close(); database = ConversationDatabase(context)
        assertEquals(before, stats().attributedUsage)
        assertEquals(7L, query(owner).getLong("input_tokens"))
        database.delete(owner.conversationId)
        val empty = stats().recordedProjection!!
        assertEquals(0L, empty.combined.requestSnapshotCount); assertNull(empty.combined.input.reportedTokens)
        assertEquals(0, empty.attributed.childCoverage.totalTasks)
    }
}
