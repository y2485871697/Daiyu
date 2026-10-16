package app.daiyu.android.storage

import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.domain.delegation.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.testContext
import java.util.UUID

@Suppress("DEPRECATION")
class SQLiteChildTaskStoreTest : InstrumentationTestCase() {
    private val contexts = mutableListOf<android.content.Context>()
    private val databases = mutableListOf<ConversationDatabase>()
    private val leases = mutableListOf<SQLiteRuntimeStore>()
    private fun fresh(): ConversationDatabase {
        val context = testContext(instrumentation.targetContext, "child-record-${UUID.randomUUID()}-")
        contexts.add(context)
        return ConversationDatabase(context).also { databases.add(it) }
    }
    private fun lease(db: ConversationDatabase) = SQLiteRuntimeStore.acquire(db).also { leases.add(it); it.recoverPending() }
    private fun parent(db: ConversationDatabase): RunExecutionOwner {
        val conversation = db.create(); val run = RunId(UUID.randomUUID().toString())
        val user = Message(MessageId(UUID.randomUUID().toString()), conversation.id, run, "user",
            listOf(MessageBlock.Text("u", "question")), RunStatus.COMPLETED)
        val assistant = user.copy(id = MessageId(UUID.randomUUID().toString()), role = "assistant", blocks = emptyList(), status = RunStatus.RUNNING)
        db.send(user, assistant)
        var captured: RunExecutionOwner? = null
        val receiver = object : ReceiptBatchToolExecutor {
            override fun bindOwner(owner: RunExecutionOwner) { captured = owner }
            override fun execute(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean) = error("no tools")
        }
        val runner = ChatRunner(ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {}
            override fun cancel() {}
        } }, object : ConversationStore { override fun save(message: Message) {} }, BatteryTool { error("no tools") },
            RunnerOptions(receiptBatchExecutor = receiver)) {}
        assertEquals(RunStatus.COMPLETED, runner.run(assistant, emptyList()).status)
        return checkNotNull(captured)
    }
    private fun tasks(owner: RunExecutionOwner, count: Int = 3): List<ChildTaskRecord> {
        val group = UUID.randomUUID().toString()
        return (0 until count).map { index -> ChildTaskRecord(ChildRunOwner(owner.conversationId.value, owner.runId.value,
            group, 1, UUID.randomUUID().toString(), UUID.randomUUID().toString()), owner.assistantMessageId.value,
            owner.ownerId, index, "review", "task $index") }
    }
    private fun rejects(work: () -> Unit) { try { work(); fail("must reject") } catch (_: Exception) {} }
    override fun tearDown() {
        try {
            leases.forEach { it.close() }; databases.forEach { it.close() }
            contexts.forEach { it.deleteDatabase("conversations.db"); java.io.File(it.getDatabasePath("conversations.db").canonicalPath + ".runtime-owner").delete() }
        } finally { super.tearDown() }
    }

    fun testRecoveryPreservesCompletedAndFencesRunningAndQueuedWithoutReplay() {
        val db = fresh(); val first = lease(db); val owner = parent(db); val rows = tasks(owner)
        first.childTasks.enqueue(owner, rows)
        first.childTasks.started(rows[0]); first.childTasks.finished(rows[0], ChildTaskStatus.COMPLETED, "saved answer")
        first.childTasks.started(rows[1])
        first.close(); db.close()
        val next = lease(db)
        val restored = next.childTasks.list(owner.conversationId.value).associateBy { it.index }
        assertEquals(ChildTaskStatus.COMPLETED, restored.getValue(0).status); assertEquals("saved answer", restored.getValue(0).text)
        assertEquals(ChildTaskStatus.UNKNOWN, restored.getValue(1).status)
        assertEquals(ChildTaskStatus.NOT_EXECUTED, restored.getValue(2).status)
        for (row in rows) rejects { next.childTasks.started(row) }
        rejects { first.childTasks.finished(rows[1], ChildTaskStatus.COMPLETED, "late old owner") }
        assertEquals(restored, next.childTasks.list(owner.conversationId.value).associateBy { it.index })
    }

    fun testAtomicAdmissionAndResultWriteFailurePreserveRecoverableState() {
        val db = fresh(); val runtime = lease(db); val owner = parent(db); val rows = tasks(owner, 2)
        val before = db.messages(owner.conversationId)
        db.writableDatabase.execSQL("CREATE TRIGGER child_insert_failure BEFORE INSERT ON child_tasks WHEN NEW.task_index=1 BEGIN SELECT RAISE(ABORT,'fixture'); END")
        rejects { runtime.childTasks.enqueue(owner, rows) }
        assertTrue(runtime.childTasks.list(owner.conversationId.value).isEmpty()); assertEquals(before, db.messages(owner.conversationId))
        db.writableDatabase.execSQL("DROP TRIGGER child_insert_failure")
        runtime.childTasks.enqueue(owner, rows); runtime.childTasks.started(rows[0])
        db.writableDatabase.execSQL("CREATE TRIGGER child_result_failure BEFORE UPDATE ON child_tasks WHEN NEW.status='COMPLETED' BEGIN SELECT RAISE(ABORT,'fixture'); END")
        rejects { runtime.childTasks.finished(rows[0], ChildTaskStatus.COMPLETED, "must not publish") }
        assertEquals(ChildTaskStatus.RUNNING, runtime.childTasks.list(owner.conversationId.value).single { it.index == 0 }.status)
        db.writableDatabase.execSQL("DROP TRIGGER child_result_failure"); runtime.close(); db.close()
        val recovered = lease(db).childTasks.list(owner.conversationId.value)
        assertEquals(ChildTaskStatus.UNKNOWN, recovered.single { it.index == 0 }.status)
        assertEquals(ChildTaskStatus.NOT_EXECUTED, recovered.single { it.index == 1 }.status)
    }

    fun testOwnerIsolationTerminalImmutabilityAndParentDeletion() {
        val db = fresh(); val runtime = lease(db); val owner = parent(db); val rows = tasks(owner, 2)
        runtime.childTasks.enqueue(owner, rows)
        rejects { runtime.childTasks.started(rows[0].copy(parentOwnerId = "foreign")) }
        rejects { runtime.childTasks.finished(rows[0], ChildTaskStatus.COMPLETED, "not started") }
        runtime.childTasks.started(rows[0]); runtime.childTasks.finished(rows[0], ChildTaskStatus.COMPLETED, "answer")
        rejects { runtime.childTasks.finished(rows[0], ChildTaskStatus.UNKNOWN, detail = "late") }
        assertTrue(runtime.childTasks.list("another-conversation").isEmpty())
        assertEquals(1, runtime.childTasks.list(owner.conversationId.value, 1).size)
        rejects { runtime.childTasks.list(owner.conversationId.value, 21) }
        assertEquals(2, db.messages(owner.conversationId).size)
        db.delete(owner.conversationId)
        assertTrue(runtime.childTasks.list(owner.conversationId.value).isEmpty())
    }

    fun testVersionSevenUpgradeKeepsHistoryAndCreatesTaskSchema() {
        val db = fresh(); val owner = parent(db); val before = db.messages(owner.conversationId)
        db.writableDatabase.execSQL("DROP TABLE child_tasks"); db.writableDatabase.version = 7; db.close()
        assertEquals(8, db.readableDatabase.version); assertEquals(before, db.messages(owner.conversationId))
        assertTrue(SQLiteChildTaskStore(db).list(owner.conversationId.value, 20).isEmpty())
        val runtime = lease(db); val newOwner = parent(db); val rows = tasks(newOwner, 1)
        runtime.childTasks.enqueue(newOwner, rows)
        assertEquals(rows, runtime.childTasks.list(newOwner.conversationId.value))
    }
}
