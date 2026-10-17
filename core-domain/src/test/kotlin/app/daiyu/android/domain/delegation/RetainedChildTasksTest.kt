package app.daiyu.android.domain.delegation

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.*
import app.daiyu.android.domain.usage.*
import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class RetainedChildTasksTest {
    private class Journal : ChildTaskStore {
        val rows = linkedMapOf<String, ChildTaskRecord>()
        val enqueues = AtomicInteger()
        var beforeEnqueue: (() -> Unit)? = null
        var failEnqueue = false
        var failStart = false
        var failFinish = false
        override fun enqueue(parent: RunExecutionOwner, tasks: List<ChildTaskRecord>) {
            enqueues.incrementAndGet()
            beforeEnqueue?.invoke()
            synchronized(this) {
                tasks.forEach { check(it.owner.childRunId !in rows); rows[it.owner.childRunId] = it }
            }
            check(!failEnqueue)
        }
        @Synchronized override fun started(task: ChildTaskRecord) {
            check(!failStart)
            check(rows.getValue(task.owner.childRunId).status == ChildTaskStatus.QUEUED)
            rows[task.owner.childRunId] = task.copy(status = ChildTaskStatus.RUNNING)
        }
        @Synchronized override fun finished(task: ChildTaskRecord, status: ChildTaskStatus, text: String, detail: String?) {
            check(!failFinish)
            val old = rows.getValue(task.owner.childRunId)
            check(old.status == ChildTaskStatus.QUEUED || old.status == ChildTaskStatus.RUNNING)
            rows[task.owner.childRunId] = task.copy(status = status, text = text, detail = detail)
        }
        @Synchronized override fun list(conversationId: String, limit: Int) =
            rows.values.filter { it.owner.conversationId == conversationId }.take(limit)
    }
    private class HeldModel : ModelPort {
        val opens = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        override fun open(history: List<Message>): ModelSession = error("explicit empty tools required")
        override fun open(history: List<Message>, definitions: List<app.daiyu.android.domain.ToolDefinition>): ModelSession {
            assertTrue(definitions.isEmpty()); opens.incrementAndGet()
            return object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                    sink(ModelEvent.TextFinal("text", "answer"))
                }
                override fun cancel() { cancelled.countDown() }
            }
        }
    }
    private class Parent(val suffix: String = "one") {
        val message = Message(MessageId("parent-$suffix"), ConversationId("conversation"), RunId("run-$suffix"),
            Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
        val owner = RunExecutionOwner.issue(message)
        val session = SessionCapabilities("session-$suffix", setOf("delegation.text.background_review"))
        val authorization = TaskAuthorization(session.sessionId, owner.runId.value)
        fun context(id: String = "call", cancelled: () -> Boolean = { false }) = ToolExecutionContext(owner,
            ToolInvocation(ToolCallId(id), "start_child_tasks", "{}"), authorization, cancelled)
        fun bind(supervisor: RetainedChildTasks, models: Map<String, ChildModelDefinition>) =
            supervisor.bind(owner, session, authorization, models)
    }
    private fun definition(model: ModelPort, pool: String = "pool", limit: Int = 1) = ChildModelDefinition(pool, limit) { model }
    private fun submit(supervisor: RetainedChildTasks, binding: ChildTaskBinding, parent: Parent,
        vararg profiles: String, id: String = "call") = supervisor.submit(binding, parent.context(id),
        profiles.map { ChildModelTask(it, "Review $it") }) as ChildTaskAdmission.Accepted
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(message, condition())
    }
    private fun drained(supervisor: RetainedChildTasks) {
        val done = CountDownLatch(1); supervisor.closeWhenIdle { done.countDown() }
        assertTrue("all real workers exit", done.await(5, TimeUnit.SECONDS))
    }

    @Test fun wholeBatchJournalPrecedesAnyNetworkAndAdmissionDoesNotWaitForResults() {
        val journal = Journal(); val first = HeldModel(); val second = HeldModel(); val parent = Parent()
        val supervisor = RetainedChildTasks(journal)
        val binding = parent.bind(supervisor, mapOf("first" to definition(first, "a"), "second" to definition(second, "b")))
        val accepted = submit(supervisor, binding, parent, "first", "second")
        try {
            assertEquals(1, journal.enqueues.get()); assertEquals(2, accepted.tasks.size)
            assertTrue(first.entered.await(3, TimeUnit.SECONDS)); assertTrue(second.entered.await(3, TimeUnit.SECONDS))
            assertEquals(2, journal.list("conversation").size)
            assertTrue(supervisor.snapshots(binding).none { it.settled })
            assertEquals(accepted.tasks.map { it.owner.taskGroupId }.distinct().size, 1)
            assertEquals(listOf(0, 1), accepted.tasks.map { it.index })
        } finally { first.release.countDown(); second.release.countDown(); drained(supervisor) }
    }

    @Test fun parentCompletionAndParentCancellationDoNotCancelAcceptedChildren() {
        val journal = Journal(); val model = HeldModel(); val parent = Parent(); val cancelled = AtomicBoolean(false)
        val supervisor = RetainedChildTasks(journal); val binding = parent.bind(supervisor, mapOf("p" to definition(model)))
        try {
            assertTrue(supervisor.submit(binding, parent.context(cancelled = { cancelled.get() }), listOf(ChildModelTask("p", "prompt"))) is ChildTaskAdmission.Accepted)
            assertTrue(model.entered.await(3, TimeUnit.SECONDS))
            cancelled.set(true); supervisor.finishParent(binding)
            assertEquals(1L, model.cancelled.count)
            assertTrue(supervisor.submit(binding, parent.context("later"), listOf(ChildModelTask("p", "later"))) is ChildTaskAdmission.Rejected)
            model.release.countDown()
            await("result retained") { supervisor.snapshots(binding).single().settled }
            val view = supervisor.snapshots(binding).single()
            assertEquals(ChildTaskStatus.COMPLETED, view.record.status); assertEquals("answer", view.record.text)
            assertFalse(view.journalUnconfirmed); assertFalse(view.stopRequested)
        } finally { model.release.countDown(); drained(supervisor) }
    }

    @Test fun independentStopKeepsSiblingAndWaitsForActualExit() {
        val first = HeldModel(); val second = HeldModel(); val parent = Parent(); val supervisor = RetainedChildTasks(Journal())
        val binding = parent.bind(supervisor, mapOf("first" to definition(first, "a"), "second" to definition(second, "b")))
        val admission = submit(supervisor, binding, parent, "first", "second")
        try {
            assertTrue(first.entered.await(3, TimeUnit.SECONDS)); assertTrue(second.entered.await(3, TimeUnit.SECONDS))
            assertEquals(ChildTaskStop.REQUESTED, supervisor.stop(binding, admission.tasks[0].owner))
            assertTrue(first.cancelled.await(3, TimeUnit.SECONDS)); assertEquals(1L, second.cancelled.count)
            assertFalse(supervisor.snapshots(binding)[0].settled)
            second.release.countDown(); await("sibling complete") { supervisor.snapshots(binding)[1].settled }
            assertEquals(ChildTaskStatus.COMPLETED, supervisor.snapshots(binding)[1].record.status)
            first.release.countDown(); await("cancelled child exits") { supervisor.snapshots(binding)[0].settled }
            assertEquals(ChildTaskStatus.UNKNOWN, supervisor.snapshots(binding)[0].record.status)
            assertEquals(ChildTaskStop.ALREADY_SETTLED, supervisor.stop(binding, admission.tasks[0].owner))
            assertEquals(1, first.opens.get()); assertEquals(1, second.opens.get())
        } finally { first.release.countDown(); second.release.countDown(); drained(supervisor) }
    }

    @Test fun queuedStopNeverCreatesOrOpensModel() {
        val model = HeldModel(); val created = AtomicInteger(); val parent = Parent(); val supervisor = RetainedChildTasks(Journal())
        val binding = parent.bind(supervisor, mapOf("p" to ChildModelDefinition("pool", 1) { created.incrementAndGet(); model }))
        val admission = submit(supervisor, binding, parent, "p", "p")
        try {
            assertTrue(model.entered.await(3, TimeUnit.SECONDS))
            val runningId = supervisor.snapshots(binding).single { it.record.status == ChildTaskStatus.RUNNING }.record.owner
            val queued = admission.tasks.single { it.owner != runningId }.owner
            assertEquals(ChildTaskStop.REQUESTED, supervisor.stop(binding, queued))
            await("queued task exits") { supervisor.snapshots(binding).single { it.record.owner == queued }.settled }
            assertEquals(ChildTaskStatus.NOT_EXECUTED, supervisor.snapshots(binding).single { it.record.owner == queued }.record.status)
            assertEquals(1, created.get()); assertEquals(1, model.opens.get())
        } finally { model.release.countDown(); drained(supervisor) }
    }

    @Test fun duplicateSubmissionReturnsOriginalHandlesAndChangedRequestCannotReplay() {
        val journal = Journal(); val model = HeldModel(); val parent = Parent(); val supervisor = RetainedChildTasks(journal)
        val binding = parent.bind(supervisor, mapOf("p" to definition(model)))
        try {
            val accepted = submit(supervisor, binding, parent, "p")
            assertEquals(accepted, submit(supervisor, binding, parent, "p"))
            assertEquals(ChildTaskAdmission.Rejected("child_request_conflict"),
                supervisor.submit(binding, parent.context(), listOf(ChildModelTask("p", "changed"))))
            supervisor.finishParent(binding)
            assertEquals(accepted, submit(supervisor, binding, parent, "p"))
            assertEquals(1, journal.enqueues.get())
            assertTrue(model.entered.await(3, TimeUnit.SECONDS)); assertEquals(1, model.opens.get())
        } finally { model.release.countDown(); drained(supervisor) }
    }

    @Test fun unknownAdmissionIsCachedWithoutSendingOrReenqueueing() {
        val journal = Journal().apply { failEnqueue = true }; val created = AtomicInteger(); val parent = Parent()
        val supervisor = RetainedChildTasks(journal)
        val binding = parent.bind(supervisor, mapOf("p" to ChildModelDefinition("pool", 1) { created.incrementAndGet(); error("not called") }))
        try {
            val result = supervisor.submit(binding, parent.context(), listOf(ChildModelTask("p", "prompt")))
            assertEquals(ChildTaskAdmission.Unconfirmed("child_admission_unconfirmed"), result)
            assertEquals(result, supervisor.submit(binding, parent.context(), listOf(ChildModelTask("p", "prompt"))))
            assertEquals(1, journal.enqueues.get()); assertEquals(0, created.get())
            val snapshot = supervisor.snapshots(binding).single(); assertTrue(snapshot.settled); assertTrue(snapshot.journalUnconfirmed)
            assertEquals(ChildTaskStatus.QUEUED, snapshot.record.status)
        } finally { drained(supervisor) }
    }

    @Test fun malformedBatchAndCancelledEntryDoNotTouchJournal() {
        val journal = Journal(); val parent = Parent(); val supervisor = RetainedChildTasks(journal)
        val binding = parent.bind(supervisor, mapOf("p" to ChildModelDefinition("pool", 1) { error("not called") }))
        try {
            assertTrue(supervisor.submit(binding, parent.context(), listOf(ChildModelTask("p", "ok"), ChildModelTask("missing", "bad"))) is ChildTaskAdmission.Rejected)
            assertTrue(supervisor.submit(binding, parent.context(cancelled = { true }), listOf(ChildModelTask("p", "ok"))) is ChildTaskAdmission.Rejected)
            assertTrue(supervisor.submit(binding, parent.context(), List(9) { ChildModelTask("p", "ok") }) is ChildTaskAdmission.Rejected)
            assertEquals(0, journal.enqueues.get()); assertTrue(supervisor.snapshots(binding).isEmpty())
        } finally { drained(supervisor) }
    }

    @Test fun foreignOwnerAuthorizationAndHandlesCannotControlAnotherBinding() {
        val first = Parent(); val second = Parent("two"); val model = HeldModel(); val supervisor = RetainedChildTasks(Journal())
        val a = first.bind(supervisor, mapOf("p" to definition(model))); val b = second.bind(supervisor, mapOf("p" to definition(model)))
        try {
            assertThrows(IllegalArgumentException::class.java) { supervisor.submit(a, second.context(), listOf(ChildModelTask("p", "x"))) }
            val wrongAuth = TaskAuthorization(first.session.sessionId, first.owner.runId.value)
            val wrongContext = ToolExecutionContext(first.owner, first.context().invocation, wrongAuth) { false }
            assertThrows(IllegalArgumentException::class.java) { supervisor.submit(a, wrongContext, listOf(ChildModelTask("p", "x"))) }
            val accepted = submit(supervisor, a, first, "p")
            assertEquals(ChildTaskStop.NOT_FOUND, supervisor.stop(b, accepted.tasks.single().owner))
            assertTrue(supervisor.snapshots(b).isEmpty())
            assertEquals(ChildTaskStop.NOT_FOUND, supervisor.stop(a, accepted.tasks.single().owner.copy(generation = 999)))
        } finally { model.release.countDown(); drained(supervisor) }
    }

    @Test fun sharedPoolAcrossParentsDoesNotReleasePermitOnCancelRequest() {
        val model = HeldModel(); val first = Parent(); val second = Parent("two"); val supervisor = RetainedChildTasks(Journal())
        val a = first.bind(supervisor, mapOf("p" to definition(model))); val b = second.bind(supervisor, mapOf("p" to definition(model)))
        try {
            val accepted = submit(supervisor, a, first, "p"); assertTrue(model.entered.await(3, TimeUnit.SECONDS))
            supervisor.stop(a, accepted.tasks.single().owner)
            assertTrue(model.cancelled.await(3, TimeUnit.SECONDS))
            submit(supervisor, b, second, "p")
            assertEquals(1, model.opens.get())
            assertTrue(supervisor.snapshots(b).none { it.settled })
            model.release.countDown(); await("both exit") { supervisor.snapshots(a).all { it.settled } && supervisor.snapshots(b).all { it.settled } }
            assertEquals(2, model.opens.get())
            assertEquals(ChildTaskStatus.COMPLETED, supervisor.snapshots(b).single().record.status)
        } finally { model.release.countDown(); drained(supervisor) }
    }

    @Test fun closeDuringEnqueueCannotDispatchAndDrainWaitsForJournalReturn() {
        val journal = Journal(); val entered = CountDownLatch(1); val release = CountDownLatch(1); val idle = CountDownLatch(1)
        journal.beforeEnqueue = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val parent = Parent(); val supervisor = RetainedChildTasks(journal); val created = AtomicInteger()
        val binding = parent.bind(supervisor, mapOf("p" to ChildModelDefinition("pool", 1) { created.incrementAndGet(); error("not called") }))
        val result = AtomicReference<ChildTaskAdmission>(); val failure = AtomicReference<Throwable>()
        val thread = Thread { try { result.set(supervisor.submit(binding, parent.context(), listOf(ChildModelTask("p", "prompt")))) } catch (t: Throwable) { failure.set(t) } }
        try {
            thread.start(); assertTrue(entered.await(3, TimeUnit.SECONDS)); supervisor.closeWhenIdle { idle.countDown() }
            assertEquals(1L, idle.count); release.countDown(); thread.join(3000)
            assertFalse(thread.isAlive); assertNull(failure.get()); assertTrue(result.get() is ChildTaskAdmission.Accepted)
            assertTrue(idle.await(3, TimeUnit.SECONDS)); assertEquals(0, created.get())
            val view = supervisor.snapshots(binding).single(); assertTrue(view.settled); assertEquals(ChildTaskStatus.NOT_EXECUTED, view.record.status)
        } finally { release.countDown(); thread.join(3000); drained(supervisor) }
    }

    @Test fun finishFailureRetainsLastConfirmedStateAndNeverReportsCompleted() {
        val journal = Journal().apply { failFinish = true }; val model = HeldModel(); val parent = Parent(); val supervisor = RetainedChildTasks(journal)
        val binding = parent.bind(supervisor, mapOf("p" to definition(model)))
        try {
            submit(supervisor, binding, parent, "p"); assertTrue(model.entered.await(3, TimeUnit.SECONDS)); model.release.countDown()
            await("worker exits") { supervisor.snapshots(binding).single().settled }
            val view = supervisor.snapshots(binding).single(); assertEquals(ChildTaskStatus.RUNNING, view.record.status)
            assertTrue(view.journalUnconfirmed); assertEquals(1, model.opens.get())
        } finally { model.release.countDown(); drained(supervisor) }
    }

    @Test fun poolLimitConflictsDuplicateBindingAndRetentionOverflowFailBeforeRequests() {
        val journal = Journal(); val first = Parent(); val second = Parent("two"); val model = HeldModel()
        val supervisor = RetainedChildTasks(journal, maxRetainedTasks = 1)
        val binding = first.bind(supervisor, mapOf("p" to definition(model)))
        try {
            assertThrows(IllegalStateException::class.java) { first.bind(supervisor, mapOf("p" to definition(model))) }
            assertThrows(IllegalArgumentException::class.java) { second.bind(supervisor, mapOf("p" to definition(model, limit = 2))) }
            submit(supervisor, binding, first, "p")
            assertEquals(ChildTaskAdmission.Rejected("child_retention_limit"),
                supervisor.submit(binding, first.context("second"), listOf(ChildModelTask("p", "another"))))
            assertEquals(1, journal.enqueues.get())
        } finally { model.release.countDown(); drained(supervisor) }
    }

    @Test fun explicitRevocationCancelsAllChildrenButNormalParentFinishDoesNot() {
        val model = HeldModel(); val parent = Parent(); val supervisor = RetainedChildTasks(Journal())
        val binding = parent.bind(supervisor, mapOf("p" to definition(model)))
        try {
            submit(supervisor, binding, parent, "p"); assertTrue(model.entered.await(3, TimeUnit.SECONDS))
            supervisor.revoke(binding); assertTrue(model.cancelled.await(3, TimeUnit.SECONDS))
            val done = CountDownLatch(1); supervisor.closeWhenIdle { done.countDown() }; assertEquals(1L, done.count)
            model.release.countDown(); assertTrue(done.await(3, TimeUnit.SECONDS))
            assertEquals(ChildTaskStatus.UNKNOWN, supervisor.snapshots(binding).single().record.status)
        } finally { model.release.countDown(); drained(supervisor) }
    }
    @Test fun failedSiblingDoesNotCancelOrEraseSuccessfulSibling() {
        val good = HeldModel(); val parent = Parent(); val supervisor = RetainedChildTasks(Journal())
        val bad = ModelPort { throw IllegalStateException("sent request failed") }
        val binding = parent.bind(supervisor, mapOf("good" to definition(good, "good"), "bad" to definition(bad, "bad")))
        try {
            submit(supervisor, binding, parent, "good", "bad")
            assertTrue(good.entered.await(3, TimeUnit.SECONDS))
            await("failed sibling settled") { supervisor.snapshots(binding)[1].settled }
            assertEquals(1L, good.cancelled.count)
            good.release.countDown(); await("good sibling settled") { supervisor.snapshots(binding)[0].settled }
            val snapshots = supervisor.snapshots(binding)
            assertEquals(ChildTaskStatus.COMPLETED, snapshots[0].record.status)
            assertEquals("answer", snapshots[0].record.text)
            assertEquals(ChildTaskStatus.UNKNOWN, snapshots[1].record.status)
        } finally { good.release.countDown(); drained(supervisor) }
    }

    @Test fun notificationsAreOutsideControlLockAndListenerFailureCannotChangeOutcome() {
        val model = HeldModel(); val parent = Parent(); val supervisor = RetainedChildTasks(Journal())
        val completed = CountDownLatch(1)
        val references = AtomicReference<ChildTaskBinding>()
        val binding = supervisor.bind(parent.owner, parent.session, parent.authorization, mapOf("p" to definition(model))) {
            val probe = Thread { references.get()?.let { if (supervisor.snapshots(it).any { v -> v.settled }) completed.countDown() } }
            probe.start(); probe.join(1000); check(!probe.isAlive) { "listener invoked under control lock" }
            throw IllegalStateException("UI listener failed")
        }
        references.set(binding)
        try {
            submit(supervisor, binding, parent, "p"); assertTrue(model.entered.await(3, TimeUnit.SECONDS))
            model.release.countDown(); assertTrue(completed.await(3, TimeUnit.SECONDS))
            assertEquals(ChildTaskStatus.COMPLETED, supervisor.snapshots(binding).single().record.status)
        } finally { model.release.countDown(); drained(supervisor) }
    }

    @Test fun startPersistenceFailureNeverOpensModelAndFinalUnsentWriteIsAuthoritative() {
        val journal = Journal().apply { failStart = true }; val model = HeldModel(); val parent = Parent()
        val supervisor = RetainedChildTasks(journal); val binding = parent.bind(supervisor, mapOf("p" to definition(model)))
        try {
            submit(supervisor, binding, parent, "p"); await("failed start settles") { supervisor.snapshots(binding).single().settled }
            val snapshot = supervisor.snapshots(binding).single()
            assertEquals(ChildTaskStatus.NOT_EXECUTED, snapshot.record.status); assertFalse(snapshot.journalUnconfirmed)
            assertEquals(0, model.opens.get())
        } finally { model.release.countDown(); drained(supervisor) }
    }

    @Test fun timeoutRetainsPoolAndCloseResponsibilityUntilTransportExits() {
        val model = HeldModel(); val parent = Parent(); val supervisor = RetainedChildTasks(Journal(), timeoutMillis = 200)
        val binding = parent.bind(supervisor, mapOf("p" to definition(model)))
        try {
            submit(supervisor, binding, parent, "p"); assertTrue(model.entered.await(3, TimeUnit.SECONDS))
            assertTrue(model.cancelled.await(3, TimeUnit.SECONDS))
            assertFalse(supervisor.snapshots(binding).single().settled)
            val idle = CountDownLatch(1); supervisor.closeWhenIdle { idle.countDown() }; assertEquals(1L, idle.count)
            model.release.countDown(); assertTrue(idle.await(3, TimeUnit.SECONDS))
            assertEquals(ChildTaskStatus.UNKNOWN, supervisor.snapshots(binding).single().record.status)
            assertEquals(1, model.opens.get())
        } finally { model.release.countDown(); drained(supervisor) }
    }

    private fun usageModel(body: ((ModelEvent) -> Unit) -> Unit, cancel: () -> Unit = {}) = ModelPort {
        object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) = body(sink)
            override fun cancel() = cancel.invoke()
        }
    }

    @Test fun retainedUsageAfterParentFinishKeepsActualRequestAndLatestTerminalSnapshot() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val parent = Parent()
        val rows = ConcurrentHashMap<String, List<AttributedRequestUsage>>()
        val supervisor = RetainedChildTasks(Journal(), usageRecorder = ChildUsageRecorder { task, checkpoint ->
            if (checkpoint.usage.isNotEmpty()) rows[task.owner.childRunId] = childRequestUsage(task, checkpoint)
        })
        val model = usageModel({ sink ->
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
            sink(ModelEvent.Usage(10, 20, 5)); sink(ModelEvent.TextFinal("t", "answer"))
            sink(ModelEvent.Usage(null, 0, 0))
        })
        val binding = parent.bind(supervisor, mapOf("p" to definition(model)))
        try {
            val admitted = submit(supervisor, binding, parent, "p")
            assertTrue(entered.await(3, TimeUnit.SECONDS)); supervisor.finishParent(binding); release.countDown()
            await("retained usage settles") { supervisor.snapshots(binding).single().settled }
            assertEquals(ChildTaskStatus.COMPLETED, supervisor.snapshots(binding).single().record.status)
            val row = rows.values.single().single()
            assertEquals(admitted.tasks.single().owner.childRunId, row.runId)
            assertEquals(parent.message.runId.value, row.parentRunId); assertEquals(parent.message.id.value, row.parentMessageId)
            assertEquals(ModelEvent.Usage(null, 0, 0), row.snapshot)
            assertEquals(UsageOrigin.Child(requireNotNull(row.runId), "p", admitted.tasks.single().owner.taskGroupId), row.origin)
            assertTrue(row.request.requestId.isNotBlank()); assertTrue(row.request.attemptId.isNotBlank())
        } finally { release.countDown(); drained(supervisor) }
    }

    @Test fun sameUsageFromRetainedSiblingsUsesDistinctActualRequests() {
        val parent = Parent(); val rows = ConcurrentHashMap<String, List<AttributedRequestUsage>>()
        val supervisor = RetainedChildTasks(Journal(), usageRecorder = ChildUsageRecorder { task, checkpoint ->
            if (checkpoint.usage.isNotEmpty()) rows[task.owner.childRunId] = childRequestUsage(task, checkpoint)
        })
        val model = usageModel({ sink -> sink(ModelEvent.Usage(10, 20, 5)); sink(ModelEvent.TextFinal("t", "answer")) })
        val binding = parent.bind(supervisor, mapOf("p" to definition(model, limit = 0)))
        try {
            submit(supervisor, binding, parent, "p", "p")
            await("siblings settle") { supervisor.snapshots(binding).all { it.settled } }
            assertEquals(2, rows.size)
            val all = rows.values.flatten(); assertEquals(2, all.map { it.request.requestId }.distinct().size)
            assertEquals(2L, aggregateAttributedUsage(all).child.requestSnapshotCount)
            assertEquals(20L, aggregateAttributedUsage(all).child.input.reportedTokens)
            assertTrue(supervisor.snapshots(binding).all { it.record.status == ChildTaskStatus.COMPLETED })
        } finally { drained(supervisor) }
    }

    @Test fun retainedUsagePersistenceFailureCannotPublishSuccessOrReplay() {
        val parent = Parent(); val calls = AtomicInteger()
        val supervisor = RetainedChildTasks(Journal(), usageRecorder = ChildUsageRecorder { _, checkpoint ->
            if (checkpoint.usage.isNotEmpty()) error("usage write failed")
        })
        val model = usageModel({ sink ->
            calls.incrementAndGet(); sink(ModelEvent.Usage(4, 5)); sink(ModelEvent.TextFinal("t", "must not publish"))
        })
        val binding = parent.bind(supervisor, mapOf("p" to definition(model)))
        try {
            submit(supervisor, binding, parent, "p")
            await("usage failure settles") { supervisor.snapshots(binding).single().settled }
            assertEquals(ChildTaskStatus.UNKNOWN, supervisor.snapshots(binding).single().record.status)
            assertEquals(ChildTaskAdmission.Rejected("child_request_settled"),
                supervisor.submit(binding, parent.context(), listOf(ChildModelTask("p", "Review p"))))
            assertEquals(1, calls.get())
        } finally { drained(supervisor) }
    }

    @Test fun stopRevokeAndCloseRetainReportedUsageWithoutAcceptingLateEvents() {
        for (action in listOf("stop", "revoke", "close")) {
            val parent = Parent(action); val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val cancelled = CountDownLatch(1); val rows = ConcurrentHashMap<String, List<AttributedRequestUsage>>()
            val supervisor = RetainedChildTasks(Journal(), usageRecorder = ChildUsageRecorder { task, checkpoint ->
                if (checkpoint.usage.isNotEmpty()) rows[task.owner.childRunId] = childRequestUsage(task, checkpoint)
            })
            val model = usageModel({ sink ->
                sink(ModelEvent.Usage(7, null)); entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                sink(ModelEvent.Usage(99, 99)); sink(ModelEvent.TextFinal("t", "late result"))
            }, { cancelled.countDown() })
            val binding = parent.bind(supervisor, mapOf("p" to definition(model)))
            try {
                val accepted = submit(supervisor, binding, parent, "p"); assertTrue(entered.await(3, TimeUnit.SECONDS))
                when (action) {
                    "stop" -> assertEquals(ChildTaskStop.REQUESTED, supervisor.stop(binding, accepted.tasks.single().owner))
                    "revoke" -> supervisor.revoke(binding)
                    else -> supervisor.close()
                }
                assertTrue(cancelled.await(3, TimeUnit.SECONDS)); assertFalse(supervisor.snapshots(binding).single().settled)
                release.countDown(); await("cancelled usage settles") { supervisor.snapshots(binding).single().settled }
                assertEquals(ChildTaskStatus.UNKNOWN, supervisor.snapshots(binding).single().record.status)
                assertEquals(ModelEvent.Usage(7, null), rows.values.single().single().snapshot)
            } finally { release.countDown(); drained(supervisor) }
        }
    }

}
