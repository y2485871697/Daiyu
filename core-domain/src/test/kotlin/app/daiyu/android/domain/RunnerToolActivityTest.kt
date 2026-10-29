package app.daiyu.android.domain

import app.daiyu.android.domain.runtime.ExecutionPhase
import org.junit.Assert.*
import org.junit.Test

class RunnerToolActivityTest {
    private fun initial() = Message(MessageId("activity"), ConversationId("activity"), RunId("activity"),
        Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)

    @Test fun sequentialCallsOnlyMarkTheExecutingCallAndClearBeforeTheNextModelTurn() {
        val calls = listOf("a", "b").map { ToolInvocation(ToolCallId(it), "lookup", "{}") }
        lateinit var runner: ChatRunner
        var round = 0
        val executed = mutableListOf<ToolCallId>()
        val port = ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                if (round++ == 0) calls.forEach { sink(ModelEvent.ToolReady(it)) }
                else {
                    assertTrue(runner.state().activeToolCalls.isEmpty())
                    sink(ModelEvent.TextFinal("body", "done"))
                }
            }
            override fun cancel() {}
        } }
        runner = ChatRunner(port, object : ConversationStore {
            override fun save(message: Message) {}
        }, BatteryTool { call ->
            assertEquals(ExecutionPhase.TOOL_EXECUTING, runner.state().phase)
            assertEquals(setOf(call.id), runner.state().activeToolCalls)
            executed += call.id
            "ok"
        }) {}
        assertEquals(RunStatus.COMPLETED, runner.run(initial(), emptyList()).status)
        assertEquals(calls.map { it.id }, executed)
        assertTrue(runner.state().activeToolCalls.isEmpty())
    }

    @Test fun unknownResultDoesNotLeaveAnyCallActive() {
        lateinit var runner: ChatRunner
        val port = ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                sink(ModelEvent.ToolReady(ToolInvocation(ToolCallId("a"), "lookup", "{}")))
            }
            override fun cancel() {}
        } }
        runner = ChatRunner(port, object : ConversationStore {
            override fun save(message: Message) {}
        }, BatteryTool { throw IllegalStateException("uncertain") }) {}
        assertEquals(RunStatus.FAILED, runner.run(initial(), emptyList()).status)
        assertTrue(runner.state().activeToolCalls.isEmpty())
    }
}
