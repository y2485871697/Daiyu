package app.daiyu.android.domain

import app.daiyu.android.domain.runtime.*
import org.junit.Assert.*
import org.junit.Test

class ChatRunnerRoundLimitTest {
    private class Runtime : AtomicRuntimeStore {
        val writes = mutableListOf<RunCheckpoint>()
        override fun commitCheckpoint(checkpoint: RunCheckpoint) { writes.add(checkpoint) }
        override fun reconcileCheckpoint(checkpoint: RunCheckpoint): RunCheckpoint = error("unexpected recovery")
    }

    private class Fixture(val limit: Int = RunnerOptions().maxRounds, val completeAt: Int? = null) {
        val runtime = Runtime()
        val executions = mutableListOf<ToolCallId>()
        var requests = 0
        private val session = object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                assertTrue(requests < limit)
                if (requests == 0) assertTrue(outputs.isEmpty())
                else assertEquals(listOf(ToolOutput(ToolCallId("call-$requests"), "result-$requests")), outputs)
                requests++
                if (requests == completeAt) sink(ModelEvent.TextFinal("answer", "done"))
                else sink(ModelEvent.ToolReady(ToolInvocation(ToolCallId("call-$requests"), "get_battery_status", "{}")))
            }
            override fun cancel() {}
        }
        private val port = object : ModelPort {
            override fun open(history: List<Message>) = session
            override fun open(history: List<Message>, tools: Set<ReadOnlyTool>) = session
            override fun open(history: List<Message>, definitions: List<ToolDefinition>) = session
        }
        fun run(defaultOptions: Boolean = false): Message {
            val options = if (defaultOptions) RunnerOptions(runtimeStore = runtime)
                else RunnerOptions(runtimeStore = runtime, maxRounds = limit)
            val runner = ChatRunner(port, object : ConversationStore {
                override fun save(message: Message) = error("atomic store required")
            }, BatteryTool { call ->
                assertFalse(executions.contains(call.id))
                executions.add(call.id)
                "result-${executions.size}"
            }, options) {}
            return runner.run(Message(MessageId("assistant"), ConversationId("conversation"), RunId("run"),
                Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING), emptyList())
        }
    }

    @Test fun defaultRunnerContinuesBeyondTwelveRoundsWithoutReexecutingTools() {
        val fixture = Fixture(completeAt = 14)
        val result = fixture.run(defaultOptions = true)
        assertEquals(RunStatus.COMPLETED, result.status)
        assertNull(result.error)
        assertEquals(14, fixture.requests)
        assertEquals(13, fixture.executions.size)
        assertEquals((1..13).map { "result-$it" }, result.blocks.filterIsInstance<MessageBlock.Tool>().map { it.result })
        assertEquals("done", result.blocks.filterIsInstance<MessageBlock.Text>().single().text)
    }

    @Test fun configuredLimitKeepsTheFinalToolOutputAndReportsTheActualLimit() {
        val fixture = Fixture(limit = 2)
        val result = fixture.run()
        assertEquals(RunStatus.FAILED, result.status)
        assertEquals("工具续轮已达到2轮安全上限，已停止追加请求；已执行的工具结果已保留", result.error)
        assertEquals(2, fixture.requests)
        assertEquals(2, fixture.executions.size)
        assertEquals(listOf("result-1", "result-2"), result.blocks.filterIsInstance<MessageBlock.Tool>().map { it.result })
        val terminal = fixture.runtime.writes.last()
        assertEquals(ExecutionPhase.TERMINAL, terminal.phase)
        assertEquals(2, terminal.round)
        assertFalse(terminal.requestUnconfirmed)
        assertEquals(listOf(ToolOutput(ToolCallId("call-2"), "result-2")), terminal.outputs)
        assertTrue(terminal.tools.all { it.disposition == ToolDisposition.SUCCEEDED })
        assertEquals(1, fixture.runtime.writes.count { it.message.status == RunStatus.FAILED })
    }

    @Test fun completionOnTheLastAllowedRoundDoesNotBecomeALimitFailure() {
        val fixture = Fixture(limit = 2, completeAt = 2)
        val result = fixture.run()
        assertEquals(RunStatus.COMPLETED, result.status)
        assertNull(result.error)
        assertEquals(2, fixture.requests)
        assertEquals(1, fixture.executions.size)
    }

    @Test fun defaultSafetyLimitStillStopsAnUnboundedToolLoop() {
        assertEquals(100, RunnerOptions().maxRounds)
        val fixture = Fixture()
        val result = fixture.run(defaultOptions = true)
        assertEquals(RunStatus.FAILED, result.status)
        assertEquals(100, fixture.requests)
        assertEquals(100, fixture.executions.size)
        assertEquals("工具续轮已达到100轮安全上限，已停止追加请求；已执行的工具结果已保留", result.error)
        assertEquals(100, fixture.runtime.writes.last().round)
        assertEquals("result-100", result.blocks.filterIsInstance<MessageBlock.Tool>().last().result)
    }
}
