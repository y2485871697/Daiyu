package app.daiyu.android.domain.delegation

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.SessionCapabilities
import app.daiyu.android.domain.tools.TaskAuthorization
import app.daiyu.android.domain.tools.ToolExecutionContext
import app.daiyu.android.domain.tools.ExecutionReceipt
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Exercise the synchronous batch's own pool, not RetainedChildTasks' outer pool. */
class ChildModelBatchPoolPermitTest {
    private class Journal(private val failAt: String? = null) : ChildTaskStore {
        val firstTerminal = CountDownLatch(1)
        override fun enqueue(parent: RunExecutionOwner, tasks: List<ChildTaskRecord>) {}
        override fun started(task: ChildTaskRecord) { check(failAt != "start") }
        override fun finished(task: ChildTaskRecord, status: ChildTaskStatus, text: String, detail: String?) {
            firstTerminal.countDown()
            check(failAt != "finish")
        }
        override fun list(conversationId: String, limit: Int) = emptyList<ChildTaskRecord>()
    }

    private class Session(holdCancel: Boolean = true, private val failNext: Boolean = false,
        private val failCancel: Boolean = false) : ModelSession {
        val entered = CountDownLatch(1)
        val returnNext = CountDownLatch(1)
        val nextReturned = CountDownLatch(1)
        val cancelEntered = CountDownLatch(1)
        val returnCancel = CountDownLatch(if (holdCancel) 1 else 0)
        val cancelReturned = CountDownLatch(1)
        val cancels = AtomicInteger()
        override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
            entered.countDown()
            try {
                check(returnNext.await(10, TimeUnit.SECONDS))
                check(!failNext) { "stream failed" }
                sink(ModelEvent.TextFinal("answer", "done"))
            } finally { nextReturned.countDown() }
        }
        override fun cancel() {
            cancels.incrementAndGet(); cancelEntered.countDown()
            try {
                check(returnCancel.await(10, TimeUnit.SECONDS))
                check(!failCancel) { "cleanup failed after returning resources" }
            } finally { cancelReturned.countDown() }
        }
        fun release() { returnNext.countDown(); returnCancel.countDown() }
    }

    private class Fixture(port: ModelPort, val journal: Journal = Journal(),
        create: (() -> ModelPort)? = null) {
        private val owner = RunExecutionOwner.issue(Message(MessageId("parent"), ConversationId("conversation"),
            RunId("parent-run"), Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING))
        private val session = SessionCapabilities("session", emptySet())
        private val authorization = TaskAuthorization(session.sessionId, owner.runId.value)
        private val definition = ChildModelDefinition("shared", 1, create ?: { port })
        val batch = ChildModelBatch(mapOf("one" to definition, "two" to definition), store = journal).apply {
            bind(owner, session, authorization)
        }
        // Read the actual private semaphore: a drain callback alone cannot detect over-release.
        val pool: Semaphore = run {
            val field = ChildModelBatch::class.java.getDeclaredField("limits").apply { isAccessible = true }
            (field.get(batch) as Map<*, *>)["shared"] as Semaphore
        }
        val result = AtomicReference<ExecutionReceipt>()
        private val failure = AtomicReference<Throwable>()
        private var thread: Thread? = null
        fun start(two: Boolean = true) {
            val tasks = if (two) listOf(ChildModelTask("one", "first"), ChildModelTask("two", "second"))
                else listOf(ChildModelTask("one", "first"))
            thread = Thread {
                try {
                    result.set(batch.execute(ToolExecutionContext(owner,
                        ToolInvocation(ToolCallId("call"), "run_child_models", "{}"), authorization) { false }, tasks))
                } catch (t: Throwable) { failure.set(t) }
            }.apply { isDaemon = true; start() }
        }
        fun join() {
            val execution = checkNotNull(thread)
            execution.join(3000)
            assertFalse("synchronous execution returned", execution.isAlive)
            assertNull(failure.get())
        }
        fun drain() {
            val idle = CountDownLatch(1)
            batch.closeWhenIdle { idle.countDown() }
            join()
            assertTrue("both workers and transport cleanup drained", idle.await(3, TimeUnit.SECONDS))
            repeat(3) { batch.close() }
            assertEquals("exactly one permit after all cleanup, including repeated close", 1, pool.availablePermits())
        }
    }

    private fun port(opens: AtomicInteger, vararg sessions: Session) = object : ModelPort {
        override fun open(history: List<Message>): ModelSession = error("explicit empty definitions required")
        override fun open(history: List<Message>, definitions: List<ToolDefinition>): ModelSession {
            assertTrue(definitions.isEmpty())
            return sessions[opens.getAndIncrement()]
        }
    }

    @Test fun samePoolWaiterOpensOnlyAfterFirstWorkersBlockingCleanupReturns() {
        for (failCancel in listOf(false, true)) {
            val opens = AtomicInteger()
            val first = Session(failCancel = failCancel); val second = Session(holdCancel = false)
            val fixture = Fixture(port(opens, first, second))
            try {
                fixture.start()
                assertTrue(first.entered.await(3, TimeUnit.SECONDS))
                first.returnNext.countDown()
                assertTrue(first.nextReturned.await(3, TimeUnit.SECONDS))
                assertTrue(first.cancelEntered.await(3, TimeUnit.SECONDS))
                assertTrue(fixture.journal.firstTerminal.await(3, TimeUnit.SECONDS))
                assertFalse("worker result must not free a still-cleaning pool", second.entered.await(200, TimeUnit.MILLISECONDS))
                assertEquals(1, opens.get()); assertEquals(0, fixture.pool.availablePermits())
                first.returnCancel.countDown()
                assertTrue("release this item's permit, not after the whole batch drains", second.entered.await(3, TimeUnit.SECONDS))
                assertEquals(2, opens.get()); assertEquals(0, fixture.pool.availablePermits())
                second.returnNext.countDown()
                fixture.join()
                assertTrue(fixture.result.get() is ExecutionReceipt.Confirmed)
                fixture.drain()
                assertEquals(1, first.cancels.get()); assertEquals(1, second.cancels.get())
            } finally { first.release(); second.release(); fixture.drain() }
        }
    }

    @Test fun cancellationReturningBeforeWorkerAndRepeatedCloseCannotFreePermitEarly() {
        val opens = AtomicInteger(); val first = Session(); val second = Session(holdCancel = false)
        val fixture = Fixture(port(opens, first, second))
        val idle = CountDownLatch(1); val callbacks = AtomicInteger()
        try {
            fixture.start()
            assertTrue(first.entered.await(3, TimeUnit.SECONDS))
            fixture.batch.closeWhenIdle { callbacks.incrementAndGet(); idle.countDown() }
            repeat(3) { fixture.batch.close() }
            assertTrue(first.cancelEntered.await(3, TimeUnit.SECONDS))
            first.returnCancel.countDown()
            assertTrue(first.cancelReturned.await(3, TimeUnit.SECONDS))
            assertFalse("worker is still using the transport", idle.await(200, TimeUnit.MILLISECONDS))
            assertEquals(0, fixture.pool.availablePermits()); assertEquals(1, opens.get())
            first.returnNext.countDown()
            assertTrue(idle.await(3, TimeUnit.SECONDS))
            fixture.drain()
            assertTrue(fixture.result.get() is ExecutionReceipt.Unknown)
            assertEquals(1, callbacks.get()); assertEquals(1, first.cancels.get())
            assertEquals(1L, second.entered.count)
        } finally { first.release(); second.release(); fixture.drain() }
    }

    @Test fun failuresBeforeTransportCreationStillReturnTheAcquiredPermit() {
        for (phase in listOf("factory", "start", "open")) {
            val opens = AtomicInteger()
            val port = object : ModelPort {
                override fun open(history: List<Message>): ModelSession {
                    opens.incrementAndGet()
                    error("open failed without a transport")
                }
            }
            val fixture = Fixture(port, Journal(failAt = phase), create = {
                check(phase != "factory") { "factory failed" }
                port
            })
            try {
                fixture.start(two = false)
                fixture.join()
                fixture.drain()
                if (phase == "open") assertTrue(fixture.result.get() is ExecutionReceipt.Unknown)
                else assertTrue(fixture.result.get() is ExecutionReceipt.NotExecuted)
                assertEquals(if (phase == "open") 1 else 0, opens.get())
            } finally { fixture.drain() }
        }
    }

    @Test fun streamAndTerminalJournalFailuresKeepPermitUntilCleanupEvenOnRepeatedCancel() {
        for (phase in listOf("next", "finish")) {
            val opens = AtomicInteger(); val transport = Session(failNext = phase == "next")
            val fixture = Fixture(port(opens, transport), Journal(failAt = phase))
            try {
                fixture.start(two = false)
                assertTrue(transport.entered.await(3, TimeUnit.SECONDS))
                transport.returnNext.countDown()
                assertTrue(transport.cancelEntered.await(3, TimeUnit.SECONDS))
                assertTrue(fixture.journal.firstTerminal.await(3, TimeUnit.SECONDS))
                fixture.join()
                assertTrue(fixture.result.get() is ExecutionReceipt.Unknown)
                repeat(3) { fixture.batch.close() }
                val idle = CountDownLatch(1)
                fixture.batch.closeWhenIdle { idle.countDown() }
                assertFalse(idle.await(200, TimeUnit.MILLISECONDS))
                assertEquals(0, fixture.pool.availablePermits()); assertEquals(1, transport.cancels.get())
                transport.returnCancel.countDown()
                assertTrue(idle.await(3, TimeUnit.SECONDS))
                fixture.drain()
                assertEquals(1, opens.get()); assertEquals(1, transport.cancels.get())
            } finally { transport.release(); fixture.drain() }
        }
    }
}
