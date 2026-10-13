package app.daiyu.android.domain

import org.junit.Assert.*
import org.junit.Test

class ChatRunnerTest {
    private fun initial() = Message(MessageId("a"), ConversationId("c"), RunId("r"), "assistant", emptyList(), RunStatus.RUNNING)
    @Test fun orderedToolContinuationAndDuplicateIdentity() {
        val writes = mutableListOf<Message>(); var executions = 0; var rounds = 0
        val call = ToolInvocation(ToolCallId("call"), "get_battery_status", "{}")
        val port = ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                if (rounds++ == 0) {
                    sink(ModelEvent.TextDelta("t1", "查询"));sink(ModelEvent.ToolReady(call));sink(ModelEvent.ToolReady(call))
                } else { assertEquals("真实结果", outputs.single().output);sink(ModelEvent.TextDelta("t2", "回答")) }
            }
            override fun cancel() {}
        } }
        val runner = ChatRunner(port, object : ConversationStore { override fun save(message: Message) { writes.add(message) } },
            BatteryTool { executions++; "真实结果" }, {})
        val result = runner.run(initial(), emptyList())
        assertEquals(RunStatus.COMPLETED, result.status); assertEquals(1, executions)
        assertEquals(3, result.blocks.size); assertTrue(result.blocks[1] is MessageBlock.Tool)
        assertEquals("真实结果", (result.blocks[1] as MessageBlock.Tool).result)
    }
    @Test fun stopIgnoresLateEventsAndHasOneTerminal() {
        val saved = mutableListOf<Message>(); lateinit var runner: ChatRunner
        val port = ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                sink(ModelEvent.TextDelta("t", "已确认"));runner.stop();sink(ModelEvent.TextDelta("t", "迟到"))
            }; override fun cancel() {}
        } }
        runner = ChatRunner(port, object : ConversationStore { override fun save(message: Message) { saved.add(message) } }, BatteryTool { error("no tool") }, {})
        val result=runner.run(initial(), emptyList())
        assertEquals(RunStatus.STOPPED,result.status);assertEquals("已确认",(result.blocks.single() as MessageBlock.Text).text)
        assertEquals(1,saved.count { it.status != RunStatus.RUNNING })
    }
    @Test fun incompleteProtocolNeverExecutesTool() {
        var executed=0
        val port=ModelPort { object : ModelSession {
            override fun next(outputs:List<ToolOutput>,sink:(ModelEvent)->Unit) {
                sink(ModelEvent.ToolReady(ToolInvocation(ToolCallId("call"),"get_battery_status","{}")));error("incomplete")
            };override fun cancel() {}
        } }
        val result=ChatRunner(port,object:ConversationStore {override fun save(message:Message) {}},BatteryTool {executed++;"{}"},{}).run(initial(),emptyList())
        assertEquals(RunStatus.FAILED,result.status);assertEquals(0,executed)
    }
    @Test fun storeFailurePreventsToolExecution() {
        var executed=0
        val port=ModelPort { object:ModelSession {
            override fun next(outputs:List<ToolOutput>,sink:(ModelEvent)->Unit) {sink(ModelEvent.ToolReady(ToolInvocation(ToolCallId("x"),"get_battery_status","{}")))}
            override fun cancel() {}
        } }
        val result=ChatRunner(port,object:ConversationStore {override fun save(message:Message) {throw PersistenceException(IllegalStateException())}},BatteryTool {executed++;"{}"},{}).run(initial(),emptyList())
        assertEquals(RunStatus.FAILED,result.status);assertEquals(0,executed)
    }
    @Test fun crossRoundDuplicateFailsWithoutReexecution() {
        var executions=0
        val call=ToolInvocation(ToolCallId("repeat"),"get_battery_status","{}")
        val port=ModelPort {object:ModelSession {
            override fun next(outputs:List<ToolOutput>,sink:(ModelEvent)->Unit) {sink(ModelEvent.ToolReady(call))}
            override fun cancel() {}
        }}
        val result=ChatRunner(port,object:ConversationStore {override fun save(message:Message) {}},BatteryTool {executions++;"{}"},{}).run(initial(),emptyList())
        assertEquals(RunStatus.FAILED,result.status);assertEquals(1,executions)
    }
    @Test fun finalTextCorrectsWithoutAppending() {
        val port=ModelPort {object:ModelSession {
            override fun next(outputs:List<ToolOutput>,sink:(ModelEvent)->Unit) {
                sink(ModelEvent.TextDelta("t","草稿"));sink(ModelEvent.TextFinal("t","最终"))
            }
            override fun cancel() {}
        }}
        val result=ChatRunner(port,object:ConversationStore {override fun save(message:Message) {}},BatteryTool {error("unused")},{}).run(initial(),emptyList())
        assertEquals("最终",(result.blocks.single() as MessageBlock.Text).text)
    }
}
