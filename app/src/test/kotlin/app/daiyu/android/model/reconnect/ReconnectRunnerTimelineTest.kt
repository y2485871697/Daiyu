package app.daiyu.android.model.reconnect

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.model.ProviderProtocol
import org.junit.Assert.*
import org.junit.Test

class ReconnectRunnerTimelineTest {
    private class Runtime : AtomicRuntimeStore {
        override fun commitCheckpoint(checkpoint: RunCheckpoint) {}
        override fun reconcileCheckpoint(checkpoint: RunCheckpoint) = error("no process recovery")
    }

    @Test fun ordinaryPauseProducesNoDividerAndOnlyALaterRealFailureStartsReconnect() {
        for (realFailure in listOf(false, true)) {
            val events = java.util.Collections.synchronizedList(mutableListOf<ReconnectChanged>())
            val policy = FrozenReconnectAdapter.freeze(ReconnectSettings(ReconnectDurationPreset.WINDOW_30S),
                ProviderProtocol.RESPONSES).runnerPolicyWithDiagnostics(events::add).copy(delayMillis = 0)
            lateinit var runner: ChatRunner
            var resumes = 0
            val session = object : RequestRetryModelSession {
                override val canResume = true
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    assertTrue(runner.pause()); assertTrue(runner.resume())
                    throw ModelRequestException(ModelFailure.NETWORK, transportInterrupted = true)
                }
                override fun resume(sink: (ModelEvent) -> Unit) {
                    resumes++
                    if (resumes == 1) {
                        assertTrue("ordinary pause must not publish reconnect", events.isEmpty())
                        if (realFailure) throw ModelRequestException(ModelFailure.SERVER, ModelRequestDiagnostic(503))
                    }
                    sink(ModelEvent.TextFinal("answer", "continued"))
                }
                override fun disconnectTransport() {}
                override fun cancel() {}
            }
            runner = ChatRunner(ModelPort { session },
                object : ConversationStore { override fun save(message: Message) = error("atomic only") },
                BatteryTool { error("no tools") }, RunnerOptions(runtimeStore = Runtime(), reconnect = policy)) {}
            val result = runner.run(Message(MessageId("a"), ConversationId("c"), RunId("r"),
                Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING), emptyList())
            assertEquals(RunStatus.COMPLETED, result.status)
            assertEquals(if (realFailure) 2 else 1, resumes)
            if (realFailure) {
                assertEquals(1, events.map { it.reconnectId }.distinct().size)
                assertTrue(events.all { it.reason == ModelFailure.SERVER && it.diagnostic?.httpStatus == 503 })
                assertEquals("succeeded", events.last().status)
            } else assertTrue(events.isEmpty())
        }
    }

    @Test fun actualHttpFailureAfterFastPauseIsNotHiddenAsControlCancellation() {
        val events = java.util.Collections.synchronizedList(mutableListOf<ReconnectChanged>())
        val policy = FrozenReconnectAdapter.freeze(ReconnectSettings(ReconnectDurationPreset.WINDOW_30S),
            ProviderProtocol.RESPONSES).runnerPolicyWithDiagnostics(events::add).copy(delayMillis = 0)
        lateinit var runner: ChatRunner
        val session = object : RequestRetryModelSession {
            override val canResume = true
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                assertTrue(runner.pause()); assertTrue(runner.resume())
                throw ModelRequestException(ModelFailure.SERVER, ModelRequestDiagnostic(503))
            }
            override fun resume(sink: (ModelEvent) -> Unit) { sink(ModelEvent.TextFinal("answer", "continued")) }
            override fun disconnectTransport() {}
            override fun cancel() {}
        }
        runner = ChatRunner(ModelPort { session },
            object : ConversationStore { override fun save(message: Message) = error("atomic only") },
            BatteryTool { error("no tools") }, RunnerOptions(runtimeStore = Runtime(), reconnect = policy)) {}
        val result = runner.run(Message(MessageId("a"), ConversationId("c"), RunId("r"),
            Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING), emptyList())
        assertEquals(RunStatus.COMPLETED, result.status)
        assertEquals(1, events.map { it.reconnectId }.distinct().size)
        assertTrue(events.all { it.reason == ModelFailure.SERVER && it.diagnostic?.httpStatus == 503 })
        assertEquals("succeeded", events.last().status)
    }

    @Test fun actualRunnerPreservesOneErrorAcrossPauseAndRefreshesItBeforeAnUnrecoverableFailure() {
        val events = mutableListOf<ReconnectChanged>()
        val policy = FrozenReconnectAdapter.freeze(ReconnectSettings(ReconnectDurationPreset.WINDOW_30S),
            ProviderProtocol.RESPONSES).runnerPolicyWithDiagnostics(events::add).copy(delayMillis = 0)
        lateinit var runner: ChatRunner
        var opens = 0; var resumes = 0
        val session = object : RequestRetryModelSession {
            override val canResume = true
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                throw ModelRequestException(ModelFailure.SERVER, ModelRequestDiagnostic(503))
            }
            override fun resume(sink: (ModelEvent) -> Unit) {
                when (++resumes) {
                    1 -> {
                        assertTrue(runner.pause()); assertTrue(runner.resume())
                        // The old attempt gate must reject this callback, not report a new NETWORK error.
                        sink(ModelEvent.TextDelta("stale", "must not be admitted"))
                        fail("stale paused delivery accepted")
                    }
                    2 -> throw ModelRequestException(ModelFailure.NETWORK, transportInterrupted = true)
                    3 -> throw ModelRequestException(ModelFailure.SERVER, ModelRequestDiagnostic(502))
                    else -> throw ModelRequestException(ModelFailure.PERMISSION, ModelRequestDiagnostic(403))
                }
            }
            override fun disconnectTransport() {}
            override fun cancel() {}
        }
        runner = ChatRunner(ModelPort { opens++; session },
            object : ConversationStore { override fun save(message: Message) = error("atomic only") },
            BatteryTool { error("no tools") }, RunnerOptions(runtimeStore = Runtime(), reconnect = policy)) {}
        val answer = runner.run(Message(MessageId("a"), ConversationId("c"), RunId("r"),
            Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING), emptyList())
        assertEquals(RunStatus.FAILED, answer.status)
        assertEquals(1, opens); assertEquals(4, resumes)
        assertEquals(1, events.map { it.reconnectId }.distinct().size)
        assertEquals(503, events.first().diagnostic!!.httpStatus)
        assertTrue(events.any { it.status == "stopped" && it.diagnostic?.httpStatus == 503 })
        assertTrue(events.any { it.status == "running" && it.diagnostic?.httpStatus == 502 })
        assertTrue(events.none { it.reason == ModelFailure.NETWORK })
        assertEquals(ModelFailure.PERMISSION, events.last().reason)
        assertEquals(403, events.last().diagnostic!!.httpStatus)
        assertEquals("failed", events.last().status)
        assertTrue(answer.blocks.filterIsInstance<MessageBlock.Text>().none { "must not" in it.text })
    }
}
