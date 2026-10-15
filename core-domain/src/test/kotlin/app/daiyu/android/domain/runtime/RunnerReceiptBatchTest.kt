package app.daiyu.android.domain.runtime

import app.daiyu.android.domain.*
import app.daiyu.android.domain.tools.DispatchOutcome
import app.daiyu.android.domain.tools.ToolDispatchResult
import org.junit.Assert.*
import org.junit.Test

class RunnerReceiptBatchTest {
    private val initial = Message(MessageId("a"), ConversationId("c"), RunId("r"),
        "assistant", emptyList(), RunStatus.RUNNING)
    private val calls = (1..3).map { ToolInvocation(ToolCallId("call-$it"), "read", "{}") }
    private class Probe {
        val checkpoints = mutableListOf<RunCheckpoint>()
        var rounds = 0
        var executions = 0
        var outputs = emptyList<ToolOutput>()
        lateinit var runner: ChatRunner
    }
    private fun run(probe: Probe = Probe(), execute: (Probe, List<ToolInvocation>, () -> Boolean) -> List<ToolDispatchResult>): Pair<Message, Probe> {
        val port = ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                if (probe.rounds++ == 0) calls.forEach { sink(ModelEvent.ToolReady(it)) }
                else { probe.outputs = outputs; sink(ModelEvent.TextFinal("answer", "done")) }
            }
            override fun cancel() {}
        } }
        probe.runner = ChatRunner(port, object : ConversationStore {
            override fun save(message: Message) {}
        }, BatteryTool { error("legacy route must not execute") }, RunnerOptions(
            runtimeStore = object : RuntimeStore {
                override fun save(checkpoint: RunCheckpoint) { probe.checkpoints += checkpoint }
            }, receiptBatchExecutor = ReceiptBatchToolExecutor { _, invocations, cancelled ->
                probe.executions++
                execute(probe, invocations, cancelled)
            })) {}
        return probe.runner.run(initial, emptyList()) to probe
    }
    @Test fun confirmedErrorAndProvenRejectionRemainDistinctFromSuccess() {
        val (result, probe) = run { _, batch, _ -> listOf(
            ToolDispatchResult(batch[0].id, DispatchOutcome.Confirmed("ok", false, replayed = true)),
            ToolDispatchResult(batch[1].id, DispatchOutcome.Confirmed("denied", true)),
            ToolDispatchResult(batch[2].id, DispatchOutcome.NotExecuted("invalid\"arguments"))) }
        assertEquals(RunStatus.COMPLETED, result.status)
        assertEquals(2, probe.rounds)
        assertEquals(1, probe.executions)
        assertEquals(listOf(ToolDisposition.SUCCEEDED, ToolDisposition.FAILED, ToolDisposition.NOT_EXECUTED),
            probe.checkpoints.last().tools.map { it.disposition })
        assertEquals(listOf("ok", "denied", "{\"ok\":false,\"executed\":false,\"error\":\"invalid\\\"arguments\"}"),
            probe.outputs.map { it.output })
        assertEquals(probe.outputs.map { it.output }, result.blocks.filterIsInstance<MessageBlock.Tool>().map { it.result })
    }
    @Test fun mixedUnknownStopsWithoutErasingConfirmedOrNotExecutedReceipts() {
        val (result, probe) = run { _, batch, _ -> listOf(
            ToolDispatchResult(batch[0].id, DispatchOutcome.Confirmed("saved", false)),
            ToolDispatchResult(batch[1].id, DispatchOutcome.Unknown("lost_receipt")),
            ToolDispatchResult(batch[2].id, DispatchOutcome.NotExecuted("batch_stopped_before_execution"))) }
        assertEquals(RunStatus.FAILED, result.status)
        assertEquals(1, probe.rounds)
        val records = probe.checkpoints.last().tools
        assertEquals(listOf(ToolDisposition.SUCCEEDED, ToolDisposition.UNKNOWN, ToolDisposition.NOT_EXECUTED), records.map { it.disposition })
        assertEquals("saved", records[0].result)
        assertNull(records[1].result)
        assertTrue(records[2].result!!.contains("executed\":false"))
        assertEquals("saved", result.blocks.filterIsInstance<MessageBlock.Tool>().first().result)
        assertTrue(result.error!!.contains("禁止重放"))
    }
    @Test fun stopSignalReachesDispatcherAndKeepsKnownReceipts() {
        val (result, probe) = run { p, batch, cancelled ->
            assertFalse(cancelled())
            p.runner.stop()
            assertTrue(cancelled())
            batch.map { ToolDispatchResult(it.id, DispatchOutcome.NotExecuted("cancelled_before_execution")) }
        }
        assertEquals(RunStatus.STOPPED, result.status)
        assertEquals(1, probe.rounds)
        assertTrue(probe.checkpoints.last().tools.all { it.disposition == ToolDisposition.NOT_EXECUTED })
    }
    @Test fun thrownOrMalformedReceiptCannotFallBackToLegacyOrRepost() {
        for (mode in 0..1) {
            val (result, probe) = run { _, batch, _ ->
                if (mode == 0) error("uncertain boundary")
                batch.reversed().map { ToolDispatchResult(it.id, DispatchOutcome.Confirmed("wrong order", false)) }
            }
            assertEquals(RunStatus.FAILED, result.status)
            assertEquals(1, probe.executions)
            assertEquals(1, probe.rounds)
            assertTrue(probe.checkpoints.last().tools.all { it.disposition == ToolDisposition.UNKNOWN && it.result == null })
        }
    }
    @Test fun twoBatchRoutesCannotBeConfiguredTogether() {
        try {
            RunnerOptions(batchExecutor = BatchToolExecutor { _, _ -> emptyList() },
                receiptBatchExecutor = ReceiptBatchToolExecutor { _, _, _ -> emptyList() })
            fail("ambiguous execution routes accepted")
        } catch (_: IllegalArgumentException) {}
    }
}
