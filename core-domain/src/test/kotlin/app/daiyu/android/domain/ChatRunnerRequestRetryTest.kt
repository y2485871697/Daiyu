package app.daiyu.android.domain

import app.daiyu.android.domain.runtime.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class ChatRunnerRequestRetryTest {
    private class Runtime : AtomicRuntimeStore {
        val writes = mutableListOf<RunCheckpoint>()
        var onCommit: (RunCheckpoint) -> Unit = {}
        override fun commitCheckpoint(checkpoint: RunCheckpoint) { writes.add(checkpoint); onCommit(checkpoint) }
        override fun reconcileCheckpoint(checkpoint: RunCheckpoint) = error("no recovery")
    }
    private open class Budget : TransportReconnectBudget {
        override fun check() {}
        override fun setPaused(paused: Boolean, revision: Long) {}
        override fun attach(disconnect: () -> Unit) = AutoCloseable {}
        override fun finish(status: String) {}
    }
    private abstract class Session : RequestRetryModelSession {
        override val canResume = true
        override fun cancel() {}
        override fun disconnectTransport() {}
    }
    private fun initial() = Message(MessageId("a"), ConversationId("c"), RunId("r"), "assistant", emptyList(), RunStatus.RUNNING)
    private fun port(session: ModelSession) = object : ModelPort {
        override fun open(history: List<Message>) = session
        override fun open(history: List<Message>, tools: Set<ReadOnlyTool>) = session
        override fun open(history: List<Message>, definitions: List<ToolDefinition>) = session
    }
    private fun runner(session: ModelSession, runtime: Runtime, tools: BatteryTool = BatteryTool { error("no tools") },
        delayMillis: Long = 0, budget: () -> TransportReconnectBudget = { Budget() }) = ChatRunner(port(session),
        object : ConversationStore { override fun save(message: Message) = error("atomic only") }, tools,
        RunnerOptions(runtimeStore = runtime, reconnect = ReconnectPolicy(delayMillis = delayMillis, budgetFactory = TransportReconnectBudgetFactory { _, _, _ -> budget() }))) {}

    @Test fun currentRequestRetriesBeyondTwentyWithoutAppendingFailedAttempts() {
        var retries = 0; var budgets = 0
        val runtime = Runtime()
        val session = object : Session() {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                sink(ModelEvent.TextDelta("same", "discard")); throw ModelRequestException(ModelFailure.NETWORK)
            }
            override fun resume(sink: (ModelEvent) -> Unit) {
                retries++
                if (retries <= 25) { sink(ModelEvent.TextDelta("same", "discard")); throw ModelRequestException(ModelFailure.NETWORK) }
                sink(ModelEvent.TextFinal("new-id", "fresh")); sink(ModelEvent.Usage(7, 2))
            }
        }
        val result = runner(session, runtime, budget = { budgets++; Budget() }).run(initial(), emptyList())
        assertEquals(RunStatus.COMPLETED, result.status)
        assertEquals(listOf("fresh"), result.blocks.filterIsInstance<MessageBlock.Text>().map { it.text })
        assertEquals(26, retries); assertEquals(1, budgets)
        assertEquals(27, runtime.writes.last().usageRequests.map { it.requestId }.distinct().size)
    }

    @Test fun retryAfterToolKeepsItsReceiptAndExactOutputsWithoutReexecuting() {
        var nexts = 0; var retries = 0; var tools = 0
        val runtime = Runtime()
        var retained = emptyList<ToolOutput>()
        val session = object : Session() {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                nexts++
                if (nexts == 1) {
                    sink(ModelEvent.ToolReady(ToolInvocation(ToolCallId("done"), "get_battery_status", "{}")))
                } else {
                    retained = outputs.toList()
                    sink(ModelEvent.TextDelta("m", "old partial"))
                    sink(ModelEvent.ToolReady(ToolInvocation(ToolCallId("unconfirmed"), "get_battery_status", "{}")))
                    throw ModelRequestException(ModelFailure.NETWORK)
                }
            }
            override fun resume(sink: (ModelEvent) -> Unit) {
                retries++; assertEquals(listOf(ToolOutput(ToolCallId("done"), "saved-result")), retained)
                sink(ModelEvent.TextFinal("different", "new answer"))
            }
        }
        val result = runner(session, runtime, BatteryTool { tools++; "saved-result" }).run(initial(), emptyList())
        assertEquals(RunStatus.COMPLETED, result.status)
        assertEquals(1, tools); assertEquals(2, nexts); assertEquals(1, retries)
        assertEquals(listOf("new answer"), result.blocks.filterIsInstance<MessageBlock.Text>().map { it.text })
        assertTrue(result.blocks.none { it is MessageBlock.Tool && it.invocation.id.value == "unconfirmed" })
        assertEquals(3, runtime.writes.last().usageRequests.size)
    }

    @Test(timeout = 10000) fun pauseAndFastResumeBeforeFirstNextNeverRequireARecoveryCursor() {
        for (fast in listOf(false, true)) {
            val runtime = Runtime(); val paused = CountDownLatch(1)
            var triggered = false; var nexts = 0; var budgets = 0
            lateinit var runner: ChatRunner
            val session = object : Session() {
                override val canResume = false
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) { nexts++; sink(ModelEvent.TextFinal("m", "ok")) }
                override fun resume(sink: (ModelEvent) -> Unit) = error("no request has been sent")
            }
            runtime.onCommit = { checkpoint ->
                if (!triggered && checkpoint.phase == ExecutionPhase.STREAMING && checkpoint.request?.operation == RequestOperation.NEXT) {
                    triggered = true; assertTrue(runner.pause()); if (fast) assertTrue(runner.resume())
                }
                if (checkpoint.phase == ExecutionPhase.PAUSED) paused.countDown()
            }
            runner = runner(session, runtime, delayMillis = 60_000, budget = { budgets++; Budget() })
            val result = AtomicReference<Message>(); val failure = AtomicReference<Throwable>()
            val worker = thread { try { result.set(runner.run(initial(), emptyList())) } catch (error: Throwable) { failure.set(error) } }
            try {
                if (!fast) { assertTrue(paused.await(3, TimeUnit.SECONDS)); assertEquals(0, nexts); assertTrue(runner.resume()) }
                worker.join(3_000); assertFalse(worker.isAlive); failure.get()?.let { throw AssertionError(it) }
                assertEquals(RunStatus.COMPLETED, result.get().status); assertEquals(1, nexts); assertEquals(0, budgets)
            } finally { runner.stop(); worker.join(3_000) }
        }
    }

    @Test fun pauseAndFastResumeDuringOrdinaryStreamRejectsLateDeliveryAndReplays() {
        val runtime = Runtime()
        lateinit var runner: ChatRunner
        var resumes = 0
        var budgets = 0
        val session = object : Session() {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                sink(ModelEvent.TextDelta("m", "discard"))
                assertTrue(runner.pause())
                assertTrue(runner.resume())
                sink(ModelEvent.TextFinal("m", "late"))
                fail("paused transport must reject late delivery")
            }
            override fun resume(sink: (ModelEvent) -> Unit) {
                resumes++
                sink(ModelEvent.TextFinal("replacement", "fresh"))
            }
        }
        runner = runner(session, runtime, delayMillis = 60_000, budget = { budgets++; Budget() })
        val result = runner.run(initial(), emptyList())
        assertEquals(RunStatus.COMPLETED, result.status)
        assertEquals(1, resumes)
        assertEquals(0, budgets)
        assertTrue(runtime.writes.none { it.phase == ExecutionPhase.RECONNECTING })
        assertEquals(listOf("fresh"), result.blocks.filterIsInstance<MessageBlock.Text>().map { it.text })
    }

    @Test(timeout = 15000) fun ordinaryPauseCancellationWaitsWithoutStartingReconnectOrBackoff() {
        for (kind in listOf("network", "timeout", "interrupted")) {
            val runtime = Runtime()
            val paused = CountDownLatch(1)
            lateinit var runner: ChatRunner
            var resumes = 0; var budgets = 0
            runtime.onCommit = { if (it.phase == ExecutionPhase.PAUSED) paused.countDown() }
            val session = object : Session() {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    sink(ModelEvent.TextDelta("m", "partial"))
                    assertTrue(runner.pause())
                    throw ModelRequestException(if (kind == "timeout") ModelFailure.TIMEOUT else ModelFailure.NETWORK,
                        transportInterrupted = kind == "interrupted")
                }
                override fun resume(sink: (ModelEvent) -> Unit) {
                    resumes++; sink(ModelEvent.TextFinal("replacement", "fresh"))
                }
            }
            runner = runner(session, runtime, delayMillis = 60_000, budget = { budgets++; Budget() })
            val result = AtomicReference<Message>(); val failure = AtomicReference<Throwable>()
            val worker = thread { try { result.set(runner.run(initial(), emptyList())) } catch (e: Throwable) { failure.set(e) } }
            try {
                assertTrue(kind, paused.await(3, TimeUnit.SECONDS))
                assertEquals(kind, 0, resumes)
                assertTrue(runner.resume())
                worker.join(3000); assertFalse(kind, worker.isAlive)
                failure.get()?.let { throw AssertionError(it) }
                assertEquals(kind, RunStatus.COMPLETED, result.get().status)
                assertEquals(kind, 1, resumes); assertEquals(kind, 0, budgets)
                assertTrue(kind, runtime.writes.none { it.phase == ExecutionPhase.RECONNECTING })
                assertEquals(kind, listOf("fresh"), result.get().blocks.filterIsInstance<MessageBlock.Text>().map { it.text })
                assertEquals(kind, 2, runtime.writes.last().usageRequests.size)
            } finally { runner.stop(); worker.join(3000) }
        }
    }

    @Test fun stopAtReconnectCheckpointNeverStartsRetryOrAcceptsLateEvents() {
        val runtime = Runtime()
        lateinit var runner: ChatRunner
        var resumes = 0
        var staleSink: ((ModelEvent) -> Unit)? = null
        val session = object : Session() {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                staleSink = sink
                sink(ModelEvent.TextDelta("m", "before stop"))
                throw ModelRequestException(ModelFailure.NETWORK)
            }
            override fun resume(sink: (ModelEvent) -> Unit) { resumes++; error("stopped run must not retry") }
        }
        runtime.onCommit = { if (it.phase == ExecutionPhase.RECONNECTING) runner.stop() }
        runner = runner(session, runtime)
        val result = runner.run(initial(), emptyList())
        assertEquals(RunStatus.STOPPED, result.status)
        assertEquals(0, resumes)
        val writes = runtime.writes.size
        checkNotNull(staleSink)(ModelEvent.TextFinal("m", "late"))
        assertEquals(writes, runtime.writes.size)
        assertEquals(RunStatus.STOPPED, runtime.writes.last().message.status)
    }
    @Test fun replayRollbackCommitIsFinalControlAdmissionBoundary() {
        for (control in listOf("stop", "pause", "deadline")) {
            val runtime = Runtime()
            lateinit var runner: ChatRunner
            var triggered = false
            var expired = false
            var resumes = 0
            val session = object : Session() {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    sink(ModelEvent.TextDelta("m", "partial"))
                    throw ModelRequestException(ModelFailure.NETWORK)
                }
                override fun resume(sink: (ModelEvent) -> Unit) { resumes++; sink(ModelEvent.TextFinal("m", "fresh")) }
            }
            runtime.onCommit = { checkpoint ->
                if (!triggered && checkpoint.phase == ExecutionPhase.STREAMING && checkpoint.usageRequests.size == 2 && checkpoint.message.blocks.isEmpty()) {
                    triggered = true
                    when (control) {
                        "stop" -> runner.stop()
                        "pause" -> { assertTrue(runner.pause()); assertTrue(runner.resume()) }
                        else -> expired = true
                    }
                }
            }
            runner = runner(session, runtime, budget = { object : Budget() {
                override fun check() { if (expired) throw TransportReconnectDeadlineException() }
            } })
            val result = runner.run(initial(), emptyList())
            assertTrue(control, triggered)
            assertEquals(control, if (control == "pause") 1 else 0, resumes)
            assertEquals(control, when (control) {
                "stop" -> RunStatus.STOPPED
                "pause" -> RunStatus.COMPLETED
                else -> RunStatus.FAILED
            }, result.status)
        }
    }

}
