package app.daiyu.android.domain

import app.daiyu.android.domain.runtime.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class ChatRunnerTransportResumeTest {
    private class Runtime : AtomicRuntimeStore {
        val writes = java.util.Collections.synchronizedList(mutableListOf<RunCheckpoint>())
        val paused = CountDownLatch(1)
        override fun commitCheckpoint(checkpoint: RunCheckpoint) {
            writes.add(checkpoint)
            if (checkpoint.phase == ExecutionPhase.PAUSED) paused.countDown()
        }
        override fun reconcileCheckpoint(checkpoint: RunCheckpoint) = error("must not recover")
    }
    private class Budget : TransportReconnectBudget {
        var checks = 0
        var deadline = false
        @Volatile var disconnect: (() -> Unit)? = null
        val pauses = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
        override fun check() { checks++; if (deadline) throw TransportReconnectDeadlineException() }
        override fun setPaused(paused: Boolean, revision: Long) { pauses.add(paused); if (paused) disconnect?.invoke() }
        override fun attach(disconnect: () -> Unit): AutoCloseable {
            this.disconnect = disconnect
            return AutoCloseable { this.disconnect = null }
        }
        override fun finish(status: String) {}
    }
    private fun batch(sequence: Long, events: List<ModelEvent> = emptyList()) =
        ModelEventBatch(events,TransportCheckpoint("resp_fixture",sequence,"{}"))
    private abstract inner class Session : CheckpointedResumableModelSession {
        override val canResume = true
        override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) = error("Unit sink is not ACK")
        override fun resume(sink: (ModelEvent) -> Unit) = error("Unit sink is not ACK")
        override fun cancel() {}
        override fun disconnectTransport() {}
        override fun nextAcknowledged(outputs: List<ToolOutput>, sink: (ModelEventBatch) -> ModelDeliveryAck) {
            assertEquals(ModelDeliveryAck.ACCEPTED,sink(batch(0)))
            throw ModelRequestException(ModelFailure.NETWORK)
        }
    }
    private fun initial() = Message(MessageId("a"),ConversationId("c"),RunId("r"),"assistant",emptyList(),RunStatus.RUNNING)
    private fun runner(session: ModelSession, runtime: Runtime, budget: TransportReconnectBudget,
                       opens: () -> Unit = {}): ChatRunner = ChatRunner(ModelPort { opens(); session },
        object : ConversationStore { override fun save(message: Message) = error("atomic only") }, BatteryTool { error("no tools") },
        RunnerOptions(runtimeStore = runtime,reconnect = ReconnectPolicy(budgetFactory = TransportReconnectBudgetFactory { _, _, _ -> budget }))) {}

    @Test fun staleWorkerPauseSnapshotCannotOverrideCompletedUiPause() {
        val workerSnapshot = CountDownLatch(1); val releaseSnapshot = CountDownLatch(1)
        val runtime = Runtime(); val failure = AtomicReference<Throwable>()
        val result = AtomicReference<Message>()
        var opens = 0; var resumes = 0
        val budget = object : TransportReconnectBudget {
            private val lock = Any()
            private var revision = -1L
            var paused = false
                private set
            override fun check() {}
            override fun setPaused(paused: Boolean, revision: Long) {
                if (revision == 0L) {
                    workerSnapshot.countDown()
                    check(releaseSnapshot.await(3, TimeUnit.SECONDS))
                }
                synchronized(lock) {
                    if (revision > this.revision) { this.revision = revision; this.paused = paused }
                }
            }
            fun isPaused() = synchronized(lock) { paused }
            override fun attach(disconnect: () -> Unit) = AutoCloseable {}
            override fun finish(status: String) {}
        }
        val session = object : Session() {
            override fun resumeAcknowledged(sink: (ModelEventBatch) -> ModelDeliveryAck) {
                resumes++
                assertEquals(ModelDeliveryAck.ACCEPTED, sink(batch(1, listOf(ModelEvent.TextFinal("m", "ok")))))
            }
        }
        val runner = runner(session, runtime, budget) { opens++ }
        val worker = thread(isDaemon = true) {
            try { result.set(runner.run(initial(), emptyList())) }
            catch (error: Throwable) { failure.set(error) }
        }
        try {
            assertTrue(workerSnapshot.await(3, TimeUnit.SECONDS))
            assertTrue(runner.pause())
            assertTrue(budget.isPaused())
            releaseSnapshot.countDown()
            assertTrue(runtime.paused.await(3, TimeUnit.SECONDS))
            assertTrue(budget.isPaused())
            assertEquals(0, resumes)
            assertTrue(runner.resume())
            worker.join(3_000)
            assertFalse(worker.isAlive)
            failure.get()?.let { throw AssertionError(it) }
            assertEquals(RunStatus.COMPLETED, result.get().status)
            assertEquals(1, opens); assertEquals(1, resumes)
        } finally {
            releaseSnapshot.countDown()
            runner.stop(); worker.join(3_000)
        }
    }

    @Test fun continuousUsesOneFirstFailureBudgetBeyondTwentyAndOneUsageRequest() {
        var resumes = 0
        var opens = 0
        val session = object : Session() {
            override fun resumeAcknowledged(sink: (ModelEventBatch) -> ModelDeliveryAck) {
                resumes++
                if (resumes <= 25) throw ModelRequestException(ModelFailure.NETWORK)
                assertEquals(ModelDeliveryAck.ACCEPTED,sink(batch(26,listOf(ModelEvent.TextFinal("m","ok"),
                    ModelEvent.Usage(7,2),ModelEvent.ProtocolOutput(listOf("confirmed"))))))
            }
        }
        val runtime = Runtime()
        val result = runner(session,runtime,Budget()) { opens++ }.run(initial(),emptyList())
        assertEquals(RunStatus.COMPLETED,result.status)
        assertEquals(26,resumes); assertEquals(1,opens)
        val last = runtime.writes.last()
        assertEquals(1,last.usageRequests.size)
        assertEquals(last.request!!.requestId,last.usage.single().request.requestId)
        assertEquals(26L,last.transport!!.sequence)
        assertEquals(27,runtime.writes.filter { it.request?.operation != RequestOperation.OPEN }
            .mapNotNull { it.request?.attemptId }.distinct().size)
    }

    @Test(timeout = 5000) fun reconnectDeadlineHasFixedTerminalMessageWithoutAnotherResume() {
        for (lateSuccess in listOf(false, true)) {
            var resumes = 0
            var opens = 0
            val failure = TransportReconnectDeadlineException(lateSuccess)
            val session = object : Session() {
                override fun resumeAcknowledged(sink: (ModelEventBatch) -> ModelDeliveryAck) {
                    resumes++
                    throw failure
                }
            }
            val runtime = Runtime()
            val result = runner(session, runtime, Budget()) { opens++ }.run(initial(), emptyList())
            assertEquals(RunStatus.FAILED, result.status)
            assertEquals(failure.userMessage, result.error)
            assertEquals(1, opens)
            assertEquals(1, resumes)
        }
    }

    @Test fun inFlightDeadlineRejectsLateTerminalBatchWithoutAck() {
        val budget = Budget()
        var terminalAck: ModelDeliveryAck? = null
        val session = object : Session() {
            override fun resumeAcknowledged(sink: (ModelEventBatch) -> ModelDeliveryAck) {
                budget.deadline = true // Includes in-flight time, not merely the retry wait.
                terminalAck = sink(batch(1,listOf(ModelEvent.Usage(8,3),ModelEvent.ProtocolOutput(listOf("late")))))
            }
        }
        val runtime = Runtime()
        val result = runner(session,runtime,budget).run(initial(),emptyList())
        assertEquals(RunStatus.FAILED, result.status)
        assertEquals(TransportReconnectDeadlineException().userMessage, result.error)
        assertNull(terminalAck)
        assertTrue(runtime.writes.none { it.transport?.sequence == 1L })
        assertTrue(runtime.writes.last().usage.isEmpty())
    }

    @Test(timeout = 10000) fun pauseDisconnectsOnlyAttemptThenSameSessionRejectsOldCallback() {
        val entered = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val runtime = Runtime()
        val budget = Budget()
        var resumes = 0
        var opens = 0
        var oldSink: ((ModelEventBatch) -> ModelDeliveryAck)? = null
        var staleAck: ModelDeliveryAck? = null
        val session = object : Session() {
            override fun disconnectTransport() { disconnected.countDown() }
            override fun resumeAcknowledged(sink: (ModelEventBatch) -> ModelDeliveryAck) {
                resumes++
                if (resumes == 1) {
                    oldSink = sink; entered.countDown()
                    check(disconnected.await(5,TimeUnit.SECONDS))
                    throw ModelRequestException(ModelFailure.NETWORK)
                }
                staleAck = oldSink!!(batch(99,listOf(ModelEvent.TextFinal("m","stale"))))
                assertEquals(ModelDeliveryAck.ACCEPTED,sink(batch(1,listOf(ModelEvent.TextFinal("m","ok")))))
            }
        }
        val runner = runner(session,runtime,budget) { opens++ }
        val result = AtomicReference<Message>()
        val worker = thread { result.set(runner.run(initial(),emptyList())) }
        assertTrue(entered.await(5,TimeUnit.SECONDS))
        assertTrue(runner.pause())
        assertTrue(runtime.paused.await(5,TimeUnit.SECONDS))
        assertTrue(runner.resume())
        worker.join(5000); assertFalse(worker.isAlive)
        assertEquals(RunStatus.COMPLETED,result.get().status)
        assertEquals(ModelDeliveryAck.REJECTED,staleAck)
        assertEquals(1,opens); assertEquals(2,resumes)
        assertTrue(budget.pauses.contains(true)); assertTrue(budget.pauses.contains(false))
        assertTrue(runtime.writes.none { it.transport?.sequence == 99L })
    }

    @Test(timeout = 10000) fun finalStopRejectsLateCallbackAndNeverContinues() {
        val entered = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val runtime = Runtime()
        var stale: ModelDeliveryAck? = null
        var resumes = 0
        val session = object : Session() {
            override fun cancel() { disconnected.countDown() }
            override fun resumeAcknowledged(sink: (ModelEventBatch) -> ModelDeliveryAck) {
                resumes++; entered.countDown(); check(disconnected.await(5,TimeUnit.SECONDS))
                stale = sink(batch(9,listOf(ModelEvent.TextFinal("m","late"))))
                throw ModelRequestException(ModelFailure.NETWORK)
            }
        }
        val runner = runner(session,runtime,Budget())
        val result = AtomicReference<Message>()
        val worker = thread { result.set(runner.run(initial(),emptyList())) }
        assertTrue(entered.await(5,TimeUnit.SECONDS)); runner.stop()
        worker.join(5000); assertFalse(worker.isAlive)
        assertEquals(RunStatus.STOPPED,result.get().status)
        assertEquals(1,resumes); assertEquals(ModelDeliveryAck.REJECTED,stale)
        assertFalse(runner.resume())
    }
}
