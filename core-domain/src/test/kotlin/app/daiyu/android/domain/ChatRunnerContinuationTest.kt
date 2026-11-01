package app.daiyu.android.domain

import app.daiyu.android.domain.runtime.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class ChatRunnerContinuationTest {
    private class Runtime : AtomicRuntimeStore {
        val writes = mutableListOf<RunCheckpoint>()
        val paused = CountDownLatch(1)
        override fun commitCheckpoint(checkpoint: RunCheckpoint) {
            writes.add(checkpoint)
            if (checkpoint.phase == ExecutionPhase.PAUSED) paused.countDown()
        }
        override fun reconcileCheckpoint(checkpoint: RunCheckpoint): RunCheckpoint = error("unexpected recovery")
    }

    private class Fixture(val completeAt: Int? = null, val stopAfter: Int? = null, val pauseAfter: Int? = null) {
        val runtime = Runtime()
        val executions = mutableListOf<ToolCallId>()
        var requests = 0
        val runner: ChatRunner
        init {
            val session = object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    if (requests == 0) assertTrue(outputs.isEmpty())
                    else assertEquals(listOf(ToolOutput(ToolCallId("call-$requests"), "result-$requests")), outputs)
                    requests++
                    if (requests == completeAt) sink(ModelEvent.TextFinal("answer", "done"))
                    else sink(ModelEvent.ToolReady(ToolInvocation(ToolCallId("call-$requests"), "get_battery_status", "{}")))
                }
                override fun cancel() {}
            }
            val port = object : ModelPort {
                override fun open(history: List<Message>) = session
                override fun open(history: List<Message>, tools: Set<ReadOnlyTool>) = session
                override fun open(history: List<Message>, definitions: List<ToolDefinition>) = session
            }
            runner = ChatRunner(port, object : ConversationStore {
                override fun save(message: Message) = error("atomic store required")
            }, BatteryTool(::execute), RunnerOptions(runtimeStore = runtime)) {}
        }
        private fun execute(call: ToolInvocation): String {
            assertFalse(executions.contains(call.id))
            executions.add(call.id)
            if (executions.size == stopAfter) runner.stop()
            if (executions.size == pauseAfter) assertTrue(runner.pause())
            return "result-${executions.size}"
        }
        fun run() = runner.run(Message(MessageId("assistant"), ConversationId("conversation"), RunId("run"),
            Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING), emptyList())
    }

    @Test fun continuesPastAllFormerRoundCapsAndCompletesWithoutReexecutingTools() {
        val fixture = Fixture(completeAt = 130)
        val result = fixture.run()
        assertEquals(RunStatus.COMPLETED, result.status)
        assertNull(result.error)
        assertEquals(130, fixture.requests)
        assertEquals(129, fixture.executions.size)
        assertEquals((1..129).map { "result-$it" }, result.blocks.filterIsInstance<MessageBlock.Tool>().map { it.result })
        assertEquals("done", result.blocks.filterIsInstance<MessageBlock.Text>().single().text)
        assertEquals(130, fixture.runtime.writes.last().round)
    }

    @Test fun userStopAfterFormerCapsKeepsFinalToolReceiptAndStartsNoFurtherRequest() {
        val fixture = Fixture(stopAfter = 130)
        val result = fixture.run()
        assertEquals(RunStatus.STOPPED, result.status)
        assertNull(result.error)
        assertEquals(130, fixture.requests)
        assertEquals(130, fixture.executions.size)
        val terminal = fixture.runtime.writes.last()
        assertEquals(ExecutionPhase.TERMINAL, terminal.phase)
        assertEquals(130, terminal.round)
        assertFalse(terminal.requestUnconfirmed)
        assertNull(terminal.pending)
        assertEquals(listOf(ToolOutput(ToolCallId("call-130"), "result-130")), terminal.outputs)
        assertTrue(terminal.tools.all { it.disposition == ToolDisposition.SUCCEEDED })
        assertEquals("result-130", result.blocks.filterIsInstance<MessageBlock.Tool>().last().result)
        assertEquals(1, fixture.runtime.writes.count { it.message.status == RunStatus.STOPPED })
    }

    @Test fun pauseAndResumeAfterFormerCapsKeepsTheSameOutputsWithoutReexecution() {
        val fixture = Fixture(completeAt = 132, pauseAfter = 130)
        val task = FutureTask { fixture.run() }
        val thread = Thread(task).apply { isDaemon = true; start() }
        try {
            assertTrue(fixture.runtime.paused.await(5, TimeUnit.SECONDS))
            assertEquals(130, fixture.requests)
            assertEquals(130, fixture.executions.size)
            assertFalse(task.isDone)
            assertTrue(fixture.runner.resume())
            val result = task.get(5, TimeUnit.SECONDS)
            assertEquals(RunStatus.COMPLETED, result.status)
            assertNull(result.error)
            assertEquals(132, fixture.requests)
            assertEquals(131, fixture.executions.size)
            assertEquals("done", result.blocks.filterIsInstance<MessageBlock.Text>().single().text)
        } finally {
            fixture.runner.stop()
            thread.join(5000)
            assertFalse(thread.isAlive)
        }
    }
}
