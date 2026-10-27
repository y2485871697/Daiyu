package app.daiyu.android.domain

import app.daiyu.android.domain.runtime.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Deterministic boundaries: no sleeps and no real provider transport. */
class ChatRunnerBatchTransactionTest {
    private fun initial() = Message(MessageId("a"), ConversationId("c"), RunId("r"), "assistant", emptyList(), RunStatus.RUNNING)
    private fun batch(sequence: Long, vararg events: ModelEvent) =
        ModelEventBatch(events.toList(), TransportCheckpoint("resp_fixture", sequence, "{}"))
    private open class Runtime : AtomicRuntimeStore {
        val writes = CopyOnWriteArrayList<RunCheckpoint>()
        val paused = CountDownLatch(1)
        override fun commitCheckpoint(checkpoint: RunCheckpoint) {
            writes.add(checkpoint)
            if (checkpoint.phase == ExecutionPhase.PAUSED) paused.countDown()
        }
        override fun reconcileCheckpoint(checkpoint: RunCheckpoint) = error("no recovery")
    }
    private open class Budget : TransportReconnectBudget {
        override fun check() {}
        override fun setPaused(paused: Boolean, revision: Long) {}
        override fun attach(disconnect: () -> Unit) = AutoCloseable {}
        override fun finish(status: String) {}
    }
    private abstract class Session : CheckpointedResumableModelSession {
        override val canResume = true
        val cancels = AtomicInteger()
        override fun cancel() { cancels.incrementAndGet() }
        override fun disconnectTransport() {}
        override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) = error("ACK required")
        override fun resume(sink: (ModelEvent) -> Unit) = error("ACK required")
    }
    private fun runner(port: ModelPort, runtime: Runtime, budget: Budget = Budget(),
                       onUsage: (ModelEvent.Usage) -> Unit = {}, onReasoning: (ModelEvent) -> Unit = {},
                       tool: BatteryTool = BatteryTool { error("uncommitted tool must not execute") }): ChatRunner =
        ChatRunner(port, object : ConversationStore {
            override fun save(message: Message) = error("atomic store only")
        }, tool, RunnerOptions(runtimeStore = runtime,
            reconnect = ReconnectPolicy(budgetFactory = TransportReconnectBudgetFactory { _, _, _ -> budget }),
            onUsage = onUsage, onReasoning = onReasoning)) {}

    private fun dirtyEvents() = listOf<ModelEvent>(
        ModelEvent.TextDelta("t", "dirty"), ModelEvent.ReasoningDelta("reason", "dirty reasoning"),
        ModelEvent.ProtocolOutput(listOf("dirty protocol")),
        ModelEvent.ToolReady(ToolInvocation(ToolCallId("dirty-call"), "get_battery_status", "{}")),
        ModelEvent.Usage(99, 99))

    private fun assertClean(checkpoint: RunCheckpoint) {
        assertFalse(checkpoint.message.blocks.any { it is MessageBlock.Tool || it is MessageBlock.Text && it.text.contains("dirty") })
        assertFalse(checkpoint.message.protocolItems.any { it.contains("dirty") })
        assertFalse(checkpoint.message.historySegments.any { segment ->
            segment.protocolItems.any { it.contains("dirty") } || segment.reportedReasoning.any { it.text.contains("dirty") }
        })
        assertTrue(checkpoint.tools.isEmpty())
        assertFalse(checkpoint.usage.any { it.snapshot.inputTokens == 99L })
        assertNotEquals(1L, checkpoint.transport?.sequence)
    }

    @Test(timeout = 10000) fun pauseBetweenStageAndCommitRestoresAllAcceptedState() {
        val runtime = Runtime()
        val usages = CopyOnWriteArrayList<ModelEvent.Usage>()
        val reasoning = CopyOnWriteArrayList<ModelEvent>()
        lateinit var runner: ChatRunner
        val session = object : Session() {
            override fun nextAcknowledged(outputs: List<ToolOutput>, sink: (ModelEventBatch) -> ModelDeliveryAck) {
                assertEquals(ModelDeliveryAck.ACCEPTED, sink(batch(0, ModelEvent.TextDelta("t", "prefix"))))
                val events = dirtyEvents()
                val pausingEvents = object : AbstractList<ModelEvent>() {
                    override val size = events.size
                    override fun get(index: Int): ModelEvent {
                        // All earlier surfaces have already staged; the last event also stages.
                        if (index == events.lastIndex) assertTrue(runner.pause())
                        return events[index]
                    }
                }
                sink(ModelEventBatch(pausingEvents, batch(1).checkpoint))
                fail("uncommitted batch must reject")
            }
            override fun resumeAcknowledged(sink: (ModelEventBatch) -> ModelDeliveryAck) {
                assertEquals(0, cancels.get()) // Pause is transport-only, not final session cancellation.
                assertEquals(ModelDeliveryAck.ACCEPTED, sink(batch(2, ModelEvent.TextDelta("t", "suffix"))))
            }
        }
        runner = runner(ModelPort { session }, runtime, onUsage = usages::add, onReasoning = reasoning::add)
        val result = FutureTask { runner.run(initial(), emptyList()) }
        val owner = Thread(result).apply { isDaemon = true; start() }
        try {
            assertTrue(runtime.paused.await(5, TimeUnit.SECONDS))
            runtime.writes.forEach(::assertClean)
            assertTrue(runner.resume())
            val message = result.get(5, TimeUnit.SECONDS)
            assertEquals(RunStatus.COMPLETED, message.status)
            assertEquals(listOf(MessageBlock.Text("t", "prefixsuffix")), message.blocks)
            assertTrue(usages.isEmpty()); assertTrue(reasoning.isEmpty())
            runtime.writes.forEach(::assertClean)
        } finally { runner.stop(); owner.join(5000); assertFalse(owner.isAlive) }
    }

    @Test fun failedBatchCommitCannotLeakDirtyStateIntoLaterCheckpoint() = failedCommit(false)
    @Test fun lostCommitReceiptSealsRunWithoutRetryOrRollbackWrite() = failedCommit(true)
    @Test fun failedBatchCommitRacingStopCannotWriteTerminalCheckpoint() = failedCommit(false, true)
    @Test fun lostBatchCommitReceiptRacingStopCannotWriteTerminalCheckpoint() = failedCommit(true, true)
    private fun failedCommit(writeBeforeThrow: Boolean, stopDuringFailure: Boolean = false) {
        lateinit var ownerRunner: ChatRunner

        val runtime = object : Runtime() {
            var failed = false
            var attemptsAfterFailure = 0
            override fun commitCheckpoint(checkpoint: RunCheckpoint) {
                if (failed) { attemptsAfterFailure++; error("must not write after uncertain commit") }
                if (checkpoint.transport?.sequence == 1L) {
                    if (writeBeforeThrow) super.commitCheckpoint(checkpoint)
                    failed = true
                    if (stopDuringFailure) ownerRunner.stop()
                    throw IllegalStateException("uncertain write")
                }
                super.commitCheckpoint(checkpoint)
            }
        }
        var resumes = 0
        val session = object : Session() {
            override fun nextAcknowledged(outputs: List<ToolOutput>, sink: (ModelEventBatch) -> ModelDeliveryAck) {
                assertEquals(ModelDeliveryAck.ACCEPTED, sink(batch(0, ModelEvent.TextDelta("t", "prefix"))))
                sink(ModelEventBatch(dirtyEvents(), batch(1).checkpoint))
            }
            override fun resumeAcknowledged(sink: (ModelEventBatch) -> ModelDeliveryAck) { resumes++; error("no retry") }
        }
        ownerRunner = runner(ModelPort { session }, runtime)
        val message = ownerRunner.run(initial(), emptyList())
        assertTrue(runtime.failed); assertEquals(0, resumes); assertEquals(0, runtime.attemptsAfterFailure)
        assertEquals(if (stopDuringFailure) RunStatus.STOPPED else RunStatus.FAILED, message.status)
        assertEquals(listOf(MessageBlock.Text("t", "prefix")), message.blocks)
        runtime.writes.filter { it.transport?.sequence != 1L }.forEach(::assertClean)
        assertEquals(if (writeBeforeThrow) 1 else 0, runtime.writes.count { it.transport?.sequence == 1L })
    }

    @Test(timeout = 10000) fun successfulCommitRacingPauseStillAcknowledges() = commitRace("pause")
    @Test(timeout = 10000) fun successfulCommitRacingPauseResumeStillAcknowledgesAndFencesOldGate() = commitRace("resume")
    @Test(timeout = 10000) fun successfulCommitRacingStopStillAcknowledges() = commitRace("stop")
    private fun commitRace(mode: String) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val runtime = object : Runtime() {
            override fun commitCheckpoint(checkpoint: RunCheckpoint) {
                if (checkpoint.transport?.sequence == 1L && checkpoint.phase != ExecutionPhase.TERMINAL && writes.none { it.transport?.sequence == 1L }) {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                }
                super.commitCheckpoint(checkpoint)
            }
        }
        var ack: ModelDeliveryAck? = null
        var stale: ModelDeliveryAck? = null
        var resumes = 0
        val session = object : Session() {
            override fun nextAcknowledged(outputs: List<ToolOutput>, sink: (ModelEventBatch) -> ModelDeliveryAck) {
                ack = sink(batch(1, ModelEvent.TextDelta("t", "committed")))
                // Even a fast pause/resume must not reopen this delivery gate.
                stale = sink(batch(99, ModelEvent.TextDelta("t", "dirty")))
                throw ModelRequestException(ModelFailure.NETWORK)
            }
            override fun resumeAcknowledged(sink: (ModelEventBatch) -> ModelDeliveryAck) {
                resumes++
                assertEquals(0, cancels.get())
                assertEquals(ModelDeliveryAck.ACCEPTED, sink(batch(2, ModelEvent.TextDelta("t", "suffix"))))
            }
        }
        val runner = runner(ModelPort { session }, runtime)
        val result = FutureTask { runner.run(initial(), emptyList()) }
        val owner = Thread(result).apply { isDaemon = true; start() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            if (mode == "stop") runner.stop() else {
                assertTrue(runner.pause())
                if (mode == "resume") assertTrue(runner.resume())
            }
            release.countDown()
            if (mode == "pause") {
                assertTrue(runtime.paused.await(5, TimeUnit.SECONDS)); assertTrue(runner.resume())
            }
            val message = result.get(5, TimeUnit.SECONDS)
            assertEquals(ModelDeliveryAck.ACCEPTED, ack)
            assertEquals(ModelDeliveryAck.REJECTED, stale)
            assertEquals(if (mode == "stop") RunStatus.STOPPED else RunStatus.COMPLETED, message.status)
            assertEquals(if (mode == "stop") 0 else 1, resumes)
            assertTrue(runtime.writes.none { it.transport?.sequence == 99L })
            assertEquals(listOf(MessageBlock.Text("t", if (mode == "stop") "committed" else "committedsuffix")), message.blocks)
        } finally { release.countDown(); runner.stop(); owner.join(5000); assertFalse(owner.isAlive) }
    }

    @Test(timeout = 10000) fun pauseResumeDuringAttachRejectsAttemptBeforeTransportAdmission() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val budget = object : Budget() {
            var attaches = 0
            override fun attach(disconnect: () -> Unit): AutoCloseable {
                if (++attaches == 1) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                return AutoCloseable {}
            }
        }
        var nexts = 0; var resumes = 0; var opens = 0
        val session = object : Session() {
            override fun nextAcknowledged(outputs: List<ToolOutput>, sink: (ModelEventBatch) -> ModelDeliveryAck) {
                nexts++; assertEquals(ModelDeliveryAck.ACCEPTED, sink(batch(0)))
                throw ModelRequestException(ModelFailure.NETWORK)
            }
            override fun resumeAcknowledged(sink: (ModelEventBatch) -> ModelDeliveryAck) {
                resumes++; assertEquals(0, cancels.get())
                assertEquals(ModelDeliveryAck.ACCEPTED, sink(batch(2, ModelEvent.TextFinal("t", "ok"))))
            }
        }
        val runner = runner(ModelPort { opens++; session }, Runtime(), budget)
        val result = FutureTask { runner.run(initial(), emptyList()) }
        val owner = Thread(result).apply { isDaemon = true; start() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(runner.pause()); assertTrue(runner.resume()); release.countDown()
            assertEquals(RunStatus.COMPLETED, result.get(5, TimeUnit.SECONDS).status)
            assertEquals(1, opens); assertEquals(1, nexts); assertEquals(1, resumes)
            assertEquals(2, budget.attaches) // rejected attach, then one admitted retry
        } finally { release.countDown(); runner.stop(); owner.join(5000); assertFalse(owner.isAlive) }
    }

    @Test fun supplementStartsNewOpenWithoutPreviousTransportCursor() {
        val runtime = object : Runtime() {
            override fun commitCheckpoint(checkpoint: RunCheckpoint) {
                if (checkpoint.request?.operation == RequestOperation.OPEN) assertNull(checkpoint.transport)
                super.commitCheckpoint(checkpoint)
            }
        }
        lateinit var runner: ChatRunner
        var opens = 0
        val port = ModelPort {
            val first = ++opens == 1
            object : Session() {
                override fun nextAcknowledged(outputs: List<ToolOutput>, sink: (ModelEventBatch) -> ModelDeliveryAck) {
                    assertEquals(ModelDeliveryAck.ACCEPTED, sink(batch(if (first) 4 else 0,
                        ModelEvent.TextFinal("t", if (first) "first" else "second"))))
                    if (first) assertTrue(runner.supplement(Message(MessageId("u2"), ConversationId("c"), RunId("r"),
                        "user", listOf(MessageBlock.Text("u", "more")), RunStatus.COMPLETED)))
                }
                override fun resumeAcknowledged(sink: (ModelEventBatch) -> ModelDeliveryAck) = error("no resume POST")
            }
        }
        runner = runner(port, runtime)
        assertEquals(RunStatus.COMPLETED, runner.run(initial(), emptyList()).status)
        assertEquals(2, opens)
        assertEquals(2, runtime.writes.filter { it.request?.operation == RequestOperation.OPEN }.map { it.request!!.requestId }.distinct().size)
    }
}
