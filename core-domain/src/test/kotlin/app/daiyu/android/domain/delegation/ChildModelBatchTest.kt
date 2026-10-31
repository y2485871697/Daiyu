package app.daiyu.android.domain.delegation

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ChildModelBatchTest {
    private val parent = Message(MessageId("parent"), ConversationId("conversation"), RunId("parent-run"),
        Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
    private val owner = RunExecutionOwner.issue(parent)
    private val capabilities = SessionCapabilities("session", setOf("delegation.text.review", "device.write"))
    private val authorization = TaskAuthorization("session", parent.runId.value)
    private fun context(auth: TaskAuthorization = authorization) = ToolExecutionContext(owner,
        ToolInvocation(ToolCallId("call"), "run_child_models", "{}"), auth) { false }
    private fun bind(batch: ChildModelBatch) = batch.apply { bind(owner, capabilities, authorization) }
    private fun port(body: (List<Message>, (ModelEvent) -> Unit) -> Unit): ModelPort = object : ModelPort {
        override fun open(history: List<Message>): ModelSession = error("legacy default tools")
        override fun open(history: List<Message>, definitions: List<app.daiyu.android.domain.ToolDefinition>): ModelSession {
            assertTrue(definitions.isEmpty()); assertTrue(history.all { it.runId != parent.runId })
            return object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) { body(history, sink) }
                override fun cancel() {}
            }
        }
    }

    @Test fun deletedModelRejectsNewSynchronousBatchBeforeAnyModelCreation() {
        val created = AtomicInteger()
        val definition = ChildModelDefinition("pool", 1, null, latest = null, selection = "saved-model",
            admissionAllowed = { false }) { created.incrementAndGet(); error("must not create") }
        val batch = bind(ChildModelBatch(mapOf("one" to definition)))
        try {
            assertEquals(ExecutionReceipt.NotExecuted("child_model_deleted"), batch.execute(context(), listOf(ChildModelTask("one", "task"))))
            assertEquals(0, created.get())
            assertTrue(batch.liveProfiles().isEmpty())
        } finally { batch.close() }
    }

    @Test fun liveProfilesReleaseEachCardIndependentlyRatherThanWaitingForWholeBatch() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val quick = port { _, sink -> sink(ModelEvent.TextFinal("quick", "done")) }
        val held = port { _, sink -> entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); sink(ModelEvent.TextFinal("held", "done")) }
        val batch = bind(ChildModelBatch(mapOf("one" to ChildModelDefinition("quick", 1) { quick },
            "two" to ChildModelDefinition("held", 1) { held })))
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val receipt = executor.submit<ExecutionReceipt> { batch.execute(context(), listOf(ChildModelTask("one", "quick"), ChildModelTask("two", "held"))) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (batch.liveProfiles() != setOf("two") && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(setOf("two"), batch.liveProfiles())
            release.countDown()
            assertTrue(receipt.get(5, TimeUnit.SECONDS) is ExecutionReceipt.Confirmed)
            while (batch.liveProfiles().isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(5)
            assertTrue(batch.liveProfiles().isEmpty())
        } finally { release.countDown(); batch.close(); executor.shutdownNow() }
    }

    @Test fun sameApiModelProfilesShareLimitAndZeroAddsNoLimit() {
        for (limit in listOf(1, 2, 0)) {
            val active = AtomicInteger(); val peak = AtomicInteger(); val ids = java.util.Collections.synchronizedSet(mutableSetOf<RunId>())
            val child = port { history, sink ->
                assertTrue(ids.add(history.single().runId))
                val n = active.incrementAndGet(); peak.updateAndGet { maxOf(it, n) }
                try { Thread.sleep(80); sink(ModelEvent.TextFinal("text", history.single().blocks.filterIsInstance<MessageBlock.Text>().single().text)) }
                finally { active.decrementAndGet() }
            }
            val first = ChildModelDefinition("shared", limit) { child }
            val batch = bind(ChildModelBatch(mapOf("one" to first, "two" to first)))
            try {
                val receipt = batch.execute(context(), listOf(ChildModelTask("one", "first"), ChildModelTask("two", "second"))) as ExecutionReceipt.Confirmed
                assertTrue(receipt.payload.indexOf("first") < receipt.payload.indexOf("second"))
                assertEquals(if (limit == 1) 1 else 2, peak.get()); assertEquals(2, ids.size)
            } finally { batch.close() }
        }
    }

    @Test fun differentModelPoolsCanOverlapAtLimitOne() {
        val both = CountDownLatch(2)
        val child = port { _, sink -> both.countDown(); check(both.await(3, TimeUnit.SECONDS)); sink(ModelEvent.TextFinal("t", "answer")) }
        val batch = bind(ChildModelBatch(mapOf("one" to ChildModelDefinition("a", 1) { child },
            "two" to ChildModelDefinition("b", 1) { child })))
        try { assertTrue(batch.execute(context(), listOf(ChildModelTask("one", "1"), ChildModelTask("two", "2"))) is ExecutionReceipt.Confirmed) }
        finally { batch.close() }
    }

    @Test fun wholeInputValidationAndForeignAuthorizationDoNotCreateModels() {
        val created = AtomicInteger()
        val batch = bind(ChildModelBatch(mapOf("one" to ChildModelDefinition("pool", 1) { created.incrementAndGet(); error("not called") })))
        try {
            assertTrue(batch.execute(context(), listOf(ChildModelTask("one", "ok"), ChildModelTask("unknown", "bad"))) is ExecutionReceipt.NotExecuted)
            assertTrue(batch.execute(context(), emptyList()) is ExecutionReceipt.NotExecuted)
            assertTrue(batch.execute(context(), listOf(ChildModelTask("one", " "))) is ExecutionReceipt.NotExecuted)
            assertThrows(IllegalArgumentException::class.java) { batch.execute(context(TaskAuthorization("session", parent.runId.value)), listOf(ChildModelTask("one", "ok"))) }
            assertEquals(0, created.get())
        } finally { batch.close() }
    }

    @Test fun oversizedStreamAndUnexpectedToolsCannotReturnSuccessOrReopen() {
        for (event in listOf<ModelEvent>(ModelEvent.TextDelta("t", "x".repeat(65537)),
            ModelEvent.ToolReady(ToolInvocation(ToolCallId("evil"), "write_file", "{}")))) {
            val opened = AtomicInteger()
            val batch = bind(ChildModelBatch(mapOf("one" to ChildModelDefinition("pool", 1) {
                opened.incrementAndGet(); port { _, sink -> sink(event) }
            })))
            try {
                assertTrue(batch.execute(context(), listOf(ChildModelTask("one", "task"))) is ExecutionReceipt.Unknown)
                assertTrue(batch.execute(context(), listOf(ChildModelTask("one", "again"))) is ExecutionReceipt.NotExecuted)
                assertEquals(1, opened.get())
            } finally { batch.close() }
        }
    }

    @Test fun timeoutRequestsCancelButDrainsOnlyAfterRealExit() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val cancelled = CountDownLatch(1); val idle = CountDownLatch(1)
        val child = object : ModelPort {
            override fun open(history: List<Message>) = object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); sink(ModelEvent.TextFinal("t", "late"))
                }
                override fun cancel() { cancelled.countDown() }
            }
        }
        val batch = bind(ChildModelBatch(mapOf("one" to ChildModelDefinition("pool", 1) { child }), 200))
        val result = AtomicReference<ExecutionReceipt>(); val failure = AtomicReference<Throwable>()
        val thread = Thread { try { result.set(batch.execute(context(), listOf(ChildModelTask("one", "task")))) } catch (e: Throwable) { failure.set(e) } }
        try {
            thread.start(); assertTrue(entered.await(3, TimeUnit.SECONDS)); thread.join(2000)
            assertFalse(thread.isAlive); assertNull(failure.get()); assertTrue(result.get() is ExecutionReceipt.Unknown)
            batch.closeWhenIdle { idle.countDown() }
            assertTrue(cancelled.await(3, TimeUnit.SECONDS)); assertEquals(1L, idle.count)
            release.countDown(); assertTrue(idle.await(3, TimeUnit.SECONDS))
        } finally { release.countDown(); batch.close(); thread.join(3000) }
    }

    @Test fun transportCannotSwallowPolicyViolationAndReturnConfirmed() {
        for (bad in listOf<ModelEvent>(ModelEvent.TextDelta("t", "x".repeat(65537)),
            ModelEvent.ToolReady(ToolInvocation(ToolCallId("evil"), "write_file", "{}")))) {
            val batch = bind(ChildModelBatch(mapOf("one" to ChildModelDefinition("pool", 1) {
                port { _, sink ->
                    sink(ModelEvent.TextFinal("good", "initial valid text"))
                    try { sink(bad) } catch (_: Exception) {}
                    try { sink(ModelEvent.TextFinal("late", "must not become success")) } catch (_: Exception) {}
                }
            })))
            try { assertTrue(batch.execute(context(), listOf(ChildModelTask("one", "task"))) is ExecutionReceipt.Unknown) }
            finally { batch.close() }
        }
    }

    @Test fun localFactoryFailureIsNotExecuted() {
        val batch = bind(ChildModelBatch(mapOf("one" to ChildModelDefinition("pool", 1) { error("local configuration failure") })))
        try { assertTrue(batch.execute(context(), listOf(ChildModelTask("one", "task"))) is ExecutionReceipt.NotExecuted) }
        finally { batch.close() }
    }

    @Test fun closeDuringLocalFactoryCannotOpenAfterNotExecuted() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val idle = CountDownLatch(1); val opened = AtomicInteger()
        val batch = bind(ChildModelBatch(mapOf("one" to ChildModelDefinition("pool", 1) {
            entered.countDown(); check(release.await(3, TimeUnit.SECONDS))
            ModelPort { opened.incrementAndGet(); error("must not open") }
        })))
        val result = AtomicReference<ExecutionReceipt>(); val failure = AtomicReference<Throwable>()
        val thread = Thread { try { result.set(batch.execute(context(), listOf(ChildModelTask("one", "task")))) } catch (e: Throwable) { failure.set(e) } }
        try {
            thread.start(); assertTrue(entered.await(3, TimeUnit.SECONDS)); batch.closeWhenIdle { idle.countDown() }
            thread.join(2000); assertFalse(thread.isAlive); assertNull(failure.get())
            assertTrue(result.get() is ExecutionReceipt.NotExecuted); assertEquals(1L, idle.count)
            release.countDown(); assertTrue(idle.await(3, TimeUnit.SECONDS)); assertEquals(0, opened.get())
        } finally { release.countDown(); batch.close(); thread.join(3000) }
    }

    @Test fun closedBatchAcknowledgesWithoutStartingAnyModel() {
        val batch = bind(ChildModelBatch(mapOf("one" to ChildModelDefinition("pool", 1) { error("not called") })))
        val count = AtomicInteger(); batch.close(); batch.closeWhenIdle { count.incrementAndGet() }
        assertEquals(1, count.get()); assertTrue(batch.execute(context(), listOf(ChildModelTask("one", "task"))) is ExecutionReceipt.NotExecuted)
    }
    private class Records(private val failAt: String? = null) : ChildTaskStore {
        val rows = linkedMapOf<String, ChildTaskRecord>()
        val completed = CountDownLatch(1)
        @Synchronized override fun enqueue(parent: RunExecutionOwner, tasks: List<ChildTaskRecord>) {
            check(failAt != "enqueue")
            tasks.forEach { rows[it.owner.childRunId] = it }
        }
        @Synchronized override fun started(task: ChildTaskRecord) {
            check(failAt != "start"); rows[task.owner.childRunId] = task.copy(status = ChildTaskStatus.RUNNING)
        }
        @Synchronized override fun finished(task: ChildTaskRecord, status: ChildTaskStatus, text: String, detail: String?) {
            check(failAt != "finish"); rows[task.owner.childRunId] = task.copy(status = status, text = text, detail = detail)
            if (status == ChildTaskStatus.COMPLETED) completed.countDown()
        }
        @Synchronized override fun list(conversationId: String, limit: Int) =
            rows.values.filter { it.owner.conversationId == conversationId }.take(limit)
    }

    @Test fun journalCommitsQueueAndSendBoundaryBeforeModelAndPreservesCompletedResult() {
        val records = Records(); val opens = AtomicInteger()
        val child = port { history, sink ->
            opens.incrementAndGet()
            val row = records.list(parent.conversationId.value).single()
            assertEquals(ChildTaskStatus.RUNNING, row.status)
            assertEquals(owner.ownerId, row.parentOwnerId)
            assertEquals(parent.id.value, row.parentMessageId)
            assertEquals(history.single().runId.value, row.owner.childRunId)
            sink(ModelEvent.TextFinal("text", "durable result"))
        }
        val batch = bind(ChildModelBatch(mapOf("one" to ChildModelDefinition("pool", 1) { child }), store = records))
        try {
            assertTrue(batch.execute(context(), listOf(ChildModelTask("one", "task"))) is ExecutionReceipt.Confirmed)
            val row = records.list(parent.conversationId.value).single()
            assertEquals(ChildTaskStatus.COMPLETED, row.status); assertEquals("durable result", row.text)
            assertEquals(1, opens.get()); assertEquals("task", row.prompt)
        } finally { batch.close() }
    }

    @Test fun journalAdmissionAndStartFailuresNeverOpenModel() {
        for (stage in listOf("enqueue", "start")) {
            val records = Records(stage); val opens = AtomicInteger()
            val batch = bind(ChildModelBatch(mapOf("one" to ChildModelDefinition("pool", 1) {
                ModelPort { opens.incrementAndGet(); error("must not open") }
            }), store = records))
            try {
                assertTrue(batch.execute(context(), listOf(ChildModelTask("one", "task"))) is ExecutionReceipt.NotExecuted)
                assertEquals(0, opens.get())
                if (stage == "enqueue") assertTrue(records.rows.isEmpty())
                else assertEquals(ChildTaskStatus.NOT_EXECUTED, records.rows.values.single().status)
            } finally { batch.close() }
        }
    }

    @Test fun journalFinishFailureCannotReportConfirmedOrReopenRequest() {
        val records = Records("finish"); val opens = AtomicInteger()
        val child = port { _, sink -> opens.incrementAndGet(); sink(ModelEvent.TextFinal("t", "answer")) }
        val batch = bind(ChildModelBatch(mapOf("one" to ChildModelDefinition("pool", 1) { child }), store = records))
        try {
            assertTrue(batch.execute(context(), listOf(ChildModelTask("one", "task"))) is ExecutionReceipt.Unknown)
            assertEquals(ChildTaskStatus.RUNNING, records.rows.values.single().status)
            assertTrue(batch.execute(context(), listOf(ChildModelTask("one", "task"))) is ExecutionReceipt.NotExecuted)
            assertEquals(1, opens.get())
        } finally { batch.close() }
    }

    @Test fun factoryFailureIsRecordedAsUnsent() {
        val records = Records()
        val batch = bind(ChildModelBatch(mapOf("one" to ChildModelDefinition("pool", 1) { error("local factory") }), store = records))
        try {
            assertTrue(batch.execute(context(), listOf(ChildModelTask("one", "task"))) is ExecutionReceipt.NotExecuted)
            assertEquals(ChildTaskStatus.NOT_EXECUTED, records.rows.values.single().status)
            assertEquals("child_not_sent", records.rows.values.single().detail)
        } finally { batch.close() }
    }

    @Test fun revokingOneProfileDoesNotCancelItsUnaffectedBatchSibling() {
        val records = Records(); val valid = java.util.concurrent.atomic.AtomicBoolean(true)
        val entered = CountDownLatch(2); val cancelled = CountDownLatch(1); val finish = CountDownLatch(1)
        val bad = object : ModelPort {
            override fun open(history: List<Message>): ModelSession = object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    entered.countDown(); check(cancelled.await(5, TimeUnit.SECONDS)); throw InterruptedException()
                }
                override fun cancel() { cancelled.countDown() }
            }
        }
        val good = port { _, sink -> entered.countDown(); check(finish.await(5, TimeUnit.SECONDS)); sink(ModelEvent.TextFinal("t", "kept")) }
        val batch = bind(ChildModelBatch(mapOf(
            "bad" to ChildModelDefinition("a", 1, null, null, "old", valid = valid::get) { bad },
            "good" to ChildModelDefinition("b", 1) { good }), store = records))
        val result = AtomicReference<ExecutionReceipt>(); val worker = Thread {
            result.set(batch.execute(context(), listOf(ChildModelTask("bad", "old"), ChildModelTask("good", "good"))))
        }
        try {
            worker.start(); assertTrue(entered.await(5, TimeUnit.SECONDS)); valid.set(false)
            batch.configurationChanged { null }; assertTrue(cancelled.await(5, TimeUnit.SECONDS))
            assertTrue(worker.isAlive); finish.countDown(); worker.join(5000); assertFalse(worker.isAlive)
            assertTrue(result.get() is ExecutionReceipt.Unknown)
            assertEquals("kept", records.list(parent.conversationId.value).single { it.profileId == "good" }.text)
        } finally { finish.countDown(); cancelled.countDown(); batch.close(); worker.join(5000) }
    }

    @Test fun failedSiblingCannotEraseAlreadyCommittedChildResult() {
        val records = Records()
        val good = port { _, sink -> sink(ModelEvent.TextFinal("t", "retained answer")) }
        val bad = port { _, _ -> check(records.completed.await(3, TimeUnit.SECONDS)); error("sent failure") }
        val batch = bind(ChildModelBatch(mapOf("good" to ChildModelDefinition("shared", 2) { good },
            "bad" to ChildModelDefinition("shared", 2) { bad }), store = records))
        val idle = CountDownLatch(1)
        try {
            assertTrue(batch.execute(context(), listOf(ChildModelTask("good", "first"), ChildModelTask("bad", "second"))) is ExecutionReceipt.Unknown)
            batch.closeWhenIdle { idle.countDown() }; assertTrue(idle.await(3, TimeUnit.SECONDS))
            val rows = records.list(parent.conversationId.value).associateBy { it.profileId }
            assertEquals(ChildTaskStatus.COMPLETED, rows.getValue("good").status)
            assertEquals("retained answer", rows.getValue("good").text)
            assertEquals(ChildTaskStatus.UNKNOWN, rows.getValue("bad").status)
        } finally { batch.close() }
    }

}
