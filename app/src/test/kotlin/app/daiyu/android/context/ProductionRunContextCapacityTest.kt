package app.daiyu.android.context

import app.daiyu.android.domain.*
import app.daiyu.android.domain.context.CompressionPolicy
import app.daiyu.android.domain.runtime.*
import org.junit.Assert.*
import org.junit.Test

class ProductionRunContextCapacityTest {
    @Test fun summaryCannotDiscardToolSchemaOverheadWhenCheckingSendBudget() {
        val parent = Message(MessageId("assistant"), ConversationId("c"), RunId("r"), "assistant",
            emptyList(), RunStatus.RUNNING)
        val history = (0..7).map { index -> parent.copy(id = MessageId("u$index"), role = "user",
            status = RunStatus.COMPLETED, blocks = listOf(MessageBlock.Text("body", "source evidence ".repeat(120)))) }
        val policy = CompressionPolicy(true, 4096, reserveTokens = 64, tokenTail = true)
        val binding = SummaryModelBinding("provider", "summary")
        val reply = SummaryCheckpoint.marker + "\n" + SummaryCheckpoint.sections.joinToString("\n") { "## $it\n- kept" }
        val runtime = ProductionRunContext(policy, parent, {
            BoundSummaryModel(binding, SummaryModelTransport { _, _ -> object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    sink(ModelEvent.TextFinal("summary", reply))
                }
                override fun cancel() {}
            } }, 20000, 15000, { SummaryRequestDeadline(10000) }, requireCheckpointFormat = true)
        }, automatic = false)
        val request = RequestCheckpoint("summary", "attempt", "owner", RequestOperation.NEXT)
        val messageTokens = runtime.localTokens(history, emptyList())
        for (billed in listOf<Long?>(null, messageTokens + policy.sendBudgetTokens!!)) {
            val local = if (billed == null) messageTokens + policy.sendBudgetTokens!! else messageTokens
            val error = assertThrows(IllegalArgumentException::class.java) {
                runtime.compact(history, billed, local, request, {}, {}, { false })
            }
            assertEquals("压缩后仍超出发送预算", error.message)
        }
    }
    @Test fun realLongToolTextStillPausesBeforeContinuationDespiteSmallPreviousCloudUsage() {
        val parent = Message(MessageId("assistant"), ConversationId("c"), RunId("r"), "assistant", emptyList(), RunStatus.RUNNING)
        val runtime = ProductionRunContext(CompressionPolicy(true, 272000, reserveTokens = 4096), parent, null, automatic = false)
        val paused = java.util.concurrent.CountDownLatch(1)
        val snapshots = java.util.concurrent.CopyOnWriteArrayList<RunCheckpoint>()
        val store = object : AtomicRuntimeStore {
            override fun commitCheckpoint(checkpoint: RunCheckpoint) {
                snapshots += checkpoint
                if (checkpoint.context?.capacity != null) paused.countDown()
            }
            override fun reconcileCheckpoint(checkpoint: RunCheckpoint) = error("no recovery")
        }
        var turns = 0; var tools = 0
        val port = ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                turns++
                check(turns == 1) { "over-budget continuation was admitted" }
                sink(ModelEvent.InputEstimate(12508)); sink(ModelEvent.Usage(12508, 22))
                sink(ModelEvent.ToolReady(ToolInvocation(ToolCallId("read"), "read", "{}")))
            }
            override fun cancel() {}
        } }
        val output = "x".repeat(1_800_000)
        val runner = ChatRunner(port, object : ConversationStore { override fun save(message: Message) {} },
            BatteryTool { tools++; output }, RunnerOptions(runtimeStore = store, contextRuntime = runtime)) {}
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<Message> { runner.run(parent, emptyList()) }
            assertTrue(paused.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val checkpoint = snapshots.last { it.context?.capacity != null }
            assertEquals(ExecutionPhase.PAUSED, checkpoint.phase)
            assertTrue(checkpoint.context!!.capacity!!.estimatedTokens > runtime.sendBudgetTokens)
            assertEquals(1, turns); assertEquals(1, tools)
            assertEquals(output, (checkpoint.message.blocks.single() as MessageBlock.Tool).result)
            runner.stop(); future.get(10, java.util.concurrent.TimeUnit.SECONDS)
        } finally {
            runner.stop(); executor.shutdownNow(); executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

}
