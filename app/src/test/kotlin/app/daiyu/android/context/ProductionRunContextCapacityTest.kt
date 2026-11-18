package app.daiyu.android.context

import app.daiyu.android.domain.*
import app.daiyu.android.domain.context.CompressionPolicy
import app.daiyu.android.domain.runtime.*
import org.junit.Assert.*
import org.junit.Test

class ProductionRunContextCapacityTest {
    @Test fun compactionAcceptsARealReductionWithoutAnExtraLocalSendGate() {
        val parent = Message(MessageId("assistant"), ConversationId("c"), RunId("r"), "assistant", emptyList(), RunStatus.RUNNING)
        val history = (0..7).map { index -> parent.copy(id = MessageId("u$index"), role = "user",
            status = RunStatus.COMPLETED, blocks = listOf(MessageBlock.Text("body", "source evidence ".repeat(120)))) }
        val policy = CompressionPolicy(true, 4096, reserveTokens = 64, tokenTail = true)
        val runtime = ProductionRunContext(policy, parent, {
            BoundSummaryModel(SummaryModelBinding("provider", "summary"), SummaryModelTransport { rows, _ ->
                assertTrue(rows.last().blocks.filterIsInstance<MessageBlock.Text>().single().text.contains("compaction engine"))
                object : ModelSession {
                    override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) { sink(ModelEvent.TextFinal("summary", "short")) }
                    override fun cancel() {}
                }
            }, 20000, 15000, { SummaryRequestDeadline(10000) })
        }, automatic = false)
        val request = RequestCheckpoint("summary", "attempt", "owner", RequestOperation.NEXT)
        val result = checkNotNull(runtime.compact(history, 1000000, 1000000, request, {}, {}, { false }))
        assertTrue(runtime.localTokens(result, emptyList()) < runtime.localTokens(history, emptyList()))
        assertTrue(result.first().blocks.filterIsInstance<MessageBlock.Text>().single().text.contains("<compacted-summary>"))
    }

    @Test fun realLongToolTextContinuesWithoutCapacityPauseOrToolReplay() {
        val parent = Message(MessageId("assistant"), ConversationId("c"), RunId("r"), "assistant", emptyList(), RunStatus.RUNNING)
        val runtime = ProductionRunContext(CompressionPolicy(true, 272000, reserveTokens = 4096, tokenTail = true), parent, null, automatic = false)
        val snapshots = mutableListOf<RunCheckpoint>()
        val store = object : AtomicRuntimeStore {
            override fun commitCheckpoint(checkpoint: RunCheckpoint) { snapshots += checkpoint }
            override fun reconcileCheckpoint(checkpoint: RunCheckpoint) = error("no recovery")
        }
        var turns = 0; var tools = 0
        val output = "x".repeat(1_800_000)
        val port = ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                if (++turns == 1) {
                    sink(ModelEvent.InputEstimate(12508)); sink(ModelEvent.Usage(12508, 22))
                    sink(ModelEvent.ToolReady(ToolInvocation(ToolCallId("read"), "read", "{}")))
                } else {
                    assertEquals(output, outputs.single().output)
                    sink(ModelEvent.TextFinal("answer", "continued"))
                }
            }
            override fun cancel() {}
        } }
        val result = ChatRunner(port, object : ConversationStore { override fun save(message: Message) {} },
            BatteryTool { tools++; output }, RunnerOptions(runtimeStore = store, contextRuntime = runtime)) {}.run(parent, emptyList())
        assertEquals(RunStatus.COMPLETED, result.status)
        assertEquals(2, turns); assertEquals(1, tools)
        assertTrue(snapshots.all { it.context?.capacity == null && it.phase != ExecutionPhase.PAUSED })
        assertEquals(output, result.blocks.filterIsInstance<MessageBlock.Tool>().single().result)
    }

}
