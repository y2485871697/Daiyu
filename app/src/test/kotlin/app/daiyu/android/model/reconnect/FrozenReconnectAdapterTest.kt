package app.daiyu.android.model.reconnect

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.ReconnectPolicy
import app.daiyu.android.domain.runtime.RunnerOptions
import app.daiyu.android.model.ProviderProtocol
import org.junit.Assert.*
import org.junit.Test

class FrozenReconnectAdapterTest {
    @Test fun defaultRunnerAndEveryCurrentProtocolRemainZeroAttemptsForAllGlobalDurations() {
        assertEquals(0, ReconnectPolicy().maxAttempts)
        assertEquals(0, RunnerOptions().reconnect.maxAttempts)
        for (protocol in ProviderProtocol.entries) {
            for (duration in ReconnectDurationPreset.entries) {
                val frozen = FrozenReconnectAdapter.freeze(ReconnectSettings(duration), protocol)
                assertEquals(ReconnectSettings(duration), frozen.settings)
                assertEquals(ReconnectPolicy(), frozen.runnerPolicy())
                assertFalse(frozen.capability.supported)
                if (duration != ReconnectDurationPreset.NONE) {
                    try { frozen.requireSupported(); fail("unsupported enabled setting accepted") }
                    catch (failure: ReconnectUnavailableException) { assertEquals(frozen.capability, failure.capability) }
                } else frozen.requireSupported()
            }
        }
    }

    @Test fun onlyRequestedReconnectOnNetworkOrTimeoutAddsExplicitLimitation() {
        for (protocol in ProviderProtocol.entries) {
            val enabled = FrozenReconnectAdapter.freeze(ReconnectSettings(ReconnectDurationPreset.WINDOW_30S), protocol)
            val disabled = FrozenReconnectAdapter.freeze(ReconnectSettings(ReconnectDurationPreset.NONE), protocol)
            for (failure in ModelFailure.entries) {
                assertNull(disabled.unavailableMessage(failure))
                if (failure in setOf(ModelFailure.NETWORK, ModelFailure.TIMEOUT)) {
                    assertEquals(enabled.capability.userMessage, enabled.unavailableMessage(failure))
                } else assertNull(enabled.unavailableMessage(failure))
            }
        }
    }

    @Test fun diagnosticWrapperPreservesEachOpenOverloadOutputsEventsAndOriginalFailure() {
        val frozen = FrozenReconnectAdapter.freeze(ReconnectSettings(ReconnectDurationPreset.WINDOW_5M),
            ProviderProtocol.MESSAGES)
        val failure = ModelRequestException(ModelFailure.NETWORK)
        val notices = mutableListOf<String>()
        val opened = mutableListOf<String>()
        var nexts = 0; var cancellations = 0
        val history = emptyList<Message>()
        val definitions = listOf(ToolDefinition("fixture", "fixture", "{}"))
        val tools = setOf(ReadOnlyTool.BATTERY)
        val images = object : ModelImagePlan {}
        val outputs = listOf(ToolOutput(ToolCallId("existing"), "retained-result"))
        val event = ModelEvent.TextDelta("t", "accepted")
        val original = object : ModelSession {
            override fun next(actual: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                nexts++; assertSame(outputs, actual); sink(event); throw failure
            }
            override fun cancel() { cancellations++ }
        }
        val port = object : ModelPort {
            override fun open(actual: List<Message>): ModelSession {
                assertSame(history, actual); opened.add("history"); return original
            }
            override fun open(actual: List<Message>, actualTools: Set<ReadOnlyTool>): ModelSession {
                assertSame(history, actual); assertSame(tools, actualTools); opened.add("tools"); return original
            }
            override fun open(actual: List<Message>, actualDefinitions: List<ToolDefinition>): ModelSession {
                assertSame(history, actual); assertSame(definitions, actualDefinitions); opened.add("definitions"); return original
            }
            override fun open(actual: List<Message>, actualDefinitions: List<ToolDefinition>, actualImages: ModelImagePlan): ModelSession {
                assertSame(history, actual); assertSame(definitions, actualDefinitions); assertSame(images, actualImages)
                opened.add("images"); return original
            }
        }
        val observed = frozen.observeFailures(port) { notices.add(it); error("observer must not replace failure") }
        val sessions = listOf(observed.open(history), observed.open(history, tools),
            observed.open(history, definitions), observed.open(history, definitions, images))
        sessions.forEach { session ->
            assertFalse(session is ResumableModelSession)
            val events = mutableListOf<ModelEvent>()
            assertSame(failure, assertThrows(ModelRequestException::class.java) { session.next(outputs, events::add) })
            assertEquals(listOf(event), events); session.cancel()
        }
        assertEquals(listOf("history", "tools", "definitions", "images"), opened)
        assertEquals(4, nexts); assertEquals(4, cancellations)
        assertEquals(List(4) { ReconnectCapability.MESSAGES_UNSUPPORTED.userMessage }, notices)
        assertEquals(300_000L, frozen.windowMillis)
    }

    @Test fun enabledPreferenceNeverReopensRepostsResumesOrExecutesLateTools() {
        val frozen = FrozenReconnectAdapter.freeze(ReconnectSettings(ReconnectDurationPreset.CONTINUOUS),
            ProviderProtocol.RESPONSES)
        var opens = 0
        var nexts = 0
        var resumes = 0
        var tools = 0
        var late: ((ModelEvent) -> Unit)? = null
        val writes = mutableListOf<Message>()
        val usages = mutableListOf<ModelEvent.Usage>()
        val model = ModelPort {
            opens++
            object : ResumableModelSession {
                // Even a fake session claiming capability cannot override frozen production policy.
                override val canResume = true
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    nexts++; late = sink
                    sink(ModelEvent.TextDelta("t", "accepted"))
                    throw ModelRequestException(ModelFailure.NETWORK)
                }
                override fun resume(sink: (ModelEvent) -> Unit) { resumes++ }
                override fun cancel() = Unit
            }
        }
        val result = ChatRunner(model, object : ConversationStore {
            override fun save(message: Message) { writes.add(message) }
        }, BatteryTool { tools++; "must not execute" },
            RunnerOptions(reconnect = frozen.runnerPolicy(), onUsage = usages::add)) {}.run(
                Message(MessageId("a"), ConversationId("c"), RunId("r"), "assistant", emptyList(), RunStatus.RUNNING),
                emptyList())
        assertEquals(RunStatus.FAILED, result.status)
        assertEquals(1, opens); assertEquals(1, nexts); assertEquals(0, resumes)
        val before = writes.toList()
        late!!(ModelEvent.TextDelta("t", "late"))
        late!!(ModelEvent.ToolReady(ToolInvocation(ToolCallId("late"), "get_battery_status", "{}")))
        late!!(ModelEvent.Usage(99, 99))
        assertEquals(before, writes); assertTrue(usages.isEmpty()); assertEquals(0, tools)
        assertEquals(listOf(result), writes.filter { it.status != RunStatus.RUNNING })
    }
}
