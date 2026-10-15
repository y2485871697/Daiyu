package app.daiyu.android.domain

import org.junit.Assert.*
import org.junit.Test
import app.daiyu.android.domain.runtime.RunnerOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class ChatRunnerTest {
    private fun initial() = Message(MessageId("a"), ConversationId("c"), RunId("r"), "assistant", emptyList(), RunStatus.RUNNING)
    @Test fun classifiedFailuresRemainSafeAndNeverExecutePendingTools() {
        for (failure in ModelFailure.entries) {
            val saved=mutableListOf<Message>();var executed=0;var opened=0
            val port=ModelPort { opened++;object:ModelSession {
                override fun next(outputs:List<ToolOutput>,sink:(ModelEvent)->Unit) {
                    sink(ModelEvent.TextDelta("t","accepted"))
                    sink(ModelEvent.ToolReady(ToolInvocation(ToolCallId("pending"),"get_battery_status","{}")))
                    throw ModelRequestException(failure)
                }
                override fun cancel() {throw IllegalStateException("synthetic-secret")}
            }}
            val result=ChatRunner(port,object:ConversationStore {override fun save(message:Message) {saved.add(message)}},BatteryTool {executed++;"{}"},{}).run(initial(),emptyList())
            assertEquals(RunStatus.FAILED,result.status);assertEquals(failure.userMessage,result.error)
            assertEquals("accepted",(result.blocks.first() as MessageBlock.Text).text)
            assertEquals(0,executed);assertEquals(1,opened)
            assertEquals(1,saved.count {it.status==RunStatus.FAILED});assertFalse(result.error!!.contains("synthetic-secret"))
        }
    }
    @Test fun unknownFailureDoesNotExposeServiceSecrets() {
        val port=ModelPort {throw IllegalStateException("synthetic-key https://example.invalid/?secret=value")}
        val result=ChatRunner(port,object:ConversationStore {override fun save(message:Message) {}},BatteryTool {error("unused")},{}).run(initial(),emptyList())
        assertEquals(RunStatus.FAILED,result.status)
        assertEquals("请求失败，请检查模型设置或网络",result.error)
    }
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
        val saved = mutableListOf<Message>(); val published = mutableListOf<Message>()
        val usage = mutableListOf<ModelEvent.Usage>(); var executed = 0
        lateinit var runner: ChatRunner; lateinit var retainedSink: (ModelEvent) -> Unit
        val late = listOf<ModelEvent>(
            ModelEvent.TextDelta("t", "迟到"), ModelEvent.TextFinal("t", "不得替换"),
            ModelEvent.ReasoningDelta("r", "OK.\n".repeat(2000)),
            ModelEvent.ReasoningFinal("r", "OK.\n".repeat(2000)),
            ModelEvent.ProtocolOutput(listOf("late-protocol")),
            ModelEvent.ToolReady(ToolInvocation(ToolCallId("late"), "get_battery_status", "{}")),
            ModelEvent.Usage(99, 99))
        val port = ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                retainedSink = sink; sink(ModelEvent.TextDelta("t", "已确认"))
                sink(ModelEvent.ProtocolOutput(listOf("accepted-protocol"))); sink(ModelEvent.Usage(1, 2))
                runner.stop(); late.forEach { sink(it) }
            }
            override fun cancel() {}
        } }
        runner = ChatRunner(port, object : ConversationStore {
            override fun save(message: Message) { saved.add(message) }
        }, BatteryTool { executed++; "bad" }, RunnerOptions(onUsage = usage::add), published::add)
        val result = runner.run(initial(), emptyList())
        assertEquals(RunStatus.STOPPED, result.status); assertNull(result.error)
        assertEquals(listOf(MessageBlock.Text("t", "已确认")), result.blocks)
        assertEquals(listOf("accepted-protocol"), result.protocolItems)
        assertEquals(listOf(ModelEvent.Usage(1, 2)), usage); assertEquals(0, executed)
        assertEquals(listOf(result), saved.filter { it.status != RunStatus.RUNNING })
        assertEquals(listOf(result), published.filter { it.status != RunStatus.RUNNING })
        val savedBefore = saved.toList(); val publishedBefore = published.toList()
        late.forEach { retainedSink(it) }
        assertEquals(savedBefore, saved); assertEquals(publishedBefore, published)
        assertEquals(listOf(ModelEvent.Usage(1, 2)), usage); assertEquals(0, executed)
        assertFalse(runner.pause()); assertFalse(runner.resume())
        assertFalse(runner.supplement(initial().copy(id = MessageId("after-stop"), role = "user", status = RunStatus.COMPLETED)))
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
    @Test fun stopBeforeNextReturnsWinsCompletion() {
        val ready=CountDownLatch(1);val stopped=CountDownLatch(1)
        val saved=mutableListOf<Message>();val published=mutableListOf<Message>()
        val port=ModelPort {object:ModelSession {
            override fun next(outputs:List<ToolOutput>,sink:(ModelEvent)->Unit) {
                sink(ModelEvent.TextDelta("t","accepted"));ready.countDown();check(stopped.await(5,TimeUnit.SECONDS))
            }
            override fun cancel() {}
        }}
        val runner=ChatRunner(port,object:ConversationStore {override fun save(message:Message) {saved.add(message)}},BatteryTool {error("unused")},published::add)
        val task=FutureTask {runner.run(initial(),emptyList())};val owner=Thread(task,"chat-owner").apply {isDaemon=true}
        owner.start()
        try {
            assertTrue(ready.await(5,TimeUnit.SECONDS))
            val stopTask=FutureTask {runner.stop()};val stopper=Thread(stopTask,"chat-stop").apply {isDaemon=true};stopper.start()
            try {stopTask.get(5,TimeUnit.SECONDS)} finally {stopped.countDown();stopper.join(5000);assertFalse(stopper.isAlive)}
            val result=task.get(5,TimeUnit.SECONDS)
            assertEquals(RunStatus.STOPPED,result.status);assertNull(result.error)
            assertEquals("accepted",(result.blocks.single() as MessageBlock.Text).text)
            assertEquals(listOf(RunStatus.STOPPED),saved.filter {it.status!=RunStatus.RUNNING}.map {it.status})
            assertEquals(listOf(RunStatus.STOPPED),published.filter {it.status!=RunStatus.RUNNING}.map {it.status})
        } finally {stopped.countDown();owner.join(5000);assertFalse(owner.isAlive)}
    }
    @Test fun completionHoldingLockWinsConcurrentStop() {
        val completing=CountDownLatch(1);val release=CountDownLatch(1)
        val saved=mutableListOf<Message>();val published=mutableListOf<Message>()
        val port=ModelPort {object:ModelSession {
            override fun next(outputs:List<ToolOutput>,sink:(ModelEvent)->Unit) {sink(ModelEvent.TextDelta("t","accepted"))}
            override fun cancel() {}
        }}
        val runner=ChatRunner(port,object:ConversationStore {
            override fun save(message:Message) {
                if(message.status==RunStatus.COMPLETED) {completing.countDown();check(release.await(10,TimeUnit.SECONDS))}
                saved.add(message)
            }
        },BatteryTool {error("unused")},published::add)
        val task=FutureTask {runner.run(initial(),emptyList())};val owner=Thread(task,"chat-owner").apply {isDaemon=true}
        val stopTask=FutureTask {runner.stop()};val stopper=Thread(stopTask,"chat-stop").apply {isDaemon=true}
        owner.start()
        try {
            assertTrue(completing.await(5,TimeUnit.SECONDS));stopper.start()
            stopTask.get(1,TimeUnit.SECONDS)
            assertTrue(stopper.state != Thread.State.BLOCKED)
            release.countDown();val result=task.get(5,TimeUnit.SECONDS)
            assertEquals(RunStatus.COMPLETED,result.status)
            assertEquals(listOf(RunStatus.COMPLETED),saved.filter {it.status!=RunStatus.RUNNING}.map {it.status})
            assertEquals(listOf(RunStatus.COMPLETED),published.filter {it.status!=RunStatus.RUNNING}.map {it.status})
        } finally {release.countDown();owner.join(5000);if(stopper.state!=Thread.State.NEW) stopper.join(5000);assertFalse(stopper.isAlive)}
    }
    @Test fun failedCompletionSaveDoesNotAttemptSecondTerminal() {
        checkTerminalSaveFailure(false)
    }
    @Test fun failedTerminalSavesPublishWithoutClaimingDurability() {
        checkTerminalSaveFailure(true)
    }
    private fun checkTerminalSaveFailure(failAllTerminals:Boolean) {
        val attempted=mutableListOf<Message>();val saved=mutableListOf<Message>();val published=mutableListOf<Message>()
        val port=ModelPort {object:ModelSession {
            override fun next(outputs:List<ToolOutput>,sink:(ModelEvent)->Unit) {sink(ModelEvent.TextDelta("t","accepted"))}
            override fun cancel() {}
        }}
        val result=ChatRunner(port,object:ConversationStore {
            override fun save(message:Message) {
                attempted.add(message)
                if(message.status==RunStatus.COMPLETED || (failAllTerminals && message.status!=RunStatus.RUNNING)) throw PersistenceException(IllegalStateException())
                saved.add(message)
            }
        },BatteryTool {error("unused")},published::add).run(initial(),emptyList())
        assertEquals(RunStatus.FAILED,result.status);assertEquals("accepted",(result.blocks.single() as MessageBlock.Text).text)
        assertEquals("保存失败，已显示内容可能未落盘",result.error)
        assertEquals(listOf(RunStatus.COMPLETED),attempted.filter {it.status!=RunStatus.RUNNING}.map {it.status})
        assertEquals(emptyList<RunStatus>(),saved.filter {it.status!=RunStatus.RUNNING}.map {it.status})
        assertEquals(listOf(RunStatus.FAILED),published.filter {it.status!=RunStatus.RUNNING}.map {it.status})
    }

}
