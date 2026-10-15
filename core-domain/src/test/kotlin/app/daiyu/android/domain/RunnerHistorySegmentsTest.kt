package app.daiyu.android.domain

import org.junit.Assert.*
import org.junit.Test

class RunnerHistorySegmentsTest {
    private fun initial() = Message(MessageId("a"),ConversationId("c"),RunId("r"),"assistant",emptyList(),RunStatus.RUNNING)
    private fun user(id: String) = initial().copy(id=MessageId(id),role="user",status=RunStatus.COMPLETED,
        blocks=listOf(MessageBlock.Text(id,id)))
    private val call = ToolInvocation(ToolCallId("call"),"lookup","{}")

    @Test fun cumulativeSnapshotReplacesAndPartialTailSurvivesUntilNextSnapshot() {
        val h=RunnerHistorySegments(initial())
        val first=initial().copy(blocks=listOf(MessageBlock.Tool(call)))
        h.replaceSessionProtocol(first,listOf("raw1"))
        val partial=first.copy(blocks=listOf(MessageBlock.Tool(call,"result"),MessageBlock.Text("t","partial")))
        val segments=h.project(partial).historySegments
        assertEquals(2,segments.size)
        assertEquals(listOf("raw1"),segments[0].protocolItems)
        assertEquals("result",(segments[0].blocks.single() as MessageBlock.Tool).result)
        assertEquals(listOf(MessageBlock.Text("t","partial")),segments[1].blocks)
        assertTrue(segments[1].protocolItems.isEmpty())
        h.replaceSessionProtocol(partial,listOf("raw1","result","raw2"))
        assertEquals(1,h.project(partial).historySegments.size)
        assertEquals(listOf("raw1","result","raw2"),h.project(partial).historySegments.single().protocolItems)
    }
    @Test fun reopenSealsRawAndUsersAndNewSessionCannotOverwriteOldRaw() {
        val h=RunnerHistorySegments(initial())
        val first=initial().copy(blocks=listOf(MessageBlock.Text("t","first")))
        h.replaceSessionProtocol(first,listOf("session1"))
        h.sealSession(first,listOf(user("u1"),user("u2")))
        val second=first.copy(blocks=first.blocks+MessageBlock.Text("t","second"))
        h.replaceSessionProtocol(second,listOf("session2"))
        val projected=h.project(second)
        assertEquals(listOf("assistant","user","user","assistant"),projected.historySegments.map { it.role })
        assertEquals(listOf(listOf("session1"),emptyList<String>(),emptyList<String>(),listOf("session2")),projected.historySegments.map { it.protocolItems })
        assertEquals(4,MessageHistory.expand(listOf(user("u1"),projected,user("u2"))).size)
    }
    @Test fun interruptedUnconfirmedToolIsNotReplayed() {
        val h=RunnerHistorySegments(initial())
        val partial=initial().copy(blocks=listOf(MessageBlock.Text("t","accepted"),MessageBlock.Tool(call)))
        val sealed=h.sealSession(partial,listOf(user("extra")))
        assertEquals(2,sealed.blocks.size)
        assertEquals(listOf(MessageBlock.Text("t","accepted")),sealed.historySegments.first().blocks)
    }
    @Test fun runnerPersistsSegmentsBeforeReopenAndDoesNotResubmitOutputs() {
        lateinit var runner:ChatRunner
        var saved:Message?=null;var opens=0;var batches=0
        val tool=BatteryTool { batches++;assertTrue(runner.supplement(user("extra")));"done" }
        val model=ModelPort { history ->
            opens++
            if(opens==2) {
                val expected=history.last()
                assertEquals(saved!!.historySegments,expected.historySegments)
                assertEquals(listOf("assistant","user"),MessageHistory.expand(history).map { it.role })
                assertEquals("done",(expected.historySegments.first().blocks.single() as MessageBlock.Tool).result)
            }
            object:ModelSession {
                override fun next(outputs:List<ToolOutput>,sink:(ModelEvent)->Unit) {
                    assertTrue(outputs.isEmpty())
                    if(opens==1) {sink(ModelEvent.ToolReady(call));sink(ModelEvent.ProtocolOutput(listOf("raw-call")))}
                    else sink(ModelEvent.TextFinal("t","answer"))
                }
                override fun cancel() {}
            }
        }
        runner=ChatRunner(model,object:ConversationStore {override fun save(message:Message) {saved=message}},tool,{})
        val result=runner.run(initial(),emptyList())
        assertEquals(RunStatus.COMPLETED,result.status);assertEquals(1,batches);assertEquals(2,opens)
        assertEquals(listOf("assistant","user","assistant"),result.historySegments.map { it.role })
        assertEquals(saved,result)
    }
    @Test fun boundarySaveFailureDoesNotOpenAnotherSession() {
        lateinit var runner:ChatRunner;var opens=0
        val store=object:ConversationStore {override fun save(message:Message) {
            if(message.historySegments.any { it.role=="user" }) error("disk")
        }}
        runner=ChatRunner(ModelPort { opens++;object:ModelSession {
            override fun next(outputs:List<ToolOutput>,sink:(ModelEvent)->Unit) {sink(ModelEvent.TextFinal("t","accepted"));runner.supplement(user("extra"))}
            override fun cancel() {}
        }},store,BatteryTool {"unused"},{})
        val result=runner.run(initial(),emptyList())
        assertEquals(1,opens);assertEquals(RunStatus.FAILED,result.status);assertTrue(result.error!!.startsWith("保存失败"))
    }
    @Test fun newStrictSegmentRulesRejectUserToolsEmptyIdsAndDuplicateTools() {
        val owner=initial()
        val invalid=listOf(
            MessageHistorySegment(MessageId("u"),"user",listOf(MessageBlock.Tool(call))),
            MessageHistorySegment(owner.id,"assistant",listOf(MessageBlock.Text("","text"))),
            MessageHistorySegment(owner.id,"assistant",listOf(MessageBlock.Tool(call),MessageBlock.Tool(call))),
            MessageHistorySegment(owner.id,"assistant",listOf(MessageBlock.Tool(call.copy(name=""))))
        )
        invalid.forEach { try {MessageHistory.validate(listOf(owner.copy(historySegments=listOf(it))));fail("accepted $it")} catch(_:IllegalArgumentException) {} }
        MessageHistory.validate(listOf(owner.copy(historySegments=listOf(
            MessageHistorySegment(owner.id,"assistant",listOf(MessageBlock.Tool(call))),
            MessageHistorySegment(owner.id,"assistant",listOf(MessageBlock.Tool(call)))))))
    }
    @Test fun interruptedConfirmedRawCannotReplayAnUnexecutedTool() {
        val h=RunnerHistorySegments(initial())
        val partial=initial().copy(blocks=listOf(MessageBlock.Text("t","accepted"),MessageBlock.Tool(call)))
        h.replaceSessionProtocol(partial,listOf("confirmed-raw-call"))
        val sealed=h.sealSession(partial,listOf(user("extra")))
        assertTrue(sealed.historySegments.first().protocolItems.isEmpty())
        assertEquals(listOf(MessageBlock.Text("t","accepted")),sealed.historySegments.first().blocks)
    }

    @Test fun runnerReopenDropsUnexecutedCallRawAndKeepsAcceptedText() {
        lateinit var runner:ChatRunner;var opens=0;var executions=0
        val model=ModelPort { history ->
            opens++
            if(opens==2) {
                val expanded=MessageHistory.expand(history)
                assertEquals(listOf("assistant","user"),expanded.map {it.role})
                assertEquals(listOf(MessageBlock.Text("t","accepted")),expanded.first().blocks)
                assertTrue(expanded.first().protocolItems.isEmpty())
                assertEquals(user("extra").blocks,expanded.last().blocks)
            }
            object:ModelSession {
                override fun next(outputs:List<ToolOutput>,sink:(ModelEvent)->Unit) {
                    assertTrue(outputs.isEmpty())
                    if(opens==1) {
                        sink(ModelEvent.TextFinal("t","accepted"));sink(ModelEvent.ToolReady(call))
                        sink(ModelEvent.ProtocolOutput(listOf("unexecuted-raw-call")))
                        assertTrue(runner.supplement(user("extra")))
                    } else sink(ModelEvent.TextFinal("last","answer"))
                }
                override fun cancel() {}
            }
        }
        runner=ChatRunner(model,object:ConversationStore {override fun save(message:Message) {}},BatteryTool {executions++;"must not execute"},{})
        val result=runner.run(initial(),emptyList())
        assertEquals(RunStatus.COMPLETED,result.status);assertEquals(2,opens);assertEquals(0,executions)
        assertTrue(result.historySegments.all {"unexecuted-raw-call" !in it.protocolItems})
    }

}
