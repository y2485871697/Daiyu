package app.daiyu.android.domain

import app.daiyu.android.domain.runtime.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ChatRunnerAttemptGateTest {
    private fun initial() = Message(MessageId("a"), ConversationId("c"), RunId("r"), "assistant", emptyList(), RunStatus.RUNNING)
    private fun store(writes: MutableList<Message>) = object : ConversationStore {
        override fun save(message: Message) { writes.add(message) }
    }
    private fun poison(sink: (ModelEvent) -> Unit) {
        listOf<ModelEvent>(ModelEvent.TextDelta("t", "late-delta"), ModelEvent.TextFinal("t", "late-final"),
            ModelEvent.ReasoningDelta("reason", "OK.\n".repeat(2000)), ModelEvent.ReasoningFinal("reason", "OK.\n".repeat(2000)),
            ModelEvent.ProtocolOutput(listOf("late-protocol")), ModelEvent.ToolReady(ToolInvocation(ToolCallId("late"), "get_battery_status", "{}")),
            ModelEvent.Usage(99, 99, 99)).forEach(sink)
    }
    @Test fun everyResumeGetsFreshGateAndOldAttemptsLoseAllEventSurfaces() {
        val writes = mutableListOf<Message>(); val usages = mutableListOf<ModelEvent.Usage>(); val retained = mutableListOf<(ModelEvent) -> Unit>()
        var opens = 0; var nexts = 0; var resumes = 0; var executions = 0
        val port = ModelPort {
            opens++
            object : ResumableModelSession {
                override val canResume = true
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    nexts++; assertTrue(outputs.isEmpty()); retained.add(sink); sink(ModelEvent.TextDelta("t", "prefix")); sink(ModelEvent.Usage(1, 2))
                    throw ModelRequestException(ModelFailure.NETWORK)
                }
                override fun resume(sink: (ModelEvent) -> Unit) {
                    resumes++; retained.toList().forEach { poison(it) }; retained.add(sink)
                    if (resumes == 1) { sink(ModelEvent.TextDelta("t", "middle")); sink(ModelEvent.Usage(3, 4)); throw ModelRequestException(ModelFailure.TIMEOUT) }
                    sink(ModelEvent.TextDelta("t", "suffix")); sink(ModelEvent.ProtocolOutput(listOf("accepted-protocol"))); sink(ModelEvent.Usage(5, 6))
                }
                override fun cancel() {}
            }
        }
        val result = ChatRunner(port, store(writes), BatteryTool { executions++; "unexpected" }, RunnerOptions(reconnect = ReconnectPolicy(2), onUsage = usages::add)) {}.run(initial(), emptyList())
        assertEquals(RunStatus.COMPLETED, result.status); assertEquals(listOf(MessageBlock.Text("t", "prefixmiddlesuffix")), result.blocks)
        assertEquals(listOf("accepted-protocol"), result.protocolItems); assertEquals(listOf(ModelEvent.Usage(1, 2), ModelEvent.Usage(3, 4), ModelEvent.Usage(5, 6)), usages)
        assertEquals(1, opens); assertEquals(1, nexts); assertEquals(2, resumes); assertEquals(0, executions)
        assertEquals(listOf(result), writes.filter { it.status != RunStatus.RUNNING })
        val before = writes.toList(); retained.forEach { poison(it) }; assertEquals(before, writes); assertEquals(3, usages.size)
    }
    @Test fun oldAttemptIsClosedBeforeReconnectCheckpoint() {
        val writes = mutableListOf<Message>(); val usages = mutableListOf<ModelEvent.Usage>(); val injected = AtomicBoolean()
        lateinit var oldSink: (ModelEvent) -> Unit
        var opens = 0; var nexts = 0; var resumes = 0
        val runtime = object : RuntimeStore {
            override fun save(checkpoint: RunCheckpoint) {
                if (checkpoint.phase == ExecutionPhase.RECONNECTING && injected.compareAndSet(false, true)) {
                    oldSink(ModelEvent.TextFinal("t", "stale-checkpoint")); oldSink(ModelEvent.Usage(99, 99))
                }
            }
        }
        val port = ModelPort {
            opens++
            object : ResumableModelSession {
                override val canResume = true
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    nexts++; assertTrue(outputs.isEmpty()); oldSink = sink
                    sink(ModelEvent.TextDelta("t", "prefix")); sink(ModelEvent.Usage(1, 2)); throw ModelRequestException(ModelFailure.NETWORK)
                }
                override fun resume(sink: (ModelEvent) -> Unit) { resumes++; sink(ModelEvent.TextDelta("t", "suffix")); sink(ModelEvent.Usage(3, 4)) }
                override fun cancel() {}
            }
        }
        val result = ChatRunner(port, store(writes), BatteryTool { error("unused") }, RunnerOptions(reconnect = ReconnectPolicy(1), runtimeStore = runtime, onUsage = usages::add)) {}.run(initial(), emptyList())
        assertTrue(injected.get()); assertEquals(RunStatus.COMPLETED, result.status)
        assertEquals(listOf(MessageBlock.Text("t", "prefixsuffix")), result.blocks); assertEquals(listOf(ModelEvent.Usage(1, 2), ModelEvent.Usage(3, 4)), usages)
        assertFalse(writes.any { m -> m.blocks.filterIsInstance<MessageBlock.Text>().any { it.text.contains("stale-checkpoint") } })
        assertEquals(1, opens); assertEquals(1, nexts); assertEquals(1, resumes)
    }
    @Test(timeout = 20000) fun inFlightCallbackDrainsBeforeReconnect() = checkInFlight("success")
    @Test(timeout = 20000) fun inFlightSinkFailurePreventsResume() = checkInFlight("failure")
    @Test(timeout = 20000) fun stopDoesNotWaitForAttemptDrain() = checkInFlight("stop")
    private fun checkInFlight(mode: String) {
        val entered = CountDownLatch(1); val leavingNext = CountDownLatch(1); val release = CountDownLatch(1); val reconnectCheckpoint = CountDownLatch(1)
        val writes = CopyOnWriteArrayList<Message>(); val resumes = AtomicInteger(); val nexts = AtomicInteger(); val opens = AtomicInteger()
        lateinit var callback: FutureTask<Unit>
        var callbackThread: Thread? = null
        val runtime = object : RuntimeStore {
            override fun save(checkpoint: RunCheckpoint) { if (checkpoint.phase == ExecutionPhase.RECONNECTING) reconnectCheckpoint.countDown() }
        }
        val messages = object : ConversationStore {
            override fun save(message: Message) {
                if (message.status == RunStatus.RUNNING && message.blocks == listOf(MessageBlock.Text("t", "prefix"))) {
                    entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); if (mode == "failure") error("fixture")
                }
                writes.add(message)
            }
        }
        val port = ModelPort {
            opens.incrementAndGet()
            object : ResumableModelSession {
                override val canResume = true
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    nexts.incrementAndGet(); assertTrue(outputs.isEmpty())
                    callback = FutureTask { sink(ModelEvent.TextDelta("t", "prefix")); Unit }
                    callbackThread = Thread(callback, "attempt-callback").apply { isDaemon = true; start() }
                    check(entered.await(5, TimeUnit.SECONDS)); leavingNext.countDown(); throw ModelRequestException(ModelFailure.NETWORK)
                }
                override fun resume(sink: (ModelEvent) -> Unit) {
                    resumes.incrementAndGet(); assertTrue(writes.any { it.status == RunStatus.RUNNING && it.blocks == listOf(MessageBlock.Text("t", "prefix")) })
                    sink(ModelEvent.TextDelta("t", "suffix"))
                }
                override fun cancel() {}
            }
        }
        val runner = ChatRunner(port, messages, BatteryTool { error("unused") }, RunnerOptions(reconnect = ReconnectPolicy(1), runtimeStore = runtime)) {}
        val run = FutureTask { runner.run(initial(), emptyList()) }; val owner = Thread(run, "attempt-owner").apply { isDaemon = true }
        var stopper: Thread? = null
        owner.start()
        try {
            assertTrue(leavingNext.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (owner.state != Thread.State.WAITING && reconnectCheckpoint.count == 1L && !run.isDone && System.nanoTime() < deadline) Thread.yield()
            assertEquals(Thread.State.WAITING, owner.state); assertEquals(1L, reconnectCheckpoint.count); assertEquals(0, resumes.get()); assertFalse(run.isDone)
            if (mode == "stop") {
                val stop = FutureTask { runner.stop() }; stopper = Thread(stop, "attempt-stop").apply { isDaemon = true; start() }
                stop.get(2, TimeUnit.SECONDS); assertEquals(1L, release.count); assertFalse(run.isDone)
            }
            release.countDown(); val result = run.get(5, TimeUnit.SECONDS)
            if (mode == "failure") {
                try { callback.get(5, TimeUnit.SECONDS); fail("sink failure must reach callback") }
                catch (expected: ExecutionException) { assertTrue(expected.cause is PersistenceException) }
                assertEquals(RunStatus.FAILED, result.status); assertEquals("保存失败，已显示内容可能未落盘", result.error)
            } else {
                callback.get(5, TimeUnit.SECONDS); assertEquals(if (mode == "stop") RunStatus.STOPPED else RunStatus.COMPLETED, result.status)
                assertEquals(listOf(MessageBlock.Text("t", if (mode == "stop") "prefix" else "prefixsuffix")), result.blocks)
            }
            assertEquals(if (mode == "success") 1 else 0, resumes.get()); assertEquals(if (mode == "success") 0L else 1L, reconnectCheckpoint.count)
            assertEquals(1, opens.get()); assertEquals(1, nexts.get()); assertEquals(listOf(result), writes.filter { it.status != RunStatus.RUNNING })
        } finally {
            release.countDown(); runner.stop(); owner.join(5000); callbackThread?.join(5000); stopper?.join(5000)
            assertFalse(owner.isAlive); assertFalse(callbackThread?.isAlive ?: false); assertFalse(stopper?.isAlive ?: false)
        }
    }
}
