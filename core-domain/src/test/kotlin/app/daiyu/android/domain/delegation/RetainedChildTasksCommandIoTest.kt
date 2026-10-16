package app.daiyu.android.domain.delegation

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.SessionCapabilities
import app.daiyu.android.domain.tools.TaskAuthorization
import app.daiyu.android.domain.tools.ToolExecutionContext
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Dedicated command I/O/lifecycle regressions. Source added only; not compiled or run here. */
class RetainedChildTasksCommandIoTest {
    private class Journal : ChildTaskStore {
        private val rows = linkedMapOf<String, ChildTaskRecord>()
        private val receipts = mutableMapOf<String, Pair<String, ChildTaskRecord>>()
        val writes = AtomicInteger()
        var receiptHook: (() -> Unit)? = null
        var getHook: (() -> Unit)? = null
        var enqueueHook: (() -> Unit)? = null
        var afterCommit: (() -> Unit)? = null
        override fun enqueue(parent: RunExecutionOwner, tasks: List<ChildTaskRecord>) {
            synchronized(this) { tasks.forEach { rows[it.owner.childRunId] = it } }
        }
        override fun commandReceipt(command: ChildTaskCommand): ChildTaskCommandReceipt? {
            receiptHook?.invoke()
            return synchronized(this) {
                receipts[command.callId]?.let {
                    if (it.first != command.fingerprint()) ChildTaskCommandReceipt.Rejected("child_request_conflict")
                    else ChildTaskCommandReceipt.Accepted(rows.getValue(it.second.owner.childRunId))
                }
            }
        }
        override fun get(owner: ChildRunOwner): ChildTaskRecord? {
            getHook?.invoke()
            return synchronized(this) { rows[owner.childRunId]?.takeIf { it.owner == owner } }
        }
        override fun enqueueCommand(parent: RunExecutionOwner, command: ChildTaskCommand, successor: ChildTaskRecord): ChildTaskCommandReceipt {
            writes.incrementAndGet()
            enqueueHook?.invoke()
            val result = synchronized(this) {
                val source = rows.getValue(command.source.owner.childRunId)
                val error = command.validateSource(source)
                if (error != null) ChildTaskCommandReceipt.Rejected(error) else {
                    rows[source.owner.childRunId] = source.copy(revision = source.revision + 1)
                    rows[successor.owner.childRunId] = successor
                    receipts[command.callId] = command.fingerprint() to successor
                    ChildTaskCommandReceipt.Accepted(successor)
                }
            }
            afterCommit?.invoke()
            return result
        }
        @Synchronized override fun started(task: ChildTaskRecord) {
            rows[task.owner.childRunId] = rows.getValue(task.owner.childRunId).copy(status = ChildTaskStatus.RUNNING)
        }
        @Synchronized override fun finished(task: ChildTaskRecord, status: ChildTaskStatus, text: String, detail: String?) {
            rows[task.owner.childRunId] = rows.getValue(task.owner.childRunId).copy(status = status, text = text, detail = detail)
        }
        @Synchronized override fun list(conversationId: String, limit: Int) = rows.values.take(limit)
    }

    private class Fixture(capacity: Int = 4, budget: Int = 8) {
        val store = Journal()
        val opens = AtomicInteger()
        val supervisor = RetainedChildTasks(store, maxRetainedTasks = capacity, maxRequestsPerBinding = budget)
        val owner = RunExecutionOwner.issue(Message(MessageId("parent-message"), ConversationId("conversation"),
            RunId("parent"), Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING))
        val authorization = TaskAuthorization("session", owner.runId.value)
        val invocation = ToolInvocation(ToolCallId("command"), "direct_child_task", "{}")
        val binding = supervisor.bind(owner, SessionCapabilities("session", emptySet()), authorization,
            mapOf("p" to ChildModelDefinition("pool", 1) {
                object : ModelPort {
                    override fun open(history: List<Message>): ModelSession = error("explicit definitions")
                    override fun open(history: List<Message>, definitions: List<ToolDefinition>): ModelSession {
                        opens.incrementAndGet()
                        return object : ModelSession {
                            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                                sink(ModelEvent.TextFinal("answer", "done"))
                            }
                            override fun cancel() = Unit
                        }
                    }
                }
            }))
        val source: ChildTaskRecord
        init {
            val context = ToolExecutionContext(owner, ToolInvocation(ToolCallId("source"), "start_child_tasks", "{}"), authorization) { false }
            val accepted = supervisor.submit(binding, context, listOf(ChildModelTask("p", "source"))) as ChildTaskAdmission.Accepted
            waitUntil { supervisor.snapshots(binding).any { it.record.owner == accepted.tasks.single().owner && it.settled } }
            source = store.get(accepted.tasks.single().owner)!!
        }
        fun command(call: String = "command") = ChildTaskCommand(call, source, ChildTaskCommandKind.APPEND, "p", "more")
        fun send(command: ChildTaskCommand = command()) = supervisor.command(binding, invocation, command)
        fun drain() {
            val done = CountDownLatch(1)
            supervisor.closeWhenIdle { done.countDown() }
            assertTrue("real drain", done.await(5, TimeUnit.SECONDS))
        }
    }

    private class Block {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fun hold() {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
    }
    private fun launch(action: () -> ChildTaskCommandReceipt): Pair<Thread, AtomicReference<ChildTaskCommandReceipt>> {
        val result = AtomicReference<ChildTaskCommandReceipt>()
        val thread = Thread { result.set(action()) }.apply { isDaemon = true; start() }
        return thread to result
    }
    private fun promptly(action: () -> Unit) {
        val failure = AtomicReference<Throwable>()
        val done = CountDownLatch(1)
        Thread { try { action() } catch (e: Throwable) { failure.set(e) } finally { done.countDown() } }
            .apply { isDaemon = true; start() }
        assertTrue("control must not wait for database I/O", done.await(1, TimeUnit.SECONDS))
        failure.get()?.let { throw AssertionError(it) }
    }

    @Test fun receiptGetAndEnqueueNeverBlockStopOrCloseAndPinUntilReturn() {
        listOf("receipt", "get", "enqueue").forEach { phase ->
            val f = Fixture(); val block = Block()
            when (phase) {
                "receipt" -> f.store.receiptHook = block::hold
                "get" -> f.store.getHook = block::hold
                else -> f.store.enqueueHook = block::hold
            }
            val (thread, result) = launch { f.send() }
            try {
                assertTrue(block.entered.await(3, TimeUnit.SECONDS))
                promptly {
                    assertEquals(ChildTaskStop.ALREADY_SETTLED, f.supervisor.stop(f.binding, f.source.owner))
                    f.supervisor.close()
                }
                assertFalse(f.supervisor.isReleased(f.binding))
                val drained = CountDownLatch(1)
                f.supervisor.closeWhenIdle { drained.countDown() }
                assertEquals(1L, drained.count)
                block.release.countDown(); thread.join(3000)
                assertFalse(thread.isAlive)
                assertTrue(drained.await(3, TimeUnit.SECONDS))
                assertTrue(f.supervisor.isReleased(f.binding))
                assertEquals(1, f.opens.get())
                if (phase == "enqueue") {
                    val accepted = result.get() as ChildTaskCommandReceipt.Accepted
                    assertEquals(ChildTaskStatus.NOT_EXECUTED, f.store.get(accepted.task.owner)!!.status)
                } else {
                    assertEquals(ChildTaskCommandReceipt.Rejected("child_binding_closed"), result.get())
                    assertEquals(0, f.store.writes.get())
                }
            } finally { block.release.countDown(); thread.join(3000); f.drain() }
        }
    }

    @Test fun reentrantStoreObservesPendingAndCanCallControlOnAnotherThread() {
        listOf("receipt", "get", "enqueue").forEach { phase ->
            val f = Fixture()
            val hooks = AtomicInteger()
            val hook: () -> Unit = {
                if (hooks.getAndIncrement() == 0) {
                    assertEquals(ChildTaskCommandReceipt.Unconfirmed("child_command_pending"), f.send())
                    promptly { f.supervisor.snapshots(f.binding); f.supervisor.stop(f.binding, f.source.owner) }
                }
            }
            when (phase) {
                "receipt" -> f.store.receiptHook = hook
                "get" -> f.store.getHook = hook
                else -> f.store.enqueueHook = hook
            }
            try { assertTrue(f.send() is ChildTaskCommandReceipt.Accepted); assertEquals(1, f.store.writes.get()) }
            finally { f.store.receiptHook = null; f.store.getHook = null; f.store.enqueueHook = null; f.drain() }
        }
    }

    @Test fun sameCallHasOneAdmissionConflictAndCapacityAndBudgetStayBounded() {
        val f = Fixture(capacity = 1, budget = 2); val block = Block()
        f.store.enqueueHook = block::hold
        val (thread, result) = launch { f.send() }
        try {
            assertTrue(block.entered.await(3, TimeUnit.SECONDS))
            promptly {
                assertEquals(ChildTaskCommandReceipt.Unconfirmed("child_command_pending"), f.send())
                assertEquals(ChildTaskCommandReceipt.Rejected("child_request_conflict"), f.send(f.command().copy(instruction = "different")))
                val context = ToolExecutionContext(f.owner, ToolInvocation(ToolCallId("ordinary"), "start_child_tasks", "{}"), f.authorization) { false }
                assertEquals(ChildTaskAdmission.Rejected("child_request_budget_exhausted"),
                    f.supervisor.submit(f.binding, context, listOf(ChildModelTask("p", "other"))))
                assertEquals(ChildTaskCommandReceipt.Rejected("child_request_budget_exhausted"), f.send(f.command("other")))
            }
            assertEquals(1, f.store.writes.get())
            block.release.countDown(); thread.join(3000)
            assertTrue(result.get() is ChildTaskCommandReceipt.Accepted)
            f.store.enqueueHook = null
            assertTrue(f.send() is ChildTaskCommandReceipt.Accepted)
            assertEquals(1, f.store.writes.get())
        } finally { block.release.countDown(); thread.join(3000); f.drain() }
    }

    @Test fun reservedSuccessorConsumesCapacityAndCanBeStoppedBeforeEnqueueReturns() {
        val f = Fixture(capacity = 1); val block = Block()
        f.store.enqueueHook = block::hold
        val (thread, result) = launch { f.send() }
        try {
            assertTrue(block.entered.await(3, TimeUnit.SECONDS))
            val pending = f.supervisor.snapshots(f.binding).single { !it.settled }
            promptly {
                assertEquals(ChildTaskCommandReceipt.Rejected("child_retention_limit"), f.send(f.command("other")))
                assertEquals(ChildTaskStop.REQUESTED, f.supervisor.stop(f.binding, pending.record.owner))
                assertEquals(ChildTaskControlReceipt.STALE, f.supervisor.resume(f.binding, pending.record.owner))
            }
            block.release.countDown(); thread.join(3000)
            assertTrue(result.get() is ChildTaskCommandReceipt.Accepted)
            waitUntil { f.supervisor.snapshots(f.binding).any { it.record.owner == pending.record.owner && it.settled } }
            assertEquals(ChildTaskStatus.NOT_EXECUTED, f.store.get(pending.record.owner)!!.status)
            assertEquals(1, f.opens.get()); assertEquals(1, f.store.writes.get())
        } finally { block.release.countDown(); thread.join(3000); f.drain() }
    }

    @Test fun parentSealDuringEnqueuePinsResourcesButDoesNotRevokeAcceptedChild() {
        val f = Fixture(); val block = Block()
        f.store.enqueueHook = block::hold
        val (thread, result) = launch { f.send() }
        try {
            assertTrue(block.entered.await(3, TimeUnit.SECONDS))
            promptly { f.supervisor.finishParent(f.binding) }
            assertNotNull(f.binding.resources)
            block.release.countDown(); thread.join(3000)
            val accepted = result.get() as ChildTaskCommandReceipt.Accepted
            waitUntil { f.supervisor.isReleased(f.binding) }
            assertEquals(ChildTaskStatus.COMPLETED, f.store.get(accepted.task.owner)!!.status)
            assertEquals(2, f.opens.get()); assertEquals(1, f.store.writes.get())
        } finally { block.release.countDown(); thread.join(3000); f.drain() }
    }

    @Test fun cancellationDuringGetRejectsBeforeWriteAndDuringEnqueueStopsAcceptedWork() {
        listOf("get", "enqueue").forEach { phase ->
            val f = Fixture(); val block = Block()
            val cancelled = java.util.concurrent.atomic.AtomicBoolean()
            if (phase == "get") f.store.getHook = block::hold else f.store.enqueueHook = block::hold
            val context = ToolExecutionContext(f.owner, f.invocation, f.authorization) { cancelled.get() }
            val (thread, result) = launch { f.supervisor.command(f.binding, context, f.command()) }
            try {
                assertTrue(block.entered.await(3, TimeUnit.SECONDS))
                cancelled.set(true); block.release.countDown(); thread.join(3000)
                if (phase == "get") {
                    assertEquals(ChildTaskCommandReceipt.Rejected("cancelled_before_admission"), result.get())
                    assertEquals(0, f.store.writes.get())
                } else {
                    val accepted = result.get() as ChildTaskCommandReceipt.Accepted
                    waitUntil { f.supervisor.snapshots(f.binding).any { it.record.owner == accepted.task.owner && it.settled } }
                    assertEquals(ChildTaskStatus.NOT_EXECUTED, f.store.get(accepted.task.owner)!!.status)
                    assertEquals(1, f.store.writes.get())
                }
                assertEquals(1, f.opens.get())
            } finally { block.release.countDown(); thread.join(3000); f.drain() }
        }
    }

    @Test fun closeAfterDurableCommitReturnsAcceptedAndNeverOpensLateSuccessor() {
        val f = Fixture(); val block = Block()
        f.store.afterCommit = block::hold
        val (thread, result) = launch { f.send() }
        try {
            assertTrue(block.entered.await(3, TimeUnit.SECONDS))
            val persisted = f.store.list("conversation", 10).single { it.sourceTaskId != null }
            val closed = CountDownLatch(1)
            promptly { f.supervisor.closeWhenIdle { closed.countDown() } }
            assertEquals(1L, closed.count); assertNotNull(f.binding.resources)
            block.release.countDown(); thread.join(3000)
            assertEquals(persisted.owner, (result.get() as ChildTaskCommandReceipt.Accepted).task.owner)
            assertTrue(closed.await(3, TimeUnit.SECONDS))
            assertEquals(ChildTaskStatus.NOT_EXECUTED, f.store.get(persisted.owner)!!.status)
            assertTrue(f.send() is ChildTaskCommandReceipt.Accepted) // Tombstone query on sealed handle.
            assertEquals(1, f.store.writes.get()); assertEquals(1, f.opens.get())
        } finally { block.release.countDown(); thread.join(3000); f.drain() }
    }

    @Test fun closeWhileReadingPersistedReceiptKeepsAcceptedWithoutReopening() {
        val f = Fixture()
        val accepted = f.send() as ChildTaskCommandReceipt.Accepted
        waitUntil { f.supervisor.snapshots(f.binding).any { it.record.owner == accepted.task.owner && it.settled } }
        val opens = f.opens.get()
        val block = Block(); f.store.receiptHook = block::hold
        val (thread, result) = launch { f.send() }
        try {
            assertTrue(block.entered.await(3, TimeUnit.SECONDS))
            val done = CountDownLatch(1)
            promptly { f.supervisor.closeWhenIdle { done.countDown() } }
            assertNotNull(f.binding.resources); assertEquals(1L, done.count)
            block.release.countDown(); thread.join(3000)
            assertEquals(accepted.task.owner, (result.get() as ChildTaskCommandReceipt.Accepted).task.owner)
            assertTrue(done.await(3, TimeUnit.SECONDS))
            assertEquals(opens, f.opens.get()); assertEquals(1, f.store.writes.get())
        } finally { block.release.countDown(); thread.join(3000); f.drain() }
    }

    @Test fun lostAcceptedReplyCanBeQueriedButNeverAutomaticallyEnqueuedOrOpened() {
        val f = Fixture()
        f.store.afterCommit = { throw IllegalStateException("reply lost after commit") }
        try {
            assertEquals(ChildTaskCommandReceipt.Unconfirmed("child_command_unconfirmed"), f.send())
            f.supervisor.finishParent(f.binding)
            assertTrue(f.supervisor.isReleased(f.binding))
            f.store.afterCommit = null
            val accepted = f.send() as ChildTaskCommandReceipt.Accepted
            assertEquals(ChildTaskStatus.QUEUED, accepted.task.status) // Unknown execution is not invented.
            assertEquals(1, f.store.writes.get()); assertEquals(1, f.opens.get())
            assertTrue(f.send() is ChildTaskCommandReceipt.Accepted)
            assertEquals(1, f.store.writes.get()); assertEquals(1, f.opens.get())
        } finally { f.drain() }
    }

    @Test fun unknownWriteIsNeverRepeatedEvenIfReceiptLookupStillReturnsNull() {
        val f = Fixture()
        f.store.enqueueHook = { throw IllegalStateException("unknown write") }
        try {
            assertEquals(ChildTaskCommandReceipt.Unconfirmed("child_command_unconfirmed"), f.send())
            f.supervisor.finishParent(f.binding)
            assertTrue(f.supervisor.isReleased(f.binding))
            f.store.enqueueHook = null
            assertEquals(ChildTaskCommandReceipt.Unconfirmed("child_command_unconfirmed"), f.send())
            assertEquals(1, f.store.writes.get()); assertEquals(1, f.opens.get())
        } finally { f.drain() }
    }

    companion object {
        private fun waitUntil(condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
            assertTrue("condition before deadline", condition())
        }
    }
}
