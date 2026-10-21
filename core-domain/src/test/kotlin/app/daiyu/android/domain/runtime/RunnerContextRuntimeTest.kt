package app.daiyu.android.domain.runtime

import app.daiyu.android.domain.*
import org.junit.Assert.*
import org.junit.Test

class RunnerContextRuntimeTest {
    private val initial = Message(MessageId("assistant"), ConversationId("c"), RunId("r"),
        "assistant", emptyList(), RunStatus.RUNNING)
    private fun user(id: String, text: String) = initial.copy(id = MessageId(id), role = "user",
        status = RunStatus.COMPLETED, blocks = listOf(MessageBlock.Text(id, text)))
    private class Store : AtomicRuntimeStore {
        val snapshots = mutableListOf<RunCheckpoint>()
        override fun commitCheckpoint(checkpoint: RunCheckpoint) { snapshots += checkpoint }
        override fun reconcileCheckpoint(checkpoint: RunCheckpoint) = error("no recovery")
    }
    @Test fun realUsageCompactsWithinToolRunWithSameRequestCalibrationAndNoOutputReplay() {
        val store = Store(); var opens = 0; var tools = 0; var summaries = 0
        val retained = user("summary", "short")
        val runtime = object : RunContextRuntime {
            override val windowTokens = 1000L
            override fun localTokens(history: List<Message>, tools: List<ToolDefinition>) =
                history.sumOf { 10L + it.blocks.size * 10L }
            override fun compact(history: List<Message>, billedTokens: Long?, localTokens: Long,
                request: RequestCheckpoint, observe: (ModelEvent.Usage) -> Unit,
                admit: () -> Unit, cancelled: () -> Boolean): List<Message> {
                summaries++
                assertTrue(history.any { row -> row.blocks.any { it is MessageBlock.Tool && it.result == "done" } })
                val anchor = store.snapshots.last().context!!.calibration!!
                assertEquals(900L, anchor.billedTokens)
                assertEquals(100L, anchor.localTokens)
                assertEquals(900 + localTokens - anchor.localTokens, billedTokens)
                admit()
                assertEquals(request, store.snapshots.last().context!!.summaries.single())
                observe(ModelEvent.Usage(40, 5))
                return listOf(retained) + history.takeLast(1)
            }
        }
        val model = ModelPort { history ->
            opens++
            if (opens == 2) assertEquals(retained, history.first())
            object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    assertTrue(outputs.isEmpty())
                    sink(ModelEvent.InputEstimate(100))
                    if (tools == 0) {
                        sink(ModelEvent.ToolReady(ToolInvocation(ToolCallId("call"), "read", "{}")))
                        sink(ModelEvent.Usage(900, 10))
                    } else sink(ModelEvent.TextFinal("end", "answer"))
                }
                override fun cancel() {}
            }
        }
        val runner = ChatRunner(model, object : ConversationStore { override fun save(message: Message) {} },
            BatteryTool { tools++; "done" }, RunnerOptions(runtimeStore = store, contextRuntime = runtime)) {}
        val result = runner.run(initial, listOf(user("u", "long source")))
        assertEquals(RunStatus.COMPLETED, result.status)
        assertEquals(1, tools); assertEquals(1, summaries); assertEquals(2, opens)
        assertTrue(result.blocks.any { it is MessageBlock.Tool && it.result == "done" })
        val last = store.snapshots.last()
        assertEquals(retained, last.history.first())
        assertEquals(1, last.usage.count { it.source == UsageRequestSource.SUMMARY })
        assertEquals(1, last.context!!.summaries.size)
    }
    @Test fun localEstimateAndSummaryUsageNeverTriggerMainPressure() {
        val store = Store(); var compact = 0
        val runtime = object : RunContextRuntime {
            override val windowTokens = 1000L
            override fun localTokens(history: List<Message>, tools: List<ToolDefinition>) = 100L
            override fun compact(history: List<Message>, billedTokens: Long?, localTokens: Long,
                request: RequestCheckpoint, observe: (ModelEvent.Usage) -> Unit,
                admit: () -> Unit, cancelled: () -> Boolean): List<Message>? { compact++; return null }
        }
        val model = ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                sink(ModelEvent.InputEstimate(9000)); sink(ModelEvent.Usage(null, 1000))
                sink(ModelEvent.TextFinal("t", "answer"))
            }
            override fun cancel() {}
        } }
        val result = ChatRunner(model, object : ConversationStore { override fun save(message: Message) {} },
            BatteryTool { "unused" }, RunnerOptions(runtimeStore = store, contextRuntime = runtime)) {}.run(initial, emptyList())
        assertEquals(RunStatus.COMPLETED, result.status); assertEquals(0, compact)
        assertNull(store.snapshots.last().context!!.calibration)
    }
    @Test fun fullUsageReplacementClearsPressureAndRealUsageWorksWithoutEstimate() {
        for (estimate in listOf(false, true)) for (correction in listOf<Long?>(900L, 100L, null)) {
            val store = Store(); var compact = 0
            val runtime = object : RunContextRuntime {
                override val windowTokens = 1000L
                override fun localTokens(history: List<Message>, tools: List<ToolDefinition>) = 100L
                override fun compact(history: List<Message>, billedTokens: Long?, localTokens: Long,
                    request: RequestCheckpoint, observe: (ModelEvent.Usage) -> Unit,
                    admit: () -> Unit, cancelled: () -> Boolean): List<Message>? { compact++; return null }
            }
            val model = ModelPort { object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    if (estimate) sink(ModelEvent.InputEstimate(100))
                    sink(ModelEvent.Usage(900, 10)); sink(ModelEvent.Usage(correction, 10))
                    sink(ModelEvent.TextFinal("t", "answer"))
                }
                override fun cancel() {}
            } }
            val result = ChatRunner(model, object : ConversationStore { override fun save(message: Message) {} },
                BatteryTool { "unused" }, RunnerOptions(runtimeStore = store, contextRuntime = runtime)) {}.run(initial, emptyList())
            assertEquals(RunStatus.COMPLETED, result.status)
            assertEquals(if (correction == 900L) 1 else 0, compact)
            assertEquals(correction, store.snapshots.last().usage.single().snapshot.inputTokens)
            if (correction == null || !estimate) assertNull(store.snapshots.last().context!!.calibration)
        }
    }
}
