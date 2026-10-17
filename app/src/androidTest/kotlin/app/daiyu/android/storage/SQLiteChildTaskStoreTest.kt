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

    fun testSealedBindingCannotAdmitDirectedWorkAndSourceKeepsCapacityUntilDrain() {
        val db = fresh(); val runtime = lease(db); val owner = parent(db)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val drained = CountDownLatch(1)
        val model = ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); sink(ModelEvent.TextFinal("result", "source result"))
            }
            override fun cancel() {}
        } }
        val supervisor = RetainedChildTasks(runtime.childTasks, maxRetainedTasks = 1)
        val session = SessionCapabilities("commands", setOf("child_test"))
        val authorization = TaskAuthorization(session.sessionId, owner.runId.value)
        val binding = supervisor.bind(owner, session, authorization, mapOf("review" to ChildModelDefinition("pool", 1) { model }))
        val invocation = ToolInvocation(ToolCallId("initial"), "start_child_tasks", "{}")
        val admission = AtomicReference<ChildTaskAdmission>()
        val tool = object : RegisteredTool {
            override val definition = app.daiyu.android.domain.tools.ToolDefinition("start_child_tasks", 1, "Fixture child admission", "child_test",
                ToolEffect.READ_ONLY, ToolSchema())
            override fun prepare(arguments: ToolArguments) = PreparedTool.contextual { context ->
                val result = supervisor.submit(binding, context, listOf(ChildModelTask("review", "source prompt")))
                admission.set(result)
                when (result) {
                    is ChildTaskAdmission.Accepted -> ExecutionReceipt.Confirmed("accepted")
                    is ChildTaskAdmission.Rejected -> ExecutionReceipt.NotExecuted(result.code)
                    is ChildTaskAdmission.Unconfirmed -> ExecutionReceipt.Unknown(result.code)
                }
            }
        }
        val dispatcher = ToolDispatcher(ToolRegistry().register(tool).snapshot(), session, ToolLedger(InMemoryToolLedgerStore()))
        try {
            assertEquals(DispatchOutcome.Confirmed("accepted", false), dispatcher.dispatchOwned(authorization, listOf(invocation), owner).single().outcome)
            val accepted = checkNotNull(admission.get()) as ChildTaskAdmission.Accepted
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val source = checkNotNull(runtime.childTasks.get(accepted.tasks.single().owner))
            val command = ChildTaskCommand("directed", source, ChildTaskCommandKind.APPEND, "review", "additional")
            assertEquals(ChildTaskCommandReceipt.Rejected("child_retention_limit"), supervisor.command(binding, invocation, command))
            supervisor.finishParent(binding)
            assertEquals(ChildTaskCommandReceipt.Rejected("child_binding_closed"), supervisor.command(binding, invocation, command))
            assertFalse(supervisor.isReleased(binding))
            try { supervisor.bind(owner, session, authorization, mapOf("review" to ChildModelDefinition("pool", 1) { model })); fail("must not revive sealed owner") }
            catch (_: IllegalStateException) { }
            assertEquals(1, runtime.childTasks.list(owner.conversationId.value).size)
        } finally {
            release.countDown(); supervisor.closeWhenIdle { drained.countDown() }
            assertTrue(drained.await(5, TimeUnit.SECONDS))
        }
        assertTrue(supervisor.isReleased(binding))
        try { supervisor.bind(owner, session, authorization, mapOf("review" to ChildModelDefinition("pool", 1) { model })); fail("must not revive owner") }
        catch (_: IllegalStateException) { }
    }

    fun testDirectedCommandCasProvenanceAndDurableReplay() {
        val db = fresh(); val runtime = lease(db); val owner = parent(db)
        val source = tasks(owner, 1).single()
        runtime.childTasks.enqueue(owner, listOf(source)); runtime.childTasks.started(source)
        runtime.childTasks.finished(source, ChildTaskStatus.COMPLETED, "original result")
        val completed = checkNotNull(runtime.childTasks.get(source.owner))
        val command = ChildTaskCommand("durable-call", completed, ChildTaskCommandKind.APPEND, "review", "additional instruction")
        val successor = tasks(owner, 1).single().copy(prompt = command.instruction, sourceTaskId = source.owner.childRunId,
            commandKind = command.kind)
        assertEquals(ChildTaskCommandReceipt.Accepted(successor), runtime.childTasks.enqueueCommand(owner, command, successor))
        val preserved = checkNotNull(runtime.childTasks.get(source.owner))
        assertEquals(completed.copy(revision = 1), preserved)
        assertEquals("original result", preserved.text); assertEquals(source.prompt, preserved.prompt)
        assertEquals(ChildTaskCommandReceipt.Rejected("child_request_conflict"),
            runtime.childTasks.commandReceipt(command.copy(instruction = "different")))
        val stale = command.copy(callId = "stale-call")
        assertEquals(ChildTaskCommandReceipt.Rejected("child_source_stale"),
            runtime.childTasks.enqueueCommand(owner, stale, successor.copy(owner = successor.owner.copy(childRunId = "another"))))
        runtime.close(); db.close()
        val reopened = lease(db)
        val replay = reopened.childTasks.commandReceipt(command) as ChildTaskCommandReceipt.Accepted
        assertEquals(successor.owner, replay.task.owner)
        assertEquals(ChildTaskStatus.NOT_EXECUTED, replay.task.status)
        assertEquals(successor.sourceTaskId, replay.task.sourceTaskId)
        assertEquals(2, reopened.childTasks.list(owner.conversationId.value).size)
    }

    fun testUnknownCommandRequiresExplicitAcknowledgementAndRollbackIsAtomic() {
        val db = fresh(); val runtime = lease(db); val owner = parent(db)
        val source = tasks(owner, 1).single()
        runtime.childTasks.enqueue(owner, listOf(source)); runtime.childTasks.started(source)
        runtime.childTasks.finished(source, ChildTaskStatus.UNKNOWN, detail = "test_unconfirmed")
        val unknown = checkNotNull(runtime.childTasks.get(source.owner))
        val command = ChildTaskCommand("unknown-call", unknown, ChildTaskCommandKind.REDISPATCH, "review", source.prompt)
        val successor = tasks(owner, 1).single().copy(sourceTaskId = source.owner.childRunId, commandKind = command.kind, prompt = command.instruction)
        assertEquals(ChildTaskCommandReceipt.Rejected("child_execution_confirmation_required"),
            runtime.childTasks.enqueueCommand(owner, command, successor))
        val confirmed = command.copy(acknowledgePossibleExecution = true)
        db.writableDatabase.execSQL("CREATE TRIGGER command_insert_failure BEFORE INSERT ON child_task_commands BEGIN SELECT RAISE(ABORT,'fixture'); END")
        rejects { runtime.childTasks.enqueueCommand(owner, confirmed, successor) }
        assertEquals(unknown, runtime.childTasks.get(source.owner))
        assertNull(runtime.childTasks.commandReceipt(confirmed)); assertEquals(1, runtime.childTasks.list(owner.conversationId.value).size)
        db.writableDatabase.execSQL("DROP TRIGGER command_insert_failure")
        assertTrue(runtime.childTasks.enqueueCommand(owner, confirmed, successor) is ChildTaskCommandReceipt.Accepted)
        assertEquals(successor.owner, (runtime.childTasks.enqueueCommand(owner, confirmed, successor) as ChildTaskCommandReceipt.Accepted).task.owner)
        assertEquals(2, runtime.childTasks.list(owner.conversationId.value).size)
    }

    private fun commandRows(db: ConversationDatabase): List<List<String?>> =
        db.readableDatabase.rawQuery("SELECT call_id,fingerprint,source_id,successor_id,expected_revision,acknowledged " +
            "FROM child_task_commands ORDER BY call_id", null).use { c ->
            buildList { while (c.moveToNext()) add((0 until c.columnCount).map { c.getString(it) }) }
        }

    private fun assertCommandTriggersRollBack(triggerBodies: List<String>) {
        val db = fresh(); val runtime = lease(db); val owner = parent(db)
        val initial = tasks(owner, 1).single()
        runtime.childTasks.enqueue(owner, listOf(initial)); runtime.childTasks.started(initial)
        runtime.childTasks.finished(initial, ChildTaskStatus.UNKNOWN, detail = "original_unknown")
        val unknown = checkNotNull(runtime.childTasks.get(initial.owner))
        val priorCommand = ChildTaskCommand("prior-command", unknown, ChildTaskCommandKind.REDISPATCH,
            "review", "prior instruction", acknowledgePossibleExecution = true)
        val priorSuccessor = tasks(owner, 1).single().copy(prompt = priorCommand.instruction,
            sourceTaskId = unknown.owner.childRunId, commandKind = priorCommand.kind)
        assertEquals(ChildTaskCommandReceipt.Accepted(priorSuccessor),
            runtime.childTasks.enqueueCommand(owner, priorCommand, priorSuccessor))
        // Start at a nonzero revision and retain an unrelated successor/tombstone to check rollback isolation.
        val source = checkNotNull(runtime.childTasks.get(initial.owner))
        assertEquals(1L, source.revision)
        val command = ChildTaskCommand("candidate-command", source, ChildTaskCommandKind.REDISPATCH,
            "review", "candidate instruction", acknowledgePossibleExecution = true)
        val successor = tasks(owner, 1).single().copy(prompt = command.instruction,
            sourceTaskId = source.owner.childRunId, commandKind = command.kind)
        val beforeTasks = runtime.childTasks.list(owner.conversationId.value)
        val beforeCommands = commandRows(db)
        val beforeMessages = db.messages(owner.conversationId)
        triggerBodies.forEachIndexed { index, body ->
            db.writableDatabase.execSQL("CREATE TRIGGER command_write_fixture $body")
            try {
                rejects { runtime.childTasks.enqueueCommand(owner, command, successor) }
                assertEquals("source after trigger $index", source, runtime.childTasks.get(source.owner))
                assertNull("successor after trigger $index", runtime.childTasks.get(successor.owner))
                assertNull("receipt after trigger $index", runtime.childTasks.commandReceipt(command))
                assertEquals("tasks after trigger $index", beforeTasks, runtime.childTasks.list(owner.conversationId.value))
                assertEquals("tombstones after trigger $index", beforeCommands, commandRows(db))
                assertEquals("messages after trigger $index", beforeMessages, db.messages(owner.conversationId))
                assertEquals(ChildTaskCommandReceipt.Accepted(priorSuccessor), runtime.childTasks.commandReceipt(priorCommand))
            } finally {
                db.writableDatabase.execSQL("DROP TRIGGER command_write_fixture")
            }
        }
        // The identical request remains admissible after every failed write and gains exactly one durable receipt.
        assertEquals(ChildTaskCommandReceipt.Accepted(successor), runtime.childTasks.enqueueCommand(owner, command, successor))
        assertEquals(source.copy(revision = 2), runtime.childTasks.get(source.owner))
        assertEquals(successor, runtime.childTasks.get(successor.owner))
        assertEquals(ChildTaskCommandReceipt.Accepted(successor), runtime.childTasks.commandReceipt(command))
        assertEquals(ChildTaskCommandReceipt.Accepted(successor), runtime.childTasks.enqueueCommand(owner, command, successor))
        assertEquals(ChildTaskCommandReceipt.Accepted(priorSuccessor), runtime.childTasks.commandReceipt(priorCommand))
        assertEquals(3, runtime.childTasks.list(owner.conversationId.value).size)
        val persistedCommands = commandRows(db)
        assertEquals(2, persistedCommands.size)
        assertEquals(listOf(command.callId, command.fingerprint(), source.owner.childRunId, successor.owner.childRunId,
            source.revision.toString(), "1"), persistedCommands.single { it[0] == command.callId })
        assertEquals(beforeMessages, db.messages(owner.conversationId))
    }

    fun testCommandIgnoredWritesRollBackWithoutPollution() {
        assertCommandTriggersRollBack(listOf(
            "BEFORE UPDATE OF control_revision ON child_tasks BEGIN SELECT RAISE(IGNORE); END",
            "BEFORE INSERT ON child_tasks WHEN NEW.source_task_id IS NOT NULL BEGIN SELECT RAISE(IGNORE); END",
            "BEFORE INSERT ON child_task_commands BEGIN SELECT RAISE(IGNORE); END"
        ))
    }

    fun testCommandAfterTaskRewritesRollBackWithoutPollution() {
        assertCommandTriggersRollBack(listOf(
            "AFTER UPDATE OF control_revision ON child_tasks BEGIN " +
                "UPDATE child_tasks SET prompt='rewritten source' WHERE task_id=NEW.task_id; END",
            "AFTER INSERT ON child_tasks WHEN NEW.source_task_id IS NOT NULL BEGIN " +
                "UPDATE child_tasks SET prompt='rewritten successor' WHERE task_id=NEW.task_id; END",
            // These fire on the final write: checking source/successor before inserting the tombstone is insufficient.
            "AFTER INSERT ON child_task_commands BEGIN " +
                "UPDATE child_tasks SET control_revision=NEW.expected_revision WHERE task_id=NEW.source_id; END",
            "AFTER INSERT ON child_task_commands BEGIN " +
                "UPDATE child_tasks SET detail='rewritten source' WHERE task_id=NEW.source_id; END",
            "AFTER INSERT ON child_task_commands BEGIN " +
                "UPDATE child_tasks SET prompt='late rewritten successor' WHERE task_id=NEW.successor_id; END",
            "AFTER INSERT ON child_task_commands BEGIN " +
                "UPDATE child_tasks SET status='NOT_EXECUTED' WHERE task_id=NEW.successor_id; END",
            "AFTER INSERT ON child_task_commands BEGIN " +
                "DELETE FROM child_tasks WHERE task_id=NEW.successor_id; END"
        ))
    }

    fun testCommandAfterTaskIntegerRewritesRollBackWithoutPollution() {
        val assignments = listOf(
            "control_revision=control_revision+0.5",
            "generation=generation+0.5",
            "task_index=task_index+0.5",
            // getInt() would wrap this INTEGER back to the expected index.
            "task_index=task_index+4294967296"
        )
        assertCommandTriggersRollBack(listOf("source_id", "successor_id").flatMap { target ->
            assignments.map { assignment ->
                "AFTER INSERT ON child_task_commands BEGIN " +
                    "UPDATE child_tasks SET $assignment WHERE task_id=NEW.$target; END"
            }
        })
    }

    fun testCommandAfterTombstoneRewritesRollBackWithoutPollution() {
        val assignments = listOf(
            "fingerprint='rewritten fingerprint'",
            "source_id=NEW.successor_id",
            "successor_id=NEW.source_id",
            "expected_revision=NEW.expected_revision+1",
            "expected_revision=NEW.expected_revision+0.5",
            "acknowledged=0",
            "acknowledged=1.5",
            "acknowledged=4294967297",
            "call_id='rewritten-call-id'"
        )
        assertCommandTriggersRollBack(assignments.map { assignment ->
            "AFTER INSERT ON child_task_commands BEGIN " +
                "UPDATE child_task_commands SET $assignment WHERE call_id=NEW.call_id; END"
        } + "AFTER INSERT ON child_task_commands BEGIN DELETE FROM child_task_commands WHERE call_id=NEW.call_id; END")
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
        assertEquals(20, db.readableDatabase.version); assertEquals(before, db.messages(owner.conversationId))
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
