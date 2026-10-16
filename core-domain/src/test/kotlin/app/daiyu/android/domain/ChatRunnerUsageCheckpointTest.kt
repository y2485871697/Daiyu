package app.daiyu.android.domain

import app.daiyu.android.domain.runtime.*
import org.junit.Assert.*
import org.junit.Test

class ChatRunnerUsageCheckpointTest {
    private fun initial() = Message(MessageId("a"), ConversationId("c"), RunId("r"),
        Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
    private val messages = object : ConversationStore { override fun save(message: Message) {} }
    private class Runtime : AtomicRuntimeStore {
        val rows = mutableListOf<RunCheckpoint>()
        var beforeCommit: (RunCheckpoint) -> Unit = {}
        override fun commitCheckpoint(checkpoint: RunCheckpoint) {
            beforeCommit(checkpoint)
            assertEquals((rows.lastOrNull()?.revision ?: 0) + 1, checkpoint.revision)
            rows.add(checkpoint)
        }
        override fun reconcileCheckpoint(checkpoint: RunCheckpoint): RunCheckpoint = error("must not acquire recovery owner")
        override fun readCommittedCheckpoint(checkpoint: RunCheckpoint) = rows.lastOrNull()
    }
    @Test fun commitsBeforeCallbackAndReplacesEntireSnapshotForResume() {
        val runtime = Runtime(); var opens = 0; var nexts = 0; var resumes = 0
        val callbacks = mutableListOf<ModelEvent.Usage>(); lateinit var stale: (ModelEvent) -> Unit
        val port = ModelPort {
            opens++
            object : ResumableModelSession {
                override val canResume = true
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    nexts++; stale = sink; sink(ModelEvent.Usage(10, 20, 5))
                    throw ModelRequestException(ModelFailure.NETWORK)
                }
                override fun resume(sink: (ModelEvent) -> Unit) {
                    resumes++; stale(ModelEvent.Usage(999, 999))
                    sink(ModelEvent.Usage(null, 0, 0))
                }
                override fun cancel() {}
            }
        }
        val runner = ChatRunner(port, messages, BatteryTool { error("unused") }, RunnerOptions(
            runtimeStore = runtime, reconnect = ReconnectPolicy(1), onUsage = {
                assertEquals(it, runtime.rows.last().usage.single().snapshot); callbacks.add(it)
            })) {}
        assertEquals(RunStatus.COMPLETED, runner.run(initial(), emptyList()).status)
        assertEquals(listOf(ModelEvent.Usage(10, 20, 5), ModelEvent.Usage(null, 0, 0)), callbacks)
        val record = runtime.rows.last().usage.single()
        assertEquals(RequestOperation.RESUME, record.request.operation)
        assertEquals(runtime.rows.last().request, record.request)
        val first = runtime.rows.first { it.usage.isNotEmpty() }.usage.single()
        assertEquals(first.request.requestId, record.request.requestId)
        assertNotEquals(first.request.attemptId, record.request.attemptId)
        assertEquals(ModelEvent.Usage(10, 20, 5), first.snapshot)
        stale(ModelEvent.Usage(888, 888)); assertEquals(2, callbacks.size)
        assertEquals(1, opens); assertEquals(1, nexts); assertEquals(1, resumes)
    }
    @Test fun toolContinuationKeepsSeparateRequestSnapshotsWithoutInventingAbsentUsage() {
        for (report in listOf(true, false)) {
            val runtime = Runtime(); var rounds = 0
            val port = ModelPort { object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    rounds++
                    if (report) sink(ModelEvent.Usage(rounds.toLong(), null))
                    if (rounds == 1) sink(ModelEvent.ToolReady(ToolInvocation(ToolCallId("call"), "get_battery_status", "{}")))
                }
                override fun cancel() {}
            } }
            val result = ChatRunner(port, messages, BatteryTool { "ok" }, RunnerOptions(runtimeStore = runtime)) {}.run(initial(), emptyList())
            assertEquals(RunStatus.COMPLETED, result.status); assertEquals(2, rounds)
            val records = runtime.rows.last().usage
            assertEquals(if (report) 2 else 0, records.size)
            if (report) {
                assertEquals(2, records.map { it.request.requestId }.distinct().size)
                assertEquals(listOf(1L, 2L), records.map { it.snapshot.inputTokens })
            }
        }
    }
    @Test fun failedUsageCommitPublishesNothingAndDoesNotResumeOrRepost() {
        val runtime = Runtime(); var callbacks = 0; var nexts = 0; var resumes = 0; var opens = 0
        runtime.beforeCommit = { if (it.usage.isNotEmpty()) error("fixture: usage write unavailable") }
        val port = ModelPort {
            opens++
            object : ResumableModelSession {
                override val canResume = true
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    nexts++; sink(ModelEvent.Usage(1, 2))
                }
                override fun resume(sink: (ModelEvent) -> Unit) { resumes++ }
                override fun cancel() {}
            }
        }
        val result = ChatRunner(port, messages, BatteryTool { error("unused") }, RunnerOptions(
            runtimeStore = runtime, reconnect = ReconnectPolicy(1), onUsage = { callbacks++ })) {}.run(initial(), emptyList())
        assertEquals(RunStatus.FAILED, result.status)
        assertTrue(runtime.rows.all { it.usage.isEmpty() }); assertEquals(0, callbacks)
        assertEquals(1, opens); assertEquals(1, nexts); assertEquals(0, resumes)
    }
    @Test fun callbackFailureDoesNotInterruptProtocolOrEraseCommittedUsage() {
        val runtime = Runtime()
        val port = ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                sink(ModelEvent.Usage(3, null)); sink(ModelEvent.ProtocolOutput(listOf("confirmed")))
            }
            override fun cancel() {}
        } }
        val result = ChatRunner(port, messages, BatteryTool { error("unused") }, RunnerOptions(
            runtimeStore = runtime, onUsage = { error("observer failed") })) {}.run(initial(), emptyList())
        assertEquals(RunStatus.COMPLETED, result.status)
        assertEquals(listOf("confirmed"), result.protocolItems)
        assertEquals(ModelEvent.Usage(3, null), runtime.rows.last().usage.single().snapshot)
    }
    @Test fun productionSliceUsageFailureLeavesDurableBoundaryAndNeverRetries() {
        val runtime = Runtime(); var nexts = 0; var callbacks = 0
        runtime.beforeCommit = { if (it.usage.isNotEmpty()) error("usage unavailable") }
        val runner = ChatRunner(ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                nexts++; sink(ModelEvent.Usage(1, 2))
            }
            override fun cancel() {}
        } }, messages, BatteryTool { error("unused") }, RunnerOptions(runtimeStore = runtime,
            onUsage = { callbacks++ })) {}
        try { runner.startSlice(initial(), emptyList()); fail("write failure accepted") }
        catch (_: PersistenceException) {}
        assertEquals(1, nexts); assertEquals(0, callbacks)
        assertEquals(RunStatus.RUNNING, runtime.rows.last().message.status)
        assertTrue(runtime.rows.last().requestUnconfirmed)
        assertTrue(runtime.rows.all { it.usage.isEmpty() })
    }
    @Test fun repeatedUsageInSameAttemptReplacesRatherThanAdds() {
        val runtime = Runtime()
        val runner = ChatRunner(ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                sink(ModelEvent.Usage(10, 20)); sink(ModelEvent.Usage(10, 20))
                sink(ModelEvent.Usage(null, 0))
            }
            override fun cancel() {}
        } }, messages, BatteryTool { error("unused") }, RunnerOptions(runtimeStore = runtime)) {}
        assertEquals(RunStatus.COMPLETED, runner.run(initial(), emptyList()).status)
        assertEquals(ModelEvent.Usage(null, 0), runtime.rows.last().usage.single().snapshot)
    }

}
