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
    private fun run(probe: Probe = Probe(), invocations: List<ToolInvocation> = calls,
        execute: (Probe, List<ToolInvocation>, () -> Boolean) -> List<ToolDispatchResult>): Pair<Message, Probe> {
        val port = ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                if (probe.rounds++ == 0) invocations.forEach { sink(ModelEvent.ToolReady(it)) }
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
    private fun guiRejection(name: String, code: String, arguments: String = "{}"):
        Pair<Message, Probe> = run(invocations = listOf(ToolInvocation(ToolCallId("gui"), name, arguments))) { _, batch, _ ->
            listOf(ToolDispatchResult(batch.single().id, DispatchOutcome.NotExecuted(code)))
        }

    private fun assertPersistedRejection(result: Message, probe: Probe): String {
        assertEquals(RunStatus.COMPLETED, result.status)
        assertEquals(1, probe.executions); assertEquals(2, probe.rounds)
        val output = probe.outputs.single().output
        val record = probe.checkpoints.last().tools.single()
        assertEquals(ToolDisposition.NOT_EXECUTED, record.disposition)
        assertEquals(output, record.result)
        assertEquals(output, result.blocks.filterIsInstance<MessageBlock.Tool>().single().result)
        return output
    }

    @Test fun windowRejectionReachesTheModelWithAnExplicitNonLockExplanation() {
        for (operation in listOf("observe", "screenshot")) {
            val (result, probe) = guiRejection("screen", "APPLICATION_WINDOW_NOT_ACTIVE_NOT_EXECUTED",
                "{\"operation\":\"$operation\"}")
            val output = assertPersistedRejection(result, probe)
            assertTrue(output.startsWith("{\"ok\":false,\"executed\":false,\"error\":\"APPLICATION_WINDOW_NOT_ACTIVE_NOT_EXECUTED\","))
            assertTrue(output.contains("\"reason\":\"无障碍尚未确认活动窗口"))
            assertTrue(output.contains("不等于屏幕关闭或锁屏"))
            assertTrue(output.contains("不要因此要求用户点亮或解锁屏幕"))
        }
    }

    @Test fun guiRejectionReasonsDistinguishServiceLockAndSelfTargetFailures() {
        val cases = listOf(
            "screen" to ("NO_ACTIVE_EXTERNAL_WINDOW" to "不等于屏幕关闭或锁屏"),
            "screen" to ("SERVICE_DISCONNECTED_NOT_EXECUTED" to "无障碍服务尚未连接"),
            "screen" to ("SERVICE_PERMISSION_REVOKED_NOT_EXECUTED" to "撤销本应用的无障碍服务授权"),
            "screen" to ("SCREEN_LOCKED_NOT_EXECUTED" to "请用户点亮并解锁屏幕后重新发起操作"),
            "screen_action" to ("SELF_APP_TARGET_LOST" to "不要推测锁屏"),
            "launch_app" to ("TARGET_PACKAGE_MISMATCH_NOT_EXECUTED" to "当前活动窗口与指定应用不一致"),
            "screen" to ("APPLICATION_WINDOW_METADATA_UNAVAILABLE_NOT_EXECUTED" to "窗口元数据读取失败"))
        for ((name, value) in cases) {
            val (code, reason) = value
            val (result, probe) = guiRejection(name, code)
            val output = assertPersistedRejection(result, probe)
            assertTrue(output.contains("\"error\":\"$code\""))
            assertTrue(output.contains("\"reason\":")); assertTrue(output.contains("\"next_action\":"))
            assertTrue(output.contains(reason))
            assertEquals(code == "SCREEN_LOCKED_NOT_EXECUTED", output.contains("请用户点亮并解锁屏幕后重新发起操作"))
        }
    }

    @Test fun unknownCodesAndNonGuiToolsKeepTheirExactOriginalRejectionPayload() {
        for ((name, code) in listOf(
            "read" to "APPLICATION_WINDOW_NOT_ACTIVE_NOT_EXECUTED",
            "screen" to "invalid\"arguments", "screen_action" to "unrecognized", "launch_app" to "other")) {
            val (result, probe) = guiRejection(name, code)
            val output = assertPersistedRejection(result, probe)
            assertEquals("{\"ok\":false,\"executed\":false,\"error\":" +
                app.daiyu.android.domain.tools.jsonQuote(code) + "}", output)
        }
    }

    @Test fun knownGuiRejectionCodeCannotTurnAnUnknownOutcomeIntoAReceipt() {
        val invocation = ToolInvocation(ToolCallId("gui-unknown"), "screen", "{\"operation\":\"screenshot\"}")
        val (result, probe) = run(invocations = listOf(invocation)) { _, batch, _ ->
            listOf(ToolDispatchResult(batch.single().id, DispatchOutcome.Unknown("APPLICATION_WINDOW_NOT_ACTIVE_NOT_EXECUTED")))
        }
        assertEquals(RunStatus.FAILED, result.status)
        assertEquals(1, probe.rounds); assertEquals(1, probe.executions)
        assertTrue(probe.outputs.isEmpty())
        assertEquals(ToolDisposition.UNKNOWN, probe.checkpoints.last().tools.single().disposition)
        assertNull(probe.checkpoints.last().tools.single().result)
        assertEquals(ModelHistory.UNKNOWN_TOOL_RESULT, result.blocks.filterIsInstance<MessageBlock.Tool>().single().result)
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
        assertEquals(listOf("saved", ModelHistory.UNKNOWN_TOOL_RESULT, records[2].result),
            result.blocks.filterIsInstance<MessageBlock.Tool>().map { it.result })
        assertEquals(result.blocks, result.historySegments.flatMap { it.blocks })
        assertEquals(ModelHistory.UNKNOWN_TOOL_RESULT,
            probe.checkpoints.last().message.blocks.filterIsInstance<MessageBlock.Tool>()[1].result)
        assertTrue(probe.outputs.isEmpty()) // Never feed UNKNOWN back into this run.
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
            assertTrue(result.blocks.filterIsInstance<MessageBlock.Tool>().all { it.result == ModelHistory.UNKNOWN_TOOL_RESULT })
            assertEquals(result.blocks, result.historySegments.flatMap { it.blocks })
        }
    }
    @Test fun twoBatchRoutesCannotBeConfiguredTogether() {
        try {
            RunnerOptions(batchExecutor = BatchToolExecutor { _, _ -> emptyList() },
                receiptBatchExecutor = ReceiptBatchToolExecutor { _, _, _ -> emptyList() })
            fail("ambiguous execution routes accepted")
        } catch (_: IllegalArgumentException) {}
    }
    @Test fun diagnosticObserverIsNonAuthoritativeAndAllAcceptedModelEventsClose() {
        val observed = mutableListOf<RunDiagnosticStage>()
        var ended = 0
        val model = ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                sink(ModelEvent.TextDelta("text", "done"))
                sink(ModelEvent.TextFinal("text", "done"))
            }
            override fun cancel() {}
        } }
        for (throwOnBegin in listOf(false, true)) {
            val runner = ChatRunner(model, object : ConversationStore {
                override fun save(message: Message) {}
            }, BatteryTool { error("unused") }, RunnerOptions(diagnostics = RunDiagnostics { stage ->
                if (throwOnBegin) throw IllegalStateException("observer-only")
                observed += stage
                RunDiagnosticSpan { ended++; throw IllegalStateException("observer-only") }
            })) {}
            assertEquals(RunStatus.COMPLETED, runner.run(initial, emptyList()).status)
        }
        assertTrue(observed.contains(RunDiagnosticStage.MODEL_REQUEST))
        assertEquals(2, observed.count { it == RunDiagnosticStage.MODEL_EVENT })
        assertEquals(observed.size, ended)
    }

}
