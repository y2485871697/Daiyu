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
            assertTrue(batch.execute(context(), List(9) { ChildModelTask("one", "x") }) is ExecutionReceipt.NotExecuted)
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
}
