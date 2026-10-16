package app.daiyu.android.domain

import app.daiyu.android.domain.runtime.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class ChatRunnerReasoningObserverTest {
    private fun initial() = Message(MessageId("a"), ConversationId("c"), RunId("r"),
        Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)

    private fun store(writes: MutableList<Message>) = object : ConversationStore {
        override fun save(message: Message) { writes.add(message) }
    }

    private fun session(events: ((ModelEvent) -> Unit) -> Unit) = object : ModelSession {
        override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) = events(sink)
        override fun cancel() {}
    }

    private fun lateReasoning(sink: (ModelEvent) -> Unit) {
        // Rejection must happen before either repetition detection or observation.
        sink(ModelEvent.ReasoningDelta("late", "OK.\n".repeat(2000)))
        sink(ModelEvent.ReasoningFinal("late", "OK.\n".repeat(2000)))
    }

    @Test fun forwardsOnlyAcceptedReasoningEventsInOrderWithoutProjectingThem() {
        val delta = ModelEvent.ReasoningDelta("reason", "Consider the inputs.")
        val final = ModelEvent.ReasoningFinal("reason", "The inputs are sufficient.")
        val observed = mutableListOf<ModelEvent>()
        val writes = mutableListOf<Message>()
        val runner = ChatRunner(ModelPort { session { sink ->
            sink(ModelEvent.TextDelta("body", "Thinking is just visible text here."))
            sink(delta)
            sink(ModelEvent.Usage(1, 2))
            sink(ModelEvent.ProtocolOutput(listOf("protocol")))
            sink(final)
            sink(ModelEvent.TextFinal("body", "answer"))
        } }, store(writes), BatteryTool { error("unused") }, RunnerOptions(onReasoning = observed::add)) {}

        val result = runner.run(initial(), emptyList())
        assertEquals(listOf(delta, final), observed)
        assertSame(delta, observed[0])
        assertSame(final, observed[1])
        assertEquals(RunStatus.COMPLETED, result.status)
        assertEquals(listOf(MessageBlock.Text("body", "answer")), result.blocks)
        assertEquals(listOf("protocol"), result.protocolItems)
        assertEquals(listOf(result), writes.filter { it.status != RunStatus.RUNNING })
        assertTrue(writes.flatMap { it.blocks }.all { it is MessageBlock.Text })
    }

    @Test fun closedAttemptAndTerminalSinksCannotNotify() {
        val first = ModelEvent.ReasoningDelta("reason", "First attempt.")
        val resumed = ModelEvent.ReasoningFinal("reason", "Resumed attempt.")
        val observed = mutableListOf<ModelEvent>()
        val writes = mutableListOf<Message>()
        lateinit var oldSink: (ModelEvent) -> Unit
        lateinit var resumedSink: (ModelEvent) -> Unit
        var nexts = 0
        var resumes = 0
        val port = ModelPort { object : ResumableModelSession {
            override val canResume = true
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                nexts++
                oldSink = sink
                sink(first)
                throw ModelRequestException(ModelFailure.NETWORK)
            }
            override fun resume(sink: (ModelEvent) -> Unit) {
                resumes++
                resumedSink = sink
                lateReasoning(oldSink)
                sink(resumed)
                sink(ModelEvent.TextFinal("body", "answer"))
            }
            override fun cancel() {}
        } }
        val runner = ChatRunner(port, store(writes), BatteryTool { error("unused") },
            RunnerOptions(reconnect = ReconnectPolicy(1), onReasoning = observed::add)) {}

        val result = runner.run(initial(), emptyList())
        assertEquals(RunStatus.COMPLETED, result.status)
        assertEquals(1, nexts)
        assertEquals(1, resumes)
        assertEquals(listOf(first, resumed), observed)
        val saved = writes.toList()
        lateReasoning(oldSink)
        lateReasoning(resumedSink)
        assertEquals(listOf(first, resumed), observed)
        assertEquals(saved, writes)
    }

    @Test fun pauseInvalidatesGenerationEvenAfterResume() {
        val first = ModelEvent.ReasoningDelta("reason", "Before pause.")
        val fresh = ModelEvent.ReasoningFinal("reason", "New generation.")
        val observed = mutableListOf<ModelEvent>()
        val writes = mutableListOf<Message>()
        lateinit var oldSink: (ModelEvent) -> Unit
        lateinit var runner: ChatRunner
        var opens = 0
        val port = ModelPort {
            opens++
            if (opens == 1) session { sink ->
                oldSink = sink
                sink(first)
                assertTrue(runner.pause())
                lateReasoning(sink)
                assertEquals(listOf(first), observed)
                assertTrue(runner.resume())
                lateReasoning(sink)
            } else session { sink ->
                lateReasoning(oldSink)
                sink(fresh)
                sink(ModelEvent.TextFinal("body", "answer"))
            }
        }
        runner = ChatRunner(port, store(writes), BatteryTool { error("unused") },
            RunnerOptions(onReasoning = observed::add)) {}

        val result = runner.run(initial(), emptyList())
        assertEquals(RunStatus.COMPLETED, result.status)
        assertEquals(2, opens)
        assertEquals(listOf(first, fresh), observed)
        assertEquals(listOf(MessageBlock.Text("body", "answer")), result.blocks)
    }

    @Test(timeout = 15000) fun stopDoesNotWaitForObserverAndRejectsSubsequentReasoning() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = ModelEvent.ReasoningDelta("reason", "Accepted before stop.")
        val observed = CopyOnWriteArrayList<ModelEvent>()
        val writes = CopyOnWriteArrayList<Message>()
        lateinit var retained: (ModelEvent) -> Unit
        val runner = ChatRunner(ModelPort { session { sink ->
            retained = sink
            sink(first)
            lateReasoning(sink)
        } }, store(writes), BatteryTool { error("unused") }, RunnerOptions(onReasoning = {
            observed.add(it)
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        })) {}
        val task = FutureTask { runner.run(initial(), emptyList()) }
        val owner = Thread(task, "reasoning-owner").apply { isDaemon = true; start() }
        var stopper: Thread? = null
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val stop = FutureTask { runner.stop() }
            stopper = Thread(stop, "reasoning-stop").apply { isDaemon = true; start() }
            // This would time out if the observer held terminalLock.
            stop.get(2, TimeUnit.SECONDS)
            assertEquals(1L, release.count)
            assertFalse(task.isDone)
            release.countDown()
            val result = task.get(5, TimeUnit.SECONDS)
            assertEquals(RunStatus.STOPPED, result.status)
            lateReasoning(retained)
            assertEquals(listOf(first), observed)
            assertEquals(listOf(result), writes.filter { it.status != RunStatus.RUNNING })
        } finally {
            release.countDown()
            runner.stop()
            owner.join(5000)
            stopper?.join(5000)
            assertFalse(owner.isAlive)
            assertFalse(stopper?.isAlive ?: false)
        }
    }

    @Test fun repetitiveDeltaOrFinalAndEventsAfterRejectionCannotNotify() {
        for (rejectFinal in listOf(false, true)) {
            val first = ModelEvent.ReasoningDelta("reason", "Accepted prefix.")
            val rejected: ModelEvent = if (rejectFinal)
                ModelEvent.ReasoningFinal("reason", "OK.\n".repeat(2000))
            else ModelEvent.ReasoningDelta("reason", "OK.\n".repeat(2000))
            val observed = mutableListOf<ModelEvent>()
            val writes = mutableListOf<Message>()
            val runner = ChatRunner(ModelPort { session { sink ->
                sink(ModelEvent.TextFinal("body", "accepted answer"))
                sink(first)
                // A transport may swallow sink failures, but cannot restore acceptance.
                for (event in listOf(rejected, ModelEvent.ReasoningDelta("later", "Must not notify."),
                    ModelEvent.ReasoningFinal("later", "Must not notify."))) {
                    try { sink(event) } catch (_: RuntimeException) { }
                }
            } }, store(writes), BatteryTool { error("unused") }, RunnerOptions(onReasoning = observed::add)) {}

            val result = runner.run(initial(), emptyList())
            assertEquals(RunStatus.FAILED, result.status)
            assertTrue(result.error!!.contains("高度重复"))
            assertEquals(listOf(first), observed)
            assertEquals(listOf(MessageBlock.Text("body", "accepted answer")), result.blocks)
            assertEquals(listOf(result), writes.filter { it.status != RunStatus.RUNNING })
        }
    }

    @Test fun observerExceptionsLeaveDefaultModelAndStorageResultsUnchanged() {
        data class Evidence(val result: Message, val writes: List<Message>,
            val checkpoints: List<Pair<ExecutionPhase, Message>>, val usages: List<ModelEvent.Usage>)
        val delta = ModelEvent.ReasoningDelta("reason", "A short draft.")
        val final = ModelEvent.ReasoningFinal("reason", "A short conclusion.")
        fun runWith(observer: ((ModelEvent) -> Unit)?): Evidence {
            val writes = mutableListOf<Message>()
            val checkpoints = mutableListOf<Pair<ExecutionPhase, Message>>()
            val usages = mutableListOf<ModelEvent.Usage>()
            val runtime = object : RuntimeStore {
                override fun save(checkpoint: RunCheckpoint) { checkpoints.add(checkpoint.phase to checkpoint.message) }
            }
            val options = if (observer == null) RunnerOptions(runtimeStore = runtime, onUsage = usages::add)
            else RunnerOptions(runtimeStore = runtime, onUsage = usages::add, onReasoning = observer)
            val runner = ChatRunner(ModelPort { session { sink ->
                sink(delta)
                sink(final)
                sink(final) // An identical final snapshot is not degenerate repeated output.
                sink(ModelEvent.TextFinal("body", "answer"))
                sink(ModelEvent.ProtocolOutput(listOf("protocol")))
                sink(ModelEvent.Usage(3, 4))
            } }, store(writes), BatteryTool { error("unused") }, options) {}
            return Evidence(runner.run(initial(), emptyList()), writes.toList(), checkpoints.toList(), usages.toList())
        }

        val baseline = runWith(null)
        val observed = mutableListOf<ModelEvent>()
        val throwing = runWith {
            observed.add(it)
            throw IllegalStateException("observer unavailable")
        }
        assertEquals(RunStatus.COMPLETED, baseline.result.status)
        assertEquals(baseline, throwing)
        assertEquals(listOf(delta, final, final), observed)
        assertEquals(listOf(ModelEvent.Usage(3, 4)), throwing.usages)
    }
}
