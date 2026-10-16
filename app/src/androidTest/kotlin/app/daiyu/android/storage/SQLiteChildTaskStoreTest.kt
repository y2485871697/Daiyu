package app.daiyu.android.storage

import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.domain.delegation.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.domain.tools.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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
        val conversation = db.create("default"); val run = RunId(UUID.randomUUID().toString())
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
        db.writableDatabase.execSQL("DROP TABLE child_tasks"); app.daiyu.android.removeAssistantSchemaForLegacyFixture(db.writableDatabase); db.writableDatabase.version = 7; db.close()
        assertEquals(11, db.readableDatabase.version); assertEquals(before, db.messages(owner.conversationId))
        assertTrue(SQLiteChildTaskStore(db).list(owner.conversationId.value, 20).isEmpty())
        val runtime = lease(db); val newOwner = parent(db); val rows = tasks(newOwner, 1)
        runtime.childTasks.enqueue(newOwner, rows)
        assertEquals(rows, runtime.childTasks.list(newOwner.conversationId.value))
    }
    private class HeldChild : ModelPort {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        override fun open(history: List<Message>, definitions: List<app.daiyu.android.domain.ToolDefinition>): ModelSession {
            check(definitions.isEmpty())
            return object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                    sink(ModelEvent.TextFinal("text", "retained child answer"))
                }
                override fun cancel() { cancelled.countDown() }
            }
        }
        override fun open(history: List<Message>): ModelSession = error("explicit no-tools required")
    }

    private fun submitRetained(supervisor: RetainedChildTasks, binding: ChildTaskBinding, owner: RunExecutionOwner,
        caps: SessionCapabilities, auth: TaskAuthorization, profiles: List<String>): ChildTaskAdmission.Accepted {
        val result = AtomicReference<ChildTaskAdmission>()
        val tool = object : RegisteredTool {
            override val definition = app.daiyu.android.domain.tools.ToolDefinition("start_test_children", 1,
                "Submit through real dispatcher", "test.review", ToolEffect.READ_ONLY, ToolSchema())
            override fun prepare(arguments: ToolArguments) = PreparedTool.contextual { context ->
                val admitted = supervisor.submit(binding, context, profiles.map { ChildModelTask(it, "review") })
                result.set(admitted)
                check(admitted is ChildTaskAdmission.Accepted)
                ExecutionReceipt.Confirmed("queued")
            }
        }
        val dispatcher = ToolDispatcher(ToolRegistry().register(tool).snapshot(), caps, ToolLedger(InMemoryToolLedgerStore()))
        val receipt = dispatcher.dispatchOwned(auth, listOf(ToolInvocation(ToolCallId(UUID.randomUUID().toString()),
            "start_test_children", "{}")), owner).single()
        assertTrue(receipt.outcome is DispatchOutcome.Confirmed)
        return result.get() as ChildTaskAdmission.Accepted
    }

    private fun awaitChildren(supervisor: RetainedChildTasks, binding: ChildTaskBinding) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (supervisor.snapshots(binding).any { !it.settled } && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(supervisor.snapshots(binding).all { it.settled })
    }

    private fun closeChildren(supervisor: RetainedChildTasks) {
        val idle = CountDownLatch(1); supervisor.closeWhenIdle { idle.countDown() }
        assertTrue(idle.await(5, TimeUnit.SECONDS))
    }

    fun testRetainedChildCompletesAfterParentCompletionAndSurvivesDatabaseReopen() {
        val db = fresh(); val runtime = lease(db); val owner = parent(db); val child = HeldChild()
        val supervisor = RetainedChildTasks(runtime.childTasks)
        val caps = SessionCapabilities("child-session", setOf("test.review"))
        val auth = TaskAuthorization(caps.sessionId, owner.runId.value)
        val binding = supervisor.bind(owner, caps, auth, mapOf("review" to ChildModelDefinition("pool", 1) { child }))
        try {
            submitRetained(supervisor, binding, owner, caps, auth, listOf("review"))
            assertTrue(child.entered.await(3, TimeUnit.SECONDS))
            val parentMessage = db.messages(owner.conversationId).single { it.second.id == owner.assistantMessageId }.second
            db.save(parentMessage.copy(status = RunStatus.COMPLETED))
            supervisor.finishParent(binding)
            assertEquals(1L, child.cancelled.count)
            child.release.countDown(); awaitChildren(supervisor, binding)
            val row = runtime.childTasks.list(owner.conversationId.value).single()
            assertEquals(ChildTaskStatus.COMPLETED, row.status); assertEquals("retained child answer", row.text)
            closeChildren(supervisor); runtime.close(); db.close()
            val restored = lease(db).childTasks.list(owner.conversationId.value).single()
            assertEquals(row, restored)
            assertEquals(RunStatus.COMPLETED, db.messages(owner.conversationId).single { it.second.id == owner.assistantMessageId }.second.status)
        } finally { child.release.countDown(); closeChildren(supervisor) }
    }

    fun testIndependentChildStopPreservesSiblingResultInRealJournal() {
        val db = fresh(); val runtime = lease(db); val owner = parent(db); val first = HeldChild(); val second = HeldChild()
        val supervisor = RetainedChildTasks(runtime.childTasks)
        val caps = SessionCapabilities("child-session", setOf("test.review"))
        val auth = TaskAuthorization(caps.sessionId, owner.runId.value)
        val binding = supervisor.bind(owner, caps, auth, mapOf(
            "first" to ChildModelDefinition("first", 1) { first }, "second" to ChildModelDefinition("second", 1) { second }))
        try {
            val accepted = submitRetained(supervisor, binding, owner, caps, auth, listOf("first", "second"))
            assertTrue(first.entered.await(3, TimeUnit.SECONDS)); assertTrue(second.entered.await(3, TimeUnit.SECONDS))
            assertEquals(ChildTaskStop.REQUESTED, supervisor.stop(binding, accepted.tasks[0].owner))
            assertTrue(first.cancelled.await(3, TimeUnit.SECONDS)); assertEquals(1L, second.cancelled.count)
            first.release.countDown(); second.release.countDown(); awaitChildren(supervisor, binding)
            val rows = runtime.childTasks.list(owner.conversationId.value).associateBy { it.index }
            assertEquals(ChildTaskStatus.UNKNOWN, rows.getValue(0).status)
            assertEquals(ChildTaskStatus.COMPLETED, rows.getValue(1).status)
            assertEquals("retained child answer", rows.getValue(1).text)
            assertEquals(2, db.messages(owner.conversationId).size)
        } finally { first.release.countDown(); second.release.countDown(); closeChildren(supervisor) }
    }

}
