package app.daiyu.android.domain

import app.daiyu.android.domain.runtime.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

class RunnerSupplementAdmissionTest {
    private val initial = Message(MessageId("answer"), ConversationId("conversation"), RunId("run"), Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
    private fun user(id: String) = initial.copy(id = MessageId(id), role = Message.USER_ROLE,
        blocks = listOf(MessageBlock.Text("user", id)), status = RunStatus.COMPLETED)
    private class Runtime : AtomicSupplementRuntimeStore {
        @Volatile var latest: RunCheckpoint? = null
        val admissions = CopyOnWriteArrayList<RunCheckpoint>()
        var before: () -> Unit = {}
        override fun commitCheckpoint(checkpoint: RunCheckpoint) { latest = checkpoint }
        override fun commitSupplement(checkpoint: RunCheckpoint, supplement: Message) {
            before(); admissions.add(checkpoint); latest = checkpoint
        }
        override fun reconcileCheckpoint(checkpoint: RunCheckpoint): RunCheckpoint = error("no recovery")
        override fun readCommittedCheckpoint(checkpoint: RunCheckpoint) = latest
    }
    private inner class Fixture(val runtime: Runtime = Runtime(), val answer: Message = initial) : AutoCloseable {
        val opens = AtomicInteger()
        val results = LinkedBlockingQueue<SupplementAdmission>()
        val histories = CopyOnWriteArrayList<List<Message>>()
        val runner: ChatRunner
        val task: FutureTask<Message>
        init {
            runner = ChatRunner(ModelPort { history ->
                histories.add(MessageHistory.expand(history)); val first = opens.incrementAndGet() == 1
                object : ModelSession {
                    override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                        if (first) runner.pause() else sink(ModelEvent.TextFinal("body", "done"))
                    }
                    override fun cancel() {}
                }
            }, object : ConversationStore { override fun save(message: Message) {} }, BatteryTool { "ok" }, RunnerOptions(runtimeStore = runtime)) {}
            task = FutureTask { runner.run(answer, listOf(user("original"))) }
            Thread(task).apply { isDaemon = true; start() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while ((runtime.latest?.phase != ExecutionPhase.PAUSED || runner.state().phase != ExecutionPhase.PAUSED) && System.nanoTime() < deadline) Thread.yield()
            assertEquals(ExecutionPhase.PAUSED, runtime.latest?.phase)
        }
        fun offer(id: String) = runner.admitSupplement(user(id), results::add)
        fun result() = checkNotNull(results.poll(5, TimeUnit.SECONDS))
        override fun close() { runner.stop(); task.get(5, TimeUnit.SECONDS) }
    }
    @Test fun pausedCommitPrecedesAcceptanceAndExplicitResumeConsumesOnce() {
        Fixture().use { f ->
            assertTrue(f.offer("added")); assertEquals(SupplementAdmission.Accepted(MessageId("added")), f.result())
            assertEquals(listOf(user("added")), f.runtime.admissions.single().supplements)
            assertEquals(ExecutionPhase.PAUSED, f.runner.state().phase); assertEquals(1, f.opens.get())
            assertTrue(f.runner.resume()); assertEquals(RunStatus.COMPLETED, f.task.get(5, TimeUnit.SECONDS).status)
            assertEquals(listOf("original", "added"), f.histories.last().filter { it.role == Message.USER_ROLE }.map { it.id.value })
        }
    }
    @Test fun blockedCommitDoesNotAcceptEarlyOrBlockStop() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val runtime = Runtime().apply { before = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
        Fixture(runtime).use { f ->
            try {
                assertTrue(f.offer("added")); assertTrue(entered.await(5, TimeUnit.SECONDS)); assertTrue(f.results.isEmpty())
                val stopped = FutureTask { f.runner.stop() }; Thread(stopped).start(); stopped.get(1, TimeUnit.SECONDS)
                assertTrue(f.results.isEmpty()); release.countDown()
                assertEquals(SupplementAdmission.Accepted(MessageId("added")), f.result())
                assertEquals(RunStatus.STOPPED, f.task.get(5, TimeUnit.SECONDS).status); assertEquals(1, f.opens.get())
            } finally { release.countDown() }
        }
    }
    @Test fun failureIsUnconfirmedWithoutResumeOrRecovery() {
        val runtime = Runtime().apply { before = { throw IllegalStateException("write failed") } }
        Fixture(runtime).use { f ->
            assertTrue(f.offer("added")); assertEquals(SupplementAdmission.Unconfirmed(MessageId("added")), f.result())
            assertEquals(RunStatus.FAILED, f.task.get(5, TimeUnit.SECONDS).status)
            assertEquals(1, f.opens.get()); assertTrue(runtime.admissions.isEmpty()); assertTrue(f.results.isEmpty())
        }
    }
    @Test fun secondProposalDuringWriteHasItsOwnCommitAndReceipt() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val writes = AtomicInteger()
        val runtime = Runtime().apply { before = { if (writes.incrementAndGet() == 1) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) } } }
        Fixture(runtime).use { f ->
            try {
                assertTrue(f.offer("one")); assertTrue(entered.await(5, TimeUnit.SECONDS)); assertTrue(f.offer("two"))
                assertTrue(f.results.isEmpty()); release.countDown()
                assertEquals(SupplementAdmission.Accepted(MessageId("one")), f.result())
                assertEquals(SupplementAdmission.Accepted(MessageId("two")), f.result())
                assertEquals(listOf("one"), runtime.admissions[0].supplements.map { it.id.value })
                assertEquals(listOf("one", "two"), runtime.admissions[1].supplements.map { it.id.value })
            } finally { release.countDown() }
        }
    }
    @Test fun stopRejectsUncapturedProposalAndDoesNotReopen() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val runtime = Runtime().apply { before = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
        Fixture(runtime).use { f ->
            try {
                assertTrue(f.offer("one")); assertTrue(entered.await(5, TimeUnit.SECONDS)); assertTrue(f.offer("two"))
                f.runner.stop(); release.countDown()
                assertEquals(SupplementAdmission.Accepted(MessageId("one")), f.result())
                assertEquals(SupplementAdmission.Rejected(MessageId("two")), f.result())
                assertEquals(RunStatus.STOPPED, f.task.get(5, TimeUnit.SECONDS).status); assertEquals(1, runtime.admissions.size)
            } finally { release.countDown() }
        }
    }
    @Test fun wrongIdentityDuplicateAndOriginalUserAreRejectedWithoutExtraWrites() {
        Fixture().use { f ->
            val bad = listOf(user("wrong").copy(runId = RunId("other")), user("wrong").copy(conversationId = ConversationId("other")),
                user("wrong").copy(role = Message.ASSISTANT_ROLE), user("wrong").copy(status = RunStatus.RUNNING), user("original"))
            bad.forEach { assertFalse(f.runner.admitSupplement(it, f.results::add)); assertTrue(f.result() is SupplementAdmission.Rejected) }
            assertTrue(f.offer("one")); assertTrue(f.result() is SupplementAdmission.Accepted)
            assertFalse(f.offer("one")); assertTrue(f.result() is SupplementAdmission.Rejected)
            assertEquals(1, f.runtime.admissions.size)
        }
    }
    @Test fun observerExceptionCannotUndoCommittedAdmission() {
        Fixture().use { f ->
            val delivered = CountDownLatch(1)
            assertTrue(f.runner.admitSupplement(user("added")) { delivered.countDown(); throw IllegalStateException("observer") })
            assertTrue(delivered.await(5, TimeUnit.SECONDS)); assertEquals(1, f.runtime.admissions.size)
            assertTrue(f.runner.resume()); assertEquals(RunStatus.COMPLETED, f.task.get(5, TimeUnit.SECONDS).status)
        }
    }

    @Test fun initialEmbeddedUserIdIsRejectedBeforePersistence() {
        val answer = initial.copy(historySegments = listOf(MessageHistorySegment(MessageId("embedded"), Message.USER_ROLE,
            listOf(MessageBlock.Text("user", "embedded")))))
        Fixture(answer = answer).use { f ->
            assertFalse(f.offer("embedded")); assertTrue(f.result() is SupplementAdmission.Rejected)
            assertTrue(f.runtime.admissions.isEmpty())
        }
    }
    @Test fun resumeCannotOvertakeAlreadyQueuedProposals() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val writes = AtomicInteger()
        val runtime = Runtime().apply { before = { if (writes.incrementAndGet() == 1) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) } } }
        Fixture(runtime).use { f ->
            try {
                assertTrue(f.offer("one")); assertTrue(entered.await(5, TimeUnit.SECONDS)); assertTrue(f.offer("two"))
                assertTrue(f.runner.resume()); release.countDown()
                assertEquals(SupplementAdmission.Accepted(MessageId("one")), f.result())
                assertEquals(SupplementAdmission.Accepted(MessageId("two")), f.result())
                assertEquals(RunStatus.COMPLETED, f.task.get(5, TimeUnit.SECONDS).status)
                assertEquals(listOf("original", "one", "two"), f.histories.last().filter { it.role == Message.USER_ROLE }.map { it.id.value })
            } finally { release.countDown() }
        }
    }
}
